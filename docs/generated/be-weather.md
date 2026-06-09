# Weather 도메인

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요
- 데이터 모델 및 스키마 구조
- API 엔드포인트 명세
- 날씨 데이터 조회 전략 (Cache-First)
- 기상청 API 연동 및 재귀적 재시도 로직
- 데이터 정합성 및 중복 저장 방지 메커니즘
- API 호출량 관리 및 Redis 활용 전략

## 개요

이 도메인은 전국 10개 시도의 날씨 데이터를 제공하며, DB 우선(Cache-First) 전략을 통해 효율적인 데이터 조회를 수행합니다.

`WeatherController`는 프론트엔드의 요청을 받는 진입점으로, `getAvailableHours()` 메서드를 통해 DB에 저장된 시간 목록을 반환하고 `getAllWeather()` 메서드를 통해 특정 시간대의 전국 날씨 데이터를 제공합니다.

`WeatherService`는 핵심 비즈니스 로직을 담당하며, 특정 시간대의 날씨를 조회할 때 먼저 `WeatherRepository`를 통해 DB 데이터를 확인합니다. 만약 해당 시간의 데이터가 부족할 경우, 기상청 API를 호출하여 데이터를 가져온 후 `WeatherEntity`로 DB에 저장합니다. API 호출 시 데이터가 부족하거나 실패할 경우를 대비해 최대 5회까지 재귀적으로 호출하는 `fetchWeatherRecursive()` 로직을 포함하며, API 실패 시 1시간 전 데이터로 재시도하거나 이미 다른 프로세스에 의해 저장된 데이터가 있는지 DB를 확인하는 방어 로직을 갖추고 있습니다.

데이터 모델인 `WeatherEntity`는 `weather_history` 테이블과 매핑되며, 지역(`region`)과 예보 시각(`fcstDateTime`)의 조합을 유니크 제약 조건으로 관리하여 데이터 중복을 방지합니다. `WeatherRepository`는 JPA를 통해 특정 예보 시간의 데이터를 조회하거나, KST(Asia/Seoul) 기준으로 계산된 오늘 날짜의 고유 시간대 목록을 추출하는 기능을 제공합니다.

## 데이터 모델 및 스키마 구조

`WeatherEntity` 클래스는 `weather_history` 테이블과 매핑되는 엔티티로, 날씨 예보 데이터를 저장합니다. (`springboot/src/main/java/com/chs/springboot/domain/weather/model/WeatherEntity.java`)

### 테이블 구조 및 제약 조건
*   **PK (Primary Key)**: `id` 필드는 `GenerationType.IDENTITY` 전략을 사용하는 식별자입니다. (`springboot/src/main/java/com/chs/springboot/domain/weather/model/WeatherEntity.java`)
*   **Unique Constraints**: `region`과 `fcstDateTime` 컬럼의 조합에 대해 `uq_region_fcstdatetime`이라는 이름의 유니크 제약 조건이 설정되어 있습니다. (`springboot/src/main/java/com/chs/springboot/domain/weather/model/WeatherEntity.java`)
*   **Indexes**: `region`과 `fcstDateTime` 컬럼을 대상으로 하는 `idx_region_fcst` 인덱스가 정의되어 있습니다. (`springboot/src/main/java/com/chs/springboot/domain/weather/model/WeatherEntity.java`)

### 주요 필드 상세
*   **지역 및 좌표**: `region`은 지역명을 저장하며, `nx`와 `ny`는 기상청 격자 좌표를 저장합니다. (`springboot/src/main/java/com/chs/springboot/domain/weather/model/WeatherEntity.java`)
*   **예보 시각**: `fcstDateTime`은 예보된 날짜와 시간을 저장하는 필수(`nullable = false`) 필드입니다. (`springboot/src/main/java/com/chs/springboot/domain/weather/model/WeatherEntity.java`)
*   **날씨 데이터**: 기온(`tmp`), 습도(`hum`), 강수량(`rain`), 풍속(`wind`) 정보를 문자열 형태로 저장합니다. (`springboot/src/main/java/com/chs/springboot/domain/weather/model/WeatherEntity.java`)
*   **등록 시각**: `regDateTime`은 데이터가 생성될 때 `@PrePersist`를 통해 현재 시각이 자동으로 기록되며, 수정이 불가능(`updatable = false`)하도록 설정되어 있습니다. (`springboot/src/main/java/com/chs/springboot/domain/weather/model/WeatherEntity.java`)

