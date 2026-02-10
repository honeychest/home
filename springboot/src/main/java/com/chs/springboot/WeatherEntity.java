package com.chs.springboot;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "weather_history", indexes = {
        @Index(name = "idx_region_fcst", columnList = "region, fcstDateTime")
})
public class WeatherEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String region;

    private String nx;
    private String ny;

    @Column(nullable = false)
    private LocalDateTime fcstDateTime;

    private String tmp;
    private String hum;
    private String rain;
    private String wind;

    @Column(updatable = false)
    private LocalDateTime regDateTime;

    @PrePersist
    public void prePersist() {
        this.regDateTime = LocalDateTime.now();
    }
}