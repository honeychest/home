// [AGENT] recipe(기까) 등록·분석 데이터 모양 — 미래 API 응답과 동일하게 유지 (CONTEXT.md "개발 방식")
// 3차 백엔드 연결 시 이 타입 그대로 API가 반환해야 함. 바꾸면 인터페이스 설계 위반.
// platform·summaryVersion 은 확장성 선반영 필드 (CONTEXT.md).

/** 분석 대기열 상태 (CONTEXT.md 파이프라인: 길이 컷 → 분류+추출) */
export type RegistrationStatus =
    | 'WAITING'      // 대기열에 있음
    | 'ANALYZING'    // Gemini 분석 중
    | 'DONE'         // 분석 완료 (category 판정됨, RECIPE 면 recipe 채워짐)
    | 'TOO_LONG'     // 길이 컷 (MAX_VIDEO_MINUTES 초과 — 등록 순간 즉시 알림, 기록은 유지)
    | 'FAILED'       // 3회 재시도 후 실패 (수동 재분석 가능)
    | 'REMOVED';     // 오너가 영상을 삭제함(원본이 유튜브에서 사라진 경우 등) — 재분석하면 복구됨 (2026-07-13 확정)

/** 영상 분류 (2026-07-12 확정): 분석이 판정. 1단계 기능은 RECIPE 만 사용, TIP/ETC 는 저장만.
    2단계에서 TRAVEL, DIY 등으로 세분화 예정 */
export type VideoCategory = 'RECIPE' | 'TIP' | 'ETC';

/** 추출된 레시피 (재료는 원문 그대로 — 사전·정규화는 4차. 2026-07-12 확정) */
export interface ExtractedRecipe {
    name: string;
    /** 영상에 나온 이름 그대로 ("계란"/"달걀" 혼재 허용) */
    ingredients: string[];
    /** 조리시간(분) — 영상에서 파악 불가하면 null */
    cookMinutes: number | null;
    /** 단계 요약 (상세는 원본 영상으로 — 확정 결정) */
    steps: string[];
    /** AI 요약 버전 (모델·프롬프트 세대 — 확장성 선반영) */
    summaryVersion: number;
}

/** 대기열 항목 하나 = 영상 하나. 고유 열쇠는 videoId (중복 등록 방지 — 확정 결정) */
export interface RegistrationItem {
    videoId: string;
    url: string;
    platform: 'YOUTUBE';
    /** 분석(DONE) 전에는 null — 분류는 Gemini 판정 결과 */
    category: VideoCategory | null;
    status: RegistrationStatus;
    /** 메타 조회(YouTube Data API) 전에는 null */
    title: string | null;
    thumbnailUrl: string | null;
    durationSeconds: number | null;
    /** DONE + RECIPE 일 때만 채워짐 */
    recipe: ExtractedRecipe | null;
    /** DONE + TIP/ETC 일 때 요점 요약 2~3문장 (RECIPE 는 name·steps 가 대신 — 2026-07-13 확정) */
    summary: string | null;
    /** 검색용 키워드 (전 분류 공통 — 검색 기능은 나중, 지금은 적립. 2026-07-13 확정) */
    tags: string[] | null;
    /** 등록 시각 ISO — 목록 정렬 기준 (최신이 위) */
    registeredAt: string;
    /** 이 분석에 실제로 쓸 수 있었던 원시 신호 목록 (예: ["FRAMES","DESCRIPTION"] — TRANSCRIPT 가
        빠졌으면 음성 인식이 거의 안 됐다는 뜻). 경고 문구는 이 값을 보고 프론트가 도출한다
        (data/analysisQuality.ts 참고). 마이그레이션 이전 데이터는 null (2026-07-14 확정,
        pattern-raw-signal — springboot/AGENTS.md) */
    analysisSignals: string[] | null;
}

/** 같은 영상 재등록 — API 구현체는 409 를 이 에러로 변환한다 (문구는 화면 소유) */
export class DuplicateVideoError extends Error {
    constructor(videoId: string) {
        super(`duplicate video: ${videoId}`);
        this.name = 'DuplicateVideoError';
    }
}
