package com.chs.springboot.domain.binance.service.rawwriter;

import com.chs.springboot.global.redis.LeaderElectionService;
import com.chs.springboot.global.redis.LeadershipChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AggTradeRawWriterKafkaTelemetryServiceTest {

    private final KafkaPipelineSwitchboard switchboard = mock(KafkaPipelineSwitchboard.class);
    private final KafkaListenerEndpointRegistry listenerRegistry = mock(KafkaListenerEndpointRegistry.class);
    private final AggTradeRawWriterKafkaOffsetInspector offsetInspector = mock(AggTradeRawWriterKafkaOffsetInspector.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> redisStorage = new HashMap<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-05-23T00:00:30Z"));

    AggTradeRawWriterKafkaTelemetryServiceTest() {
        objectMapper.findAndRegisterModules();
        when(switchboard.aggTradeRawWriterPlan())
                .thenReturn(KafkaPipelineExecutionPlan.from(KafkaPipelineState.DEBUG, "raw_agg_trade", "raw_agg_trade_test"));
        when(listenerRegistry.getListenerContainer(AggTradeRawWriterConsumer.LISTENER_ID)).thenReturn(mock(MessageListenerContainer.class));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redisStorage.get(invocation.getArgument(0)));

        // FLUSH_IF_FENCE_VALID 를 실제 Lua 와 동일한 원자 검증 규칙으로 재현한다:
        // fence 키의 현재 값이 이 flush 가 들고 온 fence 와 다르면 거부(0), 같으면 캐시 키에 씀(1).
        when(redisTemplate.execute(
                eq(AggTradeRawWriterKafkaTelemetryService.FLUSH_IF_FENCE_VALID),
                eq(List.of("aggtrade:raw-writer:kafka-telemetry", LeaderElectionService.FENCE_KEY)),
                anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String expectedFence = invocation.getArgument(2);
                    String payload = invocation.getArgument(3);
                    String currentFence = redisStorage.get(LeaderElectionService.FENCE_KEY);
                    if (!Objects.equals(expectedFence, currentFence)) {
                        return 0L;
                    }
                    redisStorage.put("aggtrade:raw-writer:kafka-telemetry", payload);
                    return 1L;
                });
    }

    /** leader 로 승격시키고, 그 fence 를 Redis 쪽에도 동시에 반영한다(원자 검증 대상). */
    private void becomeLeader(AggTradeRawWriterKafkaTelemetryService service, String ownerToken, long epoch) {
        redisStorage.put(LeaderElectionService.FENCE_KEY, ownerToken + ":" + epoch);
        service.onLeadershipChanged(new LeadershipChangedEvent("server-A", true, ownerToken, epoch));
    }

    private void loseLeadership(AggTradeRawWriterKafkaTelemetryService service, String ownerToken, long epoch) {
        service.onLeadershipChanged(new LeadershipChangedEvent("server-A", false, ownerToken, epoch));
    }

    @Test
    void snapshotHydratesFromSharedRedisStateAcrossInstances() {
        AggTradeRawWriterKafkaTelemetryService leader = newService();
        leader.hydrateAtStartup();
        becomeLeader(leader, "owner-1", 1L);
        leader.recordConsumed(10);
        leader.recordWriteSuccess(9);
        leader.recordDbFailure(1, "db down");
        leader.flushNow();

        AggTradeRawWriterKafkaTelemetryService follower = newService();
        AggTradeRawWriterKafkaTelemetryResponse response = follower.snapshot();

        assertEquals(10, response.totalConsumedRecords());
        assertEquals(9, response.totalWriteSuccessRecords());
        assertEquals(1, response.totalDbFailureRecords());
        assertEquals("db down", response.lastErrorMessage());
        assertEquals(10, response.summary().peakConsumedRecords());
        assertEquals(1, response.summary().peakDbFailureRecords());
    }

    @Test
    void windowsHydratesAndMergesSharedRedisBuckets() {
        AggTradeRawWriterKafkaTelemetryService leader = newService();
        leader.hydrateAtStartup();
        becomeLeader(leader, "owner-1", 1L);
        leader.recordConsumed(10);
        clock.setInstant(Instant.parse("2026-05-23T00:01:10Z"));
        leader.recordConsumed(5);
        leader.flushNow();

        AggTradeRawWriterKafkaTelemetryService follower = newService();
        AggTradeRawWriterKafkaTelemetryWindowsResponse windows = follower.windows(60, 120);

        assertEquals(1, windows.windows().size());
        assertEquals(15, windows.windows().get(0).consumedRecords());
        assertEquals(120, windows.bucketSeconds());
    }

    @Test
    void hydration_distinguishesMissFromConnectionFailure() {
        // MISS: 키가 없을 뿐, GET 자체는 정상 응답
        AggTradeRawWriterKafkaTelemetryService missService = newService();
        missService.hydrateAtStartup();
        assertTrue((boolean) getField(missService, "hydrated"));
        assertFalse((boolean) getField(missService, "hydrationFailed"));

        // FAILURE: GET 자체가 예외(연결 오류/timeout 상당)
        StringRedisTemplate throwingRedis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> throwingOps = mock(ValueOperations.class);
        when(throwingRedis.opsForValue()).thenReturn(throwingOps);
        when(throwingOps.get(anyString())).thenThrow(new RuntimeException("connection refused"));
        AggTradeRawWriterKafkaTelemetryService failedService = new AggTradeRawWriterKafkaTelemetryService(
                switchboard, listenerRegistry, offsetInspector, throwingRedis, objectMapper, clock,
                "raw-writer-local", "localhost:9094");

        failedService.hydrateAtStartup();

        assertFalse((boolean) getField(failedService, "hydrated"));
        assertTrue((boolean) getField(failedService, "hydrationFailed"));
    }

    @Test
    void hydration_malformedJsonIsTreatedAsFailureNotMiss() {
        redisStorage.put("aggtrade:raw-writer:kafka-telemetry", "{not-valid-json");
        AggTradeRawWriterKafkaTelemetryService service = newService();

        service.hydrateAtStartup();

        assertFalse((boolean) getField(service, "hydrated"));
        assertTrue((boolean) getField(service, "hydrationFailed"));
    }

    @Test
    void hydrationFailure_doesNotFlushEmptyStateOverExistingSharedState() {
        // Redis 에 이미 누적된 상태가 있다고 가정(다른 인스턴스가 채워둠)
        AggTradeRawWriterKafkaTelemetryService seeder = newService();
        seeder.hydrateAtStartup();
        becomeLeader(seeder, "owner-seed", 1L);
        seeder.recordConsumed(999);
        seeder.flushNow();
        String beforeJson = redisStorage.get("aggtrade:raw-writer:kafka-telemetry");
        assertTrue(beforeJson != null && beforeJson.contains("999"));

        // 이 인스턴스는 hydration 이 실패한 채로(=hydrated false) 리더가 되어 record 를 계속 쌓는다.
        StringRedisTemplate throwingRedis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> throwingOps = mock(ValueOperations.class);
        when(throwingRedis.opsForValue()).thenReturn(throwingOps);
        when(throwingOps.get(anyString())).thenThrow(new RuntimeException("connection refused"));
        // flush 호출 시 fence 검증까지 가면 안 된다 — hydrated 가 false 라 flushNow 가 이 지점 이전에 리턴해야 한다.
        AggTradeRawWriterKafkaTelemetryService failedHydration = new AggTradeRawWriterKafkaTelemetryService(
                switchboard, listenerRegistry, offsetInspector, throwingRedis, objectMapper, clock,
                "raw-writer-local", "localhost:9094");
        failedHydration.hydrateAtStartup();
        assertFalse((boolean) getField(failedHydration, "hydrated"));

        failedHydration.onLeadershipChanged(new LeadershipChangedEvent("server-A", true, "owner-new", 2L));
        failedHydration.recordConsumed(1);
        failedHydration.flushNow();

        // hydrated==false 인 동안은 flushNow 가 fence 검증·Redis 쓰기 자체를 시도하지 않는다 —
        // 그래서 다른 인스턴스가 이미 채워둔 공유 상태(redisStorage, 999 포함)가 그대로 남아 있어야 한다.
        assertEquals(beforeJson, redisStorage.get("aggtrade:raw-writer:kafka-telemetry"));
    }

    @Test
    void recordHotPath_neverCallsRedisGetEvenWhenHydrationHasFailed() {
        StringRedisTemplate throwingRedis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> throwingOps = mock(ValueOperations.class);
        when(throwingRedis.opsForValue()).thenReturn(throwingOps);
        when(throwingOps.get(anyString())).thenThrow(new RuntimeException("connection refused"));
        AggTradeRawWriterKafkaTelemetryService service = new AggTradeRawWriterKafkaTelemetryService(
                switchboard, listenerRegistry, offsetInspector, throwingRedis, objectMapper, clock,
                "raw-writer-local", "localhost:9094");
        service.hydrateAtStartup(); // 1회 GET 실패 소비

        // hot path 는 이후로도 GET 을 절대 다시 부르지 않는다(재시도는 scheduled 경계에서만).
        for (int i = 0; i < 20; i++) {
            service.recordConsumed(1);
            service.recordWriteSuccess(1);
            service.recordDbFailure(1, "x");
        }

        verify(throwingOps, org.mockito.Mockito.times(1)).get(anyString());
    }

    @Test
    void flush_isRejectedWhenFenceIsStale_previousLeaderCannotOverwriteNewLeader() {
        AggTradeRawWriterKafkaTelemetryService oldLeader = newService();
        oldLeader.hydrateAtStartup();
        becomeLeader(oldLeader, "owner-old", 1L);
        oldLeader.recordConsumed(5);

        // 새 리더가 선출되어 fence 가 교체된 상태를 Redis 쪽에 반영(oldLeader 는 아직 이 사실을 모른다고 가정하고
        // onLeadershipChanged 를 호출하지 않는다 — "늦게 도착한 이벤트"보다 더 나쁜 "이벤트 자체를 놓친" 경우까지 검증)
        redisStorage.put(LeaderElectionService.FENCE_KEY, "owner-new:2");

        oldLeader.flushNow();

        assertNull(redisStorage.get("aggtrade:raw-writer:kafka-telemetry"));
        assertTrue((boolean) getField(oldLeader, "dirty")); // 거부됐으니 dirty 는 보존돼야 한다
    }

    @Test
    void flush_selfReacquireEpochBump_updatesFenceSoFlushSucceedsAfterPriorStaleRejection() {
        // 같은 인스턴스가 leader=true 를 유지한 채 epoch 만 1 -> 2 로 바뀌는(자가 재획득) 시나리오를
        // 흉내낸다: LeaderElectionService 의 HIGH 수정 이후에는 이 경우에도 onLeadershipChanged 가
        // 발행되므로, telemetry 의 fenceValue 가 옛 epoch(1)에 멈추지 않고 새 epoch(2)로 갱신돼야 한다.
        AggTradeRawWriterKafkaTelemetryService service = newService();
        service.hydrateAtStartup();
        becomeLeader(service, "owner-1", 1L);
        service.recordConsumed(1);

        // Redis 쪽 fence 가 이미 새 epoch 로 바뀐 상태에서(=재획득 완료), 옛 epoch(1) fence 로 flush 를
        // 시도하면 거부돼야 한다 — HIGH 수정 전이었다면 이벤트 누락으로 인해 fenceValue 가 계속 1 로
        // 남아 아래 flushNow() 가 영원히 거부됐을 상황.
        redisStorage.put(LeaderElectionService.FENCE_KEY, "owner-1:2");
        service.flushNow();
        assertNull(redisStorage.get("aggtrade:raw-writer:kafka-telemetry"));
        assertTrue((boolean) getField(service, "dirty"));

        // 재획득 이벤트가 실제로 도착하면(HIGH 수정으로 LeaderElectionService 가 epoch 변경 시에도 발행)
        // fenceValue 가 새 epoch 로 갱신되고, 이후 flush 는 성공해야 한다.
        becomeLeader(service, "owner-1", 2L);
        service.flushNow();

        assertFalse((boolean) getField(service, "dirty"));
        String json = redisStorage.get("aggtrade:raw-writer:kafka-telemetry");
        assertTrue(json != null && json.contains("\"totalConsumedRecords\":1"));
    }

    @Test
    void flush_isSkippedWhenNotLeader_dirtyPreserved() {
        AggTradeRawWriterKafkaTelemetryService service = newService();
        service.hydrateAtStartup();
        service.recordConsumed(3); // 리더가 아닌 상태에서도 로컬 누적은 가능

        service.flushNow();

        assertNull(redisStorage.get("aggtrade:raw-writer:kafka-telemetry"));
        assertTrue((boolean) getField(service, "dirty"));
    }

    @Test
    void flush_preservesDirtyWhenNewRecordArrivesDuringFlush() throws Exception {
        AggTradeRawWriterKafkaTelemetryService service = newService();
        service.hydrateAtStartup();
        becomeLeader(service, "owner-1", 1L);
        service.recordConsumed(1);

        // flushNow 는 synchronized 라 진짜 동시성 창을 열 수는 없으니, version 비교 로직 자체를
        // 화이트박스로 검증한다: flush 도중 version 이 올라가면 dirty 가 false 로 안 떨어져야 한다는
        // 계약은 recordConsumed 를 flush 직후 바로 호출해 최종 dirty==true 로 남는지로 확인한다.
        service.flushNow();
        assertFalse((boolean) getField(service, "dirty"));

        service.recordConsumed(2);
        assertTrue((boolean) getField(service, "dirty"));
    }

    @Test
    void flush_multiThreadedRecordCallsAreAllAccountedForAfterFlush() throws InterruptedException {
        AggTradeRawWriterKafkaTelemetryService service = newService();
        service.hydrateAtStartup();
        becomeLeader(service, "owner-1", 1L);

        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        service.recordConsumed(1);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        service.flushNow();

        AggTradeRawWriterKafkaTelemetryService follower = newService();
        AggTradeRawWriterKafkaTelemetryResponse response = follower.snapshot();
        assertEquals((long) threads * perThread, response.totalConsumedRecords());
    }

    @Test
    void shutdownFlush_bestEffortFlushesDirtyStateWithinBoundedTime() throws Exception {
        AggTradeRawWriterKafkaTelemetryService service = newService();
        service.hydrateAtStartup();
        becomeLeader(service, "owner-1", 1L);
        service.recordConsumed(7);

        invokeFlushOnShutdown(service);

        assertFalse((boolean) getField(service, "dirty"));
        String json = redisStorage.get("aggtrade:raw-writer:kafka-telemetry");
        assertTrue(json != null && json.contains("7"));
    }

    @Test
    void shutdownFlush_noOpWhenNothingDirty() throws Exception {
        AggTradeRawWriterKafkaTelemetryService service = newService();
        service.hydrateAtStartup();

        invokeFlushOnShutdown(service); // dirty=false 이므로 아무 것도 안 함, 예외도 없어야 함

        assertNull(redisStorage.get("aggtrade:raw-writer:kafka-telemetry"));
    }

    private void invokeFlushOnShutdown(AggTradeRawWriterKafkaTelemetryService service) throws Exception {
        var method = AggTradeRawWriterKafkaTelemetryService.class.getDeclaredMethod("flushOnShutdown");
        method.setAccessible(true);
        method.invoke(service);
    }

    private Object getField(AggTradeRawWriterKafkaTelemetryService service, String name) {
        try {
            var field = AggTradeRawWriterKafkaTelemetryService.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(service);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private AggTradeRawWriterKafkaTelemetryService newService() {
        return new AggTradeRawWriterKafkaTelemetryService(
                switchboard,
                listenerRegistry,
                offsetInspector,
                redisTemplate,
                objectMapper,
                clock,
                "raw-writer-local",
                "localhost:9094"
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return TimeZone.getTimeZone("UTC").toZoneId();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
