package com.tms.common.outbox;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Thin wrapper around Confluent KafkaAvroSerializer for outbox use.
 * The serializer registers schemas with Schema Registry on first use.
 */
@Component
public class AvroSerializer {

    private final KafkaAvroSerializer delegate;

    public AvroSerializer(Map<String, Object> kafkaProducerProperties) {
        this.delegate = new KafkaAvroSerializer();
        this.delegate.configure(kafkaProducerProperties, false);
    }

    public byte[] serialize(String topic, SpecificRecord record) throws IOException {
        byte[] bytes = delegate.serialize(topic, record);
        if (bytes == null) {
            throw new IOException("Serializer returned null for topic=" + topic);
        }
        return bytes;
    }
}
