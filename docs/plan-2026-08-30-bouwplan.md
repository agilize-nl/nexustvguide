# Bouwplan NexusTVGuide

Status: **fase 1 t/m 4 geïmplementeerd en geverifieerd; backend (.171) & Android TV app (EPG-grid, NLZIET-relay, zenderordening, in-app updates) gereed, Shield-validatie open**  
Datum: 30 augustus 2026 (bijgewerkt: 1 september 2026)

Werkdocument met vastgestelde besluiten, concrete implementatiestappen en expliciete
acceptatiecriteria. Bron van de achtergrondinformatie: [`doc-2026-08-30-achtergrond.md`](doc-2026-08-30-achtergrond.md).
De aantallen en het gedrag van de tvgids.nl-API zijn voor het laatst gecontroleerd rond de
lokale datumovergang van **2026-08-29 naar 2026-08-30**.

## Doel en MVP

Het MVP is een reclamevrije Android TV-app voor de NVIDIA Shield die:

- alleen de gekozen NLZiet-zenders toont in een goed navigeerbaar EPG-grid;
- gisteren, vandaag en de komende zeven dagen kan tonen;
- bij uitval van netwerk of bron de laatst geldige gids blijft tonen;
- bij een klik minimaal de NLZiet-app opent;
- dezelfde genormaliseerde gids via REST en XMLTV beschikbaar maakt op het lokale netwerk.

Playback, accounts, favorieten, aanbevelingen, publicatie in de Play Store en rechtstreekse
programma-deeplinks vallen buiten het MVP. Deeplinken wordt pas in fase 3 onderzocht.

## Vastgestelde besluiten

| # | Onderwerp | Besluit |
|---|-----------|---------|
| 1 | UI-laag | Fork van `egeniq/android-tv-program-guide` |
| 2 | Datalaag | `json.tvgids.nl/v4` primair, eigen service normaliseert, **ook XMLTV-output** |
| 3 | NLZiet-koppeling | Uitgesteld tot backend en Android-MVP af zijn |
| 4 | Zenderselectie | Alleen zenders die in NLZiet zitten |
| 5 | Volgorde | Backend eerst, daarna Android |
| 6 | Tijdzone | Kalenderlogica in `Europe/Amsterdam`; tijdstippen intern als UTC-instant |
| 7 | Backend-runtime | Node.js 24 LTS + TypeScript |

## Nog open voor implementatie

- De definitieve NLZiet-zenderlijst en volgorde moeten op het actieve abonnement worden
  gecontroleerd. Tot die tijd is `channels.json` de handmatige waarheid.
- De basis-URL van de backend moet vóór fase 2 vaststaan. Voorkeur: HTTPS via de bestaande
  reverse proxy; anders krijgt alleen de ontwikkelbuild een beperkte cleartext-uitzondering
  voor de LAN-host.

## Architectuur

```
json.tvgids.nl/v4
        |
        v
  tvguide-api  (LXC, Node/TypeScript)
        |
        +--> GET /api/v1/*      -> Android-app (primair)
        +--> GET /xmltv.xml     -> Jellyfin / TiviMate / Kodi (bijvangst)
        |
        v
  egeniq-fork (Android TV, Shield)
        |
        v
  Intent -> NLZiet          [fase 3, nog niet uitgezocht]
```

De service is het enige dat tvgids.nl kent. Die bron is ongedocumenteerd en kan zonder
aankondiging wijzigen; als dat gebeurt pas je één adapter aan in plaats van een app die op
de Shield staat. De XMLTV-output is dezelfde genormaliseerde data in een ander jasje, dus
die kost weinig extra en maakt de gids ook bruikbaar voor andere clients.

---

## Fase 0 — Verificatie (afgerond)

### Wat is bevestigd

**`json.tvgids.nl/v4` leeft.** 122 zenders, 56 met `default=true`. Programmadata compleet
genoeg voor grid, poster-art en badges uit één bron.

```bash
curl 'https://json.tvgids.nl/v4/channels'
curl 'https://json.tvgids.nl/v4/programs/?day=0&channels=1,2,3'
```

**JSON-response envelope en structuur:**
- De endpoint retourneert altijd een envelope met `version`, `versionmessage` en `data`.
- Bij een bevraging met `channels=1,2` is `data` een JSON-object (record/dictionary) waarin
  de sleutels zender-id's zijn: `data: { "1": { "ch_id": "1", "prog": [...] }, ... }`.
- Bij een bevraging zonder `channels`-parameter is `data` een JSON-array van zenderobjecten:
  `data: [ { "ch_id": "1", "prog": [...] }, ... ]`.
- Wanneer een zender geen programma's heeft voor die dag, is `prog` een lege array `[]`.

**`day` is een relatieve integer, geen ISO-datum of lokale kalenderdag.** Een ISO-datum
geeft **HTTP 200 met lege `prog`-arrays** — stil falen. Bovendien zijn de responses
uitzenddag-buckets: om 2026-08-30 00:02 in Amsterdam bevatte `day=0` voor NPO 1 programma's
van ongeveer 2026-08-29 05:35 t/m 2026-08-30 06:15. Eén lokale kalenderdag mag dus nooit
rechtstreeks aan één `day`-offset worden gekoppeld; deel programma's na het ophalen op hun
werkelijke timestamps in.

Negatieve offsets zijn beschikbaar (minstens `day=-3` is waargenomen). `day=13` is gevuld
en `day=14` kan data bevatten, maar die extra dag is niet stabiel genoeg om als contract te
gebruiken; `day=20` levert niets. De service haalt conservatief offsets `-2..13` op. Dat is
genoeg om gisteren volledig samen te stellen en ruim genoeg voor de zeven toekomstige
MVP-dagen.

**egeniq is Leanback/Views, geen Compose.** De library gebruikt `androidx.leanback` +
ConstraintLayout met XML-layouts. De oorspronkelijke Compose-aanname uit `doc-2026-08-30-achtergrond.md`
vervalt daarmee voor het gidsscherm; besluit 1 gaat voor.

