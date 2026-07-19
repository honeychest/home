// [AGENT] recipe(기까) 스타일가이드 — /recipe/styleguide
// 토큰과 UI 킷 전부를 한 화면에 나열. 새 세션 AI는 화면을 만들기 전 이 페이지를 참조하고,
// 새 공용 컴포넌트를 만들면 반드시 여기에 등록한다 (CONTEXT.md "디자인 체계").
// 각 요소에 클래스·컴포넌트 이름을 함께 표기 — 사용자가 "rcp-chip 색 바꿔줘"처럼 지시 가능.
import { useState } from 'react';
import RcpButton from '../ui/RcpButton';
import RcpChip from '../ui/RcpChip';
import RcpBadge from '../ui/RcpBadge';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpConfirm from '../ui/RcpConfirm';
import RcpShelf from '../ui/RcpShelf';
import RcpVideoRow from '../ui/RcpVideoRow';
import RcpCoverflow from '../ui/RcpCoverflow';
import RcpInlineError from '../ui/RcpInlineError';
import RcpLoadError from '../ui/RcpLoadError';
import RcpFab from '../ui/RcpFab';
import { ChefHat } from 'lucide-react';

const COVERFLOW_SAMPLE = [
    { id: 'a', title: '떡볶이', missing: [] as string[], mine: true },
    { id: 'b', title: '아주아주 길게 적어본 요리 이름도 한 줄 말줄임까지만 (긴 글자 스트레스 테스트)', missing: ['간장', '참기름'], mine: true },
    { id: 'c', title: '제육볶음', missing: ['고추장', '고춧가루', '대파'], mine: false },
    { id: 'd', title: '순두부찌개', missing: ['순두부'], mine: false },
];

const COLOR_TOKENS = [
    '--rcp-bg', '--rcp-surface', '--rcp-surface-dim', '--rcp-line',
    '--rcp-text', '--rcp-text-sub', '--rcp-text-faint',
    '--rcp-accent', '--rcp-accent-strong', '--rcp-accent-soft',
    '--rcp-warn-bg', '--rcp-warn-text', '--rcp-danger',
];

function Label({ children }: { children: string }) {
    return (
        <code style={{
            display: 'block',
            fontSize: 'var(--rcp-fs-xs)',
            color: 'var(--rcp-text-faint)',
            margin: 'var(--rcp-space-3) 0 var(--rcp-space-1)',
        }}>
            {children}
        </code>
    );
}

