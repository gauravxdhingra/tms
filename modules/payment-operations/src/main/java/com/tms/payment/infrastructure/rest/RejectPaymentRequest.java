package com.tms.payment.infrastructure.rest;

import jakarta.validation.constraints.NotBlank;

public record RejectPaymentRequest(@NotBlank String reason) {}
