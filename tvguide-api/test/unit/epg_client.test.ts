import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NlzietEpgClient } from '../../src/enrichment/nlziet/epg-client.js';

describe('NlzietEpgClient', () => {
  const sampleEpgResponse = {
    data: [
      {
        channel: { content: { id: 'npo1' } },
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
            },
          },
        ],
      },
    ],
  };

  it('builds the correct URL with repeated channel query parameters and parses response', async () => {
    let requestedUrl = '';
    const mockFetch = vi.fn(async (url: string) => {
      requestedUrl = url;
      return {
        ok: true,
        status: 200,
        json: async () => sampleEpgResponse,
      } as unknown as Response;
    });

    const client = new NlzietEpgClient({
      fetchFn: mockFetch as unknown as typeof fetch,
      nowFn: () => new Date('2026-08-30T12:00:00Z'),
    });

    const result = await client.fetchEpg('2026-08-30', ['npo1', 'rtl4', 'canvas']);

    expect(mockFetch).toHaveBeenCalledTimes(1);
    const parsedUrl = new URL(requestedUrl);
    expect(parsedUrl.searchParams.get('date')).toBe('2026-08-30');
    expect(parsedUrl.searchParams.getAll('channel')).toEqual(['canvas', 'npo1', 'rtl4']);
    expect(result.data.length).toBe(1);
    expect(result.data[0].channel.content.id).toBe('npo1');
  });

  it('caches valid responses according to date TTL', async () => {
    let callCount = 0;
    const mockFetch = vi.fn(async () => {
      callCount++;
      return {
        ok: true,
        status: 200,
        json: async () => sampleEpgResponse,
      } as unknown as Response;
    });

    let fakeNow = new Date('2026-08-30T12:00:00Z');
    const client = new NlzietEpgClient({
      fetchFn: mockFetch as unknown as typeof fetch,
      nowFn: () => fakeNow,
    });

    // 1e aanroep: netwerk
    await client.fetchEpg('2026-08-30', ['npo1']);
    expect(callCount).toBe(1);

    // 2e aanroep binnen 10 min: cache hit
    await client.fetchEpg('2026-08-30', ['npo1']);
    expect(callCount).toBe(1);

    // Na 11 minuten: cache verlopen, nieuwe netwerk call
    fakeNow = new Date('2026-08-30T12:11:00Z');
    await client.fetchEpg('2026-08-30', ['npo1']);
    expect(callCount).toBe(2);
  });

  it('does not call upstream when date is outside [-7, +7] window', async () => {
    const mockFetch = vi.fn();
    const client = new NlzietEpgClient({
      fetchFn: mockFetch as unknown as typeof fetch,
      nowFn: () => new Date('2026-08-30T12:00:00Z'),
    });

    // 2026-08-10 is -20 dagen terug
    const resultPast = await client.fetchEpg('2026-08-10', ['npo1']);
    expect(resultPast).toEqual({ data: [] });
    expect(mockFetch).not.toHaveBeenCalled();

    // 2026-09-20 is +21 dagen vooruit
    const resultFuture = await client.fetchEpg('2026-09-20', ['npo1']);
    expect(resultFuture).toEqual({ data: [] });
    expect(mockFetch).not.toHaveBeenCalled();
  });

  it('retries on HTTP 500 error up to maxRetries', async () => {
    let callCount = 0;
    const mockFetch = vi.fn(async () => {
      callCount++;
      if (callCount < 3) {
        return {
          ok: false,
          status: 503,
          statusText: 'Service Unavailable',
        } as unknown as Response;
      }
      return {
        ok: true,
        status: 200,
        json: async () => sampleEpgResponse,
      } as unknown as Response;
    });

    const client = new NlzietEpgClient({
      fetchFn: mockFetch as unknown as typeof fetch,
      maxRetries: 2,
      nowFn: () => new Date('2026-08-30T12:00:00Z'),
    });

    const res = await client.fetchEpg('2026-08-30', ['npo1']);
    expect(callCount).toBe(3);
    expect(res.data.length).toBe(1);
  });
});
