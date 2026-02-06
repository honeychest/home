import * as Cesium from 'cesium';

export const getRelativeColor = (tmp, min, max) => {
    const t = parseFloat(tmp);
    // 0(Min) ~ 1(Max) 사이의 비율 계산
    let fraction = (max === min) ? 0.5 : (t - min) / (max - min);

    // 0에 가까울수록 파랑, 1에 가까울수록 빨강으로 가는 다단계 스펙트럼
    // 0.0: 파랑 (추움)
    // 0.3: 하늘색/초록
    // 0.5: 노랑/주황 (중간)
    // 0.7: 주황/연빨강
    // 1.0: 진한 빨강 (더움)

    if (fraction <= 0.25) {
        // 파랑 -> 하늘색
        return Cesium.Color.lerp(Cesium.Color.BLUE, Cesium.Color.CYAN, fraction * 4, new Cesium.Color());
    } else if (fraction <= 0.5) {
        // 하늘색 -> 초록/노랑
        return Cesium.Color.lerp(Cesium.Color.CYAN, Cesium.Color.YELLOW, (fraction - 0.25) * 4, new Cesium.Color());
    } else if (fraction <= 0.75) {
        // 노랑 -> 주황
        return Cesium.Color.lerp(Cesium.Color.YELLOW, Cesium.Color.ORANGE, (fraction - 0.5) * 4, new Cesium.Color());
    } else {
        // 주황 -> 빨강
        return Cesium.Color.lerp(Cesium.Color.ORANGE, Cesium.Color.RED, (fraction - 0.75) * 4, new Cesium.Color());
    }
};