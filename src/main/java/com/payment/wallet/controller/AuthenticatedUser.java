package com.payment.wallet.controller;

import com.payment.wallet.exception.WalletException;
import org.springframework.http.HttpStatus;

import java.util.regex.Pattern;

final class AuthenticatedUser {
    private static final String PREFIX = "Bearer ";
    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private AuthenticatedUser() {
    }

    static String fromAuthorization(String authorization) {
        if (authorization == null || !authorization.startsWith(PREFIX)) {
            throw new WalletException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Authorization header must be Bearer <user_id>");
        }
        String userId = authorization.substring(PREFIX.length());
        if (!USER_ID.matcher(userId).matches()) {
            throw new WalletException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Bearer token must be a valid user_id");
        }
        return userId;
    }
}
