// [AGENT] 알림 이력 테이블 (필터 + 페이징)
import { useEffect, useMemo, useState } from 'react';
import apiClient from '@/api/apiClient.js';
import styles from './AlertHistoryTable.module.css';

// 지표 enum 코드 → 한글 라벨. 정의는 docs/generated/domain-glossary.md "운영·모니터링 표시 용어".
const METRIC_LABEL = {
    CPU: 'CPU 사용률',
    RAM: 'RAM 사용률',
    DISK: 'DISK 사용률',
    REDIS_QUEUE: 'Redis 큐 적체',
    API_ERROR: 'API 에러율',
    FEED_BINANCE_TICKER: '바이낸스 시세 피드',
    FEED_BINANCE_AGG: '바이낸스 체결 피드',
    FEED_UPBIT: '업비트 시세 피드',
};

const fmt = (dt) => {
    if (!dt) return '-';
    const d = new Date(dt);
    // 서버(LocalDateTime) 값이 타임존 없이 내려올 수 있어도, 표기는 KST로 고정해서 일관되게 보여준다.
    return d.toLocaleString('ko-KR', {
        timeZone: 'Asia/Seoul',
        hour12: false,
    });
};

const todayDate = () => new Date().toISOString().slice(0, 10);
const weekAgoDate = () => {
    const d = new Date();
    d.setDate(d.getDate() - 7);
    return d.toISOString().slice(0, 10);
};

export default function AlertHistoryTable() {
    const [draft, setDraft] = useState(() => ({ from: weekAgoDate(), to: todayDate(), type: '' }));
    const [query, setQuery] = useState(() => ({ from: weekAgoDate(), to: todayDate(), type: '' }));
    const [page, setPage] = useState(0);
    const [data, setData] = useState(null);

    const params = useMemo(() => {
        const p = { page, size: 20 };
        if (query.from) p.from = query.from;
        if (query.to) p.to = query.to;
        if (query.type) p.type = query.type;
        return p;
    }, [query, page]);

    useEffect(() => {
        apiClient.get('/api/monitor/alert-history', { params })
            .then(r => setData(r.data))
            .catch(() => setData({ content: [], totalPages: 0, totalElements: 0 }));
    }, [params]);

    const handleSearch = () => {
        setPage(0);
        setQuery({ ...draft });
    };

    const content = data?.content ?? [];

    return (
        <section className={styles.wrap}>
            <div className={styles.head}>
                <div className={styles.title}>알림 이력</div>
                <div className={styles.filters}>
                    <label className={styles.filterItem}>
                        <span>from</span>
                        <input type="date" value={draft.from} onChange={(e) => setDraft(d => ({ ...d, from: e.target.value }))} />
                    </label>
                    <label className={styles.filterItem}>
                        <span>to</span>
                        <input type="date" value={draft.to} onChange={(e) => setDraft(d => ({ ...d, to: e.target.value }))} />
                    </label>
                    <label className={styles.filterItem}>
                        <span>type</span>
                        <select value={draft.type} onChange={(e) => setDraft(d => ({ ...d, type: e.target.value }))}>
                            <option value="">전체</option>
                            <option value="CPU">CPU</option>
                            <option value="RAM">RAM</option>
                            <option value="DISK">DISK</option>
                            <option value="REDIS_QUEUE">REDIS_QUEUE</option>
                            <option value="API_ERROR">API_ERROR</option>
                            <option value="FEED_BINANCE_TICKER">바이낸스 시세 피드</option>
                            <option value="FEED_BINANCE_AGG">바이낸스 체결 피드</option>
                            <option value="FEED_UPBIT">업비트 시세 피드</option>
                        </select>
                    </label>
                    <button type="button" className={styles.searchBtn} onClick={handleSearch}>검색</button>
                </div>
            </div>

            <div className={styles.tableWrap}>
                <table className={styles.table}>
                    <thead>
                        <tr>
                            <th>발생시각</th>
                            <th>지표</th>
                            <th>현재값</th>
                            <th>임계값</th>
                            <th>지속</th>
                            <th>심각도</th>
                        </tr>
                    </thead>
                    <tbody>
                        {content.length === 0 ? (
                            <tr>
                                <td colSpan={6} className={styles.empty}>알림 이력이 없습니다.</td>
                            </tr>
                        ) : content.map((row) => (
                            <tr key={row.id}>
                                <td>{fmt(row.sentAt)}</td>
                                <td className={styles.mono} title={row.metricType}>{METRIC_LABEL[row.metricType] ?? row.metricType}</td>
                                <td className={styles.mono}>{row.value?.toFixed?.(1) ?? row.value}</td>
                                <td className={styles.mono}>{row.threshold?.toFixed?.(1) ?? row.threshold}</td>
                                <td className={styles.mono}>{Math.max(0, Math.ceil((row.durationSec ?? 0) / 60))}분</td>
                                <td>
                                    <span className={`${styles.sev} ${row.severity === 'CRITICAL' ? styles.critical : styles.warn}`}>
                                        {row.severity}
                                    </span>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            <div className={styles.pager}>
                <button type="button" onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page <= 0}>
                    이전
                </button>
                <span className={styles.pagerInfo}>
                    {page + 1} / {Math.max(1, data?.totalPages ?? 1)} (총 {data?.totalElements ?? 0})
                </span>
                <button
                    type="button"
                    onClick={() => setPage(p => p + 1)}
                    disabled={data != null && page + 1 >= (data.totalPages ?? 0)}
                >
                    다음
                </button>
            </div>
        </section>
    );
}

