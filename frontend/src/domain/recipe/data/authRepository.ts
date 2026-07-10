// [AGENT] recipe(기까) 인증 저장소 — 백엔드 /api/recipe/auth 와 통신 (CONTEXT.md 인증 절)
// 세션은 HttpOnly 쿠키(gikka_token)라 JS 에서 토큰을 만지지 않는다. 로그인 여부는 me() 로만 판단.

/** GIS OAuth 클라이언트 ID — 공개 값 (백엔드 gikka.auth.google-client-id 와 쌍) */
export const GOOGLE_CLIENT_ID =
    '255145024341-3pgdiae32kuhalrqtcsbahjjmn54mkq2.apps.googleusercontent.com';

export interface AuthRepository {
    /** 로그인된 이메일. 미로그인이면 null (dev 폴백 환경에서는 dev 이메일이 옴) */
    me(): Promise<string | null>;
    /** GIS 버튼이 준 credential(ID 토큰)로 로그인. 성공 시 이메일 반환 */
    loginWithGoogle(credential: string): Promise<string>;
    logout(): Promise<void>;
}

export const authRepository: AuthRepository = {
    async me() {
        const res = await fetch('/api/recipe/auth/me');
        if (res.status === 401) return null;
        if (!res.ok) throw new Error(`세션 확인 실패 (${res.status})`);
        const body = (await res.json()) as { email: string };
        return body.email;
    },

    async loginWithGoogle(credential) {
        const res = await fetch('/api/recipe/auth/google', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ credential }),
        });
        if (res.status === 403) throw new Error('아직 공개되지 않은 서비스예요');
        if (!res.ok) throw new Error(`로그인 실패 (${res.status})`);
        const body = (await res.json()) as { email: string };
        return body.email;
    },

    async logout() {
        await fetch('/api/recipe/auth/logout', { method: 'POST' });
    },
};
