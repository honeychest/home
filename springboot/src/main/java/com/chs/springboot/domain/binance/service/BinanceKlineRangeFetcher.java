package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Binance 1분봉 API의 페이지 순회와 응답 범위 검증을 맡는다.
 * 빈 첫 응답은 거래소 데이터가 없거나 호출이 잘못된 경우를 구분해야 하므로
 * 호출부가 명시적 오류로 처리할 수 있게 결과에 표시한다. 모든 호출은 최대 48시간이다.
 */
public class BinanceKlineRangeFetcher {

    public static final long INTERVAL_MS = 60_000L;
    public static final int PAGE_LIMIT = 1_000;
    public static final long MAX_RANGE_MS = 48L * 60L * 60L * 1_000L;

    private final BinanceKlineRestClient restClient;

    public BinanceKlineRangeFetcher(BinanceKlineRestClient restClient) {
        this.restClient = restClient;
    }

    public RangeResult fetch(String symbol, String marketType, long fromMs, long toMsExclusive) {
        validateBoundedRange(fromMs, toMsExclusive);

        Set<Long> openTimes = new TreeSet<>();
        List<BinanceKline> klines = new ArrayList<>();
        long pageStartMs = fromMs;
        long previousPageMaxMs = Long.MIN_VALUE;
        boolean firstPage = true;
        int pages = 0;

        while (pageStartMs < toMsExclusive) {
            List<BinanceKline> page = restClient.fetchPage(symbol, marketType, pageStartMs, toMsExclusive);
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
                if (openTimeMs % INTERVAL_MS != 0) {
                    throw new IllegalStateException("Binance kline 시각이 1분 경계에 맞지 않습니다: " + openTimeMs);
                }
                if (previousRowMs != Long.MIN_VALUE
                        && openTimeMs - previousRowMs != INTERVAL_MS) {
                    throw new IllegalStateException("Binance kline 응답 간격이 1분이 아닙니다: " + openTimeMs);
                }
                if (firstInPage && previousPageMaxMs != Long.MIN_VALUE
                        && openTimeMs - previousPageMaxMs != INTERVAL_MS) {
                    throw new IllegalStateException("Binance kline 페이지 간격이 1분이 아닙니다: " + openTimeMs);
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

            if (page.size() < PAGE_LIMIT || pageMaxMs >= toMsExclusive - INTERVAL_MS) {
                break;
            }

            long nextPageStartMs = Math.addExact(pageMaxMs, INTERVAL_MS);
            if (nextPageStartMs <= pageStartMs) {
                throw new IllegalStateException("Binance kline 페이지 시작 시각이 전진하지 않았습니다");
            }
            previousPageMaxMs = pageMaxMs;
            pageStartMs = nextPageStartMs;
        }

        return new RangeResult(klines, false, pages);
    }

    public static void validateRange(long fromMs, long toMsExclusive) {
        if (fromMs < 0 || toMsExclusive <= fromMs) {
            throw new IllegalArgumentException("kline 범위는 0 이상이고 fromMs가 toMsExclusive보다 작아야 합니다");
        }
        if (fromMs % INTERVAL_MS != 0 || toMsExclusive % INTERVAL_MS != 0) {
            throw new IllegalArgumentException("kline 범위는 1분 경계로 맞춰야 합니다");
        }
    }

    public static void validateBoundedRange(long fromMs, long toMsExclusive) {
        validateRange(fromMs, toMsExclusive);
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
