// [AGENT] 역할: Google Safe Browsing API v4 — 메시지 내 URL 악성 여부 검사 | 연관파일: ContactController.java | 동작: 텍스트에서 URL 추출→위협(MALWARE/SOCIAL_ENGINEERING/UNWANTED_SOFTWARE) 탐지, 429(할당량초과)→통과, API키 미설정→스킵 | 일일 한도 10,000회
package com.chs.springboot.global.security;

import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthCheckRecorder;
import com.chs.springboot.global.monitor.health.HealthStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Google Safe Browsing API v4 를 사용한 URL 악성 여부 검사.
 * 메시지에서 URL을 추출하여 MALWARE / SOCIAL_ENGINEERING(피싱) / UNWANTED_SOFTWARE 위협을 탐지한다.
 * API 키 미설정 시 검사를 건너뛰고 통과 처리한다.
 */
@Slf4j
@Service
public class SafeBrowsingService {

    private static final String API_URL = "https://safebrowsing.googleapis.com/v4/threatMatches:find?key=";
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[\\S]+|www\\.[\\S]+)", Pattern.CASE_INSENSITIVE
    );

    @Value("${google.safebrowsing.api-key:}")
    private String apiKey;

    private static final String HEALTH_KEY = HealthCheckCatalog.EXT_SAFEBROWSING.key();
    private final HealthCheckRecorder healthCheckRecorder;

    public SafeBrowsingService(HealthCheckRecorder healthCheckRecorder) {
        this.healthCheckRecorder = healthCheckRecorder;
    }

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 텍스트에서 URL을 추출해 Safe Browsing API로 검사한다.
     *
     * @return true = 안전 (URL 없음 포함), false = 위협 감지
     */
    public boolean isSafe(String text) {
        if (apiKey.isBlank()) {
            log.warn("Safe Browsing API key not configured, skipping URL check");
            return true;
        }

        List<String> urls = extractUrls(text);
        if (urls.isEmpty()) return true;

        try {
            List<Map<String, String>> threatEntries = urls.stream()
                    .map(url -> Map.of("url", url))
                    .collect(Collectors.toList());

            Map<String, Object> body = Map.of(
                    "client",     Map.of("clientId", "chs-home", "clientVersion", "1.0.0"),
                    "threatInfo", Map.of(
                            "threatTypes",      List.of("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE"),
                            "platformTypes",    List.of("ANY_PLATFORM"),
                            "threatEntryTypes", List.of("URL"),
                            "threatEntries",    threatEntries
                    )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    API_URL + apiKey, new HttpEntity<>(body, headers), Map.class
            );

            healthCheckRecorder.markOk(HEALTH_KEY); // API 정상 응답
            // matches 키가 있으면 위협 감지
            boolean threat = response != null && response.containsKey("matches");
            if (threat) log.warn("Safe Browsing threat detected in message");
            return !threat;

        } catch (HttpClientErrorException.TooManyRequests e) {
            // 일일 할당량(10,000회) 초과 → 통과 (사용자 차단 안 함)
            healthCheckRecorder.record(HEALTH_KEY, HealthStatus.DEGRADED, "SafeBrowsing 할당량 초과: " + e.getMessage());
            log.error("Safe Browsing 할당량 초과 — URL 검사를 건너뜁니다. ({})", e.getMessage());
            return true;
        } catch (Exception e) {
            // 기타 API 호출 실패 → 통과 처리 (서비스 중단 방지)
            healthCheckRecorder.record(HEALTH_KEY, HealthStatus.DOWN, "SafeBrowsing API 오류: " + e.getMessage());
            log.error("Safe Browsing API error: {}", e.getMessage());
            return true;
        }
    }

    private List<String> extractUrls(String text) {
        Matcher matcher = URL_PATTERN.matcher(text);
        List<String> urls = new java.util.ArrayList<>();
        while (matcher.find()) urls.add(matcher.group());
        return urls;
    }
}
