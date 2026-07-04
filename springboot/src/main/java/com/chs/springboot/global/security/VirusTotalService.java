// [AGENT] 역할: VirusTotal API v3 — SHA-256 해시로 파일 악성코드 검사 | 연관파일: ContactController.java | 동작: 조회성공+malicious>0→차단, 404(미등록)→통과, 429(할당량초과)→통과, API키 미설정→스킵 | 일일 한도 500회(10회 이하 경고)
package com.chs.springboot.global.security;

import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthCheckRecorder;
import com.chs.springboot.global.monitor.health.HealthStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.security.MessageDigest;
import java.util.Map;

/**
 * VirusTotal API v3 를 사용한 파일 악성코드 검사.
 * SHA-256 해시로 기존 분석 결과를 조회한다.
 * - 조회 성공 + malicious > 0 → 차단
 * - 조회 성공 + malicious = 0 → 통과
 * - 미등록 파일(404)          → 통과 (MagicBytes가 1차 검증 완료)
 * API 키 미설정 시 검사를 건너뛰고 통과 처리한다.
 */
@Slf4j
@Service
public class VirusTotalService {

    private static final String API_URL = "https://www.virustotal.com/api/v3/files/";

    @Value("${virustotal.api-key:}")
    private String apiKey;

    private static final String HEALTH_KEY = HealthCheckCatalog.EXT_SECURITY_SCAN.key();
    private final HealthCheckRecorder healthCheckRecorder;

    public VirusTotalService(HealthCheckRecorder healthCheckRecorder) {
        this.healthCheckRecorder = healthCheckRecorder;
    }

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 파일의 SHA-256 해시로 VirusTotal 악성 여부를 조회한다.
     *
     * @return true = 안전, false = 악성 파일로 등록됨
     */
    public boolean isSafe(byte[] fileBytes) {
        if (apiKey.isBlank()) {
            log.warn("VirusTotal API key not configured, skipping file check");
            return true;
        }

        try {
            String hash = sha256(fileBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.set("x-apikey", apiKey);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(
                    API_URL + hash, HttpMethod.GET, new HttpEntity<>(headers), Map.class
            );

            // 잔여 호출 수 체크 (일일 한도 500회, 10회 이하 시 경고)
            String remaining = response.getHeaders().getFirst("X-RateLimit-Remaining");
            if (remaining != null) {
                int remainingCount = Integer.parseInt(remaining);
                if (remainingCount <= 10) {
                    log.warn("VirusTotal 일일 잔여 호출 {}회 — 한도 초과 임박", remainingCount);
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data       = (Map<String, Object>) response.getBody().get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> attributes = (Map<String, Object>) data.get("attributes");
            @SuppressWarnings("unchecked")
            Map<String, Object> stats      = (Map<String, Object>) attributes.get("last_analysis_stats");

            healthCheckRecorder.markOk(HEALTH_KEY); // API 정상 응답
            int malicious = (int) stats.getOrDefault("malicious", 0);
            if (malicious > 0) {
                log.warn("VirusTotal: malicious file detected (hash={})", hash);
                return false;
            }
            return true;

        } catch (HttpClientErrorException.NotFound e) {
            // VirusTotal DB에 없는 파일 → 통과 (신규 스크린샷 등). API 자체는 정상 응답.
            healthCheckRecorder.markOk(HEALTH_KEY);
            return true;
        } catch (HttpClientErrorException.TooManyRequests e) {
            // 일일 할당량(500회) 또는 분당 한도(4회) 초과 → 통과 (사용자 차단 안 함)
            healthCheckRecorder.record(HEALTH_KEY, HealthStatus.DEGRADED, "VirusTotal 할당량 초과: " + e.getMessage());
            log.error("VirusTotal 할당량 초과 — 파일 검사를 건너뜁니다. ({})", e.getMessage());
            return true;
        } catch (Exception e) {
            // 기타 API 호출 실패 → 통과 처리 (서비스 중단 방지)
            healthCheckRecorder.record(HEALTH_KEY, HealthStatus.DOWN, "VirusTotal API 오류: " + e.getMessage());
            log.error("VirusTotal API error: {}", e.getMessage());
            return true;
        }
    }

    private String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
