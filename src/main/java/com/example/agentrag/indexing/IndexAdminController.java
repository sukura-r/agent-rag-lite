package com.example.agentrag.indexing;

import com.example.agentrag.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/indexing")
public class IndexAdminController {
    private final IndexTaskRepository repository;
    private final IndexReconciliationService reconciliation;
    public IndexAdminController(IndexTaskRepository repository,IndexReconciliationService reconciliation){
        this.repository=repository;this.reconciliation=reconciliation;
    }
    @GetMapping("/tasks") public ApiResponse<List<IndexTaskRepository.TaskView>> tasks(@RequestParam(defaultValue="20")int limit){return ApiResponse.success(repository.tasks(Math.max(1,Math.min(100,limit))));}
    @GetMapping("/metrics") public ApiResponse<IndexTaskRepository.Metrics> metrics(){return ApiResponse.success(repository.metrics());}
    @PostMapping("/replay-failed") public ApiResponse<Integer> replay(){return ApiResponse.success(repository.replayFailed());}
    @PostMapping("/reconcile") public ApiResponse<IndexReconciliationService.Result> reconcile(){
        return ApiResponse.success(reconciliation.reconcile());
    }
}
