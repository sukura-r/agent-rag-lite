package com.example.agentrag.evaluation;

import com.example.agentrag.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation/public")
public class PublicBenchmarkController {

    private final PublicBenchmarkDatasetService datasetService;
    private final PublicRetrievalBenchmarkService benchmarkService;
    private final RagAblationEvaluationService ablationService;

    public PublicBenchmarkController(PublicBenchmarkDatasetService datasetService,
                                     PublicRetrievalBenchmarkService benchmarkService,
                                     RagAblationEvaluationService ablationService) {
        this.datasetService = datasetService;
        this.benchmarkService = benchmarkService;
        this.ablationService = ablationService;
    }

    @GetMapping("/dataset")
    public ApiResponse<PublicBenchmarkDatasetService.Dataset> dataset() {
        return ApiResponse.success(datasetService.load());
    }

    @PostMapping("/retrieval/run")
    public ApiResponse<PublicRetrievalBenchmarkService.Report> run(
            @RequestParam(defaultValue = "3") int topK,
            @RequestParam(defaultValue = "true") boolean rerank,
            @RequestParam(defaultValue = "0") int queryOffset,
            @RequestParam(defaultValue = "300") int queryLimit,
            @RequestParam(defaultValue = "1.0") double vectorWeight,
            @RequestParam(defaultValue = "1.0") double keywordWeight) {
        return ApiResponse.success(benchmarkService.run(topK, rerank, queryOffset, queryLimit,
                vectorWeight, keywordWeight));
    }

    @PostMapping("/rag-ablation/run")
    public ApiResponse<RagAblationEvaluationService.Report> runAblation(
            @RequestParam(defaultValue = "12") int maxQuestions,
            @RequestParam(defaultValue = "3") int topK) {
        return ApiResponse.success(ablationService.run(maxQuestions, topK));
    }

    @PostMapping("/rag-ablation/rescore")
    public ApiResponse<RagAblationEvaluationService.Report> rescore(
            @RequestBody RagAblationEvaluationService.Report report) {
        return ApiResponse.success(ablationService.rescore(report));
    }
}
