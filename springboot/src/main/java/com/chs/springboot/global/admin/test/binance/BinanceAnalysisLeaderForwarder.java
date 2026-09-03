// [AGENT] 비리더 인스턴스가 받은 binance 분석 요청을 Redis "server:leader"가 가리키는 실제
// 리더 인스턴스로 1회 내부 전달한다. docker-compose 상 컨테이너 간 주소(app1:8080/app2:8080)를
// 쓰며, nginx가 보는 호스트 포트(127.0.0.1:8080/8081)와는 다르다.
// 타임아웃은 BinanceAutoTradeAnalysisService.runLlmCall()과 같은 방식(전용 executor + Future
// bounded get)으로 건다 — RestClient의 requestFactory를 여기서 바꾸면 MockRestServiceServer
// 바인딩이 깨져 테스트가 안 된다.
package com.chs.springboot.global.admin.test.binance;

import com.chs.springboot.domain.binance.model.BinanceAnalysisResponse;
import com.chs.springboot.global.redis.LeaderElectionService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class BinanceAnalysisLeaderForwarder {

    /**
     * 이미 한 번 전달된 요청임을 표시하는 헤더. 값은 인스턴스 간 공유 비밀값과 일치해야 인정한다 —
     * 헤더 이름만으로는 외부 요청자가 그대로 흉내 내 전달을 우회시킬 수 있어서다(코덱스 검수 지적).
     */
    static final String FORWARDED_HEADER = "X-Binance-Analysis-Forwarded";

    private final RestClient restClient;
    private final LeaderElectionService leaderElectionService;
    private final String serverName;
    private final Map<String, String> peerBaseUrls;
    private final String forwardSecret;
    private final long forwardTimeoutMs;
    private final ExecutorService forwardExecutor;

    public BinanceAnalysisLeaderForwarder(
            RestClient.Builder restClientBuilder,
            LeaderElectionService leaderElectionService,
            @Value("${SERVER_NAME:LOCAL}") String serverName,
            @Value("#{${binance.analysis.peer-base-url:{}}}") Map<String, String> peerBaseUrls,
            @Value("${binance.analysis.internal-forward-secret:}") String forwardSecret,
            @Value("${binance.analysis.forward-timeout-ms:50000}") long forwardTimeoutMs) {
        this.restClient = restClientBuilder.clone().build();
        this.leaderElectionService = leaderElectionService;
        this.serverName = serverName;
        this.peerBaseUrls = peerBaseUrls;
        this.forwardSecret = forwardSecret;
        this.forwardTimeoutMs = forwardTimeoutMs;
        // 관리자 1인이 수동으로 누르는 디버그 API라 동시 호출이 많지 않다 — 512MB 힙 제약 아래
        // 스레드가 무한정 늘지 않도록 BinanceAutoTradeAnalysisService.llmExecutor와 같은 크기로 고정.
        this.forwardExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "binance-analysis-forward");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * @param incoming 원 요청 — 쿠키(JWT accessToken/refreshToken)와 전달 표시 헤더 확인용
     * @param path     리더에 그대로 전달할 경로(이 세 엔드포인트엔 쿼리스트링이 없다)
     * @param method   GET 또는 POST
     * @param body     POST일 때만 사용. 컨트롤러가 이미 역직렬화한 요청 객체를 그대로 넘긴다 —
     *                 {@code @RequestBody}로 이미 소비된 원본 스트림을 여기서 다시 읽을 수 없다.
     */
    public AnalysisForwardOutcome forward(HttpServletRequest incoming, String path, HttpMethod method, Object body) {
        if (forwardSecret == null || forwardSecret.isBlank()) {
            // 공유 비밀값이 없으면 전달 표시를 신뢰할 수 없다 — 전달 자체를 하지 않는다(무한 루프 방지).
            return new AnalysisForwardOutcome.NotEligible();
        }
        if (isAlreadyForwarded(incoming)) {
            return new AnalysisForwardOutcome.NotEligible();
        }
        String leaderName = leaderElectionService.getCurrentLeaderName();
        if (leaderName == null || leaderName.isBlank() || leaderName.equals(serverName)) {
            return new AnalysisForwardOutcome.NotEligible();
        }
        String baseUrl = peerBaseUrls.get(leaderName);
        if (baseUrl == null || baseUrl.isBlank()) {
            return new AnalysisForwardOutcome.NotEligible();
        }

        Future<ResponseEntity<BinanceAnalysisResponse>> future = forwardExecutor.submit(() -> {
            RestClient.RequestBodySpec spec = restClient.method(method)
                    .uri(baseUrl + path)
                    .headers(headers -> {
                        String cookie = incoming.getHeader(HttpHeaders.COOKIE);
                        if (cookie != null) {
                            headers.set(HttpHeaders.COOKIE, cookie);
                        }
                        headers.set(FORWARDED_HEADER, forwardSecret);
                    });
            if (body != null) {
                spec = spec.body(body);
            }
            return spec.retrieve().toEntity(BinanceAnalysisResponse.class);
        });
        try {
            ResponseEntity<BinanceAnalysisResponse> response = future.get(forwardTimeoutMs, TimeUnit.MILLISECONDS);
            String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
            return new AnalysisForwardOutcome.Forwarded(response.getBody(), setCookie);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.warn("[{}] 리더({})로 분석 요청 전달이 중단됨 path={}", serverName, leaderName, path);
            return new AnalysisForwardOutcome.Failed();
        } catch (Exception e) {
            future.cancel(true);
            log.warn("[{}] 리더({})로 분석 요청 전달 실패 path={} error={}", serverName, leaderName, path, e.getMessage());
            return new AnalysisForwardOutcome.Failed();
        }
    }

    private boolean isAlreadyForwarded(HttpServletRequest incoming) {
        String header = incoming.getHeader(FORWARDED_HEADER);
        return header != null && header.equals(forwardSecret);
    }

    @PreDestroy
    public void shutdown() {
        forwardExecutor.shutdownNow();
    }
}
