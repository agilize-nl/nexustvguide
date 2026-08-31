# Uitvoeringsplan — Optie 1: volledig standalone Android-app ("serverless")

Status: **voorstel, nog niet geïmplementeerd**
Datum: 31 augustus 2026
Betreft: verhuizing van de `tvguide-api`-logica (ophalen `json.tvgids.nl/v4`, normalisatie,
NLZIET-EPG-matching, caching) naar de Kotlin Android-app, zodat één `.apk` op de Shield
zonder LXC, Docker of homelab-afhankelijkheid werkt.

Gerelateerd: [`PLAN.md`](PLAN.md) (bouwplan backend + app),
[`nlziet-epg-mapping-plan.md`](nlziet-epg-mapping-plan.md) (de matcher die hier wordt geport),
[`channel-ordering-plan.md`](channel-ordering-plan.md) (zendervolgorde).

---

## 1. Doel en scope

### Doel

De app haalt zijn gidsdata rechtstreeks bij de bronnen op en bewaart die lokaal. Na
installatie is er geen enkele afhankelijkheid meer van een draaiende backend op het LAN.

### In scope

- Port van de ingest-, normalisatie-, matching- en cachelogica naar Kotlin.
- Lokale persistentie (Room) met dezelfde stale-semantiek als de backend.
- Achtergrondverversing via WorkManager.
- Ongewijzigd gedrag van de UI-laag en van `NlzietLauncher`.

### Buiten scope

