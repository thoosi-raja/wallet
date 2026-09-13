package com.payment.wallet.controller;

import com.payment.wallet.constants.ApiRoutes;
import com.payment.wallet.config.TransferAdmission;
import com.payment.wallet.dto.ApiResponse;
import com.payment.wallet.exception.WalletException;
import com.payment.wallet.models.TransferStatus;
import com.payment.wallet.dto.TransferRequest;
import com.payment.wallet.dto.TransferResponse;
import com.payment.wallet.dto.TransferResult;
import com.payment.wallet.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping({ApiRoutes.TRANSFERS, ApiRoutes.TRANSFER_ALIAS})
public class TransferController {
    private final TransferService transfers;
    private final TransferAdmission admission;

    public TransferController(TransferService transfers, TransferAdmission admission) {
        this.transfers = transfers;
        this.admission = admission;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TransferResponse>> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) @Pattern(regexp = "[\\x21-\\x7E]{1,128}") String key,
            @Valid @RequestBody TransferRequest request) {
        TransferResult result = admission.execute(() -> transfers.create(AuthenticatedUser.fromAuthorization(authorization),
                resolvedIdempotencyKey(key, request), request));
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(response(result.transfer()));
        }
        HttpStatus status = result.transfer().status() == TransferStatus.SUCCESS
                ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).location(URI.create(ApiRoutes.TRANSFERS + "/" + result.transfer().id()))
                .body(response(result.transfer()));
    }

    @GetMapping(ApiRoutes.BY_ID)
    public ApiResponse<TransferResponse> get(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @PathVariable String id) {
        return response(transfers.get(id, AuthenticatedUser.fromAuthorization(authorization)));
    }

    private ApiResponse<TransferResponse> response(TransferResponse transfer) {
        return transfer.status() == TransferStatus.SUCCESS
                ? ApiResponse.success(transfer)
                : ApiResponse.failure(transfer, transfer.status().name(), transfer.declineReason());
    }

    private String resolvedIdempotencyKey(String headerKey, TransferRequest request) {
        String bodyKey = request.idempotencyKey();
        if (headerKey == null && bodyKey == null) {
            throw new WalletException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "idempotency_key is required");
        }
        if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey)) {
            throw new WalletException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "Idempotency-Key header and idempotency_key body field must match");
        }
        return headerKey != null ? headerKey : bodyKey;
    }
}
