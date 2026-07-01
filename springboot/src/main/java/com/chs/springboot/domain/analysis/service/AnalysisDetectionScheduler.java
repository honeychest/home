// [AGENT] T4-ANALYSIS: TASK-14 — 1분 주기 리더 노드 전용 탐지 스케줄러
// 저장된 템플릿 전체를 최근 1440봉에 적용 → 매칭 발생 시 SSE analysis_match 이벤트 전송
// 연관: AnalysisDetectionEngine, AnalysisTemplateRepository, SignalSseService, LeaderElectionService
package com.chs.springboot.domain.analysis.service;

import com.chs.springboot.domain.analysis.dto.ConditionTreeDto;
import com.chs.springboot.domain.analysis.model.AnalysisTemplate;
import com.chs.springboot.domain.analysis.repository.AnalysisTemplateRepository;
import com.chs.springboot.domain.binance.repository.AggTrade1mRepository;
import com.chs.springboot.domain.binance.service.SignalSseService;
import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import com.chs.springboot.global.redis.LeaderElectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
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
    private final AggTrade1mRepository        agg1mRepository;
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

        for (String symbol : SYMBOLS) {
            List<Map<String, Object>> rows = agg1mRepository.findTopNWithCombinedDelta(symbol, LIMIT_COUNT);
            if (rows == null || rows.isEmpty()) continue;

            // findTopNWithCombinedDelta는 내림차순 — 오름차순으로 역정렬
            List<Map<String, Object>> sorted = new ArrayList<>(rows);
            Collections.reverse(sorted);

            List<AnalysisDetectionEngine.CandleData> klineData = toCandles(sorted);

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

    private List<AnalysisDetectionEngine.CandleData> toCandles(List<Map<String, Object>> rows) {
        List<AnalysisDetectionEngine.CandleData> list = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            list.add(new AnalysisDetectionEngine.CandleData(
                    toLong(r.get("candle_time_ms")),
                    toDouble(r.get("open_price")),
                    toDouble(r.get("high_price")),
                    toDouble(r.get("low_price")),
                    toDouble(r.get("close_price")),
                    0.0,                              // volume: 해당 쿼리 미포함
                    toDouble(r.get("delta"))
            ));
        }
        return list;
    }

    private long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Long l) return l;
        return new BigDecimal(v.toString()).longValue();
    }

    private double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Double d) return d;
        return new BigDecimal(v.toString()).doubleValue();
    }
}
