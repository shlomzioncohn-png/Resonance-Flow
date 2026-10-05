package com.rnd.bridge.task.enrichment.rules;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a deadline from free text using simple regex patterns.
 * Supports ISO dates ({@code 2024-12-31}), slash dates ({@code 31/12/2024}),
 * dot dates ({@code 31.12.2024}) and explicit "due YYYY-MM-DD" phrases.
 * Returns the earliest date found, or null when none is found.
 */
@Component
public class DateExtractor {

    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
    private static final Pattern SLASH_DATE = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b");
    private static final Pattern DOT_DATE = Pattern.compile("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b");
    private static final Pattern DUE_DATE = Pattern.compile("(?i)\\bdue\\s+(\\d{4}-\\d{2}-\\d{2})\\b");

    private static final ZoneOffset ZONE = ZoneOffset.UTC;
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    public Instant extract(String text, Instant asOf) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Instant earliest = null;
        earliest = min(earliest, findAll(ISO_DATE, text, m -> parseIso(m.group(1))));
        earliest = min(earliest, findAll(SLASH_DATE, text, m -> parseSlash(m.group(1), m.group(2), m.group(3))));
        earliest = min(earliest, findAll(DOT_DATE, text, m -> parseDot(m.group(1), m.group(2), m.group(3))));
        earliest = min(earliest, findAll(DUE_DATE, text, m -> parseIso(m.group(1))));
        return earliest;
    }

    private Instant findAll(Pattern pattern, String text, Function<Matcher, Instant> parser) {
        Matcher matcher = pattern.matcher(text);
        Instant best = null;
        while (matcher.find()) {
            best = min(best, parser.apply(matcher));
        }
        return best;
    }

    private Instant parseIso(String value) {
        try {
            return LocalDate.parse(value).atTime(END_OF_DAY).toInstant(ZONE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Instant parseSlash(String day, String month, String year) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day))
                    .atTime(END_OF_DAY).toInstant(ZONE);
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    private Instant parseDot(String day, String month, String year) {
        return parseSlash(day, month, year);
    }

    private Instant min(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
