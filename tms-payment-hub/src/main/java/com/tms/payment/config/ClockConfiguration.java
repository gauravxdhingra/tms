package com.tms.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides a UTC system clock as a Spring bean.
 * Tests inject a fixed clock to eliminate time-dependent flakiness.
 * Never call LocalDate.now() or Instant.now() without the Clock parameter.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
