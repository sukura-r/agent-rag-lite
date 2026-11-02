package com.example.agentrag.memory;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deliberately narrow: only standalone first-person preference statements are inferred. */
public class MemoryCandidateExtractor {
    private static final Pattern LANGUAGE = Pattern.compile(
            "(?:我的主要编程语言是|我主要使用|我主要用)(Java|Python|Go|Kotlin|JavaScript|TypeScript|C\\+\\+|C#)(?:开发|编程|写代码)?[。！!]?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE = Pattern.compile("我(?:偏好|喜欢)(简洁|详细)(?:的)?回答[。！!]?");

    public Optional<Candidate> extract(String message) {
        String text = message == null ? "" : message.strip();
        if (text.length() > 80) return Optional.empty();
        Matcher language = LANGUAGE.matcher(text);
        if (language.matches()) {
            String value = switch (language.group(1).toLowerCase(Locale.ROOT)) {
                case "java" -> "Java";
                case "python" -> "Python";
                case "go" -> "Go";
                case "kotlin" -> "Kotlin";
                case "javascript" -> "JavaScript";
                case "typescript" -> "TypeScript";
                case "c++" -> "C++";
                default -> "C#";
            };
            return Optional.of(new Candidate("偏好:编程语言", "我的主要编程语言是" + value));
        }
        Matcher style = STYLE.matcher(text);
        if (style.matches()) {
            return Optional.of(new Candidate("偏好:回答风格", "我偏好" + style.group(1) + "回答"));
        }
        return Optional.empty();
    }

    public record Candidate(String key, String content) { }
}
