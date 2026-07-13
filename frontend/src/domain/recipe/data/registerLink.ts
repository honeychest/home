// [AGENT] 영상 우선 등록 규칙 — 홈·레시피·공유 수신 3개 화면이 공유하는 단일 원본 (모범 패턴)
// 규칙 (2026-07-12 실사용 확정): 링크에 영상 ID 가 있으면 영상 1개 우선 —
// 재생목록 안에서 공유한 영상 링크(list= 동반)를 일괄 등록으로 오인하지 않게.
// 재생목록 일괄은 재생목록 자체 링크(v= 없음)로만.
// 문구는 화면 소유 (에러 계약) — 이 모듈은 결과(outcome)만 돌려주고, 각 화면이 문구·이동을 정한다.
import type { RegistrationItem } from './registrationTypes';
import { DuplicateVideoError } from './registrationTypes';
import type { RegistrationRepository } from './registrationRepository';
import { registrationRepository } from './registrationRepository';
import { parseYoutubePlaylistId, parseYoutubeVideoId } from './videoUrl';

export type RegisterOutcome =
    | { kind: 'invalid' }                                   // 유튜브 링크 아님 — 저장소 호출 없음
    | { kind: 'duplicate' }                                 // 이미 등록된 영상 (화면마다 정상/안내 선택)
    | { kind: 'video'; item: RegistrationItem }             // 영상 1개 등록됨 (TOO_LONG 즉시 안내용 item 포함)
    | { kind: 'playlist'; added: number };                  // 재생목록 일괄 — 추가된 수

export async function registerLink(
    rawUrl: string,
    repo: RegistrationRepository = registrationRepository,
): Promise<RegisterOutcome> {
    const url = rawUrl.trim();
    const videoId = parseYoutubeVideoId(url);
    const playlistId = parseYoutubePlaylistId(url);
    if (!videoId && !playlistId) return { kind: 'invalid' };
    try {
        if (videoId) {
            return { kind: 'video', item: await repo.register(url) };
        }
        return { kind: 'playlist', added: await repo.registerPlaylist(url) };
    } catch (e) {
        if (e instanceof DuplicateVideoError) return { kind: 'duplicate' };
        throw e; // 네트워크 등 진짜 실패 — 화면의 실행기(useMutation)가 문구 처리
    }
}
