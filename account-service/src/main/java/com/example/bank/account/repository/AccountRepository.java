package com.example.bank.account.repository;

import com.example.bank.account.domain.Account;
import com.example.bank.common.domain.AccountKind;
import com.example.bank.common.domain.Currency;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** SELECT ... FOR UPDATE. Aynı hesaba gelen diğer transaction'lar bu transaction bitene kadar bekler. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    Page<Account> findByCustomerId(Long customerId, Pageable pageable);

    Page<Account> findByKind(AccountKind kind, Pageable pageable);

    List<Account> findByKindIn(Collection<AccountKind> kinds);

    Optional<Account> findByKindAndCurrency(AccountKind kind, Currency currency);

    boolean existsByCustomerId(Long customerId);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountNumber();

    @Query("select sum(a.balance) from Account a")
    BigDecimal sumOfBalances();

    /** Tüm hesapların (kasa ve clearing dahil) bakiye toplamı. Sistem sağlıklıysa her zaman 0'dır. */
    default BigDecimal totalBalance() {
        BigDecimal total = sumOfBalances();
        return total == null ? BigDecimal.ZERO : total;
    }
}
