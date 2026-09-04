// [AGENT] 역할: 데이터 누락 구간 조회 어드민 API | 연관파일: DataGapAdminService.java
// 엔드포인트: GET /api/admin/data-gap/access (접근 가능 여부), GET /api/admin/data-gap/check?type=xxx (누락 조회)
// 접근 제어: AdminIpInterceptor(/api/admin/**)가 담당
package com.chs.springboot.domain.binance.controller;

import com.chs.springboot.domain.binance.service.DataGapAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/data-gap")
public class DataGapAdminController {

    private final DataGapAdminService service;

    public DataGapAdminController(DataGapAdminService service) {
        this.service = service;
    }

    /** 접근 권한 체크 (요청이 여기까지 왔으면 인터셉터를 통과한 것) */
    @GetMapping("/access")
    public ResponseEntity<Map<String, Boolean>> checkAccess() {
        return ResponseEntity.ok(Map.of("canAccess", true));
    }

    /** 누락 구간 조회 — type: RAW_AGG_TRADE | AGG_1M | AGG_5M | KLINE_5M | FORCE_ORDER | OI */
    @GetMapping("/check")
    public ResponseEntity<?> check(
            @RequestParam String type,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) Long fromMs,
            @RequestParam(required = false) Long toMs) {
        try {
            List<Map<String, Object>> result = service.checkGap(type, days, fromMs, toMs);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
