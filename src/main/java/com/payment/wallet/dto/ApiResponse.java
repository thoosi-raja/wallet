package com.payment.wallet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Shared business API envelope. Request correlation lives in X-Correlation-ID so that
 * replayed transfer bodies remain identical across requests.
 */
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
