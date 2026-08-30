package com.chs.springboot.domain.binance.service.rawwriter;

import com.chs.springboot.global.redis.LeaderElectionService;
import com.chs.springboot.global.redis.LeadershipChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class AggTradeRawWriterKafkaTelemetryService {

    private static final String RAW_TOPIC = "market.aggtrade.raw";
    private static final String DLQ_TOPIC = "market.aggtrade.dlq";
    private static final long BASE_BUCKET_MS = KafkaWindow.TELEMETRY_BUCKET_MS;
    private static final long RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_FAILURE_SAMPLES = 50;
    private static final String CACHE_KEY = "aggtrade:raw-writer:kafka-telemetry";
    private static final long CACHE_TTL_SECONDS = 26L * 60L * 60L;
    /** shutdown 시 최종 flush 대기 상한 — Redis 가 죽어 있어도 종료가 이 시간 이상 늘어지지 않는다. */
    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 2000L;
    /** hydration 재시도 backoff: poll 주기(5s) 배수로 5s→10s→20s→40s→80s→120s(cap) 확장. */
    private static final long HYDRATION_RETRY_BASE_MS = 5000L;
    private static final long HYDRATION_RETRY_MAX_MS = 120_000L;

    /**
     * fence 가 여전히 유효할 때만(=지금도 그 lease 의 리더) telemetry 를 덮어쓴다.
     * KEYS[1]=telemetry 캐시 키, KEYS[2]=LeaderElectionService.FENCE_KEY.
     * ARGV[1]="ownerToken:epoch"(이 인스턴스가 리더가 됐을 때 받은 값), ARGV[2]=payload, ARGV[3]=TTL(초).
     */
    static final RedisScript<Long> FLUSH_IF_FENCE_VALID = new DefaultRedisScript<>(
            "local fence = redis.call('get', KEYS[2]) " +
                    "if fence ~= ARGV[1] then return 0 end " +
                    "redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3]) " +
                    "return 1",
            Long.class);

    private final KafkaPipelineSwitchboard switchboard;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final AggTradeRawWriterKafkaOffsetInspector offsetInspector;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String consumerGroupId;
    private final String bootstrapServers;

    private final AggTradeRawWriterTelemetryBucketStore bucketStore =
            new AggTradeRawWriterTelemetryBucketStore(BASE_BUCKET_MS, RETENTION_MS);
    private final AggTradeRawWriterFailureSampleBuffer failureSamples =
            new AggTradeRawWriterFailureSampleBuffer(MAX_FAILURE_SAMPLES);

    private long totalConsumedRecords;
    private long totalWriteSuccessRecords;
    private long totalInvalidRecords;
    private long totalDlqPublishedRecords;
    private long totalDlqPublishFailureRecords;
    private long totalDbFailureRecords;
    private long totalRetrySuccessRecords;
    private long totalSuccessfulBatches;
    private long totalFailedBatches;
    private Long lastSuccessAtMs;
    private Long lastErrorAtMs;
    private String lastErrorMessage;
    /** true = hydration 이 확정됐다(Redis HIT 로 복원했거나, 정상적인 MISS 로 빈 상태임을 확인함). */
    private boolean hydrated;
    /** true = 마지막 hydration 시도가 실패(연결 오류·timeout·JSON 파싱 실패)했다 — MISS 와 구분되는 관측용 플래그. */
    private boolean hydrationFailed;
    private final AtomicInteger hydrationFailureStreak = new AtomicInteger();
    private volatile long nextHydrationRetryAtMs;
    private boolean dirty;
    private long version;
    private boolean flushing;
    /** 이 인스턴스가 리더인 동안에만 "ownerToken:epoch" 값을 들고, 리더가 아니면 null — flush 를 fence 로 게이팅한다. */
    private volatile String fenceValue;

    public AggTradeRawWriterKafkaTelemetryService(
            KafkaPipelineSwitchboard switchboard,
            KafkaListenerEndpointRegistry listenerRegistry,
            AggTradeRawWriterKafkaOffsetInspector offsetInspector,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${kafka.consumer.group-id:raw-writer}") String consumerGroupId,
            @Value("${spring.kafka.bootstrap-servers:kafka:9092}") String bootstrapServers
    ) {
        this.switchboard = switchboard;
        this.listenerRegistry = listenerRegistry;
        this.offsetInspector = offsetInspector;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.consumerGroupId = consumerGroupId;
        this.bootstrapServers = bootstrapServers;
    }

    public synchronized void recordConsumed(int count) {
        long now = clock.millis();
        totalConsumedRecords += count;
        bucketStore.recordConsumed(now, count);
        dirty = true; version++;
    }

    public synchronized void recordWriteSuccess(int count) {
        long now = clock.millis();
        totalWriteSuccessRecords += count;
        totalSuccessfulBatches += 1;
        lastSuccessAtMs = now;
        bucketStore.recordWriteSuccess(now, count);
        dirty = true; version++;
    }

    public synchronized void recordInvalidRecord(String symbol, String marketType, Integer partition, Long offset, String errorMessage) {
        long now = clock.millis();
        totalInvalidRecords += 1;
        bucketStore.recordInvalid(now);
        failureSamples.add(new AggTradeRawWriterKafkaFailureSample(
                now, "INVALID", symbol, marketType, partition, offset, errorMessage
        ));
        dirty = true; version++;
    }

    public synchronized void recordDlqPublished(String symbol, String marketType, Integer partition, Long offset, String errorMessage) {
        long now = clock.millis();
        totalDlqPublishedRecords += 1;
        bucketStore.recordDlqPublished(now);
        failureSamples.add(new AggTradeRawWriterKafkaFailureSample(
                now, "DLQ_PUBLISHED", symbol, marketType, partition, offset, errorMessage
        ));
        dirty = true; version++;
    }

    public synchronized void recordDlqPublishFailure(String symbol, String marketType, Integer partition, Long offset, String errorMessage) {
        long now = clock.millis();
        totalDlqPublishFailureRecords += 1;
        totalFailedBatches += 1;
        lastErrorAtMs = now;
        lastErrorMessage = errorMessage;
        bucketStore.recordDlqPublishFailure(now);
        failureSamples.add(new AggTradeRawWriterKafkaFailureSample(
                now, "DLQ_PUBLISH_FAIL", symbol, marketType, partition, offset, errorMessage
        ));
        dirty = true; version++;
    }

    public synchronized void recordDbFailure(int count, String errorMessage) {
        long now = clock.millis();
        totalDbFailureRecords += count;
        totalFailedBatches += 1;
        lastErrorAtMs = now;
        lastErrorMessage = errorMessage;
        bucketStore.recordDbFailure(now, count);
        dirty = true; version++;
    }

    public synchronized void recordRetrySuccess(int count) {
        long now = clock.millis();
        totalRetrySuccessRecords += count;
        bucketStore.recordRetrySuccess(now, count);
        dirty = true; version++;
    }

    public synchronized void recordFailedBatch(String errorMessage) {
        long now = clock.millis();
        totalFailedBatches += 1;
        lastErrorAtMs = now;
        lastErrorMessage = errorMessage;
        bucketStore.recordFailedBatch(now);
        dirty = true; version++;
    }

    public synchronized AggTradeRawWriterKafkaTelemetryWindowsResponse windows(int minutes, int bucketSeconds) {
        ensureHydrated();
        long now = clock.millis();
        long bucketMs = Math.max(60, bucketSeconds) * 1000L;
        List<AggTradeRawWriterKafkaTelemetryWindow> rows = bucketStore.windows(now, minutes, bucketSeconds);
        return new AggTradeRawWriterKafkaTelemetryWindowsResponse(minutes, (int) (bucketMs / 1000L), rows);
    }

    @PostConstruct
    void hydrateAtStartup() {
        attemptHydration();
    }

    /**
     * hydration miss(=Redis 에 키 없음, 정상적인 빈 상태)와 failure(연결 오류·timeout·malformed JSON)를 분리한다.
     * failure 는 hydrated 를 true 로 만들지 않는다 — 그 상태로 flush 하면 기존 공유 상태를 0(또는 미완성 델타)으로
     * 덮어쓰게 되므로, flushNow() 는 hydrated==false 인 동안 무조건 스킵한다.
     */
    private void attemptHydration() {
        HydrationResult result = readSharedStateSafely();
        synchronized (this) {
            if (hydrated) return; // 이미 다른 시도로 확정됨
            switch (result.outcome()) {
                case HIT -> {
                    applyState(result.state());
                    hydrated = true;
                    hydrationFailed = false;
                    hydrationFailureStreak.set(0);
                }
                case MISS -> {
                    hydrated = true;
                    hydrationFailed = false;
                    hydrationFailureStreak.set(0);
                }
                case FAILURE -> {
                    hydrationFailed = true;
                    log.warn("[AggTradeRawWriterTelemetry] hydration 실패(연결/timeout/malformed) — 기존 공유 상태를 덮어쓰지 않고 재시도 예정: {}", result.error());
                }
            }
        }
    }

    /**
     * hot path(record*)는 절대 Redis GET 을 재시도하지 않는다 — hydration 실패 시의 재시도는
     * 이 scheduled 경계에서만, 그리고 지수 backoff(5s→10s→…→120s cap)로 이뤄진다.
     */
    @Scheduled(fixedDelayString = "${raw-writer.telemetry.hydration-retry-poll-ms:5000}")
    void retryHydrationIfNeeded() {
        if (hydrated) return;
        long now = clock.millis();
        if (now < nextHydrationRetryAtMs) return;
        attemptHydration();
        if (hydrated) return;
        int streak = hydrationFailureStreak.incrementAndGet();
        long backoffMs = Math.min(HYDRATION_RETRY_BASE_MS * (1L << Math.min(streak, 5)), HYDRATION_RETRY_MAX_MS);
        nextHydrationRetryAtMs = now + backoffMs;
    }

    @EventListener
    public void onLeadershipChanged(LeadershipChangedEvent event) {
        synchronized (this) {
            fenceValue = event.leader() ? (event.ownerToken() + ":" + event.epoch()) : null;
        }
    }

    @Scheduled(fixedDelayString = "${raw-writer.telemetry.flush-interval-ms:5000}")
    void flushScheduled() {
        flushNow();
    }

    /**
     * shutdown 정책: bounded best-effort flush. 별도 스레드에서 한 번 더 flush 를 시도하되
     * {@link #SHUTDOWN_FLUSH_TIMEOUT_MS} 이상은 기다리지 않는다 — Redis 가 죽어 있어도 종료가
     * 무한 대기하거나 Kafka 컨테이너 shutdown 을 막지 않는다.
     * <p>데이터 유실 구간: 정상(graceful) 종료는 이 flush 로 유실이 거의 없다. 강제 종료(kill -9)는
     * 이 훅 자체가 실행되지 않으므로, 마지막 성공 flush 이후 ~ 강제종료 시점까지의 dirty delta
     * (최대 flush-interval-ms, 기본 5초치)가 유실될 수 있다 — 다음 리더가 이어받아도 그 델타는 복구되지 않는다.</p>
     */
    @PreDestroy
    void flushOnShutdown() {
        if (!dirty) return;
        Thread shutdownFlush = new Thread(this::flushNow, "aggtrade-telemetry-shutdown-flush");
        shutdownFlush.setDaemon(true);
        shutdownFlush.start();
        try {
            shutdownFlush.join(SHUTDOWN_FLUSH_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (shutdownFlush.isAlive()) {
            log.warn("[AggTradeRawWriterTelemetry] shutdown flush 가 {}ms 안에 끝나지 않음 — dirty delta 유실 가능", SHUTDOWN_FLUSH_TIMEOUT_MS);
        }
    }

    void flushNow() {
        final SharedTelemetryState snapshot;
        final long captured;
        final String fence;
        synchronized (this) {
            if (flushing || !dirty || !hydrated) return;
            fence = fenceValue;
            if (fence == null) return; // 지금 이 인스턴스는 리더가 아니다 — dirty 는 보존하고 flush 만 스킵
            flushing = true;
            captured = version;
            snapshot = stateSnapshot(clock.millis());
        }
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            Long result = redisTemplate.execute(
                    FLUSH_IF_FENCE_VALID,
                    List.of(CACHE_KEY, LeaderElectionService.FENCE_KEY),
                    fence, json, String.valueOf(CACHE_TTL_SECONDS));
            synchronized (this) {
                if (Long.valueOf(1L).equals(result)) {
                    if (version == captured) dirty = false;
                } else {
                    log.warn("[AggTradeRawWriterTelemetry] flush 거부 — fence 가 더 이상 유효하지 않음(리더 교체됨): fence={}", fence);
                }
            }
        } catch (Exception e) {
            // JSON 직렬화·Redis 오류는 Kafka 소비/DB 저장에 영향을 주지 않는다 — dirty 는 그대로 보존해 다음 주기에 재시도.
            log.warn("[AggTradeRawWriterTelemetry] Redis flush failed: {}", e.getMessage());
        } finally {
            synchronized (this) { flushing = false; }
        }
    }

    public AggTradeRawWriterKafkaTelemetryResponse snapshot() {
        KafkaPipelineExecutionPlan plan = switchboard.aggTradeRawWriterPlan();
        MessageListenerContainer container = listenerRegistry.getListenerContainer(AggTradeRawWriterConsumer.LISTENER_ID);
        boolean listenerRunning = container != null && container.isRunning();
        AggTradeRawWriterKafkaTopicSnapshot rawTopic = offsetInspector.loadTopicSnapshot(RAW_TOPIC, consumerGroupId, true);
        AggTradeRawWriterKafkaTopicSnapshot dlqTopic = offsetInspector.loadTopicSnapshot(DLQ_TOPIC, consumerGroupId, false);

        synchronized (this) {
            ensureHydrated();
            return new AggTradeRawWriterKafkaTelemetryResponse(
                    plan.mode(),
                    plan.enabled(),
                    plan.dryRun(),
                    plan.targetTable(),
                    listenerRunning,
                    consumerGroupId,
                    bootstrapServers,
                    totalConsumedRecords,
                    totalWriteSuccessRecords,
                    totalInvalidRecords,
                    totalDlqPublishedRecords,
                    totalDlqPublishFailureRecords,
                    totalDbFailureRecords,
                    totalRetrySuccessRecords,
                    totalSuccessfulBatches,
                    totalFailedBatches,
                    lastSuccessAtMs,
                    lastErrorAtMs,
                    lastErrorMessage,
                    bucketStore.summarize(),
                    failureSamples.snapshot(),
                    rawTopic,
                    dlqTopic
            );
        }
    }

    private void ensureHydrated() {
        if (hydrated) {
            return;
        }
        hydrateAtStartup();
    }

    private SharedTelemetryState stateSnapshot(long now) {
        return new SharedTelemetryState(
                    totalConsumedRecords,
                    totalWriteSuccessRecords,
                    totalInvalidRecords,
                    totalDlqPublishedRecords,
                    totalDlqPublishFailureRecords,
                    totalDbFailureRecords,
                    totalRetrySuccessRecords,
                    totalSuccessfulBatches,
                    totalFailedBatches,
                    lastSuccessAtMs,
                    lastErrorAtMs,
                    lastErrorMessage,
                    failureSamples.snapshot(),
                    bucketStore.snapshot(),
                    now
        );
    }

    private void applyState(SharedTelemetryState state) {
        totalConsumedRecords = state.totalConsumedRecords();
        totalWriteSuccessRecords = state.totalWriteSuccessRecords();
        totalInvalidRecords = state.totalInvalidRecords();
        totalDlqPublishedRecords = state.totalDlqPublishedRecords();
        totalDlqPublishFailureRecords = state.totalDlqPublishFailureRecords();
        totalDbFailureRecords = state.totalDbFailureRecords();
        totalRetrySuccessRecords = state.totalRetrySuccessRecords();
        totalSuccessfulBatches = state.totalSuccessfulBatches();
        totalFailedBatches = state.totalFailedBatches();
        lastSuccessAtMs = state.lastSuccessAtMs();
        lastErrorAtMs = state.lastErrorAtMs();
        lastErrorMessage = state.lastErrorMessage();
        bucketStore.restore(state.baseWindows());
        failureSamples.restore(state.recentFailures());
    }

    /**
     * Redis 키 없음(MISS, 정상적인 빈 상태)과 연결 오류·timeout·malformed JSON(FAILURE)을 구분해서 반환한다.
     * 이 구분이 hydration 의 핵심 계약 — 호출부(attemptHydration)가 FAILURE 를 MISS 로 착각하면
     * 기존에 쌓인 공유 상태를 0으로 덮어쓰는 사고로 이어진다.
     */
    private HydrationResult readSharedStateSafely() {
        String json;
        try {
            json = redisTemplate.opsForValue().get(CACHE_KEY);
        } catch (Exception e) {
            return HydrationResult.failure(e.getMessage());
        }
        if (json == null || json.isBlank()) {
            return HydrationResult.miss();
        }
        try {
            return HydrationResult.hit(objectMapper.readValue(json, SharedTelemetryState.class));
        } catch (Exception e) {
            return HydrationResult.failure(e.getMessage());
        }
    }

    private enum HydrationOutcome { HIT, MISS, FAILURE }

    private record HydrationResult(HydrationOutcome outcome, SharedTelemetryState state, String error) {
        static HydrationResult hit(SharedTelemetryState state) {
            return new HydrationResult(HydrationOutcome.HIT, state, null);
        }

        static HydrationResult miss() {
            return new HydrationResult(HydrationOutcome.MISS, null, null);
        }

        static HydrationResult failure(String error) {
            return new HydrationResult(HydrationOutcome.FAILURE, null, error);
        }
    }

    private record SharedTelemetryState(
            long totalConsumedRecords,
            long totalWriteSuccessRecords,
            long totalInvalidRecords,
            long totalDlqPublishedRecords,
            long totalDlqPublishFailureRecords,
            long totalDbFailureRecords,
            long totalRetrySuccessRecords,
            long totalSuccessfulBatches,
            long totalFailedBatches,
            Long lastSuccessAtMs,
            Long lastErrorAtMs,
            String lastErrorMessage,
            List<AggTradeRawWriterKafkaFailureSample> recentFailures,
            List<AggTradeRawWriterKafkaTelemetryWindow> baseWindows,
            long updatedAtMs
    ) {
    }
}
