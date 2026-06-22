/**
 * AFK Live — Stream Worker
 *
 * Polls the stream_job_queue table for PENDING jobs, downloads the source video
 * from DigitalOcean Spaces (S3-compatible), and streams it via FFmpeg to the
 * configured RTMP destination(s).
 *
 * Architecture:
 *  - Uses SELECT ... FOR UPDATE SKIP LOCKED so multiple workers can run safely
 *    without picking up the same job.
 *  - Sends a heartbeat to the DB every 15 seconds with live FFmpeg metrics
 *    (fps, bitrate, dropped frames). The Spring Boot watchdog checks these.
 *  - Respects STOP_REQUESTED status: kills FFmpeg gracefully when the API sets it.
 *  - On FFmpeg exit: marks job COMPLETED or FAILED accordingly.
 *
 * Environment variables (from dev.env):
 *   POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_HOST, POSTGRES_DB, POSTGRES_PORT
 *   DO_SPACES_ENDPOINT, DO_SPACES_REGION, DO_SPACES_BUCKET, DO_SPACES_KEY, DO_SPACES_SECRET
 */

require('dotenv').config();

const { Pool } = require('pg');
const { spawn } = require('child_process');
const { S3Client, GetObjectCommand } = require('@aws-sdk/client-s3');
const { getSignedUrl } = require('@aws-sdk/s3-request-presigner');
const os = require('os');
const fs = require('fs');
const path = require('path');
const { pipeline } = require('stream/promises');

// ── Identity ──────────────────────────────────────────────────────────────────
const WORKER_ID = `worker-${os.hostname()}-${process.pid}`;

// ── DB Config ─────────────────────────────────────────────────────────────────
const dbConfig = {
    user:     process.env.POSTGRES_USER     || 'postgres',
    host:     process.env.POSTGRES_HOST     || 'localhost',
    database: process.env.POSTGRES_DB       || 'afkdb',
    password: process.env.POSTGRES_PASSWORD || 'password',
    port:     parseInt(process.env.POSTGRES_PORT || '5433'),
    max:      20, // Max connections in the pool
    idleTimeoutMillis: 30000,
};

const pool = new Pool(dbConfig);

// ── S3 / Spaces Config ────────────────────────────────────────────────────────
const s3 = new S3Client({
    endpoint:        process.env.DO_SPACES_ENDPOINT || 'https://nyc3.digitaloceanspaces.com',
    region:          process.env.DO_SPACES_REGION   || 'nyc3',
    credentials: {
        accessKeyId:     process.env.DO_SPACES_KEY    || '',
        secretAccessKey: process.env.DO_SPACES_SECRET || '',
    },
    forcePathStyle: false, // DO Spaces uses virtual-hosted style
});

const SPACES_BUCKET   = process.env.DO_SPACES_BUCKET || 'afklive';
const POLL_INTERVAL   = 5000;  // ms between queue polls when idle
const HEARTBEAT_EVERY = 15000; // ms between heartbeat DB writes
const TMP_DIR         = os.tmpdir();
const MAX_CONCURRENT_JOBS = parseInt(process.env.MAX_CONCURRENT_JOBS || '2', 10);
let activeJobs        = 0;
let isShuttingDown    = false;
const activeProcesses = new Set();

// ── FFmpeg stderr metric regexes ──────────────────────────────────────────────
// Example FFmpeg output line:
// frame= 1234 fps= 30 q=-1.0 size=   12345kB time=00:00:41.13 bitrate=2460.2kbits/s speed=1.00x
const FFMPEG_PROGRESS_RE = /frame=\s*(\d+)\s+fps=\s*([\d.]+)\s+.*?size=\s*([\d.]+)kB\s+.*?bitrate=\s*([\d.]+)kbits\/s/;
// Dropped frames show up as: [rtmp @ 0x...] Dropped: 12
const DROPPED_FRAMES_RE  = /Dropped:\s*(\d+)/i;

// ── Queue Poller ──────────────────────────────────────────────────────────────

