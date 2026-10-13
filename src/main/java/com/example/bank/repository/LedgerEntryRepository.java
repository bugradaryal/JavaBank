package com.example.bank.repository;

import com.example.bank.domain.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Page<LedgerEntry> findByAccountIdOrderByIdDesc(Long accountId, Pageable pageable);

    @Query("""
            select sum(case when e.entryType = com.example.bank.domain.EntryType.CREDIT
                            then e.amount else -e.amount end)
            from LedgerEntry e where e.accountId = :accountId
            """)
    BigDecimal sumNetForAccount(@Param("accountId") Long accountId);

    /** Hesabın ledger'dan türetilen bakiyesi: CREDIT toplamı - DEBIT toplamı. Kayıt yoksa 0. */
    default BigDecimal netForAccount(Long accountId) {
        BigDecimal net = sumNetForAccount(accountId);
        return net == null ? BigDecimal.ZERO : net;
    }
}
