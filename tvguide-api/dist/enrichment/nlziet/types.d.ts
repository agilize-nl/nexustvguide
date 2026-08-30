export interface NlzietCatalogItem {
    slug: string;
    title: string;
    url: string;
    vodIds: string[];
    primaryId: string | null;
    channelSlug?: string | null;
    lastUpdated?: string;
}
export interface NlzietOverrideRule {
    pattern: string;
    isRegex?: boolean;
    nlzietId?: string | null;
    targetSlug?: string | null;
    channelId?: string;
}
export interface NlzietOverridesConfig {
    aliases: Record<string, string>;
    rules: NlzietOverrideRule[];
}
export interface EnrichmentStats {
    totalProgrammes: number;
    epgEligibleProgrammes: number;
    exactTargets: number;
    replayAllowedTargets: number;
    rejectedAmbiguous: number;
    rejectedTitleOrTiming: number;
    skippedOutsideEpgWindow: number;
    epgFetchFailed: boolean;
    enrichedProgrammes?: number;
    enrichmentRate?: number;
    matchedSlugs?: Record<string, number>;
}
