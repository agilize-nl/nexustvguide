import { z } from 'zod';
const Base64UrlIdRegex = /^[a-zA-Z0-9_-]{22}$/;
const Hex32Regex = /^[a-fA-F0-9]{32}$/;
export const NlzietEpgContentSchema = z.object({
    contentItemId: z.string().regex(Base64UrlIdRegex),
    assetId: z.string().regex(Hex32Regex),
    title: z.string().min(1),
    startAt: z.string().datetime({ offset: true }),
    endAt: z.string().datetime({ offset: true }),
    isReplayAllowed: z.boolean().default(false),
    isRestartAllowed: z.boolean().default(false),
    seriesId: z.string().nullable().optional(),
});
export const NlzietEpgProgramLocationSchema = z.object({
    content: NlzietEpgContentSchema,
});
export const NlzietEpgChannelGroupSchema = z.object({
    channel: z.object({
        content: z.object({
            id: z.string().min(1),
            title: z.string().optional(),
        }),
    }),
    programLocations: z.array(NlzietEpgProgramLocationSchema).default([]),
});
export const NlzietEpgResponseSchema = z.object({
    data: z.array(NlzietEpgChannelGroupSchema),
});
export function parseNlzietEpgResponse(json) {
    return NlzietEpgResponseSchema.parse(json);
}
