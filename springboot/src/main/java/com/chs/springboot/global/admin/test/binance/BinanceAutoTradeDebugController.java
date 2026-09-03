// [AGENT] Admin 테스트 — 자동매매 PoC 실시간 캔들 버퍼 상태 디버그 API
// (/api/admin/test/binance/debug/snapshot). ADMIN_ACCESS 권한 필요(SecurityConfig /api/admin/** 규칙).
// 서비스 조회만 하고 규칙은 LiveMarketDataService가 그대로 소유(README.md "디버그 API는
// 기존 서비스 조회·요약만" 원칙).
// 분석 3개 엔드포인트는 이 인스턴스가 리더가 아니면 Redis "server:leader"가 가리키는 실제 리더로
// 1회 내부 전달한다(BinanceAnalysisLeaderForwarder) — 전달이 안 되거나 실패해도 로컬 서비스를
// 다시 호출하지 않는다(코덱스 검수: 로컬 재실행이 리더 쪽 LLM 중복 호출로 이어질 수 있어서).
package com.chs.springboot.global.admin.test.binance;

import com.chs.springboot.domain.binance.model.BinanceAnalysisAskRequest;
import com.chs.springboot.domain.binance.model.BinanceAnalysisResponse;
import com.chs.springboot.domain.binance.model.BinanceAnalysisStatus;
import com.chs.springboot.domain.binance.service.BinanceAutoTradeAnalysisService;
import com.chs.springboot.domain.binance.service.LiveMarketDataService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

@RestController
@RequestMapping("/api/admin/test/binance/debug")
public class BinanceAutoTradeDebugController {

    private static final String ANALYSIS_PATH = "/api/admin/test/binance/debug/analysis";
    private static final String REFRESH_PATH = ANALYSIS_PATH + "/refresh";
    private static final String ASK_PATH = ANALYSIS_PATH + "/ask";
    private static final String FORWARD_UNAVAILABLE_MESSAGE = "리더 노드로 요청을 전달하지 못했습니다 — 잠시 후 다시 시도하세요";

    private final LiveMarketDataService liveMarketDataService;
    private final BinanceAutoTradeAnalysisService analysisService;
    private final BinanceAnalysisLeaderForwarder forwarder;

    public BinanceAutoTradeDebugController(LiveMarketDataService liveMarketDataService,
                                           BinanceAutoTradeAnalysisService analysisService,
                                           BinanceAnalysisLeaderForwarder forwarder) {
        this.liveMarketDataService = liveMarketDataService;
        this.analysisService = analysisService;
        this.forwarder = forwarder;
    }

    @GetMapping("/snapshot")
    public AutoTradeSnapshotResponse getSnapshot() {
        return AutoTradeSnapshotResponse.from(liveMarketDataService.buildSnapshot());
    }

    @GetMapping("/analysis")
    public BinanceAnalysisResponse getAnalysis(HttpServletRequest request, HttpServletResponse response) {
        return withLeaderForward(request, response, ANALYSIS_PATH, HttpMethod.GET, null,
                analysisService::getLatestAnalysis);
    }

    @PostMapping("/analysis/refresh")
    public BinanceAnalysisResponse refreshAnalysis(HttpServletRequest request, HttpServletResponse response) {
        return withLeaderForward(request, response, REFRESH_PATH, HttpMethod.POST, null,
                analysisService::refreshAnalysis);
    }

    @PostMapping("/analysis/ask")
    public BinanceAnalysisResponse askAnalysis(@RequestBody(required = false) BinanceAnalysisAskRequest askRequest,
                                               HttpServletRequest request, HttpServletResponse response) {
        return withLeaderForward(request, response, ASK_PATH, HttpMethod.POST, askRequest, () ->
                askRequest == null
                        ? analysisService.ask(null, null)
                        : analysisService.ask(askRequest.question(), askRequest.recentTurns()));
    }

    /**
     * 리더면 로컬 서비스를 그대로 호출한다. 리더가 아니면 실제 리더로 1회 전달을 시도하고,
     * 전달이 안 되거나(NotEligible) 실패하면(Failed) 로컬 서비스는 다시 호출하지 않고 안전한
     * NOT_LEADER 응답을 즉시 돌려준다 — 로컬 재실행 여부에 따라 동작이 갈리지 않게 한다.
     */
    private BinanceAnalysisResponse withLeaderForward(HttpServletRequest request, HttpServletResponse response,
                                                       String path, HttpMethod method, Object body,
                                                       Supplier<BinanceAnalysisResponse> localCall) {
        if (liveMarketDataService.isLeader()) {
            return localCall.get();
        }
        AnalysisForwardOutcome outcome = forwarder.forward(request, path, method, body);
        if (outcome instanceof AnalysisForwardOutcome.Forwarded forwarded) {
            if (forwarded.setCookieHeader() != null) {
                response.addHeader(HttpHeaders.SET_COOKIE, forwarded.setCookieHeader());
            }
            return forwarded.response();
        }
        return new BinanceAnalysisResponse(BinanceAnalysisStatus.NOT_LEADER, null, null, null, null, null, null,
                FORWARD_UNAVAILABLE_MESSAGE);
    }
}
