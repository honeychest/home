// [AGENT] recipe(기까) 스타일가이드 — /recipe/styleguide
// 토큰과 UI 킷 전부를 한 화면에 나열. 새 세션 AI는 화면을 만들기 전 이 페이지를 참조하고,
// 새 공용 컴포넌트를 만들면 반드시 여기에 등록한다 (CONTEXT.md "디자인 체계").
// 각 요소에 클래스·컴포넌트 이름을 함께 표기 — 사용자가 "rcp-chip 색 바꿔줘"처럼 지시 가능.
import { useState } from 'react';
import RcpButton from '../ui/RcpButton';
import RcpChip from '../ui/RcpChip';
import RcpBadge from '../ui/RcpBadge';
import RcpListRow from '../ui/RcpListRow';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpShelf from '../ui/RcpShelf';
import RcpVideoRow from '../ui/RcpVideoRow';

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

            <h2 className="rcp-section-label">자석 스티커 칩 — RcpChip (눌러보세요)</h2>
            <Label>{'<RcpChip on> (.rcp-chip-on) / <RcpChip> (.rcp-chip-off)'}</Label>
            <div className="rcp-chip-group">
                <RcpChip on={chipOn} onToggle={() => setChipOn(!chipOn)}>계란</RcpChip>
                <RcpChip on={!chipOn} onToggle={() => setChipOn(!chipOn)}>양파</RcpChip>
            </div>

            <h2 className="rcp-section-label">배지 — RcpBadge</h2>
            <Label>{'<RcpBadge> (.rcp-badge) / variant: expiring / dim / excluded / danger / analyzing'}</Label>
            <Label>규칙: 채움 = 정상 흐름 / 테두리만 = 제외·문제 (요약줄은 .rcp-summary-row 로 감싸기)</Label>
            <div className="rcp-summary-row">
                <RcpBadge>완료</RcpBadge>
                <RcpBadge variant="analyzing">분석 중</RcpBadge>
                <RcpBadge variant="dim">대기 중</RcpBadge>
                <RcpBadge variant="excluded">유틸</RcpBadge>
                <RcpBadge variant="excluded">기타</RcpBadge>
                <RcpBadge variant="excluded">긴 영상</RcpBadge>
                <RcpBadge variant="danger">실패</RcpBadge>
                <RcpBadge variant="expiring">임박 (냉장고용)</RcpBadge>
            </div>

            <h2 className="rcp-section-label">영상 행 — RcpVideoRow (.rcp-video-row)</h2>
            <Label>{'<RcpVideoRow title thumbnailUrl badge meta onClick> — 대기열(3차)·레시피 목록(4차)'}</Label>
            <RcpVideoRow
                title="두부조림 초간단 버전"
                thumbnailUrl={null}
                badge={<RcpBadge>완료</RcpBadge>}
                meta="7월 12일 등록"
                onClick={() => {}}
            />

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

            <h2 className="rcp-section-label">목록 행 — RcpListRow (.rcp-list-row)</h2>
            <RcpListRow
                name="두부"
                badge={<RcpBadge variant="expiring">임박</RcpBadge>}
                meta="6월 24일"
            />

            <h2 className="rcp-section-label">입력 — .rcp-input</h2>
            <input className="rcp-input" placeholder="재료 이름" />

            <h2 className="rcp-section-label">하단 시트 — RcpBottomSheet (.rcp-sheet)</h2>
            <RcpButton variant="ghost" onClick={() => setSheetOpen(true)}>시트 열어보기</RcpButton>
            <RcpBottomSheet open={sheetOpen} title="시트 제목" onClose={() => setSheetOpen(false)}>
                <p style={{ fontSize: 'var(--rcp-fs-sm)', color: 'var(--rcp-text-sub)' }}>
                    바깥을 누르면 닫혀요.
                </p>
                <RcpButton onClick={() => setSheetOpen(false)}>확인</RcpButton>
            </RcpBottomSheet>

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
                <RcpListRow
                    name="Extra virgin olive oil from the Mediterranean coast 아주 긴 재료명"
                    badge={<RcpBadge variant="expiring">bald ablaufend</RcpBadge>}
                    meta="12월 31일"
                />
            </div>
            <div style={{ marginTop: 'var(--rcp-space-2)' }}>
                <RcpVideoRow
                    title="Extra long video title that keeps going 띄어쓰기없이아주길게이어지는영상제목도행안에서줄바꿈되어야정상"
                    thumbnailUrl={null}
                    badge={<RcpBadge variant="excluded">bald zu lang</RcpBadge>}
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

            <h2 className="rcp-section-label">하단 탭 바 — RcpTabBar (.rcp-tab-bar)</h2>
            <p style={{ fontSize: 'var(--rcp-fs-sm)', color: 'var(--rcp-text-sub)' }}>
                이 화면 아래에 떠 있는 그것. 탭: 홈 / 추천 / 냉장고 / 레시피.
            </p>
        </main>
    );
}
