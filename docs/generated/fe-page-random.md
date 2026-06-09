# 프론트 페이지: random

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요
- 레이아웃 상수 및 물리 엔진 설정
- 물리 시뮬레이션 엔진 구축 및 환경 구성
- 게임 루프: 볼 생성, 이동 및 충돌 처리
- 승리 판정 및 랭킹 시스템 로직
- UI 상태 관리 및 사용자 인터랙션 흐름
- 데이터 영속성 및 초기화 프로세스

## 개요

이 시스템은 Matter.js 물리 엔진을 활용하여 사용자 정의된 레이아웃 내에서 공의 움직임을 통해 결과를 결정하는 'Random Picker' 게임을 제공합니다. `frontend/src/page/random/randomPhysics.js`에서 물리 엔진의 핵심 로직이 구현되며, `frontend/src/page/random/RandomPickerBoard.jsx`를 통해 사용자 인터페이스와 게임 흐름이 제어됩니다.

게임의 물리적 구조는 `frontend/src/page/random/randomLayout.js`에 정의된 상수값(보드 크기, 핀 위치, 디플렉터 설정 등)을 기반으로 구축됩니다. `createRandomWorld` 함수는 물리 엔진의 월드에 벽, 깔때기 형태의 구조물, 디플렉터(Deflectors), 핀(Pins), 그리고 골 센서가 포함된 목표 구조물을 생성합니다(`frontend/src/page/random/randomPhysics.js`).

게임 진행 방식은 다음과 같습니다:
1. **메뉴 등록**: 사용자는 최대 10개의 메뉴를 입력할 수 있으며, 이는 `frontend/src/page/random/RandomPickerBoard.jsx`에서 관리됩니다. 입력된 메뉴는 `buildBallMenus`를 통해 물리 엔진에 적용될 '메뉴 볼(menu-ball)' 객체로 변환됩니다.
2. **대기 상태**: 게임 시작 전, `syncStandbyBalls` 함수를 통해 생성된 볼들이 지정된 대기 위치에 배치됩니다(`frontend/src/page/random/randomPhysics.js`).
3. **실행 및 발사**: `handleStart`가 호출되면 `launchAllMenuBallsAtOnce` 함수를 통해 대기 중인 볼들이 물리 엔진 내에서 발사됩니다(`frontend/src/page/random/RandomPickerBoard.jsx`, `frontend/src/page/random/randomPhysics.js`).
4. **결과 판정**: 볼이 골 센서(`goal-sensor`)에 충돌하면 `onGoal` 콜백을 통해 순위가 기록됩니다. 가장 먼저 센서에 도달한 볼의 메뉴 정보가 승자로 결정되며, 상위 3위까지의 순위가 UI에 표시됩니다(`frontend/src/page/random/RandomPickerBoard.jsx`, `frontend/src/page/random/randomPhysics.js`).

또한, 관리자 권한을 가진 사용자는 `frontend/src/page/random/RandomLayoutEditorPage.jsx`를 통해 물리적 구조물(점, 디플렉터, 핀 등)의 위치와 속성을 직접 편집하여 레이아웃을 조정할 수 있습니다.

## 레이아웃 상수 및 물리 엔진 설정

보드 크기는 가로 1040, 세로 960으로 설정되어 있으며(`frontend/src/page/random/randomLayout.js`), 물리 엔진인 Matter.js를 사용하여 중력(gravity.y)이 1.0으로 설정된 엔진 환경에서 구동됩니다(`frontend/src/page/random/randomPhysics.js`).

주요 물리 객체 설정은 다음과 같습니다:
- **공(Ball)**: 반지름은 29이며, 탄성(restitution) 0.82, 마찰(friction) 0.005, 공기 마찰(frictionAir) 0.0014, 밀도(density) 0.00135를 가집니다(`frontend/src/page/random/randomLayout.js`, `frontend/src/page/random/randomPhysics.js`). 또한, 공의 속도가 최대 속도(`MAX_BALL_SPEED`: 18)를 초과하지 않도록 제한됩니다(`frontend/src/page/random/randomPhysics.js`).
- **깔때기(Funnel)**: `FUNNEL_LEFT_POINTS`에 정의된 좌표를 기반으로 좌우 대칭 구조로 벽이 생성됩니다(`frontend/src/page/random/randomLayout.js`, `frontend/src/page/random/randomPhysics.js`).
- **장애물(Deflectors & Pins)**: 
    - `DEFLECTORS`는 지정된 위치와 각도를 가진 정적 객체로, 움직임(motion) 설정이 포함될 수 있습니다(`frontend/src/page/random/randomLayout.js`, `frontend/src/page/random/randomPhysics.js`).
    - `PINS`는 원형 정적 객체로, 충돌 시 공에 반사력을 제공합니다(`frontend/src/page/random/randomLayout.js`, `frontend/src/page/random/randomPhysics.js`).
