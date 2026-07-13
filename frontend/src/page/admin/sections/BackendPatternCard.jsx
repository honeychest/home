import styles from '../AdminPage.module.css';

export default function BackendPatternCard({ onOpen }) {
    return (
        <div className={styles.card}>
            <div className={styles.titleRow}>
                <div>
                    <div className={styles.title}>백엔드 패턴</div>
                    <div className={styles.subtitle}>구조 부품 카탈로그</div>
                </div>
                <button
                    type="button"
                    className={`${styles.btn} ${styles.btnActive} ${styles.pushRight}`}
                    onClick={onOpen}
                >
                    패턴 보기
                </button>
            </div>
            <div className={styles.desc}>
                예: "재고 알림은 pattern-queue-worker 로" 라고 지시.
            </div>
        </div>
    );
}
