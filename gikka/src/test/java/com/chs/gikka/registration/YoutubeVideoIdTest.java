// [AGENT] 유튜브 URL 파서 고정 — 중복 판별의 뿌리 (프론트 videoUrl.test.ts 와 같은 케이스 유지)
package com.chs.gikka.registration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubeVideoIdTest {

    private static final String ID = "dQw4w9WgXcQ";

    @Test
    @DisplayName("대표 링크 형태들에서 같은 영상 ID 를 뽑는다")
    void parsesCommonForms() {
        assertEquals(Optional.of(ID), YoutubeVideoId.parseVideoId("https://youtu.be/" + ID));
        assertEquals(Optional.of(ID), YoutubeVideoId.parseVideoId("https://youtu.be/" + ID + "?si=abc123"));
        assertEquals(Optional.of(ID), YoutubeVideoId.parseVideoId("https://www.youtube.com/watch?v=" + ID));
        assertEquals(Optional.of(ID), YoutubeVideoId.parseVideoId("https://m.youtube.com/watch?v=" + ID + "&t=10s"));
        assertEquals(Optional.of(ID), YoutubeVideoId.parseVideoId("https://www.youtube.com/shorts/" + ID));
        assertEquals(Optional.of(ID), YoutubeVideoId.parseVideoId("https://www.youtube.com/shorts/" + ID + "?feature=share"));
        assertEquals(Optional.of(ID), YoutubeVideoId.parseVideoId("https://www.youtube.com/embed/" + ID));
        assertEquals(Optional.of(ID), YoutubeVideoId.parseVideoId("https://www.youtube.com/live/" + ID));
    }

    @Test
    @DisplayName("프로토콜 없이도 동작한다 (폰 클립보드 대응)")
    void parsesWithoutProtocol() {
        assertEquals(Optional.of(ID), YoutubeVideoId.parseVideoId("youtube.com/shorts/" + ID));
        assertEquals(Optional.of(ID), YoutubeVideoId.parseVideoId("youtu.be/" + ID));
    }

    @Test
    @DisplayName("유튜브가 아니거나 ID 형태가 아니면 empty")
    void rejectsInvalid() {
        assertTrue(YoutubeVideoId.parseVideoId("https://www.instagram.com/reel/abc/").isEmpty());
        assertTrue(YoutubeVideoId.parseVideoId("https://www.youtube.com/").isEmpty());
        assertTrue(YoutubeVideoId.parseVideoId("https://www.youtube.com/watch?v=short").isEmpty());
        assertTrue(YoutubeVideoId.parseVideoId("그냥 글자").isEmpty());
        assertTrue(YoutubeVideoId.parseVideoId("").isEmpty());
        assertTrue(YoutubeVideoId.parseVideoId(null).isEmpty());
    }

    @Test
    @DisplayName("재생목록 ID 추출 — 영상+재생목록 혼합 URL 포함")
    void parsesPlaylist() {
        assertEquals(Optional.of("PLabc_123-XYZ"),
                YoutubeVideoId.parsePlaylistId("https://www.youtube.com/playlist?list=PLabc_123-XYZ"));
        assertEquals(Optional.of("PLabc"),
                YoutubeVideoId.parsePlaylistId("https://www.youtube.com/watch?v=" + ID + "&list=PLabc"));
        assertTrue(YoutubeVideoId.parsePlaylistId("https://youtu.be/" + ID).isEmpty());
    }

    @Test
    @DisplayName("ISO-8601 길이 → 초 (7분 컷 판정의 근거)")
    void parsesDuration() {
        assertEquals(58, YoutubeMetadataClient.parseIsoDurationSeconds("PT58S"));
        assertEquals(90, YoutubeMetadataClient.parseIsoDurationSeconds("PT1M30S"));
        assertEquals(23 * 60, YoutubeMetadataClient.parseIsoDurationSeconds("PT23M"));
        assertEquals(3661, YoutubeMetadataClient.parseIsoDurationSeconds("PT1H1M1S"));
        assertEquals(null, YoutubeMetadataClient.parseIsoDurationSeconds(null));
        assertEquals(null, YoutubeMetadataClient.parseIsoDurationSeconds("이상한값"));
    }
}
