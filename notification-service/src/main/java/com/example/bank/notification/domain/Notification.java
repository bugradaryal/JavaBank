package com.example.bank.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification")
@Getter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    @Column(nullable = false, length = 10)
    private String channel;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
        // JPA
    }

    public Notification(UUID eventId, Long customerId, Long transferId, String subject, String message) {
        this.eventId = eventId;
        this.customerId = customerId;
        this.transferId = transferId;
        this.channel = "EMAIL";
        this.subject = subject;
        this.message = message.length() <= 500 ? message : message.substring(0, 500);
        this.createdAt = Instant.now();
    }
}
