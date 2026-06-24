# 프론트 페이지: monitor

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 역할 요약
- **한 줄 정의** — 시스템 리소스(CPU/RAM/DISK), Docker 컨테이너, Redis, WebSocket 피드 수신 현황을 실시간으로 보는 운영 모니터링 대시보드.
- **누가·언제 쓰나** — 서버/피드가 정상 동작 중인지(UP/STALE/DOWN), 자원 사용량과 큐 상태를 실시간 점검할 때.
- **핵심 기능** — ① CPU/RAM/DISK 게이지 ② Docker·Redis 상태 카드 ③ 피드별 수신 상태·마지막 수신 시간·건수 델타.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "모니터 페이지 / 시스템 리소스 / 서버 상태 대시보드"
- "CPU RAM DISK 게이지 / 사용량"
- "Docker 컨테이너 상태 / Redis leader / 큐 점유율"
- "WebSocket 피드 상태 UP STALE DOWN / 수신 건수 델타"
- "모니터 데이터는 어디서? /ws/monitor /api/monitor/snapshot"

## 핵심 개념·용어
- **snapshot**: 서버가 보내는 모니터링 전체 상태(리소스·컨테이너·redis·feeds). WS로 갱신.
- **feed(피드)**: 업스트림 WebSocket 수신 채널(바이낸스 시세/업비트/체결 등). 상태 UP/STALE/DOWN.
- **델타(delta)**: 직전 스냅샷 대비 피드 수신 건수 증가분(`receivedCount` 차이).
- **leader**: Redis `server:leader` — 수집 주도 노드.
- **큐 점유율**: `redisQueue` / `config:aggtrade:max-queue-size` 비율(%).

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/page/monitor/`

### 진입·데이터 수신 — `MonitorPage.jsx`, `hooks/useMonitorWebSocket.js`
- `useMonitorWebSocket`: `/ws/monitor` 연결, 페이지 로드 시 `apiClient.get('/api/monitor/snapshot')`로 초기 스냅샷. `onmessage` JSON→`snapshot` 상태. 연결 종료 시 2초 후 재연결.
- `snapshot`을 각 섹션에 분배: `feeds`→`WsSidebar`, `containers`(running 필터·`anyContainerBad`)→Docker 요약, `redisKeys`/`redisQueue`→`RedisKeysPreview`.

### 레이아웃·반응형 — `MonitorPage.jsx`, `hooks/useLockMainScroll.js`
- 데스크톱: `main`(`DiskMeta`+`RawAggTradeMeta`+`DockerCard`) + 우측 `WsSidebar`(`useElementHeight`로 높이 맞춤), 하단 `AlertHistoryTable`(→`fe-components`).
- 모바일(`useIsMobile`): `MobileSummaryCards`(CPU/RAM/DISK/WS/Docker 요약), 하단 `AlertHistoryTable`. `useLockMainScroll`로 main 세로 스크롤 잠금.

### 리소스·Docker 시각화 — `sections/`
- `GaugeRow`(CPU/RAM/DISK 게이지, →`fe-components` GaugeBar 패턴). `DiskMeta`(여유/전체 `fmtGb`). `RawAggTradeMeta`(`rawAggTradeRows/Bytes`, `...S3Rows/S3Bytes` 이관 현황).
- `DockerCard`: `DockerContainersTable`(이름·이미지·`cpuPercent`·`memUsedBytes/memLimitBytes`·상태·Uptime·재시작; running 아니면 경고), `RedisKeysPreview`(leader·큐 점유율 %).

### 피드 모니터링·델타 — `sections/WsSidebar.jsx`
- `snapshot.feeds`를 `FEED_ORDER`로 정렬, `STATUS_META`로 상태 클래스/라벨. `useRef prevCounts`에 직전 `receivedCount` 저장, `collectedAt` 변경 시 `cur-prev`로 델타 계산해 `info`에 표시.

### 포맷 유틸 — `utils/formatters.js`
- `parseDt`(배열[연월일시분초]/단일→Date), `fmtGb`(GB 소수1, 무효 `--`), `fmtCount`(en-US 콤마), `fmtBytes`(B/KB/MB/GB/TB), `fmtMem`(사용/전체), `fmtTime`(ko-KR 24h).

## 연관 도메인
- 공용 위젯 `fe-components`(`AlertHistoryTable`/`GaugeBar`). 백엔드: 모니터링 API/WS(global). 상세 관계는 `index.md`.
