// [AGENT] recipe(기까) 추천 탭 — 4차: 냉장고 재료로 지금 만들 수 있는 요리 3단계로 보여줌
// (CONTEXT.md 4차 확정, 2026-07-14 grill-me). 계산(재료/양념 분류·매칭)은 서버 책임 —
// 여기는 결과만 렌더한다. 레이아웃은 겹침형 커버플로우(RcpCoverflow, 목업에서 확정한 값).
import { useCallback, useState } from 'react';
import { ChefHat } from 'lucide-react';
import type { RecommendItem, RecommendSnapshot } from '../data/recommendTypes';
import { recommendRepository } from '../data/recommendRepository';
import { registrationRepository } from '../data/registrationRepository';
import { DuplicateVideoError } from '../data/registrationTypes';
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
// 내 것/남의 것 구분 — 추천 풀이 gikka 전체로 넓어져 남의 레시피도 뜬다 (2026-07-16 5차).
// 표시는 카드 테두리 색 (2026-07-17 — 배지가 제목을 가려서 교체). 기본이 남의 것이고
// 내 보관함이 강조되는 방향 (그린 = --rcp-accent).
const MINE_CARD_CLASS = 'rcp-coverflow-card-mine';
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

// 스켈레톤용 — 섹션 틀(라벨 포함)은 정적이라 빈 스냅샷으로 진짜 toSections 를 재사용한다
const EMPTY_SNAPSHOT: RecommendSnapshot = { complete: [], seasoningOnly: [], needsIngredients: [] };
// 카드 자리 개수 = 첫 화면에 보이는 만큼만 (치수 고정 원칙 — 개수를 흉내 내는 게 아니다)
const SKELETON_KEYS = ['s1', 's2', 's3', 's4'];

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
                // lazy: 화면 밖 카드(가로 스크롤 뒤 수십 장)의 썸네일을 미리 안 받는다 —
                // 첫 화면에 보이는 카드만 즉시 로드 (2026-07-18 체감 로딩 개선)
                <img className="rcp-coverflow-thumb" src={item.thumbnailUrl} alt="" loading="lazy" />
            ) : (
                <div className="rcp-coverflow-thumb-fallback"><ChefHat size={40} /></div>
            )}
            <div className="rcp-coverflow-vignette" />
            {/* 제목만 둔다 — "내 보관함" 구분은 카드 테두리(rcp-coverflow-card-mine)가 담당.
                이전엔 여기 배지를 나란히 뒀는데, 카드가 좁아 배지가 제목의 폭을 먹어 제목이
                통째로 말줄임돼 안 보였다 (2026-07-17 실사용 발견). 카드 안에 이름과 경쟁하는
                요소를 넣지 말 것 — 구분은 레이아웃을 안 건드리는 표현으로. */}
            <div className="rcp-coverflow-name">
                <span className="rcp-coverflow-title">{item.title}</span>
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
            {/* 스켈레톤 — "불러오는 중…" 글자 대신 실제와 같은 치수의 틀을 즉시 그린다.
                섹션 라벨은 정적 데이터라 진짜를 그대로 쓰고 카드 자리만 회색 펄스.
                이 화면은 세로 치수가 화면 높이 고정(3분할)이라 내용이 와도 흔들림이 0 이다 */}
            {!loadError && snapshot === null && (
                <div className="rcp-recommend-sections" aria-hidden="true">
                    {toSections(EMPTY_SNAPSHOT).map((section) => (
                        <section className="rcp-recommend-section" key={section.key}>
                            <div className="rcp-recommend-section-head">
                                <span className="rcp-recommend-section-title">{section.label}</span>
                                <span className="rcp-recommend-section-sub">{section.subtitle}</span>
                            </div>
                            <div className="rcp-skeleton-row">
                                {SKELETON_KEYS.map((key) => <div className="rcp-skeleton-card" key={key} />)}
                            </div>
                        </section>
                    ))}
                </div>
            )}

            {!loadError && isEmpty && (
                <p className="rcp-empty">
                    아직 추천할 요리가 없어요 — 보관함 탭에서 요리 영상을 등록하고, 냉장고에 재료를 넣어보세요.
                </p>
            )}

            {!loadError && snapshot !== null && !isEmpty && (
                <div className="rcp-recommend-sections rcp-fade-in">
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
                                    cardClassOf={(item) => (item.inLibrary ? MINE_CARD_CLASS : undefined)}
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
