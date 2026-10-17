package com.example.agentrag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiSecurityFilter extends OncePerRequestFilter {
    public static final String USER_ATTRIBUTE = "authenticatedUser";

    private final ObjectMapper objectMapper;
    private final SecurityAuditService auditService;
    private final String userToken;
    private final String adminToken;
    private final String mcpToken;

    public ApiSecurityFilter(ObjectMapper objectMapper, SecurityAuditService auditService,
                             @Value("${app.security.user-token}") String userToken,
                             @Value("${app.security.admin-token}") String adminToken,
                             @Value("${app.security.mcp-token}") String mcpToken) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.userToken = userToken;
        this.adminToken = adminToken;
        this.mcpToken = mcpToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("OPTIONS".equals(request.getMethod()) || isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }
        if ("/mcp".equals(path)) {
            if (!constantEquals(mcpToken, request.getHeader("X-MCP-Token"))) {
                deny(response, 401, "MCP内部认证失败");
                auditService.record("anonymous", "AUTH_FAILURE", path, "DENY", "invalid mcp token", Map.of());
                return;
            }
            request.setAttribute(USER_ATTRIBUTE, new AuthenticatedUser("internal-mcp-client", AuthenticatedUser.Role.INTERNAL));
            chain.doFilter(request, response);
            return;
        }
        AuthenticatedUser user = authenticate(request.getHeader("Authorization"));
        if (user == null) {
            deny(response, 401, "缺少或无效的Bearer访问令牌");
            auditService.record("anonymous", "AUTH_FAILURE", path, "DENY", "invalid bearer token", Map.of());
            return;
        }
        String claimedUser = request.getHeader("X-User-Id");
        if (claimedUser != null && !claimedUser.isBlank() && !claimedUser.equals(user.userId())) {
            deny(response, 403, "禁止通过X-User-Id伪造其他用户身份");
            auditService.record(user.userId(), "IDENTITY_SPOOF", path, "DENY", "conflicting X-User-Id", Map.of());
            return;
        }
        if (requiresAdmin(request) && user.role() != AuthenticatedUser.Role.ADMIN) {
            deny(response, 403, "当前账号没有管理员权限");
            auditService.record(user.userId(), "AUTHORIZATION", path, "DENY", "admin role required", Map.of());
            return;
        }
        request.setAttribute(USER_ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    private AuthenticatedUser authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        String token = authorization.substring(7).strip();
        if (constantEquals(adminToken, token)) return new AuthenticatedUser("local-admin", AuthenticatedUser.Role.ADMIN);
        if (constantEquals(userToken, token)) return new AuthenticatedUser("local-demo-user", AuthenticatedUser.Role.USER);
        return null;
    }

    private boolean requiresAdmin(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/evaluation/")
                || path.startsWith("/api/security/")
                || path.startsWith("/api/indexing/")
                || path.startsWith("/api/operations/")
                || path.startsWith("/api/knowledge/documents");
    }

    private boolean isPublic(String path) {
        return path.equals("/console") || path.startsWith("/console/")
                || path.equals("/") || path.equals("/index.html") || path.equals("/knowledge.html")
                || path.equals("/favicon.ico") || path.equals("/api/system/health")
                || path.equals("/actuator/health") || path.startsWith("/error");
    }

    private boolean constantEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("success", false, "data", Map.of(), "message", message));
    }
}
