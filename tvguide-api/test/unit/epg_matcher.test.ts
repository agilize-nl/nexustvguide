import { describe, it, expect } from 'vitest';
import { NlzietEpgMatcher, normalizeEpgTitle } from '../../src/enrichment/nlziet/epg-matcher.js';
import type { Programme } from '../../src/domain/programme.js';
import type { Channel } from '../../src/domain/channel.js';
import type { NlzietEpgResponse, NlzietEpgContent } from '../../src/enrichment/nlziet/epg-schema.js';

describe('NlzietEpgMatcher', () => {
  const matcher = new NlzietEpgMatcher();

  const channels: Channel[] = [
    { id: 'npo1', sourceId: '1', name: 'NPO 1', logoUrl: null, inNlziet: true, nlzietSlug: 'npo-1', nlzietChannelId: 'npo1', sortOrder: 1 },
    { id: 'vrtcanvas', sourceId: '6', name: 'VRT Canvas', logoUrl: null, inNlziet: true, nlzietSlug: 'vrt-canvas', nlzietChannelId: 'canvas', sortOrder: 14 },
    { id: 'discovery', sourceId: '29', name: 'Discovery', logoUrl: null, inNlziet: false, nlzietSlug: null, nlzietChannelId: null, sortOrder: 21 },
  ];

  function createProg(title: string, startIso: string, endIso: string, channelId = 'npo1'): Programme {
    return {
      id: '1001',
      channelId,
      title,
      start: startIso,
      end: endIso,
      description: null,
      imageUrl: null,
      genre: null,
      isLive: false,
      isRerun: false,
      isPremiere: false,
      ageRating: null,
    };
  }

  function createEpgContent(title: string, startIso: string, endIso: string, isReplay = true, isRestart = true): NlzietEpgContent {
    return {
      contentItemId: 'pXZD1nmyCkSuW_pB1ylCQg',
      assetId: '108C33FB3A16FDFCE5E88B43871AC6BA',
      title,
      startAt: startIso,
      endAt: endIso,
      isReplayAllowed: isReplay,
      isRestartAllowed: isRestart,
    };
  }

  it('normalizes titles correctly', () => {
    expect(normalizeEpgTitle('NOS Journaal')).toBe('journaal');
    expect(normalizeEpgTitle('AVROTROS: Radar')).toBe('radar');
    expect(normalizeEpgTitle('B&B Vol Liefde - Afl. 34')).toBe('b b vol liefde');
    expect(normalizeEpgTitle('Studio Sport Live: WK Roeien')).toBe('studio sport live wk roeien');
    expect(normalizeEpgTitle('Crème de la crème')).toBe('creme de la creme');
  });

  it('matches exact title and timing within 6 minutes start and 10 minutes duration tolerance', () => {
    const prog = createProg('NOS Journaal', '2026-08-30T18:00:00.000Z', '2026-08-30T18:30:00.000Z');
    // EPG start is 2 minuten later (18:02) en duur is 28 min ipv 30 min (2 min verschil)
    const epgItems = [createEpgContent('Journaal', '2026-08-30T20:02:00+02:00', '2026-08-30T20:30:00+02:00')];

    const result = matcher.matchProgramme(prog, epgItems, 'npo1');
    expect(result.target).not.toBeNull();
    expect(result.target?.contentItemId).toBe('pXZD1nmyCkSuW_pB1ylCQg');
    expect(result.target?.assetId).toBe('108C33FB3A16FDFCE5E88B43871AC6BA');
    expect(result.target?.channelId).toBe('npo1');
    expect(result.target?.isReplayAllowed).toBe(true);
  });

  it('rejects match when start time difference is greater than 6 minutes', () => {
    const prog = createProg('NOS Journaal', '2026-08-30T18:00:00.000Z', '2026-08-30T18:30:00.000Z');
    // EPG start is 7 minuten later (18:07 UTC / 20:07 +02:00)
    const epgItems = [createEpgContent('Journaal', '2026-08-30T20:07:00+02:00', '2026-08-30T20:37:00+02:00')];

    const result = matcher.matchProgramme(prog, epgItems, 'npo1');
    expect(result.target).toBeNull();
    expect(result.isTimingOrTitleMismatch).toBe(true);
  });

  it('rejects match when duration difference is greater than 10 minutes', () => {
    const prog = createProg('NOS Journaal', '2026-08-30T18:00:00.000Z', '2026-08-30T18:30:00.000Z');
    // Start is gelijk, maar duur is 45 minuten ipv 30 minuten (15 min verschil)
    const epgItems = [createEpgContent('Journaal', '2026-08-30T20:00:00+02:00', '2026-08-30T20:45:00+02:00')];

    const result = matcher.matchProgramme(prog, epgItems, 'npo1');
    expect(result.target).toBeNull();
    expect(result.isTimingOrTitleMismatch).toBe(true);
  });

  it('rejects ambiguous candidates when multiple items match timing and title', () => {
    const prog = createProg('Zin in Zappelin', '2026-08-30T07:15:00.000Z', '2026-08-30T07:20:00.000Z');
    const epgItems = [
      createEpgContent('Zin in Zappelin', '2026-08-30T09:14:00+02:00', '2026-08-30T09:19:00+02:00'),
      {
        contentItemId: 'a2ZDam4e8UKeXODFs9nJjA',
        assetId: '921260CF2892B3BD8A3B0BBAB40B4B5B',
        title: 'Zin in Zappelin',
        startAt: '2026-08-30T09:18:00+02:00',
        endAt: '2026-08-30T09:23:00+02:00',
        isReplayAllowed: true,
        isRestartAllowed: true,
      },
    ];

    const result = matcher.matchProgramme(prog, epgItems, 'npo1');
    expect(result.target).toBeNull();
    expect(result.isAmbiguous).toBe(true);
  });

  it('enriches a list of programmes and calculates enrichment statistics', () => {
    const progs = [
      createProg('NOS Journaal', '2026-08-30T18:00:00.000Z', '2026-08-30T18:30:00.000Z', 'npo1'),
      createProg('Terzake', '2026-08-30T18:00:00.000Z', '2026-08-30T18:35:00.000Z', 'vrtcanvas'),
      createProg('Shark Week', '2026-08-30T18:00:00.000Z', '2026-08-30T19:00:00.000Z', 'discovery'),
    ];

    const epgResponse: NlzietEpgResponse = {
      data: [
        {
          channel: { content: { id: 'npo1' } },
          programLocations: [
            { content: createEpgContent('Journaal', '2026-08-30T20:00:00+02:00', '2026-08-30T20:30:00+02:00') },
          ],
        },
        {
          channel: { content: { id: 'canvas' } },
          programLocations: [
            {
              content: {
                contentItemId: 'canvasItem123456789012',
                assetId: 'AABBCCDDEEFF00112233445566778899',
                title: 'Terzake',
                startAt: '2026-08-30T20:00:00+02:00',
                endAt: '2026-08-30T20:35:00+02:00',
                isReplayAllowed: false,
                isRestartAllowed: true,
              },
            },
          ],
        },
      ],
    };

    const stats = matcher.enrichProgrammes(progs, epgResponse, channels);

    expect(stats.totalProgrammes).toBe(3);
    expect(stats.epgEligibleProgrammes).toBe(2); // discovery is not inNlziet
    expect(stats.exactTargets).toBe(2);
    expect(stats.replayAllowedTargets).toBe(1); // NPO 1 has isReplayAllowed: true, Canvas has false

    expect(progs[0].nlziet?.contentItemId).toBe('pXZD1nmyCkSuW_pB1ylCQg');
    expect(progs[0].nlziet?.channelId).toBe('npo1');
    expect(progs[0].nlzietId).toBeNull(); // Legacy nlzietId is explicitly cleared

    expect(progs[1].nlziet?.contentItemId).toBe('canvasItem123456789012');
    expect(progs[1].nlziet?.channelId).toBe('canvas');
    expect(progs[1].nlziet?.isReplayAllowed).toBe(false);

    expect(progs[2].nlziet).toBeNull();
  });
});
