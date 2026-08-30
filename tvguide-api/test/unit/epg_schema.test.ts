import { describe, it, expect } from 'vitest';
import { parseNlzietEpgResponse } from '../../src/enrichment/nlziet/epg-schema.js';

describe('NLZIET EPG Schema Validation', () => {
  it('successfully parses a valid NLZIET EPG envelope', () => {
    const validData = {
      data: [
        {
          channel: {
            content: {
              id: 'npo1',
              title: 'NPO 1',
            },
          },
          programLocations: [
            {
              content: {
                contentItemId: 'pXZD1nmyCkSuW_pB1ylCQg',
                assetId: '108C33FB3A16FDFCE5E88B43871AC6BA',
                title: 'NOS Journaal',
                startAt: '2026-08-30T13:00:00+02:00',
                endAt: '2026-08-30T13:20:00+02:00',
                isReplayAllowed: true,
                isRestartAllowed: true,
                seriesId: 'NDyPpImZGEm6Q1zShDf-7Q',
              },
            },
          ],
        },
      ],
    };

    const parsed = parseNlzietEpgResponse(validData);
    expect(parsed.data.length).toBe(1);
    expect(parsed.data[0].channel.content.id).toBe('npo1');
    expect(parsed.data[0].programLocations.length).toBe(1);
    expect(parsed.data[0].programLocations[0].content.contentItemId).toBe('pXZD1nmyCkSuW_pB1ylCQg');
    expect(parsed.data[0].programLocations[0].content.assetId).toBe('108C33FB3A16FDFCE5E88B43871AC6BA');
    expect(parsed.data[0].programLocations[0].content.isReplayAllowed).toBe(true);
    expect(parsed.data[0].programLocations[0].content.isRestartAllowed).toBe(true);
  });

  it('rejects invalid contentItemId (not 22 characters base64url)', () => {
    const invalidData = {
      data: [
        {
          channel: { content: { id: 'npo1' } },
          programLocations: [
            {
              content: {
                contentItemId: 'too-short',
                assetId: '108C33FB3A16FDFCE5E88B43871AC6BA',
                title: 'NOS Journaal',
                startAt: '2026-08-30T13:00:00+02:00',
                endAt: '2026-08-30T13:20:00+02:00',
              },
            },
          ],
        },
      ],
    };

    expect(() => parseNlzietEpgResponse(invalidData)).toThrow();
  });

  it('rejects invalid assetId (not 32 hex chars)', () => {
    const invalidData = {
      data: [
        {
          channel: { content: { id: 'npo1' } },
          programLocations: [
            {
              content: {
                contentItemId: 'pXZD1nmyCkSuW_pB1ylCQg',
                assetId: 'NOT-A-HEX-ID-TOO-SHORT',
                title: 'NOS Journaal',
                startAt: '2026-08-30T13:00:00+02:00',
                endAt: '2026-08-30T13:20:00+02:00',
              },
            },
          ],
        },
      ],
    };

    expect(() => parseNlzietEpgResponse(invalidData)).toThrow();
  });

  it('rejects invalid date strings', () => {
    const invalidData = {
      data: [
        {
          channel: { content: { id: 'npo1' } },
          programLocations: [
            {
              content: {
                contentItemId: 'pXZD1nmyCkSuW_pB1ylCQg',
                assetId: '108C33FB3A16FDFCE5E88B43871AC6BA',
                title: 'NOS Journaal',
                startAt: 'invalid-date',
                endAt: '2026-08-30T13:20:00+02:00',
              },
            },
          ],
        },
      ],
    };

    expect(() => parseNlzietEpgResponse(invalidData)).toThrow();
  });
});
