package com.payment.wallet.exception;

import com.payment.wallet.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(WalletException.class)
    ResponseEntity<ApiResponse<Void>> domain(WalletException exception) {
        return error(exception.getStatus(), exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            ConstraintViolationException.class, MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ApiResponse<Void>> invalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Invalid request: check required fields, header lengths, wallet IDs, and positive integer amount_paise");
    }

    @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class, TransactionTimedOutException.class})
    ResponseEntity<ApiResponse<Void>> unavailable(Exception exception) {
        log.atWarn().addKeyValue("event", "database_temporarily_unavailable")
                .addKeyValue("exception_type", exception.getClass().getSimpleName())
                .log("Database operation could not complete");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(body("SERVICE_UNAVAILABLE", "Database temporarily unavailable; retry with the same idempotency key"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        // Preserve framework HTTP statuses (404, 405, 415, etc.) without leaking exception details.
        if (exception instanceof ErrorResponse response) {
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                    .body(body("HTTP_ERROR", "Request could not be handled"));
        }
        log.atError().addKeyValue("event", "unexpected_error").setCause(exception).log("Request failed");
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<ApiResponse<Void>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(body(code, message));
    }

    private ApiResponse<Void> body(String code, String message) {
        return ApiResponse.failure(code, message);
    }
}
