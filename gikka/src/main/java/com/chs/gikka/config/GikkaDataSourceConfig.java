// [AGENT] gikka(recipe) 전용 DataSource·Flyway — docs/recipe/CONTEXT.md 분리 규율 2·6·8
// 같은 PostgreSQL 컨테이너(chs-pgvector)의 별도 데이터베이스 `gikka` 에 접속한다.
// chatbot 의 PgVectorConfig 패턴을 복사 소유 (규율 7: 다른 패키지 import 금지 — ArchUnit 감시).
package com.chs.gikka.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * gikka 전용 DB 구성.
 *
 * - DataSource 분리: 기존 MySQL(@Primary)·pgvector 와 별개 풀. 실수로도 엮일 수 없게.
 * - 풀 크기 2: 이 서버는 앱 2인스턴스 + 작은 힙이라 풀을 작게 시작 (PLAYBOOK 2차).
 * - Flyway 분리: gikka 스키마는 recipe 소유 경로(db/migration/gikka)에서만 관리.
 *   기존 MySQL Flyway(classpath:db/migration)와 이력 테이블도 DB도 다르다.
 */
@Configuration
public class GikkaDataSourceConfig {

    private final GikkaDataSourceProperties properties;

    public GikkaDataSourceConfig(GikkaDataSourceProperties properties) {
        this.properties = properties;
    }

    @Bean("gikkaDataSource")
    public DataSource gikkaDataSource() {
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class).build();
        ds.setJdbcUrl(properties.getUrl());
        ds.setUsername(properties.getUsername());
        ds.setPassword(properties.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setPoolName("gikka-pool");
        ds.setMaximumPoolSize(2);
        return ds;
    }

    /** 기동 시 gikka DB 마이그레이션 자동 적용 (initMethod=migrate) */
    @Bean(name = "gikkaFlyway", initMethod = "migrate")
    public Flyway gikkaFlyway(@Qualifier("gikkaDataSource") DataSource gikkaDataSource) {
        return Flyway.configure()
                .dataSource(gikkaDataSource)
                .locations("classpath:db/migration/gikka")
                .load();
    }

    /** gikka 전용 JdbcClient — recipe 저장소는 이것만 사용한다. */
    @Bean("gikkaJdbcClient")
    @DependsOn("gikkaFlyway")
    public JdbcClient gikkaJdbcClient(@Qualifier("gikkaDataSource") DataSource gikkaDataSource) {
        return JdbcClient.create(gikkaDataSource);
    }

    /** gikka 전용 트랜잭션 실행기.
        주의: TransactionManager 를 빈으로 등록하면 Spring Boot 이 기본 'transactionManager'
        자동 구성을 포기해 기존 도메인의 @Transactional 이 전부 깨진다 (2026-07-10 실측).
        그래서 매니저는 빈이 아니라 TransactionTemplate 안에 비공개로 품는다 — 앱 전역 영향 0. */
    @Bean("gikkaTxTemplate")
    public TransactionTemplate gikkaTxTemplate(@Qualifier("gikkaDataSource") DataSource gikkaDataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(gikkaDataSource));
    }
}
