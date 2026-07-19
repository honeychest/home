// [AGENT] 재료 사전 — 오너 전용 화면 (/recipe/monitor/dictionary, 2026-07-17 MonitorPage 섹션에서 승격).
// 재료 성격(BASIC/SEASONING/MAIN)과 그룹의 단일 원본(ingredient_dictionary)을 오너가 확정한다.
// 화면 구조 (2026-07-17 사용자 확정 — 그전엔 243행이 한 덩어리 + 제안이 별도 블록이라 같은 재료가
// 화면 두 곳에 나왔다):
//   - 행 하나 = 재료 하나. 지금 확정된 값과 AI 제안이 같은 행 안에 색으로 구분돼 함께 보인다.
//   - 목록은 상태 칩 + 검색으로 자른다 (사전은 등록할수록 커져서 스크롤로는 못 씀).
//   - 버튼 탭 = 즉시 저장 (미저장 상태를 안 만든다). 일괄 경로인 [제안 전체 적용]에만 확인창.
// 그룹(슬라이스2): 멤버("계란 2개")는 성격을 대표("계란")에서 물려받으므로 분류 버튼을 안 보여준다 —
// 보여주면 눌러도 매칭에 아무 효과가 없어 거짓말이 된다.
// 사전 변경 경로 (2026-07-18 자동화): 분석 파이프라인이 신규 PENDING 을 스스로 판정·병합하고
// (수량·단위 규칙 + AI 판정, PENDING 만 — 오너 판정 불가침), 오너는 [자동 반영] 시트에서 그
// 내역을 사후 감사한다. 화면의 수동 경로(아래)는 교정·보완용으로 그대로 유지. 수동 경로는 두 가지:
// AI 제안 수락, 또는 행의 [묶기] → "그룹 관리" 하단 시트 (2026-07-18 — 제안이 있어야만 묶을 수
// 있던 구멍을 메움). 시트는 그룹의 상세 컨텍스트다: 멤버 목록에서 [빼기]하면 그 자리에
// [다시 묶기]로 남아 실수 복구가 즉시 된다 — 목록 위에서 해제하면 필터 조건에서 빠져 행이
// 눈앞에서 사라지던 문제(2026-07-18 사용자 지적)의 구조적 해결. 시트를 연 시점의 멤버 스냅샷을
// 유지하는 이유가 이것이다(라이브로만 그리면 빼는 순간 행이 또 사라진다).
// 목록 구성 판정은 data/dictionaryFilter (순수 모듈 + vitest), 3상태 조회·조작 실행기는 공용 훅.
import { useCallback, useState } from 'react';
import type {
    DictionaryChange, DictionaryEntry, DictionaryStatus, IngredientProposal,
} from '../data/monitorTypes';
import type { DictionaryFilter, DictionaryRow } from '../data/dictionaryFilter';
import {
    PROPOSAL_STATUS,
    buildRows,
    countRows,
    mergeCandidates,
    visibleRows,
} from '../data/dictionaryFilter';
import { isForbidden, monitorRepository } from '../data/monitorRepository';
import { useMutation } from './useMutation';
import { useQuery } from './useQuery';
import type { RcpBadgeVariant } from '../ui/RcpBadge';
import RcpBadge from '../ui/RcpBadge';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpConfirm from '../ui/RcpConfirm';
import RcpInlineError from '../ui/RcpInlineError';
import RcpLoadError from '../ui/RcpLoadError';

