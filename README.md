# NexusTVGuide

Een reclamevrije, remote-first tv-gids voor Android TV (NVIDIA Shield), die doorlinkt
naar NLZiet voor de daadwerkelijke playback.

> **Actueel bouwplan:** [`docs/plan-2026-08-30-bouwplan.md`](docs/plan-2026-08-30-bouwplan.md) — de normatieve bron voor scope,
> fasering, contracten en acceptatiecriteria. [`docs/doc-2026-08-30-achtergrond.md`](docs/doc-2026-08-30-achtergrond.md) bevat alleen
> de oorspronkelijke, deels achterhaalde verkenning.

## Waarom

NLZiet werkt prima als speler, maar de ingebouwde tv-gids is onwerkbaar. De bestaande
Nederlandse gids-apps (TVgids.nl, TVGiDS.tv, Cisana TV+) zijn telefoon-apps met banners:
geen Leanback-UI, geen D-pad-navigatie, dus op de Shield niet te gebruiken. De enige
goede EPG-grids op Android TV zitten in IPTV-clients (TiviMate, OTT Navigator, Kodi PVR),
en die verwachten een M3U met streams — niet een gids die naar een aparte app doorlinkt.

Die combinatie — fatsoenlijk EPG-grid + doorschakelen naar NLZiet — bestaat niet. Vandaar
dit project.

## Architectuur

Domme client, cachebare gids:

```
json.tvgids.nl/v4
        |
        v
  tvguide-api (LXC, Node.js 24/TypeScript)
        |
        +--> REST   -> Android TV-app met egeniq-grid -> Intent -> NLZiet
        +--> XMLTV  -> Jellyfin / TiviMate / Kodi
```

- **Bron** is de tvgids.nl JSON-API. Geen XMLTV-grabber nodig; dat scheelt een schakel
  ten opzichte van het oorspronkelijke plan.
- **Normalisatielaag** draait als systemd-service in een LXC, ververst periodiek en zet de
  bron om naar één eigen model met REST- en XMLTV-output. Niet optioneel: de bron is
  ongedocumenteerd, dus dit is de plek waar een wijziging wordt opgevangen zonder de app
  te raken.
- **Client** rendert het grid en start NLZiet via een Intent.

## Routes

Drie opties, afnemend in integratiediepte en inspanning:

1. **TvInputService (TIF)** — mooiste integratie. Je zet kanalen en programma's in de
   systeem-content-provider en krijgt de native Google TV / Live Channels-gids gratis,
   inclusief remote-navigatie en "volgende aflevering"-kaarten op het homescreen.
   Nadeel: het framework verwacht dat jij playback levert. Doorschakelen naar NLZiet moet
   via een intent — technisch mogelijk, maar buiten het TIF-model.

2. **Eigen Android TV-app** — GEKOZEN, in de vorm van een egeniq-fork. Meeste controle en
   snelste route naar iets bruikbaars. Let op: egeniq is Leanback/Views (XML-layouts), niet
   Compose for TV zoals oorspronkelijk aangenomen. Doorklikken = Intent naar NLZiet.

3. **Web-app als tweede scherm** — laagste inspanning. Zelfgehoste PWA op het homelab,
   gids op tablet/telefoon, met een knop die via ADB of de Shield-remote de juiste zender
   start. Dit is de fallback als deeplinken naar NLZiet niet blijkt te werken.

## Bouwstenen

### UI-laag — VASTGESTELD

