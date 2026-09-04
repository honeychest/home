package com.chs.springboot.domain.binance.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceAnalysisChatClientConfigTest {

    @Test
    void systemPromptListsDailyInterval() {
        assertThat(BinanceAnalysisChatClientConfig.SYSTEM_PROMPT)
                .contains("1m, 5m, 15m, 4h, 1d");
    }
}
