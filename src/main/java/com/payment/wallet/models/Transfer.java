package com.payment.wallet.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Immutable
@Table(name = "transfers")
public class Transfer {
    @Id
    @Column(length = 64, nullable = false, updatable = false)
    private String id;

    @Column(name = "idempotency_key", length = 128, nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "source_wallet_id", length = 64, nullable = false, updatable = false)
    private String sourceWalletId;

    @Column(name = "destination_wallet_id", length = 64, nullable = false, updatable = false)
    private String destinationWalletId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false, updatable = false)
    private TransferStatus status;

    @Column(name = "decline_reason", columnDefinition = "text", updatable = false)
    private String declineReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Transfer() {
    }

    public Transfer(String id, String idempotencyKey, String sourceWalletId, String destinationWalletId,
                    long amountPaise, String requestHash, TransferStatus status, String declineReason,
                    Instant createdAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.sourceWalletId = sourceWalletId;
        this.destinationWalletId = destinationWalletId;
        this.amountPaise = amountPaise;
        this.requestHash = requestHash;
        this.status = status;
        this.declineReason = declineReason;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getSourceWalletId() { return sourceWalletId; }
    public String getDestinationWalletId() { return destinationWalletId; }
    public long getAmountPaise() { return amountPaise; }
    public String getRequestHash() { return requestHash; }
    public TransferStatus getStatus() { return status; }
    public String getDeclineReason() { return declineReason; }
    public Instant getCreatedAt() { return createdAt; }
}
