// [AGENT] 영상 메타 조회 인터페이스 — 플랫폼별 구현체가 꽂히는 확장 지점
// (YouTube = Data API, 6차 릴스·틱톡 = yt-dlp — CONTEXT.md 파이프라인 1단계)
package com.chs.springboot.domain.recipe.registration;

import java.util.List;
import java.util.Optional;

public interface VideoMetadataClient {

    record VideoMetadata(String videoId, String title, String thumbnailUrl, Integer durationSeconds) {
    }

    /** 영상 메타 일괄 조회 (없는 영상은 결과에서 빠짐). 조회 실패 시 빈 목록 — 등록을 막지 않는다 */
    List<VideoMetadata> fetch(List<String> videoIds);

    /** 재생목록의 영상 ID 전체 (페이지네이션 처리 포함) */
    List<String> playlistVideoIds(String playlistId);

    default Optional<VideoMetadata> fetchOne(String videoId) {
        return fetch(List.of(videoId)).stream().findFirst();
    }
}