**egeniq levert geen package.** Uit hun README: *"we do not provide the library as a
package. You will probably have to fork this project."* Dus vendoren als eigen module.
Toolchain is gezonder dan de commitdatum (aug 2024) doet vermoeden: AGP 8.5.2,
Kotlin 2.0.10, appcompat 1.7.0, leanback 1.0.0, Glide 4.16.0.

### Correcties op `doc-2026-08-30-achtergrond.md`

- **iptv-org heet `tvgids.nl`, niet `tvgids.tv`.** Daarom 404't de daar genoemde
  `guides/nl/tvgids.tv.epg.xml`.
- **Die config gebruikt de JSON-API niet.** `sites/tvgids.nl/tvgids.nl.config.js` scrapet
  `www.tvgids.nl/gids/...` met cheerio en raadt eindtijden door 30 minuten op te tellen en
  achteraf te corrigeren met de starttijd van het volgende programma. Grover dan de
  JSON-API, en `days: 2` tegenover ~14.
- **tvgrabpyAPI ligt stil sinds dec 2022** (28 stars). "Actief onderhouden" klopt niet
  meer. Blijft nuttig als documentatie: `tvgrabbers/sourcematching` bevat leesbare specs.

---

## Fase 1 — Backend: `tvguide-api`

Node.js 24 LTS + TypeScript, in een LXC. Node 20 is sinds 2026-04-30 end-of-life en is
daarom geen geschikte basis meer voor een nieuw project. Begin hier: zonder data is de app
niet te testen.

### Projectstructuur

```
tvguide-api/
  src/
    sources/tvgids/
      client.ts       # HTTP naar json.tvgids.nl, retry + timeout
      schema.ts       # runtime-validatie van de onbetrouwbare externe JSON
      types.ts        # ruwe upstream-vormen (snake_case, strings)
      mapper.ts       # ruw -> domeinmodel. DE enige plek die upstream-vorm kent
      formatters.ts   # HTML entity decoding en age-rating normalisatie
    domain/
      channel.ts      # Channel model
      programme.ts    # Programme model
      guide.ts        # GuideMeta en GuideResponse
    store/
      cache.ts        # in-memory store + JSON-snapshots op disk
      time.ts         # Temporal vensterberekeningen (Europe/Amsterdam)
      refresh.ts      # periodieke verversing en sanity-checks
    output/
      rest.ts         # Fastify/Express routes voor /api/v1/*
      xmltv.ts        # XMLTV generator voor /xmltv.xml
    config/
      channels.json   # zender-mapping, handmatig onderhouden
      env.ts          # getypeerde omgevingsvariabelen
    server.ts         # applicatie-entrypoint
  test/
    fixtures/         # opgeslagen upstream-responses
    unit/
    contract/
```

### Domeinmodel

Bewust anders dan upstream: echte types, geen strings voor getallen/booleans, geen HTML in
teksten en duidelijke scheiding tussen datum/tijd-representaties.

```ts
// domain/channel.ts
export interface Channel {
  id: string;            // stabiele eigen id, bv "npo1"
  sourceId: string;      // tvgids ch_id, bv "1"
  name: string;
  logoUrl: string | null;
  inNlziet: boolean;     // stuurt de zenderselectie (besluit 4)
  nlzietSlug: string | null; // gevuld in fase 3
  sortOrder: number;
}

// domain/programme.ts
export interface Programme {
  id: string;            // db_id; decimale string die in een signed 64-bit Kotlin Long past
  channelId: string;     // verwijst naar Channel.id (bv "npo1"), niet de sourceId
  title: string;
  start: string;         // RFC 3339 UTC-instant, bv. "2026-08-29T18:00:00.000Z"
  end: string;           // idem; presentatie gebeurt in Europe/Amsterdam
  description: string | null;
  imageUrl: string | null;
  genre: string | null;
  isLive: boolean;
  isRerun: boolean;
  isPremiere: boolean;
  ageRating: string | null; // genormaliseerd naar Kijkwijzer-code (bv. "12", "AL") of null
}

// domain/guide.ts
export interface GuideMeta {
  timeZone: string;               // altijd "Europe/Amsterdam"
  date?: string;                  // "YYYY-MM-DD" indien dag-specifiek
  from: string;                   // RFC 3339 UTC start van het venster
  to: string;                     // RFC 3339 UTC eind van het venster
  lastSuccessfulRefresh: string;  // RFC 3339 UTC van oudste snapshot in het venster
  stale: boolean;                 // true als minstens 1 gebruikte snapshot stale is
}

export interface GuideResponse {
  meta: GuideMeta;
  channels: Channel[];
  programmes: Programme[];
}
```

### Ruwe upstream-types en bronvalidatie

Upstream levert data met specifieke eigenaardigheden:

```ts
// sources/tvgids/types.ts
export interface RawProgramme {
  s: string;                 // unix-seconden als string (bv. "1787974500")
  e: string;                 // unix-seconden als string (bv. "1787975700")
  db_id: string;             // numerieke id als string (bv. "218748382")
  title: string;
  descr?: string;            // bevat HTML, bv. "<html><p>...</p></html>"
  inhoud?: string;           // platte tekst
  algemene_inhoud?: string;  // platte tekst
  img?: string;              // URL of leeg
  g_id?: string;             // genre-id numeriek
  subgenre?: string;         // leesbaar genre
  tip?: string;              // "true" / "false"
  rerun?: string;            // "true" / "false"
  live?: string;             // "true" / "false"
  is_premiere?: string;      // "true" / "false"
  ei?: string;               // rommelig veld: "", "6", "12", "H", "live", "tip"
  is_type?: string;          // "film", "serie", etc.
}

export interface RawChannelBucket {
  ch_id: string;
  prog: RawProgramme[];
}

export interface RawProgramsEnvelope {
  version: string;
  versionmessage?: Record<string, unknown>;
  // Bij channels=1,2 is data een Record; zonder channels een Array
  data: Record<string, RawChannelBucket> | RawChannelBucket[];
}
```

