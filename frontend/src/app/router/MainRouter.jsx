// [AGENT] T4-ANALYSIS: /analysis 라우트 추가 (AnalysisPage)
// [AGENT] 앱 라우팅 — BrowserRouter 기반
// / → /binance 리다이렉트, /trade(TradePage), /binance(BinancePage), /analysis(AnalysisPage)
// 연관: TradePage.jsx, BinancePage.jsx, AnalysisPage.jsx, ErrorPage.tsx
import { Suspense, useEffect, useState } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';

import ErrorPage     from '../../page/error/ErrorPage.tsx';
import ErrorTest     from '../../page/error/ErrorTest.tsx';
import TestTest      from '../../page/error/TestTest.jsx';
import BinancePage   from '../../page/binance/BinancePage.jsx';
import TradePage     from '../../page/trade/TradePage.jsx';
import AdminPage     from '../../page/admin/AdminPage.jsx';
import AnalysisPage  from '../../page/analysis/AnalysisPage.jsx';
import MonitorPage      from '../../page/monitor/MonitorPage.jsx';
import LogisticsPage   from '../../page/logistics/LogisticsPage.jsx';
import AdminLoginPage   from '../../page/admin/login/AdminLoginPage.jsx';
import AdminTestLayout from '../../page/admin/test/AdminTestLayout.jsx';
import AuthTestPage    from '../../page/admin/test/AuthTestPage.jsx';
import AdminTestDomainPlaceholder from '../../page/admin/test/AdminTestDomainPlaceholder.jsx';
import RawWriterTestPage from '../../page/admin/test/RawWriterTestPage.jsx';
import ChatbotTestPage from '../../page/admin/test/ChatbotTestPage.jsx';
import RandomPage    from '../../page/random/RandomPage.jsx';
import RandomLayoutEditorPage from '../../page/random/RandomLayoutEditorPage.jsx';
import ForbiddenPage from '../../page/forbidden/ForbiddenPage.jsx';
import { SignalPage } from './lazyPages.js';

function RouteFallback() {
    const [visible, setVisible] = useState(false);

    useEffect(() => {
        const timerId = window.setTimeout(() => setVisible(true), 500);
        return () => window.clearTimeout(timerId);
    }, []);

    if (!visible) {
        return null;
    }

    return (
        <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: '#0b1220', color: '#dbe7f5' }}>
            <div style={{ textAlign: 'center' }}>
                <div style={{ fontSize: '14px', opacity: 0.72, letterSpacing: '0.08em', textTransform: 'uppercase' }}>Loading</div>
                <div style={{ marginTop: '10px', fontSize: '18px', fontWeight: 700 }}>페이지 코드 불러오는 중</div>
            </div>
        </div>
    );
}

function MainRouter() {
    return (
        <Router>
            <Routes>
                {/* 초기 접속: /binance로 리다이렉트 */}
                <Route path="/" element={<Navigate to="/binance" replace />} />

                {/* Trade 페이지 */}
                <Route path="/trade" element={<TradePage />} />

                {/* Binance 페이지 (경로 변경: / → /binance) */}
                <Route path="/binance" element={<BinancePage />} />

                {/* Signal 페이지 */}
                <Route
                    path="/signal"
                    element={(
                        <Suspense fallback={<RouteFallback />}>
                            <SignalPage />
                        </Suspense>
                    )}
                />

                {/* Analysis 페이지 */}
                <Route path="/analysis" element={<AnalysisPage />} />

                {/* Admin 페이지 */}
                <Route path="/admin" element={<AdminPage />} />

                {/* Admin 로그인 */}
                <Route path="/admin/login" element={<AdminLoginPage />} />

                {/* Admin 테스트 페이지 */}
                <Route path="/admin/test" element={<AdminTestLayout />}>
                    <Route index element={<Navigate to="auth" replace />} />
                    <Route path="auth" element={<AuthTestPage />} />
                    <Route path="raw-writer" element={<RawWriterTestPage />} />
                    <Route path="chatbot" element={<ChatbotTestPage />} />
                    <Route path="trade" element={<AdminTestDomainPlaceholder domainLabel="Trade" />} />
                    <Route path="monitor" element={<AdminTestDomainPlaceholder domainLabel="Monitor" />} />
                    <Route path="user" element={<AdminTestDomainPlaceholder domainLabel="User" />} />
                </Route>

                {/* Monitor 페이지 */}
                <Route path="/monitor" element={<MonitorPage />} />

                {/* Logistics 페이지 (전체화면, Layout 미사용) */}
                <Route path="/logistics" element={<LogisticsPage />} />

                {/*Random Picker 페이지*/}
                <Route path="/winner" element={<RandomPage />} />
                <Route path="/winner/editor" element={<RandomLayoutEditorPage />} />
                <Route path="/random" element={<Navigate to="/winner" replace />} />
                <Route path="/random/editor" element={<Navigate to="/winner/editor" replace />} />

                {/* Forbidden 페이지 (GNB/Footer 없이 단독) */}
                <Route path="/forbidden" element={<ForbiddenPage />} />

                {/* 기존 /app 경로 호환 */}
                <Route path="/app" element={<Navigate to="/binance" replace />} />

                <Route path="/test" element={<TestTest />} />

                {import.meta.env.DEV && <Route path="/error-test" element={<ErrorTest />} />}

                <Route path="*" element={<ErrorPage code="404" />} />
            </Routes>
        </Router>
    );
}

export default MainRouter;
