package com.example.agentrag.evaluation;

import com.example.agentrag.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evaluation/day12")
public class Day12EvaluationController {
    private final Day12DatasetService dataset;
    private final RetrievalEvaluationService evaluation;
    private final DatasetQualityService quality;

    public Day12EvaluationController(Day12DatasetService dataset,RetrievalEvaluationService evaluation,DatasetQualityService quality){
        this.dataset=dataset;this.evaluation=evaluation;this.quality=quality;
    }

    @PostMapping("/dataset/load")
    public ApiResponse<Day6DatasetService.LoadResult> load(){return ApiResponse.success(dataset.loadSupplement());}

    @GetMapping("/dataset/stats")
    public ApiResponse<Day6DatasetService.DatasetReport> stats(){return ApiResponse.success(dataset.statistics());}

    @PostMapping("/run")
    public ApiResponse<RetrievalEvaluationService.EvaluationReport> run(@RequestParam(defaultValue="3")int topK){
        return ApiResponse.success(evaluation.runDay12(topK));
    }

    @GetMapping("/dataset/quality")
    public ApiResponse<DatasetQualityService.Report> quality(){return ApiResponse.success(quality.inspect());}
}
