# Uitvoeringsplan — exacte NLZIET-EPG-koppeling

Status: **ontwerp vastgesteld, nog niet geïmplementeerd**  
Datum: 30 augustus 2026

## Doel

Een klik op een programma in NexusTVGuide moet, wanneer NLZIET dat programma kan
afspelen, naar **dezelfde uitzending of aflevering** in NLZIET leiden. De bestaande
titel-gebaseerde VOD-koppeling is daarvoor niet geschikt: één titel kan vele afleveringen
hebben en een VOD-ID bevat geen uitzendlocatie.

Dit plan vervangt die onveilige programmakoppeling door een koppeling met de NLZIET-EPG.
Het plan verandert niets aan XMLTV of de gidsweergave; het voegt uitsluitend veilige
afspeeldoelen aan REST-programma's toe.

## Vastgestelde feiten

### NLZIET-bron

De NLZIET-webapp gebruikt een eigen, momenteel zonder accounttoken leesbare EPG-bron:

- `GET https://api.nlziet.nl/v9/epg/channels`
- `GET https://api.nlziet.nl/v9/epg/programlocations?date=YYYY-MM-DD&channel=<id>`

Meerdere `channel`-parameters zijn toegestaan (bijv. `&channel=npo1&channel=rtl4`). Het endpoint retourneert een envelope met channel-groepen en daarbinnen een lijst van `programLocations`:

```json
{
  "data": [
    {
      "channel": {
        "content": {
          "id": "npo1",
          "title": "NPO 1"
        }
      },
      "programLocations": [
        {
          "content": {
            "contentItemId": "pXZD1nmyCkSuW_pB1ylCQg",
            "assetId": "108C33FB3A16FDFCE5E88B43871AC6BA",
            "title": "NOS Studio Sport Live: WK Roeien",
            "startAt": "2026-08-30T13:11:53+02:00",
            "endAt": "2026-08-30T13:34:43+02:00",
            "isReplayAllowed": true,
            "isRestartAllowed": true
          }
        }
      ]
    }
  ]
}
```

`contentItemId` is een base64url-ID van 22 tekens. `assetId` is de concrete
uitzendlocatie (32 hoofdletterige hexadecimale tekens). De combinatie is dus het vereiste
afspeeldoel; alleen `contentItemId` is niet voldoende.

De webconfiguratie meldt momenteel een EPG-venster van zeven dagen terug en zeven dagen
vooruit. Dit is geen gedocumenteerde publieke API. De implementatie behandelt de bron
daarom als veranderlijk: runtimevalidatie, tijdslimieten, caching, meetwaarden en een
veilige fallback zijn verplicht.

### Bevestigde Android-TV-routes

Analyse van NLZIET Android TV 5.15.3 (build 740504) bevestigt de volgende custom routes:

| Gebruik | Route | Benodigde gegevens |
|---|---|---|
| Exacte replay / restart | `nlziet://open/epg/<contentItemId>/<assetId>` | content- en asset-ID |
| Live kanaal | `nlziet://open/tv-kijken/<channelId>` | NLZIET-zender-ID |
| Algemene VOD-pagina | `nlziet://open/vod/<contentItemId>` | alleen content-ID |

`nlziet://watchnext/<id>` is geen geregistreerde route. De bestaande VOD-route blijft
alleen nuttig voor een expliciete, algemene VOD-actie; zij mag niet meer het resultaat van
een klik op een gidsprogramma zijn.

### Onderzoeksresultaat op de snapshot van 2026-08-30

Een proefmatch tussen de opgeslagen TVgids-snapshot en dezelfde NLZIET-EPG-dag gaf:

- 601 gidsprogramma's in totaal;
- 434 eenduidige koppelingen na zender-, tijd-, titel- en duurcontrole;
- 409 daarvan met `isReplayAllowed: true`;
- mediaan tijdsverschil 20 seconden en het 90e percentiel onder twee minuten.

