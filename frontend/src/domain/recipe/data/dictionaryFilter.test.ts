import { describe, expect, it } from 'vitest';
import type { DictionaryEntry, IngredientProposal } from './monitorTypes';
import {
    buildRows,
    countRows,
    needsAttention,
    visibleRows,
} from './dictionaryFilter';

const entry = (name: string, status: DictionaryEntry['status']): DictionaryEntry =>
    ({ name, status, matchKey: name });
const proposal = (name: string, suggestedTier: IngredientProposal['suggestedTier']): IngredientProposal =>
    ({ name, suggestedTier });

describe('needsAttention', () => {
    it('오너가 아직 안 정한 것만 참', () => {
        expect(needsAttention('PENDING')).toBe(true);
        expect(needsAttention('SKIPPED')).toBe(true);
        expect(needsAttention('CONFIRMED_MAIN')).toBe(false);
        expect(needsAttention('CONFIRMED_SEASONING')).toBe(false);
        expect(needsAttention('CONFIRMED_BASIC')).toBe(false);
    });
});

describe('buildRows', () => {
    it('제안을 이름으로 같은 행에 붙인다 (별도 블록 없이)', () => {
        const rows = buildRows([entry('굴소스', 'PENDING')], [proposal('굴소스', 'SEASONING')]);
        expect(rows).toEqual([{ name: '굴소스', status: 'PENDING', proposed: 'CONFIRMED_SEASONING' }]);
    });

    it('제안이 이미 확정된 값과 같으면 제안으로 안 친다', () => {
        const rows = buildRows([entry('간장', 'CONFIRMED_BASIC')], [proposal('간장', 'BASIC')]);
        expect(rows[0].proposed).toBeNull();
    });

    it('사전에 없는 이름의 제안은 무시한다', () => {
        const rows = buildRows([entry('대파', 'PENDING')], [proposal('없는재료', 'SEASONING')]);
        expect(rows).toHaveLength(1);
        expect(rows[0].proposed).toBeNull();
    });

    it('손볼 것을 위로, 그 안에서는 이름순', () => {
        const rows = buildRows([
            entry('사과', 'CONFIRMED_MAIN'),
            entry('참기름', 'SKIPPED'),
            entry('고추장', 'PENDING'),
            entry('가지', 'CONFIRMED_BASIC'),
        ], []);
        expect(rows.map((r) => r.name)).toEqual(['고추장', '참기름', '가지', '사과']);
    });
});

describe('visibleRows', () => {
    const rows = buildRows([
        entry('굴소스', 'PENDING'),
        entry('진간장', 'CONFIRMED_SEASONING'),
        entry('간장', 'CONFIRMED_BASIC'),
        entry('돼지고기', 'CONFIRMED_MAIN'),
        entry('두반장', 'SKIPPED'),
    ], [proposal('돼지고기', 'SEASONING')]);

    it('ATTENTION = 미판정·보류', () => {
        expect(visibleRows(rows, 'ATTENTION', '').map((r) => r.name)).toEqual(['굴소스', '두반장']);
    });

    it('PROPOSED = AI 제안이 붙은 행만', () => {
        expect(visibleRows(rows, 'PROPOSED', '').map((r) => r.name)).toEqual(['돼지고기']);
    });

    it('상태 칩은 그 상태만', () => {
        expect(visibleRows(rows, 'CONFIRMED_BASIC', '').map((r) => r.name)).toEqual(['간장']);
        expect(visibleRows(rows, 'CONFIRMED_MAIN', '').map((r) => r.name)).toEqual(['돼지고기']);
    });

    it('ALL = 전부', () => {
        expect(visibleRows(rows, 'ALL', '')).toHaveLength(5);
    });

    it('검색어가 있으면 칩을 무시하고 전체에서 찾는다', () => {
        // ATTENTION 칩이 걸려 있어도 확정된 "간장"·"진간장"이 나와야 한다 (이름순)
        expect(visibleRows(rows, 'ATTENTION', '간장').map((r) => r.name)).toEqual(['간장', '진간장']);
    });

    it('검색어 앞뒤 공백은 무시, 빈 검색어면 칩이 다시 적용된다', () => {
        expect(visibleRows(rows, 'ATTENTION', '  굴소스 ').map((r) => r.name)).toEqual(['굴소스']);
        expect(visibleRows(rows, 'ATTENTION', '   ').map((r) => r.name)).toEqual(['굴소스', '두반장']);
    });

    it('없는 이름은 빈 목록', () => {
        expect(visibleRows(rows, 'ALL', '없는재료')).toEqual([]);
    });
});

describe('countRows', () => {
    it('칩에 붙는 개수는 검색과 무관하게 그 칩의 전체 개수', () => {
        const rows = buildRows([
            entry('굴소스', 'PENDING'),
            entry('두반장', 'SKIPPED'),
            entry('간장', 'CONFIRMED_BASIC'),
        ], [proposal('간장', 'SEASONING')]);
        expect(countRows(rows, 'ATTENTION')).toBe(2);
        expect(countRows(rows, 'PROPOSED')).toBe(1);
        expect(countRows(rows, 'ALL')).toBe(3);
        expect(countRows(rows, 'CONFIRMED_MAIN')).toBe(0);
    });
});
