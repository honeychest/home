// [AGENT] 전 사용자 대기열 실시간 모니터링 — 오너 전용, 검증단계 필수 기능 (2026-07-13 확정)
// 목적: "어떤 계정이 어떤 영상을 요청했고 지금 어디까지 진행됐는지"를 실시간으로 추적해
// 분석 파이프라인 검증(품질 합격 기준, CONTEXT.md)을 할 수 있게 한다.
// 방식은 폴링(RecipesPage 와 동일 패턴) — SSE 는 recipe 격리 규율상 배관을 새로 복사해야 해서
// 검증단계엔 과함, 필요해지면 나중에 승격 (2026-07-13 인터뷰 확정).
// 3상태 목록 패턴 + 조작 실행기(useMutation)는 RecipesPage 모방 (PLAYBOOK "가져다 쓸 것").
// 대기열 크기·워커 생존·429 이력은 2026-07-13 추가 확정(6분 지연 사례 재발 시 원인을 화면에서
// 바로 구분하기 위함): "밀려서 느린가(대기열 크기)" vs "멈췄나(워커 생존)" vs "일시적 실패 때문인가
// (한도·과부하·타임아웃 이력 — 2026-07-13 실측으로 429 외 503·타임아웃도 같은 통계에 포함)".
// 항목 탭 → 시트(강제 재분석·영상 삭제)는 2026-07-13 추가 확정 — 오너 전용 강제 동작은 흩어지지
// 않게 이 화면 한 곳에 모은다(CONTEXT.md "오너 전용 강제 동작 정책").
import { useCallback, useEffect, useState } from 'react';
import type { MonitorItem, MonitorSnapshot } from '../data/monitorTypes';
import { MONITOR_LIMIT, isForbidden, monitorRepository } from '../data/monitorRepository';
import { useMutation } from './useMutation';
import { useQuery } from './useQuery';
import type { RcpBadgeVariant } from '../ui/RcpBadge';
import RcpBadge from '../ui/RcpBadge';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpButton from '../ui/RcpButton';
import RcpInlineError from '../ui/RcpInlineError';
import RcpLoadError from '../ui/RcpLoadError';

const POLL_MS = 2000;
const TICK_MS = 1000; // 경과 시간 표시 갱신 — 목록 재조회와 별개
// 워커는 5초마다 틱(RegistrationWorker fixedDelay=5000) — 그 몇 배 이상 조용하면 죽은 것으로 판단
const WORKER_STALE_SECONDS = 20;

const FORBIDDEN_TEXT = '이 화면은 오너 전용이에요';
const LOAD_ERROR_TEXT = '모니터링 정보를 불러오지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
// 403 은 "네트워크 문제"가 아니라 접근 거부라 문구가 다르다 — 화면 분기는 query.failure 로 판단
const loadMessage = (e: unknown) => (isForbidden(e) ? FORBIDDEN_TEXT : LOAD_ERROR_TEXT);
const ACTION_FAIL_TEXT = '실행하지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const REMOVE_CONFIRM_TEXT = (title: string) =>
    `"${title}"을(를) 삭제할까요? 이 영상을 등록한 모든 사용자 목록에서 "삭제됨"으로 표시돼요 `
    + `(연결은 유지 — 나중에 다시 분석하면 복구돼요).`;

const STATUS_LABEL: Record<MonitorItem['status'], string> = {
    WAITING: '대기 중',
    ANALYZING: '분석 중',
    DONE: '완료',
    TOO_LONG: '긴 영상',
    FAILED: '실패',
    REMOVED: '삭제됨',
};
const STATUS_BADGE: Record<MonitorItem['status'], RcpBadgeVariant> = {
    WAITING: 'neutral',
    ANALYZING: 'analyzing',
    DONE: 'good',
    TOO_LONG: 'warning',
    FAILED: 'critical',
    REMOVED: 'serious',
};

/** 진행 시간 기준: ANALYZING 은 분석 시작, 그 외(WAITING 포함)는 등록 시각 */
const elapsedBaseIso = (item: MonitorItem): string =>
    item.status === 'ANALYZING' && item.analyzingStartedAt ? item.analyzingStartedAt : item.registeredAt;

