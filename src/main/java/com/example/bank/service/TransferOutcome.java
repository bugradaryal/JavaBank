package com.example.bank.service;

import com.example.bank.domain.Transfer;

/**
 * @param replayed true ise bu istek daha önce işlenmişti; para tekrar hareket etmedi, eski sonuç döndü.
 */
public record TransferOutcome(Transfer transfer, boolean replayed) {
}
