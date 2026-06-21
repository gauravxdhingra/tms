package com.tms.common.audit;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit record. Written within the same DB transaction as the business change.
 * Never update or delete rows from this table — it is an append-only ledger.
 *
 * A Kafka consumer forwards rows to OpenSearch for querying via the Audit Trail screen.
 */
@Entity
@Table(
    name = "audit_events",
    indexes = {
        @Index(name = "idx_audit_aggregate",      columnList = "aggregate_type, aggregate_id"),
        @Index(name = "idx_audit_user",           columnList = "user_id"),
        @Index(name = "idx_audit_occurred_at",    columnList = "occurred_at"),
        @Index(name = "idx_audit_legal_entity",   columnList = "legal_entity_id"),
        @Index(name = "idx_audit_correlation_id", columnList = "correlation_id")
    }
)
public class AuditEvent {

    @Id
    @Column(name = "audit_id", updatable = false, nullable = false)
    private UUID auditId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "legal_entity_id", nullable = false, length = 50)
    private String legalEntityId;

    @Column(name = "correlation_id", nullable = false, length = 100)
    private String correlationId;

    /** JSON snapshot of the entity state after the action. */
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;

    /** JSON of changed fields: {"fieldName": {"before": x, "after": y}} */
    @Column(name = "diff", columnDefinition = "jsonb")
    private String diff;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    protected AuditEvent() {}

    public static AuditEvent of(
            String aggregateType,
            String aggregateId,
            String action,
            String userId,
            String legalEntityId,
            String correlationId,
            String payload,
            String diff,
            String sourceIp,
            Instant occurredAt) {

        AuditEvent e = new AuditEvent();
        e.auditId       = UUID.randomUUID();
        e.aggregateType = aggregateType;
        e.aggregateId   = aggregateId;
        e.action        = action;
        e.userId        = userId;
        e.legalEntityId = legalEntityId;
        e.correlationId = correlationId;
        e.payload       = payload;
        e.diff          = diff;
        e.sourceIp      = sourceIp;
        e.occurredAt    = occurredAt;
        return e;
    }

    public UUID getAuditId()         { return auditId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId()   { return aggregateId; }
    public String getAction()        { return action; }
    public String getUserId()        { return userId; }
    public String getLegalEntityId() { return legalEntityId; }
    public String getCorrelationId() { return correlationId; }
    public String getPayload()       { return payload; }
    public String getDiff()          { return diff; }
    public String getSourceIp()      { return sourceIp; }
    public Instant getOccurredAt()   { return occurredAt; }
}
