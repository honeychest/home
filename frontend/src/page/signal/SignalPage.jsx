// [AGENT] Signal Dashboard — 메인 페이지 (Grid 레이아웃 + 상태 관리)
// [AGENT] 모바일/데스크톱 레이아웃 분리: isMobile 기준으로 return 분기
// [AGENT] TASK-12: latestCandleTime, divergenceData, params/canEdit 상태 추가 + 컴포넌트 Props 스캐폴딩
// [AGENT] T4-STEALTH: PatternStrip Props 변경 — symbol만 전달 (latestCandleTime, params 제거)
// [AGENT] signal futures 캔들 조회를 timeRange별 candleType/range 기준으로 통일
import { useState, useEffect, useRef } from 'react';
import Layout from '../../shared/ui/layout/Layout.jsx';
import { useSignalSse } from '../../domain/binance/model/hook/useSignalSse.ts';
import TopBar from './TopBar.jsx';
import LongPanel from './components/LongPanel.jsx';
import ShortPanel from './components/ShortPanel.jsx';
import ShortLiqPanel from './components/ShortLiqPanel.jsx';
import LiquidationPanel from './components/LiquidationPanel.jsx';
import MainCore from './components/MainCore.jsx';
import PatternStrip from './components/PatternStrip.jsx';
import apiClient from '@/api/apiClient.js';
import EnergyGauge from './components/EnergyGauge.jsx';
import TugOfWar from './components/TugOfWar.jsx';
import '@/styles/themes/theme-black.css';
import { usePageTheme } from '@/app/context/useTheme.js';
import {
    appendCandle,
    appendOi,
    applyAggTrade,
    applyForceOrder,
    createSignalRuntimeState,
    resetSignalRuntimeState,
} from './model/signalRuntimeModel.js';
import { datetimeLocalToMs } from './model/datetimeLocal.js';

// value: 타임라인 식별자 | dataRange: 에너지·청산·OI 조회 범위 | displayCount: 차트 표시 기준 범위
const TIME_RANGES = [
    { value: '1m',  label: '1분',   dataRange: '1m',  candleType: '1m', displayCount: 30  },
    { value: '5m',  label: '5분',   dataRange: '5m',  candleType: '1m', displayCount: 60  },
    { value: '30m', label: '30분',  dataRange: '30m', candleType: '1m', displayCount: 90  },
    { value: '4h',  label: '4시간', dataRange: '4h',  candleType: '5m', displayCount: 432 },
    { value: '48h',  label: '48시간',  dataRange: '48h',  candleType: '5m', displayCount: 1728 },
    { value: '168h', label: '168시간', dataRange: '168h', candleType: '5m', displayCount: 6048 },
    { value: '336h', label: '336시간', dataRange: '336h', candleType: '5m', displayCount: 12096 },
];
const getDataRange    = (range) => TIME_RANGES.find((r) => r.value === range)?.dataRange    ?? '5m';
const getDisplayCount = (range) => TIME_RANGES.find((r) => r.value === range)?.displayCount ?? 90;

// OI 차트와 동일한 캔들 타입 — 비교 기준 통일 (변경 시 여기만 수정)
const CHART_CANDLE_TYPE = '5m';
const HISTORY_INPUT_ERROR = '시작 시각을 확인해 주세요.';
const HISTORY_LOAD_ERROR = '에너지 내역을 불러오지 못했습니다.';

