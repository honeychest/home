// [AGENT] gikka 세션 토큰(JWT) 발급·검증 — 서명 키는 gikka DB 보관 (V2, 2인스턴스 공유·.env 불필요)
package com.chs.springboot.domain.recipe.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class GikkaJwt {

    /** 개인 앱 편의 우선 — 30일마다 한 번 다시 로그인 */
    static final Duration VALIDITY = Duration.ofDays(30);

    private final JdbcClient jdbc;
    private volatile SecretKey cachedKey;

    public GikkaJwt(@Qualifier("gikkaJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String issue(long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(VALIDITY)))
                .signWith(signingKey())
                .compact();
    }

    /** @return userId, 서명·만료가 유효하지 않으면 null (호출부에서 401 처리) */
    public Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(signingKey()).build()
                    .parseSignedClaims(token).getPayload();
            return Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    /** 첫 기동 시 키 생성해 DB 저장. 동시 기동(2인스턴스)은 ON CONFLICT 로 한쪽만 성공 → 재조회로 수렴 */
    private SecretKey signingKey() {
        if (cachedKey != null) {
            return cachedKey;
        }
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        jdbc.sql("""
                        INSERT INTO auth_signing_key (id, secret) VALUES (1, :secret)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("secret", Base64.getEncoder().encodeToString(random))
                .update();
        String secret = jdbc.sql("SELECT secret FROM auth_signing_key WHERE id = 1")
                .query(String.class).single();
        cachedKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        return cachedKey;
    }
}
