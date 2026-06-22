# 프론트 페이지: monitor

## 역할 요약

**한 줄 정의** — 시스템 리소스(CPU/RAM/DISK), Docker 컨테이너, Redis, WebSocket 피드 수신 현황을 실시간으로 보는 운영 모니터링 대시보드.

**누가·언제 쓰나** — 서버/피드가 정상 동작 중인지(UP/STALE/DOWN), 자원 사용량과 큐 상태를 실시간 점검할 때.

**핵심 기능 3가지**
1. CPU/RAM/DISK 게이지
2. Docker·Redis 상태 카드
3. 피드별 수신 상태·마지막 수신 시간·건수 델타

---

## 목차
- 개요
- 데이터 수신 및 상태 관리 (WebSocket & API)
- 모니터링 대시보드 레이아웃 및 반응형 구조
- 시스템 리소스 및 Docker 컨테이너 상태 시각화
- 실시간 피드(Upstream) 모니터링 및 델타 계산
- 데이터 포맷팅 및 시간 처리 유틸리티

## 개요

이 페이지는 시스템의 리소스 상태, Docker 컨테이너 정보, Redis 데이터 및 WebSocket 피드 수신 현황을 실시간으로 모니터링하기 위한 대시보드입니다. `useMonitorWebSocket.js`를 통해 서버로부터 스냅샷 데이터를 수신하며, `WaitOverlay.jsx`를 통해 데이터가 준비되지 않은 상태를 사용자에게 알립니다.

주요 기능 및 구성 요소는 다음과 같습니다:

*   **리소스 모니터링**: `GaugeRow.jsx`를 통해 CPU, RAM, DISK 사용량을 게이지 형태로 시각화하며, `RawAggTradeMeta.jsx`를 통해 RawAggTrade 관련 데이터 크기 및 S3 이관 현황을 표시합니다.
*   **Docker 및 Redis 상태 관리**: `DockerCard.jsx`에서 실행 중인 컨테이너 목록(이름, 이미지, CPU, MEM, 상태, Uptime, 재시작 횟수)과 Redis 키(`server:leader`, `aggtrade queue`) 정보를 제공합니다.
*   **WebSocket 피드 모니터링**: `WsSidebar.jsx`를 통해 바이낸스 시세, 업비트 시세, 바이낸스 체결 등 각 피드의 상태(UP, STALE, DOWN)와 마지막 수신 시간 및 수신 건수 델타를 실시간으로 표시합니다.
*   **반응형 레이아웃**: `useIsMobile.js`를 사용하여 모바일 환경에서는 `MobileSummaryCards.jsx`를 통해 요약된 정보를 제공하며, 데스크톱 환경에서는 상세 정보와 `WsSidebar.jsx`를 포함한 그리드 레이아웃을 유지합니다.
*   **데이터 정합성 및 시간 표시**: `LastUpdatedChip.jsx`를 통해 마지막 데이터 갱신 시점을 계산하여 표시하며, `formatters.js`의 유틸리티 함수들을 통해 바이트, 개수, 시간 등의 데이터를 가독성 있게 포맷팅합니다.

## 데이터 수신 및 상태 관리 (WebSocket & API)

`useMonitorWebSocket.js` 훅은 `/ws/monitor` 경로로 WebSocket 연결을 시도하며, 페이지 로드 시 `apiClient.get('/api/monitor/snapshot')`을 통해 초기 스냅샷 데이터를 호출합니다. WebSocket 연결이 성공하면 `onmessage` 이벤트를 통해 수신된 JSON 데이터를 `snapshot` 상태로 설정하며, 연결이 종료될 경우 2초 후에 재연결을 시도합니다.

수신된 `snapshot` 데이터는 `MonitorPage.jsx`로 전달되어 화면의 각 섹션에 분배됩니다. `snapshot.feeds` 배열은 `WsSidebar.jsx`에서 처리되며, 각 피드의 상태(`status`)와 수신 건수(`receivedCount`)를 기반으로 상태 정보가 관리됩니다. 특히 `WsSidebar.jsx`는 `useMemo`와 `useRef`를 사용하여 이전 스냅샷의 수신 건수(`prevCounts.current`)와 현재 값을 비교함으로써, 피드별로 스냅샷 간의 수신 건수 차이(델타 값)를 계산합니다.

