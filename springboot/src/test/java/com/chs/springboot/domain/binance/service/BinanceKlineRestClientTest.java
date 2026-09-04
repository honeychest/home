package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BinanceKlineRestClientTest {

    private MockRestServiceServer server;
    private BinanceKlineRestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BinanceKlineRestClient(
                builder,
                new BinanceKlineResponseParser(new ObjectMapper()),
                "https://spot.test",
                "https://futures.test");
    }

    @Test
    void callsSpotKlineEndpointThroughRestClientBuilder() {
        server.expect(requestTo("https://spot.test/api/v3/klines?symbol=BTCUSDT&interval=1m&startTime=60000&endTime=119999&limit=1000"))
                .andExpect(method(GET))
                .andExpect(queryParam("symbol", equalTo("BTCUSDT")))
                .andExpect(queryParam("interval", equalTo("1m")))
                .andExpect(queryParam("startTime", equalTo("60000")))
                .andExpect(queryParam("endTime", equalTo("119999")))
                .andExpect(queryParam("limit", equalTo("1000")))
                .andRespond(withSuccess(
                        "[[60000,\"1\",\"2\",\"0.5\",\"1.5\",\"10\",119999,\"15\",3,\"5\",\"7.5\",\"0\"]]",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(1, client.fetchPage("BTCUSDT", "SPOT", 60_000L, 120_000L).size());
        server.verify();
    }

    @Test
    void callsFuturesKlineEndpointThroughRestClientBuilder() {
        server.expect(requestTo("https://futures.test/fapi/v1/klines?symbol=ENAUSDT&interval=1m&startTime=0&endTime=59999&limit=1000"))
                .andExpect(method(GET))
                .andExpect(queryParam("symbol", equalTo("ENAUSDT")))
                .andExpect(queryParam("interval", equalTo("1m")))
                .andExpect(queryParam("startTime", equalTo("0")))
                .andExpect(queryParam("endTime", equalTo("59999")))
                .andExpect(queryParam("limit", equalTo("1000")))
                .andRespond(withSuccess("[]", org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(0, client.fetchPage("ENAUSDT", "FUTURES", 0L, 60_000L).size());
        server.verify();
    }

    @Test
    void fetchPageWithFiveMinuteIntervalSendsFiveMinuteLabel() {
        server.expect(requestTo("https://spot.test/api/v3/klines?symbol=BTCUSDT&interval=5m&startTime=0&endTime=299999&limit=1000"))
                .andExpect(method(GET))
                .andExpect(queryParam("interval", equalTo("5m")))
                .andRespond(withSuccess(
                        "[[0,\"1\",\"2\",\"0.5\",\"1.5\",\"10\",299999,\"15\",3,\"5\",\"7.5\",\"0\"]]",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(1, client.fetchPage("BTCUSDT", "SPOT", 0L, 300_000L, BinanceKlineInterval.FIVE_MINUTES).size());
        server.verify();
    }

    @Test
    void latestClosedFuturesUsesFuturesServerTimeAndExcludesInProgressCandle() {
        server.expect(requestTo("https://futures.test/fapi/v1/time"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"serverTime\":1234567}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://futures.test/fapi/v1/klines?symbol=BTCUSDT&interval=1m&startTime=1080000&endTime=1199999&limit=2"))
                .andExpect(method(GET))
                .andExpect(queryParam("interval", equalTo("1m")))
                .andExpect(queryParam("limit", equalTo("2")))
                .andRespond(withSuccess(
                        "[[1080000,\"1\",\"2\",\"0.5\",\"1.5\",\"10\",1139999,\"15\",3,\"5\",\"7.5\",\"0\"],"
                                + "[1140000,\"1\",\"2\",\"0.5\",\"1.5\",\"10\",1199999,\"15\",3,\"5\",\"7.5\",\"0\"],"
                                + "[1200000,\"1\",\"2\",\"0.5\",\"1.5\",\"10\",1259999,\"15\",3,\"5\",\"7.5\",\"0\"]]",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        var result = client.fetchLatestClosedFutures("BTCUSDT", BinanceKlineInterval.ONE_MINUTE, 2);

        assertEquals(2, result.size());
        assertEquals(1_140_000L, result.get(1).openTimeMs());
        server.verify();
    }
}
