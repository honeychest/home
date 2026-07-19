// [AGENT] recipe(기까) 추천 저장소 — 인터페이스 + API 구현체 (registrationRepository 패턴)
// 401(세션 만료)은 http.ts 공용 시임이 처리 — 이 파일과 화면은 인증을 모른다.
import type { RecommendSnapshot, ShoppingSuggestion } from './recommendTypes';
import { request } from './http';

export interface RecommendRepository {
    /** 3단계 추천 스냅샷 (완전 가능 / 양념만 부족 / 재료 부족) */
    get(): Promise<RecommendSnapshot>;
    /** 구매 추천 — 내 레시피 중 주재료 1개 부족인 것의 집계 (2026-07-19, 냉장고 추가 시트용) */
    shopping(): Promise<ShoppingSuggestion[]>;
}

const BASE = '/api/recipe/recommend';

export function createApiRecommendRepository(): RecommendRepository {
    return {
        get() {
            return request<RecommendSnapshot>(BASE);
        },
        shopping() {
            return request<ShoppingSuggestion[]>(`${BASE}/shopping`);
        },
    };
}

export const recommendRepository: RecommendRepository = createApiRecommendRepository();
