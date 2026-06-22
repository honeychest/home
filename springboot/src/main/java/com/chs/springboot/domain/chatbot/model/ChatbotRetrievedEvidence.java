// [AGENT] 역할: 챗봇 답변 생성 전에 검색된 근거 청크 로그 엔티티 | 연관파일: ChatbotTurn.java, ChatbotRetrievedEvidenceRepository.java
package com.chs.springboot.domain.chatbot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
        name = "chatbot_retrieved_evidence",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_chatbot_evidence_turn_rank", columnNames = {"turn_id", "rank_no"})
        },
        indexes = {
                @Index(name = "idx_chatbot_evidence_source", columnList = "source")
        }
)
public class ChatbotRetrievedEvidence {

    private static final int CONTENT_PREVIEW_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "turn_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_chatbot_evidence_turn")
    )
    private ChatbotTurn turn;

    @Column(name = "rank_no", nullable = false)
    private Integer rankNo;

    @Column(name = "source", nullable = false, length = 500)
    private String source;

    @Column(name = "symbol", length = 255)
    private String symbol;

    @Column(name = "line_range", length = 40)
    private String lineRange;

    @Column(name = "score", precision = 12, scale = 8)
    private BigDecimal score;

    @Column(name = "content_preview", length = CONTENT_PREVIEW_MAX_LENGTH)
    private String contentPreview;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public void setContentPreview(String contentPreview) {
        if (contentPreview == null || contentPreview.length() <= CONTENT_PREVIEW_MAX_LENGTH) {
            this.contentPreview = contentPreview;
            return;
        }
        this.contentPreview = contentPreview.substring(0, CONTENT_PREVIEW_MAX_LENGTH - 3) + "...";
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
