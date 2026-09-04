// [AGENT] T4-ANALYSIS: 애널리시스 템플릿 REST 컨트롤러
// 엔드포인트: GET/POST /api/analysis/templates, PUT/DELETE /api/analysis/templates/{id}
//             GET /api/analysis/delta, POST /api/analysis/search
// 연관파일: AnalysisTemplateService.java, AnalysisSearchService.java
package com.chs.springboot.domain.analysis.controller;

import com.chs.springboot.domain.analysis.dto.AnalysisSearchRequest;
import com.chs.springboot.domain.analysis.dto.TemplateRequestDto;
import com.chs.springboot.domain.analysis.dto.TemplateResponseDto;
import com.chs.springboot.domain.analysis.service.AnalysisSearchService;
import com.chs.springboot.domain.analysis.service.AnalysisTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class AnalysisTemplateController {

    private final AnalysisTemplateService templateService;
    private final AnalysisSearchService analysisSearchService;

    @GetMapping("/templates")
    public ResponseEntity<List<TemplateResponseDto>> getAll() {
        return ResponseEntity.ok(templateService.findAll());
    }

    @PostMapping("/templates")
    public ResponseEntity<TemplateResponseDto> create(@RequestBody TemplateRequestDto req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.save(req));
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<TemplateResponseDto> rename(
            @PathVariable Long id,
            @RequestBody TemplateRequestDto req) {
        return ResponseEntity.ok(templateService.rename(id, req));
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/delta")
    public ResponseEntity<?> getDelta(
            @RequestParam String symbol,
            @RequestParam long startMs,
            @RequestParam long endMs,
            @RequestParam(defaultValue = "1m") String interval) {
        log.debug("[AnalysisTemplateController] /delta symbol={} startMs={} endMs={} interval={}", symbol, startMs, endMs, interval);
        try {
            return ResponseEntity.ok(templateService.getDelta(symbol, startMs, endMs, interval));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/search")
    public ResponseEntity<List<Long>> search(@RequestBody @Valid AnalysisSearchRequest req) {
        log.debug("[AnalysisTemplateController] /search symbol={} timeframe={} fromMs={} toMs={}",
                req.getSymbol(), req.getTimeframe(), req.getFromMs(), req.getToMs());
        return ResponseEntity.ok(analysisSearchService.search(req));
    }

    @GetMapping("/templates/{id}/signals")
    public ResponseEntity<Map<String, Object>> getTemplateSignals(
            @PathVariable("id") long templateId,
            @RequestParam String symbol,
            @RequestParam(name = "days", defaultValue = "10") int days) {
        log.debug("[AnalysisTemplateController] /templates/{}/signals symbol={} days={}", templateId, symbol, days);
        return ResponseEntity.ok(templateService.getSignalDays(symbol, templateId, days));
    }
}
