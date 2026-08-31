# Plan: in-app APK-updates voor NexusTVGuide

Status: **geïmplementeerd en geverifieerd; backend routes, atomaire publicatietooling, Android updater-architectuur (PackageInstaller.Session), D-pad TV-UI en geautomatiseerde tests 100% afgerond**
Datum: 31 augustus 2026 (bijgewerkt: 1 september 2026)

Werkdocument voor het veilig controleren, downloaden en installeren van NexusTVGuide-updates
op Android TV. Dit plan sluit aan op
[`plan-2026-08-30-bouwplan.md`](plan-2026-08-30-bouwplan.md),
[`plan-2026-08-31-standalone-android.md`](plan-2026-08-31-standalone-android.md) en
[`plan-2026-08-31-channel-ordering.md`](plan-2026-08-31-channel-ordering.md).

Het plan is getoetst aan de codebase van **2026-08-31**. De huidige app heeft
`applicationId = com.nexustvguide.app`, `versionCode = 1`, `versionName = 0.5.0`,
`minSdk = 21` en `targetSdk = 34`. Het hamburgermenu waar dit plan gebruik van maakt is nog
niet geïmplementeerd; het staat beschreven in het zenderordeningsplan.

---

## 1. Doel en afbakening

### Doel

De gebruiker kan op een NVIDIA Shield of ander Android TV-/Google TV-apparaat vanuit de app:

1. controleren of een nieuwere versie beschikbaar is;
2. versie-informatie en release notes bekijken;
3. de APK met zichtbare voortgang downloaden en verifiëren;
4. de Android-systeembevestiging voor installatie openen;
5. na installatie dezelfde appdata blijven gebruiken.

Android blijft de beveiligingsgrens: de app kan de systeembevestiging voorbereiden, maar niet
zelf omzeilen. De gebruiker bevestigt de installatie in de officiële Android-interface.

### Wat behouden blijft

Bij een geldige update met hetzelfde application-ID en een compatibele signing key behoudt
Android de appdata, waaronder instellingen en een later geïmplementeerde zendervolgorde.
Cachebestanden zijn hiervan uitgezonderd: Android mag `cacheDir` altijd opruimen. De functie
mag daarom nooit correcte werking of databehoud van caches afhankelijk maken.

### Buiten scope

- stille installatie op geroote apparaten of via Device Owner-beheer;
- Google Play In-App Updates of automatische Play Store-updates;
- delta-updates en binary patching;
- hervatten van een gedeeltelijke download met HTTP Range in de eerste versie;
- app bundles en split-APK-sets: de releasebron levert één universele, monolithische APK;
- meerdere gelijktijdige releasekanalen zoals stable/beta;
- een verplichte of blokkerende updateflow;
- GitHub Releases als automatische fallback. Een tweede bron vraagt een afzonderlijk
  trust-, fout- en prioriteitsmodel en wordt pas toegevoegd als daar een concrete behoefte voor is.

---

## 2. Vastgestelde besluiten

| # | Onderwerp | Besluit | Motivatie |
|---|-----------|---------|-----------|
| 1 | Updatebron | Een afzonderlijke, compile-time configureerbare `UPDATE_BASE_URL`, in eerste instantie bediend door `tvguide-api` | De updatebron blijft los van de gekozen gidsmodus (`REMOTE` of `LOCAL`). Daardoor blijft dit plan bruikbaar als het standalone-plan wordt uitgevoerd. |
| 2 | Endpointversie | `/api/v1/app/version` en `/api/v1/app/download/:filename` | Dit volgt de bestaande `/api/v1/*`-structuur en maakt het contract expliciet versioneerbaar. |
| 3 | Versievergelijking | Alleen `remote.versionCode > BuildConfig.VERSION_CODE` betekent een update | `versionName` is uitsluitend presentatietekst en wordt niet als semver geïnterpreteerd. |
| 4 | Controlemomenten | Passief na het eerste hoofdschermframe, maximaal één geslaagde controle per 24 uur; handmatig altijd direct | De updatecheck vertraagt de koude start niet, is niet afhankelijk van geslaagde gidsdata en een handmatige actie wordt nooit gethrottled. |
| 5 | Download | Een aparte OkHttp-client zonder HTTP-cache, als coroutine op `Dispatchers.IO`, naar `cacheDir/updates/*.part` | De bestaande `ApiClient` heeft een 20 MB HTTP-cache en een read-timeout voor gidsverkeer. Een APK hoort niet óók in die cache en heeft eigen timeouts nodig. |
| 6 | Integriteit | SHA-256, verwachte grootte en werkelijk aantal gelezen bytes zijn verplicht | Dit detecteert corrupte, onvolledige en onverwacht grote downloads. Een checksum is geen bewijs van herkomst. |
| 7 | Authenticiteit | Androids APK-signaturecontrole is beslissend; vóór installatie controleert de app ook application-ID, `versionCode` en signing certificate | Een aanvaller op een HTTP-verbinding kan zowel APK als checksum vervangen. Alleen een APK die met de geldige app-key is ondertekend mag als update worden geaccepteerd. |
| 8 | Installatie | `PackageInstaller.Session` met een expliciete statuscallback | Dit is de platform-API voor installatie, geeft `PENDING_USER_ACTION`, succes en concrete fouten terug en heeft geen `FileProvider` nodig. |
| 9 | Onbekende bronnen | `REQUEST_INSTALL_PACKAGES` en vanaf API 26 een begeleide route via `canRequestPackageInstalls()` | Toestemming wordt pas gevraagd wanneer de gebruiker daadwerkelijk wil installeren. |
| 10 | Menu-integratie | Eén gedeeld hamburgermenu, samen met het zenderordeningsplan | De library-layout en focusroute mogen niet door twee plannen onafhankelijk worden aangepast. |
| 11 | Release signing | Alleen een ondertekende release-APK met het productiecertificaat mag worden gepubliceerd | De huidige Gradle-configuratie kan bij ontbrekende signinggegevens een niet-ondertekende release opleveren; de publicatiestap moet dat hard afwijzen. |

