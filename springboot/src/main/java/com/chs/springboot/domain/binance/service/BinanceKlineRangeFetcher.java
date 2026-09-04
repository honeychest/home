package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;
import com.chs.springboot.domain.binance.model.BinanceKlineInterval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Binance kline API의 페이지 순회와 응답 범위 검증을 맡는다(인터벌은 생성자로 주입 —
 * 기본은 1분, 5분 등 다른 인터벌은 {@link BinanceKlineInterval}을 명시).
 * 빈 첫 응답은 거래소 데이터가 없거나 호출이 잘못된 경우를 구분해야 하므로
 * 호출부가 명시적 오류로 처리할 수 있게 결과에 표시한다. 모든 호출은 최대 48시간이다.
 */
public class BinanceKlineRangeFetcher {

    public static final long INTERVAL_MS = 60_000L;
    public static final int PAGE_LIMIT = 1_000;
    public static final long MAX_RANGE_MS = 48L * 60L * 60L * 1_000L;

    private final BinanceKlineRestClient restClient;
    private final BinanceKlineInterval interval;

    /** 1분봉 조회(기존 호출부 호환 — interval 생략 시 1분으로 고정된다). */
    public BinanceKlineRangeFetcher(BinanceKlineRestClient restClient) {
        this(restClient, BinanceKlineInterval.ONE_MINUTE);
    }

    public BinanceKlineRangeFetcher(BinanceKlineRestClient restClient, BinanceKlineInterval interval) {
        this.restClient = restClient;
        this.interval = interval;
    }

    public RangeResult fetch(String symbol, String marketType, long fromMs, long toMsExclusive) {
        long intervalMs = interval.intervalMs();
        validateBoundedRange(fromMs, toMsExclusive, interval);

        Set<Long> openTimes = new TreeSet<>();
        List<BinanceKline> klines = new ArrayList<>();
        long pageStartMs = fromMs;
        long previousPageMaxMs = Long.MIN_VALUE;
        boolean firstPage = true;
        int pages = 0;

        while (pageStartMs < toMsExclusive) {
            List<BinanceKline> page = restClient.fetchPage(symbol, marketType, pageStartMs, toMsExclusive, interval);
            pages++;
            if (page.isEmpty()) {
                if (firstPage) {
                    return new RangeResult(klines, true, pages);
                }
                throw new IllegalStateException("Binance kline 후속 페이지가 비어 있습니다: pageStartMs=" + pageStartMs);
            }

            long previousRowMs = Long.MIN_VALUE;
            boolean firstInPage = true;
            long pageMaxMs = Long.MIN_VALUE;
            for (BinanceKline kline : page) {
                long openTimeMs = kline.openTimeMs();
                if (openTimeMs < fromMs || openTimeMs >= toMsExclusive) {
                    continue;
                }
                if (openTimeMs % intervalMs != 0) {
                    throw new IllegalStateException("Binance kline 시각이 " + interval.label() + " 경계에 맞지 않습니다: " + openTimeMs);
                }
                if (previousRowMs != Long.MIN_VALUE
                        && openTimeMs - previousRowMs != intervalMs) {
                    throw new IllegalStateException("Binance kline 응답 간격이 " + interval.label() + "이 아닙니다: " + openTimeMs);
                }
                if (firstInPage && previousPageMaxMs != Long.MIN_VALUE
                        && openTimeMs - previousPageMaxMs != intervalMs) {
                    throw new IllegalStateException("Binance kline 페이지 간격이 " + interval.label() + "이 아닙니다: " + openTimeMs);
                }
                if (!openTimes.add(openTimeMs)) {
                    throw new IllegalStateException("Binance kline 응답에 중복 시각이 있습니다: " + openTimeMs);
                }
                klines.add(kline);
                previousRowMs = openTimeMs;
                pageMaxMs = openTimeMs;
                firstInPage = false;
            }

            if (pageMaxMs == Long.MIN_VALUE) {
                throw new IllegalStateException("Binance kline 응답이 요청 범위에서 전진하지 않았습니다");
            }
            firstPage = false;

            if (page.size() < PAGE_LIMIT || pageMaxMs >= toMsExclusive - intervalMs) {
                break;
            }

            long nextPageStartMs = Math.addExact(pageMaxMs, intervalMs);
            if (nextPageStartMs <= pageStartMs) {
                throw new IllegalStateException("Binance kline 페이지 시작 시각이 전진하지 않았습니다");
            }
            previousPageMaxMs = pageMaxMs;
            pageStartMs = nextPageStartMs;
        }

        return new RangeResult(klines, false, pages);
    }

    /** 1분 경계 검증(기존 호출부 호환 — interval 생략 시 1분으로 고정된다). */
    public static void validateRange(long fromMs, long toMsExclusive) {
        validateRange(fromMs, toMsExclusive, BinanceKlineInterval.ONE_MINUTE);
    }

    public static void validateRange(long fromMs, long toMsExclusive, BinanceKlineInterval interval) {
        if (fromMs < 0 || toMsExclusive <= fromMs) {
            throw new IllegalArgumentException("kline 범위는 0 이상이고 fromMs가 toMsExclusive보다 작아야 합니다");
        }
        if (fromMs % interval.intervalMs() != 0 || toMsExclusive % interval.intervalMs() != 0) {
            throw new IllegalArgumentException("kline 범위는 " + interval.label() + " 경계로 맞춰야 합니다");
        }
    }

    /** 48시간 상한 검증(기존 호출부 호환 — interval 생략 시 1분으로 고정된다). */
    public static void validateBoundedRange(long fromMs, long toMsExclusive) {
        validateBoundedRange(fromMs, toMsExclusive, BinanceKlineInterval.ONE_MINUTE);
    }

    public static void validateBoundedRange(long fromMs, long toMsExclusive, BinanceKlineInterval interval) {
        validateRange(fromMs, toMsExclusive, interval);
        if (toMsExclusive - fromMs > MAX_RANGE_MS) {
            throw new IllegalArgumentException("kline 범위는 최대 48시간입니다");
        }
    }

    public record RangeResult(List<BinanceKline> klines, boolean firstPageEmpty, int pages) {

        public List<Long> openTimes() {
            return klines.stream().map(BinanceKline::openTimeMs).toList();
        }
    }
}
