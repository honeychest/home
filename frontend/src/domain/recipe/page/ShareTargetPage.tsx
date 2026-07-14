// [AGENT] recipe(기까) PWA Share Target 수신 — 유튜브 앱 공유 → 기까 선택 → 즉시 대기열 등록
// (CONTEXT.md 홈 화면 절, 2026-07-11 확정). manifest.webmanifest 의 share_target 이 이 경로를 가리킨다.
// 공유 파라미터는 앱마다 제각각(유튜브는 url 대신 text 에 링크를 넣기도) — 셋을 합쳐 링크를 찾는다.
import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { registerLink } from '../data/registerLink';
import RcpButton from '../ui/RcpButton';
import RcpInlineError from '../ui/RcpInlineError';

// 에러 계약(CONTEXT.md): 문구는 프론트 소유
const NO_LINK_TEXT = '공유된 내용에서 유튜브 링크를 찾지 못했어요';
const FAIL_TEXT = '등록하지 못했어요 — 보관함 탭에서 다시 시도해 주세요';

export default function ShareTargetPage() {
    const [params] = useSearchParams();
    const navigate = useNavigate();
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        const raw = [params.get('url'), params.get('text'), params.get('title')]
            .filter(Boolean).join(' ');
        const link = raw.match(/https?:\/\/\S+/)?.[0] ?? raw;
        const goQueue = () => navigate('/recipe/recipes', { replace: true });

        const run = async () => {
            // 영상 우선 규칙은 registerLink 공용 모듈이 판정 (홈·보관함 탭과 단일 원본).
            // 이미 등록된 영상 공유(duplicate) = 정상 흐름 — 대기열에서 상태 확인.
            const outcome = await registerLink(link);
            if (outcome.kind === 'invalid') {
                setError(NO_LINK_TEXT);
                return;
            }
            goQueue();
        };
        run().catch(() => setError(FAIL_TEXT));
        // 공유 수신은 진입 시 1회 — params 는 이 화면의 수명 동안 불변
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return (
        <main className="rcp-screen" id="rcp-share-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">공유 받은 영상</h1>
            </header>
            {error ? (
                <>
                    <RcpInlineError message={error} />
                    <RcpButton className="rcp-btn-full" onClick={() => navigate('/recipe/recipes', { replace: true })}>
                        보관함 탭으로
                    </RcpButton>
                </>
            ) : (
                <p className="rcp-empty">대기열에 넣는 중…</p>
            )}
        </main>
    );
}
