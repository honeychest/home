package com.chs.springboot.domain.binance.service.rawwriter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kafka raw-writer 파이프라인의 동작 상태를 결정하는 단일 진입점.
 *
 * <p>상태값은 properties 가 아닌 코드 상수로 관리한다.
 * 이유: 운영 중 실수 토글(예: shadow 인 줄 알았는데 LIVE) 을 막고, 상태 전환을 PR 리뷰 흐름에 강제로 태우기 위함.
 * 전환 절차: 상수 값을 수정 -> PR/리뷰 -> 재배포.</p>
 *
 * <p>승급 권장 순서: OFF -> DRY_RUN -> DEBUG(_test 테이블 적재 정합성 확인) -> LIVE.</p>
 *
 * <p>{@code chs.raw-writer.force-off} 는 환경별 kill-switch. true 면 코드 상태와 무관하게 OFF 로 강등된다.
 * UP-진급은 불가능하므로 "실수 토글로 prod 가 LIVE 되는" 위험은 없다.
 * 로컬과 prod 가 같은 DB 를 보는 개발 환경에서 로컬이 prod 데이터를 건드리지 않도록 application-local 에서 true 로 둔다.</p>
 */
@Component
public class KafkaPipelineSwitchboard {

    // Phase 4에서 raw_agg_trade 저장을 중단한다. 재개는 코드 변경과 검토를 거쳐야 한다.
    private static final KafkaPipelineState AGG_TRADE_STATE = KafkaPipelineState.OFF;

    // LIVE 상태에서 INSERT 할 실 테이블.
    private static final String AGG_TRADE_LIVE_TABLE = "raw_agg_trade";

    // DEBUG(shadow) 상태에서 INSERT 할 검증용 테이블. 실 서비스 쿼리에서는 참조하지 않음.
    private static final String AGG_TRADE_DEBUG_TABLE = "raw_agg_trade_test";

    private final boolean forceOff;

    public KafkaPipelineSwitchboard(
            @Value("${chs.raw-writer.force-off:false}") boolean forceOff) {
        this.forceOff = forceOff;
    }

    /**
     * aggTrade raw-writer 가 따라야 할 현재 실행 플랜을 반환한다.
     * Consumer / Service / DryRunVerifier 가 이 메서드만 호출해 동작을 결정한다.
     */
    public KafkaPipelineExecutionPlan aggTradeRawWriterPlan() {
        if (forceOff) {
            return KafkaPipelineExecutionPlan.from(
                    KafkaPipelineState.OFF,
                    AGG_TRADE_LIVE_TABLE,
                    AGG_TRADE_DEBUG_TABLE
            );
        }
        return KafkaPipelineExecutionPlan.from(
                AGG_TRADE_STATE,
                AGG_TRADE_LIVE_TABLE,
                AGG_TRADE_DEBUG_TABLE
        );
    }
}
