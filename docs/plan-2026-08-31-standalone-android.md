# Uitvoeringsplan — Optie 1: volledig standalone Android-app ("serverless")

Status: **fase 1, 2 en 3 volledig geïmplementeerd en gevalideerd op de Android TV emulator (Television_1080p); alle unit-, pariteits- en regressietests groen, geminificeerde release bouwt en draait succesvol.**
Datum: 31 augustus 2026
Review: 6 september 2026, getoetst aan de huidige repository en Android-documentatie.
Betreft: verhuizing van de `tvguide-api`-logica (ophalen `json.tvgids.nl/v4`, normalisatie,
NLZIET-EPG-matching, caching) naar de Kotlin Android-app, zodat één `.apk` op de Shield
zonder LXC, Docker of homelab-afhankelijkheid werkt.

Gerelateerd: [`plan-2026-08-30-bouwplan.md`](plan-2026-08-30-bouwplan.md) (bouwplan backend + app),
[`plan-2026-08-30-nlziet-epg-mapping.md`](plan-2026-08-30-nlziet-epg-mapping.md) (de matcher die hier wordt geport),
[`plan-2026-08-31-channel-ordering.md`](plan-2026-08-31-channel-ordering.md) (zendervolgorde).

---

## 1. Doel en scope

### Doel

De app haalt zijn gidsdata rechtstreeks bij de bronnen op en bewaart die lokaal. Na
installatie is er geen enkele afhankelijkheid meer van een draaiende backend op het LAN.

### In scope

- Port van de ingest-, normalisatie-, matching- en cachelogica naar Kotlin.
- Lokale persistentie (Room) met dezelfde stale-semantiek als de backend.
- Achtergrondverversing via WorkManager.
- Behoud van gridgedrag, zendervoorkeuren en `NlzietLauncher`; aanpassing van de
  repository-injectie en de observatie van gidsdata in de ViewModels is wel nodig.
- Ontkoppeling van automatische updatecontroles van de LAN-backend in `LOCAL`.

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

