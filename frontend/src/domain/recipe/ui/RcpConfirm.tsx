// [AGENT] recipe UI 킷 — 확인 다이얼로그 (2026-07-19 확정, window.confirm 대체).
// 시스템 경고창은 앱 디자인·어투 밖이라 폐지 — 확인이 필요한 동작(재료 신고·제안 전체 적용·
// 영상 삭제)은 전부 이 컴포넌트를 쓴다. 화면 중앙의 작은 카드(자석 스티커 메타포 — 기존
// 그림자·모서리 토큰), 배경 탭 = 취소. 파괴적 동작(삭제류)은 danger 로 주 버튼을 빨간색으로.
// z-index 60 = 하단 시트(50) 위 — 시트 안 동작의 확인이 시트를 덮어야 한다.
import type { ReactNode } from 'react';
import RcpButton from './RcpButton';

const CANCEL_TEXT = '취소';

interface RcpConfirmProps {
    open: boolean;
    /** 본문 — 짧은 질문 문장. 긴 이름이 끼어도 카드 안에서 줄바꿈된다(스트레스 케이스 참고) */
    message: ReactNode;
    /** 주 동작 라벨 — 동사로 짧게 ("신고", "전체 적용", "삭제") */
    confirmLabel: string;
    /** 파괴적 동작(삭제류)이면 true — 주 버튼이 danger 색 */
    danger?: boolean;
    onConfirm: () => void;
    onCancel: () => void;
}

export default function RcpConfirm({
    open, message, confirmLabel, danger = false, onConfirm, onCancel,
}: RcpConfirmProps) {
    if (!open) return null;
    return (
        <div className="rcp-confirm-overlay" onClick={onCancel}>
            <div
                className="rcp-confirm-card"
                role="alertdialog"
                aria-modal="true"
                onClick={(e) => e.stopPropagation()}
            >
                <p className="rcp-confirm-message">{message}</p>
                <div className="rcp-confirm-actions">
                    <RcpButton variant="ghost" onClick={onCancel}>{CANCEL_TEXT}</RcpButton>
                    <RcpButton variant={danger ? 'danger' : 'primary'} onClick={onConfirm}>
                        {confirmLabel}
                    </RcpButton>
                </div>
            </div>
        </div>
    );
}
