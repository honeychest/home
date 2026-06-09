// [AGENT] 역할: 검색 근거를 사용하는 RAG 답변 생성 Adapter | 연관파일: ChatbotService.java, ChatbotConfig.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

@Component
public class GroundedAnswerGenerator {

    private final ChatClient chatbotChatClient;
    private final VectorStore vectorStore;
    private final ChatbotProperties properties;

    public GroundedAnswerGenerator(ChatClient chatbotChatClient, VectorStore vectorStore, ChatbotProperties properties) {
        this.chatbotChatClient = chatbotChatClient;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public String generate(String question) {
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().query(question).topK(properties.getTopK()).build())
                .build();

        return chatbotChatClient.prompt()
                .advisors(qaAdvisor)
                .user(question)
                .call()
                .content();
    }
}
