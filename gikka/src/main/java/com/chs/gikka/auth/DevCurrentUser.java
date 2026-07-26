// [AGENT] 개발 어댑터 — Google 로그인 없이 gikka.auth.dev-user-email 의 가짜 사용자로 동작.
// (local 은 http LAN 이라 GIS 가 안 됨 — CONTEXT.md 인증 절). 쿠키는 보지 않는다.
// 안전장치를 이 어댑터가 직접 소유: prod 프로파일에서는 생성 자체가 실패해 기동이 멈춘다.
package com.chs.gikka.auth;

import com.chs.gikka.user.GikkaUserRepository;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

public class DevCurrentUser implements CurrentUser {

    private final GikkaUserRepository users;
    private final String devEmail;

    public DevCurrentUser(GikkaUserRepository users, String devEmail, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "gikka.auth.dev-user-email 은 prod 에서 쓸 수 없습니다 (무인증 통과 구멍) — 설정을 제거하세요");
        }
        this.users = users;
        this.devEmail = devEmail;
    }

    @Override
    public long currentUserId() {
        return users.findOrCreate("dev:" + devEmail, devEmail);
    }
}
