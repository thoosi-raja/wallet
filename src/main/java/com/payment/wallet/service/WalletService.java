package com.payment.wallet.service;

import com.payment.wallet.dto.CreateWalletRequest;
import com.payment.wallet.dto.WalletResponse;
import com.payment.wallet.exception.WalletException;
import com.payment.wallet.repository.WalletRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.util.Objects;
import java.util.UUID;

@Service
@Validated
public class WalletService {
    private static final Logger log = LoggerFactory.getLogger(WalletService.class);
    private final WalletRepository wallets;
    private final TransactionTemplate transactions;

    public WalletService(WalletRepository wallets, TransactionTemplate transactions) {
        this.wallets = wallets;
        this.transactions = transactions;
    }

    public WalletResponse getOrCreate(@NotNull String callerUserId, @NotNull @Valid CreateWalletRequest request) {
        if (!callerUserId.equals(request.userId())) {
            throw new WalletException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bearer token does not match user_id");
        }
        ProvisionedWallet result = Objects.requireNonNull(transactions.execute(transaction -> {
            boolean created = wallets.insertIfAbsent(UUID.randomUUID().toString(), request.userId()) == 1;
            // Separate statement at READ COMMITTED sees a concurrent upsert winner after it commits.
            WalletResponse wallet = wallets.findByUserId(request.userId()).map(WalletResponse::from)
                    .orElseThrow(() -> new IllegalStateException("Provisioned wallet is missing"));
            return new ProvisionedWallet(wallet, created);
        }));
        log.atInfo().addKeyValue("event", "wallet_provisioned")
                .addKeyValue("wallet_id", result.wallet().id()).addKeyValue("created", result.created())
                .log("Wallet provisioned");
        return result.wallet();
    }

    @Transactional(readOnly = true)
    public WalletResponse get(String id, @NotNull String callerUserId) {
        WalletResponse response = wallets.findById(id).map(WalletResponse::from)
                .orElseThrow(() -> new WalletException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found"));
        if (!response.userId().equals(callerUserId)) {
            throw new WalletException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Wallet belongs to a different user");
        }
        return response;
    }

    private record ProvisionedWallet(WalletResponse wallet, boolean created) {
    }
}
