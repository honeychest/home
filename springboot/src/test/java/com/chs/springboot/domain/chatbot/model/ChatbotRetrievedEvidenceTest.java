package com.chs.springboot.domain.chatbot.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatbotRetrievedEvidenceTest {

    @Test
    @DisplayName("contentPreview는 DB 컬럼 길이를 넘지 않도록 자른다")
    void setContentPreview_truncatesToColumnLength() {
        ChatbotRetrievedEvidence evidence = new ChatbotRetrievedEvidence();

        evidence.setContentPreview("a".repeat(1001));

        assertThat(evidence.getContentPreview()).hasSize(1000);
        assertThat(evidence.getContentPreview()).endsWith("...");
    }
}
