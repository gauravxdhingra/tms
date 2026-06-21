package com.tms.payment.domain;

import com.tms.kernel.money.MonetaryAmountEmbeddable;
import jakarta.persistence.*;
import javax.money.MonetaryAmount;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payment aggregate root.
 *
 * Status machine:
 *   DRAFT → PENDING_VALIDATION → PENDING_COMPLIANCE → PENDING_APPROVAL
 *         → APPROVED → SENT_TO_NETWORK → SETTLED
 *   Any state → REJECTED | CANCELLED | FAILED
 *
 * The aggregate enforces its own invariants. The application layer handles
 * persistence, event publication, and saga coordination.
 */
@Entity
@Table(
    name = "payments",
    indexes = {
        @Index(name = "idx_payments_legal_entity_id", columnList = "legal_entity_id"),
        @Index(name = "idx_payments_status",          columnList = "status"),
        @Index(name = "idx_payments_value_date",      columnList = "value_date"),
        @Index(name = "idx_payments_idempotency_key", columnList = "idempotency_key", unique = true)
    }
)
public class Payment {

    @Id
    @Column(name = "payment_id", updatable = false, nullable = false)
    private UUID paymentId;

    @Column(name = "legal_entity_id", nullable = false, length = 50)
    private String legalEntityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 30)
    private PaymentType paymentType;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "value",    column = @Column(name = "amount_value",    nullable = false, precision = 38, scale = 10)),
        @AttributeOverride(name = "currency", column = @Column(name = "amount_currency", nullable = false, length = 3))
    })
    private MonetaryAmountEmbeddable amount;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "debit_account_id", nullable = false, length = 100)
    private String debitAccountId;

    @Column(name = "credit_account_id", length = 100)
    private String creditAccountId;

    @Column(name = "beneficiary_iban", length = 34)
    private String beneficiaryIban;

    @Column(name = "beneficiary_bic",  length = 11)
    private String beneficiaryBic;

    @Column(name = "beneficiary_name", length = 200)
    private String beneficiaryName;

    @Column(name = "remittance_info", length = 140)
    private String remittanceInfo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "network_reference", length = 50)
    private String networkReference;

    @Column(name = "rejection_reason",  length = 500)
    private String rejectionReason;

    @Column(name = "initiated_by_user_id", nullable = false, length = 100)
    private String initiatedByUserId;

    @Column(name = "approved_by_user_id", length = 100)
    private String approvedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected Payment() {}

    public static Payment initiate(
            UUID paymentId,
            String legalEntityId,
            PaymentType paymentType,
            MonetaryAmount amount,
            LocalDate valueDate,
            String debitAccountId,
            String creditAccountId,
            String beneficiaryIban,
            String beneficiaryBic,
            String beneficiaryName,
            String remittanceInfo,
            String initiatedByUserId,
            String idempotencyKey,
            Clock clock) {

        Payment p = new Payment();
        p.paymentId          = paymentId;
        p.legalEntityId      = legalEntityId;
        p.paymentType        = paymentType;
        p.amount             = new MonetaryAmountEmbeddable(amount);
        p.valueDate          = valueDate;
        p.debitAccountId     = debitAccountId;
        p.creditAccountId    = creditAccountId;
        p.beneficiaryIban    = beneficiaryIban;
        p.beneficiaryBic     = beneficiaryBic;
        p.beneficiaryName    = beneficiaryName;
        p.remittanceInfo     = remittanceInfo;
        p.initiatedByUserId  = initiatedByUserId;
        p.idempotencyKey     = idempotencyKey;
        p.status             = PaymentStatus.PENDING_VALIDATION;
        p.createdAt          = Instant.now(clock);
        p.updatedAt          = p.createdAt;
        return p;
    }

    public void pendingCompliance(Clock clock) {
        requireStatus(PaymentStatus.PENDING_VALIDATION);
        transitionTo(PaymentStatus.PENDING_COMPLIANCE, clock);
    }

    public void pendingApproval(Clock clock) {
        requireStatus(PaymentStatus.PENDING_COMPLIANCE);
        transitionTo(PaymentStatus.PENDING_APPROVAL, clock);
    }

    public void approve(String approverUserId, Clock clock) {
        requireStatus(PaymentStatus.PENDING_APPROVAL);
        this.approvedByUserId = approverUserId;
        transitionTo(PaymentStatus.APPROVED, clock);
    }

    public void sentToNetwork(String networkReference, Clock clock) {
        requireStatus(PaymentStatus.APPROVED);
        this.networkReference = networkReference;
        transitionTo(PaymentStatus.SENT_TO_NETWORK, clock);
    }

    public void settle(Clock clock) {
        requireStatus(PaymentStatus.SENT_TO_NETWORK);
        transitionTo(PaymentStatus.SETTLED, clock);
    }

    public void reject(String reason, String rejectedByUserId, Clock clock) {
        if (status == PaymentStatus.SETTLED || status == PaymentStatus.REJECTED) {
            throw new PaymentStateException("Cannot reject a " + status + " payment");
        }
        this.rejectionReason = reason;
        this.approvedByUserId = rejectedByUserId;
        transitionTo(PaymentStatus.REJECTED, clock);
    }

    public void cancel(Clock clock) {
        if (status == PaymentStatus.SETTLED || status == PaymentStatus.SENT_TO_NETWORK) {
            throw new PaymentStateException("Cannot cancel a payment in status " + status);
        }
        transitionTo(PaymentStatus.CANCELLED, clock);
    }

    private void requireStatus(PaymentStatus required) {
        if (this.status != required) {
            throw new PaymentStateException(
                "Expected status " + required + " but was " + this.status);
        }
    }

    private void transitionTo(PaymentStatus newStatus, Clock clock) {
        this.status    = newStatus;
        this.updatedAt = Instant.now(clock);
    }

    // Accessors
    public UUID getPaymentId()           { return paymentId; }
    public String getLegalEntityId()     { return legalEntityId; }
    public PaymentType getPaymentType()  { return paymentType; }
    public MonetaryAmount getAmount()    { return amount.toMonetaryAmount(); }
    public LocalDate getValueDate()      { return valueDate; }
    public String getDebitAccountId()    { return debitAccountId; }
    public String getCreditAccountId()   { return creditAccountId; }
    public String getBeneficiaryIban()   { return beneficiaryIban; }
    public String getBeneficiaryBic()    { return beneficiaryBic; }
    public String getBeneficiaryName()   { return beneficiaryName; }
    public String getRemittanceInfo()    { return remittanceInfo; }
    public PaymentStatus getStatus()     { return status; }
    public String getIdempotencyKey()    { return idempotencyKey; }
    public String getNetworkReference()  { return networkReference; }
    public String getRejectionReason()   { return rejectionReason; }
    public String getInitiatedByUserId() { return initiatedByUserId; }
    public String getApprovedByUserId()  { return approvedByUserId; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }
}
