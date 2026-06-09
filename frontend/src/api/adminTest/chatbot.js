// [AGENT] 역할: admin/test Chatbot 탭 전용 API 래퍼 | 연관: page/admin/test/ChatbotTestPage.jsx
// 컨벤션: /admin/test 화면에서만 import. 엔드포인트 문자열은 이 도메인 파일에만 둔다.
// 재색인은 부작용 있는 "실행" → 기존 프로덕션 API(/api/admin/chatbot/**) 를 그대로 호출한다.
import api from '@/api/apiClient.js';

// 재색인 시작(실행) — 성공 시 202 + { jobId }
export function startChatbotReindex() {
    return api.post('/api/admin/chatbot/reindex');
}

// 재색인 작업 상태 조회(관찰) — { status, processedChunks, totalChunks, documentCount, error }
export function fetchChatbotReindexStatus(jobId) {
    return api.get(`/api/admin/chatbot/reindex/${jobId}`);
}

// 코드베이스 질의응답(실행) — { answer, sources }
export function askChatbot(question) {
    return api.post('/api/chat', { question });
}
