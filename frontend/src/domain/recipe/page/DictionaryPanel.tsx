// [AGENT] 재료 사전 관리 — 오너 전용, MonitorPage 안 섹션 (2026-07-17 5차-4 슬라이스1).
// classify() 가 읽는 MAIN/SEASONING 분류의 단일 원본(ingredient_dictionary)을 오너가 직접
// 확정하거나 [AI 점검] 제안으로 반영한다. 3상태 조회(useQuery)+조작 실행기(useMutation) 공용 훅 재사용.
// AI 점검은 온디맨드 — 제안(양념일 수 있음)만 돌려주고 자동 반영하지 않는다(안전 비대칭, CONTEXT.md).
import { useCallback, useState } from 'react';
import type { DictionaryEntry, DictionaryStatus, IngredientProposal } from '../data/monitorTypes';
import { monitorRepository } from '../data/monitorRepository';
import { useMutation } from './useMutation';
import { useQuery } from './useQuery';
import type { RcpBadgeVariant } from '../ui/RcpBadge';
import RcpBadge from '../ui/RcpBadge';
import RcpInlineError from '../ui/RcpInlineError';
import RcpLoadError from '../ui/RcpLoadError';

const LOAD_ERROR_TEXT = '재료 사전을 불러오지 못했어요 — 다시 시도해 주세요';
const ACTION_FAIL_TEXT = '반영하지 못했어요 — 다시 시도해 주세요';
const AUDIT_FAIL_TEXT = 'AI 점검을 하지 못했어요 — 잠시 후 다시 시도해 주세요';
const AUDIT_TEXT = 'AI 점검';
const AUDIT_BUSY_TEXT = '점검 중…';

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
/** 제안 tier → 적용할 status. MAIN 제안은 서버가 빼고 보내므로 여기 올 일이 없다 */
const PROPOSAL_STATUS: Record<IngredientProposal['suggestedTier'], DictionaryStatus> = {
    SEASONING: 'CONFIRMED_SEASONING',
    BASIC: 'CONFIRMED_BASIC',
    MAIN: 'CONFIRMED_MAIN',
};
const PROPOSAL_HINT: Record<IngredientProposal['suggestedTier'], string> = {
    SEASONING: '양념일 수 있어요',
    BASIC: '늘 있는 기본양념일 수 있어요',
    MAIN: '주재료일 수 있어요',
};
const applyAllText = (n: number) => `제안 ${n}개 전체 적용`;

/** 판정 안 된 것(미판정·보류)을 위로 — 오너가 손볼 대상이 먼저 보이게 */
const needsAttention = (s: DictionaryEntry['status']): boolean => s === 'PENDING' || s === 'SKIPPED';
const actionClass = (on: boolean): string => (on ? 'rcp-dict-action rcp-dict-action-on' : 'rcp-dict-action');

