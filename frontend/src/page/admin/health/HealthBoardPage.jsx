// [AGENT] 시스템 헬스 체크 보드 (독립 라우트 /admin/health, 로그인 필요)
// A 레이아웃: 계층 카드 3열 그리드 + 상태 점 + hover/클릭 팝오버.
// 곁들임: 상단 거대 상태바 · "이상 항목만" 토글 · 이상 카드 빨강 테두리.
// 연관: healthApi.js, HealthCheckController.java, docs/health-check-board.md
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import Layout from '@/shared/ui/layout/Layout.jsx';
import { useAdminAuth } from '@/shared/auth/useAdminAuth.js';
import { shouldRedirectToAdminLogin } from '@/page/admin/adminAccessPolicy.js';
import styles from '@/page/admin/AdminPage.module.css';
import '@/styles/themes/monitor-teal.css';
import b from './HealthBoard.module.css';
import { getHealthChecks } from './healthApi.js';

// monitor-teal(라이트) 테마에서 읽히는 상태 색
const STATUS_META = {
    UP: { label: 'OK', color: '#16a34a' },
    DEGRADED: { label: '경고', color: '#ca8a04' },
    DOWN: { label: '다운', color: '#dc2626' },
    UNKNOWN: { label: '미구현', color: '#6b7280' },
};

const isAlert = (status) => status === 'DOWN' || status === 'DEGRADED';

// 실패 이벤트 상태 → 한글
const FAIL_STATUS_KO = { DOWN: '다운', DEGRADED: '경고', RESOLVED: '복구' };

function groupByLayer(checks) {
    const order = [];
    const map = new Map();
    for (const c of checks) {
        if (!map.has(c.layerCode)) {
            map.set(c.layerCode, { code: c.layerCode, label: c.layer, items: [] });
            order.push(c.layerCode);
        }
        map.get(c.layerCode).items.push(c);
    }
    return order.map((code) => map.get(code));
}

function CheckRow({ check, pinned, onToggle }) {
    const meta = STATUS_META[check.status] ?? STATUS_META.UNKNOWN;
    return (
        <div
            className={`${b.row} ${pinned ? b.rowPinned : ''}`}
            onClick={() => onToggle(check.key)}
        >
            <span className={b.dot} style={{ background: meta.color }} />
            <span className={b.rowLabel}>{check.label}</span>
            <span className={b.prio}>{check.priority}</span>
            <div className={b.popover}>
                <div className={b.popHeader}>
                    <span>{check.description}</span>
                    <span className={b.popBadge} style={{ background: meta.color }}>{meta.label}</span>
                </div>
                <div className={b.popRow}>
                    <span className={b.popKey}>판정</span>
                    <span className={b.popVal}>
                        {check.detail}{check.thresholdText ? ` · ${check.thresholdText}` : ''}
                    </span>
                </div>
                <div className={b.popRow}><span className={b.popKey}>체크키</span><span className={`${b.popVal} ${styles.mono}`}>{check.key}</span></div>
                <div className={b.popSection}>최근 실패</div>
                {(check.recentFailures?.length ?? 0) === 0 ? (
                    <div className={b.failNone}>이력 없음</div>
                ) : (
                    check.recentFailures.map((f, i) => (
                        <div className={b.failItem} key={i}>
                            <span className={b.failTime}>{f.at}</span>
                            <span className={b.failBody}>
                                {FAIL_STATUS_KO[f.status] ?? f.status}
                                {f.resolvedAt ? ` → 복구 ${f.resolvedAt}` : ' (진행 중)'}
                                {f.cause ? ` · ${f.cause}` : ''}
                            </span>
                        </div>
                    ))
                )}
            </div>
        </div>
    );
}

function LayerCard({ layer, onlyAlerts, pinned, onToggle }) {
    const counts = { UP: 0, DEGRADED: 0, DOWN: 0, UNKNOWN: 0 };
    for (const c of layer.items) counts[c.status] = (counts[c.status] ?? 0) + 1;
    const hasAlert = counts.DOWN > 0 || counts.DEGRADED > 0;

    const items = onlyAlerts ? layer.items.filter((c) => isAlert(c.status)) : layer.items;
    if (items.length === 0) return null;

    return (
        <div className={`${b.layerCard} ${hasAlert ? b.layerCardAlert : ''}`}>
            <div className={b.layerHead}>
                <span>{layer.label}</span>
                <span className={b.miniCounts}>
                    {counts.DOWN > 0 && <span style={{ color: STATUS_META.DOWN.color }}>●{counts.DOWN}</span>}
                    {counts.DEGRADED > 0 && <span style={{ color: STATUS_META.DEGRADED.color }}>●{counts.DEGRADED}</span>}
                    {counts.UP > 0 && <span style={{ color: STATUS_META.UP.color }}>●{counts.UP}</span>}
                    {counts.UNKNOWN > 0 && <span style={{ color: STATUS_META.UNKNOWN.color }}>○{counts.UNKNOWN}</span>}
                </span>
            </div>
            {items.map((c) => (
                <CheckRow key={c.key} check={c} pinned={pinned.has(c.key)} onToggle={onToggle} />
            ))}
        </div>
    );
}

