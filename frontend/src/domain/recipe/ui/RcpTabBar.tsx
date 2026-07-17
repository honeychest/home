// [AGENT] recipe UI 킷 — 하단 탭 바 (.rcp-tab-bar)
// 기본 탭: 홈 / 추천 / 냉장고 / 보관함 (CONTEXT.md "앱 골격" 확정 사항. "보관함"은 구 "레시피"
// 명칭 — 요리뿐 아니라 유틸·기타 영상도 담는 성격을 반영, 라우트는 호환을 위해 /recipes 그대로).
// tabs 를 넘기면 다른 탭 묶음을 그린다 — 운영자 모드(/recipe/monitor)가 자기 탭(대기열·사전·
// 나가기)을 쓰기 위해 2026-07-17 신설. 탭 바를 두 벌 만들지 않으려고 목록만 주입받는다.
import { NavLink } from 'react-router-dom';
import type { LucideIcon } from 'lucide-react';
import { House, Sparkles, Refrigerator, BookOpen } from 'lucide-react';

export interface RcpTab {
    to: string;
    label: string;
    Icon: LucideIcon;
}

const APP_TABS: RcpTab[] = [
    { to: '/recipe/home', label: '홈', Icon: House },
    { to: '/recipe/recommend', label: '추천', Icon: Sparkles },
    { to: '/recipe/fridge', label: '냉장고', Icon: Refrigerator },
    { to: '/recipe/recipes', label: '보관함', Icon: BookOpen },
];

interface RcpTabBarProps {
    tabs?: RcpTab[];
}

export default function RcpTabBar({ tabs = APP_TABS }: RcpTabBarProps) {
    return (
        <nav className="rcp-tab-bar" id="rcp-tab-bar">
            {tabs.map(({ to, label, Icon }) => (
                // end=정확히 그 경로일 때만 활성 — 없으면 하위 경로(/monitor/dictionary)에서
                // 상위 탭(/monitor)까지 같이 켜져 지금 어디인지 알 수 없다
                <NavLink
                    key={to}
                    to={to}
                    end
                    className={({ isActive }) => `rcp-tab ${isActive ? 'rcp-tab-active' : ''}`.trim()}
                >
                    <Icon size={20} strokeWidth={2.2} />
                    {label}
                </NavLink>
            ))}
        </nav>
    );
}
