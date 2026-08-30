import path from 'node:path';
import { buildApp } from './app.js';

const PORT = parseInt(process.env.PORT || '3000', 10);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_DIR = process.env.DATA_DIR || path.resolve(process.cwd(), 'data/snapshots');
const CHANNELS_CONFIG = process.env.CHANNELS_CONFIG || path.resolve(process.cwd(), 'config/channels.json');
const NLZIET_CACHE_FILE_PATH = process.env.NLZIET_CACHE_FILE_PATH;
const NLZIET_SEED_FILE_PATH = process.env.NLZIET_SEED_FILE_PATH;
const NLZIET_OVERRIDES_FILE_PATH = process.env.NLZIET_OVERRIDES_FILE_PATH;
const TVGIDS_BASE_URL = process.env.TVGIDS_BASE_URL;

async function main() {
  console.log('Starting NexusTVGuide backend (tvguide-api)...');
  const { app, store, engine } = buildApp({
    dataDir: DATA_DIR,
    channelsConfigPath: CHANNELS_CONFIG,
    nlzietCacheFilePath: NLZIET_CACHE_FILE_PATH,
    nlzietSeedFilePath: NLZIET_SEED_FILE_PATH,
    nlzietOverridesFilePath: NLZIET_OVERRIDES_FILE_PATH,
    tvgidsBaseUrl: TVGIDS_BASE_URL,
  });

  // 1. Laad kanalenconfiguratie en bestaande snapshots van disk
  engine.loadChannelsConfig();
  const loadedCount = await store.loadFromDisk();
  console.log(`Loaded ${loadedCount} day snapshot(s) from disk (${DATA_DIR}).`);

  // 2. Start de server
  try {
    await app.listen({ port: PORT, host: HOST });
    console.log(`NexusTVGuide API server listening on http://${HOST}:${PORT}`);
  } catch (err) {
    console.error('Failed to start server:', err);
    process.exit(1);
  }

  // 3. Start achtergrondverversing
  console.log('Initiating guide data refresh cycle in background...');
  engine.startPeriodicRefresh(30);

  // Graceful shutdown
  const shutdown = async (signal: string) => {
    console.log(`\nReceived ${signal}. Shutting down gracefully...`);
    engine.stop();
    await app.close();
    process.exit(0);
  };

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
}

main().catch((err) => {
  console.error('Fatal error during startup:', err);
  process.exit(1);
});
