// [AGENT] recipe UI 킷 — 영상 행 (.rcp-video-row) : 섬네일 + 제목 + 배지 + 보조 정보
// 대기열 목록(3차)·레시피 목록(4차)에서 재사용. 탭 가능(하단 시트 열기 등) — 전체가 버튼.
import type { ReactNode } from 'react';
import { useState } from 'react';

interface RcpVideoRowProps {
    title: ReactNode;
    thumbnailUrl: string | null;
    badge?: ReactNode;
    meta?: ReactNode;
    onClick?: () => void;
}

export default function RcpVideoRow({ title, thumbnailUrl, badge, meta, onClick }: RcpVideoRowProps) {
    // 섬네일이 없거나 로드 실패(목 데이터·삭제된 영상)면 빈 자리 유지 — 레이아웃이 흔들리지 않게
    const [imageFailed, setImageFailed] = useState(false);

    return (
        <button type="button" className="rcp-video-row" onClick={onClick}>
            <span className="rcp-video-row-thumb" aria-hidden="true">
                {thumbnailUrl && !imageFailed && (
                    <img src={thumbnailUrl} alt="" loading="lazy" onError={() => setImageFailed(true)} />
                )}
            </span>
            <span className="rcp-video-row-body">
                <span className="rcp-video-row-title">{title}</span>
                {meta !== undefined && <span className="rcp-video-row-meta">{meta}</span>}
            </span>
            {badge}
        </button>
    );
}
