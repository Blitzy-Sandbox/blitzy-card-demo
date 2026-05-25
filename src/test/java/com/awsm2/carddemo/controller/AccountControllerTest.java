/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.controller;

import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.AccountViewDto;
import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.exception.ConcurrentModificationException;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.AccountUpdateService;
import com.awsm2.carddemo.service.AccountViewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc controller-slice tests for {@link AccountController}.
 *
 * <p><b>QA Checkpoint 3 finding addressed by this suite.</b></p>
 * <ul>
 *   <li><b>Issue&nbsp;#2 (MAJOR &mdash; Test coverage gap).</b> Prior
 *       to this suite, {@code AccountController} was the only REST
 *       controller in the project without an integration-level test
 *       class. The QA report ({@code QA Checkpoint 3}) explicitly
 *       called this out as the root cause of why the date-validation
 *       defect (Issue&nbsp;#1) escaped the build pipeline and made it
 *       all the way to the QA runtime testing stage. The other
 *       controllers in the project ({@code AuthControllerTest},
 *       {@code CardControllerTest}, {@code MenuControllerTest},
 *       {@code ReportControllerTest},
 *       {@code UserAdminControllerTest}) all have integration test
 *       classes that exercise the controller layer through the Spring
 *       MVC request pipeline, so the contract-level assertion gap was
 *       isolated to {@link AccountController}. This suite closes
 *       Issue&nbsp;#2 by exercising every public endpoint surface of
 *       {@code AccountController} through {@code MockMvc}, including
 *       the path that originally exhibited the date-validation
 *       defect.</li>
 *   <li><b>Issue&nbsp;#1 (CRITICAL &mdash; Date validation regression
 *       at the controller layer).</b> A dedicated regression test
 *       drives a {@code PUT /api/accounts/{id}} request with an
 *       ISO-8601 {@code yyyy-MM-dd} body (the very payload that the
 *       QA report demonstrated would be rejected at index&nbsp;4 by
 *       the pre-fix {@code AccountUpdateService}). With the
 *       single-line fix in {@code AccountUpdateService} now in place
 *       (changing the validate call to pass the explicit
 *       {@code "YYYY-MM-DD"} mask), the request must traverse the
 *       full Spring MVC pipeline, deserialize cleanly into an
 *       {@code AccountUpdateDto} (whose {@code @JsonFormat(pattern =
 *       "yyyy-MM-dd")} annotations confirm the date fields are
 *       contractually ISO-8601), reach the service, and return
 *       HTTP&nbsp;200 with the updated account view envelope.</li>
 * </ul>
 *
 * <p><b>Slice architecture.</b> The class is wired with
 * {@code @WebMvcTest} and explicitly excludes
 * {@link JwtAuthenticationFilter} from the auto-detected filter chain
 * &mdash; the JWT filter requires Secrets-Manager-backed signing-key
 * resolution at boot time and is not the subject under test. Method-
 * level security is engaged via {@link EnableMethodSecurity} so that
 * the {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} annotations
 * declared on {@link AccountController#getAccount(Long)} and
 * {@link AccountController#updateAccount(Long, AccountUpdateDto)} are
 * actually evaluated for each request &mdash; this lets us assert the
 * {@code 401}/{@code 403} negative cases without relying on the JWT
 * filter being in the chain.</p>
 *
 * <p><b>What this slice deliberately does NOT cover.</b> The
 * transactional semantics of {@code @Transactional(rollbackFor =
 * Exception.class)}, the JPA {@code @Version} optimistic-lock
 * increment, the {@code BigDecimal} {@code HALF_EVEN} rounding mode,
 * and the dual-write atomicity (Account&nbsp;+&nbsp;Customer) are all
 * covered by {@code AccountUpdateServiceTest} at the unit-test layer
 * and by the runtime re-verification phase against the live
 * application. This slice asserts only the controller-boundary
 * contract: request shape, authorization, path/body consistency,
 * status-code mapping, and response envelope.</p>
 *
 * @see AccountController
 * @see AccountUpdateService
 * @see AccountViewService
 * @see GlobalExceptionHandler
 */
@WebMvcTest(
        controllers = AccountController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class
        )
)
@Import(GlobalExceptionHandler.class)
@EnableMethodSecurity(prePostEnabled = true)
@TestPropertySource(properties = {
        // The production wiring resolves the JWT signing key from AWS
        // Secrets Manager via SecretsManagerService. In a slice test we
        // never issue or validate tokens (each test uses Spring Security's
        // @WithMockUser to inject the authentication directly), so we
        // supply a deterministic test-only key. JwtTokenProvider is
        // additionally @MockBean below so the bean wiring never reaches
        // the Secrets Manager backend.
        "carddemo.security.jwt.signing-key=test-only-jwt-signing-key-32-bytes-min-length",
        "carddemo.security.cors.allowed-origins=http://localhost:3000"
})
@DisplayName("AccountController slice tests (/api/accounts)")
class AccountControllerTest {

    // ------------------------------------------------------------------
    // Fixture constants — chosen to align with the QA reproduction
    // payload from the Checkpoint 3 report and the COBOL field
    // constraints in app/cpy/CVACT01Y.cpy and app/cpy/CVCUS01Y.cpy.
    // ------------------------------------------------------------------

    /** 11-digit account ID from the QA reproduction step (in scope). */
    private static final Long ACCOUNT_ID = 10000000001L;

    /**
     * Initial optimistic-lock version. All ten seed accounts begin at
     * {@code version=0} per the QA report's confirmation that no
     * successful PUT had ever been processed prior to the Issue&nbsp;#1
     * fix. The QA report's diagnostic step
     * (<i>"All 10 accounts in DB still have version=0 after 477 test
     * API calls"</i>) is precisely this value.
     */
    private static final Long INITIAL_VERSION = 0L;

    /** Customer ID for the test fixture (CUST-ID PIC 9(09)). */
    private static final Long CUSTOMER_ID = 100000001L;

    /** Valid SSN with part1=123 (rejects 0, 666, and 900-999). */
    private static final Long CUSTOMER_SSN = 123456789L;

    // ------------------------------------------------------------------
    // Spring-injected test infrastructure
    // ------------------------------------------------------------------

    @Autowired
    private MockMvc mockMvc;

    /**
     * Mockito mock for {@link AccountViewService}. The
     * {@code @WebMvcTest} slice does not load the production service
     * beans, so {@code @MockBean} provides the implementation that
     * {@link AccountController#getAccount(Long)} will see at request
     * time. Per-test stubbing tailors the response or arranges for the
     * service to throw a specific exception so the slice can verify
     * the controller-to-{@code GlobalExceptionHandler} translation.
     */
    @MockBean
    private AccountViewService accountViewService;

    /**
     * Mockito mock for {@link AccountUpdateService}. As above, this
     * stands in for the production update-service bean during the
     * slice. Critically, the regression test for Issue&nbsp;#1
     * verifies the controller can deliver an ISO-8601 date body
     * <em>through</em> Jackson deserialization into the service &mdash;
     * the controller-to-service hand-off is the contract under test
     * here, not the service's internal date-mask choice (which is
     * unit-tested in {@code AccountUpdateServiceTest}).
     */
    @MockBean
    private AccountUpdateService accountUpdateService;

    /**
     * Mockito mock for {@link JwtTokenProvider}. Required because the
     * production {@link AccountController} indirectly depends on the
     * security context wiring; mocking it prevents the Secrets
     * Manager-backed initialization that would otherwise fail in a
     * slice test without AWS credentials.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    /**
     * Reusable Jackson {@link ObjectMapper} with the
     * {@link JavaTimeModule} registered so that {@link LocalDate}
     * fields on {@link AccountUpdateDto} serialize as ISO-8601
     * strings ({@code "yyyy-MM-dd"}) &mdash; the exact wire format
     * specified by the {@code @JsonFormat(pattern = "yyyy-MM-dd")}
     * annotations on the DTO. Without the module, the default
     * Jackson serializer would emit dates as integer arrays
     * ({@code [2020, 1, 15]}), which would not exercise the same
     * deserialization path that the QA-reported runtime payload uses.
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    // ------------------------------------------------------------------
    // Per-test setup
    // ------------------------------------------------------------------

    /**
     * Sanity-check that no test mutates the shared {@link ObjectMapper}.
     * The Jackson {@link JavaTimeModule} registration must remain
     * effective across every test in this class.
     */
    @BeforeEach
    void verifyObjectMapperConfiguration() {
        // Round-trip a known LocalDate through the mapper. If the
        // module is not registered, this assertion fails BEFORE the
        // test body runs, producing a clear diagnostic message.
        assertThat(objectMapper.canSerialize(LocalDate.class)).isTrue();
    }

    // ==================================================================
    // GET /api/accounts/{id} — Account-view tests
    // ==================================================================

    @Nested
    @DisplayName("GET /api/accounts/{id} — view endpoint contract")
    class GetAccount {

        /**
         * Happy-path: a valid 11-digit account ID returns HTTP 200
         * and an {@code ApiResponse} envelope whose {@code data}
         * carries the joined Account+Customer+CardCrossReference
         * view per AAP &sect;0.4.1 and the COBOL
         * {@code COACTVWC.PROCESS-ENTER-KEY} ordering.
         */
        @Test
        @DisplayName("ROLE_ADMIN GET → HTTP 200 with account view envelope")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getAccount_validId_returns200WithAccountView() throws Exception {
            AccountViewDto view = buildAccountView(ACCOUNT_ID);
            when(accountViewService.getAccountView(ACCOUNT_ID)).thenReturn(view);

            mockMvc.perform(get("/api/accounts/{id}", ACCOUNT_ID)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    // The ApiResponse.success(T) factory carries the
                    // literal "OK" reason code (NOT "SUCCESS") on its
                    // envelope. Verified via the live response body
                    // observed at /api/accounts/{id} (e.g.,
                    // {"code":"OK","message":"Success","data":{...}}).
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_ID))
                    .andExpect(jsonPath("$.data.activeStatus").value("Y"))
                    // AAP §0.6.1: monetary fields must serialize as
                    // plain decimal (never scientific notation).
                    .andExpect(jsonPath("$.data.currentBalance").value(1234.56))
                    .andExpect(jsonPath("$.data.creditLimit").value(5000.00))
                    .andExpect(jsonPath("$.data.customerId").value(CUSTOMER_ID))
                    .andExpect(jsonPath("$.data.firstName").value("JOHN"))
                    .andExpect(jsonPath("$.data.lastName").value("DOE"));

            verify(accountViewService).getAccountView(ACCOUNT_ID);
        }

        /**
         * ROLE_USER must also be able to view their account &mdash;
         * the controller's {@code @PreAuthorize("hasAnyRole('USER',
         * 'ADMIN')")} predicate permits both roles. This asserts the
         * navigation parity the QA report observed (Account VIEW
         * fully functional).
         */
        @Test
        @DisplayName("ROLE_USER GET → HTTP 200 (navigation parity preserved)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void getAccount_asUser_returns200() throws Exception {
            AccountViewDto view = buildAccountView(ACCOUNT_ID);
            when(accountViewService.getAccountView(ACCOUNT_ID)).thenReturn(view);

            mockMvc.perform(get("/api/accounts/{id}", ACCOUNT_ID)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());

            verify(accountViewService).getAccountView(ACCOUNT_ID);
        }

        /**
         * QA report §"Test Results" item #4: a non-existent account
         * must return HTTP 404 with the {@code RecordNotFoundException}
         * surface translated by {@link GlobalExceptionHandler} into
         * the {@code ApiResponse.error} envelope. The reason code
         * carried by the exception is preserved verbatim on the wire.
         */
        @Test
        @DisplayName("GET with non-existent ID → HTTP 404 RecordNotFoundException")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getAccount_notFound_returns404() throws Exception {
            // Match the COBOL-faithful exception message from
            // AccountViewService: the exception carries a reasonCode
            // of "CardCrossReference" because XREF is the first
            // dataset consulted in the COACTVWC paragraph ordering.
            when(accountViewService.getAccountView(99999999999L))
                    .thenThrow(new RecordNotFoundException(
                            "CardCrossReference",
                            "Did not find this account in account card xref file: "
                                    + "acctId=99999999999"));

            mockMvc.perform(get("/api/accounts/{id}", 99999999999L)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CardCrossReference"))
                    .andExpect(jsonPath("$.message")
                            .value(containsString("99999999999")));
        }

        /**
         * QA report §"Test Results" item #5: a non-numeric account ID
         * must be rejected by Spring MVC's path-variable type-coercion
         * (Long parsing) with HTTP 400 and the
         * {@code TYPE_MISMATCH}-family error mapped by
         * {@link GlobalExceptionHandler}.
         */
        @Test
        @DisplayName("GET with non-numeric ID → HTTP 400 type mismatch")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getAccount_nonNumericId_returns400() throws Exception {
            mockMvc.perform(get("/api/accounts/{id}", "ABC")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            // The path-variable coercion failed BEFORE the controller
            // method was invoked; the service must NEVER be consulted.
            verifyNoInteractions(accountViewService);
        }

        /**
         * QA report §"Test Results" items #6 and #7: a zero or
         * negative account ID violates the controller's
         * {@code @Min(1L)} constraint and surfaces as a
         * {@code ConstraintViolationException} →
         * HTTP&nbsp;400&nbsp;{@code VALIDATION}. The original COBOL
         * {@code 1265-EDIT-US-ACCOUNT-ID} paragraph (in
         * {@code app/cbl/COACTVWC.cbl}) rejected non-positive
         * account IDs with the message <i>"Account number must be a
         * non zero 11 digit number"</i>; the Java target carries the
         * spirit of that rejection here at the controller boundary.
         */
        @Test
        @DisplayName("GET with zero ID → HTTP 400 (violates @Min(1L))")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getAccount_zeroId_returns400() throws Exception {
            mockMvc.perform(get("/api/accounts/{id}", 0L)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(accountViewService);
        }

        /**
         * QA report §"Edge Case" tests #1-#3: SQL-injection attempts
         * via the path variable. Long parsing rejects all of these
         * with HTTP 400, and the service is never consulted &mdash;
         * confirming the defense-in-depth layering called out in the
         * QA report's "Adversarial Testing" section.
         */
        @Test
        @DisplayName("GET with SQL-injection path payload → HTTP 400")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getAccount_sqlInjection_returns400() throws Exception {
            mockMvc.perform(get("/api/accounts/{id}", "1 OR 1=1")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(accountViewService);
        }

        /**
         * QA report §"Test Results" item #8: an unauthenticated GET
         * request must be rejected by Spring Security's filter chain
         * before reaching the controller. Either HTTP 401 or 403 is
         * an acceptable rejection; the assertion is that the
         * service is NOT consulted.
         */
        @Test
        @DisplayName("anonymous GET → 4xx and service not consulted")
        void getAccount_unauthenticated_isRejected() throws Exception {
            mockMvc.perform(get("/api/accounts/{id}", ACCOUNT_ID)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().is4xxClientError());

            verifyNoInteractions(accountViewService);
        }

        /**
         * Agent-prompt checklist item: every success response must
         * carry a {@code timestamp} on the {@link ApiResponse}
         * envelope. The {@link ApiResponse#success(Object)} factory
         * always stamps {@link java.time.Instant#now()} at invocation
         * time so the envelope can be correlated with CloudWatch /
         * OpenSearch / CloudTrail entries at the same moment in
         * time per AAP &sect;0.6.6 observability requirements.
         */
        @Test
        @DisplayName("GET — successful response carries non-empty timestamp")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void getAccount_successEnvelope_carriesTimestamp() throws Exception {
            AccountViewDto view = buildAccountView(ACCOUNT_ID);
            when(accountViewService.getAccountView(ACCOUNT_ID)).thenReturn(view);

            mockMvc.perform(get("/api/accounts/{id}", ACCOUNT_ID)
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.timestamp").exists())
                    // Instant.now().toString() serialization produces
                    // an ISO-8601 string ending in 'Z' (UTC zone) when
                    // Jackson's JavaTimeModule is on the classpath.
                    .andExpect(jsonPath("$.timestamp").isString());
        }
    }

    // ==================================================================
    // PUT /api/accounts/{id} — Account-update tests
    // (the heart of the QA Checkpoint 3 Issue #1 regression coverage)
    // ==================================================================

    @Nested
    @DisplayName("PUT /api/accounts/{id} — update endpoint contract")
    class PutAccount {

        /**
         * <b>Issue&nbsp;#1 regression test (controller layer).</b>
         *
         * <p>This is the test whose ABSENCE the QA Checkpoint 3
         * report identified as Issue&nbsp;#2 and the root cause of why
         * Issue&nbsp;#1 escaped to runtime QA. It drives a
         * {@code PUT /api/accounts/{id}} request with the exact
         * ISO-8601 {@code yyyy-MM-dd} body shape that the QA
         * reproduction step used &mdash; including the four date
         * fields ({@code openDate}, {@code expirationDate},
         * {@code reissueDate}, {@code dateOfBirth}) that the pre-fix
         * service was rejecting at index&nbsp;4.</p>
         *
         * <p>With the Issue&nbsp;#1 fix in place (the explicit
         * {@code "YYYY-MM-DD"} mask passed by
         * {@link AccountUpdateService#validateDate}), the request must
         * traverse the full Spring MVC pipeline:
         * <ol>
         *   <li>Pass {@code @Valid} Bean Validation on the request
         *       body.</li>
         *   <li>Pass the controller's path/body consistency check
         *       (URL {@code id} must match {@code body.accountId}).</li>
         *   <li>Reach {@link AccountUpdateService#updateAccount}.</li>
         *   <li>Return HTTP&nbsp;200 with the updated account view
         *       wrapped in {@code ApiResponse.success}.</li>
         * </ol>
         */
        @Test
        @DisplayName("PUT with ISO-8601 date body → HTTP 200 (Issue #1 regression)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_isoDateBody_returns200() throws Exception {
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);
            AccountViewDto responseDto = buildAccountView(ACCOUNT_ID);
            when(accountUpdateService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateDto.class)))
                    .thenReturn(responseDto);

            String body = objectMapper.writeValueAsString(request);

            // Sanity-check the JSON we are about to send: every date
            // field must serialize as a yyyy-MM-dd string (NOT an
            // integer-array tuple) so this regression test actually
            // exercises the same date-string path that the QA-reported
            // bug was rejecting. If this assertion fails, the test
            // diagnostic identifies a Jackson regression independently
            // of any controller-layer change.
            assertThat(body)
                    .as("openDate must be ISO 8601 (yyyy-MM-dd) on the wire")
                    .contains("\"openDate\":\"2020-01-15\"")
                    .contains("\"expirationDate\":\"2030-01-15\"")
                    .contains("\"reissueDate\":\"2024-01-15\"")
                    .contains("\"dateOfBirth\":\"1980-01-15\"");

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    // The ApiResponse.success(T, String) factory carries
                    // the literal "OK" reason code on its envelope.
                    // The message portion is supplied by the controller
                    // ("Account updated successfully"); the response
                    // shape is the AAP-mandated standardized envelope.
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_ID))
                    .andExpect(jsonPath("$.data.activeStatus").value("Y"));

            verify(accountUpdateService).updateAccount(eq(ACCOUNT_ID), any(AccountUpdateDto.class));
        }

        /**
         * ROLE_USER may also update an account &mdash; the
         * controller's {@code @PreAuthorize("hasAnyRole('USER',
         * 'ADMIN')")} predicate permits both roles. This asserts that
         * the role-based access control matches the AAP-mandated
         * navigation parity for the account-maintenance flow.
         */
        @Test
        @DisplayName("ROLE_USER PUT → HTTP 200 (navigation parity preserved)")
        @WithMockUser(username = "USER0001", roles = "USER")
        void updateAccount_asUser_returns200() throws Exception {
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);
            AccountViewDto responseDto = buildAccountView(ACCOUNT_ID);
            when(accountUpdateService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateDto.class)))
                    .thenReturn(responseDto);

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            verify(accountUpdateService).updateAccount(eq(ACCOUNT_ID), any(AccountUpdateDto.class));
        }

        /**
         * QA report §"Test Results" item #23: when the path-variable
         * {@code id} differs from the request body's
         * {@code accountId}, the controller throws
         * {@link ValidationException} with reason code
         * {@code "ACCOUNT_ID_MISMATCH"}. This is a defense-in-depth
         * check against an IDOR-style confusion attack where a
         * client could PUT {@code /api/accounts/123} with body
         * {@code {"accountId":456}}.
         */
        @Test
        @DisplayName("PUT with path/body ID mismatch → HTTP 400 ACCOUNT_ID_MISMATCH")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_pathBodyMismatch_returns400() throws Exception {
            // Path is ACCOUNT_ID; body declares a different account.
            AccountUpdateDto bodyMismatch =
                    buildValidUpdateRequest(ACCOUNT_ID + 1, INITIAL_VERSION);

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bodyMismatch)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_ID_MISMATCH"));

            // The ValidationException is thrown BEFORE the service is
            // consulted &mdash; this is the defense-in-depth assertion.
            verifyNoInteractions(accountUpdateService);
        }

        /**
         * QA report §"Test Results" item #24: an empty body must be
         * rejected by Spring MVC's Bean Validation cascade (each of
         * the 22 {@code @NotNull} / {@code @NotBlank}-annotated
         * record components produces a field error). The service
         * must NOT be invoked.
         */
        @Test
        @DisplayName("PUT with empty body → HTTP 400 with field errors")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_emptyBody_returns400() throws Exception {
            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").isArray());

            verifyNoInteractions(accountUpdateService);
        }

        /**
         * QA report §"Test Results" item #20: malformed JSON must be
         * rejected by Jackson's {@code HttpMessageNotReadableException}
         * handler with HTTP 400.
         */
        @Test
        @DisplayName("PUT with malformed JSON → HTTP 400 MALFORMED_REQUEST")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_malformedJson_returns400() throws Exception {
            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content("{malformed json"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(accountUpdateService);
        }

        /**
         * QA report §"Edge Case" item #19: a {@code currentBalance}
         * with 11 integer digits violates the {@code @Digits(integer
         * = 10, fraction = 2)} constraint, replicating the COBOL
         * {@code ON SIZE ERROR} cap of {@code PIC S9(10)V99}. This
         * is the controller-boundary realization of the AAP
         * &sect;0.6.1 BigDecimal-precision rule.
         */
        @Test
        @DisplayName("PUT with currentBalance overflow (11 int digits) → HTTP 400")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_currentBalanceOverflow_returns400() throws Exception {
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);
            // Replace currentBalance (10 integer digits max → use 11 to overflow).
            String body = objectMapper.writeValueAsString(request)
                    .replaceFirst("\"currentBalance\":1234\\.56",
                            "\"currentBalance\":99999999999.99");

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").isArray());

            verifyNoInteractions(accountUpdateService);
        }

        /**
         * QA report §"Test Results" items #1-#23: when the service
         * itself raises a {@link ValidationException} (e.g.,
         * validation failed downstream of the controller boundary on
         * SSN/state/phone/ZIP combinations), the
         * {@link GlobalExceptionHandler} surfaces it as HTTP 400 with
         * the field-error payload preserved on the wire.
         */
        @Test
        @DisplayName("PUT — service throws ValidationException → HTTP 400 with field errors")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_serviceValidationException_returns400() throws Exception {
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);
            // The service raises a ValidationException with a field
            // error list — exactly the post-fix shape of QA report
            // Issue #1 (had it not been a 4-field date defect).
            List<ValidationException.FieldError> errors = List.of(
                    new ValidationException.FieldError(
                            "phoneNumber1",
                            "Invalid NANPA area code"));
            when(accountUpdateService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateDto.class)))
                    .thenThrow(new ValidationException(
                            "Account update validation failed", errors));

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("phoneNumber1"))
                    .andExpect(jsonPath("$.fieldErrors[0].message")
                            .value(containsString("NANPA")));
        }

        /**
         * <b>AAP &sect;0.4.1 / AAP &sect;0.6.1 cross-check.</b>
         *
         * <p>When the {@link AccountUpdateService} raises a
         * {@link ConcurrentModificationException} (because the JPA
         * {@code @Version} on Account did not match the value in the
         * request body), the {@link GlobalExceptionHandler} surfaces
         * it as HTTP 409 Conflict &mdash; the controller-boundary
         * realization of the COBOL {@code SYNCPOINT ROLLBACK} on
         * snapshot mismatch (the only explicit ROLLBACK in the
         * entire CardDemo source per AAP &sect;0.1.1).</p>
         */
        @Test
        @DisplayName("PUT — optimistic lock conflict → HTTP 409 Conflict")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_optimisticLockConflict_returns409() throws Exception {
            // Caller sends a stale version (request.version=0). The
            // service detects the snapshot mismatch and translates it
            // to ConcurrentModificationException — the GlobalExceptionHandler
            // surfaces this as HTTP 409.
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, 0L);
            when(accountUpdateService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateDto.class)))
                    .thenThrow(new ConcurrentModificationException(
                            "VERSION_CONFLICT",
                            "Account was modified by another transaction (version mismatch)"));

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        }

        /**
         * When the account does not exist, the service raises
         * {@link RecordNotFoundException} (per AAP &sect;0.4.2 FILE
         * STATUS 23 →  404 mapping). The
         * {@link GlobalExceptionHandler} surfaces this as HTTP 404.
         */
        @Test
        @DisplayName("PUT — service throws RecordNotFoundException → HTTP 404")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_recordNotFound_returns404() throws Exception {
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);
            when(accountUpdateService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateDto.class)))
                    .thenThrow(new RecordNotFoundException(
                            "Account",
                            "Account not found: acctId=" + ACCOUNT_ID));

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("Account"));
        }

        /**
         * QA report §"Test Results" item #17: wrong Content-Type
         * must be rejected by Spring MVC's content-type negotiator
         * with HTTP 415 UNSUPPORTED_MEDIA_TYPE.
         */
        @Test
        @DisplayName("PUT with text/plain Content-Type → HTTP 415")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_wrongContentType_returns415() throws Exception {
            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.TEXT_PLAIN)
                            .accept(MediaType.APPLICATION_JSON)
                            .content("not json"))
                    .andExpect(status().isUnsupportedMediaType());

            verifyNoInteractions(accountUpdateService);
        }

        /**
         * QA report §"Test Results" items #12-#14: unsupported HTTP
         * methods are rejected by Spring MVC's
         * {@code HttpRequestMethodNotSupportedException} handler
         * with HTTP 405.
         */
        @Test
        @DisplayName("DELETE /api/accounts/{id} → HTTP 405 Method Not Allowed")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void deleteAccount_methodNotAllowed_returns405() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders.delete("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isMethodNotAllowed());

            verifyNoInteractions(accountUpdateService);
            verifyNoInteractions(accountViewService);
        }

        /**
         * Anonymous PUT must be rejected by Spring Security &mdash;
         * the service must NOT be consulted.
         */
        @Test
        @DisplayName("anonymous PUT → 4xx and service not consulted")
        void updateAccount_unauthenticated_isRejected() throws Exception {
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().is4xxClientError());

            verifyNoInteractions(accountUpdateService);
        }

        /**
         * Agent-prompt checklist item:
         * {@code updateAccount_returns400ForMissingVersion}.
         *
         * <p>The optimistic-lock {@code version} field is annotated
         * {@code @NotNull} on {@link AccountUpdateDto} (per AAP
         * &sect;0.6.2 optimistic locking requirement). Submitting a
         * request body with {@code version: null} (in particular,
         * with every other field present and valid) must surface a
         * field-level validation error for {@code version} and
         * return HTTP 400 without ever consulting the service. This
         * isolates the {@code version} constraint from the other 21
         * {@code @NotNull}/{@code @NotBlank} fields that
         * {@code updateAccount_emptyBody_returns400} already
         * exercises in aggregate.</p>
         */
        @Test
        @DisplayName("PUT with version=null → HTTP 400 with version field error")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_missingVersion_returns400() throws Exception {
            // Build the otherwise-valid request, then surgically null
            // out the version field on the JSON wire so the rest of
            // the body still parses cleanly. Records are immutable so
            // we string-replace at the JSON layer rather than build
            // a separate "valid except version" fixture.
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);
            String body = objectMapper.writeValueAsString(request)
                    .replaceFirst("\"version\":0", "\"version\":null");

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath(
                            "$.fieldErrors[?(@.field=='version')]").exists());

            verifyNoInteractions(accountUpdateService);
        }

        /**
         * Agent-prompt checklist item:
         * {@code updateAccount_returns400ForInvalidActiveStatus}.
         *
         * <p>{@code activeStatus} carries the constraint
         * {@code @Pattern(regexp = "^[YN]$")} on
         * {@link AccountUpdateDto} (mirroring the COBOL 88-level
         * {@code FLG-ACCT-STATUS-ISVALID} from
         * {@code COACTUPC.cbl} line 193 which restricts
         * {@code ACCT-ACTIVE-STATUS PIC X(01)} to {@code 'Y'} or
         * {@code 'N'}). Submitting {@code "X"} violates the pattern
         * and must surface as a field-level error returning HTTP
         * 400 without consulting the service.</p>
         */
        @Test
        @DisplayName("PUT with activeStatus='X' → HTTP 400 (violates @Pattern '^[YN]$')")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_invalidActiveStatus_returns400() throws Exception {
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);
            // Replace activeStatus="Y" with activeStatus="X" — violates
            // the @Pattern("^[YN]$") constraint on the DTO record.
            String body = objectMapper.writeValueAsString(request)
                    .replaceFirst("\"activeStatus\":\"Y\"", "\"activeStatus\":\"X\"");

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath(
                            "$.fieldErrors[?(@.field=='activeStatus')]").exists());

            verifyNoInteractions(accountUpdateService);
        }

        /**
         * Agent-prompt checklist item:
         * {@code updateAccount_returns400ForInvalidStateCode}.
         *
         * <p>{@code stateCode} carries the constraint
         * {@code @Pattern(regexp = "^[A-Z]{2}$")} on
         * {@link AccountUpdateDto}. The valid form is a 2-letter
         * uppercase US state / territory code (matching the COBOL
         * {@code CSLKPCDY.cpy} {@code WS-US-STATE-AND-TERRITORIES}
         * lookup table). Submitting a 3-character or lowercase value
         * violates the pattern and must surface as a field-level
         * error returning HTTP 400.</p>
         */
        @Test
        @DisplayName("PUT with stateCode='ny' (lowercase) → HTTP 400 (violates @Pattern '^[A-Z]{2}$')")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_invalidStateCode_returns400() throws Exception {
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);
            // Replace stateCode="NY" with stateCode="ny" — violates
            // the @Pattern("^[A-Z]{2}$") constraint (lowercase rejected).
            String body = objectMapper.writeValueAsString(request)
                    .replaceFirst("\"stateCode\":\"NY\"", "\"stateCode\":\"ny\"");

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath(
                            "$.fieldErrors[?(@.field=='stateCode')]").exists());

            verifyNoInteractions(accountUpdateService);
        }

        /**
         * Agent-prompt checklist item:
         * {@code updateAccount_returns400ForInvalidPhone}.
         *
         * <p>{@code phoneNumber1} carries the constraint
         * {@code @Pattern(regexp = "^\\d{10}$")} on
         * {@link AccountUpdateDto}. Submitting a non-numeric value
         * like {@code "abc1234567"} violates the pattern and must
         * surface as a field-level error returning HTTP 400.</p>
         */
        @Test
        @DisplayName("PUT with phoneNumber1 non-numeric → HTTP 400 (violates @Pattern '^\\d{10}$')")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_invalidPhoneNumber_returns400() throws Exception {
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);
            // Replace phoneNumber1="2125551234" with non-numeric — violates
            // the @Pattern("^\\d{10}$") constraint on the DTO record.
            String body = objectMapper.writeValueAsString(request)
                    .replaceFirst("\"phoneNumber1\":\"2125551234\"",
                            "\"phoneNumber1\":\"abc1234567\"");

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath(
                            "$.fieldErrors[?(@.field=='phoneNumber1')]").exists());

            verifyNoInteractions(accountUpdateService);
        }

        /**
         * Agent-prompt checklist item:
         * {@code updateAccount_returns400ForInvalidZip}.
         *
         * <p>{@code zipCode} carries the constraint
         * {@code @Pattern(regexp = "^\\d{5}(-\\d{4})?$")} on
         * {@link AccountUpdateDto}. Submitting only 3 digits violates
         * the pattern and must surface as a field-level error
         * returning HTTP 400.</p>
         */
        @Test
        @DisplayName("PUT with zipCode='123' (too short) → HTTP 400 (violates @Pattern)")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_invalidZipCode_returns400() throws Exception {
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);
            // Replace zipCode="10001" with "123" — violates the
            // @Pattern("^\\d{5}(-\\d{4})?$") constraint (too short).
            String body = objectMapper.writeValueAsString(request)
                    .replaceFirst("\"zipCode\":\"10001\"", "\"zipCode\":\"123\"");

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath(
                            "$.fieldErrors[?(@.field=='zipCode')]").exists());

            verifyNoInteractions(accountUpdateService);
        }

        /**
         * Agent-prompt checklist item:
         * {@code updateAccount_returns500WhenServiceThrowsRuntime}.
         *
         * <p>When the {@link AccountUpdateService} raises an
         * unexpected {@link RuntimeException} (e.g., transient
         * database connectivity failure that isn't translated by a
         * more specific Spring data-access exception type), the
         * {@link GlobalExceptionHandler}'s last-resort
         * {@code @ExceptionHandler(Exception.class)} translates it to
         * HTTP 500 Internal Server Error with the
         * {@code INTERNAL_ERROR} reason code so the wire-level
         * contract is uniform regardless of the underlying cause.
         * This is the controller-boundary realization of the AAP
         * &sect;0.7.1 mandate that uncaught exceptions surface as
         * 500 with the standardized {@link ApiResponse} envelope.</p>
         */
        @Test
        @DisplayName("PUT — service throws RuntimeException → HTTP 500 INTERNAL_ERROR")
        @WithMockUser(username = "ADMIN001", roles = "ADMIN")
        void updateAccount_serviceRuntimeException_returns500() throws Exception {
            AccountUpdateDto request = buildValidUpdateRequest(ACCOUNT_ID, INITIAL_VERSION);
            when(accountUpdateService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateDto.class)))
                    .thenThrow(new RuntimeException("DB unreachable"));

            mockMvc.perform(put("/api/accounts/{id}", ACCOUNT_ID)
                            .with(org.springframework.security.test.web.servlet.request
                                    .SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isInternalServerError())
                    // The fallback @ExceptionHandler(Exception.class) in
                    // GlobalExceptionHandler emits "INTERNAL_ERROR" as the
                    // reason code (see GlobalExceptionHandler line ~1400).
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

            verify(accountUpdateService).updateAccount(eq(ACCOUNT_ID), any(AccountUpdateDto.class));
        }
    }

    // ==================================================================
    // Test-fixture builders
    // ==================================================================

    /**
     * Builds a deterministic {@link AccountViewDto} for the supplied
     * account ID, populated with values that align with the COBOL
     * record layouts in {@code app/cpy/CVACT01Y.cpy} and
     * {@code app/cpy/CVCUS01Y.cpy}. The values are NOT randomized
     * so that JSON-path assertions can compare equality directly.
     *
     * @param accountId the 11-digit account ID to embed
     * @return a fully-populated {@link AccountViewDto}
     */
    private static AccountViewDto buildAccountView(Long accountId) {
        return new AccountViewDto(
                accountId,
                "Y",
                new BigDecimal("1234.56"),
                new BigDecimal("5000.00"),
                new BigDecimal("1000.00"),
                LocalDate.of(2020, 1, 15),
                LocalDate.of(2030, 1, 15),
                LocalDate.of(2024, 1, 15),
                new BigDecimal("500.00"),
                new BigDecimal("200.00"),
                "12345",
                "DEFAULT",
                CUSTOMER_ID,
                "JOHN",
                "Q",
                "DOE",
                CUSTOMER_SSN,
                "2125551234",
                null,
                "123 MAIN ST",
                null,
                "NEW YORK",
                "NY",
                "USA",
                "10001",
                LocalDate.of(1980, 1, 15),
                "GOV12345",
                "EFT12345",
                "Y",
                720);
    }

    /**
     * Builds a fully-valid {@link AccountUpdateDto} for the supplied
     * account ID and optimistic-lock version. The payload uses:
     * <ul>
     *   <li>SSN {@code 123456789} (part1 = 123 — valid per the COBOL
     *       {@code INVALID-SSN-PART1} rule that rejects 0, 666, and
     *       900–999).</li>
     *   <li>NY state code and 10001 ZIP (a valid combination
     *       per the COBOL {@code CSLKPCDY.cpy} state/ZIP lookup
     *       table).</li>
     *   <li>NANPA-valid phone number {@code 2125551234} (area
     *       code 212 — New York City).</li>
     *   <li>ISO-8601 {@code yyyy-MM-dd} dates for all four date
     *       fields ({@code openDate}, {@code expirationDate},
     *       {@code reissueDate}, {@code dateOfBirth}). These are the
     *       exact strings that the QA Checkpoint 3 Issue #1
     *       reproduction step used, which the pre-fix
     *       {@code AccountUpdateService} would reject at index 4.
     *       Once the service-layer fix passes the correct
     *       {@code "YYYY-MM-DD"} mask, these dates traverse the full
     *       Spring MVC pipeline without rejection.</li>
     *   <li>{@code currentBalance} at the COBOL
     *       {@code PIC S9(10)V99} maximum precision boundary so
     *       deserialization preserves the decimal scale.</li>
     * </ul>
     *
     * @param accountId the account ID to embed in the request body
     * @param version   the optimistic-lock version token from a
     *                  prior GET (may be {@code 0L} for a freshly-
     *                  seeded record)
     * @return a {@link AccountUpdateDto} ready to be serialized
     */
    private static AccountUpdateDto buildValidUpdateRequest(Long accountId, Long version) {
        return new AccountUpdateDto(
                accountId,
                "Y",
                new BigDecimal("1234.56"),     // currentBalance — PIC S9(10)V99
                new BigDecimal("5000.00"),     // creditLimit
                new BigDecimal("1000.00"),     // cashCreditLimit
                LocalDate.of(2020, 1, 15),     // openDate (QA repro)
                LocalDate.of(2030, 1, 15),     // expirationDate (QA repro)
                LocalDate.of(2024, 1, 15),     // reissueDate (QA repro)
                new BigDecimal("500.00"),      // currentCycleCredit
                new BigDecimal("200.00"),      // currentCycleDebit
                "10001",                       // addressZip
                "DEFAULT",                     // accountGroupId
                CUSTOMER_ID,                   // customerId
                "JOHN",                        // firstName
                "Q",                           // middleName
                "DOE",                         // lastName
                CUSTOMER_SSN,                  // SSN (part1=123 — valid)
                "2125551234",                  // phoneNumber1 (NANPA 212)
                null,                          // phoneNumber2 (optional)
                "123 MAIN ST",                 // addressLine1
                null,                          // addressLine2 (optional)
                "NEW YORK",                    // addressLine3 (city)
                "NY",                          // stateCode (matches NY ZIP)
                "USA",                         // countryCode
                "10001",                       // zipCode
                LocalDate.of(1980, 1, 15),     // dateOfBirth (QA repro)
                "GOV12345",                    // governmentIssuedId
                "EFT12345",                    // eftAccountId
                "Y",                           // primaryCardHolderIndicator
                720,                           // ficoCreditScore
                version);                      // optimistic-lock token
    }
}
