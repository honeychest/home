// [AGENT] recipe(기까) 로그인 화면 — GIS(Google Identity Services) 버튼 (CONTEXT.md 인증 절)
// 주의: http LAN 접속(폰→PC 개발 서버)에서는 구글 정책상 GIS 가 동작하지 않음 —
// 로컬 개발은 백엔드 dev 폴백으로 로그인 없이 통과하므로 이 화면은 배포(https)에서만 보인다.
import { useEffect, useRef, useState } from 'react';
import type { AuthSession } from '../data/authRepository';
import { authRepository } from '../data/authRepository';
import { mountGoogleSignInButton } from '../data/googleSignIn';

// 태그라인 카드 콜라주 (2026-07-24 확정, 댓글 발췌 시안) — 줄바꿈 위치가 레이아웃 결정이라 상수로 고정.
// 이 문구는 원래 유튜브 쇼츠 댓글 중 하나였다는 브랜드 배경 — 주변에 다른(흐린) 댓글을 함께 두어
// "여러 댓글 중 이걸 제목으로 골랐다"는 느낌을 낸다. 실제 이용자 데이터가 아닌 연출용 예시 문구.
const TAGLINE_LINE_1 = '"기억해놨다가';
const TAGLINE_LINE_2 = '필요할때 까먹어야지"';
const LIKE_COUNT_TEXT = '👍 1.2만';
const OTHER_COMMENTS = [
    { avatar: '김', text: '완전 꿀팁이네요' },
    { avatar: '박', text: '좋은 정보 감사합니다' },
] as const;
const OTHER_COMMENTS_AFTER = [
    { avatar: '이', text: '이건 저장해둬야지 ㄹㅇ' },
] as const;

/** 새로고침 직후 모바일 브라우저 주소창이 접히며 실제 사용 가능 높이가 한 번 더 바뀌는
    경우가 있어(2026-07-24 실사용 확인 — 새로고침 후에만 레이아웃이 한 번 뒤틀리고 스크롤이
    생김), CSS dvh 대신 JS로 측정한 실제 높이를 --rcp-vh-unit(1% 값)로 고정해 쓴다.
    변수가 아직 없을 때는 recipe-ui.css 의 fallback(1dvh)이 대신 쓰인다. */
function useStableViewportUnit() {
    useEffect(() => {
        const update = () => {
            document.documentElement.style.setProperty('--rcp-vh-unit', `${window.innerHeight / 100}px`);
        };
        update();
        window.addEventListener('resize', update);
        return () => {
            window.removeEventListener('resize', update);
            document.documentElement.style.removeProperty('--rcp-vh-unit');
        };
    }, []);
}

export default function LoginPage({ onLogin }: { onLogin: (session: AuthSession) => void }) {
    useStableViewportUnit();
    const buttonHost = useRef<HTMLDivElement>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        const host = buttonHost.current;
        if (!host) return undefined;
        return mountGoogleSignInButton({
            host,
            onCredential: (idToken) => {
                authRepository.loginWithGoogle(idToken)
                    .then(onLogin)
                    .catch((e: Error) => setError(e.message));
            },
            onError: (e) => setError(e.message),
        });
    }, [onLogin]);

    return (
        <div className="rcp-login" id="rcp-login-page">
            {/* 댓글 발췌 + 카드 콜라주 (2026-07-24 확정, 2026-07-20 삭제 결정을 뒤집음) —
                흐린 다른 댓글들 사이에 문구 카드 3장 겹침 + "기까" 파일 탭. 라이트 톤 고정
                (다크모드 분기 없음 — tokens.css --rcp-login-bg-gradient 참고) */}
            <div className="rcp-login-comments">
                {/* 연출용 다른 댓글 — 실제 데이터 아님, 스크린리더에는 노출하지 않는다 */}
                {OTHER_COMMENTS.map((c) => (
                    <div className="rcp-login-comment-row" key={c.text} aria-hidden="true">
                        <span className="rcp-login-comment-avatar">{c.avatar}</span>
                        <p className="rcp-login-comment-text">{c.text}<span className="rcp-login-comment-thumb">👍</span></p>
                    </div>
                ))}
                <div className="rcp-login-stack">
                    <div className="rcp-login-card rcp-login-card-back2" aria-hidden="true" />
                    <div className="rcp-login-card rcp-login-card-back1" aria-hidden="true" />
                    <div className="rcp-login-card rcp-login-card-front">
                        <p className="rcp-login-memo">
                            {TAGLINE_LINE_1}
                            <br />
                            {TAGLINE_LINE_2}
                        </p>
                        <p className="rcp-login-thumb-line" aria-hidden="true">{LIKE_COUNT_TEXT}</p>
                    </div>
                    <div className="rcp-login-tab">기까</div>
                </div>
                {OTHER_COMMENTS_AFTER.map((c) => (
                    <div className="rcp-login-comment-row" key={c.text} aria-hidden="true">
                        <span className="rcp-login-comment-avatar">{c.avatar}</span>
                        <p className="rcp-login-comment-text">{c.text}<span className="rcp-login-comment-thumb">👍</span></p>
                    </div>
                ))}
            </div>
            <div ref={buttonHost} className="rcp-login-button" id="rcp-login-google-button" />
            {error && <p className="rcp-login-error" role="alert">{error}</p>}
        </div>
    );
}
