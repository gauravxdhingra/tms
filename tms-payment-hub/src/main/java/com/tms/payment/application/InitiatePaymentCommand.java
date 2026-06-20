package com.tms.payment.application;

import java.time.LocalDate;

/**
 * Command object for creating a new payment instruction.
 * Validated at the REST layer via Bean Validation before reaching the application service.
 */
public record InitiatePaymentCommand(
    String legalEntityId,
    String paymentType,
    String amount,
    String currency,
    LocalDate valueDate,
    String debitAccountId,
    String creditAccountId,
    String beneficiaryIban,
    String beneficiaryBic,
    String beneficiaryName,
    String remittanceInfo,
    String initiatedByUserId,
    String idempotencyKey
) {}
