// [AGENT] 상태 사다리 — "측정값이 경고선 이상이면 DEGRADED, 위험선 이상이면 DOWN" 판정의 단일 구현.
// 표시(HealthCheckService)와 기록(각 평가기)이 같은 사다리 상수를 읽으므로 임계값이 서로 어긋날 수 없다.
// 임계 문구(text)도 여기서 생성 — "다른 파일과 수동으로 맞춰야 함" 류의 상수·주석 중복을 없앤다.
package com.chs.springboot.global.monitor.health;

public record StatusLadder(double degradedAt, double downAt) {

    // ── 사다리 상수 집결 (하트비트 13종의 stale/down 초는 HealthHeartbeatConfig 가 단일 소스) ──
    /** 피드 freshness: 마지막 수신 후 경과 초 */
    public static final StatusLadder FEED_SECONDS = new StatusLadder(10, 30);
    /** 자원 사용률(CPU/RAM/DISK) % — 다운 임계는 AlertService 의 80% CRITICAL 라인과 동일값 */
    public static final StatusLadder RESOURCE_PCT = new StatusLadder(70, 80);
    /** raw_agg_trade 물리 크기 GB (data+index) */
    public static final StatusLadder RAWTABLE_GB = new StatusLadder(3, 6);
    /** WS 세션 합계 (4개 핸들러 합) */
    public static final StatusLadder WS_CONNS = new StatusLadder(300, 800);
    /** 최근 60분 누락봉 수 */
    public static final StatusLadder CANDLE_GAP = new StatusLadder(1, 3);
    /** 최근 60분 flat 캔들 비율 % */
    public static final StatusLadder FLAT_PCT = new StatusLadder(10, 30);
    /** 최근 60초 WS 재연결/에러 횟수 */
    public static final StatusLadder WS_RECONNECT = new StatusLadder(3, 6);

    /** 위험선 이상 DOWN, 경고선 이상 DEGRADED, 그 외 UP */
    public HealthStatus judge(double value) {
        if (value >= downAt) {
            return HealthStatus.DOWN;
        }
        if (value >= degradedAt) {
            return HealthStatus.DEGRADED;
        }
        return HealthStatus.UP;
    }

    /** 미수집 음수 센티널(-1 등)을 UNKNOWN 으로 처리하는 판정 */
    public HealthStatus judgeOrUnknown(double value) {
        return value < 0 ? HealthStatus.UNKNOWN : judge(value);
    }

    /** 임계 문구 생성: "경고 ≥{경고선}{단위} · 다운 ≥{위험선}{단위}{덧말}" */
    public String text(String unit, String note) {
        return "경고 ≥" + fmt(degradedAt) + unit + " · 다운 ≥" + fmt(downAt) + unit + note;
    }

    public String text(String unit) {
        return text(unit, "");
    }

    // ── 측정값 판정+판정근거 문구 묶음 — 표시(HealthCheckService)와 기록(ResourceHealthEvaluator)이 같은 구현을 호출 ──

    /** 측정값 판정 결과: 상태 + 판정근거 문구 */
    public record Judged(HealthStatus status, String detail) { }

    /** raw_agg_trade 물리 크기(bytes) 판정 — 미수집 음수는 UNKNOWN */
    public static Judged judgeRawTable(long bytes) {
        return new Judged(RAWTABLE_GB.judgeOrUnknown(bytes / GB), describeRawTable(bytes));
    }

    /** WS 세션 합계 판정 — 미수집 음수는 UNKNOWN */
    public static Judged judgeWsConn(int conns) {
        return new Judged(WS_CONNS.judgeOrUnknown(conns), describeWsConn(conns));
    }

    private static final double GB = 1024d * 1024 * 1024;

    private static String describeRawTable(long bytes) {
        if (bytes < 0) return "수집 기록 없음";
        return "raw_agg_trade %.1fGB".formatted(bytes / GB);
    }

    private static String describeWsConn(int conns) {
        if (conns < 0) return "수집 기록 없음";
        return "WS 세션 " + conns + "개";
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
