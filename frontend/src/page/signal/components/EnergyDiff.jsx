// [AGENT] Signal Dashboard EnergyDiff — 좌우 합산 차이(에너지 / 청산) 한 줄 표시
// DivergenceBar 와 같은 줄의 가운데 칸에 놓인다(MainCore 가 3등분 그리드로 배치) — 아래 Signal 게이지 정중앙 위.
// 별도 컴포넌트인 이유: DivergenceBar 는 다이버전스가 없으면 visibility:hidden 이 되지만 이 값은 상시로 봐야 한다.
// 청산은 의미가 반대다 — 롱 청산이 크다는 건 롱이 터졌다는 뜻이라 시장은 숏 우세로 표기한다(invert).
import { formatWithComma } from '../../../shared/lib/utils.ts';

function buildDiff(longValue, shortValue, invert) {
    const l = Number(longValue) || 0;
    const s = Number(shortValue) || 0;
    if (l + s <= 0) return null;

    const diff = l - s;
    const longDominant = invert ? diff < 0 : diff > 0;

    return {
        longDominant,
        // 부호는 시장 우세 방향 — 롱 우세 +, 숏 우세 -. 색과 부호가 같은 방향을 가리킨다.
        amount: (longDominant ? '+$' : '-$') + formatWithComma(Math.floor(Math.abs(diff))),
    };
}

function DiffItem({ label, diff }) {
    if (!diff) return null;
    return (
        <span style={{ display: 'inline-flex', alignItems: 'baseline', gap: '8px' }}>
            <span style={{ fontSize: '12px', fontWeight: '500', color: 'var(--black-text-muted)' }}>{label}</span>
            <span style={{
                fontSize: '16px',
                fontWeight: '700',
                color: diff.longDominant ? 'var(--black-long)' : 'var(--black-short)',
            }}>
                {diff.amount}
            </span>
        </span>
    );
}

export default function EnergyDiff({ longEnergy, shortEnergy, longLiqTotal, shortLiqTotal }) {
    const energyDiff = buildDiff(longEnergy, shortEnergy, false);
    const liqDiff    = buildDiff(longLiqTotal, shortLiqTotal, true);

    if (!energyDiff && !liqDiff) return null;

    // 라벨은 "어느 쪽이 청산됐나", 부호·색은 "그래서 시장이 어느 쪽 우세인가" — 둘은 반대 방향을 가리킨다.
    const liqLabel = liqDiff?.longDominant ? '숏청산' : '롱청산';

    return (
        <div
            style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '12px',
                letterSpacing: '0.3px',
                fontFamily: "'Pretendard', sans-serif",
                whiteSpace: 'nowrap',
            }}
        >
            <DiffItem label="에너지" diff={energyDiff} />
            {energyDiff && liqDiff && (
                <span style={{ width: '1px', height: '16px', backgroundColor: 'var(--black-border-subtle)' }} />
            )}
            <DiffItem label={liqLabel} diff={liqDiff} />
        </div>
    );
}
