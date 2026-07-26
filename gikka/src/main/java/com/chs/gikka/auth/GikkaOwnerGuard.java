// [AGENT] 오너 판정의 단일 원본 (2026-07-25 아키텍처 점검에서 신설) — 이전엔 세 컨트롤러가
// `authProperties.isOwner(users.findEmail(userId))` 를 각자 복붙하고 있었다.
//
// 판정 근거는 허용 목록 재사용(GikkaAuthProperties.isOwner) — 목록이 비면 아무도 오너가 아니다.
// **실제 경계는 여기서 던지는 403 이다** — 화면 탭 바에 없다는 건 경계가 아니다 (CONTEXT.md §14).
package com.chs.gikka.auth;

import com.chs.gikka.user.GikkaUserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GikkaOwnerGuard {

    private final GikkaAuthProperties properties;
    private final GikkaUserRepository users;

    public GikkaOwnerGuard(GikkaAuthProperties properties, GikkaUserRepository users) {
        this.properties = properties;
        this.users = users;
    }

    /** 판정만 — 막지는 않는다. 오너에게만 한도를 면제하는 식의 "분기"에 쓴다(재생목록 상한 등). */
    public boolean isOwner(long userId) {
        return properties.isOwner(users.findEmail(userId));
    }

    /** 오너 전용 기능의 문지기 — 아니면 403. */
    public void require(long userId) {
        if (!isOwner(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "오너 전용 기능");
        }
    }
}
