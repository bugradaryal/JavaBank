package com.example.bank.repository;

import com.example.bank.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByTransferId(Long transferId);

    Page<Notification> findByCustomerIdOrderByIdDesc(Long customerId, Pageable pageable);

    Page<Notification> findAllByOrderByIdDesc(Pageable pageable);
}
