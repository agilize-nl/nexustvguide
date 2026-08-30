import fs from 'node:fs';
import { Temporal } from '@js-temporal/polyfill';
import { parseProgramsEnvelope } from '../sources/tvgids/schema.js';
import { mapProgramme } from '../sources/tvgids/mapper.js';
import { getLocalDayUtcWindow, getTodayAmsterdam, TIME_ZONE } from './time.js';
export const MIN_PROVIDER_OFFSET = -2;
export const MAX_PROVIDER_OFFSET = 13;
export class RefreshEngine {
    client;
    store;
    channelsConfigPath;
    startTime = Date.now();
    validationStats = { skippedMalformedProgrammesCount: 0 };
    lastRefreshAttempt = null;
    lastSuccessfulRefresh = null;
    lastError = null;
    refreshIntervalTimer = null;
    isRefreshing = false;
    constructor(client, store, channelsConfigPath) {
        this.client = client;
        this.store = store;
        this.channelsConfigPath = channelsConfigPath;
    }
    loadChannelsConfig() {
        const raw = fs.readFileSync(this.channelsConfigPath, 'utf-8');
        const allChannels = JSON.parse(raw);
        // Alleen actieve NLZiet-zenders (Besluit 4), gesorteerd op sortOrder
        const active = allChannels.filter((c) => c.inNlziet).sort((a, b) => a.sortOrder - b.sortOrder);
        this.store.setChannels(active);
        return active;
    }
    getStats() {
        const snapshots = this.store.getAllSnapshots();
        const dates = Array.from(snapshots.keys()).sort();
        return {
            status: this.lastSuccessfulRefresh ? 'ok' : 'degraded',
            uptimeSeconds: Math.floor((Date.now() - this.startTime) / 1000),
            lastRefreshAttempt: this.lastRefreshAttempt,
            lastSuccessfulRefresh: this.lastSuccessfulRefresh,
            skippedMalformedProgrammesCount: this.validationStats.skippedMalformedProgrammesCount,
            loadedChannelCount: this.store.getChannels().length,
            availableSnapshotDates: dates,
            lastError: this.lastError,
        };
    }
    isReady() {
        const today = getTodayAmsterdam();
        return this.store.getSnapshot(today) !== undefined;
    }
    isSnapshotStale(snapshot) {
        const today = getTodayAmsterdam();
        const now = Date.now();
        const published = new Date(snapshot.publishedAt).getTime();
        const ageMs = now - published;
        // Gisteren t/m morgen: stale na 2 uur (7.200.000 ms)
        // Overige dagen: stale na 8 uur (28.800.000 ms)
        const maxAgeMs = snapshot.date <= today || snapshot.date === this.addDays(today, 1)
            ? 2 * 60 * 60 * 1000
            : 8 * 60 * 60 * 1000;
        return ageMs > maxAgeMs;
    }
    addDays(dateStr, days) {
        return Temporal.PlainDate.from(dateStr).add({ days }).toString();
    }
    /**
     * Voert een complete verversingscyclus uit voor offsets -2..13
     */
    async refreshAll() {
        if (this.isRefreshing) {
            console.log('Refresh already in progress, skipping...');
            return;
        }
        this.isRefreshing = true;
        this.lastRefreshAttempt = new Date().toISOString();
        try {
            const activeChannels = this.loadChannelsConfig();
            const sourceIdToChannel = new Map();
            for (const ch of activeChannels) {
                sourceIdToChannel.set(ch.sourceId, ch);
            }
            const sourceIds = activeChannels.map((c) => c.sourceId);
            const allProgrammesMap = new Map(); // key: `${channelId}:${prog.id}`
            // 1. Haal alle offsets -2..13 op
            for (let offset = MIN_PROVIDER_OFFSET; offset <= MAX_PROVIDER_OFFSET; offset++) {
                try {
                    const rawEnvelope = await this.client.fetchPrograms(offset, sourceIds);
                    const channelBuckets = parseProgramsEnvelope(rawEnvelope, this.validationStats);
                    for (const [sourceId, rawProgs] of channelBuckets.entries()) {
                        const domainChannel = sourceIdToChannel.get(sourceId);
                        if (!domainChannel)
                            continue;
                        for (const rawProg of rawProgs) {
                            const mapped = mapProgramme(rawProg, domainChannel.id);
                            const key = `${domainChannel.id}:${mapped.id}`;
                            allProgrammesMap.set(key, mapped);
                        }
                    }
                }
                catch (offsetErr) {
                    console.warn(`Warning: failed to fetch provider offset ${offset}:`, offsetErr);
                }
            }
            const allProgrammes = Array.from(allProgrammesMap.values());
            const nowUtcIso = new Date().toISOString();
            const today = getTodayAmsterdam();
            // Bepaal de te genereren lokale kalenderdagen: vandaag - 2 dagen t/m vandaag + 10 dagen
            const datesToProcess = [];
            for (let d = -2; d <= 10; d++) {
                datesToProcess.push(this.addDays(today, d));
            }
            let successfulDaysCount = 0;
            // 2. Verdeel programma's over lokale kalenderdagen en voer sanity-checks uit
            for (const date of datesToProcess) {
                const { from, to } = getLocalDayUtcWindow(date);
                const fromMs = new Date(from).getTime();
                const toMs = new Date(to).getTime();
                // Een programma hoort bij de dag als het overlapt met [from, to)
                const dayProgrammes = allProgrammes
                    .filter((p) => {
                    const pStart = new Date(p.start).getTime();
                    const pEnd = new Date(p.end).getTime();
                    return pStart < toMs && pEnd > fromMs;
                })
                    .sort((a, b) => {
                    // Sorteer op zender sortOrder, daarna op starttijd
                    const chA = activeChannels.find((c) => c.id === a.channelId)?.sortOrder ?? 999;
                    const chB = activeChannels.find((c) => c.id === b.channelId)?.sortOrder ?? 999;
                    if (chA !== chB)
                        return chA - chB;
                    return new Date(a.start).getTime() - new Date(b.start).getTime();
                });
                const existingSnapshot = this.store.getSnapshot(date);
                // Sanity checks:
                // a) Minimaal 1 programma vereist voor nabije dagen (gisteren t/m +5 dagen)
                const isNearDay = date >= this.addDays(today, -1) && date <= this.addDays(today, 5);
                if (isNearDay && dayProgrammes.length === 0) {
                    console.warn(`Sanity check failed for date ${date}: 0 programmes found. Preserving existing snapshot.`);
                    continue;
                }
                // b) Geen daling van meer dan 40% ten opzichte van bestaande snapshot
                if (existingSnapshot && existingSnapshot.programmes.length > 50) {
                    const dropRatio = (existingSnapshot.programmes.length - dayProgrammes.length) / existingSnapshot.programmes.length;
                    if (dropRatio > 0.4) {
                        console.warn(`Sanity check failed for date ${date}: programme count dropped from ${existingSnapshot.programmes.length} to ${dayProgrammes.length} (>40% drop). Preserving existing snapshot.`);
                        continue;
                    }
                }
                // c) Basis zender check (bv npo1) voor vandaag
                if (date === today) {
                    const hasNpo1 = dayProgrammes.some((p) => p.channelId === 'npo1');
                    if (!hasNpo1 && dayProgrammes.length > 0) {
                        console.warn(`Sanity check warning for today ${date}: NPO 1 has no programmes.`);
                    }
                }
                if (dayProgrammes.length > 0) {
                    const snapshot = {
                        date,
                        timeZone: TIME_ZONE,
                        from,
                        to,
                        sourceFetchedAt: nowUtcIso,
                        publishedAt: nowUtcIso,
                        channels: activeChannels,
                        programmes: dayProgrammes,
                    };
                    await this.store.saveSnapshot(snapshot);
                    successfulDaysCount++;
                }
            }
            if (successfulDaysCount > 0) {
                this.lastSuccessfulRefresh = nowUtcIso;
                this.lastError = null;
            }
            // Verwijder snapshots ouder dan vandaag - 3 dagen
            const purgeThreshold = this.addDays(today, -3);
            await this.store.cleanOldSnapshots(purgeThreshold);
            console.log(`Refresh cycle completed. Successfully updated ${successfulDaysCount} days.`);
        }
        catch (err) {
            const errorMsg = err instanceof Error ? err.message : String(err);
            this.lastError = errorMsg;
            console.error('Refresh cycle failed:', errorMsg);
        }
        finally {
            this.isRefreshing = false;
        }
    }
    startPeriodicRefresh(intervalMinutes = 30) {
        if (this.refreshIntervalTimer) {
            clearInterval(this.refreshIntervalTimer);
        }
        const intervalMs = intervalMinutes * 60 * 1000;
        this.refreshIntervalTimer = setInterval(() => {
            this.refreshAll().catch((err) => console.error('Periodic refresh error:', err));
        }, intervalMs);
        // Voer ook direct een initiële refresh uit
        this.refreshAll().catch((err) => console.error('Initial refresh error:', err));
    }
    stop() {
        if (this.refreshIntervalTimer) {
            clearInterval(this.refreshIntervalTimer);
            this.refreshIntervalTimer = null;
        }
    }
}
