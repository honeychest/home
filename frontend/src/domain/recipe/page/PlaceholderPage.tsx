// [AGENT] recipe(기까) — 아직 안 만든 탭의 자리 화면 (1차에서는 냉장고만 실동작)
interface PlaceholderPageProps {
    pageId: string;
    title: string;
    description: string;
}

export default function PlaceholderPage({ pageId, title, description }: PlaceholderPageProps) {
    return (
        <main className="rcp-screen" id={pageId}>
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">{title}</h1>
            </header>
            <p className="rcp-empty">{description}</p>
        </main>
    );
}
