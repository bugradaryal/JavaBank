package com.example.bank.web;

import com.example.bank.repository.OutboxEventRepository;
import com.example.bank.web.dto.OutboxStatsResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/outbox")
public class OutboxController {

    private final OutboxEventRepository outbox;

    public OutboxController(OutboxEventRepository outbox) {
        this.outbox = outbox;
    }

    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public OutboxStatsResponse stats() {
        return new OutboxStatsResponse(outbox.countByPublishedAtIsNull(), outbox.countByPublishedAtIsNotNull(),
                outbox.oldestPendingCreatedAt());
    }
}
