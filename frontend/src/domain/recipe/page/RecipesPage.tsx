// [AGENT] recipe(기까) 레시피 탭 — 3차: 등록(URL 개별/재생목록 일괄) + 분석 대기열 + 검수용 결과 시트
// 정식 레시피 목록·상세는 4차에서 이 화면을 대체·확장한다.
// 저장소는 registrationRepository 인터페이스만 사용 (지금은 목 — 백엔드 연결 시 구현체 교체).
// 진행 중(대기/분석 중) 항목이 있으면 폴링으로 목록을 갱신 — 백엔드(DB 대기열+단일 워커)에서도 같은 방식.
// 조작은 runMutation seam (FridgePage 패턴 — 실패 문구·연타 방지·재동기화 한 곳, PLAYBOOK 관례 5).
import { useCallback, useEffect, useRef, useState } from 'react';
import type { RegistrationItem, RegistrationStatus, VideoCategory } from '../data/registrationTypes';
import { DuplicateVideoError } from '../data/registrationTypes';
import { registrationRepository } from '../data/registrationRepository';
import { parseYoutubePlaylistId, parseYoutubeVideoId } from '../data/videoUrl';
import type { RcpBadgeVariant } from '../ui/RcpBadge';
import RcpBadge from '../ui/RcpBadge';
import RcpButton from '../ui/RcpButton';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpVideoRow from '../ui/RcpVideoRow';

const POLL_MS = 2500;

// 에러 계약(CONTEXT.md): 사용자 문구는 전부 프론트 소유
const LOAD_ERROR_TEXT = '목록을 불러오지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const MUTATION_ERROR_TEXT = '등록하지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const INVALID_URL_TEXT = '유튜브 링크를 인식하지 못했어요 — 영상·쇼츠·재생목록 링크를 붙여넣어 주세요';
const DUPLICATE_TEXT = '이미 등록된 영상이에요';
const PASTE_FAIL_TEXT = '클립보드를 읽지 못했어요 — 직접 붙여넣어 주세요';

const STATUS_LABEL: Record<Exclude<RegistrationStatus, 'DONE'>, string> = {
    WAITING: '대기 중',
    ANALYZING: '분석 중',
    TOO_LONG: '긴 영상',
    FAILED: '실패',
};
// 분석 완료(DONE)는 상태 대신 분류를 보여준다 (2026-07-12 확정: 요리/유틸/기타)
const CATEGORY_LABEL: Record<VideoCategory, string> = {
    RECIPE: '완료',
    TIP: '유틸',
    ETC: '기타',
};
/** 배지 문구: 완료 항목은 분류, 나머지는 진행 상태 */
const itemLabel = (item: RegistrationItem): string =>
    item.status === 'DONE' ? CATEGORY_LABEL[item.category ?? 'ETC'] : STATUS_LABEL[item.status];
/** 배지 톤 규칙: 채움 = 정상 흐름 / 테두리만 = 제외·문제 */
const itemBadge = (item: RegistrationItem): RcpBadgeVariant => {
    if (item.status === 'DONE') return item.category === 'RECIPE' ? 'default' : 'excluded';
    if (item.status === 'ANALYZING') return 'analyzing';
    if (item.status === 'FAILED') return 'danger';
    if (item.status === 'TOO_LONG') return 'excluded';
    return 'dim'; // WAITING
};
/** 결과 시트의 상태 설명 — 완료된 요리는 추출 내용을 보여주므로 설명 불필요 */
const itemDetail = (item: RegistrationItem): string | null => {
    if (item.status === 'WAITING') return '차례를 기다리고 있어요. 분석이 끝나면 자동으로 바뀌어요.';
    if (item.status === 'ANALYZING') return '영상을 분석하고 있어요. 잠시만요.';
    if (item.status === 'TOO_LONG') return '7분이 넘는 영상이라 분석하지 않았어요 (기록만 남아요).';
    if (item.status === 'FAILED') return '분석에 여러 번 실패했어요. 다시 분석을 눌러 재시도할 수 있어요.';
    if (item.status === 'DONE' && item.category !== 'RECIPE') {
        return `요리가 아니라 ${CATEGORY_LABEL[item.category ?? 'ETC']}(으)로 분류했어요. 잘못 분류된 것 같으면 다시 분석을 눌러 주세요.`;
    }
    return null;
};
/** 다시 분석 가능: 실패 또는 요리가 아닌 걸로 분류된 완료 항목 (오판 구제) */
const canReanalyze = (item: RegistrationItem): boolean =>
    item.status === 'FAILED' || (item.status === 'DONE' && item.category !== 'RECIPE');
