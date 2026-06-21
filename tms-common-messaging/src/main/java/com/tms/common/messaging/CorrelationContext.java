package com.tms.common.messaging;

/**
 * Thread-local holder for the current request's correlation ID.
 * Populated by the inbound HTTP request filter (or Kafka consumer header extractor)
 * so it's available to the Kafka producer interceptor without passing it explicitly.
 */
public final class CorrelationContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private CorrelationContext() {}

    public static void set(String correlationId) {
        HOLDER.set(correlationId);
    }

    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
