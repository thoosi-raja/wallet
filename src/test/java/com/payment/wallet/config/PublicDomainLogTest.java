package com.payment.wallet.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublicDomainLogTest {
    @Test
    void keepsOnlyBoundedWhitelistedEventsWithoutMessageOrSensitiveFields() {
        PublicDomainLog log = new PublicDomainLog();
        Logger logger = (Logger) LoggerFactory.getLogger("com.payment.wallet.service.TransferService");
        for (int i = 0; i < 220; i++) {
            LoggingEvent event = new LoggingEvent("test", logger, Level.INFO, "private request body", null, null);
            event.setMDCPropertyMap(Map.of("correlation_id", "request-" + i));
            event.addKeyValuePair(new KeyValuePair("event", "transfer_success"));
            event.addKeyValuePair(new KeyValuePair("transfer_id", "transfer-" + i));
            event.addKeyValuePair(new KeyValuePair("idempotency_key", "private-key"));
            log.append(event);
        }
        assertThat(log.snapshot()).hasSize(200);
        assertThat(log.snapshot().getFirst().transferId()).isEqualTo("transfer-20");
        assertThat(log.snapshot().getLast().correlationId()).isEqualTo("request-219");
        assertThat(log.snapshot().toString()).doesNotContain("private request body", "private-key");
        LoggingEvent unrelated = new LoggingEvent("test", logger, Level.ERROR, "private exception", null, null);
        unrelated.addKeyValuePair(new KeyValuePair("event", "unexpected_error"));
        log.append(unrelated);
        assertThat(log.snapshot()).hasSize(200);
    }
}
