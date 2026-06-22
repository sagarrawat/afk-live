require('dotenv').config();
const axios = require('axios');
const { Client } = require('pg');

const DO_API_URL = 'https://api.digitalocean.com/v2';
const API_TOKEN = process.env.DO_API_TOKEN;

const dbConfig = {
    user: process.env.DB_USER || 'afklive_user',
    host: process.env.DB_HOST || 'localhost',
    database: process.env.DB_NAME || 'afklive',
    password: process.env.DB_PASSWORD || 'password',
    port: process.env.DB_PORT || 5432,
};

async function getDigitalOceanDailyCost() {
    console.log('[Mock DO API] Fetching daily infrastructure cost...');
    // Real API would fetch billing history or droplet hours
    return 14.50; 
}

async function getTrackedPlatformCost() {
    console.log('[PostgreSQL] Fetching sum of accumulated cost from active/completed streams today...');
    const client = new Client(dbConfig);
    await client.connect();
    
    try {
        const query = `
            SELECT SUM(cost) as total_revenue
            FROM stream_jobs 
            WHERE start_time >= CURRENT_DATE
        `;
        const res = await client.query(query);
        return parseFloat(res.rows[0].total_revenue || 0);
    } catch (err) {
        console.error('DB Error:', err);
        return 20.00; // Mock fallback
    } finally {
        await client.end();
    }
}

async function runNightlyReconciliation() {
    console.log('--- Nightly Financial Reconciliation ---');
    
    const doCost = await getDigitalOceanDailyCost();
    const trackedRevenue = await getTrackedPlatformCost();
    
    const profitMargin = trackedRevenue - doCost;
    
    console.log(`DigitalOcean Infra Cost (Today): $${doCost.toFixed(2)}`);
    console.log(`Platform Tracked Revenue (Today): $${trackedRevenue.toFixed(2)}`);
    
    if (profitMargin >= 0) {
        console.log(`✅ System is profitable today. Margin: +$${profitMargin.toFixed(2)}`);
    } else {
        console.log(`❌ System operated at a loss today. Margin: -$${Math.abs(profitMargin).toFixed(2)}`);
        console.log(`Alert: Consider lowering worker capacities or checking for unbilled streams.`);
    }
    
    console.log('----------------------------------------\n');
}

runNightlyReconciliation();
