package com.tms.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findByAggregateTypeAndAggregateIdOrderByOccurredAtDesc(
        String aggregateType, String aggregateId, Pageable pageable);

    @Query("""
        SELECT a FROM AuditEvent a
        WHERE a.legalEntityId = :entityId
          AND a.occurredAt BETWEEN :from AND :to
        ORDER BY a.occurredAt DESC
        """)
    Page<AuditEvent> findByLegalEntityAndTimeRange(
        @Param("entityId") String legalEntityId,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable pageable);
}
