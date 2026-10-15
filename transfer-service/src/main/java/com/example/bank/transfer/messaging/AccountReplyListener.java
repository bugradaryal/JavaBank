package com.example.bank.transfer.messaging;

import com.example.bank.common.kafka.JsonCodec;
import com.example.bank.common.saga.AccountReply;
import com.example.bank.common.saga.SagaTopics;
import com.example.bank.transfer.service.TransferSagaService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * account-service cevaplarını state machine'e verir. Tekrar eden cevaplar state machine tarafından yok sayılır
 * (idempotent). Hata olursa exception yukarı çıkar: tekrar dener, olmazsa DLT.
 */
@Component
public class AccountReplyListener {

    public static final String LISTENER_ID = "transfer-replies";

    private final TransferSagaService saga;
    private final JsonCodec codec;

    public AccountReplyListener(TransferSagaService saga, JsonCodec codec) {
        this.saga = saga;
        this.codec = codec;
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, groupId = "transfer-service",
            topics = SagaTopics.ACCOUNT_REPLIES)
    public void onReply(String payload) {
        saga.onReply(codec.read(payload, AccountReply.class));
    }
}
