package com.chs.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
})
@EnableScheduling
public class SpringbootApplication {

    public static void main(String[] args) {
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
