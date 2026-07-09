// [AGENT] recipe UI 킷 — 목록 행 (.rcp-list-row) : 이름 + 배지 + 날짜 + 액션 버튼들
import type { ReactNode } from 'react';

interface RcpListRowProps {
    name: ReactNode;
    badge?: ReactNode;
    meta?: ReactNode;
    actions?: ReactNode;
}

export default function RcpListRow({ name, badge, meta, actions }: RcpListRowProps) {
    return (
        <div className="rcp-list-row">
            <span className="rcp-list-row-name">{name}</span>
            {badge}
            {meta !== undefined && <span className="rcp-list-row-meta">{meta}</span>}
            {actions !== undefined && <span className="rcp-list-row-actions">{actions}</span>}
        </div>
    );
}
