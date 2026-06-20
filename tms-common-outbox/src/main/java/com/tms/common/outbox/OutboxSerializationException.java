package com.tms.common.outbox;

public class OutboxSerializationException extends RuntimeException {
    public OutboxSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
