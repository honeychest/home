# 프론트 도메인: weather

> 이 문서는 로컬 LLM(gemma-4-26b-a4b-it-mlx)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.

## 목차
- 개요 및 도메인 구조
- 데이터 모델링 및 타입 정의
- 날씨 데이터 패칭 및 상태 관리 흐름
- Cesium 지도 초기화 및 렌더링 로직
- 기온 기반 색상 보간 및 시각화 알고리즘
- 지역 선택 인터랙션 및 오버레이 애니메이션 처리
- UI 컴포넌트 계층 구조 및 사용자 인터페이스 제어

## 개요 및 도메인 구조

이 도메인은 Cesium 기반의 3D 지구본 위에 전국 시도별 날씨 데이터를 시각화하여 제공하는 것을 목적으로 합니다.

데이터 흐름의 핵심은 서버로부터 수신한 날씨 데이터를 `GEO_ORDER`(`frontend/src/domain/weather/model/regions.ts`)에 정의된 지역 순서로 정렬하고, 이를 `useWeatherData` 훅(`frontend/src/domain/weather/model/hook/useWeatherData.ts`)을 통해 관리하는 것입니다. 수집된 기온 데이터는 `minT`와 `maxT` 범위를 기준으로 `getRelativeColor` 함수(`frontend/src/domain/weather/lib/weatherUtils.ts`)를 거쳐 각 지역 폴리곤의 색상으로 변환됩니다.

지도 렌더링은 `useCesiumMap` 훅(`frontend/src/domain/weather/model/hook/useCesiumMap.js`)이 담당하며, GeoJSON 데이터를 로드하여 `updateMapColors` 함수(`frontend/src/domain/weather/lib/cesiumUtils.js`)를 통해 지도상의 엔티티에 기온 색상을 적용합니다. 사용자가 특정 지역을 클릭하면 `handleCesiumClick` 함수(`frontend/src/domain/weather/lib/cesiumUtils.js`)가 실행되어 선택 오버레이 애니메이션을 처리하고, `WeatherDetail` 컴포넌트(`frontend/src/domain/weather/ui/detail/WeatherDetail.tsx`)를 통해 상세 정보를 팝업 형태로 표시합니다.

UI 구성은 크게 세 부분으로 나뉩니다. `CesiumMap` 컴포넌트(`frontend/src/domain/weather/ui/map/CesiumMap.jsx`)가 3D 지도를 화면에 출력하며, `WeatherPanel` 컴포넌트(`frontend/src/domain/weather/ui/panel/WeatherPanel.jsx`)는 지도 위에 드래그 가능한 오버레이 형태로 존재하며 실시간 기온 목록과 시간 선택 기능을 제공합니다. 마지막으로 `WeatherDetail` 컴포넌트(`frontend/src/domain/weather/ui/detail/WeatherDetail.tsx`)는 지역별 상세 기상 정보를 사용자에게 전달하는 역할을 수행합니다.

## 데이터 모델링 및 타입 정의

`WeatherData` 인터페이스는 서버에서 전달되는 날씨 데이터의 구조를 정의하며, Java 백엔드의 `WeatherHistory` 엔티티 필드와 매핑됩니다. `name`(지역명), `city`(상세 도시명), `tmp`(기온, number), `pop`(강수확률, string), `hum`(습도, string), `wind`(풍속, string), `rain`(강수량, string) 등의 필드를 포함합니다. 특히 기온(`tmp`)은 색상 보간 및 최솟값/최댓값 계산을 위해 `number` 타입으로 관리됩니다. (`frontend/src/domain/weather/lib/weatherUtils.ts`)

`WeatherDataItem` 인터페이스는 시도별 날씨 데이터 구조를 정의합니다. `name`(지역 이름), `tmp`(기온, number), `time`(예보 시간, string), `pop`, `hum`, `wind`, `rain` 필드를 포함하며, 서버에서 제공하는 추가적인 임의 필드를 허용하기 위해 인덱스 시그니처(`[key: string]: unknown`)를 사용합니다. (`frontend/src/domain/weather/model/hook/useWeatherData.ts`)

