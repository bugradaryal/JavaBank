package com.example.bank.account.web.dto;

import com.example.bank.account.domain.AuditLog;

import java.time.Instant;

public record AuditLogResponse(
        Long id,
        String actor,
        String action,
        String entityType,
        String entityId,
        String details,
        Instant createdAt) {

    public static AuditLogResponse from(AuditLog a) {
        return new AuditLogResponse(a.getId(), a.getActor(), a.getAction(), a.getEntityType(),
                a.getEntityId(), a.getDetails(), a.getCreatedAt());
    }
}
