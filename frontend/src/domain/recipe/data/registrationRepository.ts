// [AGENT] recipe(기까) 등록·대기열 저장소 — 인터페이스 + API 구현체
// 2026-07-12 3차 백엔드 연결: 목 구현체를 API 구현체로 교체 (화면 무수정 — 성공 기준).
// 이전 목 구현은 git 이력 참고. 진행 반영은 화면 폴링(list 재호출) — 서버 대기열(DB+단일 워커)과 합치.
// 401(세션 만료)은 http.ts 공용 시임이 처리 — 이 파일과 화면은 인증을 모른다.
import type { RegistrationItem } from './registrationTypes';
import { DuplicateVideoError, UnavailableVideoError } from './registrationTypes';
import { HttpError, jsonBody, request } from './http';

export interface RegistrationRepository {
    /** 대기열 전체 (최신 등록이 앞) */
    list(): Promise<RegistrationItem[]>;
    /** 영상 1개 등록 — 같은 videoId 가 이미 있으면 DuplicateVideoError.
        반환 항목이 TOO_LONG 이면 화면이 즉시 "분석하지 않음"을 알린다 (등록 시점 메타 조회 덕분) */
    register(url: string): Promise<RegistrationItem>;
    /** 재생목록 일괄 등록 — 추가된 영상 수 반환 (이미 있던 영상은 건너뜀) */
    registerPlaylist(url: string): Promise<number>;
    /** 내 목록에서 지우기 — 내 연결만 삭제 (영상 자체·다른 사용자 연결은 무관, 오판 구제용
        재분석은 운영자 모드로 이동 — 2026-07-14 확정) */
    unregister(videoId: string): Promise<void>;
    /** 홈 섬네일용: 최근 분석 완료(DONE + RECIPE) 영상 */
    recentDone(limit: number): Promise<RegistrationItem[]>;
}

const BASE = '/api/recipe/registrations';

export function createApiRegistrationRepository(): RegistrationRepository {
    return {
        list() {
            return request<RegistrationItem[]>(BASE);
        },

        async register(url) {
            try {
                return await request<RegistrationItem>(BASE, { method: 'POST', ...jsonBody({ url }) });
            } catch (e) {
                if (e instanceof HttpError && e.status === 409) throw new DuplicateVideoError(url);
                // 404 = 비공개·삭제된 영상 (링크 형식은 클라이언트가 이미 검증했으므로 형식 오류가 아님)
                if (e instanceof HttpError && e.status === 404) throw new UnavailableVideoError(url);
                throw e;
            }
        },

        async registerPlaylist(url) {
            const result = await request<{ added: number }>(`${BASE}/playlist`, {
                method: 'POST', ...jsonBody({ url }),
            });
            return result.added;
        },

        unregister(videoId) {
            return request<void>(`${BASE}/${encodeURIComponent(videoId)}`, { method: 'DELETE' });
        },

        recentDone(limit) {
            return request<RegistrationItem[]>(`${BASE}/recent?limit=${limit}`);
        },
    };
}

export const registrationRepository: RegistrationRepository = createApiRegistrationRepository();
