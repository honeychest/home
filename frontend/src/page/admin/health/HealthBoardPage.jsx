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

// 미구현 체크의 계측 계획(패턴 · 권장 주기) — 화면 로드맵 표시용.
// 구현·테스트 완료로 상태가 UNKNOWN을 벗어나면 로드맵에서 자동 제거됨.
const IMPL_PLAN = {
    // L1 인프라 — 능동 프로브
    'infra-mysql': { pattern: '능동 프로브', when: '15~30초' },
    'infra-redis': { pattern: '능동 프로브', when: '15~30초' },
    'infra-kafka': { pattern: '능동 프로브', when: '15~30초' },
    'infra-postgres': { pattern: '능동 프로브', when: '15~30초' },
    // L2 — WS 콜백 훅
    'feed-ws-reconnect': { pattern: 'WS 콜백 훅', when: '60초 창 재연결 집계' },
    // L3 파이프라인 — 하트비트 + watchdog
    'pipe-kafka-consumer': { pattern: '하트비트+watchdog', when: '소비 지연 감지' },
    'pipe-aggtrade-flush': { pattern: '하트비트+watchdog', when: '5초 무하트비트시 경고' },
    'pipe-rollup-1s': { pattern: '하트비트+watchdog', when: '5초 무하트비트시 경고' },
    'pipe-rollup-1m': { pattern: '하트비트+watchdog', when: '2~3분 무성공시 다운' },
    'pipe-rollup-5m': { pattern: '하트비트+watchdog', when: '10~15분' },
    'pipe-empty-candle-fix': { pattern: '하트비트', when: '10~15분' },
    'pipe-s3-archive': { pattern: '하트비트', when: '20~30분' },
    // L4 무결성 — 능동 쿼리(공용 평가기)
    'data-candle-gap': { pattern: '능동 쿼리', when: '1~5분' },
    'data-quality': { pattern: '능동 쿼리', when: '1~5분' },
    // L5 스케줄러 — 하트비트
    'sched-leader-election': { pattern: '하트비트', when: '15~20초 무갱신시 경고' },
    'sched-weather': { pattern: '하트비트', when: '25~30분 무성공시 경고' },
    'sched-news': { pattern: '하트비트', when: '12~15분' },
    'sched-telegram-poll': { pattern: '하트비트', when: '2~3분 무성공시 다운' },
    'sched-openinterest-poll': { pattern: '하트비트', when: '3~5분' },
    'sched-analysis': { pattern: '하트비트', when: '3~5분' },
    // L6 외부연동
    'ext-telegram-send': { pattern: '사용 시점 push', when: '주기 없음' },
    'ext-llm': { pattern: '능동 프로브', when: '1~5분' },
    'ext-weather-api': { pattern: '하트비트(수집 시)', when: 'sched-weather 연동' },
    'ext-news-rss': { pattern: '하트비트(수집 시)', when: 'sched-news 연동' },
    'ext-security-scan': { pattern: '사용 시점 push', when: '주기 없음' },
    // L7 리소스 — MetricCollector 3초 스냅샷 재사용
    'res-cpu': { pattern: '스냅샷 재사용', when: 'MetricCollector 3초' },
    'res-ram': { pattern: '스냅샷 재사용', when: 'MetricCollector 3초' },
    'res-disk': { pattern: '스냅샷 재사용', when: 'MetricCollector 3초' },
    'res-rawtable-growth': { pattern: '스냅샷 재사용', when: '임계 판정 추가' },
    'res-ws-connections': { pattern: '스냅샷 재사용', when: '임계 판정 추가' },
};

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

