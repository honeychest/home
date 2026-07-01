// [AGENT] 헬스 전이 이벤트 → 텔레그램 능동 알림. DOWN 시작(🔴)·복구(🟢)만 발송.
// 정책(결정): DOWN(치명)만 알림, DEGRADED는 보드 표시만. 복구 알림 O.
// ext-telegram-send 는 제외(텔레그램 장애를 텔레그램으로 알릴 수 없고 자기루프 방지).
// recorder는 이벤트만 발행하므로 여기서 TelegramProvider를 호출해도 순환참조가 없다.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.telegram.TelegramProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HealthAlertNotifier {

    private final TelegramProvider telegramProvider;

    // @Async: 텔레그램 HTTP 호출을 발행 스레드(평가기/요청 스레드)에서 분리 → markFail/markOk 지연 방지.
    // (@EnableAsync 는 SpringbootApplication 에 활성화됨. 단위 테스트의 직접 호출은 프록시 미경유라 동기 실행)
    @Async
    @EventListener
    public void onTransition(HealthCheckTransitionEvent event) {
        // 텔레그램 자체 상태는 텔레그램으로 알릴 수 없음 + 자기루프 방지
        if (HealthCheckCatalog.EXT_TELEGRAM_SEND.key().equals(event.checkKey())) {
            return;
        }
        // DOWN 관련 전이만 알림(실패 시작 DOWN, 또는 DOWN 이었던 장애의 복구)
        if (event.status() != HealthStatus.DOWN) {
            return;
        }
        String message = event.recovery()
                ? "🟢 [%s] 복구".formatted(event.checkKey())
                : "🔴 [%s] DOWN".formatted(event.checkKey())
                        + (event.cause() != null && !event.cause().isBlank() ? "\n원인: " + event.cause() : "");
        telegramProvider.sendMessage(message);
    }
}
