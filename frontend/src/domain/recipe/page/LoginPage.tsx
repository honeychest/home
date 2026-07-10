// [AGENT] recipe(기까) 로그인 화면 — GIS(Google Identity Services) 버튼 (CONTEXT.md 인증 절)
// 주의: http LAN 접속(폰→PC 개발 서버)에서는 구글 정책상 GIS 가 동작하지 않음 —
// 로컬 개발은 백엔드 dev 폴백으로 로그인 없이 통과하므로 이 화면은 배포(https)에서만 보인다.
import { useEffect, useRef, useState } from 'react';
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

export default function LoginPage({ onLogin }: { onLogin: (email: string) => void }) {
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
                google.accounts.id.renderButton(buttonHost.current, {
                    theme: 'outline', size: 'large', text: 'continue_with', width: 260,
                });
            })
            .catch((e: Error) => setError(e.message));
        return () => {
            cancelled = true;
        };
    }, [onLogin]);

    return (
        <div className="rcp-login" id="rcp-login-page">
            <div className="rcp-login-brand">기까</div>
            <p className="rcp-login-tagline">기억해 뒀다가 까먹을 레시피,{'\n'}기까가 기억할게요</p>
            <div ref={buttonHost} className="rcp-login-button" id="rcp-login-google-button" />
            {error && <p className="rcp-login-error" role="alert">{error}</p>}
        </div>
    );
}
