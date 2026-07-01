// 헬스 체크 보드 API 호출 모음. r.data 반환, 에러는 그대로 throw.
import apiClient from '@/api/apiClient.js';

export const getHealthChecks = () =>
    apiClient.get('/api/admin/health/checks').then(r => r.data);

export const getHealthEvents = () =>
    apiClient.get('/api/admin/health/events').then(r => r.data);