Dit zijn momentopnamen, geen service-level afspraak. Ze rechtvaardigen wel een strikte
automatische match. Een niet-eenduidig programma krijgt bewust geen afspeel-doel.

De drie afwijkende interne zender-ID's zijn:

| NexusTVGuide | NLZIET EPG |
|---|---|
| `vrtcanvas` | `canvas` |
| `bbc1` | `bbcone` |
| `bbc2` | `bbctwo` |

Voor Canvas en BBC week de onderzochte TVgids-planning materieel af van de NLZIET-planning.
BBC heeft bovendien geen replay. Deze zenders worden daarom niet versoepeld gematcht. Een
live-deeplink is alleen toegestaan wanneer de geselecteerde gidsentry op dat moment echt
loopt en de zendercode is gevalideerd.

## Doelarchitectuur

```text
json.tvgids.nl/v4                api.nlziet.nl/v9/epg
        |                                  |
        +----------- RefreshEngine --------+
                         |
                         v
              strict NlzietEpgMatcher
                         |
                         v
       snapshot / GET /api/v1/guide: programme.nlziet
                         |
                         v
                Android ProgrammeDto
                         |
             exact replay, live, or veilige launch-fallback
```

De TVgids-bron blijft de eigenaar van titel, omschrijving en gridtijden. NLZIET levert
alleen een afspeelbaar, exact EPG-doel. Bij een bronfout of een onzekere match blijft de
gids zichtbaar, maar zonder directe programma-deeplink.

## API- en datacontract

### Nieuw programma-veld

In `tvguide-api/src/domain/programme.ts` komt het volgende optionele veld:

```ts
export interface NlzietProgrammeTarget {
  kind: 'replay';
  contentItemId: string;       // exact 22 base64url-tekens
  assetId: string;             // exact 32 hoofdletterige hex-tekens
  channelId: string;           // NLZIET EPG-zender-ID, bijvoorbeeld "npo1"
  isReplayAllowed: boolean;
  isRestartAllowed: boolean;
}

export interface Programme {
  // bestaande velden
  nlziet?: NlzietProgrammeTarget | null;
  /** @deprecated Niet gebruiken voor een klik op een gidsprogramma. */
  nlzietId?: string | null;
}
```

Een REST-consument kan zo veilig onderscheiden tussen een concrete uitzending en een
generieke VOD. `nlziet` wordt alleen ingevuld na een eenduidige EPG-match. Het veld is
`null` of afwezig bij een niet-match, een ongeldige upstream-response, een dag buiten het
NLZIET-venster of een ambigu resultaat.

Voorbeeld van een REST-item:

```json
{
  "id": "218748452",
  "channelId": "npo1",
  "title": "Studio sport live: WK roeien",
  "start": "2026-08-30T11:10:00.000Z",
  "end": "2026-08-30T11:35:00.000Z",
  "nlziet": {
    "kind": "replay",
    "contentItemId": "pXZD1nmyCkSuW_pB1ylCQg",
    "assetId": "108C33FB3A16FDFCE5E88B43871AC6BA",
    "channelId": "npo1",
    "isReplayAllowed": true,
    "isRestartAllowed": true
  }
}
```

### Migratie van `nlzietId`

1. De backend stopt met het vullen van `nlzietId` vanuit de catalogus-, alias- of
   titelmatcher.
2. Het veld blijft één release aanwezig in snapshots en API-responses, zodat oudere
   clients kunnen blijven deserialiseren.
3. De nieuwe Android-client negeert `nlzietId` bij een gidsklik. Daardoor kan een oude
   snapshot nooit meer per ongeluk een andere VOD-aflevering openen.
4. Na een stabiele release en Shield-validatie wordt beslist of de catalogusscraper en het
   legacy veld verwijderd worden. Dat is geen onderdeel van de eerste implementatie.

## Backenduitwerking

### 1. Zenderconfiguratie

