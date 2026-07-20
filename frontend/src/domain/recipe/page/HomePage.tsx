// [AGENT] recipe(기까) 홈 화면 — 3차: 붙여넣기 1탭 분석 시작 + 최근 분석 섬네일 줄 (CONTEXT.md "앱 골격과 홈 화면")
// + 2차의 계정 줄(로그인 이메일 + 로그아웃).
// 개발 모드(https 아님 = dev 폴백 자동 로그인)에서는 로그아웃·클립보드가 없어 안내로 대체한다.
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { registrationRepository } from '../data/registrationRepository';
import type { RegistrationItem } from '../data/registrationTypes';
import { registerLink } from '../data/registerLink';
import { HttpError } from '../data/http';
import { xDownloadRepository } from '../data/xDownloadRepository';
import type { XResolveResult } from '../data/xDownloadRepository';
import { useMutation } from './useMutation';
import RcpButton from '../ui/RcpButton';
import RcpInlineError from '../ui/RcpInlineError';
import RcpVideoRow from '../ui/RcpVideoRow';

const RECENT_LIMIT = 10;

// 에러 계약(CONTEXT.md): 사용자 문구는 프론트 소유
const INVALID_URL_TEXT = '복사된 내용이 유튜브 링크가 아니에요 — 영상을 공유·복사한 뒤 눌러 주세요';
const UNAVAILABLE_TEXT = '비공개이거나 삭제된 영상이에요 — 다른 링크를 복사해 주세요';
const REGISTER_FAIL_TEXT = '등록하지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const PASTE_FAIL_TEXT = '클립보드를 읽지 못했어요 — 보관함 탭에서 직접 붙여넣어 주세요';
const UNTITLED_VIDEO_TEXT = '제목 없는 영상';
const mutationMessage = () => REGISTER_FAIL_TEXT;

// X(트위터) 다운로드 — recipe 등록·분석과 완전히 별개인 오너 전용 1회성 기능 (2026-07-20 확정,
// 기록·이력 없음). 에러 계약: 상태 코드만 서버 계약이고 문구는 여기(화면)가 소유.
const X_URL_MISSING_TEXT = '링크를 먼저 붙여넣어 주세요';
const X_NOT_X_LINK_TEXT = 'X(트위터) 링크만 지원해요';
const X_NOT_FOUND_TEXT = '다운로드 가능한 영상을 찾지 못했어요 — 비공개이거나 삭제된 게시물일 수 있어요';
const X_SERVICE_DOWN_TEXT = '지금은 영상 정보를 가져올 수 없어요 — 잠시 후 다시 시도해 주세요';
const X_RESOLVE_FAIL_TEXT = '영상 정보를 가져오지 못했어요';
const X_UNTITLED_TEXT = '제목 없는 게시물';
const xDownloadMessage = (e: unknown) => {
    if (e instanceof HttpError && e.status === 400) return X_NOT_X_LINK_TEXT;
    if (e instanceof HttpError && e.status === 404) return X_NOT_FOUND_TEXT;
    if (e instanceof HttpError && e.status === 503) return X_SERVICE_DOWN_TEXT;
    return X_RESOLVE_FAIL_TEXT;
};

interface HomePageProps {
    email: string;
    /** 오너 전용 모니터링 링크 노출 조건 (2026-07-13 확정 — 서버 판정, 허용 목록 재사용) */
    canViewMonitor: boolean;
    onLogout: () => void;
}

