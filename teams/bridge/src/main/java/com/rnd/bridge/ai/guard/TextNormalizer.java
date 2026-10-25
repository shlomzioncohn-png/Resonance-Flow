package com.rnd.bridge.ai.guard;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Brings text to one canonical form before it reaches a model or a filter: Unicode NFKC (so
 * full-width digits, ligatures and look-alike compatibility characters become their plain form),
 * then removes invisible format characters (zero-width spaces/joiners, bidi overrides, BOM) and
 * control characters other than newline and tab. Those are the usual way to slip a word past a
 * pattern match while it still reads the same to a model - or to a human.
 */
public final class TextNormalizer {

    // \p{Cf}: format chars (zero-width, bidi controls, BOM). \p{Cc} minus \n and \t: control chars.
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\x00-\\x08\\x0B-\\x1F\\x7F-\\x9F]");

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return INVISIBLE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
    }
}
