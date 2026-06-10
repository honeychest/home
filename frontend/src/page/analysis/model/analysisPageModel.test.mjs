import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildAnalysisSearchWindow,
  buildAnalysisSearchRequest,
  deltaHighlightThreshold,
  emptyConditionTree,
  mapSearchTimesToIndices,
  previousUtcDateStr,
} from './analysisPageModel.js';

test('buildAnalysisSearchWindow spans whole UTC end date', () => {
  assert.deepEqual(buildAnalysisSearchWindow('2026-05-10', '2026-05-12'), {
    fromMs: Date.parse('2026-05-10T00:00:00Z'),
    toMs: Date.parse('2026-05-13T00:00:00Z'),
  });
});

test('buildAnalysisSearchRequest appends search window without mutating request body', () => {
  const requestBody = { symbol: 'BTC', timeframe: '5m' };

  assert.deepEqual(buildAnalysisSearchRequest(requestBody, '2026-05-10', '2026-05-10'), {
    symbol: 'BTC',
    timeframe: '5m',
    fromMs: Date.parse('2026-05-10T00:00:00Z'),
    toMs: Date.parse('2026-05-11T00:00:00Z'),
  });
  assert.deepEqual(requestBody, { symbol: 'BTC', timeframe: '5m' });
});

test('mapSearchTimesToIndices keeps only backend matches present in loaded chart data', () => {
  const klineData = [
    { time: 1000 },
    { time: 2000 },
    { time: 3000 },
  ];

  assert.deepEqual(mapSearchTimesToIndices([3000, 9999, 1000], klineData), [2, 0]);
  assert.deepEqual(mapSearchTimesToIndices(null, klineData), []);
});

test('previousUtcDateStr returns date before current UTC day', () => {
  assert.equal(previousUtcDateStr('2026-03-01'), '2026-02-28');
});

test('emptyConditionTree creates default analysis condition state', () => {
  assert.deepEqual(emptyConditionTree(), { groups: [], groupOperator: 'OR', palette: 'MID' });
});

test('deltaHighlightThreshold uses a single user threshold for every timeframe', () => {
  assert.equal(deltaHighlightThreshold('30'), 30);
  assert.equal(deltaHighlightThreshold(75), 75);
  assert.equal(deltaHighlightThreshold(''), 10);
  assert.equal(deltaHighlightThreshold('bad'), 10);
  assert.equal(deltaHighlightThreshold(0), 10);
});
