// [AGENT] 인증 토큰 저장 포트 — 지금은 localStorage 어댑터.
//
// "읽기는 동기, 적재는 비동기"로 나눠 둔 이유 (2026-07-25 확정, 네이티브 전환 대비):
// 네이티브 보안 저장소(Capacitor Preferences/SecureStorage)는 읽기가 전부 Promise 다.
// getToken() 자체를 비동기로 바꾸면 http.ts 의 authHeader() → request() → 저장소 전부로
// async 가 번져서, 셸 교체가 저장소 10개를 고치는 일이 되어 버린다. 그래서 앱 시작 시
// initTokenStorage() 로 한 번만 읽어 메모리에 올리고 getToken() 은 그 값을 동기로 돌려준다.
// 어댑터가 비동기가 돼도 시그니처가 안 흔들린다.
//
// 전환 시 고칠 곳: 아래 "어댑터" 블록 3개 함수 + 초기값 두 줄. 그 밖은 손대지 않는다.
const TOKEN_KEY = 'gikka_token';

// ── 어댑터 (localStorage) ────────────────────────────────────────────────────────
// 아래 세 함수는 저장소가 없는 환경(vitest 의 node 환경 등)에서도 터지지 않아야 한다 —
// 이 모듈은 로드되는 순간 저장소를 읽으므로, import 만 해도 깨지면 그 모듈을 쓰는 순수
// 로직 테스트까지 전부 실패한다 (clipboard.ts 의 기능 감지와 같은 사상).
function storage(): Storage | null {
    return typeof localStorage === 'undefined' ? null : localStorage;
}

function readRaw(): string | null {
    return storage()?.getItem(TOKEN_KEY) ?? null;
}

function writeRaw(token: string): void {
    storage()?.setItem(TOKEN_KEY, token);
}

function removeRaw(): void {
    storage()?.removeItem(TOKEN_KEY);
}

// 웹 어댑터는 localStorage(동기)라 모듈 로드 시점에 바로 채운다 — initTokenStorage() 를
// 기다리기 전에도 값이 유효해 첫 렌더가 한 프레임도 지연되지 않는다. 이건 중요한 조건인데,
// 셸이 "토큰 유무"로 로그인/앱 배경색을 미리 정하기 때문이다(RecipeApp 의 probablyLoggedOut) —
// 여기서 잠깐이라도 null 이 나오면 로그인 배경으로 그렸다가 앱 배경으로 바뀌는 깜빡임이
// 생기고, 그게 안드로이드 상태바에 자국을 남기는 문제로 이미 확인된 적이 있다 (2026-07-24).
// 비동기 저장소를 쓰는 네이티브 어댑터에서는 이 두 줄을 `null` / `false` 로 바꾼다.
// 그러면 셸이 initTokenStorage() 를 기다렸다가 첫 화면을 그린다.
let cachedToken: string | null = readRaw();
let loaded = true;

/** 앱 시작 시 1회 — 저장소에서 토큰을 읽어 메모리에 올린다. 이게 끝나야 getToken() 이 유효하다.
    (웹 어댑터에서는 이미 채워져 있어 사실상 확인 사살이다) */
export async function initTokenStorage(): Promise<void> {
    cachedToken = readRaw();
    loaded = true;
}

/** initTokenStorage() 가 끝났는가. 셸(RecipeApp)이 첫 렌더와 세션 확인을 이걸로 막는다 —
    이 게이트가 없으면 비동기 어댑터에서 토큰을 읽기도 전에 세션을 확인해 전원 로그아웃된다. */
export function isTokenStorageReady(): boolean {
    return loaded;
}

export function getToken(): string | null {
    return cachedToken;
}

export function setToken(token: string): void {
    cachedToken = token;
    writeRaw(token);
}

export function clearToken(): void {
    cachedToken = null;
    removeRaw();
}
