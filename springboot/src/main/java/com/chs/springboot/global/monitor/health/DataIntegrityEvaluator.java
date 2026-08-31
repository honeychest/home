// [AGENT] L4 데이터 무결성 계측 — 최근 60분 주 심볼(BTCUSDT FUTURES) 캔들을 능동 쿼리해
//   gap(누락봉)·quality(flat 비율)를 판정하고 상태 전환을 health_check_event 로 적립.
// 2분 주기, leader 노드에서만 실행(중복 방지). 정상 지속 시 DB 쓰기 없음.
// 표시(HealthCheckService)는 이 적립된 이벤트로 상태를 읽는다(이벤트 이력 기반).
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.domain.binance.service.SignalCandleSource;
import com.chs.springboot.global.redis.LeaderElectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class DataIntegrityEvaluator {

    // 감시 범위: 대표 심볼 1개(원천 장애면 어차피 여기서 잡힘)
    private static final String SYMBOL = "BTCUSDT";
    private static final String MARKET = "FUTURES";
    private static final long MINUTE_MS = 60_000L;
    private static final int WINDOW_MINUTES = 60;
    // 1m 롤업 지연(cron 매분 +10초) 여유 — 최근 2분은 아직 안 굳었을 수 있어 제외
    private static final long LAG_MS = 2 * MINUTE_MS;

    private final SignalCandleSource candleSource;
    private final HealthCheckRecorder recorder;
    private final LeaderElectionService leaderElection;

    public DataIntegrityEvaluator(SignalCandleSource candleSource,
                                  HealthCheckRecorder recorder,
                                  LeaderElectionService leaderElection) {
        this.candleSource = candleSource;
        this.recorder = recorder;
        this.leaderElection = leaderElection;
    }

    @Scheduled(fixedDelay = 120_000)
    public void evaluate() {
        if (!leaderElection.isLeader()) {
            return;
        }
        long end = (System.currentTimeMillis() / MINUTE_MS) * MINUTE_MS - LAG_MS;
        long from = end - WINDOW_MINUTES * MINUTE_MS;
        try {
            evaluateGap(from, end);
        } catch (Exception e) {
            log.warn("[DataIntegrity] gap 평가 실패: {}", e.getMessage());
        }
        try {
            evaluateQuality(from, end);
        } catch (Exception e) {
            log.warn("[DataIntegrity] quality 평가 실패: {}", e.getMessage());
        }
    }

    private void evaluateGap(long from, long end) {
        List<SignalCandleSource.SignalCandle> candles = candleSource.find(
                SYMBOL, SignalCandleSource.Interval.ONE_MINUTE, from, end,
                SignalCandleSource.QueryMode.COMPLETED);
        int present = candles.size();
        int missing = Math.max(0, WINDOW_MINUTES - present);
        String cause = "[%s-%s] 최근%d분 누락봉 %d개(존재 %d/%d)"
                .formatted(SYMBOL, MARKET, WINDOW_MINUTES, missing, present, WINDOW_MINUTES);
        recorder.record(HealthCheckCatalog.DATA_CANDLE_GAP.key(),
                StatusLadder.CANDLE_GAP.judge(missing), cause);
    }

    private void evaluateQuality(long from, long end) {
        List<SignalCandleSource.SignalCandle> candles = candleSource.find(
                SYMBOL, SignalCandleSource.Interval.ONE_MINUTE, from, end,
                SignalCandleSource.QueryMode.COMPLETED);
        long total = candles.size();
        if (total == 0) {
            // 표본 없음 — 판정 보류(gap 쪽에서 누락으로 이미 잡힘)
            return;
        }
        long flat = candles.stream()
                .filter(c -> c.openPrice().compareTo(c.highPrice()) == 0
                        && c.highPrice().compareTo(c.lowPrice()) == 0
                        && c.lowPrice().compareTo(c.closePrice()) == 0)
                .count();
        double flatPct = (flat * 100d) / total;
        String cause = "[%s-%s] 최근%d분 flat %.1f%%(%d/%d)"
                .formatted(SYMBOL, MARKET, WINDOW_MINUTES, flatPct, flat, total);
        recorder.record(HealthCheckCatalog.DATA_QUALITY.key(),
                StatusLadder.FLAT_PCT.judge(flatPct), cause);
    }
}
