import path from 'node:path';
import fastify, { type FastifyInstance } from 'fastify';
import cors from '@fastify/cors';
import { TvgidsClient } from './sources/tvgids/client.js';
import { SnapshotStore } from './store/cache.js';
import { RefreshEngine } from './store/refresh.js';
import { registerRestRoutes } from './output/rest.js';
import { registerXmltvRoutes } from './output/xmltv.js';
import { registerHealthRoutes } from './output/health.js';
import { NlzietCatalogStore } from './enrichment/nlziet/catalog.js';
import { NlzietMatcher } from './enrichment/nlziet/matcher.js';

export interface AppOptions {
  dataDir?: string;
  channelsConfigPath?: string;
  tvgidsBaseUrl?: string;
  enableCors?: boolean;
  nlzietSeedFilePath?: string;
  nlzietCacheFilePath?: string;
  nlzietOverridesFilePath?: string;
  nlzietMatcher?: NlzietMatcher;
}

export function buildApp(options: AppOptions = {}): {
  app: FastifyInstance;
  store: SnapshotStore;
  engine: RefreshEngine;
  client: TvgidsClient;
  matcher: NlzietMatcher;
} {
  const app = fastify({
    logger: false,
  });

  if (options.enableCors !== false) {
    app.register(cors, {
      origin: true,
    });
  }

  const dataDir = options.dataDir || path.resolve(process.cwd(), 'data/snapshots');
  const channelsConfigPath = options.channelsConfigPath || path.resolve(process.cwd(), 'config/channels.json');

  const catalogStore = new NlzietCatalogStore({
    seedFilePath: options.nlzietSeedFilePath,
    cacheFilePath: options.nlzietCacheFilePath,
  });

  const matcher =
    options.nlzietMatcher ||
    new NlzietMatcher({
      catalogStore,
      overridesFilePath: options.nlzietOverridesFilePath,
    });

  const store = new SnapshotStore(dataDir);
  const client = new TvgidsClient({ baseUrl: options.tvgidsBaseUrl });
  const engine = new RefreshEngine(client, store, channelsConfigPath, matcher);

  // Standaard foutafhandeling
  app.setErrorHandler((error, request, reply) => {
    const statusCode = error.statusCode || 500;
    const errorCode = statusCode === 400 ? 'INVALID_QUERY_PARAM' : 'INTERNAL_ERROR';

    reply.status(statusCode).send({
      error: {
        code: errorCode,
        message: error.message,
      },
    });
  });

  // Registreer alle routes
  registerHealthRoutes(app, engine);
  registerRestRoutes(app, store, engine);
  registerXmltvRoutes(app, store);

  return { app, store, engine, client, matcher };
}
