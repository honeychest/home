// [AGENT] 조작 실행기 훅 — PLAYBOOK 관례 5("화면당 실행기 하나")의 공용 구현 (모범 패턴)
// 실패 문구·연타 방지·서버 재동기화를 한 곳에. 화면은 run() 으로 조작을 감싸기만 한다.
// 세션 만료(401)는 http.ts 공용 seam 이 로그인 화면 전환을 담당 — 여기서는 구분하지 않는다.
// 문구는 화면 소유 (에러 계약): toMessage 로 화면이 결정한다. 모듈 레벨 상수 함수로 넘길 것.
import { useCallback, useRef, useState } from 'react';

export interface MutationRunner {
    /** 조작 실행 — 진행 중이면 무시(연타 방지), 실패 시 toMessage 문구 + resync */
    run(op: () => Promise<void>): Promise<void>;
    /** 표시할 실패 문구 (RcpInlineError 에 그대로 전달) */
    error: string | null;
    /** 화면이 직접 문구를 넣거나 지울 때 (예: 링크 인식 실패는 저장소 호출 전에 표시) */
    setError(text: string | null): void;
    /** 조작 진행 중 — 버튼 disabled·"…중" 라벨용. 즉시 끝나는 조작은 안 써도 된다
        (한 틱 깜빡여 오히려 산만하다). 초 단위로 걸리는 조작에만 쓸 것 — 모범: DictionaryPanel
        의 [AI 점검](동기 LLM 호출이라 10초 이상. 표시가 없으면 고장으로 보인다) */
    busy: boolean;
}

export function useMutation(
    toMessage: (e: unknown) => string,
    resync?: () => Promise<unknown>,
): MutationRunner {
    const busyRef = useRef(false); // 조작 중 재진입 방지 — 렌더와 무관하므로 ref
    const [busy, setBusy] = useState(false); // 같은 사실의 표시용 — 이쪽은 렌더에 필요하므로 state
    const [error, setError] = useState<string | null>(null);

    const run = useCallback(async (op: () => Promise<void>) => {
        if (busyRef.current) return;
        busyRef.current = true;
        setBusy(true);
        setError(null);
        try {
            await op();
        } catch (e) {
            setError(toMessage(e));
            await resync?.().catch(() => undefined); // 화면을 서버 상태와 재동기화 (실패해도 문구는 이미 표시)
        } finally {
            busyRef.current = false;
            setBusy(false);
        }
    }, [toMessage, resync]);

    return { run, error, setError, busy };
}
