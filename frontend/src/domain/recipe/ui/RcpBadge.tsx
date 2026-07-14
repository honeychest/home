// [AGENT] recipe UI 킷 — 배지 (.rcp-badge)
// 디자인 규칙 (2026-07-14 확정 — dataviz 스킬로 CVD 검증 후 재설계): 모든 배지는 같은
// "파스텔 배경 + 진한 글자" 칩 모양으로 통일한다(채움/테두리 구분 폐지 — 의미는 오직 색으로).
// 두 색 체계로 나뉜다:
//   - 카테고리(cat-1~8): 콘텐츠 분류(레시피·유틸·기타 등). 고정 순서 팔레트, 늘어나면 다음 슬롯.
//   - 상태(neutral/analyzing/good/warning/serious/critical): 진행 상태의 고정 의미. 카테고리
//     팔레트와 절대 안 겹치는 예약색 — "실패"가 우연히 어떤 카테고리와 같은 색이면 안 됨.
// expiring(임박 포스트잇)은 냉장고 메타포 전용이라 이 체계 밖에 그대로 둔다.
import type { ReactNode } from 'react';

export type RcpBadgeVariant =
    | 'expiring'
    | 'neutral' | 'analyzing' | 'good' | 'warning' | 'serious' | 'critical'
    | 'cat-1' | 'cat-2' | 'cat-3' | 'cat-4' | 'cat-5' | 'cat-6' | 'cat-7' | 'cat-8';

const VARIANT_CLASS: Record<RcpBadgeVariant, string> = {
    expiring: 'rcp-badge-expiring',
    neutral: 'rcp-badge-neutral',
    analyzing: 'rcp-badge-analyzing',
    good: 'rcp-badge-good',
    warning: 'rcp-badge-warning',
    serious: 'rcp-badge-serious',
    critical: 'rcp-badge-critical',
    'cat-1': 'rcp-badge-cat-1',
    'cat-2': 'rcp-badge-cat-2',
    'cat-3': 'rcp-badge-cat-3',
    'cat-4': 'rcp-badge-cat-4',
    'cat-5': 'rcp-badge-cat-5',
    'cat-6': 'rcp-badge-cat-6',
    'cat-7': 'rcp-badge-cat-7',
    'cat-8': 'rcp-badge-cat-8',
};

interface RcpBadgeProps {
    variant: RcpBadgeVariant;
    children: ReactNode;
}

export default function RcpBadge({ variant, children }: RcpBadgeProps) {
    return (
        <span className={`rcp-badge ${VARIANT_CLASS[variant]}`.trim()}>
            {children}
        </span>
    );
}
