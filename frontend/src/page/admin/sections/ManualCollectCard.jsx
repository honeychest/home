import styles from '../AdminPage.module.css';
import { SYMBOLS, MARKETS } from '../constants';
import { fmtNum, fmtTime, statusClass } from '../utils';

export default function ManualCollectCard({ manualCollect }) {
    const {
        cType, setCType, cSymbol, setCSymbol, cMarket, setCMarket,
        cFrom, setCFrom, cTo, setCTo,
        collectLoading, collectError, jobs,
        handleCollect, isIdBased, isKline, noMarket,
    } = manualCollect;

    return (
        <div className={styles.card}>
            <div className={styles.titleRow}>
                <div className={styles.title}>수동 수집</div>
                <div className={styles.subtitle}>Job 폴링 3초</div>
            </div>

            <div className={styles.inputRow}>
                <div className={styles.field}>
                    <div className={styles.label}>Type</div>
                    <select className={styles.select} value={cType} onChange={e => setCType(e.target.value)}>
                        <option value="KLINE_1M">Kline 1분봉 (임시)</option>
                        <option value="AGG_1M">1분봉</option>
                        <option value="AGG_5M">5분봉</option>
                        <option value="OI">Open Interest</option>
                    </select>
                </div>
                <div className={styles.field}>
                    <div className={styles.label}>Symbol</div>
                    <select className={styles.select} value={cSymbol} onChange={e => setCSymbol(e.target.value)}>
                        {SYMBOLS.map(s => <option key={s} value={s}>{s}</option>)}
                    </select>
                </div>
                <div className={styles.field}>
                    <div className={styles.label}>Market</div>
                    <select
                        className={`${styles.select} ${noMarket ? styles.btnDisabled : ''}`}
                        value={noMarket ? 'FUTURES' : cMarket}
                        disabled={noMarket}
                        onChange={e => setCMarket(e.target.value)}
                    >
                        {MARKETS.map(m => <option key={m} value={m}>{m}</option>)}
                    </select>
                </div>
            </div>

            <div className={`${styles.inputRow} ${styles.rowGap}`}>
                <div className={styles.field}>
                    <div className={styles.label}>{isIdBased ? 'From ID' : isKline ? 'From (최대 48시간)' : 'From'}</div>
                    {isIdBased
                        ? <input className={styles.input} type="number" value={cFrom} onChange={e => setCFrom(e.target.value)} placeholder="시작 ID" />
                        : <input className={styles.input} type="datetime-local" value={cFrom} onChange={e => setCFrom(e.target.value)} />
                    }
                </div>
                <div className={styles.field}>
                    <div className={styles.label}>{isIdBased ? 'To ID' : 'To'}</div>
                    {isIdBased
                        ? <input className={styles.input} type="number" value={cTo} onChange={e => setCTo(e.target.value)} placeholder="종료 ID (생략 시 현재)" />
                        : <input className={styles.input} type="datetime-local" value={cTo} onChange={e => setCTo(e.target.value)} />
                    }
                </div>
                <div className={styles.field}>
                    <div className={styles.label}>Action</div>
                    <button
                        type="button"
                        className={`${styles.btn} ${styles.btnActive} ${styles.btnBlock}`}
                        onClick={handleCollect}
                        disabled={collectLoading || !cFrom || (isKline && !cTo)}
                    >
                        {collectLoading ? '요청 중...' : '수집 시작'}
                    </button>
                </div>
            </div>

            {collectError && <div className={`${styles.desc} ${styles.error}`}>{collectError}</div>}

            {jobs.length > 0 && (
                <div className={styles.tableWrap}>
                    <table className={styles.table}>
                        <thead>
                            <tr>
                                {['jobId', '타입', '심볼', '마켓', '상태', '삽입', '시작', '완료'].map(h => (
                                    <th key={h} className={styles.th}>{h}</th>
                                ))}
                            </tr>
                        </thead>
                        <tbody>
                            {jobs.map((j, i) => (
                                <tr key={j.jobId} className={i % 2 === 1 ? styles.trOdd : ''}>
                                    <td className={`${styles.td} ${styles.mono}`}>{j.jobId}</td>
                                    <td className={`${styles.td} ${styles.mono}`}>{j.type}</td>
                                    <td className={`${styles.td} ${styles.mono}`}>{j.symbol}</td>
                                    <td className={`${styles.td} ${styles.mono}`}>{j.marketType}</td>
                                    <td className={`${styles.td} ${styles[statusClass(j.status)]}`}>{j.status}</td>
                                    <td className={`${styles.td} ${styles.mono}`}>{j.inserted > 0 ? fmtNum(j.inserted) : '—'}</td>
                                    <td className={`${styles.td} ${styles.mono}`}>{fmtTime(j.startedAt)}</td>
                                    <td className={`${styles.td} ${styles.mono}`}>{j.finishedAt ? fmtTime(j.finishedAt) : j.status === 'RUNNING' ? '진행 중' : '—'}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
}