const TOO_LONG_NOTICE = '7분이 넘는 영상이라 분석하지 않아요 — 목록에 기록만 남겼어요';

const cookMinutesText = (minutes: number) => `조리 약 ${minutes}분`;
const playlistAddedText = (count: number) => `재생목록에서 ${count}개를 대기열에 넣었어요`;

const formatRegisteredAt = (iso: string) => {
    const d = new Date(iso);
    return `${d.getMonth() + 1}월 ${d.getDate()}일 등록`;
};

const hasActive = (items: RegistrationItem[]) =>
    items.some((i) => i.status === 'WAITING' || i.status === 'ANALYZING');

export default function RecipesPage() {
    const [items, setItems] = useState<RegistrationItem[] | null>(null); // null = 첫 로딩
    const [loadError, setLoadError] = useState<string | null>(null);
    const [registerOpen, setRegisterOpen] = useState(false);
    const [urlInput, setUrlInput] = useState('');
    const [justRegistered, setJustRegistered] = useState<string[]>([]); // 이번 시트에서 넣은 것 (확인줄)
    const [registerNotice, setRegisterNotice] = useState<string | null>(null); // 긴 영상 즉시 차단 안내 등
    const [selected, setSelected] = useState<RegistrationItem | null>(null); // 항목 탭 → 결과 시트
    const [actionError, setActionError] = useState<string | null>(null);
    const busyRef = useRef(false);

    const reload = useCallback(async () => {
        setItems(await registrationRepository.list());
    }, []);

    useEffect(() => {
        reload().catch(() => setLoadError(LOAD_ERROR_TEXT));
    }, [reload]);

    // 진행 중 항목이 있는 동안만 폴링 (분석이 다 끝나면 조용해짐)
    useEffect(() => {
        if (!items || !hasActive(items)) return undefined;
        const timer = window.setInterval(() => {
            reload().catch(() => undefined); // 폴링 실패는 조용히 — 다음 턴에 다시
        }, POLL_MS);
        return () => window.clearInterval(timer);
    }, [items, reload]);

    /** 조작 실행 seam — 실패 문구·연타 방지·재동기화 한 곳 (FridgePage 패턴) */
    const runMutation = useCallback(async (op: () => Promise<void>) => {
        if (busyRef.current) return;
        busyRef.current = true;
        setActionError(null);
        try {
            await op();
        } catch (e) {
            if (e instanceof DuplicateVideoError) setActionError(DUPLICATE_TEXT);
            else setActionError(MUTATION_ERROR_TEXT);
            await reload().catch(() => undefined);
        } finally {
            busyRef.current = false;
        }
    }, [reload]);

    const handleRegister = (rawUrl: string) => {
        const url = rawUrl.trim();
        if (!url) return;
        const playlistId = parseYoutubePlaylistId(url);
        const videoId = parseYoutubeVideoId(url);
        if (!playlistId && !videoId) {
            setActionError(INVALID_URL_TEXT); // 저장소 호출 전에 프론트가 먼저 거름
            return;
        }
        setRegisterNotice(null);
        void runMutation(async () => {
            // 영상 ID 가 있으면 영상 1개 우선 — 재생목록 안에서 공유한 영상 링크(list= 동반)를
            // 일괄 등록으로 오인하지 않게 (2026-07-12 실사용에서 발견). 재생목록 일괄은
            // 재생목록 자체 링크(v= 없음)로만 동작
            if (!videoId && playlistId) {
                const count = await registrationRepository.registerPlaylist(url);
                setJustRegistered((prev) => [...prev, playlistAddedText(count)]);
            } else {
                const item = await registrationRepository.register(url);
                setJustRegistered((prev) => [...prev, '영상 1개']);
                // 길이 컷은 등록 순간 바로 알림 (2026-07-12 확정 — 기록은 남되 막힌 걸 즉시 알게)
                if (item.status === 'TOO_LONG') setRegisterNotice(TOO_LONG_NOTICE);
            }
            setUrlInput(''); // 성공했을 때만 비움 (실패 시 입력 유지 — 재시도 배려)
            await reload();
        });
    };

    // 클립보드 읽기는 https 에서만 존재 — 없으면 버튼 자체를 숨김 (품질 기본선 6: 비보안 컨텍스트 폴백)
    const canPaste = typeof navigator !== 'undefined' && !!navigator.clipboard?.readText;
    const handlePaste = async () => {
        try {
            const text = await navigator.clipboard.readText();
            setUrlInput(text);
            handleRegister(text); // 붙여넣기 = 바로 등록 (홈 최종 UX "1탭" 지향)
        } catch {
            setActionError(PASTE_FAIL_TEXT);
        }
    };

    const handleReanalyze = (item: RegistrationItem) => runMutation(async () => {
        await registrationRepository.reanalyze(item.videoId);
        setSelected(null);
        await reload();
    });

    const openRegisterSheet = () => {
        setJustRegistered([]);
        setActionError(null);
        setRegisterNotice(null);
        setRegisterOpen(true);
    };

    const errorLine = actionError
        ? <p className="rcp-inline-error" role="alert">{actionError}</p>
        : null;

    // 요약줄: 표시 문구(완료·유틸·기타·대기 중…) 기준으로 집계, 0인 것은 숨김
    const summary = items
        ? items.reduce<{ label: string; variant: RcpBadgeVariant; count: number }[]>((acc, item) => {
            const label = itemLabel(item);
            const found = acc.find((s) => s.label === label);
            if (found) found.count += 1;
            else acc.push({ label, variant: itemBadge(item), count: 1 });
            return acc;
        }, [])
        : [];

    return (
        <main className="rcp-screen" id="rcp-recipes-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">레시피</h1>
                <p className="rcp-screen-subtitle">쇼츠 링크를 넣으면 재료와 조리법을 꺼내드려요</p>
            </header>
            {errorLine}

            {loadError && (
                <div className="rcp-shell-status" role="alert">
                    <span>{loadError}</span>
                    <RcpButton onClick={() => { setLoadError(null); reload().catch(() => setLoadError(LOAD_ERROR_TEXT)); }}>
                        다시 시도
                    </RcpButton>
                </div>
            )}
            {!loadError && items === null && <p className="rcp-empty">불러오는 중…</p>}

            {items !== null && (
                <>
                    {summary.length > 0 && (
                        <div className="rcp-summary-row" id="rcp-queue-summary" aria-label="분석 진행 요약">
                            {summary.map(({ label, variant, count }) => (
                                <RcpBadge key={label} variant={variant}>
                                    {label} {count}
                                </RcpBadge>
                            ))}
                        </div>
                    )}

                    <section id="rcp-queue-list" aria-label="분석 대기열">
                        {items.length === 0 && (
                            <p className="rcp-empty">아직 등록한 영상이 없어요. 아래 [+ 영상 등록]으로 시작해 보세요.</p>
                        )}
                        {items.map((item) => (
                            <RcpVideoRow
                                key={item.videoId}
                                title={item.title ?? item.url}
                                thumbnailUrl={item.thumbnailUrl}
                                badge={<RcpBadge variant={itemBadge(item)}>{itemLabel(item)}</RcpBadge>}
                                meta={formatRegisteredAt(item.registeredAt)}
                                onClick={() => setSelected(item)}
                            />
                        ))}
                    </section>

                    <RcpButton className="rcp-btn-full" id="rcp-register-button" onClick={openRegisterSheet}>
                        + 영상 등록
                    </RcpButton>
                </>
            )}

            <RcpBottomSheet open={registerOpen} title="영상 등록" onClose={() => setRegisterOpen(false)}>
                {errorLine}
                <div className="rcp-just-added" id="rcp-just-registered">
                    {justRegistered.length === 0
                        ? <span className="rcp-just-added-hint">방금 등록한 영상이 여기 표시돼요</span>
                        : justRegistered.map((label, i) => (
                            // eslint-disable-next-line react/no-array-index-key
                            <span key={`${label}-${i}`} className="rcp-just-added-tag">{label} ✓</span>
                        ))}
                </div>
                {registerNotice && (
                    <p className="rcp-sheet-detail" id="rcp-register-notice" role="status">{registerNotice}</p>
                )}
                {canPaste && (
                    <RcpButton id="rcp-register-paste" onClick={() => void handlePaste()}>
                        복사한 링크 붙여넣기
                    </RcpButton>
                )}
                <form
                    id="rcp-register-form"
                    className="rcp-input-row"
                    onSubmit={(e) => {
                        e.preventDefault();
                        handleRegister(urlInput);
                    }}
                >
                    <input
                        className="rcp-input"
                        id="rcp-register-input"
                        placeholder="유튜브 링크 (영상·쇼츠·재생목록)"
                        value={urlInput}
                        onChange={(e) => setUrlInput(e.target.value)}
                        inputMode="url"
                    />
                    <RcpButton type="submit" disabled={!urlInput.trim()}>등록</RcpButton>
                </form>
                {!parseYoutubeVideoId(urlInput) && parseYoutubePlaylistId(urlInput) && (
                    <p className="rcp-sheet-meta" id="rcp-register-playlist-hint">
                        재생목록 링크예요 — 안의 영상 전체를 대기열에 넣어요
                    </p>
                )}
            </RcpBottomSheet>

            <RcpBottomSheet
                open={selected !== null}
                title={selected?.recipe?.name ?? selected?.title ?? '분석 결과'}
                onClose={() => setSelected(null)}
            >
                {selected && (
                    <>
                        {errorLine}
                        <p className="rcp-sheet-meta">
                            {itemLabel(selected)} · {formatRegisteredAt(selected.registeredAt)}
                        </p>

                        {itemDetail(selected) && (
                            <p className="rcp-sheet-detail">{itemDetail(selected)}</p>
                        )}

                        {selected.status === 'DONE' && selected.recipe && (
                            <>
                                <h3 className="rcp-section-label">재료 (영상에 나온 그대로)</h3>
                                <div className="rcp-chip-group">
                                    {selected.recipe.ingredients.map((name) => (
                                        <span key={name} className="rcp-sticker">{name}</span>
                                    ))}
                                </div>
                                {selected.recipe.cookMinutes !== null && (
                                    <p className="rcp-sheet-meta">{cookMinutesText(selected.recipe.cookMinutes)}</p>
                                )}
                                <h3 className="rcp-section-label">조리 순서 요약</h3>
                                <ol className="rcp-step-list">
                                    {selected.recipe.steps.map((step) => (
                                        <li key={step}>{step}</li>
                                    ))}
                                </ol>
                            </>
                        )}

                        <a
                            className="rcp-btn rcp-btn-ghost"
                            id="rcp-result-open-source"
                            href={selected.url}
                            target="_blank"
                            rel="noreferrer"
                        >
                            원본 영상 보기
                        </a>
                        {canReanalyze(selected) && (
                            <RcpButton onClick={() => void handleReanalyze(selected)}>다시 분석</RcpButton>
                        )}
                    </>
                )}
            </RcpBottomSheet>
        </main>
    );
}