export default function HomePage({ email, canViewMonitor, onLogout }: HomePageProps) {
    // dev 폴백은 http(로컬 개발)에서만, 진짜 구글 로그인은 배포(https)에서만 — CONTEXT.md 인증 절
    const isDevSession = window.location.protocol !== 'https:';
    const navigate = useNavigate();
    const [recent, setRecent] = useState<RegistrationItem[]>([]);
    // 조작 실행기 (공용 useMutation — 연타 방지 + 실패 문구 한 곳, PLAYBOOK 관례 5)
    const mutation = useMutation(mutationMessage);

    // X(트위터) 다운로드 — 붙여넣기 등록과는 별개 목적이라 실행기도 분리(문구 소유가 다름)
    const [xUrl, setXUrl] = useState('');
    const [xResult, setXResult] = useState<XResolveResult | null>(null);
    const xDownload = useMutation(xDownloadMessage);
    const handleXResolve = () => void xDownload.run(async () => {
        const url = xUrl.trim();
        if (!url) {
            xDownload.setError(X_URL_MISSING_TEXT);
            return;
        }
        setXResult(null);
        setXResult(await xDownloadRepository.resolve(url));
    });

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
    const handlePasteStart = () => void mutation.run(async () => {
        let text: string;
        try {
            text = await navigator.clipboard.readText();
        } catch {
            mutation.setError(PASTE_FAIL_TEXT);
            return;
        }
        // 영상 우선 규칙은 registerLink 공용 모듈이 판정 (보관함 탭·공유 수신과 단일 원본).
        // 이미 등록된 영상(duplicate) = 정상 흐름 — 대기열에서 상태 확인.
        const outcome = await registerLink(text);
        if (outcome.kind === 'invalid') {
            mutation.setError(INVALID_URL_TEXT);
            return;
        }
        if (outcome.kind === 'unavailable') {
            mutation.setError(UNAVAILABLE_TEXT);
            return;
        }
        navigate('../recipes'); // 등록 결과·진행률은 대기열이 있는 보관함 탭에서 이어 봄
    });

    return (
        <main className="rcp-screen" id="rcp-home-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">홈</h1>
            </header>

            {canPaste ? (
                <RcpButton className="rcp-btn-full" id="rcp-home-paste" onClick={handlePasteStart}>
                    복사한 링크로 분석 시작
                </RcpButton>
            ) : (
                <p className="rcp-empty" id="rcp-home-paste-unavailable">
                    개발 모드에서는 붙여넣기 버튼이 없어요 — 보관함 탭에서 링크를 직접 넣어 주세요
                </p>
            )}
            <RcpInlineError message={mutation.error} />

            <h2 className="rcp-section-label">최근 분석된 영상</h2>
            {recent.length === 0 ? (
                <p className="rcp-empty">분석이 끝난 영상이 여기 섬네일로 쌓여요</p>
            ) : (
                <div className="rcp-thumb-strip" id="rcp-home-recent" aria-label="최근 분석된 영상">
                    {/* 탭 → 보관함 탭의 결과 시트 (2026-07-18 확정 — 유튜브 직행은 실수 탭에
                        안전장치가 없어 폐지. 원본 이동은 시트 안 "원본 영상 보기"로 명시적) */}
                    {recent.map((item) => (
                        <button
                            key={item.videoId}
                            type="button"
                            className="rcp-thumb"
                            onClick={() => navigate('../recipes', { state: { openVideoId: item.videoId } })}
                        >
                            {item.thumbnailUrl
                                ? <img className="rcp-thumb-img" src={item.thumbnailUrl} alt="" loading="lazy" />
                                : <span className="rcp-thumb-img" aria-hidden="true" />}
                            {/* 이름이 비어도 버튼의 접근 이름이 비지 않게 폴백 (품질 기본선 3) */}
                            <span className="rcp-thumb-name">
                                {item.recipe?.name ?? item.title ?? UNTITLED_VIDEO_TEXT}
                            </span>
                        </button>
                    ))}
                </div>
            )}

            {canViewMonitor && (
                <section id="rcp-home-x-download">
                    <h2 className="rcp-section-label">X 영상 다운로드</h2>
                    <input
                        className="rcp-input"
                        id="rcp-home-x-url"
                        placeholder="X(트위터) 게시물 링크"
                        value={xUrl}
                        onChange={(e) => setXUrl(e.target.value)}
                    />
                    <RcpButton className="rcp-btn-full" id="rcp-home-x-resolve"
                        onClick={handleXResolve} disabled={xDownload.busy}>
                        {xDownload.busy ? '불러오는 중…' : '해상도 불러오기'}
                    </RcpButton>
                    <RcpInlineError message={xDownload.error} />
                    {xResult && (
                        <div id="rcp-home-x-options">
                            <RcpVideoRow
                                title={xResult.title || X_UNTITLED_TEXT}
                                thumbnailUrl={xResult.thumbnail || null}
                            />
                            <div className="rcp-chip-group">
                                {xResult.options.map((o) => (
                                    <a
                                        key={o.url}
                                        className="rcp-chip rcp-chip-on"
                                        href={o.url}
                                        download
                                        rel="noopener noreferrer"
                                    >
                                        {o.height}p{!o.hasAudio && ' (소리 없음)'}
                                    </a>
                                ))}
                            </div>
                        </div>
                    )}
                </section>
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
                {canViewMonitor && (
                    <RcpButton
                        variant="ghost"
                        id="rcp-home-monitor-link"
                        onClick={() => navigate('../monitor')}
                    >
                        모니터링
                    </RcpButton>
                )}
                {!isDevSession && (
                    <RcpButton variant="ghost" id="rcp-home-logout" onClick={onLogout}>로그아웃</RcpButton>
                )}
            </section>
        </main>
    );
}