- XMLTV-output voor Jellyfin/TiviMate/Kodi. Dit is een **bewuste functionele regressie**;
  zie [§9 Gevolgen en afwegingen](#9-gevolgen-en-afwegingen).
- Playback, accounts, favorieten, Play Store-publicatie.
- Verwijderen van `tvguide-api` uit de repository. Die blijft voorlopig staan als
  referentie-implementatie en als XMLTV-leverancier.

### Uitgangspunt

De backend blijft tijdens de hele migratie werkend. De app krijgt een schakelaar tussen
`REMOTE` en `LOCAL`, zodat de standalone modus incrementeel te valideren is en er altijd
een terugvalpad bestaat.

---

## 2. Geverifieerde feiten

Alle onderstaande waarden zijn op **31 augustus 2026** live gemeten vanaf de
ontwikkelmachine, niet uit documentatie overgenomen.

### 2.1 Bronnen zijn tokenloos bereikbaar

| Bron | Endpoint | Status |
|------|----------|--------|
| tvgids.nl | `GET https://json.tvgids.nl/v4/channels` | `200`, 12.367 bytes |
| tvgids.nl | `GET https://json.tvgids.nl/v4/programs/?day=0&channels=<20 ids>` | `200`, 529.521 bytes |
| NLZIET | `GET https://api.nlziet.nl/v9/epg/programlocations?date=…&channel=…` | `200`, 62.488 bytes (1 zender) |

Geen van beide vraagt om een account-token. **Dit is de kernaanname van dit plan** en
tevens het grootste risico: zie [§10 Risico's](#10-risicos).

### 2.2 TLS werkt op minSdk 21

Beide hosts antwoorden met `--tlsv1.2 --tls-max 1.2` een `200`. De huidige `minSdk 21`
kan dus blijven staan; er is geen TLS 1.3 vereist. Wel geldt de bekende Android
5.0-beperking dat TLS 1.2 daar niet altijd standaard aanstaat — zie [§7.4](#74-minsdk-21).

### 2.3 Datavolume per dag (20 actieve NLZiet-zenders)

| Meting | Waarde |
|--------|--------|
| tvgids `day=0`, alle 20 zenders, ongecomprimeerd | 529.521 bytes |
| idem met `Accept-Encoding: gzip` | 84.801 bytes (**6,2× kleiner**) |
| aantal programma's in die respons | 577, verdeeld over 20 buckets |
| NLZIET EPG, 1 dag, alle 20 zenders in één request | 661.673 bytes, ~0,06 s |

**Consequentie:** gzip is verplicht. Een volledige cyclus van 16 tvgids-dagen kost
ongecomprimeerd ~8,5 MB en gzipped ~1,4 MB. OkHttp voegt `Accept-Encoding: gzip`
automatisch toe zolang je die header niet zelf zet — expliciet zelf zetten schakelt de
transparante decompressie uit.

### 2.4 Het NLZIET-EPG-venster is asymmetrisch

Gemeten aantal `programLocations` voor `npo1` per dag-offset:

| Offset | Datum | Aantal |
|--------|-------|--------|
| −8 | 2026-08-23 | **0** |
| −7 | 2026-08-24 | 76 |
| 0 | 2026-08-31 | 71 |
| +7 | 2026-09-07 | 63 |
| +8 | 2026-09-08 | **60** |

De ondergrens van −7 dagen is dus hard, maar de bovengrens ligt **verder dan +7**. De
huidige `isDateInEpgWindow` in [`epg-client.ts`](../tvguide-api/src/enrichment/nlziet/epg-client.ts)
kapt bij `diffDays <= 7` af en laat daarmee bruikbare toekomstige matches liggen. Neem dit
in de Kotlin-port **niet ongewijzigd over**; zie [taak 4.4](#44-nlzietepgwindow).

### 2.5 De EPG-respons bevat meer velden dan de backend gebruikt

De live respons bevat onder meer `image.landscapeUrl`, `seriesId`, `isMovie`,
`contentProvider` en `firstBroadcast`. De backend negeert die. Voor de port is dat prima
(de zod-schema's negeren onbekende velden), maar `image.landscapeUrl` is een gratis
kwaliteitswinst voor programma's waar tvgids geen `img` levert — genoteerd als optionele
vervolgstap in [§11](#11-optionele-vervolgstappen).

---

## 3. Architectuur

### 3.1 Nu

```
json.tvgids.nl/v4  +  api.nlziet.nl/v9
        |
        v
  tvguide-api (LXC, Node/TypeScript)   <-- single point of failure
        |
        +--> GET /api/v1/*   -> Android-app
        +--> GET /xmltv.xml  -> Jellyfin / TiviMate / Kodi
```

### 3.2 Straks

```
json.tvgids.nl/v4  +  api.nlziet.nl/v9
        |
        v  (rechtstreeks vanaf de Shield)
  Android-app
    ingest -> normalisatie -> EPG-matching -> Room
                                               |
                                               v
                                        GuideViewModel -> EPG-grid
```

### 3.3 Pakketindeling

Het Node-project wordt vrijwel 1-op-1 gespiegeld, zodat elke Kotlin-klasse een
aanwijsbare TypeScript-herkomst heeft:

| Node | Kotlin (`com.nexustvguide.app.…`) |
|------|-----------------------------------|
| `src/domain/*.ts` | `core.domain` — `Channel`, `Programme`, `NlzietProgrammeTarget` |
| `src/sources/tvgids/client.ts` | `core.source.tvgids.TvgidsClient` |
| `src/sources/tvgids/schema.ts` | `core.source.tvgids.TvgidsParser` |
| `src/sources/tvgids/mapper.ts` | `core.source.tvgids.ProgrammeMapper` |
| `src/sources/tvgids/formatters.ts` | `core.source.tvgids.Formatters` |
| `src/enrichment/nlziet/epg-client.ts` | `core.nlziet.NlzietEpgClient` |
| `src/enrichment/nlziet/epg-matcher.ts` | `core.nlziet.NlzietEpgMatcher` |
| `src/store/time.ts` | `core.time.GuideTime` |
| `src/store/cache.ts` | `data.local` — Room DAO's + entities |
| `src/store/refresh.ts` | `core.refresh.RefreshEngine` |
| `src/output/rest.ts` | `data.repository.LocalGuideRepository` |
| *(geen equivalent)* | `work.GuideRefreshWorker` |

**Ontwerpregel:** alles onder `core.*` is **pure Kotlin/JVM zonder Android-imports**. Zo
draait de complete port onder `testImplementation` als snelle JVM-unittest, zonder
Robolectric. Alleen `data.local` en `work` raken het Android-framework.

---

## 4. De port, per onderdeel

Onder elk kopje staat wat er letterlijk overgaat en waar Kotlin een expliciete
beslissing afdwingt die JavaScript verborgen hield.

### 4.1 Tijd en tijdzone

De backend gebruikt `@js-temporal/polyfill` met `Europe/Amsterdam`. De app heeft
**ThreeTenABP al als dependency** en `GuideViewModel` gebruikt het al. Er is dus geen
nieuwe bibliotheek nodig.

| `time.ts` | Kotlin |
|-----------|--------|
| `TIME_ZONE = 'Europe/Amsterdam'` | `val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")` |
| `getLocalDayUtcWindow(date)` | `LocalDate.atStartOfDay(ZONE).toInstant()`, plus `plusDays(1)` |
| `getAmsterdamDateString(iso)` | `Instant.parse(iso).atZone(ZONE).toLocalDate()` |
| `getTodayAmsterdam()` | `LocalDate.now(ZONE)` |
| `getLocalDateRange(from, to)` | lus over `LocalDate` |
| `formatXmltvTimestamp` | **vervalt** (geen XMLTV in de app) |

`LocalDate.atStartOfDay(zone)` lost DST identiek op aan Temporal: bij de 23-uursdag in
maart en de 25-uursdag in oktober levert het exact het juiste UTC-venster. De
DST-testcases uit de backend worden meegenomen ([taak 8.1](#81-jvm-unittests)).

**Let op:** `NexusTVGuideApp` moet `AndroidThreeTen.init(this)` al aanroepen vóórdat
`core.time` wordt gebruikt; controleer dit bij het opzetten van de Worker, want een Worker
kan draaien zonder dat je Activity ooit is gestart.

### 4.2 tvgids-ingest

`TvgidsClient` wordt een dunne OkHttp-wrapper met dezelfde parameters als nu: base-URL
`https://json.tvgids.nl/v4`, 10 s timeout, 2 retries, exponentiële backoff afgetopt op
3 s, en de bestaande `User-Agent`.

De zod-validatie uit `schema.ts` wordt handgeschreven Kotlin-parsing. Dit is het punt
waar Kotlin strenger is dan TypeScript en waar de meeste bugs kunnen ontstaan:

- **`data` is polymorf.** De envelope levert `Record<ch_id, bucket>` óf een array van
  buckets. De live meting gaf een **object** met 20 sleutels. Gson kan dit niet in één
  datatype vangen; gebruik een `JsonElement` en vertak op `isJsonObject` / `isJsonArray`.
  Sla deze tak niet over omdat de meting toevallig een object gaf — de backend ondersteunt
  beide en dat is er niet zonder reden in gekomen.
- **Alles is een string.** `s`, `e` en `db_id` komen binnen als decimale strings. De
  regexvalidatie (`^\d+$`), de eis `end > start` en de 64-bit-grens op `db_id` blijven
  precies zoals in `rawProgrammeSchema`. `db_id` past per contract in een `Long`; parse met
  `toLongOrNull()` en tel een mislukking als "skipped malformed".
- **Per-programma tolerantie.** Eén kapot programma mag nooit een hele zender laten
  sneuvelen. Behoud de `skippedMalformedProgrammesCount`-teller en toon die in het
  diagnosescherm.

`mapper.ts` en `formatters.ts` gaan direct over. Twee details:

- De beschrijvingsvoorkeur blijft `algemene_inhoud` → `inhoud` → `htmlToText(descr)`.
- `htmlToText` gebruikt in Node de `he`-bibliotheek voor entities. Neem in Kotlin
  **geen nieuwe dependency**: `android.text.Html.fromHtml` zou werken maar trekt `core`
  het Android-framework in. Schrijf in plaats daarvan een kleine decoder voor de entities
  die daadwerkelijk voorkomen (`&amp;`, `&quot;`, `&#039;`, `&eacute;`, `&euro;`, numerieke
  `&#…;`), met een unittest op echte veldwaarden. `normalizeAgeRating` is een simpele
  `setOf("AL","6","9","12","14","16","18")`-check.

### 4.3 NLZIET EPG-client

Port van `epg-client.ts` met behoud van: 10 s timeout, 2 retries, backoff `min(500·2ⁿ, 2000)`,
retry alleen bij HTTP ≥ 500, en de in-memory cache met TTL per dag (vandaag 10 min,
toekomst 60 min, verleden 6 uur). Cache **alleen na succesvolle validatie**, zoals nu.

Meerdere `channel`-parameters gaan in één request; dat is gemeten op 661 KB voor 20
zenders in 0,06 s, dus per dag volstaat één call.

### 4.4 `NlzietEpgWindow`

De enige plek waar dit plan bewust van de backend afwijkt. Huidig gedrag:

```ts
return diffDays >= -7 && diffDays <= 7;
```

De meting in [§2.4](#24-het-nlziet-epg-venster-is-asymmetrisch) laat zien dat +8 nog 60
items geeft. Voorstel voor de Kotlin-port:

- Ondergrens hard op **−7** (gemeten: −8 geeft 0).
- Bovengrens verruimen naar **+13**, gelijk aan `MAX_PROVIDER_OFFSET` van de tvgids-ingest.
- De EPG-call is en blijft *best effort*: geeft een dag 0 items terug, dan is dat simpelweg
  een dag zonder afspeeldoelen. Dat is precies het bestaande `epgFetchFailed = false`,
  `data = []`-pad en vereist geen nieuwe foutafhandeling.

Dit levert direct meer klikbare programma's op in de verste dagen van de gids.

### 4.5 De matcher — ongewijzigd overnemen

`epg-matcher.ts` is de meest kritische en meest subtiele code van het project. Neem de
regels **exact** over:

- `MAX_START_DIFF_MS = 6 min`, `MAX_DURATION_DIFF_MS = 10 min`.
- Kandidaat alleen bij start- én duurverschil binnen de marges **en** titelgelijkheid.
- **Precies één kandidaat → target. Twee of meer → géén target** (`rejectedAmbiguous`).
  Dit is een veiligheidseigenschap, geen prestatiedetail: liever geen deeplink dan een
  deeplink naar de verkeerde aflevering. Nooit "de beste kandidaat" kiezen.

`normalizeEpgTitle` verdient bijzondere aandacht bij de port, want hier verschillen de
regex-dialecten van JS en Kotlin:

1. `normalize("NFD")` + diakrietenstrip → `java.text.Normalizer.normalize(s, NFD)` gevolgd
   door `replace(Regex("\\p{Mn}+"), "")`. Gebruik de Unicode-categorie `\p{Mn}`, niet het
   letterlijke tekenbereik uit de TS-bron (dat is daar een `[̀-ͯ]`-range die in de
   broncode als losse combining marks staat en bij kopiëren stilzwijgend kan verminken).
2. De omroepprefix-strip en de suffix-strip zijn `RegexOption.IGNORE_CASE`.
3. `\s*[:\-–—]\s*` bevat en-dash en em-dash; let bij het overtypen op behoud van die tekens.
4. Titelgelijkheid vergelijkt zowel de genormaliseerde titel als de variant zonder spaties.

**Verificatiestap:** schrijf een testharnas dat de bestaande backend-fixtures door de
Kotlin-matcher haalt en de uitkomst vergelijkt met de Node-uitkomst. Zie
[taak 8.2](#82-gouden-vergelijkingstest).

### 4.6 Zenderconfiguratie

`config/channels.json` (26 zenders, 20 met `inNlziet: true`) verhuist naar
`app/src/main/assets/channels.json`. Dezelfde regel blijft gelden: filter op `inNlziet` en
sorteer op `sortOrder`.

**Blokkerend detail:** `ChannelDto` in
[ChannelDto.kt](../android/app/src/main/java/com/nexustvguide/app/data/model/ChannelDto.kt)
mist het veld `nlzietChannelId`. De backend heeft dat wel en de matcher kán er niet zonder —
het is de sleutel waarmee EPG-items aan een zender worden gekoppeld. Dit veld moet in de
Kotlin `Channel` worden toegevoegd, anders levert de matcher stelselmatig nul targets.

Let ook op `CHANNEL_ID_MAP` in `NlzietLauncher` (`vrtcanvas`→`canvas`, `bbc1`→`bbcone`,
`bbc2`→`bbctwo`). Zolang `channels.json` de juiste `nlzietChannelId` bevat, is die mapping
in de standalone modus in principe overbodig; laat hem staan maar dubbel-map niet.

---

## 5. Persistentie: Room

### 5.1 Waarom Room en niet JSON-bestanden

De backend schrijft per dag een JSON-snapshot; `GuideRepository` doet nu hetzelfde in
`cacheDir`. Met 16 dagen × ~577 programma's is dat ~9.000 records. JSON dwingt tot het
volledig deserialiseren van een dag om één dag te tonen, en `cacheDir` mag door Android
**zonder waarschuwing worden geleegd** bij weinig opslagruimte — precies het scenario
waarin je de gids het hardst nodig hebt. Room lost beide op en geeft gratis
gedeeltelijke queries per dag en per zender.

### 5.2 Schema

```kotlin
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val name: String,
    val logoUrl: String?,
    val inNlziet: Boolean,
    val nlzietSlug: String?,
    val nlzietChannelId: String?,   // vereist door de matcher
    val sortOrder: Int
)

@Entity(
    tableName = "programmes",
    primaryKeys = ["channelId", "id"],
    indices = [Index("startUtcMs"), Index("channelId", "startUtcMs")]
)
data class ProgrammeEntity(
    val id: String,
    val channelId: String,
    val title: String,
    val startUtcMs: Long,          // epoch-millis, niet ISO-string
    val endUtcMs: Long,
    val description: String?,
    val imageUrl: String?,
    val genre: String?,
    val isLive: Boolean,
    val isRerun: Boolean,
    val isPremiere: Boolean,
    val ageRating: String?,
    // afgevlakt NlzietProgrammeTarget
    val nlzietContentItemId: String?,
    val nlzietAssetId: String?,
    val nlzietChannelId: String?,
    val nlzietReplayAllowed: Boolean,
    val nlzietRestartAllowed: Boolean
)

@Entity(tableName = "day_meta")
data class DayMetaEntity(
    @PrimaryKey val date: String,  // "YYYY-MM-DD" in Europe/Amsterdam
    val fromUtcMs: Long,
    val toUtcMs: Long,
    val sourceFetchedAtMs: Long,
    val publishedAtMs: Long,
    val programmeCount: Int
)
```

Twee keuzes die afwijken van het JSON-model, met reden:

- **Tijden als `Long` epoch-millis**, niet als ISO-string. Het grid sorteert en filtert
  constant op tijd; een string dwingt tot herhaald parsen. De ISO-vorm blijft de
  interface naar de UI, niet de opslagvorm.
- **Samengestelde sleutel `(channelId, id)`.** De backend dedupliceert met exact die sleutel
  (`${domainChannel.id}:${mapped.id}`), dus dat is de natuurlijke primaire sleutel. Een
  `db_id` alléén is niet bewijsbaar uniek over zenders heen.

Een programma dat middernacht overspant hoort bij beide dagen. De backend lost dat op met
een overlaptoets per dag. In Room slaan we elk programma **één keer** op en doet de query
de overlaptoets:

```sql
SELECT * FROM programmes
WHERE startUtcMs < :dayEndMs AND endUtcMs > :dayStartMs
ORDER BY channelId, startUtcMs
```

Dat is dezelfde `pStart < toMs && pEnd > fromMs`-regel als in `refresh.ts`, maar zonder de
dubbele opslag.

### 5.3 Stale-semantiek

Ongewijzigd overnemen uit `isSnapshotStale`: gisteren t/m morgen verouderen na **2 uur**,
overige dagen na **8 uur**. `GuideUiState.Content.isStale` blijft daarmee werken zoals nu.

---

## 6. Verversing

### 6.1 De `RefreshEngine`

`refresh.ts` gaat vrijwel ongewijzigd over, inclusief de drie sanity checks die
voorkomen dat een halve of lege bronrespons een goede snapshot overschrijft:

1. Nabije dagen (−1 t/m +5) met **0 programma's** → bestaande data behouden.
2. Daling van **meer dan 40%** ten opzichte van de bestaande dag → behouden.
3. Waarschuwing als NPO 1 vandaag leeg is.

Deze checks zijn juist in een standalone app *belangrijker* dan in de backend: er is geen
beheerder die logs leest, dus de app moet zichzelf beschermen tegen een slechte respons.

Volgorde per cyclus, gelijk aan de backend: eerst alle 16 tvgids-offsets (−2 t/m +13)
ophalen en dedupliceren, dan per lokale kalenderdag (−2 t/m +10) verdelen, verrijken en
in één transactie wegschrijven.

**Transactie-eis:** schrijf per dag in één `@Transaction` (verwijder de oude dag, voeg de
nieuwe in, werk `day_meta` bij). Anders kan een onderbroken refresh een half gevulde dag
achterlaten — het equivalent van de atomaire `rename()` in `cache.ts`.

### 6.2 Foreground vs. achtergrond

| Trigger | Wat | Waarom |
|---------|-----|--------|
| App-start, data ontbreekt | volledige cyclus, met laadscherm | eerste start |
| App-start, data is stale | cyclus op de achtergrond, oude data blijft zichtbaar | nooit een leeg scherm |
| Gebruiker opent dag X | dag X eerst, rest daarna | latency waar het telt |
| `PeriodicWorkRequest`, 6 uur | volledige cyclus | gids vers houden |

De eerste start doet 16 tvgids-calls plus ~15 EPG-calls. Gemeten kost dat gzipped ~1,4 MB
en, op basis van de responstijden (0,25 s en 0,06 s), ruwweg 5–10 seconden op een bekabelde
Shield. Aanvaardbaar met een laadindicator, maar doe die eerste vulling **incrementeel**:
schrijf vandaag als eerste weg en toon het grid zodra die dag binnen is.

### 6.3 WorkManager

- `PeriodicWorkRequest` van **6 uur** met `NetworkType.CONNECTED`.
- `BackoffPolicy.EXPONENTIAL`, start 30 s.
- `ExistingPeriodicWorkPolicy.KEEP`, zodat een app-herstart de planning niet reset.
- **Geen** `setRequiresDeviceIdle` / `setRequiresCharging`: een Shield hangt aan het net,
  en die constraints kunnen de run onnodig lang uitstellen.
- Nieuwe dependency: `androidx.work:work-runtime-ktx`.

De Shield staat normaal aan het stroomnet, dus Doze speelt nauwelijks. Ga er toch niet
blind van uit dat de periodieke run altijd op tijd draait: de stale-check bij app-start
is het echte vangnet, WorkManager is de optimalisatie.

---

## 7. Wat er in de bestaande app verandert

### 7.1 `GuideRepository` wordt een interface

```kotlin
interface GuideRepository {
    suspend fun getChannels(): List<ChannelDto>
    suspend fun getGuideForDate(date: String): GuideResponseDto?
}
```

Twee implementaties: `RemoteGuideRepository` (de huidige klasse, ongewijzigd) en
`LocalGuideRepository` (Room + `RefreshEngine`). Beide leveren dezelfde `GuideResponseDto`.

**Gevolg: `GuideViewModel`, `NexusProgramGuideFragment` en `NlzietLauncher` hoeven niet te
veranderen.** Dat is de belangrijkste eigenschap van dit ontwerp — de risicovolle port zit
volledig onder een bestaande, al gevalideerde interface.

### 7.2 Modusschakelaar

`BuildConfig`-veld `GUIDE_SOURCE` (`"LOCAL"` of `"REMOTE"`), overschrijfbaar via
`SharedPreferences` in het instellingenscherm, zodat je op het apparaat zelf kunt
omschakelen zonder nieuwe build. Debug-builds behouden `REMOTE` als default totdat
[fase 3](#fase-3--validatie-op-de-shield) is afgerond.

### 7.3 `ChannelDto`

Voeg `nlzietChannelId: String?` toe. Zie [§4.6](#46-zenderconfiguratie) — zonder dit veld
matcht er niets.

### 7.4 minSdk 21

Blijft 21. Room, WorkManager en OkHttp 4.12 ondersteunen dat allemaal. Eén aandachtspunt:
op **Android 5.0 (API 21)** staat TLS 1.2 niet altijd standaard aan. De Shield draait
Android 9/11, dus dit raakt het doelapparaat niet; het is alleen relevant als je de app
ooit op een oud apparaat wilt draaien. Los het dán op met een `ConnectionSpec`, niet nu.

### 7.5 `network_security_config.xml`

De cleartext-uitzondering voor het LAN-IP mag pas verdwijnen als `REMOTE` definitief
vervalt. Beide bronnen zijn HTTPS, dus de standalone modus heeft de uitzondering niet nodig.

---

## 8. Testplan

### 8.1 JVM-unittests

Omdat `core.*` framework-vrij is, draait dit alles als gewone `testImplementation` zonder
Robolectric:

- `GuideTime`: DST-overgangen (maart 23 uur, oktober 25 uur), dagvenstergrenzen.
- `TvgidsParser`: geldige envelope (**beide** vormen: object én array), kapot programma
  wordt overgeslagen en geteld, `end <= start` afgewezen, `db_id` buiten 64-bit afgewezen.
- `Formatters`: HTML-strip, entity-decode, `normalizeAgeRating` inclusief de rommelwaarden
  `"H"`, `"live"`, `"tip"`, `""`.
- `NlzietEpgMatcher`: exacte match; start 5 min ≠ (accepteren) vs. 7 min (afwijzen);
  duur 9 min vs. 11 min; **twee kandidaten → géén target**; titelnormalisatie met
  diakrieten en omroepprefixen.
- `RefreshEngine`: de drie sanity checks, elk met een testgeval dat bewijst dat bestaande
  data behouden blijft.

### 8.2 Gouden vergelijkingstest

De belangrijkste test van dit plan. Het doel is te bewijzen dat de Kotlin-port
**identieke** uitvoer geeft als de draaiende backend, want elk verschil is een regressie
die zich als een verkeerde deeplink manifesteert.

1. Leg met `curl` een dag ruwe invoer vast (tvgids-envelope + NLZIET-EPG) als testfixture.
2. Draai dezelfde invoer door de Node-pipeline en bewaar de genormaliseerde uitvoer.
3. Laat de Kotlin-test dezelfde fixture verwerken en vergelijk veld voor veld: aantal
   programma's, titels, start/eind, en vooral het aantal en de inhoud van de
   `nlziet`-targets.
4. Accepteer de port pas bij **exacte gelijkheid van de targets**.

De bestaande fixtures in `tvguide-api/test/fixtures/` zijn hiervoor het startpunt.

### 8.3 Instrumentatie

- Room-migratie en `@Transaction`-gedrag bij een afgebroken refresh.
- Vliegtuigmodus: cold start toont de laatst opgeslagen gids met de stale-markering.
- WorkManager via `TestDriver` (periodieke run met geforceerde constraints).

### 8.4 Op het apparaat

Zie [fase 3](#fase-3--validatie-op-de-shield).

---

## 9. Gevolgen en afwegingen

### Wat je wint

- **Geen infrastructuur.** Eén `.apk`, geen LXC, Docker, reverse proxy of certificaten.
- **Geen LAN-afhankelijkheid.** De app werkt op elk netwerk, ook buitenshuis.
- **Minder latency.** Geen extra hop; data komt uit een lokale database.
- **Geen versieskew** tussen app en backend.

### Wat je verliest — expliciet

1. **XMLTV verdwijnt uit het standalone pad.** Jellyfin, TiviMate en Kodi halen hun gids
   nu bij `tvguide-api`. Een app-only opzet bedient die niet. Dit is de zwaarste
   consequentie van Optie 1 en wordt hier niet weggeschreven: als XMLTV in gebruik is,
   moet `tvguide-api` blijven draaien en levert dit plan geen infrastructuurwinst, alleen
   een robuustere app. **Beslis dit vóór fase 1** — het bepaalt of het einddoel "backend
   uitzetten" of "backend behouden voor XMLTV" is.
2. **N apparaten = N× verkeer.** Eén Shield is triviaal; bij meer apparaten doet elk zijn
   eigen 16 dagen. Bij normaal huishoudelijk gebruik geen bezwaar.
3. **Bronwijzigingen vereisen een app-update.** Verandert tvgids of NLZIET zijn formaat,
   dan is een backend-fix één herstart en een app-fix een nieuwe build op elk apparaat.
   `channels.json` in assets betekent bovendien dat een zenderwijziging een release wordt.
4. **Batterij/data op mobiele apparaten.** Niet relevant voor een Shield aan het net.

### Waarom het toch de juiste keuze is voor dit project

Het doelapparaat is één Shield aan het stroomnet, in één huishouden. De backend is voor
dat scenario een single point of failure die meer bedrijfsrisico oplevert dan hij
functionaliteit toevoegt — behalve voor XMLTV, dat expliciet apart wordt behandeld.

---

## 10. Risico's

| # | Risico | Kans | Impact | Mitigatie |
|---|--------|------|--------|-----------|
| 1 | **NLZIET vereist alsnog een token.** De EPG is nu open, maar dat is een niet-gedocumenteerde eigenschap van andermans API. | midden | hoog | De matcher is al gebouwd om te falen zonder targets (`epgFetchFailed` → gids blijft werken, alleen zonder deeplinks). Los gedrag: de gids mag nooit stukgaan omdat de EPG wegvalt. |
| 2 | **Kotlin-regex wijkt af van JS-regex** in `normalizeEpgTitle`, met stille mismatches. | midden | midden | De gouden vergelijkingstest ([§8.2](#82-gouden-vergelijkingstest)) is precies hiervoor. |
| 3 | tvgids wijzigt `data` van object naar array of andersom. | laag | hoog | Beide vormen ondersteunen én beide testen, zoals de backend al doet. |
| 4 | Refresh onderbroken → halve dag in de database. | midden | midden | Eén transactie per dag ([§6.1](#61-de-refreshengine)). |
| 5 | Gebruiker-agent-blokkade of rate limiting bij directe calls vanaf het apparaat. | laag | midden | Behoud de bestaande `User-Agent`, respecteer de backoff, en houd 6-uurs intervallen aan. |
| 6 | Room-schema wijzigt later. | laag | laag | `fallbackToDestructiveMigration()` is hier acceptabel: de data is een cache en volledig herbouwbaar uit de bron. |

---

## 11. Optionele vervolgstappen

- **`image.landscapeUrl` uit de EPG gebruiken** als tvgids geen `img` levert
  ([§2.5](#25-de-epg-respons-bevat-meer-velden-dan-de-backend-gebruikt)). Gratis
  kwaliteitswinst, geen extra request.
- **`seriesId` bewaren** voor een latere "meer afleveringen"-functie.
- **`channels.json` uit de assets kunnen overschrijven** via een gedownload bestand, zodat
  een zenderwijziging geen release vereist (verzacht risico 3 uit §9).
- **XMLTV-server ín de app** (kleine HTTP-listener op de Shield) als je toch van de LXC af
  wilt en XMLTV nodig hebt. Niet in dit plan opgenomen: het brengt het
  serverbedrijfsrisico terug in de app.

---

## 12. Fasering

Elke fase is los opleverbaar en eindigt in een toestand waarin de app werkt.

### Fase 0 — Beslissing

- [ ] Vaststellen of XMLTV nog in gebruik is (Jellyfin/TiviMate/Kodi). Dit bepaalt of
      `tvguide-api` uiteindelijk uit mag. **Blokkerend voor het einddoel, niet voor fase 1.**

### Fase 1 — `core` port, framework-vrij

- [ ] `core.domain`, `core.time`, `core.source.tvgids`, `core.nlziet` als pure Kotlin.
- [ ] `nlzietChannelId` toevoegen aan het `Channel`-model.
- [ ] `channels.json` naar `app/src/main/assets/`.
- [ ] JVM-unittests uit [§8.1](#81-jvm-unittests).
- [ ] Gouden vergelijkingstest uit [§8.2](#82-gouden-vergelijkingstest) groen.

*Oplevering: de complete pipeline draait en is bewijsbaar gelijk aan de backend, zonder
dat de app verandert.*

### Fase 2 — Persistentie en verversing

- [ ] Room-schema, DAO's, `@Transaction` per dag.
- [ ] `RefreshEngine` inclusief de drie sanity checks.
- [ ] `GuideRepository` naar interface; `LocalGuideRepository` erbij.
- [ ] `GUIDE_SOURCE`-schakelaar, default nog `REMOTE`.
- [ ] `GuideRefreshWorker` + WorkManager-dependency.

*Oplevering: standalone modus is te kiezen; `REMOTE` blijft de default.*

### Fase 3 — Validatie op de Shield

- [ ] Cold start zonder netwerk → laatst opgeslagen gids met stale-markering.
- [ ] Cold start mét netwerk, lege database → gids binnen enkele seconden.
- [ ] Targetdekking in `LOCAL` vergelijken met `REMOTE` **op hetzelfde moment en dezelfde
      dag**; het aantal `nlziet`-targets moet gelijk zijn (op de bewuste
      venstverruiming uit [§4.4](#44-nlzietepgwindow) na, die meer targets hoort te geven).
- [ ] Kliktest: deeplink opent dezelfde uitzending als in de `REMOTE`-modus.
- [ ] 24 uur laten draaien; controleren dat de periodieke refresh liep.

### Fase 4 — Omschakelen

- [ ] Default naar `LOCAL`.
- [ ] `REMOTE` blijft als terugval in de instellingen staan.
- [ ] `tvguide-api` blijft draaien zolang XMLTV nodig is (uitkomst fase 0).

---

## 13. Nieuwe dependencies

```gradle
implementation "androidx.room:room-runtime:2.6.1"
implementation "androidx.room:room-ktx:2.6.1"
kapt          "androidx.room:room-compiler:2.6.1"   // of KSP
implementation "androidx.work:work-runtime-ktx:2.9.1"
```

Room 2.6.1 en WorkManager 2.9.1 ondersteunen minSdk 21. OkHttp, Gson en ThreeTenABP zitten
al in het project. Retrofit blijft nodig zolang de `REMOTE`-modus bestaat; de standalone
pipeline gebruikt OkHttp rechtstreeks.
