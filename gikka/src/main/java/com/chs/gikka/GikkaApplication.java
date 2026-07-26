// [AGENT] gikka(기까) 앱 진입점 — springboot/ 에서 분리된 독립 서비스.
// 핵심: TimeZone KST 고정 · @ConfigurationPropertiesScan(gikka.* 바인딩) · @EnableScheduling(RegistrationWorker)
package com.chs.gikka;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * springboot/SpringbootApplication 과 다른 점 (분리하면서 덜어낸 것들):
 *
 * <ul>
 *   <li><b>SecurityAutoConfiguration 을 exclude 하지 않는다.</b> 저쪽은 전역 SecurityConfig 가
 *       체인을 통째로 소유해서 자동 구성을 껐지만, 여기서는 {@code GikkaSecurityConfig} 가
 *       SecurityFilterChain 빈을 등록하므로 스프링 부트의 기본 체인이 조건부로 알아서 빠진다
 *       ({@code @ConditionalOnMissingBean}). 그 결과 {@code /api/recipe/**} 는 gikka 체인이,
 *       나머지({@code /actuator/health} 등)는 어떤 체인에도 안 걸려 그대로 통과한다.</li>
 *   <li><b>@EnableAsync 없음.</b> recipe 에 {@code @Async} 사용처가 없다. 등록 처리는
 *       {@code RegistrationWorker} 의 {@code @Scheduled} + DB 큐(SKIP LOCKED)로 돈다.</li>
 * </ul>
 *
 * <p>{@code .env} 는 로컬 개발 편의용이다 — 없어도 그냥 뜬다(ignoreIfMissing). 운영은 도커
 * {@code env_file} 로 주입한다. 값이 비면 조용히 기능이 쉬도록 설계돼 있다
 * (gikka.llm.api-key 비면 분석 워커 휴면, gikka.youtube.api-key 비면 메타 조회 생략).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GikkaApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));

        // 실행 위치가 저장소 루트인지 gikka 폴더인지에 따라 .env 경로를 고른다 (springboot 와 같은 방식).
        String currentDir = System.getProperty("user.dir");
        String envPath = currentDir.endsWith("gikka") ? "./" : "./gikka";

        io.github.cdimascio.dotenv.Dotenv.configure()
                .directory(envPath)
                .ignoreIfMissing()
                .load()
                .entries()
                .forEach(entry -> System.setProperty(entry.getKey(), entry.getValue()));

        SpringApplication.run(GikkaApplication.class, args);
    }
}
