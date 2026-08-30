import { z } from 'zod';
export declare const NlzietEpgContentSchema: z.ZodObject<{
    contentItemId: z.ZodString;
    assetId: z.ZodString;
    title: z.ZodString;
    startAt: z.ZodString;
    endAt: z.ZodString;
    isReplayAllowed: z.ZodDefault<z.ZodBoolean>;
    isRestartAllowed: z.ZodDefault<z.ZodBoolean>;
    seriesId: z.ZodOptional<z.ZodNullable<z.ZodString>>;
}, "strip", z.ZodTypeAny, {
    title: string;
    contentItemId: string;
    assetId: string;
    startAt: string;
    endAt: string;
    isReplayAllowed: boolean;
    isRestartAllowed: boolean;
    seriesId?: string | null | undefined;
}, {
    title: string;
    contentItemId: string;
    assetId: string;
    startAt: string;
    endAt: string;
    isReplayAllowed?: boolean | undefined;
    isRestartAllowed?: boolean | undefined;
    seriesId?: string | null | undefined;
}>;
export declare const NlzietEpgProgramLocationSchema: z.ZodObject<{
    content: z.ZodObject<{
        contentItemId: z.ZodString;
        assetId: z.ZodString;
        title: z.ZodString;
        startAt: z.ZodString;
        endAt: z.ZodString;
        isReplayAllowed: z.ZodDefault<z.ZodBoolean>;
        isRestartAllowed: z.ZodDefault<z.ZodBoolean>;
        seriesId: z.ZodOptional<z.ZodNullable<z.ZodString>>;
    }, "strip", z.ZodTypeAny, {
        title: string;
        contentItemId: string;
        assetId: string;
        startAt: string;
        endAt: string;
        isReplayAllowed: boolean;
        isRestartAllowed: boolean;
        seriesId?: string | null | undefined;
    }, {
        title: string;
        contentItemId: string;
        assetId: string;
        startAt: string;
        endAt: string;
        isReplayAllowed?: boolean | undefined;
        isRestartAllowed?: boolean | undefined;
        seriesId?: string | null | undefined;
    }>;
}, "strip", z.ZodTypeAny, {
    content: {
        title: string;
        contentItemId: string;
        assetId: string;
        startAt: string;
        endAt: string;
        isReplayAllowed: boolean;
        isRestartAllowed: boolean;
        seriesId?: string | null | undefined;
    };
}, {
    content: {
        title: string;
        contentItemId: string;
        assetId: string;
        startAt: string;
        endAt: string;
        isReplayAllowed?: boolean | undefined;
        isRestartAllowed?: boolean | undefined;
        seriesId?: string | null | undefined;
    };
}>;
export declare const NlzietEpgChannelGroupSchema: z.ZodObject<{
    channel: z.ZodObject<{
        content: z.ZodObject<{
            id: z.ZodString;
            title: z.ZodOptional<z.ZodString>;
        }, "strip", z.ZodTypeAny, {
            id: string;
            title?: string | undefined;
        }, {
            id: string;
            title?: string | undefined;
        }>;
    }, "strip", z.ZodTypeAny, {
        content: {
            id: string;
            title?: string | undefined;
        };
    }, {
        content: {
            id: string;
            title?: string | undefined;
        };
    }>;
    programLocations: z.ZodDefault<z.ZodArray<z.ZodObject<{
        content: z.ZodObject<{
            contentItemId: z.ZodString;
            assetId: z.ZodString;
            title: z.ZodString;
            startAt: z.ZodString;
            endAt: z.ZodString;
            isReplayAllowed: z.ZodDefault<z.ZodBoolean>;
            isRestartAllowed: z.ZodDefault<z.ZodBoolean>;
            seriesId: z.ZodOptional<z.ZodNullable<z.ZodString>>;
        }, "strip", z.ZodTypeAny, {
            title: string;
            contentItemId: string;
            assetId: string;
            startAt: string;
            endAt: string;
            isReplayAllowed: boolean;
            isRestartAllowed: boolean;
            seriesId?: string | null | undefined;
        }, {
            title: string;
            contentItemId: string;
            assetId: string;
            startAt: string;
            endAt: string;
            isReplayAllowed?: boolean | undefined;
            isRestartAllowed?: boolean | undefined;
            seriesId?: string | null | undefined;
        }>;
    }, "strip", z.ZodTypeAny, {
        content: {
            title: string;
            contentItemId: string;
            assetId: string;
            startAt: string;
            endAt: string;
            isReplayAllowed: boolean;
            isRestartAllowed: boolean;
            seriesId?: string | null | undefined;
        };
    }, {
        content: {
            title: string;
            contentItemId: string;
            assetId: string;
            startAt: string;
            endAt: string;
            isReplayAllowed?: boolean | undefined;
            isRestartAllowed?: boolean | undefined;
            seriesId?: string | null | undefined;
        };
    }>, "many">>;
}, "strip", z.ZodTypeAny, {
    channel: {
        content: {
            id: string;
            title?: string | undefined;
        };
    };
    programLocations: {
        content: {
            title: string;
            contentItemId: string;
            assetId: string;
            startAt: string;
            endAt: string;
            isReplayAllowed: boolean;
            isRestartAllowed: boolean;
            seriesId?: string | null | undefined;
        };
    }[];
}, {
    channel: {
        content: {
            id: string;
            title?: string | undefined;
        };
    };
    programLocations?: {
        content: {
            title: string;
            contentItemId: string;
            assetId: string;
            startAt: string;
            endAt: string;
            isReplayAllowed?: boolean | undefined;
            isRestartAllowed?: boolean | undefined;
            seriesId?: string | null | undefined;
        };
    }[] | undefined;
}>;
export declare const NlzietEpgResponseSchema: z.ZodObject<{
    data: z.ZodArray<z.ZodObject<{
        channel: z.ZodObject<{
            content: z.ZodObject<{
                id: z.ZodString;
                title: z.ZodOptional<z.ZodString>;
            }, "strip", z.ZodTypeAny, {
                id: string;
                title?: string | undefined;
            }, {
                id: string;
                title?: string | undefined;
            }>;
        }, "strip", z.ZodTypeAny, {
            content: {
                id: string;
                title?: string | undefined;
            };
        }, {
            content: {
                id: string;
                title?: string | undefined;
            };
        }>;
        programLocations: z.ZodDefault<z.ZodArray<z.ZodObject<{
            content: z.ZodObject<{
                contentItemId: z.ZodString;
                assetId: z.ZodString;
                title: z.ZodString;
                startAt: z.ZodString;
                endAt: z.ZodString;
                isReplayAllowed: z.ZodDefault<z.ZodBoolean>;
                isRestartAllowed: z.ZodDefault<z.ZodBoolean>;
                seriesId: z.ZodOptional<z.ZodNullable<z.ZodString>>;
            }, "strip", z.ZodTypeAny, {
                title: string;
                contentItemId: string;
                assetId: string;
                startAt: string;
                endAt: string;
                isReplayAllowed: boolean;
                isRestartAllowed: boolean;
                seriesId?: string | null | undefined;
            }, {
                title: string;
                contentItemId: string;
                assetId: string;
                startAt: string;
                endAt: string;
                isReplayAllowed?: boolean | undefined;
                isRestartAllowed?: boolean | undefined;
                seriesId?: string | null | undefined;
            }>;
        }, "strip", z.ZodTypeAny, {
            content: {
                title: string;
                contentItemId: string;
                assetId: string;
                startAt: string;
                endAt: string;
                isReplayAllowed: boolean;
                isRestartAllowed: boolean;
                seriesId?: string | null | undefined;
            };
        }, {
            content: {
                title: string;
                contentItemId: string;
                assetId: string;
                startAt: string;
                endAt: string;
                isReplayAllowed?: boolean | undefined;
                isRestartAllowed?: boolean | undefined;
                seriesId?: string | null | undefined;
            };
        }>, "many">>;
    }, "strip", z.ZodTypeAny, {
        channel: {
            content: {
                id: string;
                title?: string | undefined;
            };
        };
        programLocations: {
            content: {
                title: string;
                contentItemId: string;
                assetId: string;
                startAt: string;
                endAt: string;
                isReplayAllowed: boolean;
                isRestartAllowed: boolean;
                seriesId?: string | null | undefined;
            };
        }[];
    }, {
        channel: {
            content: {
                id: string;
                title?: string | undefined;
            };
        };
        programLocations?: {
            content: {
                title: string;
                contentItemId: string;
                assetId: string;
                startAt: string;
                endAt: string;
                isReplayAllowed?: boolean | undefined;
                isRestartAllowed?: boolean | undefined;
                seriesId?: string | null | undefined;
            };
        }[] | undefined;
    }>, "many">;
}, "strip", z.ZodTypeAny, {
    data: {
        channel: {
            content: {
                id: string;
                title?: string | undefined;
            };
        };
        programLocations: {
            content: {
                title: string;
                contentItemId: string;
                assetId: string;
                startAt: string;
                endAt: string;
                isReplayAllowed: boolean;
                isRestartAllowed: boolean;
                seriesId?: string | null | undefined;
            };
        }[];
    }[];
}, {
    data: {
        channel: {
            content: {
                id: string;
                title?: string | undefined;
            };
        };
        programLocations?: {
            content: {
                title: string;
                contentItemId: string;
                assetId: string;
                startAt: string;
                endAt: string;
                isReplayAllowed?: boolean | undefined;
                isRestartAllowed?: boolean | undefined;
                seriesId?: string | null | undefined;
            };
        }[] | undefined;
    }[];
}>;
export type NlzietEpgContent = z.infer<typeof NlzietEpgContentSchema>;
export type NlzietEpgProgramLocation = z.infer<typeof NlzietEpgProgramLocationSchema>;
export type NlzietEpgChannelGroup = z.infer<typeof NlzietEpgChannelGroupSchema>;
export type NlzietEpgResponse = z.infer<typeof NlzietEpgResponseSchema>;
export declare function parseNlzietEpgResponse(json: unknown): NlzietEpgResponse;
