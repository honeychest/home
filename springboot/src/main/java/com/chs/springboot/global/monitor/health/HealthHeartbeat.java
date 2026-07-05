// [AGENT] 하트비트 레지스트리 — 잡/스케줄러가 성공 시 beat, 실패 시 fail 을 남기고
//   watchdog 이 마지막 beat 경과로 UP/DEGRADED/DOWN 을 판정한다. (FeedHealthRegistry 의 일반화)
// 핵심: 한 번도 beat 없으면 UNKNOWN(대기) — 리더 전용 잡이 비리더 인스턴스에서 오탐 나지 않게.
package com.chs.springboot.global.monitor.health;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HealthHeartbeat {

    /** 판정 임계: staleSeconds 경과=경고, downSeconds 경과=다운 */
    public record Spec(long staleSeconds, long downSeconds) { }

    public record Beat(String checkKey, HealthStatus status, Long secondsSinceBeat, String cause) { }

    /** 발행/전달용 원시 상태 — 판정은 읽는 쪽이 {@link #judge}로 재계산(주기와 무관하게 정확). */
    public record RawBeat(String checkKey, long staleSeconds, long downSeconds,
                          Long lastBeatEpochMs, Long lastFailEpochMs, String cause) { }

    private final Clock clock;
    private final Map<String, Spec> specs = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastBeat = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFail = new ConcurrentHashMap<>();
    private final Map<String, String> lastFailCause = new ConcurrentHashMap<>();

    public HealthHeartbeat(Clock clock) {
        this.clock = clock;
    }

    public void register(String checkKey, Spec spec) {
        specs.put(checkKey, spec);
    }

    public boolean isRegistered(String checkKey) {
        return specs.containsKey(checkKey);
    }

    /** 성공 1회 기록(진행 중 실패 상태 해제) */
    public void beat(String checkKey) {
        lastBeat.put(checkKey, clock.instant());
        lastFail.remove(checkKey);
        lastFailCause.remove(checkKey);
    }

    /** 실행 실패 기록(즉시 다운 신호) */
    public void fail(String checkKey, String cause) {
        lastFail.put(checkKey, clock.instant());
        lastFailCause.put(checkKey, cause == null ? "" : cause);
    }

    public List<Beat> snapshot() {
        List<Beat> out = new ArrayList<>(specs.size());
        for (String checkKey : specs.keySet()) {
            out.add(evaluate(checkKey));
        }
        return out;
    }

    public Beat evaluate(String checkKey) {
        return judge(checkKey, specs.get(checkKey),
                lastBeat.get(checkKey), lastFail.get(checkKey), lastFailCause.get(checkKey),
                clock.instant());
    }

    /** 발행된 원시 상태 전체(등록된 키별) — 리더가 클러스터 스냅샷으로 내보낼 때 사용. */
    public List<RawBeat> rawSnapshot() {
        List<RawBeat> out = new ArrayList<>(specs.size());
        for (Map.Entry<String, Spec> e : specs.entrySet()) {
            String key = e.getKey();
            Instant beat = lastBeat.get(key);
            Instant fail = lastFail.get(key);
            out.add(new RawBeat(key, e.getValue().staleSeconds(), e.getValue().downSeconds(),
                    beat == null ? null : beat.toEpochMilli(),
                    fail == null ? null : fail.toEpochMilli(),
                    lastFailCause.get(key)));
        }
        return out;
    }

    /**
     * 원시 상태(마지막 성공/실패 시각)로 상태를 판정한다. 로컬 evaluate 와 비리더 클러스터 판정이 같은 로직을 쓴다.
     * spec 미등록/미관측=UNKNOWN, 마지막이 실패=DOWN, 그 외 마지막 성공 경과로 UP/DEGRADED/DOWN.
     */
    public static Beat judge(String checkKey, Spec spec, Instant beat, Instant fail, String cause, Instant now) {
        if (spec == null) {
            return new Beat(checkKey, HealthStatus.UNKNOWN, null, null);
        }
        // 아직 한 번도 관측 안 됨 → 대기(UNKNOWN)
        if (beat == null && fail == null) {
            return new Beat(checkKey, HealthStatus.UNKNOWN, null, null);
        }
        // 마지막 실행이 실패였음 → 다운
        if (fail != null && (beat == null || fail.isAfter(beat))) {
            return new Beat(checkKey, HealthStatus.DOWN, null, cause);
        }
        // 마지막 성공 경과로 판정
        long age = now.getEpochSecond() - beat.getEpochSecond();
        HealthStatus status;
        if (age >= spec.downSeconds()) {
            status = HealthStatus.DOWN;
        } else if (age >= spec.staleSeconds()) {
            status = HealthStatus.DEGRADED;
        } else {
            status = HealthStatus.UP;
        }
        String reason = status == HealthStatus.UP ? null : (age + "초 동안 성공 없음");
        return new Beat(checkKey, status, age, reason);
    }
}
