import he from 'he';
const VALID_AGE_RATINGS = new Set(['AL', '6', '9', '12', '14', '16', '18']);
/**
 * Verwijdert HTML tags en decodeert HTML entities (zoals &amp;, &eacute;, &#039;, &euro;).
 */
export function htmlToText(html) {
    if (!html)
        return null;
    // Strip tags (e.g. <p>, <br>, <html>, etc.)
    const stripped = html.replace(/<[^>]*>/g, ' ');
    // Decode HTML entities
    const decoded = he.decode(stripped);
    // Normalize whitespace
    const normalized = decoded.replace(/\s+/g, ' ').trim();
    return normalized.length > 0 ? normalized : null;
}
/**
 * Normaliseert Kijkwijzer rating ('ei' veld).
 * Alleen 'AL', '6', '9', '12', '14', '16', '18' zijn geldig.
 * Ongeldige waarden zoals 'H', 'live', 'tip', '' worden genormaliseerd naar null.
 */
export function normalizeAgeRating(rawEi) {
    if (!rawEi)
        return null;
    const cleaned = rawEi.trim().toUpperCase();
    if (VALID_AGE_RATINGS.has(cleaned)) {
        return cleaned;
    }
    return null;
}