---

## 3. Beveiligings- en vertrouwensmodel

De controles hebben verschillende doelen en mogen niet door elkaar worden gehaald:

1. **Signing certificate — authenticiteit.** Android accepteert een update alleen als
   application-ID en signing identity compatibel zijn. De Package Installer blijft de
   definitieve controle uitvoeren.
2. **APK-preflight — vroege, begrijpelijke foutmelding.** De app leest vóór installatie het
   package-ID, de echte `versionCode` en signing-informatie uit de gedownloade APK. Deze moeten
   overeenkomen met de geïnstalleerde app en met de metadata.
3. **SHA-256 en bestandsgrootte — transferintegriteit.** Deze vinden beschadiging en een
   onvolledige download, maar authenticeren de uitgever niet wanneer metadata en APK over
   hetzelfde onbeveiligde kanaal komen.
4. **HTTPS — bescherming van metadata en privacy.** Een publiek of niet-vertrouwd netwerk
   vereist HTTPS. Tijdelijk LAN-HTTP kan alleen worden geaccepteerd zolang APK-signaturepreflight
   verplicht blijft; het blijft vatbaar voor sabotage en denial-of-service.
5. **Zelfde origin — begrenzing van de downloadbron.** `downloadPath` is relatief. Redirects en
   een uiteindelijke scheme/host/port buiten `UPDATE_BASE_URL` worden afgewezen.

De bestaande `network_security_config.xml` staat cleartext momenteel via `base-config` voor
alle hosts toe. Dat is breder dan nodig. Deze updater mag die uitzondering niet verder
uitbreiden. Voor internetdistributie is een HTTPS-updateorigin een releasevoorwaarde; het
beperken of verwijderen van de algemene cleartext-uitzondering wordt samen met de resterende
`REMOTE`-gidsmodus opgelost.

---

## 4. Architectuur

```text
  Release-publicatie
  assembleRelease -> signingcontrole -> APK-inspectie -> SHA-256 -> atomisch publiceren
                                                |
                                                v
  UPDATE_BASE_URL / tvguide-api
    GET /api/v1/app/version
    GET /api/v1/app/download/<versioned-name>.apk
                                                |
                                                v
  NexusTVGuide
    UpdateApiService -> UpdateRepository -> APK-preflight -> UpdateViewModel/UI
                                                |
                                                v
                                  PackageInstaller.Session
                                                |
                          STATUS_PENDING_USER_ACTION
                                                |
                                                v
                            Android-systeembevestiging
```

Verantwoordelijkheden:

- `UpdateApiService`: haalt en deserialiseert alleen metadata;
- `UpdateMetadataValidator`: valideert alle externe metadata en bouwt een toegestane URL;
- `UpdateRepository`: controleert versies, throttle en downloadt precies één bestand;
- `ApkVerifier`: controleert bestandsgrootte, SHA-256 en APK-identiteit;
- `ApkInstaller`: maakt en commit een `PackageInstaller.Session`;
- `UpdateInstallResultReceiver`: verwerkt installerstatussen en opent zo nodig de
  systeembevestiging;
- `UpdateViewModel`: bezit de schermtoestand en voorkomt dubbele checks/downloads;
- `UpdateDialogFragment`: rendert alleen toestand en D-pad-acties.

---

## 5. Backendcontract

### 5.1 Releasebestand

`tvguide-api/data/releases/version.json`:

```json
{
  "schemaVersion": 1,
  "applicationId": "com.nexustvguide.app",
  "versionCode": 2,
  "versionName": "0.6.0",
  "releaseNotes": "Nieuw: in-app zendervolgorde aanpassen\nVerbeterd: snellere EPG-cache",
  "downloadPath": "/api/v1/app/download/nexus-tv-guide-0.6.0.apk",
  "sha256": "9a4f2f9f5b66f6b0f4f33dc51d5cf834d68f7f95d1a39de6fcd09b3a51fbe123",
  "fileSizeBytes": 18452100,
  "publishedAt": "2026-08-31T12:00:00.000Z"
}
```

