package com.payment.wallet.controller;

import com.payment.wallet.constants.ApiRoutes;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {
    private final PrometheusMeterRegistry registry;

    public MetricsController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = ApiRoutes.METRICS, produces = "text/plain; version=0.0.4; charset=utf-8")
    public String metrics() {
        return registry.scrape();
    }
}
