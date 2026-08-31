package com.chs.springboot.global.feature;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SiteThemeController {

    private final SiteThemeService siteThemeService;

    /** 공개 — 모든 방문자가 현재 테마 조회 */
    @GetMapping("/site-theme")
    public ResponseEntity<Map<String, String>> getThemes() {
        return ResponseEntity.ok(siteThemeService.getAll());
    }

    /** 페이지별 테마 변경 — /api/admin/** 보안 규칙으로 관리자만 허용 */
    @PatchMapping("/admin/site-theme")
    public ResponseEntity<Map<String, String>> patchThemes(@RequestBody Map<String, String> req) {
        try {
            for (Map.Entry<String, String> entry : req.entrySet()) {
                siteThemeService.setTheme(entry.getKey(), entry.getValue());
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(siteThemeService.getAll());
    }
}
