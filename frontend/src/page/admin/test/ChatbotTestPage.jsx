// [AGENT] 역할: admin/test Chatbot 탭 — 챗봇 로그 분석 화면 + 가벼운 재색인 실행
// 연관: api/adminTest/chatbot.js, 백엔드 /api/admin/chatbot/**(재색인), /api/admin/chatbot/logs/**(로그 조회)
// 인증: 백엔드 ADMIN_ACCESS 가 최종 방어선. 여기서는 UX(상태/오류 안내)만 처리.
import { useEffect, useRef, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import {
    startChatbotReindex,
    startChatbotDocsReindex,
    fetchChatbotReindexStatus,
    fetchChatbotLogSummary,
    fetchChatbotLogTurns,
    fetchChatbotLogTurnDetail,
} from '@/api/adminTest/chatbot.js';
import styles from './ChatbotTestPage.module.css';

const POLL_MS = 1000;

const ISSUE_LABELS = {
    NONE: '문제 없음',
    RETRIEVAL_MISS: '검색 실패',
    ANSWER_QUALITY: '답변 품질',
    CONTEXT_MISS: '맥락 실패',
    PAGE_CONTEXT_MISS: '페이지 맥락',
    LATENCY: '속도 문제',
    ERROR: '오류',
};

const STATUS_LABELS = {
    SUCCESS: '정상',
    ERROR: '오류',
};

function toLocalDateTimeParam(date) {
    const pad = (value) => String(value).padStart(2, '0');
    return [
        date.getFullYear(),
        '-',
        pad(date.getMonth() + 1),
        '-',
        pad(date.getDate()),
        'T',
        pad(date.getHours()),
        ':',
        pad(date.getMinutes()),
        ':',
        pad(date.getSeconds()),
    ].join('');
}

function periodToParams(period) {
    const now = new Date();
    if (period === 'today') {
        const start = new Date(now);
        start.setHours(0, 0, 0, 0);
        return { from: toLocalDateTimeParam(start), to: toLocalDateTimeParam(now) };
    }
    if (period === '30d') {
        const start = new Date(now);
        start.setDate(start.getDate() - 30);
        return { from: toLocalDateTimeParam(start), to: toLocalDateTimeParam(now) };
    }
    const start = new Date(now);
    start.setDate(start.getDate() - 7);
    return { from: toLocalDateTimeParam(start), to: toLocalDateTimeParam(now) };
}

function formatTime(value) {
    if (!value) return '-';
    return new Date(value).toLocaleString('ko-KR', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
    });
}

function formatLatency(ms) {
    if (ms == null) return '-';
    return `${(ms / 1000).toFixed(1)}초`;
}

function issueLabel(issueType) {
    return ISSUE_LABELS[issueType] || issueType || '-';
}

function statusLabel(status) {
    return STATUS_LABELS[status] || status || '-';
}

