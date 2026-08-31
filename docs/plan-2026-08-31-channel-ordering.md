# Plan: eigen zendervolgorde in NexusTVGuide

Status: **voorstel, nog niet geïmplementeerd**

Werkdocument voor de functie "gebruiker bepaalt zelf de verticale volgorde van de zenders in
het EPG-grid". Sluit aan op [`plan-2026-08-30-bouwplan.md`](plan-2026-08-30-bouwplan.md) (fase 2, Android) en gebruikt dezelfde
conventies: expliciete besluiten, concrete stappen, acceptatiecriteria per stap.

Geschreven op basis van de code zoals die er op **2026-08-31** uitziet; de aannames over
bestaande klassen, layouts en thema's zijn op die datum tegen de bronbestanden gecontroleerd.

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
| 6b | Menuvorm | `AlertDialog` met keuzelijst, niet `PopupMenu` | `Theme.NexusTVGuide` erft van `Theme.Leanback` en is geen AppCompat-thema; de filterdialoog is daar eerder al op gecrasht. Het dialoogpatroon van `setupFilters()` is op de Shield bewezen. |
| 7 | Verbergen | Onderdeel van hetzelfde scherm, via de rechter-D-pad of een aparte toets | Ordenen en verbergen zijn in de praktijk dezelfde handeling ("deze wil ik bovenaan, die hoef ik niet"). |
| 8 | Toepassing | In `GuideViewModel`, na het ophalen, vóór het mappen naar `ProgramGuideChannel` | Eén plek; het `ProgramGuideFragment`/`ProgramGuideManager` blijft onaangeroerd. |
| 9 | Navigatie | `add` + `hide` op de bestaande container, met backstack en een begrensde lifecycle voor het verborgen gidsfragment | `replace()` vernietigt de view; alleen `hide()` laat het fragment echter actief doorrenderen. `setMaxLifecycle(..., STARTED)` plus verzamelen vanaf `RESUMED` bewaart de view zonder verborgen grid-updates. |

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
- `ChannelDto` bevat wél al `sortOrder`, maar de app gebruikt dat veld nu niet — en hoeft dat na
  deze functie ook niet te gaan doen: de resolver werkt op posities in de backendlijst, die al
  gesorteerd binnenkomt.
- `MainActivity` gebruikt `commitNow()` zonder tag en zonder backstack; er is nog geen enkele
  terug-navigatie in de app.
- `Theme.NexusTVGuide` erft van `Theme.Leanback` (geen AppCompat) en `MainActivity` erft van
  `FragmentActivity` (geen `AppCompatActivity`). Dat stuurt de keuze voor het menu — zie besluit 6b.
- Gson zit alleen transitief in de app-module, via `retrofit2:converter-gson`.
- `CAN_FOCUS_CHANNEL` staat op `false` en wordt niet overschreven, dus de zenderkolom is niet
  focusbaar en `onChannelClicked()` wordt in de praktijk nooit aangeroepen.

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

De resolver is puur: dezelfde invoer geeft altijd dezelfde uitvoer, en hij muteert `prefs` niet.
Hij is *niet* verantwoordelijk voor het opschonen van de opslag — zie "Opschonen" hieronder.

1. Neem `prefs.orderedIds` in volgorde; behoud alleen id's die in `backendChannels` voorkomen,
   en sla een id over dat al eerder in de lijst stond (dedupe op eerste voorkomen).
2. Bepaal de "nieuwe" zenders: alles in `backendChannels` dat niet in `prefs.orderedIds` staat.
   Verwerk die **in oplopende backendvolgorde** (de volgorde waarin de backend ze levert), zodat
   meerdere nieuwe zenders onderling hun backendvolgorde houden.
3. Voeg elke nieuwe zender in ten opzichte van zijn backend-buren: loop de backendlijst terug
   vanaf de nieuwe zender en zoek de eerste voorganger die al in de resultaatlijst staat; plaats
   de nieuwe zender daar direct achter. Is er geen zo'n voorganger, dan vooraan.
4. Filter `prefs.hiddenIds` eruit.
5. Bij een lege of ontbrekende voorkeur is het resultaat exact de backendvolgorde.

Twee punten die makkelijk fout gaan en die de tests dus expliciet moeten vastleggen:

- **Buren, niet `sortOrder`-getallen.** De invoegpositie wordt bepaald door de *positie* van de
  buurman in de backendlijst, niet door een numerieke vergelijking van `sortOrder`. De backend
  levert de lijst al gesorteerd op `sortOrder` (`refresh.ts`), en `sortOrder`-waarden mogen gaten
  en — bij een slordige `channels.json` — duplicaten bevatten. Positievergelijking is daar
  ongevoelig voor; `ChannelDto.sortOrder` hoeft de app dus helemaal niet te lezen.
- **Stapsgewijs invoegen.** Elke nieuwe zender wordt ingevoegd in de lijst zoals die op dat
  moment is, inclusief eerder ingevoegde nieuwe zenders. Anders belanden twee nieuwe zenders die
  in de backend naast elkaar staan in omgekeerde volgorde in het grid.

Dit is de reden dat de voorkeur een overlay is en geen momentopname: als de backend morgen een
nieuwe zender toevoegt, verschijnt die naast de zender waar hij in de backendlijst achter staat,
in plaats van onderaan of helemaal niet.

#### Opschonen en intentie van de gebruiker

`orderedIds` mag id's bevatten die de backend tijdelijk niet levert (zie de randgevallentabel).
De resolver verwijdert die nooit: hij negeert ze alleen bij het opbouwen van het resultaat. Er
komt dus géén achtergrondopschoning, want dan zou een zender die één dag ontbreekt zijn plek
definitief kwijtraken.

Het ordenscherm schrijft de twee voorkeursonderdelen niet onnodig samen opnieuw:

- Alleen openen/sluiten of zichtbaar/verborgen wisselen laat `orderedIds` exact ongemoeid. Anders
  zou een gebruiker die alleen een zender verbergt de huidige backendvolgorde onbedoeld als
  blijvende eigen volgorde vastleggen, waarna latere backendwijzigingen niet meer doorwerken.
- Na een echte verplaatsing wordt de volledige getoonde volgorde opgeslagen. Dan zijn niet meer
  geleverde id's bewust uit `orderedIds` opgeschoond: de gebruiker heeft op dat moment expliciet
  een nieuwe volgorde vastgesteld. Een later terugkerende zender geldt vervolgens als nieuw.
- Een zichtbaarheidstoggle voegt of verwijdert alleen het betreffende id in `hiddenIds` en
  behoudt verborgen id's die niet in de actuele lijst staan. Alleen "Herstel
  standaardvolgorde" wist beide collecties volledig.

Deze regels voorkomen zowel stil dataverlies als het bevriezen van de backendvolgorde door een
handeling die daar niet over ging. Leg elk van de drie regels vast in een ViewModel- of
repositorytest.

Randgevallen die de resolver expliciet moet afdekken:

