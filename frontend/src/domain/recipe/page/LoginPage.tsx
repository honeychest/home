// [AGENT] recipe(기까) 로그인 화면 — GIS(Google Identity Services) 버튼 (CONTEXT.md 인증 절)
// 주의: http LAN 접속(폰→PC 개발 서버)에서는 구글 정책상 GIS 가 동작하지 않음 —
// 로컬 개발은 백엔드 dev 폴백으로 로그인 없이 통과하므로 이 화면은 배포(https)에서만 보인다.
import { useEffect, useRef, useState } from 'react';
import type { AuthSession } from '../data/authRepository';
import { GOOGLE_CLIENT_ID, authRepository } from '../data/authRepository';

const GIS_SRC = 'https://accounts.google.com/gsi/client';

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
            else reject(new Error('구글 로그인 스크립트를 불러오지 못했어요'));
        };
        script.onerror = () => reject(new Error('구글 로그인 스크립트를 불러오지 못했어요'));
        document.head.appendChild(script);
    });
}

export default function LoginPage({ onLogin }: { onLogin: (session: AuthSession) => void }) {
    const buttonHost = useRef<HTMLDivElement>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        loadGisScript()
            .then((google) => {
                if (cancelled || !buttonHost.current) return;
                google.accounts.id.initialize({
                    client_id: GOOGLE_CLIENT_ID,
                    callback: ({ credential }) => {
                        authRepository.loginWithGoogle(credential)
                            .then(onLogin)
                            .catch((e: Error) => setError(e.message));
                    },
                });
                // 알약(pill) + 짧은 문구 — 길쭉한 직사각형 느낌 완화 (2026-07-12 사용자 확정)
                // width 200 미만 = 개인화 버튼(사진+이메일 2줄) 표시 안 됨 — 구글 공식 조건 (2026-07-13 사용자 확정)
                google.accounts.id.renderButton(buttonHost.current, {
                    theme: 'outline', size: 'large', text: 'signin_with', shape: 'pill', width: 199,
                });
            })
            .catch((e: Error) => setError(e.message));
        return () => {
            cancelled = true;
        };
    }, [onLogin]);

    return (
        <div className="rcp-login" id="rcp-login-page">
            {/* 태그라인 삭제 (2026-07-20 확정) — 로그인 후엔 다시 볼 일 없는 화면이라 이름만으로
                충분하다는 판단. 브랜드 유래("기억해놨다가 까먹어야지")는 필요하면 소개·설정
                화면에 남기고 여기선 안 쓴다. */}
            <div className="rcp-login-brand">기까</div>
            <div ref={buttonHost} className="rcp-login-button" id="rcp-login-google-button" />
            {error && <p className="rcp-login-error" role="alert">{error}</p>}
        </div>
    );
}
