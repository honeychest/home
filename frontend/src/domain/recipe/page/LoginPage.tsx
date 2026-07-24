// [AGENT] recipe(기까) 로그인 화면 — GIS(Google Identity Services) 버튼 (CONTEXT.md 인증 절)
// 주의: http LAN 접속(폰→PC 개발 서버)에서는 구글 정책상 GIS 가 동작하지 않음 —
// 로컬 개발은 백엔드 dev 폴백으로 로그인 없이 통과하므로 이 화면은 배포(https)에서만 보인다.
import { useEffect, useRef, useState } from 'react';
import type { AuthSession } from '../data/authRepository';
import { GOOGLE_CLIENT_ID, authRepository } from '../data/authRepository';

const GIS_SRC = 'https://accounts.google.com/gsi/client';
// 태그라인 카드 콜라주 (2026-07-24 확정, 카드 스택 시안) — 줄바꿈 위치가 레이아웃 결정이라 상수로 고정
const TAGLINE_LINE_1 = '"기억해놨다가';
const TAGLINE_LINE_2 = '필요할때 까먹어야지"';

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
            {/* 태그라인 카드 콜라주 복원 (2026-07-24 확정, 2026-07-20 삭제 결정을 뒤집음) —
                문구 카드 3장 겹침 + "기까" 파일 탭으로 브랜드를 표현. 라이트 톤 고정
                (다크모드 분기 없음 — tokens.css --rcp-login-bg-gradient 참고) */}
            <div className="rcp-login-stack">
                <div className="rcp-login-card rcp-login-card-back2" aria-hidden="true" />
                <div className="rcp-login-card rcp-login-card-back1" aria-hidden="true" />
                <div className="rcp-login-card rcp-login-card-front">
                    <p className="rcp-login-memo">
                        {TAGLINE_LINE_1}
                        <br />
                        {TAGLINE_LINE_2}
                    </p>
                </div>
                <div className="rcp-login-tab">기까</div>
            </div>
            <div className="rcp-login-pedestal">
                <div ref={buttonHost} className="rcp-login-button" id="rcp-login-google-button" />
            </div>
            {error && <p className="rcp-login-error" role="alert">{error}</p>}
        </div>
    );
}