| Geval | Verwacht gedrag |
|---|---|
| Voorkeur is leeg | Backendvolgorde ongewijzigd |
| Voorkeur bevat id dat de backend niet meer levert | Id wordt door de resolver genegeerd. Alleen een echte verplaatsing schoont een ontbrekend id uit `orderedIds` op; een zichtbaarheidstoggle laat onbekende voorkeuren intact |
| Backend levert zender die niet in de voorkeur staat | Ingevoegd achter zijn backend-voorganger (regel 3) |
| Meerdere nieuwe zenders naast elkaar in de backend | Blijven onderling in backendvolgorde staan |
| Nieuwe zender staat vooraan in de backendlijst | Komt vooraan in het resultaat |
| Alle zenders verborgen | Resolver geeft lege lijst; UI toont een aparte lege staat met een directe focusroute via het menu naar het ordenscherm, géén `GuideUiState.Error` |
| Backend levert zelf een lege lijst | Blijft `GuideUiState.Error` — dit is een backend-/netwerkprobleem, geen gebruikersinstelling |
| Verborgen zender komt niet meer in backend voor | Blijft in `hiddenIds` staan; ongebruikt, ongevaarlijk |
| Voorkeur bevat dubbele id's (corrupte opslag) | Eerste voorkomen telt, rest genegeerd |
| Id staat zowel in `orderedIds` als in `hiddenIds` | `hiddenIds` wint; de zender is verborgen maar houdt zijn plek voor als hij weer zichtbaar wordt |
| `orderedIds` bevat alle backendzenders, `hiddenIds` is leeg | Resultaat is een permutatie van de backendlijst, zelfde grootte |

### Opslag

Nieuw bestand `data/repository/ChannelOrderRepository.kt`:

```kotlin
class ChannelOrderRepository(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("channel_order", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): ChannelOrderPreferences           // corrupte JSON -> defaults + Log.w
    fun save(value: ChannelOrderPreferences)      // apply(); zie hieronder
    fun reset()                                   // wist de voorkeur volledig
    fun observe(): Flow<ChannelOrderPreferences>  // callbackFlow op OnSharedPreferenceChangeListener
}
```

Aandachtspunten die bij het uitwerken makkelijk misgaan:

- **`applicationContext`.** De repository wordt zowel vanuit een fragment als vanuit de
  `AndroidViewModel` gemaakt; een fragmentcontext vasthouden lekt de activity.
- **`apply()`, niet `commit()`.** `apply()` schrijft asynchroon maar is *direct* zichtbaar voor
  volgende `load()`-aanroepen in hetzelfde proces, en Android flusht de wachtende schrijfactie
  bij `onPause`. `commit()` op een IO-dispatcher zoals de eerste opzet suggereerde levert geen
  extra garantie op en maakt `save()` `suspend`, wat het aanroepen vanuit een klikhandler
  omslachtig maakt. `GuideRepository` gebruikt om dezelfde reden al `apply()` voor de ETags.
- **De listener sterk vasthouden.** `SharedPreferences` houdt zijn
  `OnSharedPreferenceChangeListener` in een `WeakHashMap`; een listener die alleen als lokale
  lambda bestaat kan zomaar worden opgeruimd en dan komen er geen updates meer. In de
  `callbackFlow` moet de listener in een `val` staan die tot `awaitClose` in scope blijft.
- **Geen gemiste eerste wijziging.** Registreer de listener eerst en stuur daarna meteen
  `trySend(load())`. De omgekeerde volgorde heeft een race: een `save()` tussen de eerste `load()`
  en registratie van de listener wordt nooit gezien. Houd de listener tot `awaitClose` sterk
  vast, registreer hem precies één keer en combineer de flow met `distinctUntilChanged()`.
- **Valideren na deserialisatie.** `JsonSyntaxException` is een subtype van
  `JsonParseException`; één catch op `JsonParseException` dekt beide. Alleen een exception
  afvangen is niet genoeg: geldige JSON zoals `null`, ontbrekende velden, null-elementen en een
  onbekende `version` moeten eveneens naar een genormaliseerde, niet-null voorkeur of naar de
  defaults leiden. `load()` wijzigt de opgeslagen tekst daarbij niet stilzwijgend.

`observe()` zorgt zo dat het gidsscherm zichzelf bijwerkt zodra het ordenscherm sluit, zonder dat
de twee schermen elkaar direct hoeven te kennen.

Sleutel: `"prefs_json"`. Schemaversie in de JSON, zodat een toekomstige v2 (bijvoorbeeld
groepen) migreerbaar is in plaats van weggegooid. `load()` behandelt een onbekende, hogere
`version` als "onleesbaar" en valt terug op de defaults — dat kan alleen na een downgrade.
Gebruik voor het lezen bij voorkeur een interne nullable opslag-DTO of controleer eerst de
`JsonObject`, zodat een ontbrekend `version`-veld niet door de Kotlin-defaultwaarde ongemerkt als
v1 wordt geaccepteerd. In versie 1 is alleen `version == CURRENT_VERSION` geldig; toekomstige
lagere versies krijgen een expliciete migratietak voordat zij worden ondersteund.

**Gson-afhankelijkheid:** de app-module declareert Gson nu niet expliciet; hij komt binnen via
`com.squareup.retrofit2:converter-gson`. Dat werkt, maar is broos. Voeg in stap 1
`implementation 'com.google.code.gson:gson:2.10.1'` toe aan `app/build.gradle`. Dit legt de versie
vast die `converter-gson:2.11.0` nu al transitief gebruikt en verandert de resolved dependency
daarom niet.

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

Toestandsmachine per rij — links/rechts staat er expliciet bij, want dat is de as waar de twee
functies (ordenen en verbergen) elkaar raken:

| Toestand | D-pad op/neer | D-pad links/rechts | OK | Terug |
|---|---|---|---|---|
| Normaal, focus op rij | Verplaats focus | Rechts → focus naar oog-icoon | Pak zender op (`grabbed`) | Sluit scherm (met opslaan) |
| Normaal, focus op oog-icoon | Verplaats focus, focus blijft op de oog-kolom | Links → terug naar de rij | Wissel zichtbaar/verborgen | Sluit scherm |
| Opgepakt | Verplaats de *zender* mee | Genegeerd (geen focuswissel, geen actie) | Zet neer | Zet neer op oorspronkelijke positie |

Dat "genegeerd" in de opgepakte staat is bewust: zou links/rechts daar de focus naar het
oog-icoon verplaatsen, dan raakt de opgepakte zender zijn ankerpunt kwijt en is er geen zinnige
plek meer om hem neer te zetten. De `OnKeyListener` geeft in die staat dus ook voor links/rechts
`true` terug.

Bij "zet neer op oorspronkelijke positie" moet de oorspronkelijke index worden onthouden op het
moment van oppakken, niet herleid worden — na meerdere verplaatsingen is hij niet meer af te
leiden uit de lijst.

