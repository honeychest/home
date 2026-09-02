// [AGENT] SSE 브로드캐스트 서비스 — Signal Dashboard 실시간 전송 (aggtrade, forceOrder, oi), 30초 ping
// 연관파일: SignalController.java(subscribe), AggTradeStreamService.java, ForceOrderStreamService.java, OpenInterestPollingService.java
// 주요메서드: subscribe() → SseEmitter 등록, broadcastAggTrade/ForceOrder/OiUpdate → 이벤트 전송, sendPing() → 30초 주기
package com.chs.springboot.domain.binance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class SignalSseService {

    private static final long SSE_TIMEOUT_MS = 0L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // broadcastX 호출부(AggTradeStreamService.dispatch 등)가 WebSocket 수신 스레드에서 직접 부른다.
    // emitter.send()를 그 스레드에서 동기 실행하면 느린 브라우저 클라이언트 하나가 소켓 읽기를 막아
    // 거래량 급증 시 stale 오탐 → 재연결을 유발한다(실측). 전송을 별도 스레드로 떼어 그 경로를 끊는다.
    // (pattern-async-sse-dispatch — springboot/AGENTS.md 패턴 카탈로그 참고)
    private final AsyncSseDispatcher dispatcher = new AsyncSseDispatcher("signal-sse-broadcast");

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.add(emitter);
        log.info("[SignalSse] 연결 등록 total={}", emitters.size());

        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.info("[SignalSse] 연결 timeout 종료 (nginx proxy_read_timeout 의심) total={}", emitters.size());
        });
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.info("[SignalSse] 연결 정상 종료 (클라이언트 disconnect) total={}", emitters.size());
        });
        emitter.onError(e -> {
            emitters.remove(emitter);
            log.warn("[SignalSse] 연결 에러 종료: {} total={}", e.getMessage(), emitters.size());
        });

        try {
            emitter.send(SseEmitter.event().name("connect").data("ok"));
        } catch (IOException e) {
            emitters.remove(emitter);
            log.warn("[SignalSse] 초기 connect 이벤트 전송 실패: {}", e.getMessage());
        }

        return emitter;
    }

    public void broadcastAggTrade(Object dto) {
        broadcast("aggtrade", dto);
    }

    public void broadcastForceOrder(Object dto) {
        broadcast("forceOrder", dto);
    }

    public void broadcastOiUpdate(Object dto) {
        broadcast("oi", dto);
    }

    public void broadcastAnalysisMatch(Object dto) {
        broadcast("analysis_match", dto);
    }

    private void broadcast(String eventName, Object dto) {
        if (emitters.isEmpty()) return;
        dispatcher.dispatch(() -> doBroadcast(eventName, dto));
    }

    private void doBroadcast(String eventName, Object dto) {
        String json;
        try {
            json = objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            log.error("[SignalSse] DTO 직렬화 실패: {}", e.getMessage());
            return;
        }

        List<SseEmitter> dead = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            log.warn("[SignalSse] broadcast dead emitter {}개 제거, event={}", dead.size(), eventName);
            emitters.removeAll(dead);
        }
    }

    @PreDestroy
    public void shutdown() {
        dispatcher.shutdown();
    }

    @Scheduled(fixedDelay = 30_000)
    public void sendPing() {
        if (emitters.isEmpty()) return;

        List<SseEmitter> dead = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            log.warn("[SignalSse] ping dead emitter {}개 제거 (nginx keepalive 문제 의심)", dead.size());
            emitters.removeAll(dead);
        }
    }
}
