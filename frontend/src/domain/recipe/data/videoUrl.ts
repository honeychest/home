// [AGENT] recipe(기까) 유튜브 URL 파서 — 순수 모듈 (PLAYBOOK 관례 6: 도메인 판정은 화면 밖 + vitest)
// 다양한 링크 형태에서 영상 ID/재생목록 ID 만 정확히 추출해 중복 판별의 기반이 된다 (CONTEXT.md "레시피").
// 백엔드에도 같은 파서를 두지만(서버가 최종 방어선), 프론트는 즉시 피드백용으로 먼저 거른다.

const VIDEO_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/;

/**
 * 유튜브 영상 ID 추출. 인식 못 하면 null (문구는 화면 소유).
 * 지원: youtu.be/{id}, watch?v={id}, /shorts/{id}, /embed/{id}, /live/{id}
 * — 각각 뒤에 ?si= 등 쿼리가 붙어도 동작.
 */
export function parseYoutubeVideoId(rawUrl: string): string | null {
    const url = toUrl(rawUrl);
    if (!url) return null;
    const host = url.hostname.replace(/^www\.|^m\./, '');

    if (host === 'youtu.be') {
        return validId(url.pathname.split('/')[1]);
    }
    if (host === 'youtube.com' || host === 'youtube-nocookie.com') {
        const fromQuery = url.searchParams.get('v');
        if (fromQuery) return validId(fromQuery);
        const path = url.pathname.split('/').filter(Boolean); // 예: ['shorts', 'abc...']
        if (path.length >= 2 && ['shorts', 'embed', 'live'].includes(path[0])) {
            return validId(path[1]);
        }
    }
    return null;
}

/** 재생목록 ID 추출 (list= 파라미터). 영상+재생목록이 같이 있는 URL 도 재생목록으로 인식 가능 */
export function parseYoutubePlaylistId(rawUrl: string): string | null {
    const url = toUrl(rawUrl);
    if (!url) return null;
    const host = url.hostname.replace(/^www\.|^m\./, '');
    if (host !== 'youtube.com' && host !== 'youtu.be') return null;
    const list = url.searchParams.get('list');
    return list && /^[A-Za-z0-9_-]+$/.test(list) ? list : null;
}

function toUrl(raw: string): URL | null {
    const trimmed = raw.trim();
    if (!trimmed) return null;
    try {
        // 프로토콜 없이 붙여넣는 경우(youtube.com/...) 대비
        return new URL(/^https?:\/\//.test(trimmed) ? trimmed : `https://${trimmed}`);
    } catch {
        return null;
    }
}

function validId(candidate: string | undefined): string | null {
    return candidate && VIDEO_ID_PATTERN.test(candidate) ? candidate : null;
}
