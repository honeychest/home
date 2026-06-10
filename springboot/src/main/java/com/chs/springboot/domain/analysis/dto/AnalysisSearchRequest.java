// [AGENT] Analysis 수동 탐색 요청 DTO — POST /api/analysis/search
package com.chs.springboot.domain.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class AnalysisSearchRequest {

    @NotBlank(message = "symbol is required")
    private String symbol;

    @NotBlank(message = "timeframe is required")
    @Pattern(regexp = "1m|5m|15m", message = "timeframe must be 1m, 5m or 15m")
    private String timeframe;

    @NotNull(message = "fromMs is required")
    private Long fromMs;

    @NotNull(message = "toMs is required")
    private Long toMs;

    @Valid
    @NotNull(message = "conditions is required")
    private Conditions conditions;

    @Getter
    @Setter
    public static class Conditions {

        @JsonProperty("price_change_rate")
        @NotNull
        private Double priceChangeRate;

        @JsonProperty("rate_tolerance")
        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "rate_tolerance must be > 0")
        private Double rateTolerance;

        @JsonProperty("total_volume")
        @NotNull
        @DecimalMin(value = "0", message = "total_volume must be >= 0")
        private BigDecimal totalVolume;

        @JsonProperty("vol_tolerance")
        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "vol_tolerance must be > 0")
        private Double volTolerance;

        @JsonProperty("use_rate_filter")
        private boolean useRateFilter = true;

        @JsonProperty("use_vol_filter")
        private boolean useVolFilter = true;
    }
}
