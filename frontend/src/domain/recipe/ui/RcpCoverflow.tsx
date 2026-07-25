// [AGENT] recipe UI 킷 — 겹침형 커버플로우 (.rcp-coverflow) : 가운데 카드가 확대되며 옆 카드와 겹침
// 추천 화면(4차) 3단계 섹션에서 재사용하는 가로 스크롤 카드 목록. 목업(2026-07-14, 사용자 확정
// 값 — 가운데 확대 1.15배·옆 카드 0.62배·투명도 0.55·겹침 -60px)을 그대로 상수화했다.
// RcpShelf(냉장고 선반)와 달리 위치에 따라 크기·투명도·앞뒤 순서가 실시간으로 바뀐다.
import { useCallback, useEffect, useRef } from 'react';
import type { ReactNode } from 'react';

const CENTER_SCALE = 1.15;
const SIDE_SCALE = 0.62;
const SIDE_OPACITY = 0.55;
const OVERLAP_PX = -60;

const DRAG_CLICK_THRESHOLD_PX = 6; // 이보다 많이 움직였으면 스와이프로 보고 탭(클릭)을 무시

interface RcpCoverflowProps<T> {
    items: T[];
    keyOf: (item: T) => string;
    renderCard: (item: T) => ReactNode;
    /** 카드 탭 — 스와이프 중 손을 뗀 경우(드래그)는 호출하지 않는다 */
    onCardClick?: (item: T) => void;
    /** 카드별 추가 클래스 — 항목의 성격을 카드 테두리로 구분할 때(예: 추천의 "내 보관함").
        카드 안 배지로 구분하지 말 것: 카드가 좁아 배지가 제목의 폭을 먹는다 (2026-07-17 실사용
        발견 — 제목이 통째로 말줄임돼 안 보였음). 레이아웃에 영향 없는 표현만 쓸 것 —
        폭·여백을 바꾸면 이 컴포넌트의 카드 폭 실측(applyLayout)이 틀어진다. */
    cardClassOf?: (item: T) => string | undefined;
}

