package com.chs.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WeatherController {

    @Value("${weather.api.service-key}")
    private String serviceKey;

    @Value("${weather.api.base-url}")
    private String baseUrl;

    @GetMapping("/weather/all")
    public Map<String, Map<String, String>> getAllWeather() {
        Map<String, int[]> locations = new HashMap<>();
        locations.put("서울특별시", new int[]{60, 127});
        locations.put("경기도", new int[]{60, 120});
        locations.put("강원도", new int[]{73, 134});
        locations.put("경상북도", new int[]{89, 90});
        locations.put("경상남도", new int[]{91, 77});
        locations.put("전라북도", new int[]{63, 89});
        locations.put("전라남도", new int[]{51, 67});
        locations.put("충청남도", new int[]{68, 100});
        locations.put("충청북도", new int[]{69, 107});
        locations.put("제주특별자치도", new int[]{52, 38});

        Map<String, Map<String, String>> results = new HashMap<>();
        RestTemplate restTemplate = new RestTemplate();
        // 현재 정시(HH00)를 기준으로 예보값 필터링
        String currentHour = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH00"));

        locations.forEach((name, coords) -> {
            // 성공할 때까지 최대 5번 재귀 호출
            Map<String, String> weatherData = fetchWeatherRecursive(restTemplate, name, coords, LocalDateTime.now(), currentHour, 0);
            results.put(name, weatherData);
        });

        return results;
    }

    private Map<String, String> fetchWeatherRecursive(RestTemplate restTemplate, String name, int[] coords, LocalDateTime targetTime, String currentHour, int retryCount) {
        if (retryCount > 5) return new HashMap<>();

        // 초단기예보 생성 주기에 맞춘 base_time 설정 (HH30)
        LocalDateTime baseTimeSource = targetTime.minusMinutes(45);
        String baseDate = baseTimeSource.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String apiBaseTime = baseTimeSource.format(DateTimeFormatter.ofPattern("HH30"));
        // UI 표시용 포맷 (HH:00)
        String displayTime = baseTimeSource.format(DateTimeFormatter.ofPattern("HH:00"));

        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "60")
                    .queryParam("dataType", "JSON")
                    .queryParam("base_date", baseDate)
                    .queryParam("base_time", apiBaseTime)
                    .queryParam("nx", coords[0])
                    .queryParam("ny", coords[1])
                    .build(true).toUri();

            String response = restTemplate.getForObject(uri, String.class);
            Map<String, String> result = extractAllFcstData(response, currentHour);

            if (result.isEmpty() || !result.containsKey("tmp")) {
                System.out.println("⚠️ " + name + " [" + apiBaseTime + "] 데이터 없음 -> 1시간 전 재시도");
                return fetchWeatherRecursive(restTemplate, name, coords, targetTime.minusHours(1), currentHour, retryCount + 1);
            }

            // 💡 성공 시 해당 데이터의 기준 시간(UI 표시용) 추가
            result.put("baseTime", displayTime);
            return result;
        } catch (Exception e) {
            System.err.println("❌ " + name + " 통신 실패: " + e.getMessage());
            return fetchWeatherRecursive(restTemplate, name, coords, targetTime.minusHours(1), currentHour, retryCount + 1);
        }
    }

    private Map<String, String> extractAllFcstData(String json, String currentHour) {
        Map<String, String> data = new HashMap<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);

            // resultCode 검증
            String resultCode = root.path("response").path("header").path("resultCode").asText();
            if (!"00".equals(resultCode)) return data;

            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (currentHour.equals(item.path("fcstTime").asText())) {
                        String category = item.path("category").asText();
                        String value = item.path("fcstValue").asText();
                        switch (category) {
                            case "T1H": data.put("tmp", value); break;
                            case "REH": data.put("hum", value); break;
                            case "RN1": data.put("rain", value); break;
                            case "WSD": data.put("wind", value); break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("파싱 오류: " + e.getMessage());
        }
        return data;
    }
}