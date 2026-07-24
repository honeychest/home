// [AGENT] 클립보드 읽기 포트 — 화면마다 흩어져 있던 navigator.clipboard 직접 호출 + 기능 감지
// 중복을 하나로 모음(네이티브 전환 대비 점검에서 발견된 마찰 지점). 실패 문구는 화면 소유
// 원칙 그대로 유지 — 이 모듈은 지원 여부·읽은 텍스트만 돌려주고 실패는 예외로 그대로 전파한다
// (호출자가 조용히 무시하거나 문구로 바꾸는 건 화면의 몫).
export function clipboardReadSupported(): boolean {
    return typeof navigator !== 'undefined' && !!navigator.clipboard?.readText;
}

export async function readClipboardText(): Promise<string> {
    if (!clipboardReadSupported()) throw new Error('clipboard read not supported');
    return (await navigator.clipboard.readText()).trim();
}
