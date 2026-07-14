// [AGENT] recipe UI 킷 — 하단 탭 바 (.rcp-tab-bar) : 홈 / 추천 / 냉장고 / 보관함
// 탭 구성은 CONTEXT.md "앱 골격" 확정 사항. "보관함"은 구 "레시피" 명칭(2026-07-14 확정 변경 —
// 요리뿐 아니라 유틸·기타 영상도 담는 성격을 반영, 라우트 경로는 호환을 위해 그대로 /recipes).
import { NavLink } from 'react-router-dom';
import { House, Sparkles, Refrigerator, BookOpen } from 'lucide-react';

const TABS = [
    { to: '/recipe/home', label: '홈', Icon: House },
    { to: '/recipe/recommend', label: '추천', Icon: Sparkles },
    { to: '/recipe/fridge', label: '냉장고', Icon: Refrigerator },
    { to: '/recipe/recipes', label: '보관함', Icon: BookOpen },
];

export default function RcpTabBar() {
    return (
        <nav className="rcp-tab-bar" id="rcp-tab-bar">
            {TABS.map(({ to, label, Icon }) => (
                <NavLink
                    key={to}
                    to={to}
                    className={({ isActive }) => `rcp-tab ${isActive ? 'rcp-tab-active' : ''}`.trim()}
                >
                    <Icon size={20} strokeWidth={2.2} />
                    {label}
                </NavLink>
            ))}
        </nav>
    );
}