- **골 구조(Goal Structure)**: `GOAL_LAYOUT`에 정의된 바닥 두께, 구멍 너비, 센서 높이 등을 사용하여 구성되며, `GOAL_LEFT_POINTS`를 기준으로 좌우 대칭 벽이 생성됩니다(`frontend/src/page/random/randomLayout.js`, `frontend/src/page/random/randomPhysics.js`).
- **대기 상태(Standby)**: 공은 `STANDBY_Y` 위치에서 대기하며, `STANDBY_INSET` 값을 고려하여 배치됩니다(`frontend/src/page/random/randomLayout.js`, `frontend/src/page/random/randomPhysics.js`).

## 물리 시뮬레이션 엔진 구축 및 환경 구성

Matter.js 엔진을 기반으로 `createRandomWorld` 함수를 통해 물리 시뮬레이션 환경을 구축한다. `engine.world.gravity.y`를 1.0으로 설정하여 중력 환경을 구성하며, `Render.create`를 통해 지정된 엘리먼트에 시각화 엔진을 생성한다.

물리 환경의 구성 요소는 다음과 같다:
- **벽면 및 경계**: `buildWalls` 함수를 통해 상단, 좌측, 우측에 정적 바디(isStatic: true)를 생성하여 물리적 경계를 형성한다. `frontend/src/page/random/randomPhysics.js`
- **깔때기(Funnel) 구조**: `buildCurvedFunnel` 함수는 `FUNNEL_LEFT_POINTS` 좌표를 기반으로 선형 보간을 통해 좌우 대칭의 곡선 벽면 세그먼트를 생성한다. `frontend/src/page/random/randomPhysics.js`
- **장애물(Deflectors & Pins)**: `buildDeflectors`는 움직이는 장애물을, `buildPins`는 정적 핀을 생성한다. 특히 디플렉터는 `registerDeflectorMotion`을 통해 시간에 따라 위치와 속도가 업데이트되는 동적 물리 효과를 가진다. `frontend/src/page/random/randomPhysics.js`
- **골 구조(Goal Structure)**: `buildGoalStructure`는 바닥면과 좌우 벽면 세그먼트, 그리고 충돌 감지를 위한 `goal-sensor`를 포함한 구조물을 생성한다. `frontend/src/page/random/randomPhysics.js`

물리 엔진의 이벤트 제어는 다음과 같이 수행된다:
- **충돌 이벤트**: `registerCollisionEvents`를 통해 공(menu-ball)과 센서, 핀, 디플렉터 간의 충돌을 처리한다. 특히 핀과의 충돌 시 `reflectBallFromPin` 함수를 통해 반사 속도를 계산하여 물리적 반동을 구현한다. `frontend/src/page/random/randomPhysics.js`
- **속도 제한**: `registerSpeedLimiter`는 모든 프레임 업데이트 전 `limitBodySpeed`를 호출하여 공의 속도가 `MAX_BALL_SPEED`를 초과하지 않도록 제어한다. `frontend/src/page/random/randomPhysics.js`
- **텍스트 렌더링**: `registerBallTextRenderer`는 물리 엔진의 `afterRender` 시점에 각 바디의 위치 정보를 활용하여 공 위에 텍스트를 오버레이한다. `frontend/src/page/random/randomPhysics.js`

## 게임 루프: 볼 생성, 이동 및 충돌 처리

볼은 `buildBallBody` 메서드를 통해 생성되며, 각 볼은 고유한 `label`(예: `menu-ball-[index]-[id]`)과 물리 속성(restitution, friction 등)을 가집니다. `syncStandbyBalls`를 통해 생성된 대기 상태의 볼들은 `standbyBalls` 배열에 저장되어 물리 엔진 월드에 추가됩니다. 게임 시작 시 `launchAllMenuBallsAtOnce`가 호출되면, 대기 중이던 볼들은 새로운 동적 볼 객체로 생성되어 물리 엔진에 추가되며, 각 볼은 `spread` 계산식에 따른 초기 속도(`vx`, `vy`)를 부여받아 발사됩니다.

볼의 이동은 Matter.js 엔진에 의해 처리되며, `registerSpeedLimiter`를 통해 모든 볼의 속도가 `MAX_BALL_SPEED`를 초과하지 않도록 제한됩니다. 또한, `registerDeflectorMotion`은 엔진의 `beforeUpdate` 이벤트 시점에 `deflector` 라벨을 가진 객체들의 위치를 주기적으로 업데이트하여 움직이는 장애물을 구현합니다.

충돌 처리는 `registerCollisionEvents`를 통해 관리됩니다. 볼이 `goal-sensor`와 충돌하면 해당 볼은 승리자로 간주되어 `rankings`에 기록되고 물리 월드에서 제거됩니다. 볼이 `pin`과 충돌할 경우 `reflectBallFromPin` 메서드가 호출되어 물리적인 반사 속도가 계산되며, 동시에 핀의 `pulseStartedAt` 값이 업데이트되어 시각적 효과를 위한 데이터가 기록됩니다. 볼이 `deflector`와 충돌할 경우, 해당 디플렉터의 `restitution`(반발 계수)이 무작위로 재설정됩니다.

