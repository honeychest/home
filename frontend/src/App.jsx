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
    const [obsTime, setObsTime] = useState("");
    const [isMobile, setIsMobile] = useState(window.innerWidth < 768);

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

    // 3. 날씨 데이터 페칭 및 시간 설정 로직
    useEffect(() => {
        fetch('/api/weather/all')
            .then(res => res.json())
            .then(data => {
                const sorted = GEO_ORDER.map(name => ({
                    name, ...data[name], tmp: parseFloat(data[name]?.tmp || 0)
                }));

                // 💡 시간 추출 및 포맷팅 (예: 1400 -> 14:00)
                const firstValidData = Object.values(data).find(d => d.baseTime);
                if (firstValidData && firstValidData.baseTime) {
                    const t = firstValidData.baseTime;
                    setObsTime(t.length === 4 ? `${t.substring(0, 2)}:${t.substring(2, 4)}` : t);
                }

                const temps = sorted.map(d => d.tmp);
                const minT = Math.min(...temps);
                const maxT = Math.max(...temps);
                setWeatherList(sorted);
                setRange({ min: minT, max: maxT });

                Cesium.GeoJsonDataSource.load('/data/korea.json').then(ds => {
                    viewerRef.current.dataSources.add(ds);
                    ds.entities.values.forEach(entity => {
                        const name = entity.properties.name?._value || "";
                        let target = null;
                        for (const [c, p] of Object.entries(CITY_TO_PROVINCE)) if (name.includes(c)) target = p;
                        if (!target) target = GEO_ORDER.find(n => name.includes(n));

                        const regionData = sorted.find(d => d.name === target);
                        if (regionData) entity.polygon.material = getRelativeColor(regionData.tmp, minT, maxT);
                    });
                });
            });
    }, []);

    return (
        <div style={{ width: '100vw', height: '100vh', position: 'relative', overflow: 'hidden', backgroundColor: '#000' }}>
            <div ref={cesiumContainer} style={{ width: '100%', height: '100%' }} />

            {/* 좌측 패널: 전국 기온 */}
            <Draggable nodeRef={nodeRef} bounds="parent" handle=".drag-handle">
                <div ref={nodeRef} style={{
                    position: 'absolute', top: '15px', left: '15px',
                    width: isCollapsed ? '90px' : (isMobile ? '160px' : '230px'),
                    backgroundColor: 'rgba(0, 0, 0, 0.75)', color: 'white', padding: '12px',
                    borderRadius: '12px', zIndex: 1000, transition: 'width 0.2s'
                }}>
                    <div className="drag-handle" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'move' }}>
                        <span style={{ fontSize: isMobile ? '11px' : '13px', fontWeight: 'bold' }}>
                            {isCollapsed ? '🌡️' : `전국 기온 (${obsTime || '--:--'})`}
                        </span>
                        <button onPointerDown={(e) => { e.stopPropagation(); setIsCollapsed(!isCollapsed); }}
                                style={{ background: '#444', border: 'none', color: '#fff', fontSize: '10px', padding: '2px 5px', borderRadius: '4px', cursor: 'pointer' }}>
                            {isCollapsed ? '펼치기' : '접기'}
                        </button>
                    </div>
                    {!isCollapsed && (
                        <div style={{ marginTop: '10px', maxHeight: '45vh', overflowY: 'auto' }}>
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
                .cesium-widget-credits, .cesium-viewer-helpButtonContainer { display: none !important; }
                .cesium-viewer-fullscreenContainer { bottom: 20px !important; right: 20px !important; }
                ::-webkit-scrollbar { width: 3px; }
                ::-webkit-scrollbar-thumb { background: #555; borderRadius: 2px; }
            `}</style>
        </div>
    );
}

export default App;