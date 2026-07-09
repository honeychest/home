// [AGENT] recipe(기까) 냉장고 데이터 모양 — 미래 API 응답과 동일하게 유지 (CONTEXT.md "개발 방식")
// 4차 백엔드 연결 시 이 타입 그대로 API가 반환해야 함. 바꾸면 인터페이스 설계 위반.

/** 냉장고 재료 한 개. 있다/없다만 관리 (수량·유통기한 없음 — 확정 결정) */
export interface FridgeItem {
    id: string;
    /** 표준 재료 이름 (재료 사전 정규화는 5차에서 — 지금은 입력 그대로) */
    name: string;
    /** 등록일 YYYY-MM-DD. 같은 재료 재등록 시 오늘로 갱신 */
    addedDate: string;
    /** 임박 여부 — 사용자가 수동 토글 */
    expiring: boolean;
}

/** 재료별 추가 횟수 — "자주 사는 재료" 상위 12개 버튼의 근거 */
export interface IngredientStat {
    name: string;
    addCount: number;
}