또한, `MonitorPage.jsx`는 `snapshot.containers` 배열을 필터링하여 상태가 'running'인 컨테이너 목록을 추출하고, `anyContainerBad` 여부를 판단하여 Docker 관련 상태 요약을 생성합니다. `snapshot.redisKeys`와 `snapshot.redisQueue` 데이터는 `DockerCard.jsx` 내의 `RedisKeysPreview` 컴포넌트로 전달되어 Redis 관련 정보(leader 정보 및 큐 점유율 등)를 표시하는 데 사용됩니다.

## 모니터링 대시보드 레이아웃 및 반응형 구조

모니터링 페이지는 `Layout` 컴포넌트를 기반으로 구성되며, 화면 크기에 따라 레이아웃이 변화하는 반응형 구조를 가집니다.

전체적인 레이아웃은 `isMobile` 상태에 따라 두 가지 방식으로 분기됩니다.

1. **데스크톱 환경 (`isMobile`이 false인 경우)**
   - 화면은 크게 `main` 섹션과 `WsSidebar`로 나뉩니다.
   - `main` 섹션(`styles.main`)에는 `DiskMeta`, `RawAggTradeMeta`, `DockerCard`가 수직으로 배치됩니다.
   - 우측에는 `WsSidebar`가 위치하며, `useElementHeight` 훅을 통해 얻은 `mainHeight` 값이 `maxHeight` 인자로 전달되어 사이드바의 높이를 조절합니다.
   - 페이지 하단에는 `AlertHistoryTable`이 별도의 영역(`styles.alertBottom`)에 배치됩니다.

2. **모바일 환경 (`isMobile`이 true인 경우)**
   - `main` 섹션 내부에 `MobileSummaryCards`가 표시됩니다. 이 컴포넌트는 CPU, RAM, DISK, WS 정보 및 Docker 요약 정보를 카드 형태로 제공합니다.
   - 데스크톱에서 보이던 `DiskMeta`, `RawAggTradeMeta`, `DockerCard` 및 우측의 `WsSidebar`는 화면에 표시되지 않습니다.
   - 페이지 하단에는 동일하게 `AlertHistoryTable`이 배치됩니다.

또한, `useLockMainScroll` 훅을 통해 페이지 마운트 시 `main` 요소의 세로 스크롤을 잠그는 제어가 이루어집니다. (`frontend/src/page/monitor/hooks/useLockMainScroll.js`, `frontend/src/page/monitor/MonitorPage.jsx`)

## 시스템 리소스 및 Docker 컨테이너 상태 시각화

`MonitorPage`의 메인 섹션은 `isMobile` 상태에 따라 서로 다른 컴포넌트를 렌더링하여 시스템 리소스와 Docker 컨테이너 상태를 시각화합니다.

데스크톱 환경(`!isMobile`)에서는 `DiskMeta`, `RawAggTradeMeta`, `DockerCard` 컴포넌트가 표시됩니다.
- `DiskMeta`는 `snapshot` 데이터를 사용하여 디스크 여유 용량과 전체 용량을 `fmtGb` 포맷터로 변환하여 보여줍니다. (`frontend/src/page/monitor/sections/DiskMeta.jsx`)
- `RawAggTradeMeta`는 스냅샷에 포함된 `rawAggTradeRows`, `rawAggTradeBytes`, `rawAggTradeS3Rows`, `rawAggTradeS3Bytes` 정보를 기반으로 데이터 이관 현황을 표시합니다. (`frontend/src/page/monitor/sections/RawAggTradeMeta.jsx`)
- `DockerCard`는 컨테이너 상태와 Redis 정보를 통합하여 보여줍니다.
    - `DockerContainersTable`은 컨테이너 목록을 순회하며 이름, 이미지, CPU 사용률(`cpuPercent`), 메모리 사용량(`memUsedBytes`/`memLimitBytes`), 상태, Uptime, 재시작 횟수를 출력합니다. 컨테이너의 `status`가 'running'이 아닐 경우 경고 상태로 표시됩니다. (`frontend/src/page/monitor/sections/DockerCard.jsx`)
    - `RedisKeysPreview`는 `redisKeys` 내의 `server:leader` 값과 `config:aggtrade:max-queue-size` 대비 현재 `redisQueue`의 비율(%)을 계산하여 표시합니다. (`frontend/src/page/monitor/sections/DockerCard.jsx`)

