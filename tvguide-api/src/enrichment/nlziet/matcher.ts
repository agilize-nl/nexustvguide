import fs from 'node:fs';
import path from 'node:path';
import type { Programme } from '../../domain/programme.js';
import type { Channel } from '../../domain/channel.js';
import type { NlzietOverridesConfig, EnrichmentStats } from './types.js';
import { slugifyTitle, normalizeDutchTitle } from './normalizer.js';
import { NlzietCatalogStore } from './catalog.js';

export interface MatcherOptions {
  overridesFilePath?: string;
  catalogStore?: NlzietCatalogStore;
}

export class NlzietMatcher {
  private catalogStore: NlzietCatalogStore;
  private overridesConfig: NlzietOverridesConfig = { aliases: {}, rules: [] };
  private overridesFilePath: string;

  constructor(options: MatcherOptions = {}) {
    this.overridesFilePath = options.overridesFilePath || process.env.NLZIET_OVERRIDES_FILE_PATH || path.resolve(process.cwd(), 'config/nlziet_overrides.json');
    this.catalogStore = options.catalogStore || new NlzietCatalogStore();

    this.loadOverrides();
  }

  private loadOverrides(): void {
    if (fs.existsSync(this.overridesFilePath)) {
      try {
        const raw = fs.readFileSync(this.overridesFilePath, 'utf-8');
        this.overridesConfig = JSON.parse(raw) as NlzietOverridesConfig;
      } catch (err) {
        console.error('Failed to load NLZIET overrides configuration:', err);
      }
    }
  }

  public getCatalogStore(): NlzietCatalogStore {
    return this.catalogStore;
  }

  /**
   * Zoekt het beste NLZIET content/playable ID voor een gegeven programma.
   */
  public matchProgramme(programme: Programme, channel?: Channel): string | null {
    if (programme.nlzietId) {
      return programme.nlzietId;
    }

    if (!programme.title) {
      return null;
    }

    const title = programme.title.trim();
    const slug = slugifyTitle(title);
    if (!slug) {
      return null;
    }

    // 1. Check specifieke override regels (Regex / Patterns)
    if (this.overridesConfig.rules) {
      for (const rule of this.overridesConfig.rules) {
        if (rule.channelId && channel && channel.id !== rule.channelId) {
          continue;
        }

        let isMatch = false;
        if (rule.isRegex) {
          try {
            const regex = new RegExp(rule.pattern, 'i');
            isMatch = regex.test(title) || regex.test(slug);
          } catch (e) {
            // Ongeldige regex negeren
          }
        } else {
          isMatch = title.toLowerCase() === rule.pattern.toLowerCase() || slug === rule.pattern.toLowerCase();
        }

        if (isMatch) {
          if (rule.nlzietId) {
            return rule.nlzietId;
          }
          if (rule.targetSlug) {
            const catalogItem = this.catalogStore.getItemBySlug(rule.targetSlug);
            if (catalogItem?.primaryId) {
              return catalogItem.primaryId;
            }
          }
        }
      }
    }

    // 2. Check geconfigureerde aliassen
    if (this.overridesConfig.aliases && this.overridesConfig.aliases[slug]) {
      const aliasTarget = this.overridesConfig.aliases[slug];
      const catalogItem = this.catalogStore.getItemBySlug(aliasTarget);
      if (catalogItem?.primaryId) {
        return catalogItem.primaryId;
      }
    }

    // 3. Exacte slug matching in de catalogus
    const exactItem = this.catalogStore.getItemBySlug(slug);
    if (exactItem?.primaryId) {
      return exactItem.primaryId;
    }

    // 4. Probeer veelvoorkomende slug-varianten (bijv. -gemist, -kijken)
    const suffixVariants = [`${slug}-gemist`, `${slug}-kijken`, `${slug}-terugkijken`];
    for (const variant of suffixVariants) {
      const variantItem = this.catalogStore.getItemBySlug(variant);
      if (variantItem?.primaryId) {
        return variantItem.primaryId;
      }
    }

    // 5. Check of titel een aflevering / ondertitel bevat (bijv. "Wie is de Mol? - Aflevering 3" of "NOS Journaal: Extra uitzending")
    const titleParts = title.split(/[-–—:]/);
    if (titleParts.length > 1) {
      const mainPartSlug = slugifyTitle(titleParts[0]);
      if (mainPartSlug && mainPartSlug.length >= 3) {
        const mainItem = this.catalogStore.getItemBySlug(mainPartSlug);
        if (mainItem?.primaryId) {
          return mainItem.primaryId;
        }

        // Check alias voor main part
        if (this.overridesConfig.aliases && this.overridesConfig.aliases[mainPartSlug]) {
          const aliasTarget = this.overridesConfig.aliases[mainPartSlug];
          const aliasItem = this.catalogStore.getItemBySlug(aliasTarget);
          if (aliasItem?.primaryId) {
            return aliasItem.primaryId;
          }
        }
      }
    }

    // 6. Fuzzy / Prefix match over catalogus items
    for (const item of this.catalogStore.getAllItems()) {
      if (!item.primaryId) continue;

      const itemSlug = item.slug.replace(/-(?:gemist|kijken|terugkijken)$/, '');
      if (itemSlug.length >= 4) {
        if (slug === itemSlug || slug.startsWith(`${itemSlug}-`) || itemSlug.startsWith(`${slug}-`)) {
          return item.primaryId;
        }
      }
    }

    return null;
  }

  /**
   * Verrijkt een lijst van programma's met NLZIET ID's en berekent statistieken.
   */
  public enrichProgrammes(programmes: Programme[], channels: Channel[] = []): EnrichmentStats {
    let enrichedCount = 0;
    const channelMap = new Map<string, Channel>(channels.map((c) => [c.id, c]));
    const matchedSlugs: Record<string, number> = {};

    for (const p of programmes) {
      const ch = channelMap.get(p.channelId);
      const matchedId = this.matchProgramme(p, ch);
      if (matchedId) {
        p.nlzietId = matchedId;
        enrichedCount++;
        const s = slugifyTitle(p.title);
        matchedSlugs[s] = (matchedSlugs[s] || 0) + 1;
      }
    }

    const total = programmes.length;
    const enrichmentRate = total > 0 ? enrichedCount / total : 0;

    return {
      totalProgrammes: total,
      enrichedProgrammes: enrichedCount,
      enrichmentRate,
      matchedSlugs,
    };
  }
}
