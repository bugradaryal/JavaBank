package com.example.bank.web;

import com.example.bank.security.AuthUser;
import com.example.bank.service.TransferService;
import com.example.bank.web.dto.TransferRequest;
import com.example.bank.web.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * Idempotency-Key zorunlu. Aynı key ile aynı istek tekrar gönderilirse para bir kez hareket eder,
     * ikinci istek 200 ve eski sonucu alır (Idempotent-Replayed: true).
     */
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthUser user) {
        return TransferResponses.of(transferService.transfer(user, request, idempotencyKey));
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return TransferResponse.from(transferService.get(id, user));
    }
}