async function pollQueue() {
    if (isShuttingDown) return;
    if (activeJobs >= MAX_CONCURRENT_JOBS) {
        setTimeout(pollQueue, POLL_INTERVAL);
        return;
    }

    let client;
    try {
        client = await pool.connect();
        await client.query('BEGIN');

        const res = await client.query(`
            SELECT q.id          AS queue_id,
                   q.stream_spec,
                   j.id          AS stream_job_id
            FROM stream_job_queue q
            JOIN stream_jobs j ON j.id = q.stream_job_id
            WHERE q.status = 'PENDING'
              AND j.status IN ('PREPARING', 'RECOVERY_PENDING')
            ORDER BY q.priority DESC, q.created_at ASC
            FOR UPDATE OF q SKIP LOCKED
            LIMIT 1
        `);

        if (res.rows.length === 0) {
            await client.query('ROLLBACK');
            return; // Nothing to do
        }

        const { queue_id, stream_spec, stream_job_id } = res.rows[0];
        console.log(`[${WORKER_ID}] Picked up job stream_job_id=${stream_job_id} queue_id=${queue_id}`);

        // Mark queue row as ACQUIRED and job as LIVE — atomically inside the transaction
        await client.query(
            `UPDATE stream_job_queue
             SET status = 'ACQUIRED', acquired_at = NOW(), acquired_by = $1
             WHERE id = $2`,
            [WORKER_ID, queue_id]
        );
        await client.query(
            `UPDATE stream_jobs
             SET status = 'LIVE', worker_id = $1, start_time = NOW()
             WHERE id = $2`,
            [WORKER_ID, stream_job_id]
        );

        await client.query('COMMIT');

        // Run the stream job asynchronously — do not await here so the poller can
        // immediately go back to polling (this worker is now busy with this job).
        activeJobs++;
        processJob(stream_job_id, queue_id, stream_spec).catch(err => {
            console.error(`[Job ${stream_job_id}] Unhandled error in processJob:`, err);
        }).finally(() => {
            activeJobs--;
        });

    } catch (err) {
        console.error(`[${WORKER_ID}] Poll error:`, err.message);
        try { await client.query('ROLLBACK'); } catch (_) {}
    } finally {
        if (client) client.release();
        // Schedule next poll. When busy, this runs but finds nothing (job already ACQUIRED).
        setTimeout(pollQueue, POLL_INTERVAL);
    }
}

// ── Job Processor ─────────────────────────────────────────────────────────────

