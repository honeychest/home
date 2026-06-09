// [AGENT] 챗봇 ChatClient 빈 — 코드베이스 RAG 답변용 시스템 프롬프트 고정
// 연관: PgVectorConfig(VectorStore), ChatbotService(추후 RAG 질의)
package com.chs.springboot.domain.chatbot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 챗봇 전용 ChatClient.
 *
 * ChatClient.Builder 는 spring-ai-starter-model-openai 가 자동 구성(LM Studio 채팅 모델).
 * 시스템 프롬프트로 "근거에 있는 내용만, 없으면 모름" 을 강제해 환각을 억제한다.
 */
@Configuration
public class ChatbotConfig {

    @Bean
    public ChatClient chatbotChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        너는 이 프로젝트 코드베이스에 대한 질문에 답하는 도우미다.
                        반드시 제공된 근거(컨텍스트)에 있는 내용만 사용해 한국어로 답하라.
                        근거에 없는 내용은 추측하지 말고 "모름" 이라고 답하라.
                        가능하면 근거가 된 파일 경로를 함께 제시하라.
                        """)
                .build();
    }
}
