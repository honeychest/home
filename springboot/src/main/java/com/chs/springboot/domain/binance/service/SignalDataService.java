// [AGENT] T4-STEALTH: Signal Dashboard 핵심 비즈니스 로직 — init/history/patterns/params/divergence/candles API 데이터 조립
// [AGENT] signal futures 캔들 조회를 최신 limit 기준에서 time range 기준으로 전환
// 연관파일: SignalController.java, RawAggTradeRepository.java, OpenInterestRepository.java, ForceOrderRepository.java, AggTrade1mRepository.java, AggTrade5mRepository.java, SignalParamsRepository.java
// 주요메서드: getInitData, getHistoryData, findPatterns, calcLargeTradeThreshold, calcMovingAverage, getParams, saveParams, getDivergence, getCandles(volume추가), getCandlesByDate, getCandleDates
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTrade5m;
import com.chs.springboot.domain.binance.model.SignalParams;
import com.chs.springboot.domain.binance.repository.AggTrade1mRepository;
import com.chs.springboot.domain.binance.repository.AggTrade5mRepository;
import com.chs.springboot.domain.binance.repository.ForceOrderRepository;
import com.chs.springboot.domain.binance.repository.OpenInterestRepository;
import com.chs.springboot.domain.binance.repository.SignalParamsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalDataService {

    private final OpenInterestRepository openInterestRepository;
    private final ForceOrderRepository       forceOrderRepository;
    private final AggTrade1mRepository       agg1mRepository;
    private final AggTrade5mRepository       agg5mRepository;
    private final SignalParamsRepository     signalParamsRepository;

    public Map<String, Object> getInitData(String symbol) {
        Map<String, Object> result = new HashMap<>();

        BigDecimal threshold = calcLargeTradeThreshold(symbol);
        result.put("largeTradeThreshold", threshold.doubleValue());

        var latestOi = openInterestRepository.findTopBySymbolOrderByCollectedAtMsDesc(symbol);
        if (latestOi.isPresent()) {
            Map<String, Object> oiData = new HashMap<>();
            oiData.put("symbol",        latestOi.get().getSymbol());
            oiData.put("openInterest",  latestOi.get().getOpenInterest().toPlainString());
            oiData.put("collectedAtMs", latestOi.get().getCollectedAtMs());
            result.put("latestOI", oiData);
        } else {
            result.put("latestOI", null);
        }

        result.put("latestFundingRate", 0.0001);

        return result;
    }

    public Map<String, Object> getHistoryData(String symbol, String range) {
        long nowMs  = System.currentTimeMillis();
        long fromMs = nowMs - parseRangeToMs(range);
        log.info("[SignalEnergy] ▶ symbol={} range={} fromMs={} nowMs={}", symbol, range, fromMs, nowMs);

        // 10m·50m 이하 → 1분봉 롤업 / 그 이상 → 5분봉 롤업
        String energyTable = switch (range) {
            case "1m", "5m", "10m", "30m", "50m" -> "agg_trade_1m";
            default -> "agg_trade_5m";
        };
        Map<String, Object> energyRow = switch (range) {
            case "1m", "5m", "10m", "30m", "50m" -> agg1mRepository.sumEnergyBySymbolAndTimeRange(symbol, fromMs, nowMs);
            default                               -> agg5mRepository.sumEnergyBySymbolAndTimeRange(symbol, fromMs, nowMs);
        };
        log.info("[SignalEnergy] table={} energyRow={}", energyTable, energyRow);

        BigDecimal longEnergy  = toBd(energyRow.get("long_energy"));
        BigDecimal shortEnergy = toBd(energyRow.get("short_energy"));
        log.info("[SignalEnergy] raw longEnergy={} shortEnergy={}", longEnergy, shortEnergy);

        // 누계: SUM 집계 쿼리 (전체 범위, 목록 조회 없음)
        BigDecimal longLiqTotal  = BigDecimal.ZERO;
        BigDecimal shortLiqTotal = BigDecimal.ZERO;
        var liqSums = forceOrderRepository.sumLiqTotalBySymbolAndTimeRange(symbol, fromMs, nowMs);
        log.info("[SignalEnergy] liqSums count={}", liqSums.size());
        for (Object[] row : liqSums) {
            String side      = (String) row[0];
            BigDecimal total = toBd(row[1]);
            log.info("[SignalEnergy] liq side={} total={}", side, total);
            if ("SELL".equals(side)) {
                longLiqTotal = total;
                longEnergy   = longEnergy.subtract(total);
            } else {
                shortLiqTotal = total;
                shortEnergy   = shortEnergy.subtract(total);
            }
        }
        log.info("[SignalEnergy] after liq adjust longEnergy={} shortEnergy={}", longEnergy, shortEnergy);

        // 이벤트 목록: side별 최근 10건
        var longTop10  = forceOrderRepository.findTop10BySymbolAndSideAndTradeTimeMsBetweenOrderByTradeTimeMsDesc(symbol, "SELL", fromMs, nowMs);
        var shortTop10 = forceOrderRepository.findTop10BySymbolAndSideAndTradeTimeMsBetweenOrderByTradeTimeMsDesc(symbol, "BUY",  fromMs, nowMs);

        List<Map<String, Object>> longLiqEvents = longTop10.stream().map(fo -> {
            Map<String, Object> event = new HashMap<>();
            event.put("side",        fo.getSide());
            event.put("price",       fo.getPrice().toPlainString());
            event.put("avgPrice",    fo.getAvgPrice().toPlainString());
            event.put("quantity",    fo.getOriginalQuantity().toPlainString());
            event.put("tradeTimeMs", fo.getTradeTimeMs());
            return event;
        }).toList();

        List<Map<String, Object>> shortLiqEvents = shortTop10.stream().map(fo -> {
            Map<String, Object> event = new HashMap<>();
            event.put("side",        fo.getSide());
            event.put("price",       fo.getPrice().toPlainString());
            event.put("avgPrice",    fo.getAvgPrice().toPlainString());
            event.put("quantity",    fo.getOriginalQuantity().toPlainString());
            event.put("tradeTimeMs", fo.getTradeTimeMs());
            return event;
        }).toList();

        Map<String, Object> result = new HashMap<>();
        result.put("longEnergy",    longEnergy.max(BigDecimal.ZERO).doubleValue());
        result.put("shortEnergy",   shortEnergy.max(BigDecimal.ZERO).doubleValue());
        result.put("longLiqTotal",  longLiqTotal.doubleValue());
        result.put("shortLiqTotal", shortLiqTotal.doubleValue());
        result.put("longLiqEvents",  longLiqEvents);
        result.put("shortLiqEvents", shortLiqEvents);
        log.info("[SignalEnergy] ◀ final longEnergy={} shortEnergy={} longLiqTotal={} shortLiqTotal={}",
            result.get("longEnergy"), result.get("shortEnergy"), longLiqTotal, shortLiqTotal);

        return result;
    }

    public List<Map<String, Object>> getOiHistory(String symbol, String range) {
        long nowMs  = System.currentTimeMillis();
        long fromMs = nowMs - parseRangeToMs(range);

        return openInterestRepository
                .findBySymbolAndCollectedAtMsBetweenOrderByCollectedAtMsAsc(symbol, fromMs, nowMs)
                .stream()
                .map(oi -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("symbol",        oi.getSymbol());
                    m.put("openInterest",  oi.getOpenInterest().toPlainString());
                    m.put("price",         oi.getPrice() != null ? oi.getPrice().toPlainString() : null);
                    m.put("collectedAtMs", oi.getCollectedAtMs());
                    return m;
                })
                .toList();
    }

    private long parseRangeToMs(String range) {
        if (range == null || range.isBlank()) throw new IllegalArgumentException("range is required");
        char unit = range.charAt(range.length() - 1);
        long num;
        try {
            num = Long.parseLong(range.substring(0, range.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid range format: " + range);
        }
        return switch (unit) {
            case 'm' -> num * 60_000L;
            case 'h' -> num * 3_600_000L;
            case 'd' -> num * 86_400_000L;
            default  -> throw new IllegalArgumentException("Unknown range unit: " + unit);
        };
    }

    public List<Map<String, Object>> findPatterns(String symbol, BigDecimal volume) {
        BigDecimal minVol = volume.multiply(new BigDecimal("0.5"));
        BigDecimal maxVol = volume.multiply(new BigDecimal("1.5"));

        var candles = agg5mRepository.findBySymbolAndVolumeBetween(symbol, minVol, maxVol);

        return candles.stream()
                .limit(5)
                .map(c -> {
                    Map<String, Object> pattern = new HashMap<>();
                    pattern.put("candleTime",  String.valueOf(c.getCandleTimeMs()));
                    pattern.put("priceChange", 0.0);
                    return pattern;
                })
                .toList();
    }

    public BigDecimal calcLargeTradeThreshold(String symbol) {
        return new BigDecimal("10000");
    }

    public BigDecimal calcMovingAverage(String symbol, String marketType, int count) {
        long afterMs = System.currentTimeMillis() - (count * 5L * 60_000L);
        var candles  = agg5mRepository
                .findBySymbolAndMarketTypeAndCandleTimeMsAfterOrderByCandleTimeMsDesc(symbol, marketType, afterMs);

        if (candles.isEmpty()) return BigDecimal.ZERO;

        BigDecimal sum = candles.stream()
                .map(c -> c.getBuyVolume().add(c.getSellVolume()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(new BigDecimal(candles.size()), 8, RoundingMode.HALF_UP);
    }

    public Map<String, Object> getScore(String symbol, long candleTimeMs,
                                         com.chs.springboot.domain.binance.service.PatternMatchService patternMatchService) {
        Map<String, Object> patternResult = patternMatchService.getPattern(symbol, candleTimeMs);
        boolean triggered = Boolean.TRUE.equals(patternResult.get("triggered"));
        if (!triggered) {
            return Map.of("triggered", false);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) patternResult.get("cases");

        // NOW 카드(direction_after=null) 제외하고 방향 집계
        long upCount   = cases.stream().filter(c -> "UP".equals(c.get("direction_after"))).count();
        long downCount = cases.stream().filter(c -> "DOWN".equals(c.get("direction_after"))).count();
        long total     = upCount + downCount;

        if (total == 0) {
            return Map.of("triggered", true, "score_pct", 0.0, "matched_count", 0, "total_count", 0, "dominant_dir", null);
        }

        String dominantDir   = upCount >= downCount ? "UP" : "DOWN";
        long matchedCount    = Math.max(upCount, downCount);
        double scorePct      = Math.round(matchedCount * 1000.0 / total) / 10.0;

        // 보조 데이터: 현재 타임라인(candleTimeMs 기준 최근 50분)에서 delta 분석
        long fromMs = candleTimeMs - 50 * 60_000L;
        List<AggTrade5m> forAux = agg5mRepository
                .findBySymbolAndMarketTypeAndCandleTimeMsAfterOrderByCandleTimeMsDesc(symbol, "FUTURES", fromMs);

        // delta 추세 방향 전환 시점
        List<Long> turningPoints = new ArrayList<>();
        if (forAux.size() > 1) {
            boolean prevPositive = forAux.get(0).getDelta().compareTo(BigDecimal.ZERO) >= 0;
            for (int i = 1; i < forAux.size(); i++) {
                boolean currPositive = forAux.get(i).getDelta().compareTo(BigDecimal.ZERO) >= 0;
                if (currPositive != prevPositive) {
                    turningPoints.add(forAux.get(i).getCandleTimeMs());
                }
                prevPositive = currPositive;
            }
        }

        // Delta POC: 최대 delta 발생 가격대
        BigDecimal pocPrice = null;
        BigDecimal maxDeltaAbs = BigDecimal.ZERO;
        for (AggTrade5m c : forAux) {
            BigDecimal abs = c.getDelta().abs();
            if (abs.compareTo(maxDeltaAbs) > 0) {
                maxDeltaAbs = abs;
                pocPrice    = c.getClosePrice();
            }
        }

        // Delta Velocity: 단위시간당 delta 변화량
        double deltaVelocity = 0.0;
        if (forAux.size() >= 2) {
            BigDecimal first = forAux.get(forAux.size() - 1).getDelta();
            BigDecimal last  = forAux.get(0).getDelta();
            double minutes   = forAux.size() * 5.0;
            deltaVelocity    = last.subtract(first).abs().doubleValue() / minutes;
        }

        // Delta 반전 카운트
        int reversalCount = turningPoints.size();

        Map<String, Object> aux = new HashMap<>();
        aux.put("delta_turning_points", turningPoints);
        aux.put("delta_poc_price",      pocPrice != null ? pocPrice.doubleValue() : null);
        aux.put("delta_velocity",       deltaVelocity);
        aux.put("delta_reversal_count", reversalCount);

        Map<String, Object> result = new HashMap<>();
        result.put("triggered",     true);
        result.put("score_pct",     scorePct);
        result.put("matched_count", (int) matchedCount);
        result.put("total_count",   (int) total);
        result.put("dominant_dir",  dominantDir);
        result.put("aux",           aux);
        return result;
    }

    public Map<String, Object> getDivergence(String symbol, String timeline) {
        long nowMs = System.currentTimeMillis();
        long fromMs = switch (timeline) {
            case "1m"  -> nowMs - 10 * 60_000L;
            case "5m"  -> nowMs - 50 * 60_000L;
            case "30m" -> nowMs -  5 * 60 * 60_000L;
            case "1h"  -> nowMs - 10 * 60 * 60_000L;
            case "4h"  -> nowMs - 40 * 60 * 60_000L;
            default    -> nowMs - 50 * 60_000L;
        };

        Map<String, Object> row = agg5mRepository.sumDivergenceBySymbolAndTimeRange(symbol, fromMs, nowMs);
        BigDecimal firstOpen  = toBd(row.get("first_open"));
        BigDecimal lastClose  = toBd(row.get("last_close"));
        BigDecimal totalDelta = toBd(row.get("total_delta"));

        if (firstOpen.compareTo(BigDecimal.ZERO) == 0 && lastClose.compareTo(BigDecimal.ZERO) == 0) {
            return Map.of("divergence", false);
        }

        String priceDir = lastClose.compareTo(firstOpen) >= 0 ? "UP" : "DOWN";
        String deltaDir = totalDelta.compareTo(BigDecimal.ZERO) >= 0 ? "BUY" : "SELL";
        boolean divergence = !priceDir.equals("UP") || !deltaDir.equals("BUY");
        if (priceDir.equals("UP")   && deltaDir.equals("BUY"))  divergence = false;
        if (priceDir.equals("DOWN") && deltaDir.equals("SELL")) divergence = false;
        if (priceDir.equals("UP")   && deltaDir.equals("SELL")) divergence = true;
        if (priceDir.equals("DOWN") && deltaDir.equals("BUY"))  divergence = true;

        String divergenceType = null;
        if (divergence) {
            divergenceType = priceDir.equals("UP") ? "BEARISH" : "BULLISH";
        }

        Double efficiency = null;
        if (firstOpen.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal priceChangePct = lastClose.subtract(firstOpen)
                    .divide(firstOpen, 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")).abs();
            if (priceChangePct.compareTo(BigDecimal.ZERO) != 0) {
                efficiency = totalDelta.abs()
                        .divide(priceChangePct, 4, RoundingMode.HALF_UP)
                        .doubleValue();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("price_dir",       priceDir);
        result.put("delta_dir",       deltaDir);
        result.put("divergence",      divergence);
        result.put("divergence_type", divergenceType);
        result.put("efficiency",      efficiency);
        return result;
    }

    public Map<String, Object> getParams(String symbol) {
        SignalParams p = signalParamsRepository.findById(symbol).orElse(null);
        Map<String, Object> result = new HashMap<>();
        result.put("vol_window",          p != null ? p.getVolWindow()          : 200);
        result.put("trigger_multiplier",  p != null ? p.getTriggerMultiplier()  : 10.0);
        result.put("strip_count",         p != null ? p.getStripCount()         : 7);
        return result;
    }

    public Map<String, Object> saveParams(String symbol, int volWindow, double triggerMultiplier, int stripCount) {
        SignalParams p = signalParamsRepository.findById(symbol)
                .orElseGet(() -> { SignalParams n = new SignalParams(); n.setSymbol(symbol); return n; });
        p.setVolWindow(volWindow);
        p.setTriggerMultiplier(triggerMultiplier);
        p.setStripCount(stripCount);
        p.setUpdatedAt(java.time.LocalDateTime.now());
        signalParamsRepository.save(p);
        Map<String, Object> result = new HashMap<>();
        result.put("vol_window",         volWindow);
        result.put("trigger_multiplier", triggerMultiplier);
        result.put("strip_count",        stripCount);
        return result;
    }

    public List<Map<String, Object>> getCandles(String symbol, String type, int limit, String range) {
        long nowMs = System.currentTimeMillis();
        long fromMs = resolveCandleFromMs(type, limit, range, nowMs);
        List<Map<String, Object>> rows = "1m".equals(type)
            ? agg1mRepository.findByTimeRangeWithCombinedDelta(symbol, fromMs, nowMs)
            : agg5mRepository.findByTimeRangeWithCombinedDelta(symbol, fromMs, nowMs);

        return rows.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("time",   toBd(r.get("candle_time_ms")).longValue());
            m.put("open",   toBd(r.get("open_price")).doubleValue());
            m.put("high",   toBd(r.get("high_price")).doubleValue());
            m.put("low",    toBd(r.get("low_price")).doubleValue());
            m.put("close",  toBd(r.get("close_price")).doubleValue());
            m.put("volume", toBd(r.get("total_volume")).doubleValue());
            m.put("delta",  toBd(r.get("delta")).doubleValue());
            return m;
        }).toList();
    }

    public List<Map<String, Object>> getCandlesByDate(String symbol, String type, String date, int overlap) {
        long dayStart = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long dayEnd   = dayStart + 86_400_000L;

        List<Map<String, Object>> dayRows = agg5mRepository.findByDateRange(symbol, dayStart, dayEnd);

        List<Map<String, Object>> overlapRows = new ArrayList<>(
            agg5mRepository.findLastNBefore(symbol, dayStart, overlap)
        );
        Collections.reverse(overlapRows);

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> r : overlapRows) {
            Map<String, Object> m = new HashMap<>();
            m.put("time",      toBd(r.get("candle_time_ms")).longValue());
            m.put("open",      toBd(r.get("open_price")).doubleValue());
            m.put("high",      toBd(r.get("high_price")).doubleValue());
            m.put("low",       toBd(r.get("low_price")).doubleValue());
            m.put("close",     toBd(r.get("close_price")).doubleValue());
            m.put("volume",    toBd(r.get("total_volume")).doubleValue());
            m.put("delta",     toBd(r.get("delta")).doubleValue());
            m.put("isOverlap", true);
            result.add(m);
        }

        for (Map<String, Object> r : dayRows) {
            Map<String, Object> m = new HashMap<>();
            m.put("time",      toBd(r.get("candle_time_ms")).longValue());
            m.put("open",      toBd(r.get("open_price")).doubleValue());
            m.put("high",      toBd(r.get("high_price")).doubleValue());
            m.put("low",       toBd(r.get("low_price")).doubleValue());
            m.put("close",     toBd(r.get("close_price")).doubleValue());
            m.put("volume",    toBd(r.get("total_volume")).doubleValue());
            m.put("delta",     toBd(r.get("delta")).doubleValue());
            m.put("isOverlap", false);
            result.add(m);
        }

        return result;
    }

    public List<String> getCandleDates(String symbol) {
        return agg5mRepository.findDistinctKstDates(symbol);
    }

    private long resolveCandleFromMs(String type, int limit, String range, long nowMs) {
        if (range != null && !range.isBlank()) {
            return nowMs - parseRangeToMs(range);
        }
        long candleUnitMs = "1m".equals(type) ? 60_000L : 300_000L;
        return nowMs - (limit * candleUnitMs);
    }


    private BigDecimal toBd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
    }
}
