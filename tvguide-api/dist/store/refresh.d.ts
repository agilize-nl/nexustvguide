import { TvgidsClient } from '../sources/tvgids/client.js';
import { SnapshotStore } from './cache.js';
import type { Channel } from '../domain/channel.js';
import type { DaySnapshot } from '../domain/guide.js';
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
}
export declare class RefreshEngine {
    private client;
    private store;
    private channelsConfigPath;
    private startTime;
    private validationStats;
    private lastRefreshAttempt;
    private lastSuccessfulRefresh;
    private lastError;
    private refreshIntervalTimer;
    private isRefreshing;
    constructor(client: TvgidsClient, store: SnapshotStore, channelsConfigPath: string);
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
