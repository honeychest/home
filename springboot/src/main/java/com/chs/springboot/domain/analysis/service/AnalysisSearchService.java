// [AGENT] Analysis 수동 탐색 서비스 — 공통 캔들 원천에서 조건 충족 봉 candle_time_ms 반환
package com.chs.springboot.domain.analysis.service;

import com.chs.springboot.domain.analysis.dto.AnalysisSearchRequest;
import com.chs.springboot.domain.binance.model.BinanceSymbolNormalizer;
import com.chs.springboot.domain.binance.service.SignalCandleSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisSearchService {

    private static final long SEARCH_CHUNK_MS = 7L * 24 * 60 * 60 * 1000;

    private final SignalCandleSource candleSource;

    /**
     * fromMs~toMs 범위 내 조건 충족 봉의 candle_time_ms 목록 반환 (ASC).
     * 구간을 나눠 읽어 장기 검색도 한 번에 전체 캔들을 힙에 올리지 않는다.
     */
    public List<Long> search(AnalysisSearchRequest req) {
        AnalysisSearchRequest.Conditions c = req.getConditions();
        BigDecimal volMin = c.getTotalVolume()
                .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(c.getVolTolerance() / 100)));
        BigDecimal volMax = c.getTotalVolume()
                .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(c.getVolTolerance() / 100)));

        log.info("[AnalysisSearch] symbol={} timeframe={} fromMs={} toMs={} rate={} rateTol={} useRate={} useVol={}",
                req.getSymbol(), req.getTimeframe(), req.getFromMs(), req.getToMs(),
                c.getPriceChangeRate(), c.getRateTolerance(), c.isUseRateFilter(), c.isUseVolFilter());

        long startedAt = System.currentTimeMillis();
        List<Long> result = findMatches(
                BinanceSymbolNormalizer.normalize(req.getSymbol()),
                SignalCandleSource.Interval.from(req.getTimeframe()),
                req.getFromMs(), req.getToMs(),
                c.getPriceChangeRate(), c.getRateTolerance(),
                volMin, volMax, c.isUseRateFilter(), c.isUseVolFilter());
        log.info("[AnalysisSearch] result={} durationMs={}", result.size(), System.currentTimeMillis() - startedAt);
        return result;
    }

    private List<Long> findMatches(
            String symbol,
            SignalCandleSource.Interval interval,
            long fromMs,
            long toMs,
            double priceChangeRate,
            double rateTolerance,
            BigDecimal volMin,
            BigDecimal volMax,
            boolean useRateFilter,
            boolean useVolFilter) {
        List<Long> result = new ArrayList<>();
        SignalCandleSource.SignalCandle previous = null;
        long cursor = fromMs;

        while (cursor < toMs) {
            long candidateEnd = cursor > Long.MAX_VALUE - SEARCH_CHUNK_MS
                    ? Long.MAX_VALUE
                    : cursor + SEARCH_CHUNK_MS;
            long next = Math.min(toMs, candidateEnd);
            for (SignalCandleSource.SignalCandle current : candleSource.find(
                    symbol, interval, cursor, next, SignalCandleSource.QueryMode.COMPLETED)) {
                if (previous != null && matches(previous, current, priceChangeRate, rateTolerance,
                        volMin, volMax, useRateFilter, useVolFilter)) {
                    result.add(current.timeMs());
                }
                previous = current;
            }
            if (next <= cursor) {
                break;
            }
            cursor = next;
        }
        return result;
    }

    private boolean matches(
            SignalCandleSource.SignalCandle previous,
            SignalCandleSource.SignalCandle current,
            double priceChangeRate,
            double rateTolerance,
            BigDecimal volMin,
            BigDecimal volMax,
            boolean useRateFilter,
            boolean useVolFilter) {
        BigDecimal previousClose = previous.closePrice();
        if (previousClose.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }

        BigDecimal actualRate = current.closePrice().subtract(previousClose)
                .divide(previousClose, 12, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        BigDecimal minRate = BigDecimal.valueOf(priceChangeRate - rateTolerance);
        BigDecimal maxRate = BigDecimal.valueOf(priceChangeRate + rateTolerance);
        boolean rateMatches = !useRateFilter
                || (actualRate.compareTo(minRate) >= 0 && actualRate.compareTo(maxRate) <= 0);
        boolean volumeMatches = !useVolFilter
                || (current.baseVolume().compareTo(volMin) >= 0
                && current.baseVolume().compareTo(volMax) <= 0);
        return rateMatches && volumeMatches;
    }
}
