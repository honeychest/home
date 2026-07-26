// [AGENT] recipe 전용 텔레그램 알림 — global/telegram 을 참조하지 않고 최소 복사 소유
// (분리 규율 7 — ArchUnit 이 global 참조를 차단. 앱 분리 시 함께 들어냄).
// 외부 HTTP 는 RestClient.Builder 주입 (PLAYBOOK 관례 4).
package com.chs.springboot.domain.recipe.registration;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GikkaTelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(GikkaTelegramNotifier.class);

    private final RestClient rest;
    private final GikkaNotifyProperties properties;

    public GikkaTelegramNotifier(RestClient.Builder builder, GikkaNotifyProperties properties) {
        this.rest = builder.baseUrl("https://api.telegram.org").build();
        this.properties = properties;
    }

    /** 토큰이 비면 무시(로컬 기본). 전송 실패도 삼킨다 — 알림이 분석 파이프라인을 막으면 안 됨. */
    public void notify(String message) {
        if (properties.getTelegramToken().isBlank()) {
            return;
        }
        try {
            rest.post()
                    .uri("/bot{token}/sendMessage", properties.getTelegramToken())
                    .body(Map.of("chat_id", properties.getTelegramChatId(), "text", message))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("[gikka] 텔레그램 알림 전송 실패: {}", e.getMessage());
        }
    }
}
