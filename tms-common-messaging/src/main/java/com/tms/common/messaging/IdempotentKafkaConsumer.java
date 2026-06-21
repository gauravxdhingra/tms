package com.tms.common.messaging;

import com.tms.common.idempotency.IdempotencyStore;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all TMS Kafka consumers.
 *
 * Guarantees exactly-once processing semantics at the consumer level:
 * uses the Avro event's eventId (from EventMetadata) as the idempotency key.
 * If the same eventId arrives twice (Kafka at-least-once delivery), the second
 * invocation is a no-op.
 *
 * Concrete consumers extend this and implement processEvent().
 */
public abstract class IdempotentKafkaConsumer<T extends SpecificRecord> {

    private static final Logger log = LoggerFactory.getLogger(IdempotentKafkaConsumer.class);

    private final IdempotencyStore idempotencyStore;

    protected IdempotentKafkaConsumer(IdempotencyStore idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }

    public final void onMessage(ConsumerRecord<String, T> record) {
        String eventId = extractEventId(record.value());

        IdempotencyStore.IdempotencyState state = idempotencyStore.getState(eventId);

        if (state == IdempotencyStore.IdempotencyState.COMPLETED) {
            log.debug("Skipping duplicate Kafka event: eventId={} topic={} partition={} offset={}",
                eventId, record.topic(), record.partition(), record.offset());
            return;
        }

        if (!idempotencyStore.claimKey(eventId)) {
            log.debug("Concurrent duplicate Kafka event ignored: eventId={}", eventId);
            return;
        }

        try {
            processEvent(record.value(), record);
            idempotencyStore.storeResponse(eventId, Boolean.TRUE);
        } catch (Exception e) {
            idempotencyStore.releaseKey(eventId);
            throw e;
        }
    }

    /**
     * Extract the eventId from the Avro record's metadata field.
     * Subclasses must implement this because Avro doesn't give us a common interface.
     */
    protected abstract String extractEventId(T event);

    /**
     * Process the event. Called at-most-once per eventId.
     * Must be idempotent anyway as a defence-in-depth measure.
     */
    protected abstract void processEvent(T event, ConsumerRecord<String, T> record);
}
