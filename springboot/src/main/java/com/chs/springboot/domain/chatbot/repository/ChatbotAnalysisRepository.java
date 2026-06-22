// [AGENT] 역할: 챗봇 사후 분석 결과 조회 Repository | 연관파일: ChatbotAnalysis.java
package com.chs.springboot.domain.chatbot.repository;

import com.chs.springboot.domain.chatbot.model.ChatbotAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatbotAnalysisRepository extends JpaRepository<ChatbotAnalysis, Long> {

    List<ChatbotAnalysis> findByTurnIdOrderByCreatedAtDesc(Long turnId);
}
