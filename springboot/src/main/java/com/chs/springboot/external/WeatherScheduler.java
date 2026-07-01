// [AGENT] 역할: 날씨 데이터 자동 수집 스케줄러 (cron "0 0/10 * * * *") | 연관파일: WeatherService.java, LeaderElectionService.java, SpringbootApplication.java(@EnableScheduling) | 핵심: SCHEDULING_ENABLED=true + 리더일 때만 실행(Redis 단일 실행 보장), 로컬은 false 유지
// Purpose: 날씨 데이터 자동 수집 스케줄러 — 10분 주기로 전국 날씨를 DB에 저장 (리더 서버에서만 실행)

/**
 * ─────────────────────────────────────────────────────────────────
 * 이 클래스의 역할
 * ─────────────────────────────────────────────────────────────────
 * 서버가 실행 중인 동안 백그라운드에서 주기적으로 날씨 데이터를 수집.
 * WeatherService.getWeatherByHour(null)을 호출해서
 * 현재 시각 기준 날씨 데이터를 DB에 저장.
 *
 * 이 스케줄러 덕분에:
 * - 사용자가 페이지를 열면 이미 DB에 데이터가 있어서 빠르게 응답
 * - 기상청 API를 사용자 요청마다 호출하지 않아도 됨
 *
 * SCHEDULING_ENABLED 환경변수:
 * 로컬 개발 시 false → 스케줄러 비활성화 (기상청 API 실제 호출 방지)
 * 운영 서버에서 true → 자동 수집 활성화
 *
 * ⚠ 로컬에서 절대 true로 바꾸지 말 것 (기상청 API 호출 횟수 제한 소진)
 *
 * jQuery 비유:
 * 서버 측에서 setInterval(function() { collectWeather(); }, 10 * 60 * 1000)
 * 처럼 주기적으로 실행하는 것과 동일한 개념.
 * 단, Spring @Scheduled는 서버 시작 시 자동 등록됨.
 * ─────────────────────────────────────────────────────────────────
 */
package com.chs.springboot.external;

import com.chs.springboot.domain.weather.service.WeatherService;
import com.chs.springboot.global.monitor.health.HealthCheckCatalog;
import com.chs.springboot.global.monitor.health.HealthCheckRecorder;
import com.chs.springboot.global.monitor.health.HealthHeartbeat;
import com.chs.springboot.global.monitor.health.HealthStatus;
import com.chs.springboot.global.redis.LeaderElectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * @Component: Spring이 이 클래스를 빈으로 등록.
 * @Service, @Controller와 달리 @Component는 역할 구분이 없는 범용 빈.
 * 스케줄러는 Service/Controller 레이어에 속하지 않으므로 @Component 사용.
 *
 * @EnableScheduling:
 * 이 어노테이션은 이 파일이 아닌 SpringbootApplication.java의 메인 클래스에 있음.
 * @EnableScheduling이 있어야 @Scheduled 어노테이션이 동작함.
 */
@Component
public class WeatherScheduler {

    /**
     * weatherService: 날씨 데이터 수집 로직이 있는 서비스.
     * @Autowired: Spring이 WeatherService 빈을 자동 주입.
     */
    @Autowired
    private WeatherService weatherService;

    @Autowired
    private LeaderElectionService leaderElection;

    @Autowired
    private HealthHeartbeat healthHeartbeat;

    @Autowired
    private HealthCheckRecorder healthCheckRecorder;

    private static final String HEALTH_KEY = HealthCheckCatalog.SCHED_WEATHER.key();
    // ext-weather-api: 스케줄러 실행(sched-weather)과 별개로 "외부 기상청 API/수집" 성공/실패 계측
    private static final String EXT_KEY = HealthCheckCatalog.EXT_WEATHER_API.key();

    /**
     * collectWeatherData: 매 10분마다 실행되는 자동 수집 메서드.
     *
     * @Scheduled(cron = "0 0/10 * * * *"):
     * cron 표현식 = "초 분 시 일 월 요일"
     * "0 0/10 * * * *" = 매 시 0분, 10분, 20분, 30분, 40분, 50분에 실행
     * 즉, 매 10분 정각(초=0)마다 실행.
     *
     * jQuery 비유:
     * setInterval(collectWeatherData, 10 * 60 * 1000)
     * 단, cron은 절대 시각 기준 (0분, 10분, 20분...)
     * setInterval은 상대 시각 기준 (시작 후 10분마다)
     *
     * SCHEDULING_ENABLED 체크:
     * 왜 @Value나 @ConditionalOnProperty를 안 쓰나?
     * DotenvConfig(@PostConstruct)가 .env를 읽어 System 프로퍼티에 등록하는 시점이
     * application.properties 평가 시점보다 늦음.
     * @Value는 application.properties 평가 시점에 주입되므로 .env 값을 못 읽을 수 있음.
     * → 런타임에 System.getProperty()로 직접 읽으면 이 문제 회피 가능.
     *
     * System.getProperty("SCHEDULING_ENABLED"):
     * JVM 시스템 프로퍼티에서 값 읽기.
     * DotenvConfig가 .env의 SCHEDULING_ENABLED 값을 이미 setProperty로 등록해둠.
     * "true".equalsIgnoreCase(...): 대소문자 무관하게 "true" 비교.
     * null.equalsIgnoreCase()를 방지하기 위해 "true"가 앞에 옴 (null-safe 비교).
     *
     * getWeatherByHour(null):
     * null = 현재 시각 기준으로 조회.
     * WeatherService 내부에서 DB 우선 조회 → 없으면 API 호출 → DB 저장.
     * 스케줄러는 이 메서드 하나만 호출하면 수집부터 저장까지 모두 처리됨.
     */
    @Scheduled(cron = "0 2/10 * * * *")
    public void collectWeatherData() {
        // 런타임에 시스템 프로퍼티 직접 확인 (DotenvConfig 로드 타이밍 문제 회피)
        if (!"true".equalsIgnoreCase(System.getProperty("SCHEDULING_ENABLED"))) {
            return; // 비활성화 상태면 즉시 종료 (아무것도 안 함)
        }
        // Redis 리더일 때만 실행 — 멀티 인스턴스에서 기상청 API 중복 호출 방지 (텔레그램 폴링과 동일 패턴)
        if (!leaderElection.isLeader()) {
            return;
        }

        System.out.println("--- [스케줄러] 10분 주기 자동 수집 시작: " + LocalDateTime.now() + " ---");
        try {
            weatherService.getWeatherByHour(null); // 현재 시각 기준 수집
            healthHeartbeat.beat(HEALTH_KEY);
            healthCheckRecorder.markOk(EXT_KEY);
            System.out.println("--- [스케줄러] 수집 완료 ---");
        } catch (Exception e) {
            // 수집 실패 시 로그만 출력하고 다음 실행 주기를 기다림 (앱 종료 방지)
            healthHeartbeat.fail(HEALTH_KEY, e.getMessage());
            healthCheckRecorder.markFail(EXT_KEY, HealthStatus.DOWN, "CRITICAL", "수집 실패: " + e.getMessage());
            System.err.println("--- [스케줄러] 에러: " + e.getMessage() + " ---");
        }
    }
}