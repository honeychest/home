// [AGENT] 역할: 1분봉·5분봉·15분봉 WebSocket 세션 관리 및 브로드캐스트 | 연관파일: CandleStreamService.java(broadcastCandle 호출자), WebSocketConfig.java
// 엔드포인트: /ws/candle/1m?symbol=, /ws/candle/5m?symbol=, /ws/candle/15m?symbol= | 심볼+인터벌별 세션 구분, ConcurrentWebSocketSessionDecorator 동시 전송 직렬화
package com.chs.springboot.domain.binance.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CandleWebSocketHandler extends TextWebSocketHandler {

    // sessionId → (symbol, interval, safeSession)
    private final ConcurrentHashMap<String, String>           sessionSymbols   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>           sessionIntervals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocketSession> sessions         = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5000, 64 * 1024);
        String symbol   = parseSymbol(session);
        String interval = parseInterval(session);
        sessions.put(session.getId(), safe);
        sessionSymbols.put(session.getId(), symbol != null ? symbol : "");
        sessionIntervals.put(session.getId(), interval);
        log.debug("[CandleWS] 연결: {} symbol={} interval={} (총 {}개)", session.getId(), symbol, interval, sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        sessionSymbols.remove(session.getId());
        sessionIntervals.remove(session.getId());
        log.debug("[CandleWS] 해제: {} (총 {}개)", session.getId(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[CandleWS] 전송 오류 ({}): {}", session.getId(), exception.getMessage());
        sessions.remove(session.getId());
        sessionSymbols.remove(session.getId());
        sessionIntervals.remove(session.getId());
        try { session.close(); } catch (Exception e) { /* 이미 닫힌 경우 무시 */ }
    }

    public void broadcastCandle(String symbol, String interval, String json) {
        sessions.forEach((id, session) -> {
            if (!symbol.equals(sessionSymbols.get(id))) return;
            if (!interval.equals(sessionIntervals.get(id))) return;
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                } else {
                    sessions.remove(id);
                    sessionSymbols.remove(id);
                }
            } catch (Exception e) {
                log.error("[CandleWS] 전송 실패 ({}): {}", id, e.getMessage());
                sessions.remove(id);
                sessionSymbols.remove(id);
            }
        });
    }

    public Set<String> getActiveSymbols(String interval) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        sessionSymbols.forEach((id, symbol) -> {
            if (interval.equals(sessionIntervals.get(id))) result.add(symbol);
        });
        return result;
    }

    public int getSessionCount() {
        return sessions.size();
    }

    private String parseInterval(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : null;
        if (path != null && path.endsWith("/1m")) return "1m";
        if (path != null && path.endsWith("/15m")) return "15m";
        return "5m";
    }

    private String parseSymbol(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) return null;
        for (String param : query.split("&")) {
            if (param.startsWith("symbol=")) return param.substring("symbol=".length());
        }
        return null;
    }
}
