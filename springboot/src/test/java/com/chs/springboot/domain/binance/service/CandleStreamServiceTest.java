package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.websocket.CandleWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandleStreamServiceTest {

    private static final String SYMBOL = "BTCUSDT";

    @Test
    void inProgressPollsThreeIntervalsUseBaseVolumeAndSkipUnchangedValues() throws Exception {
        CandleWebSocketHandler handler = mock(CandleWebSocketHandler.class);
        SignalCandleSource source = mock(SignalCandleSource.class);
        CandleStreamService service = new CandleStreamService(handler, new ObjectMapper(), source);

        when(handler.getActiveSymbols(anyString())).thenReturn(Set.of(SYMBOL));
        when(source.find(eq(SYMBOL), eq(SignalCandleSource.Interval.ONE_MINUTE), anyLong(), anyLong(),
                eq(SignalCandleSource.QueryMode.IN_PROGRESS)))
                .thenReturn(List.of(candle(1_700_000_000_000L, 10, 100, 3, 5)));
        when(source.find(eq(SYMBOL), eq(SignalCandleSource.Interval.FIVE_MINUTES), anyLong(), anyLong(),
                eq(SignalCandleSource.QueryMode.IN_PROGRESS)))
                .thenReturn(List.of(candle(1_700_000_000_000L, 20, 200, 4, 6)));
        when(source.find(eq(SYMBOL), eq(SignalCandleSource.Interval.FIFTEEN_MINUTES), anyLong(), anyLong(),
                eq(SignalCandleSource.QueryMode.IN_PROGRESS)))
                .thenReturn(List.of(candle(1_700_000_000_000L, 30, 300, 5, 7)));

        service.broadcastInProgress1m();
        service.broadcastInProgress5m();
        service.broadcastInProgress15m();
        service.broadcastInProgress1m();
        service.broadcastInProgress5m();
        service.broadcastInProgress15m();

        ArgumentCaptor<String> oneMinutePayload = ArgumentCaptor.forClass(String.class);
        verify(handler, times(1)).broadcastCandle(eq(SYMBOL), eq("1m"), oneMinutePayload.capture());
        verify(handler, times(1)).broadcastCandle(eq(SYMBOL), eq("5m"), any(String.class));
        verify(handler, times(1)).broadcastCandle(eq(SYMBOL), eq("15m"), any(String.class));
        assertThat(new ObjectMapper().readTree(oneMinutePayload.getValue()).get("volume").doubleValue())
                .isEqualTo(3.0);
    }

    @Test
    void firstClosedPollOnlyInitializesWatermarkAndLaterPollAvoidsHistoryDuplicate() throws Exception {
        CandleWebSocketHandler handler = mock(CandleWebSocketHandler.class);
        SignalCandleSource source = mock(SignalCandleSource.class);
        CandleStreamService service = new CandleStreamService(handler, new ObjectMapper(), source);
        SignalCandleSource.SignalCandle oldCandle = candle(1_700_000_000_000L, 10, 100, 3, 5);
        SignalCandleSource.SignalCandle newCandle = candle(1_700_000_060_000L, 11, 110, 4, 6);

        when(handler.getActiveSymbols("1m")).thenReturn(Set.of(SYMBOL));
        when(handler.getActiveSymbols("5m")).thenReturn(Set.of());
        when(handler.getActiveSymbols("15m")).thenReturn(Set.of());
        when(source.find(eq(SYMBOL), eq(SignalCandleSource.Interval.ONE_MINUTE), anyLong(), anyLong(),
                eq(SignalCandleSource.QueryMode.COMPLETED)))
                .thenReturn(List.of(oldCandle), List.of(newCandle), List.of(newCandle));

        service.broadcastClosedCandles();
        service.broadcastClosedCandles();
        service.broadcastClosedCandles();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(handler, times(1)).broadcastCandle(eq(SYMBOL), eq("1m"), payload.capture());
        assertThat(new ObjectMapper().readTree(payload.getValue()).get("time").asText())
                .contains("2023-11-14T22:14:20");
    }

    private SignalCandleSource.SignalCandle candle(long timeMs, int price, int quoteVolume,
                                                   int baseVolume, int delta) {
        return new SignalCandleSource.SignalCandle(
                SYMBOL, timeMs,
                BigDecimal.valueOf(price), BigDecimal.valueOf(price + 1L), BigDecimal.valueOf(price - 1L),
                BigDecimal.valueOf(price), BigDecimal.valueOf(quoteVolume), BigDecimal.valueOf(baseVolume),
                BigDecimal.valueOf(delta));
    }
}
