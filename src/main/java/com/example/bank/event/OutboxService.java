package com.example.bank.event;

import com.example.bank.domain.Account;
import com.example.bank.domain.OutboxEvent;
import com.example.bank.domain.Transfer;
import com.example.bank.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OutboxService {

    public static final String TRANSFER_AGGREGATE = "Transfer";

    private final OutboxEventRepository events;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * MANDATORY: çağıranın transaction'ı olmak zorunda. Outbox'ın bütün amacı event'in iş verisiyle
     * AYNI transaction'da yazılmasıdır; transaction dışında çağrılırsa hata verir, sessizce yanlış çalışmaz.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueTransferEvent(Transfer transfer, Account from, Account to) {
        UUID eventId = UUID.randomUUID();
        TransferEvent event = TransferEvent.of(eventId, transfer, from, to);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // RuntimeException: transfer transaction'ı da geri alınır, event'siz para hareketi olmaz.
            throw new IllegalStateException("Transfer event'i JSON'a çevrilemedi", e);
        }
        events.save(OutboxEvent.create(eventId, TRANSFER_AGGREGATE, String.valueOf(transfer.getId()),
                event.eventType(), payload));
    }
}
