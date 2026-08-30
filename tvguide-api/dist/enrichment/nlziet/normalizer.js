/**
 * Normalisatie-functies voor Nederlandse programmatitels.
 */
/**
 * Zet een ruwe programmatitel om naar een schone, genormaliseerde zoekstring.
 */
export function normalizeDutchTitle(title) {
    if (!title)
        return '';
    let cleaned = title.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
    cleaned = cleaned.toLowerCase();
    // Vervang & en + door "en"
    cleaned = cleaned.replace(/&/g, ' en ').replace(/\+/g, ' en ');
    // Verwijder tekst tussen ronde en vierkante haken indien het uitzendmeta betreft
    cleaned = cleaned.replace(/\((?:herhaling|live|premiere|première|afl[.\s]*\d+|seizoen\s*\d+|compilatie|special|samenvatting|met gebarentaal|in makkelijke taal|hh)[^)]*\)/gi, ' ');
    cleaned = cleaned.replace(/\[(?:herhaling|live|premiere|première)[^\]]*\]/gi, ' ');
    // Verwijder tijdstip-aanduidingen (bijv. "20:00", "18.00", "19:30 uur", "08.00u")
    cleaned = cleaned.replace(/\b\d{1,2}[:.]\d{2}(?:\s*uur|\s*u)?\b/gi, ' ');
    // Verwijder veelvoorkomende losse termen
    cleaned = cleaned.replace(/\b(?:met gebarentaal|in makkelijke taal)\b/gi, ' ');
    // Verwijder leestekens
    cleaned = cleaned.replace(/['"`()[\]{}:!?,.;/\\|*#~^]/g, ' ');
    // Meerdere spaties samenvoegen en trimmen
    cleaned = cleaned.replace(/\s+/g, ' ').trim();
    return cleaned;
}
/**
 * Converteert een programmatitel naar een URL-veilige slug die overeenkomt met de NLZIET slug conventie.
 */
export function slugifyTitle(title) {
    const normalized = normalizeDutchTitle(title);
    if (!normalized)
        return '';
    // Vervang alle niet-alfanumerieke karakters door hyphens
    let slug = normalized.replace(/[^a-z0-9]+/g, '-');
    slug = slug.replace(/^-+|-+$/g, '');
    return slug;
}
