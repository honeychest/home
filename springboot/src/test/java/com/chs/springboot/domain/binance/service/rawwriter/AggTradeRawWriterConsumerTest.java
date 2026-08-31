package com.chs.springboot.domain.binance.service.rawwriter;

import com.chs.springboot.global.redis.LeadershipChangedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AggTradeRawWriterConsumerTest {

    private final AggTradeRawWriterService writerService = mock(AggTradeRawWriterService.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final AggTradeRawWriterDryRunVerifier verifier = mock(AggTradeRawWriterDryRunVerifier.class);
    private final KafkaPipelineSwitchboard switchboard = mock(KafkaPipelineSwitchboard.class);
    private final AggTradeRawWriterKafkaTelemetryService telemetryService = mock(AggTradeRawWriterKafkaTelemetryService.class);
    private final AggTradeRawWriterBatchPartitioner batchPartitioner =
            new AggTradeRawWriterBatchPartitioner(new AggTradeRawWriterMessageParser(new com.fasterxml.jackson.databind.ObjectMapper()));
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final AggTradeRawWriterConsumer consumer =
            new AggTradeRawWriterConsumer(writerService, kafkaTemplate, registry, verifier, switchboard, telemetryService, batchPartitioner);
    private final Acknowledgment ack = mock(Acknowledgment.class);

    AggTradeRawWriterConsumerTest() {
        when(switchboard.aggTradeRawWriterPlan())
                .thenReturn(KafkaPipelineExecutionPlan.from(KafkaPipelineState.DEBUG, "raw_agg_trade", "raw_agg_trade_test"));
    }

    @Test
    void startsListenerWhenBecomingLeaderAndContainerStopped() {
        when(registry.getListenerContainer("rawWriterListener")).thenReturn(container);
        when(container.isRunning()).thenReturn(false);

        consumer.onLeadershipChanged(new LeadershipChangedEvent("SERVER1", true));

        verify(container).start();
    }

    @Test
    void stopsListenerAndResetsVerifierWhenLosingLeadership() {
        when(registry.getListenerContainer("rawWriterListener")).thenReturn(container);
        when(container.isRunning()).thenReturn(true);

        consumer.onLeadershipChanged(new LeadershipChangedEvent("SERVER1", false));

        verify(container).stop();
        verify(verifier).discardInFlightVerification();
    }

    @Test
    void doesNotStartListenerIfAlreadyRunning() {
        when(registry.getListenerContainer("rawWriterListener")).thenReturn(container);
        when(container.isRunning()).thenReturn(true);

        consumer.onLeadershipChanged(new LeadershipChangedEvent("SERVER1", true));

        verify(container, never()).start();
    }

    @Test
    void doesNotStopListenerOrResetVerifierIfAlreadyStopped() {
        when(registry.getListenerContainer("rawWriterListener")).thenReturn(container);
        when(container.isRunning()).thenReturn(false);

        consumer.onLeadershipChanged(new LeadershipChangedEvent("SERVER1", false));

        verify(container, never()).stop();
        verify(verifier, never()).discardInFlightVerification();
    }

    @Test
    void ignoresLeadershipEventWhenListenerContainerMissing() {
        when(registry.getListenerContainer("rawWriterListener")).thenReturn(null);

        consumer.onLeadershipChanged(new LeadershipChangedEvent("SERVER1", true));
        consumer.onLeadershipChanged(new LeadershipChangedEvent("SERVER1", false));

        verify(verifier, never()).discardInFlightVerification();
    }

    @Test
    void ignoresLeadershipEventWhenPipelineDisabled() {
        when(switchboard.aggTradeRawWriterPlan())
                .thenReturn(KafkaPipelineExecutionPlan.from(KafkaPipelineState.OFF, "raw_agg_trade", "raw_agg_trade_test"));

        consumer.onLeadershipChanged(new LeadershipChangedEvent("SERVER1", true));

        verify(registry, never()).getListenerContainer("rawWriterListener");
    }

    @Test
    void acknowledgesOffsetAfterWriterSuccess() {
        consumer.consume(List.of(record("BTCUSDT|FUTURES", validJson())), ack);

        verify(telemetryService).recordConsumed(1);
        verify(writerService).writeParsedBatch(org.mockito.ArgumentMatchers.anyList());
        verify(telemetryService).recordWriteSuccess(1);
        verify(ack).acknowledge();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void leavesOffsetUncommittedWhenDbFails() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down"))
                .when(writerService).writeParsedBatch(org.mockito.ArgumentMatchers.anyList());

        consumer.consume(List.of(record("BTCUSDT|FUTURES", validJson())), ack);

        verify(telemetryService).recordDbFailure(eq(1), anyString());
        verify(ack, never()).acknowledge();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void recordsRetrySuccessWhenPreviouslyFailedOffsetIsAcknowledged() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down"))
                .doNothing()
                .when(writerService).writeParsedBatch(org.mockito.ArgumentMatchers.anyList());
        List<ConsumerRecord<String, String>> records = List.of(record("BTCUSDT|FUTURES", validJson()));

        consumer.consume(records, ack);
        consumer.consume(records, ack);

        verify(telemetryService).recordDbFailure(eq(1), anyString());
        verify(telemetryService).recordRetrySuccess(1);
        verify(ack).acknowledge();
    }

    @Test
    void sendsInvalidMessageToDlqThenAcknowledges() {
        CompletableFuture<SendResult<String, String>> dlqSent = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("market.aggtrade.dlq"), eq("BTCUSDT|FUTURES"), anyString()))
                .thenReturn(dlqSent);

        consumer.consume(List.of(record("BTCUSDT|FUTURES", "{bad-json")), ack);

        verify(telemetryService).recordInvalidRecord(eq("BTCUSDT"), eq("FUTURES"), eq(1), eq(7L), anyString());
        verify(telemetryService).recordDlqPublished(eq("BTCUSDT"), eq("FUTURES"), eq(1), eq(7L), anyString());
        verify(kafkaTemplate).send(eq("market.aggtrade.dlq"), eq("BTCUSDT|FUTURES"), org.mockito.ArgumentMatchers.contains("INVALID_MESSAGE"));
        verify(writerService, never()).writeParsedBatch(org.mockito.ArgumentMatchers.anyList());
        verify(ack).acknowledge();
    }

    @Test
    void leavesOffsetUncommittedWhenDlqPublishFails() {
        CompletableFuture<SendResult<String, String>> dlqFailed = new CompletableFuture<>();
        dlqFailed.completeExceptionally(new RuntimeException("kafka down"));
        when(kafkaTemplate.send(eq("market.aggtrade.dlq"), eq("BTCUSDT|FUTURES"), anyString()))
                .thenReturn(dlqFailed);

        consumer.consume(List.of(record("BTCUSDT|FUTURES", "{bad-json")), ack);

        verify(telemetryService).recordDlqPublishFailure(eq("BTCUSDT"), eq("FUTURES"), eq(1), eq(7L), anyString());
        verify(ack, never()).acknowledge();
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("market.aggtrade.raw", 1, 7L, key, value);
    }

    private String validJson() {
        return """
                {
                  "symbol": "BTCUSDT",
                  "marketType": "FUTURES",
                  "payload": {
                    "e": "aggTrade",
                    "a": 123,
                    "p": "100.0",
                    "q": "0.01",
                    "f": 1,
                    "l": 2,
                    "T": 1778410000000,
                    "m": false
                  },
                  "receivedAt": 1778410000123
                }
                """;
    }
}
