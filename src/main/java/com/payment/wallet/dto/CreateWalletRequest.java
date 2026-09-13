package com.payment.wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String userId) {
}
