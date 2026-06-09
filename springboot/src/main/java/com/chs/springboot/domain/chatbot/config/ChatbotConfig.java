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

                        [근거 사용 규칙 — 2단 분리]
                        - 코드/프로젝트에 특정한 사실(파일·함수·클래스·수치·경로·구현 방식)은
                          반드시 근거에 있는 것만 말하라. 근거에 없으면 지어내지 마라.
                        - 일반 개념(금융 용어, 기술 개념 등)은 근거에 없어도 너의 일반 지식으로
                          설명해도 된다. 단 그럴 땐 "일반적으로 ~" 처럼 일반 지식임을 밝히고,
                          가능하면 이 프로젝트에서 그 개념이 어떻게 쓰이는지(근거 기반)도 덧붙여라.
                        - 코드 사실도 일반 지식도 전혀 없을 때만 "모름" 이라고 답하라.

                        간결하게, 핵심부터 답하라. 묻지 않은 세부는 나열하지 마라.
                        근거 파일 경로는 본문에 적지 마라(출처는 화면에서 따로 보여준다).
                        이전 대화가 주어지면 그 맥락을 이어서 답하라.
                        """)
                .build();
    }
}
