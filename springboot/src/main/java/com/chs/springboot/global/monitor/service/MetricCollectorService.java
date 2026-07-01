// [AGENT] 5초 주기 메트릭 수집 + /ws/monitor 브로드캐스트 + AlertService 평가
package com.chs.springboot.global.monitor.service;

import com.chs.springboot.domain.binance.repository.S3ArchiveLogRepository;
import com.chs.springboot.domain.binance.websocket.BinancePriceWebSocketHandler;
import com.chs.springboot.domain.binance.websocket.CandleWebSocketHandler;
import com.chs.springboot.domain.upbit.websocket.UpbitPriceWebSocketHandler;
import com.chs.springboot.global.config.service.AppConfigService;
import com.chs.springboot.global.monitor.dto.MetricSnapshot;
import com.chs.springboot.global.monitor.feed.FeedHealthRegistry;
import com.chs.springboot.global.monitor.handler.MonitorWebSocketHandler;
import com.chs.springboot.global.redis.LeaderElectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.w3c.dom.Text;

import java.io.File;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricCollectorService {

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final MonitorWebSocketHandler monitorWebSocketHandler;
    private final AlertService alertService;
    private final BinancePriceWebSocketHandler binancePriceWebSocketHandler;
    private final UpbitPriceWebSocketHandler upbitPriceWebSocketHandler;
    private final CandleWebSocketHandler candleWebSocketHandler;
    private final AppConfigService appConfigService;
    private final ObjectMapper objectMapper;
    private final LeaderElectionService leaderElectionService;
    private final S3ArchiveLogRepository s3ArchiveLogRepository;
    private final FeedHealthRegistry feedHealthRegistry;

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Resource(name = "batchDataSource")
    private final HikariDataSource batchDataSource;
    private volatile List<MetricSnapshot.ContainerInfo> cachedContainers = List.of();

    private final String containerId = Optional.ofNullable(System.getenv("HOSTNAME"))
            .filter(s -> !s.isBlank())
            .orElseGet(() -> UUID.randomUUID().toString().substring(0, 8));

    private volatile double prev5xxCount = 0d;
    private volatile double prevTotalCount = 0d;
    private volatile double lastCpu = -1d;
    private volatile double lastRam = -1d;
    private volatile double lastDisk = -1d;

    public double getLastCpu() {
        return lastCpu;
    }

    /** 헬스보드 res-ram 재사용 — leader 노드에서만 갱신(collect()의 leader 분기 참조) */
    public double getLastRam() {
        return lastRam;
    }

    /** 헬스보드 res-disk 재사용 — leader 노드에서만 갱신(collect()의 leader 분기 참조) */
    public double getLastDisk() {
        return lastDisk;
    }

    @Scheduled(fixedDelay = 3000)
    public void collect() {
        Double cpu = safe(this::collectCpuPercent);
        if (cpu != null) lastCpu = cpu;

        if (!leaderElectionService.isLeader()) {
            // Redis에서 snapshot 읽어서 broadcast
            String json = redisTemplate.opsForValue().get("monitor:snapshot");
            if (json != null) {
                monitorWebSocketHandler.broadcastRaw(json);
            }
            return;
        }
        Double ram = safe(this::collectRamPercent);
        Double disk = safe(this::collectDiskPercent);
        if (ram != null) lastRam = ram;
        if (disk != null) lastDisk = disk;
        Long diskTotalBytes = safe(this::collectDiskTotalBytes);
        Long diskFreeBytes = safe(this::collectDiskFreeBytes);
        Double apiErrorRate = null;

        Long rawAggTradeRows = safe(this::collectRawAggTradeRowsEstimate);
        Long rawAggTradeBytes = safe(this::collectRawAggTradeBytesEstimate);
        Long rawAggTradeS3Rows = safe(this::collectRawAggTradeS3Rows);
        Long rawAggTradeS3Bytes = safe(this::collectRawAggTradeS3Bytes);

        Long redisQueue = safe(() -> redisTemplate.opsForList().size("aggtrade:queue"));
        List<MetricSnapshot.RedisKv> redisKeys = safe(this::collectFixedRedisKv);
        if (redisKeys == null) redisKeys = List.of();

        int wsMonitor = Optional.ofNullable(safe(monitorWebSocketHandler::getSessionCount)).orElse(0);
        int wsBinance = Optional.ofNullable(safe(binancePriceWebSocketHandler::getSessionCount)).orElse(0);
        int wsUpbit = Optional.ofNullable(safe(upbitPriceWebSocketHandler::getSessionCount)).orElse(0);
        int wsCandle = Optional.ofNullable(safe(candleWebSocketHandler::getSessionCount)).orElse(0);
        int wsTotal = wsMonitor + wsBinance + wsUpbit + wsCandle;
        Integer wsConnections = wsTotal;

        List<MetricSnapshot.ContainerInfo> containers = cachedContainers;
        if (containers == null) containers = List.of();

        List<FeedHealthRegistry.FeedHealth> feeds = Optional.ofNullable(safe(feedHealthRegistry::snapshot)).orElse(List.of());

        MetricSnapshot snapshot = new MetricSnapshot(
                cpu,
                ram,
                disk,
                diskTotalBytes,
                diskFreeBytes,
                rawAggTradeRows,
                rawAggTradeBytes,
                rawAggTradeS3Rows,
                rawAggTradeS3Bytes,
                redisQueue,
                redisKeys,
                wsConnections,
                wsMonitor,
                wsBinance,
                wsUpbit,
                wsCandle,
                apiErrorRate,
                feeds,
                containers,
                LocalDateTime.now(),
                containerId
        );

        monitorWebSocketHandler.broadcast(snapshot);
        try {
            redisTemplate.opsForValue().set("monitor:snapshot", objectMapper.writeValueAsString(snapshot), 120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[MetricCollector] snapshot Redis 저장 실패: {}", e.getMessage());
        }
        alertService.evaluate(snapshot);
    }

    @Scheduled(fixedDelay = 3000)
    public void adjustBatchPool() {
        double cpu = lastCpu;
        if (cpu < 0) return;
        int targetSize;
        if (cpu >= 90) {
            targetSize = 1;
        } else if (cpu >= 70) {
            targetSize = 2;
        } else if (cpu >= 50) {
            targetSize = 4;
        } else {
            targetSize = 6;
        }
        if (batchDataSource.getMaximumPoolSize() != targetSize) {
            log.warn("[BatchPool] CPU={}% → 풀 크기 {} → {}",
                    String.format("%.1f", cpu),
                    batchDataSource.getMaximumPoolSize(),
                    targetSize);
            batchDataSource.setMaximumPoolSize(targetSize);
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void collectContainerCache() {
        if (!leaderElectionService.isLeader()) return;
        if (!new File("/var/run/docker.sock").exists()) return;
        try {
            List<MetricSnapshot.ContainerInfo> result = collectContainers();
            if (result != null) cachedContainers = result;
        } catch (Exception e) {
            log.warn("[MetricCollector] Docker 수집 실패: {}", e.getMessage());
        }
    }

    private Long collectRawAggTradeRowsEstimate() {
        String sql = """
                SELECT table_rows
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'raw_agg_trade'
                """;
        try {
            return jdbcTemplate.queryForObject(sql, Long.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Long collectRawAggTradeS3Rows() {
        // complete='Y' 인 레코드만 합산
        List<S3ArchiveLogRepository.TableRowCountProjection> projections =
                s3ArchiveLogRepository.sumRowCountByTableName();
        long total = 0L;
        for (S3ArchiveLogRepository.TableRowCountProjection p : projections) {
            if ("raw_agg_trade".equals(p.getTableName()) && p.getTotal() != null) {
                total += p.getTotal();
            }
        }
        return total;
    }

    private Long collectRawAggTradeS3Bytes() {
        List<S3ArchiveLogRepository.TableRowCountProjection> projections =
                s3ArchiveLogRepository.sumFileSizeByTableName();
        long total = 0L;
        for (S3ArchiveLogRepository.TableRowCountProjection p : projections) {
            if ("raw_agg_trade".equals(p.getTableName()) && p.getTotal() != null) {
                total += p.getTotal();
            }
        }
        return total;
    }

    private Long collectRawAggTradeBytesEstimate() {
        String sql = """
                SELECT (data_length + index_length)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'raw_agg_trade'
                """;
        try {
            return jdbcTemplate.queryForObject(sql, Long.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Double collectCpuPercent() {
        Gauge g = meterRegistry.find("system.cpu.usage").gauge();
        if (g == null) return null;
        double v = g.value() * 100d;
        return clampPercent(v);
    }

    private Double collectRamPercent() {
        double used = 0d;
        double max = 0d;

        for (Gauge g : meterRegistry.find("jvm.memory.used").tag("area", "heap").gauges()) {
            used += g.value();
        }
        for (Gauge g : meterRegistry.find("jvm.memory.max").tag("area", "heap").gauges()) {
            max += g.value();
        }
        if (max <= 0) return null;
        return clampPercent((used / max) * 100d);
    }

    private Double collectDiskPercent() {
        File root = new File("/");
        long total = root.getTotalSpace();
        long free = root.getFreeSpace();
        if (total <= 0) return null;
        double usedPercent = ((double) (total - free) / (double) total) * 100d;
        return clampPercent(usedPercent);
    }

    private Long collectDiskTotalBytes() {
        File root = new File("/");
        long total = root.getTotalSpace();
        return total > 0 ? total : null;
    }

    private Long collectDiskFreeBytes() {
        File root = new File("/");
        long free = root.getFreeSpace();
        return free >= 0 ? free : null;
    }

    private Double collectApiErrorRatePercent() {
        double total = 0d;
        double five = 0d;

        Collection<Meter> meters = meterRegistry.find("http.server.requests").meters();
        for (Meter m : meters) {
            double count = 0d;
            for (io.micrometer.core.instrument.Measurement ms : m.measure()) {
                if ("COUNT".equalsIgnoreCase(ms.getStatistic().name())) {
                    count += ms.getValue();
                }
            }

            total += count;

            String status = tagValue(m.getId().getTags(), "status");
            if (status != null && status.startsWith("5")) {
                five += count;
            }
        }

        double totalDelta = Math.max(0d, total - prevTotalCount);
        double fiveDelta = Math.max(0d, five - prev5xxCount);

        prevTotalCount = total;
        prev5xxCount = five;

        if (totalDelta <= 0d) return 0d;
        return clampPercent((fiveDelta / totalDelta) * 100d);
    }

    private List<MetricSnapshot.ContainerInfo> collectContainers() throws Exception {
        DockerClient docker = buildDockerClient();



        var containers = docker.listContainersCmd()
                .withShowAll(true)
                .exec();

        if (containers == null || containers.isEmpty()) return List.of();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(containers.size(), 8));
        List<Future<MetricSnapshot.ContainerInfo>> futures = new ArrayList<>();
        for (var c : containers) {
            String id = c.getId();
            if (id == null || id.isBlank()) continue;

            futures.add(executor.submit(() -> {
                var inspect = docker.inspectContainerCmd(id).exec();
                if (inspect == null) return null;

                Statistics[] samples = null;
                try { samples = fetchStatsTwoSample(docker, id); } catch (Exception ignored) {}

                String name = inspect.getName();
                if (name != null && name.startsWith("/")) name = name.substring(1);

                String status = inspect.getState() != null ? inspect.getState().getStatus() : null;
                Integer restarts = inspect.getRestartCount();
                String image = inspect.getConfig() != null ? inspect.getConfig().getImage() : null;

                Long uptimeSec = null;
                try {
                    String startedAt = inspect.getState() != null ? inspect.getState().getStartedAt() : null;
                    if (startedAt != null && !startedAt.isBlank()) {
                        OffsetDateTime started = OffsetDateTime.parse(startedAt);
                        uptimeSec = Math.max(0, ChronoUnit.SECONDS.between(started, OffsetDateTime.now()));
                    }
                } catch (Exception ignored) {}

                Double cpuPercent = null;
                Long memUsed = null;
                Long memLimit = null;
                try {
                    if (samples != null) {
                        Statistics prev = samples[0];
                        Statistics cur = samples[1];
                        cpuPercent = calcCpuPercent(prev, cur);
                        if (cur.getMemoryStats() != null) {
                            memUsed = cur.getMemoryStats().getUsage();
                            memLimit = cur.getMemoryStats().getLimit();
                        }
                    }
                } catch (Exception ignored) {}

                if (name == null || name.isBlank()) return null;
                return new MetricSnapshot.ContainerInfo(name, status, restarts, image, uptimeSec, cpuPercent, memUsed, memLimit);
            }));
        }
        executor.shutdown();
        List<MetricSnapshot.ContainerInfo> list = new ArrayList<>();
        for (Future<MetricSnapshot.ContainerInfo> f : futures) {
            try {
                MetricSnapshot.ContainerInfo info = f.get(5, TimeUnit.SECONDS);
                if (info != null) list.add(info);
            } catch (Exception ignored) {}
        }
        return list;
    }

    private static Double calcCpuPercent(Statistics prev, Statistics cur) {
        if (prev == null || cur == null) return null;
        if (cur.getCpuStats() == null || prev.getCpuStats() == null) return null;
        if (cur.getCpuStats().getCpuUsage() == null || prev.getCpuStats().getCpuUsage() == null) return null;

        Long cpuTotal = cur.getCpuStats().getCpuUsage().getTotalUsage();
        Long preCpuTotal = prev.getCpuStats().getCpuUsage().getTotalUsage();
        Long sys = cur.getCpuStats().getSystemCpuUsage();
        Long preSys = prev.getCpuStats().getSystemCpuUsage();
        if (cpuTotal == null || preCpuTotal == null || sys == null || preSys == null) return null;

        long cpuDelta = cpuTotal - preCpuTotal;
        long sysDelta = sys - preSys;
        if (sysDelta <= 0) return 0d;

        Long online = cur.getCpuStats().getOnlineCpus();
        int cores = (online != null && online > 0) ? (int) Math.min(Integer.MAX_VALUE, online) : 1;
        double v = ((double) cpuDelta / (double) sysDelta) * cores * 100d;
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return Math.max(0d, Math.min(100d, v));
    }

    private static Statistics[] fetchStatsTwoSample(DockerClient docker, String id) throws Exception {
        if (docker == null || id == null || id.isBlank()) return null;

        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<Statistics> prevRef = new AtomicReference<>(null);
        AtomicReference<Statistics> curRef = new AtomicReference<>(null);

        ResultCallback.Adapter<Statistics> cb = new ResultCallback.Adapter<>() {
            private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
            @Override
            public void onNext(Statistics stats) {
                int n = count.incrementAndGet();
                if (n == 1) prevRef.set(stats);
                else if (n == 2) {
                    curRef.set(stats);
                    try { close(); } catch (Exception ignored) {}
                }
                latch.countDown();
            }
        };

        docker.statsCmd(id).withNoStream(false).exec(cb);
        latch.await(3, TimeUnit.SECONDS);
        try { cb.close(); } catch (Exception ignored) {}

        Statistics prev = prevRef.get();
        Statistics cur = curRef.get();
        if (prev == null || cur == null) return null;
        return new Statistics[]{prev, cur};
    }

    private DockerClient buildDockerClient() {
        // Docker Engine API via unix socket: /var/run/docker.sock
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock")
                .build();

        var httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(URI.create("unix:///var/run/docker.sock"))
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    private List<MetricSnapshot.RedisKv> collectFixedRedisKv() {
        List<String> keys = List.of(
                "server:leader",
                "config:aggtrade:max-queue-size",
                "config:threshold"
        );

        List<MetricSnapshot.RedisKv> out = new ArrayList<>(keys.size());
        for (String k : keys) {
            String v = appConfigService.get(k);
            if (v != null && v.length() > 400) {
                v = v.substring(0, 400) + "…";
            }
            out.add(new MetricSnapshot.RedisKv(k, v));
        }
        return out;
    }

    private static String tagValue(List<Tag> tags, String key) {
        for (Tag t : tags) {
            if (key.equals(t.getKey())) return t.getValue();
        }
        return null;
    }

    private static Double clampPercent(Double v) {
        if (v == null) return null;
        if (v.isNaN() || v.isInfinite()) return null;
        return Math.max(0d, Math.min(100d, v));
    }

    private static <T> T safe(SupplierWithException<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get() throws Exception;
    }
}

