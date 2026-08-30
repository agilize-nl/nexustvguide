export interface ClientOptions {
    baseUrl?: string;
    timeoutMs?: number;
    maxRetries?: number;
    userAgent?: string;
}
export declare class TvgidsClient {
    private baseUrl;
    private timeoutMs;
    private maxRetries;
    private userAgent;
    constructor(options?: ClientOptions);
    private fetchWithRetry;
    fetchChannels(): Promise<unknown>;
    fetchPrograms(dayOffset: number, channelSourceIds?: string[]): Promise<unknown>;
}
