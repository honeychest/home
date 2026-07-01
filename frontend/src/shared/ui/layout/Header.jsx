// [AGENT] T4-ANALYSIS: NAV_ITEMS Cesium → Analysis 교체
// [AGENT] 앱 상단 헤더 — NavLink 메뉴(Trade/Binance/Signal/Analysis/Admin), X-Server-Name 서버 인디케이터
// 연관: Layout.jsx, MainRouter.jsx
// Purpose: 앱 전체 상단 헤더 - 메뉴 네비게이션, 서버 인디케이터

import { useEffect, useRef, useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import apiClient from '@/api/apiClient.js';
import { preloadSignalPage } from '@/app/router/lazyPages.js';
import { useAdminAuth } from '@/shared/auth/useAdminAuth.js';
import styles from './Header.module.css';

const NAV_ITEMS = [
    { label: 'Binance',   path: '/binance' },
    { label: 'Trade',     path: '/trade' },
    { label: 'Signal',    path: '/signal' },
    { label: 'Analysis',  path: '/analysis' },
    { label: 'Logistics', path: '/logistics' },
    { label: 'Winner',    path: '/winner' },
    { label: 'Monitor',   path: '/monitor' },
    { label: 'Admin',     path: '/admin'  },
    { label: 'Health',    path: '/admin/health',  requireAdmin: true },
    { label: 'Test',      path: '/admin/test',    requireAdmin: true },
    { label: 'Editor',    path: '/winner/editor', requireAdmin: true },
];

/**
 * Header 컴포넌트
 *
 * 마운트 시 /api/binance/price 를 1회 호출해 응답 헤더 X-Server-Name 을 읽는다.
 *
 * serverName 상태:
 *   null     = API 응답 전 (로딩 중) — 인디케이터 미표시
 *   'DOCKER1' 등 = 서버 환경변수 SERVER_NAME 값 그대로 표시
 *   'LOCAL'  = 환경변수 미설정 (로컬 개발) — 백엔드가 기본값으로 내려줌
 */
function Header() {
    const [serverName, setServerName] = useState(null);
    const [isNavScrolling, setIsNavScrolling] = useState(false);
    const [scrollProgress, setScrollProgress] = useState(0);
    const location = useLocation();
    const navRef = useRef(null);
    const { canAccess } = useAdminAuth();
    const visibleNavItems = NAV_ITEMS.filter(item => !item.requireAdmin || canAccess === true);

    useEffect(() => {
        apiClient.get('/api/binance/price')
            .then((res) => {
                const sn = res.headers['x-server-name'];
                if (sn) setServerName(sn);
            })
            .catch(() => {});
    }, []);

    // 모바일 가로 메뉴 스크롤 위치 유지 (라우트 이동/리마운트 대응)
    useEffect(() => {
        const el = navRef.current;
        if (!el) return;
        const saved = sessionStorage.getItem('header.nav.scrollLeft');
        if (saved != null) {
            const n = Number(saved);
            if (Number.isFinite(n)) el.scrollLeft = n;
        }
    }, [location.pathname]);

    useEffect(() => {
        const el = navRef.current;
        if (!el) return;
        let timer;
        const onScroll = () => {
            sessionStorage.setItem('header.nav.scrollLeft', String(el.scrollLeft));
            setIsNavScrolling(true);
            const max = el.scrollWidth - el.clientWidth;
            setScrollProgress(max > 0 ? el.scrollLeft / max : 0);
            clearTimeout(timer);
            timer = setTimeout(() => setIsNavScrolling(false), 500);
        };
        el.addEventListener('scroll', onScroll, { passive: true });
        return () => { el.removeEventListener('scroll', onScroll); clearTimeout(timer); };
    }, []);

    const handleNavPreload = (path) => {
        if (path === '/signal') {
            void preloadSignalPage();
        }
    };

    return (
        <header className={styles.header}>
            {/* ── 네비게이션 ──────────────────────────────────── */}
            <nav className={styles.navWrap}>
                <ul className={styles.nav} ref={navRef}>
                    {visibleNavItems.map(({ label, path }) => (
                        <li key={path} className={styles.navItem}>
                            <NavLink
                                to={path}
                                end
                                onMouseEnter={() => handleNavPreload(path)}
                                onFocus={() => handleNavPreload(path)}
                                className={({ isActive }) =>
                                    `${styles.navLink} ${isActive ? styles.navLinkActive : ''}`
                                }
                            >
                                {label}
                            </NavLink>
                        </li>
                    ))}
                </ul>
            </nav>

            {/* ── 서버 인디케이터 ──────────────────────────────
              API 응답 전(null)이면 미표시.
              응답 후 X-Server-Name 값을 그대로 표시.
                connected
                DOCKER1
            ──────────────────────────────────────────────── */}
            <div
                className={styles.scrollBar}
                style={{ width: `${scrollProgress * 100}%` }}
            />

            {serverName && (
                <div className={`${styles.serverInfo} ${isNavScrolling ? styles.serverInfoHidden : ''}`}>
                    <span className={styles.serverConnected}>connected</span>
                    <span className={styles.serverName}>{serverName}</span>
                </div>
            )}
        </header>
    );
}

export default Header;
