// [AGENT] Signal Dashboard MainCore — 중앙 컨테이너 (TradingViewWidget + MiniChart)
// [AGENT] TASK-09/10/12: symbol, divergenceData, onCandleTime props 추가
// [AGENT] T4-TASK: 상단 슬롯 TradingViewWidget 교체, MiniChartPlaceholder에 longEnergy/shortEnergy 전달
import TradingViewWidget from './TradingViewWidget.jsx';
import MiniChartPlaceholder from './MiniChartPlaceholder.jsx';
import DivergenceBar from './DivergenceBar.jsx';
import EnergyDiff from './EnergyDiff.jsx';

export default function MainCore({ symbol, longEnergy, shortEnergy, longLiqTotal, shortLiqTotal, fundingRate, oiData = [], candleHistory = [], candleType, timeRange, displayCount, rangeMs, onCandleTime, onCandleUpdate }) {
    const getFundingBorder = () => {
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
            boxShadow: abs > 0.05 ? '0 0 12px rgba(240,192,64,0.2)' : 'none',
            animation: shouldBlink ? 'fundingBorderBlink 4s ease-in-out infinite' : 'none',
        };
    };

    return (
        <div
            style={{
                height: '100%',
                backgroundColor: 'var(--black-panel-bg)',
                borderRadius: '10px',
                padding: '10px',
                display: 'flex',
                flexDirection: 'column',
                gap: '12px',
                ...getFundingBorder(),
            }}
        >
            <style>{`
                @keyframes fundingBorderBlink {
                    0%, 100% { border-color: rgba(240,192,64,0.5); }
                    50% { border-color: rgba(240,192,64,0.2); }
                }
            `}</style>

            <div style={{ flex: '60%' }}>
                <TradingViewWidget symbol={symbol} />
            </div>

            {/* 아래 MiniChartPlaceholder 와 같은 3등분 — EnergyDiff 가 가운데 Signal 게이지 정중앙 위에 오게 한다 */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '8px', alignItems: 'center', flexShrink: 0 }}>
                <DivergenceBar candleHistory={candleHistory} rangeMs={rangeMs} />
                <EnergyDiff longEnergy={longEnergy} shortEnergy={shortEnergy} longLiqTotal={longLiqTotal} shortLiqTotal={shortLiqTotal} />
            </div>

            <div style={{ flex: '40%' }}>
                <MiniChartPlaceholder oiData={oiData} symbol={symbol} candleHistory={candleHistory} candleType={candleType} timeRange={timeRange} displayCount={displayCount} rangeMs={rangeMs} onCandleTime={onCandleTime} onCandleUpdate={onCandleUpdate} longEnergy={longEnergy} shortEnergy={shortEnergy} />
            </div>
        </div>
    );
}
