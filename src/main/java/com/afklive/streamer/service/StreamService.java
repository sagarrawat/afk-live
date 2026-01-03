package com.afklive.streamer.service;

import com.afklive.streamer.dto.ApiResponse;
import com.afklive.streamer.dto.StreamResponse;
import com.afklive.streamer.model.StreamJob;
import com.afklive.streamer.repository.StreamJobRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class StreamService {
    @Autowired
    private StreamJobRepository streamJobRepo;

    public final ConcurrentHashMap<String, Integer> conversionProgress = new ConcurrentHashMap<>();
    private final List<String> logBuffer =
            Collections.synchronizedList(new ArrayList<>());

    // Store active streams: StreamKey -> Process
    // (In a real app, use a Database ID or User ID as the key, not the stream key itself)
    private final ConcurrentHashMap<String, Process> activeStreams = new ConcurrentHashMap<>();
    @Autowired
    private UserFileService userFileService;

    // We need to pass 'username' now
    public ApiResponse<StreamResponse> startStream(
            String username,
            String streamKey,
            String videoName,
            String musicName,
            String musicVolume
    ) throws IOException {
        
        log.info("username [{}]", username);

        // 1. SAFETY CHECK: Check DB to see if this user is already live
        if (streamJobRepo.findByUsernameAndIsLiveTrue(username).isPresent()) {
            throw new IllegalStateException("You already have an active stream running!");
        }

        // 2. Resolve Paths
        Path userDir = userFileService.getUserUploadDir(username);
        Path videoPath = userDir.resolve(videoName).toAbsolutePath();
        
        log.info("userDir [{}]", userDir);
        log.info("videoPath [{}]", videoPath);

        // 3. Build the FFmpeg Command
        Path musicPath =
                (musicName != null && !musicName.isEmpty()) ? userDir.resolve(musicName).toAbsolutePath() : null;

        log.info("musicPath [{}]", musicPath);
        
        List<String> command =
                FFmpegCommandBuilder.buildStreamCommand(videoPath, streamKey, musicPath, musicVolume);
        
        log.info("command : [{}]", String.join(" ", command));

        // 4. Start the Process
        ProcessBuilder builder = new ProcessBuilder(command);

        // Redirect logs to console so you can debug "Connection Failed" errors
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        Process process = builder.start();

        clearLogs();

        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while (process.isAlive() && (line = reader.readLine()) != null) {
                    addLog(line);
                }
            } catch (IOException e) {
                addLog("Log Capture Error: " + e.getMessage());
                log.error("Error", e);
            }
        });

        // 5. SAVE STATE TO DATABASE
        // We save the 'pid' so we can kill specifically THIS process later
        StreamJob job =
                new StreamJob(username, streamKey, videoName, musicName, musicVolume, true, process.pid());
        streamJobRepo.save(job);

        // Store reference in memory map as backup (optional, but good for speed)
        activeStreams.put(username, process);

        // 6. EXIT HANDLER (Auto-Update DB)
        process.onExit().thenRun(() -> {
            log.warn("Stream Process Exited for user: {}", username);

            // Mark as Offline in Database
            Optional<StreamJob> jobOpt = streamJobRepo.findByUsernameAndIsLiveTrue(username);
            if (jobOpt.isPresent()) {
                StreamJob existingJob = jobOpt.get();
                existingJob.setLive(false);
                streamJobRepo.save(existingJob);
            }

            // Clear memory map
            activeStreams.remove(username);
        });

        return ApiResponse.success("Stream started", new StreamResponse(
                String.valueOf(process.pid()),
                streamKey,
                "RUNNING",
                "Stream is now live"
        ));
    }

    public ApiResponse<?> stopStream(String username) {
        // 1. Find active job in DB
        Optional<StreamJob> jobOpt = streamJobRepo.findByUsernameAndIsLiveTrue(username);

        if (jobOpt.isPresent()) {
            StreamJob job = jobOpt.get();
            // 2. Kill Process
            ProcessHandle.of(job.getPid()).ifPresent(ProcessHandle::destroyForcibly);

            // 3. Update DB
            job.setLive(false);
            streamJobRepo.save(job);
            return ApiResponse.success("Stream stopped successfully", null);
        }
        return ApiResponse.success("No active stream found", null);
    }

    public StreamJob getCurrentStatus(String username) {
        return streamJobRepo.findByUsernameAndIsLiveTrue(username).orElse(null);
    }

    // SAFETY NET: Kill all streams if the Java Server shuts down
    @PreDestroy
    public void onShutdown() {
        log.info("Application shutdown - Terminating {} active streams", activeStreams.size());
        activeStreams.values().forEach(Process::destroyForcibly);
    }

    public void addLog(String line) {
        if (logBuffer.size() > 50) {
            logBuffer.removeFirst();
        }
        logBuffer.add(line);
    }

    public List<String> getLogs() {
        return new ArrayList<>(logBuffer);
    }

    public void clearLogs() {
        logBuffer.clear();
    }
}