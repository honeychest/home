// [AGENT] recipe(기까) 냉장고 화면 — 1차 핵심 (CONTEXT.md "냉장고" 절 스펙)
// 선반 3칸(임박/오래됨/신선, 스냅 가로 스크롤) / 스티커 탭 → 시트(임박·수정·삭제)
// / 자석 스티커 토글 추가 / 자유 입력 / 재등록 시 날짜 갱신
// 저장소는 fridgeRepository 인터페이스만 사용 (localStorage 직접 접근 금지)
import { useCallback, useEffect, useState } from 'react';
import { X } from 'lucide-react';
import type { FridgeItem } from '../data/fridgeTypes';
import { fridgeRepository } from '../data/fridgeRepository';
import RcpChip from '../ui/RcpChip';
import RcpButton from '../ui/RcpButton';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpShelf from '../ui/RcpShelf';

const FREQUENT_LIMIT = 12;
const OLD_THRESHOLD_DAYS = 14; // "오래됨" 선반 기준 (2026-07-09 확정)

// 문구/데이터 분리 규칙(PLAYBOOK 품질 기본선 7): 개수가 바뀌어도 문구가 따라오도록 템플릿 한 곳에
const frequentSectionLabel = (limit: number) => `자주 사는 재료 (상위 ${limit}개 표시)`;
const oldShelfLabel = (days: number) => `오래됨 (${days}일 지남)`;

function formatDate(dateString: string): string {
    const [, month, day] = dateString.split('-');
    return `${Number(month)}월 ${Number(day)}일`;
}

function daysSince(dateString: string): number {
    const [y, m, d] = dateString.split('-').map(Number);
    const then = new Date(y, m - 1, d).getTime();
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    return Math.max(0, Math.round((today - then) / 86400000));
}

function toDateString(date: Date): string {
    const mm = String(date.getMonth() + 1).padStart(2, '0');
    const dd = String(date.getDate()).padStart(2, '0');
    return `${date.getFullYear()}-${mm}-${dd}`;
}

/** 기존 날짜에서 delta일 이동 (미래 금지 — 오늘까지만) */
function shiftDate(dateString: string, delta: number): string {
    const [y, m, d] = dateString.split('-').map(Number);
    const date = new Date(y, m - 1, d);
    date.setDate(date.getDate() + delta);
    const today = new Date();
    if (date.getTime() > today.getTime()) return toDateString(today);
    return toDateString(date);
}

