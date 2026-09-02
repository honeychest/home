import { useCallback, useEffect, useRef, useState } from 'react';
import { Bot, RefreshCw, Send } from 'lucide-react';
import {
    askAutoTradeAnalysis,
    fetchAutoTradeAnalysis,
    fetchAutoTradeSnapshot,
    refreshAutoTradeAnalysis,
} from '@/api/adminTest/binance.js';
import { logApiCall } from './shared/logApiCall.js';
import styles from './TradeTestPage.module.css';

const STATUS_LABELS = {
    NOT_LEADER: '이 인스턴스는 리더 노드가 아닙니다',
    BACKFILLING: '확정봉을 초기 적재 중입니다',
    CONNECTING: '웹소켓에 연결 중입니다',
    READY: '실시간 데이터 정상',
    STALE: '최근 데이터 갱신이 지연되고 있습니다',
    GAP: '재연결 후 결측 봉을 보정 중입니다',
    ERROR: '이 인터벌 데이터를 읽지 못했습니다',
    PARTIAL: '일부 인터벌 데이터만 준비되었습니다',
    NO_ANALYSIS: '아직 분석 요청 결과가 없습니다',
    ANALYSIS_IN_PROGRESS: '이미 분석이 진행 중입니다',
    LLM_TIMEOUT: '로컬 LLM 응답 시간이 초과되었습니다',
    LLM_ERROR: '로컬 LLM 분석에 실패했습니다',
    EMPTY_QUESTION: '질문을 입력하세요',
    QUESTION_TOO_LONG: '질문은 1000자 이내로 입력하세요',
    DUPLICATE_QUESTION: '최근 대화에 같은 질문이 있습니다',
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

function formatDuration(tookMs) {
    if (tookMs == null) return null;
    return `생성에 ${(tookMs / 1000).toFixed(1)}초 걸림`;
}

function formatTrend(uptrend) {
    if (uptrend == null) return '-';
    return uptrend ? '상승' : '하락';
}

function statusLabel(status) {
    return STATUS_LABELS[status] ?? `상태: ${status ?? '알 수 없음'}`;
}

function responseOrNull(log) {
    return log?.ok ? log.responseBody : null;
}

export default function TradeTestPage() {
    const [snapshotLog, setSnapshotLog] = useState(null);
    const [analysisLog, setAnalysisLog] = useState(null);
    const [loading, setLoading] = useState(false);
    const [question, setQuestion] = useState('');
    const [turns, setTurns] = useState([]);
    const [askLog, setAskLog] = useState(null);
    const [asking, setAsking] = useState(false);
    const [analyzing, setAnalyzing] = useState(false);
    const loadingRef = useRef(false);

    const load = useCallback(async () => {
        if (loadingRef.current) return;
        loadingRef.current = true;
        setLoading(true);
        const [snapshotResult, analysisResult] = await Promise.all([
            logApiCall('GET /api/admin/test/binance/debug/snapshot', fetchAutoTradeSnapshot),
            logApiCall('GET /api/admin/test/binance/debug/analysis', fetchAutoTradeAnalysis),
        ]);
        setSnapshotLog(snapshotResult);
        setAnalysisLog(analysisResult);
        setLoading(false);
        loadingRef.current = false;
    }, []);

    useEffect(() => {
        const initialLoad = window.setTimeout(() => void load(), 0);
        return () => window.clearTimeout(initialLoad);
    }, [load]);

    const handleRefreshAnalysis = async () => {
        if (analyzing) return;
        setAnalyzing(true);
        const result = await logApiCall(
            'POST /api/admin/test/binance/debug/analysis/refresh',
            refreshAutoTradeAnalysis
        );
        setAnalysisLog(result);
        setAnalyzing(false);
    };

    const handleAsk = async (event) => {
        event.preventDefault();
        if (asking) return;
        setAsking(true);
        const result = await logApiCall(
            'POST /api/admin/test/binance/debug/analysis/ask',
            () => askAutoTradeAnalysis(question, turns)
        );
        setAskLog(result);
        if (result.ok && result.responseBody?.answer) {
            setTurns((previous) => [
                ...previous,
                { role: 'user', content: question.trim() },
                { role: 'assistant', content: result.responseBody.answer },
            ].slice(-8));
            setQuestion('');
        }
        setAsking(false);
    };

    const snapshotBody = responseOrNull(snapshotLog);
    const analysisBody = responseOrNull(analysisLog);
    const askBody = responseOrNull(askLog);
    const snapshots = snapshotBody?.snapshot?.snapshots ?? [];
    const intervalStatuses = snapshotBody?.snapshot?.intervalStatuses ?? [];
    const statusByInterval = Object.fromEntries(
        intervalStatuses.map((item) => [item.interval, item])
    );

    return (
        <div className={styles.page}>
            <header className={styles.header}>
                <div>
                    <p className={styles.eyebrow}>BINANCE FUTURES / READ ONLY</p>
                    <h1 className={styles.title}>시장 관제와 로컬 분석</h1>
                    <p className={styles.subtitle}>
                        BTCUSDT 선물의 확정봉·지표·순매수 흐름을 관리자 화면에서 확인합니다. 주문 기능은 없습니다.
                    </p>
                </div>
                <button className={styles.refreshButton} onClick={() => void load()} disabled={loading}>
                    <RefreshCw size={16} />
                    {loading ? '조회 중' : '새로고침'}
                </button>
            </header>

            {!snapshotLog ? (
                <div className={styles.emptyBox}>시장 데이터 조회 중...</div>
            ) : !snapshotLog.ok ? (
                <div className={styles.emptyBox}>시장 데이터 요청에 실패했습니다.</div>
            ) : (
                <>
                    <section className={styles.marketStrip}>
                        <div>
                            <span className={styles.statusLabel}>현재가</span>
                            <strong className={styles.currentPrice}>
                                {formatPrice(snapshotBody?.snapshot?.currentPrice)}
                            </strong>
                        </div>
                        <div>
                            <span className={styles.statusLabel}>수집 상태</span>
                            <strong>{statusLabel(snapshotBody?.status)}</strong>
                        </div>
                        <div>
                            <span className={styles.statusLabel}>스냅샷 기준 시각</span>
                            <strong>{formatTime(snapshotBody?.snapshot?.asOfMs)}</strong>
                        </div>
                        <div>
                            <span className={styles.statusLabel}>분석 가능</span>
                            <strong>{snapshotBody?.snapshot?.analysisAvailable ? '예' : '아니오'}</strong>
                        </div>
                    </section>

                    {snapshotBody?.status !== 'READY' && (
                        <div className={styles.noticeBox}>{statusLabel(snapshotBody?.status)}</div>
                    )}

                    <section className={styles.statusGrid}>
                        {snapshots.map((snapshot) => {
                            const intervalStatus = statusByInterval[snapshot.interval];
                            return (
                                <article className={styles.intervalCard} key={snapshot.interval}>
                                    <div className={styles.cardHeader}>
                                        <div>
                                            <span className={styles.eyebrow}>INTERVAL</span>
                                            <h2>{snapshot.interval}</h2>
                                        </div>
                                        <span className={styles.pill}>{intervalStatus?.status ?? '-'}</span>
                                    </div>
                                    <div className={styles.metricRow}>
                                        <span>확정봉</span>
                                        <strong>{snapshot.candleCount}</strong>
                                    </div>
                                    <div className={styles.metricRow}>
                                        <span>현재가</span>
                                        <strong>{formatPrice(snapshot.currentPrice)}</strong>
                                    </div>
                                    <div className={styles.metricRow}>
                                        <span>구간 변동률</span>
                                        <strong>{formatPercent(snapshot.changePercentFromWindowStart)}</strong>
                                    </div>
                                    <div className={styles.indicatorLine}>
                                        <span>RSI 14</span>
                                        <strong>{formatNumber(snapshot.rsi14)}</strong>
                                    </div>
                                    <div className={styles.indicatorLine}>
                                        <span>MACD 히스토그램</span>
                                        <strong>{formatNumber(snapshot.macdHistogram, 4)}</strong>
                                    </div>
                                    <div className={styles.indicatorLine}>
                                        <span>Supertrend</span>
                                        <strong>{formatTrend(snapshot.supertrendUptrend)}</strong>
                                    </div>
                                    <p className={styles.cardNote}>{intervalStatus?.message || '확정봉 기준'}</p>
                                </article>
                            );
                        })}
                    </section>
                </>
            )}

            <section className={styles.analysisGrid}>
                <article className={styles.analysisCard}>
                    <div className={styles.cardHeader}>
                        <div>
                            <span className={styles.eyebrow}>MARKET BRIEF / ON DEMAND</span>
                            <h2>분석 요약</h2>
                        </div>
                        <button
                            className={styles.refreshButton}
                            onClick={() => void handleRefreshAnalysis()}
                            disabled={analyzing}
                        >
                            <Bot size={16} />
                            {analyzing ? '분석 중' : '분석 요청'}
                        </button>
                    </div>
                    {!analysisLog ? (
                        <p className={styles.muted}>분석 상태 조회 중...</p>
                    ) : !analysisLog.ok ? (
                        <p className={styles.muted}>분석 상태 요청에 실패했습니다.</p>
                    ) : (
                        <>
                            <div className={styles.analysisStatus}>
                                <strong>{statusLabel(analysisBody?.status)}</strong>
                                {analysisBody?.failureStatus && (
                                    <span>{statusLabel(analysisBody.failureStatus)}</span>
                                )}
                            </div>
                            <p className={styles.answer}>{analysisBody?.answer || analysisBody?.message}</p>
                            <p className={styles.asOf}>
                                기준 {formatTime(analysisBody?.asOfMs)} · 마지막 성공 {formatTime(analysisBody?.lastSuccessAtMs)}
                                {formatDuration(analysisBody?.tookMs) ? ` · ${formatDuration(analysisBody?.tookMs)}` : ''}
                            </p>
                        </>
                    )}
                </article>

                <article className={styles.analysisCard}>
                    <div className={styles.cardHeader}>
                        <div>
                            <span className={styles.eyebrow}>ASK THE LOCAL MODEL</span>
                            <h2>관리자 질문</h2>
                        </div>
                    </div>
                    <form className={styles.askForm} onSubmit={handleAsk}>
                        <textarea
                            value={question}
                            onChange={(event) => setQuestion(event.target.value)}
                            maxLength={1000}
                            placeholder="예: 지금 얼마인데, 손절은 어느 기술적 무효화 구간을 봐야 하나?"
                            aria-label="시장 분석 질문"
                        />
                        <div className={styles.formFooter}>
                            <span>{question.length}/1000 · 최근 대화는 이 브라우저 탭에만 유지됩니다</span>
                            <button className={styles.askButton} type="submit" disabled={asking}>
                                <Send size={16} />
                                {asking ? '분석 중' : '질문하기'}
                            </button>
                        </div>
                    </form>
                    {askLog && (
                        <div className={styles.askResult}>
                            {!askLog.ok ? (
                                <p className={styles.muted}>질문 요청에 실패했습니다.</p>
                            ) : (
                                <>
                                    <strong>{statusLabel(askBody?.status)}</strong>
                                    <p className={styles.answer}>{askBody?.answer || askBody?.message}</p>
                                    <p className={styles.asOf}>
                                        기준 {formatTime(askBody?.asOfMs)}
                                        {formatDuration(askBody?.tookMs) ? ` · ${formatDuration(askBody?.tookMs)}` : ''}
                                    </p>
                                </>
                            )}
                        </div>
                    )}
                    {turns.length > 0 && (
                        <div className={styles.turns}>
                            <span className={styles.statusLabel}>최근 대화</span>
                            {turns.map((turn, index) => (
                                <p className={turn.role === 'user' ? styles.userTurn : styles.assistantTurn} key={`${turn.role}-${index}`}>
                                    <strong>{turn.role === 'user' ? '나' : 'LLM'}</strong> {turn.content}
                                </p>
                            ))}
                        </div>
                    )}
                </article>
            </section>
        </div>
    );
}
