// [AGENT] T4-ANALYSIS: 메인 캔들 차트 — 1분봉 + 매칭 봉 하이라이트 + 호버 툴팁(전체 봉)
// [AGENT] 실시간 1분봉 WS + 더블클릭 유사패턴 팝업 추가
import { useEffect, useRef, useState } from 'react';
import { createChart, CandlestickSeries, HistogramSeries, createSeriesMarkers, LineStyle } from 'lightweight-charts';
import { PALETTE } from '../palette.js';
import SignalSearchPopup from './SignalSearchPopup.jsx';

const DEBOUNCE_MS = 200;

function buildMarkers(klineData, matchedIndices, paletteLevel) {
  const pal = PALETTE[paletteLevel] ?? PALETTE.MID;
  return matchedIndices
    .map((idx) => {
      const c = klineData[idx];
      if (!c) return null;
      return {
        time:     Math.floor(c.time / 1000),
        position: 'belowBar',
        color:    pal.iconColor,
        shape:    'arrowUp',
        size:     1,
      };
    })
    .filter(Boolean)
    .sort((a, b) => a.time - b.time);
}

// 하이라이트: 메인 차트 영역 전체를 덮는 세로 띠 (안정성 우선)
function calcOverlayPositions(klineData, matchedIndices, paletteLevel, timeScale) {
  if (klineData.length < 2 || matchedIndices.length === 0) return [];
  const pal = PALETTE[paletteLevel] ?? PALETTE.MID;

  let barWidth = 4;
  const x0 = timeScale.timeToCoordinate(Math.floor(klineData[0].time / 1000));
  const x1 = timeScale.timeToCoordinate(Math.floor(klineData[1].time / 1000));
  if (x0 !== null && x1 !== null) barWidth = Math.max(2, Math.abs(x1 - x0));

  return matchedIndices.map((idx) => {
    const c = klineData[idx];
    if (!c) return null;
    const x = timeScale.timeToCoordinate(Math.floor(c.time / 1000));
    if (x === null) return null;

    return {
      x:      x - barWidth / 2,
      width:  barWidth,
      color:  pal.bgColor,
    };
  }).filter(Boolean);
}

