package com.tms.payment.domain;

public enum PaymentStatus {
    PENDING_VALIDATION,
    PENDING_COMPLIANCE,
    PENDING_APPROVAL,
    APPROVED,
    SENT_TO_NETWORK,
    SETTLED,
    REJECTED,
    CANCELLED,
    FAILED
}
