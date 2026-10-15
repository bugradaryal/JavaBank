package com.example.bank.transfer.web;

import com.example.bank.common.domain.TransferStatus;
import com.example.bank.common.security.AuthUser;
import com.example.bank.transfer.domain.Transfer;
import com.example.bank.transfer.service.TransferOutcome;
import com.example.bank.transfer.service.TransferService;
import com.example.bank.transfer.web.dto.CashRequest;
import com.example.bank.transfer.web.dto.TransferRequest;
import com.example.bank.transfer.web.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transfer asenkron yürür. Yanıt kodları:
 * 202 saga başlatıldı (PENDING), 201 wait ile beklenip COMPLETED olduysa, 422 FAILED, 200 aynı key'in tekrarı
 * (Idempotent-Replayed: true). Sonuç GET /api/transfers/{id} ile okunur.
 */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(name = "wait", defaultValue = "0") long waitMs,
            @AuthenticationPrincipal AuthUser user) {
        return respond(transferService.transfer(user, request, idempotencyKey), waitMs);
    }

    @PostMapping("/deposit")
    @PreAuthorize("hasAnyRole('TELLER','ADMIN')")
    public ResponseEntity<TransferResponse> deposit(
            @Valid @RequestBody CashRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(name = "wait", defaultValue = "0") long waitMs,
            @AuthenticationPrincipal AuthUser user) {
        return respond(transferService.deposit(user, request, idempotencyKey), waitMs);
    }

    @PostMapping("/withdraw")
    @PreAuthorize("hasAnyRole('TELLER','ADMIN')")
    public ResponseEntity<TransferResponse> withdraw(
            @Valid @RequestBody CashRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(name = "wait", defaultValue = "0") long waitMs,
            @AuthenticationPrincipal AuthUser user) {
        return respond(transferService.withdraw(user, request, idempotencyKey), waitMs);
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
        return TransferResponse.from(transferService.get(id, user));
    }

    private ResponseEntity<TransferResponse> respond(TransferOutcome outcome, long waitMs) {
        Transfer t = waitMs > 0 ? transferService.awaitResult(outcome.transfer(), waitMs) : outcome.transfer();
        HttpStatus status;
        if (t.status() == TransferStatus.FAILED) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        } else if (outcome.replayed()) {
            status = HttpStatus.OK;
        } else {
            status = t.status() == TransferStatus.COMPLETED ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(TransferResponse.from(t));
    }
}
