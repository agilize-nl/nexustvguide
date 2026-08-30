import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { Temporal } from '@js-temporal/polyfill';
import type { SnapshotStore } from '../store/cache.js';
import { getTodayAmsterdam, formatXmltvTimestamp } from '../store/time.js';
import type { Programme } from '../domain/programme.js';

interface XmltvQuery {
  days?: string;
}

/**
 * Escapes characters for strict XML conformance.
 */
function escapeXml(unsafe?: string | null): string {
  if (!unsafe) return '';
  return unsafe
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

export function registerXmltvRoutes(app: FastifyInstance, store: SnapshotStore): void {
  app.get('/xmltv.xml', async (request: FastifyRequest<{ Querystring: XmltvQuery }>, reply: FastifyReply) => {
    let days = 7;
    if (request.query.days) {
      const parsedDays = parseInt(request.query.days, 10);
      if (!isNaN(parsedDays) && parsedDays > 0 && parsedDays <= 14) {
        days = parsedDays;
      }
    }

    const today = getTodayAmsterdam();
    const dates: string[] = [];
    let current = Temporal.PlainDate.from(today);
    for (let i = 0; i < days; i++) {
      dates.push(current.toString());
      current = current.add({ days: 1 });
    }

    const etag = store.getETag(dates);
    reply.header('ETag', etag);
    reply.header('Cache-Control', 'public, max-age=60, stale-while-revalidate=300');

    if (request.headers['if-none-match'] === etag) {
      reply.status(304);
      return reply.send();
    }

    const channels = store.getChannels();
    const programmesMap = new Map<string, Programme>();

    for (const d of dates) {
      const snap = store.getSnapshot(d);
      if (snap) {
        for (const p of snap.programmes) {
          const key = `${p.channelId}:${p.id}`;
          programmesMap.set(key, p);
        }
      }
    }

    const programmes = Array.from(programmesMap.values()).sort((a, b) => {
      const chA = channels.find((c) => c.id === a.channelId)?.sortOrder ?? 999;
      const chB = channels.find((c) => c.id === b.channelId)?.sortOrder ?? 999;
      if (chA !== chB) return chA - chB;
      return new Date(a.start).getTime() - new Date(b.start).getTime();
    });

    let xml = '<?xml version="1.0" encoding="UTF-8"?>\n';
    xml += '<!DOCTYPE tv SYSTEM "xmltv.dtd">\n';
    xml += '<tv generator-info-name="tvguide-api">\n';

    // 1. Kanalen
    for (const ch of channels) {
      xml += `  <channel id="${escapeXml(ch.id)}">\n`;
      xml += `    <display-name>${escapeXml(ch.name)}</display-name>\n`;
      if (ch.logoUrl) {
        xml += `    <icon src="${escapeXml(ch.logoUrl)}" />\n`;
      }
      xml += '  </channel>\n';
    }

    // 2. Programma's
    for (const prog of programmes) {
      const startXml = formatXmltvTimestamp(prog.start);
      const stopXml = formatXmltvTimestamp(prog.end);

      xml += `  <programme start="${startXml}" stop="${stopXml}" channel="${escapeXml(prog.channelId)}">\n`;
      xml += `    <title lang="nl">${escapeXml(prog.title)}</title>\n`;
      if (prog.description) {
        xml += `    <desc lang="nl">${escapeXml(prog.description)}</desc>\n`;
      }
      if (prog.genre) {
        xml += `    <category lang="nl">${escapeXml(prog.genre)}</category>\n`;
      }
      if (prog.imageUrl) {
        xml += `    <icon src="${escapeXml(prog.imageUrl)}" />\n`;
      }
      if (prog.isPremiere) {
        xml += '    <premiere />\n';
      }
      if (prog.isRerun) {
        xml += '    <previously-shown />\n';
      }
      if (prog.ageRating) {
        xml += '    <rating system="Kijkwijzer">\n';
        xml += `      <value>${escapeXml(prog.ageRating)}</value>\n`;
        xml += '    </rating>\n';
      }
      xml += '  </programme>\n';
    }

    xml += '</tv>\n';

    reply.header('Content-Type', 'application/xml; charset=utf-8');
    return reply.send(xml);
  });
}
