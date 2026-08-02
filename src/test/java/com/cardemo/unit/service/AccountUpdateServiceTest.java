package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountUpdateRequest;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.service.shared.ValidationLookupService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link AccountUpdateService}, the Java replacement for COBOL CICS program
 * {@code app/cbl/COACTUPC.cbl} (transaction {@code CAUP}) at anchor commit {@code 7756d89}.
 *
 * <p>Every assertion here is derived from the COBOL source rather than from the Java
 * implementation, so the suite is a parity guard rather than a change detector. The behaviours
 * pinned are, in source order:</p>
 * <ul>
 *   <li>the four-wide valid-AID set and the silent coercion to {@code ENTER} at {@code :905-916},
 *       and the {@code PF13-24} folding of {@code app/cpy/CSSTRPFY.cpy};</li>
 *   <li>the exit-to-menu transfer at {@code :927-959}, including the {@code SYNCPOINT} commit at
 *       {@code :952-954} that is a documented no-op substitution;</li>
 *   <li>the load-bearing branch order of {@code 2000-DECIDE-ACTION} at {@code :2562-2643} and its
 *       {@code WHEN OTHER} abend carrying {@code ABEND-CODE '0001'} at {@code :2633-2640};</li>
 *   <li><strong>BLOCKER 5.2</strong> - the four-{@code WHEN} post-write classification at
 *       {@code :2606-2615} in which {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} is never tested, so a
 *       customer lock failure is reported as top-level success with nothing written;</li>
 *   <li>the seven ordered steps of {@code 9600-WRITE-PROCESSING} at {@code :3888-4105}, the two
 *       distinct lock-failure outcomes, and the asymmetric rollback of {@code :4079-4080} versus
 *       {@code :4098-4102} reproduced by one transaction boundary;</li>
 *   <li>the change detection of {@code 9700-CHECK-CHANGE-IN-REC} at {@code :4109-4193} - sixteen
 *       account clauses over ten fields, nineteen customer clauses over seventeen - including the
 *       lower-case, upper-case and no-case asymmetry and the DOB offset asymmetry of
 *       {@code :4174-4179};</li>
 *   <li>the tri-state field-error semantics of {@code app/cpy/CSSETATY.cpy}, where the {@code '*'}
 *       marker is emitted for {@code BLANK} only and never for {@code NOT_OK};</li>
 *   <li>the delegation of the fourteen {@code app/cpy/CSUTLDPY.cpy} date-editing labels to
 *       {@code DateValidationService} rather than re-mapping them here;</li>
 *   <li>the byte-exact outcome literals of {@code :513-528}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccountUpdateService: COACTUPC (CAUP) account update - dual-dataset write")
final class AccountUpdateServiceTest {

    private static final String ACCOUNT_KEY_TEXT = "00000000001";
    private static final String CUSTOMER_KEY_TEXT = "000000001";
    private static final String AID_ENTER = "DFHENTER";
    private static final String AID_PFK03 = "DFHPF3";
    private static final String AID_PFK05 = "DFHPF5";
    private static final String AID_PFK12 = "DFHPF12";
    /** PF17 folds back onto PFK05 in app/cpy/CSSTRPFY.cpy. */
    private static final String AID_PFK17 = "DFHPF17";
    private static final String LIVE_DOB = "1980-01-15";

    @Mock private CardCrossReferenceRepository crossReferenceRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private FileStatusMapper fileStatusMapper;
    @Mock private DateValidationService dateValidationService;
    @Mock private ValidationLookupService validationLookupService;

    private AccountUpdateService service;