const elapsedSecondsSince = (iso: string, nowMs: number): number =>
    Math.max(0, Math.floor((nowMs - new Date(iso).getTime()) / 1000));

const formatElapsedSeconds = (totalSeconds: number): string => {
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return minutes > 0 ? `${minutes}분 ${seconds}초` : `${seconds}초`;
};

const workerStatusText = (heartbeatAt: string | null, nowMs: number): string => {
    if (!heartbeatAt) return '아직 신호 없음';
    return `${formatElapsedSeconds(elapsedSecondsSince(heartbeatAt, nowMs))} 전`;
};
const isWorkerStale = (heartbeatAt: string | null, nowMs: number): boolean =>
    heartbeatAt === null || elapsedSecondsSince(heartbeatAt, nowMs) > WORKER_STALE_SECONDS;

const rateLimitText = (snapshot: MonitorSnapshot, nowMs: number): string => {
    if (snapshot.rateLimitCount === 0) return '아직 없음';
    const last = snapshot.lastRateLimitedAt
        ? `${formatElapsedSeconds(elapsedSecondsSince(snapshot.lastRateLimitedAt, nowMs))} 전`
        : '';
    return `${snapshot.rateLimitCount}회${last ? ` (최근 ${last})` : ''}`;
};

/** 다음 재시도까지 남은 초 — 백오프 중이 아니면(과거 시각) null */
const nextRetrySecondsLeft = (nextRetryAt: string | null, nowMs: number): number | null => {
    if (!nextRetryAt) return null;
    const left = Math.ceil((new Date(nextRetryAt).getTime() - nowMs) / 1000);
    return left > 0 ? left : null;
};

