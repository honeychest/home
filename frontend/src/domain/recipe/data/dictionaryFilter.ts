// [AGENT] 재료 사전 화면의 목록 구성 판정 — 순수 모듈 (화면은 결과만 그린다, AGENTS.md "가져다 쓸 것").
// 사전이 243행이라 한 화면에 다 쏟으면 못 쓴다 (2026-07-17 사용자 지적) → 상태 칩 + 검색으로 자른다.
// AI 제안도 별도 블록이 아니라 이 행 안으로 병합된다 — 같은 재료가 화면 두 곳에 나오던 중복을 없앰.
import type { DictionaryEntry, DictionaryStatus, IngredientProposal } from './monitorTypes';

export type DictionaryFilter =
    | 'ATTENTION'
    | 'PROPOSED'
    | 'CONFIRMED_BASIC'
    | 'CONFIRMED_SEASONING'
    | 'CONFIRMED_MAIN'
    | 'ALL';

/** 재료 한 줄 = 지금 확정된 값(status) + AI 제안(proposed, 없으면 null) */
export interface DictionaryRow {
    name: string;
    status: DictionaryStatus;
    proposed: DictionaryStatus | null;
}

/** 제안 tier → 적용할 status. MAIN 제안은 서버가 빼고 보내지만 계약상 3종을 그대로 둔다 */
export const PROPOSAL_STATUS: Record<IngredientProposal['suggestedTier'], DictionaryStatus> = {
    SEASONING: 'CONFIRMED_SEASONING',
    BASIC: 'CONFIRMED_BASIC',
    MAIN: 'CONFIRMED_MAIN',
};

/** 오너가 아직 안 정한 것 — 목록 위로 올린다 */
export const needsAttention = (status: DictionaryStatus): boolean =>
    status === 'PENDING' || status === 'SKIPPED';

/**
 * 사전 + 제안을 행으로 합친다. 제안이 이미 확정된 값과 같으면 제안으로 안 친다
 * (표시할 게 없고, "제안" 칩에 이미 끝난 것이 섞이면 큐가 안 준다).
 */
export function buildRows(entries: DictionaryEntry[], proposals: IngredientProposal[]): DictionaryRow[] {
    const proposedByName = new Map(proposals.map((p) => [p.name, PROPOSAL_STATUS[p.suggestedTier]]));
    return entries
        .map((entry) => {
            const proposed = proposedByName.get(entry.name) ?? null;
            return {
                name: entry.name,
                status: entry.status,
                proposed: proposed === entry.status ? null : proposed,
            };
        })
        .sort((a, b) => Number(needsAttention(b.status)) - Number(needsAttention(a.status))
            || a.name.localeCompare(b.name));
}

export const matchesFilter = (row: DictionaryRow, filter: DictionaryFilter): boolean => {
    switch (filter) {
        case 'ATTENTION': return needsAttention(row.status);
        case 'PROPOSED': return row.proposed !== null;
        case 'ALL': return true;
        default: return row.status === filter;
    }
};

/**
 * 검색어가 있으면 칩 선택을 무시하고 전체에서 찾는다 — 검색의 목적이 "243개 중 아무거나
 * 1초에 찾기"라, 고른 칩이 결과를 가리면 그 목적이 깨진다 (2026-07-17 확정).
 */
export function visibleRows(rows: DictionaryRow[], filter: DictionaryFilter, search: string): DictionaryRow[] {
    const query = search.trim().toLowerCase();
    if (query !== '') return rows.filter((row) => row.name.toLowerCase().includes(query));
    return rows.filter((row) => matchesFilter(row, filter));
}

export const countRows = (rows: DictionaryRow[], filter: DictionaryFilter): number =>
    rows.reduce((n, row) => (matchesFilter(row, filter) ? n + 1 : n), 0);
