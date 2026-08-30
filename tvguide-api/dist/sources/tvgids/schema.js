import { z } from 'zod';
const MAX_64BIT_SIGNED_INT = 9223372036854775807n;
export const rawProgrammeSchema = z.object({
    s: z.string().regex(/^\d+$/, 'Start time must be a positive integer string'),
    e: z.string().regex(/^\d+$/, 'End time must be a positive integer string'),
    db_id: z.string().regex(/^\d+$/, 'db_id must be a decimal string'),
    title: z.string().optional().default('(Geen titel)'),
    descr: z.string().optional(),
    inhoud: z.string().optional(),
    algemene_inhoud: z.string().optional(),
    img: z.string().optional(),
    g_id: z.string().optional(),
    subgenre: z.string().optional(),
    tip: z.string().optional(),
    rerun: z.string().optional(),
    live: z.string().optional(),
    is_premiere: z.string().optional(),
    ei: z.string().optional(),
    is_type: z.string().optional(),
}).superRefine((val, ctx) => {
    const start = Number(val.s);
    const end = Number(val.e);
    if (!Number.isFinite(start) || start <= 0) {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'Start time must be a positive finite integer',
            path: ['s'],
        });
    }
    if (!Number.isFinite(end) || end <= start) {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'End time must be greater than start time',
            path: ['e'],
        });
    }
    try {
        const dbIdBig = BigInt(val.db_id);
        if (dbIdBig > MAX_64BIT_SIGNED_INT) {
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: 'db_id exceeds 64-bit signed integer maximum',
                path: ['db_id'],
            });
        }
    }
    catch {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'Invalid db_id number',
            path: ['db_id'],
        });
    }
});
export const rawChannelBucketSchema = z.object({
    ch_id: z.string(),
    prog: z.array(z.unknown()),
});
export const rawProgramsEnvelopeSchema = z.object({
    version: z.string(),
    versionmessage: z.record(z.unknown()).optional(),
    data: z.union([
        z.record(rawChannelBucketSchema),
        z.array(rawChannelBucketSchema),
    ]),
});
/**
 * Valideert de envelope van /v4/programs/ en retourneert een genormaliseerde Map van ch_id naar geldige RawProgramme[]
 */
export function parseProgramsEnvelope(json, stats) {
    const parsedEnvelope = rawProgramsEnvelopeSchema.safeParse(json);
    if (!parsedEnvelope.success) {
        throw new Error(`Invalid programs envelope structure: ${parsedEnvelope.error.message}`);
    }
    const result = new Map();
    const data = parsedEnvelope.data.data;
    const buckets = Array.isArray(data)
        ? data
        : Object.values(data);
    for (const bucket of buckets) {
        const validProgrammes = [];
        for (const rawProg of bucket.prog) {
            const parsedProg = rawProgrammeSchema.safeParse(rawProg);
            if (parsedProg.success) {
                validProgrammes.push(parsedProg.data);
            }
            else {
                if (stats) {
                    stats.skippedMalformedProgrammesCount++;
                }
            }
        }
        result.set(bucket.ch_id, validProgrammes);
    }
    return result;
}
