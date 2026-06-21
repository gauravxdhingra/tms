package com.tms.payment.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain port (output). The application layer depends on this interface; the
 * infrastructure layer provides the JPA implementation. This keeps the domain
 * and application layers free of any persistence framework dependency.
 */
public interface PaymentRepositoryPort {

    Payment save(Payment payment);

    Optional<Payment> findById(UUID paymentId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    default Payment findByIdOrThrow(UUID paymentId) {
        return findById(paymentId).orElseThrow(() ->
            new PaymentNotFoundException("Payment not found: " + paymentId));
    }
}
