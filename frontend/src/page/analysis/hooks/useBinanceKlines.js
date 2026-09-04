// [AGENT] T4-ANALYSIS: 바이낸스 선물(FUTURES) Kline(OHLCV) + 백엔드 delta 병합 조회
// 실시간 WS(백엔드 선물 체결 집계)와 가격 기준을 맞추기 위해 현물(api/v3)이 아닌 선물(fapi/v1)을 사용
// 429 응답 시: Retry-After 파싱 후 1회 자동 재시도
// 1m: 하루(1440분)를 2회 요청으로 분할 처리 (limit=720)
// 5m/15m: 하루치 봉을 1회 요청으로 처리 (limit < 1000)
import apiClient from '@/api/apiClient.js';
import externalClient from '@/api/externalClient.js';
import { normalizeBinanceSymbol } from '../model/analysisPageModel.js';

const BINANCE_KLINE_URL  = 'https://fapi.binance.com/fapi/v1/klines';
const LIMIT_1M = 720; // 1일 1440분 → 2회 분할
const LIMIT_BY_INTERVAL = {
  '5m':  288, // 1일 288봉 → 1회
  '15m': 96,  // 1일 96봉 → 1회
};

function dateToMs(dateStr) {
  return new Date(dateStr + 'T00:00:00Z').getTime();
}

function dateRangeDays(startDateStr, endDateStr) {
  const days = [];
  const cur  = new Date(startDateStr + 'T00:00:00Z');
  const end  = new Date(endDateStr   + 'T00:00:00Z');
  while (cur <= end) {
    days.push(cur.toISOString().slice(0, 10));
    cur.setUTCDate(cur.getUTCDate() + 1);
  }
  return days;
}

async function fetchKlineChunk(symbolUsdt, interval, startMs, endMs, limit) {
  const url = `${BINANCE_KLINE_URL}?symbol=${symbolUsdt}&interval=${interval}&startTime=${startMs}&endTime=${endMs - 1}&limit=${limit}`;

  const doFetch = async () => {
    try {
      const res = await externalClient.get(url);
      return res.data;
    } catch (error) {
      if (error.response?.status === 429) {
        const retryAfter = Number(error.response.headers['retry-after'] ?? '5');
        await new Promise((r) => setTimeout(r, retryAfter * 1000));
        const retry = await externalClient.get(url);
        return retry.data;
      }
      const err = new Error(`바이낸스 API 오류: ${error.response?.statusText ?? error.message}`);
      err.status = error.response?.status;
      throw err;
    }
  };

  const raw = await doFetch();
  return raw.map((r) => ({
    time:   r[0],
    open:   Number(r[1]),
    high:   Number(r[2]),
    low:    Number(r[3]),
    close:  Number(r[4]),
    volume: Number(r[5]),
    delta:  0,
  }));
}

async function fetchOneDayKlines(symbolUsdt, dateStr, interval) {
  const startMs = dateToMs(dateStr);
  const endMs   = startMs + 86_400_000;

  if (interval !== '1m') {
    return fetchKlineChunk(symbolUsdt, interval, startMs, endMs, LIMIT_BY_INTERVAL[interval] ?? LIMIT_BY_INTERVAL['5m']);
  }

  // 1m: 하루 1440봉 → 12시간씩 2회 분할
  const midMs = startMs + LIMIT_1M * 60_000;
  const [firstHalf, secondHalf] = await Promise.all([
    fetchKlineChunk(symbolUsdt, '1m', startMs, midMs, LIMIT_1M),
    fetchKlineChunk(symbolUsdt, '1m', midMs,   endMs, LIMIT_1M),
  ]);
  return [...firstHalf, ...secondHalf];
}

async function fetchDelta(symbol, startMs, endMs, interval) {
  try {
    const res = await apiClient.get('/api/analysis/delta', {
      params: { symbol: normalizeBinanceSymbol(symbol), startMs, endMs, interval },
    });
    return res.data; // [{ timeMs, delta }]
  } catch (e) {
    console.warn('[useBinanceKlines] delta API 실패, delta=0으로 진행', e);
    return [];
  }
}

/**
 * 바이낸스 Kline + 백엔드 delta 병합
 * @param {'BTCUSDT'|'ENAUSDT'} symbol
 * @param {string} startDateStr 'YYYY-MM-DD'
 * @param {string} endDateStr   'YYYY-MM-DD'
 * @param {'1m'|'5m'|'15m'} interval
 * @returns {Promise<kline[]>} { time(ms), open, high, low, close, volume, delta }
 */
export async function fetchKlines(symbol, startDateStr, endDateStr, interval = '1m') {
  const symbolUsdt = normalizeBinanceSymbol(symbol);
  const days       = dateRangeDays(startDateStr, endDateStr);
  const startMs    = dateToMs(startDateStr);
  const endMs      = dateToMs(endDateStr) + 86_400_000;

  const [klinesByDay, deltaList] = await Promise.all([
    Promise.all(days.map((d) => fetchOneDayKlines(symbolUsdt, d, interval))),
    fetchDelta(symbol, startMs, endMs, interval),
  ]);

  const klines = klinesByDay
    .flat()
    .sort((a, b) => a.time - b.time)
    .filter((c, i, arr) => i === 0 || c.time !== arr[i - 1].time);

  if (deltaList.length > 0) {
    const deltaMap = new Map(deltaList.map((d) => [d.timeMs, d]));
    klines.forEach((c) => {
      const d = deltaMap.get(c.time);
      if (d !== undefined) {
        c.delta  = d.delta;
        c.volume = d.volume;
      }
    });
  }

  return klines;
}