`WeatherDetail` 컴포넌트에서 사용되는 `WeatherData` 인터페이스는 팝업에 표시할 정보를 정의합니다. `city`(지역명), `displayTime`(예보 시각 문자열), `tmp`(기온, number), `pop`(강수확률, string), `hum`(습도, string), `wind`(풍속, string), `rain`(강수량, string) 필드를 포함합니다. (`frontend/src/domain/weather/ui/detail/WeatherDetail.tsx`)

## 날씨 데이터 패칭 및 상태 관리 흐름

날씨 데이터 패칭 및 상태 관리는 `useWeatherData.ts`를 중심으로 이루어지며, 다음과 같은 흐름을 따릅니다.

먼저 앱이 시작되면 `useWeatherData.ts`의 `useEffect`가 실행되어 `/api/weather/available-hours` 엔드포트로 사용 가능한 시간 목록을 조회합니다. 응답받은 데이터는 `availableHours` 상태에 저장되며, 가장 최근 시간 정보가 `selectedHour`로 자동 설정됩니다.

이후 `selectedHour` 값이 결정되면, 이를 의존성 배열로 가지는 두 번째 `useEffect`가 실행됩니다. 이 과정에서 `/api/weather/all?hour=${selectedHour}`를 호출하여 해당 시간대의 전국 날씨 데이터를 가져옵니다. 서버로부터 받은 JSON 데이터는 `GEO_ORDER`(`regions.ts`)에 정의된 지역 순서에 따라 정렬되며, 각 지역의 기온 문자열은 `parseFloat`을 통해 숫자형으로 변환되어 `weatherList` 상태에 저장됩니다. 이때 데이터의 최솟값과 최댓값은 `minT`와 `maxT`로 계산되어 지도 색상 범위를 결정하는 데 사용됩니다.

만약 API 호출 중 오류가 발생하면 `errorCode` 상태에 HTTP 상태 코드가 저장되며, 사용자가 `retry` 함수를 호출하면 `retryKey`가 변경되어 전체 데이터 패칭 프로세스가 처음부터 재시도됩니다. 최종적으로 가공된 `weatherList`는 `useCesiumMap.js`를 통해 지도에 색상으로 반영되고, `WeatherPanel.jsx`에서 지역별 기온 목록을 표시하는 데 사용됩니다.

## Cesium 지도 초기화 및 렌더링 로직

`useCesiumMap.js` 훅은 `cesiumContainer` ref를 통해 전달된 DOM 요소를 기반으로 `Cesium.Viewer` 인스턴스를 생성하여 초기화한다. 뷰어 설정 시 `requestRenderMode: true`를 적용하여 변경 사항이 있을 때만 렌더링하도록 최적화하며, `creditContainer`에 빈 `div`를 할당하여 기본 워터마크를 제거한다. 초기 카메라 위치는 `Cesium.Cartesian3.fromDegrees(127.5, 36.0, 1300000.0)`를 사용하여 한반도 전체가 보이도록 설정된다.

지도 데이터 로딩은 `useEffect` 내에서 수행되며, `dataSources.length === 0`인 최초 로드 시점에 `/data/korea.json` 파일을 `Cesium.GeoJsonDataSource.load`를 통해 불러온다. 로드된 데이터는 `viewer.dataSources.add(ds)`를 통해 뷰어에 추가된다. 이후 `weatherList`, `minT`, `maxT` 값이 변경될 때마다 `updateMapColors` 함수가 호출된다. 이 함수는 `ds.entities.values`를 순회하며 각 시도 폴리곤의 색상을 결정한다.

색상 적용 로직은 `weatherUtils.ts`의 `getRelativeColor`를 사용하여 기온을 색상으로 변환하며, `CITY_TO_PROVINCE` 및 `GEO_ORDER` 상수를 활용해 GeoJSON의 지역명과 날씨 데이터를 매핑한다. 또한, 모든 폴리곤 엔티티는 `BASE_HEIGHT` 값을 `entity.polygon.extrudedHeight`에 할당하여 `ExtrudedPolygon` 타입을 유지함으로써 색상 초기화 버그를 방지한다.

