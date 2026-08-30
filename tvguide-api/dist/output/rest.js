import { getLocalDateRange, parseInstant, TIME_ZONE } from '../store/time.js';
export function registerRestRoutes(app, store, engine) {
    // GET /api/v1/channels
    app.get('/api/v1/channels', async (request, reply) => {
        const channels = store.getChannels();
        reply.header('Cache-Control', 'public, max-age=3600');
        return channels;
    });
    // GET /api/v1/guide?date=YYYY-MM-DD OF GET /api/v1/guide?from=<iso>&to=<iso>
    app.get('/api/v1/guide', async (request, reply) => {
        const { date, from, to } = request.query;
        if (!date && (!from || !to)) {
            reply.status(400);
            return {
                error: {
                    code: 'INVALID_QUERY_PARAM',
                    message: 'Geef ofwel ?date=YYYY-MM-DD op, ofwel ?from=<iso>&to=<iso>.',
                },
            };
        }
        // 1. Datum-specifieke bevraging
        if (date) {
            if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
                reply.status(400);
                return {
                    error: {
                        code: 'INVALID_QUERY_PARAM',
                        message: 'Parameter "date" moet het formaat YYYY-MM-DD hebben.',
                    },
                };
            }
            const snapshot = store.getSnapshot(date);
            if (!snapshot) {
                reply.status(404);
                return {
                    error: {
                        code: 'NOT_FOUND',
                        message: `Geen gidsdata beschikbaar voor datum ${date}.`,
                    },
                };
            }
            const etag = store.getETag([date]);
            reply.header('ETag', etag);
            reply.header('Cache-Control', 'public, max-age=60, stale-while-revalidate=300');
            if (request.headers['if-none-match'] === etag) {
                reply.status(304);
                return reply.send();
            }
            const meta = {
                timeZone: TIME_ZONE,
                date: snapshot.date,
                from: snapshot.from,
                to: snapshot.to,
                lastSuccessfulRefresh: snapshot.publishedAt,
                stale: engine.isSnapshotStale(snapshot),
            };
            const response = {
                meta,
                channels: snapshot.channels,
                programmes: snapshot.programmes,
            };
            return response;
        }
        // 2. Tijdsvenster-specifieke bevraging (?from=&to=)
        if (from && to) {
            // Valideren met dezelfde parser die getLocalDateRange() hieronder gebruikt.
            // new Date() is soepeler dan Temporal en accepteert bijv. "2026-08-30";
            // dat leverde verderop een throw op en dus een 500 op wat eigenlijk een
            // invoerfout is.
            const fromInstant = parseInstant(from);
            const toInstant = parseInstant(to);
            if (fromInstant === null || toInstant === null) {
                reply.status(400);
                return {
                    error: {
                        code: 'INVALID_QUERY_PARAM',
                        message: 'Parameters "from" en "to" moeten ISO-8601 timestamps met tijdzone zijn, ' +
                            'bijvoorbeeld 2026-08-30T00:00:00Z. Een kale datum is niet geldig; ' +
                            'gebruik daarvoor ?date=YYYY-MM-DD.',
                    },
                };
            }
            const fromTime = fromInstant.epochMilliseconds;
            const toTime = toInstant.epochMilliseconds;
            if (toTime <= fromTime) {
                reply.status(400);
                return {
                    error: {
                        code: 'INVALID_QUERY_PARAM',
                        message: 'Parameter "to" moet groter zijn dan "from".',
                    },
                };
            }
            const spanDays = (toTime - fromTime) / (24 * 3600 * 1000);
            if (spanDays > 14) {
                reply.status(422);
                return {
                    error: {
                        code: 'DATE_OUT_OF_RANGE',
                        message: 'Het opgevraagde tijdsvenster mag maximaal 14 dagen beslaan.',
                    },
                };
            }
            const dates = getLocalDateRange(from, to);
            const etag = store.getETag(dates);
            reply.header('ETag', etag);
            reply.header('Cache-Control', 'public, max-age=60, stale-while-revalidate=300');
            if (request.headers['if-none-match'] === etag) {
                reply.status(304);
                return reply.send();
            }
            const mergedProgs = new Map();
            let oldestPublish = null;
            let isAnyStale = false;
            for (const d of dates) {
                const snap = store.getSnapshot(d);
                if (snap) {
                    if (!oldestPublish || snap.publishedAt < oldestPublish) {
                        oldestPublish = snap.publishedAt;
                    }
                    if (engine.isSnapshotStale(snap)) {
                        isAnyStale = true;
                    }
                    for (const p of snap.programmes) {
                        const pStart = new Date(p.start).getTime();
                        const pEnd = new Date(p.end).getTime();
                        if (pStart < toTime && pEnd > fromTime) {
                            const key = `${p.channelId}:${p.id}`;
                            mergedProgs.set(key, p);
                        }
                    }
                }
            }
            const sortedProgs = Array.from(mergedProgs.values()).sort((a, b) => {
                const chA = store.getChannels().find((c) => c.id === a.channelId)?.sortOrder ?? 999;
                const chB = store.getChannels().find((c) => c.id === b.channelId)?.sortOrder ?? 999;
                if (chA !== chB)
                    return chA - chB;
                return new Date(a.start).getTime() - new Date(b.start).getTime();
            });
            const meta = {
                timeZone: TIME_ZONE,
                from,
                to,
                lastSuccessfulRefresh: oldestPublish || new Date().toISOString(),
                stale: isAnyStale,
            };
            const response = {
                meta,
                channels: store.getChannels(),
                programmes: sortedProgs,
            };
            return response;
        }
    });
}
