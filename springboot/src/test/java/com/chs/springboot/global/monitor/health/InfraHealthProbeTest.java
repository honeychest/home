package com.chs.springboot.global.monitor.health;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InfraHealthProbeTest {

    private final DataSource primaryDataSource = mock(DataSource.class);
    private final DataSource pgVectorDataSource = mock(DataSource.class);
    private final RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);

    private InfraHealthProbe probe() {
        return new InfraHealthProbe(primaryDataSource, pgVectorDataSource, redisConnectionFactory, "kafka:9092");
    }

    @Test
    void mysqlUpWhenConnectionValid() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.isValid(3)).thenReturn(true);
        when(primaryDataSource.getConnection()).thenReturn(connection);

        InfraHealthProbe.Probe result = probe().mysql();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void mysqlDownWhenConnectionInvalid() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.isValid(3)).thenReturn(false);
        when(primaryDataSource.getConnection()).thenReturn(connection);

        InfraHealthProbe.Probe result = probe().mysql();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void mysqlDownWhenGetConnectionThrows() throws SQLException {
        when(primaryDataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        InfraHealthProbe.Probe result = probe().mysql();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.detail()).isEqualTo("connection refused");
    }

    @Test
    void postgresUpWhenConnectionValid() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.isValid(3)).thenReturn(true);
        when(pgVectorDataSource.getConnection()).thenReturn(connection);

        InfraHealthProbe.Probe result = probe().postgres();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void postgresDownWithErrorDetailWhenConnectionThrows() throws SQLException {
        when(pgVectorDataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        InfraHealthProbe.Probe result = probe().postgres();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.detail()).isEqualTo("connection refused");
    }

    @Test
    void redisUpWhenPingReturnsPong() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn("PONG");
        when(redisConnectionFactory.getConnection()).thenReturn(connection);

        InfraHealthProbe.Probe result = probe().redis();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void redisDownWhenPingReturnsUnexpectedValue() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn("garbage");
        when(redisConnectionFactory.getConnection()).thenReturn(connection);

        InfraHealthProbe.Probe result = probe().redis();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    void redisDownWhenGetConnectionThrows() {
        when(redisConnectionFactory.getConnection()).thenThrow(new RuntimeException("timeout"));

        InfraHealthProbe.Probe result = probe().redis();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.detail()).isEqualTo("timeout");
    }
}
