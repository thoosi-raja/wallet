package com.payment.wallet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class TransactionConfig {
    @Bean
    TransactionTemplate walletTransactionTemplate(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        // The service owns commit/rollback, including when called from another transaction.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(15);
        return template;
    }
}
