import { z } from 'zod';
import type { RawProgramme } from './types.js';
export declare const rawProgrammeSchema: z.ZodEffects<z.ZodObject<{
    s: z.ZodString;
    e: z.ZodString;
    db_id: z.ZodString;
    title: z.ZodDefault<z.ZodOptional<z.ZodString>>;
    descr: z.ZodOptional<z.ZodString>;
    inhoud: z.ZodOptional<z.ZodString>;
    algemene_inhoud: z.ZodOptional<z.ZodString>;
    img: z.ZodOptional<z.ZodString>;
    g_id: z.ZodOptional<z.ZodString>;
    subgenre: z.ZodOptional<z.ZodString>;
    tip: z.ZodOptional<z.ZodString>;
    rerun: z.ZodOptional<z.ZodString>;
    live: z.ZodOptional<z.ZodString>;
    is_premiere: z.ZodOptional<z.ZodString>;
    ei: z.ZodOptional<z.ZodString>;
    is_type: z.ZodOptional<z.ZodString>;
}, "strip", z.ZodTypeAny, {
    s: string;
    e: string;
    db_id: string;
    title: string;
    descr?: string | undefined;
    inhoud?: string | undefined;
    algemene_inhoud?: string | undefined;
    img?: string | undefined;
    g_id?: string | undefined;
    subgenre?: string | undefined;
    tip?: string | undefined;
    rerun?: string | undefined;
    live?: string | undefined;
    is_premiere?: string | undefined;
    ei?: string | undefined;
    is_type?: string | undefined;
}, {
    s: string;
    e: string;
    db_id: string;
    title?: string | undefined;
    descr?: string | undefined;
    inhoud?: string | undefined;
    algemene_inhoud?: string | undefined;
    img?: string | undefined;
    g_id?: string | undefined;
    subgenre?: string | undefined;
    tip?: string | undefined;
    rerun?: string | undefined;
    live?: string | undefined;
    is_premiere?: string | undefined;
    ei?: string | undefined;
    is_type?: string | undefined;
}>, {
    s: string;
    e: string;
    db_id: string;
    title: string;
    descr?: string | undefined;
    inhoud?: string | undefined;
    algemene_inhoud?: string | undefined;
    img?: string | undefined;
    g_id?: string | undefined;
    subgenre?: string | undefined;
    tip?: string | undefined;
    rerun?: string | undefined;
    live?: string | undefined;
    is_premiere?: string | undefined;
    ei?: string | undefined;
    is_type?: string | undefined;
}, {
    s: string;
    e: string;
    db_id: string;
    title?: string | undefined;
    descr?: string | undefined;
    inhoud?: string | undefined;
    algemene_inhoud?: string | undefined;
    img?: string | undefined;
    g_id?: string | undefined;
    subgenre?: string | undefined;
    tip?: string | undefined;
    rerun?: string | undefined;
    live?: string | undefined;
    is_premiere?: string | undefined;
    ei?: string | undefined;
    is_type?: string | undefined;
}>;
export declare const rawChannelBucketSchema: z.ZodObject<{
    ch_id: z.ZodString;
    prog: z.ZodArray<z.ZodUnknown, "many">;
}, "strip", z.ZodTypeAny, {
    ch_id: string;
    prog: unknown[];
}, {
    ch_id: string;
    prog: unknown[];
}>;
export declare const rawProgramsEnvelopeSchema: z.ZodObject<{
    version: z.ZodString;
    versionmessage: z.ZodOptional<z.ZodRecord<z.ZodString, z.ZodUnknown>>;
    data: z.ZodUnion<[z.ZodRecord<z.ZodString, z.ZodObject<{
        ch_id: z.ZodString;
        prog: z.ZodArray<z.ZodUnknown, "many">;
    }, "strip", z.ZodTypeAny, {
        ch_id: string;
        prog: unknown[];
    }, {
        ch_id: string;
        prog: unknown[];
    }>>, z.ZodArray<z.ZodObject<{
        ch_id: z.ZodString;
        prog: z.ZodArray<z.ZodUnknown, "many">;
    }, "strip", z.ZodTypeAny, {
        ch_id: string;
        prog: unknown[];
    }, {
        ch_id: string;
        prog: unknown[];
    }>, "many">]>;
}, "strip", z.ZodTypeAny, {
    version: string;
    data: Record<string, {
        ch_id: string;
        prog: unknown[];
    }> | {
        ch_id: string;
        prog: unknown[];
    }[];
    versionmessage?: Record<string, unknown> | undefined;
}, {
    version: string;
    data: Record<string, {
        ch_id: string;
        prog: unknown[];
    }> | {
        ch_id: string;
        prog: unknown[];
    }[];
    versionmessage?: Record<string, unknown> | undefined;
}>;
export interface ValidationStats {
    skippedMalformedProgrammesCount: number;
}
/**
 * Valideert de envelope van /v4/programs/ en retourneert een genormaliseerde Map van ch_id naar geldige RawProgramme[]
 */
export declare function parseProgramsEnvelope(json: unknown, stats?: ValidationStats): Map<string, RawProgramme[]>;
