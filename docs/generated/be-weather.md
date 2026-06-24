# Weather 도메인 (백엔드)

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
전국 10개 시도의 날씨를 **DB 우선(Cache-First)** 으로 제공하고, 데이터가 부족하면 **기상청 단기예보 API**를 호출해 채운 뒤 DB에 저장하는 백엔드 도메인이다.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "날씨 데이터는 어디서 가져와? 기상청 API 연동은 어떻게 동작해?"
- "날씨 캐시 전략 / DB 우선 조회 / 중복 저장 방지"
- "기상청 API 호출 횟수 제한 / 일일 호출량 / Redis 카운트"
- "available-hours / /api/weather/all 엔드포인트"
- "기상청 격자 좌표 nx ny / category T1H REH RN1 WSD 뜻"

## 핵심 개념·용어
- **Cache-First(DB 우선)**: 외부 API를 부르기 전에 DB(MySQL `weather_history`)를 먼저 본다. 10개 지역이 다 있으면 API를 안 부른다.
- **기상청 단기예보 API**: 초단기실황(`getUltraSrtFcst`) 계열. 파라미터는 `base_date`, `base_time`, 격자 좌표 `nx`/`ny`. 응답은 JSON 배열로 `category`(항목코드) + `fcstValue`(값).
- **category 코드**: `T1H`=기온(°C), `REH`=습도(%), `RN1`=1시간 강수량(mm), `WSD`=풍속(m/s). 이 4개만 추출한다.
- **격자 좌표(nx, ny)**: 경위도가 아니라 기상청 전용 격자 좌표. 시도별 중심 좌표를 코드에 하드코딩.
- **fcstDateTime**: 예보 시각. `region`과 합쳐 유니크(중복 방지) 키가 된다.
- **일일 호출량(dailyLimit)**: 기상청 API 일 호출 상한. 기본값 10000(`WEATHER_API_DAILY_LIMIT`). Redis 카운터로 추적.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `springboot/src/main/java/com/chs/springboot/domain/weather/`

### REST 엔드포인트 — `WeatherController` (`@RequestMapping("/api/weather")`, `@CrossOrigin("*")`)
- `GET /api/weather/available-hours` → `WeatherRepository.findDistinctHours(today)`. 오늘(KST) 저장된 고유 시간(hour) 목록을 오름차순 반환. 예: `[0, 3, 6, 12]`.
  - DB 서버가 UTC라 `CURRENT_DATE`를 쓰면 KST 자정 직후 어제 날짜가 나오는 버그가 있어, 컨트롤러가 KST 기준 `LocalDate today`를 직접 계산해 넘긴다.
- `GET /api/weather/all?hour=N` → `WeatherService.getWeatherByHour(hour)`. `hour` 없으면 현재 정각 기준. 응답은 `Map<지역명, Map<항목, 값>>` (각 지역에 `tmp/hum/rain/wind/baseTime`).

### 조회 로직 — `WeatherService.getWeatherByHour(Integer hour)`
1. `targetHour` 결정: `hour`가 null이면 현재 정각, 요청 시각이 미래면 어제 같은 시각으로 보정.
2. `weatherRepository.findAllByFcstDateTime(targetHour)`로 DB 조회.
3. 결과가 10개 지역(`locations.size()`)을 채우면 API 없이 즉시 반환.
4. 부족하면 빠진 지역만 `fetchWeatherRecursive(...)`로 기상청 API 호출 → `saveIfAbsent(...)`로 DB 저장.

### 10개 시도 격자 좌표 — `locations` (LinkedHashMap, 삽입 순서 보장)
서울특별시(60,127), 경기도(60,120), 강원도(73,134), 충청북도(69,107), 충청남도(68,100), 전라북도(63,89), 경상북도(89,91), 전라남도(51,67), 경상남도(91,77), 제주특별자치도(52,38).

### 기상청 호출·재시도 — `fetchWeatherRecursive(...)`
- 최대 **5회** 재귀 재시도. 실패할 때마다 `dateTime.minusHours(1)`로 1시간 전 `base_time`을 시도(기상청 발표 지연 대응).
- 호출 직전 `incrementDailyCallCount(...)`로 카운트 증가 후 `[WeatherQuota]` 로그(count/limit/remaining).
- 응답은 `extractFcstData(json, currentHour)`가 파싱: `response.body.items.item` 배열에서 `fcstTime`이 목표 시각과 같은 항목의 T1H/REH/RN1/WSD를 추출.
- **재시도 전 DB 재확인**: API 실패 시 `findByRegionAndFcstDateTime(region, originalTargetHour)`로 다른 컨테이너가 이미 저장했는지 확인. 있으면 API 재호출 없이 DB 값 반환.
- 재시도 사이 `Thread.sleep(200)`(연속 호출 방지).

### 중복 저장 방지 — `saveIfAbsent(...)` + 엔티티 제약
- `existsByRegionAndFcstDateTime(name, fcstDT)`로 사전 확인하고, 예보 시각의 hour가 `targetHour`의 hour와 같을 때만 저장(재시도로 받은 과거 시각은 저장 안 함).
- `WeatherEntity`(테이블 `weather_history`): PK `id`(IDENTITY), 유니크 제약 `uq_region_fcstdatetime(region, fcstDateTime)`, 인덱스 `idx_region_fcst`. 필드: `region`(notnull), `nx`, `ny`, `fcstDateTime`(notnull), `tmp/hum/rain/wind`(문자열), `regDateTime`(`@PrePersist`로 자동 기록, `updatable=false`).

### API 호출량 관리 (Redis) — `incrementDailyCallCount(now)`
- 키 형식 `weather:api:call-count:yyyyMMdd`. 그날 첫 호출(count==1)일 때 당일 23:59:59 만료 TTL을 건다. 날짜별 독립 카운트.

### 외부 스케줄러
- 주기 자동 수집 스케줄러는 weather 도메인 밖 `springboot/src/main/java/com/chs/springboot/external/WeatherScheduler.java`에 있다(`WeatherService`를 호출). 토글: `SCHEDULING_ENABLED`.

### 설정값(.env)
- `WEATHER_API_SERVICE_KEY`(인증키), `WEATHER_API_BASE_URL`(기상청 기본 URL), `WEATHER_API_DAILY_LIMIT`(기본 10000).

### 타임존 주의
- 앱 서버는 KST, DB 서버는 UTC를 쓰므로 "오늘" 계산은 앱에서 KST로 한 뒤 파라미터로 넘겨 DB 타임존 영향을 차단한다.

## 연관 도메인
- 프론트 화면: `fe-page-weather`(Cesium 3D 지구본 시각화), `fe-domain-weather`. 상세 관계는 `index.md`.
