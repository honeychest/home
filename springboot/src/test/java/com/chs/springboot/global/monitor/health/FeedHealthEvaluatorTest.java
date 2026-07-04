package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthConfig;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedHealthEvaluatorTest {

    private final FeedHealthRegistry registry = mock(FeedHealthRegistry.class);
    private final HealthCheckRecorder recorder = mock(HealthCheckRecorder.class);
    private final FeedHealthEvaluator evaluator = new FeedHealthEvaluator(registry, recorder);

    private static FeedHealthRegistry.FeedHealth feed(String feedId, HealthStatus status, Long sinceSec) {
        return new FeedHealthRegistry.FeedHealth(feedId, status, sinceSec, null, 100L);
    }

    @Test
    void upFeed_recordsUp() {
        when(registry.snapshot()).thenReturn(List.of(feed(FeedHealthConfig.BINANCE_TICKER, HealthStatus.UP, 1L)));

        evaluator.evaluate();

        verify(recorder).record(eq(HealthCheckCatalog.FEED_BINANCE_TICKER.key()),
                eq(HealthStatus.UP), anyString());
    }

    @Test
    void degradedFeed_recordsDegraded() {
        when(registry.snapshot()).thenReturn(List.of(feed(FeedHealthConfig.UPBIT, HealthStatus.DEGRADED, 15L)));

        evaluator.evaluate();

        verify(recorder).record(eq(HealthCheckCatalog.FEED_UPBIT.key()),
                eq(HealthStatus.DEGRADED), contains("upbit"));
    }

    @Test
    void downFeed_recordsDown() {
        when(registry.snapshot()).thenReturn(
                List.of(feed(FeedHealthConfig.BINANCE_AGG_TRADE, HealthStatus.DOWN, 40L)));

        evaluator.evaluate();

        verify(recorder).record(eq(HealthCheckCatalog.FEED_BINANCE_AGGTRADE.key()),
                eq(HealthStatus.DOWN), anyString());
    }

    @Test
    void unknownFeedId_skipped() {
        when(registry.snapshot()).thenReturn(List.of(feed("feed-not-in-catalog", HealthStatus.DOWN, 40L)));

        evaluator.evaluate();

        verify(recorder, never()).record(anyString(), any(), any());
    }
}
