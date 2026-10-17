package com.example.agentrag.evaluation;

import com.example.agentrag.common.ApiResponse;
import com.example.agentrag.knowledge.KnowledgeRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evaluation/capacity")
public class CapacityDatasetController {
    private final CapacityDatasetService service;
    private final KnowledgeRepository repository;
    public CapacityDatasetController(CapacityDatasetService service,KnowledgeRepository repository){this.service=service;this.repository=repository;}

    @PostMapping("/generate")
    public ApiResponse<CapacityDatasetService.GenerateResult> generate(@RequestParam(defaultValue="20")@Min(1)@Max(1000)int count,
                                                                        @RequestParam(defaultValue="day12")String batch){
        return ApiResponse.success(service.generate(count,batch));
    }

    @GetMapping("/stats")
    public ApiResponse<KnowledgeRepository.CapacityStatistics> stats(){return ApiResponse.success(repository.capacityStatistics());}
}