export default function DictionaryPanel() {
    const load = useCallback(() => monitorRepository.listDictionary(), []);
    const query = useQuery<DictionaryEntry[]>(load, () => LOAD_ERROR_TEXT);
    const { data: entries, error: loadError, reload, setData } = query;
    const mutation = useMutation(() => ACTION_FAIL_TEXT, reload);
    const audit = useMutation(() => AUDIT_FAIL_TEXT);
    const [proposals, setProposals] = useState<IngredientProposal[] | null>(null);

    const classify = (name: string, status: DictionaryStatus) => mutation.run(async () => {
        // 낙관적 업데이트 — 실패하면 useMutation 이 reload 로 재동기화 (도메인 표준 패턴)
        setData((prev) => (prev
            ? prev.map((e) => (e.name === name ? { ...e, status } : e))
            : prev));
        await monitorRepository.classifyIngredient(name, status);
    });

    const runAudit = () => audit.run(async () => {
        setProposals(await monitorRepository.auditDictionary());
    });

    const applyProposal = (p: IngredientProposal) => mutation.run(async () => {
        await monitorRepository.classifyIngredient(p.name, PROPOSAL_STATUS[p.suggestedTier]);
        setProposals((prev) => (prev ? prev.filter((x) => x.name !== p.name) : prev));
        await reload();
    });

    // 전체 적용 — 제안이 80건대라 한 건씩 누르면 실질적으로 못 쓴다 (2026-07-17 실측)
    const applyAll = () => mutation.run(async () => {
        if (!proposals || proposals.length === 0) return;
        await monitorRepository.classifyIngredients(
            proposals.map((p) => ({ name: p.name, status: PROPOSAL_STATUS[p.suggestedTier] })));
        setProposals(null);
        await reload();
    });

    const sorted = entries === null
        ? null
        : [...entries].sort((a, b) =>
            Number(needsAttention(b.status)) - Number(needsAttention(a.status)) || a.name.localeCompare(b.name));

    return (
        <section id="rcp-dict-panel" aria-label="재료 사전 관리">
            <div className="rcp-dict-head">
                <h2 className="rcp-section-label">재료 사전 (오너 전용)</h2>
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
            <p className="rcp-screen-subtitle">
                양념으로 분류된 재료는 "양념만 부족" 으로 살아남아 추천에 더 잘 떠요.
            </p>

            <RcpInlineError message={mutation.error ?? audit.error} />
            <RcpLoadError message={loadError} onRetry={() => void reload()} />
            {!loadError && sorted === null && <p className="rcp-empty">불러오는 중…</p>}

            {proposals !== null && (
                <div className="rcp-dict-proposals" aria-label="AI 점검 제안">
                    {proposals.length === 0 ? (
                        <p className="rcp-empty">새로 분류할 만한 제안이 없어요.</p>
                    ) : (
                        <>
                            <button
                                type="button"
                                className="rcp-btn rcp-btn-full"
                                onClick={() => void applyAll()}
                                disabled={mutation.busy}
                            >
                                {applyAllText(proposals.length)}
                            </button>
                            {proposals.map((p) => (
                                <div className="rcp-dict-proposal" key={p.name}>
                                    <span className="rcp-dict-name">{p.name}</span>
                                    <span className="rcp-dict-proposal-hint">
                                        {PROPOSAL_HINT[p.suggestedTier]}
                                    </span>
                                    <button
                                        type="button"
                                        className="rcp-dict-action rcp-dict-action-on"
                                        onClick={() => void applyProposal(p)}
                                    >
                                        {STATUS_LABEL[PROPOSAL_STATUS[p.suggestedTier]]}
                                    </button>
                                </div>
                            ))}
                        </>
                    )}
                </div>
            )}

            {sorted !== null && (
                <div className="rcp-dict-list">
                    {sorted.length === 0 && <p className="rcp-empty">사전이 비어 있어요.</p>}
                    {sorted.map((e) => (
                        <div className="rcp-dict-row" key={e.name}>
                            <span className="rcp-dict-name">{e.name}</span>
                            <RcpBadge variant={STATUS_BADGE[e.status]}>{STATUS_LABEL[e.status]}</RcpBadge>
                            <div className="rcp-dict-actions">
                                {/* 기본 = 늘 있는 상비 양념(물·소금…) → 매칭에서 아예 뺌.
                                    양념 = 없을 수 있는 것(고추장·굴소스…) → "양념만 부족" 으로 살아남음 */}
                                <button
                                    type="button"
                                    className={actionClass(e.status === 'CONFIRMED_BASIC')}
                                    onClick={() => void classify(e.name, 'CONFIRMED_BASIC')}
                                >
                                    기본
                                </button>
                                <button
                                    type="button"
                                    className={actionClass(e.status === 'CONFIRMED_SEASONING')}
                                    onClick={() => void classify(e.name, 'CONFIRMED_SEASONING')}
                                >
                                    양념
                                </button>
                                <button
                                    type="button"
                                    className={actionClass(e.status === 'CONFIRMED_MAIN')}
                                    onClick={() => void classify(e.name, 'CONFIRMED_MAIN')}
                                >
                                    주재료
                                </button>
                                <button
                                    type="button"
                                    className={actionClass(e.status === 'SKIPPED')}
                                    onClick={() => void classify(e.name, 'SKIPPED')}
                                >
                                    보류
                                </button>
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </section>
    );
}