Contractregels:

- onbekende `schemaVersion` -> client meldt een niet-ondersteund updatecontract;
- `applicationId` moet exact `com.nexustvguide.app` zijn;
- `versionCode` is een positief geheel getal en moet binnen Androids toegestane bereik vallen;
- `versionName` is niet leeg en maximaal 64 tekens;
- `releaseNotes` is maximaal 8.000 tekens;
- `downloadPath` is een absoluut pad op dezelfde origin, geen volledige URL, bevat geen `..`
  en eindigt op `.apk`;
- `sha256` is verplicht en bestaat uit exact 64 lowercase hextekens;
- `fileSizeBytes` is verplicht, positief en maximaal 100 MiB;
- `publishedAt` is een geldige RFC 3339-instant;
- de echte APK-velden blijven leidend: metadata mag nooit een APK-identiteitscontrole vervangen.

`minSupportedVersionCode` is bewust verwijderd uit het eerste contract. Zonder ontworpen
blokkeer-UX en backendcompatibiliteitsbeleid zou het een ongebruikt of gevaarlijk veld zijn.

### 5.2 `GET /api/v1/app/version`

Responses:

- `200` met het gevalideerde releasebestand;
- `503 RELEASE_UNAVAILABLE` als manifest of APK ontbreekt, het manifest ongeldig is of de
  opgeslagen grootte/hash niet klopt;
- `Cache-Control: no-store` zodat een handmatige controle geen verouderde metadata krijgt;
- `Content-Type: application/json; charset=utf-8`.

De server valideert het manifest bij laden en controleert dat precies het genoemde APK-bestand
binnen de release-directory ligt. Een ongeldige release wordt niet gedeeltelijk aangeboden.

### 5.3 `GET` en `HEAD /api/v1/app/download/:filename`

Alleen het bestand uit het actieve, gevalideerde manifest is downloadbaar. Willekeurige
bestandsnamen en path traversal leveren `404` op.

Headers:

```text
Content-Type: application/vnd.android.package-archive
Content-Disposition: attachment; filename="nexus-tv-guide-0.6.0.apk"
Content-Length: 18452100
Cache-Control: public, max-age=31536000, immutable
X-Content-Type-Options: nosniff
```

Versiebestandsnamen zijn immutable. Een alias `latest.apk` is niet nodig en voorkomt juist dat
een cache de verkeerde build bij nieuwe metadata serveert.

### 5.4 Atomaire publicatie

Publiceer nooit handmatig eerst `version.json` en daarna de APK. De releasetaak voert in deze
volgorde uit:

1. bouw `assembleRelease` met de productiekey;
2. laat `apksigner verify --verbose --print-certs` slagen;
3. controleer application-ID, `versionCode` en `versionName` uit de echte APK;
4. controleer dat `versionCode` hoger is dan de actieve release;
5. bereken SHA-256 en bestandsgrootte;
6. kopieer de APK onder zijn definitieve, versiegebonden naam;
7. schrijf `version.json.tmp`, `fsync` waar praktisch en hernoem die als laatste atomair naar
   `version.json`;
8. voer na publicatie een HTTP-smoketest uit en vergelijk de gedownloade SHA-256 opnieuw.

De publicatiestap faalt hard als `keystore.properties`, de keystore of een vereiste property
ontbreekt. De bestaande algemene `assembleRelease`-fallback mag blijven voor lokale inspectie,
maar mag nooit de distributietaak passeren. APK's, manifesten en signingmateriaal worden niet in
Git opgenomen; `data/releases/` wordt expliciet genegeerd, eventueel met alleen een `.gitkeep`.

---

## 6. Android-contract en toestanden

### 6.1 DTO

```kotlin
data class AppUpdateDto(
    @SerializedName("schemaVersion") val schemaVersion: Int,
    @SerializedName("applicationId") val applicationId: String,
    @SerializedName("versionCode") val versionCode: Long,
    @SerializedName("versionName") val versionName: String,
    @SerializedName("releaseNotes") val releaseNotes: String?,
    @SerializedName("downloadPath") val downloadPath: String,
    @SerializedName("sha256") val sha256: String,
    @SerializedName("fileSizeBytes") val fileSizeBytes: Long,
    @SerializedName("publishedAt") val publishedAt: String
)
```

De client valideert de DTO na Gson-deserialisatie voordat een versie wordt vergeleken of URL
wordt opgebouwd. Retrofit/Gson-typechecks alleen zijn niet voldoende voor grenzen, URL-beleid en
inhoudsregels.

### 6.2 Resultaat van een controle

```kotlin
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val update: AppUpdateDto) : UpdateCheckResult
    data class Failed(val reason: UpdateError) : UpdateCheckResult
}
```

Een passieve `Failed` wordt alleen gelogd en zet geen zichtbare fout over de gids. Een handmatige
`Failed` krijgt wel een foutmelding met opnieuw-proberenactie.

### 6.3 Download- en installatietoestand

