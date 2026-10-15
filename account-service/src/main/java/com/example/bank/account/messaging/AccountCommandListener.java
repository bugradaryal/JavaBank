package com.example.bank.account.messaging;

import com.example.bank.account.service.AccountCommandHandler;
import com.example.bank.common.kafka.JsonCodec;
import com.example.bank.common.saga.AccountCommand;
import com.example.bank.common.saga.SagaTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Her komut tipi kendi topic'inde ve kendi listener container'ında. Testlerde container id'siyle tek tek
 * durdurulup "servis bu adımda çöktü" durumu deterministik olarak kurulabilir.
 * Hata olursa exception yukarı çıkar: DefaultErrorHandler tekrar dener, olmazsa DLT'ye yollar.
 */
@Component
public class AccountCommandListener {

    public static final String DEBIT_LISTENER = "account-debit";
    public static final String CREDIT_LISTENER = "account-credit";
    public static final String REFUND_LISTENER = "account-refund";

    private final AccountCommandHandler handler;
    private final JsonCodec codec;

    public AccountCommandListener(AccountCommandHandler handler, JsonCodec codec) {
        this.handler = handler;
        this.codec = codec;
    }

    @KafkaListener(id = DEBIT_LISTENER, idIsGroup = false, groupId = "account-service",
            topics = SagaTopics.DEBIT_COMMANDS)
    public void onDebit(String payload) {
        handler.handle(codec.read(payload, AccountCommand.class));
    }

    @KafkaListener(id = CREDIT_LISTENER, idIsGroup = false, groupId = "account-service",
            topics = SagaTopics.CREDIT_COMMANDS)
    public void onCredit(String payload) {
        handler.handle(codec.read(payload, AccountCommand.class));
    }

    @KafkaListener(id = REFUND_LISTENER, idIsGroup = false, groupId = "account-service",
            topics = SagaTopics.REFUND_COMMANDS)
    public void onRefund(String payload) {
        handler.handle(codec.read(payload, AccountCommand.class));
    }
}
