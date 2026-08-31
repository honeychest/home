import { useEffect, useRef, useState } from 'react';
import * as adminApi from '../api/adminApi';
import { ID_BASED, NO_MARKET } from '../constants';
import { datetimeLocalToMs } from '../utils';

// 수동 수집 + Job 폴링.
export default function useManualCollect() {
    const [cType, setCType] = useState('KLINE_1M');
    const [cSymbol, setCSymbol] = useState('BTCUSDT');
    const [cMarket, setCMarket] = useState('SPOT');
    const [cFrom, setCFrom] = useState('');
    const [cTo, setCTo] = useState('');
    const [collectLoading, setCollectLoading] = useState(false);
    const [collectError, setCollectError] = useState(null);
    const [jobs, setJobs] = useState([]);
    const pollRef = useRef(null);

    const isIdBased = ID_BASED.has(cType);
    const isKline = cType === 'KLINE_1M';
    const noMarket = NO_MARKET.has(cType);

    const handleCollect = async () => {
        setCollectError(null);
        setCollectLoading(true);
        try {
            const body = { type: cType, symbol: cSymbol, marketType: cMarket };
            if (ID_BASED.has(cType)) {
                if (cFrom) body.fromId = Number(cFrom);
                if (cTo)   body.toId   = Number(cTo);
            } else {
                if (isKline && !cTo) {
                    throw new Error('KLINE_1M은 From과 To가 모두 필요합니다');
                }
                if (cFrom) body.fromMs = datetimeLocalToMs(cFrom);
                if (cTo)   body.toMs   = datetimeLocalToMs(cTo);
            }
            await adminApi.postBackfillCollect(body);
            const jobs2 = await adminApi.getBackfillJobs();
            setJobs(jobs2);
        } catch (e) {
            setCollectError(e.response?.data?.error ?? '수집 요청 실패');
        } finally {
            setCollectLoading(false);
        }
    };

    // RUNNING job 있으면 3초 폴링
    useEffect(() => {
        const hasRunning = jobs.some(j => j.status === 'RUNNING');
        if (hasRunning && !pollRef.current) {
            pollRef.current = setInterval(() => {
                adminApi.getBackfillJobs().then(setJobs).catch(() => {});
            }, 3000);
        } else if (!hasRunning && pollRef.current) {
            clearInterval(pollRef.current);
            pollRef.current = null;
        }
    }, [jobs]);

    // 언마운트 시 폴링 정리
    useEffect(() => () => {
        if (pollRef.current) clearInterval(pollRef.current);
    }, []);

    return {
        cType, setCType,
        cSymbol, setCSymbol,
        cMarket, setCMarket,
        cFrom, setCFrom,
        cTo, setCTo,
        collectLoading, collectError,
        jobs, setJobs,
        handleCollect,
        isIdBased, isKline, noMarket,
    };
}
