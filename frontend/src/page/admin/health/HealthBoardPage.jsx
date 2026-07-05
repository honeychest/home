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

// monitor-teal(라이트) 테마 토큰 참조. dot = 색약 대비용 점 모양 클래스(색+모양 병행)
const STATUS_META = {
    UP: { label: 'OK', color: 'var(--monitor-gauge-ok)' },
    DEGRADED: { label: '경고', color: 'var(--monitor-severity-warn-text)', dot: 'dotDegraded' },
    DOWN: { label: '다운', color: 'var(--monitor-severity-critical)', dot: 'dotDown' },
    UNKNOWN: { label: '미구현', color: 'var(--monitor-text-secondary)' },
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
            <span className={`${b.dot}${meta.dot ? ` ${b[meta.dot]}` : ''}`} style={{ background: meta.color }} />
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
                    <div><strong>진행</strong> : 계측 완료 34/34 🎉 (피드 4 · 하트비트 13 · 리소스 5 · 인프라 4 · 데이터 2 · 외부 6). 전 항목 계측 완료.</div>
                    <div><strong>구조</strong> : 백그라운드 상시 점검 → <span className={styles.mono}>health_check_event</span> 이력 저장 → 보드는 최신값 읽기만 (정상 지속 시 DB 쓰기 0).</div>
                    <div><strong>패턴 5종</strong> : ① 하트비트+watchdog(잡) &nbsp; ② 공용 평가기(피드) &nbsp; ③ 능동 프로브(인프라 mysql/redis/kafka/postgres) &nbsp; ④ 스냅샷 재사용(리소스, MetricCollectorService 값 임계 판정) &nbsp; ⑤ 이벤트 기반(L4 능동쿼리 · L6 외부연동 호출지점 push)</div>
                    <div><strong>핵심 파일</strong> : <span className={styles.mono}>global/monitor/health/</span> (HealthHeartbeat·Config·Watchdog·Recorder·Service·Controller·Catalog·InfraHealthProbe), FeedHealthEvaluator, <span className={styles.mono}>MetricCollectorService</span>(cpu/ram/disk 스냅샷), 테이블 <span className={styles.mono}>health_check_event</span>(V9)</div>
                    <div><strong>새 하트비트 체크 추가</strong> : 1) <span className={styles.mono}>HealthCheckCatalog</span>에 한 줄 추가(경고/다운 임계 초 포함 — 등록·임계 문구 자동 파생) &nbsp; 2) 대상 서비스 성공=<span className={styles.mono}>beat</span> / 실패=<span className={styles.mono}>fail</span> &nbsp; 3) 착수 전 <span className={styles.mono}>gitnexus_impact</span>로 영향도 확인</div>
                    <div><strong>주의</strong> : <span className={styles.mono}>sched-leader-election</span> 은 HIGH(15개 의존) — 별도 신중 배선</div>
                    <div><strong>작업 단일 소스</strong> : 이 화면(인수인계 + 로드맵)만으로 지속 가능. <span className={styles.mono}>docs/health-check-board.md</span>는 챗봇/설계 참조용.</div>

                    <div style={{ marginTop: 14, paddingTop: 12, borderTop: '1px solid var(--monitor-border, rgba(0,0,0,0.12))' }}>
                        <strong>■ 아키텍처 개선 인계 (2026-07)</strong>
                    </div>
                    <div><strong>[완료] 후보1 · 상태 소스 판정 seam</strong> : <span className={styles.mono}>HealthSource</span> enum이 <span className={styles.mono}>judge(check, ports)</span>(표시 판정)와 <span className={styles.mono}>thresholdText(check)</span>(임계 문구)를 스스로 소유. <span className={styles.mono}>getChecks()</span>·<span className={styles.mono}>Catalog.thresholdText()</span>의 6분기 switch 2개(평행 중복) 제거.</div>
                    <div><strong>변경 4파일</strong> : <span className={styles.mono}>HealthSource</span> · <span className={styles.mono}>HealthCheckService</span> · <span className={styles.mono}>HealthCheckCatalog</span> · <span className={styles.mono}>springboot/CONTEXT.md</span>. 검증 <span className={styles.mono}>test --tests "*.health.*"</span> 90/90 통과, <span className={styles.mono}>detect_changes</span> risk low. <strong>아직 미커밋</strong>.</div>
                    <div><strong>[다음]</strong> 바로 : ① <span className={styles.mono}>HealthSourceTest</span> 신규(소스 단위 독립 테스트 · 선택) → ② 커밋(commit-check 선점검, 첫 줄 <span className={styles.mono}>[요청 요약]</span>).</div>
                    <div><strong>[남은 후보]</strong> 2) 자원 임계 이중화 — <span className={styles.mono}>StatusLadder.RESOURCE_PCT</span>(70/80) vs <span className={styles.mono}>AlertService</span> 리터럴 <span className={styles.mono}>80d</span>, 주석 한 줄로만 연결(조용히 어긋날 위험) &nbsp; 3) 이 보드 내장 문서(HandoffPanel/NotesPanel) 분리</div>
                    <div><strong>함정</strong> : 편집 전 <span className={styles.mono}>gitnexus_impact</span> · 커밋 전 <span className={styles.mono}>detect_changes</span> 필수. GitNexus는 repo 인자 <span className={styles.mono}>"lab"</span> 지정 필수(미지정 시 에러). 쓰기·편집은 응답에 <span className={styles.mono}>'승인'</span> 포함 시에만.</div>
                </div>
            )}
        </div>
    );
}

