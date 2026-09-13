package com.payment.wallet.controller;

import com.payment.wallet.config.PublicDomainLog;
import com.payment.wallet.constants.ApiRoutes;
import com.payment.wallet.dto.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LogsController {
    private final PublicDomainLog logs;

    public LogsController(PublicDomainLog logs) {
        this.logs = logs;
    }

    @GetMapping(ApiRoutes.LOGS)
    public ResponseEntity<ApiResponse<List<PublicDomainLog.DomainEvent>>> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(logs.snapshot()));
    }
}
