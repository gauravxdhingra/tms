package com.tms.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.createdAt ASC LIMIT :limit")
    List<OutboxEvent> findUnpublished(@Param("limit") int limit);

    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.publishedAt IS NULL AND e.createdAt < :olderThan")
    long countStuckEvents(@Param("olderThan") Instant olderThan);
}
