// [AGENT] 역할: 데이터 누락 구간 조회 + 수동 수집/보정 + 방문자 이력 어드민 페이지 | 연관파일: DataGapAdminController.java, ManualBackfillController.java, MonitorApiController.java
// IP 인증: 마운트 시 /api/admin/data-gap/access 체크 → canAccess false면 접근 거부
// 갭 조회: /api/admin/data-gap/check?type=xxx → 결과 테이블, 체크박스로 행 선택 → [선택 수집] 버튼
// 수동 수집: /api/admin/backfill/collect → Job 폴링 | flat/outlier 보정: /api/admin/backfill/*-correction
// Outlier: 전용 symbol/market select 값으로 진단/보정 API 호출, 실행 시 갭 조회 결과와 분리
import { useEffect, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import Layout from '../../shared/ui/layout/Layout.jsx';
import { useAdminAuth } from '@/shared/auth/useAdminAuth.js';
import { shouldRedirectToAdminLogin } from './adminAccessPolicy.js';
import styles from './AdminPage.module.css';
import '../../styles/themes/monitor-teal.css';
import useFeatureFlags from './hooks/useFeatureFlags';
import useManualCollect from './hooks/useManualCollect';
import useDataGap from './hooks/useDataGap';
import useDataHealth from './hooks/useDataHealth';
import useOutlier from './hooks/useOutlier';
import useRollup from './hooks/useRollup';
import useMyIp from './hooks/useMyIp';
import useVisitorLogs from './hooks/useVisitorLogs';
import useAllowedIps from './hooks/useAllowedIps';
import FeatureFlagsCard from './sections/FeatureFlagsCard';
import DataQualityCard from './sections/DataQualityCard';
import DataGapCard from './sections/DataGapCard';
import RollupCard from './sections/RollupCard';
import ManualCollectCard from './sections/ManualCollectCard';
import VisitorLogsCard from './sections/VisitorLogsCard';
import MyIpCard from './sections/MyIpCard';
import AllowedIpsCard from './sections/AllowedIpsCard';
import VisualSampleCard from './sections/VisualSampleCard';
import VisualSampleModal from './sections/VisualSampleModal';
import BackendPatternCard from './sections/BackendPatternCard';
import BackendPatternModal from './sections/BackendPatternModal';

export default function AdminPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const { canAccess, isForbidden } = useAdminAuth();

    const featureFlags = useFeatureFlags();
    const manualCollect = useManualCollect();
    const dataGap = useDataGap({ setJobs: manualCollect.setJobs, defaultSymbol: manualCollect.cSymbol });
    const dataHealth = useDataHealth();
    const outlier = useOutlier({ healthHours: dataHealth.healthHours, resetGapView: dataGap.resetGapView });
    const rollup = useRollup();
    const myIp = useMyIp();
    const visitor = useVisitorLogs();
    const allowed = useAllowedIps({ enabled: featureFlags.flags.monitorAllowedIpManage });

    const [sampleOpen, setSampleOpen] = useState(false);
    const [patternOpen, setPatternOpen] = useState(false);

    useEffect(() => {
        if (shouldRedirectToAdminLogin({ canAccess, isForbidden })) {
            navigate('/admin/login', { replace: true, state: { from: location.pathname } });
        }
    }, [canAccess, isForbidden, navigate, location.pathname]);

    if (canAccess === null) {
        return (
            <Layout footerCenter={['Admin', 'Redis', 'MySQL', 'Backfill']} enableSupport={false}>
                <div className={styles.page}>
                    <div className={styles.card}>
                        <div className={styles.muted}>접근 권한 확인 중...</div>
                    </div>
                </div>
            </Layout>
        );
    }
    if (!canAccess) {
        return (
            <Layout footerCenter={['Admin', 'Redis', 'MySQL', 'Backfill']} enableSupport={false}>
                <div className={styles.page}>
                    <div className={styles.card}>
                        <div className={styles.muted}>로그인 페이지로 이동 중...</div>
                    </div>
                </div>
            </Layout>
        );
    }

    return (
        <Layout footerCenter={['Admin', 'Redis', 'MySQL', 'Backfill']} enableSupport={false}>
            <div className={styles.page}>
                <div className={styles.grid}>
                    <section className={styles.main}>
                        <DataQualityCard dataHealth={dataHealth} outlier={outlier} />
                        <DataGapCard
                            dataGap={dataGap}
                            collectLoading={manualCollect.collectLoading}
                            collectError={manualCollect.collectError}
                        />
                        <RollupCard rollup={rollup} />
                        <ManualCollectCard manualCollect={manualCollect} />
                        <VisitorLogsCard visitor={visitor} />
                    </section>

                    <aside className={styles.sidebar}>
                        <MyIpCard myIp={myIp} />
                        <FeatureFlagsCard
                            flags={featureFlags.flags}
                            flagsLoading={featureFlags.flagsLoading}
                            patchFlags={featureFlags.patchFlags}
                        />
                        <VisualSampleCard onOpen={() => setSampleOpen(true)} />
                        <BackendPatternCard onOpen={() => setPatternOpen(true)} />
                        {featureFlags.flags.monitorAllowedIpManage && <AllowedIpsCard allowed={allowed} />}
                    </aside>
                </div>
            </div>
            <VisualSampleModal open={sampleOpen} onClose={() => setSampleOpen(false)} />
            <BackendPatternModal open={patternOpen} onClose={() => setPatternOpen(false)} />
        </Layout>
    );
}