## API 엔드포인트 명세

### API 엔드포인트 명세

**1. 사용 가능 시간 목록 조회**
- **URL:** `/api/weather/available-hours`
- **Method:** `GET`
- **Description:** DB에 저장된 오늘의 시간대 목록을 반환합니다. `WeatherRepository.findDistinctHours(today)`를 통해 오늘 날짜 기준으로 저장된 고유한 시간(`hour`) 목록을 오름차순으로 조회합니다.
- **Response Body:** `List<Integer>` (예: `[0, 3, 6, 12]`)
- **Source:** `springboot/src/main/java/com/chs/springboot/domain/weather/controller/WeatherController.java`

**2. 전국 날씨 데이터 조회**
- **URL:** `/api/weather/all`
- **Method:** `GET`
- **Parameters:** 
    - `hour` (Integer, Optional): 조회할 시간대(0~23). 파라미터가 없을 경우 현재 시각을 기준으로 조회합니다.
- **Description:** 전국 10개 시도의 날씨 데이터를 반환합니다. `WeatherService.getWeatherByHour(hour)`를 호출하여 동작하며, 다음과 같은 로직을 따릅니다.
    1. `targetHour`를 계산합니다. 요청된 시간이 현재보다 미래인 경우 어제 날짜의 해당 시간으로 설정됩니다.
    2. `WeatherRepository.findAllByFcstDateTime(targetHour)`를 통해 DB에서 해당 시간의 데이터를 조회합니다.
    3. 10개 지역 데이터가 모두 존재하면 즉시 반환합니다.
    4. 데이터가 부족한 경우 `WeatherService.fetchWeatherRecursive`를 통해 기상청 API를 호출하고, 결과값을 `WeatherEntity`로 DB에 저장합니다.
- **Response Body:** `Map<String, Map<String, String>>` (지역명을 키로 하며, 각 지역별 날씨 정보가 중첩 객체로 포함됩니다.)
    - 예시: `{"서울특별시": {"tmp": "5", "hum": "60", "wind": "2.0", "rain": "0", "baseTime": "1400"}, ...}`
- **Source:** `springboot/src/main/java/com/chs/springboot/domain/weather/controller/WeatherController.java`, `springboot/src/main/java/com/chs/springboot/domain/weather/service/WeatherService.java`

## 날씨 데이터 조회 전략 (Cache-First)

전국 10개 시도의 날씨 데이터를 제공하기 위해 DB 우선(Cache-First) 전략을 사용한다. `WeatherService.java`의 `getWeatherByHour` 메서드는 먼저 DB를 조회하여 해당 시간대의 데이터를 확보한다.

1. **DB 데이터 우선 조회**: `WeatherService.java`는 `weatherRepository.findAllByFcstDateTime(targetHour)`를 호출하여 특정 예보 시각에 해당하는 모든 지역 데이터를 DB에서 먼저 가져온다.
2. **충분성 판단 및 즉시 반환**: DB에서 조회된 데이터(`entities`)를 기반으로 생성된 결과 맵의 크기가 `locations`에 등록된 지역 수(10개)와 일치하면, 추가적인 API 호출 없이 즉시 결과를 반환한다.
3. **부족한 데이터 보완**: DB에 데이터가 충분하지 않을 경우, `locations`를 순회하며 아직 결과 맵에 포함되지 않은 지역에 대해서만 기상청 API를 호출한다.
4. **API 호출 및 DB 저장**: `fetchWeatherRecursive`를 통해 가져온 데이터는 `saveIfAbsent` 메서드를 통해 DB에 저장된다. 이때 `weatherRepository.existsByRegionAndFcstDateTime`를 사용하여 중복 저장을 방지하며, `WeatherEntity.java`의 `@UniqueConstraint` 설정에 따른 오류를 사전 차단한다.
5. **재시도 시 DB 체크**: API 호출 실패 시 수행되는 재귀적 재시도 과정(`fetchWeatherRecursive`)에서는 `weatherRepository.findByRegionAndFcstDateTime`를 통해 다른 컨테이너나 이전 시도에 의해 이미 저장된 데이터가 있는지 확인한다. 데이터가 존재하면 API를 재호출하지 않고 DB 캐시를 활용하여 응답한다.

## 기상청 API 연동 및 재귀적 재시도 로직

