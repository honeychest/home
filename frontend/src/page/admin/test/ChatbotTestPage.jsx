// [AGENT] 역할: admin/test Chatbot 탭 — 코드베이스 재색인 + 질의응답 (옛 chat.html 기능 이관)
// 연관: api/adminTest/chatbot.js, 백엔드 /api/admin/chatbot/**(재색인), /api/chat(질문)
// 인증: 백엔드 ADMIN_ACCESS 가 최종 방어선. 여기서는 UX(상태/오류 안내)만 처리.
import { useEffect, useRef, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import {
    startChatbotReindex,
    startChatbotDocsReindex,
    fetchChatbotReindexStatus,
    askChatbot,
} from '@/api/adminTest/chatbot.js';
import styles from './ChatbotTestPage.module.css';

const POLL_MS = 1000;

export default function ChatbotTestPage() {
    // 재색인 상태
    const [reindexing, setReindexing] = useState(false);
    const [reindexMsg, setReindexMsg] = useState('');
    const [reindexError, setReindexError] = useState(false);
    const pollRef = useRef(null);

    // 질의응답 상태
    const [question, setQuestion] = useState('');
    const [asking, setAsking] = useState(false);
    const [answer, setAnswer] = useState(null); // { text, sources, error }

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

    const onAsk = () => {
        const q = question.trim();
        if (!q || asking) return;
        setAsking(true);
        setAnswer({ text: '답변 생성 중...', sources: [], loading: true });

        askChatbot(q)
            .then((res) => {
                const data = res.data || {};
                setAnswer({
                    text: data.answer || '(답변 없음)',
                    sources: Array.isArray(data.sources) ? data.sources : [],
                });
            })
            .catch((err) => {
                const status = err?.response?.status;
                setAnswer({ text: `오류: ${status || err.message}`, sources: [], error: true });
            })
            .finally(() => setAsking(false));
    };

    const onKeyDown = (e) => {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
            e.preventDefault();
            onAsk();
        }
    };

    return (
        <div className={styles.page}>
            <header className={styles.header}>
                <div>
                    <h1 className={styles.title}>코드베이스 챗봇</h1>
                    <p className={styles.subtitle}>
                        코드베이스 벡터 재색인과 RAG 질의응답을 관리자 콘솔에서 직접 실행합니다.
                    </p>
                </div>
            </header>

            {/* 색인 관리 */}
            <section className={styles.section}>
                <h2 className={styles.sectionTitle}>색인 관리</h2>
                <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                    <button className={styles.primaryButton} onClick={onReindexFull} disabled={reindexing}>
                        <RefreshCw size={16} className={reindexing ? styles.spin : ''} />
                        {reindexing ? '색인 중' : '전체 재색인'}
                    </button>
                    <button className={styles.primaryButton} onClick={onReindexDocs} disabled={reindexing}>
                        <RefreshCw size={16} className={reindexing ? styles.spin : ''} />
                        {reindexing ? '색인 중' : '문서만 재색인'}
                    </button>
                </div>
                <div className={`${styles.status} ${reindexError ? styles.error : ''}`}>
                    {reindexMsg}
                </div>
            </section>

            {/* 질의응답 */}
            <section className={styles.section}>
                <h2 className={styles.sectionTitle}>질문하기</h2>
                <textarea
                    className={styles.textarea}
                    placeholder="코드베이스에 대해 궁금한 점을 입력하세요... (Ctrl+Enter 전송)"
                    value={question}
                    onChange={(e) => setQuestion(e.target.value)}
                    onKeyDown={onKeyDown}
                />
                <button
                    className={styles.primaryButton}
                    onClick={onAsk}
                    disabled={asking || !question.trim()}
                >
                    {asking ? '생성 중' : '질문'}
                </button>

                {answer && (
                    <div className={styles.answerBox}>
                        <div
                            className={`${styles.answerText} ${answer.loading ? styles.loading : ''} ${
                                answer.error ? styles.error : ''
                            }`}
                        >
                            {answer.text}
                        </div>
                        {answer.sources?.length > 0 && (
                            <div className={styles.sources}>
                                <strong>근거 파일:</strong>
                                <ul>
                                    {answer.sources.map((s, i) => (
                                        <li key={i}>{s}</li>
                                    ))}
                                </ul>
                            </div>
                        )}
                    </div>
                )}
            </section>
        </div>
    );
}
