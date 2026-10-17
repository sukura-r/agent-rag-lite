package com.example.agentrag.conversation;

import com.example.agentrag.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestAttribute;
import com.example.agentrag.security.ApiSecurityFilter;
import com.example.agentrag.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.util.List;
import java.util.Map;
import com.example.agentrag.task.AgentTaskEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final AgentTaskEventRepository events;
    private final ObjectMapper json;

    public ConversationController(ConversationService conversationService,AgentTaskEventRepository events,ObjectMapper json) {
        this.conversationService = conversationService;
        this.events=events;this.json=json;
    }

    @GetMapping("/{sessionId}/events")
    public ApiResponse<List<RestoredEvent>> events(@RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE)AuthenticatedUser user,
                                                   @PathVariable String sessionId){
        conversationService.requireSession(user.userId(),sessionId);
        return ApiResponse.success(events.findBySession(user.userId(),sessionId).stream().map(event->{
            try{return new RestoredEvent(event.taskId(),event.sequence(),event.eventType(),
                    json.readValue(event.payloadJson(),Map.class),event.createdAt());}
            catch(Exception e){throw new IllegalStateException("解析历史Agent事件失败",e);}
        }).toList());
    }

    public record RestoredEvent(String taskId,int sequence,String eventType,Map<String,Object> data,
                                java.time.LocalDateTime createdAt){}

    @GetMapping
    public ApiResponse<List<ConversationRepository.SessionRecord>> sessions(
            @RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE) AuthenticatedUser user) {
        return ApiResponse.success(conversationService.sessions(user.userId()));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<ConversationRepository.MessageRecord>> messages(
            @RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable String sessionId) {
        return ApiResponse.success(conversationService.messages(user.userId(), sessionId));
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse<String> close(
            @RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable String sessionId) {
        conversationService.close(user.userId(), sessionId);
        return ApiResponse.success("CLOSED");
    }
}
