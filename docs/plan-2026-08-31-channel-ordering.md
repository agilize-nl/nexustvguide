# Plan: eigen zendervolgorde in NexusTVGuide

Werkdocument voor de functie "gebruiker bepaalt zelf de verticale volgorde van de zenders in
het EPG-grid". Sluit aan op [`PLAN.md`](PLAN.md) (fase 2, Android) en gebruikt dezelfde
conventies: expliciete besluiten, concrete stappen, acceptatiecriteria per stap.

Geschreven op basis van de code zoals die er op **2026-08-31** uitziet.

## Doel

De gebruiker kan op de Shield, met alleen de afstandsbediening, de verticale volgorde van de
zenderrijen aanpassen en die volgorde blijft bewaard over app-herstarts en over dagwissels
heen. Daarnaast kan de gebruiker zenders verbergen die hij nooit kijkt, en kan hij in één
handeling terug naar de standaardvolgorde van de backend.

Buiten scope voor deze functie:

- Meerdere profielen of meerdere opgeslagen volgordes.
- Synchronisatie van de volgorde naar de backend of naar andere apparaten.
- Wijzigen van `sortOrder` in `tvguide-api/config/channels.json` vanuit de app.
- Groepen/mappen van zenders (favorietenlijsten, thema's).

## Vastgestelde besluiten

| # | Onderwerp | Besluit | Motivatie |
|---|-----------|---------|-----------|
| 1 | Opslaglocatie | Lokaal op het toestel, `SharedPreferences` | De app heeft nog geen Room/DataStore; `SharedPreferences` wordt al gebruikt in `ApiClient` en `GuideRepository`. Eén zenderlijst is klein genoeg. |
| 2 | Opslagvorm | JSON-string: lijst van channel-id's + lijst van verborgen id's + schemaversie | Gson zit al in de app; makkelijk te migreren en te loggen. |
| 3 | Bron van waarheid | Backend levert de *standaard*volgorde (`sortOrder`); de lokale voorkeur is een *overlay* daarop | Nieuwe zenders van de backend verdwijnen zo nooit; ze schuiven in op hun backend-positie. |
| 4 | Bewerkmodus | Aparte, expliciete "Zenders ordenen"-modus in een eigen scherm | In het grid zelf slepen met D-pad botst met de bestaande horizontale/verticale focuslogica van `ProgramGuideGridView`. |
| 5 | Bediening | D-pad: op/neer = selectie verplaatsen, OK = zender "oppakken"/"neerzetten", op/neer terwijl opgepakt = verplaatsen | Standaardpatroon op Android TV; werkt met één knop en is voorspelbaar. |
| 6 | Ingang | Hamburgermenuknop in de kopbalk, direct rechts van "Nu live"; opent een menu waarin "Zenders ordenen" voorlopig de enige optie is | De kopbalk is de enige plek die al focusbaar is boven het grid. Een menu in plaats van een directe knop houdt ruimte vrij voor latere opties zonder dat de kopbalk telkens verandert. |
| 7 | Verbergen | Onderdeel van hetzelfde scherm, via de rechter-D-pad of een aparte toets | Ordenen en verbergen zijn in de praktijk dezelfde handeling ("deze wil ik bovenaan, die hoef ik niet"). |
| 8 | Toepassing | In `GuideViewModel`, na het ophalen, vóór het mappen naar `ProgramGuideChannel` | Eén plek; het `ProgramGuideFragment`/`ProgramGuideManager` blijft onaangeroerd. |

## Huidige situatie in de code

De volgorde ligt nu volledig bij de backend en wordt ongewijzigd doorgegeven:

1. `tvguide-api` leest [`config/channels.json`](../tvguide-api/config/channels.json), filtert op
   `inNlziet` en sorteert op `sortOrder` — zie [`src/store/refresh.ts:71`](../tvguide-api/src/store/refresh.ts#L71).
2. De snapshot bevat `channels` in die volgorde; de app haalt hem op in
   [`GuideRepository.getGuideForDate()`](../android/app/src/main/java/com/nexustvguide/app/data/repository/GuideRepository.kt).
3. [`GuideViewModel.loadGuideForDate()`](../android/app/src/main/java/com/nexustvguide/app/ui/GuideViewModel.kt)
   mapt `guideResponse.channels` één-op-één naar `SimpleChannel` en zet die in
   `GuideUiState.Content.channels`.
4. [`NexusProgramGuideFragment`](../android/app/src/main/java/com/nexustvguide/app/ui/NexusProgramGuideFragment.kt)
   geeft die lijst door aan `ProgramGuideFragment.setData(...)`.
5. `ProgramGuideManager.setData(...)` neemt de lijstvolgorde letterlijk over in `channels`, en
   `ProgramGuideRowAdapter.update()` maakt per index een rij aan.

Dat betekent: **de volgorde van de `List<ProgramGuideChannel>` die de app aan `setData` geeft,
is exact de verticale volgorde van het grid.** Er is geen sortering of stabiele-id-logica in de
library die daar tussen zit. Eén ingreep in de ViewModel-laag volstaat dus; er hoeft niets aan
de egeniq-fork te veranderen voor de volgorde zelf.

Wat er nog niet is:

- Geen persistente gebruikersvoorkeuren buiten de base-URL in `ApiClient` en de ETags in `GuideRepository`.
- Geen tweede scherm/fragment; `MainActivity` doet één `replace()` met het gidsfragment.
- `ChannelDto` bevat wél al `sortOrder`, maar de app gebruikt dat veld nu niet.

## Ontwerp

### Datamodel

Nieuw bestand `data/model/ChannelOrderPreferences.kt`:

```kotlin
data class ChannelOrderPreferences(
    val version: Int = CURRENT_VERSION,
    /** Channel-id's in door de gebruiker gekozen volgorde. Mag id's bevatten die de
     *  backend niet meer levert; die worden bij het toepassen genegeerd. */
    val orderedIds: List<String> = emptyList(),
    /** Channel-id's die de gebruiker heeft verborgen. */
    val hiddenIds: Set<String> = emptySet()
) {
    companion object { const val CURRENT_VERSION = 1 }
}
```

Bewust géén positie-integers per zender: een lijst van id's is idempotent, makkelijk te
herschikken en kent geen gaten of dubbele posities.

### Samenvoegregel (de kern van de functie)

`ChannelOrderResolver.apply(backendChannels, prefs): List<ChannelDto>`

1. Neem `prefs.orderedIds` in volgorde; behoud alleen id's die in `backendChannels` voorkomen.
2. Bepaal de "nieuwe" zenders: alles in `backendChannels` dat niet in `prefs.orderedIds` staat.
3. Voeg elke nieuwe zender in op de positie die volgt uit zijn `sortOrder` ten opzichte van
   zijn backend-buren: zoek de dichtstbijzijnde voorganger (hoogste `sortOrder` lager dan de
   nieuwe) die al in de resultaatlijst staat, en plaats de nieuwe zender daar direct achter.
   Is er geen voorganger, dan vooraan.
4. Filter `prefs.hiddenIds` eruit.
5. Bij een lege of ontbrekende voorkeur is het resultaat exact de backendvolgorde.

Regel 3 is de reden dat dit een overlay is en geen momentopname: als de backend morgen een
nieuwe zender met `sortOrder: 12` toevoegt, verschijnt die tussen de zenders die bij de
gebruiker rond die positie staan, in plaats van onderaan of helemaal niet.

Randgevallen die de resolver expliciet moet afdekken:

| Geval | Verwacht gedrag |
|---|---|
| Voorkeur is leeg | Backendvolgorde ongewijzigd |
| Voorkeur bevat id dat de backend niet meer levert | Id wordt genegeerd, niet verwijderd uit de opslag (zender kan terugkomen) |
| Backend levert zender die niet in de voorkeur staat | Ingevoegd op backendpositie (regel 3) |
| Alle zenders verborgen | Resolver geeft lege lijst; UI toont een duidelijke lege staat met knop naar het ordenscherm |
| Verborgen zender komt niet meer in backend voor | Blijft in `hiddenIds` staan; ongebruikt, ongevaarlijk |
| Voorkeur bevat dubbele id's (corrupte opslag) | Eerste voorkomen telt, rest genegeerd |

### Opslag

Nieuw bestand `data/repository/ChannelOrderRepository.kt`:

```kotlin
class ChannelOrderRepository(context: Context) {
    private val prefs = context.getSharedPreferences("channel_order", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): ChannelOrderPreferences      // corrupte JSON -> defaults + Log.w
    fun save(value: ChannelOrderPreferences) // synchroon via commit() op IO-dispatcher
    fun reset()                              // wist de voorkeur volledig
    fun observe(): Flow<ChannelOrderPreferences>  // callbackFlow op OnSharedPreferenceChangeListener
}
```

`observe()` zorgt dat het gidsscherm zichzelf bijwerkt zodra het ordenscherm sluit, zonder dat
de twee schermen elkaar direct hoeven te kennen.

Sleutel: `"prefs_json"`. Schemaversie in de JSON, zodat een toekomstige v2 (bijvoorbeeld
groepen) migreerbaar is in plaats van weggegooid.

### UI: het ordenscherm

Nieuw `ChannelOrderFragment` (Leanback-vrij, gewoon een `RecyclerView` in een fragment; de app
gebruikt verder ook geen `BrowseSupportFragment`).

Layout, van boven naar beneden:

- Titel "Zendervolgorde" en een korte, permanente hulpregel die de actieve bediening beschrijft.
  De hulpregel wisselt van tekst zodra een zender is opgepakt — dat is het enige echte
  ontdekbaarheidsprobleem van deze interactie.
- `RecyclerView` met per rij: positienummer, zenderlogo (Glide, zoals in `ProgramGuideRowAdapter`),
  zendernaam, en rechts een oog-icoon voor de verborgen-status.
- Onderaan: "Herstel standaardvolgorde" en "Klaar".

Toestandsmachine per rij:

| Toestand | D-pad op/neer | OK | Terug |
|---|---|---|---|
| Normaal | Verplaats focus | Pak zender op (`grabbed`) | Sluit scherm (met opslaan) |
| Opgepakt | Verplaats de *zender* mee | Zet neer | Zet neer op oorspronkelijke positie |

Visuele feedback in opgepakte staat: rij krijgt accentrand en lichte schaalvergroting
(`programguide_title_text_color_focused` als accent, consistent met de kopbalk), plus een
`View.performHapticFeedback` en een korte `TalkBack`-announcement per verplaatsing.

Verbergen: rechter-D-pad zet de focus op het oog-icoon; OK daar wisselt zichtbaar/verborgen.
Verborgen rijen blijven in de lijst staan, gedimd op ~40% alpha, zodat ze terug te zetten zijn.

### Ingang vanuit het gidsscherm: hamburgermenu

In [`programguide_fragment.xml`](../android/library/src/main/res/layout/programguide_fragment.xml)
staat de kopbalk met `programguide_jump_to_live` ("Nu live"), de datumcontainer en rechts de
app-titel. Daar komt een **hamburgermenuknop** bij, direct rechts van "Nu live" en dus links van
`programguide_header_guide_date_container`:

```
[ Dagfilter ] [ Dagdeelfilter ] [ Nu live ] [ ☰ ]   Gidsdatum        NexusTVGuide
                                             ^ nieuw                 Ma 31 aug • 00:35
```

De knop is een vierkante `ImageButton` met een `programguide_ic_menu`-vectoricoon (drie
horizontale strepen), dezelfde hoogte als de "Nu live"-knop, en dezelfde focus-achtergrond en
tint-selector (`programguide_button_background.xml` / `programguide_button_text_color.xml`, die
net zijn toegevoegd). `contentDescription` = "Menu", zodat TalkBack de knop kan benoemen.
Positionering in de `ConstraintLayout`: `layout_constraintStart_toEndOf="@id/programguide_jump_to_live"`
met `programguide_filter_spacing` als marge, en het datumcontainer-constraint verschuift van
`toEndOf="@id/programguide_jump_to_live"` naar `toEndOf` de nieuwe knop.

#### Het menu zelf

Klikken opent een `PopupMenu`, verankerd aan de knop en uitgelijnd op de linkerrand ervan, met
op dit moment één regel:

| Regel | Actie |
|---|---|
| Zenders ordenen | Opent `ChannelOrderFragment` |

Het menu is bewust een lijst en geen directe actie, zodat latere opties (zenderlijst verversen,
backend-URL wijzigen, over de app) erbij kunnen zonder dat de kopbalk opnieuw hoeft te worden
ingedeeld. Eén regel in een menu is dan een aanvaardbare tussenstap.

Aandachtspunten bij `PopupMenu` op Android TV:

- `PopupMenu` is standaard op touch gericht. Zet expliciet `setForceShowIcon(false)` en
  controleer dat de eerste regel bij openen focus krijgt, zodat OK direct werkt.
- Terug sluit het menu zonder actie en geeft de focus terug aan de hamburgerknop — niet aan het
  grid. Dit expliciet testen; het is het meest waarschijnlijke focusdefect.
- Blijkt `PopupMenu` op de Shield te stroef of niet goed focusbaar, wijk dan uit naar dezelfde
  `AlertDialog.Builder(...).setSingleChoiceItems(...)` die `setupFilters()` in
  `ProgramGuideFragment` nu al gebruikt voor het dag- en dagdeelfilter. Dat patroon is in deze
  app al bewezen op de Shield; het is de veilige terugvaloptie.

#### Waar de knop leeft

Omdat dit de gedeelde library-layout is, zijn er twee opties:

- **A (voorkeur):** knop in de library-layout met `android:visibility="gone"` als standaard, plus
  een `open val DISPLAY_MENU_BUTTON: Boolean = false` en een open callback
  `onMenuButtonClicked(anchor: View)` in `ProgramGuideFragment`. De app zet de vlag aan en bouwt
  het menu in de callback op. De library kent de menu-inhoud dus niet; die blijft app-specifiek.
  Dit houdt de fork bruikbaar voor derden.
- **B:** knop alleen in de app, in een eigen wrapper-layout om het gidsfragment heen. Vergt een
  extra container en focusroute; niet aan te raden zolang de kopbalk in de library zit.

Kies A. Het is een `open val` + `open fun` erbij in de fork — dezelfde manier waarop
`isTopMenuVisible()` en `DISPLAY_CURRENT_TIME_INDICATOR` nu al werken.

Alternatieve, snellere ingang voor gevorderden: lang indrukken van OK op een zenderlogo in het
grid opent het ordenscherm rechtstreeks met die zender voorgeselecteerd, buiten het menu om.
Optioneel, na de basisfunctie.

### Navigatie

`MainActivity` krijgt een tweede fragmenttransactie:

```kotlin
supportFragmentManager.beginTransaction()
    .replace(R.id.main_container, ChannelOrderFragment())
    .addToBackStack("channel_order")
    .commit()
```

Bij terugnavigeren komt het gidsfragment terug; `onResume()` daarvan roept al `requestRefresh()`
aan, en de `observe()`-flow zorgt dat de nieuwe volgorde meteen wordt toegepast — ook als er
geen netwerk is, want de resolver werkt op de al opgehaalde snapshot.

Let op: `NexusProgramGuideFragment.onResume()` springt nu altijd naar "live". Na terugkeer uit
het ordenscherm is dat prima gedrag; er is geen extra logica nodig, maar het moet wel getest
worden dat de focus niet op een verdwenen (verborgen) rij achterblijft.

### Toepassing in de ViewModel

`GuideViewModel` krijgt de resolver ertussen:

```kotlin
val ordered = ChannelOrderResolver.apply(guideResponse.channels, orderPrefs)
val channels: List<ProgramGuideChannel> = ordered.map { SimpleChannel(...) }
```

en bouwt `scheduleMap` alleen nog voor de zichtbare zenders. Programma's van verborgen zenders
worden overgeslagen — dat scheelt ook geheugen bij een grote gids.

De ViewModel combineert `uiState` met `orderRepository.observe()`, zodat een wijziging van de
volgorde geen nieuwe netwerkronde vergt: de laatst geladen `GuideResponseDto` wordt in de
ViewModel bewaard en opnieuw door de resolver gehaald.

## Implementatiestappen

### Stap 1 — Datamodel, opslag en resolver

Bestanden:

- `android/app/src/main/java/com/nexustvguide/app/data/model/ChannelOrderPreferences.kt` (nieuw)
- `android/app/src/main/java/com/nexustvguide/app/data/repository/ChannelOrderRepository.kt` (nieuw)
- `android/app/src/main/java/com/nexustvguide/app/data/ChannelOrderResolver.kt` (nieuw)

Acceptatiecriteria:

- `ChannelOrderResolver` is een `object` zonder Android-afhankelijkheden, zodat het met een
  gewone JUnit-test getest kan worden (geen Robolectric nodig).
- Alle randgevallen uit de tabel hierboven hebben een test in
  `app/src/test/java/com/nexustvguide/app/data/ChannelOrderResolverTest.kt`.
- `ChannelOrderRepository.load()` op een lege of corrupte prefs geeft de defaults terug en
  logt op `Log.w`, en gooit niet.

### Stap 2 — Toepassen in het gidsscherm

Bestanden:

- `ui/GuideViewModel.kt` (aangepast)

Acceptatiecriteria:

- Zonder opgeslagen voorkeur is het grid pixel-identiek aan de huidige situatie.
- Met een voorkeur staan de rijen in die volgorde, en verborgen zenders ontbreken.
- Een wijziging in `ChannelOrderRepository` leidt tot een nieuwe `GuideUiState.Content` zonder
  netwerkverkeer (te controleren met de OkHttp-logginginterceptor).
- Prefetch van aangrenzende dagen blijft ongewijzigd werken.

### Stap 3 — Ordenscherm

Bestanden:

- `ui/ChannelOrderFragment.kt` (nieuw)
- `ui/ChannelOrderViewModel.kt` (nieuw)
- `ui/ChannelOrderAdapter.kt` (nieuw)
- `res/layout/fragment_channel_order.xml`, `res/layout/item_channel_order.xml` (nieuw)
- `res/values/strings.xml` (aangevuld, Nederlandse teksten)

Acceptatiecriteria:

- Volledig bedienbaar met alleen D-pad + OK + Terug; muis/touch is niet vereist.
- Een verplaatsing is zichtbaar binnen één frame en de rij houdt de focus vast tijdens het
  verplaatsen (`setHasStableIds(true)` en `notifyItemMoved`, geen `notifyDataSetChanged`).
- Opgepakte staat is onmiskenbaar anders dan alleen-gefocust.
- "Herstel standaardvolgorde" vraagt om bevestiging en herstelt zowel volgorde als verborgen
  zenders.
- Wijzigingen worden bij elke handeling opgeslagen, niet pas bij "Klaar" — de gebruiker kan de
  Shield uitzetten zonder werk kwijt te raken.

### Stap 4 — Hamburgermenu in de kopbalk (library-aanpassing)

Bestanden:

- `library/src/main/res/layout/programguide_fragment.xml` (aangepast — knop toevoegen, constraint
  van de datumcontainer verleggen)
- `library/src/main/res/drawable/programguide_ic_menu.xml` (nieuw — vectoricoon)
- `library/src/main/java/com/egeniq/androidtvprogramguide/ProgramGuideFragment.kt` (aangepast —
  `DISPLAY_MENU_BUTTON` en `onMenuButtonClicked(anchor)`)
- `app/src/main/java/com/nexustvguide/app/ui/NexusProgramGuideFragment.kt` (aangepast — vlag aan,
  menu opbouwen, navigatie naar `ChannelOrderFragment`)
- `app/src/main/res/menu/guide_overflow.xml` (nieuw — één item: "Zenders ordenen")
- `library/src/main/res/values/strings.xml` en `app/src/main/res/values/strings.xml` (aangevuld)

Acceptatiecriteria:

- De knop is standaard `gone`; een schone integratie van de library verandert niet van uiterlijk.
- De knop staat direct rechts van "Nu live" en de gidsdatum schuift mee op zonder de app-titel
  rechts te overlappen — ook bij de langste datumtekst ("Woensdag 10 september 2026").
- De focusroute in de kopbalk is links→rechts logisch: dagfilter → dagdeelfilter → Nu live →
  hamburgerknop, en van de knoprij omlaag naar het grid.
- Van het grid omhoog komt de focus terug op de knoprij (controleer op de eerste rij van het
  grid, waar `ProgramGuideGridView` de focus normaal vasthoudt).
- Het menu opent met de eerste regel gefocust, zodat OK direct werkt zonder eerst omlaag te
  moeten.
- Terug in het geopende menu sluit het menu en geeft de focus terug aan de hamburgerknop, niet
  aan het grid en niet aan de app-achtergrond.
- Het menu is opgebouwd in de app-laag; de library kent de menu-inhoud niet.

### Stap 5 — Randgevallen en polish

Acceptatiecriteria:

- Alle zenders verborgen → duidelijke lege staat in het grid met directe knop naar het ordenscherm,
  geen `GuideUiState.Error`.
- Backend voegt een zender toe → die verschijnt op zijn backendpositie tussen de bestaande
  zenders (handmatig te testen door `channels.json` uit te breiden en de backend te herstarten).
- Offline start met alleen de schijfcache → volgorde wordt gewoon toegepast.
- Verplaatsen van de bovenste rij omhoog of de onderste omlaag doet niets en geeft geen crash.

## Testplan

Geautomatiseerd (`./gradlew :app:testDebugUnitTest`):

- `ChannelOrderResolverTest` — de tabel met randgevallen, plus een property-achtige test dat
  het resultaat altijd dezelfde verzameling zichtbare id's bevat als de backend minus de
  verborgen id's.
- `ChannelOrderRepositoryTest` (Robolectric, zoals `NexusProgramGuideFocusTest` dat al doet) —
  opslaan/laden/reset en herstel na corrupte JSON.
- Uitbreiding van de bestaande focustest voor de nieuwe hamburgerknop in de kopbalk.

Handmatig op de Shield:

1. Open het hamburgermenu met de D-pad, kies "Zenders ordenen"; sluit het menu daarna een keer
   met Terug zonder te kiezen → focus staat weer op de hamburgerknop.
2. Verplaats de onderste zender naar boven, sluit de app volledig af, start opnieuw → volgorde blijft.
3. Wissel van dag heen en terug → volgorde blijft.
4. Verberg drie zenders, controleer dat hun programma's ook uit het detailpaneel verdwijnen.
5. Herstel standaardvolgorde → identiek aan een schone installatie.
6. Trek de netwerkstekker eruit, herstart, controleer dat de volgorde ook op de schijfcache klopt.
7. Open NLZiet vanuit het grid en kom terug → volgorde en focus nog intact.

## Risico's

| Risico | Kans | Mitigatie |
|---|---|---|
| Focus springt weg tijdens verplaatsen in de `RecyclerView` | Middel | `notifyItemMoved` + stabiele id's; expliciet `requestFocus()` op de verplaatste holder na de animatie |
| Gebruiker vindt de opgepakt-modus niet | Middel | Permanente hulpregel die van tekst wisselt; geen verborgen long-press als enige route |
| Hamburgerknop verstoort bestaande focusroutes in het grid | Middel | Stap 4 apart houden en de bestaande focustest uitbreiden vóór de knop wordt aangezet |
| `PopupMenu` gedraagt zich slecht met D-pad op de Shield | Middel | Terugvaloptie is de `AlertDialog`-aanpak die `setupFilters()` al gebruikt; vroeg testen op echte hardware |
| Menu met één regel voelt als omweg | Laag | Bewuste keuze met het oog op latere opties; long-press op een zenderlogo blijft als snelle route beschikbaar |
| Voorkeur loopt uit de pas met een sterk gewijzigde backendlijst | Laag | Overlaymodel met invoegregel; voorkeur wordt nooit stilzwijgend gewist |
| Verborgen zenders verwarren bij een lege gids | Laag | Aparte lege staat met directe ingang naar het ordenscherm |

## Definition of Done

- Volgorde en zichtbaarheid zijn met alleen de afstandsbediening in te stellen en blijven
  bewaard over herstarts, dagwissels en offline starts.
- Zonder ingestelde voorkeur is het gedrag identiek aan de huidige app.
- Nieuwe zenders uit de backend verschijnen automatisch op een zinnige positie.
- Unit tests uit het testplan draaien groen; de zeven handmatige scenario's zijn op de Shield
  afgevinkt.
- `PLAN.md` verwijst naar dit document en besluit 5 ("Volgorde: backend eerst, daarna Android")
  is bijgewerkt met de uitkomst.

## Vervolgstappen na deze functie

- Volgorde exporteren naar de backend, zodat de XMLTV-output voor Jellyfin/TiviMate dezelfde
  volgorde krijgt.
- Groepen of favorietenlijsten (schemaversie 2 van `ChannelOrderPreferences`).
- Een "recent gekeken bovenaan"-modus als alternatief voor handmatig ordenen.
