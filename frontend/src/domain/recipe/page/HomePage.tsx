// [AGENT] recipe(기까) 홈 화면 — X(트위터) 영상 다운로드 + 최근 분석 섬네일 줄 (CONTEXT.md "앱 골격과 홈 화면")
// 영상 등록(유튜브)은 보관함 탭의 [+ 영상 등록]이 전담 — 홈에 있던 "복사한 링크로 분석 시작"은
// X다운로드 도입 후 같은 화면에 "분석" 동작이 두 개가 되어 헷갈린다는 지적으로 폐지
// (2026-07-20 확정, 보관함 탭 기능은 그대로 — RecipesPage 참고).
// 계정 정보는 헤더 우측 트리거 → 하단 시트로 (2026-07-20, X다운로드 섹션이 늘며 화면 하단
// 계정 줄이 밀려 내려가 헤더로 옮김).
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Settings } from 'lucide-react';
import { registrationRepository } from '../data/registrationRepository';
import type { RegistrationItem } from '../data/registrationTypes';
import { HttpError } from '../data/http';
import { xDownloadRepository } from '../data/xDownloadRepository';
import type { XResolveResult, XVideoOption } from '../data/xDownloadRepository';
import { clipboardReadSupported, readClipboardText } from '../data/clipboard';
import { useMutation } from './useMutation';
import RcpButton from '../ui/RcpButton';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpInlineError from '../ui/RcpInlineError';
import RcpVideoRow from '../ui/RcpVideoRow';

const RECENT_LIMIT = 10;
const UNTITLED_VIDEO_TEXT = '제목 없는 영상';
/** 헤더 계정 트리거 표시 이름 — 이메일 전문 대신 아이디(@ 앞부분) 그대로 (2026-07-20 확정 —
    임의로 자르지 않고 아이디 길이만큼 그대로 보여준다). 전문은 계정 시트에서 확인 가능. */
const accountLabel = (email: string): string => email.split('@')[0] || email;

// X(트위터) 다운로드 — recipe 등록·분석과 완전히 별개인 오너 전용 1회성 기능 (2026-07-20 확정,
// 기록·이력 없음). 에러 계약: 상태 코드만 서버 계약이고 문구는 여기(화면)가 소유.
const X_URL_MISSING_TEXT = '링크를 먼저 붙여넣어 주세요';
const X_NOT_X_LINK_TEXT = 'X(트위터) 링크만 지원해요';
const X_NOT_FOUND_TEXT = '다운로드 가능한 영상을 찾지 못했어요 — 사진 전용 게시물은 아직 지원하지 않고, '
    + '비공개·삭제된 게시물일 수도 있어요';
