import styles from '../AdminPage.module.css';
import { CHECKS } from '../constants';

const GAP_ROW_DISPLAY_LIMIT = 200;

export default function DataGapCard({ dataGap, collectLoading, collectError }) {
    const {
        activeKey, rows, loading, error, selectedRows,
        activeCheck, visibleColumns, showCheckbox, allChecked,
        isRowSelectable,
        toggleAll, toggleRow,
        handleCheck, handleBulkCollect,
    } = dataGap;

    const displayLimit = activeCheck?.type === 'KLINE_1M'
        ? 'KLINE_1M 행 개수 제한 없음, 범위는 최대 48시간'
        : `기존 갭 최대 ${GAP_ROW_DISPLAY_LIMIT}건 표시`;

    return (
        <div className={styles.card}>
            <div className={styles.titleRow}>
                <div className={styles.title}>갭 조회</div>
                <div className={styles.subtitle}>{displayLimit}</div>
            </div>
            <div className={styles.btnRow}>
                {CHECKS.filter(c => !c.danger).map(({ type, label, desc, days }) => {
                    const key = `${type}_${days ?? 'all'}`;
                    const active = activeKey === key;
                    return (
                        <button
                            key={key}
                            type="button"
                            className={`${styles.btn} ${active ? styles.btnActive : ''}`}
                            onClick={() => handleCheck(type, days)}
                            disabled={loading}
                            title={desc}
                        >
                            {label}
                        </button>
                    );
                })}
            </div>
            {activeKey && <p className={styles.desc}>{activeCheck?.desc}{' · '}{displayLimit}</p>}

            {loading && <div className={styles.muted}>조회 중...</div>}
            {!loading && error && <div className={`${styles.muted} ${styles.error}`}>{error}</div>}
            {!loading && !error && rows === null && <div className={styles.muted}>버튼을 클릭하면 누락 구간을 조회합니다.</div>}
            {!loading && !error && rows !== null && rows.length === 0 && (
                <div className={`${styles.muted} ${styles.success}`}>✓ 누락 없음</div>
            )}

            {!loading && !error && rows !== null && rows.length > 0 && (
                <div className={styles.tableWrap}>
                    <table className={styles.table}>
                        <thead>
                            <tr>
                                {showCheckbox && (
                                    <th className={`${styles.th} ${styles.colCheckbox}`}>
                                        <input type="checkbox" checked={allChecked} onChange={toggleAll} />
                                    </th>
                                )}
                                {visibleColumns.map(col => <th key={col} className={styles.th}>{col}</th>)}
                            </tr>
                        </thead>
                        <tbody>
                            {rows.map((row, i) => (
                                <tr key={i} className={i % 2 === 1 ? styles.trOdd : ''}>
                                    {showCheckbox && isRowSelectable(row) ? (
                                        <td className={`${styles.td} ${styles.colCheckbox}`}>
                                            <input
                                                type="checkbox"
                                                checked={selectedRows.has(i)}
                                                onChange={() => toggleRow(i)}
                                            />
                                        </td>
                                    ) : showCheckbox ? <td className={`${styles.td} ${styles.colCheckbox}`} /> : null}
                                    {visibleColumns.map(col => (
                                        <td key={col} className={`${styles.td} ${styles.mono}`}>
                                            {row[col] != null ? String(row[col]) : '—'}
                                        </td>
                                    ))}
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            {showCheckbox && (
                <div className={styles.actions}>
                    <button
                        type="button"
                        className={`${styles.btn} ${selectedRows.size > 0 ? styles.btnActive : ''} ${(selectedRows.size === 0 || collectLoading) ? styles.btnDisabled : ''}`}
                        onClick={handleBulkCollect}
                        disabled={selectedRows.size === 0 || collectLoading}
                    >
                        {collectLoading ? '요청 중...' : `선택 수집 (${selectedRows.size}건)`}
                    </button>
                    {collectError && <div className={`${styles.muted} ${styles.error}`}>{collectError}</div>}
                </div>
            )}
        </div>
    );
}
