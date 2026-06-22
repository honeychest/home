// [AGENT] 역할: 챗봇 검색 근거 로그 조회 Repository | 연관파일: ChatbotRetrievedEvidence.java
package com.chs.springboot.domain.chatbot.repository;

import com.chs.springboot.domain.chatbot.model.ChatbotRetrievedEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatbotRetrievedEvidenceRepository extends JpaRepository<ChatbotRetrievedEvidence, Long> {

    List<ChatbotRetrievedEvidence> findByTurnIdOrderByRankNoAsc(Long turnId);
}
