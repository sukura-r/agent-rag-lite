package com.example.agentrag.retrieval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ChineseTokenizer {

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                flushLatin(latin, tokens);
                cjk.appendCodePoint(codePoint);
            } else {
                flushCjk(cjk, tokens);
                if (Character.isLetterOrDigit(codePoint)) {
                    latin.appendCodePoint(codePoint);
                } else {
                    flushLatin(latin, tokens);
                }
            }
        }
        flushLatin(latin, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private void flushLatin(StringBuilder builder, List<String> tokens) {
        if (!builder.isEmpty()) {
            tokens.add(builder.toString());
            builder.setLength(0);
        }
    }

    private void flushCjk(StringBuilder builder, List<String> tokens) {
        if (builder.isEmpty()) {
            return;
        }
        int[] points = builder.codePoints().toArray();
        for (int point : points) {
            tokens.add(new String(Character.toChars(point)));
        }
        for (int i = 0; i + 1 < points.length; i++) {
            tokens.add(new String(Character.toChars(points[i])) + new String(Character.toChars(points[i + 1])));
        }
        builder.setLength(0);
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN;
    }
}