const FORBIDDEN_TEXT = '이 화면은 오너 전용이에요';
const LOAD_ERROR_TEXT = '재료 사전을 불러오지 못했어요 — 다시 시도해 주세요';
// 403 은 "네트워크 문제"가 아니라 접근 거부라 문구가 다르다 (MonitorPage 와 같은 패턴)
const loadMessage = (e: unknown) => (isForbidden(e) ? FORBIDDEN_TEXT : LOAD_ERROR_TEXT);
const ACTION_FAIL_TEXT = '반영하지 못했어요 — 다시 시도해 주세요';
const AUDIT_FAIL_TEXT = 'AI 점검을 하지 못했어요 — 잠시 후 다시 시도해 주세요';
const AUDIT_TEXT = 'AI 점검';
const AUDIT_BUSY_TEXT = '점검 중…';
// 자동 반영 내역 (2026-07-18) — 파이프라인이 사전을 스스로 바꾼 기록의 사후 감사 시트
const CHANGES_TEXT = '자동 반영';
const CHANGES_SHEET_TITLE = '자동 반영 내역';
const CHANGES_FAIL_TEXT = '자동 반영 내역을 불러오지 못했어요 — 다시 시도해 주세요';
const CHANGES_LOADING_TEXT = '불러오는 중…';
const NO_CHANGES_TEXT = '아직 자동으로 반영된 내역이 없어요.';
const CHANGE_SOURCE_LABEL: Record<DictionaryChange['source'], string> = {
    AUTO_VARIANT: '수량·단위 규칙',
    AUTO_AUDIT: 'AI 판정',
};
/** 내역 한 줄의 변경 설명 — 병합은 "→ 대표", 분류는 status 라벨로 (문구/데이터 분리) */
const changeDetailText = (change: DictionaryChange): string =>
    change.action === 'MERGE'
        ? mergedText(change.newValue)
        : (STATUS_LABEL as Record<string, string>)[change.newValue] ?? change.newValue;
const changeDateText = (iso: string): string => {
    const d = new Date(iso);
    return `${d.getMonth() + 1}월 ${d.getDate()}일`;
};
const SEARCH_PLACEHOLDER = '재료 이름 검색';
const MERGE_TEXT = '묶기';
const MERGE_PICK_TEXT = '이 그룹으로';
const MERGE_SEARCH_PLACEHOLDER = '대표 재료 검색';
const NO_CANDIDATE_TEXT = '묶을 수 있는 대표가 없어요.';
const MEMBER_UNMERGE_TEXT = '빼기';
const MEMBER_REMERGE_TEXT = '다시 묶기';
const MEMBERS_LABEL = '묶인 재료';
const NO_MEMBERS_TEXT = '아직 묶인 재료가 없어요.';
const groupSheetTitle = (rep: string) => `"${rep}" 그룹 관리`;
const moveSectionLabel = (name: string) => `"${name}" 옮길 그룹 찾기`;
const EMPTY_TEXT = '해당하는 재료가 없어요.';
const NO_PROPOSAL_TEXT = '새로 분류할 만한 제안이 없어요.';
const applyAllText = (n: number) => `제안 ${n}개 전체 적용`;
const applyAllConfirmText = (n: number) =>
    `제안 ${n}개를 한 번에 반영할까요? 되돌리려면 한 건씩 다시 눌러야 해요.`;
const filterText = (label: string, n: number) => `${label} ${n}`;
const mergedText = (representative: string) => `→ ${representative}`;
const proposedMergeLabel = (representative: string) => `${representative} 그룹에 넣기 (AI 제안)`;
const proposedTierLabel = (label: string) => `${label} (AI 제안)`;

const STATUS_LABEL: Record<DictionaryStatus, string> = {
    PENDING: '미판정',
    SKIPPED: '보류',
    CONFIRMED_MAIN: '주재료 확정',
    CONFIRMED_SEASONING: '양념 확정',
    CONFIRMED_BASIC: '기본양념 확정',
};
const STATUS_BADGE: Record<DictionaryStatus, RcpBadgeVariant> = {
    PENDING: 'neutral',
    SKIPPED: 'warning',
    CONFIRMED_MAIN: 'good',
    CONFIRMED_SEASONING: 'good',
    CONFIRMED_BASIC: 'good',
};

/** 대표가 확정할 수 있는 값들. 기본 = 늘 있어 장 볼 필요 없는 상비 양념(물·소금…) → 매칭에서 아예 뺌.
    양념 = 없을 수 있는 것(고추장·굴소스…) → "양념만 부족" 으로 살아남음. 보류 = 지금 모르겠음. */
const ROW_ACTIONS: { status: DictionaryStatus; label: string }[] = [
    { status: 'CONFIRMED_BASIC', label: '기본' },
    { status: 'CONFIRMED_SEASONING', label: '양념' },
    { status: 'CONFIRMED_MAIN', label: '주재료' },
    { status: 'SKIPPED', label: '보류' },
];

const FILTERS: { key: DictionaryFilter; label: string }[] = [
    { key: 'ATTENTION', label: '손볼 것' },
    { key: 'PROPOSED', label: '제안' },
    { key: 'MERGED', label: '묶임' },
    { key: 'CONFIRMED_BASIC', label: '기본' },
    { key: 'CONFIRMED_SEASONING', label: '양념' },
    { key: 'CONFIRMED_MAIN', label: '주재료' },
    { key: 'ALL', label: '전체' },
];

