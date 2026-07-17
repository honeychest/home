// [AGENT] 재료 사전 — 오너 전용 화면 (/recipe/monitor/dictionary, 2026-07-17 MonitorPage 섹션에서 승격).
// classify() 가 읽는 BASIC/SEASONING/MAIN 분류의 단일 원본(ingredient_dictionary)을 오너가 확정한다.
// 화면 구조 (2026-07-17 사용자 확정 — 그전엔 243행이 한 덩어리 + 제안이 별도 블록이라 같은 재료가
// 화면 두 곳에 나왔다):
//   - 행 하나 = 재료 하나. 지금 확정된 값과 AI 제안이 같은 행 안에 색으로 구분돼 함께 보인다.
//   - 목록은 상태 칩 + 검색으로 자른다 (사전은 등록할수록 커져서 스크롤로는 못 씀).
//   - 버튼 탭 = 즉시 저장 (미저장 상태를 안 만든다). 유일한 일괄 경로인 [제안 전체 적용]에만 확인창.
// 목록 구성 판정은 data/dictionaryFilter (순수 모듈 + vitest), 3상태 조회·조작 실행기는 공용 훅.
// AI 점검은 온디맨드 — 제안만 돌려주고 자동 반영하지 않는다 (안전 비대칭, CONTEXT.md).
import { useCallback, useState } from 'react';
import type { DictionaryEntry, DictionaryStatus, IngredientProposal } from '../data/monitorTypes';
import type { DictionaryFilter, DictionaryRow } from '../data/dictionaryFilter';
import {
    PROPOSAL_STATUS,
    buildRows,
    countRows,
    visibleRows,
} from '../data/dictionaryFilter';
import { isForbidden, monitorRepository } from '../data/monitorRepository';
import { useMutation } from './useMutation';
import { useQuery } from './useQuery';
import type { RcpBadgeVariant } from '../ui/RcpBadge';
import RcpBadge from '../ui/RcpBadge';
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
const SEARCH_PLACEHOLDER = '재료 이름 검색';
const EMPTY_TEXT = '해당하는 재료가 없어요.';
const NO_PROPOSAL_TEXT = '새로 분류할 만한 제안이 없어요.';
const applyAllText = (n: number) => `제안 ${n}개 전체 적용`;
const applyAllConfirmText = (n: number) =>
    `제안 ${n}개를 한 번에 반영할까요? 되돌리려면 한 건씩 다시 눌러야 해요.`;
const filterText = (label: string, n: number) => `${label} ${n}`;
const proposedLabel = (label: string) => `${label} (AI 제안)`;

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

/** 행의 버튼 = 오너가 확정할 수 있는 값들. 기본 = 늘 있어 장 볼 필요 없는 상비 양념(물·소금…)
    → 매칭에서 아예 뺌. 양념 = 없을 수 있는 것(고추장·굴소스…) → "양념만 부족" 으로 살아남음.
    보류 = 지금 모르겠음(큐에서 안 사라짐). 주재료 판정은 status 로만 표현된다 (CONTEXT.md). */
const ROW_ACTIONS: { status: DictionaryStatus; label: string }[] = [
    { status: 'CONFIRMED_BASIC', label: '기본' },
    { status: 'CONFIRMED_SEASONING', label: '양념' },
    { status: 'CONFIRMED_MAIN', label: '주재료' },
    { status: 'SKIPPED', label: '보류' },
];

const FILTERS: { key: DictionaryFilter; label: string }[] = [
    { key: 'ATTENTION', label: '손볼 것' },
    { key: 'PROPOSED', label: '제안' },
    { key: 'CONFIRMED_BASIC', label: '기본' },
    { key: 'CONFIRMED_SEASONING', label: '양념' },
    { key: 'CONFIRMED_MAIN', label: '주재료' },
    { key: 'ALL', label: '전체' },
];

