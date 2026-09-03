package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.repository.ForceOrderRepository;
import com.chs.springboot.domain.binance.repository.OpenInterestRepository;
import com.chs.springboot.domain.binance.repository.SignalParamsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SignalDataServiceTest {

    @Test
    @DisplayName("large trade threshold는 항상 기본값을 반환한다")
    void calcLargeTradeThreshold_returnsDefaultValue() {
        SignalDataService service = new SignalDataService(
                mock(OpenInterestRepository.class),
                mock(ForceOrderRepository.class),
                mock(SignalCandleSource.class),
                mock(SignalParamsRepository.class)
        );

        assertThat(service.calcLargeTradeThreshold("BTCUSDT"))
                .isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    @DisplayName("range만 주면 기존 range 기준으로 1분봉을 조회한다")
    void getHistoryData_usesRangeWhenCustomStartIsAbsent() {
        Fixture fixture = fixture();

        fixture.service.getHistoryData("BTCUSDT", "50m", null);

        verify(fixture.candleSource).sumEnergy(
                eq("BTCUSDT"), eq(SignalCandleSource.Interval.ONE_MINUTE),
                anyLong(), anyLong(), eq(SignalCandleSource.QueryMode.COMPLETED));
    }

    @Test
    @DisplayName("짧은 커스텀 구간은 1분봉으로 조회한다")
    void getHistoryData_usesOneMinuteForShortCustomRange() {
        Fixture fixture = fixture();
        long fromMs = System.currentTimeMillis() - 49 * 60_000L;

        fixture.service.getHistoryData("BTCUSDT", null, fromMs);

        verify(fixture.candleSource).sumEnergy(
                eq("BTCUSDT"), eq(SignalCandleSource.Interval.ONE_MINUTE),
                eq(fromMs), anyLong(), eq(SignalCandleSource.QueryMode.COMPLETED));
    }

    @Test
    @DisplayName("긴 커스텀 구간은 5분봉으로 조회한다")
    void getHistoryData_usesFiveMinutesForLongCustomRange() {
        Fixture fixture = fixture();
        long fromMs = System.currentTimeMillis() - 50 * 60_000L - 1;

        fixture.service.getHistoryData("BTCUSDT", null, fromMs);

        verify(fixture.candleSource).sumEnergy(
                eq("BTCUSDT"), eq(SignalCandleSource.Interval.FIVE_MINUTES),
                eq(fromMs), anyLong(), eq(SignalCandleSource.QueryMode.COMPLETED));
    }

    @Test
    @DisplayName("range와 fromMs가 모두 있으면 fromMs가 우선한다")
    void getHistoryData_prefersCustomStartOverRange() {
        Fixture fixture = fixture();
        long fromMs = System.currentTimeMillis() - 51 * 60_000L;

        fixture.service.getHistoryData("BTCUSDT", "invalid-range", fromMs);

        verify(fixture.candleSource).sumEnergy(
                eq("BTCUSDT"), eq(SignalCandleSource.Interval.FIVE_MINUTES),
                eq(fromMs), anyLong(), eq(SignalCandleSource.QueryMode.COMPLETED));
    }

    @Test
    @DisplayName("미래의 fromMs는 원천을 호출하지 않고 거부한다")
    void getHistoryData_rejectsFutureCustomStartBeforeQuery() {
        SignalCandleSource candleSource = mock(SignalCandleSource.class);
        ForceOrderRepository forceOrderRepository = mock(ForceOrderRepository.class);
        SignalDataService service = new SignalDataService(
                mock(OpenInterestRepository.class), forceOrderRepository,
                candleSource, mock(SignalParamsRepository.class));

        assertThatThrownBy(() -> service.getHistoryData("BTCUSDT", null, System.currentTimeMillis() + 1))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(candleSource, forceOrderRepository);
    }

    @Test
    @DisplayName("음수 fromMs는 거부한다")
    void getHistoryData_rejectsNegativeCustomStart() {
        SignalCandleSource candleSource = mock(SignalCandleSource.class);
        ForceOrderRepository forceOrderRepository = mock(ForceOrderRepository.class);
        SignalDataService service = new SignalDataService(
                mock(OpenInterestRepository.class), forceOrderRepository,
                candleSource, mock(SignalParamsRepository.class));

        assertThatThrownBy(() -> service.getHistoryData("BTCUSDT", null, -1L))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(candleSource, forceOrderRepository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("range와 fromMs가 없으면 거부한다")
    void getHistoryData_rejectsMissingRange(String range) {
        SignalCandleSource candleSource = mock(SignalCandleSource.class);
        ForceOrderRepository forceOrderRepository = mock(ForceOrderRepository.class);
        SignalDataService service = new SignalDataService(
                mock(OpenInterestRepository.class), forceOrderRepository,
                candleSource, mock(SignalParamsRepository.class));

        assertThatThrownBy(() -> service.getHistoryData("BTCUSDT", range, null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(candleSource, forceOrderRepository);
    }

    private Fixture fixture() {
        SignalCandleSource candleSource = mock(SignalCandleSource.class);
        ForceOrderRepository forceOrderRepository = mock(ForceOrderRepository.class);
        when(candleSource.sumEnergy(anyString(), any(SignalCandleSource.Interval.class), anyLong(), anyLong(), any(SignalCandleSource.QueryMode.class)))
                .thenReturn(new SignalCandleSource.Energy(BigDecimal.TEN, BigDecimal.TEN));
        when(forceOrderRepository.sumLiqTotalBySymbolAndTimeRange(anyString(), anyLong(), anyLong()))
                .thenReturn(java.util.List.of());
        when(forceOrderRepository.findTop10BySymbolAndSideAndTradeTimeMsBetweenOrderByTradeTimeMsDesc(anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(java.util.List.of());
        return new Fixture(
                new SignalDataService(
                        mock(OpenInterestRepository.class), forceOrderRepository,
                        candleSource, mock(SignalParamsRepository.class)),
                candleSource);
    }

    private record Fixture(SignalDataService service, SignalCandleSource candleSource) {
    }
}
