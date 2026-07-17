// [AGENT] recipe(기까) 앱 골격 — /recipe/* 전체를 감싸는 셸
// 하단 탭 4개(홈/추천/냉장고/보관함) + PWA manifest('기까') 주입 + 로그인 게이트(2차).
// 운영자 모드(/recipe/monitor/*)에서만 탭 묶음이 통째로 바뀐다 (2026-07-17 확정) — 오너 전용
// 기능이 대기열·사전 두 화면으로 나뉘어 자기 탭이 필요해졌고, 일반 탭(홈·냉장고…)을 그대로 두면
// 운영자 모드에서 빠져나가는 길이 "일반 탭 아무거나 누르기"뿐이라 [나가기]를 명시적으로 둔다.
import { useCallback, useEffect, useState } from 'react';
import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { Activity, BookMarked, LogOut } from 'lucide-react';
import type { RcpTab } from '../ui/RcpTabBar';
import RcpTabBar from '../ui/RcpTabBar';
import RcpButton from '../ui/RcpButton';
import { authRepository } from '../data/authRepository';
import { setSessionExpiredHandler } from '../data/http';
import DictionaryPage from './DictionaryPage';
import FridgePage from './FridgePage';
import HomePage from './HomePage';
import LoginPage from './LoginPage';
import MonitorPage from './MonitorPage';
import RecommendPage from './RecommendPage';
import RecipesPage from './RecipesPage';
import ShareTargetPage from './ShareTargetPage';
import StyleguidePage from './StyleguidePage';
import '../style/tokens.css';
import '../ui/recipe-ui.css';

const MONITOR_PATH = '/recipe/monitor';
/** 운영자 모드 탭 — [나가기]는 항상 홈으로 (모니터 진입 경로가 홈의 오너 링크 하나라
    "왔던 곳으로"와 결과가 같고, 뒤로가기와 달리 새로고침·PWA 재시작에도 동작이 안 갈린다) */
const MONITOR_TABS: RcpTab[] = [
    { to: MONITOR_PATH, label: '대기열', Icon: Activity },
    { to: `${MONITOR_PATH}/dictionary`, label: '사전', Icon: BookMarked },
    { to: '/recipe/home', label: '나가기', Icon: LogOut },
];

/** 기까 전용 문서 메타(제목·manifest·테마색)를 recipe 안에서만 적용하고 나가면 원복 */
function useGikkaDocumentMeta() {
    useEffect(() => {
        const previousTitle = document.title;
        document.title = '기까';

        const manifestLink = document.createElement('link');
        manifestLink.rel = 'manifest';
        manifestLink.href = '/recipe/manifest.webmanifest';
        document.head.appendChild(manifestLink);

        const themeColor = document.createElement('meta');
        themeColor.name = 'theme-color';
        // 색의 원본은 tokens.css — 여기 하드코딩하면 테마 색 변경 시 이 줄만 옛 색으로 남는다 (2026-07-13)
        const appRoot = document.getElementById('rcp-app');
        themeColor.content = appRoot
            ? getComputedStyle(appRoot).getPropertyValue('--rcp-accent-strong').trim()
            : '';
        document.head.appendChild(themeColor);

        return () => {
            document.title = previousTitle;
            manifestLink.remove();
            themeColor.remove();
        };
    }, []);
}

type AuthState =
    | { phase: 'loading' }
    | { phase: 'in'; email: string; canViewMonitor: boolean }
    | { phase: 'out' }
    | { phase: 'error'; message: string };

export default function RecipeApp() {
    useGikkaDocumentMeta();
    const [auth, setAuth] = useState<AuthState>({ phase: 'loading' });
    const inMonitor = useLocation().pathname.startsWith(MONITOR_PATH);

    const checkSession = useCallback(() => {
        setAuth({ phase: 'loading' });
        authRepository.me()
            .then((session) => setAuth(session ? { phase: 'in', ...session } : { phase: 'out' }))
            .catch((e: Error) => setAuth({ phase: 'error', message: e.message }));
    }, []);

    useEffect(() => {
        checkSession();
    }, [checkSession]);

    // 로그아웃 = 쿠키 삭제 후 세션 재확인 (배포에서는 401 → 로그인 화면으로)
    const logout = useCallback(() => {
        authRepository.logout().finally(checkSession);
    }, [checkSession]);

    // 세션 만료(쿠키 30일) 시 어떤 화면에서 조작하다가도 로그인 화면으로 전환 —
    // 401 처리를 이 한 곳에 집중 (화면·저장소는 인증을 모름)
    useEffect(() => {
        setSessionExpiredHandler(() => setAuth({ phase: 'out' }));
        return () => setSessionExpiredHandler(null);
    }, []);

    if (auth.phase === 'loading') {
        return (
            <div className="rcp-app" id="rcp-app">
                <div className="rcp-shell-status" id="rcp-shell-loading">확인 중…</div>
            </div>
        );
    }
    if (auth.phase === 'error') {
        return (
            <div className="rcp-app" id="rcp-app">
                <div className="rcp-shell-status" id="rcp-shell-error" role="alert">
                    <span>서버에 연결하지 못했어요</span>
                    <span>{auth.message}</span>
                    <RcpButton onClick={checkSession}>다시 시도</RcpButton>
                </div>
            </div>
        );
    }
    if (auth.phase === 'out') {
        return (
            <div className="rcp-app" id="rcp-app">
                <LoginPage onLogin={(session) => setAuth({ phase: 'in', ...session })} />
            </div>
        );
    }

    return (
        <div className="rcp-app" id="rcp-app">
            <Routes>
                <Route index element={<Navigate to="fridge" replace />} />
                <Route
                    path="home"
                    element={(
                        <HomePage email={auth.email} canViewMonitor={auth.canViewMonitor} onLogout={logout} />
                    )}
                />
                <Route path="recommend" element={<RecommendPage />} />
                <Route path="fridge" element={<FridgePage />} />
                <Route path="recipes" element={<RecipesPage />} />
                <Route path="share" element={<ShareTargetPage />} />

                <Route path="styleguide" element={<StyleguidePage />} />
                {/* 운영자 모드 — 일반 탭 바에 없음(홈의 오너 링크로 진입).
                    접근 통제는 백엔드 403(허용 목록 재사용)이 실제 경계 */}
                <Route path="monitor" element={<MonitorPage />} />
                <Route path="monitor/dictionary" element={<DictionaryPage />} />
                <Route path="*" element={<Navigate to="fridge" replace />} />
            </Routes>
            <RcpTabBar tabs={inMonitor ? MONITOR_TABS : undefined} />
        </div>
    );
}
