// [AGENT] 영상 메타 조회 인터페이스 — 플랫폼별 구현체가 꽂히는 확장 지점
// (YouTube = Data API, 6차 릴스·틱톡 = yt-dlp — CONTEXT.md 파이프라인 1단계)
package com.chs.gikka.registration;

import java.util.List;
import java.util.Optional;

public interface VideoMetadataClient {

    /** description: 유튜브 설명란(본문) — 재료가 원문으로 적힌 경우가 많아 최우선 활용 (2026-07-13 확정) */
    record VideoMetadata(String videoId, String title, String thumbnailUrl, Integer durationSeconds,
                        String description) {
    }

    /**
     * 조회 호출 자체가 실패한 경우 (HTTP 오류·네트워크·타임아웃) — 지금은 안 되지만 곧 풀릴 상황.
     * "영상이 없다(비공개·삭제)"와는 정반대라 구분한다 (2026-07-16): 없는 영상은 응답에서 그냥
     * 빠지지만(㉠, 등록 차단 대상), 조회 자체가 실패한 건(㉡) 멀쩡한 영상을 영구 거부하면 안 되므로
     * 호출자가 관대하게(등록 허용) 처리하도록 예외로 알린다.
     */
    class TransientMetadataException extends RuntimeException {
        public TransientMetadataException(String message) {
            super(message);
        }
    }

    /**
     * 영상 메타 일괄 조회. 없는 영상(비공개·삭제·잘못된 ID)은 결과에서 빠진다 — 그 빠짐 자체가
     * "그 영상은 없다"는 사실이다(㉠). 조회 호출이 실패하면 TransientMetadataException(㉡).
     */
    List<VideoMetadata> fetch(List<String> videoIds);

    /** 재생목록의 영상 ID 전체 (페이지네이션 처리 포함) */
    List<String> playlistVideoIds(String playlistId);

    default Optional<VideoMetadata> fetchOne(String videoId) {
        return fetch(List.of(videoId)).stream().findFirst();
    }
}
