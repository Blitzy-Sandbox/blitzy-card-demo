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
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import com.awsm2.carddemo.validation.DateValidationService;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit&nbsp;5 + Mockito + AssertJ unit tests for
 * {@link TransactionAddService}.
 *
 * <h2>COBOL provenance (AAP &sect;0.7.3 Refactor Discipline)</h2>
 * <p>{@link TransactionAddService} translates COBOL/CICS program
 * {@code app/cbl/COTRN02C.cbl} (CICS transaction id {@code CT02}, VSAM
 * file id {@code 'TRANSACT'}). The original program is the online
 * "add transaction" conversational flow rendered through BMS mapset
 * {@code app/bms/COTRN02.bms} (symbolic map
 * {@code app/cpy-bms/COTRN02.CPY}, record {@code COTRN2AI}). Each
 * paragraph of {@code COTRN02C.cbl} translated by the SUT is covered
 * here, and every assertion preserves the semantics enumerated in the
 * source comments at:</p>
 * <ul>
 *   <li>{@code COTRN02C.cbl} L164-L188 ({@code PROCESS-ENTER-KEY},
 *       confirmation gate &mdash; {@code CONFIRMI = 'Y'} required to
 *       commit; otherwise the COBOL flow re-sends the screen with the
 *       error string "Confirm to add this transaction..." or
 *       "Invalid value. Valid values are (Y/N)...").</li>
 *   <li>{@code COTRN02C.cbl} L191-L230 ({@code VALIDATE-INPUT-KEY-FIELDS}
 *       &mdash; XOR validation: either {@code ACTIDIN} or
 *       {@code CARDNIN}; resolves the missing identifier via the
 *       {@code CARDXREF} alternate-index {@code CXACAIX}).</li>
 *   <li>{@code COTRN02C.cbl} L235-L437 ({@code VALIDATE-INPUT-DATA-FIELDS}
 *       &mdash; field-level mandatory checks: {@code TTYPCD},
 *       {@code TCATCD}, {@code TRNSRC}, {@code TDESC}, {@code TRNAMT},
 *       {@code TORIGDT}, {@code TPROCDT}, {@code MID}, {@code MNAME},
 *       {@code MCITY}, {@code MZIP}). Date validity is delegated to
 *       {@code CSUTLDTC} (LE {@code CEEDAYS} wrapper) which is
 *       replaced by the Java {@link DateValidationService} per AAP
 *       &sect;0.6.3.</li>
 *   <li>{@code COTRN02C.cbl} L442-L466 ({@code ADD-TRANSACTION}
 *       &mdash; the MAX-TRAN-ID + 1 idiom:
 *       {@code MOVE HIGH-VALUES TO TRAN-ID; STARTBR; READPREV; ENDBR;
 *       ADD 1 TO WS-TRAN-ID-N}; then INITIALIZE TRAN-RECORD + MOVE *
 *       fields, then {@code PERFORM WRITE-TRANSACT-FILE}).</li>
 *   <li>{@code CVTRA05Y.cpy} L4-L19 ({@code TRAN-RECORD} layout, RECLN
 *       = 350: {@code TRAN-ID PIC X(16)}, {@code TRAN-TYPE-CD PIC X(02)},
 *       {@code TRAN-CAT-CD PIC 9(04)}, {@code TRAN-SOURCE PIC X(10)},
 *       {@code TRAN-DESC PIC X(100)}, {@code TRAN-AMT PIC S9(09)V99},
 *       {@code TRAN-MERCHANT-ID PIC 9(09)},
 *       {@code TRAN-MERCHANT-NAME PIC X(50)},
 *       {@code TRAN-MERCHANT-CITY PIC X(50)},
 *       {@code TRAN-MERCHANT-ZIP PIC X(10)},
 *       {@code TRAN-CARD-NUM PIC X(16)},
 *       {@code TRAN-ORIG-TS PIC X(26)},
 *       {@code TRAN-PROC-TS PIC X(26)},
 *       FILLER PIC X(20)).</li>
 *   <li>{@code CVACT03Y.cpy} L4-L8 ({@code CARD-XREF-RECORD} layout,
 *       RECLN = 50: {@code XREF-CARD-NUM PIC X(16)},
 *       {@code XREF-CUST-ID PIC 9(09)}, {@code XREF-ACCT-ID PIC 9(11)}).</li>
 * </ul>
 *
 * <h2>Behavioural invariants locked by this suite</h2>
 * <ol>
 *   <li><b>MAX-TRAN-ID + 1 generator</b> &mdash; the next tran ID is
 *       the persisted maximum + 1, zero-padded to 16 digits. An empty
 *       table seeds from {@code SEED_TRAN_ID}
 *       ({@code "0000000000000000"}). See
 *       {@code TransactionAddService#nextTransactionId()} per AAP
 *       &sect;0.6.2 (the canonical MAX-TRAN-ID + 1 pattern that
 *       replaces the COBOL {@code STARTBR}/{@code READPREV}/{@code
 *       ENDBR} sequence).</li>
 *   <li><b>ON SIZE ERROR</b> &mdash; when the next ID would exceed
 *       {@code 9999999999999999} an {@link OnSizeErrorException} is
 *       raised (matches the COBOL {@code ON SIZE ERROR} branch on the
 *       {@code ADD 1 TO WS-TRAN-ID-N} arithmetic per AAP &sect;0.7.1
 *       implementation rule "Replicate COBOL ON SIZE ERROR handling
 *       with explicit overflow checks in Java").</li>
 *   <li><b>XREF lookup</b> &mdash; if the card number resolves to a
 *       missing {@link CardCrossReference} the service throws
 *       {@link RecordNotFoundException} (FILE STATUS 23 NOTFND
 *       semantic); a supplied {@code accountId} that does not match
 *       the card's owning account throws {@link ValidationException}
 *       ({@code XREF_MISMATCH}).</li>
 *   <li><b>Confirmation gate</b> &mdash; {@code confirm != 'Y'}
 *       (case-insensitive) blocks persistence with
 *       {@link ValidationException} ({@code NOT_CONFIRMED}) per the
 *       COBOL {@code EVALUATE CONFIRMI OF COTRN2AI} branch at
 *       {@code COTRN02C.cbl} L169-L188.</li>
 *   <li><b>BigDecimal preservation</b> &mdash; the supplied amount is
 *       persisted verbatim with its precision/scale intact (no
 *       float/double conversion). Per AAP &sect;0.6.1 / &sect;0.7.1,
 *       all monetary values use {@link BigDecimal} with
 *       {@link java.math.RoundingMode#HALF_EVEN}.</li>
 *   <li><b>MSK partitioning</b> &mdash; the published
 *       {@code transaction.posted} event uses the OWNING ACCOUNT ID
 *       as the partition key, satisfying the AAP &sect;0.6.5
 *       per-account ordering invariant.</li>
 *   <li><b>Audit + log discipline</b> &mdash; audit payload contains
 *       only the last 4 of the PAN; never the full card number, per
 *       AAP &sect;0.6.6 PCI-DSS requirement.</li>
 *   <li><b>@Transactional rollback semantic</b> &mdash; when
 *       {@link TransactionRepository#save(Object)} throws
 *       {@link DataIntegrityViolationException} (the Spring
 *       abstraction over JDBC {@code SQLIntegrityConstraintViolation}
 *       / Hibernate {@code ConstraintViolation} for FILE STATUS 22
 *       DUPKEY semantics), neither the {@code transaction.posted}
 *       Kafka event nor the audit emission run &mdash; preserving
 *       the COBOL CICS {@code SYNCPOINT ROLLBACK} contract that no
 *       side effects escape the failed transaction.</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}; no Testcontainers or LocalStack are
 * involved. The test class is therefore a pure unit test (Level 3 in
 * AAP &sect;0.5.1 testing taxonomy) and completes in &lt; 5 seconds.</p>
 *
 * @see TransactionAddService                       the SUT
 * @see com.awsm2.carddemo.dto.TransactionAddDto    the request DTO
 * @see com.awsm2.carddemo.domain.Transaction       the JPA entity
 * @see com.awsm2.carddemo.domain.CardCrossReference the XREF entity
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionAddService unit tests (COBOL: COTRN02C.cbl)")
class TransactionAddServiceTest {

    // ==================================================================
    // Constants (test fixtures shared across all @Nested groups)
    //
    // Values chosen to mirror the BMS field widths from COTRN02.CPY:
    //   - CARD_NUMBER : 16 digits  (CARDNINI PIC X(16))
    //   - ACCOUNT_ID  : 11 digits  (ACTIDINI PIC X(11), parsed to Long)
    //   - TRAN_TYPE   : 2 chars    (TTYPCDI  PIC X(2))
    //   - TRAN_CAT    : 4 digits   (TCATCDI  PIC X(4) numeric)
    //   - SOURCE      : <= 10 char (TRNSRCI  PIC X(10))
    //   - AMOUNT      : signed S9(09)V99 (TRAN-AMT)
    //   - MERCHANT_ID : 9 digits   (TRAN-MERCHANT-ID PIC 9(09))
    // ==================================================================
    private static final String CARD_NUMBER = "4111222233334444";
    private static final String LAST4 = "4444";
    private static final String ACCOUNT_ID_STR = "10000000001";
    private static final Long ACCOUNT_ID = 10_000_000_001L;
    private static final String TRAN_TYPE = "01";
    private static final Integer TRAN_CAT = 5;
    private static final String SOURCE = "POS";
    private static final String DESCRIPTION = "Coffee shop";
    private static final BigDecimal AMOUNT = new BigDecimal("12.50");
    private static final Long MERCHANT_ID = 999_888L;
    private static final String MERCHANT_NAME = "STARBUCKS #123";
    private static final String MERCHANT_CITY = "SEATTLE";
    private static final String MERCHANT_ZIP = "98101";

    // ==================================================================
    // Mocks and SUT
    //
    // Only the 5 collaborators on the SUT's constructor are declared as
    // mocks. AccountRepository, TransactionTypeRepository, and
    // TransactionCategoryRepository are NOT injected into
    // TransactionAddService — type/category validation in the SUT is
    // by field-presence only (the BMS-era format/range check), with
    // referential integrity enforced by the V008/V009 FK constraints
    // and the GlobalExceptionHandler exception translation.
    // ==================================================================
    @Mock private TransactionRepository transactionRepository;
    @Mock private CardCrossReferenceRepository cardCrossReferenceRepository;
    @Mock private KafkaEventPublisher kafkaEventPublisher;
    @Mock private AuditLogService auditLogService;
    @Mock private DateValidationService dateValidationService;

    @InjectMocks
    private TransactionAddService service;

    // ==================================================================
    // Common test fixtures
    // ==================================================================
    private TransactionAddDto validRequest;

    @BeforeEach
    void setUp() {
        // The canonical "happy path" request. Tests typically derive
        // mutated copies via the builder helpers below to surface the
        // single field they're stressing.
        validRequest = new TransactionAddDto(
                ACCOUNT_ID_STR,
                CARD_NUMBER,
                TRAN_TYPE,
                TRAN_CAT,
                SOURCE,
                DESCRIPTION,
                AMOUNT,
                null, // originationTimestamp -- SUT falls back to now()
                null, // processingTimestamp  -- SUT always uses now()
                MERCHANT_ID,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_ZIP,
                "Y");
    }

    /**
     * Build a DTO that only varies the {@code confirm} flag, leaving
     * every other field at its happy-path value. Used by the
     * {@link Confirmation} @Nested group.
     */
    private TransactionAddDto buildRequestWithConfirm(String confirm) {
        return new TransactionAddDto(
                ACCOUNT_ID_STR, CARD_NUMBER, TRAN_TYPE, TRAN_CAT, SOURCE,
                DESCRIPTION, AMOUNT, null, null, MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, confirm);
    }

    /**
     * Build a DTO that only varies the {@code amount}, leaving every
     * other field at its happy-path value. Used by the
     * {@link BigDecimalAmountHandling} @Nested group.
     */
    private TransactionAddDto buildRequestWithAmount(BigDecimal amount) {
        return new TransactionAddDto(
                ACCOUNT_ID_STR, CARD_NUMBER, TRAN_TYPE, TRAN_CAT, SOURCE,
                DESCRIPTION, amount, null, null, MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, "Y");
    }

    /**
     * Build a DTO that mutates only the {@code accountId} and
     * {@code cardNumber} components — used to drive XOR validation
     * scenarios (both null, only one populated, mismatched pair).
     */
    private TransactionAddDto buildRequestWithAccountAndCard(String acct, String card) {
        return new TransactionAddDto(
                acct, card, TRAN_TYPE, TRAN_CAT, SOURCE, DESCRIPTION,
                AMOUNT, null, null, MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, "Y");
    }

    /**
     * Stub the XREF lookup for the happy path (card &rarr; account).
     * Uses {@code lenient()} because some negative-path tests verify
     * the XREF call is never reached and would otherwise raise
     * Mockito's strict "UnnecessaryStubbingException" diagnostic.
     *
     * <p>Constructor order is intentionally
     * {@code CardCrossReference(xrefCardNum, xrefCustId, xrefAcctId)}
     * matching {@code CVACT03Y.cpy} field order: the test seeds a
     * fixed customer id but the actual account id supplied by the
     * caller.</p>
     *
     * <p>// COBOL: COTRN02C: READ-CXACAIX-FILE (line 208 / line 222
     * / read-by-card lookup path)</p>
     */
    private void stubXrefByCard(String cardNumber, Long acctId) {
        CardCrossReference xref =
                new CardCrossReference(cardNumber, 999_999L /* custId */, acctId);
        lenient().when(cardCrossReferenceRepository.findById(cardNumber))
                .thenReturn(Optional.of(xref));
    }

    /**
     * Stub the transaction repository's MAX-TRAN-ID lookup and save.
     *
     * <p>// COBOL: COTRN02C: ADD-TRANSACTION L444-L451
     * (MOVE HIGH-VALUES TO TRAN-ID; STARTBR; READPREV; ENDBR;
     * MOVE TRAN-ID TO WS-TRAN-ID-N; ADD 1 TO WS-TRAN-ID-N).</p>
     */
    private void stubTransactionRepository(String existingMax) {
        Transaction existing = null;
        if (existingMax != null) {
            existing = new Transaction(
                    existingMax, TRAN_TYPE, TRAN_CAT, SOURCE,
                    DESCRIPTION, AMOUNT, MERCHANT_ID, MERCHANT_NAME,
                    MERCHANT_CITY, MERCHANT_ZIP, CARD_NUMBER,
                    LocalDateTime.now(), LocalDateTime.now());
        }
        Optional<Transaction> result = existing == null
                ? Optional.empty() : Optional.of(existing);
        lenient().when(transactionRepository.findTopByOrderByTranIdDesc())
                .thenReturn(result);
        lenient().when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * Stub the Kafka publisher for the happy path so the SUT's call
     * returns a non-null CompletableFuture. Used in HappyPath +
     * post-validation flows.
     */
    private void stubKafkaPublishSuccess() {
        lenient().when(kafkaEventPublisher.publishTransactionPosted(
                anyLong(), any(TransactionAddDto.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // ==================================================================
    // @Nested test groups
    //
    // Each group corresponds to a COBOL paragraph in COTRN02C.cbl and
    // is named after the paragraph for inline traceability per AAP
    // §0.7.3 Refactor Discipline.
    // ==================================================================

    /**
     * Tests for the MAX-TRAN-ID + 1 generator (COBOL paragraph
     * {@code ADD-TRANSACTION} at {@code COTRN02C.cbl} L444-L451).
     *
     * <p>This generator preserves the COBOL idiom verbatim:</p>
     * <pre>
     *     MOVE HIGH-VALUES TO TRAN-ID
     *     PERFORM STARTBR-TRANSACT-FILE
     *     PERFORM READPREV-TRANSACT-FILE
     *     PERFORM ENDBR-TRANSACT-FILE
     *     MOVE TRAN-ID     TO WS-TRAN-ID-N
     *     ADD 1 TO WS-TRAN-ID-N
     * </pre>
     * <p>The Java translation replaces the {@code STARTBR/READPREV/ENDBR}
     * browse with
     * {@link TransactionRepository#findTopByOrderByTranIdDesc()} and the
     * {@code ADD 1} with {@code BigDecimal.add(BigDecimal.ONE)} guarded
     * by an overflow check that throws {@link OnSizeErrorException}
     * matching the COBOL {@code ON SIZE ERROR} branch.</p>
     */
    @Nested
    @DisplayName("MAX-TRAN-ID + 1 generator (COBOL: ADD-TRANSACTION + ON SIZE ERROR)")
    class TranIdGeneration {

        @Test
        @DisplayName("seeds with 0000000000000001 when journal is empty")
        void addTransaction_emptyJournal_seedsWithOne() {
            // Arrange — empty journal (first-ever transaction)
            // COBOL: STARTBR + READPREV on an empty file → SEED_TRAN_ID
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            TransactionAddDto response = service.addTransaction(validRequest);

            // Assert — captured save carries tranId = 0000000000000001
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getTranId())
                    .isEqualTo("0000000000000001");
            assertThat(response.processingTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("assigns MAX + 1 (zero-padded to 16 digits)")
        void addTransaction_existingMax_assignsNextSequentialId() {
            // Arrange — current max tran ID is 0000000000000123
            // COBOL: WS-TRAN-ID-N = 0000000000000123 + 1
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository("0000000000000123");
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(validRequest);

            // Assert — next id is 0000000000000124 (zero-padded to 16)
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getTranId())
                    .isEqualTo("0000000000000124");
        }

        @Test
        @DisplayName("MAX + 1 from 0000000000000099 yields 0000000000000100 (leading-zero preservation)")
        void addTransaction_maxIs99_assigns100WithZeroPad() {
            // Arrange — boundary that exercises leading-zero padding
            // when the integer value crosses from 2 to 3 digits.
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository("0000000000000099");
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(validRequest);

            // Assert
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getTranId())
                    .isEqualTo("0000000000000100");
        }

        @Test
        @DisplayName("MAX + 1 from 9999999999999998 yields 9999999999999999 (at-ceiling)")
        void addTransaction_maxIsCeilingMinusOne_assignsCeiling() {
            // Arrange — the absolute final allowable transaction id
            // before the 16-digit ceiling is reached.
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository("9999999999999998");
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(validRequest);

            // Assert
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getTranId())
                    .isEqualTo("9999999999999999");
        }

        @Test
        @DisplayName("throws OnSizeErrorException at 16-digit ceiling")
        void addTransaction_maxIdReached_throwsOnSizeError() {
            // Arrange — current max is the ceiling 9999999999999999;
            // adding 1 would overflow the COBOL PIC 9(16) field which
            // is the COBOL ON SIZE ERROR semantic per AAP §0.7.1.
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            lenient().when(transactionRepository.findTopByOrderByTranIdDesc())
                    .thenReturn(Optional.of(new Transaction(
                            "9999999999999999", TRAN_TYPE, TRAN_CAT, SOURCE,
                            DESCRIPTION, AMOUNT, MERCHANT_ID, MERCHANT_NAME,
                            MERCHANT_CITY, MERCHANT_ZIP, CARD_NUMBER,
                            LocalDateTime.now(), LocalDateTime.now())));

            // Act + Assert
            assertThatThrownBy(() -> service.addTransaction(validRequest))
                    .isInstanceOf(OnSizeErrorException.class)
                    .hasMessageContaining("exhausted");

            // No save, publish, or audit on overflow (CICS SYNCPOINT
            // ROLLBACK semantic per AAP §0.7.1).
            verify(transactionRepository, never()).save(any());
            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any());
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("non-numeric existing tran ID re-seeds and continues")
        void addTransaction_nonNumericMax_reseeds() {
            // Arrange — corrupted (non-numeric) value in the table;
            // the SUT's defensive branch logs a warning and re-seeds
            // from 0, allowing the next insert to succeed at id 1.
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            Transaction corrupted = new Transaction();
            corrupted.setTranId("ABCDEFGHIJKLMNOP");
            lenient().when(transactionRepository.findTopByOrderByTranIdDesc())
                    .thenReturn(Optional.of(corrupted));
            lenient().when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(validRequest);

            // Assert — service re-seeds and writes tran id = 1
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getTranId())
                    .isEqualTo("0000000000000001");
        }
    }

    /**
     * Tests for the COBOL {@code VALIDATE-INPUT-KEY-FIELDS} paragraph
     * (lines 191-230 of {@code COTRN02C.cbl}) which encodes the XOR
     * "either account-id or card-number" rule and the
     * {@code READ-CXACAIX-FILE} alternate-index lookup.
     */
    @Nested
    @DisplayName("XREF resolution (COBOL: VALIDATE-INPUT-KEY-FIELDS + READ-CXACAIX-FILE)")
    class XrefResolution {

        @Test
        @DisplayName("missing XREF for supplied card → RecordNotFoundException")
        void addTransaction_xrefMissing_throwsRecordNotFound() {
            // Arrange — XREF lookup returns empty (FILE STATUS 23
            // NOTFND in the COBOL source).
            // COBOL: READ-CCXREF-FILE returns NOTFND → error path
            when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> service.addTransaction(validRequest))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("cross-reference");

            verify(transactionRepository, never()).save(any());
            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any());
        }

        @Test
        @DisplayName("accountId mismatch against XREF → ValidationException (XREF_MISMATCH)")
        void addTransaction_acctIdMismatch_throwsValidation() {
            // Arrange — XREF resolves to acct 999999 but request says
            // 10000000001. The COBOL source does NOT have an explicit
            // mismatch check (it overwrites either side after the XREF
            // read); the Java SUT adds this defensive consistency
            // check because REST clients can supply both fields where
            // BMS operators could not. Per AAP §0.7.1 this is an
            // ADDITIVE safety check, not a behaviour change.
            CardCrossReference xref = new CardCrossReference(
                    CARD_NUMBER, 99_999_999_999L, 999_999L);
            when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(xref));

            // Act + Assert
            assertThatThrownBy(() -> service.addTransaction(validRequest))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("does not match");

            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("only accountId supplied → AIX lookup; empty AIX → RecordNotFound")
        void addTransaction_accountOnly_emptyAix_throwsRecordNotFound() {
            // Arrange — only accountId supplied. COBOL: WHEN ACTIDINI
            // NOT = SPACES AND LOW-VALUES → PERFORM READ-CXACAIX-FILE
            // (alternate-index lookup) → empty result → error
            TransactionAddDto request =
                    buildRequestWithAccountAndCard(ACCOUNT_ID_STR, null);
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of());

            // Act + Assert
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("No card cross-reference");

            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("only cardNumber supplied → XREF lookup proceeds and saves")
        void addTransaction_cardOnly_xrefLookupProceeds() {
            // Arrange — only cardNumber supplied. The SUT must call
            // findById(cardNumber) and use the returned acctId as the
            // partition key for the Kafka event.
            // COBOL: WHEN CARDNINI NOT = SPACES AND LOW-VALUES →
            // PERFORM READ-CCXREF-FILE
            TransactionAddDto request =
                    buildRequestWithAccountAndCard(null, CARD_NUMBER);
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(request);

            // Assert — save executed, Kafka event partitioned by the
            // resolved account ID.
            verify(transactionRepository).save(any(Transaction.class));
            ArgumentCaptor<Long> partitionKey =
                    ArgumentCaptor.forClass(Long.class);
            verify(kafkaEventPublisher).publishTransactionPosted(
                    partitionKey.capture(), any(TransactionAddDto.class));
            assertThat(partitionKey.getValue()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("neither accountId nor cardNumber → ValidationException")
        void addTransaction_noIdentifier_throwsValidation() {
            // Arrange — request missing both identifiers. COBOL:
            // EVALUATE TRUE ... WHEN OTHER → 'Account or Card Number
            // must be entered...' (lines 224-229).
            TransactionAddDto request =
                    buildRequestWithAccountAndCard(null, null);

            // Act + Assert — the per-field error message is collected
            // into ValidationException.fieldErrors (the top-level
            // exception message is the COBOL summary string).
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .extracting(ex -> ((ValidationException) ex).getFieldErrors())
                    .asInstanceOf(InstanceOfAssertFactories.LIST)
                    .anyMatch(fe ->
                            fe.toString().contains("Either accountId or cardNumber"));
        }
    }

    /**
     * Tests for the COBOL confirmation gate {@code EVALUATE CONFIRMI}
     * (lines 169-188 of {@code COTRN02C.cbl}). The Java SUT requires
     * {@code confirm = 'Y'} (case-insensitive) and rejects all other
     * values including {@code null} with the COBOL message
     * {@code "Confirm to add this transaction..."} or
     * {@code "Invalid value. Valid values are (Y/N)..."}.
     */
    @Nested
    @DisplayName("Confirmation gate (COBOL: EVALUATE CONFIRMI = 'Y')")
    class Confirmation {

        @Test
        @DisplayName("'N' confirm value blocks persistence")
        void addTransaction_confirmN_throwsValidation() {
            // Arrange — operator typed 'N' to cancel
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            TransactionAddDto request = buildRequestWithConfirm("N");

            // The top-level ValidationException message text is the
            // service-supplied message; the reasonCode discriminator
            // is "NOT_CONFIRMED".
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Operator did not confirm");

            verify(transactionRepository, never()).save(any());
            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any());
        }

        @Test
        @DisplayName("'y' (lowercase) is accepted (case-insensitive)")
        void addTransaction_confirmLowercaseY_succeeds() {
            // Arrange — COBOL: WHEN 'Y' WHEN 'y' → PERFORM
            // ADD-TRANSACTION (lines 170-172) — the Java SUT preserves
            // the same case-insensitive accept.
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();
            TransactionAddDto request = buildRequestWithConfirm("y");

            // Act
            service.addTransaction(request);

            // Assert — the SUT proceeds to save, equivalent to the
            // COBOL WHEN 'Y' branch.
            verify(transactionRepository).save(any(Transaction.class));
        }

        @Test
        @DisplayName("null confirm blocks persistence")
        void addTransaction_nullConfirm_throwsValidation() {
            // Arrange — COBOL: WHEN SPACES WHEN LOW-VALUES → 'Confirm
            // to add this transaction...' (lines 175-181).
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            TransactionAddDto request = buildRequestWithConfirm(null);

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class);

            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("arbitrary string confirm blocks persistence")
        void addTransaction_arbitraryConfirm_throwsValidation() {
            // Arrange — COBOL: WHEN OTHER → 'Invalid value. Valid
            // values are (Y/N)...' (lines 182-187).
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            TransactionAddDto request = buildRequestWithConfirm("X");

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("confirm");

            verify(transactionRepository, never()).save(any());
        }
    }

    /**
     * Tests for the COBOL {@code VALIDATE-INPUT-DATA-FIELDS} paragraph
     * (lines 235-437) which performs field-level emptiness and format
     * checks. The Java target implements the same checks declaratively
     * (Jakarta Bean Validation annotations on the DTO) plus
     * service-layer cross-field rules in
     * {@code TransactionAddService#validate(TransactionAddDto)}.
     *
     * <p>Note: the COBOL {@code CALL 'CSUTLDTC'} for date validation
     * (lines 393, 413) is replaced by
     * {@link DateValidationService#validate(String)} per AAP
     * &sect;0.6.3 — see the {@code addTransaction_invalidOriginationDate_*}
     * test below.</p>
     */
    @Nested
    @DisplayName("Field validation (COBOL: VALIDATE-INPUT-DATA-FIELDS)")
    class FieldValidation {

        @Test
        @DisplayName("rejects null request")
        void addTransaction_nullRequest_throwsNpe() {
            // Defensive — the SUT's Objects.requireNonNull(request)
            // guards against a malformed REST request before any
            // validation runs.
            assertThatThrownBy(() -> service.addTransaction(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects 10-digit accountId (must be 11)")
        void addTransaction_shortAccountId_throwsValidation() {
            // Arrange — COBOL: ACTIDIN PIC X(11) — only an 11-digit
            // value is acceptable.
            TransactionAddDto request =
                    buildRequestWithAccountAndCard("1000000001", null);

            // Act + Assert — the top-level ValidationException message
            // is "Transaction add request contains invalid fields"; the
            // per-field error is "accountId must be exactly 11 digits".
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .extracting(ex -> ((ValidationException) ex).getFieldErrors())
                    .asInstanceOf(InstanceOfAssertFactories.LIST)
                    .anyMatch(fe -> fe.toString().contains("11 digits"));
        }

        @Test
        @DisplayName("rejects 15-digit cardNumber (must be 16)")
        void addTransaction_shortCardNumber_throwsValidation() {
            // Arrange — COBOL: CARDNIN PIC X(16) — only a 16-digit
            // value is acceptable.
            TransactionAddDto request = buildRequestWithAccountAndCard(
                    null, "411122223333444");
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .extracting(ex -> ((ValidationException) ex).getFieldErrors())
                    .asInstanceOf(InstanceOfAssertFactories.LIST)
                    .anyMatch(fe -> fe.toString().contains("16 digits"));
        }

        @Test
        @DisplayName("rejects missing amount")
        void addTransaction_nullAmount_throwsValidation() {
            // Arrange — COBOL: WHEN TRNAMTI = SPACES OR LOW-VALUES →
            // 'Amount can NOT be empty...' (lines 276-281).
            TransactionAddDto request = buildRequestWithAmount(null);
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .extracting(ex -> ((ValidationException) ex).getFieldErrors())
                    .asInstanceOf(InstanceOfAssertFactories.LIST)
                    .anyMatch(fe -> fe.toString().contains("amount is required"));
        }

        @Test
        @DisplayName("rejects invalid origination date via DateValidationService")
        void addTransaction_invalidOriginationDate_throwsValidation() {
            // Arrange — COBOL: CALL 'CSUTLDTC' USING TORIGDTI ...; IF
            // CSUTLDTC-RESULT-SEV-CD NOT = '0000' → error (lines
            // 389-407). The Java SUT stubs DateValidationService to
            // return an invalid result, which the SUT promotes to a
            // ValidationException.
            LocalDateTime origin = LocalDateTime.of(2099, 1, 1, 12, 0);
            TransactionAddDto request = new TransactionAddDto(
                    ACCOUNT_ID_STR, CARD_NUMBER, TRAN_TYPE, TRAN_CAT, SOURCE,
                    DESCRIPTION, AMOUNT, origin, null, MERCHANT_ID,
                    MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, "Y");
            when(dateValidationService.validate(anyString()))
                    .thenReturn(DateValidationService.DateValidationResult.invalid(
                            "E001", "Date in the future"));

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class);

            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("accepts valid origination date via DateValidationService")
        void addTransaction_validOriginationDate_proceeds() {
            // Arrange — DateValidationService stubbed to return
            // success, allowing the SUT to proceed to save.
            LocalDateTime origin = LocalDateTime.of(2024, 1, 15, 12, 30);
            TransactionAddDto request = new TransactionAddDto(
                    ACCOUNT_ID_STR, CARD_NUMBER, TRAN_TYPE, TRAN_CAT, SOURCE,
                    DESCRIPTION, AMOUNT, origin, null, MERCHANT_ID,
                    MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, "Y");
            when(dateValidationService.validate(anyString()))
                    .thenReturn(DateValidationService.DateValidationResult.VALID);
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(request);

            // Assert — saved entity carries the supplied origination
            // timestamp verbatim.
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getTranOrigTs()).isEqualTo(origin);
        }
    }

    /**
     * Happy-path tests asserting the persist + publish + audit flow
     * runs end-to-end. Mirrors the COBOL {@code WRITE-TRANSACT-FILE}
     * + DISPLAY-of-audit-row pattern.
     */
    @Nested
    @DisplayName("Happy path: persist + MSK publish + audit (COBOL: WRITE-TRANSACT-FILE)")
    class HappyPath {

        @Test
        @DisplayName("end-to-end success: write, publish, audit")
        void addTransaction_happyPath_writesPublishesAndAudits() {
            // Arrange — current max = 10, valid XREF, Kafka success.
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository("0000000000000010");
            stubKafkaPublishSuccess();

            // Act
            TransactionAddDto response = service.addTransaction(validRequest);

            // Assert — response carries new tran ID, account ID
            // (zero-padded to 11), and processing timestamp.
            assertThat(response).isNotNull();
            assertThat(response.accountId()).isEqualTo("10000000001");
            assertThat(response.processingTimestamp()).isNotNull();

            // Save called once.
            verify(transactionRepository).save(any(Transaction.class));

            // MSK publish — partition key = owning account ID (AAP §0.6.5
            // per-account ordering invariant).
            ArgumentCaptor<Long> partitionKeyCaptor =
                    ArgumentCaptor.forClass(Long.class);
            verify(kafkaEventPublisher).publishTransactionPosted(
                    partitionKeyCaptor.capture(),
                    any(TransactionAddDto.class));
            assertThat(partitionKeyCaptor.getValue()).isEqualTo(ACCOUNT_ID);

            // Audit — eventName, actor, payload triple per the AAP
            // §0.7.1 audit-trail preservation rule.
            verify(auditLogService).auditEvent(
                    eq("transaction.added"), eq("system"), anyMap());
        }

        @Test
        @DisplayName("amount is preserved verbatim (BigDecimal, no float)")
        void addTransaction_amountPreservedVerbatim() {
            // Arrange — non-trivial decimal value
            BigDecimal amount = new BigDecimal("123.45");
            TransactionAddDto request = buildRequestWithAmount(amount);
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(request);

            // Assert — saved entity carries the EXACT BigDecimal value,
            // scale included (no rounding, no float conversion). AAP
            // §0.6.1: BigDecimal mandatory for every monetary field.
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            BigDecimal saved = captor.getValue().getTranAmt();
            assertThat(saved).isEqualByComparingTo(amount);
            assertThat(saved.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("processingTimestamp on the returned DTO is the SUT-assigned now()")
        void addTransaction_returnsCreatedTransaction() {
            // Arrange — happy path setup
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();
            LocalDateTime before = LocalDateTime.now().minusSeconds(1);

            // Act
            TransactionAddDto response = service.addTransaction(validRequest);
            LocalDateTime after = LocalDateTime.now().plusSeconds(1);

            // Assert — return value carries the system-assigned timestamps.
            assertThat(response).isNotNull();
            assertThat(response.processingTimestamp())
                    .as("processingTimestamp must be SUT-assigned (now)")
                    .isAfter(before).isBefore(after);
            assertThat(response.originationTimestamp())
                    .as("originationTimestamp falls back to now when not supplied")
                    .isAfter(before).isBefore(after);
            // Confirm field defaults to "Y" per the SUT's outbound DTO
            // construction (the response always carries the canonical
            // confirmation flag because the transaction has been posted).
            assertThat(response.confirm()).isEqualTo("Y");
        }
    }

    /**
     * Tests for {@link DataIntegrityViolationException} mapping and
     * the rollback semantics of {@code @Transactional(rollbackFor =
     * Exception.class)} when an INSERT against the {@code transactions}
     * table violates a unique constraint (COBOL FILE STATUS 22 DUPKEY
     * semantic).
     *
     * <p>The SUT does NOT translate {@link DataIntegrityViolationException}
     * to {@code DuplicateRecordException} at the service layer — that
     * mapping is performed by {@code GlobalExceptionHandler}
     * (@RestControllerAdvice) at the HTTP boundary per AAP &sect;0.7.1
     * ("Map COBOL RETURN-CODE / condition codes to Spring exception
     * hierarchy via @ControllerAdvice"). The service-layer contract
     * is therefore that the underlying Spring exception propagates as-is,
     * AND that no Kafka/audit side effects occur after the failed save
     * — preserving the COBOL CICS {@code SYNCPOINT ROLLBACK} contract.</p>
     */
    @Nested
    @DisplayName("Duplicate + concurrency: rollback semantics (COBOL: SYNCPOINT ROLLBACK)")
    class DuplicateAndConcurrency {

        @Test
        @DisplayName("save throws DataIntegrityViolationException → propagates and Kafka NOT invoked")
        void addTransaction_dataIntegrityViolation_propagatesAndSkipsKafka() {
            // Arrange — happy path setup except save throws DUPKEY.
            // COBOL: WRITE TRANSACT FILE STATUS = '22' DUPKEY → the
            // COBOL CICS SYNCPOINT ROLLBACK runs and no audit / next-
            // program flow occurs. The Java target preserves this via
            // @Transactional(rollbackFor = Exception.class).
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            when(transactionRepository.save(any(Transaction.class)))
                    .thenThrow(new DataIntegrityViolationException(
                            "duplicate key value violates unique constraint"));

            // Act + Assert — the underlying Spring exception
            // propagates (the GlobalExceptionHandler is responsible
            // for mapping it to HTTP 409 + DuplicateRecordException
            // at the controller boundary, per AAP §0.7.1).
            assertThatThrownBy(() -> service.addTransaction(validRequest))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("duplicate key");

            // Critical: no side effects after the failed save.
            // Preserves the COBOL CICS SYNCPOINT ROLLBACK semantic
            // that the failed transaction emits NO Kafka event and
            // NO audit row.
            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any());
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("save throws RuntimeException → propagates and Kafka NOT invoked")
        void addTransaction_runtimeException_propagatesAndSkipsKafka() {
            // Arrange — defensive: any RuntimeException from save must
            // be treated identically. The @Transactional boundary
            // rolls back; no downstream side effects fire. This
            // exercises the GENERAL rollback contract, not just the
            // FILE STATUS 22 specialisation.
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            when(transactionRepository.save(any(Transaction.class)))
                    .thenThrow(new RuntimeException("persistence failure"));

            // Act + Assert
            assertThatThrownBy(() -> service.addTransaction(validRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("persistence failure");

            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any());
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }
    }

    /**
     * BigDecimal precision-and-scale tests. Validates the AAP
     * &sect;0.6.1 mandate that:
     * <ul>
     *   <li>Every monetary value is a {@link BigDecimal} (never
     *       {@code float}/{@code double}).</li>
     *   <li>Arithmetic preserves the COBOL {@code PIC S9(09)V99}
     *       precision (11 total digits, 2 fractional).</li>
     *   <li>Comparison uses {@link BigDecimal#compareTo(BigDecimal)}
     *       (via AssertJ's {@code isEqualByComparingTo}) — never
     *       {@link BigDecimal#equals(Object)} which is scale-sensitive
     *       and would treat {@code 100.50} and {@code 100.5} as
     *       unequal.</li>
     * </ul>
     *
     * <p>Note: the SUT does NOT explicitly call {@code setScale(2,
     * HALF_EVEN)} on the inbound amount — it relies on the Jakarta
     * Bean Validation {@code @Digits(integer=9, fraction=2)}
     * annotation to gate inputs at the controller boundary and on the
     * JPA {@code NUMERIC(11,2)} column to coerce the persisted value.
     * This unit test therefore verifies the SUT's actual passthrough
     * behaviour (the saved entity carries whatever scale the caller
     * supplied) rather than asserting a service-layer normalization
     * that the SUT does not perform.</p>
     */
    @Nested
    @DisplayName("BigDecimal precision (AAP §0.6.1 — TRAN-AMT PIC S9(09)V99)")
    class BigDecimalAmountHandling {

        @Test
        @DisplayName("amount with scale 2 (\"100.50\") preserved exactly")
        void addTransaction_amountScaleTwo_preservedExactly() {
            // Arrange — typical POS amount with two decimal places.
            BigDecimal amount = new BigDecimal("100.50");
            TransactionAddDto request = buildRequestWithAmount(amount);
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(request);

            // Assert — equality must use isEqualByComparingTo per AAP
            // §0.6.1 to avoid scale-sensitivity bugs.
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            BigDecimal saved = captor.getValue().getTranAmt();
            assertThat(saved).isEqualByComparingTo(new BigDecimal("100.50"));
            assertThat(saved.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("amount at max precision (9999999.99) accepted and stored verbatim")
        void addTransaction_amountAtMaxPrecision_accepted() {
            // Arrange — boundary of the human-readable max for a card
            // purchase (7 integer digits + 2 fractional). This is well
            // inside the COBOL PIC S9(09)V99 range
            // [-999,999,999.99, +999,999,999.99] but tests a
            // realistic ceiling that downstream PostgreSQL NUMERIC(11,2)
            // must accept without precision loss.
            BigDecimal amount = new BigDecimal("9999999.99");
            TransactionAddDto request = buildRequestWithAmount(amount);
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(request);

            // Assert — saved entity carries the exact 9999999.99 value.
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            BigDecimal saved = captor.getValue().getTranAmt();
            assertThat(saved).isEqualByComparingTo(new BigDecimal("9999999.99"));
        }

        @Test
        @DisplayName("negative amount (refund/credit) preserved verbatim")
        void addTransaction_negativeAmount_preservedExactly() {
            // Arrange — COBOL TRAN-AMT PIC S9(09)V99 explicitly carries
            // a sign byte. Negative values represent refunds, credits,
            // and payment-side debits. The Java target preserves the
            // sign verbatim via BigDecimal (which is signed by default).
            BigDecimal amount = new BigDecimal("-42.13");
            TransactionAddDto request = buildRequestWithAmount(amount);
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(request);

            // Assert — sign and scale both preserved.
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            BigDecimal saved = captor.getValue().getTranAmt();
            assertThat(saved).isEqualByComparingTo(new BigDecimal("-42.13"));
            assertThat(saved.signum()).isEqualTo(-1);
        }

        @Test
        @DisplayName("zero amount (no-op transaction) preserved verbatim")
        void addTransaction_zeroAmount_preservedExactly() {
            // Arrange — zero is a legal COBOL TRAN-AMT (used in
            // bookkeeping flush rows or reversed reversals). The SUT
            // does not gate against zero amounts.
            BigDecimal amount = new BigDecimal("0.00");
            TransactionAddDto request = buildRequestWithAmount(amount);
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(request);

            // Assert — zero is propagated verbatim.
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            BigDecimal saved = captor.getValue().getTranAmt();
            assertThat(saved).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    /**
     * Verifies every DTO &rarr; Transaction entity field-mapping from
     * COBOL {@code COTRN02C.cbl}'s {@code ADD-TRANSACTION} paragraph
     * (lines 442-466). The COBOL source executes a sequence of MOVE
     * statements that each transfer a single BMS input field to a
     * single TRAN-RECORD field; the Java SUT performs the equivalent
     * constructor-time mapping (with {@code safeTrim} applied to
     * string fields per the COBOL fixed-width trim semantic).
     */
    @Nested
    @DisplayName("DTO → Transaction entity field mapping (COBOL: ADD-TRANSACTION MOVEs)")
    class FieldMapping {

        @Test
        @DisplayName("all DTO components copied into the captured Transaction")
        void addTransaction_dtoFieldsCopiedToEntity() {
            // Arrange — happy path setup.
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository("0000000000000005");
            stubKafkaPublishSuccess();

            // Act — submit the canonical valid request.
            service.addTransaction(validRequest);

            // Assert — every TRAN-* column of CVTRA05Y.cpy carries the
            // expected DTO value. The captured entity proves the SUT
            // composed the COBOL MOVE sequence faithfully.
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            Transaction saved = captor.getValue();

            // COBOL: MOVE WS-TRAN-ID-N TO TRAN-ID (line 451) — 16-char
            // zero-padded numeric.
            assertThat(saved.getTranId()).isEqualTo("0000000000000006");

            // COBOL: MOVE TTYPCDI TO TRAN-TYPE-CD (line 452)
            assertThat(saved.getTranTypeCd()).isEqualTo(TRAN_TYPE);

            // COBOL: MOVE TCATCDI TO TRAN-CAT-CD (line 453)
            assertThat(saved.getTranCatCd()).isEqualTo(TRAN_CAT);

            // COBOL: MOVE TRNSRCI TO TRAN-SOURCE (line 454)
            assertThat(saved.getTranSource()).isEqualTo(SOURCE);

            // COBOL: MOVE TDESCI TO TRAN-DESC (line 455)
            assertThat(saved.getTranDesc()).isEqualTo(DESCRIPTION);

            // COBOL: MOVE WS-TRAN-AMT-N TO TRAN-AMT (lines 456-458) —
            // BigDecimal scale 2 preserved per AAP §0.6.1.
            assertThat(saved.getTranAmt()).isEqualByComparingTo(AMOUNT);

            // COBOL: MOVE CARDNINI TO TRAN-CARD-NUM (line 459)
            assertThat(saved.getTranCardNum()).isEqualTo(CARD_NUMBER);

            // COBOL: MOVE MIDI TO TRAN-MERCHANT-ID (line 460)
            assertThat(saved.getTranMerchantId()).isEqualTo(MERCHANT_ID);

            // COBOL: MOVE MNAMEI TO TRAN-MERCHANT-NAME (line 461)
            assertThat(saved.getTranMerchantName()).isEqualTo(MERCHANT_NAME);

            // COBOL: MOVE MCITYI TO TRAN-MERCHANT-CITY (line 462)
            assertThat(saved.getTranMerchantCity()).isEqualTo(MERCHANT_CITY);

            // COBOL: MOVE MZIPI TO TRAN-MERCHANT-ZIP (line 463)
            assertThat(saved.getTranMerchantZip()).isEqualTo(MERCHANT_ZIP);

            // COBOL: MOVE TORIGDTI TO TRAN-ORIG-TS (line 464) — the
            // Java SUT falls back to now() when the DTO omits origin,
            // so we only assert the field is populated.
            assertThat(saved.getTranOrigTs()).isNotNull();

            // COBOL: MOVE TPROCDTI TO TRAN-PROC-TS (line 465) — the
            // Java SUT always assigns the service-side now() for
            // tranProcTs (deliberate divergence from COBOL where the
            // operator supplied the value; documented in
            // TransactionAddService class JavaDoc).
            assertThat(saved.getTranProcTs()).isNotNull();
        }

        @Test
        @DisplayName("string DTO components are trimmed before persistence")
        void addTransaction_stringFieldsTrimmedBeforePersist() {
            // Arrange — supply padded values that the COBOL source
            // would carry as space-filled BMS fields. The SUT's
            // safeTrim() helper removes leading/trailing whitespace
            // before composing the Transaction entity, mirroring the
            // COBOL behaviour of treating the trailing spaces of a
            // PIC X(n) value as semantically empty.
            String paddedSource = "  POS  ";
            String paddedMerchantName = "  STARBUCKS  ";
            TransactionAddDto request = new TransactionAddDto(
                    ACCOUNT_ID_STR, CARD_NUMBER, TRAN_TYPE, TRAN_CAT,
                    paddedSource, DESCRIPTION, AMOUNT, null, null,
                    MERCHANT_ID, paddedMerchantName, MERCHANT_CITY,
                    MERCHANT_ZIP, "Y");
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(request);

            // Assert — string fields are trimmed.
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            Transaction saved = captor.getValue();
            assertThat(saved.getTranSource()).isEqualTo("POS");
            assertThat(saved.getTranMerchantName()).isEqualTo("STARBUCKS");
        }
    }

    /**
     * PCI-DSS log / audit discipline per AAP &sect;0.6.6 — verifies
     * the SUT does not emit the full PAN into the audit payload.
     */
    @Nested
    @DisplayName("PCI-DSS discipline: audit + log lines (AAP §0.6.6)")
    class PciDssHandling {

        @Test
        @DisplayName("audit payload contains cardLast4 only, never the full PAN")
        void addTransaction_auditOmitsFullPan() {
            // Arrange
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(validRequest);

            // Assert — verify the audit payload has masked PAN
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).auditEvent(
                    eq("transaction.added"), eq("system"),
                    payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsEntry("cardLast4", LAST4);
            assertThat(payload).containsEntry("accountId", ACCOUNT_ID);
            assertThat(payload).containsKey("transactionId");
            assertThat(payload).containsKey("amount");
            assertThat(payload).containsKey("type");
            assertThat(payload).containsKey("category");

            // No key or value may include the full PAN — defence-in-
            // depth check against accidental PAN leakage into the
            // CloudWatch / OpenSearch audit pipeline (AAP §0.6.6).
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                assertThat(key)
                        .as("audit key %s must not contain full PAN", key)
                        .doesNotContain(CARD_NUMBER);
                if (value != null) {
                    assertThat(value.toString())
                            .as("audit value for key %s must not contain full PAN", key)
                            .doesNotContain(CARD_NUMBER);
                }
            }
        }
    }
}
