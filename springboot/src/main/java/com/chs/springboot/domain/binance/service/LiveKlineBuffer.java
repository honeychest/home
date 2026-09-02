// [AGENT] 리더 노드에서만 채워지는 인메모리 캔들 버퍼. DB 없이 REST 초기적재 + 웹소켓 갱신만으로
// 유지한다(틱 데이터 SSD 쓰기폭주 이력 때문에 캔들도 DB에 안 쌓기로 함 — docs/binance/CONTEXT.md).
// 확정봉(closed)과 진행 중인 봉(currentPartial)을 분리 — currentPrice는 진행 중인 봉에서도 갱신하되,
// 고가/저가/개수 같은 확정 통계는 확정봉만으로 계산해야 하기 때문.
package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.BinanceKline;

import java.util.List;
import java.util.TreeMap;

class LiveKlineBuffer {

    private final int maxSize;
    private final long intervalMs;
    private final boolean validateInterval;
    private final Object lock = new Object();
    private final TreeMap<Long, BinanceKline> closed = new TreeMap<>();
    private volatile BinanceKline currentPartial;
    private volatile long lastAcceptedAtMs;

    LiveKlineBuffer(int maxSize) {
        this(maxSize, 60_000L, false);
    }

    LiveKlineBuffer(int maxSize, long intervalMs) {
        this(maxSize, intervalMs, true);
    }

    private LiveKlineBuffer(int maxSize, long intervalMs, boolean validateInterval) {
        if (maxSize <= 0 || intervalMs <= 0) {
            throw new IllegalArgumentException("kline buffer 크기와 interval은 0보다 커야 합니다");
        }
        this.maxSize = maxSize;
        this.intervalMs = intervalMs;
        this.validateInterval = validateInterval;
    }

    /** 초기 REST 적재 — 기존 확정봉을 통째로 교체한다. */
    void seed(List<BinanceKline> initial) {
        validateSingleCandleValues(initial);
        synchronized (lock) {
            closed.clear();
            for (BinanceKline kline : initial) {
                put(kline);
            }
        }
        currentPartial = null;
        lastAcceptedAtMs = System.currentTimeMillis();
    }

    /** 확정봉 도착 — 같은 시각이면 교체, 그 봉이 currentPartial이었다면 정리한다. */
    void appendClosed(BinanceKline kline) {
        validateSingleCandleValues(List.of(kline));
        synchronized (lock) {
            Long latestOpenTimeMs = closed.isEmpty() ? null : closed.lastKey();
            if (validateInterval && latestOpenTimeMs != null && kline.openTimeMs() > latestOpenTimeMs
                    && kline.openTimeMs() - latestOpenTimeMs != intervalMs) {
                throw new IllegalArgumentException("실시간 kline 사이에 결측이 있습니다: " + kline.openTimeMs());
            }
            put(kline);
        }
        currentPartial = null;
        lastAcceptedAtMs = System.currentTimeMillis();
    }

    /** 진행 중인 봉 갱신 — 확정봉 목록에는 안 들어가고 currentPrice 용도로만 쓰인다. */
    void updatePartial(BinanceKline kline) {
        validateSingleCandleValues(List.of(kline));
        currentPartial = kline;
        lastAcceptedAtMs = System.currentTimeMillis();
    }

    private void validateSingleCandleValues(List<BinanceKline> candles) {
        if (candles == null) {
            throw new IllegalArgumentException("kline 목록은 null일 수 없습니다");
        }
        for (BinanceKline kline : candles) {
            if (kline == null || (validateInterval && (kline.openTimeMs() < 0
                    || kline.openTimeMs() % intervalMs != 0))) {
                throw new IllegalArgumentException("실시간 kline 값 또는 interval 경계가 올바르지 않습니다");
            }
        }
    }

    private void put(BinanceKline kline) {
        closed.put(kline.openTimeMs(), kline);
        while (closed.size() > maxSize) {
            closed.pollFirstEntry();
        }
    }

    Snapshot snapshot() {
        List<BinanceKline> list;
        synchronized (lock) {
            list = List.copyOf(closed.values());
        }
        return new Snapshot(list, currentPartial, lastAcceptedAtMs);
    }

    record Snapshot(List<BinanceKline> closedCandles, BinanceKline currentPartial, long lastAcceptedAtMs) {
    }
}
