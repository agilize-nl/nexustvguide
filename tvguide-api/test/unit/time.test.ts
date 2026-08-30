import { describe, it, expect } from 'vitest';
import {
  getLocalDayUtcWindow,
  getAmsterdamDateString,
  getLocalDateRange,
  formatXmltvTimestamp,
} from '../../src/store/time.js';

describe('Temporal Time calculations in Europe/Amsterdam', () => {
  it('calculates correct UTC window for a regular summer day', () => {
    const window = getLocalDayUtcWindow('2026-08-30');
    // In August (CEST, UTC+2), 00:00 local time is 22:00 UTC previous day
    expect(window.from).toBe('2026-08-29T22:00:00Z');
    expect(window.to).toBe('2026-08-30T22:00:00Z');
  });

  it('calculates correct UTC window for a regular winter day', () => {
    const window = getLocalDayUtcWindow('2026-01-15');
    // In January (CET, UTC+1), 00:00 local time is 23:00 UTC previous day
    expect(window.from).toBe('2026-01-14T23:00:00Z');
    expect(window.to).toBe('2026-01-15T23:00:00Z');
  });

  it('correctly handles Spring DST transition (23-hour day in March)', () => {
    // In 2026, DST starts on Sunday March 29 (02:00 -> 03:00)
    const window = getLocalDayUtcWindow('2026-03-29');
    // 2026-03-29 00:00 is UTC+1 (2026-03-28T23:00:00Z)
    // 2026-03-30 00:00 is UTC+2 (2026-03-29T22:00:00Z)
    // Total duration: 23 hours!
    expect(window.from).toBe('2026-03-28T23:00:00Z');
    expect(window.to).toBe('2026-03-29T22:00:00Z');

    const durationMs = new Date(window.to).getTime() - new Date(window.from).getTime();
    expect(durationMs).toBe(23 * 3600 * 1000);
  });

  it('correctly handles Autumn DST transition (25-hour day in October)', () => {
    // In 2026, DST ends on Sunday October 25 (03:00 -> 02:00)
    const window = getLocalDayUtcWindow('2026-10-25');
    // 2026-10-25 00:00 is UTC+2 (2026-10-24T22:00:00Z)
    // 2026-10-26 00:00 is UTC+1 (2026-10-25T23:00:00Z)
    // Total duration: 25 hours!
    expect(window.from).toBe('2026-10-24T22:00:00Z');
    expect(window.to).toBe('2026-10-25T23:00:00Z');

    const durationMs = new Date(window.to).getTime() - new Date(window.from).getTime();
    expect(durationMs).toBe(25 * 3600 * 1000);
  });

  it('formats XMLTV timestamps with accurate Amsterdam timezone offset', () => {
    // Summer (UTC+2)
    const summerXmltv = formatXmltvTimestamp('2026-08-30T04:55:00.000Z');
    // 04:55 UTC = 06:55 CEST (+0200)
    expect(summerXmltv).toBe('20260830065500 +0200');

    // Winter (UTC+1)
    const winterXmltv = formatXmltvTimestamp('2026-01-15T04:55:00.000Z');
    // 04:55 UTC = 05:55 CET (+0100)
    expect(winterXmltv).toBe('20260115055500 +0100');
  });

  it('returns date range across days', () => {
    const range = getLocalDateRange('2026-08-29T22:00:00.000Z', '2026-08-31T22:00:00.000Z');
    expect(range).toContain('2026-08-30');
    expect(range).toContain('2026-08-31');
  });
});
