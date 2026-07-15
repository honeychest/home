// [AGENT] Gemini 호출 속도 제어 + 워커 생존 신호 — gemini_rate 단일 행(id=1)이 유일한 원본.
// 앱이 2인스턴스라 "분당 몇 번" 같은 상태를 자바 메모리에 두면 인스턴스마다 따로 세어 한도를
// 못 지킨다 — 그래서 조율을 전부 DB 원자적 UPDATE 에 맡긴다 (인스턴스 합산 간격 하한).
// 2026-07-15 분리: 원래 VideoRepository 안에 있었으나 이 클래스의 SQL 은 video 테이블을 한 번도
// 건드리지 않는다 — 영상 저장소와 한 몸일 이유가 없어 떼어냄. 워커는 이제 "영상 저장소"와
// "호출 속도 제어기" 두 협력자를 갖는다 (gikka 전용 JdbcClient 만 사용 — 분리 규율 2·8).
package com.chs.springboot.domain.recipe.registration;

import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GeminiRateLimiter {

    private final JdbcClient jdbc;

    public GeminiRateLimiter(@Qualifier("gikkaJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Gemini 호출 슬롯 획득 — 인스턴스 합산 간격 하한 (원자적 UPDATE, 실패 = 이번 틱 쉼) */
    public boolean tryAcquireSlot(int minIntervalSeconds) {
        return jdbc.sql("""
                        UPDATE gemini_rate SET last_call_at = now()
                        WHERE id = 1 AND last_call_at <= now() - make_interval(secs => :interval)
                        """)
                .param("interval", minIntervalSeconds).update() > 0;
    }

    /** 429 후 전 인스턴스 공통 휴식 (last_call_at 을 미래로 밀어 양쪽 다 멈춤) + 발생 이력 기록 */
    public void backoff(int seconds) {
        jdbc.sql("""
                        UPDATE gemini_rate
                        SET last_call_at = now() + make_interval(secs => :secs),
                            rate_limit_count = rate_limit_count + 1, last_rate_limited_at = now()
                        WHERE id = 1
                        """)
                .param("secs", seconds).update();
    }

    /** 워커 생존 신호 — 일할 게 없어도 틱마다 갱신 (last_call_at 은 429 로 미래로 밀리기도 해서 별도 분리) */
    public void touchHeartbeat() {
        jdbc.sql("UPDATE gemini_rate SET heartbeat_at = now() WHERE id = 1").update();
    }

    /** 워커 생존·429 이력 — gemini_rate 단일 행. nextRetryAt: backoff 가 last_call_at 을
        미래로 밀어둔 값 그대로 — 지금 백오프 중이면 "다음 재시도 가능 시각", 아니면 과거 시각
        (2026-07-14 확정, 모니터링 화면 카운트다운용) */
    public record WorkerStatus(OffsetDateTime heartbeatAt, int rateLimitCount, OffsetDateTime lastRateLimitedAt,
                               OffsetDateTime nextRetryAt) {
    }

    public WorkerStatus workerStatus() {
        return jdbc.sql("SELECT heartbeat_at, rate_limit_count, last_rate_limited_at, last_call_at "
                        + "FROM gemini_rate WHERE id = 1")
                .query((rs, i) -> new WorkerStatus(
                        rs.getObject("heartbeat_at", OffsetDateTime.class),
                        rs.getInt("rate_limit_count"),
                        rs.getObject("last_rate_limited_at", OffsetDateTime.class),
                        rs.getObject("last_call_at", OffsetDateTime.class)))
                .single();
    }
}