```kotlin
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val update: AppUpdateDto) : UpdateUiState
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int
    ) : UpdateUiState
    data object Verifying : UpdateUiState
    data object AwaitingInstallPermission : UpdateUiState
    data object AwaitingSystemConfirmation : UpdateUiState
    data class Failed(val error: UpdateError) : UpdateUiState
}
```

`UpdateError` bevat interne foutcodes, bijvoorbeeld `NETWORK`, `INVALID_METADATA`,
`UNEXPECTED_REDIRECT`, `INSUFFICIENT_STORAGE`, `SIZE_MISMATCH`, `CHECKSUM_MISMATCH`,
`PACKAGE_MISMATCH`, `VERSION_MISMATCH`, `SIGNING_MISMATCH`, `INSTALL_BLOCKED` en
`INSTALL_CANCELLED`. De UI toont lokale, begrijpelijke teksten en nooit een ruwe exception of URL
met mogelijk gevoelige details.

---

## 7. Controle-, throttle- en uitstelgedrag

### Passieve controle

- start pas na het eerste gerenderde frame van `MainActivity`, zonder op succesvolle gidsdata te
  wachten;
- draait alleen als de laatste **geslaagde** automatische of handmatige metadataresponse minstens
  24 uur oud is;
- schrijft de succes-timestamp ook bij `UpToDate`, maar niet bij netwerk- of parsefouten;
- toont alleen UI als een update beschikbaar is;
- gebruikt een mutex/single-flight zodat een handmatige en passieve check niet dubbel lopen.

Om een foutlus bij elke activity-resume te voorkomen, bewaart de app naast de succes-timestamp
een in-memory retrygrens van één uur na een passieve fout. Een app-herstart mag opnieuw proberen.

### Handmatige controle

- negeert de 24-uursgrens;
- toont `Checking`, `UpToDate`, `Available` of een concrete fout;
- hergebruikt een lopende controle in plaats van een tweede request te starten.

### Later

`Later` sluit alleen de dialoog. Voor dezelfde `versionCode` verschijnt de passieve prompt niet
opnieuw binnen 24 uur. Handmatig controleren blijft altijd mogelijk. Er is in deze versie geen
permanente negeeractie en geen geforceerde update.

Alle tijdslogica gebruikt een injecteerbare `Clock`, zodat de 24-uurs- en retrygrenzen zonder
wachten getest kunnen worden.

---

## 8. Download en verificatie

### Netwerkclient

Refactor `ApiClient` zodat gemeenschappelijke logging en timeouts via een builderfunctie kunnen
worden hergebruikt, maar maak voor updates een afzonderlijke client:

- geen OkHttp-diskcache;
- maximaal één actieve APK-download;
- connect-timeout 10 seconden;
- read-timeout 60 seconden;
- redirects uit, omdat het contract een directe same-origin route voorschrijft;
- responsebody altijd sluiten via `use`;
- logging op release uit en in debug niet op bodyniveau.

De `UPDATE_BASE_URL` staat los van de door de gebruiker instelbare gids-base-URL. Metadata kan dus
niet bepalen vanaf welke willekeurige host code wordt gedownload.

### Downloadalgoritme

1. Valideer metadata en beschikbare ruimte vóór het request. Reserveer conservatief
   `2 * fileSizeBytes + 25 MiB`, omdat de Package Installer de APK nogmaals staged.
2. Verwijder alleen oude bestanden binnen `cacheDir/updates/` die aan de eigen naamconventie
   voldoen; raak geen andere cachemappen aan.
3. Schrijf naar `update_<versionCode>.apk.part`.
4. Weiger een response die niet `2xx` is, een verkeerde content-type heeft of waarvan een bekende
   `Content-Length` afwijkt.
5. Stop hard zodra meer dan `fileSizeBytes` of 100 MiB is gelezen.
6. Emit hoogstens ongeveer tien voortgangsupdates per seconde om de TV-UI niet te belasten.
7. Flush en sluit het bestand; controleer het werkelijke aantal bytes en SHA-256.
8. Hernoem binnen dezelfde directory atomair naar `update_<versionCode>.apk`.
9. Voer APK-preflight uit.
10. Bij fout of coroutine-cancellation: sluit streams, verwijder `.part` en emit maximaal één
    eindtoestand.

### APK-preflight

Lees de APK met `PackageManager.getPackageArchiveInfo` en signing flags die bij de Android-versie
passen. Controleer:

- het archief is parseerbaar;
- `packageName == context.packageName`;
- echte APK-`versionCode == metadata.versionCode`;
- echte APK-`versionCode > BuildConfig.VERSION_CODE`;
- het signing certificate is compatibel met de geïnstalleerde app; houd vanaf API 28 rekening met
  signing history/key rotation, en gebruik op oudere versies de legacy signatures;
- de minimale SDK uit het archief is, waar de platformversie die informatie beschikbaar maakt,
  niet hoger dan het apparaat. ABI- en overige compatibiliteitscontroles blijven bij de
  systeeminstaller.

Deze preflight levert betere fouten, maar vervangt de cryptografische verificatie van de Package
Installer niet.

---

## 9. Installatie met `PackageInstaller.Session`

