// [AGENT] recipe(기까) 레시피 탭 — 3차: 등록(URL 개별/재생목록 일괄) + 분석 대기열 + 검수용 결과 시트
// 정식 레시피 목록·상세는 4차에서 이 화면을 대체·확장한다.
// 저장소는 registrationRepository 인터페이스만 사용 (지금은 목 — 백엔드 연결 시 구현체 교체).
// 진행 중(대기/분석 중) 항목이 있으면 폴링으로 목록을 갱신 — 백엔드(DB 대기열+단일 워커)에서도 같은 방식.
// 조작은 공용 실행기(useMutation) — 실패 문구·연타 방지·재동기화 한 곳 (PLAYBOOK 관례 5).
// 등록 분기(영상 우선)는 registerLink 공용 모듈 — 홈·공유 수신과 같은 단일 원본.
import { useCallback, useEffect, useState } from 'react';
import type { RegistrationItem, RegistrationStatus, VideoCategory } from '../data/registrationTypes';
import { DuplicateVideoError } from '../data/registrationTypes';
import { registrationRepository } from '../data/registrationRepository';
import { fridgeRepository } from '../data/fridgeRepository';
import { registerLink } from '../data/registerLink';
import { parseYoutubePlaylistId, parseYoutubeVideoId } from '../data/videoUrl';
import { useMutation } from './useMutation';
import type { RcpBadgeVariant } from '../ui/RcpBadge';
import RcpBadge from '../ui/RcpBadge';
import RcpButton from '../ui/RcpButton';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpInlineError from '../ui/RcpInlineError';
import RcpVideoRow from '../ui/RcpVideoRow';

const POLL_MS = 2500;

// 에러 계약(CONTEXT.md): 사용자 문구는 전부 프론트 소유
const LOAD_ERROR_TEXT = '목록을 불러오지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const MUTATION_ERROR_TEXT = '등록하지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const INVALID_URL_TEXT = '유튜브 링크를 인식하지 못했어요 — 영상·쇼츠·재생목록 링크를 붙여넣어 주세요';
const DUPLICATE_TEXT = '이미 등록된 영상이에요';
const PASTE_FAIL_TEXT = '클립보드를 읽지 못했어요 — 직접 붙여넣어 주세요';

const STATUS_LABEL: Record<Exclude<RegistrationStatus, 'DONE'>, string> = {
    WAITING: '분석대기',
    ANALYZING: '분석중',
    TOO_LONG: '긴 영상',
    FAILED: '분석실패',
    REMOVED: '삭제됨',
};
// 분석 완료(DONE)는 상태 대신 분류를 보여준다 (2026-07-12 확정: 요리/유틸/기타)
const CATEGORY_LABEL: Record<VideoCategory, string> = {
    RECIPE: '레시피',
    TIP: '유틸',
    ETC: '기타',
};
/** 카테고리 → 배지 색 슬롯 (2026-07-14 확정, dataviz 스킬 8색 고정 순서 팔레트).
    새 카테고리가 늘면 이 순서 그대로 cat-4 부터 이어 쓴다 — 순서 변경 금지(색맹 구분성 근거) */
const CATEGORY_BADGE: Record<VideoCategory, RcpBadgeVariant> = {
    RECIPE: 'cat-1',
    TIP: 'cat-2',
    ETC: 'cat-3',
};
/** 배지 문구: 완료 항목은 분류(레시피/유틸/기타), 나머지는 진행 상태.
    모든 항목이 항상 배지를 갖는다 — 일부만 비면 카드 오른쪽 칼럼이 들쭉날쭉해져
    레이아웃 일관성이 깨짐 (2026-07-14 재확정: "완료" 대신 "레시피"로 문구만 교체) */
const itemLabel = (item: RegistrationItem): string =>
    item.status === 'DONE' ? CATEGORY_LABEL[item.category ?? 'ETC'] : STATUS_LABEL[item.status];
/** 배지 색: 완료 항목은 카테고리 팔레트, 나머지는 상태 팔레트 — 서로 절대 안 겹치는
    두 체계 (2026-07-14 재설계, dataviz 스킬 CVD 검증. 예전 "채움/테두리" 구분은 폐지) */
const itemBadge = (item: RegistrationItem): RcpBadgeVariant => {
    if (item.status === 'DONE') return CATEGORY_BADGE[item.category ?? 'ETC'];
    if (item.status === 'ANALYZING') return 'analyzing';
    if (item.status === 'FAILED') return 'critical';
    if (item.status === 'REMOVED') return 'serious';
    if (item.status === 'TOO_LONG') return 'warning';
    return 'neutral'; // WAITING
};
/** 결과 시트의 상태 설명 — 완료된 요리는 추출 내용을 보여주므로 설명 불필요 */
const itemDetail = (item: RegistrationItem): string | null => {
    if (item.status === 'WAITING') return '차례를 기다리고 있어요. 분석이 끝나면 자동으로 바뀌어요.';
    if (item.status === 'ANALYZING') return '영상을 분석하고 있어요. 잠시만요.';
    if (item.status === 'TOO_LONG') return '7분이 넘는 영상이라 분석하지 않았어요 (기록만 남아요).';
    if (item.status === 'FAILED') return '분석에 여러 번 실패했어요.';
    if (item.status === 'REMOVED') return '이 영상은 시스템에서 제거됐어요.';
    if (item.status === 'DONE' && item.category !== 'RECIPE') {
        return `요리가 아니라 ${CATEGORY_LABEL[item.category ?? 'ETC']}(으)로 분류했어요.`;
    }
    return null;
};
const TOO_LONG_NOTICE = '7분이 넘는 영상이라 분석하지 않아요 — 목록에 기록만 남겼어요';

