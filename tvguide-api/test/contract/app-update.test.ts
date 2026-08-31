import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import os from 'node:os';
import { buildApp } from '../../src/app.js';

describe('App Update API Contract Tests', () => {
  let tempReleasesDir: string;
  let tempSnapshotsDir: string;

  beforeEach(() => {
    tempReleasesDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nexus-releases-'));
    tempSnapshotsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nexus-snapshots-'));
  });

  afterEach(() => {
    fs.rmSync(tempReleasesDir, { recursive: true, force: true });
    fs.rmSync(tempSnapshotsDir, { recursive: true, force: true });
  });

  it('returns 503 when no version.json exists', async () => {
    const { app } = buildApp({
      releasesDir: tempReleasesDir,
      dataDir: tempSnapshotsDir,
    });

    const res = await app.inject({
      method: 'GET',
      url: '/api/v1/app/version',
    });

    expect(res.statusCode).toBe(503);
    expect(res.headers['cache-control']).toBe('no-store');
    const json = JSON.parse(res.body);
    expect(json.error).toBe('RELEASE_UNAVAILABLE');
  });

  it('returns 503 when manifest schema is invalid', async () => {
    const invalidManifest = {
      schemaVersion: 999, // Onbekende versie
      applicationId: 'com.other.app',
      versionCode: -1,
    };
    fs.writeFileSync(path.join(tempReleasesDir, 'version.json'), JSON.stringify(invalidManifest), 'utf8');

    const { app } = buildApp({
      releasesDir: tempReleasesDir,
      dataDir: tempSnapshotsDir,
    });

    const res = await app.inject({
      method: 'GET',
      url: '/api/v1/app/version',
    });

    expect(res.statusCode).toBe(503);
    expect(JSON.parse(res.body).error).toBe('RELEASE_UNAVAILABLE');
  });

  it('returns 503 when APK file is missing on disk', async () => {
    const manifest = {
      schemaVersion: 1,
      applicationId: 'com.nexustvguide.app',
      versionCode: 2,
      versionName: '0.6.0',
      releaseNotes: 'Test release',
      downloadPath: '/api/v1/app/download/nexus-tv-guide-0.6.0.apk',
      sha256: '9a4f2f9f5b66f6b0f4f33dc51d5cf834d68f7f95d1a39de6fcd09b3a51fbe123',
      fileSizeBytes: 1024,
      publishedAt: '2026-08-31T12:00:00.000Z',
    };
    fs.writeFileSync(path.join(tempReleasesDir, 'version.json'), JSON.stringify(manifest), 'utf8');

    const { app } = buildApp({
      releasesDir: tempReleasesDir,
      dataDir: tempSnapshotsDir,
    });

    const res = await app.inject({
      method: 'GET',
      url: '/api/v1/app/version',
    });

    expect(res.statusCode).toBe(503);
  });

  it('returns 503 when APK file size or sha256 does not match', async () => {
    const fakeApkContent = Buffer.from('FAKE_APK_BYTES_12345');
    const realSha256 = crypto.createHash('sha256').update(fakeApkContent).digest('hex');

    const manifest = {
      schemaVersion: 1,
      applicationId: 'com.nexustvguide.app',
      versionCode: 2,
      versionName: '0.6.0',
      releaseNotes: 'Test release',
      downloadPath: '/api/v1/app/download/nexus-tv-guide-0.6.0.apk',
      sha256: '0000000000000000000000000000000000000000000000000000000000000000', // Mismatch
      fileSizeBytes: fakeApkContent.length,
      publishedAt: '2026-08-31T12:00:00.000Z',
    };

    fs.writeFileSync(path.join(tempReleasesDir, 'nexus-tv-guide-0.6.0.apk'), fakeApkContent);
    fs.writeFileSync(path.join(tempReleasesDir, 'version.json'), JSON.stringify(manifest), 'utf8');

    const { app } = buildApp({
      releasesDir: tempReleasesDir,
      dataDir: tempSnapshotsDir,
    });

    const res = await app.inject({
      method: 'GET',
      url: '/api/v1/app/version',
    });

    expect(res.statusCode).toBe(503);
  });

  it('serves valid metadata and downloadable APK with correct headers and matching hash', async () => {
    const fakeApkContent = Buffer.from('VALID_APK_PAYLOAD_FOR_NEXUSTVGUIDE_v0.6.0');
    const sha256 = crypto.createHash('sha256').update(fakeApkContent).digest('hex');

    const manifest = {
      schemaVersion: 1,
      applicationId: 'com.nexustvguide.app',
      versionCode: 2,
      versionName: '0.6.0',
      releaseNotes: 'Nieuwe functies en bugfixes',
      downloadPath: '/api/v1/app/download/nexus-tv-guide-0.6.0.apk',
      sha256: sha256,
      fileSizeBytes: fakeApkContent.length,
      publishedAt: '2026-08-31T12:00:00.000Z',
    };

    fs.writeFileSync(path.join(tempReleasesDir, 'nexus-tv-guide-0.6.0.apk'), fakeApkContent);
    fs.writeFileSync(path.join(tempReleasesDir, 'version.json'), JSON.stringify(manifest), 'utf8');

    const { app } = buildApp({
      releasesDir: tempReleasesDir,
      dataDir: tempSnapshotsDir,
    });

    // 1. GET metadata
    const versionRes = await app.inject({
      method: 'GET',
      url: '/api/v1/app/version',
    });

    expect(versionRes.statusCode).toBe(200);
    expect(versionRes.headers['content-type']).toBe('application/json; charset=utf-8');
    expect(versionRes.headers['cache-control']).toBe('no-store');
    const versionJson = JSON.parse(versionRes.body);
    expect(versionJson.versionCode).toBe(2);
    expect(versionJson.versionName).toBe('0.6.0');
    expect(versionJson.sha256).toBe(sha256);

    // 2. HEAD download
    const headRes = await app.inject({
      method: 'HEAD',
      url: '/api/v1/app/download/nexus-tv-guide-0.6.0.apk',
    });

    expect(headRes.statusCode).toBe(200);
    expect(headRes.headers['content-type']).toBe('application/vnd.android.package-archive');
    expect(headRes.headers['content-disposition']).toBe('attachment; filename="nexus-tv-guide-0.6.0.apk"');
    expect(headRes.headers['content-length']).toBe(String(fakeApkContent.length));
    expect(headRes.headers['cache-control']).toBe('public, max-age=31536000, immutable');
    expect(headRes.headers['x-content-type-options']).toBe('nosniff');
    expect(headRes.body).toBe('');

    // 3. GET download
    const getRes = await app.inject({
      method: 'GET',
      url: '/api/v1/app/download/nexus-tv-guide-0.6.0.apk',
    });

    expect(getRes.statusCode).toBe(200);
    expect(getRes.headers['content-type']).toBe('application/vnd.android.package-archive');
    expect(getRes.rawPayload).toEqual(fakeApkContent);

    // Compute hash of downloaded payload
    const downloadedHash = crypto.createHash('sha256').update(getRes.rawPayload).digest('hex');
    expect(downloadedHash).toBe(sha256);
  });

  it('rejects path traversal and non-active filenames with 404', async () => {
    const fakeApkContent = Buffer.from('VALID_APK_PAYLOAD');
    const sha256 = crypto.createHash('sha256').update(fakeApkContent).digest('hex');

    const manifest = {
      schemaVersion: 1,
      applicationId: 'com.nexustvguide.app',
      versionCode: 2,
      versionName: '0.6.0',
      releaseNotes: 'Test',
      downloadPath: '/api/v1/app/download/nexus-tv-guide-0.6.0.apk',
      sha256: sha256,
      fileSizeBytes: fakeApkContent.length,
      publishedAt: '2026-08-31T12:00:00.000Z',
    };

    fs.writeFileSync(path.join(tempReleasesDir, 'nexus-tv-guide-0.6.0.apk'), fakeApkContent);
    fs.writeFileSync(path.join(tempReleasesDir, 'version.json'), JSON.stringify(manifest), 'utf8');

    const { app } = buildApp({
      releasesDir: tempReleasesDir,
      dataDir: tempSnapshotsDir,
    });

    // Path traversal
    const resTraversal = await app.inject({
      method: 'GET',
      url: '/api/v1/app/download/..%2Fversion.json',
    });
    expect(resTraversal.statusCode).toBe(404);

    // Non-active file
    const resOther = await app.inject({
      method: 'GET',
      url: '/api/v1/app/download/nexus-tv-guide-0.5.0.apk',
    });
    expect(resOther.statusCode).toBe(404);
  });
});
