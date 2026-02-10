package com.chs.springboot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface WeatherRepository extends JpaRepository<WeatherEntity, Long> {

    // 특정 지역과 예보 시간의 중복 여부 확인
    boolean existsByRegionAndFcstDateTime(String region, LocalDateTime fcstDateTime);

    // 특정 예보 시간에 해당하는 모든 지역 데이터 조회
    @Query("SELECT w FROM WeatherEntity w WHERE w.fcstDateTime = :targetTime")
    List<WeatherEntity> findAllByFcstDateTime(@Param("targetTime") LocalDateTime targetTime);
}