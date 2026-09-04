// [AGENT] Signal Dashboard TopBar — 심볼탭 + 시간 선택 + Funding Rate
// [AGENT] compact=true 시 모바일용 select 렌더
// [AGENT] TASK-11: canEdit/params/onParamsSave props 추가, ⚙ 드롭다운 ParamPanel 통합
import { useState, useRef, useEffect } from 'react';
import ParamPanel from './ParamPanel.jsx';
import { msToDatetimeLocal } from './model/datetimeLocal.js';

const TEMPLATE_SELECT_STYLE = {
    backgroundColor: 'var(--black-border)',
    border: '1px solid var(--black-border-strong)',
    borderRadius: '6px',
    color: 'var(--black-text-primary)',
    fontSize: '11px',
    fontWeight: '500',
    padding: '3px 8px',
    cursor: 'pointer',
    outline: 'none',
    fontFamily: "'Pretendard', sans-serif",
    maxWidth: '220px',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
};

function CustomHistoryStartInput({ value, maxDate, onChange, inputStyle, wrapperStyle }) {
    const datePart = value ? value.slice(0, 10) : '';
    const hourPart = value ? value.slice(11, 13) : '';

    const emit = (date, hour) => {
        if (!date) {
            onChange('');
            return;
        }
        const h = (hour || '0').padStart(2, '0');
        onChange(`${date}T${h}:00`);
    };

    return (
        <div style={{ display: 'flex', gap: '4px', alignItems: 'center', ...wrapperStyle }}>
            <input
                type="date"
                value={datePart}
                max={maxDate}
                onChange={(e) => emit(e.target.value, hourPart)}
                style={inputStyle}
            />
            <input
                type="text"
                inputMode="numeric"
                pattern="[0-9]*"
                maxLength={2}
                value={hourPart}
                onChange={(e) => {
                    const digits = e.target.value.replace(/\D/g, '').slice(0, 2);
                    const clamped = digits === '' ? '' : String(Math.min(23, Number(digits)));
                    emit(datePart, clamped);
                }}
                placeholder="시"
                style={{ ...inputStyle, flex: '0 0 44px', width: '44px' }}
            />
            <span style={{ color: 'var(--black-text-muted)', fontSize: '11px' }}>시</span>
        </div>
    );
}

