package com.example.agentrag.task;

import com.example.agentrag.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final AgentPerformanceMetrics metrics;
    private final ProjectReportService report;
    public OperationsController(AgentPerformanceMetrics metrics,ProjectReportService report){this.metrics=metrics;this.report=report;}
    @GetMapping("/agent-metrics")public ApiResponse<AgentPerformanceMetrics.Snapshot> metrics(){return ApiResponse.success(metrics.snapshot());}
    @GetMapping("/project-report")public ApiResponse<ProjectReportService.Report> report(){return ApiResponse.success(report.report());}
}
