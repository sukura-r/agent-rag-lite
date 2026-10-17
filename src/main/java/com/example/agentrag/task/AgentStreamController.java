package com.example.agentrag.task;

import com.example.agentrag.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestAttribute;
import com.example.agentrag.security.ApiSecurityFilter;
import com.example.agentrag.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentStreamController {

    private final AgentStreamService streamService;

    public AgentStreamController(AgentStreamService streamService) {
        this.streamService = streamService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE) AuthenticatedUser user,
                             @Valid @RequestBody StreamRequest request) {
        return streamService.stream(user, request.sessionId(), request.message());
    }

    @PostMapping("/tasks")
    public ApiResponse<AgentStreamService.TaskCreated> create(
            @RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE)AuthenticatedUser user,
            @Valid @RequestBody StreamRequest request){
        return ApiResponse.success(streamService.createTask(user,request.sessionId(),request.message()));
    }

    @GetMapping(value="/tasks/{taskId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE)AuthenticatedUser user,
                             @PathVariable String taskId,
                             @RequestHeader(value="Last-Event-ID",required=false)String lastEventId,
                             @org.springframework.web.bind.annotation.RequestParam(defaultValue="0")int after){
        int sequence=Math.max(0,after);
        if(lastEventId!=null&&!lastEventId.isBlank())try{sequence=Math.max(sequence,Integer.parseInt(lastEventId));}
        catch(NumberFormatException e){throw new IllegalArgumentException("Last-Event-ID必须是整数");}
        return streamService.subscribe(user.userId(),taskId,sequence);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<Map<String, String>> cancel(
            @RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable String taskId) {
        return ApiResponse.success(Map.of("taskId", taskId, "status", streamService.cancel(user.userId(), taskId)));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<Map<String, String>> status(
            @RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable String taskId) {
        return ApiResponse.success(Map.of("taskId", taskId, "status", streamService.status(user.userId(), taskId)));
    }

    @GetMapping("/tasks/running")
    public ApiResponse<List<AgentStreamService.RunningTask>> running(
            @RequestAttribute(ApiSecurityFilter.USER_ATTRIBUTE)AuthenticatedUser user){
        return ApiResponse.success(streamService.runningTasks(user.userId()));
    }

    public record StreamRequest(String sessionId,
                                @NotBlank @Size(max = 2000) String message) {
    }
}
