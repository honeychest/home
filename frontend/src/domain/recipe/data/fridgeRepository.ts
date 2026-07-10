// [AGENT] recipe(기까) 냉장고 저장소 — 인터페이스 + API 구현체
// 2026-07-10 2차: localStorage 구현체를 API 구현체로 교체 (CONTEXT.md "개발 방식" 계획 그대로).
// 규칙: 화면은 이 인터페이스만 사용. 동작 규칙(재등록 날짜 갱신, 시드 병합, 숨김)의 원본은
// 이제 백엔드(FridgeRepository.java)다. 이전 localStorage 구현은 git 이력(커밋 75b30e7 이전) 참고.
// 401(세션 만료)은 http.ts 의 공용 시임이 처리 — 이 파일과 화면은 인증을 모른다.
import type { FridgeItem } from './fridgeTypes';
import { jsonBody, request } from './http';

export interface FridgeRepository {
    /** 전체 목록 (정렬은 화면 책임) */
    list(): Promise<FridgeItem[]>;
    /** 추가. 같은 이름이 이미 있으면 등록일만 오늘로 갱신 (리필 대응 — 확정 결정) */
    add(name: string): Promise<FridgeItem>;
    /** 삭제 (확인 없이 즉시 — 확정 결정) */
    remove(id: string): Promise<void>;
    /** 임박 수동 토글 */
    setExpiring(id: string, expiring: boolean): Promise<void>;
    /** 수정: 이름 교정 + 등록일 변경 (뒤늦은 등록 대응) */
    update(id: string, changes: { name?: string; addedDate?: string }): Promise<void>;
    /** 자주 사는 재료 상위 N개 — 추가 횟수 순, 시드와 병합, 숨김 제외 (계산은 서버) */
    frequentIngredients(limit: number): Promise<string[]>;
    /** 자주 사는 재료 목록에서 제거 (편집 모드). 냉장고 안의 재료에는 영향 없음 */
    removeFrequentIngredient(name: string): Promise<void>;
}

const BASE = '/api/recipe/fridge';

export function createApiFridgeRepository(): FridgeRepository {
    return {
        list() {
            return request<FridgeItem[]>(`${BASE}/items`);
        },

        add(name) {
            return request<FridgeItem>(`${BASE}/items`, { method: 'POST', ...jsonBody({ name }) });
        },

        remove(id) {
            return request<void>(`${BASE}/items/${id}`, { method: 'DELETE' });
        },

        setExpiring(id, expiring) {
            return request<void>(`${BASE}/items/${id}`, { method: 'PATCH', ...jsonBody({ expiring }) });
        },

        update(id, changes) {
            return request<void>(`${BASE}/items/${id}`, { method: 'PATCH', ...jsonBody(changes) });
        },

        frequentIngredients(limit) {
            return request<string[]>(`${BASE}/frequent-ingredients?limit=${limit}`);
        },

        removeFrequentIngredient(name) {
            return request<void>(`${BASE}/frequent-ingredients/${encodeURIComponent(name)}`, { method: 'DELETE' });
        },
    };
}

export const fridgeRepository: FridgeRepository = createApiFridgeRepository();
