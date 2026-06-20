package com.tms.payment.domain;

public class PaymentStateException extends RuntimeException {
    public PaymentStateException(String message) {
        super(message);
    }
}
