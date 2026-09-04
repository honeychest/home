const DAY_MS = 86_400_000;

export function normalizeBinanceSymbol(symbol) {
  const normalized = String(symbol ?? '').trim().toUpperCase();
  if (!normalized) throw new Error('symbol is required');
  return normalized.endsWith('USDT') ? normalized : `${normalized}USDT`;
}

export function todayStr() {
  return new Date().toISOString().slice(0, 10);
}

export function fiveDaysAgoStr() {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() - 5);
  return d.toISOString().slice(0, 10);
}

export function emptyConditionTree() {
  return { groups: [], groupOperator: 'OR', palette: 'MID' };
}

export function previousUtcDateStr(dateStr) {
  const prev = new Date(`${dateStr}T00:00:00Z`);
  prev.setUTCDate(prev.getUTCDate() - 1);
  return prev.toISOString().slice(0, 10);
}

export function buildAnalysisSearchWindow(startDate, endDate) {
  const fromMs = Date.parse(`${startDate}T00:00:00Z`);
  const toMs = Date.parse(`${endDate}T00:00:00Z`) + DAY_MS;
  return { fromMs, toMs };
}

export function buildAnalysisSearchRequest(requestBody, startDate, endDate) {
  return {
    ...requestBody,
    ...buildAnalysisSearchWindow(startDate, endDate),
  };
}

export function mapSearchTimesToIndices(times, klineData) {
  if (!Array.isArray(times)) return [];

  const indexByTime = new Map(klineData.map((candle, index) => [candle.time, index]));
  return times
    .map((time) => indexByTime.get(time))
    .filter((index) => index !== undefined);
}

export function deltaHighlightThreshold(value) {
  const threshold = Number(value);
  return Number.isFinite(threshold) && threshold > 0 ? threshold : 10;
}
