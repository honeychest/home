// [AGENT] recipe(기까) 앱 골격 — /recipe/* 전체를 감싸는 셸
// 하단 탭 4개(홈/추천/냉장고/보관함) + PWA manifest('기까') 주입 + 로그인 게이트(2차).
// 운영자 모드(/recipe/monitor/*)에서만 탭 묶음이 통째로 바뀐다 (2026-07-17 확정) — 오너 전용
// 기능이 대기열·사전 두 화면으로 나뉘어 자기 탭이 필요해졌고, 일반 탭(홈·냉장고…)을 그대로 두면
// 운영자 모드에서 빠져나가는 길이 "일반 탭 아무거나 누르기"뿐이라 [나가기]를 명시적으로 둔다.
import { useCallback, useEffect, useRef, useState } from 'react';
import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { Activity, BookMarked, LogOut, RotateCcw } from 'lucide-react';
import type { RcpTab } from '../ui/RcpTabBar';
import RcpTabBar from '../ui/RcpTabBar';
import RcpButton from '../ui/RcpButton';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import { authRepository } from '../data/authRepository';
import { setSessionExpiredHandler } from '../data/http';
import { getToken, initTokenStorage, isTokenStorageReady } from '../data/tokenStorage';
import {
    getInstallPromptShownAt,
    isIOS,
    isNativeShell,
    isStandaloneDisplay,
    lockDocumentScroll,
    markInstallPromptShown,
    observeViewportHeightUnit,
    registerServiceWorker,
} from '../data/platform';
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
    두 effect 로 분리 (2026-07-25 확정) — theme-color 메타 태그를 화면 전환(로그인↔앱 본체)마다
    지웠다 새로 만들면, 설치된 PWA(홈 화면 아이콘 실행)에서 안드로이드 상태바가 새 노드로의
    변경을 못 따라가 노치와 배경 사이에 회색 줄이 남고 새로고침해야만 없어지는 문제가 실사용에서
    확인됨. 태그 자체는 recipe 진입 시 1회만 만들고 이탈 시에만 지우며, 화면 전환 때는 같은
    태그의 content 값만 바꾼다 — 태그가 없는 순간이 생기지 않아야 상태바가 안정적으로 따라온다. */
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
        document.head.appendChild(themeColor);

        return () => {
            document.title = previousTitle;
            manifestLink.remove();
            themeColor.remove();
        };
    }, []);

    // 로그인 화면은 크래프트 배경, 앱 본체는 아이스화이트 배경 — 노치도 각자 배경에 맞춘다.
    // 색의 원본은 tokens.css — 여기 하드코딩하면 테마 색 변경 시 이 줄만 옛 색으로 남는다 (2026-07-13)
    useEffect(() => {
        const themeColor = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
        if (!themeColor) return;
        const appRoot = document.getElementById('rcp-app');
        const colorVar = isLoginScreen ? '--rcp-login-theme-color' : '--rcp-bg';
        themeColor.content = appRoot
            ? getComputedStyle(appRoot).getPropertyValue(colorVar).trim()
            : '';
    }, [isLoginScreen]);
}

/** 서비스 워커 등록 (2026-07-24 확정) — 크롬 안드로이드가 "홈 화면에 추가"를 완전한
    standalone WebAPK로 만들어주는 요건 중 하나. 이게 없으면 약식 바로가기로 설치돼
    상태바 아래 얇은 구분선이 안 사라지는 문제가 실사용에서 확인됨. 캐싱 없는 순수
    통과(pass-through) 워커라 recipe/sw.js scope(/recipe/)만 관여 — 다른 도메인 무관.
    등록 여부(네이티브 셸에서는 안 함) 판단은 data/platform.ts 가 소유한다. */
function useGikkaServiceWorker() {
    useEffect(() => {
        registerServiceWorker('/recipe/sw.js', '/recipe/');
    }, []);
}

/** 앱 셸 높이의 기준 단위(--rcp-vh-unit) 주입 — recipe-ui.css 의 `.rcp-app` 이 이 값으로
    높이를 못박아 문서(페이지) 스크롤 자체를 없앤다 (2026-07-25 확정). 원래 로그인 화면에만
    있던 처방인데(2026-07-24), 앱 본체도 같은 이유로 "내용이 짧은 홈·냉장고·추천에서 하단으로
    조금 스크롤되는" 증상이 있어 셸로 올렸다. 로그인 화면은 셸 안에서 렌더되므로 그대로 혜택을
    받는다 — LoginPage 에 있던 같은 훅은 제거됨(중복 감시 방지). 측정은 data/platform.ts 소유. */
function useGikkaViewportUnit() {
    useEffect(() => observeViewportHeightUnit(), []);
}

