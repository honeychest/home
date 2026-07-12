// [AGENT] recipe(기까) 홈 화면 — 3차: 붙여넣기 1탭 분석 시작 + 최근 분석 섬네일 줄 (CONTEXT.md "앱 골격과 홈 화면")
// + 2차의 계정 줄(로그인 이메일 + 로그아웃).
// 개발 모드(https 아님 = dev 폴백 자동 로그인)에서는 로그아웃·클립보드가 없어 안내로 대체한다.
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { registrationRepository } from '../data/registrationRepository';
import type { RegistrationItem } from '../data/registrationTypes';
import { DuplicateVideoError } from '../data/registrationTypes';
import { parseYoutubePlaylistId, parseYoutubeVideoId } from '../data/videoUrl';
import RcpButton from '../ui/RcpButton';

const RECENT_LIMIT = 10;

// 에러 계약(CONTEXT.md): 사용자 문구는 프론트 소유
const INVALID_URL_TEXT = '복사된 내용이 유튜브 링크가 아니에요 — 영상을 공유·복사한 뒤 눌러 주세요';
const REGISTER_FAIL_TEXT = '등록하지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const PASTE_FAIL_TEXT = '클립보드를 읽지 못했어요 — 레시피 탭에서 직접 붙여넣어 주세요';

interface HomePageProps {
    email: string;
    onLogout: () => void;
}

export default function HomePage({ email, onLogout }: HomePageProps) {
    // dev 폴백은 http(로컬 개발)에서만, 진짜 구글 로그인은 배포(https)에서만 — CONTEXT.md 인증 절
    const isDevSession = window.location.protocol !== 'https:';
    const navigate = useNavigate();
    const [recent, setRecent] = useState<RegistrationItem[]>([]);
    const [pasteError, setPasteError] = useState<string | null>(null);

    const reloadRecent = useCallback(() => {
        registrationRepository.recentDone(RECENT_LIMIT)
            .then(setRecent)
            .catch(() => setRecent([])); // 홈 섬네일은 보조 정보 — 실패해도 화면을 막지 않는다
    }, []);

    useEffect(() => {
        reloadRecent();
    }, [reloadRecent]);

    // 홈 최종 UX(CONTEXT.md): 링크 복사한 채 앱 열면 바로 분석 — 웹 단계는 붙여넣기 버튼 1탭.
    // 클립보드 API 는 https 에서만 존재 (품질 기본선 6) — 없으면 버튼 대신 안내.
    const canPaste = typeof navigator !== 'undefined' && !!navigator.clipboard?.readText;
    const handlePasteStart = async () => {
        setPasteError(null);
        let text: string;
        try {
            text = await navigator.clipboard.readText();
        } catch {
            setPasteError(PASTE_FAIL_TEXT);
            return;
        }
        if (!parseYoutubeVideoId(text) && !parseYoutubePlaylistId(text)) {
            setPasteError(INVALID_URL_TEXT);
            return;
        }
        try {
            // 영상 우선 (list= 동반 공유 링크를 일괄 등록으로 오인 방지 — RecipesPage 와 동일 규칙)
            if (parseYoutubeVideoId(text)) await registrationRepository.register(text);
            else await registrationRepository.registerPlaylist(text);
        } catch (e) {
            // 이미 등록된 영상 = 정상 흐름 (대기열에서 상태 확인) — 그 외 실패만 홈에 남아 문구 표시
            if (!(e instanceof DuplicateVideoError)) {
                setPasteError(REGISTER_FAIL_TEXT);
                return;
            }
        }
        navigate('../recipes'); // 등록 결과·진행률은 대기열이 있는 레시피 탭에서 이어 봄
    };

    return (
        <main className="rcp-screen" id="rcp-home-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">홈</h1>
            </header>

            {canPaste ? (
                <RcpButton className="rcp-btn-full" id="rcp-home-paste" onClick={() => void handlePasteStart()}>
                    복사한 링크로 분석 시작
                </RcpButton>
            ) : (
                <p className="rcp-empty" id="rcp-home-paste-unavailable">
                    개발 모드에서는 붙여넣기 버튼이 없어요 — 레시피 탭에서 링크를 직접 넣어 주세요
                </p>
            )}
            {pasteError && <p className="rcp-inline-error" role="alert">{pasteError}</p>}

            <h2 className="rcp-section-label">최근 분석된 레시피</h2>
            {recent.length === 0 ? (
                <p className="rcp-empty">분석이 끝난 영상이 여기 섬네일로 쌓여요</p>
            ) : (
                <div className="rcp-thumb-strip" id="rcp-home-recent" aria-label="최근 분석된 레시피">
                    {recent.map((item) => (
                        <a key={item.videoId} className="rcp-thumb" href={item.url} target="_blank" rel="noreferrer">
                            {item.thumbnailUrl
                                ? <img className="rcp-thumb-img" src={item.thumbnailUrl} alt="" loading="lazy" />
                                : <span className="rcp-thumb-img" aria-hidden="true" />}
                            <span className="rcp-thumb-name">{item.recipe?.name ?? item.title ?? ''}</span>
                        </a>
                    ))}
                </div>
            )}

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
        </main>
    );
}
