// [AGENT] recipe(기까) 냉장고 화면 — 1차 핵심 (CONTEXT.md "냉장고" 절 스펙)
// 오래된 순 목록 / 자석 스티커 토글 추가 / 자유 입력 / 즉시 삭제 / 임박 토글 / 이름·날짜 수정
// 저장소는 fridgeRepository 인터페이스만 사용 (localStorage 직접 접근 금지)
import { useCallback, useEffect, useState } from 'react';
import { Trash2, Pencil, AlarmClock, X } from 'lucide-react';
import type { FridgeItem } from '../data/fridgeTypes';
import { fridgeRepository } from '../data/fridgeRepository';
import RcpChip from '../ui/RcpChip';
import RcpBadge from '../ui/RcpBadge';
import RcpButton from '../ui/RcpButton';
import RcpBottomSheet from '../ui/RcpBottomSheet';

const FREQUENT_LIMIT = 12;

// 문구/데이터 분리 규칙(PLAYBOOK 품질 기본선 7): 개수가 바뀌어도 문구가 따라오도록 템플릿 한 곳에
const frequentSectionLabel = (limit: number) => `자주 사는 재료 (상위 ${limit}개 표시)`;

function formatDate(dateString: string): string {
    const [, month, day] = dateString.split('-');
    return `${Number(month)}월 ${Number(day)}일`;
}

export default function FridgePage() {
    const [items, setItems] = useState<FridgeItem[]>([]);
    const [frequentNames, setFrequentNames] = useState<string[]>([]);
    const [freeInput, setFreeInput] = useState('');
    const [editing, setEditing] = useState<FridgeItem | null>(null);
    const [chipEditMode, setChipEditMode] = useState(false);
    const [editName, setEditName] = useState('');
    const [editDate, setEditDate] = useState('');

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
        if (!name.trim()) return;
        await fridgeRepository.add(name);
        await reloadItems();
    };

    const handleChipToggle = async (name: string, isOn: boolean) => {
        if (isOn) {
            const target = items.find((item) => item.name === name);
            if (target) await fridgeRepository.remove(target.id);
        } else {
            await fridgeRepository.add(name);
        }
        await reloadItems();
    };

    const handleRemove = async (id: string) => {
        await fridgeRepository.remove(id); // 확인 없이 즉시 삭제 (확정 결정)
        await reloadItems();
    };

    const handleExpiringToggle = async (item: FridgeItem) => {
        await fridgeRepository.setExpiring(item.id, !item.expiring);
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
        setEditDate(item.addedDate);
    };

    const handleEditSave = async () => {
        if (!editing || !editName.trim()) return;
        await fridgeRepository.update(editing.id, { name: editName, addedDate: editDate });
        setEditing(null);
        await reloadItems();
    };

    const ownedNames = new Set(items.map((item) => item.name));

    return (
        <main className="rcp-screen" id="rcp-fridge-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">냉장고</h1>
                <p className="rcp-screen-subtitle">오래된 재료부터 보여줘요</p>
            </header>

            <section id="rcp-fridge-list" aria-label="냉장고 재료 목록">
                {items.length === 0 && (
                    <p className="rcp-empty">아직 비어 있어요. 아래 스티커를 눌러 채워보세요.</p>
                )}
                {items.map((item) => (
                    <div className="rcp-list-row" key={item.id}>
                        <span className="rcp-list-row-name">{item.name}</span>
                        {item.expiring && <RcpBadge variant="expiring">임박</RcpBadge>}
                        <span className="rcp-list-row-meta">{formatDate(item.addedDate)}</span>
                        <span className="rcp-list-row-actions">
                            <button
                                type="button"
                                className={`rcp-icon-btn ${item.expiring ? 'rcp-icon-btn-accent' : ''}`.trim()}
                                aria-label={`${item.name} 임박 표시 토글`}
                                onClick={() => void handleExpiringToggle(item)}
                            >
                                <AlarmClock size={17} />
                            </button>
                            <button
                                type="button"
                                className="rcp-icon-btn"
                                aria-label={`${item.name} 수정`}
                                onClick={() => openEdit(item)}
                            >
                                <Pencil size={17} />
                            </button>
                            <button
                                type="button"
                                className="rcp-icon-btn rcp-icon-btn-danger"
                                aria-label={`${item.name} 삭제`}
                                onClick={() => void handleRemove(item.id)}
                            >
                                <Trash2 size={17} />
                            </button>
                        </span>
                    </div>
                ))}
            </section>

            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <h2 className="rcp-section-label">
                    {chipEditMode ? '지울 재료를 누르세요 (냉장고 안에는 영향 없음)' : frequentSectionLabel(FREQUENT_LIMIT)}
                </h2>
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

            <h2 className="rcp-section-label">직접 입력</h2>
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
                    placeholder="재료 이름 (예: 파프리카)"
                    value={freeInput}
                    onChange={(e) => setFreeInput(e.target.value)}
                />
                <RcpButton type="submit" disabled={!freeInput.trim()}>추가</RcpButton>
            </form>

            <RcpBottomSheet open={editing !== null} title="재료 수정" onClose={() => setEditing(null)}>
                <label className="rcp-section-label" htmlFor="rcp-edit-name">이름</label>
                <input
                    className="rcp-input"
                    id="rcp-edit-name"
                    value={editName}
                    onChange={(e) => setEditName(e.target.value)}
                />
                <label className="rcp-section-label" htmlFor="rcp-edit-date">등록일 (뒤늦게 등록했다면 실제 산 날로)</label>
                <input
                    className="rcp-input"
                    id="rcp-edit-date"
                    type="date"
                    value={editDate}
                    onChange={(e) => setEditDate(e.target.value)}
                />
                <RcpButton onClick={() => void handleEditSave()} disabled={!editName.trim()}>저장</RcpButton>
                <RcpButton variant="ghost" onClick={() => setEditing(null)}>취소</RcpButton>
            </RcpBottomSheet>
        </main>
    );
}
