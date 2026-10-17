package com.example.agentrag.task;

import com.example.agentrag.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations/agent-event-benchmark")
public class AgentEventPersistenceBenchmarkController {
    private final AgentEventPersistenceBenchmarkService service;

    public AgentEventPersistenceBenchmarkController(AgentEventPersistenceBenchmarkService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<AgentEventPersistenceBenchmarkService.Report> run(
            @RequestParam(defaultValue = "8") int concurrentTasks,
            @RequestParam(defaultValue = "200") int tokenDeltasPerTask,
            @RequestParam(defaultValue = "12") int batchCharacters) {
        return ApiResponse.success(service.run(concurrentTasks, tokenDeltasPerTask, batchCharacters));
    }
}
