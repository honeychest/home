// [AGENT] 텔레그램 폴링 서비스 — telegram.mode=polling 일 때만 활성화
// @PostConstruct: getUpdates?offset=-1 로 최신 update_id 읽어 offset 초기화
// @Scheduled(fixedDelay=30000): 30초마다 getUpdates, offset 전진으로 중복 방지 (인메모리 AtomicLong)
// 연관: TelegramUpdateProcessor.java (update 처리), TelegramWebhookController.java (webhook 모드 대안)
package com.chs.springboot.global.telegram;

import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import com.chs.springboot.global.redis.LeaderElectionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Telegram getUpdates API 폴링 서비스 (telegram.mode=polling 일 때만 활성화).
 *
 * SSL 설정 후 telegram.mode=webhook 으로 변경하면 이 빈이 비활성화되고
 * TelegramWebhookController 가 대신 동작한다. 코드 변경 없이 설정만으로 전환 가능.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "telegram.mode", havingValue = "polling", matchIfMissing = true)
@RequiredArgsConstructor
public class TelegramPollingService {

    @Value("${telegram_token}")
    private String token;

    private final TelegramUpdateProcessor processor;
    private final RestTemplate restTemplate = new RestTemplate();

    private final LeaderElectionService leaderElection;
    private final HealthHeartbeat healthHeartbeat;

    private static final String HEALTH_KEY = HealthCheckCatalog.SCHED_TELEGRAM_POLL.key();

    /** 다음 조회 시작 offset (중복 처리 방지) */
    private final AtomicLong offset = new AtomicLong(0);

    /**
     * 서버 시작 시 가장 최근 update_id 를 읽어 offset 초기화.
     * 이렇게 하면 서버 재시작 후 이미 처리된 메시지를 재처리하지 않는다.
     */
    @PostConstruct
    public void init() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(
                    "https://api.telegram.org/bot" + token + "/getUpdates?limit=1&offset=-1",
                    Map.class
            );
            if (response == null) return;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("result");
            if (results != null && !results.isEmpty()) {
                long lastId = ((Number) results.get(0).get("update_id")).longValue();
                offset.set(lastId + 1);
                log.info("Telegram polling initialized, offset={}", offset.get());
            }
        } catch (Exception e) {
            log.warn("Telegram polling offset 초기화 실패 (첫 실행 시 정상): {}", e.getMessage());
        }
    }

    /** 30초마다 새 메시지 조회 */
    @Scheduled(fixedDelay = 30000)
    public void poll() {
        if (!leaderElection.isLeader()) {
            return;  // 리더 아니면 스킵
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(
                    "https://api.telegram.org/bot" + token + "/getUpdates?offset=" + offset.get() + "&limit=100",
                    Map.class
            );
            healthHeartbeat.beat(HEALTH_KEY);
            if (response == null) return;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> updates = (List<Map<String, Object>>) response.get("result");
            if (updates == null || updates.isEmpty()) return;

            for (Map<String, Object> update : updates) {
                processor.process(update);
                // 처리 여부와 무관하게 offset 전진 (재처리 방지)
                long updateId = ((Number) update.get("update_id")).longValue();
                offset.set(updateId + 1);
            }

        } catch (Exception e) {
            healthHeartbeat.fail(HEALTH_KEY, e.getMessage());
            log.error("Telegram polling error: {}", e.getMessage());
        }
    }
}
