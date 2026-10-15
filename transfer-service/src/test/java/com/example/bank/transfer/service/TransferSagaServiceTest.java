package com.example.bank.transfer.service;

import com.example.bank.common.domain.AccountKind;
import com.example.bank.common.domain.Currency;
import com.example.bank.common.domain.TransferStatus;
import com.example.bank.common.domain.TransferType;
import com.example.bank.common.events.TransferEvent;
import com.example.bank.common.outbox.OutboxService;
import com.example.bank.common.saga.AccountCommand;
import com.example.bank.common.saga.AccountReply;
import com.example.bank.common.saga.SagaStep;
import com.example.bank.common.saga.SagaTopics;
import com.example.bank.transfer.domain.SagaState;
import com.example.bank.transfer.domain.Transfer;
import com.example.bank.transfer.repository.TransferRepository;
import com.example.bank.transfer.service.TransferSagaService.StartCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Saga durum makinesinin birim testi: DB ve Kafka yok, sadece geçişler ve giden komut/olaylar. */
@ExtendWith(MockitoExtension.class)
class TransferSagaServiceTest {

    @Mock
    TransferRepository transfers;
    @Mock
    OutboxService outbox;

    TransferSagaService saga;

    @BeforeEach
    void setUp() {
        saga = new TransferSagaService(transfers, outbox, new SimpleMeterRegistry(), Tracer.NOOP);
    }

    private Transfer newTransfer(TransferType type) {
        return Transfer.start("key-1", 10L, 20L, new BigDecimal("100.0000"), Currency.TRY, type, null,
                "ali@example.com", 5L);
    }

    private AccountReply reply(SagaStep step, boolean ok, String code, Long customerId) {
        return new AccountReply(UUID.randomUUID(), 1L, step, ok, code, ok ? null : "red", customerId, Instant.now());
    }