export default function MainChart({ klineData, matchedIndices, paletteLevel, loading, error, onRetry, symbol, onSearch, timeframe = '1m', onCandleClose }) {
  const containerRef      = useRef(null);
  const chartRef          = useRef(null);
  const seriesRef         = useRef(null);
  const volumeSeriesRef   = useRef(null);
  const markersWrapperRef = useRef(null);
  const isInitRef         = useRef(false);
  const debounceTimer     = useRef(null);
  const klineRef          = useRef(klineData);
  const matchedRef        = useRef(matchedIndices);
  const paletteLevelRef   = useRef(paletteLevel);
  const symbolRef         = useRef(symbol);
  const timeframeRef      = useRef(timeframe);
  const liveCandleRef     = useRef(null);
  const onCandleCloseRef  = useRef(onCandleClose);

  const selectedTimeSecRef = useRef(null);

  const [overlayPositions, setOverlayPositions] = useState([]);
  const [selectedOverlay,  setSelectedOverlay]  = useState(null);
  const [tooltip, setTooltip]                   = useState(null);
  const [doubleClickData, setDoubleClickData]   = useState(null);
  const matchedSet = useRef(new Set(matchedIndices));

  useEffect(() => { klineRef.current        = klineData;       }, [klineData]);
  useEffect(() => { matchedRef.current      = matchedIndices;  }, [matchedIndices]);
  useEffect(() => { paletteLevelRef.current = paletteLevel;    }, [paletteLevel]);
  useEffect(() => { matchedSet.current      = new Set(matchedIndices); }, [matchedIndices]);
  useEffect(() => { symbolRef.current       = symbol;          }, [symbol]);
  useEffect(() => { timeframeRef.current    = timeframe;       }, [timeframe]);
  useEffect(() => { onCandleCloseRef.current = onCandleClose;  }, [onCandleClose]);

  // 차트 초기화
  useEffect(() => {
    if (!containerRef.current || isInitRef.current) return;

    const chart = createChart(containerRef.current, {
      autoSize:     true,
      handleScale:  true,
      handleScroll: true,
      layout: {
        background:      { color: 'transparent' },
        textColor:       'var(--dark-text-muted)',
        attributionLogo: false,
      },
      grid: {
        vertLines: { color: 'rgba(255,255,255,0.03)' },
        horzLines: { color: 'rgba(255,255,255,0.03)' },
      },
      timeScale:       { visible: true, borderColor: 'var(--dark-input-border)' },
      rightPriceScale: { visible: true, borderColor: 'var(--dark-input-border)' },
      crosshair: {
        horzLine: { labelVisible: true },
        vertLine: { labelVisible: true },
      },
    });

    const volumeSeries = chart.addSeries(HistogramSeries, {
      priceScaleId:     'volume',
      lastValueVisible: false,
      priceLineVisible: false,
    });
    volumeSeries.priceScale().applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    });

    const series = chart.addSeries(CandlestickSeries, {
      upColor:          'rgba(80,160,255,0.9)',
      downColor:        'rgba(255,160,50,0.9)',
      borderUpColor:    'rgba(80,160,255,0.9)',
      borderDownColor:  'rgba(255,160,50,0.9)',
      wickUpColor:      'rgba(80,160,255,0.6)',
      wickDownColor:    'rgba(255,160,50,0.6)',
      lastValueVisible: true,
      priceLineVisible: true,
      priceLineStyle:   LineStyle.Dashed,
      priceLineWidth:   1,
      priceLineColor:   'rgba(255,255,255,0.35)',
    });

    volumeSeriesRef.current   = volumeSeries;
    markersWrapperRef.current = createSeriesMarkers(series, []);

    chart.timeScale().subscribeVisibleLogicalRangeChange(() => {
      clearTimeout(debounceTimer.current);
      debounceTimer.current = setTimeout(() => {
        if (!chartRef.current) return;
        const ts = chart.timeScale();
        setOverlayPositions(
          calcOverlayPositions(klineRef.current, matchedRef.current, paletteLevelRef.current, ts)
        );
        if (selectedTimeSecRef.current != null) {
          const x = ts.timeToCoordinate(selectedTimeSecRef.current);
          const klines = klineRef.current;
          let bw = 4;
          if (klines.length >= 2) {
            const x0 = ts.timeToCoordinate(Math.floor(klines[0].time / 1000));
            const x1 = ts.timeToCoordinate(Math.floor(klines[1].time / 1000));
            if (x0 != null && x1 != null) bw = Math.max(2, Math.abs(x1 - x0));
          }
          setSelectedOverlay(x != null ? { x: x - bw / 2, width: bw } : null);
        }
      }, DEBOUNCE_MS);
    });

    // 호버 툴팁 — 전체 봉 표시
    chart.subscribeCrosshairMove((param) => {
      if (!param.point || !param.time || !containerRef.current) { setTooltip(null); return; }
      const data = param.seriesData?.get(series);
      if (!data) { setTooltip(null); return; }

      const live = liveCandleRef.current;
      if (live && live.time === param.time) {
        const pctChgLive = live.open !== 0 ? ((live.close - live.open) / live.open * 100).toFixed(2) : '0.00';
        setTooltip({
          x:          param.point.x,
          y:          param.point.y,
          time:       live.time,
          volume:     live.volume,
          delta:      live.delta,
          priceChg:   pctChgLive,
          isMatched:  false,
          containerW: containerRef.current?.clientWidth ?? 0,
        });
        return;
      }

      const timeMs  = param.time * 1000;
      const klines  = klineRef.current;
      const idx     = klines.findIndex((c) => Math.floor(c.time / 1000) === param.time
                                            || Math.abs(c.time - timeMs) < 60_000);
      if (idx === -1) { setTooltip(null); return; }

      const c      = klines[idx];
      const pctChg = c.open !== 0 ? ((c.close - c.open) / c.open * 100).toFixed(2) : '0.00';
      setTooltip({
        x:          param.point.x,
        y:          param.point.y,
        time:       param.time,
        volume:     c.volume,
        delta:      c.delta,
        priceChg:   pctChg,
        isMatched:  matchedSet.current.has(idx),
        containerW: containerRef.current?.clientWidth ?? 0,
      });
    });

    chartRef.current  = chart;
    seriesRef.current = series;
    isInitRef.current = true;

    return () => {
      clearTimeout(debounceTimer.current);
      chart.remove();
      chartRef.current          = null;
      seriesRef.current         = null;
      volumeSeriesRef.current   = null;
      markersWrapperRef.current = null;
      isInitRef.current         = false;
    };
  }, []);

  // 실시간 봉 — 백엔드 WS (/ws/candle/{timeframe}?symbol=BTCUSDT)
  useEffect(() => {
    if (!symbol) return;
    const wsProtocol  = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const symbolUsdt  = symbol.toUpperCase() + 'USDT';
    const reconnectTimer = { current: null };
    let destroyed = false;

    const connect = () => {
      if (destroyed) return;
      const ws = new WebSocket(`${wsProtocol}//${window.location.host}/ws/candle/${timeframe}?symbol=${symbolUsdt}`);

      ws.onmessage = (e) => {
        if (destroyed || !seriesRef.current) return;
        try {
          const msg     = JSON.parse(e.data);
          const unixSec = Math.floor(new Date(msg.time).getTime() / 1000);
          if (isNaN(unixSec)) return;

          const delta      = msg.delta ?? 0;
          const volume     = msg.volume ?? 0;
          const priceUp    = msg.close >= msg.open;
          const deltaUp    = delta >= 0;
          const divergence = priceUp !== deltaUp;
          const candleData = { time: unixSec, open: msg.open, high: msg.high, low: msg.low, close: msg.close };
          if (divergence) {
            const col = priceUp ? 'rgba(255,50,150,0.95)' : 'rgba(50,220,120,0.95)';
            candleData.color       = col;
            candleData.borderColor = col;
            candleData.wickColor   = col;
          }
          seriesRef.current.update(candleData);

          if (volumeSeriesRef.current && msg.volume != null) {
            volumeSeriesRef.current.update({
              time:  unixSec,
              value: msg.volume,
              color: priceUp ? 'rgba(80,160,255,0.4)' : 'rgba(255,160,50,0.4)',
            });
          }

          liveCandleRef.current = {
            time:   unixSec,
            open:   msg.open,
            high:   msg.high,
            low:    msg.low,
            close:  msg.close,
            volume,
            delta,
          };

          if (msg.is_closed === true) {
            onCandleCloseRef.current?.({
              time:   unixSec * 1000,
              open:   msg.open,
              high:   msg.high,
              low:    msg.low,
              close:  msg.close,
              volume,
              delta,
            });
            liveCandleRef.current = null;
          }
        } catch { /* 파싱 실패 무시 */ }
      };
      ws.onclose = () => {
        if (!destroyed) reconnectTimer.current = setTimeout(connect, 5000);
      };
      ws.onerror = () => ws.close();
    };

    connect();
    return () => {
      destroyed = true;
      clearTimeout(reconnectTimer.current);
      liveCandleRef.current = null;
    };
  }, [symbol, timeframe]);

  // 더블클릭 → 유사패턴 팝업
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const handleDblClick = (e) => {
      if (!chartRef.current) return;
      const rect    = container.getBoundingClientRect();
      const timeSec = chartRef.current.timeScale().coordinateToTime(e.clientX - rect.left);
      if (timeSec == null) return;
      const klines = klineRef.current;
      const idx    = klines.findIndex((c) => Math.floor(c.time / 1000) === timeSec);
      if (idx === -1) return;
      const candle    = klines[idx];
      const prevClose = idx > 0 ? klines[idx - 1].close : candle.open;

      selectedTimeSecRef.current = timeSec;
      const ts = chartRef.current.timeScale();
      const sx = ts.timeToCoordinate(timeSec);
      let bw = 4;
      if (klines.length >= 2) {
        const x0 = ts.timeToCoordinate(Math.floor(klines[0].time / 1000));
        const x1 = ts.timeToCoordinate(Math.floor(klines[1].time / 1000));
        if (x0 != null && x1 != null) bw = Math.max(2, Math.abs(x1 - x0));
      }
      setSelectedOverlay(sx != null ? { x: sx - bw / 2, width: bw } : null);

      setDoubleClickData({
        candle:    { open: candle.open, high: candle.high, low: candle.low, close: candle.close, volume: candle.volume },
        prevClose,
        timeframe: timeframeRef.current,
        symbol:    symbolRef.current.toUpperCase() + 'USDT',
      });
    };
    container.addEventListener('dblclick', handleDblClick);
    return () => container.removeEventListener('dblclick', handleDblClick);
  }, []);

  const applyMatched = () => {
    if (!markersWrapperRef.current || !chartRef.current) return;
    markersWrapperRef.current.setMarkers(buildMarkers(klineRef.current, matchedRef.current, paletteLevelRef.current));
    setOverlayPositions(
      calcOverlayPositions(
        klineRef.current,
        matchedRef.current,
        paletteLevelRef.current,
        chartRef.current.timeScale(),
      )
    );
  };

  // klineData 변경 → 차트 전체 재세팅
  useEffect(() => {
    if (!seriesRef.current || !chartRef.current || klineData.length === 0) return;
    const sorted = klineData
      .map((c) => ({ time: Math.floor(c.time / 1000), open: c.open, high: c.high, low: c.low, close: c.close, volume: c.volume, delta: c.delta ?? 0 }))
      .sort((a, b) => a.time - b.time)
      .filter((item, i, arr) => i === 0 || item.time !== arr[i - 1].time);

    seriesRef.current.setData(sorted.map(({ time, open, high, low, close, delta }) => {
      const priceUp    = close >= open;
      const deltaUp    = delta >= 0;
      const divergence = priceUp !== deltaUp;

      if (!divergence) return { time, open, high, low, close };

      const col = priceUp
        ? 'rgba(255,50,150,0.95)'   // 가격↑ delta- : 자홍색
        : 'rgba(50,220,120,0.95)';  // 가격↓ delta+ : 연두색
      return { time, open, high, low, close, color: col, borderColor: col, wickColor: col };
    }));
    seriesRef.current.priceScale().applyOptions({
      scaleMargins: { top: 0.02, bottom: 0.22 },
    });

    if (volumeSeriesRef.current) {
      volumeSeriesRef.current.setData(sorted.map(({ time, open, close, volume }) => ({
        time,
        value: volume,
        color: close >= open ? 'rgba(80,160,255,0.4)' : 'rgba(255,160,50,0.4)',
      })));
    }

    chartRef.current.timeScale().fitContent();
    applyMatched();
  }, [klineData]);

  // matchedIndices / paletteLevel 변경 → 하이라이트만 갱신
  useEffect(() => {
    if (!seriesRef.current) return;
    applyMatched();
  }, [matchedIndices, paletteLevel]);

  const tooltipEl = tooltip && (() => {
    const containerW = tooltip.containerW ?? 0;
    const left = tooltip.x + 12 + 160 > containerW ? tooltip.x - 172 : tooltip.x + 12;
    const isNetBuy   = tooltip.delta >= 0;
    const deltaColor = isNetBuy
      ? 'rgba(var(--buy-rgb), 0.9)'
      : 'rgba(var(--sell-rgb), 0.9)';
    const deltaLabel = isNetBuy ? '순매수' : '순매도';
    const deltaValue = Math.abs(tooltip.delta);
    const timeStr = (() => {
      const d = new Date(tooltip.time * 1000);
      const pad = (n) => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    })();
    const tooltipClass = `analysis-mc__tooltip${tooltip.isMatched ? ' analysis-mc__tooltip--matched' : ''}`;
    return (
      <div
        className={tooltipClass}
        style={{ left, top: Math.max(4, tooltip.y - 36) }}
      >
        <div className="analysis-mc__tooltip-time">{timeStr}</div>
        <div className="analysis-mc__tooltip-row">
          거래량 {Number(tooltip.volume).toLocaleString(undefined, { maximumFractionDigits: 2 })}
        </div>
        <div className="analysis-mc__tooltip-row" style={{ color: deltaColor }}>
          {deltaLabel} {Number(deltaValue).toLocaleString(undefined, { maximumFractionDigits: 2 })}
        </div>
        <div className="analysis-mc__tooltip-row--price analysis-mc__tooltip-row">
          가격변화 {tooltip.priceChg}%
        </div>
      </div>
    );
  })();

  return (
    <div className="analysis-mc">
      <div ref={containerRef} className="analysis-mc__chart" />

      {/* 로딩 스피너 */}
      {loading && (
        <div className="analysis-mc__loading">
          <div className="analysis-spinner analysis-spinner--lg" />
        </div>
      )}

      {/* 에러 */}
      {!loading && error && (
        <div className="analysis-mc__error">
          <div className="analysis-mc__error-title">데이터를 불러오지 못했습니다.</div>
          <div className="analysis-mc__error-detail">
            {error.status ? `HTTP ${error.status} — ${error.message}` : error.message}
          </div>
          <button onClick={onRetry} className="analysis-mc__error-retry">다시 시도</button>
        </div>
      )}

      {/* 매칭 봉 오버레이 */}
      <div className="analysis-mc__overlay-layer">
        {overlayPositions.map((h, i) => (
          <span
            key={i}
            className="analysis-mc__overlay-bar"
            style={{ left: h.x, width: h.width, backgroundColor: h.color }}
          />
        ))}
      </div>

      {/* 더블클릭 선택 봉 하이라이트 */}
      {selectedOverlay && (
        <span
          className="analysis-mc__selected"
          style={{ left: selectedOverlay.x, width: selectedOverlay.width }}
        />
      )}

      {tooltipEl}

      {doubleClickData && (
        <SignalSearchPopup
          doubleClickData={doubleClickData}
          onSearch={(body) => { onSearch?.(body); setDoubleClickData(null); }}
          onClose={() => setDoubleClickData(null)}
          cooldownTimeLeft={0}
        />
      )}
    </div>
  );
}
