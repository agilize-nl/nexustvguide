import type { Channel } from './channel.js';
import type { Programme } from './programme.js';
export interface GuideMeta {
    timeZone: string;
    date?: string;
    from: string;
    to: string;
    lastSuccessfulRefresh: string;
    stale: boolean;
}
export interface GuideResponse {
    meta: GuideMeta;
    channels: Channel[];
    programmes: Programme[];
}
export interface DaySnapshot {
    date: string;
    timeZone: string;
    from: string;
    to: string;
    sourceFetchedAt: string;
    publishedAt: string;
    channels: Channel[];
    programmes: Programme[];
}