Handel Terug in de opgepakte toestand af met een aan de `viewLifecycleOwner` gekoppelde
`OnBackPressedCallback`, niet alleen met de `OnKeyListener` van een rij. Een systeem-Back hoeft
niet langs de gefocuste holder te lopen. Na annuleren wordt de oorspronkelijke volgorde opnieuw
opgeslagen, omdat tussenliggende verplaatsingen al direct zijn gepersisteerd; een tweede Terug
sluit vervolgens het scherm.

Visuele feedback in opgepakte staat: rij krijgt accentrand en lichte schaalvergroting
(`programguide_title_text_color_focused` als accent, consistent met de kopbalk), plus per
verplaatsing een `announceForAccessibility` met positie en totaal ("SBS 6, positie 4 van 32").
Optioneel een `View.performHapticFeedback`, maar reken daar niet op: de meeste Android
TV-afstandsbedieningen hebben geen trilmotor, dus de visuele feedback moet op zichzelf voldoende
zijn.

Verbergen: rechter-D-pad zet de focus op het oog-icoon; OK daar wisselt zichtbaar/verborgen.
Verborgen rijen blijven in de lijst staan, gedimd op ~40% alpha, zodat ze terug te zetten zijn.
Let op dat 40% alpha op een gefocuste rij het focuskader niet mag wegdrukken — dim de inhoud
(logo en naam), niet de hele rij inclusief achtergrond.

Koppel dezelfde key-afhandeling aan de rij én het oog-icoon, verwerk acties alleen op
`KeyEvent.ACTION_DOWN` en consumeer de bijbehorende key-up waar nodig. Zo toggelt één OK-druk
niet tweemaal en blijft op/neer vanuit de oog-kolom in die kolom. Geef het icoon een dynamische
content description ("Verberg NPO 1" / "Toon NPO 1") in plaats van alleen een decoratieve
"oog"-beschrijving.

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
tint-selector (`drawable/programguide_button_background.xml` / `color/programguide_button_text_color.xml`,
die al bestaan). `contentDescription` = "Menu", zodat TalkBack de knop kan benoemen.

Positionering in de `ConstraintLayout`, precies zoals de bestaande kopbalkelementen het doen —
elk element hangt aan `Top_toTopOf="@id/programguide_top_margin"` en
`Bottom_toTopOf="@id/programguide_time_row"`:

```xml
app:layout_constraintStart_toEndOf="@id/programguide_jump_to_live"
android:layout_marginStart="@dimen/programguide_filter_spacing"
```

en `programguide_header_guide_date_container` verschuift van
`layout_constraintStart_toEndOf="@id/programguide_jump_to_live"` naar `toEndOf` de nieuwe knop.
Let op dat die container nu `android:layout_marginStart="24dp"` hardcodeert. Trek die waarde
zonder visuele wijziging uit naar een nieuwe dimensie
`programguide_header_date_spacing = 24dp` en gebruik die voor zowel de gewone als de gone-marge.

Omdat de knop standaard `gone` is (zie hieronder), moet de datumcontainer bij een schone
library-integratie op zijn oude plek uitkomen. `View.GONE` in een `ConstraintLayout` laat de
keten niet vanzelf doorlopen: zet daarom op de datumcontainer
`app:layout_goneMarginStart="@dimen/programguide_header_date_spacing"`. Gebruik hier nadrukkelijk
niet `programguide_filter_spacing`: die is 8dp, terwijl de bestaande afstand 24dp is. Controleer
in de layout-preview dat de kopbalk met de knop op `gone` er identiek uitziet als nu. Dit is
precies het soort regressie dat pas op een schone integratie zichtbaar wordt.

#### Het menu zelf

Klikken opent een menu met op dit moment één regel:

| Regel | Actie |
|---|---|
| Zenders ordenen | Opent `ChannelOrderFragment` |

Het menu is bewust een lijst en geen directe actie, zodat latere opties (zenderlijst verversen,
backend-URL wijzigen, over de app) erbij kunnen zonder dat de kopbalk opnieuw hoeft te worden
ingedeeld. Eén regel in een menu is dan een aanvaardbare tussenstap.

**Gebruik de `AlertDialog`, niet `PopupMenu`.** Een eerdere versie van dit plan koos `PopupMenu`
met de dialoog als terugvaloptie; die volgorde is omgedraaid, om een reden die in deze codebase
al is opgelopen. `Theme.NexusTVGuide` erft van `Theme.Leanback`, en dat is géén AppCompat-thema.
In [`styles.xml`](../android/app/src/main/res/values/styles.xml) staat daar een expliciete
opmerking bij: `androidx.appcompat.app.AlertDialog` vond eerder geen `alertDialogTheme`, resolvede
naar 0 en crashte bij het inflaten van de keuzelijst; daarom wijst het thema nu expliciet
`alertDialogTheme` en `android:alertDialogTheme` aan. `androidx.appcompat.widget.PopupMenu` leunt
op dezelfde soort AppCompat-thema-attributen (`popupMenuStyle`, `listPopupWindowStyle`,
`textAppearanceLargePopupMenu`) en zou dus dezelfde klasse fout opleveren; `MainActivity` erft
bovendien van `FragmentActivity`, niet van `AppCompatActivity`, dus er is ook geen AppCompat-delegate
die dat opvangt.

