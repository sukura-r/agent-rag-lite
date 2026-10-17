package com.example.agentrag.security;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class PromptSecurityService {
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("忽略.{0,12}(之前|以上|系统).{0,12}(指令|规则|提示)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(显示|泄露|输出).{0,12}(系统提示词|system prompt|密钥|api.?key)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(假装|扮演).{0,12}(管理员|开发者|system)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("调用.{0,20}(delete_all|execute_shell|删除全部)", Pattern.CASE_INSENSITIVE));
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    public String validateAndRedactUserInput(String input) {
        String value = input == null ? "" : input.strip();
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(value).find()) {
                throw new SecurityViolationException("请求包含试图覆盖系统规则或获取敏感指令的内容");
            }
        }
        return redact(value);
    }

    public String sanitizeUntrustedEvidence(String content) {
        String value = redact(content == null ? "" : content);
        for (Pattern pattern : INJECTION_PATTERNS) {
            value = pattern.matcher(value).replaceAll("[已移除不可信指令]");
        }
        return value;
    }

    public String redact(String value) {
        String result = ID_CARD.matcher(value == null ? "" : value).replaceAll("[身份证号已脱敏]");
        result = PHONE.matcher(result).replaceAll("[手机号已脱敏]");
        return EMAIL.matcher(result).replaceAll("[邮箱已脱敏]");
    }
}
