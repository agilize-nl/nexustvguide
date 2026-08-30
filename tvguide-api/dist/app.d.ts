import { type FastifyInstance } from 'fastify';
import { TvgidsClient } from './sources/tvgids/client.js';
import { SnapshotStore } from './store/cache.js';
import { RefreshEngine } from './store/refresh.js';
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
export declare function buildApp(options?: AppOptions): {
    app: FastifyInstance;
    store: SnapshotStore;
    engine: RefreshEngine;
    client: TvgidsClient;
    matcher: NlzietMatcher;
};
