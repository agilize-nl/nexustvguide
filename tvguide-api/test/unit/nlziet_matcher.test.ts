import { describe, it, expect, beforeEach } from 'vitest';
import path from 'node:path';
import { normalizeDutchTitle, slugifyTitle } from '../../src/enrichment/nlziet/normalizer.js';
import { NlzietCatalogStore } from '../../src/enrichment/nlziet/catalog.js';
import { NlzietMatcher } from '../../src/enrichment/nlziet/matcher.js';
import type { Programme } from '../../src/domain/programme.js';
import type { Channel } from '../../src/domain/channel.js';

describe('NLZIET Normalizer', () => {
  it('correctly normalizes titles with ampersands and special characters', () => {
    expect(slugifyTitle('B&B Vol Liefde')).toBe('b-en-b-vol-liefde');
    expect(slugifyTitle('Bed & Breakfast')).toBe('bed-en-breakfast');
    expect(slugifyTitle('Koffie + Cake')).toBe('koffie-en-cake');
  });

  it('strips diacritics and accents', () => {
    expect(slugifyTitle('Krabbé zoekt Chagall')).toBe('krabbe-zoekt-chagall');
    expect(slugifyTitle('Mocro Maffia: Mélange')).toBe('mocro-maffia-melange');
    expect(slugifyTitle('Château Meiland')).toBe('chateau-meiland');
  });

  it('removes broadcast meta tags and parentheses', () => {
    expect(slugifyTitle('MAX Vakantieman (herhaling)')).toBe('max-vakantieman');
    expect(slugifyTitle('NOS Journaal (live)')).toBe('nos-journaal');
    expect(slugifyTitle('Wie is de Mol? [première]')).toBe('wie-is-de-mol');
    expect(slugifyTitle('Flikken Maastricht (afl. 4)')).toBe('flikken-maastricht');
  });

  it('removes time suffixes and extra phrases', () => {
    expect(slugifyTitle('NOS Journaal 20:00')).toBe('nos-journaal');
    expect(slugifyTitle('RTL Nieuws 19:30 uur')).toBe('rtl-nieuws');
    expect(slugifyTitle('Jeugdjournaal met gebarentaal')).toBe('jeugdjournaal');
    expect(slugifyTitle('NOS Journaal in makkelijke taal')).toBe('nos-journaal');
  });

  it('handles empty or blank inputs gracefully', () => {
    expect(slugifyTitle('')).toBe('');
    expect(normalizeDutchTitle('')).toBe('');
  });
});

describe('NLZIET Catalog Store', () => {
  it('loads seed data from config directory', () => {
    const seedPath = path.resolve(__dirname, '../../config/nlziet_catalog_seed.json');
    const catalog = new NlzietCatalogStore({ seedFilePath: seedPath, cacheFilePath: '/tmp/test-nlziet-cache.json' });

    expect(catalog.getItemCount()).toBeGreaterThan(10);
    const widm = catalog.getItemBySlug('wie-is-de-mol');
    expect(widm).toBeDefined();
    expect(widm?.primaryId).toBe('pDNA4tFqJU6JzlVKbBLGtQ');
    expect(widm?.vodIds.length).toBeGreaterThan(0);
  });

  it('returns undefined for non-existent slugs', () => {
    const catalog = new NlzietCatalogStore({ cacheFilePath: '/tmp/test-nlziet-cache.json' });
    expect(catalog.getItemBySlug('onbekend-programma-xyz')).toBeUndefined();
  });
});

