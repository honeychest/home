// [AGENT] recipe(기까) 분석 품질 경고 — 순수 모듈 (PLAYBOOK 관례 6: 도메인 판정은 화면 밖 + vitest)
// 백엔드는 이 분석에 실제로 쓸 수 있었던 원시 신호(analysisSignals)만 사실대로 저장한다
// (2026-07-14 확정, pattern-raw-signal — springboot/AGENTS.md). 그 신호를 보고 "무슨 경고
// 문구를 보여줄지" 판단하는 건 이 파일의 몫이다(에러 계약 — 문구는 프론트 소유).
// 계기: 영상 an2jmE9LUHY의 Gemini 요약이 화면에 없는 고유명사(선수·챔피언명)를 확신에 차
// 지어낸 사례 — 프롬프트만으로는 못 막는다고 실측 확인, 대신 "이 분석은 근거가 부실했다"는
// 사실을 사용자에게 알려주는 쪽으로 방향을 잡았다 (CONTEXT.md "5차 확장" 절).
import type { RegistrationItem } from './registrationTypes';

/** 이 신호가 없으면 "음성 인식 내용이 거의 없었다"는 뜻 (RegistrationRules.MIN_TRANSCRIPT_CHARS 기준) */
const TRANSCRIPT_SIGNAL = 'TRANSCRIPT';

/**
 * 분석 품질 경고 문구. 아직 분석 전(DONE 아님)이거나 마이그레이션 이전이라 신호 자체가
 * 없으면(null) 경고하지 않는다 — "모른다"와 "부족했다"를 구분해야 과거 데이터 전체가
 * 경고투성이가 되지 않는다.
 */
export function analysisQualityWarning(
    item: Pick<RegistrationItem, 'status' | 'analysisSignals'>,
): string | null {
    if (item.status !== 'DONE' || item.analysisSignals === null) return null;
    if (item.analysisSignals.includes(TRANSCRIPT_SIGNAL)) return null;
    return '이 영상은 음성 인식 내용이 적어 분석이 부정확할 수 있어요. 원본 영상으로 확인해 주세요.';
}
