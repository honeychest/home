// [AGENT] T4-ANALYSIS: TASK-14 — 1분 주기 리더 노드 전용 탐지 스케줄러
// 저장된 템플릿 전체를 최근 1440봉에 적용 → 매칭 발생 시 SSE analysis_match 이벤트 전송
// 연관: AnalysisDetectionEngine, AnalysisTemplateRepository, SignalSseService, LeaderElectionService
package com.chs.springboot.domain.analysis.service;

import com.chs.springboot.domain.analysis.dto.ConditionTreeDto;
import com.chs.springboot.domain.analysis.model.AnalysisTemplate;
import com.chs.springboot.domain.analysis.repository.AnalysisTemplateRepository;
import com.chs.springboot.domain.binance.service.BinanceKlineWindow;
import com.chs.springboot.domain.binance.service.SignalCandleSource;
import com.chs.springboot.domain.binance.service.SignalSseService;
import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import com.chs.springboot.global.redis.LeaderElectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisDetectionScheduler {

    private static final int LIMIT_COUNT = 1440;
    private static final List<String> SYMBOLS = List.of("BTCUSDT", "ENAUSDT");

    private final AnalysisTemplateRepository templateRepository;
    private final AnalysisDetectionEngine     detectionEngine;
    private final SignalSseService            signalSseService;
    private final LeaderElectionService       leaderElectionService;
    private final SignalCandleSource          candleSource;
    private final ObjectMapper                objectMapper;
    private final HealthHeartbeat             healthHeartbeat;

    private static final String HEALTH_KEY = HealthCheckCatalog.SCHED_ANALYSIS.key();

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        if (!leaderElectionService.isLeader()) return;

        List<AnalysisTemplate> templates = templateRepository.findAllByOrderByCreatedAtDesc();
        if (templates.isEmpty()) {
            healthHeartbeat.beat(HEALTH_KEY);
            return;
        }

        long endMs = BinanceKlineWindow.safeEnd(System.currentTimeMillis());
        long fromMs = endMs - LIMIT_COUNT * SignalCandleSource.Interval.ONE_MINUTE.durationMs();
        for (String symbol : SYMBOLS) {
            List<SignalCandleSource.SignalCandle> candles = candleSource.find(
                    symbol, SignalCandleSource.Interval.ONE_MINUTE, fromMs, endMs,
                    SignalCandleSource.QueryMode.COMPLETED);
            if (candles.isEmpty()) continue;
            if (!isContinuousWindow(candles, fromMs, endMs)) {
                log.warn("[AnalysisDetectionScheduler] {} 최근 {}개 1분봉이 결측 또는 불연속이라 탐지를 건너뜁니다",
                        symbol, LIMIT_COUNT);
                continue;
            }

            List<AnalysisDetectionEngine.CandleData> klineData = candles.stream()
                    .map(SignalCandleAnalysisConverter::toCandleData)
                    .toList();

            for (AnalysisTemplate template : templates) {
                try {
                    ConditionTreeDto tree = objectMapper.readValue(template.getConditions(), ConditionTreeDto.class);
                    List<Integer> matched = detectionEngine.evaluate(klineData, tree);
                    if (!matched.isEmpty()) {
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("symbol",       symbol);
                        payload.put("templateId",   template.getId());
                        payload.put("templateName", template.getName());
                        payload.put("matchCount",   matched.size());
                        payload.put("lastMatchIdx", matched.get(matched.size() - 1));
                        signalSseService.broadcastAnalysisMatch(payload);
                    }
                } catch (Exception e) {
                    log.error("[AnalysisDetectionScheduler] 템플릿 처리 실패 id={} symbol={}: {}",
                            template.getId(), symbol, e.getMessage());
                }
            }
        }
        healthHeartbeat.beat(HEALTH_KEY);
    }

    private boolean isContinuousWindow(List<SignalCandleSource.SignalCandle> candles,
                                       long fromMs, long toMsExclusive) {
        if (candles.size() != LIMIT_COUNT) {
            return false;
        }
        long expectedTimeMs = fromMs;
        for (SignalCandleSource.SignalCandle candle : candles) {
            if (candle.timeMs() != expectedTimeMs || expectedTimeMs >= toMsExclusive) {
                return false;
            }
            expectedTimeMs += SignalCandleSource.Interval.ONE_MINUTE.durationMs();
        }
        return expectedTimeMs == toMsExclusive;
    }

}