Behandel iedere `fetch`-response eerst als `unknown` en valideer de envelope, zenders en
programma's met een runtime-schema (bijv. Zod of TypeBox). TypeScript-interfaces alleen
beschermen niet tegen een stil gewijzigde externe API.

**Validatie- en afkeuringsregels:**
1. **Envelope-validatie:** Als `version` of `data` ontbreekt of een onverwacht type heeft,
   wordt de **hele fetch afgekeurd**. De bestaande cache/snapshot blijft onaangeroerd en er
   wordt een fout gelogd.
2. **Programma-validatie:**
   - `s` en `e` moeten niet-lege numerieke strings zijn; `Number(s)` en `Number(e)` moeten
     eindige getallen groter dan 0 zijn, en `Number(e) > Number(s)`.
   - `db_id` moet een decimale string zijn zonder letters/tekens en moet passen in een
     signed 64-bit integer (`BigInt(db_id) <= 9223372036854775807n`).
   - `title` moet een string zijn (indien leeg, fallback naar `"(Geen titel)"`).
3. **Fouttolerantie per programma:** Een enkel ongeldig programma binnen een verder geldige
   zender/dag wordt overgeslagen (niet de hele dag laten vallen). Dit wordt gelogd als
   waarschuwing en geteld in de `/health`-statistieken (`skippedMalformedProgrammesCount`).

### Mapper

De mapper is de **enige** plek in het hele project die upstream-eigenaardigheden kent en
omzet:

```ts
// sources/tvgids/mapper.ts
import { htmlToText, normalizeAgeRating } from './formatters.js';
import type { RawProgramme } from './types.js';
import type { Programme } from '../../domain/programme.js';

export function mapProgramme(raw: RawProgramme, channelId: string): Programme {
  const startSec = Number(raw.s);
  const endSec = Number(raw.e);

  return {
    id: raw.db_id,
    channelId, // Domein Channel.id (bv. "npo1")
    title: raw.title?.trim() || '(Geen titel)',
    // 1. s en e zijn unix-SECONDEN als string. Niet milliseconden.
    start: new Date(startSec * 1000).toISOString(),
    end: new Date(endSec * 1000).toISOString(),
    // 2. descr bevat HTML en HTML-entities; algemene_inhoud en inhoud zijn platte tekst.
    //    Voorkeur: algemene_inhoud -> inhoud -> htmlToText(descr) met entity decoding.
    description:
      raw.algemene_inhoud?.trim() ||
      raw.inhoud?.trim() ||
      (raw.descr ? htmlToText(raw.descr) : null) ||
      null,
    imageUrl: raw.img?.trim() || null,
    genre: raw.subgenre?.trim() || null,
    // 3. booleans komen als de STRINGS "true"/"false".
    isLive: raw.live === 'true',
    isRerun: raw.rerun === 'true',
    isPremiere: raw.is_premiere === 'true',
    // 4. ei is GEEN schoon veld: waargenomen waarden zijn o.a. '', '6', '12', 'H', 'live', 'tip'.
    //    Alleen geldige Kijkwijzer-ratings doorlaten, rest weggooien naar null.
    ageRating: normalizeAgeRating(raw.ei),
  };
}
```

**Ondersteunende formaathulpfuncties:**
- `htmlToText(html: string): string`: Verwijdert HTML-tags en decodeert entities (zoals
  `&amp;` -> `&`, `&eacute;` -> `é`, `&#039;` -> `'`, `&euro;` -> `€`), en trimt witruimte.
- `normalizeAgeRating(rawEi?: string): string | null`: Accepteert alleen herkenbare
  Kijkwijzer-codes (`"AL"`, `"6"`, `"9"`, `"12"`, `"14"`, `"16"`, `"18"`). Waarden zoals
  `"H"`, `"live"`, `"tip"`, `""` of onbekende strings worden genormaliseerd naar `null`.
- `g_id` is een numeriek genre-id met `subgenre` als leesbare tekst. Gebruik altijd
  `subgenre` als de functionele bron voor `genre` en XMLTV-categorieën.

### Ophalen, Dagvensters en Cachen

De upstream `day`-offset is een implementatiedetail van de bronadapter. De client haalt
uitzenddag-buckets op; de refreshlaag voegt ze samen en indexeert programma's daarna op
lokale kalenderdatum in `Europe/Amsterdam`.

Gebruik voor alle kalender- en tijdzoneberekeningen de TC39 Temporal-standaard via
`@js-temporal/polyfill` (of `temporal-polyfill`). Deel **nooit** door 86.400.000 om dagen te
tellen, want dagen rond de zomertijdwissel duren 23 of 25 uur.

```ts
// store/time.ts
import { Temporal } from '@js-temporal/polyfill';

export const TIME_ZONE = 'Europe/Amsterdam';

/**
 * Berekent het exacte UTC [from, to) venster voor een lokale datum in Amsterdam.
 * Houdt automatisch rekening met 23-uurs en 25-uurs dagen bij zomertijdwissels.
 */
export function getLocalDayUtcWindow(dateStr: string): { from: string; to: string } {
  const plainDate = Temporal.PlainDate.from(dateStr);
  const startZoned = plainDate.toZonedDateTime({ timeZone: TIME_ZONE, plainTime: '00:00:00' });
  const nextDayZoned = plainDate.add({ days: 1 }).toZonedDateTime({ timeZone: TIME_ZONE, plainTime: '00:00:00' });

  return {
    from: startZoned.toInstant().toString(),
    to: nextDayZoned.toInstant().toString(),
  };
}
```

