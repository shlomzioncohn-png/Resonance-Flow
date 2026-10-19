package com.rnd.bridge.media.classification;

import java.util.regex.Pattern;

/**
 * Reduces a .vtt/.srt/.txt transcript to just its spoken text - cue numbers, timestamps and the
 * WEBVTT header carry no meaning for classification, and leaving them in both skews the
 * "is there any speech at all" rule and wastes the model's input budget.
 */
final class Transcripts {

    private static final Pattern CUE_NUMBER = Pattern.compile("^\\d+$");
    private static final String TIMESTAMP_ARROW = "-->";

    private Transcripts() {
    }

    static String spokenText(String transcript) {
        if (transcript == null) {
            return "";
        }
        StringBuilder spoken = new StringBuilder();
        for (String rawLine : transcript.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("WEBVTT") || line.startsWith("NOTE")
                    || line.contains(TIMESTAMP_ARROW) || CUE_NUMBER.matcher(line).matches()) {
                continue;
            }
            spoken.append(line).append('\n');
        }
        return spoken.toString().strip();
    }
}
