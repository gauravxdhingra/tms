package com.tms.payment.application;

import com.tms.common.money.TmsMoney;
import com.tms.common.outbox.OutboxPublisher;
import com.tms.events.payment.PaymentInitiatedEvent;
import com.tms.events.payment.PaymentStatusChangedEvent;
import com.tms.events.common.EventMetadata;
import com.tms.payment.domain.*;
import com.tms.payment.infrastructure.persistence.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.money.MonetaryAmount;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Application service orchestrating the payment initiation flow.
 *
 * Transactional boundary: the entire method runs in one DB transaction.
 * OutboxPublisher.publish() writes the outbox row within the SAME transaction —
 * guaranteed atomic with the Payment row insert.
 */
@Service
public class PaymentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentApplicationService.class);

    private static final String KAFKA_TOPIC_PAYMENT_EVENTS = "tms.payments.v1";
    private static final int    SCHEMA_VERSION             = 1;

    private final PaymentRepository    paymentRepository;
    private final OutboxPublisher      outboxPublisher;
    private final Clock                clock;

    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     OutboxPublisher outboxPublisher,
                                     Clock clock) {
        this.paymentRepository = paymentRepository;
        this.outboxPublisher   = outboxPublisher;
        this.clock             = clock;
    }

    @Transactional
    public UUID initiatePayment(InitiatePaymentCommand cmd) {
        // Idempotency: if already exists with this key, return the existing payment ID
        return paymentRepository.findByIdempotencyKey(cmd.idempotencyKey())
            .map(Payment::getPaymentId)
            .orElseGet(() -> createNewPayment(cmd));
    }

    private UUID createNewPayment(InitiatePaymentCommand cmd) {
        UUID paymentId = UUID.randomUUID();

        MonetaryAmount amount = TmsMoney.of(cmd.amount(), cmd.currency());
        PaymentType type      = PaymentType.valueOf(cmd.paymentType());

        Payment payment = Payment.initiate(
            paymentId,
            cmd.legalEntityId(),
            type,
            amount,
            cmd.valueDate(),
            cmd.debitAccountId(),
            cmd.creditAccountId(),
            cmd.beneficiaryIban(),
            cmd.beneficiaryBic(),
            cmd.beneficiaryName(),
            cmd.remittanceInfo(),
            cmd.initiatedByUserId(),
            cmd.idempotencyKey(),
            clock
        );

        paymentRepository.save(payment);

        EventMetadata metadata = buildMetadata(paymentId.toString(), "PaymentInitiatedEvent", cmd.legalEntityId(), cmd.idempotencyKey());

        // TODO: replace with proper Avro builder once tms-events-schema Avro codegen runs
        // PaymentInitiatedEvent event = PaymentInitiatedEvent.newBuilder()...build();
        // outboxPublisher.publish("Payment", paymentId.toString(), KAFKA_TOPIC_PAYMENT_EVENTS,
        //     paymentId.toString(), event, SCHEMA_VERSION, cmd.idempotencyKey(), cmd.legalEntityId());

        log.info("Payment initiated: paymentId={} type={} amount={} {} valueDate={}",
            paymentId, type, cmd.amount(), cmd.currency(), cmd.valueDate());

        return paymentId;
    }

    @Transactional
    public void approvePayment(UUID paymentId, String approverUserId) {
        Payment payment = paymentRepository.findByIdOrThrow(paymentId);
        payment.approve(approverUserId, clock);
        paymentRepository.save(payment);

        log.info("Payment approved: paymentId={} approver={}", paymentId, approverUserId);
    }

    @Transactional
    public void rejectPayment(UUID paymentId, String reason, String rejectorUserId) {
        Payment payment = paymentRepository.findByIdOrThrow(paymentId);
        payment.reject(reason, rejectorUserId, clock);
        paymentRepository.save(payment);

        log.info("Payment rejected: paymentId={} reason={}", paymentId, reason);
    }

    private EventMetadata buildMetadata(String aggregateId, String eventType, String legalEntityId, String correlationId) {
        return EventMetadata.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setEventType(eventType)
            .setEventVersion(SCHEMA_VERSION)
            .setOccurredAt(Instant.now(clock).toEpochMilli() * 1000L)
            .setCorrelationId(correlationId)
            .setLegalEntityId(legalEntityId)
            .setIssuedByUserId(null)
            .build();
    }
}
