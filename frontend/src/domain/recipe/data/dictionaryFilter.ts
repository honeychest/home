// [AGENT] 재료 사전 화면의 목록 구성 판정 — 순수 모듈 (화면은 결과만 그린다, AGENTS.md "가져다 쓸 것").
// 사전이 243행이라 한 화면에 다 쏟으면 못 쓴다 (2026-07-17 사용자 지적) → 상태 칩 + 검색으로 자른다.
// AI 제안도 별도 블록이 아니라 이 행 안으로 병합된다 — 같은 재료가 화면 두 곳에 나오던 중복을 없앰.
//
// 그룹(슬라이스2)의 뜻 — 이 모듈의 판정 대부분이 여기서 나온다:
//   대표(matchKey === name) = 성격(양념 여부)을 스스로 정하는 행. 오너가 판정할 대상.
//   멤버(matchKey !== name) = "계란 2개"처럼 대표("계란")에 흡수된 행. 성격을 대표에서 물려받으므로
//     **오너가 판정할 게 없다** → 손볼 것 큐에서 빠진다. 이게 140개를 줄이는 장치다.
import type { DictionaryEntry, DictionaryStatus, IngredientProposal } from './monitorTypes';

export type DictionaryFilter =
    | 'ATTENTION'
    | 'PROPOSED'
    | 'MERGED'
    | 'CONFIRMED_BASIC'
    | 'CONFIRMED_SEASONING'
    | 'CONFIRMED_MAIN'
    | 'ALL';

/**
 * 재료 한 줄.
 * @param mergedInto 이 재료를 흡수한 대표 이름. null 이면 대표(안 묶임).
 * @param proposedStatus AI 분류 제안. 없으면 null.
 * @param proposedMergeInto AI 묶기 제안(흡수할 대표). 없으면 null.
 */
export interface DictionaryRow {
    name: string;
    status: DictionaryStatus;
    mergedInto: string | null;
    proposedStatus: DictionaryStatus | null;
    proposedMergeInto: string | null;
}

/** 제안 tier → 적용할 status */
export const PROPOSAL_STATUS: Record<'MAIN' | 'SEASONING' | 'BASIC', DictionaryStatus> = {
    SEASONING: 'CONFIRMED_SEASONING',
    BASIC: 'CONFIRMED_BASIC',
    MAIN: 'CONFIRMED_MAIN',
};

/** 오너가 판정할 게 남은 행 — 목록 위로 올린다. 멤버는 대표가 성격을 정하므로 대상이 아니다. */
export const needsAttention = (row: DictionaryRow): boolean =>
    row.mergedInto === null && (row.status === 'PENDING' || row.status === 'SKIPPED');

/** 이 행에 AI 제안이 붙어 있나 (분류든 묶기든) */
export const hasProposal = (row: DictionaryRow): boolean =>
    row.proposedStatus !== null || row.proposedMergeInto !== null;

/**
 * 사전 + 제안을 행으로 합친다. 제안이 지금 상태와 같으면 제안으로 안 친다
 * (표시할 게 없고, "제안" 칩에 이미 끝난 것이 섞이면 큐가 안 준다).
 */
export function buildRows(entries: DictionaryEntry[], proposals: IngredientProposal[]): DictionaryRow[] {
    const proposalByName = new Map(proposals.map((p) => [p.name, p]));
    return entries
        .map((entry) => {
            const mergedInto = entry.matchKey === entry.name ? null : entry.matchKey;
            const proposal = proposalByName.get(entry.name);
            const proposedStatus = proposal?.suggestedTier ? PROPOSAL_STATUS[proposal.suggestedTier] : null;
            const proposedMergeInto = proposal?.mergeInto ?? null;
            return {
                name: entry.name,
                status: entry.status,
                mergedInto,
                proposedStatus: proposedStatus === entry.status ? null : proposedStatus,
                proposedMergeInto: proposedMergeInto === mergedInto ? null : proposedMergeInto,
            };
        })
        .sort((a, b) => Number(needsAttention(b)) - Number(needsAttention(a)) || a.name.localeCompare(b.name));
}

export const matchesFilter = (row: DictionaryRow, filter: DictionaryFilter): boolean => {
    switch (filter) {
        case 'ATTENTION': return needsAttention(row);
        case 'PROPOSED': return hasProposal(row);
        case 'MERGED': return row.mergedInto !== null;
        case 'ALL': return true;
        // 상태 칩은 대표만 — 멤버의 status 는 매칭에 안 쓰이므로 여기 세면 거짓말이 된다
        default: return row.mergedInto === null && row.status === filter;
    }
};

/**
 * 검색어가 있으면 칩을 무시하고 전체에서 찾는다 — 검색의 목적이 "243개 중 아무거나 1초에
 * 찾기"라, 고른 칩이 결과를 가리면 그 목적이 깨진다 (2026-07-17 확정).
 */
export function visibleRows(rows: DictionaryRow[], filter: DictionaryFilter, search: string): DictionaryRow[] {
    const query = search.trim().toLowerCase();
    if (query !== '') return rows.filter((row) => row.name.toLowerCase().includes(query));
    return rows.filter((row) => matchesFilter(row, filter));
}

export const countRows = (rows: DictionaryRow[], filter: DictionaryFilter): number =>
    rows.reduce((n, row) => (matchesFilter(row, filter) ? n + 1 : n), 0);

/**
 * 묶기 시트의 대표 후보 (2026-07-18 — 오너가 AI 제안 없이도 직접 묶을 수 있게).
 * 대표(안 묶인 행)만, 자기 자신 제외. 미판정 대표도 허용한다 — 대표를 먼저 확정해야만
 * 묶을 수 있으면 순서 강제가 생기고, 멤버 성격은 대표를 나중에 확정하면 따라온다.
 * 고르는 자리라 "손볼 것 우선" 정렬 대신 이름순.
 */
export function mergeCandidates(rows: DictionaryRow[], target: string, search: string): DictionaryRow[] {
    const query = search.trim().toLowerCase();
    return rows
        .filter((row) => row.mergedInto === null && row.name !== target
            && (query === '' || row.name.toLowerCase().includes(query)))
        .sort((a, b) => a.name.localeCompare(b.name));
}
