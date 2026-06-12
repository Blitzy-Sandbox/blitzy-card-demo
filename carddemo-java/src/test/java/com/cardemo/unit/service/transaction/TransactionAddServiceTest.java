package com.cardemo.unit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.DateValidationService.DateValidationResult;
import com.cardemo.service.transaction.TransactionAddService;

/**
 * Fast, fully-mocked unit test for {@link TransactionAddService} &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.x translation of the online CICS program
 * <strong>{@code app/cbl/COTRN02C.cbl}</strong> (CICS transaction {@code CT02}, BMS mapset
 * {@code COTRN02}, "Add Transaction"). Adding a transaction is the highest-risk add path in the
 * transaction package: it resolves a card cross-reference (by account <em>or</em> card), runs an
 * ordered fail-fast cascade of roughly twenty field validations, auto-generates the next 16-digit
 * transaction id and writes a new row to the {@code TRANSACT} dataset (now the PostgreSQL
 * {@code transaction} table).
 *
 * <h2>Test strategy</h2>
 * <p>These are millisecond, in-memory, pure-Mockito unit tests: there is <em>no</em>
 * {@code @SpringBootTest}, no Spring context, no Testcontainers, no database, no AWS and no I/O. All
 * three collaborators &mdash; {@link TransactionRepository}, {@link CardCrossReferenceRepository}
 * and {@link DateValidationService} &mdash; are Mockito {@code @Mock}s, and the system under test is
 * wired by constructor injection through {@code @InjectMocks} (the real constructor order is
 * {@code (TransactionRepository, CardCrossReferenceRepository, DateValidationService)}). The class
 * runs under {@link MockitoExtension} (default {@code STRICT_STUBS}); therefore tests that throw
 * before reaching a collaborator stub <em>nothing</em> and assert
 * {@link org.mockito.Mockito#verifyNoInteractions(Object...) verifyNoInteractions} to prove the
 * early exit, while every other path stubs <em>only</em> the collaborators it actually exercises
 * with the exact keys the service uses (no needless {@code any()}, no {@code lenient()}).</p>
 *
 * <h2>{@link DateValidationService} is mocked here</h2>
 * <p>{@link DateValidationService} (the {@code CALL 'CSUTLDTC'} replacement) is a <em>mock</em> in
 * this suite: its deep {@code CEEDAYS}/{@code CSUTLDTC} calendar-parity behavior is exercised
 * exhaustively in {@code unit/validation/DateValidationServiceTest}. Here we verify only the
 * add-service's <em>interaction</em> with it &mdash; that the date is dash-stripped to the 8-char
 * {@code YYYYMMDD} picture before the call, and that an invalid result drives the correct verbatim
 * message. We do not re-test date math.</p>
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.1&ndash;&sect;0.7.2)</h2>
 * <p>Every assertion reflects {@code COTRN02C}'s observable behavior <em>exactly</em>, with no
 * "improvements". The governing fact is fail-fast / first-error-wins: in COBOL each error path sets
 * {@code WS-ERR-FLG} and re-sends the screen, so the program reports the <strong>first</strong>
 * failure in a fixed order. To prove that check <em>N</em> fires, each test builds a baseline where
 * checks {@code 1..N-1} all pass and only check <em>N</em> fails, then asserts the thrown message is
 * check <em>N</em>'s &mdash; this ordering is the parity contract. The order is:
 * {@code VALIDATE-INPUT-KEY-FIELDS} (account precedence, xref with card/account write-back) &rarr;
 * {@code VALIDATE-INPUT-DATA-FIELDS} (eleven empty edits &rarr; type/category numeric &rarr; amount
 * format &rarr; date format &rarr; {@code CSUTLDTC} validity &rarr; merchant-id numeric) &rarr;
 * {@code EVALUATE CONFIRMI} &rarr; {@code ADD-TRANSACTION}. Three subtle traps are encoded
 * explicitly: (1) the success message has <strong>two spaces</strong> &mdash;
 * {@code "added successfully.  Your Tran ID is"}; (2) the duplicate message is
 * {@code "Tran ID already exist..."} ({@code "exist"}, not {@code "exists"}); (3) {@code source} is
 * <strong>free text</strong>, never enum-validated, and the auto-id is
 * {@code findMaxTransactionId()+1} zero-padded to 16 (empty table &rarr; {@code "0000000000000001"}).</p>
 *
 * <h2>Date-field note (resolved by reading the generated DTO)</h2>
 * <p>{@link TransactionDto#getOriginationDate()} / {@link TransactionDto#getProcessingDate()} are
 * {@link LocalDate} (not {@code String}). An already-parsed {@link LocalDate} can never be malformed,
 * so the COBOL date-<em>format</em> edits (steps 2d/2e) always pass and are <strong>not</strong>
 * separately tested (see {@code Step2d_2e_DateFormat} for the documented skip). The {@code CSUTLDTC}
 * calendar-<em>validity</em> interaction (steps 2f/2g) is still fully exercised against the mock.</p>
 *
 * <h2>Decimal fidelity (AAP &sect;0.7.3)</h2>
 * <p>The monetary {@code amount} is a {@link BigDecimal}; it is compared with
 * {@link BigDecimal#compareTo(BigDecimal)} (via AssertJ {@code isEqualByComparingTo}),
 * <strong>never</strong> the scale-sensitive {@link BigDecimal#equals(Object)}.</p>
 *
 * <p><strong>Traceability.</strong> Parity target {@code app/cbl/COTRN02C.cbl} at the frozen COBOL
 * baseline commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material and is
 * <strong>never copied</strong> into this repository &mdash; only its behavior is reproduced.</p>
 *
 * @see TransactionAddService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionAddService — COTRN02C (CT02) add-transaction parity")
class TransactionAddServiceTest {

    // -----------------------------------------------------------------------------------------------
    // Fixture constants (representative, internally-consistent values; the ASCII fixtures
    // app/data/ASCII/cardxref.txt and dailytran.txt are byte-level reference only).
    // -----------------------------------------------------------------------------------------------

    /** A numeric account id whose {@code FUNCTION NUMVAL} value is {@link #VALID_ACCOUNT_ID_NUM}. */
    private static final String VALID_ACCOUNT_ID = "00000000010";

    /** The numeric form of {@link #VALID_ACCOUNT_ID} used as the {@code CXACAIX} lookup key. */
    private static final long VALID_ACCOUNT_ID_NUM = 10L;

    /** The 16-character card number the cross-reference resolves to (written back onto the request). */
    private static final String XREF_CARD_NUM = "1234567890123456";

    // -----------------------------------------------------------------------------------------------
    // Collaborators (mocked) and system under test (constructor-injected).
    // -----------------------------------------------------------------------------------------------

    /** {@code TRANSACT} repository: max-id discovery ({@code findMaxTransactionId}) and the write. */
    @Mock
    private TransactionRepository transactionRepository;

    /** {@code CARDXREF}/{@code CXACAIX} repository: account- and card-keyed cross-reference lookups. */
    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /** {@code CALL 'CSUTLDTC'} replacement: mocked here (deep parity lives in unit/validation). */
    @Mock
    private DateValidationService dateValidationService;

    /** The system under test, wired with the three mocks via constructor injection. */
    @InjectMocks
    private TransactionAddService service;

    // -----------------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------------

    /**
     * Builds a fully-valid add request: account-keyed (the card number is resolved via the xref),
     * every data field populated and well-formed, and {@code confirm = "Y"}. Each test starts from a
     * fresh instance and mutates exactly the field(s) under test, so the targeted check is the first
     * (and only) failure.
     *
     * @return a fresh, fully-valid {@link TransactionDto}
     */
    private static TransactionDto validRequest() {
        TransactionDto dto = new TransactionDto();
        dto.setAccountId(VALID_ACCOUNT_ID); // numeric -> resolved via CXACAIX
        dto.setCardNumber(null);            // resolved (written back) from the xref
        dto.setTypeCode("01");
        dto.setCategoryCode("0001");
        dto.setSource("POS");               // free text — NEVER enum-validated
        dto.setDescription("Coffee shop purchase");
        dto.setAmount(new BigDecimal("12.34"));
        dto.setOriginationDate(LocalDate.of(2024, 3, 9));
        dto.setProcessingDate(LocalDate.of(2024, 3, 10));
        dto.setMerchantId("123456789");
        dto.setMerchantName("ACME");
        dto.setMerchantCity("SEATTLE");
        dto.setMerchantZip("98101");
        dto.setConfirm("Y");
        return dto;
    }

    /**
     * Builds the cross-reference row the account lookup resolves to: card number
     * {@link #XREF_CARD_NUM} owned by account {@link #VALID_ACCOUNT_ID_NUM}.
     *
     * @return a populated {@link CardCrossReference}
     */
    private static CardCrossReference xref() {
        CardCrossReference x = new CardCrossReference();
        x.setXrefCardNum(XREF_CARD_NUM);
        x.setXrefAcctId(VALID_ACCOUNT_ID_NUM);
        return x;
    }

    /**
     * Stubs only the step-1 account cross-reference lookup so {@code VALIDATE-INPUT-KEY-FIELDS}
     * passes (account path) and the entered card number is written back to {@link #XREF_CARD_NUM}.
     * Used by tests whose target failure is in the data-field cascade <em>before</em> the date
     * checks (the {@link DateValidationService} mock is intentionally left unstubbed so STRICT
     * Mockito would flag it if the path unexpectedly reached the date validation).
     */
    private void stubStep1Valid() {
        when(cardCrossReferenceRepository.findByXrefAcctId(VALID_ACCOUNT_ID_NUM))
                .thenReturn(List.of(xref()));
    }

    /**
     * Stubs step 1 (as {@link #stubStep1Valid()}) plus the {@code CSUTLDTC} date validity so the
     * cascade reaches the merchant-id edit, the confirm evaluation and the add. Both date calls
     * ({@code "20240309"} and {@code "20240310"}) return {@link DateValidationResult#ofValid()}.
     */
    private void stubKeyAndDatesValid() {
        stubStep1Valid();
        when(dateValidationService.validateDate(anyString())).thenReturn(DateValidationResult.ofValid());
    }

    // -----------------------------------------------------------------------------------------------
    // Step 1 — VALIDATE-INPUT-KEY-FIELDS (COTRN02C L193-230): account-precedence xref resolution.
    // -----------------------------------------------------------------------------------------------

    /**
     * The key-field cascade ({@code EVALUATE TRUE}, account precedence). Account is evaluated first;
     * a present-but-non-numeric id fails immediately; a numeric id drives the {@code CXACAIX} lookup
     * (not-found rejects, found writes the card number back); only when no account id is present is
     * the card number evaluated; when neither is present the request is rejected.
     */
    @Nested
    @DisplayName("Step 1 — VALIDATE-INPUT-KEY-FIELDS (account precedence, xref write-back)")
    class Step1_KeyFields {

        @Test
        @DisplayName("account present & non-numeric -> 'Account ID must be Numeric...' (no collaborator touched)")
        void accountNonNumeric_rejectedBeforeAnyLookup() {
            TransactionDto request = validRequest();
            request.setAccountId("ABC");   // present but not numeric
            request.setCardNumber(null);

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Account ID must be Numeric");

            // COBOL fails on the IS NUMERIC class test before any READ -> no collaborator is reached.
            verifyNoInteractions(cardCrossReferenceRepository, transactionRepository, dateValidationService);
        }

        @Test
        @DisplayName("account numeric but CXACAIX empty -> 'Account ID NOT found...' (READ-CXACAIX NOTFND)")
        void accountNumericNotFound_rejected() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            when(cardCrossReferenceRepository.findByXrefAcctId(VALID_ACCOUNT_ID_NUM))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Account ID NOT found");

            verifyNoInteractions(transactionRepository, dateValidationService);
        }

        @Test
        @DisplayName("account found -> card written back, step 1 passes (later data edit fires)")
        void accountFound_step1PassesAndContinues() {
            // Proves step 1 succeeded (no key-field error) by making a LATER check fail: with the card
            // resolved from the xref, the next failure is the first empty data edit (type code).
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setTypeCode("");        // first data-field empty edit
            stubStep1Valid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Type CD can NOT be empty");

            // The resolved card number was written back onto the request (MOVE XREF-CARD-NUM TO CARDNINI).
            assertThat(request.getCardNumber()).isEqualTo(XREF_CARD_NUM);
            verifyNoInteractions(transactionRepository, dateValidationService);
        }

        @Test
        @DisplayName("no account, card present & non-numeric -> 'Card Number must be Numeric...'")
        void cardNonNumeric_rejectedBeforeAnyLookup() {
            TransactionDto request = validRequest();
            request.setAccountId("");       // no account -> evaluate card branch
            request.setCardNumber("ABC");   // present but not numeric

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Card Number must be Numeric");

            verifyNoInteractions(cardCrossReferenceRepository, transactionRepository, dateValidationService);
        }

        @Test
        @DisplayName("no account, card numeric but CCXREF empty -> 'Card Number NOT found...'")
        void cardNumericNotFound_rejected() {
            TransactionDto request = validRequest();
            request.setAccountId("");
            request.setCardNumber(XREF_CARD_NUM);
            when(cardCrossReferenceRepository.findById(XREF_CARD_NUM)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Card Number NOT found");

            verifyNoInteractions(transactionRepository, dateValidationService);
        }

        @Test
        @DisplayName("neither account nor card -> 'Account or Card Number must be entered...'")
        void neitherEntered_rejected() {
            TransactionDto request = validRequest();
            request.setAccountId("");
            request.setCardNumber("");

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Account or Card Number must be entered");

            verifyNoInteractions(cardCrossReferenceRepository, transactionRepository, dateValidationService);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step 2a — VALIDATE-INPUT-DATA-FIELDS empty edits (COTRN02C L251-320): first-empty-wins order.
    // -----------------------------------------------------------------------------------------------

    /**
     * The eleven non-empty edits, in COBOL field order (Type, Category, Source, Description, Amount,
     * Orig Date, Proc Date, Merchant ID, Merchant Name, Merchant City, Merchant Zip). Each test starts
     * from {@link #validRequest()}, blanks exactly one field (a {@code null}/blank string, or
     * {@code null} for the {@link BigDecimal} amount and the two {@link LocalDate} dates) and asserts
     * the matching verbatim message. Step 1 is stubbed to pass; the date validator is intentionally
     * not stubbed because every empty edit fires before the date checks.
     */
    @Nested
    @DisplayName("Step 2a — empty-field edits (first empty wins, COBOL order)")
    class Step2a_EmptyChecks {

        @ParameterizedTest(name = "{0}")
        @MethodSource(
                "com.cardemo.unit.service.transaction.TransactionAddServiceTest#emptyFieldCases")
        @DisplayName("each blank field yields its verbatim 'can NOT be empty' message")
        void blankField_yieldsItsEmptyMessage(String label, Consumer<TransactionDto> blanker,
                String expectedMessage) {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            blanker.accept(request);
            stubStep1Valid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining(expectedMessage);

            verifyNoInteractions(transactionRepository, dateValidationService);
        }

        @Test
        @DisplayName("ordering: Type empty precedes Source empty -> 'Type CD can NOT be empty...'")
        void typeBeforeSource_typeWins() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setTypeCode("");        // earlier edit
            request.setSource("");          // later edit (must NOT win)
            stubStep1Valid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Type CD can NOT be empty");
        }

        @Test
        @DisplayName("ordering: Source empty precedes Merchant Zip empty -> 'Source can NOT be empty...'")
        void sourceBeforeMerchantZip_sourceWins() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setSource("");          // earlier edit
            request.setMerchantZip("");     // later edit (must NOT win)
            stubStep1Valid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Source can NOT be empty");
        }
    }

    /**
     * Supplies the eleven empty-edit cases as (label, mutator, expected-message) tuples in the exact
     * COBOL evaluation order. Declared {@code static} on the enclosing class so the {@code @Nested}
     * {@code @ParameterizedTest} can reference it by fully-qualified {@code #}-method name.
     *
     * @return the ordered stream of empty-field cases
     */
    static Stream<Arguments> emptyFieldCases() {
        return Stream.of(
                Arguments.arguments("typeCode blank",
                        (Consumer<TransactionDto>) dto -> dto.setTypeCode(""),
                        "Type CD can NOT be empty"),
                Arguments.arguments("categoryCode blank",
                        (Consumer<TransactionDto>) dto -> dto.setCategoryCode(""),
                        "Category CD can NOT be empty"),
                Arguments.arguments("source blank",
                        (Consumer<TransactionDto>) dto -> dto.setSource(""),
                        "Source can NOT be empty"),
                Arguments.arguments("description blank",
                        (Consumer<TransactionDto>) dto -> dto.setDescription(""),
                        "Description can NOT be empty"),
                Arguments.arguments("amount null",
                        (Consumer<TransactionDto>) dto -> dto.setAmount(null),
                        "Amount can NOT be empty"),
                Arguments.arguments("originationDate null",
                        (Consumer<TransactionDto>) dto -> dto.setOriginationDate(null),
                        "Orig Date can NOT be empty"),
                Arguments.arguments("processingDate null",
                        (Consumer<TransactionDto>) dto -> dto.setProcessingDate(null),
                        "Proc Date can NOT be empty"),
                Arguments.arguments("merchantId blank",
                        (Consumer<TransactionDto>) dto -> dto.setMerchantId(""),
                        "Merchant ID can NOT be empty"),
                Arguments.arguments("merchantName blank",
                        (Consumer<TransactionDto>) dto -> dto.setMerchantName(""),
                        "Merchant Name can NOT be empty"),
                Arguments.arguments("merchantCity blank",
                        (Consumer<TransactionDto>) dto -> dto.setMerchantCity(""),
                        "Merchant City can NOT be empty"),
                Arguments.arguments("merchantZip blank",
                        (Consumer<TransactionDto>) dto -> dto.setMerchantZip(""),
                        "Merchant Zip can NOT be empty"));
    }

    // -----------------------------------------------------------------------------------------------
    // Step 2b — numeric edits (COTRN02C L322-337): type code then category code.
    // -----------------------------------------------------------------------------------------------

    /**
     * The numeric edits that run after all eleven fields are non-empty: the type code is checked
     * first, then the category code. A non-numeric value yields the matching verbatim message.
     */
    @Nested
    @DisplayName("Step 2b — numeric edits (type code, then category code)")
    class Step2b_NumericChecks {

        @Test
        @DisplayName("typeCode non-numeric (all fields non-empty) -> 'Type CD must be Numeric...'")
        void typeCodeNonNumeric_rejected() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setTypeCode("AB");      // non-empty but not numeric
            stubStep1Valid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Type CD must be Numeric");

            verifyNoInteractions(transactionRepository, dateValidationService);
        }

        @Test
        @DisplayName("categoryCode non-numeric (typeCode numeric) -> 'Category CD must be Numeric...'")
        void categoryCodeNonNumeric_rejected() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setTypeCode("01");      // numeric -> passes the earlier edit
            request.setCategoryCode("XY");  // non-numeric -> this edit fires
            stubStep1Valid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Category CD must be Numeric");

            verifyNoInteractions(transactionRepository, dateValidationService);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step 2c — amount-format edit (COTRN02C L339-351): positional picture -99999999.99.
    // -----------------------------------------------------------------------------------------------

    /**
     * The amount-format edit, the {@link BigDecimal} realization of the COBOL positional picture
     * {@code -99999999.99}: it rejects an amount whose magnitude needs more than eight integer digits
     * ({@code |amount| >= 10^8}) or whose scale exceeds two. A value within both bounds passes (proven
     * by reaching the confirm prompt). All comparisons are {@code compareTo}-based, never
     * {@code equals}.
     */
    @Nested
    @DisplayName("Step 2c — amount format (-99999999.99)")
    class Step2c_AmountFormat {

        @Test
        @DisplayName("amount with more than 8 integer digits -> 'Amount should be in format -99999999.99'")
        void amountTooManyIntegerDigits_rejected() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setAmount(new BigDecimal("100000000.00")); // 9 integer digits -> |amt| == 10^8
            stubStep1Valid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Amount should be in format -99999999.99");

            verifyNoInteractions(transactionRepository, dateValidationService);
        }

        @Test
        @DisplayName("amount with scale > 2 -> 'Amount should be in format -99999999.99'")
        void amountScaleTooLarge_rejected() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setAmount(new BigDecimal("1.234")); // scale 3
            stubStep1Valid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Amount should be in format -99999999.99");

            verifyNoInteractions(transactionRepository, dateValidationService);
        }

        @Test
        @DisplayName("amount at the 8-digit/scale-2 boundary passes the amount edit (fails later at confirm)")
        void amountAtBoundary_passesAmountEdit() {
            // 99999999.99 is the largest value the screen picture allows (8 integer digits, scale 2).
            // It must NOT trigger the amount error; we prove it by reaching the confirm prompt with
            // confirm = "N" — which can only happen if the amount (and the date) edits passed.
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setAmount(new BigDecimal("99999999.99"));
            request.setConfirm("N");
            stubKeyAndDatesValid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Confirm to add this transaction")
                    .hasMessageNotContaining("Amount should be in format");
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step 2d/2e — date-format edits (COTRN02C L353-381): NOT separately testable here (LocalDate).
    // -----------------------------------------------------------------------------------------------

    /**
     * <strong>Documented skip.</strong> The COBOL date-format edits (2d origination, 2e processing)
     * verify the positional {@code YYYY-MM-DD} shape and would yield
     * {@code "Orig Date should be in format YYYY-MM-DD"} / {@code "Proc Date should be in format
     * YYYY-MM-DD"} on a malformed string. In the generated {@link TransactionDto} these fields are
     * {@link LocalDate} (Jackson has already parsed the ISO wire form), so an inbound value can never
     * be malformed and these edits always pass &mdash; they exist in the service only as a structural
     * one-for-one mirror of the COBOL control flow. There is consequently no input that can drive the
     * format messages from this layer, so no failing-format test is written here. The single positive
     * test below documents that a well-formed {@link LocalDate} sails past the format stage and on
     * into the {@code CSUTLDTC} validity stage (exercised in {@link Step2f_2g_DateValidity}).
     */
    @Nested
    @DisplayName("Step 2d/2e — date format (documented skip: DTO dates are LocalDate)")
    class Step2d_2e_DateFormat {

        @Test
        @DisplayName("well-formed LocalDate dates pass the format stage (no format error reachable)")
        void localDateDates_passFormatStage() {
            // confirm = "N" makes the request fail at the confirm step; reaching that point proves
            // both date-format edits (and the CSUTLDTC validity edits) passed for a real LocalDate.
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setConfirm("N");
            stubKeyAndDatesValid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Confirm to add this transaction")
                    .hasMessageNotContaining("should be in format YYYY-MM-DD");
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step 2f/2g — CSUTLDTC calendar-validity edits (COTRN02C L389-427): origination then processing.
    // -----------------------------------------------------------------------------------------------

    /**
     * The {@code CALL 'CSUTLDTC'} calendar-validity edits, mapped to {@link DateValidationService}
     * (mocked). Origination is validated first, then processing. The service dash-strips each
     * {@link LocalDate} to the 8-character {@code YYYYMMDD} picture before the call, and an
     * {@link DateValidationResult#ofInvalid(String) invalid} result drives the verbatim message.
     */
    @Nested
    @DisplayName("Step 2f/2g — CSUTLDTC validity (orig then proc), dash-stripped argument")
    class Step2f_2g_DateValidity {

        @Test
        @DisplayName("origination invalid -> 'Orig Date - Not a valid date...' (proc never validated)")
        void originationInvalid_rejected() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            stubStep1Valid();
            // Only the origination call is reached; stub exactly that argument (no proc stub needed).
            when(dateValidationService.validateDate("20240309"))
                    .thenReturn(DateValidationResult.ofInvalid("bad origination date"));

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Orig Date - Not a valid date");

            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("origination valid, processing invalid -> 'Proc Date - Not a valid date...'")
        void processingInvalid_rejected() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            stubStep1Valid();
            when(dateValidationService.validateDate("20240309"))
                    .thenReturn(DateValidationResult.ofValid());   // orig passes
            when(dateValidationService.validateDate("20240310"))
                    .thenReturn(DateValidationResult.ofInvalid("bad processing date")); // proc fails

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Proc Date - Not a valid date");

            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("dash-stripping parity: CSUTLDTC receives '20240309' then '20240310' (8 chars, no dashes)")
        void datesAreDashStrippedToYyyymmdd() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            stubKeyAndDatesValid();
            when(transactionRepository.findMaxTransactionId()).thenReturn(Optional.empty());

            // A fully-valid request reaches the add; capture the two date arguments the service passed.
            assertThatCode(() -> service.addTransaction(request)).doesNotThrowAnyException();

            ArgumentCaptor<String> dateCaptor = ArgumentCaptor.forClass(String.class);
            verify(dateValidationService, times(2)).validateDate(dateCaptor.capture());
            assertThat(dateCaptor.getAllValues()).containsExactly("20240309", "20240310");
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step 2h — merchant-id numeric edit (COTRN02C L430): the LAST data edit, after the dates.
    // -----------------------------------------------------------------------------------------------

    /**
     * The merchant-id numeric edit, which the COBOL runs <em>last</em> among the data edits &mdash;
     * after both date checks. Proving it fires only once the dates have passed confirms its position
     * at the end of {@code VALIDATE-INPUT-DATA-FIELDS}.
     */
    @Nested
    @DisplayName("Step 2h — merchant id numeric (fires AFTER the date checks)")
    class Step2h_MerchantIdNumeric {

        @Test
        @DisplayName("merchantId non-numeric (dates valid) -> 'Merchant ID must be Numeric...'")
        void merchantIdNonNumeric_rejectedAfterDates() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setMerchantId("ABC");   // non-empty (passes 2a) but not numeric
            stubKeyAndDatesValid();         // dates must pass first so this is provably the last edit

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Merchant ID must be Numeric");

            verifyNoInteractions(transactionRepository);
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step 3 — EVALUATE CONFIRMI (COTRN02C L169-188): the Y/N add confirmation.
    // -----------------------------------------------------------------------------------------------

    /**
     * The add-confirmation evaluation, reached only after every key and data edit has passed.
     * {@code 'Y'}/{@code 'y'} proceeds to the add; {@code 'N'}/{@code 'n'} or a blank/absent value
     * re-prompts ({@code "Confirm to add this transaction..."}); any other value is rejected
     * ({@code "Invalid value. Valid values are (Y/N)..."}).
     */
    @Nested
    @DisplayName("Step 3 — EVALUATE CONFIRMI (Y/y add; N/blank re-prompt; other invalid)")
    class Step3_Confirm {

        @ParameterizedTest(name = "confirm = [{0}]")
        @NullSource
        @ValueSource(strings = {"N", "n", "", " "})
        @DisplayName("N/n/null/blank -> 'Confirm to add this transaction...'")
        void confirmNoOrBlank_reprompts(String confirm) {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setConfirm(confirm);
            stubKeyAndDatesValid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Confirm to add this transaction");

            // Confirmation failed before the add -> the transaction file was never touched.
            verify(transactionRepository, never()).findMaxTransactionId();
            verify(transactionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("confirm = 'X' -> 'Invalid value. Valid values are (Y/N)...'")
        void confirmInvalidValue_rejected() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setConfirm("X");
            stubKeyAndDatesValid();

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Invalid value. Valid values are (Y/N)");

            verify(transactionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("confirm = 'y' (lowercase) proceeds to the add (success)")
        void confirmLowercaseY_proceeds() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setConfirm("y");        // lowercase must be accepted (WHEN 'y')
            stubKeyAndDatesValid();
            when(transactionRepository.findMaxTransactionId()).thenReturn(Optional.empty());

            TransactionDto result = service.addTransaction(request);

            assertThat(result.getTransactionId()).isEqualTo("0000000000000001");
            verify(transactionRepository).saveAndFlush(any(Transaction.class));
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step 4 — ADD-TRANSACTION + WRITE-TRANSACT-FILE (COTRN02C L442-466, L711-749): id + write.
    // -----------------------------------------------------------------------------------------------

    /**
     * The successful add: auto-id generation ({@code findMaxTransactionId() + 1}, zero-padded to 16),
     * the record build (with the COBOL {@code MOVE} type conversions) and the verbatim success message
     * &mdash; including the <strong>two spaces</strong> between {@code "successfully."} and
     * {@code "Your"}. {@code source} is stored as free text, never enum-validated.
     */
    @Nested
    @DisplayName("Step 4 — ADD-TRANSACTION success (auto-id, record build, two-space message)")
    class Step4_AddSuccess {

        @Test
        @DisplayName("empty TRANSACT -> first id '0000000000000001' (ENDFILE -> MOVE ZEROS, +1)")
        void emptyTable_firstIdIsOne() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            stubKeyAndDatesValid();
            when(transactionRepository.findMaxTransactionId()).thenReturn(Optional.empty());

            TransactionDto result = service.addTransaction(request);

            assertThat(result.getTransactionId()).isEqualTo("0000000000000001");
            // The verbatim success message embeds the new id and carries TWO spaces after "successfully.".
            assertThat(service.buildSuccessMessage(result.getTransactionId()))
                    .isEqualTo("Transaction added successfully.  Your Tran ID is 0000000000000001.")
                    .contains("added successfully.  Your Tran ID is");
            verify(transactionRepository).saveAndFlush(any(Transaction.class));
        }

        @Test
        @DisplayName("non-empty TRANSACT (max 41) -> next id '0000000000000042' + two-space message")
        void nonEmptyTable_nextIdIsMaxPlusOne() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            stubKeyAndDatesValid();
            when(transactionRepository.findMaxTransactionId())
                    .thenReturn(Optional.of("0000000000000041"));

            TransactionDto result = service.addTransaction(request);

            assertThat(result.getTransactionId()).isEqualTo("0000000000000042");
            assertThat(service.buildSuccessMessage(result.getTransactionId()))
                    // assert the two-space substring explicitly (trailing + leading space from the STRING)
                    .contains("added successfully.  Your Tran ID is 0000000000000042.");
        }

        @Test
        @DisplayName("persisted entity reflects the COBOL MOVEs (cat=Integer, merchId=Long, source free text, amount compareTo)")
        void savedEntity_carriesConvertedFields() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);    // resolved to XREF_CARD_NUM via the account path
            stubKeyAndDatesValid();
            when(transactionRepository.findMaxTransactionId())
                    .thenReturn(Optional.of("0000000000000041"));

            service.addTransaction(request);

            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).saveAndFlush(txnCaptor.capture());
            Transaction saved = txnCaptor.getValue();

            assertThat(saved.getTranId()).isEqualTo("0000000000000042");
            assertThat(saved.getTranTypeCd()).isEqualTo("01");
            assertThat(saved.getTranCatCd()).isEqualTo(Integer.valueOf(1)); // "0001" -> 1
            assertThat(saved.getTranMerchantId()).isEqualTo(Long.valueOf(123456789L)); // "123456789" -> 123456789
            assertThat(saved.getTranSource()).isEqualTo("POS");            // free text, unchanged
            assertThat(saved.getTranCardNum()).isEqualTo(XREF_CARD_NUM);    // written back from the xref
            // BigDecimal compared with compareTo (scale-insensitive), NEVER equals.
            assertThat(saved.getTranAmt()).isEqualByComparingTo(new BigDecimal("12.34"));
        }

        @Test
        @DisplayName("source is free text (e.g. 'WIRE') -> no enum validation; stored verbatim")
        void source_isFreeTextNotEnum() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            request.setSource("WIRE");      // arbitrary text — must NOT be rejected
            stubKeyAndDatesValid();
            when(transactionRepository.findMaxTransactionId()).thenReturn(Optional.empty());

            assertThatCode(() -> service.addTransaction(request)).doesNotThrowAnyException();

            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).saveAndFlush(txnCaptor.capture());
            assertThat(txnCaptor.getValue().getTranSource()).isEqualTo("WIRE");
        }
    }

    // -----------------------------------------------------------------------------------------------
    // Step 4 (duplicate) — WRITE-TRANSACT-FILE DFHRESP(DUPKEY)/DFHRESP(DUPREC) (COTRN02C L735-739).
    // -----------------------------------------------------------------------------------------------

    /**
     * The duplicate-key path: when the synchronous {@code saveAndFlush} surfaces a
     * {@link DataIntegrityViolationException} (the JPA mapping of the COBOL
     * {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)} branch), the service throws a
     * {@link DuplicateRecordException} carrying the verbatim {@code "Tran ID already exist..."} message
     * &mdash; note {@code "exist"}, not {@code "exists"}.
     */
    @Nested
    @DisplayName("Step 4 — duplicate key (DFHRESP(DUPKEY)/DUPREC -> DuplicateRecordException)")
    class Step4_Duplicate {

        @Test
        @DisplayName("saveAndFlush throws DataIntegrityViolationException -> 'Tran ID already exist...'")
        void duplicateKey_throwsDuplicateRecordException() {
            TransactionDto request = validRequest();
            request.setCardNumber(null);
            stubKeyAndDatesValid();
            when(transactionRepository.findMaxTransactionId()).thenReturn(Optional.empty());
            when(transactionRepository.saveAndFlush(any(Transaction.class)))
                    .thenThrow(new DataIntegrityViolationException("dup"));

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(DuplicateRecordException.class)
                    .hasMessageContaining("Tran ID already exist"); // "exist", not "exists"
        }
    }
}
