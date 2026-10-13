package com.example.bank;

import com.example.bank.domain.TransferStatus;
import com.example.bank.domain.TransferType;
import com.example.bank.service.TransferOutcome;
import com.example.bank.web.dto.MoneyRequest;
import com.example.bank.web.dto.TransferRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class TransferConcurrencyIT extends AbstractIntegrationTest {

    @Test
    void hundredConcurrentWithdrawalsNeverOverdrawTheAccount() throws Exception {
        Long account = newAccount("1000.0000");

        List<Callable<TransferOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> transferService.withdraw(TELLER, account,
                    new MoneyRequest(new BigDecimal("50.0000"), "çekim"), "w-" + UUID.randomUUID()));
        }

        List<TransferOutcome> results = runConcurrently(32, tasks);

        long completed = results.stream()
                .filter(o -> o.transfer().getStatus() == TransferStatus.COMPLETED).count();
        long failed = results.stream()
                .filter(o -> o.transfer().getStatus() == TransferStatus.FAILED).count();

        // 1000 TL / 50 TL = tam 20 çekim başarılı olabilir, kalan 80'i yetersiz bakiyeden reddedilir.
        assertThat(completed).isEqualTo(20);
        assertThat(failed).isEqualTo(80);
        assertThat(balance(account)).isEqualByComparingTo("0");
        assertLedgerMatchesBalance(account);
        assertGlobalInvariant();
    }

    @Test
    void opposingTransfersBetweenTwoAccountsDoNotDeadlockAndConserveMoney() throws Exception {
        Long a = newAccount("1000.0000");
        Long b = newAccount("1000.0000");

        List<Callable<TransferOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Long from = i % 2 == 0 ? a : b;
            Long to = i % 2 == 0 ? b : a;
            tasks.add(() -> transferService.transfer(TELLER,
                    new TransferRequest(from, to, new BigDecimal("10.0000"), TransferType.EFT, "çapraz"),
                    "x-" + UUID.randomUUID()));
        }

        List<TransferOutcome> results = runConcurrently(32, tasks);

        assertThat(results).allSatisfy(o -> assertThat(o.transfer().getStatus())
                .isEqualTo(TransferStatus.COMPLETED));
        // 100 transfer A->B ve 100 transfer B->A: her iki hesap da 1000'de kalır.
        assertThat(balance(a)).isEqualByComparingTo("1000");
        assertThat(balance(b)).isEqualByComparingTo("1000");
        assertLedgerMatchesBalance(a);
        assertLedgerMatchesBalance(b);
        assertGlobalInvariant();
    }

    @Test
    void randomizedTransfersAmongSeveralAccountsKeepTotalMoneyConstant() throws Exception {
        List<Long> accountIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            accountIds.add(newAccount("500.0000"));
        }
        BigDecimal totalBefore = accountIds.stream().map(this::balance).reduce(BigDecimal.ZERO, BigDecimal::add);

        java.util.Random random = new java.util.Random(42);
        List<Callable<TransferOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            int fromIdx = random.nextInt(accountIds.size());
            int toIdx = (fromIdx + 1 + random.nextInt(accountIds.size() - 1)) % accountIds.size();
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(200)).setScale(4);
            Long from = accountIds.get(fromIdx);
            Long to = accountIds.get(toIdx);
            tasks.add(() -> transferService.transfer(TELLER,
                    new TransferRequest(from, to, amount, TransferType.EFT, "rastgele"),
                    "r-" + UUID.randomUUID()));
        }

        runConcurrently(32, tasks);

        BigDecimal totalAfter = accountIds.stream().map(this::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalAfter).isEqualByComparingTo(totalBefore);
        for (Long id : accountIds) {
            assertThat(balance(id)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertLedgerMatchesBalance(id);
        }
        assertGlobalInvariant();
    }
}
