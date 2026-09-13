package com.payment.wallet.exception;

import org.springframework.http.HttpStatus;

public class WalletException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public WalletException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
