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
import RcpBottomSheet from '../ui/RcpBottomSheet';
import { authRepository } from '../data/authRepository';
import { setSessionExpiredHandler } from '../data/http';
import { getToken } from '../data/tokenStorage';
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

/** 기까 전용 문서 메타(제목·manifest·테마색)를 recipe 안에서만 적용하고 나가면 원복.
    isLoginScreen 이 바뀌면(로그인↔앱 본체 전환) 테마색을 다시 계산한다 — 로그인 화면은
    크래프트 배경과 맞춰야 해서 앱 본체(그린)와 다른 색을 쓴다 (2026-07-24 확정) */
function useGikkaDocumentMeta(isLoginScreen: boolean) {
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
        const colorVar = isLoginScreen ? '--rcp-login-theme-color' : '--rcp-accent-strong';
        themeColor.content = appRoot
            ? getComputedStyle(appRoot).getPropertyValue(colorVar).trim()
            : '';
        document.head.appendChild(themeColor);

        return () => {
            document.title = previousTitle;
            manifestLink.remove();
            themeColor.remove();
        };
    }, [isLoginScreen]);
}

// ── 앱으로 설치 팝업 (2026-07-20 확정) — 크롬(안드로이드)은 beforeinstallprompt 를 가로채
// [설치하기]로 바로 설치, iOS Safari 는 그 이벤트 자체가 없어 "공유 → 홈 화면에 추가" 안내만
// 보여준다.
// 재노출 기준(2026-07-20 갱신): 실제 설치가 확인되면(appinstalled, 또는 안드로이드 설치
// 대화상자에서 수락) 영구히 다시 안 띄운다. 그 전까지는 24시간마다 다시 뜬다 — 단, 기준 시각은
// "닫은 시각"이 아니라 "띄운 시각"이다. 닫기(dismiss)는 사용자가 명시적으로 시트를 조작해야만
// 발생하는데, 그냥 앱을 꺼버리거나 백그라운드로 보내면 그 이벤트 자체가 안 잡혀 시각이 영영
// 안 남을 수 있다(2026-07-20 재지적) — 반면 "띄운 시각"은 실제로 화면에 뜨는 순간 무조건
// 기록되므로 어떻게 닫히든(명시적 닫기·그냥 나가기·앱 종료) 안정적으로 24시간 간격이 지켜진다.
const INSTALL_DONE_KEY = 'gikka-install-done';
const INSTALL_SHOWN_AT_KEY = 'gikka-install-shown-at';
const INSTALL_REPROMPT_MS = 24 * 60 * 60 * 1000;

