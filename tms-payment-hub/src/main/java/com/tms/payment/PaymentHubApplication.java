package com.tms.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentHubApplication.class, args);
    }
}