기상청 API 호출은 `WeatherService.java`의 `fetchWeatherRecursive` 메서드를 통해 수행됩니다. 이 메서드는 특정 지역의 날씨 데이터를 가져오기 위해 `baseUrl`과 `serviceKey`, 그리고 계산된 `baseDate`, `baseTime`, 격자 좌표(`nx`, `ny`)를 조합한 URL로 `restTemplate.getForObject`를 호출합니다.

데이터 추출 및 재시도 로직은 다음과 같은 단계로 진행됩니다:

1.  **데이터 추출**: API 호출 성공 시 `extractFcstData` 메서드가 JSON 응답을 파싱합니다. `response.body.items.item` 경로를 통해 배열을 탐색하며, `currentHour`와 일치하는 항목의 `category`(T1H, REH, RN1, WSD)를 식별하여 기온, 습도, 강수량, 풍속 데이터를 추출합니다.
2.  **재시도 전 DB 검증**: API 호출 실패 또는 데이터 부재 시, `weatherRepository.findByRegionAndFcstDateTime`를 사용하여 해당 지역에 이미 저장된 데이터가 있는지 확인합니다. 만약 `originalTargetHour`와 일치하는 캐시 데이터가 존재하면 API 재호출 없이 해당 데이터를 반환합니다.
3.  **재귀적 재시도**: API 호출이 실패하거나 유효한 데이터를 얻지 못한 경우, `fetchWeatherRecursive`를 재귀적으로 호출합니다. 이때 `dateTime.minusHours(1)`을 통해 1시간 전의 `base_time`으로 설정하여 기상청 데이터 발표 지연에 대응합니다.
4.  **종료 조건**: `retryCount`가 5에 도달하면 재귀 호출을 중단하고 빈 맵을 반환합니다.

## 데이터 정합성 및 중복 저장 방지 메커니즘

데이터 정합성을 유지하고 중복 저장을 방지하기 위해 다음과 같은 메커니즘을 적용한다.

첫째, 데이터베이스 수준에서 `region`과 `fcstDateTime` 컬럼을 결합한 유니크 제약 조건(`uq_region_fcstdatetime`)을 설정하여 동일 지역의 중복된 예보 데이터가 삽입되는 것을 방지한다. (`springboot/src/main/java/com/chs/springboot/domain/weather/model/WeatherEntity.java`)

둘째, 서비스 계층에서 API 호출 및 저장 시점에 사전 체크 로직을 수행한다. `saveIfAbsent` 메서드는 API를 통해 획득한 데이터의 예보 시각(`fcstDT`)이 요청된 대상 시간(`targetHour`)과 일치하는지 확인하며, `weatherRepository.existsByRegionAndFcstDateTime`를 호출하여 해당 지역과 시각의 데이터가 이미 존재하는지 검증한 후 저장을 진행한다. (`springboot/src/main/java/com/chs/springboot/domain/weather/service/WeatherService.java`)

셋째, API 재시도(Retry) 과정에서의 정합성을 관리한다. `fetchWeatherRecursive` 메서드는 API 호출 실패 시 재귀적으로 이전 시간 데이터를 조회할 수 있는데, 이때 `findByRegionAndFcstastDateTime`을 통해 다른 컨테이너나 이전 시도에 의해 이미 저장된 데이터가 있는지 확인한다. 만약 DB에 데이터가 존재하면 추가적인 API 호출 없이 해당 데이터를 반환하여 불필요한 중복 요청과 데이터 혼선을 차단한다. (`springboot/src/main/java/com/chs/springboot/domain/weather/service/WeatherService.java`, `springboot/src/main/java/com/chs/springboot/domain/weather/repository/WeatherRepository.java`)

## API 호출량 관리 및 Redis 활용 전략

기상청 API의 일일 호출 상한(dailyLimit)을 관리하기 위해 Redis를 활용하여 호출 카운트를 기록합니다. `WeatherService.java`의 `incrementDailyCallCount` 메서드는 호출 시점에 `weather:api:call-count:yyyyMMdd` 형식의 키를 생성하여 Redis에 저장합니다.

해당 키는 `incrementDailyCallCount` 메서드 내에서 호출 횟수가 처음으로 증가할 때(count == 1L), 당일 23:59:59에 만료되도록 TTL(Time To Live)이 설정됩니다. 이를 통해 날짜별로 독립적인 호출 카운트가 관리되며, `WeatherService.java`의 `fetchWeatherRecursive` 메서드 내에서 로그를 통해 현재 호출 횟수(`callCount`)와 남은 허용량(`remaining`)을 모니터링할 수 있습니다.