    private void given(Transfer t) {
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.of(t));
    }

    @Test
    void start_borc_komutunu_outboxa_yazar() {
        when(transfers.saveAndFlush(any(Transfer.class))).thenAnswer(i -> i.getArgument(0));

        Transfer t = saga.start(new StartCommand("k", 10L, 20L, new BigDecimal("5"), Currency.TRY, TransferType.EFT,
                null, "ali", 5L));

        assertThat(t.getSagaState()).isEqualTo(SagaState.STARTED);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(any(UUID.class), eq(SagaTopics.DEBIT_COMMANDS), eq("Transfer"), anyString(),
                anyString(), payload.capture());
        AccountCommand cmd = (AccountCommand) payload.getValue();
        assertThat(cmd.step()).isEqualTo(SagaStep.DEBIT_SOURCE);
        assertThat(cmd.accountId()).isEqualTo(10L);
        assertThat(cmd.expectedKind()).isEqualTo(AccountKind.CUSTOMER);
        assertThat(cmd.ownerCustomerId()).isEqualTo(5L);
    }

    @Test
    void borc_basarili_ise_alacak_komutu_gider() {
        Transfer t = newTransfer(TransferType.EFT);
        given(t);

        saga.onReply(reply(SagaStep.DEBIT_SOURCE, true, null, 5L));

        assertThat(t.getSagaState()).isEqualTo(SagaState.DEBITED);
        assertThat(t.getFromCustomerId()).isEqualTo(5L);
        verify(outbox).enqueue(any(UUID.class), eq(SagaTopics.CREDIT_COMMANDS), eq("Transfer"), anyString(),
                anyString(), any());
    }

    @Test
    void borc_reddedilirse_transfer_fail_olur_ve_telafi_gerekmez() {
        Transfer t = newTransfer(TransferType.EFT);
        given(t);

        saga.onReply(reply(SagaStep.DEBIT_SOURCE, false, "INSUFFICIENT_FUNDS", 5L));

        assertThat(t.getSagaState()).isEqualTo(SagaState.FAILED);
        assertThat(t.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(t.getFailureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(outbox, never()).enqueue(any(UUID.class), eq(SagaTopics.REFUND_COMMANDS), anyString(), anyString(),
                anyString(), any());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(any(UUID.class), eq(SagaTopics.TRANSFER_EVENTS), anyString(), anyString(),
                eq(TransferEvent.FAILED), payload.capture());
        assertThat(((TransferEvent) payload.getValue()).status()).isEqualTo(TransferStatus.FAILED);
    }

    @Test
    void alacak_basarili_ise_transfer_tamamlanir() {
        Transfer t = newTransfer(TransferType.EFT);
        t.debited(5L);
        given(t);

        saga.onReply(reply(SagaStep.CREDIT_DESTINATION, true, null, 6L));

        assertThat(t.getSagaState()).isEqualTo(SagaState.COMPLETED);
        assertThat(t.getToCustomerId()).isEqualTo(6L);
        verify(outbox).enqueue(any(UUID.class), eq(SagaTopics.TRANSFER_EVENTS), anyString(), anyString(),
                eq(TransferEvent.COMPLETED), any());
    }

    @Test
    void alacak_reddedilirse_telafi_baslar_ve_iade_komutu_gider() {
        Transfer t = newTransfer(TransferType.EFT);
        t.debited(5L);
        given(t);

        saga.onReply(reply(SagaStep.CREDIT_DESTINATION, false, "ACCOUNT_CLOSED", 6L));

        assertThat(t.getSagaState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(t.status()).isEqualTo(TransferStatus.PENDING);
        verify(outbox).enqueue(any(UUID.class), eq(SagaTopics.REFUND_COMMANDS), eq("Transfer"), anyString(),
                anyString(), any());
        // Sonuç olayı telafi bitmeden yayınlanmaz.
        verify(outbox, never()).enqueue(any(UUID.class), eq(SagaTopics.TRANSFER_EVENTS), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void iade_basarili_ise_transfer_FAILED_olarak_sonuclanir() {
        Transfer t = newTransfer(TransferType.EFT);
        t.debited(5L);
        t.beginCompensation("ACCOUNT_CLOSED", "hedef kapalı", 6L);
        given(t);

        saga.onReply(reply(SagaStep.REFUND_SOURCE, true, null, 5L));

        assertThat(t.getSagaState()).isEqualTo(SagaState.COMPENSATED);
        assertThat(t.status()).isEqualTo(TransferStatus.FAILED);
        verify(outbox).enqueue(any(UUID.class), eq(SagaTopics.TRANSFER_EVENTS), anyString(), anyString(),
                eq(TransferEvent.FAILED), any());
    }

    @Test
    void yinelenen_veya_gec_gelen_cevap_durumu_bozmaz() {
        Transfer t = newTransfer(TransferType.EFT);
        t.debited(5L);
        t.credited(6L);
        given(t);

        // Tamamlanmış transfere eski bir borç cevabı ve sahte bir iade cevabı gelirse hiçbir şey olmaz.
        saga.onReply(reply(SagaStep.DEBIT_SOURCE, true, null, 5L));
        saga.onReply(reply(SagaStep.REFUND_SOURCE, true, null, 5L));

        assertThat(t.getSagaState()).isEqualTo(SagaState.COMPLETED);
        verifyNoInteractions(outbox);
    }

    @Test
    void bilinmeyen_transfer_cevabi_yok_sayilir() {
        when(transfers.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        saga.onReply(reply(SagaStep.DEBIT_SOURCE, true, null, 5L));

        verifyNoInteractions(outbox);
    }

    @Test
    void takilan_saga_icin_mevcut_adimin_komutu_tekrar_gonderilir() {
        Transfer started = newTransfer(TransferType.EFT);
        Transfer debited = newTransfer(TransferType.EFT);
        debited.debited(5L);
        Transfer compensating = newTransfer(TransferType.EFT);
        compensating.debited(5L);
        compensating.beginCompensation("X", "y", 6L);
        when(transfers.lockStuck(any(Instant.class), eq(50))).thenReturn(List.of(started, debited, compensating));

        int n = saga.recoverStuck(Instant.now(), 50);

        assertThat(n).isEqualTo(3);
        verify(outbox).enqueue(any(UUID.class), eq(SagaTopics.DEBIT_COMMANDS), anyString(), anyString(),
                anyString(), any());
        verify(outbox).enqueue(any(UUID.class), eq(SagaTopics.CREDIT_COMMANDS), anyString(), anyString(),
                anyString(), any());
        verify(outbox).enqueue(any(UUID.class), eq(SagaTopics.REFUND_COMMANDS), anyString(), anyString(),
                anyString(), any());
        assertThat(started.getAttempts()).isEqualTo(1);
    }

    @Test
    void para_yatirmada_borc_kasa_hesabindan_cekilir() {
        when(transfers.saveAndFlush(any(Transfer.class))).thenAnswer(i -> i.getArgument(0));

        saga.start(new StartCommand("k", 1L, 20L, new BigDecimal("5"), Currency.TRY, TransferType.DEPOSIT, null,
                "teller", null));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(any(UUID.class), eq(SagaTopics.DEBIT_COMMANDS), anyString(), anyString(),
                anyString(), payload.capture());
        assertThat(((AccountCommand) payload.getValue()).expectedKind()).isEqualTo(AccountKind.CASH);
    }
}
