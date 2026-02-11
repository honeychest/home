package com.chs.springboot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class WeatherScheduler {

    @Autowired
    private WeatherController weatherController;

    /**
     * 💡 매 10분마다 실행 (예: 2:40, 2:50, 3:00 ...)
     * */
    @Scheduled(cron = "0 */10 * * * *")
    public void collectWeatherData() {
        System.out.println("--- [스케줄러] 10분 주기 자동 수집 시작: " + LocalDateTime.now() + " ---");
        try {
            weatherController.getAllWeather(null);
            System.out.println("--- [스케줄러] 수집 완료 ---");
        } catch (Exception e) {
            System.err.println("--- [스케줄러] 에러: " + e.getMessage() + " ---");
        }
    }
}