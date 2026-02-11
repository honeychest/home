package com.chs.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/weather")
@CrossOrigin(origins = "*")
public class WeatherController {

    @Value("${weather.api.service-key}")
    private String serviceKey;

    @Value("${weather.api.base-url}")
    private String baseUrl;

    @Autowired
    private WeatherRepository weatherRepository;

    private final Map<String, int[]> locations = new LinkedHashMap<>();

    public WeatherController() {
        locations.put("서울특별시", new int[]{60, 127});
        locations.put("경기도", new int[]{60, 120});
        locations.put("강원도", new int[]{73, 134});
        locations.put("충청북도", new int[]{69, 107});
        locations.put("충청남도", new int[]{68, 100});
        locations.put("전라북도", new int[]{63, 89});
        locations.put("경상북도", new int[]{89, 91});
        locations.put("전라남도", new int[]{51, 67});
        locations.put("경상남도", new int[]{91, 77});
        locations.put("제주특별자치도", new int[]{52, 38});
    }

    @GetMapping("/available-hours")
    public List<Integer> getAvailableHours() {
        // DB에 저장된 고유한 시간대 조회
        List<Integer> hours = weatherRepository.findDistinctHours();
        System.out.println("Available hours: " + hours);
        return hours;
    }

    @GetMapping("/all")
    public Map<String, Map<String, String>> getAllWeather(
            @RequestParam(required = false) Integer hour  // 시간 선택 파라미터 (0-23)
    ) {
        Map<String, Map<String, String>> results = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        // hour 파라미터가 있으면 해당 시간으로, 없으면 현재 시간으로 설정
        LocalDateTime targetHour = (hour != null)
                ? now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
                : now.withMinute(0).withSecond(0).withNano(0);

        String currentHourStr = targetHour.format(DateTimeFormatter.ofPattern("HH00"));

        // 1. DB 조회: 현재 정각에 해당하는 데이터가 있는지 확인
        List<WeatherEntity> entities = weatherRepository.findAllByFcstDateTime(targetHour);
        for (WeatherEntity entity : entities) {
            Map<String, String> data = new HashMap<>();
            data.put("tmp", entity.getTmp());
            data.put("hum", entity.getHum());
            data.put("rain", entity.getRain());
            data.put("wind", entity.getWind());
            // 프론트엔드용 시간 필드 추가 (기존 데이터 구조 유지)
            data.put("baseTime", entity.getFcstDateTime().format(DateTimeFormatter.ofPattern("HHmm")));
            results.put(entity.getRegion(), data);
        }

        // 모든 지역 데이터가 DB에 있으면 즉시 반환
        if (results.size() >= locations.size()) {
            System.out.println("Serving data from DB for hour: " + targetHour.getHour());
            return results;
        }

        // 2. 데이터가 부족하면 API 호출
        System.out.println("Data missing for hour " + targetHour.getHour() + ". Calling API...");
        RestTemplate restTemplate = new RestTemplate();

        locations.forEach((name, coords) -> {
            if (results.containsKey(name)) return;

            Map<String, String> weatherData = fetchWeatherRecursive(restTemplate, name, coords, targetHour, currentHourStr, 0);

            if (weatherData != null && !weatherData.isEmpty()) {
                // 프론트엔드에 전달할 데이터 준비
                weatherData.put("baseTime", weatherData.get("fcstTime"));
                results.put(name, weatherData);

                // DB 저장은 별도로 진행
                try {
                    String fDate = weatherData.get("fcstDate");
                    String fTime = weatherData.get("fcstTime");
                    LocalDateTime fcstDT = LocalDateTime.parse(fDate + fTime, DateTimeFormatter.ofPattern("yyyyMMddHHmm"));

                    // 🆕 요청한 시간과 동일한지 확인 (targetHour와 fcstDT의 시간이 같을 때만 저장)
                    if (fcstDT.getHour() == targetHour.getHour() &&
                            !weatherRepository.existsByRegionAndFcstDateTime(name, fcstDT)) {
                        WeatherEntity entity = new WeatherEntity();
                        entity.setRegion(name);
                        entity.setNx(String.valueOf(coords[0]));
                        entity.setNy(String.valueOf(coords[1]));
                        entity.setFcstDateTime(fcstDT);
                        entity.setTmp(weatherData.get("tmp"));
                        entity.setHum(weatherData.get("hum"));
                        entity.setRain(weatherData.get("rain"));
                        entity.setWind(weatherData.get("wind"));
                        weatherRepository.save(entity);
                        System.out.println("Saved: " + name + " at " + fcstDT);
                    }
                } catch (Exception e) {
                    System.err.println("Save error: " + e.getMessage());
                }
            }
        });
        return results;
    }

    private Map<String, String> fetchWeatherRecursive(RestTemplate restTemplate, String name, int[] coords, LocalDateTime dateTime, String currentHour, int retryCount) {
        if (retryCount >= 5) return new HashMap<>();

        String baseDate = dateTime.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String baseTime = dateTime.format(DateTimeFormatter.ofPattern("HHmm"));

        String url = String.format("%s?serviceKey=%s&pageNo=1&numOfRows=1000&dataType=JSON&base_date=%s&base_time=%s&nx=%d&ny=%d",
                baseUrl, serviceKey, baseDate, baseTime, coords[0], coords[1]);

        try {
            String json = restTemplate.getForObject(url, String.class);
            Map<String, String> data = extractAllFcstData(json, currentHour);
            if (!data.isEmpty()) return data;
            return fetchWeatherRecursive(restTemplate, name, coords, dateTime.minusHours(1), currentHour, retryCount + 1);
        } catch (Exception e) {
            return fetchWeatherRecursive(restTemplate, name, coords, dateTime.minusHours(1), currentHour, retryCount + 1);
        }
    }

    private Map<String, String> extractAllFcstData(String json, String currentHour) {
        Map<String, String> data = new HashMap<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode items = root.path("response").path("body").path("items").path("item");

            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (currentHour.equals(item.path("fcstTime").asText())) {
                        data.put("fcstDate", item.path("fcstDate").asText());
                        data.put("fcstTime", item.path("fcstTime").asText());
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
            System.err.println("Parsing error: " + e.getMessage());
        }
        return data;
    }
}