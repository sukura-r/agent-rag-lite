package com.example.agentrag.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class ToolAccessPolicy {
    private static final Set<String> USER_READ_TOOLS = Set.of("knowledge_search", "knowledge_document_get");
    private static final Set<String> HIGH_RISK_PREFIXES = Set.of("delete_", "execute_", "update_", "create_");

    public Decision authorize(AuthenticatedUser user, String toolName, Map<String, Object> arguments,
                              boolean confirmed) {
        if (user == null) return new Decision(false, "缺少可信用户身份");
        if (USER_READ_TOOLS.contains(toolName)) return new Decision(true, "允许只读知识工具");
        boolean highRisk = HIGH_RISK_PREFIXES.stream().anyMatch(toolName::startsWith);
        if (highRisk && user.role() != AuthenticatedUser.Role.ADMIN) {
            return new Decision(false, "高风险工具只允许管理员使用");
        }
        if (highRisk && !confirmed) {
            return new Decision(false, "高风险工具需要用户明确确认");
        }
        return new Decision(false, "工具不在当前角色允许名单中");
    }

    public record Decision(boolean allowed, String reason) { }
}
