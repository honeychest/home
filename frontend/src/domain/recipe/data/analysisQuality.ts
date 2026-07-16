// [AGENT] recipe(기까) 분석 품질 경고 — 순수 모듈 (PLAYBOOK 관례 6: 도메인 판정은 화면 밖 + vitest)
// 백엔드는 이 분석에 실제로 쓸 수 있었던 원시 신호(analysisSignals)만 사실대로 저장한다
// (2026-07-14 확정, pattern-raw-signal — springboot/AGENTS.md). 그 신호를 보고 "무슨 경고
// 문구를 보여줄지" 판단하는 건 이 파일의 몫이다(에러 계약 — 문구는 프론트 소유).
// 계기: 영상 an2jmE9LUHY의 Gemini 요약이 화면에 없는 고유명사(선수·챔피언명)를 확신에 차
// 지어낸 사례 — 프롬프트만으로는 못 막는다고 실측 확인, 대신 "이 분석은 근거가 부실했다"는
// 사실을 사용자에게 알려주는 쪽으로 방향을 잡았다 (CONTEXT.md "5차 확장" 절).
//
// 2026-07-16 확장 — 추출 결과 자체를 보는 판정 2종 추가. 근거: 사용자가 "떡볶이에 떡이 없다"를
// 우연히 발견했고 "레시피가 많아져 하나하나 눌러 확인하기 힘들다"고 제보. DB 조사 결과 RECIPE
// 116건 중 재료 0개 1건, 4개 이하 20건. 이 판정들은 analysisSignals 와 달리 이미 저장된
// recipe 만 보면 되므로 마이그레이션·재분석 없이 과거 데이터에도 그대로 적용된다.
import type { RegistrationItem } from './registrationTypes';

/** 이 신호가 없으면 "음성 인식 내용이 거의 없었다"는 뜻 (RegistrationRules.MIN_TRANSCRIPT_CHARS 기준) */
const TRANSCRIPT_SIGNAL = 'TRANSCRIPT';

/**
 * 이 개수 이하면 "재료가 적게 잡혔다"고 보고 검수를 유도한다 (2026-07-16 확정).
 * 4로 정한 근거는 실제 분포 — 재료가 빠진 게 확인된 사례("오직! 고추장…" 은 떡·간장·설탕 누락)가
 * 4개 지점에 있었고, 정상인 "떡볶이 양념 레시피"(양념만 만드는 영상이라 떡이 없는 게 정답)는
 * 6개라 안 걸린다. 이건 "틀렸다"가 아니라 "확인해 보라"는 신호라 다소 넉넉한 편이 낫다.
 */
export const FEW_INGREDIENTS_THRESHOLD = 4;

const EXTRACTION_FAILED_TEXT = '재료를 하나도 못 찾았어요 — 분석이 제대로 안 됐어요. 재분석이 필요해요.';
const FEW_INGREDIENTS_TEXT = '재료가 적게 잡혔어요 — 원본 영상과 다를 수 있으니 확인해 주세요.';
const NO_TRANSCRIPT_TEXT = '이 영상은 음성 인식 내용이 적어 분석이 부정확할 수 있어요. 원본 영상으로 확인해 주세요.';

type QualityInput = Pick<RegistrationItem, 'status' | 'analysisSignals' | 'recipe'>;

/**
 * 분석 품질 경고 문구. 아직 분석 전(DONE 아님)이면 경고하지 않는다.
 * 신호(analysisSignals)가 없으면(null — 마이그레이션 이전) 신호 기반 판정만 건너뛴다.
 * "모른다"와 "부족했다"를 구분해야 과거 데이터 전체가 경고투성이가 되지 않는다.
 *
 * 심각한 것부터 반환한다 (한 항목에 배지는 하나뿐이라 가장 급한 것을 보여줘야 함):
 * 추출 실패 → 재료 부족 → 음성 없음.
 */
export function analysisQualityWarning(item: QualityInput): string | null {
    if (item.status !== 'DONE') return null;
    // 추출 결과를 직접 보는 판정 (RECIPE 만 — TIP/ETC 는 애초에 재료가 없는 게 정상)
    if (item.recipe) {
        if (item.recipe.ingredients.length === 0 || item.recipe.steps.length === 0) {
            return EXTRACTION_FAILED_TEXT;
        }
        if (item.recipe.ingredients.length <= FEW_INGREDIENTS_THRESHOLD) {
            return FEW_INGREDIENTS_TEXT;
        }
    }
    if (item.analysisSignals === null) return null;
    if (item.analysisSignals.includes(TRANSCRIPT_SIGNAL)) return null;
    return NO_TRANSCRIPT_TEXT;
}

/** 목록에서 "확인 필요" 배지·필터를 붙일지 — 문구가 아니라 유무만 필요한 자리용 */
export function needsReview(item: QualityInput): boolean {
    return analysisQualityWarning(item) !== null;
}
