package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthConfig;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.monitor.feed.FeedStatus;
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

    private static FeedHealthRegistry.FeedHealth feed(String feedId, FeedStatus status, Long sinceSec) {
        return new FeedHealthRegistry.FeedHealth(feedId, status, sinceSec, null, 100L);
    }

    @Test
    void upFeed_marksOk() {
        when(registry.snapshot()).thenReturn(List.of(feed(FeedHealthConfig.BINANCE_TICKER, FeedStatus.UP, 1L)));

        evaluator.evaluate();

        verify(recorder).markOk(HealthCheckCatalog.FEED_BINANCE_TICKER.key());
    }

    @Test
    void staleFeed_marksFailWarn() {
        when(registry.snapshot()).thenReturn(List.of(feed(FeedHealthConfig.UPBIT, FeedStatus.STALE, 15L)));

        evaluator.evaluate();

        verify(recorder).markFail(eq(HealthCheckCatalog.FEED_UPBIT.key()),
                eq(HealthStatus.DEGRADED), eq("WARN"), contains("upbit"));
    }

    @Test
    void downFeed_marksFailCritical() {
        when(registry.snapshot()).thenReturn(
                List.of(feed(FeedHealthConfig.BINANCE_AGG_TRADE, FeedStatus.DOWN, 40L)));

        evaluator.evaluate();

        verify(recorder).markFail(eq(HealthCheckCatalog.FEED_BINANCE_AGGTRADE.key()),
                eq(HealthStatus.DOWN), eq("CRITICAL"), anyString());
    }

    @Test
    void unknownFeedId_skipped() {
        when(registry.snapshot()).thenReturn(List.of(feed("feed-not-in-catalog", FeedStatus.DOWN, 40L)));

        evaluator.evaluate();

        verify(recorder, never()).markOk(anyString());
        verify(recorder, never()).markFail(anyString(), any(), anyString(), anyString());
    }
}
