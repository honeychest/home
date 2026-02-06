package com.chs.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

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

    private Map<String, String> fetchWeatherRecursive(RestTemplate restTemplate, String name, int[] coords, LocalDateTime dateTime, String currentHour, int retryCount) {
        if (retryCount >= 5) return new HashMap<>();

        String baseDate = dateTime.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String baseTime = dateTime.format(DateTimeFormatter.ofPattern("HHmm"));

        // nx와 ny 자리에 %d를 사용하여 int 값을 대입합니다.
        String url = String.format("%s?serviceKey=%s&pageNo=1&numOfRows=1000&dataType=JSON&base_date=%s&base_time=%s&nx=%d&ny=%d",
                baseUrl, serviceKey, baseDate, baseTime, coords[0], coords[1]);

        try {
            String json = restTemplate.getForObject(url, String.class);
            Map<String, String> data = extractAllFcstData(json, currentHour);

            if (!data.isEmpty()) return data;

            return fetchWeatherRecursive(restTemplate, name, coords, dateTime.minusHours(1), currentHour, retryCount + 1);
        } catch (Exception e) {
            // 한글 대신 영문 로그를 남겨 인코딩 문제를 방지합니다.
            System.err.println("API Request Error [" + name + "]: " + e.getMessage());
            return fetchWeatherRecursive(restTemplate, name, coords, dateTime.minusHours(1), currentHour, retryCount + 1);
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
                            case "T1H":
                                data.put("tmp", value);
                                break;
                            case "REH":
                                data.put("hum", value);
                                break;
                            case "RN1":
                                data.put("rain", value);
                                break;
                            case "WSD":
                                data.put("wind", value);
                                break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("parsing error: " + e.getMessage());
        }
        return data;
    }
}