package com.payment.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.wallet.config.CorrelationFilter;
import com.payment.wallet.models.Transfer;
import com.payment.wallet.repository.TransferRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "logging.level.root=INFO")
@AutoConfigureObservability
@Import(WalletConcurrencyIT.ThreadProbe.class)
@Timeout(60)
class WalletConcurrencyIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry metrics;
    @MockitoSpyBean TransferRepository transfers;
    private final Map<String, String> walletOwners = new ConcurrentHashMap<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void twentyConcurrentProvisioningRequestsCreateOneZeroBalanceWallet() throws Exception {
        String user = unique();
        List<HttpResponse<String>> results = burst(20, i -> post("/api/v1/wallets", Map.of("user_id", user), null));
        assertThat(results).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(200));
        assertThat(results.stream().map(this::body).map(node -> node.path("data").get("id").asText()).distinct()).hasSize(1);
        assertThat(results).allSatisfy(response -> assertThat(body(response).path("data").get("balance_paise").asLong()).isZero());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallets WHERE user_id = ?", Long.class, user)).isEqualTo(1);
    }

    @Test
    void fifteenIdenticalConcurrentRequestsDebitExactlyOnceAndReturnTheOriginalBody() throws Exception {
        String source = wallet(100);
        String destination = wallet(0);
        String key = unique();
        double createdBefore = count("wallet_transfers_successful_total");
        double replaysBefore = count("wallet_transfers_idempotent_replays_total");
        var results = burst(15, i -> transfer(source, destination, 100, key));
        assertThat(results.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
        assertThat(results.stream().filter(r -> r.statusCode() == 200)).hasSize(14);
        String original = results.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow().body();
        assertThat(results).allSatisfy(r -> assertThat(r.body()).isEqualTo(original));
        assertThat(results.stream().filter(r -> r.statusCode() == 200)).allSatisfy(r ->
                assertThat(r.headers().firstValue("Idempotent-Replay")).contains("true"));
        assertThat(balance(source)).isZero();
        assertThat(balance(destination)).isEqualTo(100);
        assertThat(transferCount(key)).isEqualTo(1);
        assertThat(count("wallet_transfers_successful_total") - createdBefore).isEqualTo(1);
        assertThat(count("wallet_transfers_idempotent_replays_total") - replaysBefore).isEqualTo(14);
    }

    @Test
    void reusedKeyWithChangedAmountOrWalletsConflictsWithoutMovingMoney() throws Exception {
        String a = wallet(1000);
        String b = wallet(0);
        String key = unique();
        assertThat(transfer(a, b, 100, key).statusCode()).isEqualTo(201);
        var changedAmount = transfer(a, b, 101, key);
        assertThat(changedAmount.statusCode()).isEqualTo(409);
        assertThat(body(changedAmount).path("error").get("message").asText()).isEqualTo("Idempotency key reused with different body");
        assertThat(transfer(b, a, 100, key).statusCode()).isEqualTo(409);
        assertThat(balance(a)).isEqualTo(900);
        assertThat(balance(b)).isEqualTo(100);
    }

    @Test
    void fiftyBidirectionalTransfersPreserveBalancesAndCompleteWithoutDeadlock() throws Exception {
        String a = wallet(10_000);
        String b = wallet(10_000);
        var results = burst(50, i -> i % 2 == 0 ? transfer(a, b, 100, unique()) : transfer(b, a, 100, unique()));
        assertThat(results).allSatisfy(r -> assertThat(r.statusCode()).describedAs(r.body()).isEqualTo(201));
        assertThat(balance(a)).isEqualTo(10_000);
        assertThat(balance(b)).isEqualTo(10_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE source_wallet_id IN (?, ?)",
                Long.class, a, b)).isEqualTo(50);
    }

    @Test
    void fiftyConcurrentDebitsCannotOverdrawTheSource() throws Exception {
        String a = wallet(1000);
        String b = wallet(0);
        double declinedBefore = count("wallet_transfers_declined_insufficient_funds_total");
        var results = burst(50, i -> transfer(a, b, 100, unique()));
        assertThat(results.stream().filter(r -> r.statusCode() == 201)).hasSize(10);
        assertThat(results.stream().filter(r -> r.statusCode() == 422)).hasSize(40);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isEqualTo(1000);
        assertThat(count("wallet_transfers_declined_insufficient_funds_total") - declinedBefore).isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE source_wallet_id = ? AND status = ?",
                Long.class, a, "DECLINED_INSUFFICIENT_FUNDS")).isEqualTo(40);
    }

    @Test
    void declinedTransferIsDurableAndReplaysEvenAfterTheSourceIsFunded() throws Exception {
        String a = wallet(0);
        String b = wallet(0);
        String key = unique();
        var declined = transfer(a, b, 100, key);
        assertThat(declined.statusCode()).isEqualTo(422);
        assertThat(body(declined).get("success").asBoolean()).isFalse();
        assertThat(body(declined).path("error").get("code").asText()).isEqualTo("DECLINED_INSUFFICIENT_FUNDS");
        assertThat(body(declined).path("data").get("status").asText()).isEqualTo("DECLINED_INSUFFICIENT_FUNDS");
        assertThat(body(declined).path("data").get("decline_reason").asText()).isEqualTo("Insufficient funds");
        jdbc.update("UPDATE wallets SET balance_paise = 1000 WHERE id = ?", a);
        var replay = transfer(a, b, 100, key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(declined.body());
        assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
        assertThat(balance(a)).isEqualTo(1000);
        assertThat(balance(b)).isZero();
        assertThat(transferCount(key)).isEqualTo(1);
    }

    @Test
    void uniqueConstraintRaceAcrossDisjointWalletPairsRollsBackTheLoser() throws Exception {
        String a = wallet(1000);
        String b = wallet(0);
        String c = wallet(1000);
        String d = wallet(0);
        String key = unique();
        CyclicBarrier beforeInsert = new CyclicBarrier(2);
        var repositoryDelegate = mockingDetails(transfers).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Transfer transfer = invocation.getArgument(0);
            if (transfer.getIdempotencyKey().equals(key)) {
                beforeInsert.await(10, TimeUnit.SECONDS);
            }
            return repositoryDelegate.answer(invocation);
        }).when(transfers).saveAndFlush(any(Transfer.class));

        double createdBefore = count("wallet_transfers_successful_total");
        var results = burst(2, i -> i == 0 ? transfer(a, b, 100, key) : transfer(c, d, 200, key));
        assertThat(results.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
        JsonNode winner = body(results.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow());
        boolean firstWon = winner.path("data").get("source_wallet_id").asText().equals(a);
        assertThat(balance(a)).isEqualTo(firstWon ? 900 : 1000);
        assertThat(balance(b)).isEqualTo(firstWon ? 100 : 0);
        assertThat(balance(c)).isEqualTo(firstWon ? 1000 : 800);
        assertThat(balance(d)).isEqualTo(firstWon ? 0 : 200);
        assertThat(transferCount(key)).isEqualTo(1);
        assertThat(count("wallet_transfers_successful_total") - createdBefore).isEqualTo(1);
    }

    @Test
    void identicalInsertConflictRecoversInAFreshTransactionWithoutADoubleDebit() throws Exception {
        String a = wallet(1000);
        String b = wallet(0);
        String key = unique();
        var original = transfer(a, b, 100, key);
        Transfer winner = transfers.findByIdempotencyKey(key).orElseThrow();
        AtomicInteger reads = new AtomicInteger();
        // Force both pre-insert lookups to miss the committed winner, exercising actual PostgreSQL
        // unique-constraint failure and recovery instead of only the faster post-lock replay path.
        doAnswer(invocation -> reads.incrementAndGet() <= 2 ? Optional.empty() : Optional.of(winner))
                .when(transfers).findByIdempotencyKey(key);
        double createdBefore = count("wallet_transfers_successful_total");
        double replayBefore = count("wallet_transfers_idempotent_replays_total");
        var replay = transfer(a, b, 100, key);
        assertThat(replay.statusCode()).describedAs(replay.body()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(original.body());
        assertThat(reads).hasValue(3);
        assertThat(balance(a)).isEqualTo(900);
        assertThat(balance(b)).isEqualTo(100);
        assertThat(transferCount(key)).isEqualTo(1);
        assertThat(count("wallet_transfers_successful_total")).isEqualTo(createdBefore);
        assertThat(count("wallet_transfers_idempotent_replays_total") - replayBefore).isEqualTo(1);
    }

    @Test
    void destinationOverflowRollsBackWithoutConsumingTheKey() throws Exception {
        String a = wallet(1);
        String b = wallet(Long.MAX_VALUE);
        String key = unique();
        var response = transfer(a, b, 1, key);
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(body(response).path("error").get("code").asText()).isEqualTo("BALANCE_LIMIT_EXCEEDED");
        assertThat(balance(a)).isEqualTo(1);
        assertThat(balance(b)).isEqualTo(Long.MAX_VALUE);
        assertThat(transferCount(key)).isZero();
    }

    @Test
    void maximumLongAmountTransfersExactlyWithoutFloatingPointConversion() throws Exception {
        String a = wallet(Long.MAX_VALUE);
        String b = wallet(0);
        var response = transfer(a, b, Long.MAX_VALUE, unique());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response).path("data").get("amount_paise").asLong()).isEqualTo(Long.MAX_VALUE);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "1.0", "1e2", "null", "\"100\"", "9223372036854775808", "true"})
    void invalidAmountsAreRejectedWithoutMutatingBalances(String amount) throws Exception {
        String a = wallet(1000);
        String b = wallet(0);
        String key = unique();
        String request = "{\"from\":\"" + a + "\",\"to\":\"" + b + "\",\"amount_paise\":" + amount + "}";
        assertThat(send("POST", "/api/v1/transfers", request, key, walletOwners.get(a)).statusCode()).isEqualTo(400);
        assertThat(balance(a)).isEqualTo(1000);
        assertThat(balance(b)).isZero();
        assertThat(transferCount(key)).isZero();
    }

    @Test
    void invalidWalletsBodiesAndHeadersAreRejected() throws Exception {
        String a = wallet(1000);
        String b = wallet(0);
        assertThat(transfer(a, a, 1, unique()).statusCode()).isEqualTo(400);
        assertThat(transfer(a, unique(), 1, unique()).statusCode()).isEqualTo(404);
        assertThat(post("/api/v1/wallets", Map.of("user_id", " "), null).statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/wallets", Map.of("user_id", "a".repeat(65)), null).statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/wallets", Map.of("user_id", unique()), null, null).statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/wallets", Map.of("user_id", unique()), null, "Bearer bad user").statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/wallets", Map.of("user_id", unique()), null, "Bearer someone-else").statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/wallets/" + a, null, null, "Bearer someone-else").statusCode()).isEqualTo(403);
        assertThat(transfer(a, b, 1, null).statusCode()).isEqualTo(400);
        assertThat(transfer(a, b, 1, "k".repeat(129)).statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/transfers", Map.of("from", a, "to", b), unique()).statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/transfers", Map.of("from", a, "to", b, "amount_paise", 1, "extra", true), unique())
                .statusCode()).isEqualTo(400);
        String duplicate = "{\"from\":\"" + a + "\",\"to\":\"" + b + "\",\"amount_paise\":1,\"amount_paise\":2}";
        assertThat(send("POST", "/api/v1/transfers", duplicate, unique(), walletOwners.get(a)).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/v1/transfers", "{\"from\":\"" + a + "\",\"to\":\"" + b
                + "\",\"amount_paise\":1,\"idempotency_key\":\"" + unique() + "\"}", null, "Bearer " + walletOwners.get(a))
                .statusCode()).isEqualTo(201);
        assertThat(post("/api/v1/transfers", Map.of("from", b, "to", a, "amount_paise", 1), unique(),
                "Bearer " + walletOwners.get(a)).statusCode()).isEqualTo(403);
        assertThat(balance(a)).isEqualTo(999);
        assertThat(balance(b)).isEqualTo(1);
    }

    @Test
    void databaseCheckRejectsDirectOverdrafts() throws Exception {
        String a = wallet(100);
        assertThatThrownBy(() -> jdbc.update("UPDATE wallets SET balance_paise = -1 WHERE id = ?", a))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_balance_non_negative");
        assertThat(balance(a)).isEqualTo(100);
    }

    @Test
    void unrelatedIntegrityFailureIsNotMistakenForAnIdempotentReplay() throws Exception {
        String a = wallet(100);
        String b = wallet(0);
        String key = unique();
        double createdBefore = count("wallet_transfers_successful_total");
        jdbc.execute("ALTER TABLE transfers ADD CONSTRAINT test_reject_thirteen CHECK (amount_paise <> 13)");
        try {
            assertError(transfer(a, b, 13, key), 500, "INTERNAL_ERROR");
            assertThat(balance(a)).isEqualTo(100);
            assertThat(balance(b)).isZero();
            assertThat(transferCount(key)).isZero();
            assertThat(count("wallet_transfers_successful_total")).isEqualTo(createdBefore);
        } finally {
            jdbc.execute("ALTER TABLE transfers DROP CONSTRAINT test_reject_thirteen");
        }
    }

    @Test
    void readEndpointsHealthMetricsCorrelationAndVirtualThreadsWork() throws Exception {
        String a = wallet(100);
        String b = wallet(0);
        var created = transfer(a, b, 1, unique());
        assertThat(created.headers().firstValue("Location")).contains(
                "/api/v1/transfers/" + body(created).path("data").get("id").asText());
        var fetched = send("GET", created.headers().firstValue("Location").orElseThrow(), null, null,
                "Bearer " + walletOwners.get(a));
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(created.body());
        var wallet = send("GET", "/api/v1/wallets/" + a, null, null);
        assertThat(body(wallet).path("data").get("balance_paise").asLong()).isEqualTo(99);
        assertThat(wallet.headers().firstValue("X-Test-Virtual-Thread")).contains("true");
        assertThat(wallet.headers().firstValue(CorrelationFilter.HEADER)).contains("integration-test");
        var health = send("GET", "/actuator/health", null, null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(body(health).get("status").asText()).isEqualTo("UP");
        var prometheus = send("GET", "/actuator/prometheus", null, null);
        assertThat(prometheus.statusCode()).isEqualTo(200);
        var metricsAlias = send("GET", "/metrics", null, null);
        assertThat(metricsAlias.statusCode()).isEqualTo(200);
        assertThat(metricsAlias.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("text/plain"));
        assertThat(metricsAlias.body()).contains("http_server_requests_seconds_bucket", "http_server_requests_seconds_count");
        assertThat(prometheus.body().lines().filter(line -> line.startsWith("wallet_transfers_")).toList())
                .anyMatch(line -> line.matches("wallet_transfers_successful_total(?:\\{[^}]*})? .*"))
                .anyMatch(line -> line.matches("wallet_transfers_declined_insufficient_funds_total(?:\\{[^}]*})? .*"))
                .anyMatch(line -> line.matches("wallet_transfers_idempotent_replays_total(?:\\{[^}]*})? .*"));
        assertThat(send("GET", "/api/v1/wallets/" + unique(), null, null, "Bearer " + walletOwners.get(a)).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/v1/transfers/" + unique(), null, null, "Bearer " + walletOwners.get(a)).statusCode()).isEqualTo(404);
    }

    @Test
    void versionedApiUsesOneEnvelopeForSuccessValidationAuthAndFrameworkErrors() throws Exception {
        String a = wallet(100);
        var success = send("GET", "/api/v1/wallets/" + a, null, null);
        assertThat(success.statusCode()).isEqualTo(200);
        assertThat(body(success).size()).isEqualTo(3);
        assertThat(body(success).get("success").asBoolean()).isTrue();
        assertThat(body(success).get("data").isObject()).isTrue();
        assertThat(body(success).get("error").isNull()).isTrue();
        assertError(post("/api/v1/wallets", Map.of("user_id", " "), null), 400, "INVALID_REQUEST");
        assertError(send("GET", "/api/v1/wallets/" + a, null, null, null), 401, "UNAUTHENTICATED");
        assertError(send("GET", "/api/v1/wallets/" + a, null, null, "Bearer stranger"), 403, "FORBIDDEN");
        assertError(send("GET", "/api/v1/wallets/" + unique(), null, null, "Bearer stranger"), 404, "WALLET_NOT_FOUND");
        assertError(send("GET", "/api/v1/unknown", null, null), 404, "HTTP_ERROR");
        assertError(send("GET", "/wallets/" + a, null, null), 404, "HTTP_ERROR");
        var wrongMethod = send("DELETE", "/api/v1/wallets/" + a, null, null);
        assertError(wrongMethod, 405, "HTTP_ERROR");
        assertThat(wrongMethod.headers().firstValue("Allow")).hasValueSatisfying(
                allow -> assertThat(allow).contains("GET"));
        var unsupportedType = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/wallets"))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("hello")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertError(unsupportedType, 415, "HTTP_ERROR");
    }

    @Test
    void correlationRemainsPerRequestWhileReplayEnvelopeRemainsIdentical() throws Exception {
        String a = wallet(100);
        String b = wallet(0);
        String key = unique();
        String request = json.writeValueAsString(Map.of("from", a, "to", b, "amount_paise", 1, "idempotency_key", key));
        var original = send("POST", "/api/v1/transfers", request, null, "Bearer " + walletOwners.get(a));
        var replay = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transfers"))
                .header("Authorization", "Bearer " + walletOwners.get(a)).header("Content-Type", "application/json")
                .header(CorrelationFilter.HEADER, "different-request")
                .POST(HttpRequest.BodyPublishers.ofString(request)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(original.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue(CorrelationFilter.HEADER)).contains("different-request");
        assertThat(replay.body()).isEqualTo(original.body());
    }

    @Test
    void unavailableDatabaseUsesErrorEnvelopeAndRetainsRetryHeader() throws Exception {
        String a = wallet(100);
        String b = wallet(0);
        String key = unique();
        doThrow(new DataAccessResourceFailureException("private database detail"))
                .when(transfers).findByIdempotencyKey(key);
        var response = transfer(a, b, 1, key);
        assertError(response, 503, "SERVICE_UNAVAILABLE");
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
        assertThat(response.body()).doesNotContain("private database detail");
        assertThat(balance(a)).isEqualTo(100);
        assertThat(balance(b)).isZero();
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
        JsonNode envelope = body(response);
        assertThat(envelope.size()).isEqualTo(3);
        assertThat(envelope.get("success").asBoolean()).isFalse();
        assertThat(envelope.get("data").isNull()).isTrue();
        assertThat(envelope.path("error").get("code").asText()).isEqualTo(code);
        assertThat(envelope.path("error").get("message").asText()).isNotBlank();
        assertThat(response.headers().firstValue(CorrelationFilter.HEADER)).isPresent();
    }

    private String wallet(long balance) throws Exception {
        String userId = unique();
        var response = post("/api/v1/wallets", Map.of("user_id", userId), null, "Bearer " + userId);
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
        String id = body(response).path("data").get("id").asText();
        walletOwners.put(id, userId);
        if (balance != 0) {
            jdbc.update("UPDATE wallets SET balance_paise = ? WHERE id = ?", balance, id);
        }
        return id;
    }

    private long balance(String id) {
        return jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE id = ?", Long.class, id);
    }

    private long transferCount(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM transfers WHERE idempotency_key = ?", Long.class, key);
    }

    private double count(String name) {
        return metrics.get(name).counter().count();
    }

    private HttpResponse<String> transfer(String from, String to, long amount, String key) throws Exception {
        return post("/api/v1/transfers", Map.of("from", from, "to", to, "amount_paise", amount), key,
                "Bearer " + walletOwners.get(from));
    }

    private HttpResponse<String> post(String path, Object body, String key) throws Exception {
        String userId = null;
        if (body instanceof Map<?, ?> map && map.get("user_id") instanceof String bodyUserId) {
            userId = bodyUserId;
        }
        return post(path, body, key, userId == null ? null : "Bearer " + userId);
    }

    private HttpResponse<String> post(String path, Object body, String key, String authorization) throws Exception {
        return send("POST", path, json.writeValueAsString(body), key, authorization);
    }

    private HttpResponse<String> send(String method, String path, String body, String key) throws Exception {
        String authorization = null;
        if (path.startsWith("/api/v1/wallets/")) {
            String owner = walletOwners.get(path.substring("/api/v1/wallets/".length()));
            authorization = owner == null ? null : "Bearer " + owner;
        }
        return send(method, path, body, key, authorization);
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String authorization) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).header(CorrelationFilter.HEADER, "integration-test");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        try {
            return json.readTree(response.body());
        } catch (Exception exception) {
            throw new AssertionError("Expected JSON: " + response.body(), exception);
        }
    }

    private static String unique() {
        return UUID.randomUUID().toString();
    }

    private <T> List<T> burst(int count, BurstOperation<T> operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Burst did not start");
                    }
                    return operation.run(index);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    @FunctionalInterface
    private interface BurstOperation<T> {
        T run(int index) throws Exception;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ThreadProbe {
        @Bean
        Filter virtualThreadProbe() {
            return (request, response, chain) -> {
                ((jakarta.servlet.http.HttpServletResponse) response)
                        .setHeader("X-Test-Virtual-Thread", Boolean.toString(Thread.currentThread().isVirtual()));
                chain.doFilter(request, response);
            };
        }
    }
}