export default function ChatbotTestPage() {
    // 재색인 상태
    const [reindexing, setReindexing] = useState(false);
    const [reindexMsg, setReindexMsg] = useState('');
    const [reindexError, setReindexError] = useState(false);
    const pollRef = useRef(null);

    // 로그 조회 상태
    const [filters, setFilters] = useState({
        period: '7d',
        pageId: 'all',
        issueType: 'all',
        keyword: '',
    });
    const [summary, setSummary] = useState({
        totalLogs: 0,
        suspectedLogs: 0,
        averageLatencySeconds: 0,
        slowLogs: 0,
    });
    const [logs, setLogs] = useState([]);
    const [selectedLogId, setSelectedLogId] = useState(null);
    const [selectedLog, setSelectedLog] = useState(null);
    const [logLoading, setLogLoading] = useState(false);
    const [logError, setLogError] = useState('');

    // 언마운트 시 폴링 정리
    useEffect(() => () => {
        if (pollRef.current) clearInterval(pollRef.current);
    }, []);

    const buildLogParams = () => {
        const params = {
            ...periodToParams(filters.period),
            page: 0,
            size: 30,
        };
        if (filters.pageId !== 'all') params.pageId = filters.pageId;
        if (filters.issueType !== 'all') params.issueType = filters.issueType;
        if (filters.keyword.trim()) params.keyword = filters.keyword.trim();
        return params;
    };

    const loadLogDetail = (id) => {
        if (!id) {
            setSelectedLog(null);
            return;
        }
        fetchChatbotLogTurnDetail(id)
            .then((res) => setSelectedLog(res.data || null))
            .catch((err) => setLogError(`상세 조회 오류: ${err.message}`));
    };

    const loadLogs = () => {
        setLogLoading(true);
        setLogError('');
        const params = buildLogParams();
        const summaryParams = { ...params };
        delete summaryParams.page;
        delete summaryParams.size;

        Promise.all([
            fetchChatbotLogSummary(summaryParams),
            fetchChatbotLogTurns(params),
        ])
            .then(([summaryRes, turnsRes]) => {
                const page = turnsRes.data || {};
                const content = Array.isArray(page.content) ? page.content : [];
                setSummary(summaryRes.data || summary);
                setLogs(content);
                const nextId = content[0]?.id || null;
                setSelectedLogId(nextId);
                loadLogDetail(nextId);
            })
            .catch((err) => {
                setLogError(`로그 조회 오류: ${err.message}`);
                setLogs([]);
                setSelectedLog(null);
                setSelectedLogId(null);
            })
            .finally(() => setLogLoading(false));
    };

    useEffect(() => {
        loadLogs();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const stopPolling = () => {
        if (pollRef.current) {
            clearInterval(pollRef.current);
            pollRef.current = null;
        }
    };

    const poll = (jobId) => {
        fetchChatbotReindexStatus(jobId)
            .then((res) => {
                const data = res.data || {};
                if (data.status === 'COMPLETED') {
                    stopPolling();
                    setReindexMsg(`색인 완료: 문서 ${data.documentCount ?? 0}개`);
                    setReindexError(false);
                    setReindexing(false);
                } else if (data.status === 'FAILED') {
                    stopPolling();
                    setReindexMsg(`실패: ${data.error || '알 수 없는 오류'}`);
                    setReindexError(true);
                    setReindexing(false);
                } else {
                    const p = data.processedChunks || 0;
                    const t = data.totalChunks || 0;
                    if (t > 0) {
                        const pct = Math.floor((p * 100) / t);
                        setReindexMsg(`색인 진행 중... ${p}/${t} 청크 (${pct}%)`);
                    } else {
                        setReindexMsg('색인 진행 중... (파일 수집/청킹 단계)');
                    }
                }
            })
            .catch((err) => {
                stopPolling();
                setReindexMsg(`폴링 오류: ${err.message}`);
                setReindexError(true);
                setReindexing(false);
            });
    };

    // 색인 종류별 공통 실행기. startFn 만 갈아끼우면 전체/문서 재색인을 동일 로직으로 처리.
    const runReindex = (startFn) => {
        if (reindexing) return;
        setReindexing(true);
        setReindexError(false);
        setReindexMsg('색인 요청 중...');

        startFn()
            .then((res) => {
                const jobId = res.data?.jobId;
                setReindexMsg(`색인 진행 중... (jobId: ${jobId})`);
                pollRef.current = setInterval(() => poll(jobId), POLL_MS);
            })
            .catch((err) => {
                const status = err?.response?.status;
                if (status === 401 || status === 403) {
                    setReindexMsg('관리자 권한이 필요합니다 (로그인 상태를 확인하세요).');
                } else if (status === 409) {
                    setReindexMsg('이미 색인이 진행 중입니다.');
                } else {
                    setReindexMsg(`오류: ${err?.response?.data?.error || status || err.message}`);
                }
                setReindexError(true);
                setReindexing(false);
            });
    };

    const onReindexFull = () => runReindex(startChatbotReindex);
    const onReindexDocs = () => runReindex(startChatbotDocsReindex);

    const onSelectLog = (id) => {
        setSelectedLogId(id);
        loadLogDetail(id);
    };

    const summaryCards = [
        { label: '전체 로그', value: `${summary.totalLogs ?? 0}건`, tone: 'neutral' },
        { label: '실패 의심', value: `${summary.suspectedLogs ?? 0}건`, tone: 'warning' },
        { label: '평균 응답', value: `${summary.averageLatencySeconds ?? 0}초`, tone: 'neutral' },
        { label: '느린 응답', value: `${summary.slowLogs ?? 0}건`, tone: 'danger' },
    ];

    const latestAnalysis = selectedLog?.analyses?.[0];

    return (
        <div className={styles.page}>
            <header className={styles.header}>
                <div>
                    <h1 className={styles.title}>챗봇 로그 분석</h1>
                    <p className={styles.subtitle}>
                        대화 로그를 검색하고 질문, 답변, 근거를 함께 보면서 보강 지점을 찾습니다.
                    </p>
                </div>
            </header>

            <div className={styles.reindexBar}>
                <span className={styles.reindexTitle}>색인</span>
                <span className={`${styles.reindexStatus} ${reindexError ? styles.error : ''}`}>
                    {reindexMsg || '대기 중'}
                </span>
                <div className={styles.reindexActions}>
                    <button className={styles.compactButton} onClick={onReindexFull} disabled={reindexing}>
                        <RefreshCw size={16} className={reindexing ? styles.spin : ''} />
                        {reindexing ? '색인 중' : '전체 재색인'}
                    </button>
                    <button className={styles.compactButton} onClick={onReindexDocs} disabled={reindexing}>
                        <RefreshCw size={16} className={reindexing ? styles.spin : ''} />
                        {reindexing ? '색인 중' : '문서만 재색인'}
                    </button>
                </div>
            </div>

            <section className={`${styles.section} ${styles.logSection}`}>
                <div className={styles.sectionHeader}>
                    <div>
                        <h2 className={styles.sectionTitle}>로그 분석</h2>
                        <p className={styles.sectionDesc}>
                            질문과 답변, 검색 근거, 문제 판단을 함께 보면서 어떤 부분을 보강할지 판단하는 화면입니다.
                        </p>
                    </div>
                    <span className={styles.readyBadge}>실제 로그 연결</span>
                </div>

                <div className={styles.summaryGrid}>
                    {summaryCards.map((card) => (
                        <div key={card.label} className={`${styles.summaryCard} ${styles[card.tone] || ''}`}>
                            <span className={styles.summaryLabel}>{card.label}</span>
                            <strong className={styles.summaryValue}>{card.value}</strong>
                        </div>
                    ))}
                </div>

                <div className={styles.filterBar}>
                    <label>
                        기간
                        <select
                            value={filters.period}
                            onChange={(e) => setFilters((prev) => ({ ...prev, period: e.target.value }))}
                        >
                            <option value="today">오늘</option>
                            <option value="7d">최근 7일</option>
                            <option value="30d">최근 30일</option>
                        </select>
                    </label>
                    <label>
                        페이지
                        <select
                            value={filters.pageId}
                            onChange={(e) => setFilters((prev) => ({ ...prev, pageId: e.target.value }))}
                        >
                            <option value="all">전체</option>
                            <option value="signal">Signal</option>
                            <option value="trade">Trade</option>
                            <option value="admin">Admin</option>
                            <option value="analysis">Analysis</option>
                            <option value="binance">Binance</option>
                        </select>
                    </label>
                    <label>
                        문제 유형
                        <select
                            value={filters.issueType}
                            onChange={(e) => setFilters((prev) => ({ ...prev, issueType: e.target.value }))}
                        >
                            <option value="all">전체</option>
                            <option value="RETRIEVAL_MISS">검색 실패</option>
                            <option value="ANSWER_QUALITY">답변 실패</option>
                            <option value="CONTEXT_MISS">맥락 실패</option>
                            <option value="PAGE_CONTEXT_MISS">페이지 맥락</option>
                            <option value="LATENCY">속도 문제</option>
                            <option value="ERROR">오류</option>
                        </select>
                    </label>
                    <label className={styles.keywordField}>
                        키워드
                        <input
                            placeholder="질문, 답변, 검색어 검색"
                            value={filters.keyword}
                            onChange={(e) => setFilters((prev) => ({ ...prev, keyword: e.target.value }))}
                            onKeyDown={(e) => {
                                if (e.key === 'Enter') loadLogs();
                            }}
                        />
                    </label>
                    <button className={styles.secondaryButton} type="button" onClick={loadLogs}>
                        {logLoading ? '조회 중' : '검색'}
                    </button>
                </div>

                {logError && <div className={`${styles.status} ${styles.error}`}>{logError}</div>}

                <div className={styles.logWorkspace}>
                    <div className={styles.logList} aria-label="챗봇 로그 목록">
                        {logs.length === 0 && (
                            <div className={styles.emptyState}>
                                {logLoading ? '로그를 불러오는 중입니다.' : '조건에 맞는 로그가 없습니다.'}
                            </div>
                        )}
                        {logs.map((log) => (
                            <button
                                key={log.id}
                                type="button"
                                className={`${styles.logItem} ${
                                    selectedLogId === log.id ? styles.logItemActive : ''
                                }`}
                                onClick={() => onSelectLog(log.id)}
                            >
                                <span className={styles.logMeta}>
                                    {log.pageId || '공통'} · {formatTime(log.createdAt)} · {formatLatency(log.latencyMs)}
                                </span>
                                <strong>{log.question}</strong>
                                <span className={styles.logFooter}>
                                    <span>{issueLabel(log.issueType)}</span>
                                    <span>{statusLabel(log.status)}</span>
                                </span>
                            </button>
                        ))}
                    </div>

                    <div className={styles.logDetail}>
                        {!selectedLog && (
                            <div className={styles.emptyState}>왼쪽 목록에서 로그를 선택하세요.</div>
                        )}
                        {selectedLog && (
                            <>
                                <div className={styles.detailHeader}>
                                    <div>
                                        <span className={styles.logMeta}>
                                            {selectedLog.pageId || '공통'} · {formatTime(selectedLog.createdAt)} ·{' '}
                                            {formatLatency(selectedLog.latencyMs)}
                                        </span>
                                        <h3>{selectedLog.question}</h3>
                                    </div>
                                    <span className={styles.issueBadge}>{issueLabel(selectedLog.issueType)}</span>
                                </div>

                                <div className={styles.detailBlock}>
                                    <span className={styles.detailLabel}>답변</span>
                                    <p>{selectedLog.answer || selectedLog.errorMessage || '(답변 없음)'}</p>
                                </div>

                                <div className={styles.detailBlock}>
                                    <span className={styles.detailLabel}>검색 질의</span>
                                    <p>{selectedLog.searchQuery || '-'}</p>
                                </div>

                                <div className={styles.detailBlock}>
                                    <span className={styles.detailLabel}>검색 근거</span>
                                    {selectedLog.evidences?.length > 0 ? (
                                        <ul className={styles.sourceList}>
                                            {selectedLog.evidences.map((source) => (
                                                <li key={`${source.rankNo}-${source.source}`}>
                                                    {source.rankNo}. {source.source}
                                                    {source.symbol ? ` · ${source.symbol}` : ''}
                                                    {source.lineRange ? ` · ${source.lineRange}` : ''}
                                                </li>
                                            ))}
                                        </ul>
                                    ) : (
                                        <p>근거 없음</p>
                                    )}
                                </div>

                                <div className={styles.analysisGrid}>
                                    <div className={styles.analysisCard}>
                                        <span className={styles.detailLabel}>문제 판단</span>
                                        <p>{latestAnalysis?.summary || '사후 분석 결과가 아직 없습니다.'}</p>
                                    </div>
                                    <div className={styles.analysisCard}>
                                        <span className={styles.detailLabel}>보강 제안</span>
                                        <p>{latestAnalysis?.suggestion || '분석 저장 후 보강 제안이 표시됩니다.'}</p>
                                    </div>
                                </div>
                            </>
                        )}
                    </div>
                </div>
            </section>
        </div>
    );
}
