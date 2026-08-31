package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinanceKlineResponseParserTest {

    private final BinanceKlineResponseParser parser = new BinanceKlineResponseParser(new ObjectMapper());

    @Test
    void parsesBinanceKlineArray() {
        String response = "[[1700000000000,\"37000.10\",\"37100.20\",\"36900.30\",\"37050.40\",\"12.5\",1700000059999,\"463126.5\",42,\"6.25\",\"231563.25\",\"0\"]]";

        BinanceKline kline = parser.parse(response).get(0);

        assertEquals(1700000000000L, kline.openTimeMs());
        assertEquals(new BigDecimal("37000.10"), kline.openPrice());
        assertEquals(new BigDecimal("37100.20"), kline.highPrice());
        assertEquals(new BigDecimal("36900.30"), kline.lowPrice());
        assertEquals(new BigDecimal("37050.40"), kline.closePrice());
        assertEquals(new BigDecimal("12.5"), kline.volume());
        assertEquals(1700000059999L, kline.closeTimeMs());
        assertEquals(new BigDecimal("463126.5"), kline.quoteVolume());
        assertEquals(42L, kline.tradeCount());
        assertEquals(new BigDecimal("6.25"), kline.takerBuyBaseVolume());
        assertEquals(new BigDecimal("231563.25"), kline.takerBuyQuoteVolume());
    }

    @Test
    void rejectsMalformedKlineRow() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("[[1700000000000,\"1\"]]"));
    }

    @Test
    void rejectsValuesOutsideTempColumnRange() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "[[1700000000000,\"1\",\"2\",\"0.5\",\"1.5\",\"-1\",1700000059999,\"15\",42,\"5\",\"7.5\"]]"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "[[1700000000000,\"1\",\"2\",\"0.5\",\"1.5\",\"123456789012345.12345678901234567\",1700000059999,\"15\",42,\"5\",\"7.5\"]]"));
    }

    @Test
    void rejectsInconsistentKlineRelationships() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "[[1700000000000,\"1\",\"2\",\"3\",\"1.5\",\"10\",1700000059999,\"15\",42,\"5\",\"7.5\"]]"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "[[1700000000000,\"1\",\"2\",\"0.5\",\"1.5\",\"10\",1700000059999,\"15\",42,\"11\",\"7.5\"]]"));
    }
}
