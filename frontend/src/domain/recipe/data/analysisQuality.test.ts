// [AGENT] analysisQuality 판정 테스트 (PLAYBOOK 관례 6)
import { describe, expect, it } from 'vitest';
import type { ExtractedRecipe } from './registrationTypes';
import { analysisQualityWarning, needsReview } from './analysisQuality';

/** 경고가 안 붙는 정상 레시피 (재료 5개 = 임계값 4 초과) */
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

    it('재료가 임계값(4개) 이하면 검수 유도 경고 — "오직! 고추장…" 영상이 떡 누락된 채 4개였음', () => {
        const warning = analysisQualityWarning({
            ...done, recipe: goodRecipe({ ingredients: ['어묵', '대파', '식용유', '고추장'] }),
        });
        expect(warning).toContain('재료가 적게 잡혔어요');
    });

    it('재료가 임계값을 넘으면 경고 없음 — 양념만 만드는 정상 영상(6개)이 오탐되지 않아야 한다', () => {
        expect(analysisQualityWarning({
            ...done, recipe: goodRecipe({ ingredients: ['고춧가루', '설탕', '소금', '후추', '물엿', '물'] }),
        })).toBeNull();
    });

    it('추출 실패가 재료 부족보다 우선한다 (배지는 하나뿐 — 가장 급한 것)', () => {
        expect(analysisQualityWarning({ ...done, recipe: goodRecipe({ ingredients: [] }) }))
            .toContain('재료를 하나도 못 찾았어요');
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
