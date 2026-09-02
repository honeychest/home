package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.analysis.KlineSeriesValidator;
import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class BinanceKlineRestClient {

    private static final String INTERVAL = "1m";
    private static final int PAGE_LIMIT = 1000;
    private static final int LATEST_CANDLE_MAX_LIMIT = 1000;

    private final RestClient spotClient;
    private final RestClient futuresClient;
    private final BinanceKlineResponseParser responseParser;
    private final ObjectMapper objectMapper;

    public BinanceKlineRestClient(
            RestClient.Builder restClientBuilder,
            BinanceKlineResponseParser responseParser,
            @Value("${binance.rest.spot.base-url:https://api.binance.com}") String spotBaseUrl,
            @Value("${binance.rest.futures.base-url:https://fapi.binance.com}") String futuresBaseUrl) {
        this(restClientBuilder, responseParser, new ObjectMapper(), spotBaseUrl, futuresBaseUrl);
    }

    @Autowired
    public BinanceKlineRestClient(
            RestClient.Builder restClientBuilder,
            BinanceKlineResponseParser responseParser,
            ObjectMapper objectMapper,
            @Value("${binance.rest.spot.base-url:https://api.binance.com}") String spotBaseUrl,
            @Value("${binance.rest.futures.base-url:https://fapi.binance.com}") String futuresBaseUrl) {
        this.spotClient = restClientBuilder.clone().baseUrl(spotBaseUrl).build();
        this.futuresClient = restClientBuilder.clone().baseUrl(futuresBaseUrl).build();
        this.responseParser = responseParser;
        this.objectMapper = objectMapper;
    }

    /**
     * 선물 서버 시각으로 현재 진행봉 경계를 계산한 뒤, 확정봉만 반환한다.
     * marketType 인자를 받지 않아 이 분석 경로가 spot 엔드포인트로 갈 수 없게 한다.
     */
    public List<BinanceKline> fetchLatestClosedFutures(
            String symbol, BinanceKlineInterval interval, int count) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Binance symbol은 비어 있을 수 없습니다");
        }
        if (interval == null || count <= 0 || count > LATEST_CANDLE_MAX_LIMIT) {
            throw new IllegalArgumentException("선물 최신 kline 요청 범위가 올바르지 않습니다");
        }

        long exchangeTimeMs = fetchFuturesServerTime();
        long endTimeMsExclusive = interval.latestClosedEndExclusive(exchangeTimeMs);
        long startTimeMs = Math.max(0L, endTimeMsExclusive - Math.multiplyExact(interval.intervalMs(), count));
        String responseBody = futuresClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/fapi/v1/klines")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", interval.label())
                        .queryParam("startTime", startTimeMs)
                        .queryParam("endTime", endTimeMsExclusive - 1L)
                        .queryParam("limit", count)
                        .build())
                .retrieve()
                .body(String.class);

        List<BinanceKline> confirmed = responseParser.parse(responseBody).stream()
                .filter(kline -> kline.openTimeMs() < endTimeMsExclusive)
                .filter(kline -> kline.closeTimeMs() < exchangeTimeMs)
                .toList();
        KlineSeriesValidator.validate(confirmed, interval.intervalMs());
        int fromIndex = Math.max(0, confirmed.size() - count);
        return List.copyOf(confirmed.subList(fromIndex, confirmed.size()));
    }

    private long fetchFuturesServerTime() {
        String responseBody = futuresClient.get()
                .uri("/fapi/v1/time")
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            long serverTime = root == null ? -1L : root.path("serverTime").asLong(-1L);
            if (serverTime < 0) {
                throw new IllegalArgumentException("Binance 선물 서버 시각이 없습니다");
            }
            return serverTime;
        } catch (Exception e) {
            throw new IllegalStateException("Binance 선물 서버 시각을 읽을 수 없습니다", e);
        }
    }

    public List<BinanceKline> fetchPage(
            String symbol,
            String marketType,
            long startTimeMs,
            long endTimeMsExclusive) {
        if (endTimeMsExclusive <= startTimeMs) {
            return List.of();
        }

        RestClient client;
        String path;
        if ("SPOT".equals(marketType)) {
            client = spotClient;
            path = "/api/v3/klines";
        } else if ("FUTURES".equals(marketType)) {
            client = futuresClient;
            path = "/fapi/v1/klines";
        } else {
            throw new IllegalArgumentException("지원하지 않는 Binance marketType: " + marketType);
        }

        String responseBody = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path(path)
                        .queryParam("symbol", symbol)
                        .queryParam("interval", INTERVAL)
                        .queryParam("startTime", startTimeMs)
                        .queryParam("endTime", endTimeMsExclusive - 1L)
                        .queryParam("limit", PAGE_LIMIT)
                        .build())
                .retrieve()
                .body(String.class);
        return responseParser.parse(responseBody);
    }
}
