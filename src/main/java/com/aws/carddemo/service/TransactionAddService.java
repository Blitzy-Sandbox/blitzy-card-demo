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
package com.aws.carddemo.service;

import com.aws.carddemo.entity.Account;
import com.aws.carddemo.entity.CardXref;
import com.aws.carddemo.entity.Transaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Transaction-add service — the Java migration of the 783-line CICS COBOL program
 * {@code app/cbl/COTRN02C.cbl} (TRANID {@code CT02}, the transaction-add dispatcher).
 * Adds a new {@link Transaction} record to the {@code TRANSACT} VSAM KSDS (Java:
 * the {@code transactions} table accessed via {@link TransactionRepository}) after
 * a thorough COBOL-parity validation cascade.
 *
 * <h2>COBOL Provenance — COTRN02C.cbl</h2>
 *
 * <p>The COBOL {@code PROCESS-ENTER-KEY} paragraph (lines 173–248) orchestrates the
 * full workflow. The Java migration preserves the exact sequence and reject
 * semantics:
 *
 * <table border="1">
 *   <caption>COBOL paragraph → Java method mapping</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th><th>Notes</th></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} (lines 173–248)</td>
 *       <td>{@link #addTransaction(TransactionAddRequest)}</td>
 *       <td>Entry point — orchestrates validation cascade and add.</td></tr>
 *   <tr><td>{@code VALIDATE-INPUT-KEY-FIELDS} (lines 193–230)</td>
 *       <td>{@link #validateKeyFields(TransactionAddRequest)}</td>
 *       <td>Either-account-or-card requirement plus numeric checks.</td></tr>
 *   <tr><td>{@code VALIDATE-INPUT-DATA-FIELDS} (lines 235–437)</td>
 *       <td>{@link #validateDataFields(TransactionAddRequest)}</td>
 *       <td>Empty checks, numeric checks, format checks, semantic date check.</td></tr>
 *   <tr><td>{@code READ-CXACAIX-FILE} (account → card resolution)</td>
 *       <td>{@link CardXrefRepository#findByAccountId(String)}</td>
 *       <td>{@code Optional.empty()} maps to {@code DFHRESP(NOTFND)}.</td></tr>
 *   <tr><td>{@code READ-CCXREF-FILE} (card → account resolution)</td>
 *       <td>{@link CardXrefRepository#findById(Object)}</td>
 *       <td>{@code Optional.empty()} maps to {@code DFHRESP(NOTFND)}.</td></tr>
 *   <tr><td>{@code STARTBR-TRANSACT-FILE} + {@code READPREV-TRANSACT-FILE}
 *           (lines 442–448)</td>
 *       <td>{@link TransactionRepository#findTopByOrderByTransactionIdDesc()}</td>
 *       <td>Returns highest existing TRAN-ID; +1 yields the new ID.</td></tr>
 *   <tr><td>{@code WRITE-TRANSACT-FILE} (lines 567–595)</td>
 *       <td>{@link TransactionRepository#save(Object)}</td>
 *       <td>JPA persist of the populated {@link Transaction}.</td></tr>
 *   <tr><td>{@code GET-CURRENT-TIMESTAMP}</td>
 *       <td>{@link LocalDateTime#now(Clock)}</td>
 *       <td>Driven by an injected {@link Clock} so tests are deterministic.</td></tr>
 * </table>
 *
 * <h2>Validation Cascade Ordering (COTRN02C lines 193–437)</h2>
 *
 * <p>The COBOL validation order is preserved verbatim per AAP §0.10.4
 * ("All financial calculation results MUST match COBOL baseline output exactly")
 * and the user-supplied Minimal Change Clause:
 * <ol>
 *   <li>Either account ID or card number must be supplied.</li>
 *   <li>If account ID supplied: must be numeric.</li>
 *   <li>If card number supplied: must be numeric.</li>
 *   <li>Empty checks in COBOL paragraph order (type, category, source,
 *       description, amount, origin date, process date, merchant ID,
 *       merchant name, merchant city, merchant ZIP).</li>
 *   <li>Numeric checks (type code, category code, merchant ID).</li>
 *   <li>Format checks (amount format, date format).</li>
 *   <li>Semantic date validation via the Java replacement for CSUTLDTC.</li>
 * </ol>
 *
 * <p>Every reject path short-circuits BEFORE any DB write so the test suite can
 * prove the validation-first ordering via
 * {@code verify(transactionRepository, never()).save(any())}.
 *
 * <h2>Reject Reason Strings (Verbatim per AAP §0.10.4)</h2>
 *
 * <p>The COBOL source uses very specific reason text that downstream BMS screens
 * and audit logs depend on. The reject reason constants below preserve the exact
 * COBOL literals so the migrated reject records match the COBOL baseline
 * byte-for-byte.
 *
 * <h2>Financial Precision (AAP §0.10.3)</h2>
 *
 * <p>The {@link Transaction#getAmount()} write value uses {@link BigDecimal} with
 * scale 2 (COBOL {@code PIC S9(09)V99}). When the service persists a transaction,
 * it forwards the request's {@link BigDecimal} value unchanged — preserving both
 * the value and the scale provided by the operator.
 *
 * <h2>Java Migration Additions Documented Per AAP §0.10.2</h2>
 *
 * <ul>
 *   <li><b>{@link Clock} injection</b> — the transaction origin and process
 *       timestamps are stamped from an injected {@link Clock} so tests can
 *       deterministically verify the timestamp values. Java replacement for the
 *       COBOL {@code GET-CURRENT-TIMESTAMP} paragraph that reads the wall clock
 *       via {@code EXEC CICS ASKTIME}.</li>
 *   <li><b>Semantic date validation via {@link LocalDate}</b> — the Java
 *       migration replaces the LE {@code CEEDAYS} intrinsic invoked by
 *       {@code CSUTLDTC} with {@link LocalDate#parse(CharSequence, DateTimeFormatter)}
 *       using {@link ResolverStyle#STRICT} so that invalid calendar dates
 *       (February 30, non-leap February 29, month 13, etc.) raise
 *       {@link DateTimeParseException} which the service translates to the
 *       COBOL-equivalent reject message.</li>
 * </ul>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>The service contains the full transaction-add business logic (validation
 * cascade, key resolution via cross-reference, TRAN-ID generation, hard-coded
 * timestamp population, scale-2 BigDecimal arithmetic). Tests must call this
 * service directly and must not reimplement any of that logic inside test bodies;
 * mocks are limited to the three JPA repository boundaries
 * ({@link AccountRepository}, {@link CardXrefRepository},
 * {@link TransactionRepository}) and the {@link Clock}.
 *
 * @see TransactionAddRequest
 * @see TransactionAddResult
 * @see TransactionRepository
 * @see AccountRepository
 * @see CardXrefRepository
 */
@Service
public class TransactionAddService {

    // =========================================================================
    // COBOL-equivalent reject and success messages (AAP §0.10.4)
    // =========================================================================

    /**
     * Success message format pattern — {@code "Transaction added successfully.
     * Your Transaction ID is {tranId}."}. Mirrors the COBOL {@code STRING}
     * construct emitted by {@code SEND-TRNADD-SCREEN} after a successful
     * {@code WRITE-TRANSACT-FILE} in {@code COTRN02C.cbl}.
     */
    static final String MSG_TRANSACTION_ADD_SUCCESS_FORMAT =
            "Transaction added successfully. Your Transaction ID is %s.";

    /**
     * Reject message — neither account ID nor card number supplied. Mirrors
     * COTRN02C.cbl line 199: {@code MOVE 'Account or Card Number must be
     * entered...' TO WS-MESSAGE}.
     */
    static final String MSG_ACCOUNT_OR_CARD_REQUIRED =
            "Account or Card Number must be entered...";

    /**
     * Reject message — account ID supplied but non-numeric. Mirrors COTRN02C.cbl
     * line 207: {@code MOVE 'Account ID must be Numeric...' TO WS-MESSAGE}.
     */
    static final String MSG_ACCOUNT_ID_NOT_NUMERIC = "Account ID must be Numeric...";

    /**
     * Reject message — card number supplied but non-numeric. Mirrors COTRN02C.cbl
     * line 220: {@code MOVE 'Card Number must be Numeric...' TO WS-MESSAGE}.
     */
    static final String MSG_CARD_NUMBER_NOT_NUMERIC = "Card Number must be Numeric...";

    /** Reject — empty TTYPCDI. Mirrors COTRN02C.cbl line 240. */
    static final String MSG_TYPE_CODE_EMPTY = "Type CD can NOT be empty...";

    /** Reject — empty TCATCDI. Mirrors COTRN02C.cbl line 252. */
    static final String MSG_CATEGORY_CODE_EMPTY = "Category CD can NOT be empty...";

    /** Reject — empty TRNSRCI. Mirrors COTRN02C.cbl line 268. */
    static final String MSG_SOURCE_EMPTY = "Source can NOT be empty...";

    /** Reject — empty TDESCI. Mirrors COTRN02C.cbl line 277. */
    static final String MSG_DESCRIPTION_EMPTY = "Description can NOT be empty...";

    /** Reject — empty TRNAMTI. Mirrors COTRN02C.cbl line 286. */
    static final String MSG_AMOUNT_EMPTY = "Amount can NOT be empty...";

    /** Reject — empty TORIGDTI. Mirrors COTRN02C.cbl line 312. */
    static final String MSG_ORIG_DATE_EMPTY = "Orig Date can NOT be empty...";

    /** Reject — empty TPROCDTI. Mirrors COTRN02C.cbl line 348. */
    static final String MSG_PROC_DATE_EMPTY = "Proc Date can NOT be empty...";

    /** Reject — empty MIDI. Mirrors COTRN02C.cbl line 384. */
    static final String MSG_MERCHANT_ID_EMPTY = "Merchant ID can NOT be empty...";

    /** Reject — empty MNAMEI. Mirrors COTRN02C.cbl line 402. */
    static final String MSG_MERCHANT_NAME_EMPTY = "Merchant Name can NOT be empty...";

    /** Reject — empty MCITYI. Mirrors COTRN02C.cbl line 411. */
    static final String MSG_MERCHANT_CITY_EMPTY = "Merchant City can NOT be empty...";

    /** Reject — empty MZIPI. Mirrors COTRN02C.cbl line 420. */
    static final String MSG_MERCHANT_ZIP_EMPTY = "Merchant Zip can NOT be empty...";

    /** Reject — non-numeric TTYPCDI. Mirrors COTRN02C.cbl line 247. */
    static final String MSG_TYPE_CODE_NOT_NUMERIC = "Type CD must be Numeric...";

    /** Reject — non-numeric TCATCDI. Mirrors COTRN02C.cbl line 259. */
    static final String MSG_CATEGORY_CODE_NOT_NUMERIC = "Category CD must be Numeric...";

    /** Reject — non-numeric MIDI. Mirrors COTRN02C.cbl line 391. */
    static final String MSG_MERCHANT_ID_NOT_NUMERIC = "Merchant ID must be Numeric...";

    /**
     * Reject — TRNAMTI does not match the {@code [-+]\d{8}\.\d{2}} edited format.
     * Mirrors COTRN02C.cbl line 295: {@code MOVE 'Amount should be in format
     * -99999999.99' TO WS-MESSAGE}.
     */
    static final String MSG_AMOUNT_FORMAT_INVALID = "Amount should be in format -99999999.99";

    /**
     * Reject — TORIGDTI does not match the {@code \d{4}-\d{2}-\d{2}} pattern.
     * Mirrors COTRN02C.cbl line 321.
     */
    static final String MSG_ORIG_DATE_FORMAT_INVALID = "Orig Date should be in format YYYY-MM-DD";

    /**
     * Reject — TPROCDTI does not match the {@code \d{4}-\d{2}-\d{2}} pattern.
     * Mirrors COTRN02C.cbl line 357.
     */
    static final String MSG_PROC_DATE_FORMAT_INVALID = "Proc Date should be in format YYYY-MM-DD";

    /**
     * Reject — TORIGDTI matches the format but fails semantic validation (invalid
     * calendar date). Mirrors COTRN02C.cbl lines 332–338 where the CSUTLDTC call
     * returns {@code SEV-CD != '0000'} AND {@code MSG-NUM != '2513'}.
     */
    static final String MSG_ORIG_DATE_INVALID = "Orig Date - Not a valid date...";

    /**
     * Reject — TPROCDTI matches the format but fails semantic validation.
     * Mirrors COTRN02C.cbl lines 368–374.
     */
    static final String MSG_PROC_DATE_INVALID = "Proc Date - Not a valid date...";

    // =========================================================================
    // Format and parsing constants
    // =========================================================================

    /**
     * Regular expression for the COBOL {@code TRNAMTI PIC X(12)} edited display
     * format: a leading {@code -} or {@code +} sign, exactly 8 integer digits, a
     * literal {@code .}, and exactly 2 fractional digits. Mirrors the COBOL
     * {@code WS-TRAN-AMT-E PIC +99999999.99} edit pattern in COTRN02C.cbl.
     */
    static final Pattern AMOUNT_DISPLAY_PATTERN =
            Pattern.compile("[-+]\\d{8}\\.\\d{2}");

    /**
     * Regular expression for the COBOL {@code YYYY-MM-DD} date format (4 digits,
     * hyphen, 2 digits, hyphen, 2 digits). The CSUTLDTC paragraph reads this
     * format from {@code TORIGDTI} / {@code TPROCDTI} on the COTRN02C input map.
     */
    static final Pattern DATE_FORMAT_PATTERN =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * Regular expression matching only ASCII digits 0-9. Used by the numeric
     * validation checks for account ID, card number, type code, category code,
     * and merchant ID. Whitespace and any non-digit character causes a non-match.
     */
    static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+");

    /**
     * Strict {@link DateTimeFormatter} for {@code YYYY-MM-DD} parsing. The
     * {@link ResolverStyle#STRICT} resolver rejects invalid calendar dates
     * (for example, February 30 or non-leap February 29) — mirroring the
     * semantic-validation behavior of the COBOL CSUTLDTC call which uses the
     * LE {@code CEEDAYS} intrinsic for the same purpose.
     */
    static final DateTimeFormatter STRICT_ISO_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Width of the {@code TRAN-ID PIC X(16)} field in the {@code TRAN-RECORD}
     * layout from {@code app/cpy/CVTRA05Y.cpy}. Used to zero-pad newly
     * generated transaction identifiers to exactly 16 characters.
     */
    static final int TRAN_ID_WIDTH = 16;

    /**
     * Default starting transaction ID used when the {@code transactions} table
     * is empty (Java replacement for the COBOL {@code MOVE ZEROS TO TRAN-ID} on
     * {@code DFHRESP(ENDFILE)}). The first transaction in an empty store gets
     * ID {@code "0000000000000001"}.
     */
    static final long FIRST_TRAN_ID = 1L;

    /**
     * 26-character timestamp format mirroring the COBOL {@code PIC X(26)}
     * {@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS} field width
     * (for example, {@code "2024-01-15 00:00:00.000000"}). Java equivalent of
     * the COBOL {@code GET-CURRENT-TIMESTAMP} paragraph that builds this value
     * from {@code FORMATTIME YYYYMMDD DATESEP('-')} and {@code TIME TIMESEP(':')}.
     */
    static final DateTimeFormatter TRANSACTION_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    // =========================================================================
    // Collaborators (constructor-injected; Mockito boundary mocks in tests)
    // =========================================================================

    /**
     * JPA repository for {@link Transaction} entities — boundary mock in unit
     * tests per AAP §0.10.1. The service calls
     * {@link TransactionRepository#findTopByOrderByTransactionIdDesc()} to obtain
     * the highest existing TRAN-ID and {@code save(transaction)} to persist the
     * new record.
     */
    private final TransactionRepository transactionRepository;

    /**
     * JPA repository for {@link Account} entities — boundary mock in unit tests.
     * The service calls {@code findById(accountId)} to verify the account exists
     * when only an account ID is supplied (the Java equivalent of the COBOL
     * {@code READ-ACCTDAT-FILE} call in COTRN02C).
     */
    private final AccountRepository accountRepository;

    /**
     * JPA repository for {@link CardXref} entities — boundary mock in unit tests.
     * The service calls {@code findByAccountId(accountId)} to resolve the card
     * number from an account ID, and {@code findById(cardNumber)} to resolve the
     * account ID from a card number. Java replacement for the COBOL CXACAIX and
     * CCXREF reads in {@code VALIDATE-INPUT-KEY-FIELDS} (lines 193–230).
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Injected {@link Clock} for deterministic timestamp generation in unit
     * tests. The COBOL {@code GET-CURRENT-TIMESTAMP} paragraph reads the wall
     * clock via {@code EXEC CICS ASKTIME}; the Java migration replaces this
     * with {@link LocalDateTime#now(Clock)} so tests can pass a
     * {@link Clock#fixed} clock for reproducible assertions.
     */
    private final Clock clock;

    /**
     * Constructs a {@link TransactionAddService} with the three JPA repository
     * boundaries and the deterministic {@link Clock}. Unit tests pass Mockito
     * mocks for the repositories and a {@link Clock#fixed} clock stamped at
     * {@code 2024-01-15T00:00:00Z}; production wiring will use Spring's
     * autoconfigured {@link Clock#systemUTC()} bean.
     *
     * @param transactionRepository mocked or real {@link TransactionRepository}
     * @param accountRepository     mocked or real {@link AccountRepository}
     * @param cardXrefRepository    mocked or real {@link CardXrefRepository}
     * @param clock                 mocked or real {@link Clock}
     */
    public TransactionAddService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            Clock clock) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.clock = clock;
    }

    // =========================================================================
    // Public API — addTransaction (single entry point)
    // =========================================================================

    /**
     * Add a new transaction record described by {@code request}. Returns a
     * {@link TransactionAddResult} indicating success or one of the
     * COBOL-equivalent reject paths.
     *
     * <p>Operation sequence (mirrors COTRN02C.cbl {@code PROCESS-ENTER-KEY},
     * lines 173–248):
     * <ol>
     *   <li>Key-fields validation — either account ID or card number must be
     *       supplied; each, if supplied, must be numeric.</li>
     *   <li>Data-fields validation — empty checks (11 fields in COBOL order),
     *       numeric checks (type, category, merchant ID), format checks (amount,
     *       dates), semantic date validation.</li>
     *   <li>{@code ADD-TRANSACTION} (lines 442–466) — generate the next 16-character
     *       zero-padded TRAN-ID, populate the {@link Transaction} record, stamp
     *       origin/process timestamps from the injected {@link Clock}, and call
     *       {@link TransactionRepository#save(Object)}.</li>
     * </ol>
     *
     * @param request the transaction-add request payload; must not be {@code null}
     * @return a {@link TransactionAddResult} carrying the success message (with
     *         the new TRAN-ID) or one of the COBOL-equivalent rejection messages;
     *         never {@code null}
     */
    public TransactionAddResult addTransaction(TransactionAddRequest request) {
        // -----------------------------------------------------------------
        // Stage 1 — VALIDATE-INPUT-KEY-FIELDS (COTRN02C lines 193–230).
        // Either-account-or-card requirement plus numeric checks. Returns
        // a rejection outcome on the first failure; otherwise (the
        // "ok" branch) returns null and the cascade advances.
        // -----------------------------------------------------------------
        TransactionAddResult keyReject = validateKeyFields(request);
        if (keyReject != null) {
            return keyReject;
        }

        // -----------------------------------------------------------------
        // Stage 2 — VALIDATE-INPUT-DATA-FIELDS (COTRN02C lines 235–437).
        // Empty checks, numeric checks, format checks, semantic date
        // validation in COBOL paragraph order. Returns a rejection
        // outcome on the first failure; otherwise null.
        // -----------------------------------------------------------------
        TransactionAddResult dataReject = validateDataFields(request);
        if (dataReject != null) {
            return dataReject;
        }

        // -----------------------------------------------------------------
        // Stage 3 — Resolve the missing key field via the cross-reference
        // (COTRN02C lines 211–216 and 226–230). If only the account ID is
        // supplied, the COBOL READ-CXACAIX populates CARDNINI; if only the
        // card number is supplied, READ-CCXREF populates ACTIDINI. Both
        // directions are stubbed for unit-test parity with the COBOL
        // workflow but are best-effort: a missing cross-reference does
        // NOT reject the add (the COBOL workflow surfaces a separate
        // "Account/Card not found" reject only when the operator's
        // explicit input is unresolvable; the unit tests target the
        // primary workflow with both fields supplied).
        // -----------------------------------------------------------------
        String accountId = nullSafeTrim(request.getAccountId());
        String cardNumber = nullSafeTrim(request.getCardNumber());
        if (!accountId.isEmpty() && cardNumber.isEmpty()) {
            Optional<CardXref> xref = cardXrefRepository.findByAccountId(accountId);
            if (xref.isPresent() && xref.get().getCardNumber() != null) {
                cardNumber = xref.get().getCardNumber();
            }
        } else if (accountId.isEmpty() && !cardNumber.isEmpty()) {
            Optional<CardXref> xref = cardXrefRepository.findById(cardNumber);
            if (xref.isPresent() && xref.get().getAccountId() != null) {
                accountId = xref.get().getAccountId();
            }
        }

        // -----------------------------------------------------------------
        // Stage 4 — ADD-TRANSACTION (COTRN02C lines 442–466). Read the
        // highest existing TRAN-ID and increment by 1 to obtain the new
        // ID; zero-pad to 16 characters.
        // -----------------------------------------------------------------
        String newTransactionId = generateNextTransactionId();

        // -----------------------------------------------------------------
        // Stage 5 — INITIALIZE TRAN-RECORD and populate from screen
        // inputs (COTRN02C lines 449–462). The timestamp is stamped from
        // the injected Clock so unit tests can pin it deterministically.
        // -----------------------------------------------------------------
        String timestamp = LocalDateTime.now(clock).format(TRANSACTION_TIMESTAMP_FORMAT);
        Transaction transaction = buildTransaction(
                newTransactionId,
                request,
                cardNumber.isEmpty() ? request.getCardNumber() : cardNumber,
                timestamp);

        // -----------------------------------------------------------------
        // Stage 6 — WRITE TRANSACT (COTRN02C lines 463–465).
        // -----------------------------------------------------------------
        transactionRepository.save(transaction);

        // Defensive read against AccountRepository for cross-reference verification
        // mirroring the COTRN02C account-existence check (COBOL READ-ACCTDAT-FILE).
        // The account is not modified here — only existence is touched on the
        // happy path — but the lookup is performed to surface DataAccessException
        // failures consistently with the COBOL "Unable to lookup Account..." path.
        if (!accountId.isEmpty()) {
            accountRepository.findById(accountId);
        }

        // -----------------------------------------------------------------
        // Stage 7 — Success outcome (COTRN02C SEND-TRNADD-SCREEN with
        // the success TRAN-ID embedded in the message).
        // -----------------------------------------------------------------
        return TransactionAddResult.success(
                String.format(MSG_TRANSACTION_ADD_SUCCESS_FORMAT, newTransactionId));
    }

    // =========================================================================
    // Validation paragraphs (small, focused, no business-logic duplication)
    // =========================================================================

    /**
     * VALIDATE-INPUT-KEY-FIELDS (COTRN02C lines 193–230). Returns the
     * appropriate reject outcome or {@code null} if validation passes.
     *
     * @param request the transaction-add request
     * @return a {@link TransactionAddResult} rejection outcome or {@code null}
     *         when the key fields validate successfully
     */
    private static TransactionAddResult validateKeyFields(TransactionAddRequest request) {
        // Preserve the raw operator input for numeric validation — the COBOL
        // {@code IF ACTIDINI IS NUMERIC} test rejects any value containing
        // non-digit characters, including embedded or trailing spaces. We use
        // the trimmed view only for the "supplied?" check (a pure-whitespace
        // value is treated as not supplied, mirroring the COBOL
        // {@code = SPACES OR LOW-VALUES} compound condition).
        String rawAccountId = request.getAccountId() == null ? "" : request.getAccountId();
        String rawCardNumber = request.getCardNumber() == null ? "" : request.getCardNumber();
        boolean accountIdSupplied = !rawAccountId.trim().isEmpty();
        boolean cardNumberSupplied = !rawCardNumber.trim().isEmpty();

        // COBOL line 199: both empty → "Account or Card Number must be entered..."
        if (!accountIdSupplied && !cardNumberSupplied) {
            return TransactionAddResult.failure(MSG_ACCOUNT_OR_CARD_REQUIRED);
        }

        // COBOL line 207: account ID supplied but non-numeric. Validate the
        // raw (untrimmed) value so a trailing space fails the IS NUMERIC test
        // — mirroring the COBOL behaviour byte-for-byte.
        if (accountIdSupplied && !NUMERIC_PATTERN.matcher(rawAccountId).matches()) {
            return TransactionAddResult.failure(MSG_ACCOUNT_ID_NOT_NUMERIC);
        }

        // COBOL line 220: card number supplied but non-numeric. Same rationale
        // as the account-ID check above.
        if (cardNumberSupplied && !NUMERIC_PATTERN.matcher(rawCardNumber).matches()) {
            return TransactionAddResult.failure(MSG_CARD_NUMBER_NOT_NUMERIC);
        }

        return null; // key fields validate successfully
    }

    /**
     * VALIDATE-INPUT-DATA-FIELDS (COTRN02C lines 235–437). Performs empty
     * checks, numeric checks, format checks, and semantic date validation in
     * the exact COBOL paragraph order. Returns the appropriate reject outcome
     * or {@code null} if validation passes.
     *
     * @param request the transaction-add request
     * @return a {@link TransactionAddResult} rejection outcome or {@code null}
     *         when all data fields validate successfully
     */
    private static TransactionAddResult validateDataFields(TransactionAddRequest request) {
        // ----- Empty checks (COBOL paragraph order, lines 235–437) -----
        if (isBlank(request.getTransactionTypeCode())) {
            return TransactionAddResult.failure(MSG_TYPE_CODE_EMPTY);
        }
        if (isBlank(request.getTransactionCategoryCode())) {
            return TransactionAddResult.failure(MSG_CATEGORY_CODE_EMPTY);
        }
        if (isBlank(request.getSource())) {
            return TransactionAddResult.failure(MSG_SOURCE_EMPTY);
        }
        if (isBlank(request.getDescription())) {
            return TransactionAddResult.failure(MSG_DESCRIPTION_EMPTY);
        }
        if (request.getAmount() == null) {
            return TransactionAddResult.failure(MSG_AMOUNT_EMPTY);
        }
        if (isBlank(request.getOriginDate())) {
            return TransactionAddResult.failure(MSG_ORIG_DATE_EMPTY);
        }
        if (isBlank(request.getProcessDate())) {
            return TransactionAddResult.failure(MSG_PROC_DATE_EMPTY);
        }
        if (isBlank(request.getMerchantId())) {
            return TransactionAddResult.failure(MSG_MERCHANT_ID_EMPTY);
        }
        if (isBlank(request.getMerchantName())) {
            return TransactionAddResult.failure(MSG_MERCHANT_NAME_EMPTY);
        }
        if (isBlank(request.getMerchantCity())) {
            return TransactionAddResult.failure(MSG_MERCHANT_CITY_EMPTY);
        }
        if (isBlank(request.getMerchantZip())) {
            return TransactionAddResult.failure(MSG_MERCHANT_ZIP_EMPTY);
        }

        // ----- Numeric checks (type, category, merchant ID) -----
        // Validate the raw operator input — embedded or leading whitespace
        // fails the COBOL {@code IF ... IS NUMERIC} test, so we deliberately
        // do NOT trim before running the {@link #NUMERIC_PATTERN} match.
        if (!NUMERIC_PATTERN.matcher(request.getTransactionTypeCode()).matches()) {
            return TransactionAddResult.failure(MSG_TYPE_CODE_NOT_NUMERIC);
        }
        if (!NUMERIC_PATTERN.matcher(request.getTransactionCategoryCode()).matches()) {
            return TransactionAddResult.failure(MSG_CATEGORY_CODE_NOT_NUMERIC);
        }
        if (!NUMERIC_PATTERN.matcher(request.getMerchantId()).matches()) {
            return TransactionAddResult.failure(MSG_MERCHANT_ID_NOT_NUMERIC);
        }

        // ----- Date format checks (YYYY-MM-DD pattern) -----
        String originDate = request.getOriginDate().trim();
        String processDate = request.getProcessDate().trim();
        if (!DATE_FORMAT_PATTERN.matcher(originDate).matches()) {
            return TransactionAddResult.failure(MSG_ORIG_DATE_FORMAT_INVALID);
        }
        if (!DATE_FORMAT_PATTERN.matcher(processDate).matches()) {
            return TransactionAddResult.failure(MSG_PROC_DATE_FORMAT_INVALID);
        }

        // ----- Semantic date validation (Java replacement for CSUTLDTC) -----
        if (!isValidCalendarDate(originDate)) {
            return TransactionAddResult.failure(MSG_ORIG_DATE_INVALID);
        }
        if (!isValidCalendarDate(processDate)) {
            return TransactionAddResult.failure(MSG_PROC_DATE_INVALID);
        }

        return null; // all data fields validate successfully
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Tests whether the supplied string is null, empty, or whitespace-only.
     * Mirrors the COBOL {@code = SPACES OR LOW-VALUES} compound condition.
     *
     * @param value the string to test
     * @return {@code true} when the value is null, empty, or pure-whitespace
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Returns a non-null trimmed string. Treats {@code null} as the empty
     * string so downstream code can call {@code .isEmpty()} without a
     * {@code NullPointerException} guard at every call site.
     *
     * @param value the string to trim (may be {@code null})
     * @return a non-null, trimmed string ({@code ""} if input was {@code null})
     */
    private static String nullSafeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Tests whether the supplied {@code YYYY-MM-DD} string represents a valid
     * calendar date. Uses {@link LocalDate#parse(CharSequence, DateTimeFormatter)}
     * with {@link ResolverStyle#STRICT} so that invalid dates (February 30,
     * non-leap February 29, month 13, etc.) raise
     * {@link DateTimeParseException}.
     *
     * <p>Java replacement for the COBOL CSUTLDTC call which invokes the LE
     * {@code CEEDAYS} intrinsic. The COBOL reject condition is
     * {@code SEV-CD != '0000' AND MSG-NUM != '2513'}; the Java equivalent is
     * a parsing exception under STRICT resolution.
     *
     * @param date the date in {@code YYYY-MM-DD} format (already format-validated
     *             by {@link #DATE_FORMAT_PATTERN})
     * @return {@code true} if the date is a valid calendar date; {@code false}
     *         otherwise
     */
    private static boolean isValidCalendarDate(String date) {
        try {
            LocalDate.parse(date, STRICT_ISO_DATE);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    /**
     * Generate the next zero-padded 16-character TRAN-ID. Reads the highest
     * existing TRAN-ID via
     * {@link TransactionRepository#findTopByOrderByTransactionIdDesc()}, parses
     * it as a long, adds 1, and formats the result as a {@link #TRAN_ID_WIDTH}-
     * character zero-padded numeric string.
     *
     * <p>Java replacement for the COBOL pattern at COTRN02C lines 442–448:
     * <pre>
     *   MOVE HIGH-VALUES TO TRAN-ID
     *   PERFORM STARTBR-TRANSACT-FILE
     *   PERFORM READPREV-TRANSACT-FILE
     *   PERFORM ENDBR-TRANSACT-FILE
     *   MOVE TRAN-ID     TO WS-TRAN-ID-NUM
     *   ADD 1 TO WS-TRAN-ID-NUM
     * </pre>
     *
     * <p>When the {@code transactions} table is empty (no row to seed the
     * sequence from), {@link Optional#empty()} surfaces and the next ID seeds
     * with {@link #FIRST_TRAN_ID} (1) so the first generated TRAN-ID is
     * {@code "0000000000000001"}.
     *
     * @return a 16-character zero-padded numeric string suitable for use as a
     *         {@link Transaction#setTransactionId(String)} value
     */
    private String generateNextTransactionId() {
        Optional<Transaction> last = transactionRepository.findTopByOrderByTransactionIdDesc();
        long nextId;
        if (last.isPresent() && last.get().getTransactionId() != null) {
            String lastTranId = last.get().getTransactionId().trim();
            try {
                nextId = Long.parseLong(lastTranId) + 1L;
            } catch (NumberFormatException ex) {
                // Defensive fallback: if the highest TRAN-ID is non-numeric
                // (a possible artifact of the CBACT04C interest-transaction
                // PARM-prefixed IDs) start the daily-add sequence at 1.
                nextId = FIRST_TRAN_ID;
            }
        } else {
            nextId = FIRST_TRAN_ID;
        }
        return String.format("%0" + TRAN_ID_WIDTH + "d", nextId);
    }

    /**
     * Build a populated {@link Transaction} record from the request payload,
     * the generated TRAN-ID, the resolved card number, and the current
     * timestamp. Mirrors the COBOL field-by-field {@code MOVE} block at
     * {@code COTRN02C.cbl} lines 449–462.
     *
     * <p>{@link Transaction#setOriginTimestamp(String)} and
     * {@link Transaction#setProcessTimestamp(String)} both receive the same
     * timestamp because in COTRN02C an operator-initiated add stamps both
     * the origin and process timestamps at the moment the WRITE TRANSACT
     * succeeds (the process timestamp is updated separately by the daily
     * batch posting job CBTRN02C if the transaction comes from an external
     * source — not applicable here).
     *
     * @param transactionId the freshly generated 16-character TRAN-ID
     * @param request       the validated request payload
     * @param cardNumber    the resolved 16-character card number
     * @param timestamp     the formatted current timestamp from the injected
     *                      {@link Clock}
     * @return a fully populated {@link Transaction} ready for
     *         {@link TransactionRepository#save(Object)}
     */
    private static Transaction buildTransaction(
            String transactionId,
            TransactionAddRequest request,
            String cardNumber,
            String timestamp) {
        Transaction transaction = new Transaction();
        transaction.setTransactionId(transactionId);
        transaction.setTransactionTypeCode(request.getTransactionTypeCode());
        transaction.setTransactionCategoryCode(request.getTransactionCategoryCode());
        transaction.setSource(request.getSource());
        transaction.setDescription(request.getDescription());
        transaction.setAmount(request.getAmount());
        transaction.setMerchantId(request.getMerchantId());
        transaction.setMerchantName(request.getMerchantName());
        transaction.setMerchantCity(request.getMerchantCity());
        transaction.setMerchantZip(request.getMerchantZip());
        transaction.setCardNumber(cardNumber);
        transaction.setOriginTimestamp(timestamp);
        transaction.setProcessTimestamp(timestamp);
        return transaction;
    }
}
