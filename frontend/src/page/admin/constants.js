// AdminPage 섹션 전반에서 공유하는 상수 모음.

export const CHECKS = [
    { type: 'RAW_AGG_TRADE', label: 'Raw AggTrade (레거시, 7일)', days: 7,    desc: 'agg_trade_id 연속성 갭 · 최근 7일' },
    { type: 'KLINE_1M',      label: 'Kline 1분봉 (48시간)', days: 2, desc: 'Binance kline candle_time 누락 - 최근 48시간' },
    { type: 'AGG_1M',        label: '1분봉',               days: null, desc: 'candle_time_ms 1분 간격 초과' },
    { type: 'AGG_5M',        label: '5분봉',               days: null, desc: 'candle_time_ms 5분 간격 초과' },
    { type: 'OI',            label: 'Open Interest',      days: null, desc: '10분 이상 공백' },
];

export const HEALTH_HOURS_OPTIONS = [1, 2, 4, 12, 24, 48];

export const OUTLIER_RANGE_OPTIONS = [
    { key: 'current', label: '선택 범위(최대 48시간)', useHealthHours: true },
    { key: 'custom', label: '직접 지정' },
    { key: '48_96', label: '48~96시간 전', fromHours: 96, toHours: 48 },
    { key: '96_120', label: '96~120시간 전', fromHours: 120, toHours: 96 },
    { key: '120_144', label: '120~144시간 전', fromHours: 144, toHours: 120 },
];

export const SYMBOLS = ['BTCUSDT', 'ENAUSDT'];
export const MARKETS = ['SPOT', 'FUTURES'];

// FORCE_ORDER·OI는 marketType 불필요
export const NO_MARKET = new Set(['FORCE_ORDER', 'OI']);

// RAW_AGG_TRADE는 ID 기반, 나머지는 시간 기반
export const ID_BASED = new Set(['RAW_AGG_TRADE']);
