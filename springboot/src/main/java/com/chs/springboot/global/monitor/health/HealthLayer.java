// [AGENT] 헬스 체크 계층 — 보드 그룹 표시/정렬용 (ordinal = 표시 순서)
package com.chs.springboot.global.monitor.health;

public enum HealthLayer {
    L1_INFRA("L1 인프라 연결"),
    L2_FEED("L2 데이터 유입(피드)"),
    L3_PIPELINE("L3 파이프라인 처리"),
    L4_DATA("L4 데이터 무결성"),
    L5_SCHEDULER("L5 리더/스케줄러"),
    L6_EXTERNAL("L6 외부 연동"),
    L7_RESOURCE("L7 리소스/용량");

    private final String label;

    HealthLayer(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
