package com.example.bank.domain;

public enum TransferType {
    /** Hesaplar arası havale/EFT */
    EFT,
    /** Fatura ödemesi (simülasyon) */
    BILL_PAYMENT,
    /** Kart ödemesi (simülasyon) */
    CARD_PAYMENT,
    /** Gişeden para yatırma (kasa hesabından) */
    DEPOSIT,
    /** Gişeden para çekme (kasa hesabına) */
    WITHDRAWAL
}
