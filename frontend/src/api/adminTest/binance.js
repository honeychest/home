import api from '@/api/apiClient.js';

export function fetchAutoTradeSnapshot() {
    return api.get('/api/admin/test/binance/debug/snapshot');
}

export function fetchAutoTradeAnalysis() {
    return api.get('/api/admin/test/binance/debug/analysis');
}

export function refreshAutoTradeAnalysis() {
    return api.post('/api/admin/test/binance/debug/analysis/refresh');
}

export function askAutoTradeAnalysis(question, recentTurns) {
    return api.post('/api/admin/test/binance/debug/analysis/ask', {
        question,
        recentTurns,
    });
}
