package com.tokengateway.service;

import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.TokenUsageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes usage events without waiting for PostgreSQL.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TokenUsageEventPublisher {

    private final KafkaTemplate<String, TokenUsageEvent> kafkaTemplate;
    private final GatewayProperties properties;

    public void publish(TokenUsageEvent event) {
        String topic = properties.getKafka().getUsageTopic();
        kafkaTemplate.send(topic, event.userId(), event)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.error("[USAGE-KAFKA] Publish failed requestId={} topic={}: {}",
                                event.requestId(), topic, error.getMessage(), error);
                    } else {
                        log.debug("[USAGE-KAFKA] Published requestId={} topic={} partition={} offset={}",
                                event.requestId(), topic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