async function processJob(streamJobId, queueId, specString) {
    let spec;
    try {
        spec = typeof specString === 'string' ? JSON.parse(specString) : specString;
    } catch (err) {
        console.error(`[Job ${streamJobId}] Failed to parse stream_spec JSON:`, err.message);
        let failClient;
        try {
            failClient = await pool.connect();
            await failClient.query(
                `UPDATE stream_jobs SET status = 'FAILED', failure_reason = $1, end_time = NOW() WHERE id = $2`,
                ['Invalid stream config JSON', streamJobId]
            );
            await failClient.query(
                `UPDATE stream_job_queue SET status = 'FAILED' WHERE id = $1`,
                [queueId]
            );
        } finally {
            if (failClient) failClient.release();
        }
        return;
    }

    console.log(`[Job ${streamJobId}] Starting. Spec:`, JSON.stringify(spec, null, 2));


    let heartbeatTimer = null;
    let ffmpegProcess  = null;
    let jobClient;

    try {
        jobClient = await pool.connect();

        let presignedUrl = '';
        let isConcat = false;
        
        let keys = [];
        if (spec.source_s3_key) {
            keys = spec.source_s3_key.split(',').map(k => k.trim()).filter(k => k.length > 0);
        }
        
        if (keys.length === 0) {
            throw new Error('No source video provided.');
        }
        
        let isLocal = !process.env.DO_SPACES_KEY;
        
        if (keys.length === 1) {
            if (isLocal) {
                const localPath = path.resolve(__dirname, '../afklive-web/data/storage', keys[0]);
                console.log(`[Job ${streamJobId}] Using local file path: ${localPath}`);
                presignedUrl = localPath;
            } else {
                console.log(`[Job ${streamJobId}] Generating presigned URL for key: ${keys[0]}`);
                presignedUrl = await getSignedUrl(
                    s3,
                    new GetObjectCommand({ Bucket: SPACES_BUCKET, Key: keys[0] }),
                    { expiresIn: 7 * 24 * 3600 } // 7 days
                );
            }
        } else {
            console.log(`[Job ${streamJobId}] Generating URLs for ${keys.length} keys (Playlist)`);
            isConcat = true;
            let listContent = '';
            for (const key of keys) {
                let url;
                if (isLocal) {
                    url = path.resolve(__dirname, '../afklive-web/data/storage', key);
                } else {
                    url = await getSignedUrl(
                        s3,
                        new GetObjectCommand({ Bucket: SPACES_BUCKET, Key: key }),
                        { expiresIn: 7 * 24 * 3600 }
                    );
                }
                // escape single quotes for ffmpeg concat file
                listContent += `file '${url.replace(/'/g, "'\\''")}'\n`;
            }
            
            const listPath = path.join(TMP_DIR, `playlist_${streamJobId}.txt`);
            fs.writeFileSync(listPath, listContent);
            presignedUrl = listPath;
        }

        // ── Step 2: Build FFmpeg command ──────────────────────────────────────
        if (!spec.destinations || spec.destinations.length === 0) {
            throw new Error('No destinations provided for stream.');
        }
        for (let i = 0; i < spec.destinations.length; i++) {
            let rtmpUrl = spec.destinations[i];
            if (!rtmpUrl) continue;
            if (!rtmpUrl.startsWith('rtmp://') && !rtmpUrl.startsWith('rtmps://')) {
                // If it's just a stream key, default to YouTube's RTMP server
                // The user requested to remove the RTMP server URL field from the UI, implying YouTube as default.
                rtmpUrl = rtmpUrl.startsWith('/') ? `rtmp://a.rtmp.youtube.com/live2${rtmpUrl}` : `rtmp://a.rtmp.youtube.com/live2/${rtmpUrl}`;
                spec.destinations[i] = rtmpUrl;
            }
        }
        const ffmpegArgs = buildFfmpegArgs(presignedUrl, spec.destinations, spec, isConcat);
        console.log(`[Job ${streamJobId}] Executing: ffmpeg ${ffmpegArgs.join(' ')}`);

        // ── Step 3: Spawn FFmpeg ──────────────────────────────────────────────
        ffmpegProcess = spawn('ffmpeg', ffmpegArgs, { stdio: ['ignore', 'ignore', 'pipe'] });
        activeProcesses.add(ffmpegProcess);

        let currentMetrics = { fps: 0, bitrateKbps: 0, droppedFrames: 0, bytesStreamed: 0 };

        // Parse FFmpeg progress from stderr (FFmpeg writes everything to stderr)
        ffmpegProcess.stderr.on('data', chunk => {
            const line = chunk.toString();

            const progressMatch = line.match(FFMPEG_PROGRESS_RE);
            if (progressMatch) {
                currentMetrics.fps          = parseFloat(progressMatch[2]);
                currentMetrics.bitrateKbps  = parseFloat(progressMatch[4]);
                currentMetrics.bytesStreamed = parseFloat(progressMatch[3]) * 1024; // kB → bytes
            }

            const droppedMatch = line.match(DROPPED_FRAMES_RE);
            if (droppedMatch) {
                currentMetrics.droppedFrames = parseInt(droppedMatch[1]);
            }

            // Log important FFmpeg messages (errors, stream start confirmation)
            if (line.includes('Error') || line.includes('error') || line.includes('muxing overhead')) {
                console.log(`[Job ${streamJobId}] FFmpeg: ${line.trim()}`);
            }
        });

        // ── Step 4: Heartbeat — write metrics to DB every 15s ─────────────────
        heartbeatTimer = setInterval(async () => {
            try {
                // Check if a stop has been requested via the API
                const checkRes = await jobClient.query(
                    `SELECT status FROM stream_jobs WHERE id = $1`,
                    [streamJobId]
                );

                const currentStatus = checkRes.rows[0]?.status;

                if (currentStatus === 'STOP_REQUESTED') {
                    console.log(`[Job ${streamJobId}] Stop requested. Terminating FFmpeg...`);
                    ffmpegProcess.kill('SIGTERM');
                    return;
                }

                // Write live metrics for the watchdog and frontend to read
                await jobClient.query(
                    `UPDATE stream_jobs
                     SET last_heartbeat         = NOW(),
                         current_fps            = $1,
                         current_bitrate_kbps   = $2,
                         dropped_frames         = $3,
                         bytes_streamed         = $4
                     WHERE id = $5`,
                    [
                        currentMetrics.fps,
                        currentMetrics.bitrateKbps,
                        currentMetrics.droppedFrames,
                        currentMetrics.bytesStreamed,
                        streamJobId,
                    ]
                );

                console.log(
                    `[Job ${streamJobId}] Heartbeat — fps=${currentMetrics.fps} ` +
                    `bitrate=${currentMetrics.bitrateKbps}kbps ` +
                    `dropped=${currentMetrics.droppedFrames}`
                );

            } catch (err) {
                console.error(`[Job ${streamJobId}] Heartbeat error:`, err.message);
            }
        }, HEARTBEAT_EVERY);

        // ── Step 5: Handle FFmpeg exit ─────────────────────────────────────────
        await new Promise((resolve, reject) => {
            ffmpegProcess.on('close', async (code, signal) => {
                activeProcesses.delete(ffmpegProcess);
                clearInterval(heartbeatTimer);
                heartbeatTimer = null;

                console.log(`[Job ${streamJobId}] FFmpeg exited. code=${code} signal=${signal}`);

                try {
                    // Check final status in DB — if STOP_REQUESTED, mark COMPLETED (user-initiated)
                    const finalCheck = await jobClient.query(
                        `SELECT status FROM stream_jobs WHERE id = $1`, [streamJobId]
                    );
                    const finalStatus = finalCheck.rows[0]?.status;
                    const wasRequested = finalStatus === 'STOP_REQUESTED';

                    if (code === 0 || wasRequested) {
                        // Clean exit or user-stopped
                        await jobClient.query(
                            `UPDATE stream_jobs SET status = 'COMPLETED', end_time = NOW() WHERE id = $1`,
                            [streamJobId]
                        );
                        await jobClient.query(
                            `UPDATE stream_job_queue SET status = 'COMPLETED' WHERE id = $1`,
                            [queueId]
                        );
                        console.log(`[Job ${streamJobId}] Marked COMPLETED.`);
                    } else {
                        // FFmpeg crashed
                        const reason = signal
                            ? `FFmpeg killed by signal ${signal}`
                            : `FFmpeg exited with code ${code}`;

                        const retryRes = await jobClient.query(
                            `SELECT retry_count, max_retries FROM stream_jobs WHERE id = $1`, [streamJobId]
                        );
                        const retryCount = retryRes.rows[0]?.retry_count || 0;
                        const maxRetries = retryRes.rows[0]?.max_retries || 3;

                        if (retryCount < maxRetries) {
                            console.warn(`[Job ${streamJobId}] Crashed. Retrying... (${retryCount + 1}/${maxRetries})`);
                            await jobClient.query(
                                `UPDATE stream_jobs SET retry_count = retry_count + 1, status = 'PREPARING' WHERE id = $1`, [streamJobId]
                            );
                            // Push back to queue with slight delay implicitly by other jobs taking precedence, 
                            // but in this simple implementation it will just be PENDING.
                            await jobClient.query(
                                `UPDATE stream_job_queue SET status = 'PENDING', acquired_by = NULL, acquired_at = NULL WHERE id = $1`, [queueId]
                            );
                        } else {
                            await jobClient.query(
                                `UPDATE stream_jobs
                                 SET status = 'FAILED', end_time = NOW(), failure_reason = $1
                                 WHERE id = $2`,
                                [reason, streamJobId]
                            );
                            await jobClient.query(
                                `UPDATE stream_job_queue SET status = 'FAILED' WHERE id = $1`,
                                [queueId]
                            );
                            console.error(`[Job ${streamJobId}] Marked FAILED: ${reason}`);
                        }
                    }
                } catch (err) {
                    console.error(`[Job ${streamJobId}] Error finalising job:`, err.message);
                }

                resolve();
            });

            ffmpegProcess.on('error', err => {
                reject(err);
            });
        });

    } catch (err) {
        console.error(`[Job ${streamJobId}] Fatal error:`, err.message);
        clearInterval(heartbeatTimer);
        if (ffmpegProcess) {
            try { ffmpegProcess.kill('SIGKILL'); } catch (_) {}
        }

        // Mark job as FAILED with reason
        try {
            await jobClient.query(
                `UPDATE stream_jobs
                 SET status = 'FAILED', end_time = NOW(), failure_reason = $1
                 WHERE id = $2`,
                [err.message || 'Unknown worker error', streamJobId]
            );
            await jobClient.query(
                `UPDATE stream_job_queue SET status = 'FAILED' WHERE id = $1`,
                [queueId]
            );
        } catch (dbErr) {
            console.error(`[Job ${streamJobId}] Could not write FAILED status to DB:`, dbErr.message);
        }

    } finally {
        if (jobClient) jobClient.release();

        // Cleanup overlay file if it exists
        if (spec && spec.overlay_enabled && spec.overlay_template) {
            try {
                if (fs.existsSync(spec.overlay_template)) {
                    fs.unlinkSync(spec.overlay_template);
                    console.log(`[Job ${streamJobId}] Cleaned up overlay file: ${spec.overlay_template}`);
                }
            } catch (err) {
                console.error(`[Job ${streamJobId}] Failed to cleanup overlay file:`, err.message);
            }
        }
    }
}

