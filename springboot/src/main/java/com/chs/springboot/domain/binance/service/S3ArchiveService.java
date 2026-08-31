// [AGENT] 역할: raw_agg_trade 데이터를 CSV로 변환 → S3 업로드 → DB 삭제 | 연관파일: RawAggTradeRepository.java, S3ArchiveLogRepository.java, S3Config.java, RawAggTradeArchiveScheduler.java
// S3 key: raw_agg_trade/{startMs}_{endMs}.csv (고정키 → 재시작 시 덮어쓰기로 안전)
// 배치 삭제 중 CPU > DELETE_CPU_MAX_PERCENT(90%) 초과 시 중단, 다음 스케줄 주기에 이어서 실행
// uploadAndLog: S3 업로드 + archive_log INSERT(complete='N') — 삭제 없음
// deleteAndComplete: DB 삭제 + archive_log UPDATE(complete='Y')
// archive: uploadAndLog + deleteAndComplete 순서대로 호출 (스케줄러용)
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.RawAggTrade;
import com.chs.springboot.domain.binance.model.S3ArchiveLog;
import com.chs.springboot.domain.binance.repository.RawAggTradeRepository;
import com.chs.springboot.domain.binance.repository.S3ArchiveLogRepository;
import com.chs.springboot.global.chs;
import com.chs.springboot.global.monitor.service.MetricCollectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.StorageClass;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class S3ArchiveService {

    private static final int PAGE_SIZE = 5_000;
    private static final int DELETE_BATCH_SIZE = 5_000;
    private static final int DELETE_INTERVAL_MS = 50;
    private static final double DELETE_CPU_MAX_PERCENT = 90.0;
    private static final int DELETE_CPU_WAIT_MS = 10_000;
    private static final DateTimeFormatter KEY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);

    private final S3Client s3Client;
    private final RawAggTradeRepository rawAggTradeRepository;
    private final S3ArchiveLogRepository s3ArchiveLogRepository;
    private final MetricCollectorService metricCollectorService;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    @Value("${archive.raw-agg-trade.enabled:false}")
    private boolean archiveEnabled;

    public S3ArchiveService(S3Client s3Client,
                            RawAggTradeRepository rawAggTradeRepository,
                            S3ArchiveLogRepository s3ArchiveLogRepository,
                            MetricCollectorService metricCollectorService) {
        this.s3Client = s3Client;
        this.rawAggTradeRepository = rawAggTradeRepository;
        this.s3ArchiveLogRepository = s3ArchiveLogRepository;
        this.metricCollectorService = metricCollectorService;
    }

    /**
     * S3 업로드 + archive_log INSERT (complete='N').
     * 삭제는 하지 않는다. deleteAndComplete() 를 별도 호출해야 함.
     *
     * S3 key가 이미 존재하면 업로드는 스킵하고 archive_log만 INSERT(없을 때만).
     *
     * @param startMs     시작 Unix ms (inclusive)
     * @param endMs       종료 Unix ms (exclusive)
     * @param triggerType 실행 유형 (SCHEDULER / MANUAL / SCANNER)
     * @return 처리 결과
     */
    public UploadResult uploadAndLog(long startMs, long endMs, String triggerType) {
        String rangeLabel = KEY_FORMATTER.format(Instant.ofEpochMilli(startMs))
                + "_" + KEY_FORMATTER.format(Instant.ofEpochMilli(endMs));
        if (!archiveEnabled) {
            log.info("[Archive] raw_agg_trade 아카이빙 비활성화 상태 — 스킵: range={}", rangeLabel);
            return UploadResult.disabled(rangeLabel);
        }
        String s3Key = "raw_agg_trade/" + rangeLabel + ".csv";
        File tempFile = null;

        try {
            // 1. 건수 조회
            long totalCount = rawAggTradeRepository.countByTradedAtRange(startMs, endMs);
            if (totalCount == 0) {
                log.warn("[Archive] 대상 데이터 없음 — 스킵: range={}", rangeLabel);
                return UploadResult.skipped(rangeLabel);
            }

            // 2. S3 업로드
            long fileSize = 0;
            long uploadElapsedMs = 0;
            if (s3KeyExists(s3Key)) {
                log.warn("[Archive] S3 key 이미 존재 — 업로드 스킵: key={}", s3Key);
                // HeadObject로 기존 파일 크기 조회
                fileSize = getS3FileSize(s3Key);
            } else {
                long uploadStart = System.currentTimeMillis();
                tempFile = writeToCsvTempFile(startMs, endMs, rangeLabel);
                fileSize = tempFile.length();

                PutObjectRequest putRequest = PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(s3Key)
                        .storageClass(StorageClass.STANDARD)
                        .build();
                s3Client.putObject(putRequest, RequestBody.fromFile(tempFile));
                uploadElapsedMs = System.currentTimeMillis() - uploadStart;
                log.warn("[Archive] S3 업로드 완료: key={} size={}bytes elapsed={}ms",
                        s3Key, fileSize, uploadElapsedMs);
            }

            // 3. archive_log INSERT (없을 때만 — s3Key 중복 방지)
            if (!s3ArchiveLogRepository.existsByS3Key(s3Key)) {
                LocalDateTime rangeStartDt = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), ZoneOffset.UTC);
                LocalDateTime rangeEndDt   = LocalDateTime.ofInstant(Instant.ofEpochMilli(endMs),   ZoneOffset.UTC);

                S3ArchiveLog archiveLog = new S3ArchiveLog();
                archiveLog.setTableName("raw_agg_trade");
                archiveLog.setS3Key(s3Key);
                archiveLog.setRangeLabel(rangeLabel);
                archiveLog.setRangeStart(rangeStartDt);
                archiveLog.setRangeEnd(rangeEndDt);
                archiveLog.setRowCount(totalCount);
                archiveLog.setFileSizeBytes(fileSize);
                archiveLog.setTriggerType(triggerType);
                archiveLog.setComplete("N");
                chs.dlog("uploadedAt 저장 - ChronoUnit.SECONDS 로 truncate 하여 초 단위로 저장");
                archiveLog.setUploadedAt(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
                s3ArchiveLogRepository.save(archiveLog);
                log.warn("[Archive] archive_log INSERT: key={} rowCount={}", s3Key, totalCount);
            }

            return UploadResult.success(rangeLabel, s3Key, totalCount, fileSize, uploadElapsedMs);

        } catch (Exception e) {
            log.error("[Archive] 업로드 실패: range={} error={}", rangeLabel, e.getMessage());
            return UploadResult.failure(rangeLabel, e.getMessage());

        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * DB 삭제 + archive_log UPDATE complete='Y'.
     * uploadAndLog() 가 완료된 이후에 호출한다.
     *
     * @param startMs 시작 Unix ms (inclusive)
     * @param endMs   종료 Unix ms (exclusive)
     * @param s3Key   archive_log 조회용 S3 key
     * @return 삭제된 행 수
     */
    public int deleteAndComplete(long startMs, long endMs, String s3Key) {
        if (!archiveEnabled) {
            return 0;
        }
        String rangeLabel = s3Key.replace("raw_agg_trade/", "").replace(".csv", "");

        // DB 삭제
        int totalDeleted = deleteInBatches(startMs, endMs, rangeLabel);

        // archive_log UPDATE complete='Y'
        Optional<S3ArchiveLog> logOpt = s3ArchiveLogRepository.findByS3Key(s3Key);
        if (logOpt.isPresent()) {
            S3ArchiveLog archiveLog = logOpt.get();
            archiveLog.setComplete("Y");
            s3ArchiveLogRepository.save(archiveLog);
            log.warn("[Archive] archive_log complete='Y' 업데이트: key={}", s3Key);
        } else {
            log.warn("[Archive] archive_log 레코드 없음 — complete 업데이트 스킵: key={}", s3Key);
        }

        return totalDeleted;
    }

    /**
     * 업로드 + 삭제 전체 실행. 스케줄러 및 수동 실행(전체) 용도.
     *
     * @param startMs     시작 Unix ms (inclusive)
     * @param endMs       종료 Unix ms (exclusive)
     * @param triggerType 실행 유형 (SCHEDULER / MANUAL)
     * @return 처리 결과
     */
    public ArchiveResult archive(long startMs, long endMs, String triggerType) {
        long totalStart = System.currentTimeMillis();

        UploadResult uploadResult = uploadAndLog(startMs, endMs, triggerType);

        if (!uploadResult.success() || uploadResult.skipped()) {
            return ArchiveResult.fromUploadResult(uploadResult);
        }

        long deleteStart = System.currentTimeMillis();
        int totalDeleted = deleteAndComplete(startMs, endMs, uploadResult.s3Key());
        long deleteElapsedMs = System.currentTimeMillis() - deleteStart;

        long totalElapsedMs = System.currentTimeMillis() - totalStart;
        long perRecordMs = uploadResult.totalCount() > 0 ? totalElapsedMs / uploadResult.totalCount() : 0;

        log.warn("[Archive] 완료: range={} count={} total={}ms upload={}ms delete={}ms perRecord={}ms",
                uploadResult.rangeLabel(), totalDeleted, totalElapsedMs,
                uploadResult.uploadElapsedMs(), deleteElapsedMs, perRecordMs);

        return ArchiveResult.success(uploadResult.rangeLabel(), uploadResult.s3Key(),
                uploadResult.totalCount(), totalDeleted, uploadResult.fileSizeBytes(),
                uploadResult.uploadElapsedMs(), deleteElapsedMs, totalElapsedMs, perRecordMs);
    }

    // ---- private ----

    private boolean s3KeyExists(String s3Key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(s3Key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private long getS3FileSize(String s3Key) {
        try {
            return s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(s3Key).build())
                    .contentLength();
        } catch (Exception e) {
            return 0;
        }
    }

    private File writeToCsvTempFile(long startMs, long endMs, String rangeLabel) throws IOException {
        File tempFile = File.createTempFile("raw_agg_trade_" + rangeLabel + "_", ".csv");
        boolean hasData = false;
        long lastId = 0L;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
            writer.write("id,symbol,market_type,agg_trade_id,price,quantity,first_trade_id,last_trade_id,is_buyer_maker,traded_at,saved_at");
            writer.newLine();

            while (true) {
                List<RawAggTrade> page = rawAggTradeRepository.findByTradedAtRangeAfterIdPaged(
                        startMs, endMs, lastId, PAGE_SIZE);
                if (page.isEmpty()) {
                    break;
                }

                hasData = true;
                for (RawAggTrade trade : page) {
                    writer.write(toCsvLine(trade));
                    writer.newLine();
                }

                lastId = page.get(page.size() - 1).getId();
                if (page.size() < PAGE_SIZE) {
                    break;
                }
            }
        }

        if (!hasData) {
            tempFile.delete();
            return null;
        }
        return tempFile;
    }

    private int deleteInBatches(long startMs, long endMs, String rangeLabel) {
        int totalDeleted = 0;

        while (true) {
            double cpu = metricCollectorService.getLastCpu();
            if (cpu >= 0 && cpu >= DELETE_CPU_MAX_PERCENT) {
                log.warn("[Archive] CPU={}% 초과 — {}ms 대기 후 재시도: range={} deletedSoFar={}",
                        String.format("%.1f", cpu), DELETE_CPU_WAIT_MS, rangeLabel, totalDeleted);
                try {
                    Thread.sleep(DELETE_CPU_WAIT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            int deleted = rawAggTradeRepository.deleteByTradedAtRangeBatch(startMs, endMs, DELETE_BATCH_SIZE);
            totalDeleted += deleted;

            if (deleted == 0) {
                break;
            }

            try {
                Thread.sleep(DELETE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.warn("[Archive] DB 삭제 완료: range={} totalDeleted={}", rangeLabel, totalDeleted);
        return totalDeleted;
    }

    private String toCsvLine(RawAggTrade trade) {
        return String.join(",",
                String.valueOf(trade.getId()),
                trade.getSymbol(),
                trade.getMarketType(),
                String.valueOf(trade.getAggTradeId()),
                trade.getPrice().toPlainString(),
                trade.getQuantity().toPlainString(),
                String.valueOf(trade.getFirstTradeId()),
                String.valueOf(trade.getLastTradeId()),
                String.valueOf(trade.getIsBuyerMaker()),
                String.valueOf(trade.getTradedAt()),
                trade.getSavedAt() != null ? trade.getSavedAt().toString() : ""
        );
    }

    // ---- Result records ----

    public record UploadResult(
            String rangeLabel,
            String s3Key,
            long totalCount,
            long fileSizeBytes,
            long uploadElapsedMs,
            boolean success,
            boolean skipped,
            String errorMessage
    ) {
        static UploadResult success(String rangeLabel, String s3Key, long totalCount,
                                    long fileSizeBytes, long uploadElapsedMs) {
            return new UploadResult(rangeLabel, s3Key, totalCount, fileSizeBytes,
                    uploadElapsedMs, true, false, null);
        }

        static UploadResult skipped(String rangeLabel) {
            return new UploadResult(rangeLabel, null, 0, 0, 0, true, true, null);
        }

        static UploadResult disabled(String rangeLabel) {
            return new UploadResult(rangeLabel, null, 0, 0, 0, false, true,
                    "raw_agg_trade 아카이빙이 비활성화되어 실행되지 않았습니다");
        }

        static UploadResult failure(String rangeLabel, String errorMessage) {
            return new UploadResult(rangeLabel, null, 0, 0, 0, false, false, errorMessage);
        }
    }

    public record ArchiveResult(
            String rangeLabel,
            String s3Key,
            long totalCount,
            int deletedCount,
            long fileSizeBytes,
            long uploadElapsedMs,
            long deleteElapsedMs,
            long totalElapsedMs,
            long perRecordMs,
            boolean success,
            boolean skipped,
            String errorMessage
    ) {
        static ArchiveResult success(String rangeLabel, String s3Key, long totalCount, int deletedCount,
                                     long fileSizeBytes, long uploadElapsedMs, long deleteElapsedMs,
                                     long totalElapsedMs, long perRecordMs) {
            return new ArchiveResult(rangeLabel, s3Key, totalCount, deletedCount, fileSizeBytes,
                    uploadElapsedMs, deleteElapsedMs, totalElapsedMs, perRecordMs, true, false, null);
        }

        static ArchiveResult fromUploadResult(UploadResult u) {
            return new ArchiveResult(u.rangeLabel(), u.s3Key(), u.totalCount(), 0, u.fileSizeBytes(),
                    u.uploadElapsedMs(), 0, 0, 0, u.success(), u.skipped(), u.errorMessage());
        }
    }
}
