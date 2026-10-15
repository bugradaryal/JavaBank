package com.example.bank.common.saga;

import com.example.bank.common.domain.AccountKind;
import com.example.bank.common.domain.Currency;
import com.example.bank.common.domain.TransferType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Orkestratörün (transfer-service) account-service'e gönderdiği komut.
 *
 * <p>Komutlar at-least-once teslim edilir ve takılan saga'larda orkestratör tarafından tekrar gönderilir.
 * Bu yüzden idempotency anahtarı messageId değil, {@link #opKey()} (transferId + adım) alanıdır:
 * aynı opKey ikinci kez gelirse account-service işlemi tekrarlamaz, kayıtlı sonucu tekrar cevaplar.
 *
 * @param expectedKind     hesabın beklenen türü (müşteri transferinde CUSTOMER, para yatırmada kaynak CASH vb.)
 * @param ownerCustomerId  null değilse hesabın bu müşteriye ait olması şart (müşteri kendi hesabından harcar)
 */
public record AccountCommand(
        UUID messageId,
        Long transferId,
        SagaStep step,
        Long accountId,
        BigDecimal amount,
        Currency currency,
        AccountKind expectedKind,
        Long ownerCustomerId,
        TransferType transferType) {

    public String opKey() {
        return opKey(transferId, step);
    }

    public static String opKey(Long transferId, SagaStep step) {
        return transferId + ":" + step;
    }
}
