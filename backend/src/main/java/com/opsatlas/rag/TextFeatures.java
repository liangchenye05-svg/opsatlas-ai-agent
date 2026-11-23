package com.opsatlas.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class TextFeatures {
    private static final Pattern SPANS = Pattern.compile("[a-z0-9]+|[\\p{IsHan}]+");

    private TextFeatures() {}

    static List<String> terms(String text) {
        var tokens = new ArrayList<String>();
        var spans = SPANS.matcher(text.toLowerCase(Locale.ROOT));
        while (spans.find()) {
            String span = spans.group();
            if (span.charAt(0) < 128 || span.length() == 1) {
                tokens.add(span);
            } else {
                for (int i = 0; i + 1 < span.length(); i++) {
                    tokens.add(span.substring(i, i + 2));
                }
            }
        }
        return tokens;
    }
}