const X_SERVICE_DOWN_TEXT = '지금은 영상 정보를 가져올 수 없어요 — 잠시 후 다시 시도해 주세요';
const X_RESOLVE_FAIL_TEXT = '영상 정보를 가져오지 못했어요';
const X_FALLBACK_TEXT = '해상도 클릭 후 재생되는 화면을 길게 눌러 다운로드 하세요.';
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
    // 홈 탭 오너 전용 기능이 종종 안 보인다는 제보 진단용 임시 로그 (2026-07-20) — 렌더될 때마다
    // 찍어서 RecipeApp 의 "[recipe-auth] session resolved" 로그와 값을 대조한다. 값이 다르면
    // prop 전달 버그, 같은데도 화면에 안 보이면 렌더링 쪽 버그. 원인 확정되면 제거.
    console.log('[recipe-auth] HomePage render, canViewMonitor=', canViewMonitor);
    // dev 폴백은 http(로컬 개발)에서만, 진짜 구글 로그인은 배포(https)에서만 — CONTEXT.md 인증 절
    const isDevSession = window.location.protocol !== 'https:';
    const navigate = useNavigate();
    const [accountSheetOpen, setAccountSheetOpen] = useState(false);
    const [recent, setRecent] = useState<RegistrationItem[]>([]);

    // X(트위터) 다운로드
    const [xUrl, setXUrl] = useState('');
    const [xResult, setXResult] = useState<XResolveResult | null>(null);
    const xDownload = useMutation(xDownloadMessage);

    // 화면 진입 시 클립보드에 링크가 이미 있으면 입력칸을 미리 채워둔다(2026-07-20 확정) —
    // 사용자 동작(제스처) 없이 하는 최선 시도라, 브라우저가 권한 없이 거부하면 조용히 실패하고
    // 입력칸은 그냥 비어 있는다(아래 [영상 찾기] 버튼이 재시도 겸 실제 조회를 담당).
    useEffect(() => {
        if (!clipboardReadSupported()) return;
        readClipboardText()
            .then((text) => { if (text) setXUrl(text); })
            .catch(() => undefined);
    }, []);

    // [영상 찾기] 한 번으로 끝나게 — 입력칸이 비어 있으면 그 자리에서 한 번 더 클립보드를
    // 시도(제스처가 있는 시점이라 위 자동 채움보다 성공률이 높다)한 뒤 바로 조회 (2026-07-20 확정,
    // "해상도 불러오기"에서 명칭·흐름 정리 — 붙여넣기와 조회를 분리해 두 번 탭하게 하던 것을 통합).
    const handleXFind = () => void xDownload.run(async () => {
        let url = xUrl.trim();
        if (!url && clipboardReadSupported()) {
            url = await readClipboardText().catch(() => '');
            if (url) setXUrl(url);
        }
        if (!url) {
            xDownload.setError(X_URL_MISSING_TEXT);
            return;
        }
        setXResult(null);
        setXResult(await xDownloadRepository.resolve(url));
    });

    // <a download> 는 same-origin(또는 blob:/data:) 에서만 동작 — twimg.com 은 다른 출처라
    // 그냥 href 로 걸면 브라우저가 다운로드 대신 그 주소로 이동(영상 플레이어 표시)해버린다
    // (2026-07-20 실사용 제보로 확인). 그래서 폰이 fetch 로 직접 받은 뒤 blob: 주소로 바꿔
    // 다운로드시킨다 — twimg.com CDN 이 access-control-allow-origin: * 를 내려줘서 가능
    // (서버는 여전히 관여하지 않음, 폰이 X CDN 에서 직접 받는 것 그대로).
    // 그런데 실사용에서 이 fetch 가 즉시 실패하는 사례가 나왔다(원인 미확정 — 광고 차단기의
    // "amplify" 오탐 또는 진짜 CORS 누락 둘 다 가능, 콘솔 로그 없이는 구분 불가). 원인이 뭐든
    // 대응은 같아서(서버 프록시는 안 씀 — 이전 결정 유지) 실패하면 새 창으로 그냥 열어
    // 수동 저장(길게 눌러 저장)이라도 가능하게 폴백한다 — 안내 문구(X_FALLBACK_TEXT)는 실패
    // 시점이 아니라 해상도 버튼이 뜨는 순간부터 미리 보여준다(아래 JSX, 2026-07-20 확정 —
    // "실패하고 나서야 안내가 뜨는 건 늦다"는 지적 반영). 콘솔 로그는 "[x-download]" 로 시작 —
    // 다음에 또 실패하면 브라우저 개발자도구 콘솔에서 이 태그로 필터링해 로그를 확인할 것.
    // 영상이 여러 개인 게시물에서 서로 다른 항목이 같은 화질(height)을 가질 수 있어 항목 번호까지
    // 합친 키로 구분한다 (2026-07-20, 응답이 "영상 1개"에서 목록으로 바뀌며 함께 수정)
    const [downloadingKey, setDownloadingKey] = useState<string | null>(null);
    const handleXDownload = (itemIndex: number, o: XVideoOption) => void xDownload.run(async () => {
        const key = `${itemIndex}-${o.height}`;
        setDownloadingKey(key);
        console.log('[x-download] fetch 시작', { itemIndex, height: o.height, url: o.url });
        try {
            let res: Response;
            try {
                res = await fetch(o.url);
            } catch (e) {
                console.error('[x-download] fetch 자체가 실패(네트워크/CORS/차단기 등)', e);
                window.open(o.url, '_blank', 'noopener,noreferrer');
                return;
            }
            console.log('[x-download] fetch 응답', { status: res.status, ok: res.ok,
                contentType: res.headers.get('content-type'), contentLength: res.headers.get('content-length') });
            if (!res.ok) {
                console.error('[x-download] 응답 상태 실패', res.status);
                window.open(o.url, '_blank', 'noopener,noreferrer');
                return;
            }
            const blob = await res.blob();
            console.log('[x-download] blob 확보', { size: blob.size, type: blob.type });
            const blobUrl = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = blobUrl;
            link.download = `x-video-${itemIndex + 1}-${o.height}p.mp4`;
            document.body.appendChild(link);
            link.click();
            link.remove();
            setTimeout(() => URL.revokeObjectURL(blobUrl), 1000);
            console.log('[x-download] 다운로드 트리거 완료');
        } finally {
            setDownloadingKey(null);
        }
    });

    const reloadRecent = useCallback(() => {
        registrationRepository.recentDone(RECENT_LIMIT)
            .then(setRecent)
            .catch(() => setRecent([])); // 홈 섬네일은 보조 정보 — 실패해도 화면을 막지 않는다
    }, []);

    useEffect(() => {
        reloadRecent();
    }, [reloadRecent]);

    return (
        <main className="rcp-screen" id="rcp-home-page">
            <header className="rcp-screen-header rcp-screen-header-row">
                <h1 className="rcp-screen-title">홈</h1>
                <button
                    type="button"
                    className="rcp-account-trigger"
                    id="rcp-home-account-trigger"
                    aria-label={`계정: ${email}`}
                    onClick={() => setAccountSheetOpen(true)}
                >
                    {/* 오너 본인 계정명은 화면에 안 보이게 (2026-07-20 확정 — 오너가 버그 스크린샷을
                        찍어 공유할 때 자기 계정명이 그대로 찍히는 문제. 아이콘만 남기고, 접근성용
                        aria-label 에는 그대로 실어둔다 — 화면엔 안 보여도 스크린리더는 알 수 있게) */}
                    {!canViewMonitor && <span>{accountLabel(email)}</span>}
                    <Settings size={18} aria-hidden="true" />
                </button>
            </header>

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

            {/* 오너 전용에서 "로그인만 하면 누구나"로 완화 (2026-07-20 확정) — HomePage 는
                RecipeApp 이 auth.phase==='in' 일 때만 렌더링하므로 이미 로그인 게이트를 통과한
                상태, 별도 조건 불필요 */}
            <section className="rcp-x-download" id="rcp-home-x-download">
                <h2 className="rcp-section-label">X 영상 다운로드</h2>
                <input
                    className="rcp-input"
                    id="rcp-home-x-url"
                    placeholder="X(트위터) 게시물 링크 — 복사해 왔다면 이미 채워져 있어요"
                    value={xUrl}
                    onChange={(e) => setXUrl(e.target.value)}
                />
                <RcpButton className="rcp-btn-full" id="rcp-home-x-find"
                    onClick={handleXFind} disabled={xDownload.busy}>
                    {xDownload.busy ? '찾는 중…' : '영상 찾기'}
                </RcpButton>
                <RcpInlineError message={xDownload.error} />
                {/* 영상이 여러 개인 게시물은 항목마다 하나씩 (2026-07-20 확정) */}
                {xResult && xResult.items.map((item, itemIndex) => (
                    // eslint-disable-next-line react/no-array-index-key -- 서버가 안정적인 id 를 안 줌, 목록이 그 자리에서 안 바뀜
                    <div key={itemIndex} id={`rcp-home-x-item-${itemIndex}`}>
                        <RcpVideoRow
                            title={item.title || X_UNTITLED_TEXT}
                            thumbnailUrl={item.thumbnail || null}
                        />
                        <p className="rcp-x-download-hint">{X_FALLBACK_TEXT}</p>
                        <div className="rcp-chip-group">
                            {item.options.map((o) => (
                                <button
                                    key={o.url}
                                    type="button"
                                    className="rcp-chip rcp-chip-on"
                                    disabled={xDownload.busy}
                                    onClick={() => handleXDownload(itemIndex, o)}
                                >
                                    {downloadingKey === `${itemIndex}-${o.height}` ? '받는 중…' : `${o.height}p`}
                                </button>
                            ))}
                        </div>
                    </div>
                ))}
            </section>

            <RcpBottomSheet open={accountSheetOpen} title="계정" onClose={() => setAccountSheetOpen(false)}>
                {/* 오너 본인 이메일도 시트 안에서 가림 — 헤더 트리거와 같은 이유(스크린샷 노출) */}
                {!canViewMonitor && <span className="rcp-account-email">{email}</span>}
                {isDevSession && (
                    <span className="rcp-account-dev" id="rcp-home-account-dev">
                        개발 모드 — 구글 로그인 없이 자동 접속 중이에요
                    </span>
                )}
                {canViewMonitor && (
                    <RcpButton
                        variant="ghost"
                        className="rcp-btn-full"
                        id="rcp-home-monitor-link"
                        onClick={() => { setAccountSheetOpen(false); navigate('../monitor'); }}
                    >
                        모니터링
                    </RcpButton>
                )}
                {!isDevSession && (
                    <RcpButton variant="ghost" className="rcp-btn-full" id="rcp-home-logout" onClick={onLogout}>
                        로그아웃
                    </RcpButton>
                )}
            </RcpBottomSheet>
        </main>
    );
}
