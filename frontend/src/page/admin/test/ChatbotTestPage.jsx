// [AGENT] 역할: admin/test Chatbot 탭 — 챗봇 로그 분석 화면 + 가벼운 재색인 실행
// 연관: api/adminTest/chatbot.js, 백엔드 /api/admin/chatbot/**(재색인), 추후 로그 조회 API
// 인증: 백엔드 ADMIN_ACCESS 가 최종 방어선. 여기서는 UX(상태/오류 안내)만 처리.
import { useEffect, useRef, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import {
    startChatbotReindex,
    startChatbotDocsReindex,
    fetchChatbotReindexStatus,
} from '@/api/adminTest/chatbot.js';
import styles from './ChatbotTestPage.module.css';

const POLL_MS = 1000;

// 화면 선구성용 샘플 데이터. 실제 로그 API가 생기면 이 배열만 서버 응답으로 교체한다.
const SAMPLE_LOGS = [
    {
        id: 'log-001',
        page: 'Signal',
        time: '오늘 14:22',
        latency: '12.4초',
        status: '실패 의심',
        issue: '검색 실패',
        question: '이 페이지에서 오픈포지션이 뭘 의미해?',
        answer: '오픈포지션은 아직 청산되지 않은 계약 규모입니다. 이 화면에서는 단기 매매 신호 판단에 함께 사용됩니다.',
        sources: ['docs/generated/fe-page-signal.md', 'frontend/src/page/signal/SignalPage.jsx'],
        diagnosis: '근거는 Signal 문서에 치우쳐 있고, 실제 화면의 차트 위치와 해석 예시가 부족합니다.',
        suggestion: 'Signal 페이지 문서에 OI 카드 위치와 해석 예시를 추가',
    },
    {
        id: 'log-002',
        page: 'Admin',
        time: '오늘 13:48',
        latency: '7.8초',
        status: '정상',
        issue: '문제 없음',
        question: '문서만 재색인은 언제 쓰면 돼?',
        answer: '소스 벡터는 유지하고 docs/generated 문서만 다시 넣을 때 사용합니다.',
        sources: ['ChatbotAdminController.java', 'CodebaseIndexingService.java'],
        diagnosis: '질문 의도와 검색 근거가 잘 맞고, 답변도 짧게 정리되어 있습니다.',
        suggestion: '현재 답변 품질 유지',
    },
    {
        id: 'log-003',
        page: 'Trade',
        time: '어제 22:11',
        latency: '31.2초',
        status: '느림',
        issue: '속도 문제',
        question: '체결 틱 조회 흐름 설명해줘',
        answer: '체결 데이터 조회 패널과 테이블을 통해 최근 체결 흐름을 확인할 수 있습니다.',
        sources: ['frontend/src/page/trade/TradePage.jsx'],
        diagnosis: '응답 시간이 길고 프론트 근거만 검색되어 백엔드 조회 흐름 설명이 약합니다.',
        suggestion: 'Trade 관련 백엔드 API와 프론트 컴포넌트 근거가 함께 검색되도록 랭킹 보강',
    },
];

const SUMMARY_CARDS = [
    { label: '전체 로그', value: '128건', tone: 'neutral' },
    { label: '실패 의심', value: '18건', tone: 'warning' },
    { label: '평균 응답', value: '8.2초', tone: 'neutral' },
    { label: '느린 응답', value: '9건', tone: 'danger' },
];

export default function ChatbotTestPage() {
    // 재색인 상태
    const [reindexing, setReindexing] = useState(false);
    const [reindexMsg, setReindexMsg] = useState('');
    const [reindexError, setReindexError] = useState(false);
    const pollRef = useRef(null);

    const [selectedLogId, setSelectedLogId] = useState(SAMPLE_LOGS[0].id);
    const selectedLog = SAMPLE_LOGS.find((log) => log.id === selectedLogId) || SAMPLE_LOGS[0];

    // 언마운트 시 폴링 정리
    useEffect(() => () => {
        if (pollRef.current) clearInterval(pollRef.current);
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

            {/* 로그 분석 화면 선구성 */}
            <section className={`${styles.section} ${styles.logSection}`}>
                <div className={styles.sectionHeader}>
                    <div>
                        <h2 className={styles.sectionTitle}>로그 분석</h2>
                        <p className={styles.sectionDesc}>
                            질문과 답변, 검색 근거, 문제 판단을 함께 보면서 어떤 부분을 보강할지 판단하는 화면입니다.
                            현재는 화면 구성용 샘플이며, 이후 로그 API와 연결합니다.
                        </p>
                    </div>
                    <span className={styles.readyBadge}>화면 설계 단계</span>
                </div>

                <div className={styles.summaryGrid}>
                    {SUMMARY_CARDS.map((card) => (
                        <div key={card.label} className={`${styles.summaryCard} ${styles[card.tone] || ''}`}>
                            <span className={styles.summaryLabel}>{card.label}</span>
                            <strong className={styles.summaryValue}>{card.value}</strong>
                        </div>
                    ))}
                </div>

                <div className={styles.filterBar}>
                    <label>
                        기간
                        <select defaultValue="7d">
                            <option value="today">오늘</option>
                            <option value="7d">최근 7일</option>
                            <option value="30d">최근 30일</option>
                        </select>
                    </label>
                    <label>
                        페이지
                        <select defaultValue="all">
                            <option value="all">전체</option>
                            <option value="signal">Signal</option>
                            <option value="trade">Trade</option>
                            <option value="admin">Admin</option>
                        </select>
                    </label>
                    <label>
                        문제 유형
                        <select defaultValue="all">
                            <option value="all">전체</option>
                            <option value="retrieval">검색 실패</option>
                            <option value="answer">답변 실패</option>
                            <option value="context">맥락 실패</option>
                            <option value="latency">속도 문제</option>
                        </select>
                    </label>
                    <label className={styles.keywordField}>
                        키워드
                        <input placeholder="질문, 답변, 파일명 검색" />
                    </label>
                    <button className={styles.secondaryButton} type="button">
                        검색
                    </button>
                </div>

                <div className={styles.logWorkspace}>
                    <div className={styles.logList} aria-label="챗봇 로그 목록">
                        {SAMPLE_LOGS.map((log) => (
                            <button
                                key={log.id}
                                type="button"
                                className={`${styles.logItem} ${
                                    selectedLogId === log.id ? styles.logItemActive : ''
                                }`}
                                onClick={() => setSelectedLogId(log.id)}
                            >
                                <span className={styles.logMeta}>
                                    {log.page} · {log.time} · {log.latency}
                                </span>
                                <strong>{log.question}</strong>
                                <span className={styles.logFooter}>
                                    <span>{log.issue}</span>
                                    <span>{log.status}</span>
                                </span>
                            </button>
                        ))}
                    </div>

                    <div className={styles.logDetail}>
                        <div className={styles.detailHeader}>
                            <div>
                                <span className={styles.logMeta}>
                                    {selectedLog.page} · {selectedLog.time} · {selectedLog.latency}
                                </span>
                                <h3>{selectedLog.question}</h3>
                            </div>
                            <span className={styles.issueBadge}>{selectedLog.issue}</span>
                        </div>

                        <div className={styles.detailBlock}>
                            <span className={styles.detailLabel}>답변</span>
                            <p>{selectedLog.answer}</p>
                        </div>

                        <div className={styles.detailBlock}>
                            <span className={styles.detailLabel}>검색 근거</span>
                            <ul className={styles.sourceList}>
                                {selectedLog.sources.map((source) => (
                                    <li key={source}>{source}</li>
                                ))}
                            </ul>
                        </div>

                        <div className={styles.analysisGrid}>
                            <div className={styles.analysisCard}>
                                <span className={styles.detailLabel}>문제 판단</span>
                                <p>{selectedLog.diagnosis}</p>
                            </div>
                            <div className={styles.analysisCard}>
                                <span className={styles.detailLabel}>보강 제안</span>
                                <p>{selectedLog.suggestion}</p>
                            </div>
                        </div>
                    </div>
                </div>
            </section>
        </div>
    );
}