**[egeniq/android-tv-program-guide](https://github.com/egeniq/android-tv-program-guide)**
is de UI-laag. Dit is een besluit, geen optie meer.

- Open-source EPG-implementatie specifiek voor Android TV, gebaseerd op
  de officiële Android TV channel browser, waarbij het ophalen van programmadata uit de
  kabelaansluiting is vervangen door je eigen datalevering. Kotlin + AndroidX. Rij per
  zender, links/rechts door de tijd, dag-knoppen, jump-to-live.
- Status: 178 stars, Kotlin, laatste commit aug 2024. Niet gearchiveerd, maar wel stil —
  reken erop dat je zelf onderhoudt en tegen verouderde Gradle/AndroidX-pins aanloopt.

Niet gekozen, bewaard als referentie:

- `korre/android-tv-epg` en de Kotlin-fork `abdlhay/android-epg` — klassiek EPG-grid met
  scrollen in alle richtingen, maar meer op tablet/telefoon gericht dan op D-pad.
- **OwnTV** — Kotlin + Compose for TV, ExoPlayer/mpv, volwaardig XMLTV-guidegrid met
  catch-up. Gebouwd rond M3U/Xtream dus niet direct bruikbaar, maar het beste voorbeeld
  van hoe zo'n grid remote-first werkt.

### Datalaag (NL) — geverifieerd rond 2026-08-29/30

Alle drie de kandidaten zijn getest. Uitkomst: **json.tvgids.nl/v4 als primaire bron,
iptv-org/epg als fallback, tvgrabpyAPI afgevallen.**

#### 1. json.tvgids.nl/v4 — PRIMAIR

De ongedocumenteerde endpoint uit de oude repo's is nog gewoon in de lucht en levert
precies wat het grid nodig heeft.

```bash
curl 'https://json.tvgids.nl/v4/channels'                  # 122 zenders, 56 met default=true
curl 'https://json.tvgids.nl/v4/programs/?day=0&channels=1'
```

Let op: **`day` is een relatieve integer-offset voor een uitzenddag-bucket, geen ISO-datum
of Nederlandse kalenderdag.** Zo'n bucket loopt grofweg van de vroege ochtend tot de
volgende ochtend en kan rond lokale middernacht nog bij de vorige datum horen. Een
ISO-datum meegeven levert HTTP 200 met lege `prog`-arrays op — stil falen. De backend haalt
daarom offsets `-2..13` op en deelt programma's daarna op hun echte timestamps in lokale
dagvensters in. De app toont conservatief gisteren, vandaag en de komende zeven lokale
kalenderdagen.

Programma-velden: `s`/`e` (start/eind, Unix-seconden), `title`, `descr` (bevat HTML),
`algemene_inhoud` en `inhoud` (platte tekst — in die volgorde prefereren), `img`, `g_id` +
`subgenre`, en flags `live`, `rerun`, `is_premiere`, `tip`, `ei` (rommelig kijkwijzerveld).

Dat is genoeg voor het grid, poster-art en badges zonder tweede bron. Payload zit in
`data`, met naast de nuttige data ook een `versionmessage`-blok voor de officiële app —
negeren.

Risico: ongedocumenteerd en onaangekondigd wijzigbaar. Vandaar dat de normalisatielaag
niet optioneel is: één adapter aanpassen als het breekt, in plaats van de hele app.

#### 2. iptv-org/epg — FALLBACK

**Correctie op de aanname in `docs/doc-2026-08-30-achtergrond.md`: de site heet `tvgids.nl`, niet
`tvgids.tv`** — daarom geeft de daar genoemde guide-URL
`guides/nl/tvgids.tv.epg.xml` een 404.

Belangrijker: `sites/tvgids.nl/tvgids.nl.config.js` gebruikt de JSON-API niet, maar
**scrapet `www.tvgids.nl/gids/...` met cheerio**, en leidt eindtijden af door 30 minuten
op te tellen en dat achteraf te corrigeren met de starttijd van het volgende programma.
Dat is fragieler en grover dan route 1, en `days: 2` is aanzienlijk minder vooruitkijken
dan de ~14 dagen van de JSON-API.

Wel bruikbaar: `sites/tvgids.nl/tvgids.nl.channels.xml` als kant-en-klare zender-mapping,
en andere NL-bronnen in de repo (`horizon.tv`, `epgshare01.online`, `artonline.tv`).

#### 3. tvgrabbers/tvgrabpyAPI — AFGEVALLEN

Laatste commit december 2022, 28 stars. De omschrijving "actief onderhouden" in
`docs/doc-2026-08-30-achtergrond.md` klopt niet meer. Python-dependency in een verder Kotlin/TS-stack, voor
detail (seizoen/aflevering) dat de JSON-API grotendeels ook levert.

Blijft waardevol als **documentatie**: `tvgrabbers/sourcematching` bevat leesbare
JSON-specs van hoe je bij de NL-bronnen komt.

#### Combinatie

JSON-API als bron, iptv-org's `channels.xml` voor de zender-mapping, tvgrabpyAPI's
sourcematching als naslag bij een gebroken endpoint.

## Volgorde van werken

Vastgesteld: eerst de backend bewijzen, daarna de Android TV-app bouwen en pas daarna de
NLZiet-deeplinks onderzoeken.

### Fase 1 — backend

- Node.js/TypeScript-service in de LXC: `json.tvgids.nl/v4` valideren en normaliseren.
- Lokale dagsnapshots, stale-data-fallback, REST, XMLTV, `/health` en `/ready` opleveren.
- De zenders uit het actieve NLZiet-abonnement in `channels.json` vastleggen; de 56
  `default=true`-zenders van tvgids.nl zijn alleen een kandidatenlijst.
- XMLTV eerst in een bestaande client verifiëren. Daarmee is de datalaag bewezen voordat
  Android-code de diagnose ingewikkelder maakt.

### Fase 2 — Android TV

- Egeniq forken met behoud van de upstream-Git-history en de demo ombouwen tot app.
- Het grid uit de REST-API vullen en gisteren, vandaag en de komende zeven dagen tonen.
- D-pad, offline fallback, netwerkdip en beide zomertijdwissels op de Shield testen.

Tot NLZiet is uitgezocht: laat het aanklikken van een programma een NLZiet-launch-intent
zijn, zonder deeplink. Dan is de app al bruikbaar en is de deeplink puur een verbetering.

### Fase 3 — NLZIET-koppeling (in validatie)

Analyse en apparaattests met NLZIET Android TV v5.15.3 (build `740504`) bevestigen
`nlziet://watchnext/<contentItemId>` als de werkende TV-afspeelroute. De TV-build verwerkt
deze URI alleen via `InjectActivity.onNewIntent()`: bij een koude start opent de eerste
intent uitsluitend de app en moet dezelfde intent na initialisatie nogmaals worden
aangeboden. NexusTVGuide doet dat automatisch na 1,5 seconde. Dit is op de Android-TV-emulator
met een actuele EPG-entry geverifieerd; de Shield-acceptatietest blijft de releasegate.

De backend verrijkt de gids voorlopig op titel/alias met een VOD-content-ID. Dat kan een
bijpassend catalogusitem openen, maar is nog geen garantie voor exact dezelfde aflevering of
live-uitzending. De strikte NLZIET-EPG-koppeling levert daarvoor het exacte
`contentItemId` plus de bijbehorende `assetId`; de TV-route gebruikt het content-ID en de
asset-ID blijft onderdeel van de bewijsbare backendmatch. De uitwerking staat in
[`docs/plan-2026-08-30-nlziet-epg-mapping.md`](docs/plan-2026-08-30-nlziet-epg-mapping.md). De volledige technische
status en de Shield-acceptatietest staan in [`docs/plan-2026-08-30-bouwplan.md`](docs/plan-2026-08-30-bouwplan.md).

## Scope

Het MVP bestaat uit twee zelfstandig bewijsbare delen: de normalisatieservice met REST- en
XMLTV-output, en de egeniq-gebaseerde Android TV-app met een gewone NLZiet-launch-intent.
Zenderdeeplinks zijn een latere verbetering en blokkeren de eerste bruikbare versie niet.

De exacte afbakening en definition of done per fase staan in
[`docs/plan-2026-08-30-bouwplan.md`](docs/plan-2026-08-30-bouwplan.md#definition-of-done-per-fase).

## In-App Updates & Releases

NexusTVGuide beschikt over een veilig, D-pad bedienbaar in-app updatesysteem volgens [`docs/plan-2026-08-31-in-app-updates.md`](docs/plan-2026-08-31-in-app-updates.md):

- **Backend Distributie**: `tvguide-api` serveert release-metadata via `GET /api/v1/app/version` en APK-downloads via `GET /api/v1/app/download/:filename` met Zod-validatie, integriteitscontroles (SHA-256) en path-traversal beveiliging.
- **Serverlocatie (.171)**: `/opt/nexustvguide-api/current/tvguide-api/data/releases/` (bevat `version.json` en `nexus-tv-guide-<versie>.apk`).
- **Android Updater**: Single-flight downloader met 24-uurs passieve throttle, bestandsvalidatie (grootte en SHA-256), APK-preflight (package identity, monotone version codes en signing certificaten) en integratie via `PackageInstaller.Session`.
- **Geautomatiseerde Publicatietooling**: `tools/publish-release.mjs` automatiseert de keystore-controle, `assembleRelease`, `apksigner verify`, SHA-256 berekening, atomaire publicatie en directe SCP-upload naar de server op .171.

### Updatekanalen

De app kent twee updatekanalen. Gidsdata staan hier los van: in `LOCAL` haalt de app die
rechtstreeks bij de bron op, maar nieuwe APK-versies moeten altijd ergens vandaan komen.

| Kanaal | Host | Manifest | Wanneer |
|--------|------|----------|---------|
| `lan` (standaard) | `tvguide-api` op .171 | `api/v1/app/version` | Huidige opstelling; vereist een draaiende backend |
| `release` | GitHub/Forgejo releases | `version.json` | Volledig LAN-onafhankelijk; host nog niet gekozen |

Instellen in `android/gradle.properties` of per build:

```bash
./gradlew :app:assembleRelease \
  -PupdateChannel=release \
  -PupdateBaseUrl=https://github.com/<user>/NexusTVGuide/releases/latest/download/
```

De build faalt als een release-kanaal geen host of geen HTTPS heeft. De app accepteert een
APK alleen van de eigen origin of van een host op de ingebakken allowlist
(`UPDATE_HOST_ALLOWLIST`), controleert elke redirect-hop opnieuw, en verifieert daarna
SHA-256 én het signing-certificaat.

### Nieuwe Release Bouwen en Publiceren

```bash
# LAN-kanaal — bouwt productie-APK, verifieert handtekening en uploadt naar server .171:
node tools/publish-release.mjs --notes "• Wijzigingen in deze versie"

# Alleen lokaal bouwen zonder upload naar server:
node tools/publish-release.mjs --notes "• Wijzigingen in deze versie" --no-deploy

# Release-kanaal — bouwt, publiceert de release en uploadt APK + version.json.
# Geen `gh` nodig; de tool praat rechtstreeks met de API.
RELEASE_TOKEN=<pat> node tools/publish-release.mjs --channel release \
  --repo <eigenaar>/NexusTVGuide \
  --notes "• Wijzigingen in deze versie"

# Tegen een eigen Forgejo/Gitea:
RELEASE_TOKEN=<pat> node tools/publish-release.mjs --channel release \
  --repo <eigenaar>/NexusTVGuide --forge forgejo --api-base https://forgejo.example.com \
  --notes "• Wijzigingen in deze versie"
```

### Zelf publiceren via de API

`--repo` laat de tool de release aanmaken en beide assets uploaden. De tag is standaard
`v<versionName>` uit de APK zelf, en de download-URL wordt daaruit afgeleid — die hoeft dus
niet met de hand te kloppen. Na de upload vergelijkt de tool de URL die de server teruggeeft
met wat in `version.json` staat, en faalt bij een afwijking in plaats van een kapotte release
achter te laten.

| Optie | Betekenis |
|-------|-----------|
| `--repo <eigenaar>/<repo>` | Publiceer zelf via de API (i.p.v. `--download-base`) |
| `--forge github\|forgejo` | API-dialect; afgeleid uit `--api-base` als je het weglaat |
| `--api-base <url>` | Standaard `https://api.github.com`; verplicht voor Forgejo |
| `--tag <naam>` | Eigen tag i.p.v. `v<versionName>` |
| `--draft`, `--prerelease` | Publiceer als concept of pre-release |

**Het token komt uit de omgeving**, nooit uit een argument — argumenten belanden in
shell-history en zijn zichtbaar in de procestabel. De tool weigert een `--token`-vlag
expliciet. Gebruik `RELEASE_TOKEN` (of `GITHUB_TOKEN`):

```bash
RELEASE_TOKEN=$(cat ~/.config/nexustvguide/release-token) node tools/publish-release.mjs ...
```

Benodigde scope: GitHub fine-grained `contents: write` (classic: `repo`); Forgejo
`write:repository`. Een bestaande tag wordt nooit overschreven — dat zou de assets van een
al uitgerolde versie vervangen; verhoog `VERSION_NAME` of geef een andere `--tag`.

Zonder `--repo` schrijft de tool alleen APK + `version.json` naar `dist/release/`; dan moet
`--download-base` exact overeenkomen met de URL waar het asset belandt, en is de upload aan jou.
