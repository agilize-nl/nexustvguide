import type { NlzietCatalogItem } from './types.js';
export interface CatalogOptions {
    seedFilePath?: string;
    cacheFilePath?: string;
    sitemapUrl?: string;
    scrapeBatchSize?: number;
}
export declare class NlzietCatalogStore {
    private catalog;
    private seedFilePath;
    private cacheFilePath;
    private sitemapUrl;
    private isUpdating;
    private lastUpdated;
    constructor(options?: CatalogOptions);
    /**
     * Laadt eerst de seed-data in en probeert vervolgens de disk-cache in te lezen.
     */
    initialize(): void;
    getItemBySlug(slug: string): NlzietCatalogItem | undefined;
    getAllItems(): NlzietCatalogItem[];
    getItemCount(): number;
    getLastUpdated(): string | null;
    /**
     * Slaat de huidige catalogus atomair op naar disk.
     */
    saveToDisk(): Promise<void>;
    /**
     * Haalt de officiële program-sitemap van NLZIET op en extraheert slugs.
     */
    fetchSitemapSlugs(): Promise<string[]>;
    /**
     * Scrapet een individuele programmapagina om VOD-content ID's te ontdekken.
     */
    scrapeProgramPage(slug: string): Promise<NlzietCatalogItem | null>;
    /**
     * Update de catalogus op de achtergrond.
     */
    updateCatalog(maxItemsToScrape?: number): Promise<number>;
}
