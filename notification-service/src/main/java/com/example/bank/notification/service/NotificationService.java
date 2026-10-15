package com.example.bank.notification.service;

import com.example.bank.notification.domain.Notification;
import com.example.bank.common.domain.TransferStatus;
import com.example.bank.common.domain.TransferType;
import com.example.bank.common.events.TransferEvent;
import com.example.bank.notification.repository.NotificationRepository;
import com.example.bank.common.security.AuthUser;
import com.example.bank.notification.web.dto.NotificationResponse;
import com.example.bank.common.web.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Transfer event'lerinden müşteri bildirimi üretir (e-posta gönderimi simüle edilir: kayda yazılır ve loglanır).
 */
@Service
public class NotificationService {

    public static final String CONSUMER = "notification-service";

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ObjectMapper objectMapper;
    private final ProcessedEventStore processed;
    private final NotificationRepository notifications;
    private final MeterRegistry meters;

    public NotificationService(ObjectMapper objectMapper, ProcessedEventStore processed,
                               NotificationRepository notifications, MeterRegistry meters) {
        this.meters = meters;
        this.objectMapper = objectMapper;
        this.processed = processed;
        this.notifications = notifications;
    }

    /**
     * Idempotent: aynı event kaç kez gelirse gelsin bildirimler bir kez üretilir.
     * "İşlendi" kaydı ile bildirimler tek transaction'da; hata olursa ikisi de geri alınır ve event tekrar denenir.
     *
     * @return true: bu çağrıda işlendi, false: daha önce işlenmişti (tekrar teslim, yok sayıldı)
     * @throws IllegalArgumentException payload okunamıyorsa (tekrar denemenin faydası yok, DLT'ye gider)
     */
    @Transactional
    public boolean handle(String payload) {
        TransferEvent event = parse(payload);
        if (!processed.markProcessed(CONSUMER, event.eventId())) {
            log.info("Event {} daha önce işlenmiş, yok sayıldı", event.eventId());
            meters.counter("notification.events", "result", "duplicate").increment();
            return false;
        }
        for (Notification n : buildNotifications(event)) {
            notifications.save(n);
            log.info("[EMAIL] müşteri={} konu='{}' mesaj='{}'", n.getCustomerId(), n.getSubject(), n.getMessage());
            meters.counter("notification.created", "event_type", event.eventType()).increment();
        }
        meters.counter("notification.events", "result", "processed").increment();
        return true;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(AuthUser actor, Long customerId, Pageable pageable) {
        Page<Notification> page;
        if (!actor.isStaff()) {
            page = notifications.findByCustomerIdOrderByIdDesc(actor.customerId(), pageable);
        } else if (customerId != null) {
            page = notifications.findByCustomerIdOrderByIdDesc(customerId, pageable);
        } else {
            page = notifications.findAllByOrderByIdDesc(pageable);
        }
        return PageResponse.from(page, NotificationResponse::from);
    }

    private TransferEvent parse(String payload) {
        try {
            TransferEvent event = objectMapper.readValue(payload, TransferEvent.class);
            if (event.eventId() == null || event.status() == null || event.transferId() == null) {
                throw new IllegalArgumentException("Event zorunlu alanları içermiyor");
            }
            return event;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event JSON'u okunamadı", e);
        }
    }

    private List<Notification> buildNotifications(TransferEvent e) {
        List<Notification> result = new ArrayList<>();
        Long sender = e.fromCustomerId();
        Long receiver = e.toCustomerId();
        String amount = formatAmount(e.amount()) + " " + e.currency();
        String label = label(e.type());

        if (e.status() == TransferStatus.COMPLETED) {
            if (sender != null && Objects.equals(sender, receiver)) {
                // Kendi hesapları arasında transfer: tek bildirim (unique(event_id, customer_id) kuralına da uyar).
                result.add(new Notification(e.eventId(), sender, e.transferId(), "Hesaplarınız arasında transfer",
                        amount + " tutarındaki " + label + " işlemi hesaplarınız arasında tamamlandı."));
            } else {
                if (sender != null) {
                    result.add(new Notification(e.eventId(), sender, e.transferId(), "İşleminiz tamamlandı",
                            amount + " tutarındaki " + label + " işleminiz tamamlandı."));
                }
                if (receiver != null) {
                    result.add(new Notification(e.eventId(), receiver, e.transferId(), "Hesabınıza para geldi",
                            "Hesabınıza " + amount + " yatırıldı."));
                }
            }
        } else if (sender != null) {
            String reason = e.failureReason() == null ? "bilinmeyen neden" : e.failureReason();
            result.add(new Notification(e.eventId(), sender, e.transferId(), "İşleminiz gerçekleşmedi",
                    amount + " tutarındaki " + label + " işleminiz gerçekleşmedi: " + reason));
        }
        return result;
    }

    private static String label(TransferType type) {
        if (type == null) {
            return "para transferi";
        }
        return switch (type) {
            case EFT -> "havale/EFT";
            case BILL_PAYMENT -> "fatura ödemesi";
            case CARD_PAYMENT -> "kart ödemesi";
            case DEPOSIT -> "para yatırma";
            case WITHDRAWAL -> "para çekme";
        };
    }

    private static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        BigDecimal a = amount.stripTrailingZeros();
        if (a.scale() < 2) {
            a = a.setScale(2);
        }
        return a.toPlainString();
    }
}
