package com.rnd.inbox.seedgen;

/**
 * Minimal RFC 4180-style CSV field/row formatting — {@code null} becomes an empty field (matches
 * Postgres {@code COPY ... WITH (FORMAT csv)}'s default NULL representation), a value containing
 * a comma, quote or newline gets wrapped in quotes with inner quotes doubled.
 */
final class CsvUtil {

    private CsvUtil() {
    }

    static String row(Object... values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(field(values[i]));
        }
        return line.toString();
    }

    private static String field(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
