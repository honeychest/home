// [AGENT] recipe(기까) 냉장고 화면 — 1차 핵심 (CONTEXT.md "냉장고" 절 스펙)
// 선반 3칸(임박/오래됨/신선, 스냅 가로 스크롤) / 스티커 탭 → 시트(임박·수정·삭제)
// / 자석 스티커 토글 추가 / 자유 입력 / 재등록 시 날짜 갱신
// 저장소는 fridgeRepository 인터페이스만, 선반·날짜 판정은 fridgeShelves 순수 모듈만 사용.
// 조작(추가·삭제·수정)은 전부 runMutation seam 을 거친다 — 실패 문구·중복 탭 방지·재동기화 한 곳.
import { useCallback, useEffect, useRef, useState } from 'react';
import { X } from 'lucide-react';
import type { FridgeItem } from '../data/fridgeTypes';
import { fridgeRepository } from '../data/fridgeRepository';
import { OLD_THRESHOLD_DAYS, classifyShelves, daysSince, formatDate, shiftDate } from '../data/fridgeShelves';
import RcpChip from '../ui/RcpChip';
import RcpButton from '../ui/RcpButton';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpShelf from '../ui/RcpShelf';

const FREQUENT_LIMIT = 12;

// 문구/데이터 분리 규칙(PLAYBOOK 품질 기본선 7): 개수가 바뀌어도 문구가 따라오도록 템플릿 한 곳에
const frequentSectionLabel = (limit: number) => `자주 사는 재료 (상위 ${limit}개 표시)`;
const oldShelfLabel = (days: number) => `오래됨 (${days}일 지남)`;
// 에러 계약(CONTEXT.md): 사용자 문구는 프론트 소유 — 상태 코드 외 백엔드 메시지에 의존하지 않는다
const MUTATION_ERROR_TEXT = '저장하지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const LOAD_ERROR_TEXT = '목록을 불러오지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';

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
    const [actionError, setActionError] = useState<string | null>(null);
    const busyRef = useRef(false); // 조작 중 재진입(연타) 방지 — 렌더와 무관하므로 ref

    const reloadItems = useCallback(async () => {
        setItems(await fridgeRepository.list());
    }, []);

    // 자주 사는 재료는 화면 진입 시 한 번만 정렬 — 조작 중 버튼이 자리를 옮기지 않도록
    // (순위 반영은 다음 화면 방문 때. 2026-07-09 검수 확정)
    useEffect(() => {
        reloadItems().catch(() => setActionError(LOAD_ERROR_TEXT));
        fridgeRepository.frequentIngredients(FREQUENT_LIMIT)
            .then(setFrequentNames)
            .catch(() => setActionError(LOAD_ERROR_TEXT));
    }, [reloadItems]);

    /** 조작 실행 seam — 실패 문구·연타 방지·서버 재동기화를 이 한 곳에.
        세션 만료(401)는 http.ts 공용 seam 이 로그인 화면 전환을 담당하므로 여기서는 구분하지 않는다 */
    const runMutation = useCallback(async (op: () => Promise<void>) => {
        if (busyRef.current) return;
        busyRef.current = true;
        setActionError(null);
        try {
            await op();
        } catch {
            setActionError(MUTATION_ERROR_TEXT);
            await reloadItems().catch(() => undefined); // 화면을 서버 상태와 재동기화 (실패해도 문구는 이미 표시)
        } finally {
            busyRef.current = false;
        }
    }, [reloadItems]);

    const handleAdd = (rawName: string) => runMutation(async () => {
        const name = rawName.trim();
        if (!name) return;
        await fridgeRepository.add(name);
        setJustAdded((prev) => (prev.includes(name) ? prev : [...prev, name]));
        setFreeInput(''); // 성공했을 때만 비움 (실패 시 입력 유지 — 재시도 배려)
        await reloadItems();
    });

    const handleChipToggle = (name: string, isOn: boolean) => runMutation(async () => {
        if (isOn) {
            const target = items.find((item) => item.name === name);
            if (target) await fridgeRepository.remove(target.id);
            setJustAdded((prev) => prev.filter((n) => n !== name));
        } else {
            await fridgeRepository.add(name);
            setJustAdded((prev) => (prev.includes(name) ? prev : [...prev, name]));
        }
        await reloadItems();
    });

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

    const handleRemove = (id: string) => runMutation(async () => {
        await fridgeRepository.remove(id); // 확인 없이 즉시 삭제 (확정 결정)
        setSelected(null);
        await reloadItems();
    });

    const handleExpiringToggle = (item: FridgeItem) => runMutation(async () => {
        await fridgeRepository.setExpiring(item.id, !item.expiring);
        setSelected(null);
        await reloadItems();
    });

    const handleFrequentRemove = (name: string) => runMutation(async () => {
        await fridgeRepository.removeFrequentIngredient(name);
        // 지운 것만 빼고 나머지 순서 유지 (재정렬하지 않음)
        setFrequentNames((prev) => prev.filter((n) => n !== name));
    });

    const openEdit = (item: FridgeItem) => {
        setEditing(item);
        setEditName(item.name);
    };

    const handleEditSave = () => runMutation(async () => {
        if (!editing || !editName.trim()) return;
        await fridgeRepository.update(editing.id, { name: editName });
        setEditing(null);
        await reloadItems();
    });

    /** 날짜 즉시 저장 (2026-07-09 확정: 달력·저장 버튼 없이 스테퍼로 1탭 완결).
        시트를 열어둔 채 하루씩 조정한다 */
    const handleDateSet = (item: FridgeItem, addedDate: string) => runMutation(async () => {
        await fridgeRepository.update(item.id, { addedDate });
        setSelected({ ...item, addedDate });
        await reloadItems();
    });

    const ownedNames = new Set(items.map((item) => item.name));
    const shelves = classifyShelves(items);
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
    // 조작 실패 안내 — 화면 본문과 열려 있는 시트 양쪽에 같은 줄을 보여준다 (시트가 화면을 덮으므로)
    const errorLine = actionError
        ? <p className="rcp-inline-error" role="alert">{actionError}</p>
        : null;

    return (
        <main className="rcp-screen" id="rcp-fridge-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">냉장고</h1>
                <p className="rcp-screen-subtitle">오래된 재료부터 보여줘요</p>
            </header>
            {errorLine}

            <section id="rcp-fridge-shelves" aria-label="냉장고 재료 선반">
                {items.length === 0 && (
                    <p className="rcp-empty">아직 비어 있어요. 아래 [+ 재료 추가]로 채워보세요.</p>
                )}
                {shelves.expiring.length > 0 && (
                    <RcpShelf label="임박 — 먼저 드세요">
                        {shelves.expiring.map((i) => sticker(i, 'rcp-sticker-expiring'))}
                    </RcpShelf>
                )}
                {shelves.old.length > 0 && (
                    <RcpShelf label={oldShelfLabel(OLD_THRESHOLD_DAYS)}>
                        {shelves.old.map((i) => sticker(i, 'rcp-sticker-old'))}
                    </RcpShelf>
                )}
                {shelves.fresh.length > 0 && (
                    <RcpShelf label="신선" scrollToStartSignal={freshScrollSignal}>
                        {shelves.fresh.map((i) => sticker(i, ''))}
                    </RcpShelf>
                )}
            </section>

            <RcpButton
                className="rcp-btn-full"
                id="rcp-fridge-add-button"
                onClick={openAddSheet}
            >
                + 재료 추가
            </RcpButton>

            <RcpBottomSheet open={addOpen} title="재료 추가" onClose={closeAddSheet}>
                {errorLine}
                <div className="rcp-just-added" id="rcp-just-added">
                    {justAdded.length === 0
                        ? <span className="rcp-just-added-hint">방금 넣은 재료가 여기 표시돼요</span>
                        : justAdded.map((n) => (
                            <span key={n} className="rcp-just-added-tag">{n} ✓</span>
                        ))}
                </div>
                <div className="rcp-sheet-heading-row">
                    <h3 className="rcp-section-label">
                        {chipEditMode ? '지울 재료를 누르세요 (냉장고 안에는 영향 없음)' : frequentSectionLabel(FREQUENT_LIMIT)}
                    </h3>
                    <button
                        type="button"
                        className="rcp-icon-btn rcp-icon-btn-label"
                        id="rcp-frequent-edit-toggle"
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
                    className="rcp-input-row"
                    onSubmit={(e) => {
                        e.preventDefault();
                        void handleAdd(freeInput);
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
                        {errorLine}
                        <p className="rcp-sheet-meta">
                            등록 {formatDate(selected.addedDate)} · {daysSince(selected.addedDate)}일 지남
                            {selected.expiring && ' · 임박 표시됨'}
                        </p>

                        <div id="rcp-date-stepper" className="rcp-stepper">
                            <RcpButton
                                variant="ghost"
                                aria-label="하루 이전으로"
                                onClick={() => void handleDateSet(selected, shiftDate(selected.addedDate, -1))}
                            >
                                −1일
                            </RcpButton>
                            <span className="rcp-stepper-value">
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
                {errorLine}
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
