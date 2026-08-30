export declare const TIME_ZONE = "Europe/Amsterdam";
/**
 * Berekent het exacte UTC [from, to) venster voor een lokale datum in Amsterdam.
 * Houdt automatisch rekening met 23-uurs en 25-uurs dagen bij zomertijdwissels.
 */
export declare function getLocalDayUtcWindow(dateStr: string): {
    from: string;
    to: string;
};
/**
 * Converteert een UTC ISO instant naar lokale Amsterdam datum (YYYY-MM-DD).
 */
export declare function getAmsterdamDateString(instantUtcIso: string): string;
/**
 * Haalt de huidige lokale datum in Amsterdam op (YYYY-MM-DD).
 */
export declare function getTodayAmsterdam(): string;
/**
 * Geeft een lijst van YYYY-MM-DD datums tussen twee UTC ISO strings.
 */
export declare function getLocalDateRange(fromIso: string, toIso: string): string[];
/**
 * Formatteert een UTC ISO timestamp naar XMLTV tijdnotatie: YYYYMMDDHHmmss ±HHMM
 * Berekend in Europe/Amsterdam (bijv. +0200 voor zomer, +0100 voor winter).
 */
export declare function formatXmltvTimestamp(instantUtcIso: string): string;
