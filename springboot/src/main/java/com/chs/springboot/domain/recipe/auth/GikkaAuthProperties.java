// [AGENT] gikka 인증 설정 seam — CONTEXT.md 인증 절 (다중 사용자 기본 + 허용 목록 스위치)
package com.chs.springboot.domain.recipe.auth;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gikka.auth.* 설정.
 *
 * - allowedEmails: 1단계 허용 목록 스위치. 비어 있으면 누구나 로그인 가능(2단계 공개 상태),
 *   채워져 있으면 목록의 구글 계정만 통과 — 공개 전까지 타인의 Gemini 한도 소모 차단.
 * - devUserEmail: 개발 편의용. 설정되어 있으면 Google 로그인 없이 이 이메일의
 *   가짜 사용자(sub = "dev:이메일")로 동작한다. prod 에서는 절대 설정하지 말 것.
 */
@ConfigurationProperties(prefix = "gikka.auth")
public class GikkaAuthProperties {

    private List<String> allowedEmails = List.of();
    private String devUserEmail;
    /** GIS(Google Identity Services) OAuth 클라이언트 ID — 공개 값 (비밀 아님) */
    private String googleClientId;

    public List<String> getAllowedEmails() {
        return allowedEmails;
    }

    public void setAllowedEmails(List<String> allowedEmails) {
        this.allowedEmails = allowedEmails;
    }

    public String getDevUserEmail() {
        return devUserEmail;
    }

    public void setDevUserEmail(String devUserEmail) {
        this.devUserEmail = devUserEmail;
    }

    public String getGoogleClientId() {
        return googleClientId;
    }

    public void setGoogleClientId(String googleClientId) {
        this.googleClientId = googleClientId;
    }

    /**
     * 오너 전용 화면(모니터링 등) 접근 판정 (2026-07-13 확정).
     * 허용 목록이 비면(2단계 공개 상태) 아무도 통과시키지 않는다 — 그 시점엔 별도
     * 관리자 판정을 새로 설계해야 한다 (허용 목록 비움 = 로그인 자유화이지 오너 화면 공개가 아님).
     */
    public boolean isOwner(String email) {
        return !allowedEmails.isEmpty() && allowedEmails.contains(email);
    }
}
