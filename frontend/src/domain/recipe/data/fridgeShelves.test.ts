// [AGENT] 선반 규칙 고정 — 기준은 냉장고 화면의 확정 결정 (CONTEXT.md 냉장고 절)
import { describe, expect, it } from 'vitest';
import { OLD_THRESHOLD_DAYS, classifyShelves, daysSince, shiftDate } from './fridgeShelves';
import type { FridgeItem } from './fridgeTypes';

const TODAY = new Date(2026, 6, 10); // 2026-07-10

function item(name: string, addedDate: string, expiring = false): FridgeItem {
    return { id: name, name, addedDate, expiring };
}

describe('classifyShelves', () => {
    it('임박 토글된 재료는 14일이 지났어도 임박 선반에만 놓인다', () => {
        const shelves = classifyShelves([item('계란', '2026-06-01', true)], TODAY);
        expect(shelves.expiring.map((i) => i.name)).toEqual(['계란']);
        expect(shelves.old).toEqual([]);
        expect(shelves.fresh).toEqual([]);
    });

    it(`정확히 ${OLD_THRESHOLD_DAYS}일 지난 재료는 오래됨, 하루 덜 지난 재료는 신선`, () => {
        const shelves = classifyShelves([item('두부', '2026-06-26'), item('감자', '2026-06-27')], TODAY);
        expect(shelves.old.map((i) => i.name)).toEqual(['두부']);
        expect(shelves.fresh.map((i) => i.name)).toEqual(['감자']);
    });

    it('임박·오래됨은 오래된 순(왼쪽=급한 것), 신선은 최신순(새것이 왼쪽)', () => {
        const shelves = classifyShelves([
            item('A', '2026-07-09'), item('B', '2026-07-10'), item('C', '2026-07-01'),
            item('D', '2026-06-01'), item('E', '2026-05-01'),
        ], TODAY);
        expect(shelves.old.map((i) => i.name)).toEqual(['E', 'D']);
        expect(shelves.fresh.map((i) => i.name)).toEqual(['B', 'A', 'C']);
    });
});

describe('shiftDate (날짜 스테퍼)', () => {
    it('하루 뒤로 이동하되 오늘을 넘어가면 오늘로 고정 (미래 금지)', () => {
        expect(shiftDate('2026-07-08', 1, TODAY)).toBe('2026-07-09');
        expect(shiftDate('2026-07-10', 1, TODAY)).toBe('2026-07-10');
    });

    it('하루 앞으로(과거) 이동하고 월 경계도 넘는다', () => {
        expect(shiftDate('2026-07-10', -1, TODAY)).toBe('2026-07-09');
        expect(shiftDate('2026-07-01', -1, TODAY)).toBe('2026-06-30');
    });
});

describe('daysSince', () => {
    it('오늘 등록은 0이고 음수는 없다', () => {
        expect(daysSince('2026-07-10', TODAY)).toBe(0);
        expect(daysSince('2026-07-11', TODAY)).toBe(0);
    });
});
