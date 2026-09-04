// [AGENT] 역할: 5분봉 트리거 감지 + 과거 유사 패턴 매칭 | 연관파일: SignalController.java, SignalCandleSource.java, SignalDataService.java
// 주요메서드: getPattern(symbol, candleTimeMs) | 캐싱: ConcurrentHashMap (트리거 단위)
// 탐색 범위: 최근 2년 | 중복 방지: 4h봉 단위 1개 | 경계 케이스: 트리거 이후 4h 미만 사례 제외
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceSymbolNormalizer;
import com.chs.springboot.domain.binance.model.SignalParams;
import com.chs.springboot.domain.binance.repository.SignalParamsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PatternMatchService {

    private static final long TWO_YEARS_MS      = 2L * 365 * 24 * 60 * 60 * 1000;
    private static final long FOUR_HOURS_MS     = 4L * 60 * 60 * 1000;
    private static final long DAY_MS            = 24L * 60 * 60 * 1000;
    private static final long FIVE_MIN_MS       = 5L * 60 * 1000;

    private final SignalCandleSource candleSource;
    private final SignalParamsRepository signalParamsRepository;

    // 캐시: key = "symbol:candleTimeMs"
    private final ConcurrentHashMap<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public Map<String, Object> getPattern(String symbol, long candleTimeMs) {
        String normalizedSymbol = BinanceSymbolNormalizer.normalize(symbol);
        String cacheKey = normalizedSymbol + ":" + candleTimeMs;
        Map<String, Object> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        SignalParams params = signalParamsRepository.findById(normalizedSymbol).orElse(null);
        int volWindow           = params != null ? params.getVolWindow()         : 200;
        double triggerMult      = params != null ? params.getTriggerMultiplier() : 10.0;
        int stripCount          = params != null ? params.getStripCount()        : 7;

        // 1. 히스토리 전체 조회 (최근 2년)
        long nowMs = System.currentTimeMillis();
        long twoYearsAgoMs = nowMs - TWO_YEARS_MS;
        List<SignalCandleSource.SignalCandle> history = candleSource.find(
                normalizedSymbol, SignalCandleSource.Interval.FIVE_MINUTES,
                twoYearsAgoMs, nowMs, SignalCandleSource.QueryMode.COMPLETED);
        if (history.size() < 2) {
            return buildResult(false, Collections.emptyList());
        }

        // 2. 트리거 시점 봉 인덱스 탐색
        int triggerIdx = findIndex(history, candleTimeMs);
        if (triggerIdx < 1) {
            return buildResult(false, Collections.emptyList());
        }

        SignalCandleSource.SignalCandle triggerCandle  = history.get(triggerIdx);
        SignalCandleSource.SignalCandle prevCandle     = history.get(triggerIdx - 1);
        BigDecimal prevClose      = prevCandle.closePrice();
        BigDecimal triggerClose   = triggerCandle.closePrice();

        if (prevClose.compareTo(BigDecimal.ZERO) == 0) {
            return buildResult(false, Collections.emptyList());
        }

        // 3. 변동폭 계산
        BigDecimal changePct = triggerClose.subtract(prevClose).abs()
                .divide(prevClose, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        // 4. 평균 변동성 계산 (최근 volWindow봉)
        int startIdx = Math.max(0, triggerIdx - volWindow);
        BigDecimal avgVolatility = calcAvgVolatility(history, startIdx, triggerIdx);

        // 5. 트리거 판단
        BigDecimal threshold = avgVolatility.multiply(new BigDecimal(String.valueOf(triggerMult)));
        if (changePct.compareTo(threshold) < 0) {
            Map<String, Object> result = buildResult(false, Collections.emptyList());
            cache.put(cacheKey, result);
            return result;
        }

        // 6. 4h봉 구간 내 대표값 추출 (최대 변동 봉)
        long h4BlockStart = (candleTimeMs / FOUR_HOURS_MS) * FOUR_HOURS_MS;
        long h4BlockEnd   = h4BlockStart + FOUR_HOURS_MS;
        BigDecimal reprChangePct = calcRepresentativeChangePct(history, h4BlockStart, h4BlockEnd);
        BigDecimal reprPriceLevel = prevClose; // 직전봉 가격 수준 대표값

        // 7. 유사도 탐색 (4h 블록 단위 중복 방지)
        Map<Long, CandidateCase> bestPerBlock = new LinkedHashMap<>();
        for (int i = 1; i < triggerIdx; i++) {
            SignalCandleSource.SignalCandle c    = history.get(i);
            SignalCandleSource.SignalCandle prev = history.get(i - 1);
            if (prev.closePrice().compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal cChangePct = c.closePrice().subtract(prev.closePrice()).abs()
                    .divide(prev.closePrice(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));

            // 가격 수준 유사도: |prevClose - reprPriceLevel| / reprPriceLevel
            BigDecimal priceSim = BigDecimal.ONE.subtract(
                    prev.closePrice().subtract(reprPriceLevel).abs()
                            .divide(reprPriceLevel.max(BigDecimal.ONE), 8, RoundingMode.HALF_UP));

            // 변동% 유사도: 1 - |cChangePct - reprChangePct| / max(reprChangePct, 0.01)
            BigDecimal reprMax = reprChangePct.max(new BigDecimal("0.01"));
            BigDecimal changeSim = BigDecimal.ONE.subtract(
                    cChangePct.subtract(reprChangePct).abs()
                            .divide(reprMax, 8, RoundingMode.HALF_UP));

            BigDecimal similarity = priceSim.add(changeSim).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);

            long block = (c.timeMs() / FOUR_HOURS_MS) * FOUR_HOURS_MS;
            CandidateCase existing = bestPerBlock.get(block);
            if (existing == null || similarity.compareTo(existing.similarity) > 0) {
                bestPerBlock.put(block, new CandidateCase(i, similarity, c.timeMs()));
            }
        }

        // 8. 유사도 상위 stripCount개 정렬
        List<CandidateCase> sorted = new ArrayList<>(bestPerBlock.values());
        sorted.sort((a, b) -> b.similarity.compareTo(a.similarity));
        List<CandidateCase> topN = sorted.subList(0, Math.min(stripCount, sorted.size()));

        // 9. 각 사례 일봉 데이터 조회 + 정규화
        List<Map<String, Object>> cases = new ArrayList<>();

        for (CandidateCase candidate : topN) {
            long triggerMs = candidate.candleTimeMs;

            // 경계 케이스: 트리거 이후 4시간 미만 데이터 제외
            if (nowMs - triggerMs < FOUR_HOURS_MS && triggerMs < candleTimeMs) continue;
            // 과거 사례의 경우: 트리거로부터 4시간 후 데이터가 있어야 함
            if (triggerMs < candleTimeMs && !hasDataAfter(history, triggerMs + FOUR_HOURS_MS)) continue;

            long dayStart = toDayStartMs(triggerMs);
            long dayEnd   = dayStart + DAY_MS;
            List<SignalCandleSource.SignalCandle> dayCandles = candleSource.find(
                    normalizedSymbol, SignalCandleSource.Interval.FIVE_MINUTES,
                    dayStart, dayEnd, SignalCandleSource.QueryMode.COMPLETED);
            if (dayCandles.isEmpty()) continue;

            // 트리거 시점 인덱스 (일봉 내)
            int tIdx = findDayIndex(dayCandles, triggerMs);
            if (tIdx < 0) tIdx = 0;

            BigDecimal basePriceForNorm = dayCandles.get(tIdx).closePrice();
            if (basePriceForNorm.compareTo(BigDecimal.ZERO) == 0) continue;

            List<Map<String, Object>> candles = new ArrayList<>();
            for (SignalCandleSource.SignalCandle dc : dayCandles) {
                double relPct = dc.closePrice().subtract(basePriceForNorm)
                        .divide(basePriceForNorm, 8, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .doubleValue();
                Map<String, Object> cp = new HashMap<>();
                cp.put("time",    Instant.ofEpochMilli(dc.timeMs()).toString());
                cp.put("rel_pct", relPct);
                candles.add(cp);
            }

            // direction_after: 트리거 이후 일봉 마감 방향
            String directionAfter = calcDirectionAfter(history, triggerMs, dayEnd);

            Map<String, Object> caseMap = new HashMap<>();
            caseMap.put("case_date",       toDateString(triggerMs));
            caseMap.put("trigger_time",    Instant.ofEpochMilli(triggerMs).toString());
            caseMap.put("trigger_idx",     tIdx);
            caseMap.put("candles",         candles);
            caseMap.put("direction_after", directionAfter);
            cases.add(caseMap);
        }

        // 10. 현재 진행 중인 패턴 추가 (NOW)
        long nowDayStart = toDayStartMs(candleTimeMs);
        long nowDayEnd   = nowDayStart + DAY_MS;
        List<SignalCandleSource.SignalCandle> nowDayCandles = candleSource.find(
                normalizedSymbol, SignalCandleSource.Interval.FIVE_MINUTES,
                nowDayStart, nowMs + FIVE_MIN_MS, SignalCandleSource.QueryMode.IN_PROGRESS);
        if (!nowDayCandles.isEmpty()) {
            int nowTriggerDayIdx = findDayIndex(nowDayCandles, candleTimeMs);
            if (nowTriggerDayIdx < 0) nowTriggerDayIdx = 0;
            BigDecimal nowBase = nowDayCandles.get(nowTriggerDayIdx).closePrice();
            if (nowBase.compareTo(BigDecimal.ZERO) != 0) {
                List<Map<String, Object>> nowCandles = new ArrayList<>();
                for (SignalCandleSource.SignalCandle dc : nowDayCandles) {
                    double relPct = dc.closePrice().subtract(nowBase)
                            .divide(nowBase, 8, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")).doubleValue();
                    Map<String, Object> cp = new HashMap<>();
                    cp.put("time",    Instant.ofEpochMilli(dc.timeMs()).toString());
                    cp.put("rel_pct", relPct);
                    nowCandles.add(cp);
                }
                Map<String, Object> nowCase = new HashMap<>();
                nowCase.put("case_date",       "NOW");
                nowCase.put("trigger_time",    Instant.ofEpochMilli(candleTimeMs).toString());
                nowCase.put("trigger_idx",     nowTriggerDayIdx);
                nowCase.put("candles",         nowCandles);
                nowCase.put("direction_after", null);
                cases.add(nowCase);
            }
        }

        Map<String, Object> result = buildResult(true, cases);
        cache.put(cacheKey, result);
        return result;
    }

    // ─── 유틸 ────────────────────────────────────────────────────────────

    private Map<String, Object> buildResult(boolean triggered, List<Map<String, Object>> cases) {
        Map<String, Object> r = new HashMap<>();
        r.put("triggered", triggered);
        r.put("cases",     cases);
        return r;
    }

    private int findIndex(List<SignalCandleSource.SignalCandle> list, long candleTimeMs) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).timeMs() == candleTimeMs) return i;
        }
        return -1;
    }

    private int findDayIndex(List<SignalCandleSource.SignalCandle> list, long candleTimeMs) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).timeMs() >= candleTimeMs) return i;
        }
        return -1;
    }

    private BigDecimal calcAvgVolatility(List<SignalCandleSource.SignalCandle> history, int from, int to) {
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (int i = from + 1; i < to; i++) {
            BigDecimal prev = history.get(i - 1).closePrice();
            BigDecimal curr = history.get(i).closePrice();
            if (prev.compareTo(BigDecimal.ZERO) == 0) continue;
            sum = sum.add(curr.subtract(prev).abs().divide(prev, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100")));
            count++;
        }
        return count == 0 ? new BigDecimal("0.1") : sum.divide(new BigDecimal(count), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal calcRepresentativeChangePct(List<SignalCandleSource.SignalCandle> history, long blockStart, long blockEnd) {
        BigDecimal maxChange = BigDecimal.ZERO;
        for (int i = 1; i < history.size(); i++) {
            long t = history.get(i).timeMs();
            if (t < blockStart || t >= blockEnd) continue;
            BigDecimal prev = history.get(i - 1).closePrice();
            if (prev.compareTo(BigDecimal.ZERO) == 0) continue;
            BigDecimal chg = history.get(i).closePrice().subtract(prev).abs()
                    .divide(prev, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
            if (chg.compareTo(maxChange) > 0) maxChange = chg;
        }
        return maxChange.compareTo(BigDecimal.ZERO) == 0 ? new BigDecimal("0.1") : maxChange;
    }

    private boolean hasDataAfter(List<SignalCandleSource.SignalCandle> history, long afterMs) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).timeMs() >= afterMs) return true;
        }
        return false;
    }

    private String calcDirectionAfter(List<SignalCandleSource.SignalCandle> history, long triggerMs, long dayEndMs) {
        // 일봉 마감 또는 그 이후 첫 봉 기준
        BigDecimal triggerPrice = null;
        BigDecimal endPrice     = null;
        for (SignalCandleSource.SignalCandle c : history) {
            if (c.timeMs() == triggerMs) triggerPrice = c.closePrice();
            if (c.timeMs() >= dayEndMs - FIVE_MIN_MS && c.timeMs() < dayEndMs) {
                endPrice = c.closePrice();
            }
        }
        if (triggerPrice == null || endPrice == null) return null;
        return endPrice.compareTo(triggerPrice) >= 0 ? "UP" : "DOWN";
    }

    private long toDayStartMs(long epochMs) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC);
        return zdt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private String toDateString(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private static class CandidateCase {
        final int historyIdx;
        final BigDecimal similarity;
        final long candleTimeMs;
        CandidateCase(int idx, BigDecimal sim, long ms) {
            this.historyIdx = idx; this.similarity = sim; this.candleTimeMs = ms;
        }
    }
}
