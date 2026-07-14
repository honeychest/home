// [AGENT] recipe(기까) 추천 탭 — 4차: 냉장고 재료로 지금 만들 수 있는 요리 3단계로 보여줌
// (CONTEXT.md 4차 확정, 2026-07-14 grill-me). 계산(재료/양념 분류·매칭)은 서버 책임 —
// 여기는 결과만 렌더한다. 레이아웃은 겹침형 커버플로우(RcpCoverflow, 목업에서 확정한 값).
import { useCallback, useEffect, useState } from 'react';
import { ChefHat } from 'lucide-react';
import type { RecommendItem, RecommendSnapshot } from '../data/recommendTypes';
import { recommendRepository } from '../data/recommendRepository';
import RcpButton from '../ui/RcpButton';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpCoverflow from '../ui/RcpCoverflow';

const LOAD_ERROR_TEXT = '추천을 불러오지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const MAX_VISIBLE_CHIPS = 2; // 카드 폭이 좁아 칩이 너무 많으면 사진을 가림 — 나머지는 "+n"
const cookMinutesText = (minutes: number) => `조리 약 ${minutes}분`;

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
            <div className="rcp-coverflow-name"><span>{item.title}</span></div>
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
    const [snapshot, setSnapshot] = useState<RecommendSnapshot | null>(null); // null = 첫 로딩
    const [loadError, setLoadError] = useState<string | null>(null);
    const [selected, setSelected] = useState<RecommendItem | null>(null); // 카드 탭 → 상세 팝업

    const reload = useCallback(async () => {
        setSnapshot(await recommendRepository.get());
    }, []);

    useEffect(() => {
        setLoadError(null);
        reload().catch(() => setLoadError(LOAD_ERROR_TEXT));
    }, [reload]);

    const retry = () => {
        setLoadError(null);
        reload().catch(() => setLoadError(LOAD_ERROR_TEXT));
    };

    const sections = snapshot ? toSections(snapshot) : [];
    const isEmpty = snapshot !== null && sections.every((s) => s.items.length === 0);

    return (
        <main className="rcp-screen rcp-recommend-page" id="rcp-recommend-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">추천</h1>
                <p className="rcp-screen-subtitle">냉장고 재료로 지금 만들 수 있는 요리를 보여줘요</p>
            </header>

            {loadError && (
                <div className="rcp-shell-status" role="alert">
                    <span>{loadError}</span>
                    <RcpButton onClick={retry}>다시 시도</RcpButton>
                </div>
            )}
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
                    </>
                )}
            </RcpBottomSheet>
        </main>
    );
}
