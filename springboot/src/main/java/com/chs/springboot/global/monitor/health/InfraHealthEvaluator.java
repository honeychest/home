// [AGENT] L1 인프라 계측 완성 — 능동 프로브(mysql/redis/kafka/postgres) 상태 전환을 health_check_event 로 적립.
// 20초 주기, leader 노드에서만 실행(한 노드의 일시 네트워크 문제로 공유 이력 오염 방지). 정상 지속 시 DB 쓰기 없음.
// 보드 표시(HealthCheckService)도 여기가 적립한 이벤트를 읽는다 — 보드 요청 경로에는 실접속 프로브가 없다.
// 한계: MySQL 자체 다운은 이벤트 저장소가 MySQL 이라 저장·알림 불가(로그로만 남음).
package com.chs.springboot.global.monitor.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.chs.springboot.global.redis.LeaderElectionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class InfraHealthEvaluator {

    private final InfraHealthProbe probe;
    private final HealthCheckRecorder recorder;
    private final LeaderElectionService leaderElection;

    @Scheduled(fixedDelay = 20_000)
    public void evaluate() {
        if (!leaderElection.isLeader()) {
            return;
        }
        record(HealthCheckCatalog.INFRA_MYSQL.key(), probe::mysql);
        record(HealthCheckCatalog.INFRA_REDIS.key(), probe::redis);
        record(HealthCheckCatalog.INFRA_KAFKA.key(), probe::kafka);
        record(HealthCheckCatalog.INFRA_POSTGRES.key(), probe::postgres);
    }

    // 키별 격리 — 한 대상의 프로브/기록 실패(예: MySQL 다운 시 저장 불가)가 나머지 체크를 막지 않게.
    private void record(String checkKey, Supplier<InfraHealthProbe.Probe> probeCall) {
        try {
            InfraHealthProbe.Probe result = probeCall.get();
            recorder.record(checkKey, result.status(), result.detail());
        } catch (Exception e) {
            log.warn("[InfraHealth] {} 평가/기록 실패: {}", checkKey, e.getMessage());
        }
    }
}