모바일 환경(`isMobile`)에서는 `MobileSummaryCards` 컴포넌트가 표시됩니다.
- `MobileSummaryCards`는 CPU, RAM, DISK(여유 용량 포함), WS 연결 수 및 Docker 요약 정보를 카드 형태로 압축하여 제공합니다. (`frontend/src/page/monitor/sections/MobileSummaryCards.jsx`)

## 실시간 피드(Upstream) 모니터링 및 델타 계산

`WsSidebar` 컴포넌트 내에서 `snapshot.feeds` 데이터를 기반으로 각 피드의 상태를 관리하고 수신 건수 변화량을 계산합니다.

`feeds` 배열은 `snapshot.feeds`를 가져와 `FEED_ORDER` 정의에 따라 정렬된 목록입니다. 각 피드의 상태는 `f.status` 값을 통해 확인하며, `STATUS_META` 객체를 참조하여 UI에 표시할 클래스명(`cls`)과 라벨(`label`)을 결정합니다.

피드별 수신 건수 델타(증분) 계산은 `useMemo`를 통해 수행됩니다. `prevCounts`라는 `useRef` 객체에 직전 스냅샷의 각 피드별 `receivedCount`를 저장합니다. 새로운 스냅샷이 수신되어 `collectedAt`이 변경될 때마다, 현재 피드의 `receivedCount`와 `prevCounts.current`에 저장된 이전 값을 비교하여 차이값(`cur - prev`)을 계산합니다. 이 결과는 `deltas` 객체에 저장되며, 계산된 델타값은 피드 정보 옆에 `info` 문자열로 결합되어 표시됩니다.

- `frontend/src/page/monitor/sections/WsSidebar.jsx`
- `frontend/src/page/monitor/sections/WsSidebar.jsx` (내부 `useMemo` 및 `prevCounts` 로직)

## 데이터 포맷팅 및 시간 처리 유틸리티

`parseDt` 함수는 입력된 데이터가 배열 형태(연, 월, 일, 시, 분, 초 순서)이거나 단일 값인 경우를 처리하여 `Date` 객체를 반환합니다. 유효하지 않은 데이터는 `null`을 반환합니다. (`frontend/src/page/monitor/utils/formatters.js`)

`fmtGb` 함수는 바이트(bytes) 단위를 GB 단위로 변환하여 소수점 첫째 자리까지 문자열로 반환합니다. 값이 유효하지 않으면 `--`를 반환합니다. (`frontend/src/page/monitor/utils/formatters.js`)

`fmtCount` 함수는 숫자를 정수로 변환한 뒤 미국식 쉼표(en-US)를 포함한 문자열로 반환합니다. (`frontend/src/page/monitor/utils/formatters.js`)

`fmtBytes` 함수는 바이트 단위를 크기에 따라 B, KB, MB, GB, TB 단위로 변환하여 반환합니다. (`frontend/src/page/monitor/utils/formatters.js`)

`fmtMem` 함수는 사용 중인 바이트(`usedBytes`)와 제한 바이트(`limitBytes`)를 인자로 받아 `사용량 / 전체량` 형식으로 반환하며, 제한 값이 없으면 사용량만 표시합니다. (`frontend/src/page/monitor/utils/formatters.js`)

`fmtTime` 함수는 `parseDt`를 통해 처리된 날짜 객체를 한국어 로케일(`ko-KR`)의 24시간 형식 문자열로 반환합니다. (`frontend/src/page/monitor/utils/formatters.js`)
