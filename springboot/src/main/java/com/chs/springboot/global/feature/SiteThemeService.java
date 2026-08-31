package com.chs.springboot.global.feature;

import com.chs.springboot.global.config.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SiteThemeService {

    private static final String PREFIX = "theme:";
    private static final String DEFAULT_THEME = "dark";
    private static final Set<String> ALLOWED_THEMES = Set.of("dark", "black", "teal", "harbor");

    /** 현재 지원하는 페이지 키 목록 — 페이지 추가 시 여기만 확장 */
    private static final Map<String, String> PAGE_DEFAULTS = Map.of(
            "analysis", DEFAULT_THEME,
            "binance", DEFAULT_THEME,
            "trade", DEFAULT_THEME,
            "signal", "black"
    );

    private final AppConfigService appConfigService;

    public Map<String, String> getAll() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : PAGE_DEFAULTS.entrySet()) {
            String page = entry.getKey();
            String defaultValue = entry.getValue();
            result.put(page, getTheme(page, defaultValue));
        }
        return result;
    }

    public String getTheme(String page, String defaultValue) {
        String pageDefault = PAGE_DEFAULTS.get(page);
        if (pageDefault == null) return DEFAULT_THEME;
        String safeDefault = normalizeTheme(defaultValue, pageDefault);
        try {
            String value = appConfigService.get(PREFIX + page);
            return normalizeTheme(value, safeDefault);
        } catch (Exception e) {
            return safeDefault;
        }
    }

    public void setTheme(String page, String theme) {
        if (!PAGE_DEFAULTS.containsKey(page)) {
            throw new IllegalArgumentException("지원하지 않는 페이지입니다");
        }
        String normalizedTheme = normalizeTheme(theme, null);
        if (normalizedTheme == null) {
            throw new IllegalArgumentException("지원하지 않는 테마입니다");
        }
        appConfigService.set(PREFIX + page, normalizedTheme);
    }

    private static String normalizeTheme(String value, String fallback) {
        if (value == null) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_THEMES.contains(normalized) ? normalized : fallback;
    }
}
