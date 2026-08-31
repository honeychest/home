package com.chs.springboot.domain.analysis.service;

import com.chs.springboot.domain.binance.service.SignalCandleSource;

/** SignalCandle과 분석 엔진 입력 계약 사이의 단일 변환 지점. */
public final class SignalCandleAnalysisConverter {

    private SignalCandleAnalysisConverter() {
    }

    public static AnalysisDetectionEngine.CandleData toCandleData(SignalCandleSource.SignalCandle candle) {
        return new AnalysisDetectionEngine.CandleData(
                candle.timeMs(),
                candle.openPrice().doubleValue(),
                candle.highPrice().doubleValue(),
                candle.lowPrice().doubleValue(),
                candle.closePrice().doubleValue(),
                candle.quoteVolume().doubleValue(),
                candle.delta().doubleValue());
    }
}
