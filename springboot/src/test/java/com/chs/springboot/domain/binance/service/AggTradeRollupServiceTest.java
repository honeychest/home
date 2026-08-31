package com.chs.springboot.domain.binance.service;

import com.chs.springboot.domain.binance.repository.AggTrade1mRepository;
import com.chs.springboot.domain.binance.repository.AggTrade5mRepository;
import com.chs.springboot.domain.binance.repository.AggTradeCollectStatusRepository;
import com.chs.springboot.global.redis.LeaderElectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AggTradeRollupServiceTest {

    @Test
    @DisplayName("raw 저장이 꺼지면 1분 롤업이 저장 없이 종료된다")
    void disabledSaveSkipsRollup1m() {
        Fixture fixture = disabledFixture();

        fixture.service.rollup1m();

        fixture.assertNoInteractions();
    }

    @Test
    @DisplayName("raw 저장이 꺼지면 5분 롤업이 저장 없이 종료된다")
    void disabledSaveSkipsRollup5m() {
        Fixture fixture = disabledFixture();

        fixture.service.rollup5m();

        fixture.assertNoInteractions();
    }

    @Test
    @DisplayName("raw 저장이 꺼지면 수동 롤업 범위 API가 no-op 결과를 반환한다")
    void disabledSaveSkipsRollupRange() {
        Fixture fixture = disabledFixture();

        Map<String, Integer> result = fixture.service.rollupRange(1_000L, 2_000L);

        assertThat(result).containsEntry("inserted1m", 0).containsEntry("inserted5m", 0);
        fixture.assertNoInteractions();
    }

    private Fixture disabledFixture() {
        Fixture fixture = new Fixture(
                mock(LeaderElectionService.class),
                mock(AggTrade1mRepository.class),
                mock(AggTrade5mRepository.class),
                mock(AggTradeCollectStatusRepository.class),
                mock(StringRedisTemplate.class),
                mock(JdbcTemplate.class),
                mock(AggTrade1sRollupService.class),
                mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(fixture.service, "aggTradeSaveEnabled", false);
        return fixture;
    }

    private static final class Fixture {
        private final LeaderElectionService leaderElectionService;
        private final AggTrade1mRepository agg1mRepository;
        private final AggTrade5mRepository agg5mRepository;
        private final AggTradeCollectStatusRepository statusRepository;
        private final StringRedisTemplate redisTemplate;
        private final JdbcTemplate jdbcTemplate;
        private final AggTrade1sRollupService agg1sRollupService;
        private final ApplicationEventPublisher eventPublisher;
        private final AggTradeRollupService service;

        private Fixture(LeaderElectionService leaderElectionService,
                        AggTrade1mRepository agg1mRepository,
                        AggTrade5mRepository agg5mRepository,
                        AggTradeCollectStatusRepository statusRepository,
                        StringRedisTemplate redisTemplate,
                        JdbcTemplate jdbcTemplate,
                        AggTrade1sRollupService agg1sRollupService,
                        ApplicationEventPublisher eventPublisher) {
            this.leaderElectionService = leaderElectionService;
            this.agg1mRepository = agg1mRepository;
            this.agg5mRepository = agg5mRepository;
            this.statusRepository = statusRepository;
            this.redisTemplate = redisTemplate;
            this.jdbcTemplate = jdbcTemplate;
            this.agg1sRollupService = agg1sRollupService;
            this.eventPublisher = eventPublisher;
            this.service = new AggTradeRollupService(
                    leaderElectionService, agg1mRepository, agg5mRepository, statusRepository,
                    redisTemplate, jdbcTemplate, agg1sRollupService, eventPublisher);
        }

        private void assertNoInteractions() {
            verifyNoInteractions(leaderElectionService, agg1mRepository, agg5mRepository,
                    statusRepository, redisTemplate, jdbcTemplate, agg1sRollupService, eventPublisher);
        }
    }
}
