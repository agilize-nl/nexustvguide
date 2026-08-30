import { htmlToText, normalizeAgeRating } from './formatters.js';
/**
 * Zet een gevalideerd RawProgramme om naar het domeinmodel Programme.
 * Dit is de ENIGE plek in het project die de upstream vorm kent.
 */
export function mapProgramme(raw, channelId) {
    const startSec = Number(raw.s);
    const endSec = Number(raw.e);
    // Voorkeur voor beschrijving: algemene_inhoud -> inhoud -> htmlToText(descr)
    const desc = (raw.algemene_inhoud && raw.algemene_inhoud.trim().length > 0
        ? raw.algemene_inhoud.trim()
        : null) ||
        (raw.inhoud && raw.inhoud.trim().length > 0 ? raw.inhoud.trim() : null) ||
        (raw.descr ? htmlToText(raw.descr) : null);
    return {
        id: raw.db_id,
        channelId,
        title: raw.title?.trim() || '(Geen titel)',
        start: new Date(startSec * 1000).toISOString(),
        end: new Date(endSec * 1000).toISOString(),
        description: desc,
        imageUrl: raw.img?.trim() || null,
        genre: raw.subgenre?.trim() || null,
        isLive: raw.live === 'true',
        isRerun: raw.rerun === 'true',
        isPremiere: raw.is_premiere === 'true',
        ageRating: normalizeAgeRating(raw.ei),
    };
}
