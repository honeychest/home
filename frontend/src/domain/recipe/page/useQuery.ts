// [AGENT] 목록 조회 훅 — "목록 화면 3상태"(data=null 첫 로딩 / loadError / 다시 시도)의 공용 구현.
// useMutation(조작)의 짝이다: 조회는 이쪽, 사용자 조작은 useMutation.
// 2026-07-15 신설: 이 3상태 뭉치가 5개 화면에 손으로 복제돼 있었고(AGENTS.md 가 "모범: RecipesPage"
// 라고 복제를 굳혀둔 상태였음), 그 복제 때문에 RecipesPage 폴링에 실제 버그가 있었다 —
// useEffect 의존성에 items 가 들어가 응답이 올 때마다 인터벌이 재생성돼, 주기가 고정 2.5초가
// 아니라 "응답 후 2.5초"로 밀렸다. refresh() 가 data 를 의존성에서 떼어내 구조적으로 막는다.
//
// 문구는 화면 소유 (에러 계약 — 서버는 상태 코드만 준다): toMessage 로 화면이 결정한다.
// 세션 만료(401)는 http.ts 공용 seam 이 로그인 전환을 담당하므로 여기서 구분하지 않는다.
import { useCallback, useEffect, useRef, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';

export interface Query<T> {
    /** null = 첫 로딩 (한 번이라도 성공하면 그 뒤엔 null 이 되지 않는다) */
    data: T | null;
    /** 표시할 실패 문구 — RcpInlineError 나 다시 시도 줄에 그대로 전달 */
    error: string | null;
    /** 실패의 원인 그대로 — 화면이 원인별로 다른 분기를 해야 할 때만 쓴다
        (예: 모니터링의 403 → 접근 거부 화면. 훅은 403 을 몰라도 된다) */
    failure: unknown | null;
    /** 낙관적 업데이트용 — 서버 왕복 없이 화면을 먼저 고칠 때 (실패 시 useMutation 이 재동기화) */
    setData: Dispatch<SetStateAction<T | null>>;
    /** 재조회 — 실패하면 문구를 세운다. 다시 시도 버튼에 그대로 연결 */
    reload: () => Promise<void>;
    /** 조용한 재조회 — 실패해도 화면을 건드리지 않는다 (폴링용: 다음 턴에 다시 시도하면 됨) */
    refresh: () => Promise<void>;
}

export function useQuery<T>(load: () => Promise<T>, toMessage: (e: unknown) => string): Query<T> {
    const [data, setData] = useState<T | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [failure, setFailure] = useState<unknown | null>(null);
    const aliveRef = useRef(true); // 언마운트 후 setState 방지 (폴링 중 화면 이탈)

    useEffect(() => {
        aliveRef.current = true;
        return () => { aliveRef.current = false; };
    }, []);

    const refresh = useCallback(async () => {
        const next = await load();
        if (!aliveRef.current) return;
        setData(next);
        setError(null);
        setFailure(null);
    }, [load]);

    const reload = useCallback(async () => {
        setError(null);
        setFailure(null);
        try {
            await refresh();
        } catch (e) {
            if (!aliveRef.current) return;
            setError(toMessage(e));
            setFailure(e);
        }
    }, [refresh, toMessage]);

    useEffect(() => {
        void reload();
    }, [reload]);

    return { data, error, failure, setData, reload, refresh };
}
