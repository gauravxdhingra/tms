package com.tms.payment.domain;

public enum PaymentType {
    WIRE,
    ACH,
    SEPA_CT,
    SEPA_DD,
    SWIFT_MT103,
    INTERNAL,   // book transfer within same entity
    BOOK        // intercompany / IHB book transfer
}
