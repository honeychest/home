// [AGENT] T4-ANALYSIS: 애널리시스 페이지 — 조건 빌더 + 메인 차트 + 사례 패널 + 템플릿
import { useState, useEffect, useRef, useCallback } from 'react';
import apiClient from '@/api/apiClient.js';
import Layout                from '../../shared/ui/layout/Layout.jsx';
import '../../styles/themes/theme-dark.css';
import './AnalysisPage.css';
import { usePageTheme } from '@/app/context/useTheme.js';
import ControlBar            from './components/ControlBar.jsx';
import MainChart             from './components/MainChart.jsx';
import ConditionBuilder      from './components/ConditionBuilder.jsx';
import CasesPanel            from './components/CasesPanel.jsx';
import TemplateBar           from './components/TemplateBar.jsx';
import TemplateManagerModal  from './components/TemplateManagerModal.jsx';
import Toast                 from './components/Toast.jsx';
import { DesktopViewGate, DesktopViewResetButton } from '@/shared/ui/DesktopViewGate.jsx';
import { evaluate }          from './engine/detectionEngine.js';
import { fetchKlines }       from './hooks/useBinanceKlines.js';
import {
  buildAnalysisSearchRequest,
  emptyConditionTree,
  fiveDaysAgoStr,
  mapSearchTimesToIndices,
  previousUtcDateStr,
  todayStr,
} from './model/analysisPageModel.js';

