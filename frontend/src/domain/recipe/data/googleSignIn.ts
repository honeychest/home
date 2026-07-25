// [AGENT] 구글 로그인 포트 — GIS(Google Identity Services) 접촉면을 이 파일 한 곳에 가둔다.
// (2026-07-25 신설, 네이티브 전환 대비. 원래 LoginPage.tsx 안에 스크립트 로딩·초기화·버튼
//  렌더링이 60줄쯤 박혀 있던 것을 옮긴 것 — 화면은 fetch·외부 SDK 를 직접 부르지 않는다는
//  frontend/AGENTS.md 규칙을 이 화면만 어기고 있었다)
//
// 화면(LoginPage)이 아는 것: "이 자리에 로그인 버튼을 붙이고, 성공하면 ID 토큰을 달라".
//
// 네이티브 전환 시 이 파일만 네이티브 플러그인 어댑터로 교체한다. 웹 GIS 는 임베디드 WebView
// 에서 구글 정책상 차단되므로(disallowed_useragent) 재작업 자체는 피할 수 없다 — 목적은
// 재작업을 없애는 게 아니라 그 범위를 이 파일 하나로 묶어 두는 것이다.
//
// 지금 공통 인터페이스를 만들지 않은 이유: GIS 는 구글이 DOM 에 버튼을 직접 그려주는 방식이고
// 네이티브 플러그인은 signIn(): Promise<idToken> 형태라 모양이 근본적으로 다르다. 억지로
// 하나로 맞추려면 승인된 로그인 버튼 디자인까지 바꿔야 해서, 격리만 하고 인터페이스는
// 전환 시점에 정하기로 했다 (어댑터가 실제로 둘이 될 때 정하는 게 정확하다).
import { GOOGLE_CLIENT_ID } from './authRepository';

const GIS_SRC = 'https://accounts.google.com/gsi/client';
const LOAD_ERROR = '구글 로그인 스크립트를 불러오지 못했어요';

// 알약(pill) + 짧은 문구 — 길쭉한 직사각형 느낌 완화 (2026-07-12 사용자 확정)
// width 200 미만 = 개인화 버튼(사진+이메일 2줄) 표시 안 됨 — 구글 공식 조건 (2026-07-13 사용자 확정)
const BUTTON_OPTIONS = {
    theme: 'outline', size: 'large', text: 'signin_with', shape: 'pill', width: 199,
} as const;

/** GIS 전역 객체 (스크립트 로드 후 window.google 에 생김) */
interface GisGlobal {
    accounts: {
        id: {
            initialize(config: { client_id: string; callback: (r: { credential: string }) => void }): void;
            renderButton(parent: HTMLElement, options: Record<string, unknown>): void;
        };
    };
}

function loadGisScript(): Promise<GisGlobal> {
    return new Promise((resolve, reject) => {
        const existing = (window as unknown as { google?: GisGlobal }).google;
        if (existing) return resolve(existing);
        const script = document.createElement('script');
        script.src = GIS_SRC;
        script.async = true;
        script.onload = () => {
            const google = (window as unknown as { google?: GisGlobal }).google;
            if (google) resolve(google);
            else reject(new Error(LOAD_ERROR));
        };
        script.onerror = () => reject(new Error(LOAD_ERROR));
        document.head.appendChild(script);
    });
}

export interface GoogleSignInMount {
    /** 로그인 버튼을 붙일 자리 */
    host: HTMLElement;
    /** 로그인 성공 — 구글이 발급한 ID 토큰(백엔드가 서명을 검증한다) */
    onCredential: (idToken: string) => void;
    /** 버튼을 띄우지 못함. 문구는 화면이 소유하므로 원인만 전달한다 (에러 계약) */
    onError: (error: Error) => void;
}

/** 로그인 버튼을 붙인다. 돌려주는 함수를 호출하면 취소(언마운트)된다 —
    스크립트 로딩이 끝나기 전에 화면이 사라져도 콜백이 늦게 튀지 않는다. */
export function mountGoogleSignInButton({ host, onCredential, onError }: GoogleSignInMount): () => void {
    let cancelled = false;

    loadGisScript()
        .then((google) => {
            if (cancelled) return;
            google.accounts.id.initialize({
                client_id: GOOGLE_CLIENT_ID,
                callback: ({ credential }) => {
                    if (!cancelled) onCredential(credential);
                },
            });
            google.accounts.id.renderButton(host, BUTTON_OPTIONS);
        })
        .catch((e: Error) => {
            if (!cancelled) onError(e);
        });

    return () => {
        cancelled = true;
    };
}
