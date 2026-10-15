package com.example.bank.transfer.repository;

import com.example.bank.transfer.domain.ReconciliationFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconciliationFindingRepository extends JpaRepository<ReconciliationFinding, Long> {

    long countByResolvedAtIsNull();

    List<ReconciliationFinding> findTop100ByResolvedAtIsNullOrderByIdDesc();
}
