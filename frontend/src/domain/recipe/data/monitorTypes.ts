// [AGENT] 전 사용자 대기열 모니터링 데이터 모양 — 오너 전용 (2026-07-13 확정, 검증단계 필수 기능)
// registrationTypes.ts 와 별도 타입인 이유: 이 화면은 여러 사용자(email)를 넘나들며 보는
// 관리 관점 데이터라 RegistrationItem(내 것만) 계약과 섞지 않는다.
import type { RegistrationStatus, VideoCategory } from './registrationTypes';

export interface MonitorItem {
    userId: number;
    email: string;
    videoId: string;
    url: string;
    title: string | null;
    category: VideoCategory | null;
    status: RegistrationStatus;
    durationSeconds: number | null;
    attemptCount: number;
    /** 실패 사유 원문(개발자용) — 화면은 이 문자열을 그대로 노출해도 되는 오너 전용 화면이라 에러 계약 예외 */
    lastError: string | null;
    /** 마지막 시도의 실제 소요시간(초) — DONE/FAILED 로 끝난 뒤에만 값이 있음 (2026-07-13 확정) */
    analysisSeconds: number | null;
    registeredAt: string;
    /** ANALYZING 일 때만 값이 있음 — 경과 시간 계산 기준 */
    analyzingStartedAt: string | null;
    /** 로컬 대체 결과가 없어 Gemini 재시도 루프를 도는 중인 횟수 (2026-07-14 확정 —
        attempt_count 는 이 상황에서 소모되지 않게 설계돼 있어 별도 노출) */
    geminiRetryCount: number;
}

/** 전 사용자 대기열 스냅샷 — 대기열 크기·워커 생존·429 이력 + 항목 목록 (2026-07-13 확정) */
export interface MonitorSnapshot {
    waitingCount: number;
    analyzingCount: number;
    /** 워커가 틱마다 갱신하는 생존 신호 — 이게 오래 멈춰 있으면 스케줄러가 죽은 것 */
    workerHeartbeatAt: string | null;
    rateLimitCount: number;
    lastRateLimitedAt: string | null;
    /** Gemini 백오프 중이면 다음 재시도 가능 시각, 아니면 과거 시각 (2026-07-14 확정, 카운트다운용) */
    nextRetryAt: string | null;
    items: MonitorItem[];
}
