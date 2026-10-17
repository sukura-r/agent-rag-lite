package com.example.agentrag.agent;

import com.example.agentrag.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestAttribute;
import com.example.agentrag.security.ApiSecurityFilter;
import com.example.agentrag.security.AuthenticatedUser;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/chat")
    public ApiResponse<AgentService.UserAnswer> chat(
            @RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE) AuthenticatedUser user,
            @Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(agentService.chat(request.message(), user).userView());
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message) {
    }
}
