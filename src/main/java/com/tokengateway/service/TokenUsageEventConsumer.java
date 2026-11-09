package com.tokengateway.service;

import com.tokengateway.dto.TokenUsageEvent;
import com.tokengateway.model.UsageEvent;
import com.tokengateway.repository.GatewayUserRepository;
import com.tokengateway.repository.UsageEventRepository;
import com.tokengateway.repository.UserUsageSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Batch consumer that persists Kafka usage events and daily aggregates.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TokenUsageEventConsumer {

    private final UsageEventRepository usageEventRepository;
    private final UserUsageSummaryRepository summaryRepository;
    private final GatewayUserRepository userRepository;

    @KafkaListener(
            topics = "${gateway.kafka.usage-topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "tokenUsageKafkaListenerContainerFactory")
    @Transactional
    public void consume(List<TokenUsageEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        Set<String> requestIds = events.stream()
                .map(TokenUsageEvent::requestId)
                .collect(Collectors.toSet());
        Set<String> persistedIds = usageEventRepository.findByRequestIdIn(requestIds).stream()
                .map(UsageEvent::getRequestId)
                .collect(Collectors.toSet());

        List<TokenUsageEvent> newEvents = events.stream()
                .filter(event -> !persistedIds.contains(event.requestId()))
                .collect(Collectors.toMap(
                        TokenUsageEvent::requestId,
                        Function.identity(),
                        (first, ignored) -> first))
                .values()
                .stream()
                .toList();

        if (newEvents.isEmpty()) {
            log.debug("[USAGE-KAFKA] Ignored duplicate batch size={}", events.size());
            return;
        }

        newEvents.forEach(event -> userRepository.registerIfAbsent(event.userId()));

        usageEventRepository.saveAll(newEvents.stream()
                .map(this::toUsageEvent)
                .toList());

        newEvents.forEach(event -> summaryRepository.upsertDailySummary(
                event.userId(), event.tokensConsumed(), event.cacheHit() ? 1 : 0));

        log.info("[USAGE-KAFKA] Persisted batch size={} skippedDuplicates={}",
                newEvents.size(), events.size() - newEvents.size());
    }

    private UsageEvent toUsageEvent(TokenUsageEvent event) {
        return UsageEvent.builder()
                .requestId(event.requestId())
                .userId(event.userId())
                .promptHash(event.promptHash())
                .promptLength(event.promptLength())
                .tokensConsumed(event.tokensConsumed())
                .cacheHit(event.cacheHit())
                .latencyMs((int) Math.min(Integer.MAX_VALUE, event.latencyMs()))
                .modelId(event.modelId() != null ? event.modelId() : "simulated-v1")
                .build();
    }
}
