package com.tms.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Fallback relay poller for local-dev and environments without Debezium CDC.
 * In production, Debezium reads the outbox table via Postgres logical replication
 * and this scheduler is disabled via tms.outbox.relay.enabled=false.
 *
 * Processes up to 100 events per tick to avoid long-running transactions.
 */
@Component
@ConditionalOnProperty(name = "tms.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    public OutboxRelayScheduler(OutboxEventRepository repository,
                                KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.repository    = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${tms.outbox.relay.interval-ms:500}")
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = repository.findUnpublished(BATCH_SIZE);
        if (pending.isEmpty()) return;

        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(event.getKafkaTopic(), event.getKafkaKey(), event.getPayload()).get();
                event.markPublished();
                repository.save(event);
            } catch (Exception ex) {
                log.error("Failed to relay outbox event eventId={}: {}", event.getEventId(), ex.getMessage());
                // Do not mark published — will retry on next tick
            }
        }

        log.debug("Outbox relay processed {} events", pending.size());
    }
}
