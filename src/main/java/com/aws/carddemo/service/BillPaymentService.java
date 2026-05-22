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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Bill-payment service — the Java migration of the 572-line CICS COBOL program
 * {@code app/cbl/COBIL00C.cbl} (TRANID {@code CB00}, the bill-payment
 * dispatcher). Pays off the FULL current balance of an account in a single
 * transaction by:
 * <ol>
 *   <li>validating the operator's confirmation prompt response (Y/N gate);</li>
 *   <li>reading the {@link Account} master record by the supplied account ID;</li>
 *   <li>rejecting if the current balance is at or below zero;</li>
 *   <li>resolving the cardholder PAN via the {@link CardXref} cross-reference
 *       keyed on the account ID;</li>
 *   <li>generating the next sequential {@code TRAN-ID} via the
 *       {@code findTopByOrderByTransactionIdDesc()} read pattern (Java
 *       replacement for the COBOL {@code STARTBR HIGH-VALUES} + {@code READPREV}
 *       sequence on the TRANSACT VSAM KSDS);</li>
 *   <li>writing a {@link Transaction} record with the hard-coded payment
 *       constants (type code, category code, source, description, merchant
 *       ID/name/city/zip) and amount equal to the current account balance;</li>
 *   <li>zeroing the account current balance via {@code REWRITE ACCTDAT}
 *       equivalent (JPA {@code save()}).</li>
 * </ol>
 *
 * <h2>COBOL Provenance — COBIL00C.cbl</h2>
 *
 * <p>The COBOL {@code PROCESS-ENTER-KEY} paragraph (lines 154–244)
 * orchestrates the full workflow. The Java migration preserves the exact
 * sequence and reject semantics:
 *
 * <table border="1">
 *   <caption>COBOL paragraph → Java method mapping</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th><th>Notes</th></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY} (lines 154–244)</td>
 *       <td>{@link #payBill(BillPaymentRequest)}</td>
 *       <td>Entry point — orchestrates all subsequent reads/writes.</td></tr>
 *   <tr><td>{@code EVALUATE CONFIRMI} (lines 173–191)</td>
 *       <td>{@link #payBill} confirmation gate</td>
 *       <td>Y/N → proceed; N → cancelled reject; OTHER → confirm reject.</td></tr>
 *   <tr><td>{@code READ-ACCTDAT-FILE} (lines 343–372)</td>
 *       <td>{@link AccountRepository#findById(Object)}</td>
 *       <td>{@code Optional.empty()} maps to {@code DFHRESP(NOTFND)}.</td></tr>
 *   <tr><td>{@code IF ACCT-CURR-BAL &lt;= ZEROS} (line 198)</td>
 *       <td>{@link BigDecimal#signum()} check</td>
 *       <td>signum() ≤ 0 → "You have nothing to pay..." reject.</td></tr>
 *   <tr><td>{@code READ-CXACAIX-FILE} (lines 408–436)</td>
 *       <td>{@link CardXrefRepository#findByAccountId(String)}</td>
 *       <td>{@code Optional.empty()} maps to "Account ID NOT found..." reject.</td></tr>
 *   <tr><td>{@code STARTBR-TRANSACT-FILE} + {@code READPREV-TRANSACT-FILE}
 *           (lines 212–217, 441–496)</td>
 *       <td>{@link TransactionRepository#findTopByOrderByTransactionIdDesc()}</td>
 *       <td>Returns highest existing TRAN-ID; +1 yields the new ID.</td></tr>
 *   <tr><td>{@code WRITE-TRANSACT-FILE} (lines 510–547)</td>
 *       <td>{@link TransactionRepository#save(Object)}</td>
 *       <td>JPA persist of the populated {@link Transaction}.</td></tr>
 *   <tr><td>{@code UPDATE-ACCTDAT-FILE} (lines 377–403)</td>
 *       <td>{@link AccountRepository#save(Object)}</td>
 *       <td>JPA save with the balance set to {@link #ZERO_BALANCE}.</td></tr>
 *   <tr><td>{@code GET-CURRENT-TIMESTAMP} (lines 249–267)</td>
 *       <td>{@link LocalDateTime#now(Clock)}</td>
 *       <td>Driven by an injected {@link Clock} so tests are deterministic.</td></tr>
 * </table>
 *
 * <h2>Hard-Coded Transaction Constants (COBIL00C lines 220–229)</h2>
 *
 * <p>Every bill-payment transaction record carries the same set of constants,
 * preserved verbatim from the COBOL source per AAP §0.10.4
 * (Immutable Boundaries):
 * <ul>
 *   <li>{@code TRAN-TYPE-CD} = {@link #TRAN_TYPE_PAYMENT} ({@code "02"})</li>
 *   <li>{@code TRAN-CAT-CD} = {@link #TRAN_CAT_PAYMENT} ({@code "0002"} —
 *       the 4-digit zero-padded form of the COBOL {@code MOVE 2 TO TRAN-CAT-CD}
 *       on a {@code PIC 9(04)} field)</li>
 *   <li>{@code TRAN-SOURCE} = {@link #TRAN_SOURCE_POS_TERM}
 *       ({@code "POS TERM  "} — 10-character space-padded)</li>
 *   <li>{@code TRAN-DESC} = {@link #TRAN_DESC_BILL_PAYMENT}
 *       ({@code "BILL PAYMENT - ONLINE"})</li>
 *   <li>{@code TRAN-MERCHANT-ID} = {@link #BILL_PAYMENT_MERCHANT_ID}
 *       ({@code "999999999"})</li>
 *   <li>{@code TRAN-MERCHANT-NAME} = {@link #BILL_PAYMENT_MERCHANT_NAME}
 *       ({@code "BILL PAYMENT"})</li>
 *   <li>{@code TRAN-MERCHANT-CITY} = {@link #BILL_PAYMENT_MERCHANT_CITY}
 *       ({@code "N/A"})</li>
 *   <li>{@code TRAN-MERCHANT-ZIP} = {@link #BILL_PAYMENT_MERCHANT_ZIP}
 *       ({@code "N/A"})</li>
 * </ul>
 *
 * <h2>Financial Precision (AAP §0.10.3)</h2>
 *
 * <p>Both the {@link Account#getCurrentBalance()} read value and the
 * {@link Transaction#getAmount()} write value use {@link BigDecimal} with
 * scale 2 (COBOL {@code PIC S9(10)V99} for the account balance and
 * {@code PIC S9(09)V99} for the transaction amount). When the service zeroes
 * the account balance after the payment, it uses {@link #ZERO_BALANCE}
 * ({@code new BigDecimal("0.00")}) so the scale is preserved at 2 (not 0 as
 * would result from {@code BigDecimal.ZERO}).
 *
 * <h2>Java Migration Additions Documented Per AAP §0.10.2</h2>
 *
 * <ul>
 *   <li><b>{@link Clock} injection</b> — the transaction origin and process
 *       timestamps are stamped from an injected {@link Clock} so tests can
 *       deterministically verify the timestamp values. Java replacement for
 *       the COBOL {@code EXEC CICS ASKTIME} + {@code FORMATTIME} calls in
 *       {@code GET-CURRENT-TIMESTAMP} (lines 249–267).</li>
 *   <li><b>Collapsed confirmation reject branches</b> — the COBOL workflow
 *       distinguishes between {@code SPACES/LOW-VALUES} (operator did not
 *       enter anything → re-prompt) and {@code WHEN OTHER} (operator entered
 *       garbage → "Invalid value" reject). The Java REST request payload has
 *       no separate "no value yet" semantic — every request carries a string
 *       (possibly empty) — so both branches collapse into the single
 *       "Please confirm to make bill payment..." reject. Reason: the
 *       confirmation prompt cycle is a screen-flow artifact that does not
 *       exist in a single-request REST call.</li>
 * </ul>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>The service contains the full bill-payment business logic (confirmation
 * gate, balance check, TRAN-ID generation, hard-coded constant population,
 * scale-2 BigDecimal arithmetic, dual-write commit). Tests must call this
 * service directly and must not reimplement any of that logic inside test
 * bodies; mocks are limited to the three JPA repository boundaries
 * ({@link AccountRepository}, {@link CardXrefRepository},
 * {@link TransactionRepository}) and the {@link Clock}.
 *
 * @see BillPaymentRequest
 * @see BillPaymentResult
 * @see AccountRepository
 * @see CardXrefRepository
 * @see TransactionRepository
 */
public class BillPaymentService {

    // =========================================================================
    // COBOL-equivalent reject and success messages (AAP §0.10.4)
    // =========================================================================

    /**
     * Success message format pattern — {@code "Payment successful. Your
     * Transaction ID is {tranId}."}. Mirrors the COBOL {@code STRING}
     * construct at COBIL00C.cbl lines 527–531: {@code STRING 'Payment
     * successful. ' DELIMITED BY SIZE ' Your Transaction ID is ' DELIMITED BY
     * SIZE TRAN-ID DELIMITED BY SPACE '.' DELIMITED BY SIZE INTO WS-MESSAGE}.
     */
    static final String MSG_PAYMENT_SUCCESS_FORMAT =
            "Payment successful. Your Transaction ID is %s.";

    /**
     * Reject message — empty/null account ID. Mirrors COBIL00C.cbl line 161:
     * {@code MOVE 'Acct ID can NOT be empty...' TO WS-MESSAGE}.
     */
    static final String MSG_ACCOUNT_ID_EMPTY = "Acct ID can NOT be empty...";

    /**
     * Reject message — account not found in the master file. Mirrors
     * COBIL00C.cbl line 361 ({@code READ-ACCTDAT-FILE} NOTFND branch) and
     * line 425 ({@code READ-CXACAIX-FILE} NOTFND branch — both surface the
     * same reject to the operator).
     */
    static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /**
     * Reject message — current balance is at or below zero (nothing to pay).
     * Mirrors COBIL00C.cbl line 201: {@code MOVE 'You have nothing to pay...'
     * TO WS-MESSAGE}.
     */
    static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /**
     * Reject message — confirmation cancelled by operator. The COBOL workflow
     * (lines 178–181) clears the screen and re-prompts; the REST equivalent
     * surfaces this as an explicit "cancelled" message since there is no
     * screen redisplay in a single-request flow.
     */
    static final String MSG_CONFIRMATION_CANCELLED =
            "Confirmation cancelled by user. Try again";

    /**
     * Reject message — confirmation prompt missing or invalid. The COBOL
     * workflow has two reject branches for the non-Y/N case: {@code SPACES /
     * LOW-VALUES} falls through to {@code 'Confirm to make a bill payment...'}
     * (lines 237–238) and {@code WHEN OTHER} emits {@code 'Invalid value.
     * Valid values are (Y/N)...'} (lines 187–188). The Java migration collapses
     * both into this single "please confirm" reject because the REST request
     * has no separate "no value yet" vs "invalid value" semantic.
     */
    static final String MSG_PLEASE_CONFIRM =
            "Please confirm to make bill payment...";

    // =========================================================================
    // COBOL hard-coded transaction constants (COBIL00C lines 220–229)
    // =========================================================================

    /**
     * {@code TRAN-TYPE-CD} = {@code "02"} per COBIL00C.cbl line 220
     * ({@code MOVE '02' TO TRAN-TYPE-CD}). The 2-character payment type
     * code per the {@code TRAN-TYPE-CD PIC X(02)} field width in
     * {@code CVTRA05Y.cpy}; references {@code trantype.txt} row 2 = Payment.
     */
    static final String TRAN_TYPE_PAYMENT = "02";

    /**
     * {@code TRAN-CAT-CD} = {@code "0002"} per COBIL00C.cbl line 221
     * ({@code MOVE 2 TO TRAN-CAT-CD}). The COBOL numeric MOVE 2 into a
     * {@code PIC 9(04)} field produces the 4-digit zero-padded string
     * {@code "0002"}; references {@code trancatg.txt} row 2 = Payment.
     */
    static final String TRAN_CAT_PAYMENT = "0002";

    /**
     * {@code TRAN-SOURCE} = {@code "POS TERM  "} per COBIL00C.cbl line 222
     * ({@code MOVE 'POS TERM' TO TRAN-SOURCE}). The COBOL literal
     * {@code 'POS TERM'} (8 characters) is space-padded to the
     * {@code TRAN-SOURCE PIC X(10)} field width — 10 characters total.
     */
    static final String TRAN_SOURCE_POS_TERM = "POS TERM  ";

    /**
     * {@code TRAN-DESC} = {@code "BILL PAYMENT - ONLINE"} per COBIL00C.cbl
     * line 223 ({@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC}). The
     * COBOL literal (21 characters) does not pad to the
     * {@code TRAN-DESC PIC X(100)} field width here because the Java
     * migration uses a free-form String column; padding (if any) is left to
     * the persistence layer.
     */
    static final String TRAN_DESC_BILL_PAYMENT = "BILL PAYMENT - ONLINE";

    /**
     * {@code TRAN-MERCHANT-ID} = {@code "999999999"} per COBIL00C.cbl
     * line 226 ({@code MOVE 999999999 TO TRAN-MERCHANT-ID}). The
     * {@code TRAN-MERCHANT-ID PIC 9(09)} field stores the synthetic merchant
     * identifier for bill-payment transactions (real merchant IDs would never
     * be all 9s).
     */
    static final String BILL_PAYMENT_MERCHANT_ID = "999999999";

    /**
     * {@code TRAN-MERCHANT-NAME} = {@code "BILL PAYMENT"} per COBIL00C.cbl
     * line 227 ({@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME}). The
     * COBOL literal (12 characters) does not pad to the
     * {@code TRAN-MERCHANT-NAME PIC X(50)} field width here; persistence
     * layer handles padding if required.
     */
    static final String BILL_PAYMENT_MERCHANT_NAME = "BILL PAYMENT";

    /**
     * {@code TRAN-MERCHANT-CITY} = {@code "N/A"} per COBIL00C.cbl line 228
     * ({@code MOVE 'N/A' TO TRAN-MERCHANT-CITY}). Bill payments are not
     * geographically located, so the city field carries the explicit
     * not-applicable sentinel.
     */
    static final String BILL_PAYMENT_MERCHANT_CITY = "N/A";

    /**
     * {@code TRAN-MERCHANT-ZIP} = {@code "N/A"} per COBIL00C.cbl line 229
     * ({@code MOVE 'N/A' TO TRAN-MERCHANT-ZIP}). Bill payments are not
     * geographically located, so the ZIP field carries the explicit
     * not-applicable sentinel.
     */
    static final String BILL_PAYMENT_MERCHANT_ZIP = "N/A";

    // =========================================================================
    // Numeric and format constants
    // =========================================================================

    /**
     * {@link BigDecimal} representing the zeroed-out post-payment balance at
     * scale 2 per the COBOL {@code PIC S9(10)V99} field width. Constructed
     * via {@code new BigDecimal("0.00")} rather than {@code BigDecimal.ZERO}
     * because the latter has scale 0 — the test suite asserts scale 2 on the
     * persisted account balance per AAP §0.10.3 financial-precision mandate.
     */
    static final BigDecimal ZERO_BALANCE = new BigDecimal("0.00");

    /**
     * Width of the {@code TRAN-ID PIC X(16)} field in the {@code TRAN-RECORD}
     * layout from {@code app/cpy/CVTRA05Y.cpy}. Used to zero-pad newly
     * generated transaction identifiers to exactly 16 characters.
     */
    static final int TRAN_ID_WIDTH = 16;

    /**
     * Default starting transaction ID used when the {@code transactions}
     * table is empty (Java replacement for the COBOL
     * {@code MOVE ZEROS TO TRAN-ID} on {@code DFHRESP(ENDFILE)} at line 488
     * of COBIL00C.cbl). The first transaction in an empty store therefore
     * gets ID {@code "0000000000000001"} (zero + 1).
     */
    static final long FIRST_TRAN_ID = 1L;

    /**
     * Timestamp format used to populate {@code TRAN-ORIG-TS} and
     * {@code TRAN-PROC-TS} on the persisted {@link Transaction} record.
     * Matches the 26-character COBOL {@code PIC X(26)} timestamp format
     * (e.g. {@code "2024-01-15 00:00:00.000000"}). The COBOL
     * {@code GET-CURRENT-TIMESTAMP} paragraph (lines 249–267) builds this
     * value from {@code FORMATTIME YYYYMMDD DATESEP('-')} and
     * {@code TIME TIMESEP(':')}; the Java migration uses
     * {@link DateTimeFormatter} with the same layout.
     */
    static final DateTimeFormatter TRANSACTION_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    // =========================================================================
    // Collaborators (constructor-injected; Mockito boundary mocks in tests)
    // =========================================================================

    /**
     * JPA repository for {@link Account} entities — boundary mock in unit
     * tests per AAP §0.10.1. The service calls {@code findById(accountId)}
     * to read the account and {@code save(account)} to persist the zeroed
     * balance.
     */
    private final AccountRepository accountRepository;

    /**
     * JPA repository for {@link Transaction} entities — boundary mock in
     * unit tests. The service calls
     * {@link TransactionRepository#findTopByOrderByTransactionIdDesc()} to
     * obtain the highest existing TRAN-ID and {@code save(transaction)} to
     * persist the new payment record.
     */
    private final TransactionRepository transactionRepository;

    /**
     * JPA repository for {@link CardXref} entities — boundary mock in unit
     * tests. The service calls {@code findByAccountId(accountId)} to resolve
     * the cardholder PAN associated with the account being paid off.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Injected {@link Clock} for deterministic timestamp generation in unit
     * tests. The COBOL {@code GET-CURRENT-TIMESTAMP} paragraph (lines 249–267)
     * reads the wall clock via {@code EXEC CICS ASKTIME}; the Java migration
     * replaces this with {@link LocalDateTime#now(Clock)} so tests can pass a
     * {@link Clock#fixed} clock for reproducible assertions.
     */
    private final Clock clock;

    /**
     * Constructs a {@link BillPaymentService} with the three JPA repository
     * boundaries and the deterministic {@link Clock}. Unit tests pass
     * Mockito mocks for the repositories and a {@link Clock#fixed} clock
     * stamped at {@code 2024-01-15T00:00:00Z}; production wiring (a
     * subsequent migration agent task) will use Spring's autoconfigured
     * {@link Clock#systemUTC()} bean.
     *
     * @param accountRepository     mocked or real {@link AccountRepository}
     * @param transactionRepository mocked or real {@link TransactionRepository}
     * @param cardXrefRepository    mocked or real {@link CardXrefRepository}
     * @param clock                 mocked or real {@link Clock}
     */
    public BillPaymentService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            CardXrefRepository cardXrefRepository,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.clock = clock;
    }

    // =========================================================================
    // Public API — payBill (single entry point)
    // =========================================================================

    /**
     * Pay off the FULL current balance of the account identified by
     * {@code request.getAccountId()}, generating a payment transaction and
     * zeroing the account balance. Returns a {@link BillPaymentResult}
     * indicating success or one of the five COBOL-equivalent reject paths.
     *
     * <p>Operation sequence (mirrors COBIL00C.cbl {@code PROCESS-ENTER-KEY},
     * lines 154–244):
     * <ol>
     *   <li>Confirmation gate — {@code Y/y} → proceed; {@code N/n} → reject
     *       "cancelled" without DB access; any other value → reject "please
     *       confirm" without DB access. Validation-first guard mirrors COBOL
     *       {@code EVALUATE CONFIRMI} (lines 173–191) but collapses the
     *       SPACES/LOW-VALUES and WHEN OTHER branches per AAP §0.10.2.</li>
     *   <li>Account-ID non-empty check — null/empty → reject "Acct ID can NOT
     *       be empty..." (COBIL00C lines 159–164).</li>
     *   <li>Account read — {@code Optional.empty()} → reject "Account ID NOT
     *       found..." (COBIL00C line 361).</li>
     *   <li>Zero-balance check — {@code currentBalance.signum() &lt;= 0} →
     *       reject "You have nothing to pay..." (COBIL00C lines 197–206).</li>
     *   <li>Card cross-reference read — {@code Optional.empty()} → reject
     *       "Account ID NOT found..." (COBIL00C lines 423–428 reuse the
     *       same reject as the ACCTDAT NOTFND branch).</li>
     *   <li>TRAN-ID generation — read last TRAN-ID, increment by 1, zero-pad
     *       to 16 characters. Java replacement for the COBOL
     *       {@code STARTBR HIGH-VALUES} + {@code READPREV} + {@code +1}
     *       pattern (COBIL00C lines 212–217).</li>
     *   <li>Transaction record build + persist — populate every field per
     *       the COBOL constants (COBIL00C lines 218–232) and call
     *       {@link TransactionRepository#save(Object)}.</li>
     *   <li>Account balance update — set {@link Account#setCurrentBalance}
     *       to {@link #ZERO_BALANCE} and call
     *       {@link AccountRepository#save(Object)}. Java replacement for
     *       {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} +
     *       {@code REWRITE ACCTDAT} (COBIL00C lines 234–235).</li>
     * </ol>
     *
     * @param request the bill-payment request carrying the target account ID
     *                and the operator's confirmation response; must not be
     *                {@code null}
     * @return a {@link BillPaymentResult} carrying the success message
     *         (containing the new TRAN-ID) or one of the five COBOL-equivalent
     *         rejection messages; never {@code null}
     */
    public BillPaymentResult payBill(BillPaymentRequest request) {
        // -----------------------------------------------------------------
        // Step 1 — Confirmation gate (COBIL00C lines 173–191 EVALUATE
        // CONFIRMI). The Java migration short-circuits BEFORE any DB
        // access for the cancelled (N) and invalid (non-Y/N) paths so the
        // test suite can prove the validation-first ordering via
        // verify(repo, never()).save(any()) on the request payload.
        // -----------------------------------------------------------------
        String confirmation = request.getConfirmation();
        if (isConfirmationCancelled(confirmation)) {
            return BillPaymentResult.failure(MSG_CONFIRMATION_CANCELLED);
        }
        if (!isConfirmationProceed(confirmation)) {
            return BillPaymentResult.failure(MSG_PLEASE_CONFIRM);
        }

        // -----------------------------------------------------------------
        // Step 2 — Account-ID non-empty validation (COBIL00C lines 159–164
        // 'Acct ID can NOT be empty...'). Treats null, empty, and pure-
        // whitespace inputs as empty.
        // -----------------------------------------------------------------
        String accountId = request.getAccountId();
        if (accountId == null || accountId.trim().isEmpty()) {
            return BillPaymentResult.failure(MSG_ACCOUNT_ID_EMPTY);
        }

        // -----------------------------------------------------------------
        // Step 3 — Read ACCTDAT by account ID (COBIL00C lines 343–372
        // READ-ACCTDAT-FILE). Optional.empty() maps to DFHRESP(NOTFND)
        // and surfaces the 'Account ID NOT found...' reject (line 361).
        // -----------------------------------------------------------------
        Optional<Account> accountOpt = accountRepository.findById(accountId);
        if (accountOpt.isEmpty()) {
            return BillPaymentResult.failure(MSG_ACCOUNT_NOT_FOUND);
        }
        Account account = accountOpt.get();

        // -----------------------------------------------------------------
        // Step 4 — Zero-balance guard (COBIL00C lines 197–206
        // 'You have nothing to pay...'). BigDecimal.signum() returns the
        // sign without performing any arithmetic; signum() <= 0 covers
        // both zero balances and the (programmatically impossible but
        // defensively handled) negative-balance case.
        // -----------------------------------------------------------------
        BigDecimal currentBalance = account.getCurrentBalance();
        if (currentBalance == null || currentBalance.signum() <= 0) {
            return BillPaymentResult.failure(MSG_NOTHING_TO_PAY);
        }

        // -----------------------------------------------------------------
        // Step 5 — Read CXACAIX cross-reference (COBIL00C lines 408–436
        // READ-CXACAIX-FILE). Optional.empty() maps to DFHRESP(NOTFND)
        // and surfaces the same 'Account ID NOT found...' reject as the
        // ACCTDAT path (COBIL00C line 425).
        // -----------------------------------------------------------------
        Optional<CardXref> cardXrefOpt = cardXrefRepository.findByAccountId(accountId);
        if (cardXrefOpt.isEmpty()) {
            return BillPaymentResult.failure(MSG_ACCOUNT_NOT_FOUND);
        }
        CardXref cardXref = cardXrefOpt.get();

        // -----------------------------------------------------------------
        // Step 6 — Generate next TRAN-ID (COBIL00C lines 212–217
        // STARTBR HIGH-VALUES + READPREV + ADD 1). Returns
        // FIRST_TRAN_ID (1) when no transactions exist.
        // -----------------------------------------------------------------
        String newTransactionId = generateNextTransactionId();

        // -----------------------------------------------------------------
        // Step 7 — Build the transaction record with all hard-coded
        // constants and the current balance as the amount (COBIL00C
        // lines 218–232). The amount carries the pre-payment balance,
        // not the post-payment zero.
        // -----------------------------------------------------------------
        String timestamp = LocalDateTime.now(clock).format(TRANSACTION_TIMESTAMP_FORMAT);
        Transaction transaction = buildPaymentTransaction(
                newTransactionId,
                currentBalance,
                cardXref.getCardNumber(),
                timestamp);

        // -----------------------------------------------------------------
        // Step 8 — Persist the new transaction (COBIL00C lines 510–547
        // WRITE-TRANSACT-FILE).
        // -----------------------------------------------------------------
        transactionRepository.save(transaction);

        // -----------------------------------------------------------------
        // Step 9 — Zero the account balance and persist (COBIL00C lines
        // 234–235 COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT;
        // PERFORM UPDATE-ACCTDAT-FILE). ZERO_BALANCE is constructed with
        // scale 2 to preserve the COBOL PIC S9(10)V99 precision per AAP
        // §0.10.3.
        // -----------------------------------------------------------------
        account.setCurrentBalance(ZERO_BALANCE);
        accountRepository.save(account);

        // -----------------------------------------------------------------
        // Step 10 — Success outcome (COBIL00C lines 527–531
        // 'Payment successful. Your Transaction ID is XXX.').
        // -----------------------------------------------------------------
        return BillPaymentResult.success(
                String.format(MSG_PAYMENT_SUCCESS_FORMAT, newTransactionId));
    }

    // =========================================================================
    // Private helpers (small, focused, no business-logic duplication in tests)
    // =========================================================================

    /**
     * Tests whether the supplied confirmation value matches the COBOL
     * {@code WHEN 'Y' WHEN 'y'} branch (COBIL00C lines 174–177). Both upper
     * and lower case are accepted; trailing whitespace is trimmed.
     *
     * @param confirmation the raw confirmation value from the request
     * @return {@code true} if the value is {@code "Y"} or {@code "y"} after
     *         trimming; {@code false} otherwise
     */
    private static boolean isConfirmationProceed(String confirmation) {
        if (confirmation == null) {
            return false;
        }
        String trimmed = confirmation.trim();
        return "Y".equalsIgnoreCase(trimmed);
    }

    /**
     * Tests whether the supplied confirmation value matches the COBOL
     * {@code WHEN 'N' WHEN 'n'} branch (COBIL00C lines 178–181). Both upper
     * and lower case are accepted; trailing whitespace is trimmed.
     *
     * @param confirmation the raw confirmation value from the request
     * @return {@code true} if the value is {@code "N"} or {@code "n"} after
     *         trimming; {@code false} otherwise
     */
    private static boolean isConfirmationCancelled(String confirmation) {
        if (confirmation == null) {
            return false;
        }
        String trimmed = confirmation.trim();
        return "N".equalsIgnoreCase(trimmed);
    }

    /**
     * Generate the next zero-padded 16-character TRAN-ID. Reads the highest
     * existing TRAN-ID via
     * {@link TransactionRepository#findTopByOrderByTransactionIdDesc()},
     * parses it as a long, adds 1, and formats the result as a
     * {@link #TRAN_ID_WIDTH}-character zero-padded numeric string.
     *
     * <p>Java replacement for the COBOL pattern at COBIL00C lines 212–217:
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
     * sequence from), the COBOL {@code READPREV} returns
     * {@code DFHRESP(ENDFILE)} and the workflow at line 488 issues
     * {@code MOVE ZEROS TO TRAN-ID}; the Java equivalent surfaces this as
     * {@link Optional#empty()} and seeds the next ID with
     * {@link #FIRST_TRAN_ID} (1) so the first generated TRAN-ID is
     * {@code "0000000000000001"}.
     *
     * @return a 16-character zero-padded numeric string suitable for use
     *         as a {@link Transaction#setTransactionId(String)} value
     */
    private String generateNextTransactionId() {
        Optional<Transaction> last = transactionRepository.findTopByOrderByTransactionIdDesc();
        long nextId;
        if (last.isPresent() && last.get().getTransactionId() != null) {
            // Parse the existing 16-character TRAN-ID as a long and add 1.
            // The bill-payment subspace uses zero-padded numeric IDs so
            // numeric parsing is safe; interest-transaction IDs (with PARM
            // prefix) cannot appear here because the daily/bill-payment
            // subspaces have monotonically increasing numeric values that
            // remain below the interest-transaction PARM prefix range.
            String lastTranId = last.get().getTransactionId().trim();
            try {
                nextId = Long.parseLong(lastTranId) + 1L;
            } catch (NumberFormatException ex) {
                // Defensive fallback: if the highest TRAN-ID is non-numeric
                // (should not occur in well-formed data) start over from 1.
                nextId = FIRST_TRAN_ID;
            }
        } else {
            nextId = FIRST_TRAN_ID;
        }
        return String.format("%0" + TRAN_ID_WIDTH + "d", nextId);
    }

    /**
     * Build a populated {@link Transaction} record carrying the hard-coded
     * bill-payment constants (type code, category code, source, description,
     * merchant ID/name/city/zip) plus the dynamic fields (transaction ID,
     * amount, card number, origin and process timestamps).
     *
     * <p>Mirrors the COBOL field-by-field {@code MOVE} block at
     * {@code COBIL00C.cbl} lines 218–232.
     *
     * @param transactionId  the freshly generated 16-character TRAN-ID
     * @param amount         the pre-payment account balance (becomes the
     *                       transaction amount)
     * @param cardNumber     the cardholder PAN resolved from the card
     *                       cross-reference
     * @param timestamp      the formatted current timestamp from the
     *                       injected {@link Clock}
     * @return a fully populated {@link Transaction} ready for
     *         {@link TransactionRepository#save(Object)}
     */
    private static Transaction buildPaymentTransaction(
            String transactionId,
            BigDecimal amount,
            String cardNumber,
            String timestamp) {
        Transaction transaction = new Transaction();
        transaction.setTransactionId(transactionId);
        transaction.setTransactionTypeCode(TRAN_TYPE_PAYMENT);
        transaction.setTransactionCategoryCode(TRAN_CAT_PAYMENT);
        transaction.setSource(TRAN_SOURCE_POS_TERM);
        transaction.setDescription(TRAN_DESC_BILL_PAYMENT);
        transaction.setAmount(amount);
        transaction.setMerchantId(BILL_PAYMENT_MERCHANT_ID);
        transaction.setMerchantName(BILL_PAYMENT_MERCHANT_NAME);
        transaction.setMerchantCity(BILL_PAYMENT_MERCHANT_CITY);
        transaction.setMerchantZip(BILL_PAYMENT_MERCHANT_ZIP);
        transaction.setCardNumber(cardNumber);
        transaction.setOriginTimestamp(timestamp);
        transaction.setProcessTimestamp(timestamp);
        return transaction;
    }

    // =========================================================================
    // Suppress unused-import warnings on ZoneId for IDEs that flag transitive
    // imports — ZoneId is referenced via clock.getZone() if a subsequent
    // refactor adds zone-aware timestamping. Keeping it imported maintains
    // a stable import block during the migration's incremental refinement.
    // =========================================================================

    /**
     * Internal accessor for the injected {@link Clock}'s default zone — used
     * only when subclasses or test diagnostics need the timezone associated
     * with the deterministic clock. Returns {@link ZoneId#systemDefault()}
     * when the injected clock is null (defensive — the constructor accepts
     * null implicitly via field assignment but production wiring never
     * passes null).
     *
     * @return the {@link ZoneId} associated with the injected clock; never
     *         {@code null}
     */
    ZoneId clockZone() {
        return clock != null ? clock.getZone() : ZoneId.systemDefault();
    }
}
