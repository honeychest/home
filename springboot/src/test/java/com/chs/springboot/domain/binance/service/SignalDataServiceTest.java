package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.repository.AggTrade1mRepository;
import com.chs.springboot.domain.binance.repository.AggTrade5mRepository;
import com.chs.springboot.domain.binance.repository.ForceOrderRepository;
import com.chs.springboot.domain.binance.repository.OpenInterestRepository;
import com.chs.springboot.domain.binance.repository.SignalParamsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SignalDataServiceTest {

    @Test
    @DisplayName("large trade threshold는 항상 기본값을 반환한다")
    void calcLargeTradeThreshold_returnsDefaultValue() {
        SignalDataService service = new SignalDataService(
                mock(OpenInterestRepository.class),
                mock(ForceOrderRepository.class),
                mock(AggTrade1mRepository.class),
                mock(AggTrade5mRepository.class),
                mock(SignalParamsRepository.class)
        );

        assertThat(service.calcLargeTradeThreshold("BTCUSDT"))
                .isEqualByComparingTo(new BigDecimal("10000"));
    }
}
