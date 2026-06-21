package com.tms.platform.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "tms.idempotency")
public class IdempotencyProperties {

    /** How long a completed key → response mapping is retained in Redis. Default 24h. */
    private Duration keyTtl = Duration.ofHours(24);

    public Duration getKeyTtl() { return keyTtl; }
    public void setKeyTtl(Duration keyTtl) { this.keyTtl = keyTtl; }
}
