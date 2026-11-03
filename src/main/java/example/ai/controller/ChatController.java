package example.ai.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import example.ai.model.ChatAnswer;
import example.ai.model.ChatRequest;
import example.ai.service.ChatInterface;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController 
{
    private final ChatInterface chatService;
    
    public ChatController(ChatInterface chatService)
    {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatAnswer chat(@RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt)
    {
       return chatService.modelCall(request.prompt(), jwt.getSubject(), request.conversationId());
    }
}