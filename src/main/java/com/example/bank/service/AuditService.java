package com.example.bank.service;

import com.example.bank.domain.AuditLog;
import com.example.bank.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** Çağıranın transaction'ına katılır: iş geri alınırsa log da geri alınır. */
    @Transactional
    public void record(String actor, String action, String entityType, Object entityId, String details) {
        repository.save(new AuditLog(actor, action, entityType,
                entityId == null ? null : String.valueOf(entityId), details));
    }

    /** Kendi transaction'ında yazılır: iş hata verip geri alınsa bile log kalır (ör. başarısız giriş). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(String actor, String action, String entityType, Object entityId, String details) {
        repository.save(new AuditLog(actor, action, entityType,
                entityId == null ? null : String.valueOf(entityId), details));
    }
}