export default function MonitorPage() {
    const [now, setNow] = useState(() => Date.now());
    const [selected, setSelected] = useState<MonitorItem | null>(null); // 항목 탭 → 액션 시트

    const load = useCallback(() => monitorRepository.list(MONITOR_LIMIT), []);
    const query = useQuery<MonitorSnapshot>(load, loadMessage);
    const { data: snapshot, error: loadError, reload, refresh } = query;
    // 접근 거부는 "다시 시도"가 의미 없는 다른 화면이라 원인으로 갈라낸다 (useQuery 는 403 을 모른다)
    const forbidden = query.failure !== null && isForbidden(query.failure);

    // 조작 실행기 (공용 useMutation — 연타 방지·실패 문구·재동기화 한 곳, PLAYBOOK 관례 5)
    const mutation = useMutation(() => ACTION_FAIL_TEXT, reload);

    // 목록 재조회 폴링 — 접근 거부 상태면 재시도하지 않음(계속 403 만 반복하지 않게)
    useEffect(() => {
        if (forbidden) return undefined;
        const timer = window.setInterval(() => {
            // 폴링 실패는 조용히 넘긴다(다음 턴에 다시). 단 403(폴링 도중 권한이 사라진 경우)만은
            // 화면 자체가 바뀌어야 하므로 reload 로 한 번 더 태워 failure 를 세운다.
            void refresh().catch((e) => { if (isForbidden(e)) void reload(); });
        }, POLL_MS);
        return () => window.clearInterval(timer);
    }, [forbidden, refresh, reload]);

    // 경과 시간 표시용 초침 — 서버 재조회 없이 화면만 갱신
    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), TICK_MS);
        return () => window.clearInterval(timer);
    }, []);

    const handleReanalyze = (item: MonitorItem) => mutation.run(async () => {
        await monitorRepository.reanalyze(item.videoId);
        setSelected(null);
        await reload();
    });

    const handleRemove = (item: MonitorItem) => {
        if (!window.confirm(REMOVE_CONFIRM_TEXT(item.title ?? item.url))) return;
        void mutation.run(async () => {
            await monitorRepository.remove(item.videoId);
            setSelected(null);
            await reload();
        });
    };

    if (forbidden) {
        return (
            <main className="rcp-screen" id="rcp-monitor-page">
                <p className="rcp-empty" role="alert">{FORBIDDEN_TEXT}</p>
            </main>
        );
    }

    const errorLine = <RcpInlineError message={mutation.error} />;

    return (
        <main className="rcp-screen" id="rcp-monitor-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">분석 모니터링</h1>
                <p className="rcp-screen-subtitle">전 사용자 대기열을 실시간으로 추적해요 (오너 전용)</p>
            </header>

            <RcpLoadError message={loadError} onRetry={() => void reload()} />
            {!loadError && snapshot === null && <p className="rcp-empty">불러오는 중…</p>}

            {snapshot !== null && (
                <>
                    <div className="rcp-summary-row" id="rcp-monitor-summary" aria-label="대기열·워커 요약">
                        <RcpBadge variant="neutral">대기 {snapshot.waitingCount}</RcpBadge>
                        <RcpBadge variant="analyzing">분석 중 {snapshot.analyzingCount}</RcpBadge>
                        <RcpBadge variant={isWorkerStale(snapshot.workerHeartbeatAt, now) ? 'critical' : 'good'}>
                            워커 {workerStatusText(snapshot.workerHeartbeatAt, now)}
                        </RcpBadge>
                        <RcpBadge variant={snapshot.rateLimitCount > 0 ? 'warning' : 'neutral'}>
                            일시적 실패(한도·과부하 등) {rateLimitText(snapshot, now)}
                        </RcpBadge>
                        {nextRetrySecondsLeft(snapshot.nextRetryAt, now) !== null && (
                            <RcpBadge variant="warning">
                                다음 재시도까지 {nextRetrySecondsLeft(snapshot.nextRetryAt, now)}초
                            </RcpBadge>
                        )}
                    </div>

                    <section id="rcp-monitor-list" aria-label="전 사용자 분석 대기열">
                        {snapshot.items.length === 0 && <p className="rcp-empty">대기열이 비어 있어요.</p>}
                        {snapshot.items.map((item) => (
                            <button
                                type="button"
                                className="rcp-monitor-row"
                                key={`${item.userId}-${item.videoId}`}
                                onClick={() => setSelected(item)}
                            >
                                <div className="rcp-monitor-row-top">
                                    <span className="rcp-monitor-email">{item.email}</span>
                                    <RcpBadge variant={STATUS_BADGE[item.status]}>{STATUS_LABEL[item.status]}</RcpBadge>
                                </div>
                                <span className="rcp-monitor-title">{item.title ?? item.url}</span>
                                <div className="rcp-monitor-row-bottom">
                                    {(item.status === 'WAITING' || item.status === 'ANALYZING') && (
                                        <span>{formatElapsedSeconds(elapsedSecondsSince(elapsedBaseIso(item), now))}</span>
                                    )}
                                    {item.analysisSeconds !== null && (
                                        <span>{formatElapsedSeconds(item.analysisSeconds)} 걸림</span>
                                    )}
                                    {item.attemptCount > 0 && <span>시도 {item.attemptCount}회</span>}
                                    {item.geminiRetryCount > 0 && <span>Gemini 재시도 {item.geminiRetryCount}회</span>}
                                </div>
                                {item.lastError && (
                                    <p className="rcp-monitor-error" role="alert">{item.lastError}</p>
                                )}
                            </button>
                        ))}
                    </section>
                </>
            )}

            <RcpBottomSheet
                open={selected !== null}
                title={selected?.title ?? '영상 관리'}
                onClose={() => setSelected(null)}
            >
                {selected && (
                    <>
                        {errorLine}
                        <p className="rcp-sheet-meta">
                            {selected.email} · {STATUS_LABEL[selected.status]}
                        </p>
                        {selected.lastError && (
                            <p className="rcp-monitor-error" role="alert">{selected.lastError}</p>
                        )}
                        <a
                            className="rcp-btn rcp-btn-ghost"
                            id="rcp-monitor-open-source"
                            href={selected.url}
                            target="_blank"
                            rel="noreferrer"
                        >
                            원본 영상 보기
                        </a>
                        <RcpButton onClick={() => void handleReanalyze(selected)}>강제 재분석</RcpButton>
                        <RcpButton variant="danger" onClick={() => handleRemove(selected)}>영상 삭제</RcpButton>
                    </>
                )}
            </RcpBottomSheet>
        </main>
    );
}
