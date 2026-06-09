package com.chs.springboot.global.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DataSourceConfig {

    @Primary
    @Bean("primaryDataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource primaryDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean("batchDataSource")
    @ConfigurationProperties("spring.datasource.batch.hikari")
    public DataSource batchDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    // @Primary: JdbcTemplate 빈이 둘(batchJdbcTemplate, pgVectorJdbcTemplate)이 되면서
    // @Qualifier 없이 JdbcTemplate 을 주입받던 기존 서비스들의 모호성을 제거(기존 동작 보존).
    // PG 전용 pgVectorJdbcTemplate 은 @Qualifier 로만 접근한다.
    @Primary
    @Bean("batchJdbcTemplate")
    public JdbcTemplate batchJdbcTemplate(@Qualifier("batchDataSource") DataSource batchDataSource) {
        return new JdbcTemplate(batchDataSource);
    }
}