package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.carddemo.config.SecurityConfig;
import com.carddemo.config.WebConfig;
import com.carddemo.dto.AccountUpdateRequest;
import com.carddemo.dto.AccountUpdateResponse;
import com.carddemo.dto.AccountViewResponse;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.service.AccountUpdateService;
import com.carddemo.service.AccountViewService;
import com.carddemo.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Web-slice test for {@link AccountController} &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.5
 * REST replacement for two legacy CICS/BMS 3270 programs (frozen COBOL source referenced read-only
 * at commit SHA {@code 27d6c6f}): {@code COACTVWC} (transaction {@code CAVW}, account view) and
 * {@code COACTUPC} (transaction {@code CAUP}, the 4,236&nbsp;LOC read-then-rewrite account update).
 *
 * <h2>Harness</h2>
 * <p>The suite runs as a Spring MVC slice ({@link WebMvcTest}) that loads only the web layer for
 * {@link AccountController}, so it is fast and hermetic yet exercises the <em>real</em> HTTP stack:
 * the shared {@link GlobalExceptionHandler @RestControllerAdvice} (auto-detected by the slice) maps
 * domain failures to their HTTP statuses, and the imported {@link SecurityConfig},
 * {@link CorrelationIdFilter} and {@link WebConfig} supply the production security filter chain, the
 * correlation-id filter, and the JSON web contracts &mdash; notably {@code WebConfig}'s
 * {@code WRITE_BIGDECIMAL_AS_PLAIN} customizer, which guarantees every {@code PIC S9(10)V99}
 * monetary field serializes as a plain, scale-2 decimal (for example {@code 1000.00}) rather than
 * scientific notation (AAP &sect;0.8.2, goal&nbsp;G2). The two account services and
 * {@link JwtService} are replaced with Mockito mocks so no database, no AWS, and no real token
 * verification is required.</p>
 *
 * <p>Mockito bean overrides use {@link MockitoBean} &mdash; the non-deprecated Spring&nbsp;Boot
 * 3.4+ replacement for {@code @MockBean} &mdash; so the build stays warning-free under
 * {@code -Xlint:all} (Gate&nbsp;2) while preserving the mandated slice wiring
 * ({@code JwtService} mocked for the {@code SecurityConfig} constructor; real
 * {@link CorrelationIdFilter} imported).</p>
 *
 * <h2>Parity and fidelity invariants asserted</h2>
 * <ul>
 *   <li><strong>Decimal fidelity (AAP &sect;0.8.2, Gate&nbsp;5).</strong> The five account money
 *       fields ({@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT},
 *       {@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT}, all {@code PIC S9(10)V99}) are
 *       asserted to serialize with exactly two decimal places and never in float/scientific form,
 *       on both the view and the update responses.</li>
 *   <li><strong>Optimistic-lock parity (AAP &sect;0.8.4).</strong> {@code COACTUPC}'s
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} check (88-level at {@code COACTUPC} L521-522)
 *       surfaces as HTTP&nbsp;409 with the byte-exact message
 *       {@code "Record changed by some one else. Please review"}.</li>
 *   <li><strong>Not-found / validation.</strong> An unknown account yields HTTP&nbsp;404; a
 *       non-numeric path id and a body violating a Bean-Validation edit yield HTTP&nbsp;400 (the
 *       latter as {@code VALIDATION_ERROR} with per-field detail, before any service is invoked).</li>
 *   <li><strong>Authentication &amp; observability.</strong> {@code /api/accounts/**} requires an
 *       authenticated caller (any role); anonymous access yields HTTP&nbsp;401, and every error body
 *       carries the MDC {@code correlationId} plus the {@code X-Correlation-Id} response header.</li>
 * </ul>
 */
@WebMvcTest(controllers = AccountController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class, WebConfig.class})
@DisplayName("AccountController — account view (CAVW) + update (CAUP) web slice")
class AccountControllerTest {

