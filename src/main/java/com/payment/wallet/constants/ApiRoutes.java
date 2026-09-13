package com.payment.wallet.constants;

public final class ApiRoutes {
    public static final String V1 = "/api/v1";
    public static final String WALLETS = V1 + "/wallets";
    public static final String TRANSFERS = V1 + "/transfers";
    public static final String BY_ID = "/{id}";
    public static final String METRICS = "/metrics";

    private ApiRoutes() {
    }
}
