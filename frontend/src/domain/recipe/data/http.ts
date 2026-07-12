// [AGENT] recipe(기까) 공용 API 요청 헬퍼 — 인증 필요한 데이터 호출용 (냉장고, 향후 레시피·추천)
// 세션 만료(401) 처리는 여기 한 곳: 등록된 핸들러(앱 셸의 로그인 게이트)에 알리고 중단한다.
// 화면·저장소는 401 을 몰라도 된다. authRepository 는 401 이 정상 응답(미로그인)이라 이걸 안 쓴다.

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
    const res = await fetch(path, init);
    if (res.status === 401) {
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