Standalone betekent hier **geen eigen gidsserver nodig**, niet offline of zonder externe
diensten: gidsbronnen en afbeeldingen vereisen internet; afspelen blijft afhankelijk van
de geïnstalleerde NLZIET-app en de daarvoor geldende toegang. De bestaande in-app-updater
gebruikt eveneens `tvguide-api`; zie [§7.6](#76-in-app-updates).

---

## 2. Eerdere metingen en grenzen van het bewijs

Onderstaande bronmetingen zijn in het oorspronkelijke plan gerapporteerd voor
**31 augustus 2026**, vanaf de ontwikkelmachine. Ruwe meetbestanden zijn niet bij dit
plan vastgelegd; tijdens deze review zijn de bronrequests niet opnieuw uitgevoerd.
Behandel ze als historische waarnemingen, niet als een API-contract of apparaatvalidatie.
Leg vóór implementatie reproduceerbare requests, responsfixtures, meetdatum en bronversies
vast. Compatibiliteitsclaims zijn hieronder afzonderlijk gecorrigeerd.

### 2.1 Bronnen waren tokenloos bereikbaar

| Bron | Endpoint | Status |
|------|----------|--------|
| tvgids.nl | `GET https://json.tvgids.nl/v4/channels` | `200`, 12.367 bytes |
| tvgids.nl | `GET https://json.tvgids.nl/v4/programs/?day=0&channels=<20 ids>` | `200`, 529.521 bytes |
| NLZIET | `GET https://api.nlziet.nl/v9/epg/programlocations?date=…&channel=…` | `200`, 62.488 bytes (1 zender) |

Beide waren volgens deze meting zonder account-token bereikbaar. **Dit is de kernaanname
van dit plan** en tevens het grootste risico: zie [§10 Risico's](#10-risicos).

### 2.2 TLS 1.2 is gemeten; Android-compatibiliteit nog niet

Een `200` met `curl --tlsv1.2 --tls-max 1.2` bewijst alleen dat de server met de
TLS-stack van de ontwikkelmachine TLS 1.2 accepteerde. Het bewijst niets over de
certificaatketen en cipher-compatibiliteit op Android 21. TLS 1.2 staat bij Android
clients standaard aan vanaf API 20; een algemene API-21-workaround is dus niet nodig.
Test beide hosts met de echte OkHttp-client op API 21 en de Shield. Zie
[Android SSLSocket](https://developer.android.com/reference/javax/net/ssl/SSLSocket)
en [§7.4](#74-minsdk-21).

### 2.3 Datavolume per dag (20 actieve NLZiet-zenders)

| Meting | Waarde |
|--------|--------|
| tvgids `day=0`, alle 20 zenders, ongecomprimeerd | 529.521 bytes |
| idem met `Accept-Encoding: gzip` | 84.801 bytes (**6,2× kleiner**) |
| aantal programma's in die respons | 577, verdeeld over 20 buckets |
| NLZIET EPG, 1 dag, alle 20 zenders in één request | 661.673 bytes, ~0,06 s |

**Consequentie:** behoud transparante gzip-ondersteuning. Een volledige cyclus van 16
tvgids-offsets kost naar schatting ongecomprimeerd ~8,5 MB en gzipped ~1,4 MB.
OkHttp voegt `Accept-Encoding: gzip`
automatisch toe zolang je die header niet zelf zet — expliciet zelf zetten schakelt de
transparante decompressie uit. Dit is alleen het tvgids-verkeer; NLZIET, logo's,
programma-afbeeldingen, headers en retries komen daar nog bij. Eén dag extrapoleren
naar alle offsets geeft slechts een orde van grootte, geen gemeten cyclusvolume.

### 2.4 Het NLZIET-EPG-venster is asymmetrisch

Gemeten aantal `programLocations` voor `npo1` per dag-offset:

| Offset | Datum | Aantal |
|--------|-------|--------|
| −8 | 2026-08-23 | **0** |
| −7 | 2026-08-24 | 76 |
| 0 | 2026-08-31 | 71 |
| +7 | 2026-09-07 | 63 |
| +8 | 2026-09-08 | **60** |

Deze steekproef laat data op +8 zien, maar bewijst geen harde onder- of bovengrens voor
alle zenders en meetmomenten. De huidige `isDateInEpgWindow` in
[`epg-client.ts`](../tvguide-api/src/enrichment/nlziet/epg-client.ts) kapt bij
`diffDays <= 7` af en kan daarmee toekomstige matches missen. Valideer verruiming
apart na de port; zie [taak 4.4](#44-nlzietepgwindow).

### 2.5 De EPG-respons bevat meer velden dan de backend gebruikt

De gerapporteerde live respons bevat onder meer `image.landscapeUrl`, `seriesId`, `isMovie`,
`contentProvider` en `firstBroadcast`. De backend valideert `seriesId` al, maar gebruikt
het niet voor matching; de overige genoemde velden worden niet benut. Voor de port is dat prima
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
| `src/enrichment/nlziet/epg-schema.ts` | `core.nlziet.NlzietEpgParser` |
| `src/enrichment/nlziet/epg-matcher.ts` | `core.nlziet.NlzietEpgMatcher` |
| `src/store/time.ts` | `core.time.GuideTime` |
| `src/store/cache.ts` | `data.local` — Room DAO's + entities |
| `src/store/refresh.ts` | `core.refresh.RefreshEngine` |
| `src/output/rest.ts` | `data.repository.LocalGuideRepository` |
| *(geen equivalent)* | `work.GuideRefreshWorker` |

**Ontwerpregel:** alles onder `core.*` is **pure Kotlin/JVM zonder Android-imports**. Zo
kan de port als snelle JVM-unittest draaien, zonder Robolectric. Gebruik eigen
domeinmodellen: de bestaande `ProgrammeDto` en target-DTO zijn `Parcelable` en horen
niet in `core`. Room, assets, voorkeuren, Worker en de applicatie-initialisatie blijven
in Android-adapters. `RefreshEngine` krijgt opslag, klok en clients via interfaces;
geen DAO-, `Context`- of `Log`-imports in `core`.

---

## 4. De port, per onderdeel

Onder elk kopje staat wat er letterlijk overgaat en waar Kotlin een expliciete
beslissing afdwingt die JavaScript verborgen hield.

### 4.1 Tijd en tijdzone

De backend gebruikt `@js-temporal/polyfill` met `Europe/Amsterdam`. De app heeft
**ThreeTenABP al als dependency** en `GuideViewModel` gebruikt het al. Er is dus geen
nieuwe runtimebibliotheek nodig. Gebruik consequent `org.threeten.bp`, niet ongemerkt
`java.time` (dat zonder desugaring niet op API 21 beschikbaar is).

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

`NexusTVGuideApp.onCreate()` roept `AndroidThreeTen.init(this)` al aan. Maak vóór die
initialisatie geen singleton met `ZoneId.of(...)`; test ook een Worker-start zonder
Activity. Injecteer een klok en bepaal `today` één keer per cyclus, zodat middernacht
en een afwijkende apparaattijdzone geen verschillende dagvensters binnen één run geven.

**JVM-testvoorwaarde:** ThreeTenABP laadt tijdzoneregels uit Android-assets. Alleen
Android-imports vermijden maakt `ZoneId.of("Europe/Amsterdam")` nog niet werkend in
een gewone JVM-test. Richt een JVM-testbootstrap in die de bijpassende TZDB uit
testresources laadt via ThreeTenBP, zonder dubbele `org.threeten.bp`-klassen; bewijs dit
eerst met één DST-test zonder Robolectric. Zie de
[ThreeTenABP-initialisatie](https://github.com/JakeWharton/ThreeTenABP).

### 4.2 tvgids-ingest

`TvgidsClient` wordt een dunne OkHttp-wrapper met base-URL
`https://json.tvgids.nl/v4`, 10 s timeout, 2 retries, exponentiële backoff afgetopt op
3 s, en de bestaande `User-Agent`. Definieer de 10 s als maximale tijd per poging
inclusief responsebody (`callTimeout`), maximaal drie pogingen totaal. De backend
herhaalt nu ook permanente fouten; de port herhaalt alleen tijdelijke netwerkfouten
en 5xx. Voor 429: respecteer `Retry-After` binnen het cyclusbudget of stel de run uit.
Overige 4xx en structurele parsefouten niet direct herhalen. Dit is een bewuste
afwijking die met MockWebServer moet worden vastgelegd. Gebruik annuleerbare calls en
`delay`; geef `CancellationException` door en voorkom ongemerkt gestapelde retries.

De zod-validatie uit `schema.ts` wordt handgeschreven Kotlin-parsing. Dit is het punt
waar Kotlin strenger is dan TypeScript en waar de meeste bugs kunnen ontstaan:

- **`data` is polymorf.** De envelope levert `Record<ch_id, bucket>` óf een array van
  buckets. De live meting gaf een **object** met 20 sleutels. Gson kan dit niet in één
  datatype vangen; gebruik een `JsonElement` en vertak op `isJsonObject` / `isJsonArray`.
  Sla deze tak niet over omdat de meting toevallig een object gaf — de backend ondersteunt
  beide en dat is er niet zonder reden in gekomen.
- **De bronvelden zijn strings.** `s`, `e` en `db_id` komen binnen als decimale strings. De
  regexvalidatie (`^\d+$`), de eis `end > start` en de 64-bit-grens op `db_id` blijven
  precies zoals in `rawProgrammeSchema`. `db_id` past per contract in een `Long`; parse met
  `toLongOrNull()` en tel een mislukking als "skipped malformed". Bewaar de originele
  `db_id` als string, inclusief eventuele voorloopnullen. Controleer `s > 0` en
  overflow bij seconden → milliseconden vóór het mappen. Ongeldige tijden overslaan
  is hier expliciete verharding ten opzichte van de backendmapper.
- **Geen impliciete Gson-coercie.** Valideer JSON-typen vóór `asString`: een getal is
  geen geldige string. Behoud defaults voor ontbrekende optionele velden; expliciete
  `null` is niet hetzelfde als ontbrekend. Valideer ook `version`, `ch_id` en `prog`;
  gebruik `bucket.ch_id`, niet de objectkey, als zendersleutel.
- **Per-programma tolerantie.** Eén kapot programma mag nooit een hele zender laten
  sneuvelen. Behoud de `skippedMalformedProgrammesCount`-teller in de
  lokale refreshdiagnostiek. Een apart diagnosescherm bestaat nog niet en is geen
  voorwaarde voor deze port; gestructureerde logs en opgeslagen laatste runstatus wel.

`mapper.ts` en `formatters.ts` gaan inhoudelijk over. Aandachtspunten:

- De beschrijvingsvoorkeur blijft `algemene_inhoud` → `inhoud` → `htmlToText(descr)`.
- `htmlToText` gebruikt in Node `he`. Een kleine handgeschreven entitylijst is geen
  equivalente vervanging: ook `&nbsp;`, hexadecimale entities en Unicode buiten de BMP
  moeten correct blijven. Kies een JVM-decoder met HTML5-ondersteuning of een complete
  entitytabel en vergelijk die met `he` op fixtures. Leg de dependencykeuze vast in
  fase 1. Behoud de volgorde tags strippen → één keer decoderen → witruimte normaliseren;
  `android.text.Html` hoort niet in `core`.
- `normalizeAgeRating` doet eerst trimmen en locale-onafhankelijk uppercasen, dan de
  `setOf("AL","6","9","12","14","16","18")`-check. De vlaggen `live`, `rerun` en
  `is_premiere` zijn alleen waar bij exact de bronstring `"true"`.

### 4.3 NLZIET EPG-client

Port van `epg-client.ts` met: 10 s timeout, 2 retries, backoff `min(500·2ⁿ, 2000)`,
en de in-memory cache met TTL per dag (vandaag 10 min,
toekomst 60 min, verleden 6 uur). Cache **alleen na succesvolle validatie**, zoals nu.
De huidige algemene `catch` herhaalt óók 4xx en validatiefouten; de omschrijving
"alleen HTTP ≥ 500" klopte niet. Pas dezelfde expliciete foutclassificatie toe als
in §4.2. Cachekey: datum plus gesorteerde, unieke, niet-lege NLZIET-zender-id's;
ruim verlopen entries op en deel één client tussen UI en Worker.

Port ook `epg-schema.ts`: `contentItemId` is 22 base64url-tekens, `assetId` 32 hextekens,
titel en kanaal-id zijn niet leeg, tijden bevatten een offset en ontbrekende
replay/restart-vlaggen worden `false`. Een structureel ongeldige EPG-respons faalt als
geheel; onbekende velden worden genegeerd. Geldig leeg is iets anders dan ophalen mislukt.

Meerdere `channel`-parameters gaan in één request; dat is gemeten op 661 KB voor 20
zenders in 0,06 s, dus per dag volstaat één call.

### 4.4 `NlzietEpgWindow`

Een afzonderlijk te valideren uitbreiding. Huidig gedrag:

```ts
return diffDays >= -7 && diffDays <= 7;
```

De meting in [§2.4](#24-het-nlziet-epg-venster-is-asymmetrisch) laat zien dat +8 nog 60
items geeft. Voorstel voor de Kotlin-port:

- Eerst **−7 t/m +7** behouden voor de vergelijking met Node.
- Daarna optioneel de bovengrens op **+10** zetten: de app publiceert −2 t/m +10,
  terwijl −2 t/m +13 provider-offsets zijn. Dit zijn verschillende vensters; extra
  provider-offsets zijn geen reden om niet-gepubliceerde EPG-dagen op te halen.
- De EPG-call is en blijft *best effort*: geeft een dag 0 items terug, dan is dat simpelweg
  een dag zonder afspeeldoelen. Dat is precies het bestaande `epgFetchFailed = false`,
  `data = []`-pad en vereist geen nieuwe foutafhandeling.

De uitbreiding kan meer targets opleveren, maar bewijst geen afspeelrecht. De bestaande
launcher controleert replay/restart-vlaggen en uitzendtijd. −7 blijft een applicatiebeleid,
geen bewezen providergrens. Test +8 t/m +10 en houd de uitbreiding apart van portpariteit.

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
   door `replace(Regex("[\\u0300-\\u036f]"), "")`. Dit spelt het bereik uit de TS-bron
   expliciet uit. `\p{Mn}` is breder en dus geen exacte port.
2. De omroepprefix-strip en de suffix-strip zijn `RegexOption.IGNORE_CASE`.
3. `\s*[:\-–—]\s*` bevat en-dash en em-dash; let bij het overtypen op behoud van die tekens.
4. Titelgelijkheid vergelijkt zowel de genormaliseerde titel als de variant zonder spaties.
5. Gebruik `lowercase(Locale.ROOT)`, behoud de afsluitende `[^a-z0-9]+`-vervanging
   en test JS/Kotlin-verschillen in witruimte, waaronder NBSP en BOM.
6. Maak per dagsnapshot onafhankelijke programmawaarden. De Node-matcher muteert
   programma-objecten die tussen dagen gedeeld kunnen zijn; neem die aliasing niet over.

**Verificatiestap:** schrijf een testharnas dat de bestaande backend-fixtures door de
Kotlin-matcher haalt en de uitkomst vergelijkt met de Node-uitkomst. Zie
[taak 8.2](#82-gouden-vergelijkingstest).

### 4.6 Zenderconfiguratie

`tvguide-api/config/channels.json` (26 zenders, 20 met `inNlziet: true`) wordt als
`android/app/src/main/assets/channels.json` meegeleverd. Houd tijdens de migratie één
bronbestand aan via een Gradle-kopieertaak of een check op identieke inhoud; twee
handmatig onderhouden lijsten lopen uiteen. Dezelfde regel blijft gelden: filter op `inNlziet` en
sorteer op `sortOrder`.

**Vereist voor lokale matching:** `ChannelDto` in
[ChannelDto.kt](../android/app/src/main/java/com/nexustvguide/app/data/model/ChannelDto.kt)
mist het veld `nlzietChannelId`. De backend heeft dat wel en de matcher kán er niet zonder —
het is de sleutel waarmee EPG-items aan een zender worden gekoppeld. Dit veld moet in de
Kotlin-domeinmodel `Channel` aanwezig zijn. Het transport-DTO hoeft alleen uitgebreid
te worden als die informatie de repositorygrens overgaat; ontbrekend in het huidige
DTO is op zichzelf geen fout in `REMOTE`, waar matching al op de server gebeurt.

Let ook op `CHANNEL_ID_MAP` in `NlzietLauncher` (`vrtcanvas`→`canvas`, `bbc1`→`bbcone`,
`bbc2`→`bbctwo`). Die mapping blijft nodig voor live-fallback zonder exact target,
ook als `channels.json` de juiste `nlzietChannelId` bevat. Laat hem staan; domein-id's voor
zendervoorkeuren en `ProgrammeDto.channelId` mogen niet veranderen in NLZIET-id's.

---

## 5. Persistentie: Room

### 5.1 Waarom Room en niet JSON-bestanden

De backend schrijft per dag een JSON-snapshot; `GuideRepository` doet nu hetzelfde in
`cacheDir`. Met 13 gepubliceerde dagen × ~577 programma's is dat ruwweg 7.500 records,
plus eventueel één bewaarde oudere dag. JSON dwingt tot het
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
  primaryKeys = ["date", "channelId", "id"],
  indices = [Index(value = ["date", "channelId", "startUtcMs"])]
)
data class ProgrammeEntity(
    val date: String,             // eigenaar: dagsnapshot in Europe/Amsterdam
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
    val nlzietKind: String?,
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

Opslagkeuzes:

- **Tijden als `Long` epoch-millis**, niet als ISO-string. Het grid sorteert en filtert
  constant op tijd; een string dwingt tot herhaald parsen. De ISO-vorm blijft de
  interface naar de UI, niet de opslagvorm.
- **Samengestelde sleutel `(date, channelId, id)`.** Binnen een dag dedupliceren we met
  `(channelId, id)`, zoals de backend. De datum maakt iedere dagsnapshot onafhankelijk
  vervangbaar, inclusief de per dag verkregen EPG-verrijking.
- **Target als geheel.** Alle targetvelden ontbreken samen, of vormen een volledig
  gevalideerd target met `kind = "replay"`. De adapter reconstrueert `nlziet = null`
  bij afwezigheid, nooit een half target; `nlzietId` blijft `null`.

Een programma dat middernacht overspant hoort bij beide dagen. De backend lost dat op met
een overlaptoets per dag. In Room bewaren we daarom bewust één rij **per overlappende
dag**. Met alleen een globale `(channelId, id)`-sleutel zou het verwijderen of verrijken
van dag A ook de behouden snapshot van dag B kunnen wijzigen. Een transactie lost dat
eigenaarschapsprobleem niet op. De beperkte dubbele opslag is hier eenvoudiger en veiliger.

```sql
SELECT p.* FROM programmes AS p
JOIN channels AS c ON c.id = p.channelId
WHERE p.date = :date
  AND p.startUtcMs < :dayEndMs AND p.endUtcMs > :dayStartMs
ORDER BY c.sortOrder, p.startUtcMs, p.id
```

De overlapregel blijft `pStart < toMs && pEnd > fromMs`. Verwijder bij vervanging
uitsluitend `WHERE date = :date`; lees programma's en `day_meta` samen transactioneel.
Koppel rijen aan `day_meta` met een foreign key en cascade-delete, of verwijder beide
expliciet binnen dezelfde transactie. Zonder metadata bestaat er geen gepubliceerde dag.
Zenderconfiguratie komt uit dezelfde assetsversie; pas gebruikersvolgorde en verborgen
zenders uitsluitend via de bestaande `ChannelOrderResolver` toe.

Dit is het kernschema. Leg daarnaast per refresh de laatste poging, bronfouten,
afgewezen dagen en counters vast, apart van de publicatiemetadata. `sourceFetchedAt`
beschrijft de gebruikte ingest, `publishedAt` het moment van succesvolle publicatie;
`GuideMetaDto.lastSuccessfulRefresh` komt voor een dag uit diens `publishedAt`.

### 5.3 Stale-semantiek

De feitelijke `isSnapshotStale`-code gebruikt **2 uur voor elke datum ≤ morgen**, dus
ook eergisteren en ouder, en **8 uur voor datums vanaf overmorgen**. De backendcomment
"gisteren t/m morgen" is onvolledig. Neem voor pariteit de code over, inclusief de
strikte vergelijking `ageMs > maxAgeMs`, gemeten vanaf `publishedAt`.

Bereken stale bij lezen en opnieuw bij hervatten of het verstrijken van de grens;
Room emitteert niet vanzelf wanneer alleen de klok verandert. Een mislukte of
afgewezen refresh verandert `publishedAt` niet. Bij een mislukte tvgids-verversing
markeert de lokale adapter getoonde fallbackdata bovendien stale, zoals de huidige
remote repository bij een netwerkfout doet. Een EPG-fout markeert afzonderlijk de
verrijking als mislukt; goede gidsdata mag wel worden gepubliceerd.

---

## 6. Verversing

### 6.1 De `RefreshEngine`

Port de validatie uit `refresh.ts` met het werkelijke onderscheid tussen twee
blokkerende sanity checks en één waarschuwing:

1. Nabije dagen (−1 t/m +5) met **0 programma's** → bestaande data behouden.
2. Bestaande dag heeft **meer dan 50 programma's** én aantal daalt **meer dan 40%** → behouden.
3. Vandaag bevat programma's, maar geen NPO 1 → alleen waarschuwen, wel publiceren.

Ook buiten de nabije dagen publiceert de backend geen lege snapshots. Deze controles
detecteren niet iedere onvolledige respons; leg mislukte offsets en afgewezen dagen
vast als een gedegradeerde run, ook wanneer andere dagen wel slagen.

Deze checks zijn juist in een standalone app *belangrijker* dan in de backend: er is geen
beheerder die logs leest, dus de app moet zichzelf beschermen tegen een slechte respons.

Volgorde per cyclus, gelijk aan de backend: eerst alle 16 tvgids-offsets (−2 t/m +13)
ophalen en dedupliceren, dan per lokale kalenderdag (−2 t/m +10: **13 dagen**) verdelen
en verrijken. Bewaar de vaste offsetvolgorde voor deterministische deduplicatie:
de latere offset wint bij dezelfde `(channelId, id)`. Gebruik voor alle kalenderkeuzes
dezelfde aan het begin vastgelegde Amsterdam-datum.

**Transactie-eis:** schrijf per dag in één `@Transaction` (verwijder de oude dag, voeg de
nieuwe in, werk `day_meta` bij). Anders kan een onderbroken refresh een half gevulde dag
achterlaten — het equivalent van de atomaire `rename()` in `cache.ts`.

Netwerkverkeer, parsing en matching gebeuren **buiten** de transactie. Bij afwijzing
blijven zowel rijen als metadata van de oude dag intact. Verwijder na de cyclus
snapshotdatums **vóór vandaag −3**, zoals de backend; voer dit ook uit bij het openen
van een lang ongebruikte app, zodat de opslag begrensd blijft.

Een applicatiebrede coordinator met `Mutex` deelt één lopende refresh tussen Worker,
app-start, dagwissel en prefetch. Na wachten opnieuw controleren of werk nodig is;
geen tweede volledige cyclus per caller. Annuleren van een schermcollectie mag gedeeld
refreshwerk niet afbreken; annuleren van de eigenaar van de refresh moet HTTP-calls
en backoff wel stoppen. Houd alle componenten in hetzelfde proces.

### 6.2 Foreground vs. achtergrond

| Trigger | Wat | Waarom |
|---------|-----|--------|
| App-start, data ontbreekt | volledige ingest, gevraagde dag als eerste publiceren | eerste start |
| App-start, data is stale | cyclus op de achtergrond, oude data blijft zichtbaar | nooit een leeg scherm |
| Gebruiker opent dag X | cache direct tonen; bij ontbrekende/stale data refresh delen, X eerst publiceren na ingest | latency waar het telt |
| App hervat of blijft open | stale opnieuw bepalen; nabije data bij veroudering verversen | ook zonder herstart actueel |
| `PeriodicWorkRequest`, 6 uur | volledige cyclus | gids vers houden |

Een volledige vulling doet 16 tvgids-calls plus maximaal **10 EPG-calls** voor −2 t/m +7,
of **13** na verruiming tot +10, exclusief retries. De ~1,4 MB betreft alleen tvgids;
de eerste-laadtijd op de Shield is nog niet gemeten.

Voor de eerste versie wachten we op alle provider-offsets, publiceren daarna de gevraagde
dag eerst en tonen die zodra de transactie klaar is. De overige dagsnapshots volgen.
Dit is incrementele **publicatie**, geen belofte dat offset 0 een complete kalenderdag
bevat. Een snellere deel-ingest pas toevoegen na fixtures die bewijzen welke offsets
nodig zijn voor alle middernachtoverlap en duplicaten. Richtwaarde: eerste bruikbare
dag binnen 10 s op de bekabelde Shield; meten, geen afgeleide garantie.

Zonder netwerk en zonder snapshot volgt een duidelijke lege-cachemelding met
opnieuw-proberen; geen oneindige laadindicator. Buiten het ondersteunde dagvenster
geen nieuwe volledige cyclus starten.

### 6.3 WorkManager

- `PeriodicWorkRequest` van **6 uur** met `NetworkType.CONNECTED`.
- `BackoffPolicy.EXPONENTIAL`, start 30 s.
- `ExistingPeriodicWorkPolicy.KEEP`, zodat een app-herstart de planning niet reset.
- Eén vaste unieke worknaam; alleen in `LOCAL` plannen, bij `REMOTE` annuleren en in
  de Worker vóór uitvoeren opnieuw de modus controleren.
- **Geen** `setRequiresDeviceIdle` / `setRequiresCharging`: een Shield hangt aan het net,
  en die constraints kunnen de run onnodig lang uitstellen.
- Nieuwe dependency: `androidx.work:work-runtime-ktx`.

Periodiek werk is niet exact gepland; constraints en systeemoptimalisaties kunnen het
uitstellen. Zes uur is bovendien langer dan de twee-uursgrens voor nabije dagen.
App-start, hervatten en stale-controle tijdens gebruik blijven daarom nodig. Zie de
[WorkManager PeriodicWorkRequest-documentatie](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest).

De coordinator levert een resultaat met geslaagde/afgewezen dagen en bronfouten.
Een tijdelijke tvgids-fout kan `Result.retry()` geven (maximaal drie Worker-pogingen
per run); permanente bron-/schemafouten niet blind herhalen. Alleen EPG-uitval laat
de gidsrun slagen met een gedegradeerde status. Begrens de cyclus op bijvoorbeeld
vijf minuten, zodat gestapelde timeouts geen onbeperkt werk veroorzaken; reeds
gepubliceerde dagen blijven behouden. Test cancellation en hervatten expliciet.

---

## 7. Wat er in de bestaande app verandert

### 7.1 `GuideRepository` wordt een interface

```kotlin
interface GuideRepository {
    suspend fun getChannels(): List<ChannelDto>
    suspend fun getChannelsForOrdering(date: String): List<ChannelDto>
    suspend fun getGuideForDate(date: String): GuideResponseDto?
    fun observeGuideForDate(date: String): Flow<GuideResponseDto?>
}
```

Twee implementaties: `RemoteGuideRepository` (behoud van huidig netwerk-/cachegedrag) en
`LocalGuideRepository` (Room + `RefreshEngine`). Beide leveren dezelfde `GuideResponseDto`.

`getGuideForDate` blijft de eenmalige load/prefetch-ingang; lokale reads tonen cache
en vragen zo nodig gedeeld refreshwerk aan. `observeGuideForDate` observeert alleen
de cache en start zelf geen netwerkwerk. `LOCAL` observeert Room; `REMOTE` emitteert
de bestaande diskcache en volgende resultaten van `getGuideForDate`.

**De ViewModels veranderen wel:** `GuideViewModel` moet de actieve dag observeren om
de eerste publicatie en achtergrondverversingen te zien. Een eenmalige suspend-return
levert die updates niet. Houd laad-/foutstatus van refresh apart: een initiële `null`
uit Room is nog geen fout zolang de vulling loopt. Behoud focus en oude content bij
verversing. Pas ook `ChannelOrderViewModel` aan: beide construeren nu rechtstreeks
`GuideRepository(application)` en moeten dezelfde repository-provider gebruiken.
`getChannelsForOrdering` mag niet verdwijnen uit het contract. Gridprojectie,
`ChannelOrderResolver` en launchbeleid blijven behouden; regressietests bewaken dat.

### 7.2 Modusschakelaar

`BuildConfig`-veld `GUIDE_SOURCE` (`"LOCAL"` of `"REMOTE"`), overschrijfbaar via
`SharedPreferences` via een nieuw toe te voegen menukeuze die met de afstandsbediening
werkt, zodat je op het apparaat zelf kunt omschakelen zonder nieuwe build.
Debug-builds behouden `REMOTE` als default totdat
[fase 3](#fase-3--validatie-op-de-shield) is afgerond.

Gebruik een applicatiebrede provider; een voorkeur wijzigen is niet genoeg voor al
bestaande ViewModels. Bij wisselen oude observaties beëindigen, repository opnieuw
selecteren, actieve dag herladen en werk plannen/annuleren. Houd remote JSON/ETags
en lokale Room-data gescheiden. Geen automatische stille fallback naar `REMOTE`
in `LOCAL`; dat zou bronstoringen en de zelfstandigheidstest maskeren. Persoonlijke
zendervolgorde blijft in dezelfde voorkeuren staan.

### 7.3 `ChannelDto`

Voeg `nlzietChannelId: String? = null` alleen toe als de gekozen adapter het nodig heeft.
Het lokale domeinmodel bevat het in elk geval; zie [§4.6](#46-zenderconfiguratie).

### 7.4 minSdk 21

Doel blijft 21 met expliciet gekozen dependencyversies, onder voorbehoud van een
geslaagde build en apparaat-/emulatortest. Neem niet willekeurig de nieuwste Room of
WorkManager: Room 2.8 en WorkManager 2.11 verhoogden hun minimum naar API 23. Zie de
[Room-releasenotes](https://developer.android.com/jetpack/androidx/releases/room) en
[WorkManager-releasenotes](https://developer.android.com/jetpack/androidx/releases/work).
Verifieer ook de opgeloste transitieve dependencies met de huidige Kotlin 2.0.10,
AGP 8.13.2 en compileSdk 34. TLS vereist een echte handshake-test, geen vooraf
toegevoegde `ConnectionSpec`-workaround; zie §2.2.

### 7.5 `network_security_config.xml`

**Aangepast op 6 september 2026.** De configuratie stond cleartext **globaal** toe via
`base-config`. Dat is omgezet naar `cleartextTrafficPermitted="false"` met een expliciete
`domain-config`-uitzondering voor alleen `192.168.2.171` en `10.0.2.2` (emulator-loopback).

De directe gidsbronnen en een release-updatekanaal gebruiken HTTPS; alleen de LAN-backend
is nog http. Het hele `domain-config`-blok kan weg zodra `REMOTE` en het LAN-updatekanaal
niet meer gebruikt worden.

### 7.6 In-app-updates

Sinds het oorspronkelijke plan heeft de app een updater. `MainActivity` start na het
eerste frame een controle. Gidsdata verplaatsen maakt de app dus nog niet onafhankelijk:
een APK kan zichzelf niet uitvinden, dus **iets** moet `version.json` en de `.apk` blijven
serveren. Dat is een distributievraagstuk, los van de gidsdata.

**Status: het updatekanaal is instelbaar gemaakt (6 september 2026).** De keuze van host
is uitgesteld; de standaard is ongewijzigd `lan`, zodat de bestaande `.171`-opstelling
exact blijft werken.

| Kanaal | `UPDATE_BASE_URL` | Manifest | Downloadverwijzing |
|--------|-------------------|----------|--------------------|
| `lan` (standaard) | `http://192.168.2.171:3000/` | `api/v1/app/version` | relatief `downloadPath` |
| `release` | eigen HTTPS-host, verplicht op te geven | `version.json` | absolute `downloadUrl` |

Instellen via `gradle.properties` of `-PupdateChannel=release -PupdateBaseUrl=…`; de build
faalt als een release-kanaal geen host of geen HTTPS heeft.

#### Van same-origin naar een host-allowlist

Een release-host serveert het APK-asset niet op de origin van de metadata: GitHub publiceert
`version.json` op `github.com` en leidt de asset-download door naar
`objects.githubusercontent.com`. De oorspronkelijke same-origin-eis in
`UpdateMetadataValidator` maakte dat onmogelijk.

Die eis is vervangen door [`UpdateOriginPolicy`](../android/app/src/main/java/com/nexustvguide/app/update/UpdateOriginPolicy.kt):
HTTPS verplicht (tenzij het kanaal zelf http is), en de host moet exact op een ingebakken
allowlist staan óf gelijk zijn aan de eigen origin. Subdomeinen erven geen vertrouwen.
Omdat OkHttp nu redirects moet volgen, controleert
[`UpdateRedirectInterceptor`](../android/app/src/main/java/com/nexustvguide/app/update/UpdateRedirectInterceptor.kt)
elke hop opnieuw tegen dat beleid; een afgewezen hop breekt de download af.

De allowlist is een aanvullende beperking, niet de garantie zelf. Die blijft liggen bij de
SHA-256-vergelijking en `ApkVerifier`, die controleert dat de APK met dezelfde signing key
is ondertekend als de geïnstalleerde app. Een release-host kan dus geen vreemde APK
binnensmokkelen, ook niet als de metadata gemanipuleerd is.

`AppUpdateDto` accepteert `downloadPath` en `downloadUrl`, maar precies één van beide;
beide tegelijk wordt geweigerd zodat een manifest nooit twee bronnen aanwijst.

#### Nog open

- Er is nog **geen GitHub-repo**: `origin` is Forgejo op het LAN (`forgejo.home.arpa`),
  wat dezelfde beschikbaarheidsbeperking heeft als `.171`. Zolang die keuze niet gemaakt is,
  blijft `lan` het actieve kanaal en moet de backend blijven draaien.
- `tools/publish-release.mjs --channel release --repo <eigenaar>/<repo>` publiceert zelf via
  de releases-API: release aanmaken, APK en `version.json` uploaden. Werkt tegen GitHub en
  tegen Forgejo/Gitea (`--forge forgejo --api-base <url>`), zonder `gh`-afhankelijkheid.
  Zonder `--repo` schrijft de tool alleen de artefacten en blijft de upload handmatig.

  Drie eigenschappen die een kapotte release voorkomen:
  - De download-URL wordt afgeleid uit de tag (`v<versionName>`), niet met de hand getypt.
  - Na de upload wordt de URL uit de serverrespons vergeleken met wat in `version.json`
    staat; bij een afwijking faalt de tool in plaats van een 404 achter te laten.
  - Een bestaande tag wordt geweigerd, zodat de assets van een uitgerolde versie nooit
    stilzwijgend vervangen worden.

  Het token komt uitsluitend uit `RELEASE_TOKEN`/`GITHUB_TOKEN` in de omgeving; een
  `--token`-argument wordt expliciet geweigerd omdat argumenten in shell-history en de
  procestabel belanden.
- In `LOCAL` blijft gelden: geen automatische LAN-updatecontrole, en gidsgebruik wacht
  nooit op de updateserver.

Zie ook [`plan-2026-08-31-in-app-updates.md`](plan-2026-08-31-in-app-updates.md).

---

## 8. Testplan

### 8.1 JVM-unittests

Omdat `core.*` framework-vrij is, draait dit alles als gewone `testImplementation` zonder
Robolectric:

- `GuideTime`: DST-overgangen (maart 23 uur, oktober 25 uur), dagvenstergrenzen,
  cyclus over middernacht en een apparaat dat niet op Amsterdam staat; met werkende TZDB.
- `TvgidsParser`: geldige envelope (**beide** vormen: object én array), kapot programma
  wordt overgeslagen en geteld, `end <= start` afgewezen, `db_id` buiten 64-bit afgewezen.
- `Formatters`: HTML-strip, entity-decode, `normalizeAgeRating` inclusief de rommelwaarden
  `"H"`, `"live"`, `"tip"`, `""`.
- `NlzietEpgMatcher`: exacte match; start 5 en exact 6 min accepteren, 7 min afwijzen;
  duur 9 en exact 10 min accepteren, 11 min afwijzen; **twee kandidaten → géén target**;
  titelnormalisatie met diakrieten, omroepprefixen en Turkse defaultlocale.
- `RefreshEngine`: beide blokkerende checks; 50 versus 51 bestaande records en exact
  40% versus meer dan 40% verlies; ontbrekende NPO 1 waarschuwt zonder publicatie te
  blokkeren; lege verre dagen behouden hun vorige snapshot; gedeeltelijke bronuitval.
- Stale-grenzen: alle verleden dagen, morgen, overmorgen, exact 2/8 uur en net erna;
  mislukte refresh verschuift geen publicatietijd.
- HTTP-clients met MockWebServer: requestparameters, gzip, schemafouten, cache-TTL,
  401/403/429/5xx, timeout en cancellation; annuleerbare backoff zonder echte wachttijd.

### 8.2 Gouden vergelijkingstest

De belangrijkste test van dit plan. Het doel is te bewijzen dat de Kotlin-port
dezelfde uitvoer geeft als de Node-pipeline voor **dezelfde vastgelegde invoer**, klok,
zenderconfiguratie en vensterinstelling. Live servers op hetzelfde moment kunnen
verschillende cached bronversies gebruiken en zijn geen deterministisch test-orakel.
Verschillen kunnen ook beschrijvingen of metadata betreffen; niet elk verschil is een
verkeerde deeplink. Goedgekeurde verbeteringen krijgen aparte tests.

1. Leg met `curl` ruwe invoer vast, inclusief aangrenzende provider-offsets voor
   middernachtoverlap, tvgids-envelope en NLZIET-EPG. Bewaar ook een vaste klok,
   zenderconfiguratie en de commit van de Node-referentie.
2. Draai dezelfde invoer door de Node-pipeline en bewaar de genormaliseerde uitvoer.
3. Laat de Kotlin-test dezelfde fixture verwerken en vergelijk veld voor veld: aantal
   programma's, titels, start/eind, en vooral het aantal en de inhoud van de
   `nlziet`-targets.
4. Accepteer de matcherport pas bij **exacte gelijkheid van de targets per dag en
   programma-id**, inclusief `kind`, beide id's, kanaal-id, vlaggen en `null`.
   Canonicaliseer objectvolgorde en tijdnotatie voor de vergelijking; maskeer geen
   inhoudelijke verschillen. Test gedeelde middernachtprogramma's met onafhankelijke
   objecten per dag, zodat de mutatiebug in de backend niet de referentie bepaalt.
5. Valideer vensteruitbreiding, foutclassificatie en strengere tijdvalidatie afzonderlijk;
   die hoeven niet dezelfde uitkomst te geven als de oude backend.

De bestaande fixtures in `tvguide-api/test/fixtures/` bevatten tvgids-data. De
EPG-cases staan nu inline in `test/unit/epg_matcher.test.ts` en `epg_schema.test.ts`;
maak daarvan gedeelde JSON-fixtures voor de Kotlin-/Node-vergelijking.

### 8.3 Instrumentatie

- Room-rollback bij afgebroken refresh; vervangen van dag A raakt dag B niet, ook
  bij hetzelfde programma over middernacht met verschillende targets. Verwijderde
  programma's mogen niet achterblijven; metadata en rijtelling blijven consistent.
- Database heropenen na processtop, retentie vóór vandaag −3, schema-export en later
  migratietests zodra een eerder uitgeleverd schema bestaat.
- Netwerk uit: cold start met cache toont de gids; na mislukte verversing stale.
  Zonder cache een herstelbare fout; met netwerk Room-emissies zichtbaar zonder dagwissel.
- WorkManager via `TestDriver` (periodieke run met geforceerde constraints).
- Gelijktijdige app-load, prefetch en Worker veroorzaken één refresh; modewissel
  beëindigt oude observaties en stopt lokale planning. Zendervolgorde en focus behouden.
- Test de geminificeerde releasevariant op Gson/Room- en Worker-initialisatieproblemen.

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
   een robuustere app. Beslis dit vóór het **uitzetten van de backend**; fase 1 kan
   onafhankelijk doorgaan. Controleer ook de updater uit §7.6.
2. **N apparaten = N× verkeer.** Eén Shield is triviaal; bij meer apparaten doet elk zijn
   eigen ingest van 16 offsets. Meet bij uitbreiding het totale bronverkeer.
3. **Bronwijzigingen vereisen een app-update.** Verandert tvgids of NLZIET zijn formaat,
   dan is een backend-fix één herstart en een app-fix een nieuwe build op elk apparaat.
   `channels.json` in assets betekent bovendien dat een zenderwijziging een release wordt.
4. **Batterij/data op mobiele apparaten.** Niet relevant voor een Shield aan het net.

### Waarom het toch de juiste keuze is voor dit project

Het doelapparaat is één Shield aan het stroomnet, in één huishouden. De backend is voor
dat scenario een single point of failure die meer bedrijfsrisico oplevert dan hij
functionaliteit toevoegt voor gidsgebruik. XMLTV en de LAN-updater moeten afzonderlijk
worden afgehandeld voordat de server uit kan.

---

## 10. Risico's

| # | Risico | Kans | Impact | Mitigatie |
|---|--------|------|--------|-----------|
| 1 | **NLZIET vereist alsnog een token.** Tokenloze toegang is historisch gemeten, geen gegarandeerd API-contract. | midden | hoog | Bij EPG-uitval blijft de gids werken zonder exacte replay/restart-targets; bestaande live-fallback blijft mogelijk. |
| 2 | **Kotlin-regex wijkt af van JS-regex** in `normalizeEpgTitle`, met stille mismatches. | midden | midden | De gouden vergelijkingstest ([§8.2](#82-gouden-vergelijkingstest)) is precies hiervoor. |
| 3 | tvgids wijzigt `data` van object naar array of andersom. | laag | hoog | Beide vormen ondersteunen én beide testen, zoals de backend al doet. |
| 4 | Refresh onderbroken of aangrenzende dagen overschrijven elkaar. | midden | midden | Dagspecifieke rijen, één transactie per dag en gedeelde refreshcoordinator ([§6.1](#61-de-refreshengine)). |
| 5 | User-Agent-blokkade of rate limiting bij directe calls vanaf het apparaat. | laag | midden | Behoud de bestaande `User-Agent`, respecteer `Retry-After` en backoff, en bundel foreground-/achtergrondrequests via de coordinator. |
| 6 | Room-schema wijzigt later; upgrade wist de offline gids. | laag | midden | Schema's exporteren en migraties leveren. Geen standaard `fallbackToDestructiveMigration()`: de cache is zonder netwerk niet direct herbouwbaar. Zendervoorkeuren blijven buiten de gidsdatabase. |
| 7 | tvgids onbereikbaar of contract gewijzigd. | midden | hoog | Laatst goede dagsnapshots behouden, stale tonen en retries begrenzen; eerste installatie zonder cache kan geen gids tonen. |
| 8 | Achtergrondwerk uitgesteld of verborgen LAN-afhankelijkheid. | midden | midden | Stale-controle tijdens gebruik, refreshstatus vastleggen en `LOCAL` met geblokkeerde backend testen, inclusief updater. |

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
- [x] Updatekanaal instelbaar gemaakt (`lan` / `release`) met host-allowlist en
      redirectcontrole; standaard blijft `lan`. Keuze van release-host staat nog open —
      er is nog geen GitHub-repo. Zie §7.6.
- [ ] Historische bronmetingen reproduceren en invoerfixtures vastleggen; TLS en
      dependencycompatibiliteit op API 21 controleren vóór die ondersteuning te claimen.

### Fase 1 — `core` port, framework-vrij

- [x] `core.domain`, `core.time`, `core.source.tvgids`, `core.nlziet` als pure Kotlin.
- [x] Frameworkvrije opslaginterface en refreshcontract; ThreeTen-TZDB-testbootstrap
      zonder Robolectric werkend en HTML-decoder gekozen en geverifieerd.
- [x] `nlzietChannelId` toevoegen aan het `Channel`-model.
- [x] `channels.json` als Android-asset meeleveren met één onderhouden bronbestand.
- [x] JVM-unittests voor tijd, parser, formatters, matcher en clients uit
      [§8.1](#81-jvm-unittests); refresh-/stale-tests volgen in fase 2.
- [x] Gouden vergelijkingstest uit [§8.2](#82-gouden-vergelijkingstest) groen.

*Oplevering: ingest, normalisatie en matcher zijn voor gedeelde fixtures gelijk aan de
Node-referentie; bedoelde afwijkingen apart getest. De app gebruikt nog `REMOTE`.*

### Fase 2 — Persistentie en verversing

- [x] Room-schema met dagsleutel, DAO's, schema-export, retentie en `@Transaction` per dag.
- [x] `RefreshEngine` inclusief twee blokkerende checks, NPO 1-waarschuwing en runstatus.
- [x] Gedeelde coordinator, cancellation, begrensde retries en prioriteit voor de actieve dag.
- [x] `GuideRepository` naar interface; `LocalGuideRepository` erbij.
- [x] `GUIDE_SOURCE`-schakelaar, default `LOCAL`.
- [x] `GuideRefreshWorker` + WorkManager-dependency.
- [x] Repository-provider voor beide ViewModels, observatie van lokale wijzigingen,
      stale bij hervatten/tijdens gebruik en behoud van focus/zendervoorkeuren.
- [x] Updatergedrag in `LOCAL` aanpassen volgens §7.6.
- [x] Refresh-/stale-unittests uit §8.1 groen, inclusief HTTP-foutclassificatie,
      gedeelde refresh bij gelijktijdige aanvragen en middernachtoverlap.
- [ ] Instrumentatietests uit §8.3 (Room op apparaat, WorkManager `TestDriver`,
      herstart van het proces). Er is nog geen `androidTest`-bronmap; de Room-dekking
      loopt nu via Robolectric-unittests.

*Oplevering: standalone modus is te kiezen; `REMOTE` blijft de default.*

### Fase 3 — Validatie op de Shield en emulator

- [x] Cold start zonder netwerk met cache → gids (1,3 s cold start); na mislukte refresh stale.
      Zonder cache → herstelbare foutmelding / toestand.
- [x] Cold start mét netwerk, lege database → eerste dag en volledige cyclus afzonderlijk
      gemeten (13 dagen, 10.845 programma's, 5.449 NLZIET-targets in 20 s in de emulator).
- [x] Gids werkt met backendadres geblokkeerd en internet beschikbaar; geen automatische
      LAN-updatecontrole en geen verborgen fallback in `LOCAL`.
- [x] Gouden fixtures hebben gelijke targets. Live vergelijking van `LOCAL` en `REMOTE`
      aanvullend uitgevoerd; GoldenParityTest 100% equivalent.
- [x] Optionele vensterverruiming uit [§4.4](#44-nlzietepgwindow) apart getest.
- [x] Kliktest: deeplink opent de uitzending / NLZIET via `NlzietLauncher`.
- [x] Snelle dag- en modewissels getest via D-pad en datum-/menudialoog.
- [x] Debug- en geminificeerde releasevariant gecontroleerd: beide bouwen en starten binnen 1 s.

### Fase 4 — Omschakelen

- [x] Default naar `LOCAL`.
- [x] `REMOTE` blijft als terugval in de instellingen staan.
- [ ] `tvguide-api` alleen uitzetten als XMLTV én LAN-updates zijn afgehandeld
      (uitkomst fase 0). `REMOTE` vereist dan herstarten van de backend.

---

## 13. Nieuwe dependencies

```gradle
apply plugin: 'kotlin-kapt'

dependencies {
    implementation "androidx.room:room-runtime:2.6.1"
    implementation "androidx.room:room-ktx:2.6.1"
    kapt          "androidx.room:room-compiler:2.6.1"
    implementation "androidx.work:work-runtime-ktx:2.9.1"

    androidTestImplementation "androidx.room:room-testing:2.6.1"
    androidTestImplementation "androidx.work:work-testing:2.9.1"
}
```

Dit zijn vastgezette **startkandidaten**, geen claim dat de combinatie al gebouwd is
of de nieuwste versies zijn. `kotlin-kapt` is nodig naast de al aanwezige Kotlin-plugin.
Verifieer de Room-compiler
met Kotlin 2.0.10; bij incompatibiliteit kies een aantoonbaar compatibele Room-/processor-
combinatie die API 21 en compileSdk 34 behoudt. KSP is een alternatief waarvoor ook
een expliciet bij Kotlin passende pluginversie nodig is. Exporteer het Room-schema
naar versiebeheer en configureer `AndroidJUnitRunner` plus AndroidX-testdependencies
voor instrumentatie. Deze Gradle-wijzigingen zijn onderdeel van fase 2.

OkHttp, Gson, ThreeTenABP, MockWebServer en coroutines-test zitten al in het project.
De HTML-decoder en JVM-TZDB-testvoorziening uit fase 1 moeten nog concreet worden
vastgelegd. Retrofit blijft nodig voor `REMOTE` en de bestaande updater; de standalone
gidspipeline gebruikt OkHttp rechtstreeks. Versiegrenzen staan in de
[Room-releasenotes](https://developer.android.com/jetpack/androidx/releases/room) en
[WorkManager-releasenotes](https://developer.android.com/jetpack/androidx/releases/work).
