// [AGENT] 역할: 수동 수집/보정 서비스 — 비동기 Job 관리, 타입별 Binance REST 호출, FUTURES flat/outlier 캔들 보정 | 연관파일: ManualBackfillController.java
// 지원 타입: AGG_1M/5M(fromMs~toMs rollup), KLINE_1M(임시 1m add-only), OI(REST 호출), flat-correction(1m klines + 5m 재롤업), outlier-correction(raw 기준 1m/5m 재생성)
// 재발방지: raw 존재 1분은 kline fallback 저장 제외, id-zero/kline-like 1m/5m 기존 row는 raw/1s 기반 rollup으로 교체
// Job 상태: RUNNING → DONE | ERROR / ConcurrentHashMap 저장 (앱 재시작 시 초기화)
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.global.chs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ManualBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ManualBackfillService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long OUTLIER_MAX_RANGE_MS = 48L * 60L * 60L * 1000L;
    private static final int DIAGNOSTIC_ROW_LIMIT = 200;
    private static final String REPLACE_BAD_CANDLE_UPDATE_SQL = """
        open_price         = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(open_price),         open_price),
        high_price         = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(high_price),         high_price),
        low_price          = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(low_price),          low_price),
        close_price        = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(close_price),        close_price),
        vwap               = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(vwap),               vwap),
        buy_volume         = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(buy_volume),         buy_volume),
        sell_volume        = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(sell_volume),        sell_volume),
        total_volume       = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(total_volume),       total_volume),
        buy_quantity       = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(buy_quantity),       buy_quantity),
        sell_quantity      = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(sell_quantity),      sell_quantity),
        delta              = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(delta),              delta),
        buy_trade_count    = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(buy_trade_count),    buy_trade_count),
        sell_trade_count   = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(sell_trade_count),   sell_trade_count),
        trade_count        = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(trade_count),        trade_count),
        min_agg_trade_id   = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(min_agg_trade_id),   min_agg_trade_id),
        max_agg_trade_id   = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(max_agg_trade_id),   max_agg_trade_id),
        min_first_trade_id = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(min_first_trade_id), min_first_trade_id),
        max_last_trade_id  = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(max_last_trade_id),  max_last_trade_id)
        """;

    private final JdbcTemplate batchJdbcTemplate;
    private final BinanceKlineTempSyncService klineTempSyncService;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "manual-backfill");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, JobStatus> jobs = new ConcurrentHashMap<>();

    @Value("${binance.rest.spot.base-url:https://api.binance.com}")
    private String spotBaseUrl;

    @Value("${binance.rest.futures.base-url:https://fapi.binance.com}")
    private String futuresBaseUrl;

    // JdbcTemplate 중에서 이름이 batchJdbcTemplate인 Bean을 주입해달라는 요청
    public ManualBackfillService(
            JdbcTemplate batchJdbcTemplate,
            BinanceKlineTempSyncService klineTempSyncService) {
        this.batchJdbcTemplate = batchJdbcTemplate;
        this.klineTempSyncService = klineTempSyncService;
    }

    // ─── Job Record ──────────────────────────────────────────────────────────

    public record JobStatus(
        String jobId,
        String type,
        String symbol,
        String marketType,
        String status,      // RUNNING | DONE | ERROR
        String message,
        long   startedAt,
        Long   finishedAt,
        int    inserted
    ) {}

    // ─── Job Start ───────────────────────────────────────────────────────────

    public String startCollect(String type, String symbol, String marketType,
                               Long fromId, Long toId, Long fromMs, Long toMs) {
        if ("RAW_AGG_TRADE".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("raw_agg_trade 백필은 중단되었습니다. KLINE_1M 백필을 사용하세요");
        }
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis();
        jobs.put(jobId, new JobStatus(jobId, type, symbol, marketType, "RUNNING", null, now, null, 0));

        CompletableFuture.runAsync(() -> {
            try {
                int inserted = switch (type.toUpperCase()) {
                    case "AGG_1M"        -> collectRollup1m(symbol, marketType, fromMs, toMs);
                    case "AGG_5M"        -> collectRollup5m(symbol, marketType, fromMs, toMs);
                    case "KLINE_1M"      -> collectKline1m(symbol, marketType, fromMs, toMs);
                    case "OI"            -> collectOi(symbol, fromMs, toMs);
                    default -> throw new IllegalArgumentException("Unknown type: " + type);
                };
                JobStatus prev = jobs.get(jobId);
                jobs.put(jobId, new JobStatus(jobId, type, symbol, marketType, "DONE", null,
                        prev.startedAt(), System.currentTimeMillis(), inserted));
                log.info("[ManualBackfill] {} {} {} 완료, {}건", type, symbol, marketType, inserted);
            } catch (Exception e) {
                log.error("[ManualBackfill] {} {} {} 실패: {}", type, symbol, marketType, e.getMessage(), e);
                JobStatus prev = jobs.get(jobId);
                jobs.put(jobId, new JobStatus(jobId, type, symbol, marketType, "ERROR", e.getMessage(),
                        prev.startedAt(), System.currentTimeMillis(), 0));
            }
        }, executor);

        return jobId;
    }

    private int collectKline1m(String symbol, String marketType, Long fromMs, Long toMs) {
        if (fromMs == null || toMs == null) {
            throw new IllegalArgumentException("KLINE_1M은 fromMs와 toMs가 필수입니다");
        }
        BinanceKlineRangeFetcher.validateBoundedRange(fromMs, toMs);
        long safeEnd = BinanceKlineWindow.safeEnd(System.currentTimeMillis());
        if (toMs > safeEnd) {
            throw new IllegalArgumentException("KLINE_1M 종료 시각은 현재 시각보다 2분 이전이어야 합니다");
        }
        BinanceKlineTempSyncService.RangeSyncResult result = klineTempSyncService
                .rangeSync(symbol, marketType, fromMs, toMs);
        if (result.skippedInFlight()) {
            throw new IllegalStateException("같은 심볼/마켓의 KLINE_1M 동기화가 이미 실행 중입니다");
        }
        if (result.firstPageEmpty()) {
            throw new IllegalStateException("KLINE_1M Binance 응답이 비어 있어 백필하지 못했습니다");
        }
        return result.inserted();
    }

    public JobStatus getStatus(String jobId) {
        return jobs.get(jobId);
    }

    public List<JobStatus> getAllJobs() {
        return jobs.values().stream()
            .sorted(Comparator.comparingLong(JobStatus::startedAt).reversed())
            .toList();
    }

    // ─── AGG_1M rollup ───────────────────────────────────────────────────────

    /**
     * 1. agg_trade_1s 있는 구간 → SQL rollup (정확한 데이터)
     * 2. 이후 여전히 빈 1분봉 구간 → Binance klines로 채움 (ON DUPLICATE KEY → 1s 결과 덮어쓰지 않음)
     */
    private int collectRollup1m(String symbol, String marketType, Long fromMs, Long toMs) throws Exception {
        int total = 0;

        // 1. agg_trade_1s 데이터 있으면 rollup
        Long s1Count = batchJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM agg_trade_1s WHERE symbol=? AND market_type=? AND candle_time_ms >= ? AND candle_time_ms < ?",
            Long.class, symbol, marketType, fromMs, toMs
        );
        if (s1Count != null && s1Count > 0) {
            chs.dlog("collectRollup1m agg_trade_1s 기반 1m rollup 결과 준비");
            chs.dlog("collectRollup1m 기존 1m row의 min_agg_trade_id 또는 min_first_trade_id가 0인지 확인");
            chs.dlog("collectRollup1m 기존 1m row가 buy_trade_count 0 sell_trade_count 0 trade_count 양수인지 확인");
            chs.dlog("collectRollup1m 기존 1m row가 id-zero 또는 kline-like이면 raw 기반 rollup 값으로 교체");
            chs.dlog("collectRollup1m 정상 raw 기반 기존 row는 유지");
            String sql = """
                INSERT INTO agg_trade_1m
                    (symbol, market_type, candle_time_ms,
                     open_price, high_price, low_price, close_price, vwap,
                     buy_volume, sell_volume, total_volume,
                     buy_quantity, sell_quantity, delta,
                     buy_trade_count, sell_trade_count, trade_count,
                     min_agg_trade_id, max_agg_trade_id,
                     min_first_trade_id, max_last_trade_id)
                SELECT
                    symbol, market_type,
                    FLOOR(candle_time_ms / 60000) * 60000                                                     AS candle_time_ms,
                    SUBSTRING_INDEX(MIN(CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price)),'|',-1)           AS open_price,
                    MAX(high_price)                                                                           AS high_price,
                    MIN(low_price)                                                                            AS low_price,
                    SUBSTRING_INDEX(MAX(CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price)),'|',-1)         AS close_price,
                    CASE WHEN SUM(buy_quantity + sell_quantity) = 0 THEN 0
                         ELSE SUM(total_volume) / SUM(buy_quantity + sell_quantity) END                       AS vwap,
                    SUM(buy_volume)                                                                           AS buy_volume,
                    SUM(sell_volume)                                                                          AS sell_volume,
                    SUM(total_volume)                                                                         AS total_volume,
                    SUM(buy_quantity)                                                                         AS buy_quantity,
                    SUM(sell_quantity)                                                                        AS sell_quantity,
                    SUM(buy_quantity) - SUM(sell_quantity)                                                    AS delta,
                    SUM(buy_trade_count)                                                                      AS buy_trade_count,
                    SUM(sell_trade_count)                                                                     AS sell_trade_count,
                    SUM(trade_count)                                                                          AS trade_count,
                    MIN(min_agg_trade_id)                                                                     AS min_agg_trade_id,
                    MAX(max_agg_trade_id)                                                                     AS max_agg_trade_id,
                    MIN(min_first_trade_id)                                                                   AS min_first_trade_id,
                    MAX(max_last_trade_id)                                                                    AS max_last_trade_id
                FROM agg_trade_1s
                WHERE symbol = ? AND market_type = ? AND candle_time_ms >= ? AND candle_time_ms < ?
                GROUP BY symbol, market_type, FLOOR(candle_time_ms / 60000) * 60000
                ON DUPLICATE KEY UPDATE
                    open_price         = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(open_price),         open_price),
                    high_price         = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(high_price),         high_price),
                    low_price          = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(low_price),          low_price),
                    close_price        = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(close_price),        close_price),
                    vwap               = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(vwap),               vwap),
                    buy_volume         = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(buy_volume),         buy_volume),
                    sell_volume        = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(sell_volume),        sell_volume),
                    total_volume       = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(total_volume),       total_volume),
                    buy_quantity       = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(buy_quantity),       buy_quantity),
                    sell_quantity      = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(sell_quantity),      sell_quantity),
                    delta              = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(delta),              delta),
                    buy_trade_count    = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(buy_trade_count),    buy_trade_count),
                    sell_trade_count   = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(sell_trade_count),   sell_trade_count),
                    trade_count        = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(trade_count),        trade_count),
                    min_agg_trade_id   = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(min_agg_trade_id),   min_agg_trade_id),
                    max_agg_trade_id   = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(max_agg_trade_id),   max_agg_trade_id),
                    min_first_trade_id = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(min_first_trade_id), min_first_trade_id),
                    max_last_trade_id  = IF(min_agg_trade_id = 0 OR min_first_trade_id = 0 OR (buy_trade_count = 0 AND sell_trade_count = 0 AND trade_count > 0), VALUES(max_last_trade_id),  max_last_trade_id)
                """;
            int s1Inserted = batchJdbcTemplate.update(sql, symbol, marketType, fromMs, toMs);
            log.info("[ManualBackfill] 1m {} {} 1s rollup {}건", symbol, marketType, s1Inserted);
            total += s1Inserted;
        } else {
            log.info("[ManualBackfill] 1m {} {} 1s 없음 → klines 단독 수집", symbol, marketType);
        }

        // 2. 여전히 빈 구간 → klines로 채움 (raw 결과는 ON DUPLICATE KEY로 보호)
        int klinesInserted = fillMissing1mWithKlines(symbol, marketType, fromMs, toMs);
        log.info("[ManualBackfill] 1m {} {} klines fill {}건", symbol, marketType, klinesInserted);
        total += klinesInserted;

        return total;
    }

    /** klines API로 1분봉 채우기 — raw rollup으로 이미 채워진 분은 ON DUPLICATE KEY로 스킵 */
    private int fillMissing1mWithKlines(String symbol, String marketType, long fromMs, long toMs) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = "SPOT".equals(marketType) ? spotBaseUrl : futuresBaseUrl;
        String path = "SPOT".equals(marketType) ? "/api/v3/klines" : "/fapi/v1/klines";
        int LIMIT = 1500;
        int total = 0;
        long startMs = fromMs;

        chs.dlog("fillMissing1mWithKlines 대상 1분에 raw_agg_trade가 존재하는지 확인");
        chs.dlog("fillMissing1mWithKlines raw가 존재하는 1분은 kline fallback 저장 대상에서 제외");
        chs.dlog("fillMissing1mWithKlines raw가 없는 1분만 kline fallback row 저장 허용");
        chs.dlog("fillMissing1mWithKlines kline fallback row는 id-zero와 buy/sell count 0 특성을 유지하여 raw row와 구분");
        String insertSql = """
            INSERT INTO agg_trade_1m
            (symbol, market_type, candle_time_ms,
             open_price, high_price, low_price, close_price, vwap,
             buy_volume, sell_volume, total_volume,
             buy_quantity, sell_quantity, delta,
             buy_trade_count, sell_trade_count, trade_count,
             min_agg_trade_id, max_agg_trade_id, min_first_trade_id, max_last_trade_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            """ + REPLACE_BAD_CANDLE_UPDATE_SQL;

        while (startMs < toMs) {
            String url = base + path + "?symbol=" + symbol + "&interval=1m"
                    + "&startTime=" + startMs + "&endTime=" + (toMs - 1) + "&limit=" + LIMIT;
            HttpResponse<String> response = httpGet(client, url);

            if (response.statusCode() != 200) {
                throw new IllegalStateException("klines HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode array = mapper.readTree(response.body());
            if (!array.isArray() || array.isEmpty()) break;

            List<Object[]> batch = new ArrayList<>();
            long lastOpenTime = startMs;

            for (JsonNode k : array) {
                long      openTime  = k.get(0).asLong();
                BigDecimal open     = new BigDecimal(k.get(1).asText());
                BigDecimal high     = new BigDecimal(k.get(2).asText());
                BigDecimal low      = new BigDecimal(k.get(3).asText());
                BigDecimal close    = new BigDecimal(k.get(4).asText());
                BigDecimal baseVol  = new BigDecimal(k.get(5).asText());  // base asset qty
                BigDecimal totalVol = new BigDecimal(k.get(7).asText());  // quote asset vol
                long       trades   = k.get(8).asLong();
                BigDecimal buyQty   = new BigDecimal(k.get(9).asText());  // taker buy base
                BigDecimal buyVol   = new BigDecimal(k.get(10).asText()); // taker buy quote
                BigDecimal sellQty  = baseVol.subtract(buyQty);
                BigDecimal sellVol  = totalVol.subtract(buyVol);
                BigDecimal vwap     = baseVol.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : totalVol.divide(baseVol, 8, java.math.RoundingMode.HALF_UP);

                if (hasRawAggTrade(symbol, marketType, openTime, openTime + 60_000L)) {
                    if (openTime > lastOpenTime) lastOpenTime = openTime;
                    continue;
                }

                batch.add(new Object[]{
                    symbol, marketType, openTime,
                    open, high, low, close, vwap,
                    buyVol, sellVol, totalVol,
                    buyQty, sellQty, buyQty.subtract(sellQty),
                    0L, 0L, trades,   // buy/sell_trade_count 불가 → 0
                    0L, 0L, 0L, 0L    // agg_trade_id 관련 불가 → 0
                });
                if (openTime > lastOpenTime) lastOpenTime = openTime;
            }

            if (!batch.isEmpty()) {
                int[] results = batchJdbcTemplate.batchUpdate(insertSql, batch);
                for (int v : results) if (v == 1) total++;
            }

            if (array.size() < LIMIT) break;
            startMs = lastOpenTime + 60_000L;
        }

        return total;
    }

    private boolean hasRawAggTrade(String symbol, String marketType, long fromMs, long toMs) {
        Long rawCount = batchJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM raw_agg_trade
            WHERE symbol = ? AND market_type = ? AND traded_at >= ? AND traded_at < ?
            """,
            Long.class, symbol, marketType, fromMs, toMs
        );
        return rawCount != null && rawCount > 0;
    }

    // ─── AGG_5M rollup ───────────────────────────────────────────────────────

    private int collectRollup5m(String symbol, String marketType, Long fromMs, Long toMs) {
        Long oneM = batchJdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM agg_trade_1m WHERE symbol=? AND market_type=? AND candle_time_ms >= ? AND candle_time_ms < ?",
            Long.class, symbol, marketType, fromMs, toMs
        );
        if (oneM == null || oneM == 0) {
            throw new IllegalStateException("agg_trade_1m 선수집 필요: 해당 구간에 1분봉 데이터 없음 (" + symbol + " " + marketType + ")");
        }

        String sql = """
            INSERT INTO agg_trade_5m
                (symbol, market_type, candle_time_ms,
                 open_price, high_price, low_price, close_price, vwap,
                 buy_volume, sell_volume, total_volume,
                 buy_quantity, sell_quantity, delta,
                 buy_trade_count, sell_trade_count, trade_count,
                 min_agg_trade_id, max_agg_trade_id,
                 min_first_trade_id, max_last_trade_id)
            SELECT
                symbol, market_type,
                FLOOR(candle_time_ms / 300000) * 300000                                                       AS candle_time_ms,
                SUBSTRING_INDEX(MIN(CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price)),'|',-1)               AS open_price,
                MAX(high_price)                                                                               AS high_price,
                MIN(low_price)                                                                                AS low_price,
                SUBSTRING_INDEX(MAX(CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price)),'|',-1)             AS close_price,
                CASE WHEN SUM(buy_quantity + sell_quantity) = 0 THEN 0
                     ELSE SUM(total_volume) / SUM(buy_quantity + sell_quantity) END                           AS vwap,
                SUM(buy_volume)                                                                               AS buy_volume,
                SUM(sell_volume)                                                                              AS sell_volume,
                SUM(total_volume)                                                                             AS total_volume,
                SUM(buy_quantity)                                                                             AS buy_quantity,
                SUM(sell_quantity)                                                                            AS sell_quantity,
                SUM(buy_quantity) - SUM(sell_quantity)                                                       AS delta,
                SUM(buy_trade_count)                                                                         AS buy_trade_count,
                SUM(sell_trade_count)                                                                        AS sell_trade_count,
                SUM(trade_count)                                                                             AS trade_count,
                MIN(min_agg_trade_id)                                                                        AS min_agg_trade_id,
                MAX(max_agg_trade_id)                                                                        AS max_agg_trade_id,
                MIN(min_first_trade_id)                                                                      AS min_first_trade_id,
                MAX(max_last_trade_id)                                                                       AS max_last_trade_id
            FROM agg_trade_1m
            WHERE symbol = ? AND market_type = ? AND candle_time_ms >= ? AND candle_time_ms < ?
            GROUP BY symbol, market_type, FLOOR(candle_time_ms / 300000) * 300000
            ON DUPLICATE KEY UPDATE
            """ + REPLACE_BAD_CANDLE_UPDATE_SQL;
        return batchJdbcTemplate.update(sql, symbol, marketType, fromMs, toMs);
    }

    // ─── OI ──────────────────────────────────────────────────────────────────

    private int collectOi(String symbol, Long fromMs, Long toMs) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        long startMs  = fromMs != null ? fromMs : (System.currentTimeMillis() - 7 * 86_400_000L);
        long endMs    = toMs   != null ? toMs   : System.currentTimeMillis();
        long PERIOD   = 300_000L;
        int  LIMIT    = 500;
        int  total    = 0;

        while (startMs < endMs) {
            String url = "https://fapi.binance.com/futures/data/openInterestHist"
                    + "?symbol=" + symbol + "&period=5m&limit=" + LIMIT
                    + "&startTime=" + startMs + "&endTime=" + endMs;
            HttpResponse<String> response = httpGet(client, url);
            log.info("[ManualBackfill] OI {} startMs={} status={}", symbol, startMs, response.statusCode());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode array = mapper.readTree(response.body());
            if (!array.isArray() || array.isEmpty()) break;

            long lastTs = startMs;
            List<Object[]> batch = new ArrayList<>();

            for (JsonNode node : array) {
                long ts = node.get("timestamp").asLong();
                batch.add(new Object[]{
                    symbol,
                    new BigDecimal(node.get("sumOpenInterest").asText()),
                    new BigDecimal(node.get("sumOpenInterestValue").asText()),
                    null,
                    ts
                });
                if (ts > lastTs) lastTs = ts;
            }

            String sql = """
                INSERT INTO open_interest (symbol, open_interest, oi_value, price, collected_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE price = COALESCE(price, VALUES(price))
                """;
            batchJdbcTemplate.batchUpdate(sql, batch);
            total += batch.size();

            if (array.size() < LIMIT) break;
            startMs = lastTs + PERIOD;
        }

        return total;
    }

    // ─── Delete Flat ─────────────────────────────────────────────────────────

    public Map<String, Object> deleteFlatData(String symbol, String marketType, String tableKey, Long fromMs, Long toMs) {
        String table = switch (tableKey) {
            case "1s" -> "agg_trade_1s";
            case "1m" -> "agg_trade_1m";
            case "5m" -> "agg_trade_5m";
            default   -> throw new IllegalArgumentException("지원하지 않는 테이블: " + tableKey);
        };

        String rangeClause = (fromMs != null && toMs != null)
                ? "AND candle_time_ms >= ? AND candle_time_ms < ?"
                : "";

        List<Object> params = new java.util.ArrayList<>();
        params.add(symbol);
        params.add(marketType);
        if (fromMs != null && toMs != null) {
            params.add(fromMs);
            params.add(toMs);
        }

        int deleted = batchJdbcTemplate.update("""
                DELETE FROM %s
                WHERE symbol = ? AND market_type = ?
                  AND trade_count = 0
                  %s
                """.formatted(table, rangeClause), params.toArray());

        String message = deleted == 0 ? "이미 교정됨" : deleted + "건 삭제";
        return Map.of("deleted", deleted, "message", message);
    }

    // ─── Data Health ─────────────────────────────────────────────────────────

    public Map<String, Object> getDataHealth(String symbol, String marketType, long fromMs, long toMs) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();

        // raw에 거래가 있는데 1s가 flat(trade_count=0)인 진짜 불일치만 카운트
        // raw_agg_trade는 오래된 데이터가 삭제될 수 있으므로 지정 범위 내만 체크
        // IN + MATERIALIZED 방식: raw를 한 번만 범위 스캔 후 hash 매칭 (correlated EXISTS 대비 대폭 개선)
        String mismatchSql = """
            SELECT
                COUNT(*)                                      AS flat_count,
                FROM_UNIXTIME(MIN(s.candle_time_ms) / 1000)  AS flat_from,
                FROM_UNIXTIME(MAX(s.candle_time_ms) / 1000)  AS flat_to
            FROM agg_trade_1s s
            WHERE s.symbol = ? AND s.market_type = ?
              AND s.trade_count = 0
              AND s.candle_time_ms >= ? AND s.candle_time_ms < ?
              AND s.candle_time_ms IN (
                  SELECT FLOOR(traded_at / 1000) * 1000
                  FROM raw_agg_trade
                  WHERE symbol = ? AND market_type = ?
                    AND traded_at >= ? AND traded_at < ?
              )
            """;

        result.put("mismatch1s", batchJdbcTemplate.queryForMap(mismatchSql,
            symbol, marketType, fromMs, toMs,
            symbol, marketType, fromMs, toMs));

        return result;
    }

    // ─── FUTURES 장애 구간 보정 ───────────────────────────────────────────

    public Map<String, Object> getFlatCorrectionHealth(String symbol, String marketType, long fromMs, long toMs) {
        chs.dlog("admin 보정 진단 요청 파라미터 확인");
        validateCorrectionRequest(symbol, marketType, fromMs, toMs);
        chs.dlog("symbol과 marketType 필수값 검증");
        chs.dlog("fromMs와 toMs 시간 범위 검증");
        chs.dlog("raw_agg_trade 존재 구간 카운트 조회");
        Map<String, Object> raw = summarizeRawAggTrade(symbol, marketType, fromMs, toMs);
        chs.dlog("agg_trade_1s trade_count 0 구간 카운트 조회");
        Map<String, Object> flat1s = summarizeFlatCandles("agg_trade_1s", symbol, marketType, fromMs, toMs);
        chs.dlog("agg_trade_1m trade_count 0 구간 카운트 조회");
        Map<String, Object> flat1m = summarizeFlatCandles("agg_trade_1m", symbol, marketType, fromMs, toMs);
        chs.dlog("agg_trade_5m trade_count 0 구간 카운트 조회");
        Map<String, Object> flat5m = summarizeFlatCandles("agg_trade_5m", symbol, marketType, fromMs, toMs);
        chs.dlog("raw 존재 여부와 flat 캔들 현황을 함께 응답에 담기");
        long now = System.currentTimeMillis();
        boolean rawRestRecoverable = "FUTURES".equals(marketType) && fromMs >= now - 86_400_000L;

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("marketType", marketType);
        result.put("fromMs", fromMs);
        result.put("toMs", toMs);
        result.put("rawAggTrade", raw);
        result.put("flat1s", flat1s);
        result.put("flat1m", flat1m);
        result.put("flat5m", flat5m);
        chs.dlog("flat 대상 row 목록 조회");
        List<Map<String, Object>> flat1sRows = findFlatCandleRows("agg_trade_1s", symbol, marketType, fromMs, toMs, DIAGNOSTIC_ROW_LIMIT);
        List<Map<String, Object>> flat1mRows = findFlatCandleRows("agg_trade_1m", symbol, marketType, fromMs, toMs, DIAGNOSTIC_ROW_LIMIT);
        List<Map<String, Object>> flat5mRows = findFlatCandleRows("agg_trade_5m", symbol, marketType, fromMs, toMs, DIAGNOSTIC_ROW_LIMIT);
        chs.dlog("flat1s flat1m flat5m 별 대상 candle_time_ms 목록 조회");
        result.put("flat1sRows", flat1sRows);
        result.put("flat1mRows", flat1mRows);
        result.put("flat5mRows", flat5mRows);
        chs.dlog("admin 화면에서 요약 count뿐 아니라 실제 대상 시간대를 확인 가능하게 반환");
        result.put("rawRestRecoverable", rawRestRecoverable);
        result.put("correctionMode", "KLINES_1M_AND_ROLLUP_5M");
        result.put("message", rawRestRecoverable
                ? "최근 24시간 구간은 raw aggTrade REST 복구 검토 가능"
                : "24시간 초과 FUTURES 구간은 raw aggTrade REST 복구 대신 kline 기반 1m/5m 보정");
        chs.dlog("getFlatCorrectionHealth 반환 - admin 화면에서 보정 가능 범위와 위험 구간 표시");
        return result;
    }

    public Map<String, Object> correctFlatCandles(String symbol, String marketType, long fromMs, long toMs) throws Exception {
        chs.dlog("admin 보정 실행 요청 파라미터 확인");
        validateCorrectionRequest(symbol, marketType, fromMs, toMs);
        if (!"FUTURES".equals(marketType)) {
            throw new IllegalArgumentException("FUTURES 보정만 지원합니다.");
        }
        chs.dlog("FUTURES 과거 raw aggTrade REST 복구 가능 기간인지 확인");
        chs.dlog("raw 복구 불가 구간은 klines 기반 1m 표시용 보정 대상으로 분리");
        chs.dlog("agg_trade_1m flat row만 삭제 대상 산정");
        chs.dlog("1m flat이 포함된 5m row 재생성 대상 시간 산정");
        List<Long> impacted5mTimes = findFlatImpacted5mTimes(symbol, marketType, fromMs, toMs);
        chs.dlog("Binance klines로 1m 캔들 보정 실행");
        Map<String, Object> oneM = rebuildFlat1mWithKlines(symbol, marketType, fromMs, toMs);
        chs.dlog("agg_trade_5m flat row와 1m flat 영향 5m row를 삭제 대상 산정");
        chs.dlog("보정된 1m 기준으로 5m 캔들 재생성 실행");
        Map<String, Object> fiveM = rebuildFlat5mFrom1m(symbol, marketType, fromMs, toMs, impacted5mTimes);
        chs.dlog("정상 trade_count가 있는 1m/5m row는 삭제하지 않음");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("marketType", marketType);
        result.put("fromMs", fromMs);
        result.put("toMs", toMs);
        result.put("oneMinute", oneM);
        result.put("fiveMinute", fiveM);
        result.put("health", getFlatCorrectionHealth(symbol, marketType, fromMs, toMs));
        chs.dlog("correctFlatCandles 반환 - 삭제건수와 재생성건수를 admin 화면에 표시");
        return result;
    }

    private Map<String, Object> rebuildFlat1mWithKlines(String symbol, String marketType, long fromMs, long toMs) throws Exception {
        chs.dlog("1m 보정 대상 범위 검증");
        validateCorrectionRequest(symbol, marketType, fromMs, toMs);
        chs.dlog("agg_trade_1m에서 trade_count 0인 row만 삭제");
        int deleted = deleteFlatRows("agg_trade_1m", symbol, marketType, fromMs, toMs);
        chs.dlog("Binance klines 요청 범위를 1500개 단위로 분할");
        chs.dlog("kline 응답으로 open high low close volume vwap 계산");
        chs.dlog("정상 1m 캔들은 유지하고 비어있는 1m만 삽입");
        int inserted = fillMissing1mWithKlines(symbol, marketType, fromMs, toMs);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("deletedFlat", deleted);
        result.put("inserted", inserted);
        chs.dlog("rebuildFlat1mWithKlines 반환 - 1m 삭제건수와 삽입건수");
        return result;
    }

    private Map<String, Object> rebuildFlat5mFrom1m(String symbol, String marketType, long fromMs, long toMs,
                                                    List<Long> impacted5mTimes) {
        chs.dlog("5m 보정 대상 범위를 5분 경계로 정렬");
        validateCorrectionRequest(symbol, marketType, fromMs, toMs);
        long from5m = (fromMs / 300_000L) * 300_000L;
        long to5m = ((toMs + 299_999L) / 300_000L) * 300_000L;
        chs.dlog("agg_trade_5m에서 trade_count 0인 row만 삭제");
        int deleted = deleteFlatRows("agg_trade_5m", symbol, marketType, from5m, to5m);
        int deletedImpacted = 0;
        int insertedImpacted = 0;
        chs.dlog("1m flat 영향 5m row를 삭제 후 재삽입");
        for (Long candleTimeMs : impacted5mTimes) {
            deletedImpacted += deleteOneMinuteCandle("agg_trade_5m", symbol, marketType, candleTimeMs);
            insertedImpacted += insertFiveMinuteFrom1m(symbol, marketType, candleTimeMs);
        }
        chs.dlog("보정 완료된 agg_trade_1m 데이터를 5분 단위로 집계");
        chs.dlog("정상 5m 캔들은 유지하고 삭제된 5m만 삽입");
        int inserted = collectRollup5m(symbol, marketType, from5m, to5m);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("deletedFlat", deleted);
        result.put("deletedImpacted", deletedImpacted);
        result.put("inserted", inserted);
        result.put("insertedImpacted", insertedImpacted);
        result.put("impacted5mCount", impacted5mTimes.size());
        result.put("impacted5mTimes", impacted5mTimes);
        result.put("fromMs", from5m);
        result.put("toMs", to5m);
        chs.dlog("rebuildFlat5mFrom1m 반환 - 5m 삭제건수와 삽입건수");
        return result;
    }

    private void validateCorrectionRequest(String symbol, String marketType, long fromMs, long toMs) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol 필수");
        }
        if (marketType == null || marketType.isBlank()) {
            throw new IllegalArgumentException("marketType 필수");
        }
        if (fromMs <= 0 || toMs <= 0) {
            throw new IllegalArgumentException("fromMs, toMs 필수");
        }
        if (fromMs >= toMs) {
            throw new IllegalArgumentException("fromMs는 toMs보다 작아야 합니다.");
        }
    }

    private Map<String, Object> summarizeRawAggTrade(String symbol, String marketType, long fromMs, long toMs) {
        String sql = """
            SELECT
                COUNT(*) AS row_count,
                MIN(traded_at) AS min_ms,
                MAX(traded_at) AS max_ms
            FROM raw_agg_trade
            WHERE symbol = ? AND market_type = ?
              AND traded_at >= ? AND traded_at < ?
            """;
        return batchJdbcTemplate.queryForMap(sql, symbol, marketType, fromMs, toMs);
    }

    private Map<String, Object> summarizeFlatCandles(String table, String symbol, String marketType, long fromMs, long toMs) {
        String sql = """
            SELECT
                COUNT(*) AS flat_count,
                MIN(candle_time_ms) AS min_ms,
                MAX(candle_time_ms) AS max_ms
            FROM %s
            WHERE symbol = ? AND market_type = ?
              AND candle_time_ms >= ? AND candle_time_ms < ?
              AND trade_count = 0
            """.formatted(table);
        return batchJdbcTemplate.queryForMap(sql, symbol, marketType, fromMs, toMs);
    }

    private List<Long> findFlatImpacted5mTimes(String symbol, String marketType, long fromMs, long toMs) {
        chs.dlog("1m flat row가 포함된 5m candle_time_ms 목록 조회");
        String sql = """
            SELECT DISTINCT FLOOR(candle_time_ms / 300000) * 300000 AS candle_time_ms
            FROM agg_trade_1m
            WHERE symbol = ? AND market_type = ?
              AND candle_time_ms >= ? AND candle_time_ms < ?
              AND trade_count = 0
            ORDER BY candle_time_ms ASC
            """;
        return batchJdbcTemplate.queryForList(sql, Long.class, symbol, marketType, fromMs, toMs);
    }

    private List<Map<String, Object>> findFlatCandleRows(String table, String symbol, String marketType,
                                                         long fromMs, long toMs, int limit) {
        String sql = """
            SELECT
                candle_time_ms,
                FROM_UNIXTIME(candle_time_ms / 1000) AS candle_time,
                open_price,
                high_price,
                low_price,
                close_price,
                total_volume,
                trade_count
            FROM %s
            WHERE symbol = ? AND market_type = ?
              AND candle_time_ms >= ? AND candle_time_ms < ?
              AND trade_count = 0
            ORDER BY candle_time_ms ASC
            LIMIT ?
            """.formatted(table);
        return batchJdbcTemplate.queryForList(sql, symbol, marketType, fromMs, toMs, limit);
    }

    private int deleteFlatRows(String table, String symbol, String marketType, long fromMs, long toMs) {
        String sql = """
            DELETE FROM %s
            WHERE symbol = ? AND market_type = ?
              AND candle_time_ms >= ? AND candle_time_ms < ?
              AND trade_count = 0
            """.formatted(table);
        return batchJdbcTemplate.update(sql, symbol, marketType, fromMs, toMs);
    }

    public Map<String, Object> getOutlierCorrectionHealth(String symbol, String marketType, long fromMs, long toMs) {
        chs.dlog("admin outlier 보정 진단 요청 파라미터 확인");
        validateCorrectionRequest(symbol, marketType, fromMs, toMs);
        validateOutlierRange(fromMs, toMs);
        chs.dlog("symbol marketType fromMs toMs 필수값 검증");
        chs.dlog("raw_agg_trade를 1분 단위로 집계해서 raw min max open close 계산");
        chs.dlog("agg_trade_1m의 open high low close와 raw 가격 범위 비교");
        chs.dlog("agg_trade_1m의 min_agg_trade_id 또는 min_first_trade_id가 0인 혼합 row 정보를 함께 조회");
        chs.dlog("raw 범위 밖 OHLC를 가진 1m row를 outlier 후보로 표시");
        chs.dlog("mixed row 탐지 조건 적용");
        chs.dlog("min_agg_trade_id 또는 min_first_trade_id가 0인 1m row 확인");
        chs.dlog("raw가 해당 1분에 일부 존재하는지 확인");
        chs.dlog("agg OHLC와 raw OHLC가 서로 다른지 확인");
        chs.dlog("mixed 후보를 outlier 보정 대상으로 포함");
        chs.dlog("outlier 후보별 reason 값을 계산");
        chs.dlog("reason PRICE_OUT_OF_RAW_RANGE는 agg OHLC가 raw high low 범위를 벗어난 경우");
        chs.dlog("reason OPEN_CLOSE_MISMATCH는 agg open close가 raw open close와 다른 경우");
        chs.dlog("reason ID_ZERO는 agg_trade_id 또는 first_trade_id가 0으로 저장된 경우");
        chs.dlog("reason KLINE_LIKE_ROW는 trade_count는 있으나 buy sell trade count가 0인 경우");
        List<Map<String, Object>> outlierRows = findOutlier1mRows(symbol, marketType, fromMs, toMs);
        chs.dlog("outlier 1m이 포함된 5m candle_time_ms 목록 계산");
        Set<Long> impacted5m = collectImpacted5mTimes(outlierRows);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("marketType", marketType);
        result.put("fromMs", fromMs);
        result.put("toMs", toMs);
        result.put("outlier1mCount", outlierRows.size());
        result.put("impacted5mCount", impacted5m.size());
        result.put("rows", outlierRows);
        result.put("impacted5m", impacted5m);
        chs.dlog("outlier reason별 건수 summary를 응답에 포함");
        result.put("reasonSummary", summarizeOutlierReasons(outlierRows));
        chs.dlog("getOutlierCorrectionHealth 반환 - admin 화면에서 outlier 후보와 영향 5m 표시");
        return result;
    }

    public Map<String, Object> correctOutlierCandles(String symbol, String marketType, long fromMs, long toMs) {
        chs.dlog("admin outlier 보정 실행 요청 파라미터 확인");
        validateCorrectionRequest(symbol, marketType, fromMs, toMs);
        validateOutlierRange(fromMs, toMs);
        chs.dlog("FUTURES 보정 대상인지 검증");
        if (!"FUTURES".equals(marketType)) {
            throw new IllegalArgumentException("FUTURES 보정만 지원합니다.");
        }
        chs.dlog("raw 데이터가 존재하는 1m 범위만 보정 대상으로 제한");
        chs.dlog("raw 가격 범위와 불일치하거나 mixed row인 1m row 목록 조회");
        List<Map<String, Object>> outlierRows = findOutlier1mRows(symbol, marketType, fromMs, toMs);
        chs.dlog("오염 1m row 삭제");
        chs.dlog("raw_agg_trade 기준으로 1m row 재생성");
        Map<String, Object> oneM = rebuildOutlier1mFromRaw(symbol, marketType, outlierRows);
        chs.dlog("오염 1m이 포함된 5m row 삭제");
        chs.dlog("재생성된 1m 기준으로 5m row 재생성");
        Map<String, Object> fiveM = rebuildImpacted5mFrom1m(symbol, marketType, outlierRows);
        chs.dlog("정상 범위 1m/5m row는 삭제하지 않음");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("marketType", marketType);
        result.put("fromMs", fromMs);
        result.put("toMs", toMs);
        result.put("oneMinute", oneM);
        result.put("fiveMinute", fiveM);
        chs.dlog("보정 결과에 요청 fromMs toMs와 대상 1m count를 summary로 포함");
        chs.dlog("보정 결과에 대상 5m count와 targetTimes를 summary로 포함");
        result.put("summary", Map.of(
                "fromMs", fromMs,
                "toMs", toMs,
                "targetOneMinuteCount", outlierRows.size(),
                "targetFiveMinuteCount", collectImpacted5mTimes(outlierRows).size()
        ));
        result.put("health", getOutlierCorrectionHealth(symbol, marketType, fromMs, toMs));
        chs.dlog("correctOutlierCandles 반환 - 1m/5m 삭제건수와 재생성건수 표시");
        return result;
    }

    private List<Map<String, Object>> findOutlier1mRows(String symbol, String marketType, long fromMs, long toMs) {
        chs.dlog("raw 기준 outlier 1m 후보 조회");
        chs.dlog("raw_agg_trade를 1분 단위로 그룹핑");
        chs.dlog("raw_min raw_max raw_open raw_close raw_trade_count 계산");
        chs.dlog("agg_trade_1m과 candle_time_ms로 조인");
        chs.dlog("agg high가 raw_max보다 크거나 agg low가 raw_min보다 작은 row 탐지");
        chs.dlog("min_agg_trade_id 또는 min_first_trade_id가 0인 row 정보도 응답에 포함");
        chs.dlog("mixed row 조건 조회");
        chs.dlog("raw_trade_count가 0보다 크고 agg id가 0으로 섞인 경우");
        chs.dlog("agg open high low close 중 하나라도 raw open high low close와 다른 경우");
        chs.dlog("SELECT 절에 reason 계산 컬럼 추가");
        chs.dlog("PRICE_OUT_OF_RAW_RANGE 조건을 reason에 포함");
        chs.dlog("OPEN_CLOSE_MISMATCH 조건을 reason에 포함");
        chs.dlog("ID_ZERO 조건을 reason에 포함");
        chs.dlog("KLINE_LIKE_ROW 조건을 reason에 포함");
        chs.dlog("복수 reason은 쉼표로 합쳐 admin에서 그대로 표시");
        String sql = """
            WITH raw_ranked AS (
                SELECT
                    FLOOR(traded_at / 60000) * 60000 AS candle_time_ms,
                    price,
                    traded_at,
                    agg_trade_id,
                    ROW_NUMBER() OVER (
                        PARTITION BY FLOOR(traded_at / 60000) * 60000
                        ORDER BY traded_at ASC, agg_trade_id ASC
                    ) AS rn_first,
                    ROW_NUMBER() OVER (
                        PARTITION BY FLOOR(traded_at / 60000) * 60000
                        ORDER BY traded_at DESC, agg_trade_id DESC
                    ) AS rn_last
                FROM raw_agg_trade
                WHERE symbol = ? AND market_type = ?
                  AND traded_at >= ? AND traded_at < ?
            ),
            raw_1m AS (
                SELECT
                    candle_time_ms,
                    MAX(CASE WHEN rn_first = 1 THEN price END) AS raw_open,
                    MAX(price) AS raw_high,
                    MIN(price) AS raw_low,
                    MAX(CASE WHEN rn_last = 1 THEN price END) AS raw_close,
                    COUNT(*) AS raw_trade_count
                FROM raw_ranked
                GROUP BY candle_time_ms
            )
            SELECT
                a.candle_time_ms,
                FROM_UNIXTIME(a.candle_time_ms / 1000) AS candle_time,
                a.open_price,
                a.high_price,
                a.low_price,
                a.close_price,
                a.trade_count,
                a.buy_trade_count,
                a.sell_trade_count,
                a.min_agg_trade_id,
                a.max_agg_trade_id,
                a.min_first_trade_id,
                a.max_last_trade_id,
                r.raw_open,
                r.raw_high,
                r.raw_low,
                r.raw_close,
                r.raw_trade_count,
                GREATEST(
                    ABS(a.open_price - r.raw_open),
                    ABS(a.high_price - r.raw_high),
                    ABS(a.low_price - r.raw_low),
                    ABS(a.close_price - r.raw_close)
                ) AS max_price_diff,
                CONCAT_WS(',',
                    CASE WHEN
                        a.open_price > r.raw_high OR a.open_price < r.raw_low OR
                        a.high_price > r.raw_high OR a.high_price < r.raw_low OR
                        a.low_price > r.raw_high OR a.low_price < r.raw_low OR
                        a.close_price > r.raw_high OR a.close_price < r.raw_low
                    THEN 'PRICE_OUT_OF_RAW_RANGE' END,
                    CASE WHEN
                        a.open_price <> r.raw_open OR
                        a.close_price <> r.raw_close
                    THEN 'OPEN_CLOSE_MISMATCH' END,
                    CASE WHEN
                        a.min_agg_trade_id = 0 OR a.min_first_trade_id = 0
                    THEN 'ID_ZERO' END,
                    CASE WHEN
                        a.buy_trade_count = 0 AND a.sell_trade_count = 0 AND a.trade_count > 0
                    THEN 'KLINE_LIKE_ROW' END
                ) AS reason
            FROM agg_trade_1m a
            JOIN raw_1m r ON r.candle_time_ms = a.candle_time_ms
            WHERE a.symbol = ? AND a.market_type = ?
              AND a.candle_time_ms >= ? AND a.candle_time_ms < ?
              AND (
                  a.open_price > r.raw_high OR a.open_price < r.raw_low OR
                  a.high_price > r.raw_high OR a.high_price < r.raw_low OR
                  a.low_price > r.raw_high OR a.low_price < r.raw_low OR
                  a.close_price > r.raw_high OR a.close_price < r.raw_low OR
                  (
                      (a.min_agg_trade_id = 0 OR a.min_first_trade_id = 0)
                      AND r.raw_trade_count > 0
                      AND (
                          a.open_price <> r.raw_open OR
                          a.high_price <> r.raw_high OR
                          a.low_price <> r.raw_low OR
                          a.close_price <> r.raw_close
                      )
                  ) OR
                  (
                      a.buy_trade_count = 0
                      AND a.sell_trade_count = 0
                      AND a.trade_count > 0
                      AND r.raw_trade_count > 0
                  )
              )
            ORDER BY a.candle_time_ms ASC
            """;
        chs.dlog("findOutlier1mRows 반환 - 보정 대상 1m 목록");
        return batchJdbcTemplate.queryForList(sql, symbol, marketType, fromMs, toMs, symbol, marketType, fromMs, toMs);
    }

    private void validateOutlierRange(long fromMs, long toMs) {
        chs.dlog("outlier 진단/보정 단일 요청 범위를 48시간 이하로 제한");
        if (toMs - fromMs > OUTLIER_MAX_RANGE_MS) {
            throw new IllegalArgumentException("Outlier 보정 범위는 한 번에 최대 48시간까지만 지원합니다.");
        }
    }

    private Map<String, Object> rebuildOutlier1mFromRaw(String symbol, String marketType, List<Map<String, Object>> rows) {
        chs.dlog("outlier 1m 재생성 대상 목록 확인");
        if (rows.isEmpty()) {
            return Map.of("deleted", 0, "inserted", 0);
        }
        int deleted = 0;
        int inserted = 0;
        chs.dlog("대상 candle_time_ms를 순회");
        for (Map<String, Object> row : rows) {
            long candleTimeMs = ((Number) row.get("candle_time_ms")).longValue();
            chs.dlog("agg_trade_1m에서 해당 1m row 삭제");
            deleted += deleteOneMinuteCandle("agg_trade_1m", symbol, marketType, candleTimeMs);
            chs.dlog("raw_agg_trade에서 해당 1분 범위를 OHLCV로 집계");
            chs.dlog("raw 집계 결과로 agg_trade_1m row 삽입");
            inserted += insertOneMinuteFromRaw(symbol, marketType, candleTimeMs);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("inserted", inserted);
        result.put("targetCount", rows.size());
        chs.dlog("rebuildOutlier1mFromRaw 반환 - 삭제건수와 삽입건수");
        return result;
    }

    private Map<String, Object> rebuildImpacted5mFrom1m(String symbol, String marketType, List<Map<String, Object>> rows) {
        chs.dlog("outlier 1m이 포함된 5m candle_time_ms 산정");
        Set<Long> impacted5m = collectImpacted5mTimes(rows);
        if (impacted5m.isEmpty()) {
            return Map.of("deleted", 0, "inserted", 0);
        }
        int deleted = 0;
        int inserted = 0;
        for (Long candleTimeMs : impacted5m) {
            chs.dlog("agg_trade_5m에서 영향받은 5m row 삭제");
            deleted += deleteOneMinuteCandle("agg_trade_5m", symbol, marketType, candleTimeMs);
            chs.dlog("agg_trade_1m에서 해당 5분 범위를 OHLCV로 집계");
            chs.dlog("집계 결과로 agg_trade_5m row 삽입");
            inserted += insertFiveMinuteFrom1m(symbol, marketType, candleTimeMs);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("inserted", inserted);
        result.put("targetCount", impacted5m.size());
        result.put("targetTimes", impacted5m);
        chs.dlog("rebuildImpacted5mFrom1m 반환 - 삭제건수와 삽입건수");
        return result;
    }

    private Set<Long> collectImpacted5mTimes(List<Map<String, Object>> rows) {
        Set<Long> result = new java.util.TreeSet<>();
        for (Map<String, Object> row : rows) {
            long candleTimeMs = ((Number) row.get("candle_time_ms")).longValue();
            long fiveMinuteMs = (candleTimeMs / 300_000L) * 300_000L;
            result.add(fiveMinuteMs);
        }
        return result;
    }

    private Map<String, Integer> summarizeOutlierReasons(List<Map<String, Object>> rows) {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get("reason");
            if (value == null) {
                continue;
            }
            String reasonText = value.toString();
            if (reasonText.isBlank()) {
                continue;
            }
            String[] reasons = reasonText.split(",");
            for (String reason : reasons) {
                String key = reason.trim();
                if (key.isEmpty()) {
                    continue;
                }
                result.put(key, result.getOrDefault(key, 0) + 1);
            }
        }
        return result;
    }

    private int deleteOneMinuteCandle(String table, String symbol, String marketType, long candleTimeMs) {
        String sql = """
            DELETE FROM %s
            WHERE symbol = ? AND market_type = ? AND candle_time_ms = ?
            """.formatted(table);
        return batchJdbcTemplate.update(sql, symbol, marketType, candleTimeMs);
    }

    private int insertOneMinuteFromRaw(String symbol, String marketType, long candleTimeMs) {
        String sql = """
            INSERT INTO agg_trade_1m
                (symbol, market_type, candle_time_ms,
                 open_price, high_price, low_price, close_price, vwap,
                 buy_volume, sell_volume, total_volume,
                 buy_quantity, sell_quantity, delta,
                 buy_trade_count, sell_trade_count, trade_count,
                 min_agg_trade_id, max_agg_trade_id,
                 min_first_trade_id, max_last_trade_id)
            SELECT
                ? AS symbol,
                ? AS market_type,
                ? AS candle_time_ms,
                MAX(CASE WHEN rn_first = 1 THEN price END) AS open_price,
                MAX(price) AS high_price,
                MIN(price) AS low_price,
                MAX(CASE WHEN rn_last = 1 THEN price END) AS close_price,
                CASE WHEN SUM(quantity) = 0 THEN 0 ELSE SUM(price * quantity) / SUM(quantity) END AS vwap,
                SUM(CASE WHEN is_buyer_maker = 0 THEN price * quantity ELSE 0 END) AS buy_volume,
                SUM(CASE WHEN is_buyer_maker = 1 THEN price * quantity ELSE 0 END) AS sell_volume,
                SUM(price * quantity) AS total_volume,
                SUM(CASE WHEN is_buyer_maker = 0 THEN quantity ELSE 0 END) AS buy_quantity,
                SUM(CASE WHEN is_buyer_maker = 1 THEN quantity ELSE 0 END) AS sell_quantity,
                SUM(CASE WHEN is_buyer_maker = 0 THEN quantity ELSE 0 END)
                    - SUM(CASE WHEN is_buyer_maker = 1 THEN quantity ELSE 0 END) AS delta,
                SUM(CASE WHEN is_buyer_maker = 0 THEN 1 ELSE 0 END) AS buy_trade_count,
                SUM(CASE WHEN is_buyer_maker = 1 THEN 1 ELSE 0 END) AS sell_trade_count,
                COUNT(*) AS trade_count,
                MIN(agg_trade_id) AS min_agg_trade_id,
                MAX(agg_trade_id) AS max_agg_trade_id,
                MIN(first_trade_id) AS min_first_trade_id,
                MAX(last_trade_id) AS max_last_trade_id
            FROM (
                SELECT
                    price,
                    quantity,
                    is_buyer_maker,
                    traded_at,
                    agg_trade_id,
                    first_trade_id,
                    last_trade_id,
                    ROW_NUMBER() OVER (ORDER BY traded_at ASC, agg_trade_id ASC) AS rn_first,
                    ROW_NUMBER() OVER (ORDER BY traded_at DESC, agg_trade_id DESC) AS rn_last
                FROM raw_agg_trade
                WHERE symbol = ? AND market_type = ?
                  AND traded_at >= ? AND traded_at < ?
            ) raw_ranked
            HAVING COUNT(*) > 0
            """;
        long endMs = candleTimeMs + 60_000L;
        return batchJdbcTemplate.update(sql,
                symbol, marketType, candleTimeMs,
                symbol, marketType, candleTimeMs, endMs);
    }

    private int insertFiveMinuteFrom1m(String symbol, String marketType, long candleTimeMs) {
        chs.dlog("insertFiveMinuteFrom1m 삭제된 5m 구간을 1m 재집계 값으로 삽입");
        chs.dlog("insertFiveMinuteFrom1m 정상 1m 구간만 5m로 집계");
        String sql = """
            INSERT INTO agg_trade_5m
                (symbol, market_type, candle_time_ms,
                 open_price, high_price, low_price, close_price, vwap,
                 buy_volume, sell_volume, total_volume,
                 buy_quantity, sell_quantity, delta,
                 buy_trade_count, sell_trade_count, trade_count,
                 min_agg_trade_id, max_agg_trade_id,
                 min_first_trade_id, max_last_trade_id)
            SELECT
                symbol, market_type,
                ? AS candle_time_ms,
                SUBSTRING_INDEX(MIN(CONCAT(LPAD(candle_time_ms,20,'0'),'|',open_price)),'|',-1) AS open_price,
                MAX(high_price) AS high_price,
                MIN(low_price) AS low_price,
                SUBSTRING_INDEX(MAX(CONCAT(LPAD(candle_time_ms,20,'0'),'|',close_price)),'|',-1) AS close_price,
                CASE WHEN SUM(buy_quantity + sell_quantity) = 0 THEN 0
                     ELSE SUM(total_volume) / SUM(buy_quantity + sell_quantity) END AS vwap,
                SUM(buy_volume) AS buy_volume,
                SUM(sell_volume) AS sell_volume,
                SUM(total_volume) AS total_volume,
                SUM(buy_quantity) AS buy_quantity,
                SUM(sell_quantity) AS sell_quantity,
                SUM(buy_quantity) - SUM(sell_quantity) AS delta,
                SUM(buy_trade_count) AS buy_trade_count,
                SUM(sell_trade_count) AS sell_trade_count,
                SUM(trade_count) AS trade_count,
                MIN(min_agg_trade_id) AS min_agg_trade_id,
                MAX(max_agg_trade_id) AS max_agg_trade_id,
                MIN(min_first_trade_id) AS min_first_trade_id,
                MAX(max_last_trade_id) AS max_last_trade_id
            FROM agg_trade_1m
            WHERE symbol = ? AND market_type = ?
              AND candle_time_ms >= ? AND candle_time_ms < ?
            GROUP BY symbol, market_type
            """;
        long endMs = candleTimeMs + 300_000L;
        return batchJdbcTemplate.update(sql, candleTimeMs, symbol, marketType, candleTimeMs, endMs);
    }

    // ─── HTTP util ───────────────────────────────────────────────────────────

    private HttpResponse<String> httpGet(HttpClient client, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
