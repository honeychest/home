// [AGENT] 냉장고 재료 API 응답 — 프론트 fridgeTypes.ts FridgeItem 과 1:1 (모양 바꾸면 계약 위반)
// id 가 문자열인 이유: localStorage 구현체가 uuid 문자열을 쓰므로 프론트 타입이 string.
package com.chs.springboot.domain.recipe.fridge;

public record FridgeItemResponse(
        String id,
        String name,
        String addedDate,   // YYYY-MM-DD
        boolean expiring
) {
}
