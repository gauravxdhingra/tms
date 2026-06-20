package com.tms.common.outbox;

import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Writes an outbox row within the CALLER'S transaction.
 * Propagation.MANDATORY ensures this is never called outside a transaction —
 * the whole point is that the outbox write and the business state change are atomic.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final AvroSerializer avroSerializer;

    public OutboxPublisher(OutboxEventRepository repository, AvroSerializer avroSerializer) {
        this.repository    = repository;
        this.avroSerializer = avroSerializer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(
            String aggregateType,
            String aggregateId,
            String kafkaTopic,
            String kafkaKey,
            SpecificRecord event,
            int schemaVersion,
            String correlationId,
            String legalEntityId) {

        byte[] payload;
        try {
            payload = avroSerializer.serialize(kafkaTopic, event);
        } catch (IOException e) {
            throw new OutboxSerializationException("Failed to serialize event for outbox", e);
        }

        OutboxEvent outboxEvent = OutboxEvent.create(
            aggregateType,
            aggregateId,
            event.getClass().getName(),
            kafkaTopic,
            kafkaKey,
            payload,
            schemaVersion,
            correlationId,
            legalEntityId
        );

        repository.save(outboxEvent);
        log.debug("Outbox event written: eventId={} topic={} key={}", outboxEvent.getEventId(), kafkaTopic, kafkaKey);
    }
}
