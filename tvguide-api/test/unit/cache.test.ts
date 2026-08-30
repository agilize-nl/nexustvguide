import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { SnapshotStore } from '../../src/store/cache.js';
import type { DaySnapshot } from '../../src/domain/guide.js';

describe('SnapshotStore (Atomic Disk Cache)', () => {
  let tempDir: string;
  let store: SnapshotStore;

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tvguide-test-'));
    store = new SnapshotStore(tempDir);
  });

  afterEach(() => {
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  it('saves snapshot atomically to disk and in-memory', async () => {
    const snapshot: DaySnapshot = {
      date: '2026-08-30',
      timeZone: 'Europe/Amsterdam',
      from: '2026-08-29T22:00:00.000Z',
      to: '2026-08-30T22:00:00.000Z',
      sourceFetchedAt: '2026-08-30T00:05:00.000Z',
      publishedAt: '2026-08-30T00:05:01.000Z',
      channels: [
        { id: 'npo1', sourceId: '1', name: 'NPO 1', logoUrl: null, inNlziet: true, nlzietSlug: null, sortOrder: 1 },
      ],
      programmes: [
        {
          id: '218748382',
          channelId: 'npo1',
          title: 'Nederland in beweging',
          start: '2026-08-30T04:55:00.000Z',
          end: '2026-08-30T05:15:00.000Z',
          description: 'Gym',
          imageUrl: null,
          genre: 'Gymnastiek',
          isLive: false,
          isRerun: true,
          isPremiere: false,
          ageRating: null,
        },
      ],
    };

    await store.saveSnapshot(snapshot);

    // Verify in-memory
    expect(store.getSnapshot('2026-08-30')).toBeDefined();

    // Verify disk file
    const diskPath = path.join(tempDir, 'guide-2026-08-30.json');
    expect(fs.existsSync(diskPath)).toBe(true);

    const onDisk = JSON.parse(fs.readFileSync(diskPath, 'utf-8'));
    expect(onDisk.date).toBe('2026-08-30');
    expect(onDisk.programmes.length).toBe(1);
  });

  it('recovers existing snapshots on loadFromDisk() without internet', async () => {
    const snapshot: DaySnapshot = {
      date: '2026-08-30',
      timeZone: 'Europe/Amsterdam',
      from: '2026-08-29T22:00:00.000Z',
      to: '2026-08-30T22:00:00.000Z',
      sourceFetchedAt: '2026-08-30T00:05:00.000Z',
      publishedAt: '2026-08-30T00:05:01.000Z',
      channels: [],
      programmes: [],
    };

    // Schrijf direct een JSON bestand op disk
    fs.writeFileSync(path.join(tempDir, 'guide-2026-08-30.json'), JSON.stringify(snapshot));

    const newStore = new SnapshotStore(tempDir);
    const count = await newStore.loadFromDisk();

    expect(count).toBe(1);
    expect(newStore.getSnapshot('2026-08-30')).toBeDefined();
  });

  it('generates consistent ETag for given dates', () => {
    const etag1 = store.getETag(['2026-08-30', '2026-08-31']);
    const etag2 = store.getETag(['2026-08-30', '2026-08-31']);
    expect(etag1).toBe(etag2);
    expect(etag1.startsWith('W/"')).toBe(true);
  });
});
