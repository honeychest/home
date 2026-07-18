// [AGENT] 전 사용자 대기열 모니터링 데이터 모양 — 오너 전용 (2026-07-13 확정, 검증단계 필수 기능)
// registrationTypes.ts 와 별도 타입인 이유: 이 화면은 여러 사용자(email)를 넘나들며 보는
// 관리 관점 데이터라 RegistrationItem(내 것만) 계약과 섞지 않는다.
import type { ExtractedRecipe, RegistrationStatus, VideoCategory } from './registrationTypes';

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

/**
 * mac-mini 호스트 서비스가 스스로 관측해 보고한 사실 (2026-07-16 신설).
 * 설정값이 아니라 "지금 도는 프로세스의 실제 상태"다 — 하루에 두 번, 설정은 맞는데 실제
 * 환경이 다른 사고가 났기 때문(저장소는 최신인데 launchd 는 손으로 뜬 사본을 돌고 있었고,
 * deno 는 설치돼 있는데 launchd 의 최소 PATH 라 yt-dlp 가 못 찾았다).
 * 스냅샷의 localExtractor 가 null 이면 서비스가 죽었거나 /health 없는 옛 버전 — 그 null
 * 자체가 "지금 로컬을 못 쓴다(전부 Gemini 로 간다)"는 사실이다.
 */
export interface LocalExtractorHealth {
    /** 실행 중인 server.py 의 절대경로 — 사본을 돌고 있으면 여기서 드러난다 */
    serverPath: string;
    targetFrames: number;
    lmStudioModel: string;
    whisperModelExists: boolean;
    ytDlpExists: boolean;
    ffmpegExists: boolean;
    whisperCliExists: boolean;
    /** 이 프로세스가 받은 PATH 에서 deno 가 찾아지는가 — 없으면 yt-dlp 가 deprecated 경로로 돈다 */
    denoOnPath: boolean;
}

/** 재료 사전 항목 — 재료 성격 판정의 단일 원본 (2026-07-17 5차-4, 오너 전용).
    status 가 곧 원본이다 — 별도 tier 필드를 두지 않는다(순수 파생이라 2026-07-17 점검에서 제거).
      CONFIRMED_BASIC     = 늘 있는 상비 양념(물·소금…) → 매칭에서 아예 뺌
      CONFIRMED_SEASONING = 없을 수 있는 양념(고추장·굴소스…) → "양념만 부족" 으로 살아남음
      그 외(PENDING·SKIPPED·CONFIRMED_MAIN) = 주재료 취급(안전 기본값) */
export type DictionaryStatus =
    'PENDING' | 'SKIPPED' | 'CONFIRMED_MAIN' | 'CONFIRMED_SEASONING' | 'CONFIRMED_BASIC';

export interface DictionaryEntry {
    name: string;
    /** 그룹 매칭 키 (슬라이스2) — "무엇이 있으면 이걸 가진 걸로 칠까".
        자기 이름이면 대표(안 묶임), 다른 이름이면 그 그룹의 멤버다.
        **멤버의 양념 여부는 대표가 정한다** — 멤버 자신의 status 는 무시되므로 화면도 멤버에겐
        분류 버튼을 안 보여준다(보여주면 눌러도 아무 효과가 없어 거짓말이 된다). */
    matchKey: string;
    status: DictionaryStatus;
}

/** AI 점검 제안 한 건 — 자동 반영 아님(오너가 확인). 두 종류가 섞여 온다.
    서버가 쓸모없는 제안(MAIN·이미 확정된 것·없는 이름·체인)은 걸러서 보낸다. */
export interface IngredientProposal {
    name: string;
    /** 분류 제안. 묶기 제안이면 null — 묶이면 분류는 대표가 정하므로 제안할 게 없다 */
    suggestedTier: 'MAIN' | 'SEASONING' | 'BASIC' | null;
    /** 묶기 제안 — 이 이름을 흡수할 대표 이름. 분류 제안이면 null */
    mergeInto: string | null;
}

/** 그룹 확정 한 건 — matchKey === name 이면 그룹 해제 */
export interface IngredientMerge {
    name: string;
    matchKey: string;
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
    /** null = 로컬 추출기를 지금 못 씀 (죽었거나 /health 없는 옛 버전) → 전부 Gemini 로 간다 */
    localExtractor: LocalExtractorHealth | null;
    /** 전체(limit 무관) 상태별 등록 행 수 — 상태 칩 필터의 개수 (2026-07-18) */
    statusCounts: Partial<Record<RegistrationStatus, number>>;
    items: MonitorItem[];
}

/** 모니터 시트의 분석 내용 — 탭한 1건만 조회 (2026-07-18. 폴링 목록에 안 실음 — payload 낭비) */
export interface MonitorAnalysis {
    category: VideoCategory | null;
    recipe: ExtractedRecipe | null;
    summary: string | null;
    tags: string[] | null;
    analysisSignals: string[] | null;
}
