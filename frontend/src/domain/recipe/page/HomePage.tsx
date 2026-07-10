// [AGENT] recipe(기까) 홈 화면 — 2차: 계정 줄(로그인 이메일 + 로그아웃)만.
// 링크 붙여넣기·최근 분석 목록은 3차에서 이 화면에 생긴다.
// 개발 모드(https 아님 = dev 폴백 자동 로그인)에서는 로그아웃이 무의미하므로 버튼 대신 안내를 보여준다.
import RcpButton from '../ui/RcpButton';

interface HomePageProps {
    email: string;
    onLogout: () => void;
}

export default function HomePage({ email, onLogout }: HomePageProps) {
    // dev 폴백은 http(로컬 개발)에서만, 진짜 구글 로그인은 배포(https)에서만 — CONTEXT.md 인증 절
    const isDevSession = window.location.protocol !== 'https:';

    return (
        <main className="rcp-screen" id="rcp-home-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">홈</h1>
            </header>
            <section className="rcp-account" id="rcp-home-account">
                <div className="rcp-account-info">
                    <span className="rcp-account-email">{email}</span>
                    {isDevSession && (
                        <span className="rcp-account-dev" id="rcp-home-account-dev">
                            개발 모드 — 구글 로그인 없이 자동 접속 중이에요
                        </span>
                    )}
                </div>
                {!isDevSession && (
                    <RcpButton variant="ghost" id="rcp-home-logout" onClick={onLogout}>로그아웃</RcpButton>
                )}
            </section>
            <p className="rcp-empty">복사한 쇼츠 링크 붙여넣기와 최근 분석 목록이 여기에 생겨요 (3차 예정)</p>
        </main>
    );
}
