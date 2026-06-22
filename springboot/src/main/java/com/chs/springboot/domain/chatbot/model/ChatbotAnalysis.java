// [AGENT] 역할: 챗봇 질문답변 턴에 대한 사후 분석 결과 엔티티 | 연관파일: ChatbotTurn.java, ChatbotAnalysisRepository.java
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
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
        name = "chatbot_analysis",
        indexes = {
                @Index(name = "idx_chatbot_analysis_turn_created", columnList = "turn_id, created_at"),
                @Index(name = "idx_chatbot_analysis_issue_created", columnList = "issue_type, created_at")
        }
)
public class ChatbotAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "turn_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_chatbot_analysis_turn")
    )
    private ChatbotTurn turn;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 40)
    private ChatbotIssueType issueType;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "suggestion", columnDefinition = "TEXT")
    private String suggestion;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "created_by", nullable = false, length = 20)
    private ChatbotAnalysisAuthor createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onPersist() {
        if (issueType == null) {
            issueType = ChatbotIssueType.NONE;
        }
        if (createdBy == null) {
            createdBy = ChatbotAnalysisAuthor.SYSTEM;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
