// AdminPage 섹션 전반에서 쓰는 순수 유틸리티.

export const fmtTtl = (ttlSeconds) => {
    const n = Number(ttlSeconds);
    if (!Number.isFinite(n) || n <= 0) return '만료';
    return `${Math.ceil(n / 60)}분 후`;
};

export function datetimeLocalToMs(s) {
    if (!s) return null;
    return new Date(s).getTime();
}

export function msToDatetimeLocal(ms) {
    const d = new Date(ms);
    const pad = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export const fmtNum = n => n != null ? Number(n).toLocaleString() : '—';
export const fmtTime = ms => ms != null ? new Date(Number(ms)).toLocaleTimeString() : '—';
export const fmtDateTime = ms => ms != null ? new Date(Number(ms)).toLocaleString() : '—';

// 시간 기반 갭을 인접 또는 겹치는 구간끼리만 합친다. 입력 행은 변경하지 않는다.
export function mergeGapRanges(rows, intervalMs = 60_000) {
    const ranges = rows
        .map(row => ({
            start: Number(row.gap_start_ms),
            end: Number(row.gap_end_ms),
        }))
        .filter(({ start, end }) =>
            Number.isSafeInteger(start) && Number.isSafeInteger(end)
            && start >= 0 && end > start
            && start % intervalMs === 0 && end % intervalMs === 0)
        .sort((a, b) => a.start - b.start || a.end - b.end);

    const merged = [];
    for (const range of ranges) {
        const previous = merged[merged.length - 1];
        if (previous && range.start <= previous.end) {
            previous.end = Math.max(previous.end, range.end);
        } else {
            merged.push({ ...range });
        }
    }
    return merged;
}

export const statusColor = (s) => ({ RUNNING: '#60a5fa', DONE: '#4ade80', ERROR: '#ef4444' }[s] ?? '#94a3b8');
export const statusClass = (s) => ({ RUNNING: 'statusRunning', DONE: 'statusDone', ERROR: 'statusError' }[s] ?? 'statusDefault');