사용자 상호작용을 위해 `Cesium.ScreenSpaceEventHandler`를 사용하여 `LEFT_CLICK` 이벤트를 등록한다. 클릭 시 `handleCesiumClick` 함수가 호출되며, 이는 `selectedOverlayRef`를 사용하여 선택된 지역에 대한 오버레이 효과를 처리한다. 애니메이션은 `overlayAnimationFrameRef`를 통해 관리되며, 기존의 선택 상태는 `clearSelectionOverlay`를 통해 정리된 후 새로운 오버레이가 적용된다.

## 기온 기반 색상 보간 및 시각화 알고리즘

기온 데이터를 시각화하기 위해 전체 데이터셋의 최솟값(`minT`)과 최댓값(`maxT`)을 기준으로 상대적 위치를 계산하는 정규화 과정을 거칩니다. `getRelativeColor` 함수는 입력된 기온(`tmp`)을 기반으로 0에서 1 사이의 `fraction` 값을 산출합니다. 이때 `Math.max(0, Math.min(1, (tmp - minTmp) / (maxTmp - minTmp)))` 공식을 사용하여 값이 범위를 벗어나지 않도록 클램핑(clamping)합니다. (`weatherUtils.ts`)

계산된 `fraction` 값에 따라 5단계의 색상 스펙트럼이 적용됩니다. 색상은 `BLUE` → `CYAN` → `YELLOW` → `ORANGE` → `RED` 순으로 정의되며, 각 구간은 다음과 같이 처리됩니다.

*   **0.00 ~ 0.25 구간**: `BLUE`와 `CYAN` 사이를 선형 보간합니다.
*   **0.25 ~ 0.50 구간**: `CYAN`과 `YELLOW` 사이를 선형 보간합니다.
*   **0.50 ~ 0.75 구간**: `YELLOW`와 `ORANGE` 사이를 선형 보간합니다.
*   **0.75 ~ 1.00 구간**: `ORANGE`와 `RED` 사이를 선형 보간합니다.
(`weatherUtils.ts`)

두 색상 사이의 보간은 `lerp` 함수를 통해 수행됩니다. `lerp`는 각 RGB 채널에 대해 `a[i] + (b[i] - a[i]) * t` 공식을 적용하여 중간색을 생성하며, 결과값은 `Math.round`를 통해 정수형 RGB 값으로 변환됩니다. (`weatherUtils.ts`)

이렇게 생성된 색상은 `updateMapColors` 함수를 통해 GeoJSON 엔티티의 `polygon.material`에 할당되어 지도상의 지역별 색상으로 시각화됩니다. (`cesiumUtils.js`) 또한, `WeatherPanel` 컴포넌트 내의 지역별 기온 리스트에서도 동일한 알고리즘이 적용되어 텍스트 색상으로 표시됩니다. (`WeatherPanel.jsx`)

## 지역 선택 인터랙션 및 오버레이 애니메이션 처리

지역 선택 시 발생하는 인터랙션은 `cesiumUtils.js`의 `handleCesiumClick` 함수를 통해 수행됩니다. 사용자가 지도를 클릭하면 `viewer.scene.pick`을 통해 선택된 객체를 식별하며, 기존에 활성화되어 있던 오버레이는 `clearSelectionOverlay` 함수를 호출하여 애니메이션을 취소하고 높이 값을 초기화하며 제거합니다(`cesiumUtils.js`).

선택된 지역이 존재할 경우, `getOrCreateOverlayEntity`를 통해 재사용 가능한 오버레이 엔티티를 가져오거나 생성합니다(`cesiumUtils.js`). 이때 `baseEntity`의 `hierarchy`를 오버레이로 복제하여 지역의 형태를 유지하며, `resolveEntityColor`를 통해 해당 지역의 기존 색상을 추출하여 오버레이에 적용합니다(`cesiumUtils.js`).

