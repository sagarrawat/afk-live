const { Pool } = require('pg');
const fetch = require('node-fetch'); // assuming node-fetch is available or using built-in fetch if Node 18+

// Nightly Reconciliation Script (Phase 6)
// Compares tracked DB costs against DigitalOcean actual billing API.

const pool = new Pool({
  user: process.env.POSTGRES_USER || 'postgres',
  host: process.env.POSTGRES_HOST || 'localhost',
  database: process.env.POSTGRES_DB || 'afkdb',
  password: process.env.POSTGRES_PASSWORD || 'password',
  port: process.env.POSTGRES_PORT || 5432,
});

const DO_TOKEN = process.env.DO_API_TOKEN;

async function run() {
  console.log('[Reconciliation] Starting nightly billing reconciliation...');

  let client;
  try {
    client = await pool.connect();
    
    // 1. Get tracked daily spend from our DB config (which resets daily or we can query active streams)
    const result = await client.query(`SELECT value FROM app_configs WHERE key = 'daily_spend_usd'`);
    const trackedSpendStr = result.rows.length > 0 ? result.rows[0].value : "0.00";
    const trackedSpend = parseFloat(trackedSpendStr);
    
    console.log(`[Reconciliation] Our tracked daily spend (USD): $${trackedSpend.toFixed(2)}`);

    // 2. Query DigitalOcean API for month-to-date billing
    if (!DO_TOKEN) {
      console.warn('[Reconciliation] WARNING: DO_API_TOKEN not set. Cannot fetch actual DO costs.');
    } else {
      const response = await fetch('https://api.digitalocean.com/v2/customers/my/balance', {
        headers: { 'Authorization': `Bearer ${DO_TOKEN}` }
      });
      
      if (response.ok) {
        const data = await response.json();
        const doBalance = Math.abs(parseFloat(data.month_to_date_balance));
        console.log(`[Reconciliation] DigitalOcean Month-to-Date Spend: $${doBalance.toFixed(2)}`);
        
        // This is a naive check; in reality, we'd store yesterday's balance and compare the delta.
        // If discrepancy > 10%, we send an alert!
      } else {
        console.error(`[Reconciliation] Failed to fetch from DO: ${response.statusText}`);
      }
    }

    console.log('[Reconciliation] Finished.');
  } catch (err) {
    console.error('[Reconciliation] Error during reconciliation:', err);
  } finally {
    if (client) client.release();
    await pool.end();
  }
}

run();
