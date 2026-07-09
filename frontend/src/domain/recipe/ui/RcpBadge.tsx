// [AGENT] recipe UI 킷 — 배지 (.rcp-badge / .rcp-badge-expiring = 임박 포스트잇)
import type { ReactNode } from 'react';

interface RcpBadgeProps {
    variant?: 'default' | 'expiring';
    children: ReactNode;
}

export default function RcpBadge({ variant = 'default', children }: RcpBadgeProps) {
    return (
        <span className={`rcp-badge ${variant === 'expiring' ? 'rcp-badge-expiring' : ''}`.trim()}>
            {children}
        </span>
    );
}
