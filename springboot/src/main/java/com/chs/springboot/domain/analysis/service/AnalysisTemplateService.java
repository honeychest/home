// [AGENT] T4-ANALYSIS: AnalysisTemplate CRUD 서비스 + delta 조회 (1m/5m/15m interval 라우팅)
// 연관파일: AnalysisTemplateRepository.java, SignalCandleSource.java, AnalysisTemplateController.java
package com.chs.springboot.domain.analysis.service;

import com.chs.springboot.domain.analysis.dto.ConditionTreeDto;
import com.chs.springboot.domain.analysis.dto.TemplateRequestDto;
import com.chs.springboot.domain.analysis.dto.TemplateResponseDto;
import com.chs.springboot.domain.analysis.model.AnalysisTemplate;
import com.chs.springboot.domain.analysis.repository.AnalysisTemplateRepository;
import com.chs.springboot.domain.binance.model.BinanceSymbolNormalizer;
import com.chs.springboot.domain.binance.service.SignalCandleSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AnalysisTemplateService {

    private final AnalysisTemplateRepository templateRepository;
    private final SignalCandleSource         candleSource;
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

    /** 5분/15분 delta 조회 상한 — 15분은 canonical 5분 행을 자바에서 묶어 집계하므로
     * 요청 범위가 무제한이면 512MB 힙에서 위험(kline-temp-retire-plan.md 7단계 검수 참고). */
    private static final long MAX_FIVE_OR_FIFTEEN_MINUTE_DELTA_RANGE_MS = 90L * 24 * 60 * 60 * 1000;

    /**
     * delta 시간범위 조회 — interval에 따라 1m/5m 조회, 15m는 5m에서 집계
     * @param symbol   'BTCUSDT' | 'ENAUSDT' (짧은 심볼도 전체 심볼로 정규화)
     * @param interval '1m' | '5m' | '15m'
     */
    public List<Map<String, Object>> getDelta(String symbol, long startMs, long endMs, String interval) {
        String dbSymbol = BinanceSymbolNormalizer.normalize(symbol);
        SignalCandleSource.Interval sourceInterval = SignalCandleSource.Interval.from(interval);
        if (sourceInterval == SignalCandleSource.Interval.FIVE_MINUTES
                || sourceInterval == SignalCandleSource.Interval.FIFTEEN_MINUTES) {
            validateFiveOrFifteenMinuteRange(startMs, endMs);
        }
        return candleSource.find(dbSymbol, sourceInterval, startMs, endMs, SignalCandleSource.QueryMode.COMPLETED)
            .stream().map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("timeMs",  c.timeMs());
            m.put("volume",  c.baseVolume().doubleValue());
            m.put("delta",   c.delta().doubleValue());
            return m;
        }).toList();
    }

    /** long 오버플로로 90일 상한이 우회되지 않게 {@link Math#subtractExact}로 뺄셈한다. */
    private void validateFiveOrFifteenMinuteRange(long startMs, long endMs) {
        if (endMs < startMs) {
            throw new IllegalArgumentException("delta 조회 범위는 startMs가 endMs보다 작아야 합니다");
        }
        long rangeMs;
        try {
            rangeMs = Math.subtractExact(endMs, startMs);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("5분/15분 delta 조회 범위는 최대 90일입니다");
        }
        if (rangeMs > MAX_FIVE_OR_FIFTEEN_MINUTE_DELTA_RANGE_MS) {
            throw new IllegalArgumentException("5분/15분 delta 조회 범위는 최대 90일입니다");
        }
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

            String dbSymbol = BinanceSymbolNormalizer.normalize(symbol);
            java.util.List<SignalCandleSource.SignalCandle> rows = candleSource.find(
                    dbSymbol, SignalCandleSource.Interval.FIVE_MINUTES, dayStart, dayEnd,
                    SignalCandleSource.QueryMode.COMPLETED);
            if (rows.isEmpty()) {
                continue;
            }

            java.util.List<AnalysisDetectionEngine.CandleData> kline = rows.stream()
                    .map(SignalCandleAnalysisConverter::toCandleData)
                    .toList();

            java.util.List<Integer> matched = detectionEngine.evaluate(kline, tree);
            if (matched.isEmpty()) {
                continue;
            }

            java.util.List<Map<String, Object>> candles = new java.util.ArrayList<>(rows.size());
            for (SignalCandleSource.SignalCandle row : rows) {
                Map<String, Object> c = new HashMap<>();
                c.put("time",   row.timeMs());
                c.put("open",   row.openPrice().doubleValue());
                c.put("high",   row.highPrice().doubleValue());
                c.put("low",    row.lowPrice().doubleValue());
                c.put("close",  row.closePrice().doubleValue());
                c.put("volume", row.quoteVolume().doubleValue());
                c.put("delta",  row.delta().doubleValue());
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

}
