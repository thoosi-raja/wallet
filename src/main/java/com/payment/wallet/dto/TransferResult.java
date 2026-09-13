package com.payment.wallet.dto;

public record TransferResult(TransferResponse transfer, boolean replayed) {
}
