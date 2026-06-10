// [AGENT] T4-ANALYSIS: 컨트롤 바 — 심볼 선택 + 봉 단위(1m/5m/15m) + 날짜 범위 + 불러오기 버튼
export default function ControlBar({
  symbol,
  onSymbolChange,
  timeframe,
  onTimeframeChange,
  startDate,
  endDate,
  onStartDateChange,
  onEndDateChange,
  onLoad,
  loading,
  deltaThreshold,
  onDeltaThresholdChange,
}) {
  const toggleClass = (active, extra = '') =>
    `analysis-control-bar__toggle${extra ? ` ${extra}` : ''}${active ? ' analysis-control-bar__toggle--active' : ''}${loading ? ' analysis-btn--disabled' : ''}`;

  return (
    <div className="analysis-control-bar">
      {/* 심볼 */}
      <div className="analysis-control-bar__group">
        {['BTC', 'ENA'].map((s) => (
          <button
            key={s}
            className={toggleClass(symbol === s)}
            onClick={() => !loading && onSymbolChange(s)}
          >{s}</button>
        ))}
      </div>

      {/* 봉 단위 */}
      <div className="analysis-control-bar__tf-group">
        {['1m', '5m', '15m'].map((tf) => (
          <button
            key={tf}
            className={toggleClass(timeframe === tf, 'analysis-control-bar__toggle--tf')}
            onClick={() => !loading && onTimeframeChange(tf)}
          >{tf}</button>
        ))}
      </div>

      {/* 날짜 범위 */}
      <span className="analysis-control-bar__sep">범위</span>
      <input
        type="date"
        className={`analysis-input${loading ? ' analysis-btn--disabled' : ''}`}
        value={startDate}
        disabled={loading}
        onChange={(e) => onStartDateChange(e.target.value)}
      />
      <span className="analysis-control-bar__sep">~</span>
      <input
        type="date"
        className={`analysis-input${loading ? ' analysis-btn--disabled' : ''}`}
        value={endDate}
        disabled={loading}
        onChange={(e) => onEndDateChange(e.target.value)}
      />

      {/* 불러오기 */}
      <button
        onClick={onLoad}
        disabled={loading}
        className="analysis-btn analysis-btn--primary analysis-btn--load"
      >불러오기</button>

      {/* Delta 라벨 기준 */}
      <label className="analysis-control-bar__delta">
        <span className="analysis-control-bar__sep">Delta 라벨</span>
        <input
          type="number"
          min="0"
          step="1"
          className="analysis-input analysis-control-bar__delta-input"
          value={deltaThreshold}
          onChange={(e) => onDeltaThresholdChange(e.target.value)}
          title="모든 타임프레임에 동일하게 적용되는 delta 라벨 최소값"
        />
      </label>
    </div>
  );
}
