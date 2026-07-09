// [AGENT] recipe UI 킷 — 하단 시트 (.rcp-sheet) : 수정 폼 등 모바일 하단 팝업
import type { ReactNode } from 'react';

interface RcpBottomSheetProps {
    open: boolean;
    title: string;
    onClose: () => void;
    children: ReactNode;
}

export default function RcpBottomSheet({ open, title, onClose, children }: RcpBottomSheetProps) {
    if (!open) return null;
    return (
        <div className="rcp-sheet-overlay" onClick={onClose}>
            <div className="rcp-sheet" role="dialog" aria-label={title} onClick={(e) => e.stopPropagation()}>
                <div className="rcp-sheet-title">{title}</div>
                {children}
            </div>
        </div>
    );
}