```ts
// sources/tvgids/client.ts
const MIN_PROVIDER_OFFSET = -2;
const MAX_PROVIDER_OFFSET = 13;

export async function fetchProviderDay(offset: number, channelSourceIds: string[]): Promise<RawChannelBucket[]> {
  if (!Number.isInteger(offset) || offset < MIN_PROVIDER_OFFSET || offset > MAX_PROVIDER_OFFSET) {
    throw new OutOfRangeError(offset);
  }
  const url = `https://json.tvgids.nl/v4/programs/?day=${offset}&channels=${channelSourceIds.join(',')}`;
  // timeout 15s; maximaal 3 pogingen met exponential backoff + jitter
}
```

#### Snapshot-opbouw en vensterindeling:
1. Maak voor iedere lokale doel-datum in `Europe/Amsterdam` het halfopen interval
   `[dayStartUtc, dayEndUtc)`.
2. Neem uit alle opgehaalde buckets ieder programma op waarvoor geldt:
   `programme.start < dayEndUtc && programme.end > dayStartUtc`.
3. Een programma dat middernacht overschrijdt, komt hierdoor terecht in beide betreffende
   dagsnapshots. Combineert een API-query meerdere dagen, dan dedupliceert de endpoint op
   `programme.id`.
4. Sorteer programma's per zender oplopend op `start`. Controleer op eventuele overlap of
   gaten en log datakwaliteitswaarschuwingen zonder het programma te laten vallen.

#### Refresh-strategie en disk-persistentie:
- **Startup:** Laad alle geldige snapshots van disk, start direct één asynchrone verversing
  en start daarna de periodieke timers (met een jitter van 1-30 seconden om piekbelasting na
  een reboot te voorkomen).
- **Cyclus:**
  - Haal elke **6 uur** het volledige venster `-2..13` op.
  - Haal elk **uur** de dynamische dagen `-2..2` op en herindexeer alle lokale dagsnapshots
    die door deze buckets worden geraakt.
- **Atomaire disk-write:** Schrijf een samengestelde dagsnapshot eerst naar een tijdelijk
  bestand in dezelfde map (`guide-YYYY-MM-DD.json.tmp.<pid>`) en hernoem dit atomair via
  `fs.promises.rename` naar `guide-YYYY-MM-DD.json`.
- **In-memory publicatie:** Publiceer de nieuwe in-memory snapshot pas nadat validatie en de
  atomaire disk-write beide succesvol zijn afgerond. Laat nooit twee refreshes tegelijk
  lopen (mutex/lock).

#### Snapshot-bestandstructuur (`data/snapshots/guide-YYYY-MM-DD.json`):
```json
{
  "schemaVersion": 1,
  "date": "2026-08-30",
  "from": "2026-08-29T22:00:00.000Z",
  "to": "2026-08-30T22:00:00.000Z",
  "sourceFetchedAt": "2026-08-30T00:05:00.000Z",
  "publishedAt": "2026-08-30T00:05:01.000Z",
  "channels": [
    { "id": "npo1", "sourceId": "1", "name": "NPO 1", "logoUrl": null, "inNlziet": true, "nlzietSlug": null, "sortOrder": 1 }
  ],
  "programmes": [
    {
      "id": "218748382",
      "channelId": "npo1",
      "title": "Nederland in beweging",
      "start": "2026-08-30T04:55:00.000Z",
      "end": "2026-08-30T05:15:00.000Z",
      "description": "Beweeg mee met 'Nederland in beweging'.",
      "imageUrl": "https://tvgidsassets.nl/upload/n/nederland-in-beweging-760133392.jpg",
      "genre": "Gymnastiekprogramma",
      "isLive": false,
      "isRerun": true,
      "isPremiere": false,
      "ageRating": null
    }
  ]
}
```

#### Stale-afhandeling en sanity-checks:
- Snapshots voor gisteren t/m morgen worden na **2 uur** gemarkeerd als stale; overige dagen
  na **8 uur**.
- Een stale snapshot blijft serveerbaar met `meta.stale = true`.
- **Sanity-check per refresh:** Een kandidaat-dag met 0 programma's, een verlies van meer dan
  40% van het programma-aantal ten opzichte van de vorige snapshot, of 0 programma's voor
  een actieve basiszender (bv. NPO 1) wordt als corrupt beschouwd. Log een duidelijke error en
  **behoud de bestaande snapshot op disk en in-memory**.
- Verwijder verlopen dagsnapshots (ouder dan gisteren - 2 dagen) pas nadat een nieuwe geldige
  set is gepubliceerd.

### API-contract

```
GET /api/v1/channels                       -> Channel[]
GET /api/v1/guide?date=2026-08-30          -> GuideResponse { meta, channels, programmes }
GET /api/v1/guide?from=<iso>&to=<iso>      -> GuideResponse over venster heen
GET /xmltv.xml?days=7                      -> application/xml (XMLTV)
GET /health                                -> { status: "ok" | "degraded", ... }
GET /ready                                 -> 200 met bruikbare snapshot, anders 503
```

#### Regels en headers:
1. **Zenders:** `/api/v1/channels` retourneert uitsluitend actieve `inNlziet: true` zenders,
   gesorteerd op `sortOrder`.
2. **Gids per datum:** `/api/v1/guide?date=YYYY-MM-DD` levert het volledige programma-aanbod
   voor die lokale dag in Amsterdam (`[00:00, 00:00)`).
3. **Gids per tijdsvenster:** `/api/v1/guide?from=<iso>&to=<iso>` accepteert geldige RFC 3339
   UTC-instants (maximaal 14 dagen spanne).
4. **Caching en ETag:**
   - Voeg op `/api/v1/*` en `/xmltv.xml` een `ETag`-header toe (bijv. `W/"<hash-van-snapshot-versies>"`).
   - Indien de client een `If-None-Match`-header stuurt die overeenkomt met de actuele ETag,
     geef direct **HTTP `304 Not Modified`** met een lege body.
   - Zet `Cache-Control: public, max-age=60, stale-while-revalidate=300`.
5. **Foutformaat:**
   Alle 4xx- en 5xx-fouten retourneren een uniform JSON-foutformaat:
   ```json
   {
     "error": {
       "code": "INVALID_QUERY_PARAM",
       "message": "Parameter 'date' moet het formaat YYYY-MM-DD hebben."
     }
   }
   ```
   Standaard foutcodes: `INVALID_QUERY_PARAM` (400), `DATE_OUT_OF_RANGE` (422),
   `DATA_UNAVAILABLE` (503), `NOT_FOUND` (404), `INTERNAL_ERROR` (500).
6. **Health & Ready:**
   - `/health` geeft altijd `200` zolang het Node-proces draait, met status `"ok"` of
     `"degraded"`, uptime, aantal geladen zenders/dagen, tijdstip van laatste refresh en
     aantal overgeslagen defecte programma's (`skippedMalformedProgrammesCount`).
   - `/ready` geeft `200` zodra er minimaal één geldige snapshot (vandaag) beschikbaar is, en
     `503` tijdens het initiële opstarten zolang er nog geen data is.

#### XMLTV-specificatie (`GET /xmltv.xml?days=7`):
- Header: `Content-Type: application/xml; charset=utf-8`.
- Formaat:
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <!DOCTYPE tv SYSTEM "xmltv.dtd">
  <tv generator-info-name="tvguide-api">
    <channel id="npo1">
      <display-name>NPO 1</display-name>
      <!-- <icon src="..."/> indien logoUrl niet null is -->
    </channel>
    <programme start="20260830065500 +0200" stop="20260830071500 +0200" channel="npo1">
      <title lang="nl">Nederland in beweging</title>
      <desc lang="nl">Beweeg mee met 'Nederland in beweging'.</desc>
      <category lang="nl">Gymnastiekprogramma</category>
      <icon src="https://tvgidsassets.nl/upload/n/nederland-in-beweging-760133392.jpg"/>
    </programme>
  </tv>
  ```
- **Tijdstip-notatie:** `YYYYMMDDHHmmss ±HHMM`. De tijdzone-offset (`+0200` in de zomer,
  `+0100` in de winter) moet per programma-start/stop worden berekend op basis van
  `Europe/Amsterdam`.
- **XML-escaping:** Alle dynamische tekst (titels, beschrijvingen, genres, kanaalnamen en
  URL-attributen) moet strikt worden ge-escapet voor XML (`&` -> `&amp;`, `<` -> `&lt;`,
  `>` -> `&gt;`, `"` -> `&quot;`, `'` -> `&apos;`).

### Zender-mapping (`config/channels.json`)

Besluit 4 stelt: alleen zenders die in NLZiet zitten. Tot fase 3 is `channels.json` het
handmatig onderhouden configuratiebestand:

```json
[
  { "id": "npo1", "sourceId": "1", "name": "NPO 1", "logoUrl": null, "inNlziet": true, "nlzietSlug": null, "sortOrder": 1 },
  { "id": "npo2", "sourceId": "2", "name": "NPO 2", "logoUrl": null, "inNlziet": true, "nlzietSlug": null, "sortOrder": 2 },
  { "id": "npo3", "sourceId": "3", "name": "NPO 3", "logoUrl": null, "inNlziet": true, "nlzietSlug": null, "sortOrder": 3 },
  { "id": "rtl4", "sourceId": "4", "name": "RTL 4", "logoUrl": null, "inNlziet": true, "nlzietSlug": null, "sortOrder": 4 },
  { "id": "rtl5", "sourceId": "31", "name": "RTL 5", "logoUrl": null, "inNlziet": true, "nlzietSlug": null, "sortOrder": 5 },
  { "id": "sbs6", "sourceId": "36", "name": "SBS 6", "logoUrl": null, "inNlziet": true, "nlzietSlug": null, "sortOrder": 6 },
  { "id": "rtl7", "sourceId": "46", "name": "RTL 7", "logoUrl": null, "inNlziet": true, "nlzietSlug": null, "sortOrder": 7 },
  { "id": "veronica", "sourceId": "480", "name": "Veronica", "logoUrl": null, "inNlziet": true, "nlzietSlug": null, "sortOrder": 8 },
  { "id": "net5", "sourceId": "37", "name": "NET 5", "logoUrl": null, "inNlziet": true, "nlzietSlug": null, "sortOrder": 9 },
  { "id": "rtl8", "sourceId": "92", "name": "RTL 8", "logoUrl": null, "inNlziet": true, "nlzietSlug": null, "sortOrder": 10 }
]
```

- `/v4/channels` levert momenteel geen logo-URL's; laat `logoUrl` op `null` tenzij er een
  stabiele afbeeldingsbron is. Maak het ontbreken van een logo nergens fataal.
- Sportzenders (ESPN, Ziggo Sport) standaard op `inNlziet: false` zetten tot actieve
  beschikbaarheid in het abonnement op de Shield is vastgesteld.

### Tests

Sla echte upstream-responses op als test-fixtures in `test/fixtures/` en test de mapper en
services hiertegen:

- **Mapper & Parser:**
  - `s`/`e` als seconden-strings omzetten naar valide ISO UTC-timestamps.
  - `"true"`/`"false"` strings correct mappen naar TypeScript booleans.
  - Voorkeursvolgorde `algemene_inhoud` -> `inhoud` -> `descr` met correcte entity-decoding.
  - Vreemde `ei`-waarden (`"live"`, `"tip"`, `"H"`, `""`, `"12"`) normaliseren naar
    Kijkwijzer of `null`.
  - Weigeren van corrupte data: `e <= s`, niet-numerieke `db_id` of `db_id` buiten 64-bit Long
    bereik.
- **Tijdzone & Kalenderlogica:**
  - Zomertijdwissel maart (23-uurs dag) en wintertijdwissel oktober (25-uurs dag) met vaste
    mock-klokken testen.
  - Correcte indeling van uitzenddag-buckets (lopend van ~06:00 tot ~06:00) over lokale
    kalenderdagen `[00:00, 00:00)`.
  - Programma's over middernacht verschijnen in beide opeenvolgende dagsnapshots.
- **Store & Caching:**
  - Atomaire snapshot-writes: een geforceerde crash/afbreking halverwege een write laat het
    oude geldige snapshot intact.
  - Sanity-check: een response met 0 programma's of 50% verlies overschrijft de geldige
    snapshot niet.
  - `If-None-Match` levert HTTP `304` bij ongewijzigde snapshot-versie.
- **XMLTV & REST Output:**
  - XMLTV genereert geldige XML die voldoet aan de XMLTV DTD, met correct ge-escapete entiteiten
    en juiste tijdzone-offsets.

### Uitrol in de LXC

- Draai de service via systemd onder een unprivileged user met uitsluitend schrijfrechten op
  de snapshotmap (`/var/lib/tvguide-api/snapshots`).
- Gebruik een `EnvironmentFile` (`/etc/tvguide-api/tvguide-api.env`) voor configuratie:
  `PORT=3000`, `HOST=127.0.0.1`, `SNAPSHOT_DIR=...`, `LOG_LEVEL=info`.
- Reverse proxy (Nginx of Caddy) verzorgt TLS/HTTPS en proxy_pass naar de lokale poort.
- Installeer dependencies met `npm ci --omit=dev` en start met Node.js 24 LTS.

---

## Fase 2 — Android: fork van egeniq

Pas starten wanneer `/api/v1/guide` live draait en echte data retourneert.

> [!NOTE]
> Zie [`plan-2026-08-31-channel-ordering.md`](plan-2026-08-31-channel-ordering.md) voor het gedetailleerde plan en de implementatie van zendervolgorde en zichtbaarheid via het ordenscherm.

### Opzetten

```bash
git clone https://github.com/egeniq/android-tv-program-guide.git tvguide-android
cd tvguide-android
git remote rename origin upstream
git remote add origin <eigen-repository-url>
```

- Fork de repository met behoud van Git-history.
- Laat `library/` intact. Hernoem `demo/` naar `app/` of maak een schone `app/`-module aan.
- Behoud de Apache-2.0 licentie en copyrightheaders van Egeniq.

### Contracten en Library-koppeling

De Egeniq-library stelt de volgende interfaces:

```kotlin
// Data-model voor kanalen in het grid
data class ChannelItem(
    override val id: String,
    val channelName: String,
    override val imageUrl: String?
) : ProgramGuideChannel {
    override val name: Spanned
        get() = SpannedString(channelName)
}
```

```kotlin
// Mappen van API-programma naar Schedule
fun Programme.toProgramGuideSchedule(): ProgramGuideSchedule<Programme> {
    return ProgramGuideSchedule.createScheduleWithProgram(
        id = this.id.toLong(), // backend garandeert signed 64-bit Long
        startsAt = org.threeten.bp.Instant.parse(this.start),
        endsAt = org.threeten.bp.Instant.parse(this.end),
        isClickable = true,
        displayTitle = this.title,
        program = this // generic payload
    )
}
```

**Belangrijke library-vereisten:**
- **ThreeTenABP:** De library gebruikt `org.threeten.bp.Instant` en `LocalDate`. Initialiseer
  dit verplicht in de `Application`-klasse:
  ```kotlin
  class TvGuideApp : Application() {
      override fun onCreate() {
          super.onCreate()
          AndroidThreeTen.init(this)
      }
  }
  ```
- **Unieke IDs:** `ProgramGuideSchedule.id` moet uniek zijn over alle geladen programma's in
  het actieve overzicht.

### Fragment-configuratie (`TvGuideFragment`)

Overschrijf de standaard Engelse/UTC configuratie van `ProgramGuideFragment`:

```kotlin
class TvGuideFragment : ProgramGuideFragment<Programme>() {
    override val DISPLAY_LOCALE = Locale("nl", "NL")
    override val DISPLAY_TIMEZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
    override val SELECTABLE_DAYS_IN_PAST = 1
    // Egeniq gebruikt `until` (exclusief bovengrens): 8 toont vandaag t/m +7 dagen.
    override val SELECTABLE_DAYS_IN_FUTURE = 8
    override val DISPLAY_CURRENT_TIME_INDICATOR = true
    override val USE_HUMAN_DATES = true

    override fun requestingProgramGuideFor(localDate: LocalDate) {
        viewModel.loadGuideForDate(localDate)
    }

    override fun requestRefresh() {
        viewModel.loadGuideForDate(currentDate)
    }

    override fun onScheduleClicked(programGuideSchedule: ProgramGuideSchedule<Programme>) {
        launchNlziet()
    }

    fun renderGuide(channels: List<ProgramGuideChannel>, scheduleMap: Map<String, List<ProgramGuideSchedule<Programme>>>, date: LocalDate) {
        setData(channels, scheduleMap, date)
        setState(State.Content)
    }
}
```

### App-laag, Netwerk & Offline Fallback

- **Stack:** Retrofit, OkHttp met cache, Moshi (of kotlinx.serialization), Glide (reeds
  aanwezig in library), AndroidX ViewModel + StateFlow.
- **Android 11+ Package Visibility:** Declareer `nl.nlziet` expliciet in `AndroidManifest.xml`:
  ```xml
  <queries>
      <package android:name="nl.nlziet" />
  </queries>
  ```
- **Netwerkbeveiliging:** Gebruik HTTPS voor productie. Sta cleartext LAN-HTTP uitsluitend
  toe in debug-builds via `res/xml/network_security_config.xml`:
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <network-security-config>
      <domain-config cleartextTrafficPermitted="true">
          <domain includeSubdomains="false">192.168.1.100</domain> <!-- voorbeeld LAN IP -->
      </domain-config>
  </network-security-config>
  ```
  Neem **nooit** de algemene `android:usesCleartextTraffic="true"` over in release builds.
- **Offline en Caching:**
  - Prefetch aangrenzende dagen zodat D-pad navigatie door dagfilters direct reageert.
  - Sla de laatst succesvol opgehaalde gids per dag op disk (Room of JSON in app storage).
  - Toon bij een netwerkfout de laatst bekende gids met een subtiele toast/balk ("Laatst
    bijgewerkt om..."), in plaats van een leeg foutscherm.

### Klikgedrag tot fase 3

Laat een klik op een programma direct de NLZiet-app starten:

```kotlin
private fun launchNlziet() {
    val intent = requireContext().packageManager.getLaunchIntentForPackage("nl.nlziet")
    if (intent != null && intent.resolveActivity(requireContext().packageManager) != null) {
        startActivity(intent)
    } else {
        Toast.makeText(requireContext(), "NLZiet app is niet geïnstalleerd", Toast.LENGTH_LONG).show()
    }
}
```

---

## Fase 3 — NLZiet-koppeling & Verrijkingslaag (EPG-mapping geïmplementeerd, Shield-validatie open)

> **Uitgewerkt vervolgplan:**
> [`plan-2026-08-30-nlziet-epg-mapping.md`](plan-2026-08-30-nlziet-epg-mapping.md). Dit beschrijft het
> exacte EPG-datacontract, de strikte zender-/tijd-/titelmatching, de Android
> replay- en live-routes, de veilige migratie van `nlzietId` en de Shield-acceptatietest.

### Geanalyseerde APK & Intent Filters
- **Appversie & Build:** NLZIET Android TV v5.15.3 (build `740504`, package `nl.nlziet`).
- **Main Leanback Launch Activity:** `nl.nlziet.tv.app.di.tv.InjectActivity`
  (`android.intent.action.MAIN` + `android.intent.category.LEANBACK_LAUNCHER`).
- **TV-afspeeldeeplink:** `nlziet://watchnext/<contentItemId>`. De TV-build maakt deze URI
  zelf aan voor Android TV Watch Next en verwerkt hem in `InjectActivity.onNewIntent()`.
  Een koude start verwerkt de initiële URI niet; NexusTVGuide stuurt daarom na 1,5 seconde een
  identieke intent. Een directe emulatorproef met `Jazzportretten` op 30 augustus 2026
  opende daarmee aantoonbaar de geselecteerde uitzending.
- **Niet voor de TV-build:** de eerder veronderstelde routes
  `nlziet://open/epg/<contentItemId>/<assetId>` en `nlziet://open/vod/<contentId>` openden
  in dezelfde TV-build alleen het dashboard. Zij mogen niet voor een gidsklik worden gebruikt.
- **Web/App Link:** `https://app.nlziet.nl/vod/<id>` is eveneens geregistreerd. Dynamic Links
  op `https://nlzietshare.page.link` bestaan ook, maar zijn geen stabiel contract voor de app.
- **Package Visibility:** Geconfigureerd in `AndroidManifest.xml` via `<queries>` voor `nl.nlziet`, `nlziet://` en `nlzietshare.page.link`.

### Verrijkingsarchitectuur (`tvguide-api/src/enrichment/nlziet/`)
1. **Catalogus & Scraper (`catalog.ts`):** Verwerkt NLZIET sitemaps (`https://www.nlziet.nl/nl/program-sitemap.xml`) en programmapagina's, met persistente caching op disk (`data/nlziet-catalog.json`) en offline seed fallback (`config/nlziet_catalog_seed.json`).
2. **Nederlandse Titelnourmalisatie (`normalizer.ts`):** Stript accenten, broadcast-labels (`(herhaling)`, `(live)`), timestamps (`20:00`), normaliseert ampersands (`&` -> `en`) en genereert veilige slugs.
3. **Gelaagde Matcher Engine (`matcher.ts`):** Resolutie via regex/overrides (`config/nlziet_overrides.json`), exacte slugs, aliassen en hoofdtitel/ondertitel splitsing.
4. **Verrijking in Refresh Pipeline (`store/refresh.ts`):** Vult `programme.nlzietId` automatisch in tijdens periodieke gidsverversingen en levert verrijkte data via `/api/v1/guide`.

`nlzietId` is een afspeelbare NLZIET-VOD-content-ID, geen `tvgids.nl`-programma-ID. De huidige
matcher gebruikt titel/alias en kan daarom alleen een bijpassende NLZIET-titel of catalogusitem
openen; hij bewijst nog niet dat het exact dezelfde aflevering of live-uitzending is. Exacte
EPG-doorschakeling vereist een betrouwbare NLZIET-EPG-ID plus zender- en tijdmapping. Maak een
match zonder zo'n bron niet sterker in de UI of documentatie dan hij is.

De volgende stap is niet het versoepelen van de titelmatcher, maar de implementatie van het
strikte EPG-plan waarnaar hierboven wordt verwezen. Tot die tijd blijft een gewone
NLZIET-launch de veilige fallback.

---

## Risico's & Mitigaties

| Risico | Kans | Impact | Mitigatie |
|--------|------|--------|-----------|
| `tvgids.nl` wijzigt API-formaat | Middel | Hoog | Runtime schema-validatie; adapter geïsoleerd; snapshots blijven intact op disk; iptv-org scraper als fallback |
| Stil falen (HTTP 200 met lege arrays) | **Hoog** | Hoog | Sanity-checks bij elke refresh; nooit overschrijven met 0 programma's; stale snapshot blijven tonen |
| Geen NLZiet deep-links mogelijk | Middel | Laag | Basis launch-intent werkt altijd; gidservaring is alsnog superieur aan NLZiet-gids |
| Egeniq library verouderd | Laag | Middel | Library is gevorkt en in eigen beheer; dependencies gepind; leanback/views is stabiel op Android TV |
| Zomertijd/wintertijd fouten | Middel | Middel | UTC-instants intern; Temporal polyfill met `Europe/Amsterdam`; specifieke unittests voor 23u/25u dagen |
| Corruptie van snapshot na crash | Laag | Hoog | Schrijven naar tijdelijk bestand en atomair hernoemen (`fs.rename`) |

## Wat als tvgids.nl stopt of blokkeert

De architectuur is voorbereid op uitwijk:
- Maak een tweede adapter aan onder `sources/iptvorg/` die iptv-org's scraper aanroept en
  hetzelfde `RawProgramme`-formaat levert.
- Omdat het domeinmodel, de REST-output, de XMLTV-output en de Android-app volledig zijn
  ontkoppeld, hoeft er buiten de bronadapter geen regel code te veranderen.

---

## Definition of Done per fase

### Fase 1 is klaar wanneer:
- [x] Alle unit-, schema- en contracttests slagen (incl. zomertijdwissels en fixture-tests).
- [x] De service na herstart zonder internetverbinding de gids kan serveren vanuit disk-snapshots.
- [x] `/api/v1/guide` (zowel met `?date=` als met `?from=&to=`), `/api/v1/channels`, `/xmltv.xml`,
      `/health` en `/ready` exact aan het contract voldoen.
- [x] HTTP caching headers (`ETag`, `If-None-Match`, `Cache-Control`) correct werken en `304` teruggeven.
- [x] XMLTV-output valide XML genereert die geaccepteerd wordt door minstens één externe client (TiviMate of Jellyfin).
- [x] De systemd-service in de LXC stabiel draait onder een unprivileged user met HTTPS reverse proxy.

### Fase 2 is klaar wanneer:
- [x] Het EPG-grid op de Shield vloeiend navigeert via D-pad (links/rechts door de tijd, op/neer door zenders).
- [x] Dagfilters tonen: gisteren, vandaag en +1 t/m +7 dagen; wisselen tussen dagen werkt vlot.
- [x] Jump-to-live en huidige-tijd-indicator kloppen visueel en qua tijdzone (`Europe/Amsterdam`).
- [x] Bij tijdelijk netwerkverlies blijft de laatst bekende gids zichtbaar.
- [x] Een klik op een programma opent betrouwbaar de NLZiet-app (of toont een nette melding indien niet aanwezig).
- [x] Zendervolgorde en zichtbaarheid instelbaar via ordenscherm ([`plan-2026-08-31-channel-ordering.md`](plan-2026-08-31-channel-ordering.md)).
- [x] Bij terugkeer uit NLZiet of instellingenmenu's blijft de gids exact op datum, tijdlijn en scrollpositie staan (geen sprong naar willekeurig tijdstip).
- [x] Bij terugkeer uit NLZiet wordt het eerder geselecteerde programma automatisch hersteld als actieve selectie zonder NLZiet opnieuw te triggeren.

### Fase 3 is klaar wanneer:
- [x] De geteste NLZiet-appversie en de ondersteunde VOD-deeplink-URI zijn vastgelegd.
- [x] Deeplinks vallen automatisch terug op de launch-intent bij falen.
- [x] NLZIET-matcher en verrijkingslaag in `tvguide-api` verrijkt gidsdata met `nlzietId`.
- [x] De koude-startafwijking van de TV-build wordt afgevangen met een geteste retry.
- [ ] Op de Shield is bevestigd dat een geselecteerd, verrijkt programma de verwachte
      NLZIET-uitzending opent.

### Fase 4 is klaar wanneer:
- [x] In-app update endpoints en atomaire publicatietooling gereed zijn.
- [x] Android updater met PackageInstaller.Session, single-flight download en preflight-checks is geïmplementeerd.
- [x] D-pad bedienbare update-dialoog met release notes en focusmanagement is afgerond ([`plan-2026-08-31-in-app-updates.md`](plan-2026-08-31-in-app-updates.md)).

---

## Werkvolgorde

1. **Backend Basis (`tvguide-api`):** Node.js 24 setup, runtime-schema's (Zod/TypeBox), `RawProgramme` types, mapper en fixture tests.
2. **Opslag & Refresh:** Atomaire disk-snapshots, Temporal dagvenster-berekening, achtergrond-refreshtimer en sanity checks.
3. **API Endpoints & XMLTV:** Fastify/Express routes voor `/api/v1/*`, `/xmltv.xml`, ETag/304 handling, `/health` en `/ready`.
4. **Zenderconfiguratie:** `config/channels.json` afstemmen op het NLZiet-aanbod.
5. **LXC Deployment & XMLTV Validatie:** Uitrollen in LXC en XMLTV testen in TiviMate/Jellyfin.
6. **Android Project Setup:** Egeniq forken, app-module inrichten, ThreeTenABP initialiseren, locale en tijdzone instellen.
7. **Android ViewModel & Grid:** Retrofit client, offline disk-cache, UI data-binding via `setData()`, launch-intent bij klik.
8. **Testen op de Shield:** D-pad navigatie, netwerkfoutafhandeling en zomertijdovergangen valideren.
9. **Fase 3:** NLZiet package dumpen via ADB en deeplinks implementeren.
10. **Fase 4 (In-App Updates & Releases):** In-app update API (`tvguide-api`), package installer staging (`PackageInstaller.Session`), D-pad dialoog en release-publicatietooling conform [`docs/plan-2026-08-31-in-app-updates.md`](plan-2026-08-31-in-app-updates.md).

---

## Referenties

- [tvgids.nl v4-zenders](https://json.tvgids.nl/v4/channels) en
  [voorbeeld van een programmaresponse](https://json.tvgids.nl/v4/programs/?day=0&channels=1)
- [egeniq/android-tv-program-guide](https://github.com/egeniq/android-tv-program-guide),
  inclusief [dagselectie in `ProgramGuideFragment`](https://github.com/egeniq/android-tv-program-guide/blob/master/library/src/main/java/com/egeniq/androidtvprogramguide/ProgramGuideFragment.kt)
  en [het schedule-contract](https://github.com/egeniq/android-tv-program-guide/blob/master/library/src/main/java/com/egeniq/androidtvprogramguide/entity/ProgramGuideSchedule.kt)
- [TC39 Temporal Proposal & Polyfill](https://tc39.es/proposal-temporal/)
- [Officiële Node.js-releases en onderhoudsstatus](https://nodejs.org/en/about/previous-releases)
- [Android package visibility](https://developer.android.com/training/package-visibility) en
  [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)
- [XMLTV DTD](https://github.com/XMLTV/xmltv/blob/master/xmltv.dtd)
