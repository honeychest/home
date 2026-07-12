// [AGENT] videoUrl 파서 테스트 — 중복 판별의 뿌리이므로 링크 형태별로 고정 (PLAYBOOK 관례 6)
import { describe, expect, it } from 'vitest';
import { parseYoutubePlaylistId, parseYoutubeVideoId } from './videoUrl';

const ID = 'dQw4w9WgXcQ';

describe('parseYoutubeVideoId', () => {
    it('대표 링크 형태들에서 같은 ID 를 뽑는다', () => {
        expect(parseYoutubeVideoId(`https://youtu.be/${ID}`)).toBe(ID);
        expect(parseYoutubeVideoId(`https://youtu.be/${ID}?si=abc123`)).toBe(ID);
        expect(parseYoutubeVideoId(`https://www.youtube.com/watch?v=${ID}`)).toBe(ID);
        expect(parseYoutubeVideoId(`https://m.youtube.com/watch?v=${ID}&t=10s`)).toBe(ID);
        expect(parseYoutubeVideoId(`https://www.youtube.com/shorts/${ID}`)).toBe(ID);
        expect(parseYoutubeVideoId(`https://www.youtube.com/shorts/${ID}?feature=share`)).toBe(ID);
        expect(parseYoutubeVideoId(`https://www.youtube.com/embed/${ID}`)).toBe(ID);
        expect(parseYoutubeVideoId(`https://www.youtube.com/live/${ID}`)).toBe(ID);
    });

    it('프로토콜 없이 붙여넣어도 동작한다 (폰 클립보드 대응)', () => {
        expect(parseYoutubeVideoId(`youtube.com/shorts/${ID}`)).toBe(ID);
        expect(parseYoutubeVideoId(`youtu.be/${ID}`)).toBe(ID);
    });

    it('유튜브가 아니거나 ID 형태가 아니면 null', () => {
        expect(parseYoutubeVideoId('https://www.instagram.com/reel/abc/')).toBeNull();
        expect(parseYoutubeVideoId('https://www.youtube.com/')).toBeNull();
        expect(parseYoutubeVideoId('https://www.youtube.com/watch?v=short')).toBeNull();
        expect(parseYoutubeVideoId('그냥 글자')).toBeNull();
        expect(parseYoutubeVideoId('')).toBeNull();
    });
});

describe('parseYoutubePlaylistId', () => {
    it('재생목록 링크에서 list ID 를 뽑는다', () => {
        expect(parseYoutubePlaylistId('https://www.youtube.com/playlist?list=PLabc_123-XYZ'))
            .toBe('PLabc_123-XYZ');
        // 영상+재생목록이 같이 있는 URL 도 재생목록으로 인식
        expect(parseYoutubePlaylistId(`https://www.youtube.com/watch?v=${ID}&list=PLabc`)).toBe('PLabc');
    });

    it('재생목록이 없으면 null', () => {
        expect(parseYoutubePlaylistId(`https://youtu.be/${ID}`)).toBeNull();
        expect(parseYoutubePlaylistId('아무 글자')).toBeNull();
    });
});