// 하단 구현 로드맵 — 미구현(UNKNOWN) 체크만 계층별로 나열. 구현되면 자동 제거.
function RoadmapPanel({ checks }) {
    const [open, setOpen] = useState(true);
    if (checks.length === 0) return null;

    const total = checks.length;
    const pending = checks.filter((c) => c.status === 'UNKNOWN');
    const done = total - pending.length;
    const pct = total ? Math.round((done / total) * 100) : 0;
    const groups = groupByLayer(pending);

    return (
        <div className={styles.card}>
            <div className={styles.titleRow}>
                <div className={styles.title}>구현 로드맵 — 남은 {pending.length} / {total}</div>
                <button
                    type="button"
                    className={`${styles.btn} ${styles.pushRight}`}
                    onClick={() => setOpen((o) => !o)}
                >
                    {open ? '접기 ▲' : '펼치기 ▼'}
                </button>
            </div>

            <div className={b.roadmapBar}><div className={b.roadmapBarFill} style={{ width: `${pct}%` }} /></div>
            <div className={`${styles.muted} ${b.roadmapDesc}`}>
                헬스는 백그라운드에서 상시 점검하고 실패/복구 순간만 기록합니다.
                아래는 아직 계측이 안 된 항목 — 구현·테스트가 끝나 상태가 잡히면 이 목록에서 자동으로 사라집니다.
            </div>

            {open && (pending.length === 0 ? (
                <div className={styles.muted}>전부 구현 완료 🎉</div>
            ) : (
                groups.map((layer) => (
                    <div key={layer.code} className={b.roadmapLayer}>
                        <div className={b.roadmapLayerTitle}>{layer.label}</div>
                        {layer.items.map((c) => {
                            const plan = IMPL_PLAN[c.key] ?? {};
                            return (
                                <div key={c.key} className={b.roadmapItem}>
                                    <span className={b.roadmapName}>▸ {c.label}</span>
                                    <span className={b.roadmapPattern}>{plan.pattern ?? '-'}</span>
                                    <span className={b.roadmapWhen}>{plan.when ?? ''}</span>
                                </div>
                            );
                        })}
                    </div>
                ))
            ))}
        </div>
    );
}

// 하단 작업 인수인계 — 새 세션에서 이어가기 위한 개발 현황 기록(기본 펼침)
function HandoffPanel() {
    const [open, setOpen] = useState(true);
    return (
        <div className={styles.card}>
            <div className={styles.titleRow}>
                <div className={styles.title}>작업 인수인계 (개발 현황)</div>
                <button
                    type="button"
                    className={`${styles.btn} ${styles.pushRight}`}
                    onClick={() => setOpen((o) => !o)}
                >
                    {open ? '접기 ▲' : '펼치기 ▼'}
                </button>
            </div>
            {open && (
                <div className={b.handoff}>
                    <div><strong>진행</strong> : 계측 완료 22/33 (피드 3 · 하트비트 12 · 리소스 3 · 인프라 4). 남은 11은 위 로드맵 참조.</div>
                    <div><strong>구조</strong> : 백그라운드 상시 점검 → <span className={styles.mono}>health_check_event</span> 이력 저장 → 보드는 최신값 읽기만 (정상 지속 시 DB 쓰기 0).</div>
                    <div><strong>패턴 4종</strong> : ① 하트비트+watchdog(잡) &nbsp; ② 공용 평가기(피드) &nbsp; ③ 능동 프로브(인프라 mysql/redis/kafka/postgres, ext-* 미구현) &nbsp; ④ 스냅샷 재사용(리소스, MetricCollectorService 값 임계 판정)</div>
                    <div><strong>핵심 파일</strong> : <span className={styles.mono}>global/monitor/health/</span> (HealthHeartbeat·Config·Watchdog·Recorder·Service·Controller·Catalog·InfraHealthProbe), FeedHealthEvaluator, <span className={styles.mono}>MetricCollectorService</span>(cpu/ram/disk 스냅샷), 테이블 <span className={styles.mono}>health_check_event</span>(V9)</div>
                    <div><strong>새 하트비트 체크 추가</strong> : 1) <span className={styles.mono}>HealthHeartbeatConfig.register(체크, stale, down)</span> &nbsp; 2) 대상 서비스 성공=<span className={styles.mono}>beat</span> / 실패=<span className={styles.mono}>fail</span> &nbsp; 3) 착수 전 <span className={styles.mono}>gitnexus_impact</span>로 영향도 확인</div>
                    <div><strong>주의</strong> : <span className={styles.mono}>sched-leader-election</span> 은 HIGH(15개 의존) — 별도 신중 배선</div>
                    <div><strong>작업 단일 소스</strong> : 이 화면(인수인계 + 로드맵)만으로 지속 가능. <span className={styles.mono}>docs/health-check-board.md</span>는 챗봇/설계 참조용.</div>
                </div>
            )}
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
                <div className={b.scrollArea}>
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
                <RoadmapPanel checks={data?.checks ?? []} />
                <HandoffPanel />
                </div>
            </div>
        </Layout>
    );
}
