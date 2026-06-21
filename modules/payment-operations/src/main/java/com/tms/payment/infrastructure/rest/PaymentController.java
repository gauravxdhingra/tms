package com.tms.payment.infrastructure.rest;

import com.tms.payment.application.InitiatePaymentCommand;
import com.tms.payment.application.PaymentApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentApplicationService service;

    public PaymentController(PaymentApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('PAYMENT_INITIATOR')")
    public ResponseEntity<InitiatePaymentResponse> initiatePayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Legal-Entity-Id") String legalEntityId,
            @RequestAttribute("userId") String userId,
            @Valid @RequestBody InitiatePaymentRequest request) {

        InitiatePaymentCommand cmd = new InitiatePaymentCommand(
            legalEntityId,
            request.paymentType(),
            request.amount(),
            request.currency(),
            request.valueDate(),
            request.debitAccountId(),
            request.creditAccountId(),
            request.beneficiaryIban(),
            request.beneficiaryBic(),
            request.beneficiaryName(),
            request.remittanceInfo(),
            userId,
            idempotencyKey
        );

        UUID paymentId = service.initiatePayment(cmd);

        return ResponseEntity
            .created(URI.create("/api/v1/payments/" + paymentId))
            .body(new InitiatePaymentResponse(paymentId.toString(), "PENDING_VALIDATION"));
    }

    @PostMapping("/{paymentId}/approve")
    @PreAuthorize("hasRole('PAYMENT_CHECKER')")
    public ResponseEntity<Void> approvePayment(
            @PathVariable UUID paymentId,
            @RequestAttribute("userId") String userId) {

        service.approvePayment(paymentId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{paymentId}/reject")
    @PreAuthorize("hasRole('PAYMENT_CHECKER')")
    public ResponseEntity<Void> rejectPayment(
            @PathVariable UUID paymentId,
            @RequestAttribute("userId") String userId,
            @Valid @RequestBody RejectPaymentRequest request) {

        service.rejectPayment(paymentId, request.reason(), userId);
        return ResponseEntity.noContent().build();
    }
}