Het menu wordt daarom opgebouwd met dezelfde
`AlertDialog.Builder(...).setSingleChoiceItems(...)`-aanpak die `setupFilters()` in
[`ProgramGuideFragment.kt:306`](../android/library/src/main/java/com/egeniq/androidtvprogramguide/ProgramGuideFragment.kt#L306)
al gebruikt voor het dag- en dagdeelfilter. Dat patroon is in deze app al bewezen op de Shield:
het is D-pad-bedienbaar, het eerste item krijgt focus, en Terug sluit de dialoog en geeft de focus
terug aan de aanroepende view. Dat scheelt bovendien de hele lijst met TV-aandachtspunten die een
`PopupMenu` nodig zou hebben.

Blijft over als aandachtspunt: na het sluiten van de dialoog (zowel via Terug als na een keuze
die niet navigeert) moet de focus terug naar de hamburgerknop, niet naar het grid. Zet dat
expliciet in `setOnDismissListener` met een `post { menuButton.requestFocus() }`, maar alleen als
er niet naar het ordenscherm wordt genavigeerd en de knop nog `isShown` is. Anders kan de
dismiss-callback in dezelfde frame de focus van het nieuwe fragment terugstelen. Controleer dit
op de Shield — het is het meest waarschijnlijke focusdefect van deze stap.

Wil je later toch de compactere `PopupMenu`-look, dan is de voorwaarde dat `MainActivity`
naar `AppCompatActivity` gaat of dat de popup een `ContextThemeWrapper` met een AppCompat-thema
krijgt. Dat is een aparte wijziging, geen onderdeel van deze functie.

#### Waar de knop leeft

Omdat dit de gedeelde library-layout is, zijn er twee opties:

- **A (voorkeur):** knop in de library-layout met `android:visibility="gone"` als standaard, plus
  een `protected open val DISPLAY_MENU_BUTTON: Boolean = false` en een open callback
  `protected open fun onMenuButtonClicked(anchor: View) {}` in `ProgramGuideFragment`. De app zet
  de vlag aan en bouwt het menu in de callback op. De library kent de menu-inhoud dus niet; die
  blijft app-specifiek. Dit houdt de fork bruikbaar voor derden.
- **B:** knop alleen in de app, in een eigen wrapper-layout om het gidsfragment heen. Vergt een
  extra container en focusroute; niet aan te raden zolang de kopbalk in de library zit.

Kies A. Het is een `open val` + `open fun` erbij in de fork — dezelfde soort uitbreidingspunten
als `DISPLAY_CURRENT_TIME_INDICATOR` en `CAN_FOCUS_CHANNEL` die er al zijn. Let op de zichtbaarheid:
de bestaande vlaggen zijn `protected open val`, dus de nieuwe moet dat ook zijn om consistent te
blijven (`isTopMenuVisible()` is een `abstract fun` en dus een ander patroon — niet als voorbeeld
gebruiken).

De zichtbaarheid wordt gezet waar de andere kopbalkelementen worden geïnitialiseerd, in
`onViewCreated`/`setupFilters`-omgeving, met `View.VISIBLE` of `View.GONE` op basis van de vlag —
niet `INVISIBLE`, anders blijft de knop ruimte innemen bij een schone integratie.

Behandel de nieuwe view als optioneel met `findViewById(...)?` en niet met `!!`.
`ProgramGuideFragment` ondersteunt immers `OVERRIDE_LAYOUT_ID`; een bestaande custom layout kent
de nieuwe id nog niet en mag bij een library-update niet crashen. Leg daarnaast de horizontale
focusroute expliciet vast (`Nu live` ↔ hamburger) in plaats van alleen op geometrische
focus-search te vertrouwen. De bestaande `ProgramGuideGridView.focusSearch()` stuurt Omhoog
vanaf de eerste rij bewust eerst naar "Nu live"; vanaf daar is Rechts de stabiele route naar het
menu.

Alternatieve, snellere ingang voor gevorderden: lang indrukken van OK op een zenderlogo in het
grid opent het ordenscherm rechtstreeks met die zender voorgeselecteerd, buiten het menu om.
**Dit vereist eerst `CAN_FOCUS_CHANNEL = true`**, die in `ProgramGuideFragment` standaard `false`
is en door `NexusProgramGuideFragment` niet wordt overschreven — de zenderkolom is in de huidige
app dus helemaal niet focusbaar, en een long-press erop is nu onbereikbaar. `onChannelClicked()`
wordt daarom vandaag ook nooit aangeroepen. Het aanzetten van die vlag verandert de horizontale
focusroute van het hele grid en is daarmee een eigen wijziging met eigen regressierisico; strikt
optioneel, en pas ná de basisfunctie. Het is expliciet **geen** vervanging van de menu-ingang.

### Navigatie

`MainActivity` zet het gidsfragment nu met één `replace(...).commitNow()` en gebruikt nog geen
backstack. Het ordenscherm komt erbovenop:

```kotlin
val channelOrderFragment = ChannelOrderFragment.newInstance(currentDate)
supportFragmentManager.beginTransaction()
    .add(R.id.main_container, channelOrderFragment, "channel_order")
    .hide(guideFragment)
    .setMaxLifecycle(guideFragment, Lifecycle.State.STARTED)
    .setPrimaryNavigationFragment(channelOrderFragment)
    .addToBackStack("channel_order")
    .commit()
```

**`add` + `hide`, niet `replace`.** `replace()` vernietigt de view van het gidsfragment; bij
terugkeer wordt die volledig opnieuw opgebouwd, met een nieuwe `Loading`-staat, een nieuwe
grid-scroll en een nieuwe focusbepaling. Dat is precies het soort zichtbare hapering dat deze
functie niet zou moeten introduceren. Met `add`+`hide` blijft het grid intact en hoeft er bij
terugkeer alleen opnieuw te worden gebonden. `hide()` alleen verandert de fragment-lifecycle
echter niet: zonder `setMaxLifecycle(..., STARTED)` blijft het onzichtbare grid timers draaien en
bij elke D-pad-verplaatsing adapters verversen. Laat de UI-collector in
`NexusProgramGuideFragment` daarom vanaf `Lifecycle.State.RESUMED` verzamelen; de ViewModel blijft
de nieuwste voorkeur bezitten, maar het grid rendert die na terugkeer precies één keer.

De root van `fragment_channel_order.xml` vult de container volledig, heeft een ondoorzichtige
achtergrond en vangt focus/clicks op. Zo kan de bewaarde gidsview niet door het ordenscherm heen
te zien zijn of invoer ontvangen.

Om dit te laten werken moet `MainActivity` het gidsfragment kunnen terugvinden. Geef de eerste
transactie daarom een tag (`.replace(R.id.main_container, NexusProgramGuideFragment(), "guide")`)
en zoek hem later op met `findFragmentByTag("guide")`. Dat is een kleine wijziging in
[`MainActivity.kt`](../android/app/src/main/java/com/nexustvguide/app/ui/MainActivity.kt) die nu
meteen moet, want zonder tag is er geen handvat.

Maak `showChannelOrder(date)` idempotent: negeer de actie als een transactie al onderweg is, als
`findFragmentByTag("channel_order")` al iets vindt of als `FragmentManager.isStateSaved` waar is.
Hiermee kunnen key-repeat en een dubbele OK geen twee ordenschermen of twee backstack-items
maken. Houd `navigationPending` waar zolang het scherm open of de transactie pending is en wis
hem via een backstack-/fragment-callback na sluiten, of direct wanneer `commit()` niet kan worden
gestart. Gebruik hier geen `runOnCommit`: die mag niet worden gecombineerd met
`addToBackStack()`.

Maak de terugkeer expliciet met een fragmentresultaat, bijvoorbeeld `channel_order_closed`, dat
vóór de pop wordt gezet. Daarmee markeert het gidsfragment de interne terugkeer; na resume
rendert de herstartende collector de nieuwste `uiState` één keer en focust daarna de laatst
geselecteerde nog zichtbare channel-id (anders de eerste zichtbare zender). Het bestaande
externe-terugkeerpad wordt voor deze interne navigatie eenmalig overgeslagen.
Dat pad in `NexusProgramGuideFragment.onResume()` doet nu namelijk:

- staat de app op een andere dag dan vandaag → `selectToday()`;
- staat hij op vandaag → `jumpToLive(focus = true)` **en** `requestRefresh()`.

Dat gedrag is bedoeld voor terugkeer uit NLZiet of naar de app, maar zou bij een intern scherm de
gekozen dag veranderen en onnodig netwerkverkeer veroorzaken. Het fragmentresultaat zet daarom
een eenmalige vlag vóór `onResume`; die slaat alleen de extra logica van de subclass over. De
`ProgramGuideFragment`-basisklasse springt op vandaag bij resume nog steeds naar live, conform
het bestaande librarygedrag. Door `setMaxLifecycle` is `onPause`/`onResume` hier deterministisch;
we vertrouwen niet op `hide()` om lifecycle-callbacks te veroorzaken.

De `observe()`-flow blijft hoe dan ook de dragende route: die zorgt dat de nieuwe volgorde wordt
toegepast ook als er geen netwerk is, want de resolver werkt op de al opgehaalde snapshot en niet
op een nieuwe netwerkronde.

### Toepassing in de ViewModel

`GuideViewModel` krijgt de resolver ertussen:

```kotlin
val ordered = ChannelOrderResolver.apply(guideResponse.channels, orderPrefs)
val channels: List<ProgramGuideChannel> = ordered.map { SimpleChannel(...) }
```

en geeft uiteindelijk alleen schedulelijsten van zichtbare zenders aan `setData()` door.

Parseer bij een voorkeurswijziging niet opnieuw alle programma-instants. Splits de huidige mapping
in twee fasen:

1. `prepareGuide(response, date)` valideert/parset elk programma één keer en maakt een
   `PreparedGuide` met backendchannels en schedules per channel-id.
2. `projectGuide(prepared, prefs)` past de resolver toe en selecteert alleen de schedulelijsten
   voor de zichtbare ids. Deze goedkope projectie wordt door het netwerkpad én de
   `orderRepository.observe()`-collector gebruikt.

Bewaar de laatst voorbereide gids samen met zijn `LocalDate`. Een voorkeursevent mag alleen die
snapshot projecteren als hij nog bij de actieve, reeds geladen datum hoort; tijdens `Loading` van
een andere dag wordt alleen de nieuwste voorkeur onthouden en pas op het nieuwe resultaat
toegepast. Annuleer bovendien de vorige voorgrond-load of gebruik een oplopend request-token,
zodat twee dagrequests die buiten volgorde afronden niet alsnog de verkeerde dag tonen. Prefetch
blijft hiervan losstaan en schrijft nooit naar `uiState`.

### Lege staat bij "alles verborgen"

Dit is de plek waar deze functie het makkelijkst een regressie introduceert. `loadGuideForDate()`
zet nu `GuideUiState.Error("Geen zenders of programmadata beschikbaar voor ...")` zodra
`guideResponse.channels` leeg is. Als de resolver een lege lijst teruggeeft omdat de gebruiker
alles heeft verborgen, zou de app dus een *foutmelding* tonen voor een normale instelling — en
die foutstaat heeft geen enkele route terug naar het ordenscherm, dus de gebruiker zit vast.

De controle moet daarom worden gesplitst:

- `guideResponse.channels` leeg (backend/netwerk) → `GuideUiState.Error`, zoals nu.
- `guideResponse.channels` niet leeg maar `ordered` leeg (alles verborgen) → een nieuwe
  `GuideUiState.AllChannelsHidden(date, isStale)`.

`GuideUiState` is een `sealed class`, dus een nieuwe variant dwingt de `when` in
`NexusProgramGuideFragment.onViewCreated()` af om die af te handelen — de compiler bewaakt dat
dit niet vergeten wordt. De fragmentafhandeling toont `State.Error` met de eigen tekst "Alle
zenders zijn verborgen" en zet de focus op de hamburgerknop; OK opent het bestaande menu met de
route naar het ordenscherm. Open het ordenscherm niet automatisch vanuit de state-collector:
dat kan bij configuratieherstel of een herhaalde emissie dubbele backstack-items en een
open-dicht-lus veroorzaken.

Voor de stapsgewijze implementatie toont stap 2 alleen de eigen tekst; stap 4 voegt de
hamburger-id en daarmee de focusroute toe. Zo blijft elke tussenstap compileerbaar.

Omdat `AllChannelsHidden` zijn datum draagt, behandelt de loading-conditie deze staat voor
dezelfde datum net als `Content`: een gewone refresh toont geen tussentijdse spinner. Een echte
lege backendlijst blijft `GuideUiState.Error`.

## Implementatiestappen

### Stap 1 — Datamodel, opslag en resolver

Bestanden:

- `android/app/src/main/java/com/nexustvguide/app/data/model/ChannelOrderPreferences.kt` (nieuw)
- `android/app/src/main/java/com/nexustvguide/app/data/repository/ChannelOrderRepository.kt` (nieuw)
- `android/app/src/main/java/com/nexustvguide/app/data/ChannelOrderResolver.kt` (nieuw)
- `android/app/build.gradle` (aangepast — expliciete Gson-dependency)

Acceptatiecriteria:

- `ChannelOrderResolver` is een `object` zonder Android-afhankelijkheden, zodat het met een
  gewone JUnit-test getest kan worden (geen Robolectric nodig). Concreet: geen `Log`, geen
  `Context`, geen resources — die zouden de test naar Robolectric dwingen.
- Alle randgevallen uit de tabel hierboven hebben een test in
  `app/src/test/java/com/nexustvguide/app/data/ChannelOrderResolverTest.kt`.
- `ChannelOrderRepository.load()` op een lege of corrupte prefs geeft de defaults terug en
  logt op `Log.w`, en gooit niet. Een catch op `JsonParseException` dekt ook
  `JsonSyntaxException`; valideer daarnaast het gedeserialiseerde object op `null`, versie en
  niet-null collecties/elementen.
- `observe()` registreert vóór de initiële `trySend`, mist geen wijziging tussen registratie en
  eerste waarde, reageert na de eerste wijziging ook op een tweede en registreert na cancel geen
  callbacks meer.
- Gson `2.10.1` staat expliciet in `app/build.gradle` en vervangt alleen de bestaande transitieve
  declaratie, zonder dependency-upgrade.
- Deze stap raakt geen bestaande bestanden behalve `build.gradle`: het gedrag van de app is na
  stap 1 aantoonbaar ongewijzigd.

### Stap 2 — Toepassen in het gidsscherm

Bestanden:

- `ui/GuideViewModel.kt` (aangepast — resolver, `PreparedGuide`, projectie en nieuwe
  uiState-variant)
- `ui/NexusProgramGuideFragment.kt` (aangepast — `when` uitbreiden met `AllChannelsHidden`)

Acceptatiecriteria:

- Zonder opgeslagen voorkeur is het grid pixel-identiek aan de huidige situatie: zelfde rijen,
  zelfde volgorde, zelfde beginfocus.
- Met een voorkeur staan de rijen in die volgorde, en verborgen zenders ontbreken.
- Een wijziging in `ChannelOrderRepository` leidt tot een nieuwe `GuideUiState.Content` zonder
  netwerkverkeer (te controleren met de OkHttp-logginginterceptor).
- Alles verborgen geeft `GuideUiState.AllChannelsHidden`, nooit `GuideUiState.Error`; een lege
  backendlijst geeft nog steeds `GuideUiState.Error`.
- Programma's worden per netwerk-/cache-response maximaal één keer geparset; een
  voorkeurswijziging voert alleen `projectGuide()` uit.
- Een voorkeursevent tijdens het laden van een andere dag zet nooit de vorige dag terug in
  `uiState`; buiten volgorde afgeronde voorgrondrequests kunnen de nieuwste dag niet
  overschrijven.
- Prefetch van aangrenzende dagen blijft ongewijzigd werken en wordt niet opnieuw afgevuurd door
  een voorkeurswijziging.

### Stap 3 — Ordenscherm

Bestanden:

- `ui/ChannelOrderFragment.kt` (nieuw)
- `ui/ChannelOrderViewModel.kt` (nieuw)
- `ui/ChannelOrderAdapter.kt` (nieuw)
- `res/layout/fragment_channel_order.xml`, `res/layout/item_channel_order.xml` (nieuw)
- `res/values/strings.xml` (aangevuld, Nederlandse teksten — geen hardcoded strings in code)
- `ui/MainActivity.kt` (aangepast — tag op het gidsfragment, `add`+`hide`-navigatie)
- `data/repository/GuideRepository.kt` (aangepast — expliciete diskfallback voor de zenderlijst)
- `app/build.gradle` (aangepast — rechtstreekse RecyclerView-dependency)

Het scherm heeft de volledige zenderlijst nodig, inclusief de verborgen zenders — de resolver
levert die juist niet. `ChannelOrderViewModel` haalt de ongefilterde lijst daarom apart op via
`GuideRepository`. Alleen `getChannels()` noemen is niet voldoende: de huidige app roept die
methode nergens aan, dus `channels.json` kan bij de eerste offline opening ontbreken terwijl een
gids-snapshot wél beschikbaar is. Voeg een expliciete `getChannelsForOrdering(date)`-route toe:
probeer de endpoint/schijfcache en val bij een lege uitkomst terug op de reeds opgeslagen
gids-snapshot voor de geselecteerde datum, zonder die snapshot opnieuw via het netwerk op te
halen. Geef de datum als ISO-string in een fragmentargument door, niet als een alleen in-memory
veld, zodat fragmentherstel na proces-dood dezelfde fallback kan vinden. Dat is de reden dat het ordenscherm een eigen
ViewModel krijgt en niet op de gefilterde `GuideUiState.Content.channels` leunt.

Een lege uitkomst is `ChannelOrderUiState.Error` met "Opnieuw" en "Terug", niet een bewerkbare
lege lijst. In die toestand mogen `save()` en `reset()` nooit worden aangeroepen: een ontbrekende
cache of backendfout mag geen geldige voorkeur wissen. De directe RecyclerView-dependency wordt
vastgezet op de reeds transitief aanwezige versie `1.0.0`, zodat deze stap geen impliciete
library-upgrade uitvoert.

Acceptatiecriteria:

- Volledig bedienbaar met alleen D-pad + OK + Terug; muis/touch is niet vereist.
- Een verplaatsing is zichtbaar binnen één frame en de rij houdt de focus vast tijdens het
  verplaatsen (`setHasStableIds(true)` en `notifyItemMoved`, geen `notifyDataSetChanged`). Omdat
  `RecyclerView.getItemId()` een `Long` vereist, kent de adapter elk channel-id voor de duur van
  het scherm een unieke, stabiele `Long` toe; gebruik niet alleen `String.hashCode()`, want twee
  channel-id's kunnen botsen.
- Terwijl een zender is opgepakt, onderschept het scherm op/neer vóór de `RecyclerView` ze als
  scroll interpreteert; gebruik daarvoor een `OnKeyListener` op de holder die `true` teruggeeft
  zodra hij de toets heeft afgehandeld.
- Opgepakte staat is onmiskenbaar anders dan alleen-gefocust.
- De lijst toont alle backendzenders, ook de verborgen; verborgen rijen zijn gedimd en blijven
  selecteerbaar, zodat ze terug te zetten zijn.
- "Herstel standaardvolgorde" vraagt om bevestiging en herstelt zowel volgorde als verborgen
  zenders. De bevestiging gebruikt hetzelfde AppCompat-dialoogthema als de filters
  (`Theme.NexusTVGuide.Dialog`), niet een kaal `AlertDialog`.
- Het scherm laat zich niet in een toestand achter waarin álle zenders verborgen zijn zonder dat
  de gebruiker dat expliciet bevestigt; anders belandt hij na Terug meteen in de lege staat.
- Wijzigingen worden bij elke handeling opgeslagen, niet pas bij "Klaar" — de gebruiker kan de
  Shield uitzetten zonder werk kwijt te raken. "Klaar" is dus puur een navigatieknop en doet
  hetzelfde als Terug.
- Een zichtbaarheidstoggle wijzigt alleen `hiddenIds`; alleen na een echte verplaatsing wordt de
  getoonde volgorde naar `orderedIds` geschreven en van ontbrekende id's opgeschoond. Een no-op
  (openen en "Klaar") schrijft niets.
- Terug tijdens `grabbed` herstelt én persisteert de oorspronkelijke volgorde; Terug in normale
  toestand en "Klaar" zetten eerst `channel_order_closed` en poppen daarna de backstack.
- Een lege of mislukte zenderload laat bestaande voorkeuren byte-voor-byte ongemoeid.
- Twee snelle OK-events openen maximaal één ordenscherm en voegen maximaal één backstack-item
  toe.

### Stap 4 — Hamburgermenu in de kopbalk (library-aanpassing)

Bestanden:

- `library/src/main/res/layout/programguide_fragment.xml` (aangepast — knop toevoegen, constraint
  van de datumcontainer verleggen)
- `library/src/main/res/drawable/programguide_ic_menu.xml` (nieuw — vectoricoon)
- `library/src/main/res/values/dimens.xml` (aangepast — bestaande datumafstand als dimensie)
- `library/src/main/java/com/egeniq/androidtvprogramguide/ProgramGuideFragment.kt` (aangepast —
  `DISPLAY_MENU_BUTTON` en `onMenuButtonClicked(anchor)`)
- `app/src/main/java/com/nexustvguide/app/ui/NexusProgramGuideFragment.kt` (aangepast — vlag aan,
  menu opbouwen, navigatie naar `ChannelOrderFragment`)
- `library/src/main/res/values/strings.xml` en `app/src/main/res/values/strings.xml` (aangevuld;
  de library krijgt alleen `programguide_content_description_menu`, de menuteksten zitten in de app)

Er komt géén `res/menu/guide_overflow.xml`: het menu is een `AlertDialog` met een
string-array, niet een geïnflate menu-resource. Een menu-XML zou alleen bij `PopupMenu` horen, en
die route is hierboven verworpen.

Deze stap wordt bewust als laatste gedaan. Na stap 3 is de functie volledig testbaar — het
ordenscherm kan tijdelijk via een debug-ingang worden geopend — dus als stap 4 focusproblemen in
de kopbalk oplevert, staat de rest van de functie niet stil en kan de knop uit blijven.

Acceptatiecriteria:

- De knop is standaard `gone`; een schone integratie van de library verandert niet van uiterlijk.
  De gewone en gone-marge van de datumcontainer zijn beide exact 24dp via
  `programguide_header_date_spacing`; `programguide_filter_spacing` (8dp) is hier onjuist.
- De knop staat direct rechts van "Nu live" en de gidsdatum schuift mee op zonder de app-titel
  rechts te overlappen — ook bij de langste datumtekst. `programguide_header_guide_date_value`
  is `singleLine` met `wrap_content`, dus overlap toont zich als afgekapte of over elkaar heen
  vallende tekst, niet als een layoutfout; controleer visueel op 1920×1080.
- De focusroute in de kopbalk is links→rechts logisch: dagfilter → dagdeelfilter → Nu live →
  hamburgerknop, en van de knoprij omlaag naar het grid.
- Van het grid omhoog komt de focus terug op de knoprij (controleer op de eerste rij van het
  grid, waar `ProgramGuideGridView` de focus normaal vasthoudt).
- De dialoog opent met het eerste item gefocust, zodat OK direct werkt zonder eerst omlaag te
  moeten — hetzelfde gedrag als de bestaande dag- en dagdeelfilters.
- Terug in de geopende dialoog sluit hem en geeft de focus terug aan de hamburgerknop, niet aan
  het grid en niet aan de app-achtergrond.
- Na de navigerende menukeuze probeert `setOnDismissListener` niet de inmiddels verborgen
  hamburgerknop opnieuw te focussen.
- Het menu is opgebouwd in de app-laag; de library kent de menu-inhoud niet en compileert
  onveranderd zonder de app-module.
- Een subclass met `OVERRIDE_LAYOUT_ID` zonder de nieuwe menu-id crasht niet wanneer de
  standaardvlag `false` blijft.
- De dialoog crasht niet op `Theme.Leanback`; dit is de concrete regressie die eerder met de
  filterdialoog is opgetreden en die het themafix in `styles.xml` afdekt.

### Stap 5 — Randgevallen en polish

Acceptatiecriteria:

- Alle zenders verborgen → duidelijke lege staat in het grid met directe route naar het
  ordenscherm, geen `GuideUiState.Error`.
- Backend voegt een zender toe → die verschijnt naast zijn backend-buurman tussen de bestaande
  zenders (handmatig te testen door `channels.json` uit te breiden en de backend te herstarten).
- Backend verwijdert een zender en voegt hem later weer toe → hij staat weer op zijn oude plek,
  mits de gebruiker er tussendoor niets heeft opgeslagen.
- Offline start met alleen de schijfcache → volgorde wordt gewoon toegepast.
- Verplaatsen van de bovenste rij omhoog of de onderste omlaag doet niets en geeft geen crash.
- Rotatie/configuratiewijziging en proces-dood tijdens het ordenen verliezen geen opgeslagen werk
  (volgt uit "opslaan bij elke handeling", maar moet één keer echt geprobeerd worden).

## Testplan

Geautomatiseerd (`./gradlew :app:testDebugUnitTest`):

- `ChannelOrderResolverTest` — elke rij uit de randgevallentabel, plus twee property-achtige
  tests: (a) het resultaat bevat altijd exact de verzameling backend-id's minus de verborgen
  id's, en (b) met een lege voorkeur is het resultaat element-voor-element gelijk aan de
  backendlijst. Die tweede test is de regressiebewaking voor "gedrag ongewijzigd zonder
  voorkeur" en hoort er vanaf stap 1 in te zitten.
- `ChannelOrderRepositoryTest` (Robolectric, met dezelfde `@Config(sdk = [28])` als
  `NexusProgramGuideFocusTest`) — opslaan/laden/reset, herstel na corrupte JSON, JSON `null`,
  onbekende versies en null velden; daarnaast dat `observe()` de huidige waarde en meerdere
  wijzigingen aflevert en na cancel stil blijft.
- `ChannelOrderViewModelTest` met fake repositories — alleen verbergen laat `orderedIds`
  ongemoeid, een echte verplaatsing schrijft de getoonde volgorde, annuleren van `grabbed`
  herstelt die volgorde, en een lege/mislukte zenderload schrijft niets.
- `GuideViewModelTest` met fake repositories — voorkeur wijzigen doet geen netwerkcall,
  programma's worden niet opnieuw geparset, een voorkeur tijdens een dagwissel zet de oude dag
  niet terug en een ouder request mag een nieuwer afgerond request niet overschrijven. Houd de
  repository- en projectiedependencies daarom injecteerbaar via een interne constructor/factory;
  productie blijft ze vanuit `Application` maken.

Wat hier bewust **niet** staat: een uitbreiding van de bestaande focustest voor de hamburgerknop.
`NexusProgramGuideFocusTest` test `ProgramGuideUtil.findNextFocusedProgram` op losse
`ProgramGuideItemView`s; het inflate't de kopbalk niet en heeft geen fragment of layout. De knop
in de kopbalk is er niet mee te testen zonder er een heel ander soort test van te maken. De
focusroute van de kopbalk wordt daarom handmatig op de Shield gecontroleerd (scenario 1 en 8
hieronder), en dat is de reden dat stap 4 als laatste staat.

Handmatig op de Shield:

1. Open het hamburgermenu met de D-pad, kies "Zenders ordenen"; sluit het menu daarna een keer
   met Terug zonder te kiezen → focus staat weer op de hamburgerknop.
2. Verplaats de onderste zender naar boven, sluit de app volledig af, start opnieuw → volgorde blijft.
3. Wissel van dag heen en terug → volgorde blijft.
4. Verberg drie zenders, controleer dat hun programma's ook uit het detailpaneel verdwijnen.
5. Herstel standaardvolgorde → identiek aan een schone installatie.
6. Trek de netwerkstekker eruit, herstart, controleer dat de volgorde ook op de schijfcache klopt.
7. Open NLZiet vanuit het grid en kom terug → volgorde en focus nog intact.
8. Kom terug uit het ordenscherm → het grid staat er meteen, zonder zichtbare laadstaat, en de
   gekozen dag is behouden, er is geen extra gidsrequest en de focus staat op de eerder gekozen
   zender of, als die verborgen is, op de eerste zichtbare rij.
9. Verberg álle zenders → de lege staat verschijnt, geen foutmelding, en er is een route terug
   naar het ordenscherm; herhaald Terug/openen maakt geen dubbele backstack-items.
10. Vul alleen een gids-snapshotcache, verwijder de losse `channels.json`-cache en start offline →
    het ordenscherm gebruikt de channels uit de gids-snapshot en blijft bewerkbaar.
11. Open het ordenscherm zonder netwerk en zonder bruikbare cache → foutstaat met Opnieuw/Terug;
    bestaande voorkeuren blijven exact behouden.

Scenario 6 controleert het toepassen van voorkeuren op de gids-cache; scenario 10 controleert
apart dat bewerken niet afhankelijk is van een eerder gevulde `/channels`-cache. Zo wordt de
feitelijke koude-startsituatie afgedekt die de huidige app oplevert.

## Risico's

| Risico | Kans | Mitigatie |
|---|---|---|
| Focus springt weg tijdens verplaatsen in de `RecyclerView` | Middel | `notifyItemMoved` + stabiele id's; expliciet `requestFocus()` op de verplaatste holder na de animatie |
| Gebruiker vindt de opgepakt-modus niet | Middel | Permanente hulpregel die van tekst wisselt; geen verborgen long-press als enige route |
| Hamburgerknop verstoort bestaande focusroutes in het grid | Middel | Expliciete links/rechts-focus-id's, knop standaard `gone`, stap 4 apart houden en scenario 1 en 8 op de Shield |
| Dialoog crasht op `Theme.Leanback` (geen AppCompat-thema) | Middel | Al opgelost voor de filters via `alertDialogTheme` in `styles.xml`; hergebruik exact dat pad en gebruik geen `PopupMenu` |
| Menu met één regel voelt als omweg | Laag | Bewuste keuze met het oog op latere opties |
| Verborgen gids blijft renderen en vertraagt D-pad-verplaatsingen | Middel | `setMaxLifecycle(STARTED)` + UI verzamelen vanaf `RESUMED`; na terugkeer slechts één projectie renderen |
| Voorkeur loopt uit de pas met een sterk gewijzigde backendlijst | Laag | Overlaymodel met invoegregel; voorkeur wordt door de resolver nooit gewist |
| Verborgen zenders verwarren bij een lege gids | Laag | Aparte `AllChannelsHidden`-staat met directe ingang naar het ordenscherm; de `sealed class` dwingt afhandeling af |
| `SharedPreferences`-listener wordt weggeruimd, updates blijven uit | Laag | Sterke referentie in de `callbackFlow` tot `awaitClose`; testen dat `observe()` een tweede wijziging nog steeds levert |
| Lege/onvolledige channelcache wist een geldige voorkeur | Middel | Lege load is niet bewerkbaar en schrijft nooit; diskfallback naar de reeds geladen gids-snapshot |
| Voorkeursevent of ouder request zet de verkeerde dag terug | Middel | `PreparedGuide` aan datum koppelen en voorgrondloads annuleren of met request-token bewaken |
| Stable-id-hash botst en RecyclerView verwisselt focus | Laag | Unieke `Long`-toewijzing per channel-id, niet alleen `String.hashCode()` |

## Definition of Done

- Volgorde en zichtbaarheid zijn met alleen de afstandsbediening in te stellen en blijven
  bewaard over herstarts, dagwissels en offline starts.
- Zonder ingestelde voorkeur is het gedrag identiek aan de huidige app — vastgelegd in een
  resolvertest én visueel gecontroleerd op de Shield.
- De library compileert en oogt onveranderd zonder de app-module (knop `gone`, gone-marge klopt).
- Nieuwe zenders uit de backend verschijnen automatisch op een zinnige positie.
- Unit tests uit het testplan draaien groen (`./gradlew :app:testDebugUnitTest`); de elf
  handmatige scenario's zijn op de Shield afgevinkt.
- `plan-2026-08-30-bouwplan.md` verwijst naar dit document. Let op: besluit 5 daar
  ("Volgorde: backend eerst, daarna Android") gaat over de *bouwvolgorde* van het project, niet
  over de zendervolgorde, en hoeft dus niet te worden aangepast — voeg in plaats daarvan een
  verwijzing naar dit plan toe in fase 2.

## Vervolgstappen na deze functie

- Volgorde exporteren naar de backend, zodat de XMLTV-output voor Jellyfin/TiviMate dezelfde
  volgorde krijgt.
- Groepen of favorietenlijsten (schemaversie 2 van `ChannelOrderPreferences`).
- Een "recent gekeken bovenaan"-modus als alternatief voor handmatig ordenen.

## Revisie 2026-08-31 — wijzigingen na codeverificatie

Dit plan is tegen de bronbestanden gecontroleerd. Wat er inhoudelijk is veranderd, en waarom:

| Wijziging | Reden |
|---|---|
| `PopupMenu` → `AlertDialog` (besluit 6b) | `Theme.NexusTVGuide` is geen AppCompat-thema; de filterdialoog is daar eerder al op gecrasht, met een expliciete opmerking in `styles.xml` |
| `replace()` → `add`+`hide` + lifecyclegrens (besluit 9) | `replace()` vernietigt de view, maar alleen `hide()` laat het onzichtbare grid actief doorrenderen; `STARTED`/`RESUMED` bewaart de view zonder achtergrond-rebinds |
| Samenvoegregel herschreven | De oude regel 3 vergeleek `sortOrder`-getallen en liet in het midden hoe meerdere nieuwe zenders zich onderling verhouden |
| `AllChannelsHidden` als eigen uiState | `loadGuideForDate()` maakt van een lege lijst nu een `GuideUiState.Error` zonder route terug — "alles verborgen" zou de gebruiker vastzetten |
| `commit()` → `apply()` in de opslag | De oude formulering ("synchroon via `commit()` op IO-dispatcher") was tegenstrijdig en maakte `save()` onnodig `suspend` |
| Focustest-uitbreiding geschrapt | `NexusProgramGuideFocusTest` test een util-functie op losse views en inflate't de kopbalk niet; de knop is er niet mee te testen |
| Long-press op zenderlogo gemarkeerd als geblokkeerd | `CAN_FOCUS_CHANNEL` staat op `false`, dus de zenderkolom is nu niet focusbaar |
| Gone-marge op de datumcontainer gecorrigeerd naar 24dp | `programguide_filter_spacing` is 8dp en zou de bestaande datumafstand verkleinen; de huidige hardcoded marge is 24dp |
| Expliciete Gson- en RecyclerView-dependencies | Beide worden rechtstreeks door de nieuwe app-code gebruikt; de al resolved versies blijven behouden |
| Opslaan per gebruikersintentie gescheiden | Alleen verbergen mag de backendvolgorde niet stilzwijgend als eigen volgorde bevriezen; onbekende verborgen ids blijven behouden |
| Programmaparsing gesplitst van voorkeurprojectie | Anders worden alle programma's bij iedere D-pad-verplaatsing opnieuw geparset |
| Offline bron en lege-loadgedrag aangescherpt | De huidige app vult de losse channelcache nog niet; een lege cache mag nooit geldige voorkeuren overschrijven |
| Terugkeer uit ordenscherm expliciet gemaakt | `hide()` triggert geen lifecycle-callbacks; een fragmentresultaat voorkomt tevens dagreset en onnodig netwerkverkeer |
| DoD over besluit 5 gecorrigeerd | Besluit 5 in het bouwplan gaat over bouwvolgorde, niet over zendervolgorde |
