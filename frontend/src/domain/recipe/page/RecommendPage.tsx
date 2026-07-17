// [AGENT] recipe(기까) 추천 탭 — 4차: 냉장고 재료로 지금 만들 수 있는 요리 3단계로 보여줌
// (CONTEXT.md 4차 확정, 2026-07-14 grill-me). 계산(재료/양념 분류·매칭)은 서버 책임 —
// 여기는 결과만 렌더한다. 레이아웃은 겹침형 커버플로우(RcpCoverflow, 목업에서 확정한 값).
import { useCallback, useState } from 'react';
import { ChefHat } from 'lucide-react';
import type { RecommendItem, RecommendSnapshot } from '../data/recommendTypes';
import { recommendRepository } from '../data/recommendRepository';
import { registrationRepository } from '../data/registrationRepository';
import { DuplicateVideoError } from '../data/registrationTypes';
import RcpBadge from '../ui/RcpBadge';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpButton from '../ui/RcpButton';
import RcpCoverflow from '../ui/RcpCoverflow';
import RcpInlineError from '../ui/RcpInlineError';
import RcpLoadError from '../ui/RcpLoadError';
import { useMutation } from './useMutation';
import { useQuery } from './useQuery';

const LOAD_ERROR_TEXT = '추천을 불러오지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const loadMessage = () => LOAD_ERROR_TEXT;
const MAX_VISIBLE_CHIPS = 2; // 카드 폭이 좁아 칩이 너무 많으면 사진을 가림 — 나머지는 "+n"
const cookMinutesText = (minutes: number) => `조리 약 ${minutes}분`;
// 내 것/남의 것 구분 — 추천 풀이 gikka 전체로 넓어져 남의 레시피도 뜬다 (2026-07-16 5차)
const IN_LIBRARY_LABEL = '내 보관함';
const ADD_TO_LIBRARY_TEXT = '내 보관함에 담기';
const ADD_ERROR_TEXT = '담지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const ALREADY_ADDED_TEXT = '이미 보관함에 있어요';
// 실패 문구 결정 — useMutation 에 모듈 레벨로 넘긴다 (렌더마다 재생성 방지, 에러 계약)
const addMessage = (e: unknown) =>
    e instanceof DuplicateVideoError ? ALREADY_ADDED_TEXT : ADD_ERROR_TEXT;

interface Section {
    key: string;
    label: string;
    subtitle: string;
    emptyText: string;
    items: RecommendItem[];
}

// 3단계 레이아웃은 항목 유무와 무관하게 항상 표시한다 (2026-07-14 확정 — 일부 섹션만 있을 때
// 레이아웃이 들쭉날쭉해지는 걸 사용자가 지적함). 빈 섹션은 emptyText 로 채워 자리를 지킨다.
function toSections(snapshot: RecommendSnapshot): Section[] {
    return [
        {
            key: 'complete', label: '완전 가능', subtitle: '재료·양념 다 있어요',
            emptyText: '아직 완전히 가능한 요리가 없어요', items: snapshot.complete,
        },
        {
            key: 'seasoningOnly', label: '양념만 부족', subtitle: '재료는 다 있어요',
            emptyText: '아직 양념만 있으면 되는 요리가 없어요', items: snapshot.seasoningOnly,
        },
        {
            key: 'needsIngredients', label: '재료 부족', subtitle: '부족한 순서예요',
            emptyText: '아직 재료가 조금만 부족한 요리가 없어요', items: snapshot.needsIngredients,
        },
    ];
}

function RecommendCard({ item }: { item: RecommendItem }) {
    const visible = item.missing.slice(0, MAX_VISIBLE_CHIPS);
    const moreCount = item.missing.length - visible.length;
    return (
        <>
            {item.thumbnailUrl ? (
                <img className="rcp-coverflow-thumb" src={item.thumbnailUrl} alt="" />
            ) : (
                <div className="rcp-coverflow-thumb-fallback"><ChefHat size={40} /></div>
            )}
            <div className="rcp-coverflow-vignette" />
            {/* 제목 행 안에 배지를 함께 둔다 — 카드가 좁아 겹치지 않게 flex 로 나란히
                (내 보관함에 있는 레시피만 배지, 남의 것은 담을 수 있는 새 레시피) */}
            <div className="rcp-coverflow-name">
                <span className="rcp-coverflow-title">{item.title}</span>
                {item.inLibrary && <RcpBadge variant="good">{IN_LIBRARY_LABEL}</RcpBadge>}
            </div>
            {item.missing.length > 0 && (
                <div className="rcp-coverflow-chips">
                    {visible.map((name) => <span key={name} className="rcp-coverflow-chip">{name}</span>)}
                    {moreCount > 0 && <span className="rcp-coverflow-chip rcp-coverflow-chip-more">{`+${moreCount}`}</span>}
                </div>
            )}
        </>
    );
}

