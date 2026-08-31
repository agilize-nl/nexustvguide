import { type FastifyInstance } from 'fastify';
import { TvgidsClient } from './sources/tvgids/client.js';
import { SnapshotStore } from './store/cache.js';
import { RefreshEngine } from './store/refresh.js';
import { NlzietMatcher } from './enrichment/nlziet/matcher.js';
import { NlzietEpgMatcher } from './enrichment/nlziet/epg-matcher.js';
import { NlzietEpgClient } from './enrichment/nlziet/epg-client.js';
export interface AppOptions {
    dataDir?: string;
    releasesDir?: string;
    channelsConfigPath?: string;
    tvgidsBaseUrl?: string;
    nlzietEpgBaseUrl?: string;
    enableCors?: boolean;
    nlzietSeedFilePath?: string;
    nlzietCacheFilePath?: string;
    nlzietOverridesFilePath?: string;
    nlzietMatcher?: NlzietMatcher;
    nlzietEpgMatcher?: NlzietEpgMatcher;
    nlzietEpgClient?: NlzietEpgClient;
}
export declare function buildApp(options?: AppOptions): {
    app: FastifyInstance;
    store: SnapshotStore;
    engine: RefreshEngine;
    client: TvgidsClient;
    matcher: NlzietMatcher;
    epgMatcher: NlzietEpgMatcher;
    epgClient: NlzietEpgClient;
};
