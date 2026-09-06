# NexusTVGuide

NexusTVGuide is een reclamevrije tv-gids voor Android TV. De app is gemaakt voor bediening
met de afstandsbediening en werkt samen met de NLZIET-app: NexusTVGuide helpt je kiezen wat
je wilt kijken, NLZIET verzorgt het afspelen.

> NexusTVGuide is geen videospeler en levert zelf geen televisiekanalen, abonnement of
> kijkrechten.

## Wat doet de app?

- Toont een overzichtelijke elektronische programmagids (EPG) van Nederlandse tv-zenders.
- Laat programma-informatie, uitzendtijden, afbeeldingen en labels zoals live, première,
  herhaling, genre en leeftijdsindicatie zien.
- Werkt volledig met de D-pad: door zenders en tijd navigeren, een dag kiezen en direct naar
  de huidige uitzending springen. De gids toont gisteren, vandaag en de volgende zeven dagen.
- Haalt gidsinformatie rechtstreeks van de bron op, slaat die lokaal op en ververst die op de
  achtergrond. Bij een tijdelijke verbindingsstoring blijft de laatst beschikbare gids zichtbaar.
- Laat je de volgorde van zenders aanpassen en zenders verbergen.
- Opent bij een geselecteerd programma NLZIET. Wanneer een betrouwbare koppeling beschikbaar
  is, wordt de juiste uitzending direct in NLZIET geopend; anders opent de NLZIET-tv-app zodat
  je daar verder kunt kijken.

## Wat heb je nodig?

- Een Android TV-apparaat met Android 5.0 (API 21) of nieuwer. De app is gericht op onder
  andere NVIDIA Shield TV.
- Een internetverbinding voor gidsdata en afbeeldingen.
- De officiële NLZIET Android TV-app en een geldig NLZIET-abonnement om programma's te kunnen
  bekijken.

De gidsdata worden opgehaald bij externe bronnen. Beschikbaarheid en inhoud daarvan kunnen
veranderen. NexusTVGuide is een onafhankelijk project en is niet verbonden aan NLZIET.

## Installeren

Zodra er een release beschikbaar is, download je de APK via
[Releases](https://github.com/agilize-nl/nexustvguide/releases) en installeer je die op je
Android TV. Android vraagt mogelijk toestemming om apps vanuit deze bron te installeren.

Binnen de app kun je via het menu handmatig op updates controleren. Een update wordt vóór de
installatie gecontroleerd op versie, bestandsgrootte, SHA-256 en de verwachte app-ondertekening.
De Android-systeemdialoog vraagt altijd om jouw bevestiging voor de installatie.

## Voor ontwikkelaars

De Android-app staat in [`android/`](android/) en is de standaardimplementatie. Zij haalt de
gids rechtstreeks op en bewaart dagsnapshots in een lokale Room-database. De optionele
TypeScript-service in [`tvguide-api/`](tvguide-api/) kan dezelfde gidsdata via REST en XMLTV
beschikbaar maken voor andere clients.

Een debug-build maken:

```bash
cd android
./gradlew :app:assembleDebug
```

De APK staat daarna onder `android/app/build/outputs/apk/debug/`. Installeer hem bijvoorbeeld
met `adb install -r <apk-bestand>`.

Voor details over de technische opzet, testafspraken en distributie staan de actuele documenten
in [`docs/`](docs/).
