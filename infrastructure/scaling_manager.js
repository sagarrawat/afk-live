require('dotenv').config();
const axios = require('axios');
const moment = require('moment-timezone');

const DO_API_URL = 'https://api.digitalocean.com/v2';
const API_TOKEN = process.env.DO_API_TOKEN;
const WORKER_DROPLET_TAG = 'afklive-worker';

// Peak Hours: 1 PM (13:00) to 10 PM (22:00) IST
const PEAK_START_HOUR = 13;
const PEAK_END_HOUR = 22;

const SCALE_CONFIG = {
    PEAK: { min: 20, max: 100 },
    NON_PEAK: { min: 5, max: 30 }
};

async function getActiveWorkerCount() {
    // In production, this queries the DO API:
    // const res = await axios.get(`${DO_API_URL}/droplets?tag_name=${WORKER_DROPLET_TAG}`, { ... });
    // return res.data.droplets.length;
    console.log('[Mock DO API] Fetching active worker count...');
    return 10; // Mock current worker count
}

async function getQueueDepth() {
    // Queries our API server for queue depth
    // const res = await axios.get('http://api-server/metrics/queue');
    // return res.data.depth;
    console.log('[Mock App API] Fetching queue depth...');
    return 25; // Mock pending jobs
}

async function scaleWorkers(targetCount) {
    console.log(`[DO API] Scaling droplet pool to exactly ${targetCount} workers...`);
    // Example DO logic: if adding, trigger DO App Platform or Droplet API
}

async function runScaler() {
    console.log('--- AFK Live Autoscaler ---');
    
    // 1. Determine Time Context (IST)
    const now = moment().tz('Asia/Kolkata');
    const hour = now.hour();
    
    const isPeak = hour >= PEAK_START_HOUR && hour < PEAK_END_HOUR;
    const config = isPeak ? SCALE_CONFIG.PEAK : SCALE_CONFIG.NON_PEAK;
    
    console.log(`Time: ${now.format('YYYY-MM-DD HH:mm:ss')} (IST)`);
    console.log(`Context: ${isPeak ? 'PEAK HOURS 📈' : 'NON-PEAK HOURS 📉'}`);
    console.log(`Constraints: Min=${config.min}, Max=${config.max}`);
    
    // 2. Fetch Metrics
    const activeWorkers = await getActiveWorkerCount();
    const queueDepth = await getQueueDepth();
    console.log(`Current Workers: ${activeWorkers}`);
    console.log(`Queue Depth: ${queueDepth}`);
    
    // 3. Calculation Logic
    let targetWorkers = activeWorkers;
    
    // Simple heuristic: 1 worker per job in queue, up to max.
    const neededWorkers = queueDepth; 
    
    if (neededWorkers > activeWorkers) {
        targetWorkers = neededWorkers;
    }
    
    // Apply bounds
    if (targetWorkers < config.min) targetWorkers = config.min;
    if (targetWorkers > config.max) targetWorkers = config.max;
    
    // 4. Execution
    if (targetWorkers !== activeWorkers) {
        console.log(`Action: Scaling from ${activeWorkers} to ${targetWorkers}`);
        await scaleWorkers(targetWorkers);
    } else {
        console.log(`Action: No scaling needed. Current pool is sufficient.`);
    }
    console.log('---------------------------\n');
}

// Run immediately, then every 5 minutes
runScaler();
setInterval(runScaler, 5 * 60 * 1000);
