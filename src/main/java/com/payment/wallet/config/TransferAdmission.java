package com.payment.wallet.config;

import com.payment.wallet.exception.WalletException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class TransferAdmission {
    private final int maximumInFlight;
    private final long waitSeconds;
    private Semaphore permits;

    public TransferAdmission(@Value("${wallet.transfer-admission.maximum-in-flight:8}") int maximumInFlight,
                             @Value("${wallet.transfer-admission.wait-seconds:120}") long waitSeconds) {
        this.maximumInFlight = maximumInFlight;
        this.waitSeconds = waitSeconds;
    }

    @PostConstruct
    void initialize() {
        permits = new Semaphore(maximumInFlight, true);
    }

    public <T> T execute(Supplier<T> work) {
        try {
            if (!permits.tryAcquire(waitSeconds, TimeUnit.SECONDS)) {
                throw unavailable();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    private WalletException unavailable() {
        return new WalletException(HttpStatus.SERVICE_UNAVAILABLE, "TRANSFER_QUEUE_FULL",
                "Transfer is queued beyond the configured wait limit; retry with the same idempotency key");
    }
}
