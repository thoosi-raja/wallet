package com.payment.wallet.dto;

import com.payment.wallet.models.Wallet;

import java.time.Instant;

public record WalletResponse(String id, String userId, long balancePaise, Instant createdAt, Instant updatedAt) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.getId(), wallet.getUserId(), wallet.getBalancePaise(),
                wallet.getCreatedAt(), wallet.getUpdatedAt());
    }
}
