// [AGENT] 역할: 챗봇 질문 1개와 답변 1개를 묶어 저장하는 로그 엔티티 | 연관파일: ChatbotConversation.java, ChatbotTurnRepository.java
package com.chs.springboot.domain.chatbot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
        name = "chatbot_turn",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_chatbot_turn_request", columnNames = "request_id")
        },
        indexes = {
                @Index(name = "idx_chatbot_turn_conversation_index", columnList = "conversation_id, turn_index"),
                @Index(name = "idx_chatbot_turn_created", columnList = "created_at"),
                @Index(name = "idx_chatbot_turn_page_created", columnList = "page_id, created_at"),
                @Index(name = "idx_chatbot_turn_issue_created", columnList = "issue_type, created_at"),
                @Index(name = "idx_chatbot_turn_status_created", columnList = "status, created_at"),
                @Index(name = "idx_chatbot_turn_latency", columnList = "latency_ms")
        }
)
public class ChatbotTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "conversation_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_chatbot_turn_conversation")
    )
    private ChatbotConversation conversation;

    @Column(name = "request_id", length = 80, unique = true)
    private String requestId;

    @Column(name = "turn_index", nullable = false)
    private Integer turnIndex;

    @Column(name = "page_id", length = 40)
    private String pageId;

    @Column(name = "question", nullable = false, length = 2000)
    private String question;

    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;

    @Column(name = "search_query", length = 3000)
    private String searchQuery;

    @Column(name = "llm_question", length = 3000)
    private String llmQuestion;

    @Column(name = "page_context", length = 1000)
    private String pageContext;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChatbotTurnStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 40)
    private ChatbotIssueType issueType;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "evidence_count", nullable = false)
    private Integer evidenceCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void onPersist() {
        if (status == null) {
            status = ChatbotTurnStatus.SUCCESS;
        }
        if (issueType == null) {
            issueType = ChatbotIssueType.NONE;
        }
        if (evidenceCount == null) {
            evidenceCount = 0;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
