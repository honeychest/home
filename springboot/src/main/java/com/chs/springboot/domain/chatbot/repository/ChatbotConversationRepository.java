// [AGENT] 역할: 챗봇 대화 묶음 조회 Repository | 연관파일: ChatbotConversation.java
package com.chs.springboot.domain.chatbot.repository;

import com.chs.springboot.domain.chatbot.model.ChatbotConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatbotConversationRepository extends JpaRepository<ChatbotConversation, Long> {

    Optional<ChatbotConversation> findFirstBySessionIdAndSourceEnvOrderByLastMessageAtDesc(
            String sessionId,
            String sourceEnv
    );
}
