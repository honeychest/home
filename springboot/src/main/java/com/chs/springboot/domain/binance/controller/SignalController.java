// [AGENT] T4-STEALTH: Signal Dashboard REST + SSE 컨트롤러 — /api/signal/* 엔드포인트
// 연관파일: SignalDataService.java, SignalSseService.java, PatternMatchService.java
// 주요엔드포인트: GET /init, GET /history, GET /patterns, GET /stream/sse, GET|PUT /params, GET /pattern, GET /score, GET /divergence, GET /candles(range/date+overlap추가), GET /candles/dates
package com.chs.springboot.domain.binance.controller;

import com.chs.springboot.domain.binance.service.SignalDataService;
import com.chs.springboot.domain.binance.service.SignalSseService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.chs.springboot.domain.binance.service.PatternMatchService;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/signal")
@RequiredArgsConstructor
public class SignalController {

    private final SignalDataService signalDataService;
    private final SignalSseService  signalSseService;
    private final PatternMatchService patternMatchService;

    @Value("${binance.threshold.allowed-ips:}")
    private String allowedIps;

    private static String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String ip  = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) return "127.0.0.1";
        return ip;
    }

    private boolean canEdit(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        if (allowedIps == null || allowedIps.isBlank()) return true;
        Set<String> allowed = Arrays.stream(allowedIps.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        allowed.add("127.0.0.1");
        return allowed.contains(clientIp);
    }

    @GetMapping("/init")
    public ResponseEntity<Map<String, Object>> init(@RequestParam String symbol) {
        log.debug("[SignalController] /init symbol={}", symbol);
        Map<String, Object> data = signalDataService.getInitData(symbol);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(
            @RequestParam String symbol,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) Long fromMs) {
        log.debug("[SignalController] /history symbol={} range={} fromMs={}", symbol, range, fromMs);
        Map<String, Object> data = signalDataService.getHistoryData(symbol, range, fromMs);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/patterns")
    public ResponseEntity<List<Map<String, Object>>> patterns(
            @RequestParam String symbol,
            @RequestParam BigDecimal volume) {
        log.debug("[SignalController] /patterns symbol={} volume={}", symbol, volume);
        List<Map<String, Object>> patterns = signalDataService.findPatterns(symbol, volume);
        return ResponseEntity.ok(patterns);
    }

    @GetMapping(value = "/stream/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSse(@RequestParam String symbol, HttpServletRequest request) {
        log.info("[SignalController] /stream/sse symbol={} ip={}", symbol, getClientIp(request));
        return signalSseService.subscribe();
    }

    @GetMapping("/params")
    public ResponseEntity<Map<String, Object>> getParams(
            @RequestParam String symbol,
            HttpServletRequest request) {
        log.debug("[SignalController] GET /params symbol={}", symbol);
        Map<String, Object> result = signalDataService.getParams(symbol);
        result.put("canEdit", canEdit(request));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/score")
    public ResponseEntity<Map<String, Object>> getScore(
            @RequestParam String symbol,
            @RequestParam long candle_time) {
        log.debug("[SignalController] GET /score symbol={} candle_time={}", symbol, candle_time);
        Map<String, Object> data = signalDataService.getScore(symbol, candle_time, patternMatchService);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/pattern")
    public ResponseEntity<Map<String, Object>> getPattern(
            @RequestParam String symbol,
            @RequestParam long candle_time) {
        log.debug("[SignalController] GET /pattern symbol={} candle_time={}", symbol, candle_time);
        Map<String, Object> data = patternMatchService.getPattern(symbol, candle_time);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/candles")
    public ResponseEntity<List<Map<String, Object>>> getCandles(
            @RequestParam String symbol,
            @RequestParam String type,
            @RequestParam(defaultValue = "90") int limit,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "0") int overlap) {
        log.debug("[SignalController] GET /candles symbol={} type={} limit={} range={} date={} overlap={}", symbol, type, limit, range, date, overlap);
        if (date != null && !date.isEmpty()) {
            return ResponseEntity.ok(signalDataService.getCandlesByDate(symbol, type, date, overlap));
        }
        List<Map<String, Object>> data = signalDataService.getCandles(symbol, type, limit, range);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/candles/dates")
    public ResponseEntity<Map<String, List<String>>> getCandleDates(@RequestParam String symbol) {
        log.debug("[SignalController] GET /candles/dates symbol={}", symbol);
        return ResponseEntity.ok(Map.of("dates", signalDataService.getCandleDates(symbol)));
    }

    @GetMapping("/oi")
    public ResponseEntity<List<Map<String, Object>>> getOiHistory(
            @RequestParam String symbol,
            @RequestParam String range) {
        log.debug("[SignalController] GET /oi symbol={} range={}", symbol, range);
        List<Map<String, Object>> data = signalDataService.getOiHistory(symbol, range);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/divergence")
    public ResponseEntity<Map<String, Object>> getDivergence(
            @RequestParam String symbol,
            @RequestParam String timeline) {
        log.debug("[SignalController] GET /divergence symbol={} timeline={}", symbol, timeline);
        Map<String, Object> data = signalDataService.getDivergence(symbol, timeline);
        return ResponseEntity.ok(data);
    }


    @PutMapping("/params")
    public ResponseEntity<?> putParams(
            @RequestParam String symbol,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        log.debug("[SignalController] PUT /params symbol={}", symbol);
        if (!canEdit(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "권한 없음"));
        }
        int volWindow           = ((Number) body.get("vol_window")).intValue();
        double triggerMultiplier = ((Number) body.get("trigger_multiplier")).doubleValue();
        int stripCount          = ((Number) body.get("strip_count")).intValue();
        Map<String, Object> result = signalDataService.saveParams(symbol, volWindow, triggerMultiplier, stripCount);
        result.put("canEdit", true);
        return ResponseEntity.ok(result);
    }
}
