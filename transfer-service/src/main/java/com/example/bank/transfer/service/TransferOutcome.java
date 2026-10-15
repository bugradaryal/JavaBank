package com.example.bank.transfer.service;

import com.example.bank.transfer.domain.Transfer;

public record TransferOutcome(Transfer transfer, boolean replayed) {
}
