package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.AccountKind;
import com.example.bank.domain.Currency;
import com.example.bank.domain.EntryType;
import com.example.bank.domain.LedgerEntry;
import com.example.bank.domain.Transfer;
import com.example.bank.domain.TransferStatus;
import com.example.bank.domain.TransferType;
import com.example.bank.event.OutboxService;
import com.example.bank.error.BankException;
import com.example.bank.error.ErrorCode;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.LedgerEntryRepository;
import com.example.bank.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferExecutorTest {

    @Mock
    AccountRepository accounts;
    @Mock
    TransferRepository transfers;
    @Mock
    LedgerEntryRepository ledger;
    @Mock
    AuditService audit;
    @Mock
    OutboxService outbox;

    @InjectMocks
    TransferExecutor executor;

    @Test
    void successfulTransferMovesMoneyAndWritesDoubleEntryLedger() {
        Account from = account(1L, "100.0000", Currency.TRY);
        Account to = account(2L, "0.0000", Currency.TRY);
        stubNewTransfer(from, to);

        TransferOutcome outcome = executor.execute(cmd("k1", 1L, 2L, "30.0000"));

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(from.getBalance()).isEqualByComparingTo("70");
        assertThat(to.getBalance()).isEqualByComparingTo("30");

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledger, times(2)).save(captor.capture());
        List<LedgerEntry> entries = captor.getAllValues();
        assertThat(entries).extracting(LedgerEntry::getEntryType)
                .containsExactlyInAnyOrder(EntryType.DEBIT, EntryType.CREDIT);
        assertThat(entries).allSatisfy(e -> assertThat(e.getAmount()).isEqualByComparingTo("30"));
        // Outbox event'i aynı transaction'da, para hareketiyle birlikte yazılır.
        verify(outbox).enqueueTransferEvent(outcome.transfer(), from, to);
    }

    @Test
    void insufficientFundsIsRecordedAsFailedTransferWithoutMovingMoney() {
        Account from = account(1L, "10.0000", Currency.TRY);
        Account to = account(2L, "0.0000", Currency.TRY);
        stubNewTransfer(from, to);

        TransferOutcome outcome = executor.execute(cmd("k1", 1L, 2L, "30.0000"));

        assertThat(outcome.transfer().getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(outcome.transfer().getFailureReason()).isNotBlank();
        assertThat(from.getBalance()).isEqualByComparingTo("10");
        assertThat(to.getBalance()).isEqualByComparingTo("0");
        verify(ledger, never()).save(any(LedgerEntry.class));
        // Başarısız transfer de event üretir (müşteriye "gerçekleşmedi" bildirimi için).
        verify(outbox).enqueueTransferEvent(outcome.transfer(), from, to);
    }

    @Test
    void sameAccountIsRejected() {
        assertThatThrownBy(() -> executor.execute(cmd("k1", 1L, 1L, "5.0000")))
                .isInstanceOfSatisfying(BankException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.SAME_ACCOUNT));
        verifyNoInteractions(accounts, transfers, ledger, outbox);
    }

    @Test
    void currencyMismatchIsRejected() {
        Account from = account(1L, "100.0000", Currency.TRY);
        Account to = account(2L, "0.0000", Currency.USD);
        when(transfers.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(accounts.findByIdForUpdate(1L)).thenReturn(Optional.of(from));
        when(accounts.findByIdForUpdate(2L)).thenReturn(Optional.of(to));

        assertThatThrownBy(() -> executor.execute(cmd("k1", 1L, 2L, "5.0000")))
                .isInstanceOfSatisfying(BankException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
        verify(transfers, never()).save(any(Transfer.class));
        verifyNoInteractions(outbox);
    }

    @Test
    void closedAccountIsRejected() {
        Account from = account(1L, "100.0000", Currency.TRY);
        Account to = account(2L, "0.0000", Currency.TRY);
        to.close();
        when(transfers.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(accounts.findByIdForUpdate(1L)).thenReturn(Optional.of(from));
        when(accounts.findByIdForUpdate(2L)).thenReturn(Optional.of(to));

        assertThatThrownBy(() -> executor.execute(cmd("k1", 1L, 2L, "5.0000")))
                .isInstanceOfSatisfying(BankException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ACCOUNT_CLOSED));
    }

    @Test
    void accountsAreAlwaysLockedInAscendingIdOrder() {
        Account from = account(5L, "100.0000", Currency.TRY);
        Account to = account(2L, "0.0000", Currency.TRY);
        stubNewTransfer(from, to);

        executor.execute(cmd("k1", 5L, 2L, "10.0000"));

        InOrder order = inOrder(accounts);
        order.verify(accounts).findByIdForUpdate(2L);
        order.verify(accounts).findByIdForUpdate(5L);
    }

    @Test
    void sameKeyAndSamePayloadReturnsPreviousResultWithoutTouchingAccounts() {
        Transfer previous = Transfer.pending("k1", 1L, 2L, new BigDecimal("30.0000"), Currency.TRY,
                TransferType.EFT, null, "u");
        previous.complete();
        when(transfers.findByIdempotencyKey("k1")).thenReturn(Optional.of(previous));

        TransferOutcome outcome = executor.execute(cmd("k1", 1L, 2L, "30.0000"));

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.transfer()).isSameAs(previous);
        // Tekrar gelen istek yeni event üretmez.
        verifyNoInteractions(accounts, ledger, outbox);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        Transfer previous = Transfer.pending("k1", 1L, 2L, new BigDecimal("30.0000"), Currency.TRY,
                TransferType.EFT, null, "u");
        when(transfers.findByIdempotencyKey("k1")).thenReturn(Optional.of(previous));

        assertThatThrownBy(() -> executor.execute(cmd("k1", 1L, 2L, "99.0000")))
                .isInstanceOfSatisfying(BankException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        verifyNoInteractions(accounts, ledger);
    }

    @Test
    void cashAccountMayGoNegativeSoDepositWorks() {
        Account cash = account(1L, "0.0000", Currency.TRY);
        ReflectionTestUtils.setField(cash, "kind", AccountKind.SYSTEM);
        Account customer = account(2L, "0.0000", Currency.TRY);
        stubNewTransfer(cash, customer);

        TransferOutcome outcome = executor.execute(new TransferCommand("dep-1", 1L, 2L,
                new BigDecimal("50.0000"), TransferType.DEPOSIT, null, "teller"));

        assertThat(outcome.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(cash.getBalance()).isEqualByComparingTo("-50");
        assertThat(customer.getBalance()).isEqualByComparingTo("50");
        // Para yaratılmadı: toplam her zaman 0
        assertThat(cash.getBalance().add(customer.getBalance())).isEqualByComparingTo("0");
    }

    private void stubNewTransfer(Account from, Account to) {
        when(transfers.findByIdempotencyKey(any(String.class))).thenReturn(Optional.empty());
        when(accounts.findByIdForUpdate(from.getId())).thenReturn(Optional.of(from));
        when(accounts.findByIdForUpdate(to.getId())).thenReturn(Optional.of(to));
        when(transfers.save(any(Transfer.class))).thenAnswer(inv -> {
            Transfer t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 100L);
            return t;
        });
    }

    private static TransferCommand cmd(String key, Long from, Long to, String amount) {
        return new TransferCommand(key, from, to, new BigDecimal(amount), TransferType.EFT, null, "tester");
    }

    private static Account account(long id, String balance, Currency currency) {
        Account a = Account.open("TR" + id, 1000L + id, currency);
        ReflectionTestUtils.setField(a, "id", id);
        ReflectionTestUtils.setField(a, "balance", new BigDecimal(balance));
        return a;
    }
}