    /** Base path for the migrated account endpoints. */
    private static final String ACCOUNTS_PATH = "/api/accounts";

    /** The numeric account key ({@code ACCT-ID PIC 9(11)}) used in the request path and service call. */
    private static final long ACCOUNT_ID = 123L;

    /** The eleven-digit, leading-zero-preserving account id carried by the response DTO. */
    private static final String ACCOUNT_ID_TEXT = "00000000123";

    /** Success confirmation message expected on a 200 update ({@code COACTUP} INFOMSG equivalent). */
    private static final String SUCCESS_MESSAGE = AccountUpdateResponse.SUCCESS_MESSAGE;

    /** The byte-exact optimistic-lock conflict message (COACTUPC L521-522 / COCRDUPC L208). */
    private static final String CONFLICT_MESSAGE = "Record changed by some one else. Please review";

    /**
     * Regex proving no money field serializes in float/scientific notation. It matches any of the
     * five money keys followed by a value (up to the next delimiter) that contains an {@code e}/{@code E}
     * exponent marker; its <em>absence</em> from a payload is the decimal-fidelity guarantee. It is
     * deliberately scoped to the money keys so unrelated fields (for example {@code eftAccountId})
     * cannot trip it.
     */
    private static final String MONEY_SCIENTIFIC_PATTERN =
            "\"(currentBalance|creditLimit|cashCreditLimit|currentCycleCredit|currentCycleDebit)\""
                    + ":[^,}\\]]*[eE]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountViewService accountViewService;

    @MockitoBean
    private AccountUpdateService accountUpdateService;

    @MockitoBean
    private JwtService jwtService;

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a representative flattened account/customer view with distinct scale-2 money values so
     * that each field's serialization can be asserted independently. The {@link AccountViewResponse}
     * canonical constructor normalizes every money value to scale&nbsp;2 and masks the SSN, so the
     * fixture never exposes a full nine-digit {@code CUST-SSN}.
     *
     * @param version the optimistic-lock {@code @Version} token to echo (view=7, post-update=8)
     * @return a fully populated {@link AccountViewResponse}
     */
    private AccountViewResponse sampleViewResponse(final long version) {
        return new AccountViewResponse(
                ACCOUNT_ID_TEXT,               // accountId
                "Y",                           // activeStatus
                new BigDecimal("1000.00"),     // currentBalance
                new BigDecimal("5000.00"),     // creditLimit
                new BigDecimal("2500.00"),     // cashCreditLimit
                new BigDecimal("750.50"),      // currentCycleCredit
                new BigDecimal("123.45"),      // currentCycleDebit
                LocalDate.of(2020, 1, 15),     // openDate
                LocalDate.of(2027, 5, 31),     // expirationDate
                LocalDate.of(2024, 6, 1),      // reissueDate
                "GROUP001",                    // accountGroupId
                version,                       // version (Long)
                "000000456",                   // customerId
                "JOHN",                        // firstName
                "Q",                           // middleName
                "PUBLIC",                      // lastName
                "123 MAIN ST",                 // addressLine1
                "APT 4",                       // addressLine2
                "ANYTOWN",                     // city
                "NY",                          // stateCode
                "USA",                         // countryCode
                "12345",                       // zipCode
                "5551234567",                  // phoneNumber1
                "5559876543",                  // phoneNumber2
                "123456789",                   // ssn (masked to *****6789 by the canonical ctor)
                "GOVTID0001",                 // govtIssuedId
                LocalDate.of(1985, 3, 20),     // dateOfBirth
                "EFT0001",                     // eftAccountId
                "Y",                           // primaryCardHolderIndicator
                700);                          // ficoScore (Integer)
    }

