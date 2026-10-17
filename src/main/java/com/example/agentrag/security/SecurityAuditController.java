package com.example.agentrag.security;

import com.example.agentrag.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/security/audits")
public class SecurityAuditController {
    private final SecurityAuditService auditService;

    public SecurityAuditController(SecurityAuditService auditService) { this.auditService = auditService; }

    @GetMapping
    public ApiResponse<List<SecurityAuditService.AuditRecord>> recent(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(auditService.recent(limit));
    }
}
