// [AGENT] 현재 요청의 gikka 사용자 식별 seam — 어댑터 2개(JwtCurrentUser·DevCurrentUser)가 구현.
// 어느 쪽이 쓰일지는 CurrentUserConfig 가 설정(gikka.auth.dev-user-email)으로 결정한다.
// 호출부(리졸버)는 이 인터페이스만 안다 — 인증 방식이 바뀌어도 호출부 무수정.
package com.chs.gikka.auth;

public interface CurrentUser {

    /** 현재 요청 사용자의 gikka_user.id. 식별 불가 시 401(ResponseStatusException). */
    long currentUserId();
}
