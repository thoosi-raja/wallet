package com.payment.wallet.config;

import com.payment.wallet.models.TransferStatus;
import com.payment.wallet.dto.TransferResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class TransferMetrics {
    private final Counter created;
    private final Counter declined;
    private final Counter replays;

    public TransferMetrics(MeterRegistry registry) {
        created = Counter.builder("wallet_transfers_successful_total")
                .description("Committed successful transfers").register(registry);
        declined = Counter.builder("wallet_transfers_declined_insufficient_funds_total")
                .description("Committed transfers declined for insufficient funds").register(registry);
        replays = Counter.builder("wallet_transfers_idempotent_replays_total")
                .description("Requests returning a previously committed transfer").register(registry);
    }

    public void record(TransferResult result) {
        if (result.replayed()) {
            replays.increment();
        } else if (result.transfer().status() == TransferStatus.SUCCESS) {
            created.increment();
        } else {
            declined.increment();
        }
    }
}
