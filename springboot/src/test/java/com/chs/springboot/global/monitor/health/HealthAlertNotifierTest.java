package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.telegram.TelegramProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HealthAlertNotifierTest {

    private final TelegramProvider telegram = mock(TelegramProvider.class);
    private final HealthAlertNotifier notifier = new HealthAlertNotifier(telegram);

    {
        // 운영 기본값(발송 on) 재현 — @Value 미주입 유닛 테스트라 명시 세팅
        ReflectionTestUtils.setField(notifier, "alertEnabled", true);
    }

    @Test
    void disabled_doesNotSend() {
        ReflectionTestUtils.setField(notifier, "alertEnabled", false);

        notifier.onTransition(new HealthCheckTransitionEvent("infra-mysql", HealthStatus.DOWN, "연결 실패", false));

        verify(telegram, never()).sendMessage(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void downFailure_sendsAlert() {
        notifier.onTransition(new HealthCheckTransitionEvent("infra-mysql", HealthStatus.DOWN, "연결 실패", false));

        verify(telegram).sendMessage(contains("infra-mysql"));
    }

    @Test
    void degradedFailure_doesNotSend() {
        notifier.onTransition(new HealthCheckTransitionEvent("res-cpu", HealthStatus.DEGRADED, "70%", false));

        verify(telegram, never()).sendMessage(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void downRecovery_sendsGreenAlert() {
        notifier.onTransition(new HealthCheckTransitionEvent("infra-mysql", HealthStatus.DOWN, null, true));

        verify(telegram).sendMessage(contains("복구"));
    }

    @Test
    void telegramSelfKey_doesNotSend() {
        notifier.onTransition(new HealthCheckTransitionEvent(
                HealthCheckCatalog.EXT_TELEGRAM_SEND.key(), HealthStatus.DOWN, "송신 실패", false));

        verify(telegram, never()).sendMessage(org.mockito.ArgumentMatchers.anyString());
    }
}
