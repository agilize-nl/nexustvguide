import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';

export const AppUpdateManifestSchema = z.object({
  schemaVersion: z.literal(1),
  applicationId: z.literal('com.nexustvguide.app'),
  versionCode: z.number().int().positive(),
  versionName: z.string().min(1).max(64),
  releaseNotes: z.string().max(8000).nullable().optional(),
  downloadPath: z.string().regex(/^\/api\/v1\/app\/download\/[a-zA-Z0-9._-]+\.apk$/),
  sha256: z.string().regex(/^[0-9a-f]{64}$/),
  fileSizeBytes: z.number().int().positive().max(100 * 1024 * 1024),
  publishedAt: z.string().datetime(),
});

export type AppUpdateManifest = z.infer<typeof AppUpdateManifestSchema>;

export interface ActiveRelease {
  manifest: AppUpdateManifest;
  apkPath: string;
}

export function getActiveRelease(releasesDir: string): ActiveRelease | null {
  const manifestPath = path.join(releasesDir, 'version.json');
  if (!fs.existsSync(manifestPath)) {
    return null;
  }

  let rawJson: unknown;
  try {
    const content = fs.readFileSync(manifestPath, 'utf8');
    rawJson = JSON.parse(content);
  } catch {
    return null;
  }

  const parseResult = AppUpdateManifestSchema.safeParse(rawJson);
  if (!parseResult.success) {
    return null;
  }

  const manifest = parseResult.data;
  const expectedFilename = path.basename(manifest.downloadPath);
  const apkPath = path.join(releasesDir, expectedFilename);

  if (!fs.existsSync(apkPath)) {
    return null;
  }

  try {
    const stats = fs.statSync(apkPath);
    if (stats.size !== manifest.fileSizeBytes) {
      return null;
    }

    const apkBuffer = fs.readFileSync(apkPath);
    const computedHash = crypto.createHash('sha256').update(apkBuffer).digest('hex').toLowerCase();
    if (computedHash !== manifest.sha256.toLowerCase()) {
      return null;
    }

    return { manifest, apkPath };
  } catch {
    return null;
  }
}

export interface AppUpdateRoutesOptions {
  releasesDir?: string;
}

export function registerAppUpdateRoutes(app: FastifyInstance, options: AppUpdateRoutesOptions = {}): void {
  const releasesDir = options.releasesDir || path.resolve(process.cwd(), 'data/releases');

  // GET /api/v1/app/version
  app.get('/api/v1/app/version', async (request: FastifyRequest, reply: FastifyReply) => {
    const active = getActiveRelease(releasesDir);
    if (!active) {
      reply.header('Cache-Control', 'no-store');
      reply.header('Content-Type', 'application/json; charset=utf-8');
      return reply.status(503).send({
        error: 'RELEASE_UNAVAILABLE',
        message: 'Geen geldige release beschikbaar',
      });
    }

    reply.header('Cache-Control', 'no-store');
    reply.header('Content-Type', 'application/json; charset=utf-8');
    return reply.status(200).send(active.manifest);
  });

  // GET & HEAD /api/v1/app/download/:filename
  const handleDownload = async (
    request: FastifyRequest<{ Params: { filename: string } }>,
    reply: FastifyReply
  ) => {
    const requestedFilename = request.params.filename;

    // Reject path traversal and suspicious filenames
    if (
      !requestedFilename ||
      requestedFilename.includes('..') ||
      requestedFilename.includes('/') ||
      requestedFilename.includes('\\') ||
      !requestedFilename.endsWith('.apk')
    ) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'Bestand niet gevonden' });
    }

    const active = getActiveRelease(releasesDir);
    if (!active) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'Bestand niet gevonden' });
    }

    const activeFilename = path.basename(active.manifest.downloadPath);
    if (requestedFilename !== activeFilename) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'Bestand niet gevonden' });
    }

    reply.header('Content-Type', 'application/vnd.android.package-archive');
    reply.header('Content-Disposition', `attachment; filename="${activeFilename}"`);
    reply.header('Content-Length', active.manifest.fileSizeBytes);
    reply.header('Cache-Control', 'public, max-age=31536000, immutable');
    reply.header('X-Content-Type-Options', 'nosniff');

    if (request.method === 'HEAD') {
      return reply.status(200).send();
    }

    const stream = fs.createReadStream(active.apkPath);
    return reply.status(200).send(stream);
  };

  app.get('/api/v1/app/download/:filename', handleDownload);
  app.head('/api/v1/app/download/:filename', handleDownload);
}
