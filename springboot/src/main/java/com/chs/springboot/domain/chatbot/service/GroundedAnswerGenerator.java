// [AGENT] 역할: 검색 근거를 사용하는 RAG 답변 생성 Adapter | 연관파일: ChatbotService.java, ChatbotConfig.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

@Component
public class GroundedAnswerGenerator {

    // QuestionAnswerAdvisor 기본 템플릿("컨텍스트에 없으면 답할 수 없다")이 근거 연결까지 막아서,
    // 합성을 허용하되 날조는 금지하는 커스텀 템플릿으로 교체한다. (placeholder 규약: query, question_answer_context)
    private static final PromptTemplate QA_TEMPLATE = new PromptTemplate("""
            아래 컨텍스트(근거)를 사용해 질문에 답하라.
            ---------------------
            {question_answer_context}
            ---------------------
            규칙:
            - 여러 근거를 논리적으로 연결·종합한 설명은 허용한다.
            - 근거에서 확인되지 않는 사실/수치/이름은 지어내지 마라.
            - 답에 사용한 근거 파일 경로를 함께 제시하라.
            - 관련 근거가 전혀 없을 때만 "모름" 이라 답하라.

            질문: {query}
            """);

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
                .promptTemplate(QA_TEMPLATE)
                .build();

        return chatbotChatClient.prompt()
                .advisors(qaAdvisor)
                .user(question)
                .call()
                .content();
    }
}
