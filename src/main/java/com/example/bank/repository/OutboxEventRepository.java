package com.example.bank.repository;

import com.example.bank.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Yayınlanmamış event'leri sırayla alır ve satırları kilitler. SKIP LOCKED: aynı anda birden fazla
     * publisher çalışırsa (ör. iki uygulama örneği) birbirlerinin kilitlediği satırları atlar, aynı event'i
     * iki kez almaz. Transaction içinde çağrılmalıdır.
     */
    @Query(value = """
            select * from outbox_event
            where published_at is null
            order by seq
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("limit") int limit);

    long countByPublishedAtIsNull();

    long countByPublishedAtIsNotNull();

    @Query("select min(e.createdAt) from OutboxEvent e where e.publishedAt is null")
    Instant oldestPendingCreatedAt();

    List<OutboxEvent> findByAggregateTypeAndAggregateIdIn(String aggregateType, Collection<String> aggregateIds);
}