// ── FFmpeg Argument Builder ───────────────────────────────────────────────────

function buildFfmpegArgs(localPath, rtmpUrls, spec, isConcat) {
    const args = ['-re'];
    
    // Only use reconnect options if the input is an HTTP/HTTPS URL
    if (localPath.startsWith('http://') || localPath.startsWith('https://')) {
        args.push(
            '-reconnect',        '1',
            '-reconnect_streamed','1',
            '-reconnect_at_eof', '1',
            '-reconnect_delay_max','5'
        );
    }

    // Looping: ffmpeg supports -stream_loop before the input.
    // N=0 means play once, N=1 means play twice, N=-1 means infinite loop.
    // spec.loop_count is total plays (so loop_count 1 = play once).
    if (spec.loop_count && spec.loop_count > 1) {
        // loop_count 2 means loop 1 time (play twice)
        args.push('-stream_loop', String(spec.loop_count - 1));
    } else if (spec.loop_count === -1) {
        args.push('-stream_loop', '-1');
    }

    if (isConcat) {
        args.push('-f', 'concat', '-safe', '0', '-protocol_whitelist', 'file,http,https,tcp,tls');
    }

    args.push('-i', localPath);

    if (spec.overlay_enabled && spec.overlay_template) {
        args.push('-i', spec.overlay_template);
    }

    // Mix background music if provided
    if (spec.music_name && !spec.mute_video_audio) {
        console.log(`[FFmpeg] Music mixing requested (${spec.music_name}) — not yet implemented, streaming original audio.`);
    }

    // Mute video audio track if requested
    if (spec.mute_video_audio) {
        args.push('-an'); // drop audio
    }

    // Video codec handling based on stream_quality and overlay
    let mapVideo = '0:v?';
    let vFilter = '';
    let targetWidth = 1920;
    let targetHeight = 1080;
    let maxRate = '4500k';
    let bufSize = '9000k';

    if (spec.stream_quality === 720 || spec.stream_quality === '720') {
        targetWidth = 1280;
        targetHeight = 720;
        maxRate = '2500k';
        bufSize = '5000k';
    }

    if (spec.overlay_enabled && spec.overlay_template) {
        vFilter = `scale=${targetWidth}:${targetHeight}:force_original_aspect_ratio=decrease,pad=${targetWidth}:${targetHeight}:(ow-iw)/2:(oh-ih)/2`;
        const filterComplex = `[0:v]${vFilter}[bg];[1:v]scale=${targetWidth}:${targetHeight}[ov];[bg][ov]overlay=0:0[outv]`;
        args.push('-filter_complex', filterComplex);
        mapVideo = '[outv]';
        args.push('-c:v', 'libx264', '-preset', 'veryfast', '-maxrate', maxRate, '-bufsize', bufSize, '-pix_fmt', 'yuv420p', '-g', '60');
        if (!spec.mute_video_audio) args.push('-c:a', 'aac', '-b:a', '128k');
    } else if (spec.stream_quality === 1080 || spec.stream_quality === '1080' || spec.stream_quality === 720 || spec.stream_quality === '720') {
        vFilter = `scale=${targetWidth}:${targetHeight}:force_original_aspect_ratio=decrease,pad=${targetWidth}:${targetHeight}:(ow-iw)/2:(oh-ih)/2`;
        args.push('-vf', vFilter);
        args.push('-c:v', 'libx264', '-preset', 'veryfast', '-maxrate', maxRate, '-bufsize', bufSize, '-pix_fmt', 'yuv420p', '-g', '60');
        if (!spec.mute_video_audio) args.push('-c:a', 'aac', '-b:a', '128k');
    } else {
        // Original
        args.push('-c:v', 'copy');
        if (!spec.mute_video_audio) args.push('-c:a', 'copy');
    }

    // Multi-destination using the 'tee' muxer
    args.push('-map', mapVideo);
    if (!spec.mute_video_audio) {
        args.push('-map', '0:a?');
    }
    
    // Format: -f tee "[f=flv:flvflags=no_duration_filesize]rtmp://server1|[f=flv:flvflags=no_duration_filesize]rtmp://server2"
    const teeOutput = rtmpUrls.map(url => `[f=flv:flvflags=no_duration_filesize]${url}`).join('|');
    args.push('-f', 'tee', teeOutput);

    return args;
}



