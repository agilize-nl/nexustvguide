# Implementation Plan - Review en Verbetering van docs/plan-2026-08-30-bouwplan.md

Review van [`docs/plan-2026-08-30-bouwplan.md`](file:///home/djawiz/Data/Development/_Apps/NexusTVGuide/docs/plan-2026-08-30-bouwplan.md) en het aanbrengen van gerichte verbeteringen en preciseringen zonder regressies of wijzigingen in de vastgestelde architectuurbesluiten.

## Analyse & Bevindingen

Na een grondige inspectie van `docs/plan-2026-08-30-bouwplan.md`, de live `json.tvgids.nl/v4`-API en de broncode van `egeniq/android-tv-program-guide` zijn de volgende verbeterpunten en aanscherpingen geïdentificeerd:

1. **Upstream Response Envelope & Structuur**:
   - `json.tvgids.nl/v4/programs/?day=X&channels=Y` retourneert een object (dictionary) onder `data` met zender-id's als keys (`data: { [chId: string]: { ch_id: string, prog: RawProgramme[] } }`), terwijl calls zonder `channels`-parameter een array retourneren. Dit moet expliciet in het schema en mapper-contract worden vastgelegd om parsingfouten te voorkomen.
2. **Runtime Schema & Validatieregels**:
   - Explicitering van de afkeuringsregels: een ongeldige envelopstructuur verwerpt de hele fetch; een individueel defect programma (bijv. corrupte timestamps `s`/`e`, `e <= s`, niet-decimale `db_id`) wordt overgeslagen met een log en tellertje in `/health`.
   - Age-rating (`ei`): specificatie van normalisatie naar herkenbare Kijkwijzer-waarden (`AL`, `6`, `9`, `12`, `14`, `16`, `18`) of `null` (filtert waarden zoals `live`, `tip`, `H` en lege strings uit).
   - HTML/tekst decodering: verduidelijken dat HTML-entities (zoals `&amp;`, `&eacute;`, `&#039;`) gedecodeerd moeten worden.
3. **Temporal Tijdzone- & Vensterberekening**:
   - Uitwerking van het halfopen interval `[00:00, 00:00)` via TC39 Temporal polyfill (`@js-temporal/polyfill` of `temporal-polyfill`) in `Europe/Amsterdam`.
   - Expliciete documentatie van de conversie naar UTC-instants en robuustheid rond zomertijd/wintertijd (23-uurs en 25-uurs dagen).
4. **API-contract & Headers**:
   - Volledige specificatie van het `GuideMeta`-object (`timeZone`, `from`, `to`, `date`, `lastSuccessfulRefresh`, `stale`).
   - Specificatie van `If-None-Match` en `ETag` (gebaseerd op snapshot hash/versie) voor `304 Not Modified` ondersteuning in OkHttp.
   - Foutafhandeling met concrete error-codes (`INVALID_PARAM`, `OUT_OF_RANGE`, `DATA_UNAVAILABLE`, `INTERNAL_ERROR`).
5. **Disk Snapshot Formaat & Atomaire Opslag**:
   - Snapshot-formaat (`guide-YYYY-MM-DD.json`) en atomaire rename-strategie (tmp-file -> rename).
6. **XMLTV Specificatie**:
   - Juiste XMLTV-headers (`<!DOCTYPE tv SYSTEM "xmltv.dtd">`), tijdstipformaat (`YYYYMMDDHHmmss +0200`/`+0100`) en XML-escaping (`&`, `<`, `>`, `"`, `'`).
7. **Android TV (egeniq fork) Details**:
   - Concrete `setData`-aanroep (`setData(channels, schedulesByChannelId, localDate)`) en `Spanned`-conversie (`SpannedString`).
   - `ThreeTenABP` setup (`AndroidThreeTen.init(this)` in `Application`).
   - Android 11+ package visibility `<queries><package android:name="nl.nlziet"/></queries>` in `AndroidManifest.xml`.
   - Netwerkconfiguratie (HTTPS release, cleartext restrictie per host alleen in debug).

## User Review Required

> [!NOTE]
> Alle 7 vastgestelde besluiten (egeniq-fork, `json.tvgids.nl/v4` normalisatieservice, uitgestelde NLZiet-koppeling, zenderselectie op basis van NLZiet, backend-eerst volgorde, `Europe/Amsterdam` tijdzone, Node.js 24 LTS + TypeScript) blijven 100% gehandhaafd. Er worden geen regressies geïntroduceerd.

## Proposed Changes

### Documentatie

#### [MODIFY] [plan-2026-08-30-bouwplan.md](file:///home/djawiz/Data/Development/_Apps/NexusTVGuide/docs/plan-2026-08-30-bouwplan.md)
- **Fase 0 (Verificatie)**: Upstream payload dictionary vs array formaat preciseren.
- **Fase 1 (Backend)**:
  - Schema & mapper: validatieregels voor `RawProgramme`, timestamp checks, `db_id` validatie, HTML-entity decoding en Kijkwijzer `ageRating` normalisatie.
  - Temporal polyfill: codevoorbeeld voor veilige dagvensterberekening in `Europe/Amsterdam`.
  - API-contract: `GuideMeta` velden, error responses, `ETag` + `If-None-Match` flow en XMLTV generatieregels.
  - Snapshot formaat: JSON schema voor disk storage en atomaire schrijfinstructie.
- **Fase 2 (Android)**:
  - `TvGuideFragment` integratie met `setData(channels, schedulesByChannel, localDate)`.
  - Android package visibility (`<queries>`) en network security config voorbeelden.
- **Fase 3 (NLZiet)** & **Definition of Done**:
  - Aanscherping van acceptatietests en kwaliteitscontroles.

## Verification Plan

### Manual Verification
- Controleren of alle hyperlinks in `docs/plan-2026-08-30-bouwplan.md` correct zijn.
- Valideren dat de voorgestelde TypeScript en Kotlin codevoorbeelden syntactisch correct zijn en aansluiten op de echte bibliotheken (`egeniq/android-tv-program-guide`, TC39 Temporal, Retrofit/OkHttp).
- Verifiëren dat er geen tegenstrijdigheden zijn tussen `doc-2026-08-30-achtergrond.md`, `README.md` en `docs/plan-2026-08-30-bouwplan.md`.
