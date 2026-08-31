// [AGENT] 역할: aggTrade Redis 큐 적재 + DB 배치 flush 서비스 | 연관파일: AggTradeStreamService.java(→enqueue), AggTradeFlushScheduler.java(→doFlush), AggTradeConfigService.java, AggTradeBackfillService.java, AggTradeCollectStatusRepository.java
// 핵심흐름: enqueue(json) → Redis SETNX dedup → RPUSH 큐 → 임계치 초과 시 flush | doFlush() → 리더만 실행, LPOP batch → JSON→Entity → JDBC batchInsert(ON DUPLICATE KEY) → checkpoint·collect_status 갱신
// 큐 경고: 60% info, 90% error (Telegram), 100% 차단
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.RawAggTrade;
import com.chs.springboot.domain.binance.model.AggTradeCollectStatus;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.global.redis.LeaderElectionService;
import com.chs.springboot.global.telegram.TelegramLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class AggTradeStorageService {

    private static final Logger log = LoggerFactory.getLogger(AggTradeStorageService.class);

    private static final String QUEUE_KEY = "aggtrade:queue";
    private static final String DEDUP_KEY_PREFIX = "dedup:aggtrade:";

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate batchJdbcTemplate;
    private final AggTradeConfigService configService;
    private final LeaderElectionService leaderElectionService;
    private final AggTradeBackfillService backfillService;
    private final AggTradeCollectStatusRepository statusRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${binance.agg-trade.save.enabled:false}")
    private boolean aggTradeSaveEnabled;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    private final ScheduledExecutorService flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aggtrade-flush");
        t.setDaemon(false);
        return t;
    });

    public AggTradeStorageService(StringRedisTemplate redisTemplate,
                                  JdbcTemplate jdbcTemplate,
                                  AggTradeConfigService configService,
                                  LeaderElectionService leaderElectionService,
                                  AggTradeBackfillService backfillService,
                                  AggTradeCollectStatusRepository statusRepository) {
        this.redisTemplate = redisTemplate;
        this.batchJdbcTemplate = jdbcTemplate;
        this.configService = configService;
        this.leaderElectionService = leaderElectionService;
        this.backfillService = backfillService;
        this.statusRepository = statusRepository;
    }

    public ScheduledExecutorService getFlushExecutor() {
        return flushExecutor;
    }

    private boolean isLocalProfile() {
        return "local".equalsIgnoreCase(activeProfile);
    }

    /**
     * WebSocket 수신 시 호출 — Redis SETNX dedup 후 큐에 적재.
     */
    public void enqueue(String json, String symbol, String marketType) {
        if (!aggTradeSaveEnabled) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            long aggTradeId = node.get("a").asLong();

            // ENAUSDT FUTURES 수신 추적 로그 (DEBUG)
            if ("ENAUSDT".equals(symbol) && "FUTURES".equals(marketType)) {
                log.debug("[AggTradeEnqueueDebug] ENAUSDT FUTURES enqueue 시도 aggId={}", aggTradeId);
            }

            String dedupKey = DEDUP_KEY_PREFIX + symbol + ":" + marketType + ":" + aggTradeId;
            int dedupTtl = configService.getDedupTtlSec();
            Boolean added = redisTemplate.opsForValue()
                    .setIfAbsent(dedupKey, "1", Duration.ofSeconds(dedupTtl));
            if (!Boolean.TRUE.equals(added)) {
                if ("ENAUSDT".equals(symbol) && "FUTURES".equals(marketType)) {
                    log.debug("[AggTradeEnqueueDebug] DEDUP HIT ENAUSDT FUTURES aggId={}", aggTradeId);
                }
                return;
            }

            int maxQueueSize = configService.getMaxQueueSize();
            Long currentSize = redisTemplate.opsForList().size(QUEUE_KEY);
            if (currentSize != null && currentSize >= maxQueueSize) {
                TelegramLog.error("[AggTrade] 큐 오버플로우 — enqueue 차단, size=" + currentSize
                        + ", symbol=" + symbol + ", marketType=" + marketType + ", aggId=" + aggTradeId);
                return;
            }

            String value = objectMapper.writeValueAsString(new QueueItem(symbol, marketType, json));
            Long newSize = redisTemplate.opsForList().rightPush(QUEUE_KEY, value);
            if (newSize == null) {
                return;
            }

            // ENAUSDT FUTURES 큐 사이즈 추적 로그 (DEBUG)
            if ("ENAUSDT".equals(symbol) && "FUTURES".equals(marketType)) {
                log.debug("[AggTradeEnqueueDebug] ENAUSDT FUTURES queued aggId={} queueSize={}", aggTradeId, newSize);
            }

            int warning60 = (int) (maxQueueSize * 0.6);
            int warning90 = (int) (maxQueueSize * 0.9);
            if (newSize >= warning90) {
                TelegramLog.error("[AggTrade] 큐 90% 경고 — size=" + newSize);
            } else if (newSize >= warning60) {
                TelegramLog.info("[AggTrade] 큐 60% 경고 — size=" + newSize);
            }

            int flushThreshold = configService.getFlushThreshold();
            if (newSize >= flushThreshold) {
                flushExecutor.submit(this::doFlush);
                backfillService.rescheduleMinutes(4);
            }
        } catch (Exception e) {
            log.error("[AggTrade] enqueue 실패: {}", e.getMessage());
        }
    }

    /**
     * 단일 스레드 executor에서만 실행되는 flush 로직.
     */
    public void doFlush() {
        if (!aggTradeSaveEnabled || !leaderElectionService.isLeader() || isLocalProfile()) {
            return;
        }
        try {
            int batchSize = configService.getBatchSize();
            while (true) {
                List<String> values = redisTemplate.opsForList().leftPop(QUEUE_KEY, batchSize);
                if (values == null || values.isEmpty()) {
                    break;
                }
                List<RawAggTrade> entities = new ArrayList<>(values.size());
                for (String value : values) {
                    try {
                        QueueItem item = objectMapper.readValue(value, QueueItem.class);
                        RawAggTrade trade = parseToEntity(item);
                        if (trade != null) {
                            entities.add(trade);
                        }
                    } catch (Exception ex) {
                        log.warn("[AggTrade] 파싱 스킵: {}", ex.getMessage());
                    }
                }
                if (!entities.isEmpty()) {
                    // ENAUSDT FUTURES 배치 범위 추적 로그 (DEBUG)
                    var enaFutures = entities.stream()
                            .filter(e -> "ENAUSDT".equals(e.getSymbol()) && "FUTURES".equals(e.getMarketType()))
                            .toList();
                    if (!enaFutures.isEmpty()) {
                        long minId = enaFutures.stream().mapToLong(RawAggTrade::getAggTradeId).min().orElse(-1L);
                        long maxId = enaFutures.stream().mapToLong(RawAggTrade::getAggTradeId).max().orElse(-1L);
                        log.debug("[AggTradeFlushDebug] ENAUSDT FUTURES batch size={} aggIdRange={}~{}",
                                enaFutures.size(), minId, maxId);
                    }

                    long startMs = System.currentTimeMillis();
                    try {
                        batchInsert(entities, startMs);
                        // DB insert 성공 시에만 checkpoint 업데이트
                        updateCheckpoints(entities);

                        Long remain = redisTemplate.opsForList().size(QUEUE_KEY);
                        long remainCount = remain != null ? remain : -1L;

                        var bySymbolMarket = entities.stream()
                                .collect(java.util.stream.Collectors.groupingBy(
                                        e -> e.getSymbol() + "/" + e.getMarketType(),
                                        java.util.stream.Collectors.counting()
                                ));

                        log.info("[AggTradeFlush] 배치 insert 완료: {}건, 큐 잔여={}, bySymbolMarket={}",
                                entities.size(), remainCount, bySymbolMarket);
                    } catch (Exception ex) {
                        long elapsed = System.currentTimeMillis() - startMs;
                        if (ex instanceof CannotGetJdbcConnectionException) {
                            log.error("[AggTrade] 배치 insert 실패: {}건, {}ms, checkpoint 업데이트 스킵 error={}", entities.size(), elapsed, ex.getMessage());
                        }else{
                            log.error("[AggTrade] 배치 insert 실패: {}건, {}ms, checkpoint 업데이트 스킵", entities.size(), elapsed, ex);
                        }
                        TelegramLog.error("[AggTrade] 배치 insert 실패: " + entities.size() + "건, " + elapsed + "ms, checkpoint 업데이트 스킵");
                    }
                }
                if (values.size() < batchSize) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("[AggTrade] doFlush 실패: {}", e.getMessage());
        }
    }

    private RawAggTrade parseToEntity(QueueItem item) throws Exception {
        JsonNode node = objectMapper.readTree(item.json());
        RawAggTrade trade = new RawAggTrade();
        trade.setSymbol(item.symbol());
        trade.setMarketType(item.marketType());
        trade.setAggTradeId(node.get("a").asLong());
        trade.setPrice(new BigDecimal(node.get("p").asText()));
        trade.setQuantity(new BigDecimal(node.get("q").asText()));
        trade.setFirstTradeId(node.get("f").asLong());
        trade.setLastTradeId(node.get("l").asLong());
        trade.setIsBuyerMaker(node.get("m").asBoolean());
        trade.setTradedAt(node.get("T").asLong());
        return trade;
    }

    private void updateCheckpoints(List<RawAggTrade> entities) {
        entities.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        e -> e.getSymbol() + ":" + e.getMarketType(),
                        java.util.stream.Collectors.mapping(RawAggTrade::getAggTradeId, java.util.stream.Collectors.maxBy(Long::compareTo))
                ))
                .forEach((key, maxIdOpt) -> maxIdOpt.ifPresent(maxId -> {
                    String[] parts = key.split(":");
                    if (parts.length != 2) return;
                    String symbol = parts[0];
                    String marketType = parts[1];
                    String checkpointKey = "aggtrade:checkpoint:" + symbol + ":" + marketType;

                    if ("ENAUSDT".equals(symbol) && "FUTURES".equals(marketType)) {
                        log.debug("[AggTradeCheckpointDebug] ENAUSDT FUTURES checkpoint update aggId={}", maxId);
                    }

                    setIfGreater(checkpointKey, maxId);

                    try {
                        AggTradeCollectStatus status = statusRepository
                                .findBySymbolAndMarketType(symbol, marketType)
                                .orElseGet(() -> {
                                    AggTradeCollectStatus s = new AggTradeCollectStatus();
                                    s.setSymbol(symbol);
                                    s.setMarketType(marketType);
                                    s.setEnabled(Boolean.TRUE);
                                    s.setBackfillIntervalMin(1);
                                    return s;
                                });
                        Long currentLastStream = status.getLastStreamAggId();
                        if (currentLastStream == null || maxId > currentLastStream) {
                            status.setLastStreamAggId(maxId);
                        }
                        statusRepository.save(status);
                    } catch (Exception ex) {
                        log.warn("[AggTrade] collect_status 업데이트 실패 symbol={} marketType={} error={}",
                                symbol, marketType, ex.getMessage());
                    }
                }));
    }

    private void setIfGreater(String key, Long newValue) {
        try {
            String current = redisTemplate.opsForValue().get(key);
            if (current == null) {
                redisTemplate.opsForValue().set(key, String.valueOf(newValue));
                return;
            }
            long currentVal = Long.parseLong(current);
            if (newValue > currentVal) {
                redisTemplate.opsForValue().set(key, String.valueOf(newValue));
            }
        } catch (Exception e) {
            log.warn("[AggTrade] checkpoint 갱신 실패: {}", e.getMessage());
        }
    }

    private void batchInsert(List<RawAggTrade> entities, long startMs) {
        String sql = "INSERT IGNORE INTO raw_agg_trade " +
                "(symbol, market_type, agg_trade_id, price, quantity, first_trade_id, last_trade_id, is_buyer_maker, traded_at, saved_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6)) ";

        batchJdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                RawAggTrade t = entities.get(i);
                ps.setString(1, t.getSymbol());
                ps.setString(2, t.getMarketType());
                ps.setLong(3, t.getAggTradeId());
                ps.setBigDecimal(4, t.getPrice());
                ps.setBigDecimal(5, t.getQuantity());
                ps.setLong(6, t.getFirstTradeId());
                ps.setLong(7, t.getLastTradeId());
                ps.setBoolean(8, Boolean.TRUE.equals(t.getIsBuyerMaker()));
                ps.setLong(9, t.getTradedAt());
            }

            @Override
            public int getBatchSize() {
                return entities.size();
            }
        });
    }

    @PreDestroy
    public void destroy() {
        flushExecutor.shutdown();
        try {
            if (!flushExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                flushExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            flushExecutor.shutdownNow();
        }
    }

    private record QueueItem(String symbol, String marketType, String json) {
    }
}