Voeg `nlzietChannelId: string | null` aan `Channel` en `channels.json` toe. Dit is een
technische EPG-ID en **niet** hetzelfde als de bestaande `nlzietSlug`, die naar een
publieke cataloguspagina verwijst.

- Voor de meeste gekozen zenders is de waarde gelijk aan de interne `id` (`npo1`, `rtl4`,
  `sbs6`, enzovoort).
- Gebruik expliciet `canvas`, `bbcone` en `bbctwo` voor respectievelijk `vrtcanvas`,
  `bbc1` en `bbc2`.
- De refresh gebruikt uitsluitend actieve zenders met een niet-lege
  `nlzietChannelId`.
- Een onbekende of uit `channels` verdwenen NLZIET-zender is een waarschuwing en levert
  geen programma-targets op; hij mag de volledige gidsrefresh niet blokkeren.

### 2. NLZIET-EPG-client en validatie

Voeg een kleine, geïsoleerde client toe, bijvoorbeeld
`src/enrichment/nlziet/epg-client.ts`:

- bouw de URL met `URL` en herhaalde `searchParams.append('channel', id)`;
- gebruik een connectie-/responstijdslimiet van hoogstens 10 seconden;
- hoogstens twee retries met korte exponentiële back-off voor netwerk- en 5xx-fouten;
- valideer het antwoord met Zod voordat het in de matcher terechtkomt;
- accepteer alleen geldige `contentItemId`, `assetId`, RFC 3339-tijden en booleans;
- log geen volledige payloads of accountgegevens;
- maak basis-URL, timeout en retries via opties testbaar.

De client zet de EPG-datum uitsluitend om naar de lokale kalenderdag `Europe/Amsterdam`.
Hij wordt niet aangeroepen voor dagen buiten `vandaag - 7` tot en met `vandaag + 7`.

### 3. Cache- en foutbeleid

De EPG is niet nodig om een gids te tonen. Daarom gelden deze regels:

- cache per lokale datum en set zenders in het proces;
- ververs vandaag kort (bijvoorbeeld 10 minuten), morgen en toekomstige dagen langer
  (bijvoorbeeld 60 minuten), verleden dagen nog langer (bijvoorbeeld 6 uur);
- invalideer een cache-entry uitsluitend na een volledig, gevalideerd antwoord;
- bij een mislukte EPG-fetch blijft de TVgids-refresh doorgaan zonder nieuw target;
- behoud een bestaand target alleen als `channelId`, programma-ID, start, eind en titel
  exact gelijk zijn aan de vorige snapshot; anders wist de refresh het target;
- rapporteer fetch-fouten en matchstatistieken via `/health`, zonder de status van de
  basisgids op `degraded` te zetten zolang de TVgids-refresh zelf slaagt.

### 4. Strikte matcher

Voeg een EPG-index en matcher toe, bijvoorbeeld `epg-matcher.ts`. De matcher werkt per
dag en nooit over zenders heen.

Een kandidaat is alleen geldig als al deze voorwaarden gelden:

1. dezelfde geconfigureerde NLZIET-zender-ID;
2. starttijd maximaal zes minuten afwijkend;
3. tijdsduur maximaal tien minuten afwijkend;
4. dezelfde canonieke titel na beperkte, deterministische normalisatie;
5. precies één kandidaat blijft over.

De EPG-normalisatie is nadrukkelijk strenger dan de oude catalogusmatcher. Toegestaan zijn
onder andere accentnormalisatie, leestekens, bekende omroepprefixen (`NOS`, `AVROTROS`,
`NPO`) en episode-/live-labels. Niet toegestaan zijn losse prefix-, substring- of
catalogusmatches. Bij gelijke scores of meerdere kandidaten: geen target.

Een toekomstig, handmatig beheerd aliasbestand mag pas worden toegevoegd als het zowel
zender- als tijdgebonden is en daarvoor een regression test bestaat. Het huidige generieke
`nlziet_overrides.json` is geen bron voor replay-targets.

### 5. Refresh-integratie en meetwaarden

