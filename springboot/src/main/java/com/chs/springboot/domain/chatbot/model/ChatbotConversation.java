// [AGENT] 역할: 챗봇 대화 묶음 엔티티 | 연관파일: ChatbotTurn.java, ChatbotConversationRepository.java
package com.chs.springboot.domain.chatbot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
        name = "chatbot_conversation",
        indexes = {
                @Index(name = "idx_chatbot_conversation_session_started", columnList = "session_id, started_at"),
                @Index(name = "idx_chatbot_conversation_page_last", columnList = "page_id, last_message_at"),
                @Index(name = "idx_chatbot_conversation_env_last", columnList = "source_env, last_message_at")
        }
)
public class ChatbotConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 80)
    private String sessionId;

    @Column(name = "page_id", length = 40)
    private String pageId;

    @Column(name = "source_env", nullable = false, length = 20)
    private String sourceEnv;

    @Column(name = "message_count", nullable = false)
    private Integer messageCount;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "last_message_at", nullable = false)
    private LocalDateTime lastMessageAt;

    @PrePersist
    void onPersist() {
        LocalDateTime now = LocalDateTime.now();
        if (sourceEnv == null || sourceEnv.isBlank()) {
            sourceEnv = "unknown";
        }
        if (messageCount == null) {
            messageCount = 0;
        }
        if (startedAt == null) {
            startedAt = now;
        }
        if (lastMessageAt == null) {
            lastMessageAt = startedAt;
        }
    }
}
