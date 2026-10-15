package com.example.bank.common.saga;

import java.time.Instant;
import java.util.UUID;

/**
 * account-service'in komuta cevabı.
 *
 * @param success    iş kuralı açısından başarılı mı (başarısızlık "yetersiz bakiye" gibi beklenen bir sonuçtur,
 *                   teknik hata değil; teknik hatalarda cevap gönderilmez, komut tekrar denenir)
 * @param reasonCode başarısızsa ErrorCode adı (ör. INSUFFICIENT_FUNDS)
 * @param customerId hesabın sahibi (biliniyorsa); bildirim için kullanılır
 */
public record AccountReply(
        UUID messageId,
        Long transferId,
        SagaStep step,
        boolean success,
        String reasonCode,
        String reason,
        Long customerId,
        Instant occurredAt) {
}
