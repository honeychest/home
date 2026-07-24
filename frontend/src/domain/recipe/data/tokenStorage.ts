// [AGENT] 인증 토큰 저장 포트 — 지금은 localStorage 어댑터. 네이티브 전환 시 보안 저장소
// (Capacitor Preferences/Secure Storage 등)로 교체하되 get/set/clear 모양은 그대로 유지된다.
const TOKEN_KEY = 'gikka_token';

export function getToken(): string | null {
    return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
    localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
    localStorage.removeItem(TOKEN_KEY);
}
