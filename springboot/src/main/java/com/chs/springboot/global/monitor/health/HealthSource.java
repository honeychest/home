// [AGENT] 체크별 상태 소스 — 보드 표시 시 상태를 "어디서 읽고 어떻게 판정하는지"와
// 팝오버 임계 문구를 소스가 스스로 소유한다(판정·문구가 한 소스에 응집).
// HealthCheckService.getChecks() 는 c.source().judge(c, ports) 만 호출하고 분기하지 않는다.
// enum 은 스프링 빈을 주입받을 수 없으므로 협력자는 Ports 로 요청마다 전달받는다.
// HEARTBEAT 선언은 HealthHeartbeatConfig 등록과 양방향 일치해야 하며 기동 시 검증된다.
package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.monitor.service.MetricCollectorService;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public enum HealthSource {
    FEED {          // FeedHealthRegistry 신선도 스냅샷
        @Override
        Judgement judge(HealthCheckCatalog check, Ports ports) {
            FeedHealthRegistry.FeedHealth fh = ports.feeds().get(check.feedId());
            // 이 노드가 관측했으면(리더) 로컬 판정이 가장 신선
            if (fh != null && fh.secondsSinceLastMessage() != null) {
                return new Judgement(fh.status(), describeFeed(fh));
            }
            // 로컬 미관측(리더 전용 WS 라 비리더는 수신 기록 없음) → 리더 발행 스냅샷 원시상태로 재판정
            HealthClusterSnapshot.FeedEntry e = ports.cluster().feed(check.feedId());
            if (e == null || e.lastMessageAtEpochMs() == null) {
                HealthStatus status = fh == null ? HealthStatus.UNKNOWN : fh.status();
                return new Judgement(status, describeFeed(fh)); // 리더값도 없음 → 로컬 유지
            }
            long elapsed = Instant.now().getEpochSecond() - e.lastMessageAtEpochMs() / 1000;
            FeedHealthRegistry.FeedHealth leader = new FeedHealthRegistry.FeedHealth(
                    check.feedId(), StatusLadder.FEED_SECONDS.judge(elapsed),
                    elapsed, e.lastMessageAtEpochMs(), e.receivedCount());
            return new Judgement(leader.status(), describeFeed(leader));
        }

        @Override
        String thresholdText(HealthCheckCatalog check) {
            return StatusLadder.FEED_SECONDS.text("초");
        }
    },
    HEARTBEAT {     // HealthHeartbeat 경과 판정 (HealthHeartbeatConfig 임계 등록 필수)
        @Override
        Judgement judge(HealthCheckCatalog check, Ports ports) {
            // 이 노드가 관측했으면(리더) 로컬 판정이 가장 신선
            HealthHeartbeat.Beat local = ports.heartbeat().evaluate(check.key());
            if (local.status() != HealthStatus.UNKNOWN) {
                return new Judgement(local.status(), describeBeat(local));
            }
            // 로컬 미관측(비리더) → 리더가 발행한 클러스터 스냅샷 원시상태로 재판정
            HealthClusterSnapshot.HeartbeatEntry e = ports.cluster().heartbeat(check.key());
            if (e == null) {
                return new Judgement(HealthStatus.UNKNOWN, describeBeat(local)); // 대기
            }
            HealthHeartbeat.Beat b = HealthHeartbeat.judge(
                    check.key(), new HealthHeartbeat.Spec(e.staleSeconds(), e.downSeconds()),
                    epoch(e.lastBeatEpochMs()), epoch(e.lastFailEpochMs()), e.cause(), Instant.now());
            return new Judgement(b.status(), describeBeat(b));
        }

        @Override
        String thresholdText(HealthCheckCatalog check) {
            HealthHeartbeat.Spec hb = check.heartbeat();
            return new StatusLadder(hb.staleSeconds(), hb.downSeconds())
                    .text("초", " (마지막 성공 경과, 실행 실패는 즉시 다운)");
        }
    },
    RESOURCE_PCT {  // MetricCollectorService 퍼센트 스냅샷 (cpu/ram/disk)
        @Override
        Judgement judge(HealthCheckCatalog check, Ports ports) {
            Double value = resourceValue(check, ports.metrics());
            if (value == null) {
                value = clusterResource(check, ports.cluster()); // 비리더 → 리더 발행값
            }
            HealthStatus status = value == null ? HealthStatus.UNKNOWN : StatusLadder.RESOURCE_PCT.judge(value);
            return new Judgement(status, describeResource(value));
        }

        @Override
        String thresholdText(HealthCheckCatalog check) {
            return StatusLadder.RESOURCE_PCT.text("%", "(AlertService 임계와 동일)");
        }
    },
    WSCONN {        // WS 세션 수 절대값 임계
        @Override
        Judgement judge(HealthCheckCatalog check, Ports ports) {
            int conns = ports.metrics().getLastWsConnections();
            if (conns < 0 && ports.cluster().wsConnections() != null) {
                conns = ports.cluster().wsConnections(); // 비리더 → 리더 발행값
            }
            StatusLadder.Judged j = StatusLadder.judgeWsConn(conns);
            return new Judgement(j.status(), j.detail());
        }

        @Override
        String thresholdText(HealthCheckCatalog check) {
            return StatusLadder.WS_CONNS.text("", " (4개 핸들러 합)");
        }
    },
    INFRA {         // InfraHealthProbe 능동 프로브가 적립한 open 이벤트 기반 (UP 아니면 DOWN)
        @Override
        Judgement judge(HealthCheckCatalog check, Ports ports) {
            return judgeFromEvents(check.key(), ports.events());
        }

        @Override
        String thresholdText(HealthCheckCatalog check) {
            // 보드 요청 경로에서 실접속 프로브 제거 — InfraHealthEvaluator 가 적립한 이벤트 기반(CONTEXT.md 설계 결정)
            return "20초 주기 능동 프로브 기록 기반 — UP 아니면 DOWN";
        }
    },
    EVENT {         // 능동 평가기·호출지점 push 가 적립한 open 이벤트 유무
        @Override
        Judgement judge(HealthCheckCatalog check, Ports ports) {
            return judgeFromEvents(check.key(), ports.events());
        }

        @Override
        String thresholdText(HealthCheckCatalog check) {
            return null; // 사용 시점 push — 임계 없음
        }
    };

    /** 보드 표시용 live 판정 결과: 상태 + 판정근거 문구 */
    public record Judgement(HealthStatus status, String detail) { }

    /** 판정에 필요한 협력자 묶음 — enum 은 스프링 빈을 못 받으므로 서비스가 요청마다 구성해 넘긴다. */
    public record Ports(
            Map<String, FeedHealthRegistry.FeedHealth> feeds,
            HealthHeartbeat heartbeat,
            MetricCollectorService metrics,
            HealthCheckEventRepository events,
            ClusterView cluster
    ) { }

    /**
     * 리더가 발행한 클러스터 스냅샷의 요청당 조회 뷰 — 노드-로컬 값이 미관측(비리더)일 때 폴백에 쓴다.
     * 스냅샷이 없으면 {@link #empty()} (전부 미관측처럼 동작).
     */
    public record ClusterView(
            Map<String, HealthClusterSnapshot.HeartbeatEntry> heartbeats,
            Map<String, HealthClusterSnapshot.FeedEntry> feeds,
            Double ram, Double disk, Long rawTableBytes, Integer wsConnections
    ) {
        public static ClusterView empty() {
            return new ClusterView(Map.of(), Map.of(), null, null, null, null);
        }

        public static ClusterView from(HealthClusterSnapshot.Dto dto) {
            if (dto == null) {
                return empty();
            }
            Map<String, HealthClusterSnapshot.HeartbeatEntry> map = new HashMap<>();
            for (HealthClusterSnapshot.HeartbeatEntry e : dto.heartbeats()) {
                map.put(e.checkKey(), e);
            }
            Map<String, HealthClusterSnapshot.FeedEntry> feedMap = new HashMap<>();
            if (dto.feeds() != null) {
                for (HealthClusterSnapshot.FeedEntry e : dto.feeds()) {
                    feedMap.put(e.feedId(), e);
                }
            }
            return new ClusterView(map, feedMap, dto.ram(), dto.disk(), dto.rawTableBytes(), dto.wsConnections());
        }

        HealthClusterSnapshot.HeartbeatEntry heartbeat(String checkKey) {
            return heartbeats.get(checkKey);
        }

        HealthClusterSnapshot.FeedEntry feed(String feedId) {
            return feeds.get(feedId);
        }
    }

    /** 보드 표시용 live 판정 (상태 + 판정근거). */
    abstract Judgement judge(HealthCheckCatalog check, Ports ports);

    /**
     * 팝오버에 표시할 판정 기준 문구 — 소스가 자기 기준을 스스로 설명한다.
     * 사다리 참조 소스는 StatusLadder 상수에서, HEARTBEAT 는 항목의 임계에서 파생. EVENT 는 임계가 없어 null.
     */
    abstract String thresholdText(HealthCheckCatalog check);

    // ── 공용 판정/문구 헬퍼 ─────────────────────────────────────────────

    // 평가기·호출지점 push 가 이벤트로 적립한 결과를 읽는다(INFRA·EVENT 소스 공용).
    // 미복구(open) 이벤트 있으면 그 상태, 없으면 정상(UP). "알려진 실패 없음" 낙관 표시.
    private static Judgement judgeFromEvents(String key, HealthCheckEventRepository events) {
        HealthCheckEvent open =
                events.findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(key);
        if (open == null) {
            return new Judgement(HealthStatus.UP, "정상");
        }
        return new Judgement(open.getStatus().asHealthStatus(),
                open.getCause() != null ? open.getCause() : "이상 감지");
    }

    private static String describeFeed(FeedHealthRegistry.FeedHealth fh) {
        if (fh == null || fh.secondsSinceLastMessage() == null) {
            return "수신 기록 없음";
        }
        return fh.secondsSinceLastMessage() + "초 전 수신 (누적 " + fh.receivedCount() + ")";
    }

    private static Double resourceValue(HealthCheckCatalog check, MetricCollectorService metrics) {
        double v;
        if (check.key().equals(HealthCheckCatalog.RES_CPU.key())) {
            v = metrics.getLastCpu();
        } else if (check.key().equals(HealthCheckCatalog.RES_RAM.key())) {
            v = metrics.getLastRam();
        } else {
            v = metrics.getLastDisk();
        }
        return v < 0 ? null : v;
    }

    private static String describeResource(Double value) {
        if (value == null) return "수집 기록 없음";
        return "%.1f%%".formatted(value);
    }

    // 비리더 폴백: 리더 발행 자원값(ram/disk). cpu 는 양 노드가 관측하므로 로컬에서 이미 값이 있음.
    private static Double clusterResource(HealthCheckCatalog check, ClusterView cluster) {
        if (check.key().equals(HealthCheckCatalog.RES_RAM.key())) {
            return cluster.ram();
        }
        if (check.key().equals(HealthCheckCatalog.RES_DISK.key())) {
            return cluster.disk();
        }
        return null;
    }

    private static Instant epoch(Long epochMs) {
        return epochMs == null ? null : Instant.ofEpochMilli(epochMs);
    }

    private static String describeBeat(HealthHeartbeat.Beat beat) {
        if (beat.status() == HealthStatus.UNKNOWN) {
            return "대기 — 아직 실행 기록 없음";
        }
        if (beat.secondsSinceBeat() == null) {
            return "최근 실행 실패";
        }
        return beat.secondsSinceBeat() + "초 전 마지막 성공";
    }
}
