// [AGENT] SSE 브로드캐스트 서비스 — 체결 실시간 전송, 30초 ping
// 연관파일: BinanceTradeService.java(broadcast 호출), BinanceTradeController.java(subscribe)
// 주요메서드: subscribe() → SseEmitter 등록, broadcast(dto) → 전체 전송, sendPing() → 30초 주기
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceTradeDto;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Service
public class BinanceTradeSseService {

    private static final Logger log = LoggerFactory.getLogger(BinanceTradeSseService.class);

    // 타임아웃 0 = 무제한 (Nginx proxy_read_timeout이 연결 관리)
    private static final long SSE_TIMEOUT_MS = 0L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // broadcast() 호출부(BinanceTradeService.parseAndSave, DB save 직후)가
    // WebSocket 수신 스레드(onText) 안에서 호출된다. 동기 emitter.send()가 그 스레드를
    // 막지 않도록 별도 스레드로 위임한다. (pattern-async-sse-dispatch — springboot/AGENTS.md 참고)
    private final AsyncSseDispatcher dispatcher = new AsyncSseDispatcher("trade-sse-broadcast");

    /** 클라이언트 SSE 구독 등록 */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.add(emitter);

        Runnable cleanup = () -> emitters.remove(emitter);
        emitter.onTimeout(cleanup);
        emitter.onCompletion(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("connect").data("ok"));
        } catch (IOException e) {
            cleanup.run();
        }

        log.debug("[TradeSse] 구독 등록, 현재 emitter 수: {}", emitters.size());
        return emitter;
    }

    /** DB 저장 성공 후 호출 — 전체 구독자에게 체결 이벤트 전송 */
    public void broadcast(BinanceTradeDto dto) {
        if (emitters.isEmpty()) return;
        dispatcher.dispatch(() -> doBroadcast(dto));
    }

    private void doBroadcast(BinanceTradeDto dto) {
        log.debug("[TradeSse] broadcast id={} emitters={}", dto.id(), emitters.size());
        String json;
        try {
            json = objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            log.error("[TradeSse] DTO 직렬화 실패: {}", e.getMessage());
            return;
        }

        List<SseEmitter> dead = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("trade").data(json));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    /** Nginx 연결 유지용 30초 ping (comment 형식 — 프론트 별도 처리 불필요) */
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
        emitters.removeAll(dead);
    }

    @PreDestroy
    public void shutdown() {
        dispatcher.shutdown();
    }
}
