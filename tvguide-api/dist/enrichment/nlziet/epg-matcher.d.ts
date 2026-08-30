import type { Programme, NlzietProgrammeTarget } from "../../domain/programme.js";
import type { Channel } from "../../domain/channel.js";
import type { NlzietEpgResponse, NlzietEpgContent } from "./epg-schema.js";
import type { EnrichmentStats } from "./types.js";
export declare const MAX_START_DIFF_MS: number;
export declare const MAX_DURATION_DIFF_MS: number;
export declare function normalizeEpgTitle(title: string | null | undefined): string;
export interface MatchCandidate {
    content: NlzietEpgContent;
    startDiffMs: number;
    durationDiffMs: number;
}
export declare class NlzietEpgMatcher {
    /**
     * Zoekt een exact EPG-target voor één programma op basis van NLZIET EPG-items.
     */
    matchProgramme(programme: Programme, epgItems: NlzietEpgContent[], nlzietChannelId: string): {
        target: NlzietProgrammeTarget | null;
        isAmbiguous: boolean;
        isTimingOrTitleMismatch: boolean;
    };
    /**
     * Verrijkt programmas voor een dag met data uit de NLZIET EPG response.
     */
    enrichProgrammes(programmes: Programme[], epgResponse: NlzietEpgResponse | null, channels?: Channel[], isOutsideEpgWindow?: boolean, epgFetchFailed?: boolean): EnrichmentStats;
}
