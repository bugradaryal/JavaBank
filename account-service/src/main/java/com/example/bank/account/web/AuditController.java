package com.example.bank.account.web;

import com.example.bank.account.repository.AuditLogRepository;
import com.example.bank.account.web.dto.AuditLogResponse;
import com.example.bank.common.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {

    private final AuditLogRepository auditLogs;

    public AuditController(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<AuditLogResponse> list(Pageable pageable) {
        return PageResponse.from(auditLogs.findAllByOrderByIdDesc(pageable), AuditLogResponse::from);
    }
}