export default function FridgePage() {
    const [items, setItems] = useState<FridgeItem[]>([]);
    const [frequentNames, setFrequentNames] = useState<string[]>([]);
    const [freeInput, setFreeInput] = useState('');
    const [selected, setSelected] = useState<FridgeItem | null>(null); // 스티커 탭 → 액션 시트
    const [addOpen, setAddOpen] = useState(false); // [+ 재료 추가] → 추가 시트
    const [justAdded, setJustAdded] = useState<string[]>([]); // 이번 시트에서 넣은 재료 (확인줄)
    const [arriveNames, setArriveNames] = useState<Set<string>>(new Set()); // 시트 닫힌 뒤 등장 연출할 스티커
    const [freshScrollSignal, setFreshScrollSignal] = useState(0); // 신선 선반 왼쪽 되감기 신호
    const [editing, setEditing] = useState<FridgeItem | null>(null);
    const [chipEditMode, setChipEditMode] = useState(false);
    const [editName, setEditName] = useState('');

    const reloadItems = useCallback(async () => {
        const list = await fridgeRepository.list();
        // 오래된 순 (확정 결정: 오래된 재료부터 소진 유도)
        list.sort((a, b) => a.addedDate.localeCompare(b.addedDate));
        setItems(list);
    }, []);

    // 자주 사는 재료는 화면 진입 시 한 번만 정렬 — 조작 중 버튼이 자리를 옮기지 않도록
    // (순위 반영은 다음 화면 방문 때. 2026-07-09 검수 확정)
    useEffect(() => {
        void reloadItems();
        void fridgeRepository.frequentIngredients(FREQUENT_LIMIT).then(setFrequentNames);
    }, [reloadItems]);

    const handleAdd = async (name: string) => {
        const trimmed = name.trim();
        if (!trimmed) return;
        await fridgeRepository.add(trimmed);
        setJustAdded((prev) => (prev.includes(trimmed) ? prev : [...prev, trimmed]));
        await reloadItems();
    };

    const handleChipToggle = async (name: string, isOn: boolean) => {
        if (isOn) {
            const target = items.find((item) => item.name === name);
            if (target) await fridgeRepository.remove(target.id);
            setJustAdded((prev) => prev.filter((n) => n !== name));
        } else {
            await fridgeRepository.add(name);
            setJustAdded((prev) => (prev.includes(name) ? prev : [...prev, name]));
        }
        await reloadItems();
    };

    const openAddSheet = () => {
        setJustAdded([]);
        setAddOpen(true);
    };

    /** 시트를 닫으며, 이번에 넣은 스티커들을 "아래에서 올라와 쾅" 등장 연출 (1회) */
    const closeAddSheet = () => {
        setAddOpen(false);
        setChipEditMode(false);
        if (justAdded.length > 0) {
            setArriveNames(new Set(justAdded));
            setFreshScrollSignal((s) => s + 1); // 새 스티커가 보이도록 신선 선반을 맨 왼쪽으로
            window.setTimeout(() => setArriveNames(new Set()), 1100);
        }
    };

    const handleRemove = async (id: string) => {
        await fridgeRepository.remove(id); // 확인 없이 즉시 삭제 (확정 결정)
        setSelected(null);
        await reloadItems();
    };

    const handleExpiringToggle = async (item: FridgeItem) => {
        await fridgeRepository.setExpiring(item.id, !item.expiring);
        setSelected(null);
        await reloadItems();
    };

    const handleFrequentRemove = async (name: string) => {
        await fridgeRepository.removeFrequentIngredient(name);
        // 지운 것만 빼고 나머지 순서 유지 (재정렬하지 않음)
        setFrequentNames((prev) => prev.filter((n) => n !== name));
    };

    const openEdit = (item: FridgeItem) => {
        setEditing(item);
        setEditName(item.name);
    };

    const handleEditSave = async () => {
        if (!editing || !editName.trim()) return;
        await fridgeRepository.update(editing.id, { name: editName });
        setEditing(null);
        await reloadItems();
    };

    /** 날짜 즉시 저장 (2026-07-09 확정: 달력·저장 버튼 없이 스테퍼로 1탭 완결).
        시트를 열어둔 채 하루씩 조정한다 */
    const handleDateSet = async (item: FridgeItem, addedDate: string) => {
        await fridgeRepository.update(item.id, { addedDate });
        setSelected({ ...item, addedDate });
        await reloadItems();
    };

    const ownedNames = new Set(items.map((item) => item.name));

    return (
        <main className="rcp-screen" id="rcp-fridge-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">냉장고</h1>
                <p className="rcp-screen-subtitle">오래된 재료부터 보여줘요</p>
            </header>

            <section id="rcp-fridge-shelves" aria-label="냉장고 재료 선반">
                {items.length === 0 && (
                    <p className="rcp-empty">아직 비어 있어요. 아래 [+ 재료 추가]로 채워보세요.</p>
                )}
                {(() => {
                    // 임박(수동 토글)은 임박 선반에만. 나머지를 14일 기준으로 오래됨/신선 분리.
                    const expiring = items.filter((i) => i.expiring);
                    const rest = items.filter((i) => !i.expiring);
                    const old = rest.filter((i) => daysSince(i.addedDate) >= OLD_THRESHOLD_DAYS);
                    // 신선 선반만 최신순(새것이 왼쪽) — 방금 넣은 게 바로 보이게 (2026-07-09 확정).
                    // "오래된 것부터" 신호는 14일 경과 시 오래됨 선반 승격이 담당.
                    const fresh = rest
                        .filter((i) => daysSince(i.addedDate) < OLD_THRESHOLD_DAYS)
                        .reverse();
                    const sticker = (item: FridgeItem, extraClass: string) => (
                        <button
                            type="button"
                            key={item.id}
                            className={`rcp-sticker ${extraClass} ${arriveNames.has(item.name) ? 'rcp-sticker-arrive' : ''}`.trim()}
                            onClick={() => setSelected(item)}
                        >
                            {item.name}
                        </button>
                    );
                    return (
                        <>
                            {expiring.length > 0 && (
                                <RcpShelf label="임박 — 먼저 드세요">
                                    {expiring.map((i) => sticker(i, 'rcp-sticker-expiring'))}
                                </RcpShelf>
                            )}
                            {old.length > 0 && (
                                <RcpShelf label={oldShelfLabel(OLD_THRESHOLD_DAYS)}>
                                    {old.map((i) => sticker(i, 'rcp-sticker-old'))}
                                </RcpShelf>
                            )}
                            {fresh.length > 0 && (
                                <RcpShelf label="신선" scrollToStartSignal={freshScrollSignal}>
                                    {fresh.map((i) => sticker(i, ''))}
                                </RcpShelf>
                            )}
                        </>
                    );
                })()}
            </section>

            <RcpButton
                className="rcp-btn-full"
                id="rcp-fridge-add-button"
                onClick={openAddSheet}
            >
                + 재료 추가
            </RcpButton>

            <RcpBottomSheet open={addOpen} title="재료 추가" onClose={closeAddSheet}>
                <div className="rcp-just-added" id="rcp-just-added">
                    {justAdded.length === 0
                        ? <span className="rcp-just-added-hint">방금 넣은 재료가 여기 표시돼요</span>
                        : justAdded.map((n) => (
                            <span key={n} className="rcp-just-added-tag">{n} ✓</span>
                        ))}
                </div>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <h3 className="rcp-section-label" style={{ margin: 0 }}>
                        {chipEditMode ? '지울 재료를 누르세요 (냉장고 안에는 영향 없음)' : frequentSectionLabel(FREQUENT_LIMIT)}
                    </h3>
                    <button
                        type="button"
                        className="rcp-icon-btn"
                        id="rcp-frequent-edit-toggle"
                        style={{ width: 'auto', padding: '0 var(--rcp-space-2)', fontSize: 'var(--rcp-fs-xs)', fontWeight: 'var(--rcp-fw-bold)' as never }}
                        onClick={() => setChipEditMode(!chipEditMode)}
                    >
                        {chipEditMode ? '완료' : '편집'}
                    </button>
                </div>
                <div className="rcp-chip-group" id="rcp-frequent-chips">
                    {frequentNames.map((name) => {
                        const isOn = ownedNames.has(name);
                        if (chipEditMode) {
                            return (
                                <RcpChip
                                    key={name}
                                    on={false}
                                    onToggle={() => void handleFrequentRemove(name)}
                                >
                                    {name} <X size={13} />
                                </RcpChip>
                            );
                        }
                        return (
                            <RcpChip key={name} on={isOn} onToggle={() => void handleChipToggle(name, isOn)}>
                                {name}
                            </RcpChip>
                        );
                    })}
                </div>
                <form
                    id="rcp-fridge-add-form"
                    style={{ display: 'flex', gap: 'var(--rcp-space-2)' }}
                    onSubmit={(e) => {
                        e.preventDefault();
                        void handleAdd(freeInput).then(() => setFreeInput(''));
                    }}
                >
                    <input
                        className="rcp-input"
                        id="rcp-fridge-add-input"
                        placeholder="직접 입력 (예: 파프리카)"
                        value={freeInput}
                        onChange={(e) => setFreeInput(e.target.value)}
                    />
                    <RcpButton type="submit" disabled={!freeInput.trim()}>추가</RcpButton>
                </form>
            </RcpBottomSheet>

            <RcpBottomSheet
                open={selected !== null}
                title={selected?.name ?? ''}
                onClose={() => setSelected(null)}
            >
                {selected && (
                    <>
                        <p style={{ fontSize: 'var(--rcp-fs-sm)', color: 'var(--rcp-text-sub)' }}>
                            등록 {formatDate(selected.addedDate)} · {daysSince(selected.addedDate)}일 지남
                            {selected.expiring && ' · 임박 표시됨'}
                        </p>

                        <div
                            id="rcp-date-stepper"
                            style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 'var(--rcp-space-3)' }}
                        >
                            <RcpButton
                                variant="ghost"
                                aria-label="하루 이전으로"
                                onClick={() => void handleDateSet(selected, shiftDate(selected.addedDate, -1))}
                            >
                                −1일
                            </RcpButton>
                            <span style={{ fontWeight: 'var(--rcp-fw-heavy)' as never }}>
                                {formatDate(selected.addedDate)}
                            </span>
                            <RcpButton
                                variant="ghost"
                                aria-label="하루 이후로"
                                disabled={daysSince(selected.addedDate) === 0}
                                onClick={() => void handleDateSet(selected, shiftDate(selected.addedDate, 1))}
                            >
                                +1일
                            </RcpButton>
                        </div>

                        <RcpButton variant="ghost" onClick={() => void handleExpiringToggle(selected)}>
                            {selected.expiring ? '임박 표시 해제' : '임박 표시'}
                        </RcpButton>
                        <RcpButton
                            variant="ghost"
                            onClick={() => { openEdit(selected); setSelected(null); }}
                        >
                            이름 수정
                        </RcpButton>
                        <RcpButton variant="danger" onClick={() => void handleRemove(selected.id)}>
                            냉장고에서 빼기
                        </RcpButton>
                    </>
                )}
            </RcpBottomSheet>

            <RcpBottomSheet open={editing !== null} title="이름 수정" onClose={() => setEditing(null)}>
                <label className="rcp-section-label" htmlFor="rcp-edit-name">이름</label>
                <input
                    className="rcp-input"
                    id="rcp-edit-name"
                    value={editName}
                    onChange={(e) => setEditName(e.target.value)}
                />
                <RcpButton onClick={() => void handleEditSave()} disabled={!editName.trim()}>저장</RcpButton>
                <RcpButton variant="ghost" onClick={() => setEditing(null)}>취소</RcpButton>
            </RcpBottomSheet>
        </main>
    );
}
