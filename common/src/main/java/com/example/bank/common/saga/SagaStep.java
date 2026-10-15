package com.example.bank.common.saga;

public enum SagaStep {
    /** Gönderen hesaptan düş, clearing hesabına aktar. */
    DEBIT_SOURCE,
    /** Clearing hesabından düş, alıcı hesaba aktar. */
    CREDIT_DESTINATION,
    /** Telafi: clearing hesabındaki parayı gönderen hesaba geri ver. */
    REFUND_SOURCE
}
