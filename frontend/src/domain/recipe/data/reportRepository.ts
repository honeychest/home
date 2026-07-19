// [AGENT] 재료 신고 저장소 (2026-07-18 확정 — CONTEXT.md "재료 신고(전력 재분석)" 절).
// 일반 사용자 기능이다 — 지금은 공개 전이라 서버가 허용 목록(오너)으로 게이트하고, 화면도
// canViewMonitor 로만 노출한다(공개 시 양쪽 게이트만 풂). 접수는 즉시 응답(기록만)이고 처리
// (전력 재분석)는 서버 워커가 백그라운드로 — 결과는 나중에 결과 시트를 다시 열면 반영돼 보인다.
// 1인 1신고는 서버 UNIQUE 가 강제 — outcome 'already' 는 그 사실의 표면이다.
import { jsonBody, request } from './http';

export interface ReportOutcome {
    /** reported = 이번에 접수됨, already = 이미 접수돼 처리 대기 중 */
    outcome: 'reported' | 'already';
}

export interface ReportRepository {
    /** 재료 신고 접수 — 없는 영상=404 (HttpError). 문구는 화면 소유(에러 계약) */
    report(videoId: string, name: string): Promise<ReportOutcome>;
    /** 내가 이 영상에서 접수해 둔(처리 안 끝난) 재료 이름들 — 신고 버튼 상태 표시용 */
    myActive(videoId: string): Promise<string[]>;
}

export function createApiReportRepository(): ReportRepository {
    return {
        report(videoId, name) {
            return request<ReportOutcome>('/api/recipe/ingredient-reports',
                { method: 'POST', ...jsonBody({ videoId, name }) });
        },
        myActive(videoId) {
            return request<string[]>(
                `/api/recipe/ingredient-reports?videoId=${encodeURIComponent(videoId)}`);
        },
    };
}

export const reportRepository: ReportRepository = createApiReportRepository();
