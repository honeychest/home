import { useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { fetchAutoTradeSnapshot } from '@/api/adminTest/binance.js';
import { logApiCall } from './shared/logApiCall.js';
import styles from './TradeTestPage.module.css';

const STATUS_LABELS = {
    NOT_LEADER: '이 인스턴스는 리더 노드가 아닙니다 — 리더 노드에서 조회하세요',
    BACKFILLING: '초기 적재 중입니다 — 잠시 후 다시 조회하세요',
    STALE: '최근 데이터 갱신이 지연되고 있습니다',
};

function formatPrice(value) {
    if (value == null) return '-';
    return new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }).format(value);
}

function formatPercent(value) {
    if (value == null) return '-';
    return `${value > 0 ? '+' : ''}${value}%`;
}

function formatTime(value) {
    if (!value) return '-';
    return new Date(value).toLocaleString('ko-KR', { hour12: false });
}

function formatNumber(value, digits = 2) {
    if (value == null) return '-';
    return new Intl.NumberFormat('ko-KR', { maximumFractionDigits: digits }).format(value);
}

function formatTrend(uptrend) {
    if (uptrend == null) return '-';
    return uptrend ? '상승' : '하락';
}

export default function TradeTestPage() {
    const [log, setLog] = useState(null);
    const [loading, setLoading] = useState(false);

    const load = async () => {
        if (loading) return;
        setLoading(true);
        const result = await logApiCall(
            'GET /api/admin/test/binance/debug/snapshot',
            fetchAutoTradeSnapshot
        );
        setLog(result);
        setLoading(false);
    };

    useEffect(() => {
        load();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const body = log?.responseBody;
    const status = body?.status;
    const snapshot = body?.snapshot;

    return (
        <div className={styles.page}>
            <header className={styles.header}>
                <div>
                    <h1 className={styles.title}>Auto-Trade Live Market Data(자동매매 PoC — 실시간 캔들 버퍼 현황)</h1>
                    <p className={styles.subtitle}>
                        리더 노드에서만 채워지는 인메모리 1분봉 버퍼(DB 미사용)의 지금 상태를 봅니다.
                    </p>
                </div>
                <button className={styles.refreshButton} onClick={load} disabled={loading}>
                    <RefreshCw size={16} />
                    {loading ? '조회 중' : '새로고침'}
                </button>
            </header>

            {!log ? (
                <div className={styles.emptyBox}>조회 중...</div>
            ) : !log.ok ? (
                <div className={styles.emptyBox}>
                    <p className={styles.errorText}>요청 실패: {log.errorMessage}</p>
                </div>
            ) : status !== 'READY' ? (
                <div className={styles.emptyBox}>
                    {STATUS_LABELS[status] ?? `상태: ${status ?? '알 수 없음'}`}
                </div>
            ) : (
                <section className={styles.statusGrid}>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>symbol(심볼)</span>
                        <strong>{snapshot.symbol}</strong>
                    </div>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>market(시장 구분)</span>
                        <strong>{snapshot.marketType}</strong>
                    </div>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>interval(캔들 간격)</span>
                        <strong>{snapshot.interval}</strong>
                    </div>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>candle count(버퍼에 있는 확정봉 개수)</span>
                        <strong>{snapshot.candleCount}</strong>
                    </div>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>current price(마지막 수신 가격 — 진행 중인 봉 포함)</span>
                        <strong>{formatPrice(snapshot.currentPrice)}</strong>
                    </div>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>window high(버퍼 내 최고가)</span>
                        <strong>{formatPrice(snapshot.windowHigh)}</strong>
                    </div>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>window low(버퍼 내 최저가)</span>
                        <strong>{formatPrice(snapshot.windowLow)}</strong>
                    </div>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>change(버퍼 시작 시점 대비 변동률)</span>
                        <strong>{formatPercent(snapshot.changePercentFromWindowStart)}</strong>
                    </div>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>last received(마지막 수신 시각 — 거래소 캔들 시각 아님)</span>
                        <strong>{formatTime(snapshot.lastUpdatedMs)}</strong>
                    </div>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>RSI(14)(상대강도지수 — 70↑ 과매수, 30↓ 과매도)</span>
                        <strong>{formatNumber(snapshot.rsi14)}</strong>
                    </div>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>MACD(12,26,9)(단기·장기 이동평균 차이)</span>
                        <strong>
                            {formatNumber(snapshot.macdLine, 4)} / {formatNumber(snapshot.macdSignal, 4)}
                            {' '}(hist {formatNumber(snapshot.macdHistogram, 4)})
                        </strong>
                    </div>
                    <div className={styles.statusBox}>
                        <span className={styles.statusLabel}>Supertrend(10,3)(추세 방향 + 추적 지지·저항선)</span>
                        <strong>{formatTrend(snapshot.supertrendUptrend)} / {formatNumber(snapshot.supertrendValue)}</strong>
                    </div>
                </section>
            )}
        </div>
    );
}
