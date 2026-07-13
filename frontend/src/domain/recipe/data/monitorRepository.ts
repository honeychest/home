// [AGENT] 전 사용자 대기열 모니터링 저장소 — 오너 전용 화면(MonitorPage) 전용 (2026-07-13 확정)
import type { MonitorSnapshot } from './monitorTypes';
import { HttpError, request } from './http';

export const MONITOR_LIMIT = 100;

export interface MonitorRepository {
    /** 전 사용자 대기열 스냅샷 — 최신 등록이 앞. 오너가 아니면 403 (HttpError) */
    list(limit: number): Promise<MonitorSnapshot>;
    /** 강제 재분석 — 상태·분류 무관, 요청자가 등록했는지도 무관 (오너 전용) */
    reanalyze(videoId: string): Promise<void>;
    /** 영상 삭제 — 분석정보만 지우고 REMOVED 로 표시(영상정보·연결은 유지, 오너 전용) */
    remove(videoId: string): Promise<void>;
}

export function createApiMonitorRepository(): MonitorRepository {
    return {
        list(limit) {
            return request<MonitorSnapshot>(`/api/recipe/registrations/monitor?limit=${limit}`);
        },
        reanalyze(videoId) {
            return request<void>(`/api/recipe/registrations/monitor/${encodeURIComponent(videoId)}/reanalyze`,
                { method: 'POST' });
        },
        remove(videoId) {
            return request<void>(`/api/recipe/registrations/monitor/${encodeURIComponent(videoId)}/remove`,
                { method: 'POST' });
        },
    };
}

export const monitorRepository: MonitorRepository = createApiMonitorRepository();

export const isForbidden = (e: unknown): boolean => e instanceof HttpError && e.status === 403;
