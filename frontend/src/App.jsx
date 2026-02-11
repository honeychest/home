import { useEffect, useRef, useState } from 'react';
import * as Cesium from 'cesium';
import "cesium/Build/Cesium/Widgets/widgets.css";
import { getRelativeColor } from "./utils/weatherUtils";
import Draggable from 'react-draggable';

const GEO_ORDER = ["서울특별시", "경기도", "강원도", "충청북도", "충청남도", "전라북도", "경상북도", "전라남도", "경상남도", "제주특별자치도"];
const CITY_TO_PROVINCE = { "광주": "전라남도", "대구": "경상북도", "대전": "충청남도", "울산": "경상남도", "부산": "경상남도", "인천": "경기도", "세종": "충청남도" };

function App() {
    const cesiumContainer = useRef(null);
    const viewerRef = useRef(null);
    const nodeRef = useRef(null);
    const selectedEntityRef = useRef(null);

    const [weatherList, setWeatherList] = useState([]);
    const [range, setRange] = useState({ min: 0, max: 0 });
    const [selectedRegion, setSelectedRegion] = useState(null);
    const [isCollapsed, setIsCollapsed] = useState(false);
    const [isMobile, setIsMobile] = useState(window.innerWidth < 768);

    // 🆕 현재 시간과 사용 가능한 시간 목록
    const [selectedHour, setSelectedHour] = useState(new Date().getHours());
    const [availableHours, setAvailableHours] = useState([]);
    const [isInitialLoading, setIsInitialLoading] = useState(true); // 초기 로딩만 추적
    const [isTimePickerOpen, setIsTimePickerOpen] = useState(false); // 시간 선택 팝업

    // 1. 화면 크기 감지 및 모바일 대응
    useEffect(() => {
        const handleResize = () => setIsMobile(window.innerWidth < 768);
        window.addEventListener('resize', handleResize);
        return () => window.removeEventListener('resize', handleResize);
    }, []);

    // 2. Cesium 초기화 및 클릭 핸들러
    useEffect(() => {
        if (!cesiumContainer.current) return;

        const viewer = new Cesium.Viewer(cesiumContainer.current, {
            terrainProvider: null,
            animation: false,
            timeline: false,
            baseLayerPicker: false,
            infoBox: false,
            selectionIndicator: false,
            fullscreenButton: true,
        });
        viewerRef.current = viewer;
        viewer.camera.setView({ destination: Cesium.Cartesian3.fromDegrees(127.5, 36.0, 1300000.0) });

        const handler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas);
        handler.setInputAction((click) => {
            const pickedObject = viewer.scene.pick(click.position);

            if (selectedEntityRef.current) {
                const prev = selectedEntityRef.current;
                prev.polygon.outlineColor = Cesium.Color.WHITE.withAlpha(0.5);
                prev.polygon.outlineWidth = 1;
                prev.polygon.extrudedHeight = 0;
            }

            if (Cesium.defined(pickedObject) && pickedObject.id) {
                const entity = pickedObject.id;
                let h = 0;
                entity.polygon.outlineColor = Cesium.Color.GRAY;
                entity.polygon.outlineWidth = 4;
                entity.polygon.extrudedHeight = new Cesium.CallbackProperty(() => {
                    if (h < 60000) h += 6000;
                    return h;
                }, false);

                selectedEntityRef.current = entity;
                viewer.flyTo(entity, {
                    offset: new Cesium.HeadingPitchRange(0, Cesium.Math.toRadians(-75), 700000),
                    duration: 1.2
                });

                const fullName = entity.properties.name?._value || "";
                let mappingName = null;
                for (const [city, province] of Object.entries(CITY_TO_PROVINCE)) {
                    if (fullName.includes(city)) { mappingName = province; break; }
                }
                if (!mappingName) mappingName = GEO_ORDER.find(name => fullName.includes(name));

                setWeatherList(prev => {
                    const found = prev.find(w => w.name === mappingName);
                    if (found) setSelectedRegion({ ...found, displayName: fullName });
                    return prev;
                });
            } else {
                setSelectedRegion(null);
                selectedEntityRef.current = null;
            }
        }, Cesium.ScreenSpaceEventType.LEFT_CLICK);

        return () => { handler.destroy(); viewer.destroy(); };
    }, []);

    // 3. 🆕 사용 가능한 시간 목록 조회 (초기 로딩 시)
    useEffect(() => {
        fetch('/api/weather/available-hours')
            .then(res => res.json())
            .then(hours => {
                setAvailableHours(hours);
                // 사용 가능한 시간 중 가장 최근 시간을 기본값으로
                if (hours.length > 0) {
                    setSelectedHour(Math.max(...hours));
                }
            })
            .catch(err => {
                console.error('사용 가능한 시간 조회 실패:', err);
                // 실패 시 현재 시간부터 0시까지 표시
                const currentHour = new Date().getHours();
                const fallbackHours = Array.from({ length: currentHour + 1 }, (_, i) => i);
                setAvailableHours(fallbackHours);
            });
    }, []);

    // 4. 날씨 데이터 페칭 함수
    const fetchWeatherData = (hour) => {
        const url = `/api/weather/all?hour=${hour}`;

        fetch(url)
            .then(res => res.json())
            .then(data => {
                const sorted = GEO_ORDER.map(name => ({
                    name, ...data[name], tmp: parseFloat(data[name]?.tmp || 0)
                }));

                const temps = sorted.map(d => d.tmp);
                const minT = Math.min(...temps);
                const maxT = Math.max(...temps);
                setWeatherList(sorted);
                setRange({ min: minT, max: maxT });

                // 🆕 날씨 데이터를 받은 후 GeoJSON 로드 및 색상 적용
                if (viewerRef.current && viewerRef.current.dataSources.length === 0) {
                    // 첫 로딩: GeoJSON 로드 후 즉시 색상 적용
                    Cesium.GeoJsonDataSource.load('/data/korea.json').then(ds => {
                        viewerRef.current.dataSources.add(ds);

                        // GeoJSON 로드 직후 즉시 색상 적용
                        ds.entities.values.forEach(entity => {
                            const name = entity.properties.name?._value || "";
                            let target = null;
                            for (const [c, p] of Object.entries(CITY_TO_PROVINCE)) {
                                if (name.includes(c)) {
                                    target = p;
                                    break;
                                }
                            }
                            if (!target) target = GEO_ORDER.find(n => name.includes(n));

                            const regionData = sorted.find(d => d.name === target);
                            if (regionData) {
                                entity.polygon.material = getRelativeColor(regionData.tmp, minT, maxT);
                            }
                        });

                        setIsInitialLoading(false); // 초기 로딩 완료
                    });
                } else {
                    // 이미 로드된 경우: 색상만 업데이트 (스피너 안 뜸)
                    updateMapColors(sorted, minT, maxT);
                }
            })
            .catch(err => {
                console.error('날씨 데이터 로드 실패:', err);
                setIsInitialLoading(false); // 에러 발생 시도 스피너 숨김
            });
    };

    // 지도 색상 업데이트 함수
    const updateMapColors = (sorted, minT, maxT) => {
        if (!viewerRef.current) return;

        const dataSources = viewerRef.current.dataSources;
        if (dataSources.length > 0) {
            const ds = dataSources.get(0);
            ds.entities.values.forEach(entity => {
                const name = entity.properties.name?._value || "";
                let target = null;
                for (const [c, p] of Object.entries(CITY_TO_PROVINCE)) if (name.includes(c)) target = p;
                if (!target) target = GEO_ORDER.find(n => name.includes(n));

                const regionData = sorted.find(d => d.name === target);
                if (regionData) entity.polygon.material = getRelativeColor(regionData.tmp, minT, maxT);
            });
        }
    };

    // 5. 시간 변경 시 데이터 재조회
    useEffect(() => {
        if (selectedHour !== null) {
            fetchWeatherData(selectedHour);
        }
    }, [selectedHour]);

    // 🆕 팝업 외부 클릭 시 닫기
    useEffect(() => {
        const handleClickOutside = () => {
            if (isTimePickerOpen) {
                setIsTimePickerOpen(false);
            }
        };

        if (isTimePickerOpen) {
            document.addEventListener('click', handleClickOutside);
        }

        return () => {
            document.removeEventListener('click', handleClickOutside);
        };
    }, [isTimePickerOpen]);

    return (
        <div style={{ width: '100vw', height: '100vh', position: 'relative', overflow: 'hidden', backgroundColor: '#000' }}>
            <div ref={cesiumContainer} style={{ width: '100%', height: '100%' }} />

            {/* 🆕 초기 로딩 스피너 */}
            {isInitialLoading && (
                <div style={{
                    position: 'absolute',
                    top: '50%',
                    left: '50%',
                    transform: 'translate(-50%, -50%)',
                    textAlign: 'center',
                    zIndex: 9999
                }}>
                    <div style={{
                        width: '50px',
                        height: '50px',
                        border: '5px solid rgba(255, 255, 255, 0.3)',
                        borderTop: '5px solid #00d4ff',
                        borderRadius: '50%',
                        animation: 'spin 1s linear infinite',
                        margin: '0 auto 15px'
                    }} />
                    <div style={{
                        color: 'white',
                        fontSize: '16px',
                        fontWeight: 'bold',
                        textShadow: '0 2px 4px rgba(0,0,0,0.5)'
                    }}>
                        날씨 데이터 로딩 중...
                    </div>
                </div>
            )}

            {/* 좌측 패널: 전국 기온 */}
            <Draggable nodeRef={nodeRef} bounds="parent" handle=".drag-handle">
                <div ref={nodeRef} style={{
                    position: 'absolute', top: '15px', left: '15px',
                    width: isCollapsed ? '90px' : (isMobile ? '180px' : '250px'),
                    backgroundColor: 'rgba(0, 0, 0, 0.75)', color: 'white', padding: '12px',
                    borderRadius: '12px', zIndex: 1000, transition: 'width 0.2s'
                }}>
                    {/* 헤더에 시간 선택 통합 */}
                    <div style={{ marginBottom: isCollapsed ? '0' : '10px' }}>
                        <div className="drag-handle" style={{
                            display: 'flex',
                            justifyContent: 'space-between',
                            alignItems: 'center',
                            cursor: 'move'
                        }}>
                            {isCollapsed ? (
                                <span style={{ fontSize: '11px', fontWeight: 'bold' }}>🌡️</span>
                            ) : (
                                <div style={{ display: 'flex', alignItems: 'center', gap: '6px', flex: 1 }}>
                                    <span style={{ fontSize: isMobile ? '11px' : '13px', fontWeight: 'bold' }}>
                                        전국 기온
                                    </span>
                                    {/* 🆕 커스텀 시간 선택 버튼 */}
                                    <button
                                        onClick={(e) => {
                                            e.stopPropagation();
                                            setIsTimePickerOpen(!isTimePickerOpen);
                                        }}
                                        style={{
                                            padding: '2px 6px',
                                            backgroundColor: '#333',
                                            color: '#00d4ff',
                                            border: '1px solid #555',
                                            borderRadius: '4px',
                                            fontSize: isMobile ? '10px' : '12px',
                                            fontWeight: 'bold',
                                            cursor: 'pointer',
                                            display: 'flex',
                                            alignItems: 'center',
                                            gap: '3px'
                                        }}
                                    >
                                        ({selectedHour.toString().padStart(2, '0')}:00)
                                        <span style={{ fontSize: '8px' }}>▼</span>
                                    </button>
                                </div>
                            )}
                            <button
                                onPointerDown={(e) => { e.stopPropagation(); setIsCollapsed(!isCollapsed); }}
                                style={{
                                    background: '#444',
                                    border: 'none',
                                    color: '#fff',
                                    fontSize: '10px',
                                    padding: '2px 5px',
                                    borderRadius: '4px',
                                    cursor: 'pointer'
                                }}
                            >
                                {isCollapsed ? '펼치기' : '접기'}
                            </button>
                        </div>

                        {/* 🆕 시간 선택 팝업 */}
                        {!isCollapsed && isTimePickerOpen && (
                            <div
                                style={{
                                    position: 'absolute',
                                    top: '50px',
                                    left: '15px',
                                    backgroundColor: 'rgba(20, 20, 20, 0.98)',
                                    border: '1px solid #00d4ff',
                                    borderRadius: '8px',
                                    padding: '8px',
                                    zIndex: 10000,
                                    maxHeight: '200px',
                                    overflowY: 'auto',
                                    boxShadow: '0 4px 12px rgba(0, 212, 255, 0.3)'
                                }}
                                onClick={(e) => e.stopPropagation()}
                            >
                                <div style={{
                                    display: 'grid',
                                    gridTemplateColumns: 'repeat(4, 1fr)',
                                    gap: '6px'
                                }}>
                                    {availableHours.map(hour => (
                                        <button
                                            key={hour}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                setSelectedHour(hour);
                                                setIsTimePickerOpen(false);
                                            }}
                                            style={{
                                                padding: '8px 4px',
                                                backgroundColor: hour === selectedHour ? '#00d4ff' : '#333',
                                                color: hour === selectedHour ? '#000' : '#fff',
                                                border: 'none',
                                                borderRadius: '4px',
                                                fontSize: '11px',
                                                fontWeight: hour === selectedHour ? 'bold' : 'normal',
                                                cursor: 'pointer',
                                                transition: 'all 0.2s'
                                            }}
                                        >
                                            {hour.toString().padStart(2, '0')}시
                                        </button>
                                    ))}
                                </div>
                            </div>
                        )}
                    </div>

                    {/* 날씨 목록 */}
                    {!isCollapsed && (
                        <div style={{ maxHeight: '45vh', overflowY: 'auto' }}>
                            {weatherList.map((item, i) => (
                                <div key={i} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', marginBottom: '4px' }}>
                                    <span>{item.name}</span>
                                    <span style={{ fontWeight: 'bold', color: getRelativeColor(item.tmp, range.min, range.max).toCssColorString() }}>{item.tmp}°C</span>
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            </Draggable>

            {/* 우측 상단: 상세 패널 */}
            {selectedRegion && (
                <div style={{
                    position: 'absolute', top: '15px', right: '15px', width: isMobile ? '150px' : '200px',
                    backgroundColor: 'rgba(15, 15, 15, 0.95)', color: 'white', padding: '12px',
                    borderRadius: '12px', zIndex: 2000, border: '1px solid #00d4ff', boxShadow: '0 4px 15px #000',
                    animation: 'fadeIn 0.2s ease-out'
                }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                        <b style={{ fontSize: '14px', color: '#00d4ff' }}>{selectedRegion.displayName.split(' ')[0]}</b>
                        <button onClick={() => { if (selectedEntityRef.current) selectedEntityRef.current.polygon.extrudedHeight = 0; setSelectedRegion(null); }}
                                style={{ background: 'none', border: 'none', color: '#fff', cursor: 'pointer', fontSize: '14px' }}>✕</button>
                    </div>
                    <div style={{ fontSize: '12px', display: 'flex', flexDirection: 'column', gap: '4px' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid #333', paddingBottom: '2px' }}><span>기온</span> <b>{selectedRegion.tmp}°C</b></div>
                        <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid #333', paddingBottom: '2px' }}><span>습도</span> <b>{selectedRegion.hum}%</b></div>
                        <div style={{ display: 'flex', justifyContent: 'space-between' }}><span>강수/풍속</span> <b>{selectedRegion.rain}㎜ / {selectedRegion.wind}m</b></div>
                    </div>
                </div>
            )}

            <style>{`
                @keyframes fadeIn { from { opacity: 0; transform: translateY(-10px); } to { opacity: 1; transform: translateY(0); } }
                @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
                .cesium-widget-credits, .cesium-viewer-helpButtonContainer { display: none !important; }
                .cesium-viewer-fullscreenContainer { bottom: 20px !important; right: 20px !important; }
                ::-webkit-scrollbar { width: 3px; }
                ::-webkit-scrollbar-thumb { background: #555; borderRadius: 2px; }
            `}</style>
        </div>
    );
}

export default App;