export default function SignalPage() {
    const [theme] = usePageTheme('signal');
    const themeClass = theme !== 'black' ? `theme-${theme}` : '';
    const [symbol, setSymbol] = useState('BTCUSDT');
    const [timeRange, setTimeRange] = useState(() => localStorage.getItem('signal_timeRange') || TIME_RANGES[Math.floor(TIME_RANGES.length / 2)].value);
    const [customHistoryEnabled, setCustomHistoryEnabled] = useState(() => localStorage.getItem('signal_customHistoryEnabled') === 'true');
    const [customHistoryStart, setCustomHistoryStart] = useState(() => localStorage.getItem('signal_customHistoryStart') || '');
    const [historyError, setHistoryError] = useState('');
    const [initData, setInitData] = useState(null);
    const [isMobile, setIsMobile] = useState(() => window.innerWidth < 768);

    useEffect(() => {
        const handleResize = () => setIsMobile(window.innerWidth < 768);
        window.addEventListener('resize', handleResize);
        return () => window.removeEventListener('resize', handleResize);
    }, []);

    const [runtimeState, setRuntimeState] = useState(() => createSignalRuntimeState());
    const [params, setParams] = useState(null);
    const [canEdit, setCanEdit] = useState(false);
    const [templates, setTemplates] = useState([]);
    const [selectedTemplateId, setSelectedTemplateId] = useState(() => {
        const stored = localStorage.getItem('signal_template_id');
        return stored ? Number(stored) : null;
    });

    const abortControllerRef = useRef(null);
    const symbolDebounceRef = useRef(null);

    const { aggTrades, forceOrders, latestOi } = useSignalSse({ symbol });

    useEffect(() => {
        const loadInit = async () => {
            try {
                const res = await apiClient.get(`/api/signal/init?symbol=${symbol}`);
                setInitData(res.data);
            } catch (err) {
                console.error('[SignalPage] init failed', err);
            }
        };
        loadInit();
    }, [symbol]);

    useEffect(() => {
        if (abortControllerRef.current) {
            abortControllerRef.current.abort();
        }
        abortControllerRef.current = new AbortController();

        const loadHistory = async () => {
            let historyUrl;
            if (customHistoryEnabled) {
                const fromMs = datetimeLocalToMs(customHistoryStart);
                if (!Number.isFinite(fromMs) || fromMs > Date.now()) {
                    setHistoryError(HISTORY_INPUT_ERROR);
                    return;
                }
                historyUrl = `/api/signal/history?symbol=${symbol}&fromMs=${fromMs}`;
            } else {
                historyUrl = `/api/signal/history?symbol=${symbol}&range=${getDataRange(timeRange)}`;
            }

            try {
                const res = await apiClient.get(historyUrl, {
                    signal: abortControllerRef.current.signal,
                });
                setRuntimeState((prev) => ({
                    ...prev,
                    longEnergy: res.data.longEnergy ?? prev.longEnergy,
                    shortEnergy: res.data.shortEnergy ?? prev.shortEnergy,
                    longLiqTotal: res.data.longLiqTotal ?? prev.longLiqTotal,
                    shortLiqTotal: res.data.shortLiqTotal ?? prev.shortLiqTotal,
                    longLiqEvents: res.data.longLiqEvents ?? prev.longLiqEvents,
                    shortLiqEvents: res.data.shortLiqEvents ?? prev.shortLiqEvents,
                }));
                setHistoryError('');
            } catch (err) {
                if (err.name !== 'CanceledError') {
                    console.error('[SignalPage] history failed', err);
                    setHistoryError(HISTORY_LOAD_ERROR);
                }
            }
        };
        loadHistory();
    }, [symbol, timeRange, customHistoryEnabled, customHistoryStart]);

    // OI 히스토리: timeRange 이상 데이터 로드 (최소 120h 보장) → 클라이언트에서 rangeMs 기준 슬라이싱
    const LARGE_OI_RANGES = new Set(['168h', '336h']);
    useEffect(() => {
        const loadOiHistory = async () => {
            try {
                const oiRange = LARGE_OI_RANGES.has(timeRange) ? timeRange : '120h';
                const res = await apiClient.get(`/api/signal/oi?symbol=${symbol}&range=${oiRange}`);
                if (Array.isArray(res.data)) {
                    setRuntimeState((prev) => ({ ...prev, oiDataHistory: res.data }));
                }
            } catch (err) {
                console.error('[SignalPage] OI history failed', err);
            }
        };
        loadOiHistory();
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [symbol, timeRange]);

    useEffect(() => {
        const loadParams = async () => {
            try {
                const res = await apiClient.get(`/api/signal/params?symbol=${symbol}`);
                const { canEdit: ce, ...rest } = res.data;
                setParams(rest);
                setCanEdit(!!ce);
            } catch (err) {
                console.error('[SignalPage] params failed', err);
            }
        };
        loadParams();
    }, [symbol]);

    useEffect(() => {
        const loadTemplates = async () => {
            try {
                const res = await apiClient.get('/api/analysis/templates');
                const list = Array.isArray(res.data) ? res.data : [];
                setTemplates(list);
                if (list.length === 0) {
                    return;
                }
                // 현재 선택된 템플릿이 없거나 목록에 없으면 첫 번째 템플릿으로 기본 설정
                const exists = list.some((t) => t.id === selectedTemplateId);
                if (selectedTemplateId == null || !exists) {
                    const firstId = list[0].id;
                    setSelectedTemplateId(firstId);
                    localStorage.setItem('signal_template_id', String(firstId));
                }
            } catch (err) {
                console.error('[SignalPage] templates failed', err);
            }
        };
        loadTemplates();
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        if (selectedTemplateId != null) {
            localStorage.setItem('signal_template_id', String(selectedTemplateId));
        } else {
            localStorage.removeItem('signal_template_id');
        }
    }, [selectedTemplateId]);

    const candleType  = CHART_CANDLE_TYPE;
    const candleRange = timeRange;
    useEffect(() => {
        setRuntimeState((prev) => ({ ...prev, candleHistory: [] }));
        apiClient.get(`/api/signal/candles?symbol=${symbol}&type=${candleType}&range=${candleRange}`)
            .then((res) => setRuntimeState((prev) => ({ ...prev, candleHistory: res.data })))
            .catch((err) => console.error('[SignalPage] candles failed', err));
    }, [symbol, candleType, candleRange]);

    const handleCandleUpdate = (bar) => {
        setRuntimeState((prev) => appendCandle(prev, bar));
    };

    const handleParamsSave = async (newParams) => {
        const res = await apiClient.put(`/api/signal/params?symbol=${symbol}`, newParams);
        const { canEdit: _ce, ...rest } = res.data;
        setParams(rest);
        // params 참조 변경 → PatternStrip useEffect 재실행 (자동 재계산 트리거)
    };

    useEffect(() => {
        if (aggTrades.length === 0) return;

        setRuntimeState((prev) => applyAggTrade(prev, aggTrades[0], symbol));
    }, [aggTrades, symbol]);

    useEffect(() => {
        if (forceOrders.length === 0) return;

        setRuntimeState((prev) => applyForceOrder(prev, forceOrders[0], symbol));
    }, [forceOrders, symbol]);

    // OI 데이터 수신 처리
    useEffect(() => {
        if (!latestOi) return;

        setRuntimeState((prev) => appendOi(prev, latestOi, symbol));
    }, [latestOi, symbol]);

    const handleTimeRangeChange = (range) => {
        localStorage.setItem('signal_timeRange', range);
        setTimeRange(range);
    };

    // 체크 해제는 조건만 끈다 — 날짜값은 로컬스토리지에 남겨 재입력 번거로움을 없앤다.
    const handleCustomHistoryEnabledChange = (enabled) => {
        if (enabled) {
            localStorage.setItem('signal_customHistoryEnabled', 'true');
        } else {
            localStorage.removeItem('signal_customHistoryEnabled');
        }
        setCustomHistoryEnabled(enabled);
    };

    const handleCustomHistoryStartChange = (value) => {
        localStorage.setItem('signal_customHistoryStart', value);
        setCustomHistoryStart(value);
    };

    const handleSymbolChange = (newSymbol) => {
        if (symbolDebounceRef.current) clearTimeout(symbolDebounceRef.current);
        symbolDebounceRef.current = setTimeout(() => {
            setSymbol(newSymbol);
            setRuntimeState((prev) => resetSignalRuntimeState(prev));
        }, 300);
    };

    const displayCount  = getDisplayCount(timeRange);
    const candleUnitMs  = candleType === '1m' ? 60_000 : 300_000;
    const rangeMs       = displayCount * candleUnitMs;

    const commonProps = {
        symbol,
        fundingRate: initData?.latestFundingRate || null,
        ...runtimeState,
    };

    if (isMobile) {
        return (
            <Layout footerCenter={['SSE', 'ECharts', 'React 19', 'Tailwind CSS']}>
                <div className={themeClass || undefined} style={{ display: 'flex', flexDirection: 'column', gap: '4px', padding: '4px', backgroundColor: 'var(--black-bg)', fontFamily: "'Pretendard', sans-serif", height: '100%', overflow: 'hidden' }}>
                    <TopBar
                        symbol={symbol}
                        onSymbolChange={handleSymbolChange}
                        timeRange={timeRange}
                        onTimeRangeChange={handleTimeRangeChange}
                        fundingRate={commonProps.fundingRate}
                        timeRanges={TIME_RANGES}
                        customHistoryEnabled={customHistoryEnabled}
                        onCustomHistoryEnabledChange={handleCustomHistoryEnabledChange}
                        customHistoryStart={customHistoryStart}
                        onCustomHistoryStartChange={handleCustomHistoryStartChange}
                        compact
                    />
                    {historyError && <div style={{ color: 'var(--black-short)', fontSize: '11px', padding: '0 4px' }}>{historyError}</div>}
                    <div style={{ backgroundColor: 'var(--black-panel-bg)', borderRadius: '10px', padding: '10px', position: 'relative', height: '200px', flexShrink: 0 }}>
                        <EnergyGauge longEnergy={runtimeState.longEnergy} shortEnergy={runtimeState.shortEnergy} compact />
                        <TugOfWar longEnergy={runtimeState.longEnergy} shortEnergy={runtimeState.shortEnergy} />
                        <div style={{ position: 'absolute', bottom: '20px', left: '30px', fontSize: '10px', color: 'rgba(0,232,135,0.35)' }}>LONG</div>
                        <div style={{ position: 'absolute', bottom: '20px', right: '30px', fontSize: '10px', color: 'rgba(255,59,92,0.35)' }}>SHORT</div>
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '4px', flex: 1, minHeight: 0 }}>
                        <LongPanel energy={runtimeState.longEnergy} trades={runtimeState.longTrades} side="LONG" compact />
                        <ShortPanel energy={runtimeState.shortEnergy} trades={runtimeState.shortTrades} side="SHORT" compact />
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '4px', flex: 1, minHeight: 0 }}>
                        <ShortLiqPanel total={runtimeState.shortLiqTotal} events={runtimeState.shortLiqEvents} />
                        <LiquidationPanel total={runtimeState.longLiqTotal} events={runtimeState.longLiqEvents} />
                    </div>
                </div>
            </Layout>
        );
    }

    return (
        <Layout footerCenter={['SSE', 'ECharts', 'Lightweight Charts', 'React 19', 'Tailwind CSS']}>
            <div
                className={themeClass || undefined}
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(12, 1fr)',
                    gridTemplateRows: 'auto 1fr 0.6fr minmax(200px, 0.5fr)',
                    gap: '4px',
                    padding: '4px',
                    height: '100%',
                    backgroundColor: 'var(--black-bg)',
                    fontFamily: "'Pretendard', sans-serif",
                }}
            >
            <div style={{ gridColumn: '1 / 13', gridRow: '1' }}>
                <TopBar
                    symbol={symbol}
                    onSymbolChange={handleSymbolChange}
                    timeRange={timeRange}
                    onTimeRangeChange={handleTimeRangeChange}
                    fundingRate={commonProps.fundingRate}
                    timeRanges={TIME_RANGES}
                    canEdit={canEdit}
                    params={params}
                    onParamsSave={handleParamsSave}
                    templates={templates}
                    selectedTemplateId={selectedTemplateId}
                    onTemplateChange={setSelectedTemplateId}
                    customHistoryEnabled={customHistoryEnabled}
                    onCustomHistoryEnabledChange={handleCustomHistoryEnabledChange}
                    customHistoryStart={customHistoryStart}
                    onCustomHistoryStartChange={handleCustomHistoryStartChange}
                />
                {historyError && <div style={{ color: 'var(--black-short)', fontSize: '11px', padding: '2px 4px' }}>{historyError}</div>}
            </div>

            <div style={{ gridColumn: '1 / 4', gridRow: '2', overflow: 'hidden' }}>
                <LongPanel energy={runtimeState.longEnergy} trades={runtimeState.longTrades} side="LONG" />
            </div>

            <div style={{ gridColumn: '1 / 4', gridRow: '3', overflow: 'hidden' }}>
                <ShortLiqPanel total={runtimeState.shortLiqTotal} events={runtimeState.shortLiqEvents} />
            </div>

            <div style={{ gridColumn: '4 / 10', gridRow: '2 / 4' }}>
                <MainCore
                    symbol={symbol}
                    longEnergy={runtimeState.longEnergy}
                    shortEnergy={runtimeState.shortEnergy}
                    fundingRate={commonProps.fundingRate}
                    oiData={runtimeState.oiDataHistory}
                    candleHistory={runtimeState.candleHistory}
                    candleType={candleType}
                    timeRange={timeRange}
                    displayCount={displayCount}
                    rangeMs={rangeMs}
                    onCandleTime={(time) => setRuntimeState((prev) => ({ ...prev, latestCandleTime: time }))}
                    onCandleUpdate={handleCandleUpdate}
                />
            </div>

            <div style={{ gridColumn: '10 / 13', gridRow: '2', overflow: 'hidden' }}>
                <ShortPanel energy={runtimeState.shortEnergy} trades={runtimeState.shortTrades} side="SHORT" />
            </div>

            <div style={{ gridColumn: '10 / 13', gridRow: '3', overflow: 'hidden' }}>
                <LiquidationPanel total={runtimeState.longLiqTotal} events={runtimeState.longLiqEvents} />
            </div>

            <div style={{ gridColumn: '1 / 13', gridRow: '4' }}>
            <PatternStrip
                symbol={symbol}
                templateId={selectedTemplateId}
                templateName={templates.find((t) => t.id === selectedTemplateId)?.name}
                paletteLevel={templates.find((t) => t.id === selectedTemplateId)?.palette ?? 'MID'}
            />
            </div>
            </div>
        </Layout>
    );
}
