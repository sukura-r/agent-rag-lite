package com.example.agentrag.evaluation;

import com.example.agentrag.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnResource(resources = {"classpath:evaluation/day6-corpus.json", "classpath:evaluation/day6-queries.json"})
@RequestMapping("/api/evaluation/day6")
public class EvaluationController {

    private final Day6DatasetService datasetService;
    private final RetrievalEvaluationService evaluationService;

    public EvaluationController(Day6DatasetService datasetService,
                                RetrievalEvaluationService evaluationService) {
        this.datasetService = datasetService;
        this.evaluationService = evaluationService;
    }

    @PostMapping("/dataset/load")
    public ApiResponse<Day6DatasetService.LoadResult> load() {
        return ApiResponse.success(datasetService.loadCorpus());
    }

    @GetMapping("/dataset/stats")
    public ApiResponse<Day6DatasetService.DatasetReport> statistics() {
        return ApiResponse.success(datasetService.statistics());
    }

    @PostMapping("/run")
    public ApiResponse<RetrievalEvaluationService.EvaluationReport> run(
            @RequestParam(defaultValue = "3") int topK) {
        return ApiResponse.success(evaluationService.run(topK));
    }

}
