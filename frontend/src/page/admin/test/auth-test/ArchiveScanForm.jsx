import styles from '../AuthTestPage.module.css';

export default function ArchiveScanForm({ actions }) {
    const {
        busy, runningAction, handleScanPreview, handleScanRun,
        archiveDisabled: configuredArchiveDisabled,
    } = actions;
    const archiveDisabled = configuredArchiveDisabled ?? true;
    return (
        <>
            <div className={styles.label}>S3 스캔 테스트 (비활성화)</div>
            <div className={styles.desc}>raw_agg_trade 아카이브 스캔은 현재 중단되어 실행되지 않습니다.</div>
            <button className={styles.primaryBtn} onClick={handleScanPreview} disabled={archiveDisabled || busy}>
                {runningAction === 'scanPreview' ? '조회 중…' : 'S3 파일 미리보기'}
            </button>
            <button
                className={`${styles.primaryBtn} ${styles.warningBtn}`}
                onClick={handleScanRun}
                disabled={archiveDisabled || busy}
            >
                {runningAction === 'scanRun' ? '스캔 중…' : 'DB 초기화 스캔 (1회용)'}
            </button>
        </>
    );
}
