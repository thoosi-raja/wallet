package com.payment.wallet.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "wallets")
public class Wallet {
    @Id
    @Column(length = 64, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 64, nullable = false, unique = true, updatable = false)
    private String userId;

    @Column(name = "balance_paise", nullable = false)
    private long balancePaise;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wallet() {
    }

    /** Caller must hold this wallet's pessimistic write lock for the whole transaction. */
    public void changeBalance(long newBalancePaise, Instant now) {
        if (newBalancePaise < 0) {
            throw new IllegalArgumentException("Wallet balance cannot be negative");
        }
        balancePaise = newBalancePaise;
        updatedAt = now;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public long getBalancePaise() { return balancePaise; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
