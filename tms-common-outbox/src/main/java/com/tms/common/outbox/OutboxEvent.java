package com.tms.common.outbox;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the transactional outbox table.
 * Each service that publishes domain events writes to this table within the
 * same DB transaction as the business state change. A relay process then
 * reads unpublished rows and forwards them to Kafka.
 *
 * In production: Debezium CDC reads the table via Postgres logical replication.
 * In local-dev:  OutboxRelayScheduler polls every 500ms as a fallback.
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = {
        @Index(name = "idx_outbox_published_at",    columnList = "published_at"),
        @Index(name = "idx_outbox_aggregate_id",    columnList = "aggregate_id"),
        @Index(name = "idx_outbox_correlation_id",  columnList = "correlation_id")
    }
)
public class OutboxEvent {

    @Id
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(name = "kafka_topic", nullable = false, length = 200)
    private String kafkaTopic;

    @Column(name = "kafka_key", nullable = false, length = 200)
    private String kafkaKey;

    /** Avro-serialized payload encoded as base64 for storage. */
    @Lob
    @Column(name = "payload", nullable = false)
    private byte[] payload;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "correlation_id", nullable = false, length = 100)
    private String correlationId;

    @Column(name = "legal_entity_id", nullable = false, length = 50)
    private String legalEntityId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected OutboxEvent() {}

    public static OutboxEvent create(
            String aggregateType,
            String aggregateId,
            String eventType,
            String kafkaTopic,
            String kafkaKey,
            byte[] payload,
            int schemaVersion,
            String correlationId,
            String legalEntityId) {

        OutboxEvent e = new OutboxEvent();
        e.eventId       = UUID.randomUUID();
        e.aggregateType = aggregateType;
        e.aggregateId   = aggregateId;
        e.eventType     = eventType;
        e.kafkaTopic    = kafkaTopic;
        e.kafkaKey      = kafkaKey;
        e.payload       = payload;
        e.schemaVersion = schemaVersion;
        e.correlationId = correlationId;
        e.legalEntityId = legalEntityId;
        e.createdAt     = Instant.now();
        return e;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public boolean isPublished()         { return publishedAt != null; }
    public UUID getEventId()             { return eventId; }
    public String getAggregateType()     { return aggregateType; }
    public String getAggregateId()       { return aggregateId; }
    public String getEventType()         { return eventType; }
    public String getKafkaTopic()        { return kafkaTopic; }
    public String getKafkaKey()          { return kafkaKey; }
    public byte[] getPayload()           { return payload; }
    public int getSchemaVersion()        { return schemaVersion; }
    public String getCorrelationId()     { return correlationId; }
    public String getLegalEntityId()     { return legalEntityId; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getPublishedAt()      { return publishedAt; }
}
