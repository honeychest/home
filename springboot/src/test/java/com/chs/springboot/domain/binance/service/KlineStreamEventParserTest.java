package com.chs.springboot.domain.binance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KlineStreamEventParserTest {

    private KlineStreamEventParser parser;

    @BeforeEach
    void setUp() {
        parser = new KlineStreamEventParser(new ObjectMapper());
    }

    @Test
    @DisplayName("확정봉(x=true) 파싱")
    void parse_closedCandle() {
        String json = "{\"e\":\"kline\",\"s\":\"BTCUSDT\",\"k\":{" +
                "\"t\":1000,\"T\":1059999,\"o\":\"100\",\"h\":\"110\",\"l\":\"90\",\"c\":\"105\"," +
                "\"v\":\"10\",\"q\":\"1000\",\"n\":5,\"x\":true,\"V\":\"6\",\"Q\":\"600\"}}";

        KlineStreamEventParser.KlineStreamEvent event = parser.parse(json);

        assertThat(event).isNotNull();
        assertThat(event.closed()).isTrue();
        assertThat(event.kline().openTimeMs()).isEqualTo(1000L);
        assertThat(event.kline().closePrice()).isEqualByComparingTo("105");
        assertThat(event.kline().tradeCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("진행 중인 봉(x=false) 파싱")
    void parse_partialCandle() {
        String json = "{\"e\":\"kline\",\"s\":\"BTCUSDT\",\"k\":{" +
                "\"t\":1000,\"T\":1059999,\"o\":\"100\",\"h\":\"110\",\"l\":\"90\",\"c\":\"105\"," +
                "\"v\":\"10\",\"q\":\"1000\",\"n\":5,\"x\":false,\"V\":\"6\",\"Q\":\"600\"}}";

        KlineStreamEventParser.KlineStreamEvent event = parser.parse(json);

        assertThat(event.closed()).isFalse();
    }

    @Test
    @DisplayName("깨진 JSON은 null 반환")
    void parse_brokenJson_returnsNull() {
        assertThat(parser.parse("not-json")).isNull();
    }

    @Test
    @DisplayName("k 필드 없으면 null 반환")
    void parse_missingKField_returnsNull() {
        assertThat(parser.parse("{\"e\":\"kline\"}")).isNull();
    }
}
