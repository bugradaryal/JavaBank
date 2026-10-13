package com.example.bank;

import com.example.bank.domain.TransferStatus;
import com.example.bank.domain.TransferType;
import com.example.bank.error.BankException;
import com.example.bank.error.ErrorCode;
import com.example.bank.service.TransferOutcome;
import com.example.bank.web.dto.TransferRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferIdempotencyIT extends AbstractIntegrationTest {

    private TransferRequest eft(Long from, Long to, String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount), TransferType.EFT, "test");
    }

    @Test
    void sameKeyTwiceMovesMoneyOnlyOnce() {
        Long a = newAccount("1000.0000");
        Long b = newAccount("0.0000");
        String key = "idem-" + UUID.randomUUID();

        TransferOutcome first = transferService.transfer(TELLER, eft(a, b, "100.0000"), key);
        TransferOutcome second = transferService.transfer(TELLER, eft(a, b, "100.0000"), key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.transfer().getId()).isEqualTo(first.transfer().getId());
        assertThat(balance(a)).isEqualByComparingTo("900");
        assertThat(balance(b)).isEqualByComparingTo("100");
        assertThat(transferRepository.countByIdempotencyKey(key)).isEqualTo(1);
        assertGlobalInvariant();
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        Long a = newAccount("1000.0000");
        Long b = newAccount("0.0000");
        String key = "idem-" + UUID.randomUUID();
        transferService.transfer(TELLER, eft(a, b, "100.0000"), key);

        assertThatThrownBy(() -> transferService.transfer(TELLER, eft(a, b, "250.0000"), key))
                .isInstanceOfSatisfying(BankException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(balance(a)).isEqualByComparingTo("900");
    }

    @Test
    void twentyConcurrentRequestsWithTheSameKeyMoveMoneyExactlyOnce() throws Exception {
        Long a = newAccount("1000.0000");
        Long b = newAccount("0.0000");
        String key = "idem-" + UUID.randomUUID();

        List<Callable<TransferOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> transferService.transfer(TELLER, eft(a, b, "100.0000"), key));
        }

        List<TransferOutcome> results = runConcurrently(20, tasks);

        Set<Long> transferIds = results.stream().map(o -> o.transfer().getId()).collect(Collectors.toSet());
        long fresh = results.stream().filter(o -> !o.replayed()).count();
        assertThat(transferIds).hasSize(1);
        assertThat(fresh).isEqualTo(1);
        assertThat(balance(a)).isEqualByComparingTo("900");
        assertThat(balance(b)).isEqualByComparingTo("100");
        assertThat(transferRepository.countByIdempotencyKey(key)).isEqualTo(1);
        assertLedgerMatchesBalance(a);
        assertLedgerMatchesBalance(b);
        assertGlobalInvariant();
    }

    @Test
    void failedTransferIsPersistedAndReplayedWithoutRetryingTheDebit() {
        Long a = newAccount("50.0000");
        Long b = newAccount("0.0000");
        String key = "idem-" + UUID.randomUUID();

        TransferOutcome first = transferService.transfer(TELLER, eft(a, b, "100.0000"), key);
        // Araya para yatırılsa bile aynı key ile gelen istek eski (FAILED) sonucu döner.
        transferService.deposit(TELLER, a,
                new com.example.bank.web.dto.MoneyRequest(new BigDecimal("500.0000"), "sonradan"),
                "dep-" + UUID.randomUUID());
        TransferOutcome second = transferService.transfer(TELLER, eft(a, b, "100.0000"), key);

        assertThat(first.transfer().getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(second.replayed()).isTrue();
        assertThat(second.transfer().getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(balance(a)).isEqualByComparingTo("550");
        assertThat(balance(b)).isEqualByComparingTo("0");
        assertGlobalInvariant();
    }
}
