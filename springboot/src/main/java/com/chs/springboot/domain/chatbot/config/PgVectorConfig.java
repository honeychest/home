// [AGENT] PGVector 전용 빈 — 기존 MySQL DataSource(@Primary)와 분리 등록 [A][E]
// 컨벤션: global/config/DataSourceConfig 패턴(전용 DataSource + JdbcTemplate)을 그대로 따름.
// 연관: SpringbootApplication(PgVectorStoreAutoConfiguration exclude), DataSourceConfig
package com.chs.springboot.domain.chatbot.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PGVector 전용 설정.
 *
 * 왜 직접 등록하나:
 *   - 이 앱은 DataSourceConfig 에서 MySQL DataSource 를 @Primary 로 수동 등록 중.
 *   - PgVector starter 의 auto-config 에 맡기면 @Primary(MySQL) JdbcTemplate 을
 *     잡아 벡터 테이블을 MySQL 에 만들려다 깨질 수 있음.
 *   - 따라서 auto-config 는 exclude([E]) 하고, 아래처럼 PG 전용 빈을 직접 등록한다.
 *
 * 차원(dimensions)은 임베딩 모델 출력 길이와 반드시 일치해야 한다(실측 2560).
 */
@Configuration
public class PgVectorConfig {

    private final PgVectorProperties properties;

    public PgVectorConfig(PgVectorProperties properties) {
        this.properties = properties;
    }

    /** PG 전용 DataSource (MySQL @Primary 와 분리, @Primary 아님). */
    @Bean("pgVectorDataSource")
    public DataSource pgVectorDataSource() {
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class).build();
        ds.setJdbcUrl(properties.getUrl());
        ds.setUsername(properties.getUsername());
        ds.setPassword(properties.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setPoolName("pgvector-pool");
        ds.setMaximumPoolSize(4);
        return ds;
    }

    /** PG 전용 JdbcTemplate (MySQL JdbcTemplate 과 분리). */
    @Bean("pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource pgVectorDataSource) {
        return new JdbcTemplate(pgVectorDataSource);
    }

    /**
     * 코드베이스 임베딩 저장소.
     * initializeSchema(true): 최초 기동 시 vector_store 테이블/확장 자동 생성.
     * dimensions: 임베딩 모델(text-embedding-qwen3-embedding-4b) 출력 차원과 일치(2560).
     */
    @Bean
    public VectorStore vectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                   EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(pgVectorJdbcTemplate, embeddingModel)
                .dimensions(properties.getDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                // 임베딩 2560차원 > HNSW/IVFFlat 인덱스 한계(2000). 인덱스 없이 정확검색(NONE).
                // 데이터 규모가 작아 전수 스캔으로 충분. 대용량 전환 시 2000차원 이하 모델 검토.
                .indexType(PgVectorStore.PgIndexType.NONE)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .build();
    }
}
