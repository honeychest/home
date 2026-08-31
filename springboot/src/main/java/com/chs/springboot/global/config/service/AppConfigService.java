// [AGENT] 앱 설정 서비스 — DB 원본, Redis 캐시, 시작 시 기본값 초기화                                                                       
package com.chs.springboot.global.config.service;

import com.chs.springboot.global.config.entity.AppConfig;
import com.chs.springboot.global.config.repository.AppConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppConfigService {

    private static final Set<String> MIGRATABLE_THEMES = Set.of("dark", "black", "teal", "harbor");

    private final AppConfigRepository appConfigRepository;
    private final StringRedisTemplate redisTemplate;

    public static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("config:aggtrade:max-queue-size",    "200000"),
            Map.entry("config:aggtrade:flush-threshold",   "20000"),
            Map.entry("config:aggtrade:batch-size",         "10000"),
            Map.entry("config:aggtrade:flush-interval-sec", "10"),
            Map.entry("config:aggtrade:dedup-ttl-sec",      "60"),
            Map.entry("config:aggtrade:weight-per-minute",  "1200"),
            Map.entry("config:threshold",                   "100000"),
            Map.entry("feature:trade:threshold-edit",       "ON"),
            Map.entry("feature:monitor:allowed-ip-manage",  "OFF"),
            Map.entry("monitor:silence",                    "OFF"),
            Map.entry("theme:analysis",                     "dark"),
            Map.entry("theme:binance",                      "dark"),
            Map.entry("theme:trade",                        "dark"),
            Map.entry("theme:signal",                       "black")
    );

    @PostConstruct
    public void init() {
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            String key = entry.getKey();
            String defaultValue = entry.getValue();

            appConfigRepository.findByConfigKey(key).ifPresentOrElse(
                    existing -> log.info("[AppConfig] DB 기존값 유지: {}={}", key, existing.getConfigValue()),
                    () -> {
                        AppConfig config = new AppConfig();
                        config.setConfigKey(key);
                        config.setConfigValue(migratedValue(key, defaultValue));
                        appConfigRepository.save(config);
                        log.info("[AppConfig] DB 기본값 초기화: {}={}", key, config.getConfigValue());
                    }
            );
        }
    }

    public String get(String key) {
        // Redis 우선
        try {
            String val = redisTemplate.opsForValue().get(key);
            if (val != null) return val;
        } catch (Exception e) {
            log.warn("[AppConfig] Redis 조회 실패: {}", e.getMessage());
        }
        // DB fallback
        return appConfigRepository.findByConfigKey(key)
                .map(AppConfig::getConfigValue)
                .orElse(null);
    }

    public void set(String key, String value) {
        // DB 저장을 먼저 완료해 Redis 장애가 설정 변경을 실패시키지 않도록 한다.
        AppConfig config = appConfigRepository.findByConfigKey(key)
                .orElseGet(() -> {
                    AppConfig c = new AppConfig();
                    c.setConfigKey(key);
                    return c;
                });
        config.setConfigValue(value);
        appConfigRepository.save(config);

        // 캐시는 DB 반영 후 무효화하고 새 값을 best-effort로 저장한다.
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("[AppConfig] Redis 캐시 무효화 실패: key={} error={}", key, e.getMessage());
        }
        try {
            redisTemplate.opsForValue().set(key, value);
        } catch (Exception e) {
            log.warn("[AppConfig] Redis 캐시 저장 실패: key={} error={}", key, e.getMessage());
        }
    }

    private String migratedValue(String key, String defaultValue) {
        if (!key.startsWith("feature:") && !key.startsWith("theme:") && !"monitor:silence".equals(key)) {
            return defaultValue;
        }

        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) return defaultValue;
            if (key.startsWith("feature:") || "monitor:silence".equals(key)) {
                return isEnabledValue(value) ? "ON" : "OFF";
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return MIGRATABLE_THEMES.contains(normalized)
                    ? normalized
                    : defaultValue;
        } catch (Exception e) {
            log.warn("[AppConfig] Redis 기존값 이관 조회 실패: key={} error={}", key, e.getMessage());
            return defaultValue;
        }
    }

    private static boolean isEnabledValue(String value) {
        String normalized = value.trim();
        return "ON".equalsIgnoreCase(normalized)
                || "TRUE".equalsIgnoreCase(normalized)
                || "1".equals(normalized);
    }
}
