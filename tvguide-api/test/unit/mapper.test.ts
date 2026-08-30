import { describe, it, expect } from 'vitest';
import { htmlToText, normalizeAgeRating } from '../../src/sources/tvgids/formatters.js';
import { mapProgramme } from '../../src/sources/tvgids/mapper.js';
import type { RawProgramme } from '../../src/sources/tvgids/types.js';

describe('Formatters & Mapper', () => {
  describe('htmlToText', () => {
    it('strips html tags and decodes entities', () => {
      const input = '<html><p>Journaal &amp; Weer met <b>Dionne</b> &eacute;n &#039;special&#039;</p></html>';
      const output = htmlToText(input);
      expect(output).toBe("Journaal & Weer met Dionne én 'special'");
    });

    it('handles empty or null string', () => {
      expect(htmlToText('')).toBeNull();
      expect(htmlToText(null)).toBeNull();
      expect(htmlToText(undefined)).toBeNull();
    });
  });

  describe('normalizeAgeRating', () => {
    it('preserves valid Kijkwijzer codes', () => {
      expect(normalizeAgeRating('AL')).toBe('AL');
      expect(normalizeAgeRating('6')).toBe('6');
      expect(normalizeAgeRating('9')).toBe('9');
      expect(normalizeAgeRating('12')).toBe('12');
      expect(normalizeAgeRating('14')).toBe('14');
      expect(normalizeAgeRating('16')).toBe('16');
      expect(normalizeAgeRating('18')).toBe('18');
      expect(normalizeAgeRating(' 12 ')).toBe('12');
    });

    it('converts non-kijkwijzer or invalid ratings to null', () => {
      expect(normalizeAgeRating('H')).toBeNull();
      expect(normalizeAgeRating('live')).toBeNull();
      expect(normalizeAgeRating('tip')).toBeNull();
      expect(normalizeAgeRating('')).toBeNull();
      expect(normalizeAgeRating(undefined)).toBeNull();
    });
  });

  describe('mapProgramme', () => {
    it('correctly transforms raw programme to domain model', () => {
      const raw: RawProgramme = {
        s: '1787974500',
        e: '1787975700',
        db_id: '218748382',
        title: 'Nederland in beweging',
        descr: '<html><p>Oude omschrijving</p></html>',
        algemene_inhoud: 'Beweeg mee met Nederland in beweging.',
        inhoud: '',
        img: 'https://tvgidsassets.nl/upload/img.jpg',
        subgenre: 'Gymnastiek',
        live: 'false',
        rerun: 'true',
        is_premiere: 'false',
        ei: '6',
      };

      const mapped = mapProgramme(raw, 'npo1');

      expect(mapped.id).toBe('218748382');
      expect(mapped.channelId).toBe('npo1');
      expect(mapped.title).toBe('Nederland in beweging');
      expect(mapped.start).toBe(new Date(1787974500 * 1000).toISOString());
      expect(mapped.end).toBe(new Date(1787975700 * 1000).toISOString());
      expect(mapped.description).toBe('Beweeg mee met Nederland in beweging.');
      expect(mapped.imageUrl).toBe('https://tvgidsassets.nl/upload/img.jpg');
      expect(mapped.genre).toBe('Gymnastiek');
      expect(mapped.isLive).toBe(false);
      expect(mapped.isRerun).toBe(true);
      expect(mapped.isPremiere).toBe(false);
      expect(mapped.ageRating).toBe('6');
      expect(mapped.nlzietId).toBeNull();
    });

    it('falls back to descr if algemene_inhoud and inhoud are empty', () => {
      const raw: RawProgramme = {
        s: '1787974500',
        e: '1787975700',
        db_id: '218748382',
        title: 'Test Show',
        descr: '<p>HTML beschrijving</p>',
        algemene_inhoud: '',
        inhoud: '',
      };

      const mapped = mapProgramme(raw, 'npo1');
      expect(mapped.description).toBe('HTML beschrijving');
    });
  });
});
