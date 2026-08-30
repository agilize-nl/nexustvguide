import { Temporal } from '@js-temporal/polyfill';
export const TIME_ZONE = 'Europe/Amsterdam';
/**
 * Berekent het exacte UTC [from, to) venster voor een lokale datum in Amsterdam.
 * Houdt automatisch rekening met 23-uurs en 25-uurs dagen bij zomertijdwissels.
 */
export function getLocalDayUtcWindow(dateStr) {
    const plainDate = Temporal.PlainDate.from(dateStr);
    const startZoned = plainDate.toZonedDateTime({ timeZone: TIME_ZONE, plainTime: '00:00:00' });
    const nextDayZoned = plainDate.add({ days: 1 }).toZonedDateTime({ timeZone: TIME_ZONE, plainTime: '00:00:00' });
    return {
        from: startZoned.toInstant().toString(),
        to: nextDayZoned.toInstant().toString(),
    };
}
/**
 * Converteert een UTC ISO instant naar lokale Amsterdam datum (YYYY-MM-DD).
 */
export function getAmsterdamDateString(instantUtcIso) {
    const instant = Temporal.Instant.from(instantUtcIso);
    const zonedDateTime = instant.toZonedDateTimeISO(TIME_ZONE);
    return zonedDateTime.toPlainDate().toString();
}
/**
 * Haalt de huidige lokale datum in Amsterdam op (YYYY-MM-DD).
 */
export function getTodayAmsterdam() {
    return Temporal.Now.zonedDateTimeISO(TIME_ZONE).toPlainDate().toString();
}
/**
 * Geeft een lijst van YYYY-MM-DD datums tussen twee UTC ISO strings.
 */
export function getLocalDateRange(fromIso, toIso) {
    const startInstant = Temporal.Instant.from(fromIso);
    const endInstant = Temporal.Instant.from(toIso);
    const startDate = startInstant.toZonedDateTimeISO(TIME_ZONE).toPlainDate();
    const endDate = endInstant.toZonedDateTimeISO(TIME_ZONE).toPlainDate();
    const dates = [];
    let current = startDate;
    while (Temporal.PlainDate.compare(current, endDate) <= 0) {
        dates.push(current.toString());
        current = current.add({ days: 1 });
    }
    return dates;
}
/**
 * Formatteert een UTC ISO timestamp naar XMLTV tijdnotatie: YYYYMMDDHHmmss ±HHMM
 * Berekend in Europe/Amsterdam (bijv. +0200 voor zomer, +0100 voor winter).
 */
export function formatXmltvTimestamp(instantUtcIso) {
    const instant = Temporal.Instant.from(instantUtcIso);
    const zdt = instant.toZonedDateTimeISO(TIME_ZONE);
    const year = String(zdt.year).padStart(4, '0');
    const month = String(zdt.month).padStart(2, '0');
    const day = String(zdt.day).padStart(2, '0');
    const hour = String(zdt.hour).padStart(2, '0');
    const minute = String(zdt.minute).padStart(2, '0');
    const second = String(zdt.second).padStart(2, '0');
    // Offset format: +02:00 -> +0200
    const offset = zdt.offset.replace(':', '');
    return `${year}${month}${day}${hour}${minute}${second} ${offset}`;
}
