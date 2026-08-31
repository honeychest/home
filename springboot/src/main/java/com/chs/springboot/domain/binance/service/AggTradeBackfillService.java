// [AGENT] 역할: aggTrade 과거 데이터 백필 서비스 (Binance REST → DB) | 연관파일: AggTradeCollectStatusRepository.java, AggTradeConfigService.java, LeaderElectionService.java, AggTradeStorageService.java(→rescheduleMinutes 호출받음)
// 핵심흐름: @PostConstruct → 스케줄 예약 → runBackfill() Redis 분산락(2분 TTL + heartbeat 30초) → executeBackfill() 심볼별 루프 → backfillOne() REST /aggTrades?fromId=&limit=1000 반복, JDBC batchInsert, collect_status 갱신
// 대상: BTCUSDT SPOT, ENAUSDT SPOT/FUTURES | weight 90% 도달 시 60초 후 재시도 | rescheduleMinutes()로 flush 부하 시 4분 연기
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTradeCollectStatus;
import com.chs.springboot.domain.binance.model.RawAggTrade;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.global.redis.LeaderElectionService;
import com.chs.springboot.global.telegram.TelegramLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
public class AggTradeBackfillService {

    private static final Logger log = LoggerFactory.getLogger(AggTradeBackfillService.class);

    private static final String BACKFILL_LOCK_KEY = "aggtrade:backfill:lock";

    private final StringRedisTemplate redisTemplate;
    private final AggTradeConfigService configService;
    private final LeaderElectionService leaderElectionService;
    private final AggTradeCollectStatusRepository statusRepository;
    private final JdbcTemplate batchJdbcTemplate;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(new CustomizableThreadFactory("aggtrade-backfill-"));
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(new CustomizableThreadFactory("aggtrade-backfill-heartbeat-"));

    private ScheduledFuture<?> scheduledFuture;

    // application.properties 에서 설정된 값을 주입 없으면 :값이 기본값으로 설정된다.
    @Value("${binance.rest.spot.base-url:https://api.binance.com}")
    private String binanceSpotBaseUrl;

    @Value("${binance.rest.futures.base-url:https://fapi.binance.com}")
    private String binanceFuturesBaseUrl;

    @Value("${binance.agg-trade.save.enabled:false}")
    private boolean aggTradeSaveEnabled;

    public AggTradeBackfillService(StringRedisTemplate redisTemplate,
                                   AggTradeConfigService configService,
                                   LeaderElectionService leaderElectionService,
                                   AggTradeCollectStatusRepository statusRepository,
                                   JdbcTemplate batchJdbcTemplate) {
        this.redisTemplate = redisTemplate;
        this.configService = configService;
        this.leaderElectionService = leaderElectionService;
        this.statusRepository = statusRepository;
        this.batchJdbcTemplate = batchJdbcTemplate;
    }

    @PostConstruct
    public void init() {
        if (!aggTradeSaveEnabled) {
            log.info("[AggTradeBackfill] raw_agg_trade 백필 비활성화 상태, 스케줄 등록 생략");
            return;
        }
        scheduleBackfillSeconds(0);
    }

    public synchronized void rescheduleMinutes(int minutes) {
        if (!aggTradeSaveEnabled) {
            return;
        }
        scheduleBackfillSeconds(minutes * 60L);
    }

    private synchronized void scheduleBackfillSeconds(long delaySec) {
        if (!aggTradeSaveEnabled) {
            return;
        }
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        scheduledFuture = scheduler.schedule(this::runBackfillSafely, delaySec, TimeUnit.SECONDS);
        log.info("[AggTradeBackfill] {}초 후 Backfill 예약", delaySec);
    }

    private void runBackfillSafely() {
        try {
            if (!aggTradeSaveEnabled) {
                log.info("[AggTradeBackfill] 기능 비활성화 상태, 종료");
                return;
            }
            runBackfill();
        } catch (Exception e) {
            log.error("[AggTradeBackfill] 실행 실패: {}", e.getMessage());
        }
    }

