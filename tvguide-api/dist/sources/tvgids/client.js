export class TvgidsClient {
    baseUrl;
    timeoutMs;
    maxRetries;
    userAgent;
    constructor(options = {}) {
        this.baseUrl = (options.baseUrl || 'https://json.tvgids.nl/v4').replace(/\/$/, '');
        this.timeoutMs = options.timeoutMs || 10000;
        this.maxRetries = options.maxRetries ?? 2;
        this.userAgent = options.userAgent || 'NexusTVGuide/1.0 (+https://github.com/NexusTVGuide)';
    }
    async fetchWithRetry(url) {
        let attempt = 0;
        let lastError = null;
        while (attempt <= this.maxRetries) {
            attempt++;
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), this.timeoutMs);
            try {
                const response = await fetch(url, {
                    signal: controller.signal,
                    headers: {
                        'User-Agent': this.userAgent,
                        'Accept': 'application/json',
                    },
                });
                clearTimeout(timeoutId);
                if (!response.ok) {
                    throw new Error(`Upstream HTTP error: ${response.status} ${response.statusText}`);
                }
                const data = await response.json();
                return data;
            }
            catch (err) {
                clearTimeout(timeoutId);
                lastError = err instanceof Error ? err : new Error(String(err));
                if (attempt <= this.maxRetries) {
                    const delay = Math.min(1000 * Math.pow(2, attempt - 1), 3000);
                    await new Promise((resolve) => setTimeout(resolve, delay));
                }
            }
        }
        throw new Error(`Failed to fetch ${url} after ${this.maxRetries + 1} attempts: ${lastError?.message}`);
    }
    async fetchChannels() {
        const url = `${this.baseUrl}/channels`;
        return this.fetchWithRetry(url);
    }
    async fetchPrograms(dayOffset, channelSourceIds) {
        let url = `${this.baseUrl}/programs/?day=${dayOffset}`;
        if (channelSourceIds && channelSourceIds.length > 0) {
            url += `&channels=${channelSourceIds.join(',')}`;
        }
        return this.fetchWithRetry(url);
    }
}
