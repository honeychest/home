// [AGENT] recipe UI 킷 — 떠 있는 주 동작 버튼 (FAB, .rcp-fab)
// 세로로 길어지는 목록 화면의 주 동작(예: 보관함의 영상 등록) 하나만 여기에 둔다.
// 목록 끝에 놓인 버튼은 항목이 쌓일수록 스크롤 바닥으로 밀려나 못 찾게 되기 때문 (2026-07-16 실사용 제보).
// 라벨을 다는 확장형이다 — 아이콘만 있는 원형은 뜻이 안 읽히고 i18n 때 붙일 자리도 없다.
// 쓰는 화면은 .rcp-screen-with-fab 을 함께 걸 것 (마지막 항목이 가려지지 않게).
import type { ReactNode } from 'react';
import RcpButton from './RcpButton';

interface RcpFabProps {
    children: ReactNode;
    onClick: () => void;
    id?: string;
}

export default function RcpFab({ children, onClick, id }: RcpFabProps) {
    return (
        <RcpButton className="rcp-fab" id={id} onClick={onClick}>
            <span className="rcp-fab-label">{children}</span>
        </RcpButton>
    );
}
