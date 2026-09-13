package com.payment.wallet.controller;

import com.payment.wallet.constants.ApiRoutes;
import com.payment.wallet.dto.ApiResponse;
import com.payment.wallet.dto.CreateWalletRequest;
import com.payment.wallet.dto.WalletResponse;
import com.payment.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiRoutes.WALLETS)
public class WalletController {
    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @PostMapping
    public ApiResponse<WalletResponse> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @Valid @RequestBody CreateWalletRequest request) {
        return ApiResponse.success(wallets.getOrCreate(AuthenticatedUser.fromAuthorization(authorization), request));
    }

    @GetMapping(ApiRoutes.BY_ID)
    public ApiResponse<WalletResponse> get(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @PathVariable String id) {
        return ApiResponse.success(wallets.get(id, AuthenticatedUser.fromAuthorization(authorization)));
    }
}
