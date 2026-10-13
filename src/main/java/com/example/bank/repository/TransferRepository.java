package com.example.bank.repository;

import com.example.bank.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    long countByIdempotencyKey(String idempotencyKey);
}