/** recipe 가 떠 있는 동안 문서 스크롤 잠금 — iOS 는 셸의 overflow:hidden 만으로는 문서가
    손가락을 따라 달랑거린다(탄성 스크롤). recipe 를 떠나면 자동 해제되어 다른 화면(트레이딩·
    관리자)의 문서 스크롤은 그대로다. 잠금 규칙의 원본은 recipe-ui.css 의 html.rcp-locked. */
function useGikkaDocumentLock() {
    useEffect(() => lockDocumentScroll(), []);
}

// ── 앱으로 설치 팝업 (2026-07-20 확정, 2026-07-24 재노출 규칙 수정) — 크롬(안드로이드)은
// beforeinstallprompt 를 가로채 [설치하기]로 바로 설치, iOS Safari 는 그 이벤트 자체가
// 없어 "공유 → 홈 화면에 추가" 안내만 보여준다.
// 재노출 기준: 닫기(dismiss)·설치(installed) 구분 없이 24시간마다 다시 뜬다 — 기준 시각은
// "닫은 시각"이 아니라 "띄운 시각"이다. 닫기는 사용자가 명시적으로 시트를 조작해야만
// 발생하는데, 그냥 앱을 꺼버리거나 백그라운드로 보내면 그 이벤트 자체가 안 잡혀 시각이 영영
// 안 남을 수 있다(2026-07-20 재지적) — 반면 "띄운 시각"은 실제로 화면에 뜨는 순간 무조건
// 기록되므로 어떻게 닫히든(명시적 닫기·그냥 나가기·앱 종료) 안정적으로 24시간 간격이 지켜진다.
// (2026-07-24 수정 — 예전엔 설치 확인 시 영구히 다시 안 띄웠는데, 그 판정에 쓰던
// localStorage 플래그가 "앱을 실제로 지워도" 안 지워져서, 재설치가 필요한 사용자에게
// 평생 추천이 안 뜨는 문제가 실사용에서 발견됨. isStandalone() 이 매번 실제 설치 상태를
// 다시 확인해주므로 별도 영구 플래그 없이 이 시간 규칙 하나로 통일 — 이미 설치돼 있으면
// isStandalone() 이 걸러주고, 지워졌으면 24시간 뒤 다시 자연스럽게 추천된다
// (2026-07-25 — 저장 열쇠·환경 판정은 data/platform.ts 로 옮김. 네이티브 셸에는 "설치 안내"
// 라는 개념 자체가 없어 이 기능이 통째로 사라지므로, 그때 지울 것을 한곳에 모아 둔 것)
// 24시간으로는 실사용 확인이 잘 안 돼 6시간으로 단축 (2026-07-25 확정) — 평소 사용자에게
// 하루 최대 4번까지 보일 수 있다는 트레이드오프를 감수하고 테스트 편의를 우선함
const INSTALL_REPROMPT_MS = 6 * 60 * 60 * 1000;

