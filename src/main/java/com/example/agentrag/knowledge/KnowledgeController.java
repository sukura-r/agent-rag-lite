package com.example.agentrag.knowledge;

import com.example.agentrag.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping("/documents")
    public ApiResponse<KnowledgeService.IngestResult> ingest(@Valid @RequestBody IngestRequest request) {
        return ApiResponse.success(knowledgeService.ingest(request.title(), request.content()));
    }

    @PutMapping("/documents/{documentId}")
    public ApiResponse<KnowledgeService.IngestResult> update(@PathVariable String documentId,
                                                              @Valid @RequestBody IngestRequest request){
        return ApiResponse.success(knowledgeService.update(documentId,request.title(),request.content()));
    }

    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<KnowledgeService.IngestResult> delete(@PathVariable String documentId){
        return ApiResponse.success(knowledgeService.delete(documentId));
    }

    @GetMapping("/search")
    public ApiResponse<List<KnowledgeService.SearchResult>> search(
            @RequestParam @NotBlank String query,
            @RequestParam(defaultValue = "3") @Min(1) @Max(20) int topK) {
        return ApiResponse.success(knowledgeService.search(query, topK));
    }

    public record IngestRequest(@NotBlank @Size(max = 255) String title,
                                @NotBlank @Size(max = 100000) String content) {
    }
}
