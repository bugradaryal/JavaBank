package com.example.bank.common.saga;

import java.util.List;

/**
 * Kafka topic'leri. Her komut tipi ayrı topic'te: adımlar bağımsız ölçeklenir, izlenir ve (testlerde)
 * tek tek durdurulabilir.
 */
public final class SagaTopics {

    /** transfer-service -> notification-service: transfer sonuçlandı. */
    public static final String TRANSFER_EVENTS = "transfer-events";
    /** transfer-service -> account-service komutları. */
    public static final String DEBIT_COMMANDS = "account.debit-commands";
    public static final String CREDIT_COMMANDS = "account.credit-commands";
    public static final String REFUND_COMMANDS = "account.refund-commands";
    /** account-service -> transfer-service cevapları. */
    public static final String ACCOUNT_REPLIES = "account.replies";

    private SagaTopics() {
    }

    public static String commandTopic(SagaStep step) {
        return switch (step) {
            case DEBIT_SOURCE -> DEBIT_COMMANDS;
            case CREDIT_DESTINATION -> CREDIT_COMMANDS;
            case REFUND_SOURCE -> REFUND_COMMANDS;
        };
    }

    public static List<String> all() {
        return List.of(TRANSFER_EVENTS, DEBIT_COMMANDS, CREDIT_COMMANDS, REFUND_COMMANDS, ACCOUNT_REPLIES);
    }
}
