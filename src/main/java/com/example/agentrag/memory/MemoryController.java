package com.example.agentrag.memory;

import com.example.agentrag.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestAttribute;
import com.example.agentrag.security.ApiSecurityFilter;
import com.example.agentrag.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) { this.memoryService = memoryService; }

    @GetMapping
    public ApiResponse<List<MemoryView>> list(
            @RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE) AuthenticatedUser user) {
        return ApiResponse.success(memoryService.list(user.userId()).stream()
                .map(memory -> new MemoryView(
                        memory.id(),
                        memory.key(),
                        memory.content(),
                        memory.expiresAt(),
                        memory.createdAt(),
                        memory.updatedAt()))
                .toList());
    }

    @DeleteMapping("/{memoryId}")
    public ApiResponse<String> delete(
            @RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable String memoryId) {
        memoryService.delete(user.userId(), memoryId);
        return ApiResponse.success("DELETED");
    }

    public record MemoryView(String id, String key, String content,
                             LocalDateTime expiresAt, LocalDateTime createdAt,
                             LocalDateTime updatedAt) {}
}
