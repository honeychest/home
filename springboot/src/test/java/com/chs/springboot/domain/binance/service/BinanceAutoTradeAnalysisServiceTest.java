package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.analysis.MarketSnapshotCalculator;
import com.chs.springboot.domain.binance.model.BinanceAnalysisStatus;
import com.chs.springboot.domain.binance.model.BinanceAnalysisTurn;
import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;
import com.chs.springboot.domain.binance.model.IntervalMarketSnapshot;
import com.chs.springboot.domain.binance.model.MarketDataStatus;
import com.chs.springboot.domain.binance.model.MultiTimeframeMarketSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BinanceAutoTradeAnalysisServiceTest {

    private ExecutorService executor;
    private BinanceAutoTradeAnalysisService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsEmptyTooLongAndDuplicateQuestionsWithoutCallingLlm() {
        LiveMarketDataService live = mock(LiveMarketDataService.class);
        ChatClient chatClient = mockChatClient("답변");
        createService(live, chatClient, 200);

        assertThat(service.ask("   ", List.of()).status()).isEqualTo(BinanceAnalysisStatus.EMPTY_QUESTION);
        assertThat(service.ask("a".repeat(1_001), List.of()).status())
                .isEqualTo(BinanceAnalysisStatus.QUESTION_TOO_LONG);
        assertThat(service.ask("현재가", List.of(new BinanceAnalysisTurn("user", "현재가"))).status())
                .isEqualTo(BinanceAnalysisStatus.DUPLICATE_QUESTION);
    }

    @Test
    void askReturnsAsOfAndPartialStatusWithoutPersistingConversation() {
        LiveMarketDataService live = mockLive(true);
        ChatClient chatClient = mockChatClient("현재 시장 답변");
        createService(live, chatClient, 200);

        var result = service.ask("지금 얼마야?", List.of(new BinanceAnalysisTurn("user", "이전 질문")));

        assertThat(result.status()).isEqualTo(BinanceAnalysisStatus.READY);
        assertThat(result.answer()).isEqualTo("현재 시장 답변");
        assertThat(result.asOfMs()).isEqualTo(1700000000000L);
    }

    @Test
    void askReturnsTimeoutWhenDedicatedLlmCallExceedsLimit() {
        LiveMarketDataService live = mockLive(true);
        ChatClient chatClient = mockChatClient(null);
        ChatClient.CallResponseSpec response = mock(CallResponseSpec.class);
        ChatClientRequestSpec request = mockRequest(response);
        when(chatClient.prompt()).thenReturn(request);
        when(response.content()).thenAnswer(invocation -> {
            Thread.sleep(500);
            return "늦은 답변";
        });
        createService(live, chatClient, 20);

        var result = service.ask("지금 얼마야?", List.of());

        assertThat(result.status()).isEqualTo(BinanceAnalysisStatus.LLM_TIMEOUT);
    }

    @Test
    void discardsAnswerWhenLeadershipChangesWhileLlmIsResponding() {
        AtomicBoolean leader = new AtomicBoolean(true);
        LiveMarketDataService live = mockLive(leader);
        ChatClient chatClient = mockChatClient("답변");
        ChatClient.CallResponseSpec response = mock(CallResponseSpec.class);
        ChatClientRequestSpec request = mockRequest(response);
        when(chatClient.prompt()).thenReturn(request);
        when(response.content()).thenAnswer(invocation -> {
            leader.set(false);
            return "폐기될 답변";
        });
        createService(live, chatClient, 2_000);

        var result = service.ask("지금 얼마야?", List.of());

        assertThat(result.status()).isEqualTo(BinanceAnalysisStatus.NOT_LEADER);
        assertThat(result.answer()).isNull();
    }

    @Test
    void refreshDoesNotOverlap() throws Exception {
        LiveMarketDataService live = mockLive(true);
        ChatClient chatClient = mockChatClient(null);
        ChatClient.CallResponseSpec response = mock(CallResponseSpec.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ChatClientRequestSpec request = mockRequest(response);
        when(chatClient.prompt()).thenReturn(request);
        when(response.content()).thenAnswer(invocation -> {
            calls.incrementAndGet();
            started.countDown();
            release.await(1, TimeUnit.SECONDS);
            return "자동 답변";
        });
        createService(live, chatClient, 1_000);

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<com.chs.springboot.domain.binance.model.BinanceAnalysisResponse> first =
                    callers.submit(() -> service.refreshAnalysis());
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            var second = service.refreshAnalysis();
            release.countDown();

            assertThat(second.status()).isEqualTo(BinanceAnalysisStatus.ANALYSIS_IN_PROGRESS);
            assertThat(first.get(1, TimeUnit.SECONDS).status()).isEqualTo(BinanceAnalysisStatus.READY);
            assertThat(calls).hasValue(1);
            verify(response).content();
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void refreshTimeoutIsReportedWithoutLeavingAReadyResult() {
        LiveMarketDataService live = mockLive(true);
        ChatClient chatClient = mockChatClient(null);
        ChatClient.CallResponseSpec response = mock(CallResponseSpec.class);
        ChatClientRequestSpec request = mockRequest(response);
        when(chatClient.prompt()).thenReturn(request);
        when(response.content()).thenAnswer(invocation -> {
            Thread.sleep(500);
            return "늦은 자동 답변";
        });
        createService(live, chatClient, 20);

        var result = service.refreshAnalysis();
        assertThat(result.status()).isEqualTo(BinanceAnalysisStatus.LLM_TIMEOUT);

        assertThat(service.getLatestAnalysis().status()).isEqualTo(BinanceAnalysisStatus.NO_ANALYSIS);
        assertThat(service.getLatestAnalysis().failureStatus()).isEqualTo(BinanceAnalysisStatus.LLM_TIMEOUT);
    }

    @Test
    void refreshFailureMakesPreviousAnswerStale() {
        LiveMarketDataService live = mockLive(true);
        ChatClient chatClient = mock(ChatClient.class);
        CallResponseSpec response = mock(CallResponseSpec.class);
        ChatClientRequestSpec request = mockRequest(response);
        when(chatClient.prompt()).thenReturn(request);
        when(response.content()).thenReturn("첫 자동 답변")
                .thenThrow(new IllegalStateException("LLM down"));
        createService(live, chatClient, 200);

        var first = service.refreshAnalysis();
        assertThat(first.status()).isEqualTo(BinanceAnalysisStatus.READY);
        var second = service.refreshAnalysis();
        assertThat(second.status()).isEqualTo(BinanceAnalysisStatus.LLM_ERROR);

        var result = service.getLatestAnalysis();
        assertThat(result.status()).isEqualTo(BinanceAnalysisStatus.STALE);
        assertThat(result.failureStatus()).isEqualTo(BinanceAnalysisStatus.LLM_ERROR);
        assertThat(result.answer()).isEqualTo("첫 자동 답변");
    }

    private void createService(LiveMarketDataService live, ChatClient chatClient, long timeoutMs) {
        executor = Executors.newFixedThreadPool(2);
        service = new BinanceAutoTradeAnalysisService(live, chatClient, timeoutMs, executor);
    }

    private LiveMarketDataService mockLive(boolean leader) {
        return mockLive(new AtomicBoolean(leader));
    }

    private LiveMarketDataService mockLive(AtomicBoolean leader) {
        LiveMarketDataService live = mock(LiveMarketDataService.class);
        when(live.isLeader()).thenAnswer(invocation -> leader.get());
        when(live.leadershipGeneration()).thenReturn(1L);
        when(live.buildSnapshot()).thenReturn(snapshot());
        return live;
    }

    private ChatClient mockChatClient(String answer) {
        ChatClient chatClient = mock(ChatClient.class);
        CallResponseSpec response = mock(CallResponseSpec.class);
        ChatClientRequestSpec request = mockRequest(response);
        when(chatClient.prompt()).thenReturn(request);
        when(response.content()).thenReturn(answer);
        return chatClient;
    }

    private ChatClientRequestSpec mockRequest(CallResponseSpec response) {
        ChatClientRequestSpec request = mock(ChatClientRequestSpec.class);
        when(request.messages(anyList())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.toolCallbacks(anyList())).thenReturn(request);
        when(request.call()).thenReturn(response);
        return request;
    }

    private MultiTimeframeMarketSnapshot snapshot() {
        List<IntervalMarketSnapshot> intervals = List.of(BinanceKlineInterval.values()).stream()
                .map(interval -> {
                    List<BinanceKline> candles = java.util.stream.IntStream.range(0, 60)
                            .mapToObj(index -> candle(interval, index, 100 + index))
                            .toList();
                    return MarketSnapshotCalculator.calculate(interval, candles, null,
                            1700000000000L, MarketDataStatus.READY, "");
                })
                .toList();
        return new MultiTimeframeMarketSnapshot("BTCUSDT", "FUTURES", 1700000000000L,
                true, 1L, intervals, true);
    }

    private BinanceKline candle(BinanceKlineInterval interval, int index, int close) {
        BigDecimal price = BigDecimal.valueOf(close);
        return new BinanceKline(index * interval.intervalMs(), price, price.add(BigDecimal.ONE),
                price.subtract(BigDecimal.ONE), price, BigDecimal.TEN,
                (index + 1L) * interval.intervalMs() - 1L, BigDecimal.TEN, 1L,
                new BigDecimal("6"), new BigDecimal("6"));
    }
}
