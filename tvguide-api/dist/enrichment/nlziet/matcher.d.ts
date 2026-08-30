import type { Programme } from '../../domain/programme.js';
import type { Channel } from '../../domain/channel.js';
import type { EnrichmentStats } from './types.js';
import { NlzietCatalogStore } from './catalog.js';
export interface MatcherOptions {
    overridesFilePath?: string;
    catalogStore?: NlzietCatalogStore;
}
export declare class NlzietMatcher {
    private catalogStore;
    private overridesConfig;
    private overridesFilePath;
    constructor(options?: MatcherOptions);
    private loadOverrides;
    getCatalogStore(): NlzietCatalogStore;
    /**
     * Zoekt het beste NLZIET content/playable ID voor een gegeven programma.
     */
    matchProgramme(programme: Programme, channel?: Channel): string | null;
    /**
     * Verrijkt een lijst van programma's met NLZIET ID's en berekent statistieken.
     */
    enrichProgrammes(programmes: Programme[], channels?: Channel[]): EnrichmentStats;
}
