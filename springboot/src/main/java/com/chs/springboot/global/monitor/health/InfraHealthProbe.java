// [AGENT] L1 인프라 능동 프로브 — MySQL/Postgres/Redis 는 이미 등록된 커넥션 풀(DataSource,
// RedisConnectionFactory)에서 커넥션을 하나 빌려 짧게 확인만 하고 반납한다(신규 풀 생성 없음).
// 과거에는 Actuator가 내부적으로 등록하는 "dbHealthContributor"/"redisHealthContributor" 빈을
// 이름으로 찾아 재사용했으나, Lettuce는 ReactiveRedisConnectionFactory도 함께 자동 등록되어
// 두 오토컨피그(RedisHealthContributorAutoConfiguration / RedisReactiveHealthContributorAutoConfiguration)가
// 같은 빈 이름을 두고 충돌 → 타입(HealthContributor vs ReactiveHealthIndicator)이 기동 시점 조건에 따라
// 달라지며 BeanNotOfRequiredTypeException이 발생했다. 우리가 직접 만든 안정적인 타입(빈 이름까지 확정)인
// DataSource/RedisConnectionFactory를 그대로 주입받아 Actuator 내부 배선에 대한 의존을 제거한다.
package com.chs.springboot.global.monitor.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
public class InfraHealthProbe {

    public record Probe(HealthStatus status, String detail) { }

    private static final int PROBE_TIMEOUT_SECONDS = 3;

    private final DataSource primaryDataSource;
    private final DataSource pgVectorDataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    public InfraHealthProbe(
            @Qualifier("primaryDataSource") DataSource primaryDataSource,
            @Qualifier("pgVectorDataSource") DataSource pgVectorDataSource,
            RedisConnectionFactory redisConnectionFactory
    ) {
        this.primaryDataSource = primaryDataSource;
        this.pgVectorDataSource = pgVectorDataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    public Probe mysql() {
        return fromDataSource(primaryDataSource);
    }

    public Probe postgres() {
        return fromDataSource(pgVectorDataSource);
    }

    public Probe redis() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            String pong = connection.ping();
            return "PONG".equalsIgnoreCase(pong)
                    ? new Probe(HealthStatus.UP, "정상 응답")
                    : new Probe(HealthStatus.DOWN, "예상치 못한 응답: " + pong);
        } catch (Exception e) {
            return new Probe(HealthStatus.DOWN, e.getMessage());
        }
    }

    private Probe fromDataSource(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(PROBE_TIMEOUT_SECONDS)
                    ? new Probe(HealthStatus.UP, "정상 응답")
                    : new Probe(HealthStatus.DOWN, "커넥션 유효성 검사 실패");
        } catch (SQLException e) {
            return new Probe(HealthStatus.DOWN, e.getMessage());
        }
    }
}