interface BeforeInstallPromptEvent extends Event {
    prompt(): Promise<void>;
    userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

/** 마지막으로 띄운 지 재노출 간격이 지났으면(또는 띄운 적 없으면) 다시 보여줄 차례 */
function isRepromptDue(): boolean {
    return Date.now() - getInstallPromptShownAt() >= INSTALL_REPROMPT_MS;
}

function useInstallPrompt() {
    const [deferredEvent, setDeferredEvent] = useState<BeforeInstallPromptEvent | null>(null);
    const [open, setOpen] = useState(false);

    useEffect(() => {
        // 네이티브 셸은 이미 "설치된 앱" 이라 설치 안내가 성립하지 않는다 (2026-07-25)
        if (isNativeShell() || isStandaloneDisplay() || !isRepromptDue()) return undefined;

        const onBeforeInstall = (e: Event) => {
            e.preventDefault(); // 브라우저 기본 미니 인포바 대신 우리 시트로
            setDeferredEvent(e as BeforeInstallPromptEvent);
            setOpen(true);
            markInstallPromptShown();
        };
        const onInstalled = () => setOpen(false);
        window.addEventListener('beforeinstallprompt', onBeforeInstall);
        window.addEventListener('appinstalled', onInstalled);

        // iOS 는 beforeinstallprompt 가 아예 없어 안내문만 — 이벤트 대신 바로 연다
        if (isIOS()) {
            setOpen(true);
            markInstallPromptShown();
        }

        return () => {
            window.removeEventListener('beforeinstallprompt', onBeforeInstall);
            window.removeEventListener('appinstalled', onInstalled);
        };
    }, []);

    const install = async () => {
        if (!deferredEvent) return;
        await deferredEvent.prompt();
        await deferredEvent.userChoice; // 수락이면 appinstalled 가 곧 뒤따라와 시트를 닫는다
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
    useGikkaViewportUnit(); // 셸 높이 기준 — 로그인·로딩 화면까지 포함해 항상 먼저 잡아둔다
    useGikkaDocumentLock(); // 문서 스크롤 잠금 — 같은 이유로 모든 단계에 걸어둔다
    const install = useInstallPrompt();
    // 설치 안내 영상 다시재생 (2026-07-25 확정) — 무한반복(loop)이 "또 해야 하나" 헷갈린다는
    // 지적으로 폐지. 끝나면 멈추고 원형 화살표 오버레이로 재생 여부를 사용자가 직접 선택.
    const installVideoRef = useRef<HTMLVideoElement>(null);
    const [installVideoEnded, setInstallVideoEnded] = useState(false);
    const replayInstallVideo = () => {
        setInstallVideoEnded(false);
        installVideoRef.current?.play();
    };
    const [auth, setAuth] = useState<AuthState>({ phase: 'loading' });
    // 저장된 토큰이 아예 없으면 서버 응답 전에도 "거의 확실히 로그아웃 상태"라고 미리 판단
    // 가능 — loading 단계를 무조건 앱 본체(그린)로 그리다가 확인 후 로그인(크래프트)으로
    // 바뀌는 배경·노치색 깜빡임을 없앤다 (2026-07-24 실사용 확인·수정).
    // 앱 켤 때 한 번만 계산하면 안 된다 — 로그아웃 직후에도 checkSession 이 다시 도는데,
    // 그 순간 이 값이 로그인 상태였던 옛 값(false)에 멈춰 있으면 로그아웃 후 로딩 찰나에
    // 그린으로 잘못 그려졌다가 로그인 화면(크래프트)으로 바뀌는 깜빡임이 다시 생긴다 —
    // 이 깜빡임이 안드로이드 상태바에 자국(선)을 남기는 것으로 실사용에서 확인됨
    // (2026-07-24) — checkSession 이 돌 때마다 토큰을 다시 읽도록 수정.
    const [probablyLoggedOut, setProbablyLoggedOut] = useState(() => !getToken());
    // 토큰 저장소 적재 게이트 (2026-07-25 신설, 네이티브 전환 대비) — 지금의 localStorage
    // 어댑터는 동기라 시작부터 준비 완료(true)이므로 웹 동작은 한 프레임도 안 바뀐다.
    // 네이티브 보안 저장소(비동기)로 갈아끼우면 여기가 false 로 시작해, 토큰을 읽기도 전에
    // 세션을 확인해 전원 로그아웃시키는 사고를 막는다. tokenStorage.ts 상단 주석 참고.
    const [storageReady, setStorageReady] = useState(isTokenStorageReady);
    useEffect(() => {
        if (storageReady) return;
        void initTokenStorage().then(() => setStorageReady(true));
    }, [storageReady]);
    const showLoginVisuals = auth.phase === 'out' || (probablyLoggedOut && auth.phase !== 'in');
    useGikkaDocumentMeta(showLoginVisuals);
    useGikkaServiceWorker();
    const inMonitor = useLocation().pathname.startsWith(MONITOR_PATH);

    const checkSession = useCallback(() => {
        setProbablyLoggedOut(!getToken());
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
        if (!storageReady) return; // 토큰을 읽기 전에 세션을 물으면 무조건 401 이 된다
        checkSession();
    }, [checkSession, storageReady]);

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
    if (!storageReady || auth.phase === 'loading') {
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

            <RcpBottomSheet
                open={install.open}
                title="앱으로 설치"
                onClose={() => { setInstallVideoEnded(false); install.dismiss(); }}
            >
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
                            (2026-07-20 확정). muted+playsInline 없으면 iOS Safari 가 자동재생을 막는다.
                            controls 는 일부러 안 씀(GIF 느낌 유지). 무한반복(loop)은 "또 해야 하나"
                            헷갈린다는 지적으로 폐지 — 끝나면 멈추고 다시재생 버튼을 오버레이한다
                            (2026-07-25 확정). */}
                        <div className="rcp-install-demo-wrap">
                            <video
                                ref={installVideoRef}
                                className="rcp-install-demo"
                                src="/recipe/gikka_ios.mp4"
                                autoPlay
                                muted
                                playsInline
                                onEnded={() => setInstallVideoEnded(true)}
                            />
                            {installVideoEnded && (
                                <button
                                    type="button"
                                    className="rcp-install-replay"
                                    aria-label="설치 안내 영상 다시 재생"
                                    onClick={replayInstallVideo}
                                >
                                    {/* 원(배지)은 span 이 그린다 — 예전엔 svg 자체에 padding 을 줘
                                        원을 만들었는데, iOS 에서 그 padding 이 안 먹어 회색 점처럼
                                        작게 보였다 (2026-07-25 실사용 제보). 치수는 recipe-ui.css 소유 */}
                                    <span className="rcp-install-replay-badge">
                                        <RotateCcw size={48} aria-hidden="true" />
                                    </span>
                                </button>
                            )}
                        </div>
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
