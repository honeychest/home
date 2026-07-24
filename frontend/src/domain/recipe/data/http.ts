// [AGENT] recipe(기까) 공용 API 요청 헬퍼 — 인증 필요한 데이터 호출용 (냉장고, 향후 레시피·추천)
// 세션 만료(401) 처리는 여기 한 곳: 등록된 핸들러(앱 셸의 로그인 게이트)에 알리고 중단한다.
// 화면·저장소는 401 을 몰라도 된다. authRepository 는 401 이 정상 응답(미로그인)이라 이걸 안 쓴다.
// (다만 API 오리진 접두사(apiUrl)·인증 헤더(authHeader)는 authRepository 도 함께 쓴다 — 아래 참고)
// 인증은 쿠키가 아니라 Authorization: Bearer 헤더 — 네이티브 앱은 API 서버와 다른 오리진이라
// 쿠키가 자동 전송되지 않지만, 헤더는 오리진과 무관하게 클라이언트가 직접 실어 보낸다.
import { getToken, clearToken } from './tokenStorage';

/** API 오리진 접두사 — 지금은 항상 같은 오리진이라 빈 문자열(상대경로 그대로).
    네이티브 앱처럼 다른 오리진에서 호출해야 하는 시점에 VITE_RECIPE_API_BASE_URL 하나만 채우면 됨. */
const API_BASE = import.meta.env.VITE_RECIPE_API_BASE_URL ?? '';

/** 저장소가 호출할 경로를 API_BASE 와 합친다 — 오리진 접두사가 필요한 곳은 항상 이 함수를 거친다. */
export function apiUrl(path: string): string {
    return `${API_BASE}${path}`;
}

/** 저장된 토큰이 있으면 Authorization 헤더로, 없으면 빈 객체(로그인 전·로그인 요청 자체가 이 경우). */
export function authHeader(): Record<string, string> {
    const token = getToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
}

let onSessionExpired: (() => void) | null = null;

/** 앱 셸(RecipeApp)이 등록 — 세션 만료 시 로그인 화면으로 전환 */
export function setSessionExpiredHandler(handler: (() => void) | null): void {
    onSessionExpired = handler;
}

/** 상태 코드가 계약 (CONTEXT.md 에러 계약) — 저장소가 코드별 분기(409=중복 등)할 때 사용 */
export class HttpError extends Error {
    constructor(public readonly status: number) {
        super(`요청 실패 (${status})`);
        this.name = 'HttpError';
    }
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(apiUrl(path), { ...init, headers: { ...init?.headers, ...authHeader() } });
    if (res.status === 401) {
        clearToken();
        onSessionExpired?.();
        throw new Error('로그인이 필요해요');
    }
    if (!res.ok) throw new HttpError(res.status);
    // DELETE/PATCH 는 본문 없음
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
}

export function jsonBody(body: unknown): RequestInit {
    return { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}
