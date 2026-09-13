package com.payment.wallet.constants;

public final class ApiRoutes {
    public static final String V1 = "/api/v1";
    public static final String WALLET_ALIAS = "/wallets";
    public static final String TRANSFER_ALIAS = "/transfers";
    public static final String WALLETS = V1 + WALLET_ALIAS;
    public static final String TRANSFERS = V1 + TRANSFER_ALIAS;
    public static final String BY_ID = "/{id}";
    public static final String METRICS = "/metrics";
    public static final String LOGS = "/logs";

    private ApiRoutes() {
    }
}
