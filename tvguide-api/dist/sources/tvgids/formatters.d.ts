/**
 * Verwijdert HTML tags en decodeert HTML entities (zoals &amp;, &eacute;, &#039;, &euro;).
 */
export declare function htmlToText(html?: string | null): string | null;
/**
 * Normaliseert Kijkwijzer rating ('ei' veld).
 * Alleen 'AL', '6', '9', '12', '14', '16', '18' zijn geldig.
 * Ongeldige waarden zoals 'H', 'live', 'tip', '' worden genormaliseerd naar null.
 */
export declare function normalizeAgeRating(rawEi?: string | null): string | null;
