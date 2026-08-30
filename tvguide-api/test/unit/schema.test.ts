import fs from 'node:fs';
import path from 'node:path';
import { describe, it, expect } from 'vitest';
import { parseProgramsEnvelope, rawProgrammeSchema } from '../../src/sources/tvgids/schema.js';

describe('Schema Validation', () => {
  it('parses real upstream fixture with dictionary format', () => {
    const fixturePath = path.resolve(__dirname, '../fixtures/programs_fixture.json');
    if (fs.existsSync(fixturePath)) {
      const raw = JSON.parse(fs.readFileSync(fixturePath, 'utf-8'));
      const stats = { skippedMalformedProgrammesCount: 0 };
      const parsed = parseProgramsEnvelope(raw, stats);

      expect(parsed.size).toBeGreaterThan(0);
      expect(stats.skippedMalformedProgrammesCount).toBe(0);
    }
  });

  it('parses upstream fixture with array format', () => {
    const fixturePath = path.resolve(__dirname, '../fixtures/programs_array_fixture.json');
    const raw = JSON.parse(fs.readFileSync(fixturePath, 'utf-8'));
    const stats = { skippedMalformedProgrammesCount: 0 };
    const parsed = parseProgramsEnvelope(raw, stats);

    expect(parsed.has('1')).toBe(true);
    const progs = parsed.get('1')!;
    expect(progs.length).toBe(1);
    expect(progs[0].title).toBe('Nederland in beweging');
  });

  it('rejects programme with end time before start time', () => {
    const invalidProg = {
      s: '1787975700',
      e: '1787974500', // e < s
      db_id: '218748382',
      title: 'Invalid Show',
    };

    const result = rawProgrammeSchema.safeParse(invalidProg);
    expect(result.success).toBe(false);
  });

  it('rejects programme with non-decimal or out-of-range db_id', () => {
    const invalidDbId = {
      s: '1787974500',
      e: '1787975700',
      db_id: '99999999999999999999999999', // exceeds 64-bit int
      title: 'Invalid db_id',
    };

    const result = rawProgrammeSchema.safeParse(invalidDbId);
    expect(result.success).toBe(false);
  });

  it('skips malformed programme in bucket and increments stats counter', () => {
    const payload = {
      version: '1.0',
      data: {
        '1': {
          ch_id: '1',
          prog: [
            { s: '100', e: '200', db_id: '123', title: 'Good' },
            { s: '200', e: '100', db_id: '456', title: 'Bad e < s' },
          ],
        },
      },
    };

    const stats = { skippedMalformedProgrammesCount: 0 };
    const result = parseProgramsEnvelope(payload, stats);

    expect(result.get('1')?.length).toBe(1);
    expect(stats.skippedMalformedProgrammesCount).toBe(1);
  });

  it('throws error on corrupt envelope', () => {
    const corruptPayload = {
      invalid: 'no version and no data',
    };

    expect(() => parseProgramsEnvelope(corruptPayload)).toThrow(/Invalid programs envelope/);
  });
});