    /**
     * Builds an account update request that is fully valid except for the supplied
     * {@code currentBalance}. All ids are digits-only, flags are {@code Y}/{@code N}, dates are
     * in the past, and the FICO score is within 300&ndash;850, so passing a scale-2 balance yields a
     * request that satisfies every Bean-Validation constraint; passing a 3-fraction-digit balance
     * violates only the {@code @Digits(integer = 10, fraction = 2)} money edit.
     *
     * @param currentBalance the balance to place in the request (drives valid vs. invalid cases)
     * @return an {@link AccountUpdateRequest} for the PUT endpoint
     */
    private AccountUpdateRequest updateRequest(final BigDecimal currentBalance) {
        return new AccountUpdateRequest(
                ACCOUNT_ID_TEXT,               // accountId (@NotBlank @Pattern \d{1,11})
                "Y",                           // activeStatus (@Pattern [YN])
                currentBalance,                // currentBalance (@Digits 10,2) — parameterized
                new BigDecimal("5000.00"),     // creditLimit
                new BigDecimal("2500.00"),     // cashCreditLimit
                new BigDecimal("750.50"),      // currentCycleCredit
                new BigDecimal("123.45"),      // currentCycleDebit
                LocalDate.of(2020, 1, 15),     // openDate (@PastOrPresent)
                LocalDate.of(2027, 5, 31),     // expirationDate
                LocalDate.of(2024, 6, 1),      // reissueDate
                "GROUP001",                    // accountGroupId
                7L,                            // version
                "000000456",                   // customerId (@Pattern \d{1,9})
                "JOHN",                        // firstName (@NotBlank)
                "Q",                           // middleName
                "PUBLIC",                      // lastName (@NotBlank)
                "123 MAIN ST",                 // addressLine1
                "APT 4",                       // addressLine2
                "ANYTOWN",                     // city
                "NY",                          // stateCode
                "USA",                         // countryCode
                "12345",                       // zipCode (@Pattern \d{0,10})
                "5551234567",                  // phoneNumber1
                "5559876543",                  // phoneNumber2
                "123456789",                   // ssn (@Pattern \d{9})
                "GOVTID0001",                 // govtIssuedId
                LocalDate.of(1985, 3, 20),     // dateOfBirth (@Past)
                "EFT0001",                     // eftAccountId
                "Y",                           // primaryCardHolderIndicator (@Pattern [YN])
                700);                          // ficoScore (@Min 300 @Max 850)
    }

