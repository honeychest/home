// [AGENT] Signal 수동 탐색 팝업 — 더블클릭 봉 기준 등락율 검색
import { useState } from 'react';

function fmt(num, decimals = 2) {
    if (num === null || num === undefined) return '';
    return Number(num).toFixed(decimals);
}

/**
 * @param {{candle, prevClose, timeframe}} doubleClickData
 * @param {Function} onSearch  - (requestBody) => void
 * @param {Function} onClose
 * @param {number} cooldownTimeLeft
 */
export default function SignalSearchPopup({ doubleClickData, onSearch, onClose, cooldownTimeLeft }) {
    const { candle, prevClose, timeframe } = doubleClickData;

    const initRate = prevClose !== 0
        ? ((candle.close - prevClose) / prevClose * 100)
        : 0;

    const [priceChangeRate, setPriceChangeRate] = useState(fmt(initRate));
    const [rateTolerance,   setRateTolerance]   = useState('0.02');
    const [openPrice,       setOpenPrice]        = useState(fmt(candle.open));
    const [highPrice,       setHighPrice]        = useState(fmt(candle.high));
    const [lowPrice,        setLowPrice]         = useState(fmt(candle.low));
    const [closePrice,      setClosePrice]       = useState(fmt(candle.close));
    const [totalVolume,     setTotalVolume]      = useState(fmt(candle.volume, 0));
    const [volTolerance,    setVolTolerance]     = useState('15');
    const [useRateFilter,   setUseRateFilter]    = useState(true);
    const [useVolFilter,    setUseVolFilter]     = useState(true);
    const [errors,          setErrors]           = useState({});

    const handleRateChange = (value) => {
        setPriceChangeRate(value);
        const rate = parseFloat(value);
        if (!isNaN(rate) && prevClose > 0) {
            const newClose = prevClose * (1 + rate / 100);
            setClosePrice(fmt(newClose));
            setHighPrice((prev) => {
                const h = parseFloat(prev);
                return isNaN(h) ? fmt(newClose) : fmt(Math.max(h, newClose));
            });
            setLowPrice((prev) => {
                const l = parseFloat(prev);
                return isNaN(l) ? fmt(newClose) : fmt(Math.min(l, newClose));
            });
        }
    };

    function validate() {
        const errs = {};
        const open  = parseFloat(openPrice);
        const high  = parseFloat(highPrice);
        const low   = parseFloat(lowPrice);
        const close = parseFloat(closePrice);
        const vol   = parseFloat(totalVolume);
        const rate  = parseFloat(priceChangeRate);
        const rtol  = parseFloat(rateTolerance);
        const vtol  = parseFloat(volTolerance);

        if (isNaN(open)  || open  <= 0) errs.openPrice       = 'open_price must be > 0';
        if (isNaN(close) || close <= 0) errs.closePrice      = 'close_price must be > 0';
        if (isNaN(vol)   || vol   <  0) errs.totalVolume     = 'total_volume must be >= 0';
        if (isNaN(rate)  || rate  <= -100) errs.priceChangeRate = 'price_change_rate must be > -100';
        if (isNaN(rtol)  || rtol  <= 0) errs.rateTolerance   = 'rate_tolerance must be > 0';
        if (isNaN(vtol)  || vtol  <= 0) errs.volTolerance    = 'vol_tolerance must be > 0';

        if (!isNaN(high) && !isNaN(open) && !isNaN(close)) {
            if (high < open || high < close) errs.highPrice = 'high_price must be >= open_price and >= close_price';
        }
        if (!isNaN(low) && !isNaN(open) && !isNaN(close)) {
            if (low > open || low > close) errs.lowPrice = 'low_price must be <= open_price and <= close_price';
        }
        if (!isNaN(high) && !isNaN(low) && high < low) {
            errs.highPrice = 'high_price must be >= low_price';
        }
        return errs;
    }

    function handleSearch() {
        if (cooldownTimeLeft > 0) return;
        const errs = validate();
        if (Object.keys(errs).length > 0) { setErrors(errs); return; }
        setErrors({});
        onSearch({
            symbol:    doubleClickData.symbol,
            timeframe,
            conditions: {
                open_price:        parseFloat(openPrice),
                high_price:        parseFloat(highPrice),
                low_price:         parseFloat(lowPrice),
                close_price:       parseFloat(closePrice),
                total_volume:      parseFloat(totalVolume),
                price_change_rate: parseFloat(priceChangeRate),
                rate_tolerance:    parseFloat(rateTolerance),
                vol_tolerance:     parseFloat(volTolerance),
                use_rate_filter:   useRateFilter,
                use_vol_filter:    useVolFilter,
            },
        });
    }

    const dimIf = (active) => active ? '' : 'analysis-signal-popup--dim';

    return (
        <div className="analysis-modal-backdrop analysis-modal-backdrop--signal">
            <div className="analysis-signal-popup">
                <div className="analysis-signal-popup__title">유사한 거래 패턴 검색</div>

                {/* 등락율 + 허용범위 */}
                <div className="analysis-signal-popup__row analysis-signal-popup__row--top">
                    <input type="checkbox" checked={useRateFilter}
                        className="analysis-signal-popup__check"
                        onChange={(e) => { if (!e.target.checked && !useVolFilter) return; setUseRateFilter(e.target.checked); }} />
                    <span className={`analysis-signal-popup__label ${dimIf(useRateFilter)}`}>등락율</span>
                    <input type="number" step="0.01"
                        className={`analysis-input analysis-signal-popup__field ${dimIf(useRateFilter)}`}
                        disabled={!useRateFilter}
                        value={priceChangeRate}
                        onChange={(e) => handleRateChange(e.target.value)} />
                    <span className="analysis-signal-popup__unit">%  ±</span>
                    <input type="number" step="0.01" min="0"
                        className={`analysis-input analysis-signal-popup__field analysis-signal-popup__field--tol ${dimIf(useRateFilter)}`}
                        disabled={!useRateFilter}
                        value={rateTolerance}
                        onChange={(e) => setRateTolerance(e.target.value)} />
                    <span className="analysis-signal-popup__unit">%p</span>
                </div>

                {/* OHLC */}
                {[
                    ['Open',  openPrice,  setOpenPrice],
                    ['High',  highPrice,  setHighPrice],
                    ['Low',   lowPrice,   setLowPrice],
                    ['Close', closePrice, setClosePrice],
                ].map(([label, val, setter]) => (
                    <div key={label} className="analysis-signal-popup__row analysis-signal-popup__row--ohlc">
                        <span className="analysis-signal-popup__label">{label}</span>
                        <input type="number" step="0.01" min="0"
                            className="analysis-input analysis-signal-popup__field"
                            value={val}
                            onChange={(e) => setter(e.target.value)} />
                    </div>
                ))}

                {/* 거래량 + 허용범위 */}
                <div className="analysis-signal-popup__row analysis-signal-popup__row--volume">
                    <input type="checkbox" checked={useVolFilter}
                        className="analysis-signal-popup__check"
                        onChange={(e) => { if (!e.target.checked && !useRateFilter) return; setUseVolFilter(e.target.checked); }} />
                    <span className={`analysis-signal-popup__label ${dimIf(useVolFilter)}`}>거래량</span>
                    <input type="number" step="1" min="0"
                        className={`analysis-input analysis-signal-popup__field ${dimIf(useVolFilter)}`}
                        disabled={!useVolFilter}
                        value={totalVolume}
                        onChange={(e) => setTotalVolume(e.target.value)} />
                    <span className="analysis-signal-popup__unit">±</span>
                    <input type="number" step="1" min="0"
                        className={`analysis-input analysis-signal-popup__field analysis-signal-popup__field--tol ${dimIf(useVolFilter)}`}
                        disabled={!useVolFilter}
                        value={volTolerance}
                        onChange={(e) => setVolTolerance(e.target.value)} />
                    <span className="analysis-signal-popup__unit">%</span>
                </div>

                {/* 오류 메시지 */}
                {Object.values(errors).length > 0 && (
                    <div className="analysis-signal-popup__errors">
                        {Object.values(errors).map((msg) => (
                            <div key={msg} className="analysis-signal-popup__error">{msg}</div>
                        ))}
                    </div>
                )}

                {/* 쿨다운 */}
                {cooldownTimeLeft > 0 && (
                    <div className="analysis-signal-popup__cooldown">
                        {cooldownTimeLeft}초 뒤 다시 시도 가능합니다
                    </div>
                )}

                {/* 버튼 */}
                <div className="analysis-signal-popup__actions">
                    <button onClick={onClose} className="analysis-signal-popup__btn analysis-signal-popup__btn--cancel">
                        취소
                    </button>
                    <button onClick={handleSearch}
                        disabled={cooldownTimeLeft > 0}
                        className="analysis-signal-popup__btn analysis-signal-popup__btn--submit">
                        조회
                    </button>
                </div>
            </div>
        </div>
    );
}
