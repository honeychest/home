// [AGENT] X(트위터) 영상 다운로드 API — 로그인 사용자 전용, 기록 없음(1회성 조회)
// (2026-07-20 확정 — 오너 전용에서 "로그인만 하면 누구나"로 완화. recipe 자체 로그인 제한
// (GikkaAuthProperties.allowedEmails)은 GikkaAuthController 가 이미 걸고 있어 별도 게이트 불필요,
// @GikkaUserId 가 미로그인 401 을 처리한다.)
// recipe 의 등록·분석·냉장고 기능과 완전히 별개 — DB 저장·이력 없음. 링크를 주면 다운로드
// 가능한 해상도 목록(직접 CDN 주소)만 돌려주고, 실제 다운로드는 폰 브라우저가 그 주소로 직접 한다.
package com.chs.springboot.domain.recipe.xdownload;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.chs.springboot.domain.recipe.auth.GikkaUserId;

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

    public XDownloadController(XVideoResolver resolver) {
        this.resolver = resolver;
    }

    public record ResolveRequest(String url) {
    }

    public record VideoOptionResponse(int height, String url) {
    }

    /** 게시물 안 영상 하나 — 영상이 여러 개인 게시물(최대 4개까지 가정)은 이게 여러 개 온다
        (2026-07-20 확정, 3개짜리 게시물 다운로드 실패 제보로 응답을 "영상 1개" 에서
        "영상 목록"으로 바꿈) */
    public record VideoItemResponse(String title, String thumbnail, List<VideoOptionResponse> options) {
    }

    public record ResolveResponse(List<VideoItemResponse> items) {
    }

    // userId 자체는 안 쓰지만 @GikkaUserId 가 미로그인 요청을 401 로 막는 게이트 역할 —
    // 파라미터를 지우면 로그인 여부를 아예 안 따지게 된다.
    @PostMapping("/resolve")
    public ResolveResponse resolve(@GikkaUserId long userId, @RequestBody ResolveRequest request) {
        String url = validUrl(request);
        XVideoResolver.ResolveResult result;
        try {
            result = resolver.resolve(url);
        } catch (XVideoResolver.ResolveFailedException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "영상 정보를 가져오지 못했어요");
        }
        if (result.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "다운로드 가능한 영상을 찾지 못했어요");
        }
        return new ResolveResponse(result.items().stream()
                .map(i -> new VideoItemResponse(i.title(), i.thumbnail(), i.options().stream()
                        .map(o -> new VideoOptionResponse(o.height(), o.url()))
                        .toList()))
                .toList());
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