const actionClass = (row: DictionaryRow, status: DictionaryStatus): string => {
    if (row.status === status) return 'rcp-dict-action rcp-dict-action-on';
    if (row.proposedStatus === status) return 'rcp-dict-action rcp-dict-action-proposed';
    return 'rcp-dict-action';
};

export default function DictionaryPage() {
    const load = useCallback(() => monitorRepository.listDictionary(), []);
    const query = useQuery<DictionaryEntry[]>(load, loadMessage);
    const { data: entries, error: loadError, reload, setData } = query;
    // 접근 거부는 "다시 시도"가 의미 없는 다른 화면이라 원인으로 갈라낸다 (useQuery 는 403 을 모른다)
    const forbidden = query.failure !== null && isForbidden(query.failure);
    const mutation = useMutation(() => ACTION_FAIL_TEXT, reload);
    const audit = useMutation(() => AUDIT_FAIL_TEXT);
    // 자동 반영 내역 시트 — 열 때마다 새로 조회 (사후 감사라 항상 최신이어야 의미가 있다)
    const changesLoad = useMutation(() => CHANGES_FAIL_TEXT);
    const [changes, setChanges] = useState<DictionaryChange[] | null>(null);
    const [changesOpen, setChangesOpen] = useState(false);
    const openChanges = () => {
        setChangesOpen(true);
        setChanges(null);
        void changesLoad.run(async () => {
            setChanges(await monitorRepository.listDictionaryChanges());
        });
    };
    // null = 아직 AI 점검을 안 돌림 (그동안은 "제안" 칩 자체가 없다)
    const [proposals, setProposals] = useState<IngredientProposal[] | null>(null);
    const [filter, setFilter] = useState<DictionaryFilter>('ATTENTION');
    const [search, setSearch] = useState('');
    // 그룹 관리 시트 (2026-07-18) — name: [묶기]를 누른 재료, rep: 그 그룹의 대표(연 시점 고정),
    // members: 연 시점의 멤버 스냅샷 ([빼기] 후에도 행이 남아 [다시 묶기]로 즉시 복구 가능)
    const [groupSheet, setGroupSheet] =
        useState<{ name: string; rep: string; members: string[] } | null>(null);
    const [mergeSearch, setMergeSearch] = useState('');

    /** 이 이름의 제안은 역할이 끝났다 — 오너가 직접 정했으므로 제안 칩에서 바로 뺀다 */
    const dropProposal = (name: string) =>
        setProposals((prev) => (prev ? prev.filter((p) => p.name !== name) : prev));

    const classify = (name: string, status: DictionaryStatus) => mutation.run(async () => {
        // 낙관적 업데이트 — 실패하면 useMutation 이 reload 로 재동기화 (도메인 표준 패턴)
        setData((prev) => (prev ? prev.map((e) => (e.name === name ? { ...e, status } : e)) : prev));
        dropProposal(name);
        await monitorRepository.classifyIngredient(name, status);
    });

    /** matchKey === name 이면 그룹 해제 (같은 API). 서버는 name 을 대표로 삼던 멤버도 함께
        옮긴다(깊이 1 평탄화) — 낙관적 업데이트도 똑같이 따라간다 (해제 때 e.matchKey === name
        조건은 이미 같은 값이라 무해). */
    const merge = (name: string, matchKey: string) => mutation.run(async () => {
        setData((prev) => (prev ? prev.map(
            (e) => (e.name === name || e.matchKey === name ? { ...e, matchKey } : e)) : prev));
        dropProposal(name);
        await monitorRepository.mergeIngredient(name, matchKey);
    });

    const openGroupSheet = (row: DictionaryRow) => {
        if (entries === null) return;
        const rep = row.mergedInto ?? row.name;
        const members = entries.filter((e) => e.matchKey === rep && e.name !== rep)
            .map((e) => e.name)
            .sort((a, b) => a.localeCompare(b));
        setGroupSheet({ name: row.name, rep, members });
        setMergeSearch('');
    };
    const closeGroupSheet = () => setGroupSheet(null);
    /** 다른 그룹 선택 — 그룹 컨텍스트(rep)가 바뀌므로 시트를 닫고 즉시 저장 */
    const moveToGroup = (representative: string) => {
        if (groupSheet === null) return;
        const { name } = groupSheet;
        closeGroupSheet();
        void merge(name, representative);
    };

    const runAudit = () => audit.run(async () => {
        setProposals(await monitorRepository.auditDictionary());
        setFilter('PROPOSED'); // 점검 결과를 보러 누른 것이므로 바로 그 칩으로
        setSearch('');
    });

    // 전체 적용 — 제안이 80건대라 한 건씩 누르면 실질적으로 못 쓴다 (2026-07-17 실측).
    // 한 번에 여러 건을 바꾸는 유일한 경로라 여기에만 확인창을 둔다 (2026-07-17 확정).
    // 확인은 킷 다이얼로그(RcpConfirm)로 — 시스템 창 금지 (2026-07-19 확정).
    // 두 종류(분류·묶기)를 각자의 API 로 보낸다 — 계약이 다르다(하나는 status, 하나는 대표).
    const [confirmApplyAll, setConfirmApplyAll] = useState(false);
    const applyAll = () => {
        if (!proposals || proposals.length === 0) return;
        setConfirmApplyAll(false);
        void mutation.run(async () => {
            const tiers = proposals.filter((p) => p.suggestedTier !== null);
            const merges = proposals.filter((p) => p.mergeInto !== null);
            if (tiers.length > 0) {
                await monitorRepository.classifyIngredients(
                    tiers.map((p) => ({ name: p.name, status: PROPOSAL_STATUS[p.suggestedTier!] })));
            }
            if (merges.length > 0) {
                await monitorRepository.mergeIngredients(
                    merges.map((p) => ({ name: p.name, matchKey: p.mergeInto! })));
            }
            setProposals([]);
            setFilter('ATTENTION'); // 제안이 비었으니 다음 할 일(손볼 것)로
            await reload();
        });
    };

    if (forbidden) {
        return (
            <main className="rcp-screen" id="rcp-dict-page">
                <p className="rcp-empty" role="alert">{FORBIDDEN_TEXT}</p>
            </main>
        );
    }

    const rows = entries === null ? null : buildRows(entries, proposals ?? []);
    const visible = rows === null ? null : visibleRows(rows, filter, search);
    // 시트의 "옮길 그룹" 후보 — 지금 그룹의 대표는 제외(같은 그룹으로 옮기기는 무의미)
    const candidates = rows === null || groupSheet === null
        ? [] : mergeCandidates(rows, groupSheet.name, mergeSearch)
            .filter((c) => c.name !== groupSheet.rep);
    // 시트 멤버 행의 [빼기]/[다시 묶기] 판정용 — 스냅샷이 아니라 지금 상태를 본다
    const entryByName = new Map((entries ?? []).map((e) => [e.name, e]));
    // 점검 전에는 "제안" 칩을 감춘다 — 항상 0인 칩은 자리만 차지한다
    const chips = FILTERS.filter((f) => f.key !== 'PROPOSED' || proposals !== null);

    return (
        <main className="rcp-screen" id="rcp-dict-page">
            <header className="rcp-dict-head">
                <div className="rcp-dict-head-text">
                    <h1 className="rcp-screen-title">재료 사전</h1>
                    <p className="rcp-screen-subtitle">
                        양념은 "양념만 부족"으로 살아남고, 묶은 재료는 대표가 있으면 있는 걸로 쳐요 (오너 전용)
                    </p>
                </div>
                <div className="rcp-dict-head-actions">
                    {/* 파이프라인이 스스로 바꾼 내역의 사후 감사 (2026-07-18) */}
                    <button
                        type="button"
                        className="rcp-btn rcp-btn-ghost rcp-dict-audit"
                        onClick={openChanges}
                    >
                        {CHANGES_TEXT}
                    </button>
                    {/* 동기 LLM 호출이라 10초 이상 걸린다 — 표시가 없으면 눌러도 아무 반응이 없어
                        고장으로 보인다 (2026-07-17). busy 동안 disabled 는 useMutation 의 연타 방지를
                        화면에도 드러내는 것 (막고 있다는 걸 보여준다) */}
                    <button
                        type="button"
                        className="rcp-btn rcp-btn-ghost rcp-dict-audit"
                        onClick={runAudit}
                        disabled={audit.busy}
                    >
                        {audit.busy ? AUDIT_BUSY_TEXT : AUDIT_TEXT}
                    </button>
                </div>
            </header>

            <RcpInlineError message={mutation.error ?? audit.error} />
            <RcpLoadError message={loadError} onRetry={() => void reload()} />
            {!loadError && rows === null && <p className="rcp-empty">불러오는 중…</p>}

            {rows !== null && visible !== null && (
                <>
                    <div className="rcp-dict-filters" role="group" aria-label="재료 사전 필터">
                        {chips.map(({ key, label }) => (
                            <button
                                type="button"
                                key={key}
                                className={`rcp-dict-action ${filter === key ? 'rcp-dict-action-on' : ''}`.trim()}
                                aria-pressed={filter === key}
                                onClick={() => setFilter(key)}
                            >
                                {filterText(label, countRows(rows, key))}
                            </button>
                        ))}
                    </div>
                    {/* 검색 중에는 칩을 무시하고 전체에서 찾는다 (판정은 dictionaryFilter) */}
                    <input
                        className="rcp-input rcp-dict-search"
                        type="search"
                        value={search}
                        placeholder={SEARCH_PLACEHOLDER}
                        aria-label={SEARCH_PLACEHOLDER}
                        onChange={(e) => setSearch(e.target.value)}
                    />

                    {proposals !== null && proposals.length > 0 && filter === 'PROPOSED' && (
                        <button
                            type="button"
                            className="rcp-btn rcp-btn-full rcp-dict-apply-all"
                            onClick={() => setConfirmApplyAll(true)}
                            disabled={mutation.busy}
                        >
                            {applyAllText(proposals.length)}
                        </button>
                    )}

                    <RcpConfirm
                        open={confirmApplyAll}
                        message={applyAllConfirmText(proposals?.length ?? 0)}
                        confirmLabel="전체 적용"
                        onConfirm={applyAll}
                        onCancel={() => setConfirmApplyAll(false)}
                    />

                    <div className="rcp-dict-list">
                        {visible.length === 0 && (
                            <p className="rcp-empty">
                                {filter === 'PROPOSED' && search.trim() === '' ? NO_PROPOSAL_TEXT : EMPTY_TEXT}
                            </p>
                        )}
                        {visible.map((row) => (
                            // 2단 행: 이름 줄(전체 폭, 1줄 말줄임) / 조작 줄. 확정 상태 배지는 안 둔다 —
                            // 버튼 불(그린)이 이미 같은 말을 해서 이름 자리만 뺏었다 (2026-07-18 제거)
                            <div className="rcp-dict-row" key={row.name}>
                                <span className="rcp-dict-name">{row.name}</span>
                                <div className="rcp-dict-foot">
                                    {row.mergedInto !== null && (
                                        <RcpBadge variant="neutral">{mergedText(row.mergedInto)}</RcpBadge>
                                    )}
                                    <div className="rcp-dict-actions">
                                        {/* 멤버는 분류 버튼 없음 — 성격은 대표가 정한다. 묶기 제안이
                                            있으면 분류 버튼 대신 그것부터(묶이면 분류는 대표가 정한다) */}
                                        {row.mergedInto === null && (row.proposedMergeInto !== null ? (
                                            <button
                                                type="button"
                                                className="rcp-dict-action rcp-dict-action-proposed"
                                                aria-label={proposedMergeLabel(row.proposedMergeInto)}
                                                onClick={() => void merge(row.name, row.proposedMergeInto!)}
                                            >
                                                {mergedText(row.proposedMergeInto)}
                                            </button>
                                        ) : ROW_ACTIONS.map(({ status, label }) => (
                                            <button
                                                type="button"
                                                key={status}
                                                className={actionClass(row, status)}
                                                // 색만으로는 제안인지 안 보이는 경우(색맹·스크린리더)를 위해 이름에도 실음
                                                aria-label={row.proposedStatus === status
                                                    ? proposedTierLabel(label) : undefined}
                                                onClick={() => void classify(row.name, status)}
                                            >
                                                {label}
                                            </button>
                                        )))}
                                        {/* AI 제안 유무와 무관하게 항상 — 그룹 관리 시트를 연다 */}
                                        <button
                                            type="button"
                                            className="rcp-dict-action"
                                            onClick={() => openGroupSheet(row)}
                                        >
                                            {MERGE_TEXT}
                                        </button>
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>

                    {/* 자동 반영 내역 시트 — 최신순. 복구는 시트를 닫고 해당 재료의 그룹 해제·
                        재분류로 (내역 행에 되돌리기 버튼을 안 두는 이유: 그 사이 오너가 다른 값으로
                        바꿨을 수 있어 "로그 시점으로 되돌리기"가 오히려 사고를 만든다) */}
                    {changesOpen && (
                        <RcpBottomSheet open title={CHANGES_SHEET_TITLE} onClose={() => setChangesOpen(false)}>
                            <RcpInlineError message={changesLoad.error} />
                            {changesLoad.busy && <p className="rcp-empty">{CHANGES_LOADING_TEXT}</p>}
                            {changes !== null && changes.length === 0 && (
                                <p className="rcp-empty">{NO_CHANGES_TEXT}</p>
                            )}
                            {(changes ?? []).map((change) => (
                                <div className="rcp-dict-row" key={change.id}>
                                    <span className="rcp-dict-name">{change.name}</span>
                                    <div className="rcp-dict-foot">
                                        <RcpBadge variant="neutral">{changeDetailText(change)}</RcpBadge>
                                        <span className="rcp-dict-change-meta">
                                            {CHANGE_SOURCE_LABEL[change.source]}
                                            {' · '}
                                            {changeDateText(change.createdAt)}
                                        </span>
                                    </div>
                                </div>
                            ))}
                        </RcpBottomSheet>
                    )}

                    {/* 그룹 관리 시트 — 멤버 검수([빼기]↔[다시 묶기] 즉시 복구)와 옮기기를
                        그룹 컨텍스트 안에서 처리. 후보 판정은 dictionaryFilter.mergeCandidates */}
                    {groupSheet !== null && (
                        <RcpBottomSheet
                            open
                            title={groupSheetTitle(groupSheet.rep)}
                            onClose={closeGroupSheet}
                        >
                            <h3 className="rcp-section-label">{MEMBERS_LABEL}</h3>
                            {groupSheet.members.length === 0 && (
                                <p className="rcp-empty">{NO_MEMBERS_TEXT}</p>
                            )}
                            {groupSheet.members.map((member) => {
                                // 스냅샷으로 행을 유지하되 버튼은 지금 상태 기준 — 빼도 행이 안 사라진다
                                const stillMerged = entryByName.get(member)?.matchKey === groupSheet.rep;
                                return (
                                    <div className="rcp-dict-row" key={member}>
                                        <span className="rcp-dict-name">{member}</span>
                                        <div className="rcp-dict-foot">
                                            <div className="rcp-dict-actions">
                                                <button
                                                    type="button"
                                                    className="rcp-dict-action"
                                                    onClick={() => void merge(
                                                        member, stillMerged ? member : groupSheet.rep)}
                                                >
                                                    {stillMerged ? MEMBER_UNMERGE_TEXT : MEMBER_REMERGE_TEXT}
                                                </button>
                                            </div>
                                        </div>
                                    </div>
                                );
                            })}

                            <h3 className="rcp-section-label">{moveSectionLabel(groupSheet.name)}</h3>
                            <input
                                className="rcp-input rcp-dict-search"
                                type="search"
                                value={mergeSearch}
                                placeholder={MERGE_SEARCH_PLACEHOLDER}
                                aria-label={MERGE_SEARCH_PLACEHOLDER}
                                onChange={(e) => setMergeSearch(e.target.value)}
                            />
                            <div className="rcp-dict-list">
                                {candidates.map((candidate) => (
                                    <div className="rcp-dict-row" key={candidate.name}>
                                        <span className="rcp-dict-name">{candidate.name}</span>
                                        <div className="rcp-dict-foot">
                                            <RcpBadge variant={STATUS_BADGE[candidate.status]}>
                                                {STATUS_LABEL[candidate.status]}
                                            </RcpBadge>
                                            <div className="rcp-dict-actions">
                                                <button
                                                    type="button"
                                                    className="rcp-dict-action"
                                                    onClick={() => moveToGroup(candidate.name)}
                                                >
                                                    {MERGE_PICK_TEXT}
                                                </button>
                                            </div>
                                        </div>
                                    </div>
                                ))}
                                {candidates.length === 0 && (
                                    <p className="rcp-empty">{NO_CANDIDATE_TEXT}</p>
                                )}
                            </div>
                        </RcpBottomSheet>
                    )}
                </>
            )}
        </main>
    );
}
