package com.chs.springboot.domain.binance.analysis;

import com.chs.springboot.domain.binance.model.BinanceKline;

import java.util.List;

/** 캔들 입력의 순서·중복·거래소 시간 간격을 한 곳에서 검증한다. */
public final class KlineSeriesValidator {

    private KlineSeriesValidator() {
    }

    public static void validate(List<BinanceKline> candles, long intervalMs) {
        if (candles == null) {
            throw new IllegalArgumentException("kline 목록은 null일 수 없습니다");
        }
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("kline interval은 0보다 커야 합니다");
        }

        for (int i = 0; i < candles.size(); i++) {
            BinanceKline current = candles.get(i);
            if (current == null) {
                throw new IllegalArgumentException("kline 목록에 null이 있습니다: index=" + i);
            }
            if (current.openTimeMs() < 0 || current.openTimeMs() % intervalMs != 0) {
                throw new IllegalArgumentException("kline 시작 시각이 interval 경계에 맞지 않습니다: " + current.openTimeMs());
            }
            if (current.closeTimeMs() < current.openTimeMs()) {
                throw new IllegalArgumentException("kline 종료 시각이 시작 시각보다 빠릅니다: " + current.openTimeMs());
            }
            if (i == 0) {
                continue;
            }

            BinanceKline previous = candles.get(i - 1);
            long difference = current.openTimeMs() - previous.openTimeMs();
            if (difference <= 0) {
                throw new IllegalArgumentException("kline 목록이 정렬되지 않았거나 중복 시각이 있습니다: " + current.openTimeMs());
            }
            if (difference != intervalMs) {
                throw new IllegalArgumentException("kline 사이에 결측 또는 잘못된 간격이 있습니다: " + current.openTimeMs());
            }
        }
    }

    public static boolean hasGap(List<BinanceKline> candles, long intervalMs) {
        if (candles == null || intervalMs <= 0) {
            return true;
        }
        for (int i = 1; i < candles.size(); i++) {
            if (candles.get(i) == null || candles.get(i - 1) == null
                    || candles.get(i).openTimeMs() - candles.get(i - 1).openTimeMs() != intervalMs) {
                return true;
            }
        }
        return false;
    }
}
