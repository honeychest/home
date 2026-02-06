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
    const [weatherList, setWeatherList] = useState([]);
    const [range, setRange] = useState({ min: 0, max: 0 });

    // 💡 접기/펼치기 상태 추가 (기본값: 모바일이면 접힘, 웹이면 펼침)
    const [isCollapsed, setIsCollapsed] = useState(false);

    useEffect(() => {
        const viewer = new Cesium.Viewer(cesiumContainer.current, {
            terrainProvider: null, animation: false, timeline: false, baseLayerPicker: false, infoBox: false
        });
        viewerRef.current = viewer;
        viewer.scene.globe.depthTestAgainstTerrain = false;
        viewer.scene.fog.enabled = false;
        viewer.scene.skyAtmosphere.show = false;
        viewer.camera.setView({ destination: Cesium.Cartesian3.fromDegrees(127.5, 36.0, 1200000.0) });
        return () => viewer.destroy();
    }, []);

    useEffect(() => {
        fetch('/api/weather/all')
            .then(res => res.json())
            .then(data => {
                const sorted = GEO_ORDER.map(name => ({ name, tmp: parseFloat(data[name] || 0) }));
                const temps = sorted.map(d => d.tmp);
                const currentMin = Math.min(...temps);
                const currentMax = Math.max(...temps);

                setWeatherList(sorted);
                setRange({ min: currentMin, max: currentMax });

                const viewer = viewerRef.current;
                if (!viewer) return;
                viewer.dataSources.removeAll();

                Cesium.GeoJsonDataSource.load('/data/korea.json', {
                    stroke: Cesium.Color.WHITE.withAlpha(0.5), strokeWidth: 1
                }).then(dataSource => {
                    viewer.dataSources.add(dataSource);
                    dataSource.entities.values.forEach(entity => {
                        const fullName = entity.properties.name?._value || "";
                        let targetName = null;
                        for (const [city, province] of Object.entries(CITY_TO_PROVINCE)) {
                            if (fullName.includes(city)) { targetName = province; break; }
                        }
                        if (!targetName) targetName = GEO_ORDER.find(name => fullName.includes(name));

                        const regionData = sorted.find(d => d.name === targetName);
                        if (regionData) {
                            entity.polygon.material = getRelativeColor(regionData.tmp, currentMin, currentMax);
                        } else {
                            entity.polygon.material = Cesium.Color.WHITE.withAlpha(0.0);
                        }
                    });
                });
            });
    }, []);

    return (
        <div style={{ width: '100vw', height: '100vh', position: 'relative', overflow: 'hidden' }}>
            <div ref={cesiumContainer} style={{ width: '100%', height: '100%' }} />

            <Draggable
                nodeRef={nodeRef}
                bounds="parent"
                handle=".drag-handle"
                // 드래그 시작 시 버튼 위라면 드래그 무시
                onStart={(e) => {
                    if (e.target.closest('button')) return false;
                }}
            >
                <div ref={nodeRef} style={{
                    position: 'absolute', top: '20px', left: '20px',
                    width: isCollapsed ? '130px' : (window.innerWidth < 768 ? '210px' : '260px'),
                    backgroundColor: 'rgba(0, 0, 0, 0.85)', color: 'white', padding: '15px',
                    borderRadius: '15px', zIndex: 1000, userSelect: 'none',
                    transition: 'width 0.2s ease-out'
                }}>
                    {/* 헤더 부분 */}
                    <div className="drag-handle" style={{
                        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                        cursor: 'move', paddingBottom: isCollapsed ? '0' : '10px',
                        borderBottom: isCollapsed ? 'none' : '1px solid #444'
                    }}>
                        <h4 style={{ margin: 0, fontSize: '14px', whiteSpace: 'nowrap' }}>
                            📍 {isCollapsed ? '기온' : '전국 기온'}
                        </h4>
                        <button
                            // 💡 onClick 대신 onPointerDown을 사용하면 드래그보다 먼저 반응합니다.
                            onPointerDown={(e) => {
                                e.stopPropagation(); // 드래그 이벤트로 전파 차단
                                setIsCollapsed(!isCollapsed);
                            }}
                            style={{
                                background: '#555', border: 'none', color: 'white',
                                borderRadius: '4px', cursor: 'pointer', fontSize: '11px',
                                padding: '4px 8px', marginLeft: '10px', touchAction: 'none'
                            }}
                        >
                            {isCollapsed ? '펼치기' : '접기'}
                        </button>
                    </div>

                    {/* 리스트 부분: display 대신 height와 opacity로 제어해야 레이아웃이 깨지지 않습니다. */}
                    <div style={{
                        display: isCollapsed ? 'none' : 'block',
                        marginTop: '15px'
                    }}>
                        <div style={{ maxHeight: '50vh', overflowY: 'auto', paddingRight: '5px' }}>
                            {weatherList.map((item, idx) => {
                                const barColor = getRelativeColor(item.tmp, range.min, range.max).toCssColorString();
                                const diff = range.max - range.min;
                                const ratio = diff === 0 ? 0 : (item.tmp - range.min) / diff;
                                const barWidth = (ratio * 85) + 15;

                                return (
                                    <div key={idx} style={{ marginBottom: '10px' }}>
                                        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px' }}>
                                            <span>{item.name}</span>
                                            <span style={{ color: barColor, fontWeight: 'bold' }}>{item.tmp}°C</span>
                                        </div>
                                        <div style={{ width: '100%', height: '4px', backgroundColor: '#333', borderRadius: '2px', marginTop: '4px' }}>
                                            <div style={{ width: `${barWidth}%`, height: '100%', backgroundColor: barColor, borderRadius: '2px' }} />
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                </div>
            </Draggable>
        </div>
    );
}
export default App;