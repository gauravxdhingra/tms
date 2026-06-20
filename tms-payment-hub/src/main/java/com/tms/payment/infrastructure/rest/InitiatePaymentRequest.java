package com.tms.payment.infrastructure.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public record InitiatePaymentRequest(
    @NotBlank String paymentType,

    @NotBlank
    @Pattern(regexp = "^-?\\d+(\\.\\d+)?$", message = "amount must be a decimal string")
    String amount,

    @NotBlank
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO 4217 code")
    String currency,

    @NotNull
    LocalDate valueDate,

    @NotBlank String debitAccountId,

    String creditAccountId,
    String beneficiaryIban,
    String beneficiaryBic,
    String beneficiaryName,

    @Pattern(regexp = ".{0,140}", message = "remittanceInfo max 140 chars")
    String remittanceInfo
) {}