describe('NLZIET Matcher Engine', () => {
  let matcher: NlzietMatcher;
  let sampleChannels: Channel[];

  beforeEach(() => {
    const seedPath = path.resolve(__dirname, '../../config/nlziet_catalog_seed.json');
    const overridesPath = path.resolve(__dirname, '../../config/nlziet_overrides.json');
    const catalogStore = new NlzietCatalogStore({
      seedFilePath: seedPath,
      cacheFilePath: '/tmp/test-nlziet-cache.json',
    });
    matcher = new NlzietMatcher({
      catalogStore,
      overridesFilePath: overridesPath,
    });

    sampleChannels = [
      { id: 'npo1', sourceId: '1', name: 'NPO 1', logoUrl: null, inNlziet: true, nlzietSlug: 'npo-1', sortOrder: 1 },
      { id: 'rtl4', sourceId: '4', name: 'RTL 4', logoUrl: null, inNlziet: true, nlzietSlug: 'rtl-4', sortOrder: 4 },
      { id: 'sbs6', sourceId: '36', name: 'SBS 6', logoUrl: null, inNlziet: true, nlzietSlug: 'sbs-6', sortOrder: 6 },
    ];
  });

  const createProg = (title: string, channelId = 'npo1', id = '100'): Programme => ({
    id,
    channelId,
    title,
    start: '2026-08-30T18:00:00.000Z',
    end: '2026-08-30T18:30:00.000Z',
    description: 'Test beschrijving',
    imageUrl: null,
    genre: 'Amusement',
    isLive: false,
    isRerun: false,
    isPremiere: false,
    ageRating: null,
    nlzietId: null,
  });

  it('matches exact title to seed catalog', () => {
    const prog = createProg('Wie is de Mol?');
    const id = matcher.matchProgramme(prog);
    expect(id).toBe('pDNA4tFqJU6JzlVKbBLGtQ');
  });

  it('matches B&B Vol Liefde with normalized ampersand', () => {
    const prog = createProg('B&B Vol Liefde', 'rtl4');
    const id = matcher.matchProgramme(prog);
    expect(id).toBe('Xk6gZ6CVQEulAN6plVOlEA');
  });

  it('matches Vandaag Inside on SBS 6', () => {
    const prog = createProg('Vandaag Inside', 'sbs6');
    const id = matcher.matchProgramme(prog);
    expect(id).toBe('4AcLsp3y30ygoRsNgjnBjQ');
  });

  it('matches title with timestamp via regex rules or normalization', () => {
    const prog = createProg('Studio Sport 20:00');
    const id = matcher.matchProgramme(prog);
    expect(id).toBe('RVvAjvdvf0e_9SAK3OWaUg');
  });

  it('matches titles with subtitle or episode indicators', () => {
    const prog = createProg('Wie is de Mol? - Aflevering 4: Het complot');
    const id = matcher.matchProgramme(prog);
    expect(id).toBe('pDNA4tFqJU6JzlVKbBLGtQ');
  });

  it('matches alias rules', () => {
    const prog = createProg('Studio Sport');
    const id = matcher.matchProgramme(prog);
    expect(id).toBe('RVvAjvdvf0e_9SAK3OWaUg');
  });

  it('returns null for unmatchable local or regional programs', () => {
    const prog = createProg('Omroep Flevoland Nieuws Update');
    const id = matcher.matchProgramme(prog);
    expect(id).toBeNull();
  });

  it('preserves existing nlzietId if already set and valid', () => {
    const prog = createProg('Onbekend Programma');
    prog.nlzietId = 'pDNA4tFqJU6JzlVKbBLGtQ';
    const id = matcher.matchProgramme(prog);
    expect(id).toBe('pDNA4tFqJU6JzlVKbBLGtQ');
  });

  it('enriches a batch of programmes and produces correct statistics', () => {
    const programmes = [
      createProg('Wie is de Mol?', 'npo1', '1'),
      createProg('B&B Vol Liefde', 'rtl4', '2'),
      createProg('Vandaag Inside', 'sbs6', '3'),
      createProg('Onbekend Lokale Show', 'npo1', '4'),
    ];

    const stats = matcher.enrichProgrammes(programmes, sampleChannels);
    expect(stats.totalProgrammes).toBe(4);
    expect(stats.enrichedProgrammes).toBe(3);
    expect(stats.enrichmentRate).toBe(0.75);

    expect(programmes[0].nlzietId).toBe('pDNA4tFqJU6JzlVKbBLGtQ');
    expect(programmes[1].nlzietId).toBe('Xk6gZ6CVQEulAN6plVOlEA');
    expect(programmes[2].nlzietId).toBe('4AcLsp3y30ygoRsNgjnBjQ');
    expect(programmes[3].nlzietId).toBeNull();
  });
});
