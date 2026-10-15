package com.example.bank.notification.web.dto;

import com.example.bank.notification.domain.Notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        Long id,
        UUID eventId,
        Long customerId,
        Long transferId,
        String channel,
        String subject,
        String message,
        Instant createdAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getEventId(), n.getCustomerId(), n.getTransferId(),
                n.getChannel(), n.getSubject(), n.getMessage(), n.getCreatedAt());
    }
}