    private void runBackfill() {
        try {
            boolean locked = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(BACKFILL_LOCK_KEY, leaderElectionService.getServerName(), Duration.ofMinutes(2))
            );
            if (!locked) {
                log.info("[AggTradeBackfill] 다른 서버가 실행 중, 30분 후 재예약");
                scheduleBackfillSeconds(30 * 60L);
                return;
            }

            CountDownLatch latch = new CountDownLatch(1);
            heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    redisTemplate.expire(BACKFILL_LOCK_KEY, Duration.ofMinutes(2));
                } catch (Exception e) {
                    log.warn("[AggTradeBackfill] heartbeat 실패: {}", e.getMessage());
                }
            }, 30, 30, TimeUnit.SECONDS);

            try {
                executeBackfill();
            } finally {
                latch.countDown();
                redisTemplate.delete(BACKFILL_LOCK_KEY);
            }

            scheduleBackfillSeconds(60L);
        } catch (Exception e) {
            log.error("[AggTradeBackfill] 예외 발생: {}", e.getMessage());
            scheduleBackfillSeconds(60L);
        }
    }

    private void executeBackfill() throws Exception {
        List<Map.Entry<String, String>> targets = Arrays.asList(
                Map.entry("BTCUSDT", "SPOT"),
                Map.entry("BTCUSDT", "FUTURES"),
                Map.entry("ENAUSDT", "SPOT"),
                Map.entry("ENAUSDT", "FUTURES")
        );

        for (Map.Entry<String, String> target : targets) {
            String symbol = target.getKey();
            String marketType = target.getValue();
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

                if (!Boolean.TRUE.equals(status.getEnabled())) {
                    continue;
                }

                LocalDateTime now = LocalDateTime.now();
                if (status.getNextBackfillAt() != null && status.getNextBackfillAt().isAfter(now)) {
                    continue;
                }

                backfillOne(status);
                statusRepository.save(status);
            } catch (Exception e) {
                TelegramLog.error("[AggTradeBackfill] " + symbol + " / " + marketType + " 실패: " + e.getMessage());
            }
        }
    }

    private void backfillOne(AggTradeCollectStatus status) throws Exception {
        String symbol = status.getSymbol();
        String marketType = status.getMarketType();

        Long lastBackfill = status.getLastBackfillAggId();
        if (lastBackfill == null) {
            String checkpointKey = "aggtrade:checkpoint:" + symbol + ":" + marketType;
            String checkpoint = redisTemplate.opsForValue().get(checkpointKey);
            if (checkpoint != null) {
                try {
                    lastBackfill = Long.parseLong(checkpoint);
                    status.setLastBackfillAggId(lastBackfill);
                    if (status.getLastStreamAggId() == null) {
                        status.setLastStreamAggId(lastBackfill);
                    }
                } catch (NumberFormatException ignore) {
                    lastBackfill = 0L;
                }
            } else {
                lastBackfill = 0L;
            }
        }

        long lastBackfillIdBefore = lastBackfill;
        long currentFromId = lastBackfillIdBefore > 0 ? lastBackfillIdBefore + 1 : lastBackfillIdBefore;

        HttpClient client = HttpClient.newHttpClient();
        int weightLimit = configService.getWeightPerMinute();

        long globalMaxId = lastBackfillIdBefore;
        long globalMinId = Long.MAX_VALUE;
        int newCandidateCount = 0;
        boolean hitWeightLimit = false;

        LocalDateTime now = LocalDateTime.now();

        while (true) {
            String path;
            String base;
            if ("SPOT".equals(marketType)) {
                base = binanceSpotBaseUrl;
                path = "/api/v3/aggTrades";
            } else { // FUTURES
                base = binanceFuturesBaseUrl;
                path = "/fapi/v1/aggTrades";
            }
            String url = base + path + "?symbol=" + symbol + "&fromId=" + currentFromId + "&limit=1000";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                log.error("[AggTradeBackfill] REST 실패: {} {} fromId={} url={} error={}", symbol, marketType, currentFromId, url, e.getMessage());
                throw e;
            }

            String usedWeightHeader = response.headers().firstValue("X-MBX-USED-WEIGHT-1M").orElse("0");
            int usedWeight = Integer.parseInt(usedWeightHeader);
            log.info("[AggTradeBackfill] {} {} fromId={} status={} usedWeight={}/{}",
                    symbol, marketType, currentFromId, response.statusCode(), usedWeight, weightLimit);

            if (usedWeight >= weightLimit * 0.9) {
                TelegramLog.info("[AggTradeBackfill] weight 90% 초과, symbol=" + symbol +
                        ", marketType=" + marketType +
                        ", fromId=" + currentFromId +
                        ", usedWeight=" + usedWeight + "/" + weightLimit);
                hitWeightLimit = true;
                break;
            }

            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }

            String body = response.body();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode array = mapper.readTree(body);
            if (!array.isArray() || array.isEmpty()) {
                log.info("[AggTradeBackfill] {} {} fromId={} 응답 0건, 종료", symbol, marketType, currentFromId);
                break;
            }
            log.info("[AggTradeBackfill] {} {} fromId={} 응답 {}건", symbol, marketType, currentFromId, array.size());

            List<RawAggTrade> entities = new ArrayList<>();
            long batchMaxId = currentFromId;
            long batchMinId = Long.MAX_VALUE;
            for (JsonNode node : array) {
                RawAggTrade t = new RawAggTrade();
                t.setSymbol(symbol);
                t.setMarketType(marketType);
                long aggId = node.get("a").asLong();
                t.setAggTradeId(aggId);
                t.setPrice(new BigDecimal(node.get("p").asText()));
                t.setQuantity(new BigDecimal(node.get("q").asText()));
                t.setFirstTradeId(node.get("f").asLong());
                t.setLastTradeId(node.get("l").asLong());
                t.setIsBuyerMaker(node.get("m").asBoolean());
                t.setTradedAt(node.get("T").asLong());
                entities.add(t);

                if (aggId > batchMaxId) {
                    batchMaxId = aggId;
                }
                if (aggId < batchMinId) {
                    batchMinId = aggId;
                }
                if (aggId > globalMaxId) {
                    newCandidateCount++;
                    globalMaxId = aggId;
                }
                if (aggId < globalMinId) {
                    globalMinId = aggId;
                }
            }

            if ("ENAUSDT".equals(symbol) && "FUTURES".equals(marketType)) {
                log.debug("[AggTradeBackfillDebug] ENAUSDT FUTURES fromId={} batchSize={} aggIdRange={}~{}",
                        currentFromId, array.size(), batchMinId, batchMaxId);
            }

            String sql = "INSERT IGNORE INTO raw_agg_trade " +
                    "(symbol, market_type, agg_trade_id, price, quantity, first_trade_id, last_trade_id, is_buyer_maker, traded_at, saved_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6)) ";

            var setter = new BatchPreparedStatementSetter() {
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
            };
            try {
                batchJdbcTemplate.batchUpdate(sql, setter);
            } catch (DeadlockLoserDataAccessException e) {
                log.warn("[AggtradeBackfill] 데드락 감지, 500ms 후 재시도 - symbol={} marketType={}", symbol, marketType);
                try {
                    Thread.sleep(500);
                }catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                batchJdbcTemplate.batchUpdate(sql, setter);
            }

            if (array.size() < 1000) {
                break;
            }
            currentFromId = batchMaxId + 1;
        }

        status.setLastBackfillCheckedAt(now);
        if (globalMaxId > lastBackfillIdBefore) {
            status.setLastBackfillAggId(globalMaxId);
        }

        Integer intervalMin = status.getBackfillIntervalMin() != null ? status.getBackfillIntervalMin() : 1;
        if (hitWeightLimit) {
            status.setNextBackfillAt(now.plusSeconds(60));
        } else {
            status.setNextBackfillAt(now.plusMinutes(intervalMin));
        }

        if (newCandidateCount > 0) {
            // 백필로 실제 새 구간을 채웠다는 정보는 애플리케이션 로그에만 남기고,
            // 텔레그램 알림은 전송하지 않는다 (너무 잦은 알림 방지).
            log.info("[AggTradeBackfillFill] {} {} backfillInserted={} idRange={}~{} fromLastBackfill={}",
                    symbol, marketType, newCandidateCount,
                    globalMinId == Long.MAX_VALUE ? "-" : globalMinId,
                    globalMaxId,
                    lastBackfillIdBefore);
        }
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdown();
        heartbeatScheduler.shutdown();
    }
}

