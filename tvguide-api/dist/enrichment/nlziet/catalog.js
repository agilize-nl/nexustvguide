import fs from 'node:fs';
import path from 'node:path';
export class NlzietCatalogStore {
    catalog = new Map();
    seedFilePath;
    cacheFilePath;
    sitemapUrl;
    isUpdating = false;
    lastUpdated = null;
    constructor(options = {}) {
        this.seedFilePath = options.seedFilePath || path.resolve(process.cwd(), 'config/nlziet_catalog_seed.json');
        this.cacheFilePath = options.cacheFilePath || path.resolve(process.cwd(), 'data/nlziet-catalog.json');
        this.sitemapUrl = options.sitemapUrl || 'https://www.nlziet.nl/nl/program-sitemap.xml';
        this.initialize();
    }
    /**
     * Laadt eerst de seed-data in en probeert vervolgens de disk-cache in te lezen.
     */
    initialize() {
        // 1. Laad seed-bestand
        if (fs.existsSync(this.seedFilePath)) {
            try {
                const raw = fs.readFileSync(this.seedFilePath, 'utf-8');
                const items = JSON.parse(raw);
                for (const item of items) {
                    if (item && item.slug) {
                        this.catalog.set(item.slug, item);
                    }
                }
            }
            catch (err) {
                console.error('Failed to load NLZIET seed catalog:', err);
            }
        }
        // 2. Laad persistente disk cache indien aanwezig
        if (fs.existsSync(this.cacheFilePath)) {
            try {
                const raw = fs.readFileSync(this.cacheFilePath, 'utf-8');
                const items = JSON.parse(raw);
                for (const item of items) {
                    if (item && item.slug) {
                        this.catalog.set(item.slug, item);
                    }
                }
            }
            catch (err) {
                console.error('Failed to load NLZIET disk cache:', err);
            }
        }
    }
    getItemBySlug(slug) {
        return this.catalog.get(slug);
    }
    getAllItems() {
        return Array.from(this.catalog.values());
    }
    getItemCount() {
        return this.catalog.size;
    }
    getLastUpdated() {
        return this.lastUpdated;
    }
    /**
     * Slaat de huidige catalogus atomair op naar disk.
     */
    async saveToDisk() {
        try {
            const dir = path.dirname(this.cacheFilePath);
            if (!fs.existsSync(dir)) {
                await fs.promises.mkdir(dir, { recursive: true });
            }
            const items = Array.from(this.catalog.values());
            const json = JSON.stringify(items, null, 2);
            const tmpPath = `${this.cacheFilePath}.tmp.${process.pid}.${Date.now()}`;
            await fs.promises.writeFile(tmpPath, json, 'utf-8');
            await fs.promises.rename(tmpPath, this.cacheFilePath);
        }
        catch (err) {
            console.error('Failed to save NLZIET catalog to disk:', err);
        }
    }
    /**
     * Haalt de officiële program-sitemap van NLZIET op en extraheert slugs.
     */
    async fetchSitemapSlugs() {
        try {
            const resp = await fetch(this.sitemapUrl, {
                headers: {
                    'User-Agent': 'NexusTVGuide/1.0 (NLZIET-Enrichment-Worker)',
                    'Accept': 'text/xml,application/xml',
                },
                signal: AbortSignal.timeout(10000),
            });
            if (!resp.ok) {
                console.warn(`NLZIET sitemap request returned status ${resp.status}`);
                return [];
            }
            const xml = await resp.text();
            const locMatches = xml.matchAll(/<loc>([^<]+)<\/loc>/g);
            const slugs = [];
            for (const m of locMatches) {
                const url = m[1].trim();
                const parts = url.replace(/\/+$/, '').split('/');
                const slug = parts[parts.length - 1];
                if (slug && !slugs.includes(slug)) {
                    slugs.push(slug);
                }
            }
            return slugs;
        }
        catch (err) {
            console.warn('Could not fetch NLZIET sitemap:', err instanceof Error ? err.message : String(err));
            return [];
        }
    }
    /**
     * Scrapet een individuele programmapagina om VOD-content ID's te ontdekken.
     */
    async scrapeProgramPage(slug) {
        const url = `https://www.nlziet.nl/nl/programma/${slug}/`;
        try {
            const resp = await fetch(url, {
                headers: {
                    'User-Agent': 'NexusTVGuide/1.0 (NLZIET-Enrichment-Worker)',
                    'Accept': 'text/html,application/xhtml+xml',
                },
                signal: AbortSignal.timeout(8000),
            });
            if (!resp.ok) {
                return null;
            }
            const html = await resp.text();
            // Extract titel uit <title> tag
            const titleMatch = html.match(/<title>([^<]+)<\/title>/i);
            let title = slug;
            if (titleMatch && titleMatch[1]) {
                title = titleMatch[1].split('|')[0].replace(/\b(?:gemist|kijken|live|terugkijken|bij NLZIET)\b/gi, '').trim();
            }
            // Extract VOD / Watch ID links
            const vodMatches = Array.from(html.matchAll(/https:\/\/app\.nlziet\.nl\/(?:vod|series|play|watch)\/([a-zA-Z0-9_-]+)/g));
            const vodIds = Array.from(new Set(vodMatches.map((m) => m[1])));
            const primaryId = vodIds.length > 0 ? vodIds[0] : null;
            const item = {
                slug,
                title,
                url,
                vodIds,
                primaryId,
                lastUpdated: new Date().toISOString(),
            };
            return item;
        }
        catch (err) {
            return null;
        }
    }
    /**
     * Update de catalogus op de achtergrond.
     */
    async updateCatalog(maxItemsToScrape = 50) {
        if (this.isUpdating)
            return 0;
        this.isUpdating = true;
        try {
            const slugs = await this.fetchSitemapSlugs();
            if (slugs.length === 0) {
                return 0;
            }
            let updatedCount = 0;
            let scrapedCount = 0;
            for (const slug of slugs) {
                const existing = this.catalog.get(slug);
                // Als we nog geen item hebben of als er nog geen primaryId is, proberen we de pagina op te halen
                if (!existing || !existing.primaryId) {
                    if (scrapedCount >= maxItemsToScrape)
                        break;
                    scrapedCount++;
                    const item = await this.scrapeProgramPage(slug);
                    if (item) {
                        this.catalog.set(slug, item);
                        updatedCount++;
                    }
                }
                else if (!existing.url) {
                    existing.url = `https://www.nlziet.nl/nl/programma/${slug}/`;
                    this.catalog.set(slug, existing);
                    updatedCount++;
                }
            }
            if (updatedCount > 0) {
                this.lastUpdated = new Date().toISOString();
                await this.saveToDisk();
            }
            return updatedCount;
        }
        finally {
            this.isUpdating = false;
        }
    }
}
