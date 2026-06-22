// [AGENT] 역할: 챗봇 질의응답 API(POST /api/chat) | 연관파일: ChatbotService.java, ChatRequest.java, ChatResponse.java
// 화면은 React(admin/test Chatbot 탭, FloatingChatbot 위젯)가 담당. 옛 Thymeleaf 페이지(GET /chatbot)는 제거됨.
package com.chs.springboot.domain.chatbot.controller;

import com.chs.springboot.domain.chatbot.dto.ChatRequest;
import com.chs.springboot.domain.chatbot.dto.ChatResponse;
import com.chs.springboot.domain.chatbot.service.ChatbotService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatbotController {

    private final ChatbotService chatbotService;

    public ChatbotController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    @PostMapping("/api/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        return chatbotService.ask(req.getQuestion(), req.getHistory(), req.getPageId(), req.getSessionId());
    }
}
