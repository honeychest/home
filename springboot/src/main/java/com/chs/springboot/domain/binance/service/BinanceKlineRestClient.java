package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class BinanceKlineRestClient {

    private static final String INTERVAL = "1m";
    private static final int PAGE_LIMIT = 1000;

    private final RestClient spotClient;
    private final RestClient futuresClient;
    private final BinanceKlineResponseParser responseParser;

    public BinanceKlineRestClient(
            RestClient.Builder restClientBuilder,
            BinanceKlineResponseParser responseParser,
            @Value("${binance.rest.spot.base-url:https://api.binance.com}") String spotBaseUrl,
            @Value("${binance.rest.futures.base-url:https://fapi.binance.com}") String futuresBaseUrl) {
        this.spotClient = restClientBuilder.clone().baseUrl(spotBaseUrl).build();
        this.futuresClient = restClientBuilder.clone().baseUrl(futuresBaseUrl).build();
        this.responseParser = responseParser;
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
