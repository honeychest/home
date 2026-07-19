// [AGENT] recipe(기까) 추천 데이터 모양 — 백엔드 RecommendController 응답과 1:1 (CONTEXT.md 4차 확정)
// 계산(재료/양념 분류·매칭)은 서버 책임 — 화면은 결과만 렌더한다.

/** 재료 한 줄(상세 팝업용) — 원문 순서 그대로, 냉장고 보유 여부만 표시 */
export interface IngredientStatus {
    name: string;
    have: boolean;
}

/** 추천 카드 하나. missing = 카드에 표시할 부족분(완전 가능=빈 배열, 양념만 부족=부족한 양념
    이름, 재료 부족=부족한 재료 이름, 개수 상한 없음). ingredients·cookMinutes·steps 는
    카드 탭 → 상세 팝업용(2026-07-14 확정). inLibrary = 이 레시피가 내 보관함에 있는지 —
    추천 풀이 gikka 전체로 넓어져(2026-07-16 5차) 남의 레시피도 뜨므로, 내 것엔 배지를,
    남의 것엔 "내 보관함에 담기" 버튼을 보여주는 판단에 쓴다 */
export interface RecommendItem {
    videoId: string;
    url: string;
    title: string;
    thumbnailUrl: string | null;
    missing: string[];
    ingredients: IngredientStatus[];
    cookMinutes: number | null;
    steps: string[];
    inLibrary: boolean;
}

/** 구매 추천 한 줄 (2026-07-19 확정 — 냉장고 재료 추가 시트 하단).
    name = 사전 대표 이름(장보기 이름), recipes = 이거 하나 사면 완성되는 내 레시피의 요리 이름들 */
export interface ShoppingSuggestion {
    name: string;
    recipes: string[];
}

/** 3단계 — 완전 가능 / 양념만 부족 / 재료 부족(1~3개, 부족 적은 순). 각 섹션 최대 5개 */
export interface RecommendSnapshot {
    complete: RecommendItem[];
    seasoningOnly: RecommendItem[];
    needsIngredients: RecommendItem[];
}
