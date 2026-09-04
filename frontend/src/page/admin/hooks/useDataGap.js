import { useCallback, useState } from 'react';
import * as adminApi from '../api/adminApi';
import { CHECKS, ID_BASED } from '../constants';
import { mergeGapRanges } from '../utils';

// Data Gap 조회 + 선택 수집.
// 선택 수집 후 jobs 목록을 갱신해야 하므로 manualCollect 훅의 setJobs 를 받는다.
export default function useDataGap({ setJobs, defaultSymbol }) {
    const [activeKey, setActiveKey] = useState(null);
    const [rows, setRows] = useState(null);
    const [columns, setColumns] = useState([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [selectedRows, setSelectedRows] = useState(new Set());

    const activeCheck = CHECKS.find(c => `${c.type}_${c.days ?? 'all'}` === activeKey);
    const visibleColumns = columns.filter(c => !c.endsWith('_ms'));
    const isRowSelectable = row => !!(activeCheck &&
        (ID_BASED.has(activeCheck.type)
            ? row.gap_start_id != null && row.gap_end_id != null
            : row.gap_start_ms != null && row.gap_end_ms != null));
    const selectableRows = rows?.reduce((indices, row, index) => {
        if (isRowSelectable(row)) indices.push(index);
        return indices;
    }, []) ?? [];
    // 체크박스 표시 여부: start/end 범위가 있는 행이 하나라도 있어야 수집 가능
    const showCheckbox = selectableRows.length > 0;
    const allChecked = showCheckbox && selectedRows.size === selectableRows.length;

    const toggleAll = () =>
        setSelectedRows(allChecked ? new Set() : new Set(selectableRows));

    const toggleRow = (i) =>
        setSelectedRows(prev => {
            const next = new Set(prev);
            next.has(i) ? next.delete(i) : next.add(i);
            return next;
        });

    const handleCheck = async (type, days) => {
        const key = `${type}_${days ?? 'all'}`;
        setActiveKey(key);
        setRows(null);
        setSelectedRows(new Set());
        setError(null);
        setLoading(true);
        try {
            const params = days != null ? { type, days } : { type };
            const data = await adminApi.getDataGapCheck(params);
            setRows(data);
            setColumns(data.length > 0 ? Object.keys(data[0]) : []);
        } catch (e) {
            setError(e.response?.data?.error ?? '조회 실패');
        } finally {
            setLoading(false);
        }
    };

    const handleBulkCollect = async () => {
        if (selectedRows.size === 0 || !activeCheck) return;
        const selected = [...selectedRows].map(i => rows[i]).filter(isRowSelectable);
        const isIdBasedType = ID_BASED.has(activeCheck.type);
        const intervalMs = activeCheck.type === 'KLINE_5M' ? 300_000 : 60_000;

        // symbol+market_type 그룹핑
        const groups = {};
        for (const row of selected) {
            const sym    = row.symbol ?? defaultSymbol;
            const market = row.market_type ?? 'FUTURES';
            const key    = `${sym}__${market}`;
            if (!groups[key]) groups[key] = { symbol: sym, marketType: market, rows: [] };
            groups[key].rows.push(row);
        }

        setError(null);
        try {
            for (const g of Object.values(groups)) {
                const body = { type: activeCheck.type, symbol: g.symbol, marketType: g.marketType };
                if (isIdBasedType) {
                    body.fromId = Math.min(...g.rows.map(r => Number(r.gap_start_id)));
                    body.toId   = Math.max(...g.rows.map(r => Number(r.gap_end_id)));
                } else {
                    for (const range of mergeGapRanges(g.rows, intervalMs)) {
                        await adminApi.postBackfillCollect({
                            ...body,
                            fromMs: range.start,
                            toMs: range.end,
                        });
                    }
                    continue;
                }
                await adminApi.postBackfillCollect(body);
            }
            const jobs2 = await adminApi.getBackfillJobs();
            setJobs(jobs2);
            setSelectedRows(new Set());
            handleCheck(activeCheck.type, activeCheck.days);
        } catch (e) {
            setError(e.response?.data?.error ?? '수집 요청 실패');
        }
    };

    // outlier 진단/보정 시작 시 갭 조회 결과를 비우기 위해 노출.
    const resetGapView = useCallback(() => {
        setActiveKey(null);
        setRows(null);
        setColumns([]);
        setSelectedRows(new Set());
        setError(null);
    }, []);

    return {
        activeKey, rows, columns, loading, error, selectedRows,
        activeCheck, visibleColumns, showCheckbox, allChecked, isRowSelectable,
        toggleAll, toggleRow,
        handleCheck, handleBulkCollect,
        resetGapView,
    };
}
