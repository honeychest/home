// [AGENT] analysisQuality 판정 테스트 (PLAYBOOK 관례 6)
import { describe, expect, it } from 'vitest';
import { analysisQualityWarning } from './analysisQuality';

describe('analysisQualityWarning', () => {
    it('TRANSCRIPT 신호가 없으면 경고를 반환한다', () => {
        const warning = analysisQualityWarning({ status: 'DONE', analysisSignals: ['FRAMES'] });
        expect(warning).toContain('음성 인식');
    });

    it('TRANSCRIPT 신호가 있으면 경고 없음', () => {
        expect(analysisQualityWarning({
            status: 'DONE', analysisSignals: ['FRAMES', 'DESCRIPTION', 'TRANSCRIPT'],
        })).toBeNull();
    });

    it('아직 분석 전(DONE 아님)이면 신호가 없어도 경고하지 않는다', () => {
        expect(analysisQualityWarning({ status: 'WAITING', analysisSignals: null })).toBeNull();
        expect(analysisQualityWarning({ status: 'ANALYZING', analysisSignals: null })).toBeNull();
    });

    it('마이그레이션 이전 데이터(신호 자체가 null)는 경고하지 않는다 — "모른다"와 "부족했다"를 구분', () => {
        expect(analysisQualityWarning({ status: 'DONE', analysisSignals: null })).toBeNull();
    });
});
