package com.example.agent.controller;

import com.example.agent.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    // ===== 流式输出（SSE，当前使用）=====
    // conversationId：多轮上下文记忆的会话键；mode：think=深度思考，其它=快速回答
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        return chatService.chat(request.message(), request.conversationId(), request.mode());
    }

    // ===== 整体输出（保留备用）=====
    // @PostMapping("/chat")
    // public String chat(@RequestBody ChatRequest request) {
    //     return chatService.chat(request.message(), request.conversationId(), request.mode());
    // }

    /** 由 AI 概括会话内容生成短标题（前端侧边栏展示）。 */
    @PostMapping("/chat/title")
    public TitleResponse title(@RequestBody TitleRequest request) {
        return new TitleResponse(chatService.generateTitle(request.text()));
    }

    public record ChatRequest(String message, String conversationId, String mode) {
    }

    public record TitleRequest(String text) {
    }

    public record TitleResponse(String title) {
    }
}
