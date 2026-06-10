// [AGENT] Analysis 수동 탐색 서비스 — 날짜 범위 내 조건 충족 전체 봉 candle_time_ms 반환
package com.chs.springboot.domain.analysis.service;

import com.chs.springboot.domain.analysis.dto.AnalysisSearchRequest;
import com.chs.springboot.domain.binance.repository.AggTrade1mRepository;
import com.chs.springboot.domain.binance.repository.AggTrade5mRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisSearchService {

    private final AggTrade1mRepository aggTrade1mRepository;
    private final AggTrade5mRepository aggTrade5mRepository;

    /**
     * fromMs~toMs 범위 내 조건 충족 봉의 candle_time_ms 목록 반환 (ASC)
     */
    public List<Long> search(AnalysisSearchRequest req) {
        AnalysisSearchRequest.Conditions c = req.getConditions();
        BigDecimal volMin = c.getTotalVolume()
                .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(c.getVolTolerance() / 100)));
        BigDecimal volMax = c.getTotalVolume()
                .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(c.getVolTolerance() / 100)));
        int useRateFilter = c.isUseRateFilter() ? 1 : 0;
        int useVolFilter  = c.isUseVolFilter()  ? 1 : 0;

        log.info("[AnalysisSearch] symbol={} timeframe={} fromMs={} toMs={} rate={} rateTol={} useRate={} useVol={}",
                req.getSymbol(), req.getTimeframe(), req.getFromMs(), req.getToMs(),
                c.getPriceChangeRate(), c.getRateTolerance(), useRateFilter, useVolFilter);

        long startMs = System.currentTimeMillis();
        List<Object[]> rows = queryAllByTimeframe(
                req.getTimeframe(), req.getSymbol(),
                req.getFromMs(), req.getToMs(),
                c.getPriceChangeRate(), c.getRateTolerance(),
                volMin, volMax, useRateFilter, useVolFilter);
        log.info("[AnalysisSearch] result={} durationMs={}", rows.size(), System.currentTimeMillis() - startMs);

        return rows.stream()
                .map(row -> ((Number) row[0]).longValue())
                .collect(Collectors.toList());
    }

    private List<Object[]> queryAllByTimeframe(
            String timeframe, String symbol,
            long fromMs, long toMs,
            double priceChangeRate, double rateTolerance,
            BigDecimal volMin, BigDecimal volMax,
            int useRateFilter, int useVolFilter) {

        if ("1m".equals(timeframe)) {
            return aggTrade1mRepository.findAllSimilarCandles(
                    symbol, fromMs, toMs, priceChangeRate, rateTolerance, volMin, volMax, useRateFilter, useVolFilter);
        } else if ("15m".equals(timeframe)) {
            return aggTrade5mRepository.findAllSimilarCandles15m(
                    symbol, fromMs, toMs, priceChangeRate, rateTolerance, volMin, volMax, useRateFilter, useVolFilter);
        } else {
            return aggTrade5mRepository.findAllSimilarCandles(
                    symbol, fromMs, toMs, priceChangeRate, rateTolerance, volMin, volMax, useRateFilter, useVolFilter);
        }
    }
}
