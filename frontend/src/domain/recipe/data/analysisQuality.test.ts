// [AGENT] analysisQuality 판정 테스트 (PLAYBOOK 관례 6)
import { describe, expect, it } from 'vitest';
import type { ExtractedRecipe } from './registrationTypes';
import { analysisQualityWarning, needsReview } from './analysisQuality';

/** 경고가 안 붙는 정상 레시피 */
const goodRecipe = (over: Partial<ExtractedRecipe> = {}): ExtractedRecipe => ({
    name: '떡볶이',
    ingredients: ['밀떡', '어묵', '대파', '고추장', '설탕'],
    cookMinutes: 15,
    steps: ['떡을 물에 담근다', '양념을 푼다', '끓인다'],
    summaryVersion: 1,
    ...over,
});
const done = { status: 'DONE' as const, analysisSignals: ['FRAMES', 'TRANSCRIPT'] };

describe('analysisQualityWarning — 원시 신호 기반', () => {
    it('TRANSCRIPT 신호가 없으면 경고를 반환한다', () => {
        const warning = analysisQualityWarning({
            status: 'DONE', analysisSignals: ['FRAMES'], recipe: null,
        });
        expect(warning).toContain('음성 인식');
    });

    it('TRANSCRIPT 신호가 있으면 경고 없음', () => {
        expect(analysisQualityWarning({
            status: 'DONE', analysisSignals: ['FRAMES', 'DESCRIPTION', 'TRANSCRIPT'], recipe: null,
        })).toBeNull();
    });

    it('아직 분석 전(DONE 아님)이면 신호가 없어도 경고하지 않는다', () => {
        expect(analysisQualityWarning({ status: 'WAITING', analysisSignals: null, recipe: null })).toBeNull();
        expect(analysisQualityWarning({ status: 'ANALYZING', analysisSignals: null, recipe: null })).toBeNull();
    });

    it('마이그레이션 이전 데이터(신호 자체가 null)는 경고하지 않는다 — "모른다"와 "부족했다"를 구분', () => {
        expect(analysisQualityWarning({ status: 'DONE', analysisSignals: null, recipe: null })).toBeNull();
    });
});

describe('analysisQualityWarning — 추출 결과 기반 (2026-07-16)', () => {
    it('재료가 하나도 없으면 추출 실패로 경고 (단호박 영상 실사례)', () => {
        const warning = analysisQualityWarning({
            ...done, recipe: goodRecipe({ ingredients: [], steps: [] }),
        });
        expect(warning).toContain('재료를 하나도 못 찾았어요');
    });

    it('조리 순서가 비어도 추출 실패로 본다', () => {
        expect(analysisQualityWarning({ ...done, recipe: goodRecipe({ steps: [] }) }))
            .toContain('재료를 하나도 못 찾았어요');
    });

    // 재료 개수 임계값은 실측으로 폐기했다 (2026-07-16) — 재료가 적은 게 정답인 라면·간단
    // 요리가 전부 걸려 경고 피로만 만들었다. 아래 두 케이스가 그 회귀 방지선이다.
    it('재료가 적어도(2개) 경고하지 않는다 — "재료 딱 하나로 라면" 처럼 적은 게 정답인 레시피가 실재', () => {
        expect(analysisQualityWarning({ ...done, recipe: goodRecipe({ ingredients: ['라면', 'MSG'] }) }))
            .toBeNull();
    });

    it('재료 1개짜리도 경고하지 않는다 — 개수는 오답의 증거가 못 된다', () => {
        expect(analysisQualityWarning({ ...done, recipe: goodRecipe({ ingredients: ['안성탕면'] }) }))
            .toBeNull();
    });

    it('신호가 null(과거 데이터)이어도 추출 결과 판정은 그대로 작동한다 — 소급 적용이 이 판정의 목적', () => {
        expect(analysisQualityWarning({
            status: 'DONE', analysisSignals: null, recipe: goodRecipe({ ingredients: [] }),
        })).toContain('재료를 하나도 못 찾았어요');
    });

    it('TIP/ETC(recipe 없음)는 재료 판정을 하지 않는다 — 재료가 없는 게 정상', () => {
        expect(analysisQualityWarning({ ...done, recipe: null })).toBeNull();
    });
});

describe('needsReview', () => {
    it('경고가 있으면 true, 없으면 false', () => {
        expect(needsReview({ ...done, recipe: goodRecipe({ ingredients: [] }) })).toBe(true);
        expect(needsReview({ ...done, recipe: goodRecipe() })).toBe(false);
    });
});
