import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { buildApp } from '../../src/app.js';
import type { DaySnapshot } from '../../src/domain/guide.js';

describe('API Contract Tests', () => {
  let tempDir: string;
  let channelsPath: string;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tvguide-api-test-'));
    channelsPath = path.join(tempDir, 'channels.json');

    const sampleChannels = [
      { id: 'npo1', sourceId: '1', name: 'NPO 1', logoUrl: null, inNlziet: true, nlzietSlug: 'npo-1', nlzietChannelId: 'npo1', sortOrder: 1 },
      { id: 'npo2', sourceId: '2', name: 'NPO 2', logoUrl: null, inNlziet: true, nlzietSlug: 'npo-2', nlzietChannelId: 'npo2', sortOrder: 2 },
      { id: 'discovery', sourceId: '29', name: 'Discovery', logoUrl: null, inNlziet: false, nlzietSlug: null, nlzietChannelId: null, sortOrder: 3 },
    ];
    fs.writeFileSync(channelsPath, JSON.stringify(sampleChannels));
  });

  afterEach(() => {
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  it('handles /health and /ready properly', async () => {
    const { app, engine } = buildApp({
      dataDir: tempDir,
      channelsConfigPath: channelsPath,
    });
    engine.loadChannelsConfig();

    // 1. Health is always 200
    const resHealth = await app.inject({ method: 'GET', url: '/health' });
    expect(resHealth.statusCode).toBe(200);
    const healthJson = resHealth.json();
    expect(healthJson.loadedChannelCount).toBe(2); // Only inNlziet: true

    // 2. Ready is 503 when no snapshot exists
    const resReadyEmpty = await app.inject({ method: 'GET', url: '/ready' });
    expect(resReadyEmpty.statusCode).toBe(503);
  });

  it('GET /api/v1/channels returns active inNlziet channels sorted by sortOrder', async () => {
    const { app, engine } = buildApp({
      dataDir: tempDir,
      channelsConfigPath: channelsPath,
    });
    engine.loadChannelsConfig();

    const res = await app.inject({ method: 'GET', url: '/api/v1/channels' });
    expect(res.statusCode).toBe(200);
    const channels = res.json();
    expect(channels.length).toBe(2);
    expect(channels[0].id).toBe('npo1');
    expect(channels[0].nlzietChannelId).toBe('npo1');
    expect(channels[1].id).toBe('npo2');
  });

  it('GET /api/v1/guide?date=YYYY-MM-DD returns guide with exact nlziet target and handles ETag/304', async () => {
    const { app, store, engine } = buildApp({
      dataDir: tempDir,
      channelsConfigPath: channelsPath,
    });
    const channels = engine.loadChannelsConfig();

    const snapshot: DaySnapshot = {
      date: '2026-08-30',
      timeZone: 'Europe/Amsterdam',
      from: '2026-08-29T22:00:00.000Z',
      to: '2026-08-30T22:00:00.000Z',
      sourceFetchedAt: '2026-08-30T00:05:00.000Z',
      publishedAt: '2026-08-30T00:05:01.000Z',
      channels,
      programmes: [
        {
          id: '218748382',
          channelId: 'npo1',
          title: 'Wie is de Mol?',
          start: '2026-08-30T18:30:00.000Z',
          end: '2026-08-30T19:30:00.000Z',
          description: 'Spelshow',
          imageUrl: null,
          genre: 'Amusement',
          isLive: false,
          isRerun: false,
          isPremiere: true,
          ageRating: '12',
          nlziet: {
            kind: 'replay',
            contentItemId: 'pXZD1nmyCkSuW_pB1ylCQg',
            assetId: '108C33FB3A16FDFCE5E88B43871AC6BA',
            channelId: 'npo1',
            isReplayAllowed: true,
            isRestartAllowed: true,
          },
          nlzietId: null,
        },
      ],
    };
    await store.saveSnapshot(snapshot);

    // Request guide
    const res = await app.inject({ method: 'GET', url: '/api/v1/guide?date=2026-08-30' });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.meta.timeZone).toBe('Europe/Amsterdam');
    expect(body.meta.date).toBe('2026-08-30');
    expect(body.programmes.length).toBe(1);
    expect(body.programmes[0].title).toBe('Wie is de Mol?');
    expect(body.programmes[0].nlziet).toEqual({
      kind: 'replay',
      contentItemId: 'pXZD1nmyCkSuW_pB1ylCQg',
      assetId: '108C33FB3A16FDFCE5E88B43871AC6BA',
      channelId: 'npo1',
      isReplayAllowed: true,
      isRestartAllowed: true,
    });

    const etag = res.headers['etag'];
    expect(etag).toBeDefined();

    // Test 304 Not Modified
    const res304 = await app.inject({
      method: 'GET',
      url: '/api/v1/guide?date=2026-08-30',
      headers: { 'if-none-match': etag as string },
    });
    expect(res304.statusCode).toBe(304);
    expect(res304.body).toBe('');
  });

  it('GET /api/v1/guide validates query parameters and spans', async () => {
    const { app, engine } = buildApp({
      dataDir: tempDir,
      channelsConfigPath: channelsPath,
    });
    engine.loadChannelsConfig();

    // Missing params
    const res400 = await app.inject({ method: 'GET', url: '/api/v1/guide' });
    expect(res400.statusCode).toBe(400);
    expect(res400.json().error.code).toBe('INVALID_QUERY_PARAM');

    // Invalid date format
    const resInvalidDate = await app.inject({ method: 'GET', url: '/api/v1/guide?date=30-08-2026' });
    expect(resInvalidDate.statusCode).toBe(400);

    // Span > 14 days
    const resSpan = await app.inject({
      method: 'GET',
      url: '/api/v1/guide?from=2026-08-01T00:00:00Z&to=2026-08-25T00:00:00Z',
    });
    expect(resSpan.statusCode).toBe(422);
    expect(resSpan.json().error.code).toBe('DATE_OUT_OF_RANGE');

    // Regressie: een kale datum in from/to werd door new Date() geaccepteerd,
    // waarna Temporal.Instant.from() verderop gooide -> 500 op een invoerfout.
    // Dit hoort een nette 400 te zijn.
    const resBareDate = await app.inject({
      method: 'GET',
      url: '/api/v1/guide?from=2026-08-30&to=2026-08-31',
    });
    expect(resBareDate.statusCode).toBe(400);
    expect(resBareDate.json().error.code).toBe('INVALID_QUERY_PARAM');

    // Volledige ISO-timestamps met offset blijven wel geldig.
    const resOffset = await app.inject({
      method: 'GET',
      url: '/api/v1/guide?from=2026-08-30T00:00:00%2B02:00&to=2026-08-31T00:00:00%2B02:00',
    });
    expect(resOffset.statusCode).not.toBe(400);
    expect(resOffset.statusCode).not.toBe(500);

    // Onzin blijft afgewezen.
    const resGarbage = await app.inject({
      method: 'GET',
      url: '/api/v1/guide?from=onzin&to=ooknietgeldig',
    });
    expect(resGarbage.statusCode).toBe(400);
  });

  it('GET /xmltv.xml generates valid XMLTV XML with DTD header and escaped content', async () => {
    const { app, store, engine } = buildApp({
      dataDir: tempDir,
      channelsConfigPath: channelsPath,
    });
    const channels = engine.loadChannelsConfig();

    const snapshot: DaySnapshot = {
      date: '2026-08-30',
      timeZone: 'Europe/Amsterdam',
      from: '2026-08-29T22:00:00.000Z',
      to: '2026-08-30T22:00:00.000Z',
      sourceFetchedAt: '2026-08-30T00:05:00.000Z',
      publishedAt: '2026-08-30T00:05:01.000Z',
      channels,
      programmes: [
        {
          id: '218748382',
          channelId: 'npo1',
          title: 'Nederland in beweging <Special> & Sport',
          start: '2026-08-30T04:55:00.000Z',
          end: '2026-08-30T05:15:00.000Z',
          description: 'Beweeg "veilig" & gezond',
          imageUrl: 'https://tvgidsassets.nl/img.jpg?a=1&b=2',
          genre: 'Sport & Gym',
          isLive: false,
          isRerun: true,
          isPremiere: false,
          ageRating: 'AL',
        },
      ],
    };
    await store.saveSnapshot(snapshot);

    const res = await app.inject({ method: 'GET', url: '/xmltv.xml?days=1' });
    expect(res.statusCode).toBe(200);
    expect(res.headers['content-type']).toBe('application/xml; charset=utf-8');

    const xml = res.body;
    expect(xml).toContain('<!DOCTYPE tv SYSTEM "xmltv.dtd">');
    expect(xml).toContain('<channel id="npo1">');
    expect(xml).toContain('<display-name>NPO 1</display-name>');
    expect(xml).toContain('start="20260830065500 +0200"');
    // Verify XML escaping
    expect(xml).toContain('&lt;Special&gt; &amp; Sport');
    expect(xml).toContain('&quot;veilig&quot; &amp; gezond');
    expect(xml).toContain('https://tvgidsassets.nl/img.jpg?a=1&amp;b=2');
    expect(xml).toContain('<previously-shown />');
    expect(xml).toContain('<value>AL</value>');
  });
});
