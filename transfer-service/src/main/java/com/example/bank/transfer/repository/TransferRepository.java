package com.example.bank.transfer.repository;

import com.example.bank.transfer.domain.SagaState;
import com.example.bank.transfer.domain.Transfer;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    /** Cevap işlenirken satırı kilitler: aynı transfer için iki cevap aynı anda işlenmez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") Long id);

    /** Takılmış (son adımı cutoff'tan eski, bitmemiş) saga'lar. SKIP LOCKED: iki recovery örneği çakışmaz. */
    @Query(value = """
            select * from transfer
            where saga_state in ('STARTED', 'DEBITED', 'COMPENSATING')
              and last_step_at < :cutoff
            order by last_step_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Transfer> lockStuck(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    long countBySagaState(SagaState state);

    /** Bitmiş (terminal) ve henüz mutabakatı yapılmamış transferler; cutoff'tan önce bitenler (geç gelen mesajlara tolerans). */
    @Query("""
            select t from Transfer t
            where t.reconciledAt is null
              and t.sagaState in (com.example.bank.transfer.domain.SagaState.COMPLETED,
                                  com.example.bank.transfer.domain.SagaState.FAILED,
                                  com.example.bank.transfer.domain.SagaState.COMPENSATED)
              and t.completedAt < :cutoff
            order by t.id
            """)
    List<Transfer> findUnreconciled(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Query("""
            select count(t) from Transfer t
            where t.reconciledAt is null
              and t.sagaState in (com.example.bank.transfer.domain.SagaState.COMPLETED,
                                  com.example.bank.transfer.domain.SagaState.FAILED,
                                  com.example.bank.transfer.domain.SagaState.COMPENSATED)
            """)
    long countUnreconciled();
}
