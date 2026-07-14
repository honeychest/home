// [AGENT] recipe UI 킷 — 하단 시트 (.rcp-sheet) : 수정 폼 등 모바일 하단 팝업
// 최대 70% 높이로 열려 위 30%는 항상 배경(overlay) — 탭으로 닫기는 overlay onClick 이 그대로 처리.
// 헤더(핸들+제목)에서 아래로 쓸어내리면 닫힘 (2026-07-14 확정 — 콘텐츠가 화면을 꽉 채워도
// 항상 닫을 수 있는 영역 확보. X 버튼 대신 이 방식을 선택함).
import { useRef, type PointerEvent, type ReactNode } from 'react';

interface RcpBottomSheetProps {
    open: boolean;
    title: string;
    onClose: () => void;
    children: ReactNode;
}

const SWIPE_CLOSE_THRESHOLD_PX = 60;

export default function RcpBottomSheet({ open, title, onClose, children }: RcpBottomSheetProps) {
    const dragStartY = useRef<number | null>(null);

    if (!open) return null;

    const handlePointerDown = (e: PointerEvent) => {
        dragStartY.current = e.clientY;
    };
    const handlePointerUp = (e: PointerEvent) => {
        const startY = dragStartY.current;
        dragStartY.current = null;
        if (startY !== null && e.clientY - startY > SWIPE_CLOSE_THRESHOLD_PX) onClose();
    };

    return (
        <div className="rcp-sheet-overlay" onClick={onClose}>
            <div className="rcp-sheet" role="dialog" aria-label={title} onClick={(e) => e.stopPropagation()}>
                <div
                    className="rcp-sheet-header"
                    onPointerDown={handlePointerDown}
                    onPointerUp={handlePointerUp}
                    onPointerCancel={() => { dragStartY.current = null; }}
                >
                    <div className="rcp-sheet-handle" aria-hidden="true" />
                    <div className="rcp-sheet-title">{title}</div>
                </div>
                {children}
            </div>
        </div>
    );
}
