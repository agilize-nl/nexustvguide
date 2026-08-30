import type { TvgidsClient } from '../sources/tvgids/client.js';
import type { SnapshotStore } from './cache.js';
import type { Channel } from '../domain/channel.js';
import type { DaySnapshot } from '../domain/guide.js';
import { NlzietEpgClient } from '../enrichment/nlziet/epg-client.js';
import { NlzietEpgMatcher } from '../enrichment/nlziet/epg-matcher.js';
import type { EnrichmentStats } from '../enrichment/nlziet/types.js';
export declare const MIN_PROVIDER_OFFSET = -2;
export declare const MAX_PROVIDER_OFFSET = 13;
export interface RefreshStats {
    status: 'ok' | 'degraded';
    uptimeSeconds: number;
    lastRefreshAttempt: string | null;
    lastSuccessfulRefresh: string | null;
    skippedMalformedProgrammesCount: number;
    loadedChannelCount: number;
    availableSnapshotDates: string[];
    lastError: string | null;
    lastEnrichmentStats?: EnrichmentStats | null;
}
export declare class RefreshEngine {
    private client;
    private store;
    private channelsConfigPath;
    private epgClient;
    private epgMatcher;
    private isRefreshing;
    private refreshIntervalTimer;
    private lastSuccessfulRefresh;
    private lastRefreshAttempt;
    private lastError;
    private startTime;
    private validationStats;
    private lastEnrichmentStats;
    constructor(client: TvgidsClient, store: SnapshotStore, channelsConfigPath: string, epgMatcher?: NlzietEpgMatcher | any, epgClient?: NlzietEpgClient);
    getEpgMatcher(): NlzietEpgMatcher;
    getEpgClient(): NlzietEpgClient;
    loadChannelsConfig(): Channel[];
    getStats(): RefreshStats;
    isReady(): boolean;
    isSnapshotStale(snapshot: DaySnapshot): boolean;
    private addDays;
    /**
     * Voert een complete verversingscyclus uit voor offsets -2..13
     */
    refreshAll(): Promise<void>;
    startPeriodicRefresh(intervalMinutes?: number): void;
    stop(): void;
}
