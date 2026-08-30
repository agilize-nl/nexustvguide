import { Temporal } from "@js-temporal/polyfill";
import { getTodayAmsterdam } from "../../store/time.js";
import { parseNlzietEpgResponse } from "./epg-schema.js";
export class NlzietEpgClient {
    baseUrl;
    timeoutMs;
    maxRetries;
    fetchFn;
    nowFn;
    cache = new Map();
    constructor(options = {}) {
        this.baseUrl = options.baseUrl || "https://api.nlziet.nl/v9/epg/programlocations";
        this.timeoutMs = options.timeoutMs ?? 10_000;
        this.maxRetries = options.maxRetries ?? 2;
        this.fetchFn = options.fetchFn || globalThis.fetch;
        this.nowFn = options.nowFn || (() => new Date());
    }
    isDateInEpgWindow(dateStr, todayStr) {
        const today = todayStr || getTodayAmsterdam();
        const todayPlain = Temporal.PlainDate.from(today);
        const targetPlain = Temporal.PlainDate.from(dateStr);
        const diffDays = targetPlain.since(todayPlain).total({ unit: "day" });
        return diffDays >= -7 && diffDays <= 7;
    }
    calculateTtlMs(dateStr, todayStr) {
        if (dateStr === todayStr) {
            return 10 * 60 * 1000; // 10 minuten voor vandaag
        }
        else if (dateStr > todayStr) {
            return 60 * 60 * 1000; // 60 minuten voor toekomstige dagen
        }
        else {
            return 6 * 60 * 60 * 1000; // 6 uur voor verleden dagen
        }
    }
    clearCache() {
        this.cache.clear();
    }
    async fetchEpg(dateStr, channelIds) {
        const validChannelIds = Array.from(new Set(channelIds.filter((id) => id && id.trim().length > 0))).sort();
        if (validChannelIds.length === 0) {
            return { data: [] };
        }
        const today = getTodayAmsterdam();
        if (!this.isDateInEpgWindow(dateStr, today)) {
            return { data: [] };
        }
        const cacheKey = `${dateStr}:${validChannelIds.join(",")}`;
        const nowMs = this.nowFn().getTime();
        const cached = this.cache.get(cacheKey);
        if (cached && cached.expiresAt > nowMs) {
            return cached.data;
        }
        const url = new URL(this.baseUrl);
        url.searchParams.set("date", dateStr);
        for (const chId of validChannelIds) {
            url.searchParams.append("channel", chId);
        }
        let lastError = null;
        for (let attempt = 0; attempt <= this.maxRetries; attempt++) {
            if (attempt > 0) {
                const backoffMs = Math.min(500 * Math.pow(2, attempt - 1), 2000);
                await new Promise((resolve) => setTimeout(resolve, backoffMs));
            }
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), this.timeoutMs);
            try {
                const response = await this.fetchFn(url.toString(), {
                    headers: {
                        "Accept": "application/json",
                        "User-Agent": "NexusTVGuide/1.0",
                    },
                    signal: controller.signal,
                });
                clearTimeout(timer);
                if (!response.ok) {
                    if (response.status >= 500 && attempt < this.maxRetries) {
                        lastError = new Error(`NLZIET EPG HTTP error ${response.status}: ${response.statusText}`);
                        continue;
                    }
                    throw new Error(`NLZIET EPG HTTP error ${response.status}: ${response.statusText}`);
                }
                const rawJson = await response.json();
                const validated = parseNlzietEpgResponse(rawJson);
                // Alleen opslaan in cache na succesvolle validatie
                const ttlMs = this.calculateTtlMs(dateStr, today);
                this.cache.set(cacheKey, {
                    expiresAt: nowMs + ttlMs,
                    data: validated,
                });
                return validated;
            }
            catch (err) {
                clearTimeout(timer);
                const errObj = err instanceof Error ? err : new Error(String(err));
                lastError = errObj;
                if (attempt === this.maxRetries) {
                    console.warn(`NLZIET EPG fetch failed for date ${dateStr} after ${this.maxRetries + 1} attempts: ${errObj.message}`);
                    throw errObj;
                }
            }
        }
        throw lastError || new Error(`NLZIET EPG fetch failed for date ${dateStr}`);
    }
}
