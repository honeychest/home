// [AGENT] 역할: Spring Boot 앱 진입점 | 연관파일: DotenvConfig.java, SchedulingConfig.java | 핵심: main()에서 .env 로드→System.setProperty 후 SpringApplication.run (DotenvConfig @PostConstruct보다 앞서 로드), TimeZone KST 고정, SecurityAutoConfiguration exclude, 조건부 스케줄링 + @EnableAsync 활성화
package com.chs.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.TimeZone;

@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        // PgVector 오토컨피그 제외 [E]: @Primary(MySQL) JdbcTemplate 오용 방지.
        // PG 전용 VectorStore 는 PgVectorConfig 에서 직접 등록.
        org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration.class
})
@ConfigurationPropertiesScan
@EnableAsync
public class SpringbootApplication {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        String currentDir = System.getProperty("user.dir");

        // 실행 위치가 root(home)인지 프로젝트 폴더(springboot)인지에 따라 경로 자동 선택
        String envPath = currentDir.endsWith("springboot") ? "./" : "./springboot";

        io.github.cdimascio.dotenv.Dotenv dotenv = io.github.cdimascio.dotenv.Dotenv.configure()
                .directory(envPath)
                .ignoreIfMissing()
                .load();

        // 시스템 프로퍼티 주입
        dotenv.entries().forEach(entry -> System.setProperty(entry.getKey(), entry.getValue()));

        SpringApplication.run(SpringbootApplication.class, args);
    }
}
