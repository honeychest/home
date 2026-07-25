// [AGENT] 실행 환경(셸) 판정 포트 — 화면이 window/navigator 를 직접 만지지 않게 모아 두는 자리.
// (2026-07-25 신설, TWA → 네이티브(Capacitor) 전환 대비)
//
// 지금 값은 전부 "웹 셸"(브라우저·PWA·TWA) 기준이다. TWA 는 속이 크롬이라 여전히 웹 셸이며
// 서비스워커·설치 안내가 그대로 필요하다 — 달라지는 건 네이티브 셸로 바꿀 때뿐이다.
// 그때 isNativeShell() 하나가 true 가 되면서 설치 안내·서비스워커·개발모드 안내가 한꺼번에
// 꺼지도록 설계했다. 화면마다 전환용 분기를 새로 심지 않는 것이 이 파일의 존재 이유.
//
// 규칙: page/**, ui/** 는 환경 API(window.location·navigator.*·localStorage 등)를 직접 부르지
//       않고 이 포트를 거친다. 어기면 platformIsolation.test.ts 가 빌드 전에 막는다.

/** 네이티브 셸(Capacitor 등) 안에서 도는 중인가. 웹·PWA·TWA 는 전부 false.
    네이티브 어댑터에서는 이 함수만 true 로 바꾼다 — 나머지 분기가 여기에 매달려 있다. */
export function isNativeShell(): boolean {
    return false;
}

/** 홈 화면에 설치된 상태로 실행 중인가 (설치 안내를 띄울지 판단용).
    iOS 사파리는 display-mode 미디어쿼리 대신 비표준 navigator.standalone 으로만 확인 가능. */
export function isStandaloneDisplay(): boolean {
    return window.matchMedia('(display-mode: standalone)').matches
        || (window.navigator as Navigator & { standalone?: boolean }).standalone === true;
}

/** iOS 계열인가 — beforeinstallprompt 이벤트가 아예 없어 설치를 안내문으로만 유도해야 한다. */
export function isIOS(): boolean {
    return /iphone|ipad|ipod/i.test(window.navigator.userAgent);
}

/** 로컬 개발(http) 세션인가. 진짜 구글 로그인은 배포(https)에서만 되고 http 에서는 백엔드
    dev 폴백으로 통과하므로, 화면이 개발 모드 안내를 띄울지 판단하는 데 쓴다 (CONTEXT.md 인증 절).
    네이티브 셸은 파일에서 뜨거나 커스텀 스킴이라 이 판정이 무의미해져 항상 false 다. */
export function isDevSession(): boolean {
    return !isNativeShell() && window.location.protocol !== 'https:';
}

/** 서비스 워커 등록 — 크롬 안드로이드가 "홈 화면에 추가"를 완전한 standalone WebAPK 로
    만들어주는 요건. 네이티브 셸에서는 필요 없을 뿐 아니라 웹 자산이 앱에 담기면 캐시가
    꼬이므로 아예 등록하지 않는다. 실패는 조용히 무시 — 설치 품질 문제라 앱 동작을 막지 않는다. */
export function registerServiceWorker(scriptPath: string, scope: string): void {
    if (isNativeShell()) return;
    if (!('serviceWorker' in navigator)) return;
    navigator.serviceWorker.register(scriptPath, { scope }).catch(() => undefined);
}

// ── 설치 안내 재노출 시각 (웹 셸 전용 상태) ─────────────────────────────────────────
// 네이티브 셸에는 "설치 안내"라는 개념 자체가 없어 이 두 함수와 호출부가 통째로 사라진다.
// 그래서 별도 저장소 포트를 만들지 않고 셸 상태로서 여기에 둔다 (tokenStorage 와 달리
// 네이티브로 옮겨갈 값이 아니다).
const INSTALL_SHOWN_AT_KEY = 'gikka-install-shown-at';

export function getInstallPromptShownAt(): number {
    return Number(localStorage.getItem(INSTALL_SHOWN_AT_KEY) ?? 0);
}

export function markInstallPromptShown(): void {
    localStorage.setItem(INSTALL_SHOWN_AT_KEY, String(Date.now()));
}