const actionClass = (row: DictionaryRow, status: DictionaryStatus): string => {
    if (row.status === status) return 'rcp-dict-action rcp-dict-action-on';
    if (row.proposed === status) return 'rcp-dict-action rcp-dict-action-proposed';
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
    // null = 아직 AI 점검을 안 돌림 (그동안은 "제안" 칩 자체가 없다)
    const [proposals, setProposals] = useState<IngredientProposal[] | null>(null);
    const [filter, setFilter] = useState<DictionaryFilter>('ATTENTION');
    const [search, setSearch] = useState('');

    const classify = (name: string, status: DictionaryStatus) => mutation.run(async () => {
        // 낙관적 업데이트 — 실패하면 useMutation 이 reload 로 재동기화 (도메인 표준 패턴)
        setData((prev) => (prev ? prev.map((e) => (e.name === name ? { ...e, status } : e)) : prev));
        // 오너가 직접 정했으므로 그 재료의 제안은 역할이 끝났다 (제안 칩에서 바로 빠진다)
        setProposals((prev) => (prev ? prev.filter((p) => p.name !== name) : prev));
        await monitorRepository.classifyIngredient(name, status);
    });

    const runAudit = () => audit.run(async () => {
        setProposals(await monitorRepository.auditDictionary());
        setFilter('PROPOSED'); // 점검 결과를 보러 누른 것이므로 바로 그 칩으로
        setSearch('');
    });

    // 전체 적용 — 제안이 80건대라 한 건씩 누르면 실질적으로 못 쓴다 (2026-07-17 실측).
    // 한 번에 여러 건을 바꾸는 유일한 경로라 여기에만 확인창을 둔다 (2026-07-17 확정).
    const applyAll = () => {
        if (!proposals || proposals.length === 0) return;
        if (!window.confirm(applyAllConfirmText(proposals.length))) return;
        void mutation.run(async () => {
            await monitorRepository.classifyIngredients(
                proposals.map((p) => ({ name: p.name, status: PROPOSAL_STATUS[p.suggestedTier] })));
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
    // 점검 전에는 "제안" 칩을 감춘다 — 항상 0인 칩은 자리만 차지한다
    const chips = FILTERS.filter((f) => f.key !== 'PROPOSED' || proposals !== null);

    return (
        <main className="rcp-screen" id="rcp-dict-page">
            <header className="rcp-dict-head">
                <div className="rcp-dict-head-text">
                    <h1 className="rcp-screen-title">재료 사전</h1>
                    <p className="rcp-screen-subtitle">
                        양념으로 분류된 재료는 "양념만 부족" 으로 살아남아 추천에 더 잘 떠요 (오너 전용)
                    </p>
                </div>
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
                            onClick={applyAll}
                            disabled={mutation.busy}
                        >
                            {applyAllText(proposals.length)}
                        </button>
                    )}

                    <div className="rcp-dict-list">
                        {visible.length === 0 && (
                            <p className="rcp-empty">
                                {filter === 'PROPOSED' && search.trim() === '' ? NO_PROPOSAL_TEXT : EMPTY_TEXT}
                            </p>
                        )}
                        {visible.map((row) => (
                            <div className="rcp-dict-row" key={row.name}>
                                <span className="rcp-dict-name">{row.name}</span>
                                <RcpBadge variant={STATUS_BADGE[row.status]}>{STATUS_LABEL[row.status]}</RcpBadge>
                                <div className="rcp-dict-actions">
                                    {ROW_ACTIONS.map(({ status, label }) => (
                                        <button
                                            type="button"
                                            key={status}
                                            className={actionClass(row, status)}
                                            // 색만으로는 제안인지 안 보이는 경우(색맹·스크린리더)를 위해 이름에도 실음
                                            aria-label={row.proposed === status ? proposedLabel(label) : undefined}
                                            onClick={() => void classify(row.name, status)}
                                        >
                                            {label}
                                        </button>
                                    ))}
                                </div>
                            </div>
                        ))}
                    </div>
                </>
            )}
        </main>
    );
}
