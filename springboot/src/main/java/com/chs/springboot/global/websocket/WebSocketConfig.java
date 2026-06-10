// [AGENT] 역할: WS 엔드포인트 등록 설정 | 연관파일: BinancePriceWebSocketHandler.java(/ws/binance-price), UpbitPriceWebSocketHandler.java(/ws/upbit-price), CandleWebSocketHandler.java(/ws/candle/1m·5m·15m) | 주의: Vite 프록시에서 각 경로 구체적으로 설정 필요 (/ws만 쓰면 HMR 충돌)
// Purpose: WebSocket 엔드포인트 등록 — Binance/Upbit 가격 중계 경로를 핸들러에 연결

/**
 * ─────────────────────────────────────────────────────────────────
 *  이 클래스의 역할
 * ─────────────────────────────────────────────────────────────────
 *  "어떤 URL로 접속하면 어떤 핸들러가 처리할지" 를 Spring에 등록하는 설정 클래스.
 *
 *  jQuery 비유:
 *    서버에서 $.ajax url 라우팅을 설정하는 것처럼,
 *    WebSocket 연결 요청이 특정 URL로 오면 특정 핸들러가 받도록 연결.
 *
 *  등록 내용:
 *    URL: /ws/binance-price
 *    핸들러: BinancePriceWebSocketHandler
 *
 *    URL: /ws/upbit-price
 *    핸들러: UpbitPriceWebSocketHandler
 *
 *    CORS: 모든 출처(*) 허용
 *
 *  프론트엔드에서의 연결:
 *    new WebSocket('ws://localhost:5173/ws/binance-price')
 *    → Vite 프록시 → ws://localhost:8080/ws/binance-price
 *    → WebSocketConfig가 BinancePriceWebSocketHandler에 연결
 * ─────────────────────────────────────────────────────────────────
 */
package com.chs.springboot.global.websocket;

import com.chs.springboot.domain.binance.websocket.BinancePriceWebSocketHandler;
import com.chs.springboot.domain.binance.websocket.CandleWebSocketHandler;
import com.chs.springboot.domain.upbit.websocket.UpbitPriceWebSocketHandler;
import com.chs.springboot.global.monitor.handler.MonitorWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * @Configuration: Spring 설정 클래스.
 *
 * @EnableWebSocket:
 *   Spring의 WebSocket 지원을 활성화.
 *   이 어노테이션이 있어야 WebSocketConfigurer 인터페이스가 인식됨.
 *   build.gradle에 spring-boot-starter-websocket 의존성이 있어야 동작.
 *
 * @RequiredArgsConstructor (Lombok):
 *   final 필드들을 받는 생성자를 자동 생성.
 *   Spring이 BinancePriceWebSocketHandler/UpbitPriceWebSocketHandler 빈을 자동 주입.
 *
 * implements WebSocketConfigurer:
 *   Spring WebSocket 설정 인터페이스.
 *   registerWebSocketHandlers() 메서드를 구현해야 함.
 */
@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * binanceHandler: Binance 가격 중계 WebSocket 핸들러.
     * final = 불변, Spring이 생성자를 통해 주입.
     */
    private final BinancePriceWebSocketHandler binanceHandler;

    /**
     * upbitHandler: Upbit 가격 중계 WebSocket 핸들러.
     * final = 불변, Spring이 생성자를 통해 주입.
     */
    private final UpbitPriceWebSocketHandler upbitHandler;

    private final CandleWebSocketHandler candleHandler;

    private final MonitorWebSocketHandler monitorWebSocketHandler;

    /**
     * registerWebSocketHandlers: WebSocket 핸들러와 URL 경로를 등록.
     *
     * @param registry WebSocket 핸들러 등록 레지스트리
     *
     * registry.addHandler(handler, path):
     *   handler = 연결 요청을 처리할 핸들러 객체 (BinancePriceWebSocketHandler)
     *   path    = 이 핸들러가 담당할 URL 경로 ("/ws/binance-price")
     *
     *   브라우저에서 new WebSocket('ws://서버/ws/binance-price') 로 연결하면
     *   이 핸들러의 afterConnectionEstablished()가 호출됨.
     *
     * .setAllowedOrigins("*"):
     *   CORS(Cross-Origin Resource Sharing) 설정.
     *   "*" = 모든 도메인에서의 WebSocket 연결 허용.
     *
     *   왜 필요한가?
     *     브라우저는 보안상 다른 도메인으로의 WebSocket 연결을 제한.
     *     개발 환경: 프론트(localhost:5173) → 백엔드(localhost:8080) 가 다른 포트이므로 필요.
     *     Vite 프록시를 통해 같은 포트처럼 보이지만, 백엔드 직접 접속 시를 위해 설정.
     *
     *   운영 환경에서는 "*" 대신 특정 도메인으로 제한 권장:
     *     .setAllowedOrigins("https://mysite.com")
     *
     *   jQuery 비유:
     *     PHP header("Access-Control-Allow-Origin: *"); 와 동일한 개념.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(binanceHandler, "/ws/binance-price")
                .setAllowedOrigins("*");

        registry.addHandler(upbitHandler, "/ws/upbit-price")
                .setAllowedOrigins("*");

        registry.addHandler(candleHandler, "/ws/candle/5m", "/ws/candle/1m", "/ws/candle/15m")
                .setAllowedOrigins("*");

        registry.addHandler(monitorWebSocketHandler, "/ws/monitor")
                .setAllowedOrigins("*");

        log.info("[WebSocketConfig] 등록 완료: /ws/binance-price, /ws/upbit-price, /ws/candle/5m, /ws/candle/1m, /ws/candle/15m, /ws/monitor =====");
    }
}
