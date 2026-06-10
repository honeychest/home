// [AGENT] 역할: 모든 페이지 우측 하단에 상주하는 플로팅 코드베이스 챗봇 위젯
// 연관: App.jsx(전역 마운트), 백엔드 POST /api/chat (ChatbotController.java)
// 스타일: 독립 CSS Module(FloatingChatbot.module.css) — 앱의 shadcn/Tailwind 토큰에 의존하지 않음

import { useEffect, useRef, useState } from 'react';
import apiClient from '@/api/apiClient';
import styles from './FloatingChatbot.module.css';

// 봇 답변의 근거 출처 — 기본 접힘, 클릭하면 펼침
function SourceList({ sources }) {
    const [expanded, setExpanded] = useState(false);
    return (
        <div className={styles.sources}>
            <button className={styles.sourceToggle} onClick={() => setExpanded((v) => !v)}>
                {expanded ? '▾' : '▸'} 근거 {sources.length}개
            </button>
            {expanded && (
                <div className={styles.sourceList}>
                    {sources.map((s, i) => (
                        <div key={i}>{s}</div>
                    ))}
                </div>
            )}
        </div>
    );
}

function FloatingChatbot() {
    const [open, setOpen] = useState(false);
    const [question, setQuestion] = useState('');
    const [loading, setLoading] = useState(false);
    // messages: { role: 'user' | 'bot', text: string, sources?: string[] }
    const [messages, setMessages] = useState([]);
    const messagesEndRef = useRef(null);

    // 새 메시지가 추가되면 맨 아래로 스크롤
    useEffect(() => {
        if (open) {
            messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
        }
    }, [messages, open]);

    const send = async () => {
        const q = question.trim();
        if (!q || loading) return;

        // 직전까지의 대화를 맥락으로 함께 전송(서버는 저장 안 함). 최근 12개만, 봇 역할은 assistant 로.
        const history = messages.slice(-12).map((m) => ({
            role: m.role === 'user' ? 'user' : 'assistant',
            content: m.text,
        }));

        setMessages((prev) => [...prev, { role: 'user', text: q }]);
        setQuestion('');
        setLoading(true);

        try {
            const res = await apiClient.post('/api/chat', { question: q, history });
            const data = res.data || {};
            setMessages((prev) => [
                ...prev,
                {
                    role: 'bot',
                    text: data.answer || '(답변 없음)',
                    sources: Array.isArray(data.sources) ? data.sources : [],
                },
            ]);
        } catch (err) {
            const msg = err?.response?.status
                ? `오류: ${err.response.status}`
                : `오류: ${err.message}`;
            setMessages((prev) => [...prev, { role: 'bot', text: msg, sources: [] }]);
        } finally {
            setLoading(false);
        }
    };

    // Enter 전송 / Shift+Enter 줄바꿈
    const onKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            send();
        }
    };

    const sendDisabled = loading || !question.trim();

    return (
        <div id="chatbot-root" className={styles.root}>
            {open && (
                <div id="chatbot-panel" className={styles.panel}>
                    <div id="chatbot-header" className={styles.header}>
                        <span>코드베이스 챗봇</span>
                        <button
                            id="chatbot-close"
                            className={styles.closeBtn}
                            onClick={() => setOpen(false)}
                            aria-label="닫기"
                        >
                            ×
                        </button>
                    </div>

                    <div id="chatbot-messages" className={styles.messages}>
                        {messages.length === 0 && (
                            <div className={styles.bubbleBot + ' ' + styles.bubble}>
                                안녕하세요. 코드베이스에 대해 궁금한 점을 물어보세요.
                            </div>
                        )}
                        {messages.map((m, i) => (
                            <div
                                key={i}
                                className={
                                    styles.msgRow +
                                    ' ' +
                                    (m.role === 'user' ? styles.msgRowUser : styles.msgRowBot)
                                }
                            >
                                <div
                                    className={
                                        styles.bubble +
                                        ' ' +
                                        (m.role === 'user' ? styles.bubbleUser : styles.bubbleBot)
                                    }
                                >
                                    {m.text}
                                </div>
                                {m.role === 'bot' && m.sources && m.sources.length > 0 && (
                                    <SourceList sources={m.sources} />
                                )}
                            </div>
                        ))}
                        {loading && (
                            <div className={styles.msgRow + ' ' + styles.msgRowBot}>
                                <div className={styles.bubble + ' ' + styles.bubbleBot + ' ' + styles.hint}>
                                    답변 생성 중... (로컬 AI 모델이라 수십 초 걸릴 수 있어요)
                                </div>
                            </div>
                        )}
                        <div ref={messagesEndRef} />
                    </div>

                    <div id="chatbot-input-area" className={styles.inputArea}>
                        <textarea
                            id="chatbot-input"
                            className={styles.textarea}
                            placeholder="질문을 입력하세요 (Enter 전송)"
                            value={question}
                            onChange={(e) => setQuestion(e.target.value)}
                            onKeyDown={onKeyDown}
                        />
                        <button
                            id="chatbot-send"
                            className={styles.sendBtn + (sendDisabled ? ' ' + styles.sendBtnDisabled : '')}
                            onClick={send}
                            disabled={sendDisabled}
                        >
                            전송
                        </button>
                    </div>
                </div>
            )}

            <button
                id="chatbot-fab"
                className={styles.fab}
                onClick={() => setOpen((v) => !v)}
                aria-label={open ? '챗봇 닫기' : '챗봇 열기'}
            >
                {open ? '×' : '💬'}
            </button>
        </div>
    );
}

export default FloatingChatbot;
