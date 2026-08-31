package com.chs.springboot.domain.binance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@ConditionalOnProperty(value = "binance.agg-trade.kafka.enabled", havingValue = "true")
public class AggTradeKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(AggTradeKafkaProducer.class);

    public static final String RAW_TOPIC = "market.aggtrade.raw";
    public static final String DLQ_TOPIC = "market.aggtrade.dlq";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public AggTradeKafkaProducer(KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<SendResult<String, String>> publishRaw(String payloadJson,
                                                                    String symbol,
                                                                    String marketType,
                                                                    long receivedAt) {
        String key = symbol + "|" + marketType;
        String value = buildEnvelope(payloadJson, symbol, marketType, receivedAt);
        CompletableFuture<SendResult<String, String>> result = kafkaTemplate.send(RAW_TOPIC, key, value);
        result.whenComplete((sendResult, ex) -> {
            if (ex != null) {
                log.error("[AggTradeKafkaProducer] publish 실패 topic={} key={} error={}",
                        RAW_TOPIC, key, ex.getMessage());
            }
        });
        return result;
    }

    public CompletableFuture<SendResult<String, String>> publishRaw(String payloadJson,
                                                                    String symbol,
                                                                    String marketType) {
        return publishRaw(payloadJson, symbol, marketType, System.currentTimeMillis());
    }

    private String buildEnvelope(String payloadJson, String symbol, String marketType, long receivedAt) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("symbol", symbol);
            envelope.put("marketType", marketType);
            envelope.put("payload", payload);
            envelope.put("receivedAt", receivedAt);
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid aggTrade payload JSON", e);
        }
    }
}
