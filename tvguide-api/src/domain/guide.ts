import type { Channel } from './channel.js';
import type { Programme } from './programme.js';

export interface GuideMeta {
  timeZone: string;               // altijd "Europe/Amsterdam"
  date?: string;                  // "YYYY-MM-DD" indien dag-specifiek
  from: string;                   // RFC 3339 UTC start van het venster
  to: string;                     // RFC 3339 UTC eind van het venster
  lastSuccessfulRefresh: string;  // RFC 3339 UTC van data refresh
  stale: boolean;                 // true als snapshot stale is
}

export interface GuideResponse {
  meta: GuideMeta;
  channels: Channel[];
  programmes: Programme[];
}

export interface DaySnapshot {
  date: string;                   // "YYYY-MM-DD" in Europe/Amsterdam
  timeZone: string;               // "Europe/Amsterdam"
  from: string;                   // RFC 3339 UTC [00:00
  to: string;                     // RFC 3339 UTC 00:00)
  sourceFetchedAt: string;        // RFC 3339 UTC
  publishedAt: string;            // RFC 3339 UTC
  channels: Channel[];
  programmes: Programme[];
}
