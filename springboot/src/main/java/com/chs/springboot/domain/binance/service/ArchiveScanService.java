// [AGENT] 역할: S3 기존 파일 스캔 → s3_archive_log 초기화 | 연관파일: S3ArchiveLogRepository.java, ArchiveAdminController.java
// S3 ListObjectsV2 → 파일명 파싱(range_start/end) + LastModified(uploaded_at) + Size + GetObject 스트리밍 LINE COUNT → INSERT(complete='Y', trigger_type='SCANNER')
// 이미 s3_key 존재하면 스킵 (uq_s3_key 중복 방지)
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.S3ArchiveLog;
import com.chs.springboot.domain.binance.repository.S3ArchiveLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ArchiveScanService {

    private static final String TABLE_NAME = "raw_agg_trade";
    private static final String S3_PREFIX  = "raw_agg_trade/";
    private static final DateTimeFormatter KEY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final S3Client s3Client;
    private final S3ArchiveLogRepository s3ArchiveLogRepository;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket; // S3 버킷명 (application.properties 에서 주입)

    @Value("${archive.raw-agg-trade.enabled:false}")
    private boolean archiveEnabled;

    public ArchiveScanService(S3Client s3Client,
                              S3ArchiveLogRepository s3ArchiveLogRepository) {
        this.s3Client = s3Client;
        this.s3ArchiveLogRepository = s3ArchiveLogRepository;
    }

    /**
     * S3에서 raw_agg_trade/*.csv 목록을 페이지네이션으로 전부 읽어서 S3FileInfo 리스트로 반환. DB INSERT는 없음.
     * AdminPage 미리보기 버튼에서 호출.
     *
     * @return S3 파일 메타 목록
     */
    public List<S3FileInfo> listS3Files() {
        if (!archiveEnabled) {
            return List.of();
        }
        List<S3FileInfo> result = new ArrayList<>();

        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(S3_PREFIX)
                .build();

        ListObjectsV2Response response;
        do {
            response = s3Client.listObjectsV2(request);

            for (S3Object obj : response.contents()) {
                String s3Key = obj.key();
                if (!s3Key.endsWith(".csv")) {
                    continue;
                }

                RangeParsed range = parseRange(s3Key);
                if (range == null) {
                    log.warn("[ArchiveScan] 파일명 파싱 실패 — 스킵: key={}", s3Key);
                    continue;
                }

                // DB에 이미 존재하면 complete 값(N/Y), 없으면 null
                String complete = s3ArchiveLogRepository.findByS3Key(s3Key)
                        .map(S3ArchiveLog::getComplete)
                        .orElse(null);
                LocalDateTime uploadedAt = LocalDateTime.ofInstant(obj.lastModified(), ZoneOffset.UTC);

                result.add(new S3FileInfo(s3Key, range.rangeLabel(), range.rangeStart(),
                        range.rangeEnd(), obj.size(), uploadedAt, complete));
            }

            request = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(S3_PREFIX)
                    .continuationToken(response.nextContinuationToken())
                    .build();

        } while (Boolean.TRUE.equals(response.isTruncated()));

        return result;
    }

    /**
     * listS3Files()로 파일 목록을 가져온 뒤, DB에 없는 파일만 행 수를 세어
     * s3_archive_log에 INSERT. 이미 있는 파일은 스킵. AdminPage 초기화 버튼에서 호출.
     *
     * @return 삽입된 건수
     */
    public ScanResult scan() {
        if (!archiveEnabled) {
            return new ScanResult(0, 0);
        }
        List<S3FileInfo> files = listS3Files();

        int inserted = 0;
        int skipped  = 0;

        for (S3FileInfo file : files) {
            if (file.complete() != null) { // null이 아니면 이미 DB에 존재 → 스킵
                skipped++;
                continue;
            }

            long rowCount = countRowsByStreaming(file.s3Key());

            S3ArchiveLog archiveLog = new S3ArchiveLog();
            archiveLog.setTableName(TABLE_NAME);
            archiveLog.setS3Key(file.s3Key());
            archiveLog.setRangeLabel(file.rangeLabel());
            archiveLog.setRangeStart(file.rangeStart());
            archiveLog.setRangeEnd(file.rangeEnd());
            archiveLog.setRowCount(rowCount);
            archiveLog.setFileSizeBytes(file.fileSizeBytes());
            archiveLog.setTriggerType("SCANNER");
            archiveLog.setComplete("Y"); // 기존 파일 = 이미 삭제 완료
            archiveLog.setUploadedAt(file.uploadedAt());
            s3ArchiveLogRepository.save(archiveLog);

            log.warn("[ArchiveScan] INSERT: key={} rowCount={}", file.s3Key(), rowCount);
            inserted++;
        }

        log.warn("[ArchiveScan] 완료: inserted={} skipped={}", inserted, skipped);
        return new ScanResult(inserted, skipped);
    }

    /**
     * S3Client.getObject() 스트리밍으로 파일을 읽어 개행 문자를 카운트한 뒤 헤더 1줄 차감.
     * S3 Select는 2024-11 AWS 서비스 종료로 대체.
     */
    private long countRowsByStreaming(String s3Key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build();

        try (var is = s3Client.getObject(request)) {
            long newlineCount = 0;
            byte[] buf = new byte[8192];
            int read;
            while ((read = is.read(buf)) != -1) {
                for (int i = 0; i < read; i++) {
                    if (buf[i] == '\n') newlineCount++;
                }
            }
            return Math.max(0, newlineCount - 1); // 헤더 행 1줄 차감
        } catch (Exception e) {
            log.error("[ArchiveScan] 행 수 조회 실패: key={} error={}", s3Key, e.getMessage());
            return 0L;
        }
    }

    /**
     *  raw_agg_trade/2024-01-01_00-00-00_2024-01-02_00-00-00.csv 형식의 파일명에서 rangeStart, rangeEnd를
     *  파싱. 실패 시 null 반환 → 호출부에서 스킵.
     */
    private RangeParsed parseRange(String s3Key) {
        try {
            // "raw_agg_trade/" 제거, ".csv" 제거
            String rangeLabel = s3Key.substring(S3_PREFIX.length(), s3Key.length() - 4);

            // "yyyy-MM-dd_HH-mm-ss_yyyy-MM-dd_HH-mm-ss" → 20자 + "_" + 20자 = 41자
            // start: 0~18(19자), end: 20~38(19자)
            String startPart = rangeLabel.substring(0, 19);
            String endPart   = rangeLabel.substring(20);

            LocalDateTime rangeStart = LocalDateTime.parse(startPart, KEY_FORMATTER);
            LocalDateTime rangeEnd   = LocalDateTime.parse(endPart,   KEY_FORMATTER);

            return new RangeParsed(rangeLabel, rangeStart, rangeEnd);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Records ----

    public record S3FileInfo(
            String s3Key,
            String rangeLabel,
            LocalDateTime rangeStart,
            LocalDateTime rangeEnd,
            long fileSizeBytes,
            LocalDateTime uploadedAt,
            String complete  // DB에 존재하면 N/Y, 없으면 null
    ) {}

    public record ScanResult(int inserted, int skipped) {}

    private record RangeParsed(String rangeLabel, LocalDateTime rangeStart, LocalDateTime rangeEnd) {}
}
