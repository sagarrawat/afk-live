const { execSync } = require('child_process');

// Time-Aware Scaling Script (Phase 6)
// Adjusts worker pool size based on peak (1 PM - 10 PM IST) vs non-peak hours.

const PEAK_WORKERS = 5;
const NON_PEAK_WORKERS = 2;

function getISTHour() {
  const now = new Date();
  // Convert current time to IST (UTC + 5:30)
  const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
  const istTime = new Date(utc + (3600000 * 5.5));
  return istTime.getHours();
}

function scaleWorkers(targetScale) {
  console.log(`[Scaler] Scaling afk-worker to ${targetScale} instances...`);
  try {
    // In a real DigitalOcean swarm/kubernetes setup, this would be `kubectl scale` or DO API call.
    // For docker-compose, we can use `docker-compose up --scale worker=N`
    execSync(`docker-compose up -d --scale worker=${targetScale}`, { stdio: 'inherit' });
    console.log(`[Scaler] Successfully scaled to ${targetScale}.`);
  } catch (error) {
    console.error(`[Scaler] Failed to scale workers: ${error.message}`);
  }
}

function run() {
  const currentHour = getISTHour();
  console.log(`[Scaler] Current IST Hour is ${currentHour}`);

  // Peak hours: 13:00 to 22:00 (1 PM - 10 PM IST)
  if (currentHour >= 13 && currentHour < 22) {
    console.log("[Scaler] We are in PEAK hours.");
    scaleWorkers(PEAK_WORKERS);
  } else {
    console.log("[Scaler] We are in NON-PEAK hours.");
    scaleWorkers(NON_PEAK_WORKERS);
  }
}

run();