// 하단 운영 체크리스트 · 장기 메모 — 후속 작업/점검용 참조 패널(기본 접힘, 정적 콘텐츠)
function NotesPanel() {
    const [open, setOpen] = useState(false);
    return (
        <div className={styles.card}>
            <div className={styles.titleRow}>
                <div className={styles.title}>운영 체크리스트 · 장기 메모</div>
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
                    <div><strong>■ 남은 작업 (우선순위)</strong></div>
                    <div>[높음] 임계값 실측 튜닝 — 전부 추정 기본값. 운영 baseline 관찰 후 조정
                        (rawtable 3/6GB · ws 300/800 · ws-reconnect 3/6·60s · L4 gap 1/3봉·flat 10/30% · leader 15/30s)</div>
                    <div>[중간] ext-llm 커버리지 — 로컬 <span className={styles.mono}>.call</span>만 계측, Codex external runner 실패는 미포함</div>
                    <div>[낮음] L4 다심볼 확장(현재 BTCUSDT FUTURES 1심볼) · 공유키 node별 분리(leader·ws-reconnect) · DEGRADED 알림 확장</div>

                    <div><strong>■ 주의점 (운영 시 인지)</strong></div>
                    <div>1. 알림 전제 : 텔레그램(<span className={styles.mono}>telegram_token/chatid</span>) 설정돼야 알림 발송. prod 확인 필수</div>
                    <div>2. DOWN만 알림 : DEGRADED(경고)는 텔레그램 미발송·보드만 표시</div>
                    <div>3. leader 기준 : 리소스%·rawtable·ws·L4·weather 값은 leader 노드에서만 갱신
                        (장애 이벤트는 공유DB라 어느 노드서든 동일, '현재값'만 리더 기준)</div>
                    <div>4. 공유키 flap : leader·ws-reconnect(다노드) 순간 뒤집힘 여지(실용상 무해). security-scan은 provider별 키 분리(ext-virustotal/ext-safebrowsing)로 해소</div>
                    <div>5. 자기참조 : <span className={styles.mono}>ext-telegram-send</span>는 알림 제외 → 텔레그램 장애는 보드/로그로 확인</div>
                    <div>6. 리테이션 : 매일 04:30 leader가 30일 경과 이력 삭제(진행중 장애는 삭제 안 됨)</div>
                    <div>7. 알림 비동기 : 대량 동시 DOWN 시 다수 텔레그램 호출 → 텔레그램 1분 도배차단이 완화</div>
                    <div>8. 로컬 : <span className={styles.mono}>SCHEDULING_ENABLED=false</span>면 ext-weather-api는 이벤트 없이 정상 표시(정상)</div>

                    <div><strong>■ 배포 전 체크리스트</strong></div>
                    <div>☐ prod 텔레그램 토큰/챗ID 설정 확인(알림 전제)</div>
                    <div>☐ <span className={styles.mono}>health_check_event</span>(V9) prod 존재 · <span className={styles.mono}>monitor.health.retention-days</span>(기본 30) 확인</div>
                    <div>☐ 배포 후 이 보드 상단 34/34 · '전부 정상' 표시 확인</div>
                    <div>☐ 정상 지속 시 <span className={styles.mono}>health_check_event</span> write 0 확인</div>
                    <div>☐ 스테이징서 의도적 DOWN 1회 → 🔴 텔레그램 수신 + 복구 🟢 확인</div>
                    <div>☐ 임계 baseline 관찰 시작(rawtable 실제 크기·ws 실제 세션수) → 값 튜닝</div>

                    <div className={styles.muted}>※ 이 메모는 후속 작업 참조용입니다. 설계·체크리스트 원본은 <span className={styles.mono}>docs/health-check-board.md</span>, 진행현황은 위 인수인계 패널이 단일 소스.</div>
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
                </div>                <HandoffPanel />
                <NotesPanel />
                </div>
            </div>
        </Layout>
    );
}