시각적 효과를 위해 `animateOverlayRise` 함수가 호출됩니다. 이 함수는 오버레이의 `height`를 지면보다 낮은 `-100`으로 설정하여 지표면과의 겹침 현상(Z-fighting)을 방지한 뒤, `requestAnimationFrame`을 사용하여 `extrudedHeight`를 설정된 목표 높이(`TARGET_HEIGHT`)까지 점진적으로 상승시키는 애니메이션을 실행합니다(`cesiumUtils.js`).

애니메이션 과정에서 `viewer.scene.requestRender()`를 명시적으로 호출하여 프레임 변화를 반영하며, 최종적으로 `clickCallbackRef.current`를 통해 선택된 지역의 정보(`fullName`, `mappingName`, `screenPosition`)를 상위 컴포넌트로 전달합니다(`cesiumUtils.js`).

## UI 컴포넌트 계층 구조 및 사용자 인터페이스 제어

사용자 인터페이스는 `CesiumPage.jsx`를 중심으로 각 기능별 컴포넌트가 계층적으로 구성되어 있습니다.

**1. UI 컴포넌트 계층 구조**
*   **최상위 레이어**: `CesiumPage.jsx`가 상태 관리의 중심 역할을 하며, 하위 컴포넌트인 `CesiumMap.jsx`, `WeatherPanel.jsx`, `WeatherDetail.tsx`를 호출합니다.
*   집중된 **지도 레이어**: `CesiumMap.jsx`는 `useCesiumMap.js` 훅을 통해 Cesium Viewer를 초기화하고, 지도 위에 `WeatherPanel.jsx`와 같은 UI 요소를 오버레이합니다.
*   **패널 레이어**: `WeatherPanel.jsx`는 지도 위에 떠 있는 드래그 가능한 오버레이로, 전국 기온 목록과 시간 선택 기능을 제공합니다.
*   **상세 정보 레이어**: `WeatherDetail.tsx`는 사용자가 지도상의 특정 지역을 클릭했을 때 나타나는 팝업/바텀 시트 형태의 컴포넌트입니다.

**2. 사용자 인터페이스 제어 흐름**
*   **데이터 기반 색상 제어**: `useWeatherData.ts`에서 계산된 최저/최고 기온(`minT`, `maxT`)과 `weatherList`의 각 지역별 기온(`tmp`) 데이터는 `getRelativeColor` 함수(`weatherUtils.ts`)를 거쳐 지도 폴리곤 및 패널의 텍스트 색상으로 실시간 반영됩니다.
*   **지도 상호작용 및 오버레이 제어**: 
    *   `useCesiumMap.js` 내의 `handleCesiumClick` 함수가 사용자의 클릭 이벤트를 감지합니다.
    *   클릭 시 `selectedOverlayRef`를 통해 선택된 지역에 대한 강조 효과(애니메이션 포함)가 적용되며, 동시에 `clickCallbackRef`를 통해 부모 컴포넌트(`CesiumPage.jsx`)로 클릭 정보가 전달됩니다.
    *   전달된 데이터는 `WeatherDetail.tsx`를 렌더링하는 데 사용됩니다.
*   **반응형 및 위치 제어**: 
    *   `WeatherDetail.tsx`는 `isMobile` 상태에 따라 PC용 고정 팝업(`pcStyle`) 또는 모바일용 바텀 시트(`mobileStyle`)로 레이아웃을 전환합니다.
    *   `WeatherPanel.jsx`는 `react-draggable` 라이브러리를 사용하여 사용자가 패널의 위치를 직접 조정할 수 있도록 제어합니다.
*   **시간 선택 및 데이터 갱신**: `WeatherPanel.jsx`에서 사용자가 시간 버튼을 클릭하면 `setSelectedHour`가 호출되어 `useWeatherData.ts`의 상태를 변경하고, 이는 다시 API 재요청 및 지도 색상 갱신(`updateMapColors`)으로 이어지는 순환 구조를 가집니다.
