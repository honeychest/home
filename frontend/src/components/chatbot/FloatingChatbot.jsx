// [AGENT] 역할: 모든 페이지 우측 하단에 상주하는 플로팅 코드베이스 챗봇 위젯
// 연관: App.jsx(전역 마운트), 백엔드 POST /api/chat (ChatbotController.java)
// 스타일: 독립(인라인) — 앱의 shadcn/Tailwind 토큰에 의존하지 않음

import { useEffect, useRef, useState } from 'react';
import apiClient from '@/api/apiClient';

// 인라인 스타일 모음 (독립 스타일)
const S = {
    // 우측 하단 고정 컨테이너
    root: {
        position: 'fixed',
        right: '24px',
        bottom: '24px',
        zIndex: 2147483000,
        fontFamily: "'Segoe UI', Tahoma, Geneva, Verdana, sans-serif",
    },
    // 둥근 토글 버튼
    fab: {
        width: '56px',
        height: '56px',
        borderRadius: '50%',
        border: 'none',
        cursor: 'pointer',
        background: '#4a6fa5',
        color: '#fff',
        fontSize: '24px',
        boxShadow: '0 4px 14px rgba(0,0,0,0.25)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        marginLeft: 'auto',
    },
    // 채팅 패널
    panel: {
        width: '360px',
        maxWidth: 'calc(100vw - 48px)',
        height: '520px',
        maxHeight: 'calc(100vh - 120px)',
        background: '#ffffff',
        borderRadius: '12px',
        boxShadow: '0 8px 30px rgba(0,0,0,0.25)',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
        marginBottom: '12px',
        border: '1px solid #e2e2e2',
    },
    header: {
        background: '#4a6fa5',
        color: '#fff',
        padding: '12px 16px',
        fontSize: '15px',
        fontWeight: 600,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
    },
    closeBtn: {
        background: 'transparent',
        border: 'none',
        color: '#fff',
        fontSize: '20px',
        cursor: 'pointer',
        lineHeight: 1,
    },
    messages: {
        flex: 1,
        overflowY: 'auto',
        padding: '14px',
        background: '#f5f6f8',
    },
    // 말풍선 (사용자/봇 공통 베이스)
    bubbleUser: {
        alignSelf: 'flex-end',
        background: '#4a6fa5',
        color: '#fff',
        padding: '8px 12px',
        borderRadius: '12px 12px 2px 12px',
        maxWidth: '85%',
        fontSize: '14px',
        lineHeight: 1.5,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
    },
    bubbleBot: {
        alignSelf: 'flex-start',
        background: '#fff',
        color: '#333',
        padding: '8px 12px',
        borderRadius: '12px 12px 12px 2px',
        maxWidth: '85%',
        fontSize: '14px',
        lineHeight: 1.5,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        border: '1px solid #e6e6e6',
    },
    msgRow: {
        display: 'flex',
        flexDirection: 'column',
        marginBottom: '10px',
    },
    inputArea: {
        display: 'flex',
        gap: '8px',
        padding: '10px',
        borderTop: '1px solid #e2e2e2',
        background: '#fff',
    },
    textarea: {
        flex: 1,
        resize: 'none',
        height: '40px',
        maxHeight: '100px',
        padding: '9px 10px',
        fontSize: '14px',
        border: '1px solid #ccc',
        borderRadius: '8px',
        boxSizing: 'border-box',
        fontFamily: 'inherit',
    },
    sendBtn: {
        border: 'none',
        borderRadius: '8px',
        padding: '0 16px',
        background: '#4a6fa5',
        color: '#fff',
        fontSize: '14px',
        cursor: 'pointer',
    },
    sendBtnDisabled: {
        background: '#aaa',
        cursor: 'not-allowed',
    },
    hint: {
        color: '#aaa',
        fontStyle: 'italic',
    },
};

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

        setMessages((prev) => [...prev, { role: 'user', text: q }]);
        setQuestion('');
        setLoading(true);

        try {
            const res = await apiClient.post('/api/chat', { question: q });
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

    return (
        <div style={S.root}>
            {open && (
                <div style={S.panel}>
                    <div style={S.header}>
                        <span>코드베이스 챗봇</span>
                        <button
                            style={S.closeBtn}
                            onClick={() => setOpen(false)}
                            aria-label="닫기"
                        >
                            ×
                        </button>
                    </div>

                    <div style={S.messages}>
                        {messages.length === 0 && (
                            <div style={{ ...S.bubbleBot, alignSelf: 'flex-start' }}>
                                안녕하세요. 코드베이스에 대해 궁금한 점을 물어보세요.
                            </div>
                        )}
                        {messages.map((m, i) => (
                            <div
                                key={i}
                                style={{
                                    ...S.msgRow,
                                    alignItems: m.role === 'user' ? 'flex-end' : 'flex-start',
                                }}
                            >
                                <div style={m.role === 'user' ? S.bubbleUser : S.bubbleBot}>
                                    {m.text}
                                </div>
                            </div>
                        ))}
                        {loading && (
                            <div style={{ ...S.msgRow, alignItems: 'flex-start' }}>
                                <div style={{ ...S.bubbleBot, ...S.hint }}>
                                    답변 생성 중... (로컬 AI 모델이라 수십 초 걸릴 수 있어요)
                                </div>
                            </div>
                        )}
                        <div ref={messagesEndRef} />
                    </div>

                    <div style={S.inputArea}>
                        <textarea
                            style={S.textarea}
                            placeholder="질문을 입력하세요 (Enter 전송)"
                            value={question}
                            onChange={(e) => setQuestion(e.target.value)}
                            onKeyDown={onKeyDown}
                        />
                        <button
                            style={{
                                ...S.sendBtn,
                                ...(loading || !question.trim() ? S.sendBtnDisabled : {}),
                            }}
                            onClick={send}
                            disabled={loading || !question.trim()}
                        >
                            전송
                        </button>
                    </div>
                </div>
            )}

            <button
                style={S.fab}
                onClick={() => setOpen((v) => !v)}
                aria-label={open ? '챗봇 닫기' : '챗봇 열기'}
            >
                {open ? '×' : '💬'}
            </button>
        </div>
    );
}

export default FloatingChatbot;
