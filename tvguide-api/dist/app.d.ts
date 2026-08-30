import { type FastifyInstance } from 'fastify';
import { TvgidsClient } from './sources/tvgids/client.js';
import { SnapshotStore } from './store/cache.js';
import { RefreshEngine } from './store/refresh.js';
export interface AppOptions {
    dataDir?: string;
    channelsConfigPath?: string;
    tvgidsBaseUrl?: string;
    enableCors?: boolean;
}
export declare function buildApp(options?: AppOptions): {
    app: FastifyInstance;
    store: SnapshotStore;
    engine: RefreshEngine;
    client: TvgidsClient;
};