// 카드 제목: AI가 추출한 요리 이름이 원본 영상 제목보다 "무슨 요리인지" 더 잘 드러남
// (2026-07-14 확정 — 이전엔 저장만 하고 목록엔 안 보여주고 있었음)
const itemTitle = (item: RegistrationItem): string => item.recipe?.name || item.title || item.url;
const cookMinutesText = (minutes: number) => `조리 약 ${minutes}분`;
const playlistAddedText = (count: number) => `재생목록에서 ${count}개를 대기열에 넣었어요`;
// 문구/데이터 분리 (품질 기본선 7): 조합 문구는 템플릿 한 곳에
// 필터로 선택된 배지는 체크 표시를 붙인다 — 색만으로 구분하지 않기 위한 보조 신호(색맹 대비)
const summaryBadgeText = (label: string, count: number, active: boolean) =>
    active ? `${label} ${count} ✓` : `${label} ${count}`;
const justAddedTagText = (label: string) => `${label} ✓`;
const resultMetaText = (item: RegistrationItem) =>
    `${itemLabel(item)} · ${formatRegisteredAt(item.registeredAt)}`;
// 실패 문구 결정 — useMutation 에 모듈 레벨로 넘긴다 (렌더마다 재생성 방지)
const mutationMessage = (e: unknown) =>
    e instanceof DuplicateVideoError ? DUPLICATE_TEXT : MUTATION_ERROR_TEXT;

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
    // 요약 배지 탭 → 필터 (2026-07-14 확정). 지금은 전체 로드 + 클라이언트 필터로 충분
    // (개인용, 1000건도 부담 없는 규모) — 페이징 도입 시 서버 쿼리 파라미터 방식으로 교체 예정.
    const [activeFilter, setActiveFilter] = useState<string | null>(null);
    // 상세 시트의 재료별 있음/없음 표시용 (2026-07-14 확정) — 정확 매칭, 정규화 없음
    const [fridgeNames, setFridgeNames] = useState<Set<string> | null>(null);

    const reload = useCallback(async () => {
        setItems(await registrationRepository.list());
    }, []);

    useEffect(() => {
        fridgeRepository.list().then((list) => setFridgeNames(new Set(list.map((i) => i.name)))).catch(() => undefined);
    }, []);

    // 조작 실행기 (공용 useMutation — 실패 문구·연타 방지·재동기화 한 곳, PLAYBOOK 관례 5)
    const mutation = useMutation(mutationMessage, reload);

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

    const handleRegister = (rawUrl: string) => {
        const url = rawUrl.trim();
        if (!url) return;
        setRegisterNotice(null);
        void mutation.run(async () => {
            // 영상 우선 규칙은 registerLink 공용 모듈이 판정 (홈·공유 수신과 단일 원본)
            const outcome = await registerLink(url);
            if (outcome.kind === 'invalid') {
                mutation.setError(INVALID_URL_TEXT);
                return;
            }
            if (outcome.kind === 'duplicate') {
                mutation.setError(DUPLICATE_TEXT); // 이 화면에서는 안내 문구로 (홈·공유는 정상 통과)
                return;
            }
            setUrlInput(''); // 성공했을 때만 비움 (실패 시 입력 유지 — 재시도 배려)
            if (outcome.kind === 'playlist') {
                setJustRegistered((prev) => [...prev, playlistAddedText(outcome.added)]);
                setRegisterOpen(false); // 등록 성공하면 팝업도 닫는다 (2026-07-14 확정)
            } else {
                setJustRegistered((prev) => [...prev, '영상 1개']);
                // 길이 컷은 등록 순간 바로 알림 (2026-07-12 확정 — 기록은 남되 막힌 걸 즉시 알게).
                // 이 경우엔 팝업을 닫지 않고 경고를 보여준다 — 자동 닫힘과 즉시 안내가 충돌하는
                // 유일한 경로 (2026-07-14 확정).
                if (outcome.item.status === 'TOO_LONG') {
                    setRegisterNotice(TOO_LONG_NOTICE);
                } else {
                    setRegisterOpen(false);
                }
            }
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
            mutation.setError(PASTE_FAIL_TEXT);
        }
    };

    // 내 목록에서 지우기 — 내 연결만 삭제 (다른 사용자·video 자체는 무관, 확인 팝업 없이 즉시 —
    // 냉장고 삭제와 동일한 개인 데이터 관리 패턴, 2026-07-14 확정)
    const handleUnregister = (item: RegistrationItem) => mutation.run(async () => {
        await registrationRepository.unregister(item.videoId);
        setSelected(null);
        // 낙관적 업데이트 (2026-07-14 확정): 성공을 이미 아니까 전체 재조회 대신 로컬에서
        // 바로 제거 — 재조회 왕복이 없어져 삭제 직후 체감 지연이 사라진다.
        // 실패 시엔 이 줄까지 오지 않고 useMutation 의 자동 재동기화(reload)가 서버 상태로
        // 복구한다 (삭제 안 된 항목이 다시 나타남 — 별도 복구 로직 불필요).
        setItems((prev) => prev?.filter((i) => i.videoId !== item.videoId) ?? prev);
    });

    const openRegisterSheet = () => {
        setJustRegistered([]);
        mutation.setError(null);
        setRegisterNotice(null);
        setRegisterOpen(true);
    };

    const errorLine = <RcpInlineError message={mutation.error} />;

    // 요약줄: 표시 문구(레시피·유틸·기타·분석대기…) 기준으로 집계, 0인 것은 숨김
    const summary = items
        ? items.reduce<{ label: string; variant: RcpBadgeVariant; count: number }[]>((acc, item) => {
            const label = itemLabel(item);
            const found = acc.find((s) => s.label === label);
            if (found) found.count += 1;
            else acc.push({ label, variant: itemBadge(item), count: 1 });
            return acc;
        }, [])
        : [];
    // 요약 배지 필터 적용 — summary 자체는 항상 전체 items 기준으로 집계되므로(위),
    // 필터가 걸려도 활성 배지는 그대로 남아 다시 탭하면 해제할 수 있다
    const displayedItems = (items && activeFilter) ? items.filter((item) => itemLabel(item) === activeFilter) : items ?? [];

    return (
        <main className="rcp-screen" id="rcp-recipes-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">보관함</h1>
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
                        <div className="rcp-summary-row" id="rcp-queue-summary" aria-label="분석 진행 요약 · 필터">
                            {summary.map(({ label, variant, count }) => (
                                <button
                                    key={label}
                                    type="button"
                                    className="rcp-summary-badge-btn"
                                    aria-pressed={activeFilter === label}
                                    onClick={() => setActiveFilter((prev) => (prev === label ? null : label))}
                                >
                                    <RcpBadge variant={variant}>
                                        {summaryBadgeText(label, count, activeFilter === label)}
                                    </RcpBadge>
                                </button>
                            ))}
                        </div>
                    )}

                    <section id="rcp-queue-list" aria-label="분석 대기열">
                        {displayedItems.length === 0 && (
                            <p className="rcp-empty">
                                {items.length === 0
                                    ? '아직 등록한 영상이 없어요. 아래 [+ 영상 등록]으로 시작해 보세요.'
                                    : '이 필터에 해당하는 항목이 없어요.'}
                            </p>
                        )}
                        {displayedItems.map((item) => (
                            <RcpVideoRow
                                key={item.videoId}
                                title={itemTitle(item)}
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
                            <span key={`${label}-${i}`} className="rcp-just-added-tag">{justAddedTagText(label)}</span>
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
                        <p className="rcp-sheet-meta">{resultMetaText(selected)}</p>

                        {itemDetail(selected) && (
                            <p className="rcp-sheet-detail">{itemDetail(selected)}</p>
                        )}

                        {/* TIP/ETC 요점 요약 — 검수용 노출 (정식 화면은 2단계, 2026-07-13 확정) */}
                        {selected.status === 'DONE' && selected.summary && (
                            <>
                                <h3 className="rcp-section-label">요점 요약</h3>
                                <p className="rcp-sheet-detail">{selected.summary}</p>
                            </>
                        )}

                        {selected.status === 'DONE' && selected.recipe && (
                            <>
                                <h3 className="rcp-section-label">재료 (영상에 나온 그대로)</h3>
                                <div className="rcp-chip-group">
                                    {selected.recipe.ingredients.map((name) => {
                                        // 냉장고 보유 여부 표시 (2026-07-14 확정) — 정확 매칭, 정규화 없음.
                                        // 조회 실패(fridgeNames===null)면 판정 없이 중립 스티커로
                                        const haveClass = fridgeNames === null ? 'rcp-sticker'
                                            : `rcp-chip ${fridgeNames.has(name) ? 'rcp-chip-on' : 'rcp-chip-off'}`;
                                        return <span key={name} className={haveClass}>{name}</span>;
                                    })}
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

                        {/* 검색 태그 — 전 분류 공통 적립분의 검수용 노출 */}
                        {selected.status === 'DONE' && selected.tags && selected.tags.length > 0 && (
                            <>
                                <h3 className="rcp-section-label">검색 태그</h3>
                                <div className="rcp-chip-group">
                                    {selected.tags.map((tag) => (
                                        <span key={tag} className="rcp-sticker">{tag}</span>
                                    ))}
                                </div>
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
                        <RcpButton variant="ghost" onClick={() => void handleUnregister(selected)}>
                            내 목록에서 지우기
                        </RcpButton>
                    </>
                )}
            </RcpBottomSheet>
        </main>
    );
}