export default function TopBar({
    symbol,
    onSymbolChange,
    timeRange,
    onTimeRangeChange,
    fundingRate,
    timeRanges = [],
    compact = false,
    canEdit = false,
    params = null,
    onParamsSave,
    templates = [],
    selectedTemplateId = null,
    onTemplateChange,
    customHistoryEnabled = false,
    onCustomHistoryEnabledChange,
    customHistoryStart = '',
    onCustomHistoryStartChange,
}) {
    const [panelOpen, setPanelOpen] = useState(false);
    const gearContainerRef = useRef(null);

    useEffect(() => {
        if (!panelOpen) return;
        const handleKey = (e) => { if (e.key === 'Escape') setPanelOpen(false); };
        const handleClick = (e) => {
            const path = e.composedPath ? e.composedPath() : [];
            if (gearContainerRef.current && !path.includes(gearContainerRef.current)) {
                setPanelOpen(false);
            }
        };
        document.addEventListener('keydown', handleKey);
        document.addEventListener('mousedown', handleClick);
        return () => {
            document.removeEventListener('keydown', handleKey);
            document.removeEventListener('mousedown', handleClick);
        };
    }, [panelOpen]);
    const getFundingStyle = () => {
        if (!fundingRate) return {};
        const abs = Math.abs(fundingRate);
        let borderColor = 'rgba(240,192,64,0.15)';
        let shouldBlink = false;

        if (abs > 0.05) {
            borderColor = 'rgba(240,192,64,0.5)';
            shouldBlink = true;
        } else if (abs > 0.01) {
            borderColor = 'rgba(240,192,64,0.3)';
        }

        return {
            border: `1px solid ${borderColor}`,
            animation: shouldBlink ? 'fundingBlink 4s ease-in-out infinite' : 'none',
        };
    };

    const selectStyle = {
        backgroundColor: 'var(--black-border)',
        border: '1px solid var(--black-border-strong)',
        borderRadius: '6px',
        color: 'var(--black-text-primary)',
        fontSize: '12px',
        fontWeight: '600',
        padding: '4px 8px',
        cursor: 'pointer',
        outline: 'none',
        fontFamily: "'Pretendard', sans-serif",
    };

    const datetimeInputStyle = {
        ...selectStyle,
        fontSize: '11px',
        fontWeight: '500',
        padding: '3px 6px',
    };
    const currentDatetimeLocal = msToDatetimeLocal(Date.now());
    const currentDateOnly = currentDatetimeLocal.slice(0, 10);

    return (
        <>
            <div
                style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    height: '44px',
                    backgroundColor: 'var(--black-topbar-bg)',
                    borderRadius: '10px',
                    padding: '0 16px',
                    fontFamily: "'Pretendard', sans-serif",
                }}
            >
            <style>{`
                @keyframes fundingBlink {
                    0%, 100% { opacity: 1; }
                    50% { opacity: 0.5; }
                }
            `}</style>

            {compact ? (
                <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                    <select
                        value={symbol}
                        onChange={(e) => onSymbolChange(e.target.value)}
                        style={selectStyle}
                    >
                        {['BTCUSDT', 'ENAUSDT'].map((sym) => (
                            <option key={sym} value={sym} style={{ backgroundColor: 'var(--black-panel-bg)', color: 'var(--black-text-primary)' }}>{sym.replace('USDT', '')}</option>
                        ))}
                    </select>
                    <select
                        value={timeRange}
                        onChange={(e) => {
                            if (customHistoryEnabled) onCustomHistoryEnabledChange(false);
                            onTimeRangeChange(e.target.value);
                        }}
                        style={selectStyle}
                    >
                        {timeRanges.map(({ value, label }) => (
                            <option key={value} value={value} style={{ backgroundColor: 'var(--black-panel-bg)', color: 'var(--black-text-primary)' }}>{label}</option>
                        ))}
                    </select>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '3px', color: 'var(--black-text-muted)', fontSize: '10px', whiteSpace: 'nowrap' }}>
                        <input
                            type="checkbox"
                            checked={customHistoryEnabled}
                            onChange={(e) => onCustomHistoryEnabledChange(e.target.checked)}
                        />
                        직접
                    </label>
                </div>
            ) : (
                <>
                    <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                        {['BTCUSDT', 'ENAUSDT'].map((sym) => (
                            <button
                                key={sym}
                                onClick={() => onSymbolChange(sym)}
                                style={{
                                    padding: '6px 14px',
                                    borderRadius: '4px',
                                    border: symbol === sym ? '1px solid rgba(255,255,255,0.2)' : `1px solid var(--black-border)`,
                                    backgroundColor: symbol === sym ? 'rgba(255,255,255,0.08)' : 'transparent',
                                    color: symbol === sym ? 'var(--black-text-primary)' : 'var(--black-text-muted)',
                                    fontSize: '12px',
                                    fontWeight: '600',
                                    cursor: 'pointer',
                                    transition: 'all 0.2s',
                                }}
                            >
                                {sym.replace('USDT', '')}
                            </button>
                        ))}
                        {templates.length > 0 && (
                            <select
                                value={selectedTemplateId ?? ''}
                                onChange={(e) => {
                                    const val = e.target.value;
                                    if (!val) return;
                                    onTemplateChange && onTemplateChange(Number(val));
                                }}
                                style={TEMPLATE_SELECT_STYLE}
                            >
                                <option value="" disabled style={{ backgroundColor: 'var(--black-panel-bg)', color: 'var(--black-text-muted)' }}>
                                    분석 템플릿 선택
                                </option>
                                {templates.map((t) => (
                                    <option
                                        key={t.id}
                                        value={t.id}
                                        style={{ backgroundColor: 'var(--black-panel-bg)', color: 'var(--black-text-primary)' }}
                                    >
                                        {t.name}
                                    </option>
                                ))}
                            </select>
                        )}
                    </div>
                    <div style={{ display: 'flex', gap: '6px', alignItems: 'center' }}>
                        {timeRanges.map(({ value, label }) => (
                            <button
                                key={value}
                                onClick={() => {
                                    if (customHistoryEnabled) onCustomHistoryEnabledChange(false);
                                    onTimeRangeChange(value);
                                }}
                                style={{
                                    padding: '4px 10px',
                                    borderRadius: '3px',
                                    border: timeRange === value ? '1px solid rgba(255,255,255,0.15)' : 'none',
                                    backgroundColor: timeRange === value ? 'var(--black-border)' : 'transparent',
                                    color: timeRange === value ? 'var(--black-text-primary)' : 'var(--black-text-muted)',
                                    fontSize: '11px',
                                    cursor: 'pointer',
                                    transition: 'all 0.15s',
                                }}
                            >
                                {label}
                            </button>
                        ))}
                        <label style={{ display: 'flex', alignItems: 'center', gap: '4px', color: 'var(--black-text-muted)', fontSize: '11px', whiteSpace: 'nowrap', marginLeft: '4px' }}>
                            <input
                                type="checkbox"
                                checked={customHistoryEnabled}
                                onChange={(e) => onCustomHistoryEnabledChange(e.target.checked)}
                            />
                            시작 시각 지정
                        </label>
                        {customHistoryEnabled && (
                            <CustomHistoryStartInput
                                value={customHistoryStart}
                                maxDate={currentDateOnly}
                                onChange={onCustomHistoryStartChange}
                                inputStyle={datetimeInputStyle}
                            />
                        )}
                    </div>
                </>
            )}

            <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
                <div
                    style={{
                        padding: '4px 10px',
                        borderRadius: '4px',
                        fontSize: '11px',
                        fontWeight: '600',
                        color: fundingRate !== null ? (fundingRate >= 0 ? 'var(--black-long)' : 'var(--black-short)') : 'transparent',
                        visibility: fundingRate !== null ? 'visible' : 'hidden',
                        ...getFundingStyle(),
                    }}
                >
                    {fundingRate !== null ? `${fundingRate >= 0 ? '+' : ''}${(fundingRate * 100).toFixed(3)}%` : '0.000%'}
                </div>
                {canEdit && (
                    <div ref={gearContainerRef} style={{ position: 'relative' }}>
                        <button
                            onClick={() => setPanelOpen((v) => !v)}
                            style={{
                                background: panelOpen ? 'rgba(255,255,255,0.08)' : 'transparent',
                                border: `1px solid var(--black-border-strong)`,
                                borderRadius: '5px',
                                color: 'var(--black-text-secondary)',
                                fontSize: '14px',
                                cursor: 'pointer',
                                padding: '3px 8px',
                                lineHeight: 1,
                            }}
                            title="파라미터 설정"
                        >
                            ⚙
                        </button>
                        {panelOpen && (
                            <ParamPanel
                                params={params}
                                onParamsSave={onParamsSave}
                            />
                        )}
                    </div>
                )}
            </div>
            </div>
            {compact && customHistoryEnabled && (
                <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', minHeight: '36px', backgroundColor: 'var(--black-topbar-bg)', borderRadius: '8px', padding: '0 10px' }}>
                    <CustomHistoryStartInput
                        value={customHistoryStart}
                        maxDate={currentDateOnly}
                        onChange={onCustomHistoryStartChange}
                        inputStyle={{ ...datetimeInputStyle, flex: 1 }}
                        wrapperStyle={{ width: '100%' }}
                    />
                </div>
            )}
        </>
    );
}