    // ---------------------------------------------------------------------------------------------
    // GET /api/accounts/{accountId} — account view (COACTVWC / CAVW)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/accounts/{accountId} — account view (COACTVWC / CAVW)")
    class ViewAccount {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("authenticated -> 200 with flattened view and every money field at scale 2 (plain)")
        void viewReturns200WithScaleTwoMoney() throws Exception {
            when(accountViewService.viewAccount(ACCOUNT_ID)).thenReturn(sampleViewResponse(7L));

            final String body = mockMvc.perform(get(ACCOUNTS_PATH + "/{accountId}", ACCOUNT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID_TEXT))
                    .andExpect(jsonPath("$.version").value(7))
                    // Money serialized as a plain scale-2 decimal (WebConfig WRITE_BIGDECIMAL_AS_PLAIN).
                    .andExpect(jsonPath("$.currentBalance").value(1000.00))
                    .andExpect(jsonPath("$.creditLimit").value(5000.00))
                    // SSN is masked to its last four digits by the DTO canonical constructor.
                    .andExpect(jsonPath("$.ssn").value("*****6789"))
                    .andReturn().getResponse().getContentAsString();

            // Deterministic wire-form check: exact scale-2 plain rendering for every money field.
            assertThat(body).contains(
                    "\"currentBalance\":1000.00",
                    "\"creditLimit\":5000.00",
                    "\"cashCreditLimit\":2500.00",
                    "\"currentCycleCredit\":750.50",
                    "\"currentCycleDebit\":123.45");
            // No full nine-digit SSN ever leaks onto the wire.
            assertThat(body).doesNotContain("123456789");

            verify(accountViewService).viewAccount(ACCOUNT_ID);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("unknown account -> 404 (service raises ResourceNotFoundException)")
        void viewUnknownReturns404() throws Exception {
            when(accountViewService.viewAccount(ACCOUNT_ID))
                    .thenThrow(new ResourceNotFoundException("Account not found for id: " + ACCOUNT_ID));

            mockMvc.perform(get(ACCOUNTS_PATH + "/{accountId}", ACCOUNT_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));

            verify(accountViewService).viewAccount(ACCOUNT_ID);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("non-numeric path id -> 400 (Long type mismatch), service never called")
        void viewNonNumericIdReturns400() throws Exception {
            // "abc" cannot bind to the Long path variable -> MethodArgumentTypeMismatchException -> 400.
            // The exact domain code is tolerated (framework type-mismatch, not a VALIDATION_ERROR).
            mockMvc.perform(get(ACCOUNTS_PATH + "/{accountId}", "abc"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(accountViewService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("decimal fidelity: no money field serializes in scientific notation on the view")
        void viewMoneyNeverScientific() throws Exception {
            when(accountViewService.viewAccount(ACCOUNT_ID)).thenReturn(sampleViewResponse(7L));

            final String body = mockMvc.perform(get(ACCOUNTS_PATH + "/{accountId}", ACCOUNT_ID))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // Every money field is present with exactly two decimals ...
            assertThat(body).containsPattern("\"currentBalance\":\\d+\\.\\d{2}(?!\\d)");
            assertThat(body).containsPattern("\"creditLimit\":\\d+\\.\\d{2}(?!\\d)");
            assertThat(body).containsPattern("\"cashCreditLimit\":\\d+\\.\\d{2}(?!\\d)");
            assertThat(body).containsPattern("\"currentCycleCredit\":\\d+\\.\\d{2}(?!\\d)");
            assertThat(body).containsPattern("\"currentCycleDebit\":\\d+\\.\\d{2}(?!\\d)");
            // ... and none is rendered with a float/scientific exponent.
            assertThat(body).doesNotContainPattern(MONEY_SCIENTIFIC_PATTERN);
        }

        @Test
        @WithAnonymousUser
        @DisplayName("anonymous -> 401 with generic body carrying the correlationId; service never called")
        void viewAnonymousReturns401() throws Exception {
            mockMvc.perform(get(ACCOUNTS_PATH + "/{accountId}", ACCOUNT_ID))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Authentication required"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());

            verifyNoInteractions(accountViewService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("error body carries the correlationId and the X-Correlation-Id response header")
        void viewErrorBodyCarriesCorrelationId() throws Exception {
            when(accountViewService.viewAccount(ACCOUNT_ID))
                    .thenThrow(new ResourceNotFoundException("Account not found for id: " + ACCOUNT_ID));

            mockMvc.perform(get(ACCOUNTS_PATH + "/{accountId}", ACCOUNT_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .andExpect(jsonPath("$.correlationId").isString())
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PUT /api/accounts/{accountId} — account update (COACTUPC / CAUP)
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("PUT /api/accounts/{accountId} — account update (COACTUPC / CAUP)")
    class UpdateAccount {

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("valid body -> 200 with confirmation, new version, and scale-2 money on the refreshed snapshot")
        void updateValidReturns200WithScaleTwoMoney() throws Exception {
            final AccountUpdateResponse response = AccountUpdateResponse.updated(sampleViewResponse(8L));
            when(accountUpdateService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateRequest.class)))
                    .thenReturn(response);

            final String body = mockMvc.perform(put(ACCOUNTS_PATH + "/{accountId}", ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest(new BigDecimal("1000.00")))))
                    .andExpect(status().isOk())
                    // COACTUP INFOMSG success equivalent + the post-commit optimistic-lock version.
                    .andExpect(jsonPath("$.message").value(SUCCESS_MESSAGE))
                    .andExpect(jsonPath("$.version").value(8))
                    // The refreshed snapshot is nested under "account"; money stays at scale 2 (plain).
                    .andExpect(jsonPath("$.account.accountId").value(ACCOUNT_ID_TEXT))
                    .andExpect(jsonPath("$.account.version").value(8))
                    .andExpect(jsonPath("$.account.currentBalance").value(1000.00))
                    .andExpect(jsonPath("$.account.creditLimit").value(5000.00))
                    .andReturn().getResponse().getContentAsString();

            // Deterministic wire-form check: exact scale-2 plain rendering for every money field.
            assertThat(body).contains(
                    "\"currentBalance\":1000.00",
                    "\"creditLimit\":5000.00",
                    "\"cashCreditLimit\":2500.00",
                    "\"currentCycleCredit\":750.50",
                    "\"currentCycleDebit\":123.45");

            verify(accountUpdateService).updateAccount(eq(ACCOUNT_ID), any(AccountUpdateRequest.class));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("optimistic-lock conflict -> 409 with the byte-exact 'record changed' message (COACTUPC L521-522)")
        void updateOptimisticConflictReturns409ExactMessage() throws Exception {
            // No-arg constructor -> OptimisticLockConflictException.DEFAULT_MESSAGE (byte-for-byte parity).
            when(accountUpdateService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateRequest.class)))
                    .thenThrow(new OptimisticLockConflictException());

            mockMvc.perform(put(ACCOUNTS_PATH + "/{accountId}", ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest(new BigDecimal("1000.00")))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(CONFLICT_MESSAGE))
                    .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("unknown account -> 404 (service raises ResourceNotFoundException)")
        void updateUnknownReturns404() throws Exception {
            when(accountUpdateService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateRequest.class)))
                    .thenThrow(new ResourceNotFoundException("Account not found for id: " + ACCOUNT_ID));

            mockMvc.perform(put(ACCOUNTS_PATH + "/{accountId}", ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest(new BigDecimal("1000.00")))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("invalid body (>2 fraction digits) -> 400 VALIDATION_ERROR with fieldErrors, service never called")
        void updateInvalidBodyReturns400WithFieldErrors() throws Exception {
            // currentBalance "1000.001" has three fraction digits, violating @Digits(integer=10, fraction=2)
            // (the migrated PIC S9(10)V99 money edit). Validation fails before the service is invoked.
            mockMvc.perform(put(ACCOUNTS_PATH + "/{accountId}", ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest(new BigDecimal("1000.001")))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors").isNotEmpty())
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());

            verifyNoInteractions(accountUpdateService);
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("decimal fidelity: no money field serializes in scientific notation on the update response")
        void updateMoneyNeverScientific() throws Exception {
            final AccountUpdateResponse response = AccountUpdateResponse.updated(sampleViewResponse(8L));
            when(accountUpdateService.updateAccount(eq(ACCOUNT_ID), any(AccountUpdateRequest.class)))
                    .thenReturn(response);

            final String body = mockMvc.perform(put(ACCOUNTS_PATH + "/{accountId}", ACCOUNT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRequest(new BigDecimal("1000.00")))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // Every money field on the nested snapshot is present at exactly two decimals ...
            assertThat(body).containsPattern("\"currentBalance\":\\d+\\.\\d{2}(?!\\d)");
            assertThat(body).containsPattern("\"creditLimit\":\\d+\\.\\d{2}(?!\\d)");
            assertThat(body).containsPattern("\"cashCreditLimit\":\\d+\\.\\d{2}(?!\\d)");
            assertThat(body).containsPattern("\"currentCycleCredit\":\\d+\\.\\d{2}(?!\\d)");
            assertThat(body).containsPattern("\"currentCycleDebit\":\\d+\\.\\d{2}(?!\\d)");
            // ... and none is rendered with a float/scientific exponent.
            assertThat(body).doesNotContainPattern(MONEY_SCIENTIFIC_PATTERN);
        }
    }
}