// ── Boot ──────────────────────────────────────────────────────────────────────

console.log(`\n[${WORKER_ID}] AFK Live Stream Worker starting...`);
console.log(`[${WORKER_ID}] DB: ${dbConfig.host}:${dbConfig.port}/${dbConfig.database}`);
console.log(`[${WORKER_ID}] Spaces bucket: ${SPACES_BUCKET}`);
console.log(`[${WORKER_ID}] Poll interval: ${POLL_INTERVAL}ms | Heartbeat: ${HEARTBEAT_EVERY}ms\n`);

// Graceful shutdown — let the current job finish before exiting
process.on('SIGTERM', () => {
    console.log(`\n[${WORKER_ID}] SIGTERM received. Shutting down gracefully...`);
    isShuttingDown = true;
    if (activeJobs === 0) {
        process.exit(0);
    } else {
        console.log(`[${WORKER_ID}] Waiting for ${activeJobs} active job(s) to finish.`);
        activeProcesses.forEach(p => {
            try { p.kill('SIGTERM'); } catch (_) {}
        });
        
        // Failsafe exit
        setTimeout(() => {
            console.error(`[${WORKER_ID}] Force quitting after 30s timeout.`);
            process.exit(1);
        }, 30000).unref();
    }
});

pollQueue();

// ── Health Server ─────────────────────────────────────────────────────────────
const http = require('http');
const healthServer = http.createServer((req, res) => {
    if (req.url === '/health') {
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end('OK');
    } else {
        res.writeHead(404);
        res.end();
    }
});
const PORT = process.env.PORT || 8081;
healthServer.listen(PORT, () => {
    console.log(`[${WORKER_ID}] Health server listening on port ${PORT}`);
});
