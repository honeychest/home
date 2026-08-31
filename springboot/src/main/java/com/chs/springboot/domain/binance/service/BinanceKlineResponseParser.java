package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class BinanceKlineResponseParser {

    private static final int MINIMUM_KLINE_FIELDS = 11;
    private static final int MAX_DECIMAL_PRECISION = 30;
    private static final int MAX_DECIMAL_SCALE = 16;

    private final ObjectMapper objectMapper;

    public BinanceKlineResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<BinanceKline> parse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("Binance kline 응답이 배열이 아닙니다");
            }

            List<BinanceKline> result = new ArrayList<>(root.size());
            for (int rowIndex = 0; rowIndex < root.size(); rowIndex++) {
                JsonNode row = root.get(rowIndex);
                if (!row.isArray() || row.size() < MINIMUM_KLINE_FIELDS) {
                    throw new IllegalArgumentException("Binance kline 행의 필드 수가 부족합니다: index=" + rowIndex);
                }
                BinanceKline kline = new BinanceKline(
                        longValue(row, 0, rowIndex, "openTimeMs"),
                        decimalValue(row, 1, rowIndex, "openPrice"),
                        decimalValue(row, 2, rowIndex, "highPrice"),
                        decimalValue(row, 3, rowIndex, "lowPrice"),
                        decimalValue(row, 4, rowIndex, "closePrice"),
                        decimalValue(row, 5, rowIndex, "volume"),
                        longValue(row, 6, rowIndex, "closeTimeMs"),
                        decimalValue(row, 7, rowIndex, "quoteVolume"),
                        longValue(row, 8, rowIndex, "tradeCount"),
                        decimalValue(row, 9, rowIndex, "takerBuyBaseVolume"),
                        decimalValue(row, 10, rowIndex, "takerBuyQuoteVolume")
                );
                validate(kline, rowIndex);
                result.add(kline);
            }
            return result;
        } catch (JsonProcessingException | NumberFormatException e) {
            throw new IllegalArgumentException("Binance kline 응답을 파싱할 수 없습니다", e);
        }
    }

    private static long longValue(JsonNode row, int index, int rowIndex, String fieldName) {
        String text = textValue(row, index, rowIndex, fieldName);
        if (text == null) {
            throw new IllegalArgumentException("Binance kline 숫자 필드가 없습니다: index=" + rowIndex);
        }
        return Long.parseLong(text);
    }

    private static BigDecimal decimalValue(JsonNode row, int index, int rowIndex, String fieldName) {
        String text = textValue(row, index, rowIndex, fieldName);
        if (text == null) {
            throw new IllegalArgumentException("Binance kline 소수 필드가 없습니다: index=" + rowIndex);
        }
        BigDecimal value = new BigDecimal(text);
        if (value.signum() < 0
                || value.precision() > MAX_DECIMAL_PRECISION
                || value.scale() > MAX_DECIMAL_SCALE) {
            throw new IllegalArgumentException("Binance kline 숫자 범위를 벗어났습니다: " + fieldName);
        }
        return value;
    }

    private static String textValue(JsonNode row, int index, int rowIndex, String fieldName) {
        JsonNode value = row.get(index);
        if (value == null || value.isNull() || !value.isValueNode()) {
            throw new IllegalArgumentException("Binance kline 숫자 필드가 없습니다: " + fieldName + " index=" + rowIndex);
        }
        return value.asText();
    }

    private static void validate(BinanceKline kline, int rowIndex) {
        if (kline.openTimeMs() < 0
                || kline.closeTimeMs() < kline.openTimeMs()
                || kline.tradeCount() < 0
                || kline.highPrice().compareTo(kline.lowPrice()) < 0
                || kline.openPrice().compareTo(kline.lowPrice()) < 0
                || kline.openPrice().compareTo(kline.highPrice()) > 0
                || kline.closePrice().compareTo(kline.lowPrice()) < 0
                || kline.closePrice().compareTo(kline.highPrice()) > 0
                || kline.takerBuyBaseVolume().compareTo(kline.volume()) > 0
                || kline.takerBuyQuoteVolume().compareTo(kline.quoteVolume()) > 0) {
            throw new IllegalArgumentException("Binance kline 값 관계가 올바르지 않습니다: index=" + rowIndex);
        }
    }
}
