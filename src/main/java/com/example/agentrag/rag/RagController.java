package com.example.agentrag.rag;

import com.example.agentrag.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/ask")
    public ApiResponse<RagService.RagAnswer> ask(@Valid @RequestBody AskRequest request) {
        return ApiResponse.success(ragService.ask(request.question(), request.effectiveTopK()));
    }

    public record AskRequest(@NotBlank @Size(max = 2000) String question,
                             @Min(1) @Max(20) Integer topK) {
        public int effectiveTopK() {
            return topK == null ? 3 : topK;
        }
    }
}
