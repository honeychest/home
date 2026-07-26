// [AGENT] gikka 인증 API — GIS 로그인·세션 확인·로그아웃 (CONTEXT.md 인증 절)
// 세션 전달은 쿠키가 아니라 Authorization 헤더(Bearer 토큰) — 네이티브 전환 대비
// (네이티브 웹뷰는 API 서버와 다른 오리진이라 쿠키가 자동 전송되지 않음). 토큰 보관은 프론트 소관.
package com.chs.gikka.auth;

import com.chs.gikka.user.GikkaUserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipe/auth")
public class GikkaAuthController {

    private final GikkaAuthProperties properties;
    private final GoogleTokenVerifier verifier;
    private final GikkaUserRepository users;
    private final GikkaJwt jwt;

    public GikkaAuthController(GikkaAuthProperties properties, GoogleTokenVerifier verifier,
                               GikkaUserRepository users, GikkaJwt jwt) {
        this.properties = properties;
        this.verifier = verifier;
        this.users = users;
        this.jwt = jwt;
    }

    /** GIS 버튼이 주는 credential(ID 토큰) */
    public record LoginRequest(String credential) {
    }

    /** canViewMonitor: 홈 탭 모니터링 링크 노출 조건 (2026-07-13 확정 — 허용 목록 재사용) */
    public record MeResponse(String email, boolean canViewMonitor) {
    }

    /** token: 이후 요청은 이 값을 Authorization: Bearer 로 실어 보낸다 (저장은 프론트 소관) */
    public record LoginResponse(String token, String email, boolean canViewMonitor) {
    }

    @PostMapping("/google")
    public LoginResponse login(@RequestBody LoginRequest request) {
        GoogleTokenVerifier.GoogleIdentity identity = verifier.verify(request.credential());

        // 1단계 허용 목록 스위치: 목록이 비어 있으면 누구나(공개 상태), 있으면 목록만
        if (!properties.getAllowedEmails().isEmpty()
                && !properties.getAllowedEmails().contains(identity.email())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "아직 공개되지 않은 서비스입니다");
        }

        long userId = users.findOrCreate(identity.sub(), identity.email());
        String token = jwt.issue(userId);
        return new LoginResponse(token, identity.email(), properties.isOwner(identity.email()));
    }

    /** 프론트 진입 시 세션 확인용. 미로그인 401 (dev 폴백 환경에서는 dev 사용자로 통과) */
    @GetMapping("/me")
    public MeResponse me(@GikkaUserId long userId) {
        String email = users.findEmail(userId);
        return new MeResponse(email, properties.isOwner(email));
    }

    /** 무상태 토큰이라 서버가 지울 것이 없음 — 클라이언트가 저장된 토큰을 지우면 로그아웃 완료.
        엔드포인트는 API 대칭성 유지 + 훗날 토큰 무효화 목록을 붙일 자리로 남겨둔다. */
    @PostMapping("/logout")
    public void logout() {
    }
}
