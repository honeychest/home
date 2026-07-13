// [AGENT] recipe UI 킷 — 조작 실패 인라인 안내 줄 (useMutation.error 를 그대로 받는다)
// 화면 본문과 열려 있는 시트 양쪽에 같은 줄을 놓는 패턴 (시트가 화면을 덮으므로).
export default function RcpInlineError({ message }: { message: string | null }) {
    if (!message) return null;
    return <p className="rcp-inline-error" role="alert">{message}</p>;
}