export default function StyleguidePage() {
    const [chipOn, setChipOn] = useState(true);
    const [sheetOpen, setSheetOpen] = useState(false);
    const [confirmDemo, setConfirmDemo] = useState<'normal' | 'danger' | 'long' | null>(null);

    return (
        <main className="rcp-screen" id="rcp-styleguide-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">기까 스타일가이드</h1>
                <p className="rcp-screen-subtitle">토큰과 UI 킷의 유일한 카탈로그 — 여기 없는 스타일은 쓰지 않기</p>
            </header>

            <h2 className="rcp-section-label">색 토큰 (tokens.css)</h2>
            <div className="rcp-chip-group">
                {COLOR_TOKENS.map((token) => (
                    <div key={token} style={{ width: 148 }}>
                        <div style={{
                            height: 36,
                            borderRadius: 'var(--rcp-radius-sm)',
                            border: '1px solid var(--rcp-line)',
                            background: `var(${token})`,
                        }} />
                        <code style={{ fontSize: 'var(--rcp-fs-xs)', color: 'var(--rcp-text-sub)' }}>{token}</code>
                    </div>
                ))}
            </div>

            <h2 className="rcp-section-label">글자</h2>
            <div style={{ fontSize: 'var(--rcp-fs-xl)', fontWeight: 'var(--rcp-fw-heavy)' as never }}>화면 제목 --rcp-fs-xl</div>
            <div style={{ fontSize: 'var(--rcp-fs-lg)', fontWeight: 'var(--rcp-fw-bold)' as never }}>섹션 제목 --rcp-fs-lg</div>
            <div style={{ fontSize: 'var(--rcp-fs-md)' }}>본문·재료명 --rcp-fs-md</div>
            <div style={{ fontSize: 'var(--rcp-fs-sm)', color: 'var(--rcp-text-sub)' }}>보조 문구 --rcp-fs-sm</div>
            <div style={{ fontSize: 'var(--rcp-fs-xs)', color: 'var(--rcp-text-faint)' }}>배지·날짜 --rcp-fs-xs</div>

            <h2 className="rcp-section-label">버튼 — RcpButton</h2>
            <Label>{'<RcpButton> (.rcp-btn)'}</Label>
            <RcpButton onClick={() => {}}>기본 버튼</RcpButton>
            <Label>{'<RcpButton variant="ghost"> (.rcp-btn-ghost)'}</Label>
            <RcpButton variant="ghost" onClick={() => {}}>고스트 버튼</RcpButton>
            <Label>{'<RcpButton variant="danger"> (.rcp-btn-danger)'}</Label>
            <RcpButton variant="danger" onClick={() => {}}>삭제 버튼</RcpButton>

            <h2 className="rcp-section-label">떠 있는 주 동작 버튼 — RcpFab (.rcp-fab)</h2>
            <Label>{'<RcpFab onClick> — 세로로 길어지는 목록 화면의 주 동작 하나만. 목록 끝에 놓으면 항목이 쌓일수록 스크롤 바닥으로 밀려나 못 찾는다(2026-07-16 보관함 제보). 쓰는 화면은 .rcp-screen-with-fab 을 함께 걸 것 — 안 걸면 마지막 항목을 덮는다'}</Label>
            <p style={{ fontSize: 'var(--rcp-fs-sm)', color: 'var(--rcp-text-sub)' }}>
                이 화면 오른쪽 아래에 떠 있는 그것 — 스크롤해도 안 따라 내려가는지 확인해 보세요.
                (긴 글자 스트레스 테스트 겸용: 라벨이 길어지면 왼쪽으로 늘어나다 화면 끝에서 말줄임)
            </p>
            <RcpFab id="rcp-styleguide-fab" onClick={() => {}}>
                + Video zur Sammlung hinzufügen (아주 긴 라벨)
            </RcpFab>

            <h2 className="rcp-section-label">자석 스티커 칩 — RcpChip (눌러보세요)</h2>
            <Label>{'<RcpChip on> (.rcp-chip-on) / <RcpChip> (.rcp-chip-off)'}</Label>
            <div className="rcp-chip-group">
                <RcpChip on={chipOn} onToggle={() => setChipOn(!chipOn)}>계란</RcpChip>
                <RcpChip on={!chipOn} onToggle={() => setChipOn(!chipOn)}>양파</RcpChip>
            </div>

            <h2 className="rcp-section-label">배지 — RcpBadge</h2>
            <Label>{'<RcpBadge> (.rcp-badge) — 카테고리: cat-1~8(고정 순서 팔레트) / 상태: neutral·analyzing·good·warning·serious·critical / expiring(냉장고 전용)'}</Label>
            <Label>규칙(2026-07-14 재설계): 전부 같은 파스텔 칩 모양, 의미는 색으로만 구분 — 카테고리 팔레트와 상태 팔레트는 절대 안 겹침 (요약줄은 .rcp-summary-row 로 감싸기)</Label>
            <div className="rcp-summary-row">
                <RcpBadge variant="cat-1">레시피</RcpBadge>
                <RcpBadge variant="cat-2">유틸</RcpBadge>
                <RcpBadge variant="cat-3">기타</RcpBadge>
                <RcpBadge variant="analyzing">분석중</RcpBadge>
                <RcpBadge variant="neutral">분석대기</RcpBadge>
                <RcpBadge variant="warning">긴 영상</RcpBadge>
                <RcpBadge variant="serious">삭제됨</RcpBadge>
                <RcpBadge variant="critical">분석실패</RcpBadge>
                <RcpBadge variant="expiring">임박 (냉장고용)</RcpBadge>
            </div>

            <h2 className="rcp-section-label">영상 행 — RcpVideoRow (.rcp-video-row)</h2>
            <Label>{'<RcpVideoRow title thumbnailUrl badge meta onClick> — 대기열(3차)·레시피 목록(4차)'}</Label>
            <RcpVideoRow
                title="두부조림 초간단 버전"
                thumbnailUrl={null}
                badge={<RcpBadge variant="cat-1">레시피</RcpBadge>}
                meta="7월 12일 등록"
                onClick={() => {}}
            />

            <h2 className="rcp-section-label">겹침형 커버플로우 — RcpCoverflow (.rcp-coverflow)</h2>
            <Label>{'<RcpCoverflow items keyOf renderCard cardClassOf> — 추천 화면(4차) 3단계 섹션. 가운데 카드 확대(1.15배)+겹침(-60px), 옆 카드 축소(0.62배)·반투명(0.55) — 목업에서 사용자가 슬라이더로 확정한 값을 상수화'}</Label>
            <Label>{'cardClassOf 로 카드 성격 구분 — 앞 2장이 .rcp-coverflow-card-mine(내 보관함, 그린 테두리). 카드 안 배지로 구분하지 말 것: 카드가 좁아 제목을 가린다(2026-07-17)'}</Label>
            <RcpCoverflow
                items={COVERFLOW_SAMPLE}
                keyOf={(item) => item.id}
                cardClassOf={(item) => (item.mine ? 'rcp-coverflow-card-mine' : undefined)}
                renderCard={(item) => (
                    <>
                        <div className="rcp-coverflow-thumb-fallback"><ChefHat size={40} /></div>
                        <div className="rcp-coverflow-vignette" />
                        <div className="rcp-coverflow-name"><span className="rcp-coverflow-title">{item.title}</span></div>
                        {item.missing.length > 0 && (
                            <div className="rcp-coverflow-chips">
                                {item.missing.slice(0, 2).map((m) => (
                                    <span key={m} className="rcp-coverflow-chip">{m}</span>
                                ))}
                                {item.missing.length > 2 && (
                                    <span className="rcp-coverflow-chip rcp-coverflow-chip-more">{`+${item.missing.length - 2}`}</span>
                                )}
                            </div>
                        )}
                    </>
                )}
            />
            <Label>{'긴 글자 스트레스 테스트: 이름표는 1줄+말줄임(카드 폭이 좁아 2줄은 과함 — 눌러서 상세로 들어가면 전체 이름 확인 가능, 정보 손실 없음)'}</Label>

            <h2 className="rcp-section-label">홈 섬네일 줄 — .rcp-thumb-strip / .rcp-thumb</h2>
            <Label>{'최근 분석 완료 영상 — 쇼츠 비율(9:16) 카드, 스냅 가로 스크롤'}</Label>
            <div className="rcp-thumb-strip">
                {['두부조림', '김치볶음밥', '아주아주 길게 적어본 요리 이름도 두 줄까지만'].map((name) => (
                    <span key={name} className="rcp-thumb">
                        <span className="rcp-thumb-img" aria-hidden="true" />
                        <span className="rcp-thumb-name">{name}</span>
                    </span>
                ))}
            </div>

            <h2 className="rcp-section-label">냉장고 선반 — RcpShelf (.rcp-shelf) + 재료 스티커 (.rcp-sticker)</h2>
            <Label>{'<RcpShelf label> — 스냅 가로 스크롤, 넘칠 때만 오른쪽 페이드'}</Label>
            <RcpShelf label="선반 예시 (옆으로 밀어보세요)">
                <button type="button" className="rcp-sticker rcp-sticker-expiring">두부</button>
                <button type="button" className="rcp-sticker rcp-sticker-old">대파</button>
                <button type="button" className="rcp-sticker rcp-sticker-old">계란</button>
                <button type="button" className="rcp-sticker">버섯</button>
                <button type="button" className="rcp-sticker">김치</button>
                <button type="button" className="rcp-sticker">우유</button>
                <button type="button" className="rcp-sticker">애호박</button>
                <button type="button" className="rcp-sticker">파프리카</button>
                <button type="button" className="rcp-sticker">청양고추</button>
            </RcpShelf>
            <Label>{'.rcp-sticker(신선) / .rcp-sticker-old(14일+) / .rcp-sticker-expiring(임박 포스트잇)'}</Label>

            <h2 className="rcp-section-label">입력 — .rcp-input</h2>
            <input className="rcp-input" placeholder="재료 이름" />

            <h2 className="rcp-section-label">조작 실패 안내 — RcpInlineError (.rcp-inline-error)</h2>
            <Label>{'<RcpInlineError message> — useMutation.error 를 그대로 전달, null 이면 안 그림'}</Label>
            <RcpInlineError message="저장하지 못했어요 — 네트워크 확인 후 다시 시도해 주세요" />

            <h2 className="rcp-section-label">조회 실패 안내 — RcpLoadError (.rcp-load-error)</h2>
            <Label>{'<RcpLoadError message onRetry> — useQuery 의 error·reload 를 그대로 전달, null 이면 안 그림. RcpInlineError(조작 실패)와 짝이다. 화면 안에서는 .rcp-shell-status(100dvh, 셸 전용)를 쓰지 말 것 — 화면 하나만큼 부풀거나(냉장고·보관함) 잘렸다(추천)'}</Label>
            <RcpLoadError message="목록을 불러오지 못했어요 — 네트워크 확인 후 다시 시도해 주세요" onRetry={() => {}} />

            <h2 className="rcp-section-label">스켈레톤 — .rcp-skeleton-row / .rcp-skeleton-card</h2>
            <Label>
                {'로딩 중 자리표시 (2026-07-18, 추천 화면). 치수 고정 원칙: 실제 컴포넌트와 같은 '
                    + '치수의 틀을 흉내 낸다(개수 흉내 금지 — 뷰포트에 보이는 만큼만). 내용으로 '
                    + '갈아끼워도 세로 치수가 변하면 결함'}
            </Label>
            <div className="rcp-skeleton-row" style={{ height: 120 }}>
                <div className="rcp-skeleton-card" />
                <div className="rcp-skeleton-card" />
                <div className="rcp-skeleton-card" />
            </div>

            <h2 className="rcp-section-label">하단 시트 — RcpBottomSheet (.rcp-sheet)</h2>
            <RcpButton variant="ghost" onClick={() => setSheetOpen(true)}>시트 열어보기</RcpButton>
            <RcpBottomSheet open={sheetOpen} title="시트 제목" onClose={() => setSheetOpen(false)}>
                <p style={{ fontSize: 'var(--rcp-fs-sm)', color: 'var(--rcp-text-sub)' }}>
                    바깥을 누르면 닫혀요.
                </p>
                <RcpButton onClick={() => setSheetOpen(false)}>확인</RcpButton>
            </RcpBottomSheet>

            <h2 className="rcp-section-label">확인 다이얼로그 — RcpConfirm (.rcp-confirm-card)</h2>
            <Label>{'<RcpConfirm open message confirmLabel danger? onConfirm onCancel> — window.confirm 대체(2026-07-19 확정, 시스템 창 금지). 배경 탭 = 취소. 파괴적 동작(삭제류)은 danger 로 주 버튼을 빨간색으로. z-index 60 — 하단 시트(50) 안의 동작도 덮는다'}</Label>
            <div style={{ display: 'flex', gap: 'var(--rcp-space-2)' }}>
                <RcpButton variant="ghost" onClick={() => setConfirmDemo('normal')}>일반 확인</RcpButton>
                <RcpButton variant="ghost" onClick={() => setConfirmDemo('danger')}>삭제 확인 (danger)</RcpButton>
                <RcpButton variant="ghost" onClick={() => setConfirmDemo('long')}>긴 글자 스트레스</RcpButton>
            </div>
            <RcpConfirm
                open={confirmDemo === 'normal'}
                message={'"쭈유(참기름)" 재료가 이상한가요? 영상을 다시 살펴보게 신고할까요?'}
                confirmLabel="신고"
                onConfirm={() => setConfirmDemo(null)}
                onCancel={() => setConfirmDemo(null)}
            />
            <RcpConfirm
                open={confirmDemo === 'danger'}
                message={'"영상 제목"을(를) 삭제할까요? 이 영상을 등록한 모든 사용자 목록에서 "삭제됨"으로 표시돼요.'}
                confirmLabel="삭제"
                danger
                onConfirm={() => setConfirmDemo(null)}
                onCancel={() => setConfirmDemo(null)}
            />
            <RcpConfirm
                open={confirmDemo === 'long'}
                message={'"Pfannkuchen mit außergewöhnlich langer Zutatenbezeichnung 아주아주아주아주 긴 재료 이름도 카드 안에서 줄바꿈되어야 합니다" 재료가 이상한가요? 영상을 다시 살펴보게 신고할까요?'}
                confirmLabel="아주 긴 확인 버튼 라벨"
                onConfirm={() => setConfirmDemo(null)}
                onCancel={() => setConfirmDemo(null)}
            />

            <h2 className="rcp-section-label">긴 글자 스트레스 테스트 (다국어 대비 — 여기가 깨지면 킷 결함)</h2>
            <p style={{ fontSize: 'var(--rcp-fs-sm)', color: 'var(--rcp-text-sub)' }}>
                글자가 세로로 꺾이거나, 컴포넌트가 짓눌리거나, 화면 밖으로 삐져나오면 안 됩니다.
            </p>
            <div style={{ display: 'flex', gap: 'var(--rcp-space-2)' }}>
                <input className="rcp-input" placeholder="입력창은 줄어들고" />
                <RcpButton onClick={() => {}}>Hinzufügen</RcpButton>
            </div>
            <div className="rcp-chip-group" style={{ marginTop: 'var(--rcp-space-2)' }}>
                <RcpChip on onToggle={() => {}}>Hähnchenbrustfilet</RcpChip>
                <RcpChip on={false} onToggle={() => {}}>아주아주 길게 적어본 재료 이름</RcpChip>
            </div>
            <div style={{ marginTop: 'var(--rcp-space-2)' }}>
                <RcpVideoRow
                    title="Extra long video title that keeps going 띄어쓰기없이아주길게이어지는영상제목도행안에서줄바꿈되어야정상"
                    thumbnailUrl={null}
                    badge={<RcpBadge variant="warning">bald zu lang</RcpBadge>}
                    meta="12월 31일 등록"
                    onClick={() => {}}
                />
            </div>
            <div style={{ marginTop: 'var(--rcp-space-2)' }}>
                <RcpShelf label="긴 이름 스티커 선반 — 스티커가 내용만큼 넓어지고 선반이 옆으로 흘러야 정상">
                    <button type="button" className="rcp-sticker rcp-sticker-expiring">Hähnchenbrustfilet mit Kräuterbutter</button>
                    <button type="button" className="rcp-sticker">아주아주 길게 적어본 재료 이름</button>
                    <button type="button" className="rcp-sticker rcp-sticker-old">무</button>
                </RcpShelf>
            </div>
            <div style={{ marginTop: 'var(--rcp-space-2)' }}>
                <RcpInlineError message="Fehler beim Speichern — bitte Netzwerkverbindung prüfen und erneut versuchen 아주 길게 이어지는 오류 문구도 줄바꿈되어야 정상" />
            </div>
            <div style={{ marginTop: 'var(--rcp-space-2)' }}>
                <RcpLoadError
                    message="Liste konnte nicht geladen werden — bitte Netzwerkverbindung prüfen und erneut versuchen 아주 길게 이어지는 조회 실패 문구도 줄바꿈되고 버튼은 안 짓눌려야 정상"
                    onRetry={() => {}}
                />
            </div>

            <h2 className="rcp-section-label">재료 사전 행 — .rcp-dict-row (오너 전용 화면)</h2>
            <Label>{'.rcp-dict-action(안 고른 값) / .rcp-dict-action-on(지금 확정된 값) / .rcp-dict-action-proposed(AI 제안 — 아직 확정 아님)'}</Label>
            <p style={{ fontSize: 'var(--rcp-fs-sm)', color: 'var(--rcp-text-sub)' }}>
                확정(그린 실선)과 제안(노랑 점선)은 절대 같은 색이면 안 됩니다 — 색과 모양 둘 다로
                구분해야 색맹에서도 읽힙니다. 탭하면 즉시 저장이라 "미저장" 상태는 없습니다.
                묶인 멤버(→ 계란)는 성격을 대표에서 물려받으므로 분류 버튼 대신 [그룹 해제]만 줍니다 —
                눌러도 효과가 없는 버튼을 보여주면 거짓말이 됩니다.
                긴 글자 스트레스 테스트 겸용: 재료명이 길어지면 이름만 줄바꿈되고 버튼은 안 짓눌립니다.
            </p>
            <div className="rcp-dict-filters">
                {['손볼 것 12', '제안 83', '묶임 37', '전체 243'].map((label, i) => (
                    <button type="button" key={label} className={`rcp-dict-action ${i === 0 ? 'rcp-dict-action-on' : ''}`.trim()}>
                        {label}
                    </button>
                ))}
            </div>
            <div className="rcp-dict-list">
                {[
                    { name: '굴소스', badge: '미판정', variant: 'neutral' as const, on: -1, proposed: 1 },
                    { name: '아주아주 길게 적어본 재료 이름 (긴 글자 스트레스 테스트)', badge: '주재료 확정', variant: 'good' as const, on: 2, proposed: -1 },
                ].map((row) => (
                    <div className="rcp-dict-row" key={row.name}>
                        <span className="rcp-dict-name">{row.name}</span>
                        <RcpBadge variant={row.variant}>{row.badge}</RcpBadge>
                        <div className="rcp-dict-actions">
                            {['기본', '양념', '주재료', '보류'].map((label, i) => (
                                <button
                                    type="button"
                                    key={label}
                                    className={`rcp-dict-action ${i === row.on ? 'rcp-dict-action-on' : ''} ${i === row.proposed ? 'rcp-dict-action-proposed' : ''}`.trim()}
                                >
                                    {label}
                                </button>
                            ))}
                        </div>
                    </div>
                ))}
                {/* 묶기 제안이 붙은 대표 — 분류 버튼 대신 이것부터 (묶이면 분류는 대표가 정한다) */}
                <div className="rcp-dict-row">
                    <span className="rcp-dict-name">계란 2개</span>
                    <RcpBadge variant="neutral">미판정</RcpBadge>
                    <div className="rcp-dict-actions">
                        <button type="button" className="rcp-dict-action rcp-dict-action-proposed">→ 계란</button>
                    </div>
                </div>
                {/* 이미 묶인 멤버 */}
                <div className="rcp-dict-row">
                    <span className="rcp-dict-name">라면 건더기스프</span>
                    <RcpBadge variant="neutral">→ 라면</RcpBadge>
                    <div className="rcp-dict-actions">
                        <button type="button" className="rcp-dict-action">그룹 해제</button>
                    </div>
                </div>
            </div>

            <h2 className="rcp-section-label">하단 탭 바 — RcpTabBar (.rcp-tab-bar)</h2>
            <p style={{ fontSize: 'var(--rcp-fs-sm)', color: 'var(--rcp-text-sub)' }}>
                이 화면 아래에 떠 있는 그것. 기본 탭: 홈 / 추천 / 냉장고 / 보관함.
                <br />
                {'<RcpTabBar tabs={...}> 로 탭 묶음을 통째로 갈아끼웁니다 — 운영자 모드'}
                (/recipe/monitor/*)가 대기열 / 사전 / 나가기 를 씁니다. 탭 바를 두 벌 만들지 말 것.
            </p>
        </main>
    );
}
