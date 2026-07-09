// [AGENT] recipe(기까) 앱 골격 — /recipe/* 전체를 감싸는 셸
// 하단 탭 4개(홈/추천/냉장고/레시피) + PWA manifest('기까') 주입. CONTEXT.md "앱 골격" 확정.
// 1차에서는 냉장고만 실동작, 나머지 탭은 자리만 (2~3차에서 구현).
import { useEffect } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import RcpTabBar from '../ui/RcpTabBar';
import FridgePage from './FridgePage';
import PlaceholderPage from './PlaceholderPage';
import StyleguidePage from './StyleguidePage';
import '../style/tokens.css';
import '../ui/recipe-ui.css';

/** 기까 전용 문서 메타(제목·manifest·테마색)를 recipe 안에서만 적용하고 나가면 원복 */
function useGikkaDocumentMeta() {
    useEffect(() => {
        const previousTitle = document.title;
        document.title = '기까';

        const manifestLink = document.createElement('link');
        manifestLink.rel = 'manifest';
        manifestLink.href = '/recipe/manifest.webmanifest';
        document.head.appendChild(manifestLink);

        const themeColor = document.createElement('meta');
        themeColor.name = 'theme-color';
        themeColor.content = '#1f7a3d';
        document.head.appendChild(themeColor);

        return () => {
            document.title = previousTitle;
            manifestLink.remove();
            themeColor.remove();
        };
    }, []);
}

export default function RecipeApp() {
    useGikkaDocumentMeta();

    return (
        <div className="rcp-app" id="rcp-app">
            <Routes>
                <Route index element={<Navigate to="fridge" replace />} />
                <Route
                    path="home"
                    element={(
                        <PlaceholderPage
                            pageId="rcp-home-page"
                            title="홈"
                            description="복사한 쇼츠 링크 붙여넣기와 최근 분석 목록이 여기에 생겨요 (3차 예정)"
                        />
                    )}
                />
                <Route
                    path="recommend"
                    element={(
                        <PlaceholderPage
                            pageId="rcp-recommend-page"
                            title="추천"
                            description="지금 만들 수 있는 요리를 여기서 보여줘요 (2차 예정)"
                        />
                    )}
                />
                <Route path="fridge" element={<FridgePage />} />
                <Route
                    path="recipes"
                    element={(
                        <PlaceholderPage
                            pageId="rcp-recipes-page"
                            title="레시피"
                            description="저장한 쇼츠 레시피 목록이 여기에 생겨요 (2차 예정)"
                        />
                    )}
                />
                <Route path="styleguide" element={<StyleguidePage />} />
                <Route path="*" element={<Navigate to="fridge" replace />} />
            </Routes>
            <RcpTabBar />
        </div>
    );
}
