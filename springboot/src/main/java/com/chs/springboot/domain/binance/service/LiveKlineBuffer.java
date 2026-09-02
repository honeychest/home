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
    private final Object lock = new Object();
    private final TreeMap<Long, BinanceKline> closed = new TreeMap<>();
    private volatile BinanceKline currentPartial;
    private volatile long lastAcceptedAtMs;

    LiveKlineBuffer(int maxSize) {
        this.maxSize = maxSize;
    }

    /** 초기 REST 적재 — 기존 확정봉을 통째로 교체한다. */
    void seed(List<BinanceKline> initial) {
        synchronized (lock) {
            closed.clear();
            for (BinanceKline kline : initial) {
                put(kline);
            }
        }
        lastAcceptedAtMs = System.currentTimeMillis();
    }

    /** 확정봉 도착 — 같은 시각이면 교체, 그 봉이 currentPartial이었다면 정리한다. */
    void appendClosed(BinanceKline kline) {
        synchronized (lock) {
            put(kline);
        }
        currentPartial = null;
        lastAcceptedAtMs = System.currentTimeMillis();
    }

    /** 진행 중인 봉 갱신 — 확정봉 목록에는 안 들어가고 currentPrice 용도로만 쓰인다. */
    void updatePartial(BinanceKline kline) {
        currentPartial = kline;
        lastAcceptedAtMs = System.currentTimeMillis();
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
