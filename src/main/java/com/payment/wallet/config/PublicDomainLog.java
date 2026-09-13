package com.payment.wallet.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.stream.Collectors;

@Component
public class PublicDomainLog extends AppenderBase<ILoggingEvent> {
    private static final Set<String> EVENTS = Set.of("wallet_provisioned", "transfer_initiated", "transfer_created",
            "wallet_debited", "wallet_credited", "transfer_success", "transfer_declined_insufficient_funds", "idempotent_replay_hit");
    private static final Set<String> LOGGERS = Set.of("com.payment.wallet.service.WalletService",
            "com.payment.wallet.service.TransferService");
    private final ArrayBlockingQueue<DomainEvent> events = new ArrayBlockingQueue<>(200);
    private Logger root;

    @PostConstruct
    void attach() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        setContext(root.getLoggerContext());
        setName("PUBLIC_DOMAIN_LOG");
        start();
        root.addAppender(this);
    }

    @PreDestroy
    void detach() {
        root.detachAppender(this);
        stop();
    }

    @Override
    protected void append(ILoggingEvent loggingEvent) {
        if (!LOGGERS.contains(loggingEvent.getLoggerName()) || loggingEvent.getKeyValuePairs() == null) {
            return;
        }
        Map<String, String> fields = loggingEvent.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value), (first, last) -> last));
        String eventName = fields.get("event");
        if (eventName == null || !EVENTS.contains(eventName)) {
            return;
        }
        DomainEvent event = new DomainEvent(Instant.ofEpochMilli(loggingEvent.getTimeStamp()), eventName,
                loggingEvent.getMDCPropertyMap().get(CorrelationFilter.MDC_KEY), fields.get("transfer_id"),
                fields.get("wallet_id"), fields.containsKey("amount_paise") ? Long.valueOf(fields.get("amount_paise")) : null,
                fields.get("status"));
        while (!events.offer(event)) {
            events.poll();
        }
    }

    public List<DomainEvent> snapshot() {
        return List.copyOf(events);
    }

    public record DomainEvent(Instant timestamp, String event, String correlationId, String transferId,
                              String walletId, Long amountPaise, String status) {
    }
}
