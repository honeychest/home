// [AGENT] recipe(기까) 인증 저장소 — 백엔드 /api/recipe/auth 와 통신 (CONTEXT.md 인증 절)
// 세션은 Authorization: Bearer 토큰 — 로그인 응답의 토큰을 tokenStorage 에 저장해두고 이후
// 요청마다 실어 보낸다(쿠키 아님, 네이티브 전환 대비 — 다른 오리진에서도 동일하게 동작).
// http.ts 의 request() 는 401 을 "세션 만료"로 취급해 로그인 화면으로 튕기는데, 여기선 401 이
// 정상 응답(미로그인)이라 request() 를 쓰지 않고 fetch 를 직접 부른다. 다만 오리진 접두사(apiUrl)·
// 인증 헤더(authHeader)는 다른 저장소와 함께 써야 네이티브 전환 시 http.ts 한 곳만 바꾸면 된다.
import { apiUrl, authHeader } from './http';
import { setToken, clearToken } from './tokenStorage';

/** GIS OAuth 클라이언트 ID — 공개 값 (백엔드 gikka.auth.google-client-id 와 쌍) */
export const GOOGLE_CLIENT_ID =
    '255145024341-3pgdiae32kuhalrqtcsbahjjmn54mkq2.apps.googleusercontent.com';

/** canViewMonitor: 홈 탭 모니터링 링크 노출 조건 (2026-07-13 확정 — 오너 전용, 허용 목록 재사용) */
export interface AuthSession {
    email: string;
    canViewMonitor: boolean;
}

export interface AuthRepository {
    /** 로그인 세션. 미로그인이면 null (dev 폴백 환경에서는 dev 이메일이 옴) */
    me(): Promise<AuthSession | null>;
    /** GIS 버튼이 준 credential(ID 토큰)로 로그인. */
    loginWithGoogle(credential: string): Promise<AuthSession>;
    logout(): Promise<void>;
}

export const authRepository: AuthRepository = {
    async me() {
        const res = await fetch(apiUrl('/api/recipe/auth/me'), { headers: authHeader() });
        if (res.status === 401) return null;
        if (!res.ok) throw new Error(`세션 확인 실패 (${res.status})`);
        return (await res.json()) as AuthSession;
    },

    async loginWithGoogle(credential) {
        const res = await fetch(apiUrl('/api/recipe/auth/google'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ credential }),
        });
        if (res.status === 403) throw new Error('아직 공개되지 않은 서비스예요');
        if (!res.ok) throw new Error(`로그인 실패 (${res.status})`);
        const { token, ...session } = (await res.json()) as AuthSession & { token: string };
        setToken(token);
        return session;
    },

    async logout() {
        try {
            await fetch(apiUrl('/api/recipe/auth/logout'), { method: 'POST', headers: authHeader() });
        } finally {
            clearToken();
        }
    },
};
