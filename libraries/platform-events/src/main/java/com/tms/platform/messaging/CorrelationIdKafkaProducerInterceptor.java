package com.tms.platform.messaging;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Propagates the X-Correlation-ID from the current thread context into Kafka
 * message headers so consumers can log and trace the full request chain.
 */
public class CorrelationIdKafkaProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        String correlationId = CorrelationContext.get();
        if (correlationId != null) {
            record.headers().add(new RecordHeader(
                CORRELATION_ID_HEADER,
                correlationId.getBytes(StandardCharsets.UTF_8)
            ));
        }
        return record;
    }

    @Override public void onAcknowledgement(RecordMetadata metadata, Exception exception) {}
    @Override public void close() {}
    @Override public void configure(Map<String, ?> configs) {}
}
