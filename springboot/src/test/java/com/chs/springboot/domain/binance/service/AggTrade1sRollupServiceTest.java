package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.model.AggTrade1s;
import com.chs.springboot.domain.binance.repository.AggTrade1sRepository;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.global.redis.LeaderElectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AggTrade1sRollupServiceTest {

    @Test
    @DisplayName("raw 저장이 꺼지면 1s 실시간 롤업·빈 캔들 교정·catch-up이 모두 저장 없이 종료된다")
    void disabledSaveSkipsRollupAndCatchUp() {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        AggTrade1sRepository agg1sRepository = mock(AggTrade1sRepository.class);
        AggTradeCollectStatusRepository statusRepository = mock(AggTradeCollectStatusRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AggTrade1sRollupService service = new AggTrade1sRollupService(
                leaderElectionService, agg1sRepository, statusRepository, redisTemplate, jdbcTemplate);
        ReflectionTestUtils.setField(service, "aggTradeSaveEnabled", false);

        service.rollup1s();
        service.correctRecentEmptyCandles();
        service.catchUp();

        assertThat(service.getCatchUpFuture().isDone()).isTrue();
        verifyNoInteractions(leaderElectionService, agg1sRepository, statusRepository, redisTemplate, jdbcTemplate);
    }

    @Test
    @DisplayName("batchInsert는 거래량과 거래수는 있지만 buy/sell quantity가 없는 1s 오염 row를 저장하지 않는다")
    void batchInsert_skipsInvalidDeltaSourceCandle() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AtomicInteger capturedBatchSize = new AtomicInteger(-1);
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenAnswer(invocation -> {
                    BatchPreparedStatementSetter setter = invocation.getArgument(1);
                    capturedBatchSize.set(setter.getBatchSize());
                    return new int[setter.getBatchSize()];
                });

        AggTrade1sRollupService service = new AggTrade1sRollupService(
                mock(LeaderElectionService.class),
                mock(AggTrade1sRepository.class),
                mock(AggTradeCollectStatusRepository.class),
                mock(StringRedisTemplate.class),
                jdbcTemplate
        );
        ReflectionTestUtils.setField(service, "aggTradeSaveEnabled", true);

        ReflectionTestUtils.invokeMethod(service, "batchInsert", List.of(
                invalidDeltaSourceCandle(),
                validCandle()
        ));

        verify(jdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        assertThat(capturedBatchSize.get()).isEqualTo(1);
    }

    private AggTrade1s invalidDeltaSourceCandle() {
        AggTrade1s c = validCandle();
        c.setCandleTimeMs(1_000L);
        c.setTotalVolume(new BigDecimal("100"));
        c.setBuyQuantity(BigDecimal.ZERO);
        c.setSellQuantity(BigDecimal.ZERO);
        c.setDelta(BigDecimal.ZERO);
        c.setTradeCount(3L);
        return c;
    }

    private AggTrade1s validCandle() {
        AggTrade1s c = new AggTrade1s();
        c.setSymbol("BTCUSDT");
        c.setMarketType("FUTURES");
        c.setCandleTimeMs(2_000L);
        c.setOpenPrice(new BigDecimal("100"));
        c.setHighPrice(new BigDecimal("101"));
        c.setLowPrice(new BigDecimal("99"));
        c.setClosePrice(new BigDecimal("100.5"));
        c.setVwap(new BigDecimal("100.25"));
        c.setBuyVolume(new BigDecimal("60"));
        c.setSellVolume(new BigDecimal("40"));
        c.setTotalVolume(new BigDecimal("100"));
        c.setBuyQuantity(new BigDecimal("0.6"));
        c.setSellQuantity(new BigDecimal("0.4"));
        c.setDelta(new BigDecimal("0.2"));
        c.setBuyTradeCount(2L);
        c.setSellTradeCount(1L);
        c.setTradeCount(3L);
        c.setMinAggTradeId(10L);
        c.setMaxAggTradeId(12L);
        c.setMinFirstTradeId(100L);
        c.setMaxLastTradeId(102L);
        return c;
    }
}
