import { describe, expect, it } from 'vitest';
import type { DictionaryEntry, IngredientProposal } from './monitorTypes';
import {
    buildRows,
    countRows,
    hasProposal,
    needsAttention,
    visibleRows,
} from './dictionaryFilter';

const entry = (name: string, status: DictionaryEntry['status'], matchKey = name): DictionaryEntry =>
    ({ name, status, matchKey });
const tierProposal = (name: string, suggestedTier: 'MAIN' | 'SEASONING' | 'BASIC'): IngredientProposal =>
    ({ name, suggestedTier, mergeInto: null });
const mergeProposal = (name: string, mergeInto: string): IngredientProposal =>
    ({ name, suggestedTier: null, mergeInto });
const row = (name: string, status: DictionaryEntry['status'], matchKey = name) =>
    buildRows([entry(name, status, matchKey)], [])[0];

describe('needsAttention', () => {
    it('오너가 아직 안 정한 대표만 참', () => {
        expect(needsAttention(row('굴소스', 'PENDING'))).toBe(true);
        expect(needsAttention(row('두반장', 'SKIPPED'))).toBe(true);
        expect(needsAttention(row('두부', 'CONFIRMED_MAIN'))).toBe(false);
    });

    it('묶인 멤버는 판정할 게 없어 큐에서 빠진다 — 성격은 대표가 정한다 (140개를 줄이는 장치)', () => {
        expect(needsAttention(row('계란 2개', 'PENDING', '계란'))).toBe(false);
    });
});

describe('buildRows', () => {
    it('분류 제안을 이름으로 같은 행에 붙인다 (별도 블록 없이)', () => {
        const rows = buildRows([entry('굴소스', 'PENDING')], [tierProposal('굴소스', 'SEASONING')]);
        expect(rows[0].proposedStatus).toBe('CONFIRMED_SEASONING');
        expect(rows[0].proposedMergeInto).toBeNull();
    });

    it('묶기 제안도 같은 행에 붙는다', () => {
        const rows = buildRows([entry('계란 2개', 'PENDING')], [mergeProposal('계란 2개', '계란')]);
        expect(rows[0].proposedMergeInto).toBe('계란');
        expect(rows[0].proposedStatus).toBeNull();
    });

    it('matchKey 가 자기 이름이면 대표, 다르면 그 그룹의 멤버', () => {
        expect(row('계란', 'CONFIRMED_MAIN').mergedInto).toBeNull();
        expect(row('계란 2개', 'PENDING', '계란').mergedInto).toBe('계란');
    });

    it('제안이 이미 확정된 값과 같으면 제안으로 안 친다', () => {
        const rows = buildRows([entry('간장', 'CONFIRMED_BASIC')], [tierProposal('간장', 'BASIC')]);
        expect(rows[0].proposedStatus).toBeNull();
        expect(hasProposal(rows[0])).toBe(false);
    });

    it('이미 그 그룹인데 또 묶으라는 제안은 제안으로 안 친다', () => {
        const rows = buildRows([entry('계란 2개', 'PENDING', '계란')], [mergeProposal('계란 2개', '계란')]);
        expect(rows[0].proposedMergeInto).toBeNull();
    });

    it('사전에 없는 이름의 제안은 무시한다', () => {
        const rows = buildRows([entry('대파', 'PENDING')], [tierProposal('없는재료', 'SEASONING')]);
        expect(rows).toHaveLength(1);
        expect(hasProposal(rows[0])).toBe(false);
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
        entry('계란 2개', 'PENDING', '계란'),
        entry('계란', 'CONFIRMED_MAIN'),
    ], [mergeProposal('돼지고기', '고기')]);

    it('ATTENTION = 판정 안 된 대표만 (묶인 "계란 2개"는 빠진다)', () => {
        expect(visibleRows(rows, 'ATTENTION', '').map((r) => r.name)).toEqual(['굴소스', '두반장']);
    });

    it('PROPOSED = AI 제안이 붙은 행만', () => {
        expect(visibleRows(rows, 'PROPOSED', '').map((r) => r.name)).toEqual(['돼지고기']);
    });

    it('MERGED = 묶인 멤버만 — 오너가 오병합을 검수하는 자리', () => {
        expect(visibleRows(rows, 'MERGED', '').map((r) => r.name)).toEqual(['계란 2개']);
    });

    it('상태 칩은 대표만 센다 — 멤버의 status 는 매칭에 안 쓰이므로 세면 거짓말이 된다', () => {
        expect(visibleRows(rows, 'CONFIRMED_MAIN', '').map((r) => r.name)).toEqual(['계란', '돼지고기']);
        expect(visibleRows(rows, 'CONFIRMED_BASIC', '').map((r) => r.name)).toEqual(['간장']);
    });

    it('ALL = 전부 (멤버 포함)', () => {
        expect(visibleRows(rows, 'ALL', '')).toHaveLength(7);
    });

    it('검색어가 있으면 칩을 무시하고 전체에서 찾는다', () => {
        // ATTENTION 칩이 걸려 있어도 확정된 "간장"·"진간장"이 나와야 한다 (이름순)
        expect(visibleRows(rows, 'ATTENTION', '간장').map((r) => r.name)).toEqual(['간장', '진간장']);
    });

    it('검색은 묶인 멤버도 찾는다 — 오병합을 확인하려면 보여야 한다', () => {
        expect(visibleRows(rows, 'ATTENTION', '계란').map((r) => r.name)).toEqual(['계란', '계란 2개']);
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
            entry('계란 2개', 'PENDING', '계란'),
            entry('계란', 'CONFIRMED_MAIN'),
        ], [tierProposal('간장', 'SEASONING')]);
        expect(countRows(rows, 'ATTENTION')).toBe(2);
        expect(countRows(rows, 'PROPOSED')).toBe(1);
        expect(countRows(rows, 'MERGED')).toBe(1);
        expect(countRows(rows, 'CONFIRMED_MAIN')).toBe(1);
        expect(countRows(rows, 'ALL')).toBe(5);
    });
});