export default function AnalysisPage() {
  const [symbol,            setSymbol]            = useState('BTC');
  const [startDate,         setStartDate]         = useState(fiveDaysAgoStr());
  const [endDate,           setEndDate]           = useState(todayStr());
  const [klineData,         setKlineData]         = useState([]);
  const [loading,           setLoading]           = useState(false);
  const [loadError,         setLoadError]         = useState(null);
  const [conditionTree,     setConditionTree]     = useState(emptyConditionTree());
  const [matchedIndices,    setMatchedIndices]    = useState([]);
  const [detectionError,    setDetectionError]    = useState(null);
  const [page,              setPage]              = useState(0);
  const [templates,         setTemplates]         = useState([]);
  const [selectedId,        setSelectedId]        = useState(null);
  const [saveState,         setSaveState]         = useState('idle'); // 'idle' | 'input' | 'saving'
  const [modalOpen,         setModalOpen]         = useState(false);
  const [toast,             setToast]             = useState(null);
  const [hasExtendedData,   setHasExtendedData]   = useState(false);
  const [timeframe,         setTimeframe]         = useState('1m');
  const [isMobile,          setIsMobile]          = useState(() => window.innerWidth < 768);
  const [viewMode,          setViewMode]          = useState(() => {
    const p = new URLSearchParams(window.location.search);
    return p.get('view') === 'desktop' ? 'desktop' : 'auto'; // 'auto' | 'desktop'
  });

  const mountedRef  = useRef(false);
  const symbolRef   = useRef(symbol);
  const startRef    = useRef(startDate);
  const endRef      = useRef(endDate);

  useEffect(() => { symbolRef.current = symbol;    }, [symbol]);
  useEffect(() => { startRef.current  = startDate; }, [startDate]);
  useEffect(() => { endRef.current    = endDate;   }, [endDate]);

  // ─── 반응형: 모바일에서는 페이지 비활성화 ──────────────────────────────────────

  useEffect(() => {
    const handleResize = () => setIsMobile(window.innerWidth < 768);
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  const forceDesktop = viewMode === 'desktop';
  const showMobileBlocked = isMobile && !forceDesktop;

  const setDesktopView = (next) => {
    const p = new URLSearchParams(window.location.search);
    if (next === 'desktop') p.set('view', 'desktop');
    else p.delete('view');
    const nextUrl = `${window.location.pathname}${p.toString() ? `?${p.toString()}` : ''}${window.location.hash ?? ''}`;
    window.history.replaceState({}, '', nextUrl);
    setViewMode(next === 'desktop' ? 'desktop' : 'auto');
  };

  // ─── 데이터 로드 ─────────────────────────────────────────────────────────────

  const loadData = useCallback(async (sym, start, end, tf = '1m') => {
    setLoading(true);
    setLoadError(null);
    try {
      const klines = await fetchKlines(sym, start, end, tf);
      setKlineData(klines);
      setPage(0);
      setHasExtendedData(false);
    } catch (e) {
      setLoadError({ status: e.status ?? null, message: e.message ?? '알 수 없는 오류' });
    } finally {
      setLoading(false);
    }
  }, []);

  // 초기 진입 자동 로드 (REQ-005)
  useEffect(() => {
    if (mountedRef.current) return;
    mountedRef.current = true;
    Promise.all([
      loadData('BTC', fiveDaysAgoStr(), todayStr()),
      apiClient.get('/api/analysis/templates')
        .then((r) => {
          const list = Array.isArray(r.data) ? r.data : [];
          setTemplates(list);
          if (list.length > 0) {
            const first = list[0];
            setSelectedId(first.id);
            try {
              const tree = typeof first.conditions === 'string'
                ? JSON.parse(first.conditions)
                : first.conditions;
              setConditionTree(tree);
              setPage(0);
            } catch {
              // 템플릿 파싱 실패 시 조용히 무시
            }
          }
        })
        .catch(() => {}),
    ]);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 심볼·타임프레임 변경 즉시 재로드 (REQ-002)
  useEffect(() => {
    if (!mountedRef.current) return;
    loadData(symbol, startDate, endDate, timeframe);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [symbol, timeframe]);

  const handleLoad = () => loadData(symbol, startDate, endDate, timeframe);

  const handleTimeframeChange = (tf) => {
    setTimeframe(tf);
    setMatchedIndices([]);
    setPage(0);
  };

  // ─── 조건 변경 → 즉시 재계산 (REQ-011) ────────────────────────────────────

  useEffect(() => {
    if (klineData.length === 0 || conditionTree.groups.length === 0) {
      setMatchedIndices([]);
      setDetectionError(null);
      return;
    }
    try {
      const indices = evaluate(klineData, conditionTree);
      setMatchedIndices(indices);
      setDetectionError(null);
    } catch {
      setDetectionError('조건 계산 실패');
    }
  }, [conditionTree, klineData]);

  // ─── 사례 패널 네비게이션 ──────────────────────────────────────────────────

  const handlePrev = async () => {
    if (page > 0) { setPage(page - 1); return; }
    // 이전 기간 추가 로드
    const prevStartStr = previousUtcDateStr(startDate);
    try {
      setLoading(true);
      const prevKlines = await fetchKlines(symbol, prevStartStr, prevStartStr);
      setKlineData((prev) => [...prevKlines, ...prev]);
      setStartDate(prevStartStr);
      setHasExtendedData(true);
    } catch (e) {
      setToast({ message: `이전 데이터 로드 실패: ${e.message}`, type: 'error' });
    } finally {
      setLoading(false);
    }
  };

  const handleNext = () => {
    const PAGE_SIZE = 18;
    if ((page + 1) * PAGE_SIZE < matchedIndices.length) setPage(page + 1);
  };

  // ─── 조건 빌더 핸들러 ─────────────────────────────────────────────────────

  const handleTreeChange = (newTree) => {
    setConditionTree(newTree);
    setPage(0);
  };

  const handleReset = () => {
    setConditionTree(emptyConditionTree());
    setMatchedIndices([]);
    setDetectionError(null);
    setPage(0);
  };

  // ─── Analysis 더블클릭 수동 탐색 ─────────────────────────────────────────

  // ─── 실시간 봉 완성 콜백 (MainChart WS → klineData 동기화) ───────────────

  const handleCandleUpdate = useCallback((candle) => {
    setKlineData((prev) => {
      if (prev.length === 0) return prev;
      const last = prev[prev.length - 1];
      if (candle.time === last.time) {
        const next = prev.slice(0, -1);
        next.push(candle);
        return next;
      }
      if (candle.time > last.time) {
        return [...prev, candle];
      }
      return prev;
    });
  }, []);

  const handleAnalysisSearch = async (requestBody) => {
    try {
      const body = buildAnalysisSearchRequest(requestBody, startDate, endDate);
      const res = await apiClient.post('/api/analysis/search', body);
      setMatchedIndices(mapSearchTimesToIndices(res.data, klineData));
      setTimeframe(requestBody.timeframe);
      setPage(0);
    } catch (e) {
      setToast({ message: `검색 실패: ${e.message}`, type: 'error' });
    }
  };

  // ─── 템플릿 저장 ──────────────────────────────────────────────────────────

  const handleSaveClick = () => {
    // 항상 확인 입력 팝업으로 시작 (선택된 템플릿 이름을 기본값으로 사용)
    setSaveState('input');
  };

  const handleSaveConfirm = async (name) => {
    if (!name) { setSaveState('idle'); return; }
    setSaveState('saving');
    const trimmed = name.trim();
    if (!trimmed) { setSaveState('idle'); return; }

    // 현재 선택된 템플릿과 이름이 같으면: 해당 템플릿 덮어쓰기
    const current = selectedId != null ? templates.find((t) => t.id === selectedId) : null;
    const isOverwrite = current && current.name === trimmed;

    try {
      if (isOverwrite && selectedId != null) {
        const res = await apiClient.put(`/api/analysis/templates/${selectedId}`, {
          name: trimmed,
          conditions: JSON.stringify(conditionTree),
          palette:    conditionTree.palette ?? 'MID',
        });
        setTemplates((prev) => prev.map((t) => (t.id === selectedId ? res.data : t)));
        setToast({ message: `'${trimmed}' 템플릿이 업데이트되었습니다.`, type: 'success' });
      } else {
        // 새 이름이거나 선택된 템플릿이 없는 경우 → 새 템플릿 생성
        const res = await apiClient.post('/api/analysis/templates', {
          name: trimmed,
          conditions: JSON.stringify(conditionTree),
          palette:    conditionTree.palette ?? 'MID',
        });
        setTemplates((prev) => [res.data, ...prev]);
        setSelectedId(res.data.id);
        setToast({ message: `'${trimmed}' 저장되었습니다.`, type: 'success' });
      }
    } catch {
      setToast({ message: '저장에 실패했습니다. 다시 시도해주세요.', type: 'error' });
    } finally {
      setSaveState('idle');
    }
  };

  // ─── 템플릿 불러오기 ─────────────────────────────────────────────────────

  const handleSelectTemplate = (template) => {
    setSelectedId(template.id);
    try {
      const tree = typeof template.conditions === 'string'
        ? JSON.parse(template.conditions)
        : template.conditions;
      setConditionTree(tree);
      setPage(0);
    } catch {
      setToast({ message: '템플릿 복원에 실패했습니다.', type: 'error' });
    }
  };

  // ─── 템플릿 관리 ─────────────────────────────────────────────────────────

  const handleRename = async (id, newName) => {
    try {
      const res = await apiClient.put(`/api/analysis/templates/${id}`, { name: newName });
      setTemplates((prev) => prev.map((t) => t.id === id ? res.data : t));
      setToast({ message: '이름이 변경되었습니다.', type: 'success' });
    } catch {
      setToast({ message: '이름 변경에 실패했습니다.', type: 'error' });
    }
  };

  const handleDelete = async (id) => {
    try {
      await apiClient.delete(`/api/analysis/templates/${id}`);
      setTemplates((prev) => prev.filter((t) => t.id !== id));
      if (selectedId === id) setSelectedId(null);
      setToast({ message: '삭제되었습니다.', type: 'delete' });
    } catch {
      setToast({ message: '삭제에 실패했습니다.', type: 'error' });
    }
  };

  const paletteLevel = conditionTree.palette ?? 'MID';
  const [theme] = usePageTheme('analysis');
  const themeClass = theme !== 'dark' ? `theme-${theme}` : '';
  const rootClassName = ['analysis-page', themeClass].filter(Boolean).join(' ');

  return (
    <Layout>
      <div className={rootClassName}>
        {showMobileBlocked ? (
          <DesktopViewGate
            message="데스크톱 화면에서만 분석 페이지를 사용할 수 있습니다."
            onAction={() => setDesktopView('desktop')}
          />
        ) : (
        <>
        {isMobile && forceDesktop && (
          <DesktopViewResetButton onClick={() => setDesktopView('auto')} />
        )}
        {/* 컨트롤 바 */}
        <ControlBar
          symbol={symbol}
          onSymbolChange={setSymbol}
          timeframe={timeframe}
          onTimeframeChange={handleTimeframeChange}
          startDate={startDate}
          endDate={endDate}
          onStartDateChange={setStartDate}
          onEndDateChange={setEndDate}
          onLoad={handleLoad}
          loading={loading}
        />

        {/* 본문 */}
        <div className="analysis-main">
          {/* 좌측 영역: 메인 차트 + 조건 필터 (가로 50%) */}
          <div className="analysis-left">
            {/* 상단: 메인 차트, 하단: 조건 빌더 (세로 50% / 50%) */}
            <div className="analysis-left-top">
              <div className="analysis-chart-slot">
                <MainChart
                  klineData={klineData}
                  matchedIndices={matchedIndices}
                  paletteLevel={paletteLevel}
                  loading={loading}
                  error={loadError}
                  onRetry={handleLoad}
                  symbol={symbol}
                  onSearch={handleAnalysisSearch}
                  timeframe={timeframe}
                  onCandleClose={handleCandleUpdate}
                />
              </div>

              <div className="analysis-card analysis-cb-slot">
                <ConditionBuilder
                  conditionTree={conditionTree}
                  onTreeChange={handleTreeChange}
                  onSave={handleSaveClick}
                  onReset={handleReset}
                  detectionError={detectionError}
                />
              </div>
            </div>

            {/* 템플릿 바 (하단 고정) */}
            <div className="analysis-card analysis-tb-slot">
              <TemplateBar
                templates={templates}
                selectedId={selectedId}
                onSelect={handleSelectTemplate}
                onManage={() => setModalOpen(true)}
                saveState={saveState}
                onSaveClick={handleSaveClick}
                onSaveConfirm={handleSaveConfirm}
                onSaveCancel={() => setSaveState('idle')}
              />
            </div>
          </div>

          {/* 우측 사례 패널: 나머지 가로 영역 전체 사용 */}
          <div className="analysis-right">
            <CasesPanel
              klineData={klineData}
              matchedIndices={matchedIndices}
              page={page}
              totalCount={matchedIndices.length}
              onPrev={handlePrev}
              onNext={handleNext}
              hasPrevPage={!hasExtendedData}
              paletteLevel={paletteLevel}
              symbol={symbol}
              timeframe={timeframe}
            />
          </div>
        </div>
        </>
        )}
      </div>

      {/* 템플릿 관리 팝업 */}
      {modalOpen && (
        <TemplateManagerModal
          templates={templates}
          onClose={() => setModalOpen(false)}
          onLoad={handleSelectTemplate}
          onRename={handleRename}
          onDelete={handleDelete}
        />
      )}

      {/* 토스트 */}
      {toast && (
        <Toast
          message={toast.message}
          type={toast.type}
          onClose={() => setToast(null)}
        />
      )}
    </Layout>
  );
}