Per dag haalt `RefreshEngine` eerst de TVgids-programma's op, daarna — binnen het
ondersteunde datumvenster — de NLZIET-EPG voor de actieve zenders. Vervolgens vult de
matcher `programme.nlziet`.

Breid `EnrichmentStats` uit met ten minste:

```ts
{
  totalProgrammes: number;
  epgEligibleProgrammes: number;
  exactTargets: number;
  replayAllowedTargets: number;
  rejectedAmbiguous: number;
  rejectedTitleOrTiming: number;
  skippedOutsideEpgWindow: number;
  epgFetchFailed: boolean;
}
```

Deze cijfers maken regressies zichtbaar, bijvoorbeeld wanneer NLZIET de API of een
zendercode wijzigt.

## Android-uitwerking

### DTO en validatie

Voeg aan `ProgrammeDto` een nullable `NlzietProgrammeTargetDto` toe met dezelfde JSON-namen
als het backendcontract. Valideer vóór het maken van een Intent:

- `contentItemId`: `^[A-Za-z0-9_-]{22}$` en geen placeholder;
- `assetId`: `^[A-F0-9]{32}$`;
- `channelId`: veilige, niet-lege NLZIET-zender-ID.

### Selectieregels

`NlzietLauncher.launchProgramme` volgt deze volgorde:

1. Een geldig exact target met `isReplayAllowed` opent
   `nlziet://open/epg/<contentItemId>/<assetId>`.
2. Een programma dat op dat moment loopt en een geldig target met
   `isRestartAllowed` heeft, mag dezelfde EPG-route gebruiken voor restart.
3. Een programma dat op dat moment loopt, maar geen bruikbare replay-/restarttarget heeft,
   opent uitsluitend `nlziet://open/tv-kijken/<channelId>`.
4. Alle andere gevallen openen de gewone NLZIET Leanback-app.

Een fout bij het starten van een deeplink valt terug op de gewone app-launch. Een toekomstig
of niet-replaybaar programma mag nooit via de oude `nlzietId` naar een generieke VOD-route
gaan.

De bestaande `createVodDeeplinkIntent` kan blijven bestaan voor een later, expliciet
VOD-scherm, maar wordt niet vanuit `launchProgramme` aangeroepen.

## Testplan

### Backend-unit- en integratietests

Voeg vaste, geanonimiseerde EPG-fixtures toe; tests maken geen live call naar NLZIET.

- EPG-response met juiste waarden wordt geparseerd.
- Ongeldige IDs, ontbrekende velden en ongeldige tijdstippen worden afgewezen.
- Herhaalde `channel`-queryparameters worden correct opgebouwd.
- Het juiste item wordt gekozen wanneer hetzelfde programma meerdere keren op een dag
  voorkomt.
- Een verschil binnen tijd- en duurgrens resulteert in een target met het juiste
  `contentItemId` en `assetId`.
- Een titelverschil, te grote tijdsduurafwijking of verkeerde zender resulteert in geen
  target.
- Twee even goede kandidaten resulteren in geen target.
- Een datum buiten het EPG-venster roept de client niet aan.
- EPG-fout resulteert in een geldige gids zonder nieuw target en met de juiste statistiek.
- REST-contracttests verifiëren de serialisatie van `programme.nlziet` en compatibiliteit
  van oude snapshots zonder dat veld.

### Android-tests

- `createReplayDeeplinkIntent` produceert exact
  `nlziet://open/epg/<contentItemId>/<assetId>`.
- `createLiveDeeplinkIntent` produceert exact
  `nlziet://open/tv-kijken/<channelId>`.
- Foutieve content-, asset- en zender-ID's worden afgewezen.
- Een replaybaar `ProgrammeDto` kiest de replay-intent.
- Een actueel restartbaar programma kiest de EPG-intent.
- Een actueel live-only programma kiest de live-intent.
- Een toekomstig, ambigu of legacy-only programma kiest geen VOD-intent en valt terug op
  de gewone app-launch.

