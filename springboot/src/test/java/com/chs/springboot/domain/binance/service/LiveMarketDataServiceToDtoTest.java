package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.MarketSnapshotDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** LiveMarketDataService.toDto() — 순수 함수라 버퍼/리더 없이 직접 테스트 가능. */
class LiveMarketDataServiceToDtoTest {

    @Test
    @DisplayName("확정봉이 없으면 candleCount 0, 지표는 전부 null")
    void toDto_noClosedCandles() {
        LiveKlineBuffer.Snapshot snapshot = new LiveKlineBuffer.Snapshot(List.of(), null, 0L);

        MarketSnapshotDto dto = LiveMarketDataService.toDto(snapshot, "BTCUSDT", "FUTURES", "1m");

        assertThat(dto.candleCount()).isZero();
        assertThat(dto.rsi14()).isNull();
        assertThat(dto.macdLine()).isNull();
        assertThat(dto.supertrendValue()).isNull();
    }

    @Test
    @DisplayName("결측 없이 충분한 확정봉이 있으면 지표가 계산된다")
    void toDto_noGap_computesIndicators() {
        List<BinanceKline> closed = trending(100, 1, 60);
        LiveKlineBuffer.Snapshot snapshot = new LiveKlineBuffer.Snapshot(closed, null, 123L);

        MarketSnapshotDto dto = LiveMarketDataService.toDto(snapshot, "BTCUSDT", "FUTURES", "1m");

        assertThat(dto.candleCount()).isEqualTo(60);
        assertThat(dto.rsi14()).isNotNull();
        assertThat(dto.macdLine()).isNotNull();
        assertThat(dto.supertrendValue()).isNotNull();
    }

    @Test
    @DisplayName("확정봉 사이에 결측(1분 경계가 아닌 간격)이 있으면 지표는 null(현재가/구간 통계는 유지)")
    void toDto_withGap_indicatorsNull() {
        List<BinanceKline> closed = new ArrayList<>(trending(100, 1, 40));
        // 20번째와 21번째 캔들 사이를 3분으로 벌려서 결측을 만든다
        List<BinanceKline> withGap = new ArrayList<>(closed.subList(0, 20));
        for (int i = 20; i < closed.size(); i++) {
            BinanceKline k = closed.get(i);
            withGap.add(new BinanceKline(
                    k.openTimeMs() + 2 * 60_000L, k.openPrice(), k.highPrice(), k.lowPrice(), k.closePrice(),
                    k.volume(), k.closeTimeMs() + 2 * 60_000L, k.quoteVolume(), k.tradeCount(),
                    k.takerBuyBaseVolume(), k.takerBuyQuoteVolume()));
        }
        LiveKlineBuffer.Snapshot snapshot = new LiveKlineBuffer.Snapshot(withGap, null, 123L);

        MarketSnapshotDto dto = LiveMarketDataService.toDto(snapshot, "BTCUSDT", "FUTURES", "1m");

        assertThat(dto.candleCount()).isEqualTo(40);
        assertThat(dto.rsi14()).isNull();
        assertThat(dto.macdLine()).isNull();
        assertThat(dto.supertrendValue()).isNull();
        // 결측이 있어도 현재가/구간 통계는 그대로 나온다(지표만 영향받음)
        assertThat(dto.currentPrice()).isNotNull();
        assertThat(dto.windowHigh()).isNotNull();
    }

    @Test
    @DisplayName("진행 중인 봉이 있으면 currentPrice는 거기서 오고, 확정봉 목록에는 안 들어간다")
    void toDto_partialCandle_usedForCurrentPriceOnly() {
        List<BinanceKline> closed = trending(100, 1, 20);
        BinanceKline partial = kline(closed.get(closed.size() - 1).openTimeMs() + 60_000L, "999");
        LiveKlineBuffer.Snapshot snapshot = new LiveKlineBuffer.Snapshot(closed, partial, 123L);

        MarketSnapshotDto dto = LiveMarketDataService.toDto(snapshot, "BTCUSDT", "FUTURES", "1m");

        assertThat(dto.candleCount()).isEqualTo(20);
        assertThat(dto.currentPrice()).isEqualByComparingTo("999");
    }

    private static List<BinanceKline> trending(int start, int step, int count) {
        List<BinanceKline> result = new ArrayList<>(count);
        int price = start;
        for (int i = 0; i < count; i++) {
            result.add(kline(i * 60_000L, String.valueOf(price)));
            price += step;
        }
        return result;
    }

    private static BinanceKline kline(long openTimeMs, String close) {
        BigDecimal closePrice = new BigDecimal(close);
        return new BinanceKline(
                openTimeMs,
                closePrice, closePrice.add(BigDecimal.ONE), closePrice.subtract(BigDecimal.ONE), closePrice,
                BigDecimal.ONE, openTimeMs + 59_999L,
                BigDecimal.ONE, 1L, BigDecimal.ONE, BigDecimal.ONE);
    }
}