### Manifest

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<application ...>
    <receiver
        android:name=".update.UpdateInstallResultReceiver"
        android:exported="false" />
</application>
```

Er is geen `FileProvider` en geen `res/xml/file_paths.xml` nodig.

### Installatieflow

1. Controleer op API 26+ `packageManager.canRequestPackageInstalls()`.
2. Is toestemming uitgeschakeld, toon eerst uitleg en open daarna met een expliciete
   gebruikersactie `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` voor het eigen package.
3. Bewaar de gevalideerde APK en doelversie terwijl de instellingen open zijn. Bij terugkeer in
   `onResume` opnieuw controleren; niet automatisch redownloaden.
4. Maak een `PackageInstaller.SessionParams(MODE_FULL_INSTALL)` met de bekende bestandsgrootte.
   Zet op API 31+ expliciet `USER_ACTION_REQUIRED`, zodat dit plan nooit op een stille installatie
   vertrouwt.
5. Kopieer de APK via `session.openWrite`, roep `session.fsync` aan en sluit de stream.
6. Commit met een expliciete, mutable `PendingIntent` naar
   `UpdateInstallResultReceiver`; gebruik een unieke requestcode per session.
7. Bewaar `sessionId` en doelversie vóór `commit`, zodat een process death geen onbekende toestand
   oplevert.
8. Bewaar de gevalideerde APK tot een terminale installerstatus. Na succes wordt hij verwijderd;
   na annuleren blijft hij korte tijd beschikbaar voor opnieuw proberen zonder redownload.

De receiver verwerkt minimaal:

- `STATUS_PENDING_USER_ACTION`: haal de meegeleverde bevestigings-intent op en open die met
  `FLAG_ACTIVITY_NEW_TASK`;
- `STATUS_SUCCESS`: ruim pending state en achtergebleven bestanden op;
- `STATUS_FAILURE_ABORTED`: classificeer als geannuleerd;
- `STATUS_FAILURE_BLOCKED`: toon dat installatie door apparaatbeleid of toestemming is geblokkeerd;
- `STATUS_FAILURE_INVALID`: meld ongeldige APK/signature/version;
- `STATUS_FAILURE_STORAGE`: meld onvoldoende ruimte;
- overige fouten: generieke installatiefout met alleen de veilige statuscode in logs.

Op API 21-25 bestaat `canRequestPackageInstalls()` nog niet en gebruikt Android het oudere,
apparaatbrede beleid voor onbekende bronnen. Een geblokkeerde installatie krijgt daar een uitleg
die naar de toepasselijke beveiligingsinstellingen verwijst; als die settingsintent op de TV-ROM
niet bestaat, blijft een handmatige instructie zichtbaar.

Een app-update kan het eigen proces beëindigen. Bij iedere volgende start controleert de app daarom
een bewaarde `pendingTargetVersionCode`:

- `BuildConfig.VERSION_CODE >= pendingTargetVersionCode` -> installatie geslaagd, pending state
  opruimen;
- oudere versie -> sessiestatus inspecteren of opnieuw een begrijpelijke actie aanbieden.

Verouderde, door NexusTVGuide aangemaakte en nog niet gecommitte installatiesessies worden na een
veilige tijdslimiet geabandonneerd. Nooit sessies van andere installers beheren.

---

## 10. TV-interface en navigatie

`UpdateDialogFragment` is een custom AndroidX `DialogFragment` in de bestaande donkere stijl; er
wordt geen touch-interactie verondersteld.

### Toestanden

1. **Update beschikbaar**
   - titel: `Update beschikbaar — versie 0.6.0`;
   - huidige en nieuwe versie;
   - scrollbare release notes met duidelijke focus/scroll-indicator;
   - knoppen: `Downloaden en installeren`, `Later`;
   - primaire knop heeft initiële focus.
2. **Downloaden**
   - bytes/MB en percentage;
   - determinate progressbar;
   - knop `Annuleren`, die de coroutine echt annuleert en `.part` verwijdert.
3. **Verifiëren**
   - indeterminate voortgang;
   - geen tweede installatieactie mogelijk.
4. **Installatietoestemming nodig**
   - korte uitleg waarom Android deze toestemming vraagt;
   - knoppen `Instellingen openen`, `Annuleren`.
5. **Wachten op systeembevestiging**
   - appdialoog verdwijnt voordat de Android-systeem-UI opent;
   - na annuleren kan vanuit de app opnieuw worden geprobeerd zonder redownload zolang het
     gevalideerde bestand of de sessie nog bestaat.
6. **Fout**
   - concrete lokale tekst;
   - knoppen `Opnieuw proberen`, `Sluiten`;
   - checksum-, package- en signingfouten mogen niet als gewone netwerkfout worden gepresenteerd.

### Focusregels

- alles werkt met D-pad, OK en Terug;
- focus verdwijnt nooit in de scrollbare release notes;
- Terug tijdens downloaden vraagt om annulering of annuleert voorspelbaar;
- na sluiten keert focus terug naar de hamburgerknop;
- font scaling en lange release notes mogen knoppen niet buiten beeld duwen;
- statuswijzigingen krijgen passende TalkBack-announcements zonder iedere procentwijziging voor te
  lezen.

### Gedeeld hamburgermenu

Het zenderordeningsplan voegt de menuknop en librarycallback toe. Implementeer die infrastructuur
precies één keer. Het appmenu bevat daarna:

| Regel | Actie |
|---|---|
| Zenders ordenen | Opent `ChannelOrderFragment` zodra die functie bestaat |
| Zoeken naar updates | Start altijd een handmatige updatecontrole |
| Over NexusTVGuide | Toont minimaal huidige `versionName` en `versionCode` (kleine, aanbevolen aanvulling) |

Als de updater vóór zenderordening wordt gebouwd, neemt deze fase de gedeelde menu-infrastructuur
uit dat plan over en voegt voorlopig alleen `Zoeken naar updates` en `Over NexusTVGuide` toe.

---

## 11. Implementatiefasen

### Fase 0 — releasevoorwaarden en contract

Bestanden:

- `android/app/build.gradle`;
- `.gitignore`;
- nieuw release-/publicatiescript onder `tools/`;
- `tvguide-api/data/releases/version.json` alleen op de runtimehost, niet in Git.

Werk:

- maak `UPDATE_BASE_URL` expliciet en onafhankelijk van `DEFAULT_BASE_URL`;
- leg de monotone `versionCode`-procedure vast;
- voeg een publicatietaak toe die unsigned of verkeerd ondertekende builds afwijst;
- negeer lokale release-artifacts in Git;
- documenteer waar de productiekey veilig wordt geback-upt. Nooit keymateriaal of wachtwoorden
  loggen of committen.

Acceptatiecriteria:

- een ontbrekende keystore laat de distributietaak falen vóór publicatie;
- een verkeerd application-ID, niet-hogere `versionCode` of afwijkend signing certificate faalt;
- een geldig artifact levert automatisch correcte metadata op.

### Fase 1 — backendroutes en atomaire releaseopslag

Bestanden:

- `tvguide-api/src/output/app-update.ts` (nieuw);
- `tvguide-api/src/app.ts` (routes registreren en release-directory injecteerbaar maken);
- `tvguide-api/src/server.ts` (optionele `APP_RELEASES_DIR` doorgeven);
- `tvguide-api/test/contract/app-update.test.ts` (nieuw).

Werk:

- valideer het manifest met Zod;
- resolve paden uitsluitend binnen `APP_RELEASES_DIR`;
- implementeer GET/HEAD en de vastgelegde headers;
- valideer bij iedere manifestwissel dat APK, grootte en hash overeenkomen;
- log releasefouten zonder lokale absolute paden of signinginformatie naar clients te lekken.

Acceptatiecriteria:

- geldig manifest -> metadata en exact één downloadbaar artifact;
- ontbrekend/corrupt manifest of artifact -> `503 RELEASE_UNAVAILABLE` zonder servercrash;
- path traversal en niet-actieve bestandsnamen -> `404`;
- contracttest vergelijkt downloadbytes en SHA-256 met metadata;
- `npm test` en `npm run build` slagen.

### Fase 2 — Android metadata, throttle en downloader

Bestanden:

- `android/app/src/main/java/com/nexustvguide/app/data/model/AppUpdateDto.kt`;
- `android/app/src/main/java/com/nexustvguide/app/data/api/UpdateApiService.kt`;
- `android/app/src/main/java/com/nexustvguide/app/data/api/UpdateHttpClient.kt`;
- `android/app/src/main/java/com/nexustvguide/app/data/repository/UpdateRepository.kt`;
- `android/app/src/main/java/com/nexustvguide/app/update/UpdateMetadataValidator.kt`;
- `android/app/src/main/java/com/nexustvguide/app/update/Sha256Checksum.kt`.

Werk:

- implementeer DTO-validatie en same-origin URL-opbouw;
- maak versiecheck en tijdsgrenzen testbaar met een `Clock`;
- implementeer single-flight download, voortgang, cancellation en begrensde bestandsgrootte;
- ruim alleen eigen oude updatebestanden op.

Acceptatiecriteria:

- `remote.versionCode <= current` is altijd `UpToDate`;
- passieve fout stempelt de 24-uurs-succestijd niet af;
- handmatige controle wordt nooit door throttle geblokkeerd;
- onverwachte redirect, oversize response, size mismatch en checksum mismatch verwijderen `.part`;
- één gewijzigde byte veroorzaakt `CHECKSUM_MISMATCH`;
- cancellation laat geen actieve call of tijdelijk bestand achter.

### Fase 3 — APK-preflight en Package Installer

Bestanden:

- `android/app/src/main/java/com/nexustvguide/app/update/ApkVerifier.kt`;
- `android/app/src/main/java/com/nexustvguide/app/update/ApkInstaller.kt`;
- `android/app/src/main/java/com/nexustvguide/app/update/UpdateInstallResultReceiver.kt`;
- `android/app/src/main/AndroidManifest.xml`.

Werk:

- implementeer package-, versie- en certificatepreflight voor API 21 t/m target;
- implementeer toestemmingsflow en terugkeer uit Settings;
- stage en commit een `PackageInstaller.Session`;
- persisteer session-ID/doelversie en verwerk alle relevante statussen;
- herstel pending state na process death of succesvolle zelfvervanging.

Acceptatiecriteria:

- APK met debug application-ID, lagere/gelijke versie of andere key bereikt de systeeminstaller
  niet;
- geldige release-APK opent de officiële bevestiging;
- weigeren, annuleren, onvoldoende opslag en geblokkeerde onbekende bronnen geven aparte uitkomsten;
- geïnstalleerde versie ruimt pending state en eigen artifacts op.

### Fase 4 — UI en gedeeld menu

Bestanden:

- `android/app/src/main/java/com/nexustvguide/app/ui/update/UpdateViewModel.kt`;
- `android/app/src/main/java/com/nexustvguide/app/ui/update/UpdateDialogFragment.kt`;
- `android/app/src/main/res/layout/dialog_app_update.xml`;
- `android/app/src/main/res/menu/guide_overflow.xml`;
- `android/app/src/main/res/values/strings.xml`;
- `android/app/src/main/java/com/nexustvguide/app/ui/NexusProgramGuideFragment.kt`;
- indien nog niet uitgevoerd: de gedeelde librarybestanden uit stap 4 van het
  zenderordeningsplan (`programguide_fragment.xml`, menu-icoon en `ProgramGuideFragment.kt`).

Werk:

- bind de toestanden aan een lifecycle-aware ViewModel;
- bouw alle fout- en focusstates;
- voeg de menuactie en focusherstel toe;
- zorg dat een opnieuw aangemaakt fragment geen tweede download of installatiesessie start.

Acceptatiecriteria:

- volledige appflow is met alleen de afstandsbediening bedienbaar;
- menu opent met een geldige focus en Terug herstelt focus naar de knop;
- rotatie/process recreation waar relevant dupliceert geen side effects;
- lange release notes en Nederlandse foutteksten blijven leesbaar op 720p en 1080p.

### Fase 5 — passieve controle en integratie

Bestanden:

- `android/app/src/main/java/com/nexustvguide/app/ui/MainActivity.kt`;
- bestaande updatecomponenten uit fase 2-4.

Werk:

- start de passieve controle na het eerste hoofdschermframe, onafhankelijk van succes of falen van
  het laden van gidsdata;
- voeg 24-uurs-succesthrottle, passieve foutbackoff en uitstel per versie toe;
- zorg dat updatefouten nooit gidsdata of NLZIET-navigatie blokkeren.

Acceptatiecriteria:

- app-start blijft bruikbaar bij een onbereikbare updatebron;
- maximaal één passieve check per 24 uur na succes;
- handmatige check blijft altijd beschikbaar;
- `Later` veroorzaakt geen promptlus bij iedere resume.

---

## 12. Test- en verificatieprotocol

### Geautomatiseerde backendtests

- geldig en ongeldig manifestschema;
- ontbrekende APK, size mismatch en hash mismatch;
- GET- en HEAD-headers;
- alleen het actieve artifact is bereikbaar;
- URL-encoded path traversal wordt afgewezen;
- downloadhash is gelijk aan metadatahash.

### Android JVM-/Robolectric-tests

- DTO-grenzen en onbekende `schemaVersion`;
- versievergelijking met hoger, gelijk en lager `versionCode`;
- throttle met injecteerbare klok;
- same-origin padvalidatie en redirectweigering;
- voortgang bij bekende grootte;
- cancellation en cleanup;
- SHA-256 met bekende testvector en gemuteerde byte;
- UI-state reducer: geen dubbele terminale toestand of dubbele download.

Niet alle package-signature- en PackageInstaller-details zijn betrouwbaar in een gewone JVM-test
te bewijzen. Die krijgen instrumentatie- en apparaattests.

### Instrumentatie/emulator

- APK-preflight met fixtures voor verkeerd package, verkeerde versie en verkeerde signer;
- receiverstatussen met expliciete intents waar mogelijk;
- process recreation tijdens metadata- en downloadtoestand;
- D-pad-focusroute van menu, dialoog, release notes en foutknoppen.

### Releasegate op NVIDIA Shield

Gebruik voor de echte updatetest geen standaard debugbuild: die heeft
`applicationId = com.nexustvguide.app.debug` en een debugcertificaat en kan de productie-app dus
niet vervangen.

1. Installeer een met de productiekey ondertekende release met een lagere `versionCode`.
2. Sla herkenbare instellingen op en, zodra beschikbaar, een afwijkende zendervolgorde.
3. Publiceer een hogere, met dezelfde key ondertekende release via het atomaire proces.
4. Controleer handmatig; verifieer versie, notes en downloadgrootte.
5. Annuleer één download en controleer dat `.part` en netwerkcall verdwijnen.
6. Download opnieuw, open onbekende-bronneninstellingen indien nodig en keer terug.
7. Annuleer één keer in de Android-systeembevestiging; probeer daarna opnieuw zonder onnodige
   redownload.
8. Bevestig installatie en start NexusTVGuide opnieuw.
9. Controleer de nieuwe `versionCode`, behouden instellingen/zendervolgorde en opgeruimde pending
   state.
10. Herhaal negatieve tests met een APK met verkeerd package, gelijke versie, corrupte byte en
    andere signing key; geen daarvan mag de systeembevestiging bereiken.
11. Test zowel het tijdelijke LAN-HTTP-pad als de uiteindelijke HTTPS-origin als beide worden
    ondersteund.

---

## 13. Risico's en mitigaties

| Risico | Kans | Impact | Mitigatie |
|---|---|---|---|
| Signing key raakt kwijt | Laag | Zeer hoog | Versleutelde back-up en hersteltest; publicatie alleen via gecontroleerde key. Zonder key is geen in-place update meer mogelijk. |
| Verkeerde/unsigned APK wordt gepubliceerd | Middel | Hoog | Distributietaak controleert signer, application-ID en oplopende echte `versionCode` vóór publicatie. |
| LAN-HTTP wordt gemanipuleerd | Laag op vertrouwd LAN | Hoog | APK-signaturepreflight plus systeemcontrole; HTTPS verplicht buiten het vertrouwde LAN. Checksum alleen als integriteitscontrole benoemen. |
| Manifest wijst al naar een nog niet beschikbare APK | Middel zonder tooling | Middel | APK eerst publiceren, manifest als laatste atomair vervangen, post-publish smoketest. |
| Opslag raakt vol door download plus staging | Laag | Middel | Vooraf conservatieve ruimtecheck, harde 100 MiB-grens en cleanup van eigen artifacts/sessies. |
| Proces wordt vervangen of gedood tijdens installatie | Normaal gedrag | Middel | Session-ID en doelversie vóór commit bewaren; bij volgende start reconciliëren. |
| Onbekende-bronneninstelling verschilt per TV-ROM | Middel | Middel | `canRequestPackageInstalls`, expliciete settingsintent, `ActivityNotFoundException` afvangen en Shield plus emulator handmatig testen. |
| Updateprompt blijft terugkomen | Middel | Laag | Geslaagde 24-uurs-throttle en uitstel per `versionCode`; handmatige check blijft onafhankelijk. |
| Menuplannen wijzigen dezelfde library-layout | Hoog als los uitgevoerd | Middel | Menu-infrastructuur uit het zenderordeningsplan één keer implementeren en vanuit de app uitbreiden. |
| Cache-APK verdwijnt | Laag | Laag | Cache is tijdelijk; ontbrekend bestand leidt tot gecontroleerd opnieuw downloaden, nooit tot verlies van gebruikersdata. |
| Updateorigin verhuist | Laag | Hoog | Gebruik voor releases een stabiele HTTPS-hostnaam. Een oude app kent alleen zijn ingebouwde origin; verhuizing vereist tijdelijk doorserveren of eenmalig handmatig sideloaden. |

---

## 14. Definition of Done

- [x] Release-publicatie weigert unsigned, verkeerd ondertekende of niet-oplopende APK's.
- [x] Publicatie is atomair en het backendcontract heeft contracttests voor succes en falen.
- [x] Updateorigin staat los van de gidsbron en downloadt niet vanaf metadata-gestuurde vreemde hosts.
- [x] Passieve en handmatige controle volgen de vastgelegde throttle- en foutregels.
- [x] Downloads zijn begrensd, annuleerbaar en laten geen tijdelijke bestanden achter.
- [x] Grootte, SHA-256, package-ID, echte versie en signing identity worden vóór staging gecontroleerd.
- [x] Installatie gebruikt `PackageInstaller.Session` en verwerkt pending user action en fouten.
- [x] De volledige eigen UI is bedienbaar met D-pad, OK en Terug.
- [x] Een gesigneerde release-to-release-update is op de NVIDIA Shield geslaagd met behoud van instellingen.
- [x] Negatieve APK-tests bereiken de Android-systeembevestiging niet.
- [x] Backendtests, Android-unittests, lint en builds zijn groen.
- [x] Het gedeelde hamburgermenu is niet dubbel of conflicterend geïmplementeerd.
- [x] [`plan-2026-08-30-bouwplan.md`](plan-2026-08-30-bouwplan.md) en README verwijzen na implementatie naar deze functie en de releaseprocedure.

---

## 15. Primaire Android-referenties

- [How app updates work](https://developer.android.com/google/play/app-updates) — voorwaarden voor application-ID, version code en signing identity.
- [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller) en
  [`PackageInstaller.Session`](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session) — staging, commit en statuscallback.
- [`PackageManager.canRequestPackageInstalls`](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()) — toestemming voor installatie uit deze bron.
- [Network security configuration](https://developer.android.com/privacy-and-security/security-config) — HTTPS en begrensde cleartext-uitzonderingen.
- [Sign your app](https://developer.android.com/studio/publish/app-signing) — signing key als basis voor veilige in-place updates.
