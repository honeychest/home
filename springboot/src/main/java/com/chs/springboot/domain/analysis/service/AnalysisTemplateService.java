// [AGENT] T4-ANALYSIS: AnalysisTemplate CRUD 서비스 + delta 조회 (1m/5m/15m interval 라우팅)
// 연관파일: AnalysisTemplateRepository.java, AggTrade1mRepository.java, AggTrade5mRepository.java, AnalysisTemplateController.java
package com.chs.springboot.domain.analysis.service;

import com.chs.springboot.domain.analysis.dto.ConditionTreeDto;
import com.chs.springboot.domain.analysis.dto.TemplateRequestDto;
import com.chs.springboot.domain.analysis.dto.TemplateResponseDto;
import com.chs.springboot.domain.analysis.model.AnalysisTemplate;
import com.chs.springboot.domain.analysis.repository.AnalysisTemplateRepository;
import com.chs.springboot.domain.binance.repository.AggTrade1mRepository;
import com.chs.springboot.domain.binance.repository.AggTrade5mRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AnalysisTemplateService {

    private final AnalysisTemplateRepository templateRepository;
    private final AggTrade1mRepository       agg1mRepository;
    private final AggTrade5mRepository       agg5mRepository;
    private final AnalysisDetectionEngine    detectionEngine;
    private final ObjectMapper               objectMapper;

    public List<TemplateResponseDto> findAll() {
        return templateRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    public TemplateResponseDto save(TemplateRequestDto req) {
        AnalysisTemplate t = new AnalysisTemplate();
        t.setName(req.getName());
        t.setConditions(req.getConditions());
        t.setPalette(req.getPalette());
        return toDto(templateRepository.save(t));
    }

    public TemplateResponseDto rename(Long id, TemplateRequestDto req) {
        AnalysisTemplate t = templateRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found: " + id));
        if (req.getName() != null) {
            t.setName(req.getName());
        }
        if (req.getConditions() != null) {
            t.setConditions(req.getConditions());
        }
        if (req.getPalette() != null) {
            t.setPalette(req.getPalette());
        }
        return toDto(templateRepository.save(t));
    }

    public void delete(Long id) {
        if (!templateRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found: " + id);
        }
        templateRepository.deleteById(id);
    }

    /**
     * delta 시간범위 조회 — interval에 따라 1m/5m 조회, 15m는 5m에서 집계
     * @param symbol   'BTC' | 'ENA' → BTCUSDT / ENAUSDT 변환
     * @param interval '1m' | '5m' | '15m'
     */
    public List<Map<String, Object>> getDelta(String symbol, long startMs, long endMs, String interval) {
        String dbSymbol = symbol.toUpperCase() + "USDT";
        List<Map<String, Object>> rows;
        if ("15m".equals(interval)) {
            rows = agg5mRepository.findDelta15mByTimeRange(dbSymbol, startMs, endMs);
        } else if ("5m".equals(interval)) {
            rows = agg5mRepository.findDeltaByTimeRange(dbSymbol, startMs, endMs);
        } else {
            rows = agg1mRepository.findDeltaByTimeRange(dbSymbol, startMs, endMs);
        }
        return rows.stream().map(r -> {
            Map<String, Object> m = new HashMap<>();
            m.put("timeMs",  toBd(r.get("timeMs")).longValue());
            m.put("volume",  toBd(r.get("volume")).doubleValue());
            m.put("delta",   toBd(r.get("delta")).doubleValue());
            return m;
        }).toList();
    }

    /**
     * 템플릿 기준 시그널 날짜 조회:
     * - 기준: UTC 오늘부터 과거 days일 동안의 5분봉
     * - 시그널이 1개 이상 있는 날짜만 entries로 반환
     * - entries는 최신 날짜가 앞에 오도록 정렬
     */
    public Map<String, Object> getSignalDays(String symbol, long templateId, int days) {
        int lookbackDays = Math.max(1, Math.min(days, 365));

        AnalysisTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found: " + templateId));

        ConditionTreeDto tree;
        try {
            tree = objectMapper.readValue(template.getConditions(), ConditionTreeDto.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid template conditions JSON for id=" + templateId, e);
        }

        java.time.LocalDate todayUtc = java.time.LocalDate.now(java.time.ZoneOffset.UTC);

        java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>();

        for (int offset = 0; offset < lookbackDays && entries.size() < 5; offset++) {
            java.time.LocalDate day = todayUtc.minusDays(offset);
            long dayStart = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            long dayEnd   = dayStart + 86_400_000L;

            java.util.List<Map<String, Object>> rows = agg5mRepository.findByDateRange(symbol, dayStart, dayEnd);
            if (rows == null || rows.isEmpty()) {
                continue;
            }

            java.util.List<AnalysisDetectionEngine.CandleData> kline = new java.util.ArrayList<>(rows.size());
            for (Map<String, Object> r : rows) {
                long   timeMs = toBd(r.get("candle_time_ms")).longValue();
                double open   = toBd(r.get("open_price")).doubleValue();
                double high   = toBd(r.get("high_price")).doubleValue();
                double low    = toBd(r.get("low_price")).doubleValue();
                double close  = toBd(r.get("close_price")).doubleValue();
                double volume = toBd(r.get("total_volume")).doubleValue();
                double delta  = toBd(r.get("delta")).doubleValue();
                kline.add(new AnalysisDetectionEngine.CandleData(
                        timeMs, open, high, low, close, volume, delta
                ));
            }

            java.util.List<Integer> matched = detectionEngine.evaluate(kline, tree);
            if (matched.isEmpty()) {
                continue;
            }

            java.util.List<Map<String, Object>> candles = new java.util.ArrayList<>(rows.size());
            for (Map<String, Object> r : rows) {
                Map<String, Object> c = new HashMap<>();
                c.put("time",   toBd(r.get("candle_time_ms")).longValue());
                c.put("open",   toBd(r.get("open_price")).doubleValue());
                c.put("high",   toBd(r.get("high_price")).doubleValue());
                c.put("low",    toBd(r.get("low_price")).doubleValue());
                c.put("close",  toBd(r.get("close_price")).doubleValue());
                c.put("volume", toBd(r.get("total_volume")).doubleValue());
                c.put("delta",  toBd(r.get("delta")).doubleValue());
                candles.add(c);
            }

            java.util.List<Map<String, Object>> events = new java.util.ArrayList<>(matched.size());
            for (Integer idx : matched) {
                if (idx == null) continue;
                Map<String, Object> ev = new HashMap<>();
                ev.put("idx", idx);
                events.add(ev);
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("dateStr", day.toString());
            entry.put("candles", candles);
            entry.put("events",  events);
            entries.add(entry);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("entries", entries);
        return result;
    }

    private TemplateResponseDto toDto(AnalysisTemplate t) {
        return new TemplateResponseDto(
                t.getId(), t.getName(), t.getConditions(),
                t.getPalette(), t.getCreatedAt(), t.getUpdatedAt());
    }

    private BigDecimal toBd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
    }
}
