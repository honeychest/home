// [AGENT] recipe UI 킷 — 냉장고 선반 (.rcp-shelf) : 스냅 가로 스크롤 한 줄
// 스크롤이 남아 있을 때만 오른쪽 페이드("뒤에 더 있음" 힌트)를 켠다.
// 항목이 적어 스크롤이 없으면 페이드도 없음 — 그게 정상 동작.
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';

interface RcpShelfProps {
    label: string;
    children: ReactNode;
    /** 값이 바뀔 때마다 선반을 맨 왼쪽으로 되감음 (예: 새 재료가 왼쪽에 등장할 때) */
    scrollToStartSignal?: number;
}

export default function RcpShelf({ label, children, scrollToStartSignal }: RcpShelfProps) {
    const rowRef = useRef<HTMLDivElement>(null);
    const [fade, setFade] = useState(false);

    const updateFade = useCallback(() => {
        const row = rowRef.current;
        if (!row) return;
        const scrollable = row.scrollWidth > row.clientWidth + 4;
        const atEnd = row.scrollLeft + row.clientWidth >= row.scrollWidth - 8;
        setFade(scrollable && !atEnd);
    }, []);

    useEffect(() => {
        updateFade();
        window.addEventListener('resize', updateFade);
        return () => window.removeEventListener('resize', updateFade);
    }, [updateFade, children]);

    useEffect(() => {
        if (scrollToStartSignal === undefined || scrollToStartSignal === 0) return undefined;
        const row = rowRef.current;
        if (!row) return undefined;
        // 크롬은 직전 스냅 대상 스티커를 기억했다가 스타일 변화 시 그 위치로 되돌린다(검증됨).
        // 되감는 동안 스냅을 껐다가, 등장 연출이 끝난 뒤 다시 켠다.
        row.style.scrollSnapType = 'none';
        row.scrollLeft = 0;
        const timer = window.setTimeout(() => { row.style.scrollSnapType = ''; }, 1400);
        return () => window.clearTimeout(timer);
    }, [scrollToStartSignal]);

    return (
        <>
            <h2 className="rcp-shelf-label">{label}</h2>
            <div className="rcp-shelf-wrap" data-fade={fade}>
                <div className="rcp-shelf" ref={rowRef} onScroll={updateFade}>
                    {children}
                </div>
            </div>
        </>
    );
}
