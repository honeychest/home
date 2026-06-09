// [AGENT] 역할: RAG 기반 코드베이스 질의응답 서비스 | 연관파일: ChatbotConfig.java, PgVectorConfig.java, ChatbotController.java, ChatRequest.java, ChatResponse.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class ChatbotService {

    private static final Logger log = LoggerFactory.getLogger(ChatbotService.class);

    private final EvidenceRetriever evidenceRetriever;
    private final GroundedAnswerGenerator answerGenerator;

    public ChatbotService(EvidenceRetriever evidenceRetriever, GroundedAnswerGenerator answerGenerator) {
        this.evidenceRetriever = evidenceRetriever;
        this.answerGenerator = answerGenerator;
    }

    public ChatResponse ask(String question) {
        log.info("[챗봇] 질문 수신: {}", question);
        long startMs = System.currentTimeMillis();

        try {
            RetrievedEvidence evidence = evidenceRetriever.retrieve(question);
            log.info("[챗봇] 검색된 청크 수: {}", evidence.documentCount());
            log.info("[챗봇] 근거 파일 목록: {}", evidence.sources());

            String answer = answerGenerator.generate(question);

            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[챗봇] 답변 생성 소요시간: {}ms, 답변 길이: {}자", elapsedMs, answer == null ? 0 : answer.length());

            return new ChatResponse(answer, evidence.sources());

        } catch (Exception e) {
            log.error("[챗봇] 답변 생성 중 오류 발생: {}", e.getMessage(), e);
            return new ChatResponse("오류: " + e.getMessage(), Collections.emptyList());
        }
    }
}