export default function RcpCoverflow<T>({ items, keyOf, renderCard, onCardClick, cardClassOf }: RcpCoverflowProps<T>) {
    const trackRef = useRef<HTMLDivElement>(null);
    const draggedRef = useRef(false);
    /** setup() 이 1번만 실측해 두는 값 — paint() 는 DOM 을 전혀 읽지 않고 이 숫자로만 계산한다 */
    const metricsRef = useRef<{
        cards: HTMLElement[]; cardWidth: number; firstCardLeft: number; trackWidth: number;
    } | null>(null);

    // 스크롤 매 프레임 실행 — 순수 산수 + 스타일 쓰기만. 이전 구현은 프레임마다 카드 20장의
    // getBoundingClientRect 읽기와 스타일 쓰기가 섞여 카드마다 강제 레이아웃 재계산이 일어났고
    // (읽기-쓰기 교차 = layout thrashing), 폰에서 스크롤이 뚝뚝 끊기는 원인이었다 (2026-07-18).
    // 카드 위치는 마진·폭이 고정이라 scrollLeft 에서 계산으로 나온다 — DOM 읽기가 필요 없다.
    const paint = useCallback(() => {
        const track = trackRef.current;
        const metrics = metricsRef.current;
        if (!track || !metrics || metrics.cardWidth === 0) return;
        const { cards, cardWidth, firstCardLeft, trackWidth } = metrics;
        const step = cardWidth + OVERLAP_PX; // 카드 하나만큼 전진하는 거리 (겹침 포함)
        const viewCenter = trackWidth / 2;
        const scrollLeft = track.scrollLeft;
        cards.forEach((card, i) => {
            const cardCenter = firstCardLeft + i * step + cardWidth / 2 - scrollLeft;
            const norm = Math.min(Math.abs(cardCenter - viewCenter) / viewCenter, 1); // 0=중앙, 1=가장자리
            const scale = CENTER_SCALE - norm * (CENTER_SCALE - SIDE_SCALE);
            const opacity = 1 - norm * (1 - SIDE_OPACITY);
            card.style.transform = `scale(${scale.toFixed(3)}) translateY(${(norm * 10).toFixed(1)}px)`;
            card.style.opacity = opacity.toFixed(3);
            card.style.zIndex = String(100 - Math.round(norm * 100));
        });
    }, []);

    // 마운트·리사이즈·items 변경 때만 — 마진·padding 세팅과 실측을 여기로 몰아 스크롤 경로에서 뺀다
    const setup = useCallback(() => {
        const track = trackRef.current;
        if (!track) return;
        const cards = Array.from(track.querySelectorAll<HTMLElement>('.rcp-coverflow-card'));
        cards.forEach((card, i) => {
            card.style.marginLeft = i === 0 ? '0px' : `${OVERLAP_PX}px`;
        });
        // 카드 폭이 고정 px 가 아니라 섹션 높이에서 역산(aspect-ratio)되므로, 첫/끝 카드도
        // 가운데에 스냅되도록 좌우 여백을 실측한 폭으로 계산한다.
        // offsetWidth/offsetLeft 사용(transform scale 은 시각적 효과일 뿐 레이아웃 박스는 안 바꿈)
        const firstCard = cards[0];
        const cardWidth = firstCard ? firstCard.offsetWidth : 0;
        if (firstCard) {
            const halfWidth = cardWidth / 2;
            track.style.paddingLeft = `calc(50% - ${halfWidth}px)`;
            // 오른쪽 여백은 padding 이 아니라 "마지막 카드의 margin" 으로 준다 (2026-07-25 확정).
            // 가로 스크롤 컨테이너의 끝쪽 padding 은 브라우저(특히 WebKit)가 스크롤 가능 범위에
            // 넣지 않아 그 몫만큼 범위가 깎인다. 카드가 3장쯤으로 적으면 깎이는 양이 전체 범위와
            // 맞먹어 스크롤이 아예 안 되고 뒤 카드에 닿을 수 없었다 (iOS 추천 "완전 가능" 실사용
            // 발견). margin 은 어느 브라우저에서나 스크롤 범위에 포함되므로 여백 총량은 그대로면서
            // 마지막 카드까지 가운데로 온다.
            track.style.paddingRight = '0px';
            const trailing = Math.max(track.clientWidth / 2 - halfWidth, 0);
            cards.forEach((card, i) => {
                card.style.marginRight = i === cards.length - 1 ? `${trailing}px` : '0px';
            });
        }
        metricsRef.current = {
            cards,
            cardWidth,
            // padding 반영 후의 실제 시작 위치 — paint 의 산수가 여기서 출발한다
            firstCardLeft: firstCard ? firstCard.offsetLeft : 0,
            trackWidth: track.clientWidth,
        };
        paint();
    }, [paint]);

    useEffect(() => {
        setup();
        window.addEventListener('resize', setup);
        return () => window.removeEventListener('resize', setup);
    }, [setup, items]);

    useEffect(() => {
        const track = trackRef.current;
        if (!track) return undefined;
        let ticking = false;
        const onScroll = () => {
            if (ticking) return;
            ticking = true;
            requestAnimationFrame(() => { paint(); ticking = false; });
        };
        track.addEventListener('scroll', onScroll, { passive: true });
        return () => track.removeEventListener('scroll', onScroll);
    }, [paint]);

    // 데스크톱(마우스 드래그) 지원 — 터치는 네이티브 스크롤 사용
    useEffect(() => {
        const track = trackRef.current;
        if (!track) return undefined;
        let dragging = false;
        let startX = 0;
        let startScroll = 0;
        const onDown = (e: PointerEvent) => {
            dragging = true;
            draggedRef.current = false;
            track.classList.add('rcp-coverflow-dragging');
            startX = e.clientX;
            startScroll = track.scrollLeft;
            track.setPointerCapture(e.pointerId);
        };
        const onMove = (e: PointerEvent) => {
            if (!dragging) return;
            if (Math.abs(e.clientX - startX) > DRAG_CLICK_THRESHOLD_PX) draggedRef.current = true;
            track.scrollLeft = startScroll - (e.clientX - startX);
        };
        const onUp = () => { dragging = false; track.classList.remove('rcp-coverflow-dragging'); };
        track.addEventListener('pointerdown', onDown);
        track.addEventListener('pointermove', onMove);
        track.addEventListener('pointerup', onUp);
        track.addEventListener('pointercancel', onUp);
        return () => {
            track.removeEventListener('pointerdown', onDown);
            track.removeEventListener('pointermove', onMove);
            track.removeEventListener('pointerup', onUp);
            track.removeEventListener('pointercancel', onUp);
        };
    }, []);

    return (
        <div className="rcp-coverflow-wrap">
            <div className="rcp-coverflow" ref={trackRef}>
                {items.map((item) => (
                    <div
                        className={['rcp-coverflow-card', cardClassOf?.(item)].filter(Boolean).join(' ')}
                        key={keyOf(item)}
                        role={onCardClick ? 'button' : undefined}
                        tabIndex={onCardClick ? 0 : undefined}
                        onClick={() => {
                            if (draggedRef.current) return; // 스와이프 끝에 탭이 함께 잡히는 것 방지
                            onCardClick?.(item);
                        }}
                    >
                        {renderCard(item)}
                    </div>
                ))}
            </div>
        </div>
    );
}
