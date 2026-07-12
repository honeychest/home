// [AGENT] 유튜브 URL 파서 — 프론트 videoUrl.ts 와 동일 규칙 (서버가 최종 방어선)
// 순수 정적 함수만 — DB 없이 테스트 (PLAYBOOK 관례 3)
package com.chs.springboot.domain.recipe.registration;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class YoutubeVideoId {

    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern PLAYLIST_ID = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final List<String> PATH_PREFIXES = List.of("shorts", "embed", "live");

    private YoutubeVideoId() {
    }

    /** 영상 ID 추출. 지원: youtu.be/{id}, watch?v={id}, /shorts|embed|live/{id} (+쿼리 허용) */
    public static Optional<String> parseVideoId(String rawUrl) {
        URI uri = toUri(rawUrl);
        if (uri == null || uri.getHost() == null) {
            return Optional.empty();
        }
        String host = uri.getHost().replaceFirst("^(www|m)\\.", "");
        String[] path = uri.getPath() == null ? new String[0]
                : java.util.Arrays.stream(uri.getPath().split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);

        if (host.equals("youtu.be")) {
            return path.length >= 1 ? valid(path[0]) : Optional.empty();
        }
        if (host.equals("youtube.com") || host.equals("youtube-nocookie.com")) {
            Optional<String> fromQuery = queryParam(uri, "v");
            if (fromQuery.isPresent()) {
                return fromQuery.flatMap(YoutubeVideoId::valid);
            }
            if (path.length >= 2 && PATH_PREFIXES.contains(path[0])) {
                return valid(path[1]);
            }
        }
        return Optional.empty();
    }

    /** 재생목록 ID 추출 (list= 파라미터) */
    public static Optional<String> parsePlaylistId(String rawUrl) {
        URI uri = toUri(rawUrl);
        if (uri == null || uri.getHost() == null) {
            return Optional.empty();
        }
        String host = uri.getHost().replaceFirst("^(www|m)\\.", "");
        if (!host.equals("youtube.com") && !host.equals("youtu.be")) {
            return Optional.empty();
        }
        return queryParam(uri, "list").filter(v -> PLAYLIST_ID.matcher(v).matches());
    }

    private static URI toUri(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return URI.create(trimmed.matches("^https?://.*") ? trimmed : "https://" + trimmed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Optional<String> queryParam(URI uri, String name) {
        String query = uri.getQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return Optional.of(pair.substring(eq + 1));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> valid(String candidate) {
        return VIDEO_ID.matcher(candidate).matches() ? Optional.of(candidate) : Optional.empty();
    }
}