    @BeforeEach
    void setUp() {
        this.service = new AccountUpdateService(this.crossReferenceRepository,
                this.accountRepository,
                this.customerRepository,
                this.fileStatusMapper,
                this.dateValidationService,
                this.validationLookupService,
                Clock.fixed(Instant.parse("2026-08-02T10:15:30Z"), ZoneOffset.UTC));
        when(this.fileStatusMapper.toException(any(), any(), any())).thenReturn(Optional.empty());
        when(this.fileStatusMapper.toException(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------ fixtures

    private static Account liveAccount() {
        final Map<String, Object> values = new HashMap<>();
        values.put("accountId", Long.valueOf(1L));
        values.put("activeStatus", "Y");
        values.put("currentBalance", new BigDecimal("194.00"));
        values.put("creditLimit", new BigDecimal("2020.00"));
        values.put("cashCreditLimit", new BigDecimal("1020.00"));
        values.put("currentCycleCredit", new BigDecimal("0.00"));
        values.put("currentCycleDebit", new BigDecimal("0.00"));
        values.put("openDate", "2000-01-01");
        values.put("expiraionDate", "2025-12-31");
        values.put("reissueDate", "2020-06-15");
        values.put("groupId", "ZEROBAL");
        values.put("addressZip", "98101");
        return build(Account.class, values);
    }

    private static Customer liveCustomer() {
        final Map<String, Object> values = new HashMap<>();
        values.put("customerId", Long.valueOf(1L));
        values.put("firstName", "MARGARET");
        values.put("middleName", "A");
        values.put("lastName", "GOLD");
        values.put("addressLine1", "100 MAIN ST");
        values.put("addressLine2", "APT 1");
        values.put("addressLine3", "SEATTLE");
        values.put("addressStateCode", "WA");
        values.put("addressCountryCode", "USA");
        values.put("addressZip", "98101");
        values.put("phoneNumber1", "(206)555-0100");
        values.put("phoneNumber2", "(425)555-0199");
        values.put("ssn", "123456789");
        values.put("governmentIssuedId", "WA-DL-9988776");
        values.put("dateOfBirth", LIVE_DOB);
        values.put("eftAccountId", "eft-0001");
        values.put("primaryCardHolderIndicator", "Y");
        values.put("ficoCreditScore", "750");
        return build(Customer.class, values);
    }

    /** The snapshot that MATCHES the live pair, exercising the lower/upper/no-case asymmetry. */
    private static Map<String, Object> matchingSnapshot() {
        final Map<String, Object> values = new HashMap<>();
        values.put("accountId", ACCOUNT_KEY_TEXT);
        values.put("activeStatus", "Y");
        // PIC S9(10)V99 zoned images with a trailing overpunch sign; '{' is +0
        values.put("currentBalance", "00000001940{");
        values.put("creditLimit", "00000020200{");
        values.put("cashCreditLimit", "00000010200{");
        values.put("currentCycleCredit", "00000000000{");
        values.put("currentCycleDebit", "00000000000{");
        // compact, separator-free eight-character dates
        values.put("openDate", "20000101");
        values.put("expiraionDate", "20251231");
        values.put("reissueDate", "20200615");
        // LOWER-CASE on both sides at :4139-4140, so case must not matter
        values.put("groupId", "zerobal");
        values.put("customerId", CUSTOMER_KEY_TEXT);
        // UPPER-CASE on both sides for these nine, so case must not matter
        values.put("firstName", "margaret");
        values.put("middleName", "a");
        values.put("lastName", "gold");
        values.put("addressLine1", "100 main st");
        values.put("addressLine2", "apt 1");
        values.put("addressLine3", "seattle");
        values.put("addressStateCode", "wa");
        values.put("addressCountryCode", "usa");
        values.put("governmentIssuedId", "wa-dl-9988776");
        // no case function at all for these, so they must match byte for byte
        values.put("addressZip", "98101");
        values.put("phoneNumber1", "(206)555-0100");
        values.put("phoneNumber2", "(425)555-0199");
        values.put("ssn", "123456789");
        values.put("eftAccountId", "eft-0001");
        values.put("primaryCardHolderIndicator", "Y");
        values.put("dateOfBirth", "19800115");
        values.put("ficoScore", "750");
        return values;
    }

    private static Map<String, Object> screenFields() {
        final Map<String, Object> values = new HashMap<>();
        values.put("accountId", ACCOUNT_KEY_TEXT);
        values.put("accountStatus", "N");
        values.put("creditLimit", "2020.00");
        values.put("cashCreditLimit", "1020.00");
        values.put("currentBalance", "194.00");
        values.put("currentCycleCredit", "0.00");
        values.put("currentCycleDebit", "0.00");
        values.put("openDateYear", "2000");
        values.put("openDateMonth", "01");
        values.put("openDateDay", "01");
        values.put("expiryDateYear", "2025");
        values.put("expiryDateMonth", "12");
        values.put("expiryDateDay", "31");
        values.put("reissueDateYear", "2020");
        values.put("reissueDateMonth", "06");
        values.put("reissueDateDay", "15");
        values.put("accountGroupId", "ZEROBAL");
        values.put("customerId", CUSTOMER_KEY_TEXT);
        values.put("customerSsnPart1", "123");
        values.put("customerSsnPart2", "45");
        values.put("customerSsnPart3", "6789");
        values.put("dateOfBirthYear", "1980");
        values.put("dateOfBirthMonth", "01");
        values.put("dateOfBirthDay", "15");
        values.put("customerFicoScore", "750");
        values.put("customerFirstName", "MARGARET");
        values.put("customerMiddleName", "A");
        values.put("customerLastName", "GOLD");
        values.put("addressLine1", "100 MAIN ST");
        values.put("addressLine2", "APT 1");
        values.put("addressCity", "SEATTLE");
        values.put("addressStateCode", "WA");
        values.put("addressCountryCode", "USA");
        values.put("addressZip", "98101");
        values.put("phone1AreaCode", "206");
        values.put("phone1Prefix", "555");
        values.put("phone1LineNumber", "0100");
        values.put("phone2AreaCode", "425");
        values.put("phone2Prefix", "555");
        values.put("phone2LineNumber", "0199");
        values.put("governmentIssuedId", "WA-DL-9988776");
        values.put("eftAccountId", "eft-0001");
        values.put("primaryCardHolderIndicator", "Y");
        return values;
    }

    private static AccountUpdateRequest requestWith(final Map<String, Object> snapshotOverrides) {
        final Map<String, Object> snapshot = matchingSnapshot();
        snapshot.putAll(snapshotOverrides);
        final Map<String, Object> top = screenFields();
        top.put("oldDetails", build(AccountUpdateRequest.OldDetails.class, snapshot));
        return build(AccountUpdateRequest.class, top);
    }

    private static <T> T build(final Class<T> type, final Map<String, Object> values) {
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        for (final Constructor<?> candidate : type.getDeclaredConstructors()) {
            if (candidate.getParameterCount() > constructor.getParameterCount()) {
                constructor = candidate;
            }
        }
        final Parameter[] parameters = constructor.getParameters();
        final Object[] arguments = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            arguments[index] = values.get(parameters[index].getName());
        }
        try {
            constructor.setAccessible(true);
            return type.cast(constructor.newInstance(arguments));
        } catch (final ReflectiveOperationException cause) {
            throw new AssertionError("cannot build " + type.getSimpleName(), cause);
        }
    }

    private void happyReads() {
        when(this.accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(liveAccount()));
        when(this.customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(liveCustomer()));
    }

    /** Models the real FileStatusMapper, which maps a not-found RESP to RecordNotFoundException. */
    private void notFoundMapping() {
        when(this.fileStatusMapper.toException(any(), any(), any()))
                .thenReturn(Optional.of(new RecordNotFoundException("not found")));
        when(this.fileStatusMapper.toException(any(), any(), any(), any()))
                .thenReturn(Optional.of(new RecordNotFoundException("not found")));
    }

    /** Stubs the two collaborators the 1200-series edit routines consult, all outcomes valid. */
    private void happyEdits() {
        final DateValidationService.EditOutcome valid = new DateValidationService.EditOutcome(
                DateValidationService.EditFlag.ISVALID,
                DateValidationService.EditFlag.ISVALID,
                DateValidationService.EditFlag.ISVALID,
                false,
                "");
        when(this.dateValidationService.editDate(any(), any())).thenReturn(valid);
        when(this.dateValidationService.editDateOfBirth(any(), any())).thenReturn(valid);
        when(this.validationLookupService.isValidPhoneAreaCode(any())).thenReturn(true);
        when(this.validationLookupService.isValidGeneralPurposeAreaCode(any())).thenReturn(true);
        when(this.validationLookupService.isValidEasilyRecognisableAreaCode(any())).thenReturn(true);
        when(this.validationLookupService.isValidUsStateCode(any())).thenReturn(true);
        when(this.validationLookupService.isValidStateZipCodeCombination(any())).thenReturn(true);
        when(this.validationLookupService.isValidStateAndZipCode(any(), any())).thenReturn(true);
    }

    /** The ENTER turn: 1000-PROCESS-INPUTS runs, so 1200-EDIT-MAP-INPUTS is NOT skipped. */
    private AccountUpdateService.AccountUpdateResult submit(final AccountUpdateRequest request) {
        return this.service.processRequest(request,
                AID_ENTER,
                AccountUpdateService.ChangeAction.SHOW_DETAILS,
                AccountUpdateService.EntryMode.REENTER);
    }

    /** A request whose top-level screen fields carry the supplied overrides. */
    private static AccountUpdateRequest screenWith(final Map<String, Object> screenOverrides) {
        final Map<String, Object> top = screenFields();
        top.putAll(screenOverrides);
        top.put("oldDetails", build(AccountUpdateRequest.OldDetails.class, matchingSnapshot()));
        return build(AccountUpdateRequest.class, top);
    }

    private static boolean hasAspect(final AccountUpdateService.AccountUpdateResult result,
                                     final String aspect,
                                     final String value) {
        return result.fieldAttributes().stream()
                .anyMatch(attribute -> aspect.equals(attribute.aspect())
                        && value.equals(attribute.value()));
    }

    private AccountUpdateService.AccountUpdateResult confirm(final AccountUpdateRequest request) {
        return this.service.processRequest(request,
                AID_PFK05,
                AccountUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                AccountUpdateService.EntryMode.REENTER);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("9600 seven steps run in order and both writes commit")
    void writeSucceedsAndOrdersTheFourIoCalls() {
        happyReads();
        final AccountUpdateService.AccountUpdateResult result = confirm(requestWith(Map.of()));
        assertThat(result.changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE);
        final InOrder order = inOrder(this.accountRepository, this.customerRepository);
        order.verify(this.accountRepository).findByIdForUpdate(1L);
        order.verify(this.customerRepository).findByIdForUpdate(1L);
        order.verify(this.accountRepository).save(any(Account.class));
        order.verify(this.customerRepository).save(any(Customer.class));
    }

    @Test
    @DisplayName("BLOCKER 5.2 - customer lock failure is reported as SUCCESS with nothing written")
    void customerLockFailureIsReportedAsSuccess() {
        when(this.accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(liveAccount()));
        when(this.customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        final AccountUpdateService.AccountUpdateResult result = confirm(requestWith(Map.of()));
        assertThat(result.changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE);
        verify(this.accountRepository, never()).save(any(Account.class));
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("account lock failure is a DISTINCT outcome from the customer one")
    void accountLockFailureIsLockError() {
        when(this.accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        final AccountUpdateService.AccountUpdateResult result = confirm(requestWith(Map.of()));
        assertThat(result.changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR);
        verify(this.customerRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("account rewrite failure never reaches the customer write and issues no rollback")
    void accountRewriteFailureStopsBeforeTheCustomerWrite() {
        happyReads();
        when(this.accountRepository.save(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("acct"));
        final AccountUpdateService.AccountUpdateResult result = confirm(requestWith(Map.of()));
        assertThat(result.changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED);
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("customer rewrite failure reports failed; the single boundary performs the backout")
    void customerRewriteFailureReportsFailed() {
        happyReads();
        when(this.customerRepository.save(any(Customer.class)))
                .thenThrow(new DataIntegrityViolationException("cust"));
        final AccountUpdateService.AccountUpdateResult result = confirm(requestWith(Map.of()));
        assertThat(result.changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED);
        verify(this.accountRepository).save(any(Account.class));
    }

    @Test
    @DisplayName("9700 account ELSE branch names ACCTDAT and abandons the write")
    void accountChangeDetectedNamesAcctdat() {
        happyReads();
        assertThatThrownBy(() -> this.service.updateAccount(
                        requestWith(Map.of("activeStatus", "N"))))
                .isInstanceOf(ConcurrentUpdateException.class)
                .extracting(failure -> ((ConcurrentUpdateException) failure).getAffectedRecord())
                .isEqualTo("ACCTDAT");
        verify(this.accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("9700 customer ELSE branch names CUSTDAT")
    void customerChangeDetectedNamesCustdat() {
        happyReads();
        assertThatThrownBy(() -> this.service.updateAccount(
                        requestWith(Map.of("lastName", "SILVER"))))
                .isInstanceOf(ConcurrentUpdateException.class)
                .extracting(failure -> ((ConcurrentUpdateException) failure).getAffectedRecord())
                .isEqualTo("CUSTDAT");
    }

    @Test
    @DisplayName("DOB offset asymmetry - snapshot 1/5/7 against live 1/6/9 (:4174-4179)")
    void dobOffsetAsymmetryIsReproduced() {
        happyReads();
        // (a) The live record holds "1980-01-15" and is sliced (1:4)/(6:2)/(9:2); the compact
        //     snapshot holds "19800115" and is sliced (1:4)/(5:2)/(7:2). Only that pairing
        //     matches. An implementation that applied the LIVE offsets to the snapshot would
        //     compare month "01" against "15" and report a spurious change on every request.
        assertThat(confirm(requestWith(Map.of("dateOfBirth", "19800115"))).changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE);
        // (b) The day component really is compared at (7:2): 16 against the live 15.
        assertThat(confirm(requestWith(Map.of("dateOfBirth", "19800116"))).changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.SHOW_DETAILS);
        // (c) The compact form is structurally enforced. The OldDetails constructor assigns
        //     without validating, so the guard fires in the derived view compactDatePart the
        //     moment the service reads a component - and the abend funnel of ABEND-ROUTINE
        //     (:4203) wraps it as FatalProcessingException with the root cause PRESERVED,
        //     rather than swallowing it. A dash-separated snapshot can therefore never be
        //     silently mis-compared.
        assertThatThrownBy(() -> confirm(requestWith(Map.of("dateOfBirth", LIVE_DOB))))
                .isInstanceOf(FatalProcessingException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 8 characters");
    }

    @Test
    @DisplayName("case asymmetry - EFT id has NO case function, so case is a change")
    void noCaseFunctionFieldsAreCaseSensitive() {
        happyReads();
        assertThat(confirm(requestWith(Map.of("eftAccountId", "EFT-0001"))).changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.SHOW_DETAILS);
    }

    @Test
    @DisplayName("money compares by compareTo, so a differing scale is not a change")
    void moneyComparesByCompareTo() {
        final Map<String, Object> values = new HashMap<>();
        values.put("accountId", Long.valueOf(1L));
        values.put("activeStatus", "Y");
        // Scales 0 and 1 differ from the snapshot's decoded scale of 2, so equals() would
        // report a change while compareTo() correctly does not. Account.requireMoney
        // (Account.java:1001) rejects any scale ABOVE 2, so the divergence is created below,
        // never above, the NUMERIC(12,2) precision.
        values.put("currentBalance", new BigDecimal("194"));
        values.put("creditLimit", new BigDecimal("2020.0"));
        values.put("cashCreditLimit", new BigDecimal("1020"));
        values.put("currentCycleCredit", new BigDecimal("0"));
        values.put("currentCycleDebit", new BigDecimal("0.0"));
        values.put("openDate", "2000-01-01");
        values.put("expiraionDate", "2025-12-31");
        values.put("reissueDate", "2020-06-15");
        values.put("groupId", "ZEROBAL");
        values.put("addressZip", "98101");
        // The entity is built BEFORE when(...) is entered: a throw inside a when(...) argument
        // list leaves Mockito with an unfinished stubbing that then poisons the NEXT test.
        final Account rescaled = build(Account.class, values);
        when(this.accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(rescaled));
        when(this.customerRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(liveCustomer()));
        assertThat(confirm(requestWith(Map.of())).changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE);
    }

    @Test
    @DisplayName("2000 decider - DETAILS_NOT_FETCHED performs 9000-READ-ACCT")
    void detailsNotFetchedReadsTheAccount() {
        when(this.crossReferenceRepository.findByAccountIdOrderByCardNumberAsc(1L))
                .thenReturn(java.util.List.of(crossReference()));
        when(this.accountRepository.findById(1L)).thenReturn(Optional.of(liveAccount()));
        when(this.customerRepository.findById(1L)).thenReturn(Optional.of(liveCustomer()));
        final AccountUpdateService.AccountUpdateResult result =
                this.service.fetchForUpdate(ACCOUNT_KEY_TEXT);
        assertThat(result.changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.SHOW_DETAILS);
        assertThat(result.screen().getOldDetails()).isNotNull();
        assertThat(result.screen().getOldDetails().getDateOfBirth()).isEqualTo("19800115");
    }

    @Test
    @DisplayName("a missing snapshot is an explicit ValidationException, never a skipped comparison")
    void missingSnapshotIsRejected() {
        final Map<String, Object> top = screenFields();
        assertThatThrownBy(() -> this.service.updateAccount(
                        build(AccountUpdateRequest.class, top)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("a null request is rejected before any repository is touched")
    void nullRequestIsRejected() {
        assertThatThrownBy(() -> this.service.updateAccount(null))
                .isInstanceOf(ValidationException.class);
        verify(this.accountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("a rewrite failure surfaces as DataIntegrityException through updateAccount")
    void rewriteFailureSurfacesTyped() {
        happyReads();
        when(this.accountRepository.save(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("acct"));
        assertThatThrownBy(() -> this.service.updateAccount(requestWith(Map.of())))
                .isInstanceOf(DataIntegrityException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------- AID handling and the control-flow spine

    @Test
    @DisplayName("PF03 exits to the menu (:927-959) and touches no repository")
    void pf03ExitsToTheMenu() {
        final AccountUpdateService.AccountUpdateResult result = this.service.processRequest(
                requestWith(Map.of()),
                AID_PFK03,
                AccountUpdateService.ChangeAction.SHOW_DETAILS,
                AccountUpdateService.EntryMode.REENTER);
        // EXEC CICS XCTL at :956-958 becomes a transfer, and the bare SYNCPOINT at :952-954 is a
        // COMMIT with no pending unit of work on this path - a documented no-op substitution.
        assertThat(result.responseKind()).isEqualTo(AccountUpdateService.ResponseKind.TRANSFER);
        assertThat(result.navigation()).isNotNull();
        verify(this.accountRepository, never()).findByIdForUpdate(any());
        verify(this.customerRepository, never()).findByIdForUpdate(any());
        verify(this.accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("PF13-24 fold back onto PFK01-12, so PF17 drives the write exactly as PF05 does")
    void pf17FoldsOntoPfk05() {
        happyReads();
        final AccountUpdateService.AccountUpdateResult result = this.service.processRequest(
                requestWith(Map.of()),
                AID_PFK17,
                AccountUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                AccountUpdateService.EntryMode.REENTER);
        assertThat(result.changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE);
        verify(this.accountRepository).save(any(Account.class));
    }

    @Test
    @DisplayName("an unrecognised AID is SILENTLY coerced to ENTER (:914-916), never rejected")
    void unrecognisedAidIsCoercedToEnter() {
        happyEdits();
        // PF09 is outside the four-wide valid set of :905-916, so PFK-INVALID is set and ENTER
        // is substituted. The source raises no validation error for this, and neither may we.
        assertThatCode(() -> this.service.processRequest(requestWith(Map.of()),
                "DFHPF9",
                AccountUpdateService.ChangeAction.SHOW_DETAILS,
                AccountUpdateService.EntryMode.REENTER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PF12 with DETAILS_NOT_FETCHED takes the :2572 fall-through and reads the account")
    void pf12FallsThroughToTheRead() {
        when(this.crossReferenceRepository.findByAccountIdOrderByCardNumberAsc(1L))
                .thenReturn(java.util.List.of(crossReference()));
        when(this.accountRepository.findById(1L)).thenReturn(Optional.of(liveAccount()));
        when(this.customerRepository.findById(1L)).thenReturn(Optional.of(liveCustomer()));
        final AccountUpdateService.AccountUpdateResult result = this.service.processRequest(
                emptyScreen(),
                AID_PFK12,
                AccountUpdateService.ChangeAction.DETAILS_NOT_FETCHED,
                AccountUpdateService.EntryMode.REENTER);
        // :2568 WHEN ACUP-DETAILS-NOT-FETCHED falls through into :2572 WHEN CCARD-AID-PFK12,
        // sharing one body; FOUND-CUST-IN-MASTER then sets ACUP-SHOW-DETAILS at :2577-2578.
        assertThat(result.changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.SHOW_DETAILS);
        verify(this.accountRepository).findById(1L);
    }

    // ------------------------------------------------------------- the byte-exact outcome literals

    @Test
    @DisplayName("outcome literals of :513-528 are surfaced byte for byte")
    void outcomeLiteralsAreByteExact() {
        // :517-518 COULD-NOT-LOCK-ACCT-FOR-UPDATE
        when(this.accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThat(confirm(requestWith(Map.of())).errorMessage())
                .contains("Could not lock account record for update");

        // :521-522 DATA-WAS-CHANGED-BEFORE-UPDATE - note the space in "some one"
        happyReads();
        assertThat(confirm(requestWith(Map.of("activeStatus", "N"))).errorMessage())
                .contains("Record changed by some one else. Please review");

        // :523-524 LOCKED-BUT-UPDATE-FAILED. 3250-SETUP-INFOMSG (:2955-2985) chooses which of the
        // two channels carries a given outcome - WS-INFO-MSG to INFOMSGO at :2979, WS-RETURN-MSG to
        // ERRMSGO at :2981 - so the literal is asserted across both rather than pinned to one.
        happyReads();
        when(this.accountRepository.save(any(Account.class)))
                .thenThrow(new DataIntegrityViolationException("acct"));
        final AccountUpdateService.AccountUpdateResult failed = confirm(requestWith(Map.of()));
        assertThat(String.valueOf(failed.informationMessage())
                        + String.valueOf(failed.errorMessage()))
                .contains("Update of record failed");
    }

    // ------------------------------------------------- CSSETATY tri-state field-error semantics

    @Test
    @DisplayName("CSSETATY tri-state - a BLANK field gets DFHRED *and* the '*' marker")
    void blankFieldEmitsRedAndTheAsteriskMarker() {
        happyEdits();
        // ACCT-STATUS is mandatory (1215-EDIT-MANDATORY), so a blank value yields FLG-*-BLANK.
        final AccountUpdateService.AccountUpdateResult result =
                submit(screenWith(Map.of("accountStatus", "")));
        assertThat(hasAspect(result, "colour", "DFHRED")).isTrue();
        assertThat(hasAspect(result, "marker", "*")).isTrue();
        verify(this.accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("CSSETATY tri-state - a NOT_OK field gets DFHRED but NEVER the '*' marker")
    void invalidFieldEmitsRedWithoutTheAsteriskMarker() {
        happyEdits();
        // 'X' is neither 'Y' nor 'N', so 1220-EDIT-YESNO yields FLG-*-NOT-OK, not -BLANK. This is
        // exactly why a per-field boolean would be insufficient: only BLANK earns the marker.
        final AccountUpdateService.AccountUpdateResult result =
                submit(screenWith(Map.of("accountStatus", "X")));
        assertThat(hasAspect(result, "colour", "DFHRED")).isTrue();
        assertThat(hasAspect(result, "marker", "*")).isFalse();
        verify(this.accountRepository, never()).save(any(Account.class));
    }

    // ------------------------------------------- CSUTLDPY delegation and lookup-table delegation

    @Test
    @DisplayName("the 14 CSUTLDPY date labels are DELEGATED, not re-mapped (Phase 4.3)")
    void dateEditsAreDelegatedToDateValidationService() {
        happyEdits();
        submit(screenWith(Map.of()));
        // PERFORM EDIT-DATE-CCYYMMDD at :1480-1481, :1492-1493, :1505-1506 and :1536-1537, then
        // PERFORM EDIT-DATE-OF-BIRTH at :1540-1541 - five delegated calls, zero re-mapped labels.
        verify(this.dateValidationService, atLeast(3)).editDate(any(), any());
        verify(this.dateValidationService).editDateOfBirth(any(), any());
    }

    @Test
    @DisplayName("CSLKPCDY lookups are delegated to ValidationLookupService")
    void lookupsAreDelegatedToValidationLookupService() {
        happyEdits();
        submit(screenWith(Map.of()));
        verify(this.validationLookupService, atLeastOnce()).isValidUsStateCode(any());
    }

    @Test
    @DisplayName("the edit routines are SKIPPED on the confirm turn (:1463-1468)")
    void editRoutinesAreSkippedOnTheConfirmTurn() {
        happyReads();
        happyEdits();
        confirm(requestWith(Map.of()));
        // 1000-PROCESS-INPUTS is not performed on the PF05 confirm turn, so no date edit runs.
        verify(this.dateValidationService, never()).editDate(any(), any());
        verify(this.dateValidationService, never()).editDateOfBirth(any(), any());
    }

    // --------------------------------------------------------------- the 9000/9200/9300 read chain

    @Test
    @DisplayName("9200 with no cross-reference is typed, never swallowed, and stops the chain")
    void missingCrossReferenceIsTypedAndStopsTheChain() {
        notFoundMapping();
        when(this.crossReferenceRepository.findByAccountIdOrderByCardNumberAsc(1L))
                .thenReturn(java.util.List.of());
        // :3668-3685 WHEN DFHRESP(NOTFND). Every RESP is routed through FileStatusMapper, which
        // maps a not-found to RecordNotFoundException (FileStatusMapper.java:752), so the outcome
        // is a typed CardDemoException and the account read is never attempted.
        assertThatThrownBy(() -> this.service.fetchForUpdate(ACCOUNT_KEY_TEXT))
                .isInstanceOf(RecordNotFoundException.class);
        verify(this.accountRepository, never()).findById(any());
    }

    @Test
    @DisplayName("D16 preserved - the :3627 post-read guard is DEAD, so 9400 is still reached")
    void deadPostReadGuardStillReachesTheCustomerRead() {
        notFoundMapping();
        when(this.crossReferenceRepository.findByAccountIdOrderByCardNumberAsc(1L))
                .thenReturn(java.util.List.of(crossReference()));
        when(this.accountRepository.findById(1L)).thenReturn(Optional.empty());
        // 9000-READ-ACCT guards the customer read with IF DID-NOT-FIND-ACCT-IN-ACCTDAT at
        // :3627-3629 - but that condition name is NEVER SET anywhere in the program: the only two
        // SET sites, :3719 and :3769, are commented out. The guard is therefore dead and control
        // falls through to 9400-GETCUSTDATA-BYCUST even though the account was not found. This is
        // preserved defect D16, reproduced deliberately rather than repaired: adding the missing
        // SET would be a behaviour change. The same applies to the twin guard at :3636.
        assertThatThrownBy(() -> this.service.fetchForUpdate(ACCOUNT_KEY_TEXT))
                .isInstanceOf(RecordNotFoundException.class);
        verify(this.customerRepository).findById(1L);
    }

    @Test
    @DisplayName("a non-numeric account filter is rejected before any repository is touched")
    void nonNumericFilterNeverReachesARepository() {
        // 1210-EDIT-ACCOUNT at :1783-1820: an eleven-digit non-zero number is required.
        assertThatThrownBy(() -> this.service.fetchForUpdate("ABCDEFGHIJK"))
                .isInstanceOf(ValidationException.class);
        verify(this.crossReferenceRepository, never())
                .findByAccountIdOrderByCardNumberAsc(any());
        verify(this.accountRepository, never()).findById(any());
    }

    @Test
    @DisplayName("3202 slices the packed snapshot into SSN and phone components")
    void showDetailsPaintSlicesTheSnapshot() {
        when(this.crossReferenceRepository.findByAccountIdOrderByCardNumberAsc(1L))
                .thenReturn(java.util.List.of(crossReference()));
        when(this.accountRepository.findById(1L)).thenReturn(Optional.of(liveAccount()));
        when(this.customerRepository.findById(1L)).thenReturn(Optional.of(liveCustomer()));
        final AccountUpdateRequest screen =
                this.service.fetchForUpdate(ACCOUNT_KEY_TEXT).screen();
        // CUST-SSN (1:3)/(4:2)/(6:4) at :2829-2831
        assertThat(screen.getCustomerSsnPart1()).isEqualTo("123");
        assertThat(screen.getCustomerSsnPart2()).isEqualTo("45");
        assertThat(screen.getCustomerSsnPart3()).isEqualTo("6789");
        // CUST-PHONE-NUM-1 (2:3)/(6:3)/(10:4) at :2846-2857 over "(206)555-0100"
        assertThat(screen.getPhone1AreaCode()).isEqualTo("206");
        assertThat(screen.getPhone1Prefix()).isEqualTo("555");
        assertThat(screen.getPhone1LineNumber()).isEqualTo("0100");
        // the three account dates are painted as discrete y/m/d components
        assertThat(screen.getOpenDateYear()).isEqualTo("2000");
        assertThat(screen.getExpiryDateDay()).isEqualTo("31");
    }

    private static AccountUpdateRequest emptyScreen() {
        final Map<String, Object> top = new HashMap<>();
        top.put("accountId", ACCOUNT_KEY_TEXT);
        return build(AccountUpdateRequest.class, top);
    }

    // ----------------------------------------------- the 1200-series edit routines, field by field

    /** Asserts that a submitted screen value is rejected: red attribute, and no write at all. */
    private void assertRejected(final Map<String, Object> screenOverrides) {
        happyEdits();
        final AccountUpdateService.AccountUpdateResult result =
                submit(screenWith(screenOverrides));
        assertThat(hasAspect(result, "colour", "DFHRED"))
                .as("a failed edit must colour its field DFHRED per CSSETATY")
                .isTrue();
        verify(this.accountRepository, never()).save(any(Account.class));
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("1260 EDIT-AREA-CODE rejects an area code the lookup does not know")
    void unknownAreaCodeIsRejected() {
        happyEdits();
        when(this.validationLookupService.isValidPhoneAreaCode(any())).thenReturn(false);
        when(this.validationLookupService.isValidGeneralPurposeAreaCode(any())).thenReturn(false);
        when(this.validationLookupService.isValidEasilyRecognisableAreaCode(any())).thenReturn(false);
        final AccountUpdateService.AccountUpdateResult result = submit(screenWith(Map.of()));
        assertThat(hasAspect(result, "colour", "DFHRED")).isTrue();
        verify(this.accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("EDIT-US-PHONE-PREFIX rejects a non-numeric prefix")
    void nonNumericPhonePrefixIsRejected() {
        assertRejected(Map.of("phone1Prefix", "5A5"));
    }

    @Test
    @DisplayName("EDIT-US-PHONE-LINENUM rejects a non-numeric line number")
    void nonNumericPhoneLineNumberIsRejected() {
        assertRejected(Map.of("phone1LineNumber", "01O0"));
    }

    @Test
    @DisplayName("1245 EDIT-NUM-REQD rejects a non-numeric FICO score")
    void nonNumericFicoScoreIsRejected() {
        assertRejected(Map.of("customerFicoScore", "7X0"));
    }

    @Test
    @DisplayName("1275 EDIT-FICO-SCORE rejects a score outside the accepted band")
    void outOfRangeFicoScoreIsRejected() {
        assertRejected(Map.of("customerFicoScore", "100"));
    }

    @Test
    @DisplayName("1250 EDIT-SIGNED-9V2 rejects an unparseable credit limit")
    void unparseableCreditLimitIsRejected() {
        assertRejected(Map.of("creditLimit", "20Z0.00"));
    }

    @Test
    @DisplayName("1225 EDIT-ALPHA-REQD rejects digits in a mandatory name field")
    void digitsInAMandatoryNameAreRejected() {
        assertRejected(Map.of("customerFirstName", "MARG4RET"));
    }

    @Test
    @DisplayName("1215 EDIT-MANDATORY rejects a blank first name and marks it with '*'")
    void blankMandatoryNameIsMarked() {
        happyEdits();
        final AccountUpdateService.AccountUpdateResult result =
                submit(screenWith(Map.of("customerFirstName", "")));
        assertThat(hasAspect(result, "colour", "DFHRED")).isTrue();
        assertThat(hasAspect(result, "marker", "*")).isTrue();
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("1270 EDIT-US-STATE-CD rejects a state the lookup does not know")
    void unknownStateCodeIsRejected() {
        happyEdits();
        when(this.validationLookupService.isValidUsStateCode(any())).thenReturn(false);
        final AccountUpdateService.AccountUpdateResult result = submit(screenWith(Map.of()));
        assertThat(hasAspect(result, "colour", "DFHRED")).isTrue();
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("1280 EDIT-US-STATE-ZIP-CD rejects a state and ZIP that do not pair")
    void mismatchedStateAndZipAreRejected() {
        happyEdits();
        when(this.validationLookupService.isValidStateZipCodeCombination(any())).thenReturn(false);
        when(this.validationLookupService.isValidStateAndZipCode(any(), any())).thenReturn(false);
        final AccountUpdateService.AccountUpdateResult result = submit(screenWith(Map.of()));
        assertThat(hasAspect(result, "colour", "DFHRED")).isTrue();
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("1220 EDIT-YESNO flags a cardholder flag that is neither Y nor N (:1657-1662)")
    void invalidPrimaryCardHolderFlagIsFlagged() {
        happyEdits();
        final AccountUpdateService.AccountUpdateResult result =
                submit(screenWith(Map.of("primaryCardHolderIndicator", "Q")));
        // :1662 MOVE WS-EDIT-YES-NO TO WS-EDIT-PRI-CARDHOLDER. For a yes/no field the flag field IS
        // the data field (:77-78, :350-352), so 1220 leaves '0' for not-ok and 'B' for blank there,
        // and the MOVE transfers that state encoding rather than a separate flag group.
        assertThat(result.fieldAttributes())
                .anySatisfy(attribute -> {
                    assertThat(attribute.field()).isEqualTo("ACSPFLG");
                    assertThat(attribute.aspect()).isEqualTo("colour");
                    assertThat(attribute.value()).isEqualTo("DFHRED");
                });
        // NOT_OK, never BLANK, so no '*' marker may be emitted for this field
        assertThat(result.fieldAttributes())
                .noneSatisfy(attribute -> {
                    assertThat(attribute.field()).isEqualTo("ACSPFLG");
                    assertThat(attribute.aspect()).isEqualTo("marker");
                });
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName("the EFT id is edited as NUMERIC-required (:1648-1653), so a non-numeric id is red")
    void nonNumericEftAccountIdIsFlagged() {
        happyEdits();
        final AccountUpdateService.AccountUpdateResult result = submit(screenWith(Map.of()));
        assertThat(result.fieldAttributes())
                .anySatisfy(attribute -> {
                    assertThat(attribute.field()).isEqualTo("ACSEFTC");
                    assertThat(attribute.aspect()).isEqualTo("colour");
                });
    }

    @Test
    @DisplayName("a NOT_OK date outcome from DateValidationService is honoured, not re-derived")
    void notOkDateOutcomeIsHonoured() {
        happyEdits();
        when(this.dateValidationService.editDate(any(), any()))
                .thenReturn(new DateValidationService.EditOutcome(
                        DateValidationService.EditFlag.NOT_OK,
                        DateValidationService.EditFlag.ISVALID,
                        DateValidationService.EditFlag.ISVALID,
                        true,
                        "Invalid card expiry year"));
        final AccountUpdateService.AccountUpdateResult result = submit(screenWith(Map.of()));
        // The 14 CSUTLDPY labels live in DateValidationService; this bean consumes the outcome and
        // re-applies the IF WS-RETURN-MSG-OFF latch, so the delegated message reaches the screen.
        assertThat(hasAspect(result, "colour", "DFHRED")).isTrue();
        assertThat(String.valueOf(result.errorMessage())).contains("Invalid card expiry year");
        verify(this.accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("3009 positions the cursor on the first field in error, not the last")
    void cursorLandsOnTheFirstFieldInError() {
        happyEdits();
        // The 44-arm EVALUATE at :3009-3167 is FIRST-MATCH: account status precedes the customer
        // name in the arm order, so with both in error the cursor must land on the status field.
        final Map<String, Object> both = new HashMap<>();
        both.put("accountStatus", "X");
        both.put("customerFirstName", "MARG4RET");
        final AccountUpdateService.AccountUpdateResult result = submit(screenWith(both));
        final java.util.List<String> cursorFields = result.fieldAttributes().stream()
                .filter(attribute -> "cursor".equals(attribute.aspect()))
                .map(AccountUpdateService.FieldAttribute::field)
                .toList();
        assertThat(cursorFields).hasSize(1);
        assertThat(cursorFields.get(0)).isEqualTo("ACSTTUS");
    }

    @Test
    @DisplayName("3201 paints the initial map when the details have not been fetched")
    void initialPaintRunsWhenDetailsAreNotFetched() {
        final AccountUpdateService.AccountUpdateResult result = this.service.processRequest(
                emptyScreen(),
                AID_ENTER,
                AccountUpdateService.ChangeAction.DETAILS_NOT_FETCHED,
                AccountUpdateService.EntryMode.REENTER);
        // :2711-2714 the fall-through pair routes an unfetched screen to 3201-SHOW-INITIAL-VALUES.
        assertThat(result.responseKind()).isEqualTo(AccountUpdateService.ResponseKind.MAP);
        assertThat(result.screen()).isNotNull();
    }

    // ------------------------------------------- 3009: the 44-arm first-match cursor EVALUATE

    @Test
    @DisplayName("3009 resolves the cursor to the one field in error, arm by arm (:3009-3167)")
    void cursorResolvesToEachErroredFieldInTurn() {
        // The EVALUATE at :3009-3167 is first-match, so with exactly ONE field in error the cursor
        // must land on that field whatever the arm order is. Each pair below breaks one field and
        // names the BMS symbolic-map field its arm selects. Note that the EFT account id is
        // permanently NOT_OK on any submitted turn: :1648-1653 applies 1245-EDIT-NUM-REQD - a
        // NUMERIC-required edit - to ACUP-NEW-CUST-EFT-ACCOUNT-ID, and the seeded value is
        // "eft-0001". Its arm at :3158 therefore fires on every turn, so only fields whose arm
        // PRECEDES :3158 can win the first match. The cardholder arm at :3162 comes after it and is
        // covered separately below.
        final java.util.Map<java.util.Map<String, Object>, String> cases =
                new java.util.LinkedHashMap<>();
        cases.put(Map.of("accountStatus", "X"), "ACSTTUS");
        cases.put(Map.of("creditLimit", "20Z0.00"), "ACRDLIM");
        cases.put(Map.of("cashCreditLimit", "10Z0.00"), "ACSHLIM");
        cases.put(Map.of("currentBalance", "19Z.00"), "ACURBAL");
        cases.put(Map.of("currentCycleCredit", "0.Z0"), "ACRCYCR");
        cases.put(Map.of("currentCycleDebit", "0.Z0"), "ACRCYDB");
        cases.put(Map.of("customerFicoScore", "7X0"), "ACSTFCO");
        cases.put(Map.of("customerFirstName", "MARG4RET"), "ACSFNAM");
        cases.put(Map.of("customerLastName", "GO1D"), "ACSLNAM");
        cases.put(Map.of("customerMiddleName", "4"), "ACSMNAM");
        // :1584-1590 edits address line 1 with 1215-EDIT-MANDATORY ONLY - a presence check - so a
        // digit in a street address is perfectly valid and only a blank value flags the field.
        cases.put(Map.of("addressLine1", ""), "ACSADL1");
        cases.put(Map.of("addressCity", "SE4TTLE"), "ACSCITY");
        cases.put(Map.of("addressCountryCode", "U5A"), "ACSCTRY");
        cases.put(Map.of("addressZip", "981O1"), "ACSZIPC");
        cases.put(Map.of("customerSsnPart1", "12X"), "ACTSSN1");
        cases.put(Map.of("customerSsnPart3", "678X"), "ACTSSN3");
        cases.put(Map.of("phone2AreaCode", "4X5"), "ACSPH2A");
        cases.put(Map.of("phone2Prefix", "5X5"), "ACSPH2B");
        cases.put(Map.of("phone2LineNumber", "019X"), "ACSPH2C");
        cases.forEach((override, expectedField) -> {
            happyEdits();
            final AccountUpdateService.AccountUpdateResult result =
                    submit(screenWith(override));
            assertThat(result.fieldAttributes().stream()
                    .filter(attribute -> "cursor".equals(attribute.aspect()))
                    .map(AccountUpdateService.FieldAttribute::field)
                    .toList())
                    .as("cursor for %s", override)
                    .containsExactly(expectedField);
        });
    }

    // ----------------------------------------- CSSTRPFY: the 28-arm EVALUATE TRUE over EIBAID

    @Test
    @DisplayName("CSSTRPFY maps all 28 EIBAID arms and folds PF13-24 onto PF01-12")
    void storePfKeyMapsEveryAttentionIdentifier() {
        // app/cpy/CSSTRPFY.cpy:17-80 YYYY-STORE-PFKEY. Every AID below is recognised; only the four
        // conditions of :905-916 are *valid* for this program, and the rest are silently coerced to
        // ENTER at :914-916 rather than rejected - so none of these may raise.
        final java.util.List<String> aids = java.util.List.of(
                "DFHENTER", "DFHCLEAR", "DFHPA1", "DFHPA2",
                "DFHPF1", "DFHPF2", "DFHPF3", "DFHPF4", "DFHPF5", "DFHPF6",
                "DFHPF7", "DFHPF8", "DFHPF9", "DFHPF10", "DFHPF11", "DFHPF12",
                "DFHPF13", "DFHPF14", "DFHPF15", "DFHPF16", "DFHPF17", "DFHPF18",
                "DFHPF19", "DFHPF20", "DFHPF21", "DFHPF22", "DFHPF23", "DFHPF24");
        aids.forEach(aid -> {
            happyEdits();
            assertThatCode(() -> this.service.processRequest(requestWith(Map.of()),
                    aid,
                    AccountUpdateService.ChangeAction.SHOW_DETAILS,
                    AccountUpdateService.EntryMode.REENTER))
                    .as("AID %s", aid)
                    .doesNotThrowAnyException();
        });
        // PF15 folds onto PFK03, so it must exit to the menu exactly as PF03 does at :927-959.
        final AccountUpdateService.AccountUpdateResult folded = this.service.processRequest(
                requestWith(Map.of()),
                "DFHPF15",
                AccountUpdateService.ChangeAction.SHOW_DETAILS,
                AccountUpdateService.EntryMode.REENTER);
        assertThat(folded.responseKind()).isEqualTo(AccountUpdateService.ResponseKind.TRANSFER);
    }

    @Test
    @DisplayName("EDIT-AREA-CODE accepts a general-purpose code and an easily-recognisable one")
    void areaCodeAcceptsBothLookupClasses() {
        // :2246-2315 tests three CSLKPCDY tables in turn; a hit in any one accepts the code.
        happyEdits();
        when(this.validationLookupService.isValidPhoneAreaCode(any())).thenReturn(false);
        when(this.validationLookupService.isValidGeneralPurposeAreaCode(any())).thenReturn(true);
        when(this.validationLookupService.isValidEasilyRecognisableAreaCode(any())).thenReturn(false);
        assertThatCode(() -> submit(screenWith(Map.of()))).doesNotThrowAnyException();

        happyEdits();
        when(this.validationLookupService.isValidPhoneAreaCode(any())).thenReturn(false);
        when(this.validationLookupService.isValidGeneralPurposeAreaCode(any())).thenReturn(false);
        when(this.validationLookupService.isValidEasilyRecognisableAreaCode(any())).thenReturn(true);
        assertThatCode(() -> submit(screenWith(Map.of()))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D17 preserved - 9400 stamps the response codes OUTSIDE the message latch")
    void customerReadStampsResponseCodesOutsideTheLatch() {
        notFoundMapping();
        when(this.crossReferenceRepository.findByAccountIdOrderByCardNumberAsc(1L))
                .thenReturn(java.util.List.of(crossReference()));
        when(this.accountRepository.findById(1L)).thenReturn(Optional.of(liveAccount()));
        when(this.customerRepository.findById(1L)).thenReturn(Optional.empty());
        // 9400 places MOVE ... TO ERROR-RESP / ERROR-RESP2 at :3770-3771, outside the
        // IF WS-RETURN-MSG-OFF latch that 9200 (:3672-3673) and 9300 (:3721-3722) keep them inside.
        // Preserved as written rather than harmonised.
        assertThatThrownBy(() -> this.service.fetchForUpdate(ACCOUNT_KEY_TEXT))
                .isInstanceOf(RecordNotFoundException.class);
        verify(this.customerRepository).findById(1L);
    }

    // ----------------------------------------------- remaining spine and read-chain branches

    @Test
    @DisplayName(":964-965 the first fall-through pair paints an empty map and returns")
    void firstEntryPaintsAnEmptyMapAndReturns() {
        // WHEN ACUP-DETAILS-NOT-FETCHED (:964) falls through into WHEN CDEMO-FROM-PROGRAM = the menu
        // AND NOT CDEMO-PGM-REENTER (:966), sharing one body at :968-973 that sends the map and goes
        // to COMMON-RETURN. No repository is touched, and the account is read on the NEXT turn.
        final AccountUpdateService.AccountUpdateResult result = this.service.processRequest(
                emptyScreen(),
                AID_ENTER,
                AccountUpdateService.ChangeAction.DETAILS_NOT_FETCHED,
                AccountUpdateService.EntryMode.ENTER);
        assertThat(result.responseKind()).isEqualTo(AccountUpdateService.ResponseKind.MAP);
        verify(this.crossReferenceRepository, never())
                .findByAccountIdOrderByCardNumberAsc(any());
        verify(this.accountRepository, never()).findById(any());
    }

    @Test
    @DisplayName(":2596 the CHANGES_NOT_OK arm is a CONTINUE - it repaints without writing")
    void changesNotOkArmRepaintsWithoutWriting() {
        happyEdits();
        final AccountUpdateService.AccountUpdateResult result = this.service.processRequest(
                requestWith(Map.of()),
                AID_ENTER,
                AccountUpdateService.ChangeAction.CHANGES_NOT_OK,
                AccountUpdateService.EntryMode.REENTER);
        assertThat(result.responseKind()).isEqualTo(AccountUpdateService.ResponseKind.MAP);
        verify(this.accountRepository, never()).save(any(Account.class));
        verify(this.customerRepository, never()).save(any(Customer.class));
    }

    @Test
    @DisplayName(":2620 the general CHANGES_OK_NOT_CONFIRMED arm does NOT write without PF05")
    void confirmedChangesWithoutPf05DoNotWrite() {
        happyReads();
        happyEdits();
        // The specific branch at :2602-2603 requires CCARD-AID-PFK05; the general arm at :2620 is a
        // CONTINUE. This is why the specific branch MUST be evaluated first - reversing them would
        // make the write unreachable.
        final AccountUpdateService.AccountUpdateResult result = this.service.processRequest(
                requestWith(Map.of()),
                AID_ENTER,
                AccountUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                AccountUpdateService.EntryMode.REENTER);
        assertThat(result.changeAction())
                .isEqualTo(AccountUpdateService.ChangeAction.CHANGES_OK_NOT_CONFIRMED);
        verify(this.accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("a repository fault on the cross-reference read is typed, not swallowed")
    void crossReferenceFaultIsTyped() {
        when(this.crossReferenceRepository.findByAccountIdOrderByCardNumberAsc(1L))
                .thenThrow(new DataIntegrityViolationException("xref unavailable"));
        assertThatThrownBy(() -> this.service.fetchForUpdate(ACCOUNT_KEY_TEXT))
                .isInstanceOf(CardDemoException.class);
        verify(this.accountRepository, never()).findById(any());
    }

    @Test
    @DisplayName("a repository fault on the customer read is typed, not swallowed")
    void customerReadFaultIsTyped() {
        when(this.crossReferenceRepository.findByAccountIdOrderByCardNumberAsc(1L))
                .thenReturn(java.util.List.of(crossReference()));
        when(this.accountRepository.findById(1L)).thenReturn(Optional.of(liveAccount()));
        when(this.customerRepository.findById(1L))
                .thenThrow(new DataIntegrityViolationException("cust unavailable"));
        assertThatThrownBy(() -> this.service.fetchForUpdate(ACCOUNT_KEY_TEXT))
                .isInstanceOf(CardDemoException.class);
    }

    private static CardCrossReference crossReference() {
        final Map<String, Object> values = new HashMap<>();
        values.put("cardNumber", "4111111111111111");
        values.put("accountId", Long.valueOf(1L));
        values.put("customerId", Long.valueOf(1L));
        return build(CardCrossReference.class, values);
    }
}
