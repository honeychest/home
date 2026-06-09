// [AGENT] 역할: 검색 근거를 사용하는 RAG 답변 생성 Adapter | 연관파일: ChatbotService.java, ChatbotConfig.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

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
            - 코드/프로젝트 사실(파일·함수·수치·경로·구현)은 근거에 있는 것만 말하고 지어내지 마라.
            - 일반 개념(금융 용어, 기술 개념)은 근거에 없어도 일반 지식으로 설명해도 된다.
              그럴 땐 "일반적으로 ~" 라고 밝히고, 이 프로젝트에서의 쓰임(근거 기반)도 덧붙여라.
            - 코드 사실도 일반 지식도 전혀 없을 때만 "모름" 이라 답하라.

            [답변 방식 — 반드시 지켜라]
            - 핵심을 먼저 1~2문장으로 답하라. 그게 전부면 거기서 끝내라.
            - 묻지 않은 세부(데이터 구조·옵션·내부 구현·파일 경로)는 나열하지 마라.
            - 근거 파일 경로를 본문에 적지 마라. (출처는 화면에서 따로 보여준다)
            - 질문이 모호해 여러 대상에 해당할 수 있으면, 추측하지 말고 무엇을 말하는지 한 번 되물어라.
            - "응/그래/자세히/더" 같은 짧은 호응이면 이전 대화의 직전 주제를 이어서 더 설명하라.
            - 더 설명할 게 남았으면 본문 끝에 "더 자세히 원하시면 말씀해 주세요" 한 줄만 덧붙여라.

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

    // searchQuery: 근거 검색에 쓸 질의(후속질문 맥락 보강된 것). question: LLM에 보낼 실제 질문 문장.
    public String generate(String question, String searchQuery, List<Message> history) {
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().query(searchQuery).topK(properties.getTopK()).build())
                .promptTemplate(QA_TEMPLATE)
                .build();

        // history(이전 대화)는 맥락용으로만 주입. 근거 검색(qaAdvisor)은 현재 question 기준.
        return chatbotChatClient.prompt()
                .advisors(qaAdvisor)
                .messages(history)
                .user(question)
                .call()
                .content();
    }
}
