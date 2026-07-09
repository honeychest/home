// [AGENT] recipe UI 킷 — 자석 스티커 칩 (.rcp-chip-on = 냉장고에 있음 / .rcp-chip-off = 없음)
// 냉장고 문에 붙였다 뗐다 하는 메타포. 탭하면 토글.
import type { ReactNode } from 'react';

interface RcpChipProps {
    on: boolean;
    onToggle: () => void;
    children: ReactNode;
}

export default function RcpChip({ on, onToggle, children }: RcpChipProps) {
    return (
        <button
            type="button"
            className={`rcp-chip ${on ? 'rcp-chip-on' : 'rcp-chip-off'}`}
            aria-pressed={on}
            onClick={onToggle}
        >
            {children}
        </button>
    );
}
