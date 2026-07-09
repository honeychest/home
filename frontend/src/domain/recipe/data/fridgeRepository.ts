// [AGENT] recipe(기까) 냉장고 저장소 — 인터페이스 + localStorage 구현체
// 규칙(CONTEXT.md): 화면은 저장소를 직접 부르지 않고 이 인터페이스만 사용.
// 4차 백엔드 연결 = createLocalStorageFridgeRepository 를 API 구현체로 교체하면 끝.
// 모든 메서드가 Promise 인 이유: API 교체 시 화면 코드가 바뀌지 않아야 하므로.
import type { FridgeItem, IngredientStat } from './fridgeTypes';
import { SEED_INGREDIENTS } from './seedIngredients';

export interface FridgeRepository {
    /** 전체 목록 (정렬은 화면 책임) */
    list(): Promise<FridgeItem[]>;
    /** 추가. 같은 이름이 이미 있으면 등록일만 오늘로 갱신 (리필 대응 — 확정 결정) */
    add(name: string): Promise<FridgeItem>;
    /** 삭제 (확인 없이 즉시 — 확정 결정) */
    remove(id: string): Promise<void>;
    /** 임박 수동 토글 */
    setExpiring(id: string, expiring: boolean): Promise<void>;
    /** 수정: 이름 교정 + 등록일 변경 (뒤늦은 등록 대응) */
    update(id: string, changes: { name?: string; addedDate?: string }): Promise<void>;
    /** 자주 사는 재료 상위 N개 — 추가 횟수 순, 시드와 병합, 숨김 제외 */
    frequentIngredients(limit: number): Promise<string[]>;
    /** 자주 사는 재료 목록에서 제거 (편집 모드). 냉장고 안의 재료에는 영향 없음.
        시드도 다시 올라오지 않도록 숨김 처리. 이후 직접 입력으로 다시 추가하면 숨김 해제 */
    removeFrequentIngredient(name: string): Promise<void>;
}

const ITEMS_KEY = 'gikka.fridge.items';
const STATS_KEY = 'gikka.fridge.ingredientStats';
const HIDDEN_KEY = 'gikka.fridge.hiddenIngredients';

/** http LAN 접속(비보안 컨텍스트)에서는 crypto.randomUUID가 없어 폴백 사용 */
function generateId(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID();
    }
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function todayString(): string {
    const now = new Date();
    const mm = String(now.getMonth() + 1).padStart(2, '0');
    const dd = String(now.getDate()).padStart(2, '0');
    return `${now.getFullYear()}-${mm}-${dd}`;
}

function readJson<T>(key: string, fallback: T): T {
    try {
        const raw = localStorage.getItem(key);
        return raw ? (JSON.parse(raw) as T) : fallback;
    } catch {
        return fallback;
    }
}

function writeJson(key: string, value: unknown): void {
    localStorage.setItem(key, JSON.stringify(value));
}

export function createLocalStorageFridgeRepository(): FridgeRepository {
    return {
        async list() {
            return readJson<FridgeItem[]>(ITEMS_KEY, []);
        },

        async add(name) {
            const trimmed = name.trim();
            const items = readJson<FridgeItem[]>(ITEMS_KEY, []);

            const stats = readJson<IngredientStat[]>(STATS_KEY, []);
            const stat = stats.find((s) => s.name === trimmed);
            if (stat) stat.addCount += 1;
            else stats.push({ name: trimmed, addCount: 1 });
            writeJson(STATS_KEY, stats);

            // 직접 다시 추가한 재료는 숨김 해제 (다시 관심이 생긴 것으로 봄)
            const hidden = readJson<string[]>(HIDDEN_KEY, []);
            if (hidden.includes(trimmed)) {
                writeJson(HIDDEN_KEY, hidden.filter((n) => n !== trimmed));
            }

            const existing = items.find((item) => item.name === trimmed);
            if (existing) {
                existing.addedDate = todayString();
                writeJson(ITEMS_KEY, items);
                return existing;
            }
            const created: FridgeItem = {
                id: generateId(),
                name: trimmed,
                addedDate: todayString(),
                expiring: false,
            };
            items.push(created);
            writeJson(ITEMS_KEY, items);
            return created;
        },

        async remove(id) {
            const items = readJson<FridgeItem[]>(ITEMS_KEY, []);
            writeJson(ITEMS_KEY, items.filter((item) => item.id !== id));
        },

        async setExpiring(id, expiring) {
            const items = readJson<FridgeItem[]>(ITEMS_KEY, []);
            const target = items.find((item) => item.id === id);
            if (target) {
                target.expiring = expiring;
                writeJson(ITEMS_KEY, items);
            }
        },

        async update(id, changes) {
            const items = readJson<FridgeItem[]>(ITEMS_KEY, []);
            const target = items.find((item) => item.id === id);
            if (target) {
                if (changes.name !== undefined) target.name = changes.name.trim();
                if (changes.addedDate !== undefined) target.addedDate = changes.addedDate;
                writeJson(ITEMS_KEY, items);
            }
        },

        async frequentIngredients(limit) {
            const stats = readJson<IngredientStat[]>(STATS_KEY, []);
            const hidden = new Set(readJson<string[]>(HIDDEN_KEY, []));
            const counted = new Map(stats.map((s) => [s.name, s.addCount]));
            // 시드는 횟수 0으로 병합 — 실사용이 쌓이면 자연히 시드를 밀어냄
            const names = [...new Set([...stats.map((s) => s.name), ...SEED_INGREDIENTS])]
                .filter((name) => !hidden.has(name));
            names.sort((a, b) => (counted.get(b) ?? 0) - (counted.get(a) ?? 0));
            return names.slice(0, limit);
        },

        async removeFrequentIngredient(name) {
            const stats = readJson<IngredientStat[]>(STATS_KEY, []);
            writeJson(STATS_KEY, stats.filter((s) => s.name !== name));
            const hidden = readJson<string[]>(HIDDEN_KEY, []);
            if (!hidden.includes(name)) {
                hidden.push(name);
                writeJson(HIDDEN_KEY, hidden);
            }
        },
    };
}

export const fridgeRepository: FridgeRepository = createLocalStorageFridgeRepository();
