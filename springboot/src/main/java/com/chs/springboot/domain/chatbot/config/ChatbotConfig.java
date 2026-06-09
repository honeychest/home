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
                        제공된 근거(컨텍스트)에 기반해 한국어로 답하라.
                        여러 근거 조각을 논리적으로 연결·종합한 설명은 허용한다.
                        (예: A가 B를 렌더링하고 B가 OI를 그린다 → A는 OI를 보여주는 화면이다)
                        단, 근거에서 확인되지 않는 사실/수치/이름/경로를 지어내지는 마라.
                        답에 사용한 근거 파일 경로를 함께 제시하라.
                        관련 근거가 전혀 없을 때만 "모름" 이라고 답하라.
                        """)
                .build();
    }
}
