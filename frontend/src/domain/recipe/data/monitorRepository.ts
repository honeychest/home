// [AGENT] 전 사용자 대기열 모니터링 저장소 — 오너 전용 화면(MonitorPage) 전용 (2026-07-13 확정)
// 재료 사전 관리(2026-07-17 5차-4)도 오너 전용이라 같은 저장소에 둔다(모니터 화면 한 곳 정책).
import type {
    DictionaryEntry, DictionaryStatus, IngredientMerge, IngredientProposal, MonitorSnapshot,
} from './monitorTypes';
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
    classifyIngredient(name: string, status: DictionaryStatus): Promise<void>;
    /** 일괄 판정 — [AI 점검] 제안 전체 적용용. 개별과 달리 없는 이름은 404 가 아니라 조용히 건너뜀
        (제안이 80건대라 한 건씩 왕복하면 못 쓴다 — 2026-07-17 실측) */
    classifyIngredients(decisions: IngredientDecision[]): Promise<void>;
    /** 그룹 확정 — name 을 matchKey 그룹에 넣는다(matchKey === name 이면 해제).
        묶기는 오너 확정만 — AI 는 제안까지만이고 자동 병합 경로는 없다(안전 비대칭 규칙).
        없는 이름·사전에 없는 대표 = 404 (HttpError) */
    mergeIngredient(name: string, matchKey: string): Promise<void>;
    /** 일괄 그룹 확정 — [AI 점검] 병합 제안 전체 적용용 (없는 이름은 조용히 건너뜀) */
    mergeIngredients(merges: IngredientMerge[]): Promise<void>;
    /** AI 일괄 점검 — 분류 제안(아직 판정 안 된 이름의 양념·기본양념)과 묶기 제안("계란 2개"→"계란")
        을 함께 (자동 반영 아님).
        동기 LLM 호출이라 10초 이상 걸린다 — 화면은 useMutation 의 busy 로 진행 표시할 것 */
    auditDictionary(): Promise<IngredientProposal[]>;
}

/** 일괄 판정 한 건 — 제안(IngredientProposal)을 오너가 적용하기로 한 결정 */
export interface IngredientDecision {
    name: string;
    status: DictionaryStatus;
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
        classifyIngredients(decisions) {
            return request<void>('/api/recipe/registrations/dictionary/classify-batch',
                { method: 'POST', ...jsonBody(decisions) });
        },
        mergeIngredient(name, matchKey) {
            return request<void>('/api/recipe/registrations/dictionary/merge',
                { method: 'POST', ...jsonBody({ name, matchKey }) });
        },
        mergeIngredients(merges) {
            return request<void>('/api/recipe/registrations/dictionary/merge-batch',
                { method: 'POST', ...jsonBody(merges) });
        },
        auditDictionary() {
            // /llm/ 경로는 동기 LLM 호출 전용 — nginx 가 이 접두사에만 긴 타임아웃(120s)을 준다.
            // 일반 /api 는 15초라 여기 두면 10~25초짜리 이 호출이 504 로 끊긴다 (2026-07-17 실측).
            // 경로를 바꾸려면 nginx 설정도 함께 (IngredientAuditController 주석 참고).
            return request<IngredientProposal[]>('/api/recipe/llm/dictionary/audit',
                { method: 'POST' });
        },
    };
}

export const monitorRepository: MonitorRepository = createApiMonitorRepository();

export const isForbidden = (e: unknown): boolean => e instanceof HttpError && e.status === 403;
