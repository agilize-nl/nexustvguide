# Nexus TV Gids — Logo en Branding

Drie visuele richtingen voor de nieuwe app-identiteit:

1. `concept-1-nexus-beam.png` — Nexus-monogram met play-beweging (**Geselecteerd**).
2. `concept-2-live-grid.png` — EPG-programmablokken met een live-lijn.
3. `concept-3-focus-play.png` — D-pad/focuskader rond playback.

## Geselecteerd ontwerp: Nexus Beam (Concept 1)

**Concept 1 (Nexus Beam)** is het officiële app-icoon en merklogo van NexusTVGuide.

- **Karakteristieken**: Een elektrisch blauwe 'N'-monogramstructuur met play-driehoek in warm amber op een diepe donkere achtergrond (`#0F1217`).
- **Productie-assets**:
  - Legacy app launcher iconen: `android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`
  - Ronde iconen: `android/app/src/main/res/mipmap-*/ic_launcher_round.png`
  - Adaptive icon foreground laag (108dp canvas met safe zone): `android/app/src/main/res/mipmap-*/ic_launcher_foreground.png`
  - Android TV Leanback Banners (16:9 banner 320x180 dp): `android/app/src/main/res/drawable-*/tv_banner.png`
- **Asset Generator Script**:
  - `tools/generate_app_branding_assets.py` genereert alle resoluties direct vanuit `docs/logo-concepts/concept-1-nexus-beam.png`.
