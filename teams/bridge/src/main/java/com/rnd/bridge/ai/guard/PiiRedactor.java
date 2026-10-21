package com.rnd.bridge.ai.guard;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces structured personal data in free text with a placeholder ({@code [EMAIL]},
 * {@code [IBAN]}, {@code [CREDIT_CARD]}, {@code [PHONE]}, {@code [ID]}) - the kinds with a fixed
 * shape a regex can find reliably. Names and addresses are not covered: telling them apart from
 * ordinary words needs NER, which this deliberately doesn't attempt.
 * <p>
 * Order matters: longer, more specific shapes go first, so a card number isn't half-eaten as a
 * phone. Card numbers must pass Luhn and Israeli IDs their check digit, which keeps ordinary long
 * numbers (order ids, amounts) from being redacted. Dates, times and amounts never match.
 */
public class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?\\b");
    private static final Pattern CARD = Pattern.compile("(?<![\\d-])(?:\\d[ -]?){12,18}\\d(?![\\d-])");
    private static final Pattern IL_PHONE = Pattern.compile("(?<![\\d+])(?:\\+972[ -]?|0)(?:5\\d|7\\d|[23489])(?:[ -]?\\d){7}(?!\\d)");
    private static final Pattern INTL_PHONE = Pattern.compile("(?<![\\d+])\\+\\d{1,3}(?:[ -]?\\d){6,12}(?!\\d)");
    private static final Pattern IL_ID = Pattern.compile("(?<!\\d)\\d{9}(?!\\d)");

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = replace(text, EMAIL, "[EMAIL]", match -> true);
        out = replace(out, IBAN, "[IBAN]", match -> true);
        out = replace(out, CARD, "[CREDIT_CARD]", match -> luhnValid(digitsOf(match)));
        out = replace(out, IL_PHONE, "[PHONE]", match -> true);
        out = replace(out, INTL_PHONE, "[PHONE]", match -> true);
        return replace(out, IL_ID, "[ID]", PiiRedactor::israeliIdValid);
    }

    private static String replace(String text, Pattern pattern, String placeholder, Predicate<String> accept) {
        Matcher matcher = pattern.matcher(text);
        return matcher.replaceAll(result -> accept.test(result.group())
                ? Matcher.quoteReplacement(placeholder) : Matcher.quoteReplacement(result.group()));
    }

    private static String digitsOf(String value) {
        return value.replaceAll("\\D", "");
    }

    static boolean luhnValid(String digits) {
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    /** Israeli Teudat Zehut check digit: weights 1,2,1,2..., digits of each product summed. */
    static boolean israeliIdValid(String nineDigits) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int d = (nineDigits.charAt(i) - '0') * (i % 2 == 0 ? 1 : 2);
            sum += d > 9 ? d - 9 : d;
        }
        return sum % 10 == 0;
    }
}
