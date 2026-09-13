package com.payment.wallet.repository;

import com.payment.wallet.models.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, String> {
    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);
}
