// [AGENT] GIS ID 토큰 검증 — 구글 tokeninfo 엔드포인트 사용 (로그인 시에만 호출되는 저빈도 경로)
// 라이브러리 검증(google-api-client) 대신 HTTP 검증을 쓴 이유: 의존성 0개 추가, 개인 앱 트래픽엔 충분.
package com.chs.gikka.auth;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class GoogleTokenVerifier {

    static final String TOKENINFO_BASE = "https://oauth2.googleapis.com";

    private final GikkaAuthProperties properties;
    private final RestClient restClient;

    /** Builder 주입: 테스트가 MockRestServiceServer 를 붙일 수 있는 시임 (실 구글 불필요) */
    public GoogleTokenVerifier(GikkaAuthProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.baseUrl(TOKENINFO_BASE).build();
    }

    public record GoogleIdentity(String sub, String email) {
    }

    /** ID 토큰을 구글에 확인시켜 sub/email 을 얻는다. 위조·만료·다른 앱 토큰이면 401. */
    public GoogleIdentity verify(String idToken) {
        Map<?, ?> claims;
        try {
            claims = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/tokeninfo").queryParam("id_token", idToken).build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            // 구글이 4xx 응답 = 유효하지 않은 토큰 (만료 포함)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "구글 로그인 검증에 실패했습니다");
        }
        if (claims == null
                || !properties.getGoogleClientId().equals(claims.get("aud"))       // 우리 앱에 발급된 토큰인가
                || !"true".equals(String.valueOf(claims.get("email_verified")))) { // 이메일 소유 확인된 계정인가
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "구글 로그인 검증에 실패했습니다");
        }
        return new GoogleIdentity(String.valueOf(claims.get("sub")), String.valueOf(claims.get("email")));
    }
}
