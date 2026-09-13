package com.payment.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String from,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String to,
        @NotNull @Positive Long amountPaise,
        @Pattern(regexp = "[\\x21-\\x7E]{1,128}") String idempotencyKey) {
}
