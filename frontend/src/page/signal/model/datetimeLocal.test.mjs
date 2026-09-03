import test from 'node:test';
import assert from 'node:assert/strict';
import { datetimeLocalToMs, msToDatetimeLocal } from './datetimeLocal.js';

test('datetime-local 값을 로컬 시각 기준 밀리초로 변환하고 되돌린다', () => {
    const value = '2026-09-03T12:34';
    const ms = datetimeLocalToMs(value);

    assert.equal(Number.isFinite(ms), true);
    assert.equal(msToDatetimeLocal(ms), value);
});

test('빈 datetime-local 값은 null을 반환한다', () => {
    assert.equal(datetimeLocalToMs(''), null);
});
