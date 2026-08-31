package com.chs.springboot.global.monitor.health;

import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.monitor.service.MetricCollectorService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthCheckServiceTest {

    private final FeedHealthRegistry feedHealthRegistry = mock(FeedHealthRegistry.class);
    private final HealthHeartbeat healthHeartbeat = mock(HealthHeartbeat.class);
    private final HealthCheckEventRepository eventRepository = mock(HealthCheckEventRepository.class);
    private final MetricCollectorService metricCollectorService = mock(MetricCollectorService.class);
    private final HealthClusterSnapshot healthClusterSnapshot = mock(HealthClusterSnapshot.class);
    private final HealthCheckService service =
            new HealthCheckService(feedHealthRegistry, healthHeartbeat, eventRepository,
                    metricCollectorService, healthClusterSnapshot);

    {
        // HEARTBEAT 소스는 선언 기반으로 항상 evaluate 되므로 기본 대기(UNKNOWN) 응답을 준다.
        when(healthHeartbeat.evaluate(anyKey()))
                .thenReturn(new HealthHeartbeat.Beat("hb", HealthStatus.UNKNOWN, null, null));
        // 기본은 클러스터 스냅샷 없음(단일노드/리더 로컬값 경로) — 필요한 테스트가 개별 스텁.
        when(healthClusterSnapshot.read()).thenReturn(Optional.empty());
    }

    @Test
    void resourceUnderDegradedThresholdIsUp() {
        when(metricCollectorService.getLastCpu()).thenReturn(50d);

        HealthStatus status = statusOf(HealthCheckCatalog.RES_CPU.key());

        assertThat(status).isEqualTo(HealthStatus.UP);
    }

    @Test
    void resourceAtDegradedThresholdIsDegraded() {
        when(metricCollectorService.getLastRam()).thenReturn(70d);

        HealthStatus status = statusOf(HealthCheckCatalog.RES_RAM.key());

        assertThat(status).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void resourceAtDownThresholdIsDown() {
        when(metricCollectorService.getLastDisk()).thenReturn(80d);

        HealthStatus status = statusOf(HealthCheckCatalog.RES_DISK.key());

        assertThat(status).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void resourceNeverCollectedIsUnknown() {
        when(metricCollectorService.getLastCpu()).thenReturn(-1d);

        HealthStatus status = statusOf(HealthCheckCatalog.RES_CPU.key());

        assertThat(status).isEqualTo(HealthStatus.UNKNOWN);
    }

    @Test
    void infraNoOpenEventIsUp() {
        // 인프라도 이벤트 기반 — 미해결 이벤트 없으면 정상(낙관 표시)
        HealthStatus status = statusOf(HealthCheckCatalog.INFRA_MYSQL.key());

        assertThat(status).isEqualTo(HealthStatus.UP);
    }

    @Test
    void wsConnUnderDegradedThresholdIsUp() {
        when(metricCollectorService.getLastWsConnections()).thenReturn(120);

        assertThat(statusOf(HealthCheckCatalog.RES_WS_CONNECTIONS.key())).isEqualTo(HealthStatus.UP);
    }

    @Test
    void wsConnAtDegradedThresholdIsDegraded() {
        when(metricCollectorService.getLastWsConnections()).thenReturn(300);

        assertThat(statusOf(HealthCheckCatalog.RES_WS_CONNECTIONS.key())).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void wsConnAtDownThresholdIsDown() {
        when(metricCollectorService.getLastWsConnections()).thenReturn(800);

        assertThat(statusOf(HealthCheckCatalog.RES_WS_CONNECTIONS.key())).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void wsConnNeverCollectedIsUnknown() {
        when(metricCollectorService.getLastWsConnections()).thenReturn(-1);

        assertThat(statusOf(HealthCheckCatalog.RES_WS_CONNECTIONS.key())).isEqualTo(HealthStatus.UNKNOWN);
    }

    @Test
    void eventDerivedNoOpenEventIsUp() {
        // open 이벤트 없음(기본 null) → 정상(UP)
        assertThat(statusOf(HealthCheckCatalog.DATA_CANDLE_GAP.key())).isEqualTo(HealthStatus.UP);
    }

    @Test
    void eventDerivedOpenDegradedIsDegraded() {
        HealthCheckEvent open = new HealthCheckEvent();
        open.setStatus(HealthEventStatus.DEGRADED);
        open.setCause("gap 2개");
        when(eventRepository.findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(
                HealthCheckCatalog.DATA_CANDLE_GAP.key())).thenReturn(open);

        assertThat(statusOf(HealthCheckCatalog.DATA_CANDLE_GAP.key())).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void eventDerivedOpenDownIsDown() {
        HealthCheckEvent open = new HealthCheckEvent();
        open.setStatus(HealthEventStatus.DOWN);
        open.setCause("flat 40%");
        when(eventRepository.findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(
                HealthCheckCatalog.DATA_QUALITY.key())).thenReturn(open);

        assertThat(statusOf(HealthCheckCatalog.DATA_QUALITY.key())).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void externalNoEventIsUp() {
        // L6 외부연동: 호출 이력 없으면(open 없음) 정상(UP) — 결정1=가
        assertThat(statusOf(HealthCheckCatalog.EXT_TELEGRAM_SEND.key())).isEqualTo(HealthStatus.UP);
    }

    @Test
    void externalOpenFailureIsDown() {
        HealthCheckEvent open = new HealthCheckEvent();
        open.setStatus(HealthEventStatus.DOWN);
        open.setCause("송신 실패");
        when(eventRepository.findTopByCheckKeyAndResolvedAtIsNullOrderByLastFailedAtDesc(
                HealthCheckCatalog.EXT_LLM.key())).thenReturn(open);

        assertThat(statusOf(HealthCheckCatalog.EXT_LLM.key())).isEqualTo(HealthStatus.DOWN);
    }

    // /events 응답 매핑: 엔티티 필드가 타입드 뷰로 옮겨지고 시각은 yyyy-MM-dd HH:mm:ss 로 포맷된다
    @Test
    void recentEventsMapToTypedView() {
        HealthCheckEvent e = new HealthCheckEvent();
        e.setCheckKey(HealthCheckCatalog.INFRA_MYSQL.key());
        e.setStatus(HealthEventStatus.RESOLVED);
        e.setSeverity("CRITICAL");
        e.setCause("연결 실패");
        e.setFirstFailedAt(java.time.LocalDateTime.of(2026, 7, 4, 10, 0, 0));
        e.setLastFailedAt(java.time.LocalDateTime.of(2026, 7, 4, 10, 5, 0));
        when(eventRepository.findTop100ByOrderByLastFailedAtDesc()).thenReturn(List.of(e));

        List<HealthEventView> events = service.getRecentEvents();

        assertThat(events).hasSize(1);
        HealthEventView v = events.get(0);
        assertThat(v.checkKey()).isEqualTo(HealthCheckCatalog.INFRA_MYSQL.key());
        assertThat(v.status()).isEqualTo(HealthEventStatus.RESOLVED);
        assertThat(v.severity()).isEqualTo("CRITICAL");
        assertThat(v.lastFailedAt()).isEqualTo("2026-07-04 10:05:00");
        assertThat(v.resolvedAt()).isNull();
    }

    // ── 이중화 폴백: 비리더(로컬 미관측)일 때 리더 발행 클러스터 스냅샷으로 판정 ──

    @Test
    void heartbeatFromClusterWhenLocalUnknown() {
        // 비리더: 로컬 하트비트 UNKNOWN(대기)이어도 리더 스냅샷에 최근 성공이 있으면 UP
        String key = HealthCheckCatalog.SCHED_LEADER_ELECTION.key();
        long now = System.currentTimeMillis();
        HealthClusterSnapshot.HeartbeatEntry entry =
                new HealthClusterSnapshot.HeartbeatEntry(key, 15, 30, now, null, null);
        when(healthClusterSnapshot.read()).thenReturn(Optional.of(
                new HealthClusterSnapshot.Dto(List.of(entry), List.of(), null, null, null, null, now)));

        assertThat(statusOf(key)).isEqualTo(HealthStatus.UP);
    }

    @Test
    void resourceRamFromClusterWhenLocalUnobserved() {
        // 비리더: 로컬 ram 미관측(-1) → 리더 발행 클러스터 ram(다운선 80%)으로 DOWN
        when(metricCollectorService.getLastRam()).thenReturn(-1d);
        // Dto(heartbeats, feeds, ram, disk, rawTableBytes, wsConnections, publishedAtEpochMs)
        when(healthClusterSnapshot.read()).thenReturn(Optional.of(
                new HealthClusterSnapshot.Dto(List.of(), List.of(), 80d, null, null, null, 0L)));

        assertThat(statusOf(HealthCheckCatalog.RES_RAM.key())).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void feedFromClusterWhenLocalUnobserved() {
        // 비리더: aggTrade WS 는 리더 전용이라 로컬 미수신(DOWN) → 리더 발행 스냅샷의
        // 최근 수신시각으로 재판정하면 UP. 표시결함(영구 DOWN) 폴백 검증.
        String feedId = com.chs.springboot.global.monitor.feed.FeedHealthConfig.BINANCE_AGG_TRADE;
        long recent = System.currentTimeMillis() - 2_000; // 2초 전 수신 → FEED_SECONDS(10/30) 안쪽
        HealthClusterSnapshot.FeedEntry feed = new HealthClusterSnapshot.FeedEntry(feedId, recent, 100);
        when(healthClusterSnapshot.read()).thenReturn(Optional.of(
                new HealthClusterSnapshot.Dto(List.of(), List.of(feed), null, null, null, null, 0L)));

        assertThat(statusOf(HealthCheckCatalog.FEED_BINANCE_AGGTRADE.key())).isEqualTo(HealthStatus.UP);
    }

    // ── 최근이상 흔적: 현재 UP 이나 창(recent-window-hours) 내 복구된 장애가 있으면 표시 ──

    @Test
    void recentlyRecoveredFlaggedWhenResolvedWithinWindow() {
        ReflectionTestUtils.setField(service, "recentWindowHours", 24);
        String key = HealthCheckCatalog.RES_CPU.key();
        when(metricCollectorService.getLastCpu()).thenReturn(10d); // 현재 정상(UP)
        HealthCheckEvent recovered = new HealthCheckEvent();
        recovered.setStatus(HealthEventStatus.DOWN);
        recovered.setLastFailedAt(LocalDateTime.now().minusHours(1));
        recovered.setResolvedAt(LocalDateTime.now().minusMinutes(50));
        when(eventRepository.findTop3ByCheckKeyOrderByLastFailedAtDesc(eq(key)))
                .thenReturn(List.of(recovered));

        HealthCheckView v = viewOf(key);
        assertThat(v.status()).isEqualTo(HealthStatus.UP);
        assertThat(v.recentlyRecovered()).isTrue();
        assertThat(v.recoveredAt()).isNotNull();
    }

    @Test
    void notFlaggedWhenResolvedOutsideWindow() {
        ReflectionTestUtils.setField(service, "recentWindowHours", 24);
        String key = HealthCheckCatalog.RES_CPU.key();
        when(metricCollectorService.getLastCpu()).thenReturn(10d);
        HealthCheckEvent old = new HealthCheckEvent();
        old.setStatus(HealthEventStatus.DOWN);
        old.setLastFailedAt(LocalDateTime.now().minusDays(2)); // 창 밖
        old.setResolvedAt(LocalDateTime.now().minusDays(2));
        when(eventRepository.findTop3ByCheckKeyOrderByLastFailedAtDesc(eq(key)))
                .thenReturn(List.of(old));

        HealthCheckView v = viewOf(key);
        assertThat(v.recentlyRecovered()).isFalse();
        assertThat(v.recoveredAt()).isNull();
    }

    private HealthCheckView viewOf(String key) {
        // 대상 키만 개별 스텁, 나머지는 Mockito 기본(빈 리스트)로 이력 없음
        return service.getChecks().stream()
                .filter(c -> c.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private HealthStatus statusOf(String key) {
        when(eventRepository.findTop3ByCheckKeyOrderByLastFailedAtDesc(anyKey()))
                .thenReturn(List.of());
        List<HealthCheckView> checks = service.getChecks();
        return checks.stream()
                .filter(c -> c.key().equals(key))
                .findFirst()
                .map(HealthCheckView::status)
                .orElseThrow();
    }

    private static String anyKey() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
