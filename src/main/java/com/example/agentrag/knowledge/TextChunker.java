package com.example.agentrag.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TextChunker {

    private final int chunkSize;
    private final int overlap;

    public TextChunker(@Value("${app.chunk.size}") int chunkSize,
                       @Value("${app.chunk.overlap}") int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("分块参数必须满足 chunkSize > overlap >= 0");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<String> split(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());
            if (end < normalized.length()) {
                end = findNaturalBoundary(normalized, start, end);
            }
            chunks.add(normalized.substring(start, end).trim());
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    private int findNaturalBoundary(String text, int start, int expectedEnd) {
        int lowerBound = start + chunkSize / 2;
        for (int i = expectedEnd; i > lowerBound; i--) {
            char c = text.charAt(i - 1);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '.' || c == '!' || c == '?') {
                return i;
            }
        }
        return expectedEnd;
    }
}
