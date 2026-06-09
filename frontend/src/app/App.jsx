// [AGENT] 앱 진입점 셸 — MainRouter 감싸기
// 연관: MainRouter.jsx
// Purpose: 앱 진입 셸 — MainRouter를 감싸 전체 라우팅 진입점을 통일

import MainRouter from './router/MainRouter.jsx';
import { AdminAuthProvider } from '@/shared/auth/AdminAuthContext.jsx';
import FloatingChatbot from '@/components/chatbot/FloatingChatbot.jsx';

function App() {
    return (
        <AdminAuthProvider>
            <MainRouter />
            <FloatingChatbot />
        </AdminAuthProvider>
    );
}

export default App;