export default function HealthBoardPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const { canAccess, isForbidden } = useAdminAuth();

    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [onlyAlerts, setOnlyAlerts] = useState(false);
    const [pinned, setPinned] = useState(() => new Set());

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            setData(await getHealthChecks());
        } catch (e) {
            setError(e.response?.data?.error ?? '조회 실패');
        } finally {
            setLoading(false);
        }
    }, []);

    const togglePin = useCallback((key) => {
        setPinned((prev) => {
            const next = new Set(prev);
            if (next.has(key)) next.delete(key); else next.add(key);
            return next;
        });
    }, []);

    useEffect(() => {
        if (shouldRedirectToAdminLogin({ canAccess, isForbidden })) {
            navigate('/admin/login', { replace: true, state: { from: location.pathname } });
        }
    }, [canAccess, isForbidden, navigate, location.pathname]);

    useEffect(() => {
        if (canAccess) load();
    }, [canAccess, load]);

    const layers = useMemo(() => groupByLayer(data?.checks ?? []), [data]);

    if (canAccess === null || !canAccess) {
        return (
            <Layout footerCenter={['Health', 'Monitor', 'Admin']} enableSupport={false}>
                <div className={styles.page}><div className={styles.card}>
                    <div className={styles.muted}>{canAccess === null ? '접근 권한 확인 중...' : '로그인 페이지로 이동 중...'}</div>
                </div></div>
            </Layout>
        );
    }

    const summary = data?.summary;

    return (
        <Layout footerCenter={['Health', 'Monitor', 'Admin']} enableSupport={false}>
            <div className={styles.page}>
                <div className={styles.card}>
                    <div className={styles.titleRow}>
                        <div className={styles.title}>시스템 헬스 체크 보드</div>
                        <button
                            type="button"
                            className={`${styles.btn} ${styles.btnActive} ${styles.pushRight}`}
                            onClick={load}
                            disabled={loading}
                        >
                            {loading ? '로딩 중...' : '새로고침'}
                        </button>
                    </div>

                    {error && <div className={`${styles.muted} ${styles.error}`}>{error}</div>}

                    {summary && (
                        <div className={`${b.statusBar} ${summary.allOk ? b.statusBarOk : b.statusBarAlert}`}>
                            <span className={b.statusHeadline} style={{ color: summary.allOk ? STATUS_META.UP.color : STATUS_META.DOWN.color }}>
                                {summary.allOk ? '전부 정상 (OK)' : '이상 감지'}
                            </span>
                            <span className={styles.muted}>총 {summary.total}</span>
                            <span className={b.count} style={{ color: STATUS_META.UP.color }}>OK {summary.up}</span>
                            <span className={b.count} style={{ color: STATUS_META.DEGRADED.color }}>경고 {summary.degraded}</span>
                            <span className={b.count} style={{ color: STATUS_META.DOWN.color }}>다운 {summary.down}</span>
                            <span className={styles.muted}>미구현 {summary.unknown}</span>
                            {data?.generatedAt && <span className={styles.muted}>· {data.generatedAt}</span>}
                            <span className={b.spacer} />
                            <button
                                type="button"
                                className={`${b.toggleBtn} ${onlyAlerts ? b.toggleBtnOn : ''}`}
                                onClick={() => setOnlyAlerts((v) => !v)}
                            >
                                {onlyAlerts ? '전체 보기' : '이상 항목만'}
                            </button>
                        </div>
                    )}

                    <div className={b.grid}>
                        {layers.map((layer) => (
                            <LayerCard
                                key={layer.code}
                                layer={layer}
                                onlyAlerts={onlyAlerts}
                                pinned={pinned}
                                onToggle={togglePin}
                            />
                        ))}
                    </div>

                    {onlyAlerts && summary && summary.down === 0 && summary.degraded === 0 && (
                        <div className={`${styles.muted} ${styles.tableEmpty}`}>이상 항목 없음 — 전부 정상</div>
                    )}
                </div>
            </div>
        </Layout>
    );
}
