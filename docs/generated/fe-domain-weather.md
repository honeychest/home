# 프론트 도메인: weather

> wiki-refresh로 실제 소스를 읽고 검증함(2026-06-24). 페이지 간 관계는 `index.md` 참고.

## 한 줄 요약
Cesium 3D 지구본 위에 전국 시도별 날씨를 색으로 시각화하는 프론트 도메인. 기온을 전국 최저~최고 범위 대비 상대 색상(파랑~빨강)으로 칠하고, 지역 클릭 시 상세 팝업을 띄운다.

## 이런 걸 물을 때 찾으면 된다 (검색 키워드)
- "날씨 지도는 어떻게 그려져? Cesium 3D 지구본 시각화"
- "기온 색상은 어떻게 정해져? 색상 보간 / getRelativeColor"
- "날씨 데이터는 어디서 불러와? available-hours / weather/all"
- "광주는 왜 전라남도로 표시돼? CITY_TO_PROVINCE 매핑"
- "강수형태 코드 PTY 0~4 의미 / getPtyText"

## 핵심 개념·용어
- **Cesium**: WebGL 기반 3D 지구본 렌더링 라이브러리. `Cesium.Viewer`로 지도를 띄우고 GeoJSON 폴리곤으로 시도를 그린다.
- **상대 색상(getRelativeColor)**: 절대 온도가 아니라 *이번 예보 시각의 전국 최저(minT)~최고(maxT)* 범위 안에서 상대 위치(fraction 0~1)로 색을 정한다.
- **GEO_ORDER**: 10개 시도 렌더링/정렬 순서(서울→경기→강원→충청북→충청남→전북→경북→전남→경남→제주).
- **CITY_TO_PROVINCE**: 기상청이 광역시 단위로 주는 데이터를 시도 폴리곤에 매핑(광주→전라남도, 대구→경상북도, 대전·세종→충청남도, 울산·부산→경상남도, 인천→경기도).
- **fraction**: `(tmp - minTmp) / (maxTmp - minTmp)`를 0~1로 클램핑한 값. 색 구간 선택에 쓴다.

## 구조 / 흐름 (확인된 코드 기준)

파일 위치: `frontend/src/domain/weather/`

### 데이터 패칭 — `model/hook/useWeatherData.ts`
- 앱 시작 시 `GET /api/weather/available-hours`로 저장된 시간 목록을 받아 `availableHours`에 저장하고, 마지막(가장 최근) 시간을 `selectedHour`로 자동 선택.
- `selectedHour` 변경 시 `GET /api/weather/all?hour=${selectedHour}` 호출 → 응답을 `GEO_ORDER` 순서로 정렬하고 각 지역 `tmp`를 `parseFloat`로 숫자 변환해 `weatherList`에 저장.
- `minT`/`maxT` = `weatherList`의 tmp 최소/최대(빈 배열이면 0). 지도·패널 색상 범위 기준.
- 오류 시 `errorCode`에 HTTP 상태(네트워크 단절은 `'503'`) 저장, `retry()`는 `retryKey`를 올려 전체 체인 재요청.
- 반환: `{ weatherList, availableHours, selectedHour, setSelectedHour, isInitialLoading, minT, maxT, errorCode, retry }`.

### 색상 유틸 — `lib/weatherUtils.ts`
- `getRelativeColor(tmp, minTmp, maxTmp)`: minTmp===maxTmp면 흰색(`rgb(255,255,255)`). 아니면 fraction을 4구간으로 나눠 `lerp`(선형보간):
  - 0~0.25 BLUE→CYAN, 0.25~0.5 CYAN→YELLOW, 0.5~0.75 YELLOW→ORANGE, 0.75~1 ORANGE→RED. (BLUE[0,0,255]·CYAN[0,255,255]·YELLOW[255,255,0]·ORANGE[255,165,0]·RED[255,0,0])
- `getPtyText(pty)`: 기상청 강수형태 코드 → 한국어. `0` 맑음, `1` 비, `2` 비/눈, `3` 눈, `4` 소나기, 그 외 "정보 없음".
- `WeatherData` 인터페이스: `name, city?, tmp(number), pop, hum, wind, rain, displayTime?, time?`.

### 지역 상수 — `model/regions.ts`
- `GEO_ORDER`(10개 시도), `CITY_TO_PROVINCE`(광역시→시도) 위 표 참고.

### Cesium 지도 — `model/hook/useCesiumMap.js` + `lib/cesiumUtils.js`
- `useCesiumMap.js`: `Cesium.Viewer` 생성(`requestRenderMode: true`로 변경 시에만 렌더, 워터마크 제거), 초기 카메라 `fromDegrees(127.5, 36.0, 1300000)`로 한반도 전체. 최초 1회 `/data/korea.json`(GeoJSON) 로드. `weatherList/minT/maxT` 변경 시 `updateMapColors` 호출.
- `lib/cesiumUtils.js`: `updateMapColors`(폴리곤 색칠, `getRelativeColor` + `CITY_TO_PROVINCE`/`GEO_ORDER` 매핑, `BASE_HEIGHT`로 ExtrudedPolygon 유지), `handleCesiumClick`(LEFT_CLICK pick → 오버레이 애니메이션 `animateOverlayRise` → 클릭 정보 콜백). Z-fighting 방지로 오버레이 height -100에서 상승.

### UI 컴포넌트 — `ui/`
- `ui/map/CesiumMap.jsx`(지도), `ui/panel/WeatherPanel.jsx`(드래그 가능 오버레이 패널 — 기온 목록·시간 선택, `react-draggable`), `ui/detail/WeatherDetail.tsx`(지역 클릭 상세 팝업; `isMobile`이면 바텀시트). 상위 페이지는 `fe-page-weather`의 `CesiumPage.jsx`.

## 연관 도메인
- 백엔드 데이터: `be-weather`(`/api/weather/available-hours`, `/api/weather/all`). 상위 화면: `fe-page-weather`. 상세 관계는 `index.md`.
