import type { DaySnapshot } from '../domain/guide.js';
import type { Channel } from '../domain/channel.js';
export declare class SnapshotStore {
    private inMemorySnapshots;
    private dataDir;
    private channels;
    constructor(dataDir: string);
    setChannels(channels: Channel[]): void;
    getChannels(): Channel[];
    /**
     * Laadt alle bestaande snapshots van disk bij opstarten.
     */
    loadFromDisk(): Promise<number>;
    getSnapshot(date: string): DaySnapshot | undefined;
    getAllSnapshots(): Map<string, DaySnapshot>;
    /**
     * Slaat een snapshot atomair op in het geheugen en op disk via een tijdelijk bestand en rename.
     */
    saveSnapshot(snapshot: DaySnapshot): Promise<void>;
    /**
     * Berekent een ETag op basis van de opgeslagen snapshots.
     */
    getETag(dates: string[]): string;
    /**
     * Verwijdert oude snapshots die ouder zijn dan de opgegeven drempeldatum.
     */
    cleanOldSnapshots(minAllowedDate: string): Promise<void>;
}