interface BeforeInstallPromptEvent extends Event {
    prompt(): Promise<void>;
    userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

const isStandalone = () =>
    window.matchMedia('(display-mode: standalone)').matches
    // iOS Safari 는 display-mode 미디어쿼리 대신 이 비표준 플래그로만 확인 가능
    || (window.navigator as Navigator & { standalone?: boolean }).standalone === true;
const isIOS = () => /iphone|ipad|ipod/i.test(window.navigator.userAgent);

/** 마지막으로 띄운 지 재노출 간격이 지났으면(또는 띄운 적 없으면) 다시 보여줄 차례 */
function isRepromptDue(): boolean {
    const shownAt = Number(localStorage.getItem(INSTALL_SHOWN_AT_KEY) ?? 0);
    return Date.now() - shownAt >= INSTALL_REPROMPT_MS;
}

function useInstallPrompt() {
    const [deferredEvent, setDeferredEvent] = useState<BeforeInstallPromptEvent | null>(null);
    const [open, setOpen] = useState(false);

    useEffect(() => {
        if (isStandalone() || localStorage.getItem(INSTALL_DONE_KEY) || !isRepromptDue()) return undefined;

        const onBeforeInstall = (e: Event) => {
            e.preventDefault(); // 브라우저 기본 미니 인포바 대신 우리 시트로
            setDeferredEvent(e as BeforeInstallPromptEvent);
            setOpen(true);
            localStorage.setItem(INSTALL_SHOWN_AT_KEY, String(Date.now()));
        };
        const onInstalled = () => {
            localStorage.setItem(INSTALL_DONE_KEY, '1'); // 실제 설치 확인 — 영구 종료
            setOpen(false);
        };
        window.addEventListener('beforeinstallprompt', onBeforeInstall);
        window.addEventListener('appinstalled', onInstalled);

        // iOS 는 beforeinstallprompt 가 아예 없어 안내문만 — 이벤트 대신 바로 연다
        if (isIOS()) {
            setOpen(true);
            localStorage.setItem(INSTALL_SHOWN_AT_KEY, String(Date.now()));
        }

        return () => {
            window.removeEventListener('beforeinstallprompt', onBeforeInstall);
            window.removeEventListener('appinstalled', onInstalled);
        };
    }, []);

    const install = async () => {
        if (!deferredEvent) return;
        await deferredEvent.prompt();
        await deferredEvent.userChoice; // 수락이면 appinstalled 가 곧 뒤따라와 영구 종료 처리
        setDeferredEvent(null);
        setOpen(false);
    };
    const dismiss = () => setOpen(false);

    return { open, canPrompt: deferredEvent !== null, install, dismiss };
}

type AuthState =
    | { phase: 'loading' }
    | { phase: 'in'; email: string; canViewMonitor: boolean }
    | { phase: 'out' }
    | { phase: 'error'; message: string };

export default function RecipeApp() {
    const install = useInstallPrompt();
    const [auth, setAuth] = useState<AuthState>({ phase: 'loading' });
    // 저장된 토큰이 아예 없으면 서버 응답 전에도 "거의 확실히 로그아웃 상태"라고 미리 판단
    // 가능 — loading 단계를 무조건 앱 본체(그린)로 그리다가 확인 후 로그인(크래프트)으로
    // 바뀌는 배경·노치색 깜빡임을 없앤다 (2026-07-24 실사용 확인·수정)
    const [probablyLoggedOut] = useState(() => !getToken());
    const showLoginVisuals = auth.phase === 'out' || (probablyLoggedOut && auth.phase !== 'in');
    useGikkaDocumentMeta(showLoginVisuals);
    const inMonitor = useLocation().pathname.startsWith(MONITOR_PATH);

    const checkSession = useCallback(() => {
        setAuth({ phase: 'loading' });
        authRepository.me()
            .then((session) => {
                // 홈 탭 오너 전용 기능(X다운로드 등)이 종종 안 보인다는 제보 진단용 임시 로그
                // (2026-07-20) — canViewMonitor 가 서버 응답 시점부터 false 인지, 이후 어딘가에서
                // 바뀌는지 구분하는 첫 지점. 원인 확정되면 제거.
                console.log('[recipe-auth] session resolved', session);
                setAuth(session ? { phase: 'in', ...session } : { phase: 'out' });
            })
            .catch((e: Error) => setAuth({ phase: 'error', message: e.message }));
    }, []);

    useEffect(() => {
        checkSession();
    }, [checkSession]);

    // 로그아웃 = 저장된 토큰 삭제 후 세션 재확인 (배포에서는 401 → 로그인 화면으로)
    const logout = useCallback(() => {
        authRepository.logout().finally(checkSession);
    }, [checkSession]);

    // 세션 만료(토큰 30일) 시 어떤 화면에서 조작하다가도 로그인 화면으로 전환 —
    // 401 처리를 이 한 곳에 집중 (화면·저장소는 인증을 모름)
    useEffect(() => {
        setSessionExpiredHandler(() => setAuth({ phase: 'out' }));
        return () => setSessionExpiredHandler(null);
    }, []);

    const shellStatusClass = `rcp-shell-status${showLoginVisuals ? ' rcp-shell-status-login' : ''}`;
    if (auth.phase === 'loading') {
        return (
            <div className="rcp-app" id="rcp-app">
                <div className={shellStatusClass} id="rcp-shell-loading">확인 중…</div>
            </div>
        );
    }
    if (auth.phase === 'error') {
        return (
            <div className="rcp-app" id="rcp-app">
                <div className={shellStatusClass} id="rcp-shell-error" role="alert">
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
                <Route path="recipes" element={<RecipesPage canReport={auth.canViewMonitor} />} />
                <Route path="share" element={<ShareTargetPage />} />

                <Route path="styleguide" element={<StyleguidePage />} />
                {/* 운영자 모드 — 일반 탭 바에 없음(홈의 오너 링크로 진입).
                    접근 통제는 백엔드 403(허용 목록 재사용)이 실제 경계 */}
                <Route path="monitor" element={<MonitorPage />} />
                <Route path="monitor/dictionary" element={<DictionaryPage />} />
                <Route path="*" element={<Navigate to="fridge" replace />} />
            </Routes>
            <RcpTabBar tabs={inMonitor ? MONITOR_TABS : undefined} />

            <RcpBottomSheet open={install.open} title="앱으로 설치" onClose={install.dismiss}>
                {install.canPrompt ? (
                    <>
                        <p className="rcp-sheet-detail">
                            홈 화면에 추가하면 브라우저 없이 앱처럼 바로 열 수 있어요.
                        </p>
                        <RcpButton className="rcp-btn-full" onClick={() => void install.install()}>
                            설치하기
                        </RcpButton>
                    </>
                ) : (
                    <>
                        {/* 영상이 위(남는 영역을 채움), 요약 텍스트는 하단 고정 — 영상을 못 보거나
                            소리·움직임으로는 못 따라 하는 사람도 텍스트만 읽고 가능해야 한다
                            (2026-07-20 확정). GIF 처럼 자동재생·무한반복 — muted+playsInline 없으면
                            iOS Safari 가 자동재생을 막는다. controls 는 일부러 안 씀(GIF 느낌 유지). */}
                        <video
                            className="rcp-install-demo"
                            src="/recipe/gikka_ios.mp4"
                            autoPlay
                            muted
                            loop
                            playsInline
                        />
                        <div className="rcp-install-summary">
                            <p className="rcp-install-summary-title">앱으로 설치</p>
                            <p className="rcp-install-summary-steps">
                                브라우저 공유 → 더보기 → 홈 화면에 추가 → 앱 추가
                            </p>
                        </div>
                    </>
                )}
            </RcpBottomSheet>
        </div>
    );
}
