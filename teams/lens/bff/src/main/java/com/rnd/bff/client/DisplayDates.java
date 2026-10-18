package com.rnd.bff.client;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Inbox sends task dates already formatted for display, in Israel time
 * (Inbox's DisplayDateFormat, inbox-team PR #591): a deadline as "12/10/2026",
 * every other date as "12/10/2026 09:00". The BFF passes them on as-is.
 *
 * Only the mock needs this class - it formats its own seeded dates the same
 * way, so mock and real mode look identical on screen.
 */
final class DisplayDates {

    static final ZoneId ZONE = ZoneId.of("Asia/Jerusalem");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZONE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZONE);

    private DisplayDates() {
    }

    /** A deadline, e.g. "12/10/2026". The mock's dates are zone-less UTC. */
    static String date(LocalDateTime utc) {
        return utc == null ? null : DATE.format(utc.toInstant(ZoneOffset.UTC));
    }

    /** Any other date, e.g. "12/10/2026 09:00". */
    static String dateTime(LocalDateTime utc) {
        return utc == null ? null : DATE_TIME.format(utc.toInstant(ZoneOffset.UTC));
    }
}
