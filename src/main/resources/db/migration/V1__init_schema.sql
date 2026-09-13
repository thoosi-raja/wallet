CREATE TABLE wallets (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    balance_paise BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wallets_user_id UNIQUE (user_id),
    CONSTRAINT chk_balance_non_negative CHECK (balance_paise >= 0)
);

CREATE TABLE transfers (
    id VARCHAR(64) PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    source_wallet_id VARCHAR(64) NOT NULL REFERENCES wallets(id),
    destination_wallet_id VARCHAR(64) NOT NULL REFERENCES wallets(id),
    amount_paise BIGINT NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    decline_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_transfer_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT chk_transfer_distinct_wallets CHECK (source_wallet_id <> destination_wallet_id),
    CONSTRAINT chk_transfer_status CHECK (status IN ('SUCCESS', 'DECLINED_INSUFFICIENT_FUNDS')),
    CONSTRAINT chk_transfer_decline_reason CHECK (
        (status = 'SUCCESS' AND decline_reason IS NULL) OR
        (status = 'DECLINED_INSUFFICIENT_FUNDS' AND decline_reason IS NOT NULL)
    ),
    CONSTRAINT chk_transfer_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- UNIQUE constraints already create B-tree indexes on user_id and idempotency_key.
CREATE INDEX idx_transfers_source_wallet_id ON transfers (source_wallet_id);
CREATE INDEX idx_transfers_destination_wallet_id ON transfers (destination_wallet_id);
