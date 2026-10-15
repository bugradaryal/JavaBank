package com.example.bank.account.repository;

import com.example.bank.account.domain.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationLogRepository extends JpaRepository<OperationLog, String> {
}