## 승리 판정 및 랭킹 시스템 로직

`randomPhysics.js`의 `registerCollisionEvents` 함수 내에서 `isMenuBall`로 판별된 객체와 `goal-sensor` 라벨을 가진 바디 간의 충돌이 발생할 경우 승리 로직이 실행됩니다. 

승리 판정 시, 해당 공의 ID를 `ctx.finishedBallBodyIds`에 기록하여 중복 판정을 방지합니다. 이후 공의 `plugin.menu` 정보를 `ctx.rankings` 배열에 순차적으로 추가합니다. 이때 `onGoal` 콜백 함수가 호출되며, 전달된 `rank`(순위)가 1인 경우에만 최종 승자로 간주됩니다.

`RandomPickerBoard.jsx`에서는 `onGoal` 콜백을 통해 랭킹 시스템을 관리합니다. `rank`가 1인 공이 들어오면 `setWinner(goalMenu)`를 호출하여 승자를 설정하고, 동시에 `setShowFanfare(true)`를 통해 축하 연출을 실행합니다. 랭킹 데이터는 `setRankings`를 통해 관리되며, 승자가 결정된 후 일정 시간(`220ms`)이 지나면 `setShowRankingPanel(true)`를 통해 1위부터 3위까지의 순위가 표시되는 패널을 노출합니다. 만약 `rank`가 3보다 큰 경우(4위 이하)에는 순위 목록에 추가하지 않습니다.

## UI 상태 관리 및 사용자 인터랙션 흐름

사용자는 `RandomPickerBoard.jsx`를 통해 메뉴를 관리하고 게임을 진행합니다. 메뉴 데이터는 `localStorage`에 저장되며, `readStoredMenus` 함수를 통해 초기화됩니다. 메뉴는 최대 10개까지 등록 가능하며, `addMenuItem`을 통해 추가하거나 `removeMenuItem`으로 삭제할 수 있습니다. 게임이 진행 중(`isRunning: true`)일 때는 메뉴를 수정하거나 삭제할 수 없습니다.

게임의 흐름은 다음과 같이 제어됩니다:
- **준비 단계**: `menuItems`가 변경되면 `useMemo`를 통해 `menuBalls` 객체들이 생성됩니다. `useEffect`에 의해 `launchOrder`가 업데이트되며, `syncStandbyBalls`를 통해 물리 엔진 상에 대기 상태의 공들이 배치됩니다.
- **시작 단계**: `handleStart`를 호출하면 `isRunning` 상태가 활성화되고, `launchAllMenuBallsAtOnce` 함수가 호출되어 대기 중이던 공들이 물리 엔진 내에서 발사됩니다.
- **결과 처리**: 공이 `goal-sensor`에 충돌하면 `onGoal` 콜백이 실행됩니다. 1위(`rank === 1`)가 결정되면 `setWinner`를 통해 승자가 설정되고, `fanfareTimerRef`와 `rankingTimerRef`를 이용해 순차적으로 승리 연출(`showFanfare`)과 순위 패널(`showRankingPanel`)이 화면에 나타납니다.
- **재시작 및 초기화**: `handleRestart`를 호출하면 게임이 중단되고 대기 상태의 공들이 다시 배치됩니다. `handleShuffleLayout`은 게임 진행 중이 아닐 때만 호출 가능하며, `launchOrder`의 순서를 섞어 물리 엔진에 반영합니다.

물리 엔진과의 상호작용은 `worldRef`를 통해 관리되며, 게임 종료 또는 컴포넌트 언마운트 시 `destroyRandomWorld`를 호출하여 물리 엔진 인스턴스를 정리합니다.

## 데이터 영속성 및 초기화 프로세스

메뉴 데이터는 브라우저의 `localStorage`를 통해 영속성을 유지한다. `RandomPickerBoard.jsx` 파일에서는 `MENU_STORAGE_KEY`('random-pachinko-menus')를 사용하여 메뉴 목록을 저장하며, 페이지 로드 시 `readStoredMenus` 함수를 통해 저장된 데이터를 불러온다. 만약 저장된 데이터가 없거나 형식이 올바르지 않은 경우 `DEFAULT_MENUS`를 기본값으로 사용한다.

초기화 프로세스는 두 가지 방식으로 제공된다. 첫째, `handleMenuReset` 함수를 통해 사용자가 명시적으로 메뉴를 기본 9개 구성(`DEFAULT_MENUS`)으로 되돌릴 수 있다. 둘째, `readStoredMenus` 함수는 데이터가 유효하지 않을 경우 자동으로 기본 메뉴를 반환하도록 설계되어 있다. 또한, `addMenuItem` 함수는 메뉴가 최대 10개(`MAX_MENUS`)를 초과하지 않도록 제한하며, `useEffect` 훅을 통해 메뉴 목록이 변경될 때마다 `localStorage`에 최신 상태를 동기화한다.
