import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
export declare const AppUpdateManifestSchema: z.ZodObject<{
    schemaVersion: z.ZodLiteral<1>;
    applicationId: z.ZodLiteral<"com.nexustvguide.app">;
    versionCode: z.ZodNumber;
    versionName: z.ZodString;
    releaseNotes: z.ZodOptional<z.ZodNullable<z.ZodString>>;
    downloadPath: z.ZodString;
    sha256: z.ZodString;
    fileSizeBytes: z.ZodNumber;
    publishedAt: z.ZodString;
}, "strip", z.ZodTypeAny, {
    schemaVersion: 1;
    applicationId: "com.nexustvguide.app";
    versionCode: number;
    versionName: string;
    downloadPath: string;
    sha256: string;
    fileSizeBytes: number;
    publishedAt: string;
    releaseNotes?: string | null | undefined;
}, {
    schemaVersion: 1;
    applicationId: "com.nexustvguide.app";
    versionCode: number;
    versionName: string;
    downloadPath: string;
    sha256: string;
    fileSizeBytes: number;
    publishedAt: string;
    releaseNotes?: string | null | undefined;
}>;
export type AppUpdateManifest = z.infer<typeof AppUpdateManifestSchema>;
export interface ActiveRelease {
    manifest: AppUpdateManifest;
    apkPath: string;
}
export declare function getActiveRelease(releasesDir: string): ActiveRelease | null;
export interface AppUpdateRoutesOptions {
    releasesDir?: string;
}
export declare function registerAppUpdateRoutes(app: FastifyInstance, options?: AppUpdateRoutesOptions): void;
