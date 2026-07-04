// [AGENT] 텔레그램 전송 컴포넌트 — sendMessage(텍스트), sendPhoto(이미지+캡션)
// 도배방지: 동일 메시지 1분 내 재전송 차단 (인메모리 ConcurrentHashMap 캐시)
// 연관: ContactController.java (문의 수신 시 sendPhoto/sendMessage 호출)
package com.chs.springboot.global.telegram;

import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthCheckRecorder;
import com.chs.springboot.global.monitor.health.HealthStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TelegramProvider {

    @Value("${telegram_token}")
    private String token;

    @Value("${telegram_chatid}")
    private String chatId;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final String HEALTH_KEY = HealthCheckCatalog.EXT_TELEGRAM_SEND.key();
    private final HealthCheckRecorder healthCheckRecorder;

    public TelegramProvider(HealthCheckRecorder healthCheckRecorder) {
        this.healthCheckRecorder = healthCheckRecorder;
    }

    // 도배 방지를 위한 메모리 캐시 (메시지 내용, 마지막 전송 시간)
    private final Map<String, Long> messageCache = new ConcurrentHashMap<>();
    private static final long MIN_INTERVAL = 60000; // 동일 메시지 제한 (1분)

    public void sendMessage(String message) {
        long currentTime = System.currentTimeMillis();

        // 도배 방지 로직: 동일한 메시지가 1분 내에 오면 무시
        if (messageCache.containsKey(message) && (currentTime - messageCache.get(message) < MIN_INTERVAL)) {
            log.warn("Telegram spam prevented: {}", message);
            return;
        }

        try {
            String url = "https://api.telegram.org/bot" + token + "/sendMessage";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = Map.of("chat_id", chatId, "text", message);
            HttpEntity<Map<String, String>> httpRequest = new HttpEntity<>(body, headers);

            restTemplate.postForEntity(url, httpRequest, String.class);
            messageCache.put(message, currentTime); // 전송 성공 시 캐시 갱신
            healthCheckRecorder.markOk(HEALTH_KEY);
            log.info("Telegram message sent successfully");
        } catch (Exception e) {
            healthCheckRecorder.record(HEALTH_KEY, HealthStatus.DOWN, "송신 실패: " + e.getMessage());
            log.error("Failed to send telegram message: {}", e.getMessage());
        }

        // 오래된 캐시 삭제 (메모리 관리용 - 필요 시 스케줄러로 확장 가능)
        if(messageCache.size() > 100) messageCache.clear();
    }

    /**
     * 이미지를 캡션과 함께 텔레그램으로 전송한다.
     * sendMessage와 동일한 도배 방지 로직 적용.
     *
     * @param caption   이미지 하단에 표시할 텍스트 (최대 1024자)
     * @param imageBytes 전송할 이미지 바이트 배열
     * @param filename  파일명 (확장자 포함)
     */
    public void sendPhoto(String caption, byte[] imageBytes, String filename) {
        long currentTime = System.currentTimeMillis();

        if (messageCache.containsKey(caption) && (currentTime - messageCache.get(caption) < MIN_INTERVAL)) {
            log.warn("Telegram spam prevented (photo): {}", caption);
            return;
        }

        try {
            String url = "https://api.telegram.org/bot" + token + "/sendPhoto";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return filename != null ? filename : "image.jpg";
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("chat_id", chatId);
            body.add("caption", caption);
            body.add("photo", imageResource);

            restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            messageCache.put(caption, currentTime);
            healthCheckRecorder.markOk(HEALTH_KEY);
            log.info("Telegram photo sent successfully");
        } catch (Exception e) {
            healthCheckRecorder.record(HEALTH_KEY, HealthStatus.DOWN, "사진 송신 실패: " + e.getMessage());
            log.error("Failed to send telegram photo: {}", e.getMessage());
        }

        if (messageCache.size() > 100) messageCache.clear();
    }
}