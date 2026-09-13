package com.payment.wallet.service;

import com.payment.wallet.config.TransferMetrics;
import com.payment.wallet.models.Transfer;
import com.payment.wallet.models.TransferStatus;
import com.payment.wallet.models.Wallet;
import com.payment.wallet.dto.TransferRequest;
import com.payment.wallet.dto.TransferResponse;
import com.payment.wallet.dto.TransferResult;
import com.payment.wallet.exception.WalletException;
import com.payment.wallet.repository.TransferRepository;
import com.payment.wallet.repository.WalletRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
@Validated
public class TransferService {
    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final String IDEMPOTENCY_CONSTRAINT = "uq_transfers_idempotency_key";
    private final WalletRepository wallets;
    private final TransferRepository transfers;
    private final TransactionTemplate transactions;
    private final TransferMetrics metrics;

    public TransferService(WalletRepository wallets, TransferRepository transfers,
                           TransactionTemplate transactions, TransferMetrics metrics) {
        this.wallets = wallets;
        this.transfers = transfers;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    public TransferResult create(@NotNull String callerUserId,
                                 @NotNull @Pattern(regexp = "[\\x21-\\x7E]{1,128}") String idempotencyKey,
                                 @NotNull @Valid TransferRequest request) {
        if (request.from().equals(request.to())) {
            throw new WalletException(HttpStatus.BAD_REQUEST, "SAME_WALLET", "Source and destination must differ");
        }
        String hash = requestHash(request);
        log.atInfo().addKeyValue("event", "transfer_initiated").addKeyValue("request_hash", hash)
                .log("Transfer initiated");

        TransferResult result;
        try {
            result = Objects.requireNonNull(transactions.execute(transaction -> execute(callerUserId, idempotencyKey, request, hash)));
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyViolation(exception)) {
                throw exception;
            }
            // Recover in a fresh transaction after the losing transaction has fully rolled back.
            result = Objects.requireNonNull(transactions.execute(transaction -> transfers.findByIdempotencyKey(idempotencyKey)
                    .map(winner -> replay(winner, callerUserId, hash)).orElseThrow(() -> exception)));
        }

        // Record completion only after commit.
        metrics.record(result);
        String event = result.replayed() ? "idempotent_replay_hit"
                : result.transfer().status() == TransferStatus.SUCCESS
                ? "transfer_success" : "transfer_declined_insufficient_funds";
        log.atInfo().addKeyValue("event", event).addKeyValue("transfer_id", result.transfer().id())
                .addKeyValue("request_hash", hash).log("Transfer completed");
        return result;
    }

    private TransferResult execute(String callerUserId, String key, TransferRequest request, String hash) {
        var existing = transfers.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(existing.get(), callerUserId, hash);
        }

        String firstLock = request.from().compareTo(request.to()) < 0 ? request.from() : request.to();
        String secondLock = request.from().compareTo(request.to()) < 0 ? request.to() : request.from();
        Wallet first = lock(firstLock);
        Wallet second = lock(secondLock);

        // Another request may have committed while we waited for its wallet locks.
        existing = transfers.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return replay(existing.get(), callerUserId, hash);
        }

        Wallet source = request.from().equals(firstLock) ? first : second;
        Wallet destination = request.to().equals(firstLock) ? first : second;
        authorizeSource(source, callerUserId);
        // PostgreSQL stores microseconds. Truncate before returning so a replay has exactly the same body.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        TransferStatus status;
        String declineReason = null;
        if (source.getBalancePaise() < request.amountPaise()) {
            status = TransferStatus.DECLINED_INSUFFICIENT_FUNDS;
            declineReason = "Insufficient funds";
        } else {
            long credited;
            try {
                credited = Math.addExact(destination.getBalancePaise(), request.amountPaise());
            } catch (ArithmeticException exception) {
                throw new WalletException(HttpStatus.UNPROCESSABLE_ENTITY, "BALANCE_LIMIT_EXCEEDED",
                        "Destination balance would exceed the 64-bit integer limit");
            }
            source.changeBalance(source.getBalancePaise() - request.amountPaise(), now);
            destination.changeBalance(credited, now);
            status = TransferStatus.SUCCESS;
            log.atInfo().addKeyValue("event", "wallet_debited").addKeyValue("wallet_id", source.getId())
                    .addKeyValue("amount_paise", request.amountPaise()).log("Wallet debited");
            log.atInfo().addKeyValue("event", "wallet_credited").addKeyValue("wallet_id", destination.getId())
                    .addKeyValue("amount_paise", request.amountPaise()).log("Wallet credited");
        }

        Transfer transfer = new Transfer(UUID.randomUUID().toString(), key, request.from(), request.to(),
                request.amountPaise(), hash, status, declineReason, now);
        // Flush here so uniqueness failures are translated to DataIntegrityViolationException.
        transfers.saveAndFlush(transfer);
        log.atInfo().addKeyValue("event", "transfer_created").addKeyValue("transfer_id", transfer.getId())
                .addKeyValue("status", transfer.getStatus()).log("Transfer row created");
        return new TransferResult(TransferResponse.from(transfer), false);
    }

    private Wallet lock(String id) {
        return wallets.findByIdForUpdate(id)
                .orElseThrow(() -> new WalletException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found"));
    }

    private TransferResult replay(Transfer transfer, String callerUserId, String hash) {
        if (!transfer.getRequestHash().equals(hash)) {
            throw new WalletException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Idempotency key reused with different body");
        }
        authorizeReplay(transfer, callerUserId);
        return new TransferResult(TransferResponse.from(transfer), true);
    }

    private boolean isIdempotencyViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && IDEMPOTENCY_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private void authorizeSource(Wallet source, String callerUserId) {
        if (!source.getUserId().equals(callerUserId)) {
            throw new WalletException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Source wallet belongs to a different user");
        }
    }

    private void authorizeReplay(Transfer transfer, String callerUserId) {
        Wallet source = wallets.findById(transfer.getSourceWalletId())
                .orElseThrow(() -> new WalletException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found"));
        if (!source.getUserId().equals(callerUserId)) {
            throw new WalletException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Transfer belongs to a different source user");
        }
    }

    static String requestHash(TransferRequest request) {
        String canonical = request.from() + "|" + request.to() + "|" + request.amountPaise();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime does not support SHA-256", exception);
        }
    }

    @Transactional(readOnly = true)
    public TransferResponse get(String id, @NotNull String callerUserId) {
        Transfer transfer = transfers.findById(id)
                .orElseThrow(() -> new WalletException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found"));
        authorizeParticipant(transfer, callerUserId);
        return TransferResponse.from(transfer);
    }

    private void authorizeParticipant(Transfer transfer, String callerUserId) {
        boolean source = wallets.findById(transfer.getSourceWalletId())
                .map(wallet -> wallet.getUserId().equals(callerUserId)).orElse(false);
        boolean destination = wallets.findById(transfer.getDestinationWalletId())
                .map(wallet -> wallet.getUserId().equals(callerUserId)).orElse(false);
        if (!source && !destination) {
            throw new WalletException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Transfer belongs to different users");
        }
    }
}
