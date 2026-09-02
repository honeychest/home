import api from '@/api/apiClient.js';

export function fetchAutoTradeSnapshot() {
    return api.get('/api/admin/test/binance/debug/snapshot');
}
