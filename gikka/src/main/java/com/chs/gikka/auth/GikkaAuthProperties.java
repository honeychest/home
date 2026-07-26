// [AGENT] gikka 인증 설정 seam — CONTEXT.md 인증 절 (다중 사용자 기본 + 허용 목록 스위치)
package com.chs.gikka.auth;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gikka.auth.* 설정.
 *
 * - allowedEmails: 1단계 허용 목록 스위치. 비어 있으면 누구나 로그인 가능(2단계 공개 상태),
 *   채워져 있으면 목록의 구글 계정만 통과 — 공개 전까지 타인의 Gemini 한도 소모 차단.
 * - ownerEmail: 오너 전용 화면(모니터링 등) 접근 판정 전용 (2026-07-20 확정 — allowedEmails 와
 *   분리). 예전엔 isOwner() 가 allowedEmails 를 그대로 재사용해서, 로그인을 공개하려고 그
 *   목록을 비우면 오너 본인도 같이 막히는 문제가 있었다(친구 배포 개시 계기로 발견). 지금은
 *   로그인 허용(allowedEmails)과 오너 판정(ownerEmail)이 서로 무관하게 따로 움직인다.
 * - devUserEmail: 개발 편의용. 설정되어 있으면 Google 로그인 없이 이 이메일의
 *   가짜 사용자(sub = "dev:이메일")로 동작한다. prod 에서는 절대 설정하지 말 것.
 * - allowedOrigins: CORS 허용 오리진 (네이티브 앱 등 API 서버와 다른 오리진에서 호출할 때만
 *   필요 — 지금의 같은 오리진 PWA 는 비어 있어도 영향 없음). 네이티브 착수 시 그 오리진을 채운다.
 */
@ConfigurationProperties(prefix = "gikka.auth")
public class GikkaAuthProperties {

    private List<String> allowedEmails = List.of();
    private String ownerEmail;
    private String devUserEmail;
    /** GIS(Google Identity Services) OAuth 클라이언트 ID — 공개 값 (비밀 아님) */
    private String googleClientId;
    private List<String> allowedOrigins = List.of();

    public List<String> getAllowedEmails() {
        return allowedEmails;
    }

    public void setAllowedEmails(List<String> allowedEmails) {
        this.allowedEmails = allowedEmails;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
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

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * 오너 전용 화면(모니터링 등) 접근 판정 (2026-07-20 갱신 — ownerEmail 전용 비교로 변경,
     * allowedEmails 공개 여부와 무관하게 항상 오너를 식별할 수 있다).
     */
    public boolean isOwner(String email) {
        return ownerEmail != null && Objects.equals(ownerEmail, email);
    }
}
