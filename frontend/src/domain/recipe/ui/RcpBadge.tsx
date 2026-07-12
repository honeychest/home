// [AGENT] recipe UI 킷 — 배지 (.rcp-badge)
// 디자인 규칙 (2026-07-12 검수 확정): 채움 = 정상 흐름(완료·분석 중·대기) / 테두리만 = 제외·문제(요리 아님·긴 영상·실패)
// 변형: expiring(임박 포스트잇) / dim(대기) / excluded(판정 제외 — 회색 테두리) / danger(실패) / analyzing(분석 중 펄스)
import type { ReactNode } from 'react';

export type RcpBadgeVariant = 'default' | 'expiring' | 'dim' | 'excluded' | 'danger' | 'analyzing';

const VARIANT_CLASS: Record<RcpBadgeVariant, string> = {
    default: '',
    expiring: 'rcp-badge-expiring',
    dim: 'rcp-badge-dim',
    excluded: 'rcp-badge-excluded',
    danger: 'rcp-badge-danger',
    analyzing: 'rcp-badge-analyzing',
};

interface RcpBadgeProps {
    variant?: RcpBadgeVariant;
    children: ReactNode;
}

export default function RcpBadge({ variant = 'default', children }: RcpBadgeProps) {
    return (
        <span className={`rcp-badge ${VARIANT_CLASS[variant]}`.trim()}>
            {children}
        </span>
    );
}
