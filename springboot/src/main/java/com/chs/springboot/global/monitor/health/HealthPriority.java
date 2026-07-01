// [AGENT] 헬스 체크 우선순위 — 화면 표기는 한글(치명/중요/여유)
//  치명: 끊기면 시스템 핵심(수집·저장·차트)이 즉시 망가짐
//  중요: 기능 저하·일부 손실, 당장 치명은 아님
//  여유: 있으면 좋은 부가 기능
package com.chs.springboot.global.monitor.health;

public enum HealthPriority {
    CRITICAL("치명"),
    HIGH("중요"),
    LOW("여유");

    private final String label;

    HealthPriority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