### Handmatige Shield-acceptatie

Test met de geïnstalleerde NLZIET-versie en een ingelogd account:

1. een recent NPO/RTL/Talpa-programma opent de juiste aflevering;
2. een lopend programma start op het juiste kanaal of vanaf het begin wanneer restart
   beschikbaar is;
3. een programma zonder replay opent niet een willekeurige VOD;
4. een toekomstig programma opent slechts de NLZIET-app;
5. een foutieve of niet-geïnstalleerde NLZIET-app geeft de bestaande nette fallback;
6. BBC en Canvas worden gecontroleerd op de actuele zenderplanning voordat zij voor een
   automatische match worden vrijgegeven.

## Gefaseerde uitvoering

1. **Contract en fixtures** — domeintype, channel-ID-configuratie, Zod-schema's en
   matcherfixtures toevoegen; nog geen gedrag in Android wijzigen.
2. **Backend** — client, cache, strikte matcher, refreshintegratie, REST-contract en
   health-statistieken implementeren; alle TypeScript-tests en build draaien.
3. **Android** — DTO, replay- en live-intents, veilige selectieregels en Robolectric-tests
   implementeren; debug/release-build uitvoeren.
4. **Migratiecontrole** — bevestigen dat de nieuwe client geen `nlzietId` meer voor een
   programma-klik gebruikt en dat oude snapshots veilig degraderen.
5. **Shield-validatie** — de handmatige acceptatieset uitvoeren en de geverifieerde
   NLZIET-appversie plus uitkomst documenteren.
6. **Productie** — eerst backend, vervolgens Android verspreiden; in de eerste week
   matchratio, EPG-fouten en deeplinkfeedback bewaken.

## Acceptatiecriteria

De wijziging is pas klaar wanneer:

- een `nlziet`-target altijd beide vereiste IDs bevat en een uniek EPG-item representeert;
- een gidsklik nooit meer een titel-gebaseerde, generieke VOD opent;
- een bron- of matchfout geen bestaande gidsdata of app-launch breekt;
- backend-, contract- en Android-tests groen zijn;
- op de Shield voor minimaal drie verschillende omroepen is bevestigd dat een gekozen
  replay de verwachte uitzending opent;
- het resultaat van de Shield-test en eventuele uitzonderingen per zender zijn toegevoegd
  aan `docs/PLAN.md`.

## Risico's en open verificaties

| Risico of vraag | Beheersing |
|---|---|
| NLZIET wijzigt of sluit de ongedocumenteerde EPG-endpoint | Client isoleren, schema valideren, cache gebruiken, fout veilig degradëren en matchratio monitoren. |
| TVgids- en NLZIET-tijden lopen uiteen | Alleen strikte unieke matches; nooit een gok of titel-only fallback. |
| Rechten of abonnement maken replay onmogelijk | `isReplayAllowed` en `isRestartAllowed` respecteren; altijd app-fallback. |
| BBC/Canvas-planning wijkt af | Alleen live openen als de actuele status klopt; automatische afleveringmatch uitgeschakeld tot verificatie. |
| Nieuwe NLZIET-TV-app verandert deeplinks | APK-/Shield-test als releasegate; centrale launcher houdt wijziging lokaal. |
| Oude snapshots bevatten `nlzietId` | Nieuwe Android-client negeert het veld voor programma-kliks. |

## Bronnen

- NLZIET-webapp en EPG: `https://app.nlziet.nl/` en
  `https://api.nlziet.nl/v9/epg/programlocations`
- NLZIET-zenderoverzicht: `https://www.nlziet.nl/nl/zenderoverzicht/`
- NLZIET-help over live, on demand en replay:
  `https://help.nlziet.nl/hc/nl/articles/360021628971-Kijken-met-NLZIET-live-on-demand-en-replay`
- Lokale APK-analyse: NLZIET Android TV v5.15.3, build 740504.
