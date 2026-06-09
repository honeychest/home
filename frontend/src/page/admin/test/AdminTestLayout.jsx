// [AGENT] Admin 테스트 레이아웃 — 접근 가드 + 도메인 탭 + Outlet
// 백엔드가 최종 방어선; 여기서는 UX(로딩·거부·로그인 유도)만 처리
import { useEffect } from 'react';
import { NavLink, Outlet, useNavigate, useLocation } from 'react-router-dom';
import Layout from '../../../shared/ui/layout/Layout.jsx';
import { useAdminAuth } from '@/shared/auth/useAdminAuth.js';
import { shouldRedirectToAdminLogin } from '../adminAccessPolicy.js';
import '../../../styles/themes/monitor-teal.css';
import styles from './AdminTestLayout.module.css';

const TEST_TABS = [
    { label: 'Auth', path: 'auth' },
    { label: 'Raw Writer', path: 'raw-writer' },
    { label: 'Chatbot', path: 'chatbot' },
    { label: 'Trade', path: 'trade' },
    { label: 'Monitor', path: 'monitor' },
    { label: 'User', path: 'user' },
];

export default function AdminTestLayout() {
    const navigate = useNavigate();
    const location = useLocation();
    const { canAccess, isForbidden } = useAdminAuth();

    useEffect(() => {
        if (shouldRedirectToAdminLogin({ canAccess, isForbidden })) {
            navigate('/admin/login', { replace: true, state: { from: location.pathname } });
        }
    }, [canAccess, isForbidden, navigate, location.pathname]);

    if (canAccess === null) {
        return (
            <Layout footerCenter={['Admin', 'Test']}>
                <div className={`${styles.layout} ${styles.mutedBox}`}>접근 권한 확인 중...</div>
            </Layout>
        );
    }

    if (!canAccess) {
        return (
            <Layout footerCenter={['Admin', 'Test']}>
                <div className={`${styles.layout} ${styles.mutedBox}`}>로그인 페이지로 이동 중...</div>
            </Layout>
        );
    }

    return (
        <Layout footerCenter={['Admin', 'Test']}>
            <div className={styles.layout}>
                <nav className={styles.tabBar}>
                    {TEST_TABS.map(({ label, path }) => (
                        <NavLink
                            key={path}
                            to={path}
                            end
                            className={({ isActive }) =>
                                `${styles.tab} ${isActive ? styles.tabActive : ''}`
                            }
                        >
                            {label}
                        </NavLink>
                    ))}
                </nav>
                <Outlet />
            </div>
        </Layout>
    );
}
