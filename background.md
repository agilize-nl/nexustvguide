# Achtergrond (historisch)

> Dit bestand bewaart de oorspronkelijke vraag en verkenning en is **geen actuele
> projectspecificatie**. Sommige aannames hieronder zijn na verificatie onjuist gebleken.
> Gebruik [README.md](README.md) voor het overzicht en
> [docs/PLAN.md](docs/PLAN.md) voor actuele besluiten, contracten en werkvolgorde.

Bekende achterhaalde punten in de ruwe tekst:

- de gekozen egeniq-UI gebruikt Leanback/Views, niet Compose for TV;
- de primaire bron is rechtstreeks `json.tvgids.nl/v4`, niet een XMLTV-grabber;
- de iptv-org-site heet `tvgids.nl`, niet `tvgids.tv`;
- tvgrabpyAPI geldt niet meer als actief onderhouden;
- de werkvolgorde is backend, Android-app en pas daarna NLZiet-deeplinks.

---

## Oorspronkelijke conversatie

ik heb NLZiet als tv-app op mijn shield, maar ik vind de tvgids van nlziet niet gebruiksvriendelijk. ik zoek naar een goed alternatief als app (zonder reklame) voor op de shield. als deze niet bestaat wil ik deze zelf bouwen . wat zijn de mogelijkheden
Kort antwoord: die app bestaat niet echt
Alle Nederlandse tvgids-apps (TVgids.nl, TVGiDS.tv, Cisana TV+) zijn telefoon-apps met banners — geen Leanback/D-pad-navigatie, dus op de Shield onwerkbaar. De enige goede EPG-grids op Android TV zitten in IPTV-clients (TiviMate, OTT Navigator, Kodi PVR), en die verwachten een M3U met streams — niet een gids die naar NLZiet doorlinkt.

Zelf bouwen — drie routes
1. TIF (TvInputService) — mooiste integratie
Je schrijft een TvInputService die kanalen + programma's in de systeem-content-provider zet. Je krijgt dan de native Google TV / Live Channels-gids gratis, inclusief remote-navigatie en "volgende aflevering"-kaarten op het homescreen. Nadeel: het framework verwacht dat jij playback levert. Doorschakelen naar NLZiet moet dan via een intent — technisch mogelijk maar buiten het TIF-model.

2. Eigen Compose for TV-app — meeste controle
Standaard Android TV-app met je eigen EPG-grid (LazyRow per zender op een tijdsas), favorieten, filters. Doorklikken = Intent naar NLZiet. Dit is waarschijnlijk je snelste route naar iets bruikbaars.

3. Web-app als tweede scherm — laagste inspanning
Zelfgehoste PWA op je homelab, gids op tablet/telefoon, met een knop die via ADB of Shield-remote de juiste zender start.

Datalaag (past bij je stack)
iptv-org/epg — Node/TypeScript, npm run grab --- --sites=tvgids.tv, cron via chronos, en het heeft een ingebouwde serve-modus. Kant-en-klare NL-guide: iptv-org.github.io/epg/guides/nl/tvgids.tv.epg.xml
tvgrabpyAPI (opvolger van tvgrabnlpy) — Python, combineert TVgids.nl, NPO.nl, Horizon.tv; meer detail (seizoen/aflevering) maar zwaarder onderhoud.
Draai de grabber in een LXC, parse XMLTV naar een genormaliseerde JSON-API, en laat de app daar tegenaan praten. Dan is je client dom en je gids cachebaar.

De echte onbekende: deeplinken naar NLZiet
Dit maakt of breekt het idee. Check eerst of NLZiet intent-filters exporteert:

adb shell pm dump nl.nlziet | grep -A5 "android.intent.action.VIEW"
adb shell dumpsys package nl.nlziet | grep -i "filter\|scheme"
Als NLZiet App Links op nlziet.nl/... afhandelt, kun je vanuit je gids rechtstreeks een programma of live-zender openen. Zo niet, dan blijft het bij "app openen en zelf zoeken" — nog steeds beter dan hun gids, maar minder mooi.

Dat één commando is de eerste stap: zonder werkende deeplink is route 2 opeens een stuk minder aantrekkelijk dan route 3.

is er ergens een nl tvgids app op github


Searched the web
Searched the web
Geen kant-en-klare Nederlandse tvgids-app voor Android TV — maar wel alle bouwstenen, en ze passen verdacht goed op elkaar.

UI-laag (Android TV)
egeniq/android-tv-program-guide is je beste vondst. Het is een open-source EPG-implementatie specifiek voor Android TV, gebaseerd op de officiële Android TV channel browser, waarbij het ophalen van programmadata uit de kabelaansluiting eruit is gesloopt en vervangen door de mogelijkheid je eigen data aan te leveren; de code is omgezet naar Kotlin en AndroidX. Rij per zender, links/rechts door de tijd, dag-knoppen, jump-to-live. Egeniq is trouwens een Nederlandse club (Amsterdam), gebouwd voor NPO-achtige klussen.
GitHub

Alternatieven: korre/android-tv-epg en de Kotlin-fork abdlhay/android-epg — een "klassiek" EPG-grid met scrollen in alle richtingen, maar meer op tablet/telefoon gericht dan op D-pad.
GitHub

Datalaag (NL)
tvgrabbers/tvgrabpyAPI — actief onderhouden Python XMLTV-grabber; de bronnen staan als losse JSON-definities in tvgrabbers/sourcematching, inclusief json.tvgids.nl/v4/channels. Handig: die definities zijn leesbare specs van hoe je bij de data komt, ook als je zelf in TypeScript scrapt.
iptv-org/epg — Node/TS, --sites=tvgids.tv, en het serveert XMLTV zelf.
Oudere, vooral als referentie: janpascal/tv_grab_nl_java, taddeus/tvgids (PHP web-gids op de tvgids.nl API), Jorijn/tvgids.
Als referentie-implementatie
OwnTV — Kotlin + Compose for TV, ExoPlayer/mpv, met een volwaardig XMLTV-guidegrid en catch-up. Gebouwd rond M3U/Xtream dus niet direct bruikbaar, maar wel het beste voorbeeld van hoe zo'n grid remote-first werkt.

Wat dit betekent voor je scope
egeniq/android-tv-program-guide + een XMLTV→JSON-service in je LXC = grofweg 80% klaar. Wat overblijft is de zender-mapping en de deeplink naar NLZiet.

Eén ding om eerst te verifiëren: json.tvgids.nl/v4 is een ongedocumenteerde endpoint uit repo's van een paar jaar terug. Even curlen voordat je erop bouwt — als die dicht zit, is tvgids.tv via iptv-org je fallback.
