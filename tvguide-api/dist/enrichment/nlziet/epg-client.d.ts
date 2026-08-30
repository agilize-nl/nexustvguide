import { type NlzietEpgResponse } from "./epg-schema.js";
export interface NlzietEpgClientOptions {
    baseUrl?: string;
    timeoutMs?: number;
    maxRetries?: number;
    fetchFn?: typeof fetch;
    nowFn?: () => Date;
}
export declare class NlzietEpgClient {
    private baseUrl;
    private timeoutMs;
    private maxRetries;
    private fetchFn;
    private nowFn;
    private cache;
    constructor(options?: NlzietEpgClientOptions);
    isDateInEpgWindow(dateStr: string, todayStr?: string): boolean;
    private calculateTtlMs;
    clearCache(): void;
    fetchEpg(dateStr: string, channelIds: string[]): Promise<NlzietEpgResponse>;
}
