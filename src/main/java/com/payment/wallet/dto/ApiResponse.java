package com.payment.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Correlation stays in response headers to keep idempotent replay bodies identical. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(boolean success, T data, ApiError error) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return failure(null, code, message);
    }

    public static <T> ApiResponse<T> failure(T data, String code, String message) {
        return new ApiResponse<>(false, data, new ApiError(code, message));
    }
}
