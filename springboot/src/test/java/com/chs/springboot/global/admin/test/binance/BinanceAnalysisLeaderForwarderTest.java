package com.chs.springboot.global.admin.test.binance;

import com.chs.springboot.domain.binance.model.BinanceAnalysisAskRequest;
import com.chs.springboot.global.redis.LeaderElectionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BinanceAnalysisLeaderForwarderTest {

    private static final String ANALYSIS_PATH = "/api/admin/test/binance/debug/analysis";
    private static final String SECRET = "test-secret";

    private MockRestServiceServer server;
    private LeaderElectionService leaderElectionService;
    private HttpServletRequest incoming;

    private BinanceAnalysisLeaderForwarder newForwarder(String selfServerName, Map<String, String> peerBaseUrls,
                                                         String secret) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        leaderElectionService = mock(LeaderElectionService.class);
        return new BinanceAnalysisLeaderForwarder(builder, leaderElectionService, selfServerName,
                peerBaseUrls, secret, 5_000L);
    }

    @BeforeEach
    void setUp() {
        incoming = mock(HttpServletRequest.class);
    }

    @Test
    void docker1이_리더면_docker1주소로_전달하고_쿠키를_그대로_넘긴다() {
        BinanceAnalysisLeaderForwarder forwarder = newForwarder("DOCKER2",
                Map.of("DOCKER1", "http://app1:8080", "DOCKER2", "http://app2:8080"), SECRET);
        when(leaderElectionService.getCurrentLeaderName()).thenReturn("DOCKER1");
        when(incoming.getHeader("Cookie")).thenReturn("accessToken=abc; refreshToken=def");
        when(incoming.getHeader(BinanceAnalysisLeaderForwarder.FORWARDED_HEADER)).thenReturn(null);

        server.expect(requestTo("http://app1:8080" + ANALYSIS_PATH))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Cookie", "accessToken=abc; refreshToken=def"))
                .andExpect(header(BinanceAnalysisLeaderForwarder.FORWARDED_HEADER, SECRET))
                .andRespond(withSuccess(
                        "{\"status\":\"READY\",\"answer\":\"ok\"}", APPLICATION_JSON));

        AnalysisForwardOutcome outcome = forwarder.forward(incoming, ANALYSIS_PATH, HttpMethod.GET, null);

        assertThat(outcome).isInstanceOf(AnalysisForwardOutcome.Forwarded.class);
        assertThat(((AnalysisForwardOutcome.Forwarded) outcome).response().answer()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void ask요청은_역직렬화된_바디를_그대로_JSON으로_전달한다() {
        BinanceAnalysisLeaderForwarder forwarder = newForwarder("DOCKER2",
                Map.of("DOCKER1", "http://app1:8080"), SECRET);
        when(leaderElectionService.getCurrentLeaderName()).thenReturn("DOCKER1");
        when(incoming.getHeader("Cookie")).thenReturn(null);
        when(incoming.getHeader(BinanceAnalysisLeaderForwarder.FORWARDED_HEADER)).thenReturn(null);

        server.expect(requestTo("http://app1:8080" + ANALYSIS_PATH + "/ask"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"READY\",\"answer\":\"답변\"}", APPLICATION_JSON));

        BinanceAnalysisAskRequest body = new BinanceAnalysisAskRequest("현재가?", List.of());
        AnalysisForwardOutcome outcome = forwarder.forward(incoming, ANALYSIS_PATH + "/ask", HttpMethod.POST, body);

        assertThat(outcome).isInstanceOf(AnalysisForwardOutcome.Forwarded.class);
        server.verify();
    }

    @Test
    void 이미_전달된_요청_표시가_있으면_다시_전달하지_않는다() {
        BinanceAnalysisLeaderForwarder forwarder = newForwarder("DOCKER2",
                Map.of("DOCKER1", "http://app1:8080"), SECRET);
        when(incoming.getHeader(BinanceAnalysisLeaderForwarder.FORWARDED_HEADER)).thenReturn(SECRET);

        AnalysisForwardOutcome outcome = forwarder.forward(incoming, ANALYSIS_PATH, HttpMethod.GET, null);

        assertThat(outcome).isInstanceOf(AnalysisForwardOutcome.NotEligible.class);
        server.verify(); // 아무 요청도 없어야 함(expect 없음)
    }

    @Test
    void 외부에서_보낸_전달_헤더값이_비밀값과_다르면_무시하고_정상_전달한다() {
        BinanceAnalysisLeaderForwarder forwarder = newForwarder("DOCKER2",
                Map.of("DOCKER1", "http://app1:8080"), SECRET);
        when(leaderElectionService.getCurrentLeaderName()).thenReturn("DOCKER1");
        when(incoming.getHeader(BinanceAnalysisLeaderForwarder.FORWARDED_HEADER)).thenReturn("guessed-value");
        when(incoming.getHeader("Cookie")).thenReturn(null);

        server.expect(requestTo("http://app1:8080" + ANALYSIS_PATH))
                .andRespond(withSuccess("{\"status\":\"READY\"}", APPLICATION_JSON));

        AnalysisForwardOutcome outcome = forwarder.forward(incoming, ANALYSIS_PATH, HttpMethod.GET, null);

        assertThat(outcome).isInstanceOf(AnalysisForwardOutcome.Forwarded.class);
        server.verify();
    }

    @Test
    void 비밀값이_설정_안돼있으면_전달하지_않는다() {
        BinanceAnalysisLeaderForwarder forwarder = newForwarder("DOCKER2",
                Map.of("DOCKER1", "http://app1:8080"), "");

        AnalysisForwardOutcome outcome = forwarder.forward(incoming, ANALYSIS_PATH, HttpMethod.GET, null);

        assertThat(outcome).isInstanceOf(AnalysisForwardOutcome.NotEligible.class);
    }

    @Test
    void 리더이름을_모르면_전달하지_않는다() {
        BinanceAnalysisLeaderForwarder forwarder = newForwarder("DOCKER2",
                Map.of("DOCKER1", "http://app1:8080"), SECRET);
        when(leaderElectionService.getCurrentLeaderName()).thenReturn(null);
        when(incoming.getHeader(BinanceAnalysisLeaderForwarder.FORWARDED_HEADER)).thenReturn(null);

        AnalysisForwardOutcome outcome = forwarder.forward(incoming, ANALYSIS_PATH, HttpMethod.GET, null);

        assertThat(outcome).isInstanceOf(AnalysisForwardOutcome.NotEligible.class);
    }

    @Test
    void 리더가_자기_자신이면_전달하지_않는다() {
        BinanceAnalysisLeaderForwarder forwarder = newForwarder("DOCKER1",
                Map.of("DOCKER1", "http://app1:8080"), SECRET);
        when(leaderElectionService.getCurrentLeaderName()).thenReturn("DOCKER1");
        when(incoming.getHeader(BinanceAnalysisLeaderForwarder.FORWARDED_HEADER)).thenReturn(null);

        AnalysisForwardOutcome outcome = forwarder.forward(incoming, ANALYSIS_PATH, HttpMethod.GET, null);

        assertThat(outcome).isInstanceOf(AnalysisForwardOutcome.NotEligible.class);
    }

    @Test
    void 리더_이름에_해당하는_peer주소가_없으면_전달하지_않는다() {
        BinanceAnalysisLeaderForwarder forwarder = newForwarder("DOCKER2", Map.of(), SECRET);
        when(leaderElectionService.getCurrentLeaderName()).thenReturn("DOCKER1");
        when(incoming.getHeader(BinanceAnalysisLeaderForwarder.FORWARDED_HEADER)).thenReturn(null);

        AnalysisForwardOutcome outcome = forwarder.forward(incoming, ANALYSIS_PATH, HttpMethod.GET, null);

        assertThat(outcome).isInstanceOf(AnalysisForwardOutcome.NotEligible.class);
    }

    @Test
    void 피어가_5xx를_반환하면_Failed를_반환한다() {
        BinanceAnalysisLeaderForwarder forwarder = newForwarder("DOCKER2",
                Map.of("DOCKER1", "http://app1:8080"), SECRET);
        when(leaderElectionService.getCurrentLeaderName()).thenReturn("DOCKER1");
        when(incoming.getHeader(BinanceAnalysisLeaderForwarder.FORWARDED_HEADER)).thenReturn(null);
        when(incoming.getHeader("Cookie")).thenReturn(null);

        server.expect(requestTo("http://app1:8080" + ANALYSIS_PATH))
                .andRespond(withServerError());

        AnalysisForwardOutcome outcome = forwarder.forward(incoming, ANALYSIS_PATH, HttpMethod.GET, null);

        assertThat(outcome).isInstanceOf(AnalysisForwardOutcome.Failed.class);
    }

    @Test
    void 연결_자체가_실패하면_Failed를_반환한다() {
        // 존재하지 않는 리더 주소로 실제 연결을 시도 — MockRestServiceServer expect 없이 호출하면
        // 등록 안 된 요청이라 예외가 나고, forwarder는 이를 잡아 Failed로 변환해야 한다.
        BinanceAnalysisLeaderForwarder forwarder = newForwarder("DOCKER2",
                Map.of("DOCKER1", "http://app1:8080"), SECRET);
        when(leaderElectionService.getCurrentLeaderName()).thenReturn("DOCKER1");
        when(incoming.getHeader(BinanceAnalysisLeaderForwarder.FORWARDED_HEADER)).thenReturn(null);
        when(incoming.getHeader("Cookie")).thenReturn(null);

        AnalysisForwardOutcome outcome = forwarder.forward(incoming, ANALYSIS_PATH, HttpMethod.GET, null);

        assertThat(outcome).isInstanceOf(AnalysisForwardOutcome.Failed.class);
    }
}
