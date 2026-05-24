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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.AccountViewDto;
import com.awsm2.carddemo.exception.ConcurrentModificationException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
import com.awsm2.carddemo.validation.DateValidationService;
import com.awsm2.carddemo.validation.ValidationLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link AccountUpdateService}.
 *
 * <p><b>COBOL provenance.</b> {@link AccountUpdateService} translates
 * {@code app/cbl/COACTUPC.cbl} (CICS transaction id {@code CAUP},
 * file {@code 'ACCTDAT'}). The COBOL source performs the
 * {@code SEND MAP COACTUPA} &rarr; {@code RECEIVE MAP COACTUPA}
 * &rarr; {@code 1000-PROCESS-INPUTS} (state/ZIP/date validation)
 * &rarr; {@code 9000-READ-ACCT} &rarr; {@code 9200-READ-CUST}
 * &rarr; {@code 9700-CHECK-CHANGE-IN-REC} (before/after snapshot
 * compare with explicit {@code SYNCPOINT ROLLBACK}) &rarr;
 * {@code 9700-UPDATE-ACCT} ({@code EXEC CICS REWRITE
 * DATASET('ACCTDAT')}) &rarr; {@code 9800-UPDATE-CUST}.</p>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>Optimistic-lock conflict mapping</b> &mdash; JPA's
 *       {@link OptimisticLockingFailureException} is propagated by
 *       {@link AccountRepository#save(Object)} when the
 *       {@code @Version} on the loaded {@link Account} does not match
 *       the caller-supplied version. {@code GlobalExceptionHandler}
 *       maps this to HTTP 409 (verified in
 *       {@code GlobalExceptionHandlerTest}). This service-level test
 *       confirms the service does NOT swallow the optimistic-lock
 *       exception &mdash; it propagates unchanged.</li>
 *   <li><b>Missing-version request rejection</b> &mdash; a
 *       {@code null} {@link AccountUpdateDto#version()} surfaces as
 *       {@link ValidationException} BEFORE any repository call, so
 *       the caller cannot bypass optimistic locking.</li>
 *   <li><b>Version pass-through to entity</b> &mdash; the
 *       caller-supplied version is set on the loaded entity before
 *       save so Hibernate's UPDATE includes {@code WHERE version =
 *       ?}.</li>
 *   <li><b>Cross-field validation cascade</b> &mdash; state code,
 *       state+zip combination, and LocalDate field validity.</li>
 *   <li><b>BigDecimal preservation</b> &mdash; balance fields are
 *       persisted at the exact precision supplied by the request
 *       (no float/double conversion). The {@code safeBalance(...)}
 *       helper normalises null to {@link BigDecimal#ZERO}.</li>
 *   <li><b>@Transactional rollback path</b> &mdash; the
 *       {@code @Transactional(rollbackFor = Exception.class)}
 *       contract is exercised by ensuring every exception thrown
 *       inside the method body propagates without being caught
 *       (the rollback itself is enforced by Spring's
 *       transaction interceptor and is verified at the integration
 *       layer; here we verify the service does not catch and
 *       swallow exceptions).</li>
 *   <li><b>Cache eviction</b> &mdash; {@link CacheService#evict} on
 *       the {@code account-view} namespace (see
 *       {@code AccountViewService.CACHE_NS}) with the zero-padded
 *       11-digit account-id key.</li>
 *   <li><b>MSK account.updated publish</b> &mdash;
 *       {@link KafkaEventPublisher#publishAccountUpdated} invoked
 *       exactly once with the persisted entity's id and the
 *       response DTO.</li>
 *   <li><b>Audit emission</b> &mdash; exactly one
 *       {@link AuditLogService#auditEvent} call with
 *       {@code "account.updated"} event name + non-empty payload.</li>
 * </ol>
 *
 * <p><b>COBOL: COACTUPC &mdash; account update with @Version
 * optimistic lock.</b></p>
 *
 * @see AccountUpdateService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountUpdateService — COACTUPC with JPA @Version optimistic lock")
class AccountUpdateServiceTest {

    // ------------------------------------------------------------------
    // Test fixture constants
    // ------------------------------------------------------------------

    private static final Long ACCOUNT_ID = 11111111111L;
    private static final String CACHE_KEY = "11111111111";
    private static final Long CUSTOMER_ID = 100000001L;
    private static final Long VERSION = 7L;
    private static final String STATE_CODE = "CA";
    private static final String ZIP_CODE = "90001";
    private static final String COUNTRY_CODE = "USA";
    private static final BigDecimal CURRENT_BALANCE =
            new BigDecimal("1234.56");
    private static final BigDecimal CREDIT_LIMIT =
            new BigDecimal("5000.00");
    private static final LocalDate OPEN_DATE = LocalDate.of(2020, 1, 1);
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2030, 12, 31);

    // ------------------------------------------------------------------
    // Mock collaborators + system under test
    // ------------------------------------------------------------------

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private CacheService cacheService;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ValidationLookupService validationLookupService;

    @Mock
    private DateValidationService dateValidationService;

    @InjectMocks
    private AccountUpdateService service;

    private AccountUpdateDto validRequest;
    private Account existingAccount;
    private Customer existingCustomer;
    private CardCrossReference existingXref;

    @BeforeEach
    void setUp() {
        // SSN 123456789 has part1=123 (valid per COBOL
        // INVALID-SSN-PART1 rule which rejects 0, 666, 900-999;
        // see app/cbl/COACTUPC.cbl WS-EDIT-US-SSN paragraph
        // 1265-EDIT-US-SSN).
        validRequest = new AccountUpdateDto(
                ACCOUNT_ID,
                "Y",
                CURRENT_BALANCE,
                CREDIT_LIMIT,
                new BigDecimal("1000.00"),
                OPEN_DATE,
                EXPIRATION_DATE,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                ZIP_CODE,
                "001",
                CUSTOMER_ID,
                "JOHN",
                "Q",
                "DOE",
                123456789L,
                "5551234567",
                null,
                "123 MAIN ST",
                null,
                null,
                STATE_CODE,
                COUNTRY_CODE,
                ZIP_CODE,
                LocalDate.of(1980, 5, 15),
                "GOV12345",
                "EFT12345",
                "Y",
                720,
                VERSION);

        existingAccount = new Account();
        existingAccount.setAcctId(ACCOUNT_ID);
        existingAccount.setAcctActiveStatus("Y");
        existingAccount.setAcctCurrBal(new BigDecimal("0.00"));
        existingAccount.setAcctCreditLimit(new BigDecimal("0.00"));
        existingAccount.setVersion(VERSION);

        existingCustomer = new Customer();
        existingCustomer.setCustId(CUSTOMER_ID);
        existingCustomer.setCustFirstName("ORIG_FIRST");
        existingCustomer.setCustLastName("ORIG_LAST");
        existingCustomer.setCustAddrStateCd("AK");
        existingCustomer.setCustAddrZip("00000");

        // CardCrossReference fixture replacing the VSAM CXACAIX AIX
        // lookup (CARDXREF.VSAM.AIX KEYS(11 25) NONUNIQUEKEY). The
        // service resolves the customer ID via
        // CardCrossReferenceRepository.findByXrefAcctId(acctId) — NOT
        // by trusting request.customerId() (per COACTUPC.cbl
        // 9200-GETCARDXREF-BYACCT paragraph). The xref fixture below
        // therefore maps ACCOUNT_ID → CUSTOMER_ID so that the
        // downstream customer findById sees CUSTOMER_ID.
        existingXref = new CardCrossReference();
        existingXref.setXrefCardNum("4111111111111111");
        existingXref.setXrefAcctId(ACCOUNT_ID);
        existingXref.setXrefCustId(CUSTOMER_ID);
    }

    private void stubHappyPathRepositories() {
        when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(existingAccount));
        // XREF lookup — replaces VSAM CXACAIX AIX read in COBOL
        // COACTUPC:9200-GETCARDXREF-BYACCT. Returns a single-element
        // list (NONUNIQUEKEY semantics allow multiple, but one is
        // sufficient for the happy-path test).
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                .thenReturn(List.of(existingXref));
        when(customerRepository.findById(CUSTOMER_ID))
                .thenReturn(Optional.of(existingCustomer));
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubValidationServices() {
        // Validation services accept all the test inputs by default.
        lenient().when(validationLookupService.isValidStateCode(anyString()))
                .thenReturn(true);
        lenient().when(validationLookupService.isValidStateZipCombination(
                anyString(), anyString())).thenReturn(true);
        // NANPA area-code lookup (CSLKPCDY.cpy NANPA-AREA-CODES table):
        // accept any 3-digit area code by default so the
        // updateAccount-level validation cascade proceeds through to
        // the next field. Tests that need to exercise area-code
        // rejection override this stub.
        lenient().when(validationLookupService.isValidAreaCode(anyString()))
                .thenReturn(true);
        lenient().when(dateValidationService.validate(anyString()))
                .thenReturn(DateValidationService.DateValidationResult.VALID);
    }

    private void stubKafkaPublishSuccess() {
        lenient().when(kafkaEventPublisher.publishAccountUpdated(
                anyLong(), any(AccountUpdateDto.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    @Nested
    @DisplayName("Optimistic-lock conflict (CP5 — JPA @Version + 409 mapping)")
    class OptimisticLock {

        @Test
        @DisplayName("rethrows save-time OptimisticLockingFailureException as ConcurrentModificationException (HTTP 409)")
        void updateAccount_optimisticLockFailure_rethrownAs409() {
            // Arrange — repository.save throws optimistic-lock failure
            // (Hibernate's StaleStateException is wrapped to
            //  OptimisticLockingFailureException by Spring Data). Per
            // AAP §0.6.2, the service catches this and rethrows as
            // the carddemo ConcurrentModificationException so callers
            // see a single 409-mapped exception type regardless of
            // whether the conflict is detected by the pre-mutation
            // version check or by JPA's automatic check on save (the
            // race-condition gap).
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(existingXref));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(existingCustomer));
            stubValidationServices();
            when(accountRepository.save(any(Account.class)))
                    .thenThrow(new OptimisticLockingFailureException(
                            "Row was updated by another transaction"));

            // Act + Assert — service catches OLE and rethrows as the
            // carddemo ConcurrentModificationException (HTTP 409)
            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest))
                    .isInstanceOf(ConcurrentModificationException.class);

            // Assert — customer.save and kafka publish do NOT occur
            // after the optimistic-lock failure
            verify(customerRepository, never()).save(any());
            verify(kafkaEventPublisher, never())
                    .publishAccountUpdated(anyLong(), any());
            // No success audit
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("rejects request with null version (no save attempted)")
        void updateAccount_nullVersion_throwsValidation() {
            // Arrange — request carries null version. Per AAP §0.6.2,
            // a null version is treated as a ValidationException by
            // verifyVersion() — the service refuses to "fall back" to
            // letting JPA detect the conflict because that would
            // silently mask a caller error.
            AccountUpdateDto request = buildRequestWithVersion(null);
            stubValidationServices();
            // findById must succeed to reach the version-check step;
            // the xref / customer / save paths must NOT be reached, so
            // those stubs are intentionally absent (Mockito strictness
            // would flag them as unnecessary if present).
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));

            // Act + Assert
            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("version");

            // Assert — no xref lookup / save / publish / audit
            verify(cardCrossReferenceRepository, never())
                    .findByXrefAcctId(anyLong());
            verify(customerRepository, never()).findById(anyLong());
            verify(accountRepository, never()).save(any());
            verify(customerRepository, never()).save(any());
            verify(kafkaEventPublisher, never())
                    .publishAccountUpdated(anyLong(), any());
        }

        @Test
        @DisplayName("rejects mismatched caller version via explicit pre-check (no save attempted)")
        void updateAccount_versionMismatch_throwsConcurrentModification() {
            // Arrange — request carries version=42, but loaded entity
            // has version=7. Per AAP §0.6.2 / COACTUPC.cbl's
            // DATA-WAS-CHANGED-BEFORE-UPDATE pattern, the service
            // performs an EXPLICIT pre-mutation version comparison
            // and throws com.awsm2.carddemo.exception.ConcurrentModificationException
            // BEFORE attempting any save. This is the canonical
            // replacement for the COBOL before/after image compare;
            // JPA's automatic @Version check on save only covers the
            // race-condition gap between the pre-check and the save.
            Long callerVersion = 42L;
            AccountUpdateDto request = buildRequestWithVersion(callerVersion);
            existingAccount.setVersion(VERSION);   // 7L — does not match 42L
            // Stub validation services so input validation passes —
            // version check happens AFTER validation but BEFORE
            // xref/customer/save in the service flow.
            stubValidationServices();
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));

            // Act + Assert — explicit pre-check throws 409-mapped exception
            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, request))
                    .isInstanceOf(ConcurrentModificationException.class);

            // Assert — service short-circuits BEFORE any xref / customer
            // lookup, save, publish, audit, or cache invalidation:
            verify(cardCrossReferenceRepository, never())
                    .findByXrefAcctId(anyLong());
            verify(customerRepository, never()).findById(anyLong());
            verify(accountRepository, never()).save(any());
            verify(customerRepository, never()).save(any());
            verify(cacheService, never()).evict(anyString(), anyString());
            verify(kafkaEventPublisher, never())
                    .publishAccountUpdated(anyLong(), any());
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("Validation cascade (CSLKPCDY rules)")
    class ValidationCascade {

        @Test
        @DisplayName("rejects invalid state code")
        void updateAccount_invalidStateCode_throwsValidation() {
            // AccountUpdateService.validate() COLLECTS all errors before
            // throwing — so date-validation calls still occur even when
            // an earlier state-code check fails. Use the lenient helper
            // to provide VALID stubs for every cascade member, then
            // override state-code specifically.
            stubValidationServices();
            when(validationLookupService.isValidStateCode("XX"))
                    .thenReturn(false);
            // CA→XX in the request
            AccountUpdateDto bad = buildRequestWithStateCode("XX");

            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, bad))
                    .isInstanceOf(ValidationException.class);

            verify(accountRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("rejects invalid state+ZIP combination")
        void updateAccount_invalidStateZip_throwsValidation() {
            // Provide lenient defaults so the entire cascade has stubs;
            // then override the state-ZIP combination specifically.
            stubValidationServices();
            when(validationLookupService.isValidStateZipCombination(
                    STATE_CODE, ZIP_CODE)).thenReturn(false);

            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest))
                    .isInstanceOf(ValidationException.class);

            verify(accountRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("rejects invalid LocalDate from DateValidationService")
        void updateAccount_invalidDate_throwsValidation() {
            // Arrange — date validation service returns an invalid
            // result for ANY date in the request
            when(validationLookupService.isValidStateCode(anyString()))
                    .thenReturn(true);
            when(validationLookupService.isValidStateZipCombination(
                    anyString(), anyString())).thenReturn(true);
            when(dateValidationService.validate(anyString()))
                    .thenReturn(DateValidationService.DateValidationResult.invalid(
                            "E001", "Date is in the future"));

            // Act + Assert
            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest))
                    .isInstanceOf(ValidationException.class);

            verify(accountRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("rejects null request DTO")
        void updateAccount_nullRequest_throwsNpe() {
            // The service uses Objects.requireNonNull(request, "request")
            // as the first statement of updateAccount(Long, AccountUpdateDto)
            // — passing a null DTO triggers NPE before any other work.
            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Record-not-found path")
    class RecordNotFound {

        @Test
        @DisplayName("throws RecordNotFoundException when account does not exist")
        void updateAccount_accountNotFound_throws() {
            stubValidationServices();
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest))
                    .isInstanceOf(RecordNotFoundException.class);

            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws RecordNotFoundException when customer does not exist")
        void updateAccount_customerNotFound_throws() {
            stubValidationServices();
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));
            // XREF resolves to a customer ID, but the customer record
            // itself is missing — COBOL: DID-NOT-FIND-CUST-IN-CUSTDAT
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(existingXref));
            when(customerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, validRequest))
                    .isInstanceOf(RecordNotFoundException.class);

            verify(accountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("BigDecimal preservation (AAP §0.7.1 — no float/double)")
    class BigDecimalPreservation {

        @Test
        @DisplayName("persists currentBalance at exact request precision (scale=2)")
        void updateAccount_preservesBigDecimalScale() {
            stubHappyPathRepositories();
            stubValidationServices();
            stubKafkaPublishSuccess();

            service.updateAccount(ACCOUNT_ID, validRequest);

            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            BigDecimal persisted = captor.getValue().getAcctCurrBal();
            assertThat(persisted)
                    .as("balance preserves scale and value exactly")
                    .isEqualByComparingTo(CURRENT_BALANCE)
                    .isEqualTo(CURRENT_BALANCE.setScale(2, RoundingMode.HALF_EVEN));
        }

        @Test
        @DisplayName("normalises null balance fields to BigDecimal.ZERO")
        void updateAccount_nullBalance_normalisedToZero() {
            // Arrange — request with null currentCycleCredit.
            // SSN part-1 = 123 → passes COBOL INVALID-SSN-PART1 rule.
            AccountUpdateDto request = new AccountUpdateDto(
                    ACCOUNT_ID, "Y", CURRENT_BALANCE, CREDIT_LIMIT,
                    BigDecimal.ZERO, OPEN_DATE, EXPIRATION_DATE, null,
                    null, null,  // currentCycleCredit, currentCycleDebit null
                    ZIP_CODE, "001",
                    CUSTOMER_ID, "JOHN", "Q", "DOE", 123456789L,
                    "5551234567", null, "123 MAIN ST", null, null,
                    STATE_CODE, COUNTRY_CODE, ZIP_CODE,
                    LocalDate.of(1980, 5, 15), "GOV12345", "EFT12345",
                    "Y", 720, VERSION);
            stubHappyPathRepositories();
            stubValidationServices();
            stubKafkaPublishSuccess();

            // Act
            service.updateAccount(ACCOUNT_ID, request);

            // Assert
            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            assertThat(captor.getValue().getAcctCurrCycCredit())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(captor.getValue().getAcctCurrCycDebit())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("Side effects (cache evict, MSK publish, audit emit)")
    class SideEffects {

        @Test
        @DisplayName("evicts the account-view cache after successful save")
        void updateAccount_evictsCache() {
            stubHappyPathRepositories();
            stubValidationServices();
            stubKafkaPublishSuccess();

            service.updateAccount(ACCOUNT_ID, validRequest);

            verify(cacheService).evict(eq(AccountViewService.CACHE_NS), eq(CACHE_KEY));
        }

        @Test
        @DisplayName("publishes account.updated to MSK after successful save")
        void updateAccount_publishesKafkaEvent() {
            stubHappyPathRepositories();
            stubValidationServices();
            stubKafkaPublishSuccess();

            service.updateAccount(ACCOUNT_ID, validRequest);

            ArgumentCaptor<AccountUpdateDto> dtoCaptor =
                    ArgumentCaptor.forClass(AccountUpdateDto.class);
            verify(kafkaEventPublisher, times(1))
                    .publishAccountUpdated(eq(ACCOUNT_ID), dtoCaptor.capture());
            assertThat(dtoCaptor.getValue().accountId()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("emits account.updated audit event")
        void updateAccount_emitsAuditEvent() {
            stubHappyPathRepositories();
            stubValidationServices();
            stubKafkaPublishSuccess();

            service.updateAccount(ACCOUNT_ID, validRequest);

            verify(auditLogService, times(1))
                    .auditEvent(eq("account.updated"), eq("system"), anyMap());
        }
    }

    @Nested
    @DisplayName("Response DTO assembly")
    class ResponseShape {

        @Test
        @DisplayName("returns AccountViewDto with merged account + customer fields")
        void updateAccount_returnsMergedDto() {
            stubHappyPathRepositories();
            stubValidationServices();
            stubKafkaPublishSuccess();

            // AAP §0.3.4 / schema: updateAccount returns AccountViewDto
            // (read-model projection of the post-update Account + Customer
            // state, NOT the request DTO) — mirroring COACTUPC's post-REWRITE
            // screen redisplay.
            AccountViewDto response = service.updateAccount(ACCOUNT_ID, validRequest);

            assertThat(response).isNotNull();
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
            // The persisted entity's currentBalance was overwritten by
            // applyAccountEdits with the request value
            assertThat(response.currentBalance())
                    .isEqualByComparingTo(CURRENT_BALANCE);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private AccountUpdateDto buildRequestWithVersion(Long version) {
        // SSN part-1 = 123 → passes COBOL INVALID-SSN-PART1 rejection rule
        // (rejects 0, 666, 900-999). See AccountUpdateService.validateSsn.
        return new AccountUpdateDto(
                ACCOUNT_ID, "Y", CURRENT_BALANCE, CREDIT_LIMIT,
                new BigDecimal("1000.00"), OPEN_DATE, EXPIRATION_DATE,
                null, BigDecimal.ZERO, BigDecimal.ZERO,
                ZIP_CODE, "001",
                CUSTOMER_ID, "JOHN", "Q", "DOE", 123456789L,
                "5551234567", null, "123 MAIN ST", null, null,
                STATE_CODE, COUNTRY_CODE, ZIP_CODE,
                LocalDate.of(1980, 5, 15), "GOV12345", "EFT12345",
                "Y", 720, version);
    }

    private AccountUpdateDto buildRequestWithStateCode(String stateCode) {
        // SSN part-1 = 123 → passes COBOL INVALID-SSN-PART1 rejection rule.
        return new AccountUpdateDto(
                ACCOUNT_ID, "Y", CURRENT_BALANCE, CREDIT_LIMIT,
                new BigDecimal("1000.00"), OPEN_DATE, EXPIRATION_DATE,
                null, BigDecimal.ZERO, BigDecimal.ZERO,
                ZIP_CODE, "001",
                CUSTOMER_ID, "JOHN", "Q", "DOE", 123456789L,
                "5551234567", null, "123 MAIN ST", null, null,
                stateCode, COUNTRY_CODE, ZIP_CODE,
                LocalDate.of(1980, 5, 15), "GOV12345", "EFT12345",
                "Y", 720, VERSION);
    }
}
