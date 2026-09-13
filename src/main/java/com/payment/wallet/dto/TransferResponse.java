package com.payment.wallet.dto;

import com.payment.wallet.models.Transfer;
import com.payment.wallet.models.TransferStatus;

import java.time.Instant;

public record TransferResponse(String id, String idempotencyKey, String sourceWalletId,
                               String destinationWalletId, long amountPaise, TransferStatus status,
                               String declineReason, Instant createdAt) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(transfer.getId(), transfer.getIdempotencyKey(), transfer.getSourceWalletId(),
                transfer.getDestinationWalletId(), transfer.getAmountPaise(), transfer.getStatus(),
                transfer.getDeclineReason(), transfer.getCreatedAt());
    }
}
