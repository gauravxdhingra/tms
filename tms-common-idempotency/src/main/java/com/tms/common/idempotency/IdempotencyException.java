package com.tms.common.idempotency;

public class IdempotencyException extends RuntimeException {
    public IdempotencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
