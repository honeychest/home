// [AGENT] recipe UI 킷 — 조회 실패 안내 + 다시 시도 (useQuery 의 error·reload 를 그대로 받는다)
// 조작 실패(useMutation)는 RcpInlineError, 조회 실패(useQuery)는 이것 — 짝이다.
// message 가 null 이면 안 그린다 (RcpInlineError 와 같은 관용구).
// 화면 안에 .rcp-shell-status(셸 높이를 통째로 채우는 셸 전용)를 쓰던 4곳을 대체한다 (2026-07-16 점검).
import RcpButton from './RcpButton';

const RETRY_TEXT = '다시 시도';

interface RcpLoadErrorProps {
    message: string | null;
    onRetry: () => void;
}

export default function RcpLoadError({ message, onRetry }: RcpLoadErrorProps) {
    if (!message) return null;
    return (
        <div className="rcp-load-error" role="alert">
            <span>{message}</span>
            <RcpButton onClick={onRetry}>{RETRY_TEXT}</RcpButton>
        </div>
    );
}
