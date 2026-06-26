/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * End-to-end REST integration test exercising the full stateless-JWT online surface
 * &mdash; the eight controllers that replace the seventeen CICS online screens and the
 * pseudo-conversational {@code CARDDEMO-COMMAREA} navigation. Every flow is driven over
 * <em>real HTTP</em> through {@link #restTemplate} bound to the embedded server's
 * {@link #port}, so each request traverses the production filter chain end to end
 * ({@code CorrelationIdFilter} &rarr; Spring Security / {@code JwtAuthenticationFilter}
 * &rarr; controller &rarr; {@code GlobalExceptionHandler}) against a real Spring context,
 * a real PostgreSQL 16 container, and a real LocalStack container (no mocks, no H2, no
 * live AWS). The suite contributes the nineteen online-E2E checks of the wider
 * integration footprint and validates the migration's behavioral-parity contracts:
 * RFC&nbsp;7807 {@code application/problem+json} error envelopes, byte-exact COBOL
 * messages where the service passes them through, {@code BigDecimal} monetary precision,
 * JPA {@code @Version} optimistic locking, FIFO report-submission bridging, role-based
 * access control, and correlation/health observability.
 *
 * <h2>State management</h2>
 * Unlike repository ITs this class is <strong>not</strong> {@code @Transactional}: real
 * HTTP requests commit in the server thread, so a test-thread transaction cannot roll
 * them back. Every mutation is therefore undone explicitly &mdash; reference/master rows
 * are snapshotted and restored, the (initially empty) {@code transactions} table is
 * cleared, and test-created users are deleted &mdash; in {@link #cleanUpOnlineE2E()} so
 * the shared Flyway seed and sibling suites are unaffected.
 *
 * <h2>Actuator port</h2>
 * The base application serves Actuator on a separate management port. This class sets an
 * empty {@code management.server.port} so the operational endpoints are colocated on the
 * random application port, letting {@link #url(String)} reach {@code /actuator/health}
 * and {@code /actuator/prometheus} through the same {@link #restTemplate} while the
 * {@code permitAll} actuator security chain still applies.
 */
@TestPropertySource(properties = "management.server.port=")
public class OnlineTransactionE2EIT extends AbstractIntegrationIT {

    /** JSON parser for asserting on response and message payloads. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Restore actions registered by mutating tests; executed (LIFO) in {@link #cleanUpOnlineE2E()}. */
    private final List<Runnable> restorers = new ArrayList<>();

    /** Strict SQL identifier guard for the dynamic snapshot/restore helpers. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** RFC 7807 problem media type rendered by {@code GlobalExceptionHandler}. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** The documented legacy initial password; V3 seeds the BCrypt hash of its upper-cased form. */
    private static final String SEED_RAW_PASSWORD = "PASSWORD";

    /** Seeded account that owns a card cross-reference and a positive balance (billing/view). */
    private static final long ACCOUNT_VIEW_ID = 1L;

    /** Seeded account used for the mutating account-update / optimistic-lock flow. */
    private static final long ACCOUNT_UPDATE_ID = 2L;

    /** Card number cross-referenced to {@link #ACCOUNT_UPDATE_ID} (embossed "Enrico Rosenbaum"). */
    private static final String SEEDED_CARD_NUMBER = "0923877193247330";

    /** Eleven-digit textual form of {@link #ACCOUNT_UPDATE_ID} required by the card-update edits. */
    private static final String SEEDED_CARD_ACCOUNT_ID = "00000000002";

    /** Concurrency width and retry budget for provoking a deterministic optimistic-lock conflict. */
    private static final int CONCURRENT_WRITERS = 8;
    private static final int MAX_CONFLICT_ATTEMPTS = 10;
    private static final int FUTURE_TIMEOUT_SECONDS = 30;

    /** FIFO message-group id the report bridge stamps on every submission. */
    private static final String REPORT_MESSAGE_GROUP_ID = "report-jobs";

    /** Report date format mirrored from {@code ReportService} ({@code uuuu-MM-dd}). */
    private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    /** SQS drain tuning (mirrors the sibling SQS IT) for deterministic FIFO receive. */
    private static final int SQS_MAX_BATCH = 10;
    private static final int SQS_WAIT_SECONDS = 1;
    private static final int SQS_EMPTY_POLLS_TO_STOP = 3;
    private static final int SQS_MAX_DRAIN_POLLS = 15;

    // ----------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------

    /**
     * Ensures the canonical AWS resources exist (idempotent) and that the per-test data
     * surfaces this suite mutates start from the shared-seed baseline: the
     * {@code transactions} table is empty (the seed populates {@code daily_transaction},
     * not {@code transactions}) and no leftover test users remain.
     */
    @BeforeEach
    void setUpOnlineE2E() {
        provisionCanonicalAwsResources();
        jdbcTemplate.update("DELETE FROM users WHERE user_id LIKE 'TESTUSR%'");
        deleteFrom("transactions");
    }

    /**
     * Undoes every mutation so the shared Flyway seed and sibling suites are unaffected:
     * registered row snapshots are restored last-in-first-out, the {@code transactions}
     * table is cleared, and any test-created users are removed. Best-effort by design so a
     * single failed restore cannot mask the assertion that triggered it.
     */
    @AfterEach
    void cleanUpOnlineE2E() {
        for (int i = restorers.size() - 1; i >= 0; i--) {
            try {
                restorers.get(i).run();
            } catch (RuntimeException ex) {
                // best-effort restore; never mask the originating test failure
            }
        }
        restorers.clear();
        try {
            jdbcTemplate.update("DELETE FROM users WHERE user_id LIKE 'TESTUSR%'");
        } catch (RuntimeException ex) {
            // best-effort cleanup
        }
        try {
            deleteFrom("transactions");
        } catch (RuntimeException ex) {
            // best-effort cleanup
        }
    }

    // ----------------------------------------------------------------------------------
    // HTTP helpers
    // ----------------------------------------------------------------------------------

    /** Performs a GET with the supplied headers and returns the raw {@link String} body. */
    private ResponseEntity<String> httpGet(String path, HttpHeaders headers) {
        return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** Performs a write (POST/PUT/DELETE) with a JSON body and the supplied headers. */
    private ResponseEntity<String> httpWrite(HttpMethod method, String path, HttpHeaders headers, Object body) {
        return restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }

    /** Builds bearer headers for the seeded administrator with a JSON content type. */
    private HttpHeaders adminJsonHeaders() {
        HttpHeaders headers = adminAuthHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Builds bearer headers for the seeded standard user with a JSON content type. */
    private HttpHeaders userJsonHeaders() {
        HttpHeaders headers = userAuthHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Parses a response body as JSON, tolerating an empty body (e.g. a 202 with no content). */
    private JsonNode json(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException | java.io.IOException ex) {
            throw new AssertionError("Response body was not valid JSON: " + body, ex);
        }
    }

    /**
     * Asserts the response is an RFC 7807 problem document carrying the expected status,
     * the {@code application/problem+json} media type, and a matching {@code status}
     * member, then returns the parsed body for further field assertions.
     */
    private JsonNode assertProblem(ResponseEntity<String> response, int expectedStatus) {
        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).as("problem responses must declare a content type").isNotNull();
        assertThat(contentType.toString()).contains(PROBLEM_JSON);
        JsonNode body = json(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(body.path("title").asText()).isNotBlank();
        return body;
    }

    // ----------------------------------------------------------------------------------
    // Database snapshot / restore helpers (this class is not @Transactional)
    // ----------------------------------------------------------------------------------

    /** Validates a SQL identifier against a strict pattern before interpolation. */
    private static String ident(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        }
        return identifier;
    }

    /**
     * Captures the current state of a single row and registers a restore action that will
     * rewrite every non-key column back to the captured value during teardown.
     *
     * @param table the table name
     * @param pkCol the single primary-key column
     * @param pk    the primary-key value identifying the row
     */
    private void snapshotForRestore(String table, String pkCol, Object pk) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM " + ident(table) + " WHERE " + ident(pkCol) + " = ?", pk);
        restorers.add(() -> restoreRow(table, pkCol, pk, row));
    }

    /** Rewrites every captured non-key column of a row back to its snapshotted value. */
    private void restoreRow(String table, String pkCol, Object pk, Map<String, Object> row) {
        List<String> assignments = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> column : row.entrySet()) {
            if (column.getKey().equalsIgnoreCase(pkCol)) {
                continue;
            }
            assignments.add(ident(column.getKey()) + " = ?");
            values.add(column.getValue());
        }
        values.add(pk);
        String sql = "UPDATE " + ident(table) + " SET " + String.join(", ", assignments)
                + " WHERE " + ident(pkCol) + " = ?";
        jdbcTemplate.update(sql, values.toArray());
    }

    // ----------------------------------------------------------------------------------
    // Request-body builders (exact JSON keys mirror the controller DTO record components)
    // ----------------------------------------------------------------------------------

    /**
     * Builds a fully valid {@code AccountDto.UpdateRequest} payload for
     * {@link #ACCOUNT_UPDATE_ID}. Every value satisfies the COBOL-order field edits in
     * {@code AccountUpdateService}: a real, non-future date of birth; FICO in [300,850]
     * (the seed's 274/268 are invalid); a valid state code and matching state/ZIP prefix
     * combo ({@code CA} + {@code 90} = {@code CA90}); a valid NANPA area code; and SSN
     * groups outside the reserved ranges. Only {@code creditLimit} varies per attempt so
     * each submission is a genuine change rather than a {@code No change detected} reject.
     *
     * @param creditLimit the credit-limit value to submit (scale 2)
     * @return the request payload as an ordered JSON-serialisable map
     */
    private Map<String, Object> validAccountUpdate(BigDecimal creditLimit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", Long.toString(ACCOUNT_UPDATE_ID));
        body.put("accountStatus", "Y");
        body.put("openYear", "2010");
        body.put("openMonth", "01");
        body.put("openDay", "01");
        body.put("expiryYear", "2030");
        body.put("expiryMonth", "06");
        body.put("expiryDay", "15");
        body.put("reissueYear", "2015");
        body.put("reissueMonth", "06");
        body.put("reissueDay", "15");
        body.put("dobYear", "1990");
        body.put("dobMonth", "01");
        body.put("dobDay", "15");
        body.put("creditLimit", creditLimit);
        body.put("cashCreditLimit", new BigDecimal("2000.00"));
        body.put("currentBalance", new BigDecimal("100.00"));
        body.put("currentCycleCredit", new BigDecimal("0.00"));
        body.put("currentCycleDebit", new BigDecimal("0.00"));
        body.put("ssnPart1", "123");
        body.put("ssnPart2", "45");
        body.put("ssnPart3", "6789");
        body.put("ficoScore", "700");
        body.put("firstName", "JOHN");
        body.put("lastName", "DOE");
        body.put("addressLine1", "123 MAIN ST");
        body.put("state", "CA");
        body.put("zipCode", "90001");
        body.put("city", "SPRINGFIELD");
        body.put("country", "USA");
        body.put("phone1Area", "201");
        body.put("phone1Prefix", "555");
        body.put("phone1Line", "0123");
        body.put("eftAccountId", "1234567890");
        body.put("primaryCardHolder", "Y");
        return body;
    }

    // ----------------------------------------------------------------------------------
    // SQS helpers (mirror the sibling SQS IT receive/drain pattern)
    // ----------------------------------------------------------------------------------

    /**
     * Receives and deletes every visible message on the canonical report FIFO queue,
     * returning them in receive order. Stops after {@link #SQS_EMPTY_POLLS_TO_STOP}
     * consecutive empty receives (or {@link #SQS_MAX_DRAIN_POLLS} polls), tolerating the
     * brief delay before a just-published message becomes visible.
     *
     * @return the drained messages, in receive order; never {@code null}
     */
    private List<Message> drainReportQueue() {
        String queueUrl = reportQueueUrl();
        List<Message> collected = new ArrayList<>();
        try (SqsClient sqs = newSqsClient()) {
            int emptyPolls = 0;
            for (int poll = 0; poll < SQS_MAX_DRAIN_POLLS && emptyPolls < SQS_EMPTY_POLLS_TO_STOP; poll++) {
                ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(SQS_MAX_BATCH)
                        .waitTimeSeconds(SQS_WAIT_SECONDS)
                        .messageSystemAttributeNames(
                                MessageSystemAttributeName.MESSAGE_GROUP_ID,
                                MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID)
                        .build());
                List<Message> batch = response.messages();
                if (batch.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (Message message : batch) {
                    collected.add(message);
                    sqs.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                }
            }
        }
        return collected;
    }

    // ----------------------------------------------------------------------------------
    // Monetary-precision helpers
    // ----------------------------------------------------------------------------------

    /**
     * Asserts that a monetary field is serialized on the wire with exactly two fraction
     * digits, mirroring the COBOL {@code PIC S9(n)V99} contract. This inspects the raw
     * JSON body rather than a parsed node so client-side numeric normalisation cannot mask
     * a scale regression.
     *
     * @param rawBody the raw JSON response body
     * @param field   the JSON member name of the monetary field
     */
    private void assertWireScale2(String rawBody, String field) {
        assertThat(rawBody)
                .as("money field '%s' must serialize with exactly two fraction digits", field)
                .containsPattern("\"" + field + "\":-?\\d+\\.\\d{2}(?=[,}\\]])");
    }

    /** Reads a numeric JSON member as an exact {@link BigDecimal}. */
    private BigDecimal money(JsonNode body, String field) {
        JsonNode node = body.path(field);
        assertThat(node.isNumber()).as("field '%s' must be numeric", field).isTrue();
        return node.decimalValue();
    }

    // ==================================================================================
    // Auth flow (CC00 <- COSGN00C)
    // ==================================================================================

    @Test
    @DisplayName("CC00: POST /api/auth/signin with valid credentials returns 200 and a JWT")
    void signinWithValidCredentialsReturns200AndJwt() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", SEEDED_ADMIN_USER_ID);
        request.put("password", SEED_RAW_PASSWORD);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = httpWrite(HttpMethod.POST, "/api/auth/signin", headers, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(response.getBody());
        assertThat(body.path("token").asText()).isNotBlank();
        assertThat(body.path("userId").asText()).isEqualTo(SEEDED_ADMIN_USER_ID);
        assertThat(body.path("userType").asText()).isEqualTo(SEEDED_ADMIN_USER_TYPE);
    }

    @Test
    @DisplayName("CC00: POST /api/auth/signin with a wrong password returns 401 problem+json")
    void signinWithWrongPasswordReturns401() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", SEEDED_ADMIN_USER_ID);
        request.put("password", "WRONGPW1");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = httpWrite(HttpMethod.POST, "/api/auth/signin", headers, request);

        JsonNode body = assertProblem(response, 401);
        assertThat(body.path("title").asText()).isEqualTo("Authentication Failed");
        assertThat(body.path("detail").asText()).isEqualTo("Invalid signon credentials. Try again ...");
    }

    @Test
    @DisplayName("CC00: POST /api/auth/signin with a blank body returns 400 with field errors")
    void signinWithBlankBodyReturns400() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", "");
        request.put("password", "");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = httpWrite(HttpMethod.POST, "/api/auth/signin", headers, request);

        JsonNode body = assertProblem(response, 400);
        assertThat(body.path("title").asText()).isEqualTo("Validation Failed");
        JsonNode errors = body.path("errors");
        assertThat(errors.isObject()).as("400 body must carry a field-keyed 'errors' map").isTrue();
        assertThat(errors.has("userId")).isTrue();
        assertThat(errors.has("password")).isTrue();
    }

    // ==================================================================================
    // Account flow (CAVW / CAUP <- COACTVWC / COACTUPC)
    // ==================================================================================

    @Test
    @DisplayName("CAVW: GET /api/accounts/{id} returns 200 with the account view and scale-2 money")
    void getAccountByIdReturns200WithViewResponse() {
        ResponseEntity<String> response = httpGet("/api/accounts/" + ACCOUNT_VIEW_ID, userAuthHeaders());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String raw = response.getBody();
        JsonNode body = json(raw);
        assertThat(Long.parseLong(body.path("accountId").asText())).isEqualTo(ACCOUNT_VIEW_ID);
        assertThat(body.path("accountStatus").asText()).isNotBlank();
        assertThat(body.path("customerId").asText()).isNotBlank();
        // Monetary members must be numeric and rendered with scale 2 on the wire.
        assertWireScale2(raw, "currentBalance");
        assertWireScale2(raw, "creditLimit");
        assertWireScale2(raw, "cashCreditLimit");
        assertThat(money(body, "currentBalance")).isEqualByComparingTo("194.00");
        assertThat(money(body, "creditLimit")).isEqualByComparingTo("2020.00");
        assertThat(money(body, "cashCreditLimit")).isEqualByComparingTo("1020.00");
    }

    @Test
    @DisplayName("CAVW: GET /api/accounts/{id} for an unknown account returns 404 problem+json")
    void getUnknownAccountReturns404() {
        ResponseEntity<String> response = httpGet("/api/accounts/99999999", userAuthHeaders());

        JsonNode body = assertProblem(response, 404);
        assertThat(body.path("title").asText()).isEqualTo("Resource Not Found");
        assertThat(body.path("detail").asText()).contains("could not be found");
    }

    @Test
    @DisplayName("CAUP: PUT /api/accounts/{id} updates then surfaces a 409 optimistic-lock conflict")
    void updateAccountThenOptimisticConflictReturns409() throws Exception {
        // This flow mutates both the account and its customer; snapshot both for restore.
        snapshotForRestore("accounts", "acct_id", ACCOUNT_UPDATE_ID);
        snapshotForRestore("customers", "cust_id", 2L);

        // A single, well-formed update commits cleanly (200) and returns the refreshed view.
        ResponseEntity<String> ok = httpWrite(HttpMethod.PUT, "/api/accounts/" + ACCOUNT_UPDATE_ID,
                userJsonHeaders(), validAccountUpdate(new BigDecimal("1000.00")));
        assertThat(ok.getStatusCode().value()).as("first update must succeed").isEqualTo(200);
        assertThat(money(json(ok.getBody()), "creditLimit")).isEqualByComparingTo("1000.00");

        // Provoke a genuine @Version conflict: fire N concurrent identical updates that all
        // read the same version before any commits, so all-but-one fail at flush time and are
        // mapped to a 409 ConcurrentUpdateException. Retry with a fresh (changed) value each
        // round to avoid a "no change detected" reject and to guarantee a real conflict window.
        boolean sawConflict = false;
        for (int attempt = 1; attempt <= MAX_CONFLICT_ATTEMPTS && !sawConflict; attempt++) {
            sawConflict = fireConcurrentUpdatesExpectingConflict(attempt);
        }
        assertThat(sawConflict)
                .as("concurrent identical updates must yield at least one 409 optimistic-lock conflict")
                .isTrue();
    }

    /**
     * Fires {@link #CONCURRENT_WRITERS} identical account updates that all release from a
     * {@link CyclicBarrier} together, maximising the load-then-commit overlap that triggers
     * JPA optimistic locking. Returns {@code true} if any request received a {@code 409}
     * carrying the COBOL parity message; asserts that every non-conflicting response was a
     * clean {@code 200} so an unexpected status never silently passes.
     *
     * @param attempt the 1-based round number, used to derive a fresh, collision-free
     *                credit-limit band so no submission is ever a no-op
     * @return whether at least one writer observed the optimistic-lock conflict
     */
    private boolean fireConcurrentUpdatesExpectingConflict(int attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_WRITERS);
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENT_WRITERS);
        try {
            // Every writer submits a DISTINCT, previously-unused credit limit so each request
            // is a genuine change (never a "no change detected" reject) while all still race on
            // the same @Version: writers that loaded the pre-commit version fail at flush with an
            // optimistic-lock conflict (409). The per-round band keeps values unique across rounds.
            int base = 2000 + attempt * CONCURRENT_WRITERS;
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_WRITERS; i++) {
                BigDecimal creditLimit = new BigDecimal((base + i) + ".00");
                Callable<ResponseEntity<String>> task = () -> {
                    barrier.await(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return httpWrite(HttpMethod.PUT, "/api/accounts/" + ACCOUNT_UPDATE_ID,
                            userJsonHeaders(), validAccountUpdate(creditLimit));
                };
                futures.add(pool.submit(task));
            }
            boolean conflict = false;
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                int status = response.getStatusCode().value();
                if (status == 409) {
                    JsonNode body = assertProblem(response, 409);
                    assertThat(body.path("title").asText()).isEqualTo("Concurrent Update Conflict");
                    assertThat(body.path("detail").asText())
                            .isEqualTo("Record changed by some one else. Please review");
                    conflict = true;
                } else {
                    // With distinct values the only other valid outcome is a clean commit (200).
                    assertThat(status)
                            .as("non-conflicting concurrent update must be a clean 200, was %s", status)
                            .isEqualTo(200);
                }
            }
            return conflict;
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================================================================================
    // Card flow (CCLI / CCDL / CCUP <- COCRDLIC / COCRDSLC / COCRDUPC, PAGE_SIZE = 7)
    // ==================================================================================

    @Test
    @DisplayName("CCLI: GET /api/cards is paged at seven; a negative page is rejected with 400")
    void listCardsIsPagedAtSeven() {
        ResponseEntity<String> response = httpGet("/api/cards?page=0", userAuthHeaders());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode cards = json(response.getBody()).path("cards");
        assertThat(cards.isArray()).as("ListResponse must expose a 'cards' array").isTrue();
        assertThat(cards.size()).as("card page must honour PAGE_SIZE = 7").isBetween(1, 7);

        // @Min(0) on the page parameter -> ConstraintViolationException -> 400 problem+json.
        ResponseEntity<String> negative = httpGet("/api/cards?page=-1", userAuthHeaders());
        assertProblem(negative, 400);
    }

    @Test
    @DisplayName("CCDL: GET /api/cards/{cardNum} returns 200 with the card detail")
    void getCardDetailReturns200() {
        ResponseEntity<String> response =
                httpGet("/api/cards/" + SEEDED_CARD_NUMBER + "?accountId=" + ACCOUNT_UPDATE_ID, userAuthHeaders());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(response.getBody());
        assertThat(body.path("cardNumber").asText()).isEqualTo(SEEDED_CARD_NUMBER);
        assertThat(Long.parseLong(body.path("accountId").asText())).isEqualTo(ACCOUNT_UPDATE_ID);
        assertThat(body.path("cardholderName").asText()).isEqualTo("Enrico Rosenbaum");
        assertThat(body.path("cardStatus").asText()).isEqualTo("Y");
        assertThat(body.path("expiryYear").asText()).isEqualTo("2024");
        assertThat(body.path("expiryMonth").asText()).isEqualTo("08");
    }

    @Test
    @DisplayName("CCUP: PUT /api/cards/{cardNum} updates a card (200); an unknown card returns 404")
    void updateCardReturns200_andUnknownReturns404() {
        // The update mutates the embossed name; snapshot the card row for restore.
        snapshotForRestore("cards", "card_num", SEEDED_CARD_NUMBER);

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("accountId", SEEDED_CARD_ACCOUNT_ID);
        update.put("cardNumber", SEEDED_CARD_NUMBER);
        update.put("cardholderName", "John Smith");
        update.put("cardStatus", "Y");
        update.put("expiryMonth", "08");
        update.put("expiryYear", "2024");
        update.put("expiryDay", "11");

        ResponseEntity<String> ok =
                httpWrite(HttpMethod.PUT, "/api/cards/" + SEEDED_CARD_NUMBER, userJsonHeaders(), update);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(ok.getBody());
        assertThat(body.path("cardNumber").asText()).isEqualTo(SEEDED_CARD_NUMBER);
        assertThat(body.path("cardholderName").asText()).isEqualTo("John Smith");

        // An unknown 16-digit card under a valid account is not found -> 404.
        Map<String, Object> unknown = new LinkedHashMap<>(update);
        unknown.put("cardNumber", "1111111111111111");
        ResponseEntity<String> notFound =
                httpWrite(HttpMethod.PUT, "/api/cards/1111111111111111", userJsonHeaders(), unknown);
        assertProblem(notFound, 404);
    }

    // ==================================================================================
    // Transaction flow (CT00 / CT01 / CT02 <- COTRN00C / COTRN01C / COTRN02C, PAGE_SIZE = 10)
    // ==================================================================================

    /**
     * Builds a fully valid {@code TransactionDto.AddRequest} for account
     * {@link #ACCOUNT_UPDATE_ID}. The account is resolved through the card cross-reference
     * to derive the card number ({@code COTRN02C} account-precedence rule), the type code
     * {@code 01} is a known {@code TransactionTypeCode}, dates are ISO {@code YYYY-MM-DD}
     * and calendar-valid, and every required merchant field is populated. Confirmation is
     * {@code Y} so the add commits.
     *
     * @return the request payload as an ordered JSON-serialisable map
     */
    private Map<String, Object> validAddRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", Long.toString(ACCOUNT_UPDATE_ID));
        body.put("cardNumber", "");
        body.put("typeCode", "01");
        body.put("categoryCode", "0001");
        body.put("source", "POS");
        body.put("description", "E2E ONLINE TEST TRANSACTION");
        body.put("amount", new BigDecimal("100.00"));
        body.put("originDate", "2024-01-15");
        body.put("processDate", "2024-01-15");
        body.put("merchantId", "123456789");
        body.put("merchantName", "TEST MERCHANT");
        body.put("merchantCity", "SPRINGFIELD");
        body.put("merchantZip", "90001");
        body.put("confirm", "Y");
        return body;
    }

    @Test
    @DisplayName("CT00: GET /api/transactions is paged at ten")
    void listTransactionsIsPagedAtTen() {
        // The seed leaves the transactions table empty; add one through the real endpoint.
        ResponseEntity<String> added =
                httpWrite(HttpMethod.POST, "/api/transactions", userJsonHeaders(), validAddRequest());
        assertThat(added.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> response = httpGet("/api/transactions?page=0", userAuthHeaders());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode transactions = json(response.getBody()).path("transactions");
        assertThat(transactions.isArray()).as("ListResponse must expose a 'transactions' array").isTrue();
        assertThat(transactions.size()).as("transaction page must honour PAGE_SIZE = 10").isBetween(1, 10);
    }

    @Test
    @DisplayName("CT01: GET /api/transactions/{id} returns 200 for a known id and 404 for an unknown id")
    void getTransactionByIdReturns200_andNotFoundReturns404() {
        ResponseEntity<String> added =
                httpWrite(HttpMethod.POST, "/api/transactions", userJsonHeaders(), validAddRequest());
        assertThat(added.getStatusCode().value()).isEqualTo(201);
        String tranId = json(added.getBody()).path("transactionId").asText();
        assertThat(tranId).isNotBlank();

        ResponseEntity<String> detail = httpGet("/api/transactions/" + tranId, userAuthHeaders());
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(json(detail.getBody()).path("transactionId").asText()).isEqualTo(tranId);

        ResponseEntity<String> notFound = httpGet("/api/transactions/9999999999999999", userAuthHeaders());
        JsonNode problem = assertProblem(notFound, 404);
        assertThat(problem.path("title").asText()).isEqualTo("Resource Not Found");
    }

    @Test
    @DisplayName("CT02: POST /api/transactions adds a transaction and returns 201 with a 16-digit id")
    void addTransactionReturns201() {
        // The transactions table is emptied per test, so the first add receives id 1.
        ResponseEntity<String> response =
                httpWrite(HttpMethod.POST, "/api/transactions", userJsonHeaders(), validAddRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode body = json(response.getBody());
        assertThat(body.path("transactionId").asText()).isEqualTo("0000000000000001");
        assertThat(body.path("transactionType").asText()).isEqualTo("01");
        assertThat(money(body, "amount")).isEqualByComparingTo("100.00");
        assertWireScale2(response.getBody(), "amount");
    }

    // ==================================================================================
    // Billing flow (CB00 <- COBIL00C)
    // ==================================================================================

    @Test
    @DisplayName("CB00: POST /api/billing/pay settles the balance (200), then a second pay is 422")
    void payBillReturns200WithUpdatedBalance() {
        // Paying settles the balance to zero and writes a payment transaction; snapshot the
        // account so the seed balance is restored and clear the inserted transaction.
        snapshotForRestore("accounts", "acct_id", ACCOUNT_VIEW_ID);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("accountId", Long.toString(ACCOUNT_VIEW_ID));
        request.put("confirm", "Y");

        ResponseEntity<String> response =
                httpWrite(HttpMethod.POST, "/api/billing/pay", userJsonHeaders(), request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String raw = response.getBody();
        JsonNode body = json(raw);
        assertThat(Long.parseLong(body.path("accountId").asText())).isEqualTo(ACCOUNT_VIEW_ID);
        assertThat(body.path("confirm").asText()).isEqualTo("Y");
        assertWireScale2(raw, "currentBalance");
        assertThat(money(body, "currentBalance")).isEqualByComparingTo("0.00");

        // The balance is now zero, so a second payment hits the "nothing to pay" rule (422).
        ResponseEntity<String> second =
                httpWrite(HttpMethod.POST, "/api/billing/pay", userJsonHeaders(), request);
        JsonNode problem = assertProblem(second, 422);
        assertThat(problem.path("detail").asText()).isEqualTo("You have nothing to pay...");
    }

    // ==================================================================================
    // Report flow (CR00 <- CORPT00C, F-011 SQS FIFO bridge)
    // ==================================================================================

    @Test
    @DisplayName("CR00: POST /api/reports/submit returns 202 and publishes one FIFO report message")
    void submitReportReturns202AndPublishesToFifo() {
        // Start from an empty queue so the assertion targets only this submission.
        drainReportQueue();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("monthly", "Y");
        request.put("confirm", "Y");

        ResponseEntity<String> response =
                httpWrite(HttpMethod.POST, "/api/reports/submit", userJsonHeaders(), request);
        assertThat(response.getStatusCode().value()).isEqualTo(202);

        List<Message> messages = drainReportQueue();
        assertThat(messages).as("exactly one report-request message must be published").hasSize(1);
        Message message = messages.get(0);
        assertThat(message.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID))
                .isEqualTo(REPORT_MESSAGE_GROUP_ID);

        LocalDate today = LocalDate.now();
        String windowStart = today.withDayOfMonth(1).format(REPORT_DATE_FORMAT);
        String windowEnd = today.withDayOfMonth(today.lengthOfMonth()).format(REPORT_DATE_FORMAT);
        assertThat(message.body())
                .as("payload must carry the monthly report type and the computed month window")
                .contains("Monthly")
                .contains(windowStart)
                .contains(windowEnd);
    }

    @Test
    @DisplayName("CR00: POST /api/reports/submit with no report type returns 400 and publishes nothing")
    void submitReportWithNoTypeReturns400() {
        drainReportQueue();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("confirm", "Y");

        ResponseEntity<String> response =
                httpWrite(HttpMethod.POST, "/api/reports/submit", userJsonHeaders(), request);
        JsonNode body = assertProblem(response, 400);
        assertThat(body.path("detail").asText()).isEqualTo("Select a report type to print report...");

        assertThat(drainReportQueue())
                .as("no message must be published when validation fails").isEmpty();
    }

    // ==================================================================================
    // User-admin flow (CU00-CU03 <- COUSR00C/01C/02C/03C, ROLE_ADMIN)
    // ==================================================================================

    @Test
    @DisplayName("CU00: GET /api/admin/users lists for an admin (200) and forbids a user token (403)")
    void adminListsUsers_andUserTokenForbidden() {
        ResponseEntity<String> adminResponse = httpGet("/api/admin/users", adminAuthHeaders());
        assertThat(adminResponse.getStatusCode().value()).isEqualTo(200);
        JsonNode users = json(adminResponse.getBody()).path("users");
        assertThat(users.isArray()).as("ListResponse must expose a 'users' array").isTrue();
        assertThat(users.size()).as("user page must honour PAGE_SIZE = 10").isBetween(1, 10);

        ResponseEntity<String> userResponse = httpGet("/api/admin/users", userAuthHeaders());
        JsonNode problem = assertProblem(userResponse, 403);
        assertThat(problem.path("detail").asText()).contains("Access is denied");
    }

    @Test
    @DisplayName("CU01/CU03: admin creates a user (201), rejects a duplicate (409), then deletes it (200)")
    void adminCreatesThenDeletesUser() {
        String userId = "TESTUSR1";
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("firstName", "TEST");
        create.put("lastName", "USERONE");
        create.put("userId", userId);
        create.put("password", "PASS1234");
        create.put("userType", "U");

        ResponseEntity<String> created =
                httpWrite(HttpMethod.POST, "/api/admin/users", adminJsonHeaders(), create);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode createdBody = json(created.getBody());
        assertThat(createdBody.path("userId").asText()).isEqualTo(userId);
        assertThat(createdBody.has("password")).as("UserSummary must not expose a password").isFalse();

        // A second create of the same id is a duplicate -> 409.
        ResponseEntity<String> duplicate =
                httpWrite(HttpMethod.POST, "/api/admin/users", adminJsonHeaders(), create);
        JsonNode duplicateBody = assertProblem(duplicate, 409);
        assertThat(duplicateBody.path("title").asText()).isEqualTo("Duplicate Resource");

        // Delete -> 200, and the delete response must not leak the password.
        ResponseEntity<String> deleted =
                httpWrite(HttpMethod.DELETE, "/api/admin/users/" + userId, adminAuthHeaders(), null);
        assertThat(deleted.getStatusCode().value()).isEqualTo(200);
        JsonNode deletedBody = json(deleted.getBody());
        assertThat(deletedBody.path("userId").asText()).isEqualTo(userId);
        assertThat(deletedBody.has("password")).as("DeleteResponse must not expose a password").isFalse();
    }

    @Test
    @DisplayName("CU02: admin updates a user (200); an unknown user id returns 404")
    void adminUpdatesUser_andUnknownReturns404() {
        String userId = "TESTUSR2";
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("firstName", "TEMP");
        create.put("lastName", "UPDATEME");
        create.put("userId", userId);
        create.put("password", "PASS1234");
        create.put("userType", "U");
        ResponseEntity<String> created =
                httpWrite(HttpMethod.POST, "/api/admin/users", adminJsonHeaders(), create);
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        // A blank password keeps the existing hash; the names change.
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("userId", userId);
        update.put("firstName", "UPDATED");
        update.put("lastName", "SURNAME");
        update.put("password", "");
        update.put("userType", "U");
        ResponseEntity<String> updated =
                httpWrite(HttpMethod.PUT, "/api/admin/users/" + userId, adminJsonHeaders(), update);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(json(updated.getBody()).path("firstName").asText()).isEqualTo("UPDATED");

        // An unknown user id is not found -> 404.
        Map<String, Object> unknown = new LinkedHashMap<>(update);
        unknown.put("userId", "NOSUCH99");
        ResponseEntity<String> notFound =
                httpWrite(HttpMethod.PUT, "/api/admin/users/NOSUCH99", adminJsonHeaders(), unknown);
        assertProblem(notFound, 404);
    }

    // ==================================================================================
    // Cross-cutting: observability + the security envelope
    // ==================================================================================

    @Test
    @DisplayName("Cross-cutting: correlation id echo/replace, health UP, and 401 for an unauthenticated call")
    void correlationIdEchoed_healthUp_andUnauthenticatedIs401() {
        // (a) A well-formed client correlation id is echoed back verbatim.
        String validCorrelationId = "e2e-correlation-1234";
        HttpHeaders validCidHeaders = new HttpHeaders();
        validCidHeaders.set("X-Correlation-Id", validCorrelationId);
        ResponseEntity<String> health = httpGet("/actuator/health", validCidHeaders);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(validCorrelationId);

        // A malformed correlation id is replaced by a freshly generated UUID.
        HttpHeaders badCidHeaders = new HttpHeaders();
        badCidHeaders.set("X-Correlation-Id", "bad id with spaces!");
        ResponseEntity<String> healthAgain = httpGet("/actuator/health", badCidHeaders);
        String regenerated = healthAgain.getHeaders().getFirst("X-Correlation-Id");
        assertThat(regenerated).isNotNull().isNotEqualTo("bad id with spaces!");
        assertThat(regenerated).matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

        // (b) Health is UP with the real datasource and LocalStack components green.
        JsonNode healthBody = json(health.getBody());
        assertThat(healthBody.path("status").asText()).isEqualTo("UP");
        JsonNode components = healthBody.path("components");
        for (String component : List.of("db", "database", "s3", "sqs")) {
            assertThat(components.path(component).path("status").asText())
                    .as("health component '%s' must report UP", component)
                    .isEqualTo("UP");
        }

        // (c) A protected endpoint with no Authorization header is 401 problem+json.
        ResponseEntity<String> unauthenticated =
                httpGet("/api/accounts/" + ACCOUNT_VIEW_ID, new HttpHeaders());
        JsonNode problem = assertProblem(unauthenticated, 401);
        assertThat(problem.path("title").asText()).isEqualTo("Unauthorized");
        assertThat(problem.path("detail").asText()).isEqualTo("Authentication required or token invalid");

        // (d) A signin increments the auth-attempts counter exposed via Prometheus.
        Map<String, Object> signin = new LinkedHashMap<>();
        signin.put("userId", SEEDED_ADMIN_USER_ID);
        signin.put("password", SEED_RAW_PASSWORD);
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        httpWrite(HttpMethod.POST, "/api/auth/signin", jsonHeaders, signin);

        ResponseEntity<String> prometheus = httpGet("/actuator/prometheus", new HttpHeaders());
        assertThat(prometheus.getStatusCode().value()).isEqualTo(200);
        assertThat(prometheus.getBody()).contains("carddemo_auth_attempts_total");
    }
}
