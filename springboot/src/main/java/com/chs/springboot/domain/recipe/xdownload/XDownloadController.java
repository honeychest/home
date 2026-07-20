// [AGENT] X(트위터) 영상 다운로드 API — 오너 전용, 기록 없음(1회성 조회) (2026-07-20 확정)
// recipe 의 등록·분석·냉장고 기능과 완전히 별개 — DB 저장·이력 없음. 링크를 주면 다운로드
// 가능한 해상도 목록(직접 CDN 주소)만 돌려주고, 실제 다운로드는 폰 브라우저가 그 주소로 직접 한다.
package com.chs.springboot.domain.recipe.xdownload;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.chs.springboot.domain.recipe.auth.GikkaAuthProperties;
import com.chs.springboot.domain.recipe.auth.GikkaUserId;
import com.chs.springboot.domain.recipe.user.GikkaUserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipe/x-download")
public class XDownloadController {

    /** X(트위터) 링크만 허용 — 인스타·유튜브는 링크 추출만으로는 신뢰성 있게 안 돼 범위 밖
        (2026-07-20 확정, CONTEXT.md 밖 별도 기능이라 여기 상수로만 관리) */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com");

    private final XVideoResolver resolver;
    private final GikkaAuthProperties authProperties;
    private final GikkaUserRepository users;

    public XDownloadController(XVideoResolver resolver, GikkaAuthProperties authProperties,
                               GikkaUserRepository users) {
        this.resolver = resolver;
        this.authProperties = authProperties;
        this.users = users;
    }

    public record ResolveRequest(String url) {
    }

    public record VideoOptionResponse(int height, String url, boolean hasAudio) {
    }

    public record ResolveResponse(String title, String thumbnail, List<VideoOptionResponse> options) {
    }

    @PostMapping("/resolve")
    public ResolveResponse resolve(@GikkaUserId long userId, @RequestBody ResolveRequest request) {
        requireOwner(userId);
        String url = validUrl(request);
        XVideoResolver.ResolveResult result;
        try {
            result = resolver.resolve(url);
        } catch (XVideoResolver.ResolveFailedException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "영상 정보를 가져오지 못했어요");
        }
        if (result.options().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "다운로드 가능한 영상을 찾지 못했어요");
        }
        return new ResolveResponse(result.title(), result.thumbnail(), result.options().stream()
                .map(o -> new VideoOptionResponse(o.height(), o.url(), o.hasAudio()))
                .toList());
    }

    private void requireOwner(long userId) {
        if (!authProperties.isOwner(users.findEmail(userId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "오너 전용 기능");
        }
    }

    private static String validUrl(ResolveRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url 누락");
        }
        String url = request.url().trim();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 URL");
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X(트위터) 링크만 지원해요");
        }
        return url;
    }
}
