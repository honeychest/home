// [AGENT] 바이낸스 kline 웹소켓 스트림(<symbol>@kline_<interval>) 메시지 하나를 파싱한다.
// REST klines 응답(BinanceKlineResponseParser, 배열 형태)과 모양이 달라 별도로 둔다.
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class KlineStreamEventParser {

    private static final Logger log = LoggerFactory.getLogger(KlineStreamEventParser.class);

    private final ObjectMapper objectMapper;

    public KlineStreamEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public KlineStreamEvent parse(String rawJson) {
        try {
            JsonNode k = objectMapper.readTree(rawJson).get("k");
            if (k == null) {
                return null;
            }
            BinanceKline kline = new BinanceKline(
                    k.get("t").asLong(),
                    new BigDecimal(k.get("o").asText()),
                    new BigDecimal(k.get("h").asText()),
                    new BigDecimal(k.get("l").asText()),
                    new BigDecimal(k.get("c").asText()),
                    new BigDecimal(k.get("v").asText()),
                    k.get("T").asLong(),
                    new BigDecimal(k.get("q").asText()),
                    k.get("n").asLong(),
                    new BigDecimal(k.get("V").asText()),
                    new BigDecimal(k.get("Q").asText())
            );
            return new KlineStreamEvent(kline, k.get("x").asBoolean());
        } catch (Exception e) {
            log.warn("[KlineStreamEventParser] 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    /** closed=true면 확정봉, false면 아직 진행 중인 봉(현재가 갱신용으로만 사용). */
    public record KlineStreamEvent(BinanceKline kline, boolean closed) {
    }
}
