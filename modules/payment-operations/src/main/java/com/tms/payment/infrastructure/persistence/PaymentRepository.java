package com.tms.payment.infrastructure.persistence;

import com.tms.payment.domain.Payment;
import com.tms.payment.domain.PaymentRepositoryPort;
import com.tms.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, PaymentRepositoryPort {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByLegalEntityIdAndStatus(String legalEntityId, PaymentStatus status);

    @Query("SELECT p FROM Payment p WHERE p.paymentId = :id")
    Optional<Payment> findById(@Param("id") UUID id);
}
