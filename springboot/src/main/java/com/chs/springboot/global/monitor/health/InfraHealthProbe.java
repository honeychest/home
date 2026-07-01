// [AGENT] L1 인프라 능동 프로브 — MySQL/Postgres/Redis 는 이미 등록된 커넥션 풀(DataSource,
// RedisConnectionFactory)에서 커넥션을 하나 빌려 짧게 확인만 하고 반납한다(신규 풀 생성 없음).
// 과거에는 Actuator가 내부적으로 등록하는 "dbHealthContributor"/"redisHealthContributor" 빈을
// 이름으로 찾아 재사용했으나, Lettuce는 ReactiveRedisConnectionFactory도 함께 자동 등록되어
// 두 오토컨피그(RedisHealthContributorAutoConfiguration / RedisReactiveHealthContributorAutoConfiguration)가
// 같은 빈 이름을 두고 충돌 → 타입(HealthContributor vs ReactiveHealthIndicator)이 기동 시점 조건에 따라
// 달라지며 BeanNotOfRequiredTypeException이 발생했다. 우리가 직접 만든 안정적인 타입(빈 이름까지 확정)인
// DataSource/RedisConnectionFactory를 그대로 주입받아 Actuator 내부 배선에 대한 의존을 제거한다.
// Kafka는 Actuator 기본 인디케이터가 없어(Spring Boot 3.4 기준 미제공) AdminClient로 짧은 타임아웃 프로브.
package com.chs.springboot.global.monitor.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class InfraHealthProbe {

    public record Probe(HealthStatus status, String detail) { }

    private static final int PROBE_TIMEOUT_SECONDS = 3;

    private final DataSource primaryDataSource;
    private final DataSource pgVectorDataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final String kafkaBootstrapServers;

    public InfraHealthProbe(
            @Qualifier("primaryDataSource") DataSource primaryDataSource,
            @Qualifier("pgVectorDataSource") DataSource pgVectorDataSource,
            RedisConnectionFactory redisConnectionFactory,
            @Value("${spring.kafka.bootstrap-servers:kafka:9092}") String kafkaBootstrapServers
    ) {
        this.primaryDataSource = primaryDataSource;
        this.pgVectorDataSource = pgVectorDataSource;
        this.redisConnectionFactory = redisConnectionFactory;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
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

    public Probe kafka() {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Duration.ofSeconds(3).toMillis());
        try (AdminClient adminClient = AdminClient.create(config)) {
            int nodeCount = adminClient.describeCluster(new DescribeClusterOptions().timeoutMs(3000))
                    .nodes().get(3, TimeUnit.SECONDS).size();
            return nodeCount > 0
                    ? new Probe(HealthStatus.UP, nodeCount + "개 브로커 노드 응답")
                    : new Probe(HealthStatus.DOWN, "브로커 노드 0개");
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
