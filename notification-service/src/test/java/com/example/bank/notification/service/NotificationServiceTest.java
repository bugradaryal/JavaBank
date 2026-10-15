package com.example.bank.notification.service;

import com.example.bank.common.domain.Currency;
import com.example.bank.notification.domain.Notification;
import com.example.bank.common.domain.TransferStatus;
import com.example.bank.common.domain.TransferType;
import com.example.bank.common.events.TransferEvent;
import com.example.bank.notification.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Mock
    ProcessedEventStore processed;
    @Mock
    NotificationRepository notifications;

    NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(mapper, processed, notifications, new SimpleMeterRegistry());
    }

    @Test
    void completedTransferNotifiesSenderAndReceiver() throws Exception {
        UUID id = UUID.randomUUID();
        when(processed.markProcessed(NotificationService.CONSUMER, id)).thenReturn(true);

        boolean handled = service.handle(json(event(id, TransferStatus.COMPLETED, TransferType.EFT, 10L, 20L, null)));

        assertThat(handled).isTrue();
        List<Notification> saved = savedNotifications(2);
        assertThat(saved).extracting(Notification::getCustomerId).containsExactlyInAnyOrder(10L, 20L);
        assertThat(saved).allSatisfy(n -> assertThat(n.getEventId()).isEqualTo(id));
        assertThat(saved).anySatisfy(n -> assertThat(n.getMessage()).contains("150.50 TRY"));
    }

    @Test
    void duplicateEventIsIgnoredAndCreatesNothing() throws Exception {
        UUID id = UUID.randomUUID();
        when(processed.markProcessed(NotificationService.CONSUMER, id)).thenReturn(false);

        boolean handled = service.handle(json(event(id, TransferStatus.COMPLETED, TransferType.EFT, 10L, 20L, null)));

        assertThat(handled).isFalse();
        verifyNoInteractions(notifications);
    }

    @Test
    void transferBetweenOwnAccountsProducesSingleNotification() throws Exception {
        UUID id = UUID.randomUUID();
        when(processed.markProcessed(eq(NotificationService.CONSUMER), eq(id))).thenReturn(true);

        service.handle(json(event(id, TransferStatus.COMPLETED, TransferType.EFT, 10L, 10L, null)));

        assertThat(savedNotifications(1)).extracting(Notification::getCustomerId).containsExactly(10L);
    }

    @Test
    void failedTransferNotifiesOnlyTheSenderWithTheReason() throws Exception {
        UUID id = UUID.randomUUID();
        when(processed.markProcessed(eq(NotificationService.CONSUMER), eq(id))).thenReturn(true);

        service.handle(json(event(id, TransferStatus.FAILED, TransferType.EFT, 10L, 20L, "Yetersiz bakiye")));

        List<Notification> saved = savedNotifications(1);
        assertThat(saved.get(0).getCustomerId()).isEqualTo(10L);
        assertThat(saved.get(0).getMessage()).contains("Yetersiz bakiye");
    }

    @Test
    void depositFromCashAccountNotifiesOnlyTheCustomer() throws Exception {
        UUID id = UUID.randomUUID();
        when(processed.markProcessed(eq(NotificationService.CONSUMER), eq(id))).thenReturn(true);

        service.handle(json(event(id, TransferStatus.COMPLETED, TransferType.DEPOSIT, null, 20L, null)));

        assertThat(savedNotifications(1)).extracting(Notification::getCustomerId).containsExactly(20L);
    }

    @Test
    void malformedPayloadThrowsIllegalArgumentSoItGoesStraightToTheDeadLetterTopic() {
        assertThatThrownBy(() -> service.handle("bu json degil"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(notifications, never()).save(any(Notification.class));
        verifyNoInteractions(processed);
    }

    private List<Notification> savedNotifications(int expected) {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, org.mockito.Mockito.times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    private String json(TransferEvent event) throws Exception {
        return mapper.writeValueAsString(event);
    }

    private static TransferEvent event(UUID id, TransferStatus status, TransferType type,
                                       Long fromCustomer, Long toCustomer, String reason) {
        String eventType = status == TransferStatus.COMPLETED ? TransferEvent.COMPLETED : TransferEvent.FAILED;
        return new TransferEvent(id, eventType, 7L, type, status, 1L, 2L, fromCustomer, toCustomer,
                new BigDecimal("150.5000"), Currency.TRY, reason, Instant.now());
    }
}
