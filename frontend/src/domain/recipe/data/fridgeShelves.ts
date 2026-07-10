// [AGENT] 냉장고 날짜·선반 규칙 — 순수 모듈 (DB·브라우저 없이 테스트 가능).
// 화면(FridgePage)과 4차 추천 정렬("임박 재료 사용 최상단 → 오래된 재료 순")이 같은 판정을 공유한다.
// 확정 결정: 임박(수동 토글)은 임박 선반에만, 나머지는 14일 기준 오래됨/신선.
// 정렬 — 임박·오래됨: 오래된 순(왼쪽=급한 것), 신선: 최신순(방금 넣은 게 스크롤 없이 보이게).
import type { FridgeItem } from './fridgeTypes';

export const OLD_THRESHOLD_DAYS = 14; // "오래됨" 선반 기준 (2026-07-09 확정)

export function toDateString(date: Date): string {
    const mm = String(date.getMonth() + 1).padStart(2, '0');
    const dd = String(date.getDate()).padStart(2, '0');
    return `${date.getFullYear()}-${mm}-${dd}`;
}

/** 등록일로부터 지난 일수 (자정 기준, 음수 없음) */
export function daysSince(dateString: string, today: Date = new Date()): number {
    const [y, m, d] = dateString.split('-').map(Number);
    const then = new Date(y, m - 1, d).getTime();
    const base = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
    return Math.max(0, Math.round((base - then) / 86400000));
}

/** "7월 10일" — 표시용 (영어는 2단계 i18n 도입 때) */
export function formatDate(dateString: string): string {
    const [, month, day] = dateString.split('-');
    return `${Number(month)}월 ${Number(day)}일`;
}

/** 기존 날짜에서 delta일 이동 (미래 금지 — 오늘까지만. 날짜 스테퍼의 규칙) */
export function shiftDate(dateString: string, delta: number, today: Date = new Date()): string {
    const [y, m, d] = dateString.split('-').map(Number);
    const date = new Date(y, m - 1, d);
    date.setDate(date.getDate() + delta);
    if (date.getTime() > today.getTime()) return toDateString(today);
    return toDateString(date);
}

export interface Shelves {
    expiring: FridgeItem[]; // 오래된 순
    old: FridgeItem[];      // 오래된 순
    fresh: FridgeItem[];    // 최신순
}

export function classifyShelves(items: FridgeItem[], today: Date = new Date()): Shelves {
    const byOldest = [...items].sort((a, b) => a.addedDate.localeCompare(b.addedDate));
    const expiring = byOldest.filter((i) => i.expiring);
    const rest = byOldest.filter((i) => !i.expiring);
    const old = rest.filter((i) => daysSince(i.addedDate, today) >= OLD_THRESHOLD_DAYS);
    const fresh = rest.filter((i) => daysSince(i.addedDate, today) < OLD_THRESHOLD_DAYS).reverse();
    return { expiring, old, fresh };
}
