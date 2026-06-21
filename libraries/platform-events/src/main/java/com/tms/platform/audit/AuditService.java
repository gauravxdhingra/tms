package com.tms.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Writes audit records within the CALLER'S transaction (Propagation.MANDATORY).
 * Services call this directly from application service methods, or use the
 * @Audited AOP annotation which calls this automatically.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final ObjectMapper          objectMapper;
    private final Clock                 clock;

    public AuditService(AuditEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository   = repository;
        this.objectMapper = objectMapper;
        this.clock        = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            String aggregateType,
            String aggregateId,
            String action,
            String userId,
            String legalEntityId,
            String correlationId,
            Object payload,
            String sourceIp) {

        String payloadJson = toJson(payload);

        AuditEvent event = AuditEvent.of(
            aggregateType, aggregateId, action,
            userId, legalEntityId, correlationId,
            payloadJson, null, sourceIp,
            Instant.now(clock)
        );

        repository.save(event);
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }
}
