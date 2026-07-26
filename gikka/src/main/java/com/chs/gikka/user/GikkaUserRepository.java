// [AGENT] gikka 사용자 저장소 — 고유 열쇠는 Google 계정 ID(sub) (CONTEXT.md 인증 절)
package com.chs.gikka.user;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GikkaUserRepository {

    private final JdbcClient jdbc;

    public GikkaUserRepository(@Qualifier("gikkaJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String findEmail(long userId) {
        return jdbc.sql("SELECT email FROM gikka_user WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
    }

    /** 있으면 id 반환, 없으면 생성 후 id 반환 (로그인 시마다 호출해도 안전 — upsert) */
    public long findOrCreate(String googleSub, String email) {
        return jdbc.sql("""
                        INSERT INTO gikka_user (google_sub, email) VALUES (:sub, :email)
                        ON CONFLICT (google_sub) DO UPDATE SET email = :email
                        RETURNING id
                        """)
                .param("sub", googleSub)
                .param("email", email)
                .query(Long.class)
                .single();
    }
}
