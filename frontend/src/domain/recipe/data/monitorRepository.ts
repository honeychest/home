// [AGENT] 전 사용자 대기열 모니터링 저장소 — 오너 전용 화면(MonitorPage) 전용 (2026-07-13 확정)
// 재료 사전 관리(2026-07-17 5차-4)도 오너 전용이라 같은 저장소에 둔다(모니터 화면 한 곳 정책).
import type { DictionaryEntry, IngredientProposal, MonitorSnapshot } from './monitorTypes';
import { HttpError, jsonBody, request } from './http';

export const MONITOR_LIMIT = 100;

export interface MonitorRepository {
    /** 전 사용자 대기열 스냅샷 — 최신 등록이 앞. 오너가 아니면 403 (HttpError) */
    list(limit: number): Promise<MonitorSnapshot>;
    /** 강제 재분석 — 상태·분류 무관, 요청자가 등록했는지도 무관 (오너 전용) */
    reanalyze(videoId: string): Promise<void>;
    /** 영상 삭제 — 분석정보만 지우고 REMOVED 로 표시(영상정보·연결은 유지, 오너 전용) */
    remove(videoId: string): Promise<void>;
    /** 재료 사전 전체 (이름순) — 오너 전용 */
    listDictionary(): Promise<DictionaryEntry[]>;
    /** 재료 이름의 판정(status) 설정 — tier 는 서버가 파생. 없는 이름=404 (HttpError) */
    classifyIngredient(name: string, status: DictionaryEntry['status']): Promise<void>;
    /** AI 일괄 점검 — 아직 판정 안 된 이름 중 "양념일 것 같은" 제안만 (자동 반영 아님) */
    auditDictionary(): Promise<IngredientProposal[]>;
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
        listDictionary() {
            return request<DictionaryEntry[]>('/api/recipe/registrations/dictionary');
        },
        classifyIngredient(name, status) {
            return request<void>('/api/recipe/registrations/dictionary/classify',
                { method: 'POST', ...jsonBody({ name, status }) });
        },
        auditDictionary() {
            return request<IngredientProposal[]>('/api/recipe/registrations/dictionary/audit',
                { method: 'POST' });
        },
    };
}

export const monitorRepository: MonitorRepository = createApiMonitorRepository();

export const isForbidden = (e: unknown): boolean => e instanceof HttpError && e.status === 403;
