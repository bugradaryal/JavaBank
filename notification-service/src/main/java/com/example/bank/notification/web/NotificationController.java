package com.example.bank.notification.web;

import com.example.bank.notification.service.NotificationService;
import com.example.bank.common.security.AuthUser;
import com.example.bank.notification.web.dto.NotificationResponse;
import com.example.bank.common.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Müşteri sadece kendi bildirimlerini görür. Personel customerId verebilir, vermezse hepsini görür. */
    @GetMapping
    public PageResponse<NotificationResponse> list(@RequestParam(required = false) Long customerId,
                                                   @AuthenticationPrincipal AuthUser user, Pageable pageable) {
        return notificationService.list(user, customerId, pageable);
    }
}
