package com.example.bank.account.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * account-service'in KENDİ verisi üzerinde muhasebe tutarlılık kontrolü. Tüm kontroller tek bir
 * REPEATABLE READ transaction'ında, yani aynı anlık görüntü (snapshot) üzerinde çalışır; kontrol sırasında
 * akan işlemler sonucu bozmaz.
 *
 * <p>Kontroller:
 * <ol>
 *   <li>TOTAL_NOT_ZERO: tüm bakiyelerin toplamı 0 olmalı;</li>
 *   <li>BALANCE_LEDGER_MISMATCH: her hesabın bakiyesi = ledger'daki CREDIT - DEBIT toplamı;</li>
 *   <li>ENTRY_WITHOUT_OPERATION: her ledger kaydının başarılı bir operation_log kaydı olmalı;</li>
 *   <li>OPERATION_ENTRY_COUNT: başarılı her işlem tam 2 ledger kaydı (borç + alacak) üretmiş olmalı;</li>
 *   <li>OPERATION_NOT_BALANCED: her işlemin ledger kayıtları kendi içinde sıfıra toplanmalı.</li>
 * </ol>
 * Clearing bakiyesi ihlal sayılmaz (yoldaki saga'lar için normal), sadece raporlanır.
 */
@Service
public class LedgerReconciliationService {

    private static final int MAX_VIOLATIONS_PER_CHECK = 50;

    public record Violation(String check, String detail) {
    }

    public record Report(Instant checkedAt, BigDecimal totalBalance, BigDecimal clearingBalance,
                         long accountsChecked, long ledgerEntriesChecked, List<Violation> violations) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    private final JdbcTemplate jdbc;

    public LedgerReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report run() {
        List<Violation> violations = new ArrayList<>();

        BigDecimal total = jdbc.queryForObject("select coalesce(sum(balance), 0) from account", BigDecimal.class);
        if (total.signum() != 0) {
            violations.add(new Violation("TOTAL_NOT_ZERO", "Tüm bakiyelerin toplamı " + total));
        }
        BigDecimal clearing = jdbc.queryForObject(
                "select coalesce(sum(balance), 0) from account where kind = 'CLEARING'", BigDecimal.class);

        jdbc.query("""
                select a.id, a.balance,
                       coalesce(sum(case when l.entry_type = 'CREDIT' then l.amount else -l.amount end), 0) as net
                from account a left join ledger_entry l on l.account_id = a.id
                group by a.id, a.balance
                having a.balance <> coalesce(sum(case when l.entry_type = 'CREDIT' then l.amount else -l.amount end), 0)
                order by a.id limit ?
                """, rs -> {
            violations.add(new Violation("BALANCE_LEDGER_MISMATCH", "hesap " + rs.getLong("id")
                    + ": bakiye " + rs.getBigDecimal("balance") + ", ledger toplamı " + rs.getBigDecimal("net")));
        }, MAX_VIOLATIONS_PER_CHECK);

        jdbc.query("""
                select l.id, l.reference from ledger_entry l
                where not exists (select 1 from operation_log o where o.op_key = l.reference and o.success)
                order by l.id limit ?
                """, rs -> {
            violations.add(new Violation("ENTRY_WITHOUT_OPERATION", "ledger kaydı " + rs.getLong("id")
                    + " (" + rs.getString("reference") + ") için başarılı işlem kaydı yok"));
        }, MAX_VIOLATIONS_PER_CHECK);

        jdbc.query("""
                select o.op_key, count(l.id) as entries
                from operation_log o left join ledger_entry l on l.reference = o.op_key
                where o.success
                group by o.op_key
                having count(l.id) <> 2
                order by o.op_key limit ?
                """, rs -> {
            violations.add(new Violation("OPERATION_ENTRY_COUNT", "işlem " + rs.getString("op_key")
                    + " " + rs.getLong("entries") + " ledger kaydı üretmiş (2 olmalı)"));
        }, MAX_VIOLATIONS_PER_CHECK);

        jdbc.query("""
                select reference,
                       sum(case when entry_type = 'CREDIT' then amount else -amount end) as net
                from ledger_entry
                group by reference
                having sum(case when entry_type = 'CREDIT' then amount else -amount end) <> 0
                order by reference limit ?
                """, rs -> {
            violations.add(new Violation("OPERATION_NOT_BALANCED", "işlem " + rs.getString("reference")
                    + " ledger kayıtları sıfıra toplanmıyor (" + rs.getBigDecimal("net") + ")"));
        }, MAX_VIOLATIONS_PER_CHECK);

        long accounts = jdbc.queryForObject("select count(*) from account", Long.class);
        long entries = jdbc.queryForObject("select count(*) from ledger_entry", Long.class);
        return new Report(Instant.now(), total, clearing, accounts, entries, List.copyOf(violations));
    }
}