export default function RecommendPage() {
    const [selected, setSelected] = useState<RecommendItem | null>(null); // 카드 탭 → 상세 팝업
    const load = useCallback(() => recommendRepository.get(), []);
    const query = useQuery<RecommendSnapshot>(load, loadMessage);
    // 남의 레시피 담기 — 공용 useMutation(실패 문구·연타 방지·재동기화). 담은 뒤 추천 재조회로
    // 배지가 "내 보관함"으로 바뀐다 (registerByVideoId = 2번에서 만든 by-video 등록 재사용)
    const mutation = useMutation(addMessage, query.reload);
    const handleAdd = (item: RecommendItem) => mutation.run(async () => {
        await registrationRepository.registerByVideoId(item.videoId);
        setSelected(null);
        await query.reload();
    });

    const snapshot = query.data;
    const loadError = query.error;
    const sections = snapshot ? toSections(snapshot) : [];
    const isEmpty = snapshot !== null && sections.every((s) => s.items.length === 0);

    return (
        <main className="rcp-screen rcp-recommend-page" id="rcp-recommend-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">추천</h1>
                <p className="rcp-screen-subtitle">냉장고 재료로 지금 만들 수 있는 요리를 보여줘요</p>
            </header>

            <RcpLoadError message={loadError} onRetry={() => void query.reload()} />
            {!loadError && snapshot === null && <p className="rcp-empty">불러오는 중…</p>}

            {!loadError && isEmpty && (
                <p className="rcp-empty">
                    아직 추천할 요리가 없어요 — 보관함 탭에서 요리 영상을 등록하고, 냉장고에 재료를 넣어보세요.
                </p>
            )}

            {!loadError && snapshot !== null && !isEmpty && (
                <div className="rcp-recommend-sections">
                    {sections.map((section) => (
                        <section className="rcp-recommend-section" key={section.key} aria-label={section.label}>
                            <div className="rcp-recommend-section-head">
                                <span className="rcp-recommend-section-title">{section.label}</span>
                                <span className="rcp-recommend-section-sub">{section.subtitle}</span>
                            </div>
                            {section.items.length === 0 ? (
                                <p className="rcp-recommend-section-empty">{section.emptyText}</p>
                            ) : (
                                <RcpCoverflow
                                    items={section.items}
                                    keyOf={(item) => item.videoId}
                                    renderCard={(item) => <RecommendCard item={item} />}
                                    onCardClick={(item) => setSelected(item)}
                                />
                            )}
                        </section>
                    ))}
                </div>
            )}

            <RcpBottomSheet
                open={selected !== null}
                title={selected?.title ?? '레시피'}
                onClose={() => setSelected(null)}
            >
                {selected && (
                    <>
                        <RcpInlineError message={mutation.error} />
                        <h3 className="rcp-section-label">재료 (영상에 나온 그대로)</h3>
                        <div className="rcp-chip-group">
                            {selected.ingredients.map((ing) => (
                                <span
                                    key={ing.name}
                                    className={`rcp-chip ${ing.have ? 'rcp-chip-on' : 'rcp-chip-off'}`}
                                >
                                    {ing.name}
                                </span>
                            ))}
                        </div>
                        {selected.cookMinutes !== null && (
                            <p className="rcp-sheet-meta">{cookMinutesText(selected.cookMinutes)}</p>
                        )}
                        {selected.steps.length > 0 && (
                            <>
                                <h3 className="rcp-section-label">조리 순서 요약</h3>
                                <ol className="rcp-step-list">
                                    {selected.steps.map((step) => <li key={step}>{step}</li>)}
                                </ol>
                            </>
                        )}
                        <a
                            className="rcp-btn rcp-btn-ghost"
                            href={selected.url}
                            target="_blank"
                            rel="noreferrer"
                        >
                            원본 영상 보기
                        </a>
                        {/* 남의 레시피면 담기 버튼 — 내 것이면 이미 보관함에 있으니 안 보여준다 */}
                        {!selected.inLibrary && (
                            <RcpButton onClick={() => void handleAdd(selected)}>{ADD_TO_LIBRARY_TEXT}</RcpButton>
                        )}
                    </>
                )}
            </RcpBottomSheet>
        </main>
    );
}
