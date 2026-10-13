package com.example.bank.web;

import com.example.bank.domain.TransferStatus;
import com.example.bank.service.TransferOutcome;
import com.example.bank.web.dto.TransferResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class TransferResponses {

    private TransferResponses() {
    }

    /**
     * FAILED (ör. yetersiz bakiye) -> 422, yeni tamamlanan -> 201, tekrar gelen aynı istek -> 200.
     * Tekrar gelen istekte Idempotent-Replayed header'ı eklenir.
     */
    static ResponseEntity<TransferResponse> of(TransferOutcome outcome) {
        TransferResponse body = TransferResponse.from(outcome.transfer());
        HttpStatus status;
        if (outcome.transfer().getStatus() == TransferStatus.FAILED) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        } else {
            status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(body);
    }
}
