# 프론트 페이지: random

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 역할 요약
- **한 줄 정의** — Matter.js 물리 엔진으로 공을 굴려 무작위로 당첨자를 뽑는 추첨(Random Picker) 페이지. (라우트 `/winner`)
- **누가·언제 쓰나** — 참가자/메뉴 중 공정하게 무작위로 1등을 뽑고 싶을 때.
- **핵심 기능** — ① 물리 시뮬레이션 보드(핀·디플렉터·골 센서) ② 승리 판정·랭킹(1~3위) ③ 관리자용 레이아웃 편집기(`/winner/editor`).

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "추첨 페이지 / 랜덤 당첨 / winner / 파친코 물리"
- "Matter.js 물리 엔진 / 공 굴리기 / 골 센서"
- "메뉴 등록 최대 몇 개 / 랭킹 1~3위"
- "레이아웃 편집기 핀 디플렉터 위치 편집"
- "추첨 결과 저장 localStorage"

## 핵심 개념·용어
- **Matter.js**: 2D 물리 엔진. 중력·충돌·반발로 공의 낙하를 시뮬레이션.
- **menu-ball**: 입력한 메뉴(참가자)를 나타내는 공. 골 센서 도달 순서로 순위 결정.
- **goal-sensor**: 바닥 목표 구멍의 충돌 감지 센서. 먼저 도달한 공이 1위.
- **deflector / pin**: 공 경로를 바꾸는 움직이는 장애물 / 정적 핀.
- **레이아웃 상수**: 보드·공·장애물 위치/속성. `randomLayout.js`에 정의.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/page/random/`

### 진입·게임 흐름 — `RandomPage.jsx`, `RandomPickerBoard.jsx`
1. **메뉴 등록**: 최대 `MAX_MENUS=10`개. `localStorage` `MENU_STORAGE_KEY='random-pachinko-menus'`에 저장, `readStoredMenus`로 로드(없거나 무효면 `DEFAULT_MENUS`). 진행 중(`isRunning`)엔 수정/삭제 불가.
2. **대기**: `menuItems` 변경 시 `useMemo`로 `menuBalls` 생성→`syncStandbyBalls`로 대기 위치 배치.
3. **시작**: `handleStart`→`isRunning` 활성→`launchAllMenuBallsAtOnce`로 일제 발사(spread 초기속도).
4. **결과**: 공이 `goal-sensor` 충돌→`onGoal(rank)`. `rank===1`이면 `setWinner`+`setShowFanfare`, 220ms 후 `setShowRankingPanel`(1~3위). 4위 이하는 순위 제외.
- `handleRestart`(중단·재배치), `handleShuffleLayout`(미진행 시 발사 순서 셔플), `handleMenuReset`(기본 메뉴 복원). 언마운트 시 `destroyRandomWorld`.

### 레이아웃 상수 — `randomLayout.js`
- `BOARD_WIDTH=1040`, `BOARD_HEIGHT=960`, `BALL_RADIUS=29`, `MAX_BALL_SPEED=18`. 공 물성(restitution 0.82, friction 0.005, frictionAir 0.0014, density 0.00135). `FUNNEL_LEFT_POINTS`(깔때기), `DEFLECTORS`/`PINS`(장애물), `GOAL_LAYOUT`/`GOAL_LEFT_POINTS`(골), `STANDBY_Y`/`STANDBY_INSET`(대기).

### 물리 엔진 — `randomPhysics.js`
- `createRandomWorld`: `engine.world.gravity.y=1.0`, `Render.create`. `buildWalls`(경계), `buildCurvedFunnel`(선형보간 곡선벽), `buildDeflectors`(+`registerDeflectorMotion` 동적), `buildPins`, `buildGoalStructure`(+`goal-sensor`).
- 이벤트: `registerCollisionEvents`(공-센서/핀/디플렉터; 핀은 `reflectBallFromPin` 반사, 디플렉터는 restitution 무작위 재설정), `registerSpeedLimiter`(`limitBodySpeed`로 `MAX_BALL_SPEED` 제한), `registerBallTextRenderer`(`afterRender`에 공 위 텍스트).
- 승리: `goal-sensor` 충돌 시 `ctx.finishedBallBodyIds`로 중복 방지, `plugin.menu`를 `ctx.rankings`에 추가, `onGoal` 콜백(rank=1이 최종 승자).

### 레이아웃 편집기 — `RandomLayoutEditorPage.jsx`
- 관리자만 접근. 점·디플렉터·핀 등 구조물 위치/속성을 직접 편집해 레이아웃 조정.

## 연관 도메인
- 순수 프론트(백엔드/외부 데이터 의존 없음). 관리자 권한은 `fe-shared`(`useAdminAccess`). 상세 관계는 `index.md`.
