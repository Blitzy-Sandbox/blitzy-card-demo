/*
 * BillPaymentService.java
 *
 * Spring @Service migrating COBOL program COBIL00C.cbl (572 lines) — the online
 * bill payment functionality for the CardDemo application, transaction CB00.
 *
 * This service implements the complete bill payment flow:
 *   1. Account lookup (READ-ACCTDAT-FILE, lines 343-372)
 *   2. Balance validation (PROCESS-ENTER-KEY, lines 197-206)
 *   3. Credit limit validation (AAP requirement)
 *   4. Cross-reference resolution for card number (READ-CXACAIX-FILE, lines 408-436)
 *   5. Transaction ID auto-generation (STARTBR/READPREV/ENDBR, lines 441-505)
 *   6. Transaction record creation (lines 218-232)
 *   7. Account balance update (UPDATE-ACCTDAT-FILE, lines 377-403)
 *
 * All operations execute within a single @Transactional boundary to ensure
 * atomicity, mapping the COBOL CICS READ UPDATE + WRITE + REWRITE atomic
 * sequence that implies transactional integrity.
 *
 * COBOL Source Reference:
 *   Program: COBIL00C (app/cbl/COBIL00C.cbl, commit 27d6c6f)
 *   Transaction: CB00
 *   Record Layouts: CVACT01Y.cpy (Account), CVACT03Y.cpy (XRef), CVTRA05Y.cpy (Transaction)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.billing;

import com.cardemo.exception.CreditLimitExceededException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service implementing the online bill payment workflow migrated from COBOL
 * program {@code COBIL00C.cbl}.
 *
 * <p>The bill payment operation pays the full current account balance by:
 * <ol>
 *   <li>Reading and validating the account record (balance &gt; 0, within credit limit)</li>
 *   <li>Resolving the card number via the CXACAIX alternate index cross-reference</li>
 *   <li>Auto-generating a new sequential transaction ID</li>
 *   <li>Creating a transaction record with bill payment specifics</li>
 *   <li>Updating the account balance (subtracting the payment amount)</li>
 * </ol>
 *
 * <h3>COBOL Paragraph Coverage</h3>
 * <table>
 *   <caption>Complete paragraph mapping for COBIL00C.cbl</caption>
 *   <tr><th>COBOL Paragraph</th><th>Java Method</th><th>Notes</th></tr>
 *   <tr><td>PROCESS-ENTER-KEY (lines 154-244)</td>
 *       <td>{@link #processPayment(String)}</td>
 *       <td>Core orchestration method</td></tr>
 *   <tr><td>READ-ACCTDAT-FILE (lines 343-372)</td>
 *       <td>{@code accountRepository.findById()}</td>
 *       <td>VSAM READ → JPA findById</td></tr>
 *   <tr><td>UPDATE-ACCTDAT-FILE (lines 377-403)</td>
 *       <td>{@code accountRepository.save()}</td>
 *       <td>VSAM REWRITE → JPA save</td></tr>
 *   <tr><td>READ-CXACAIX-FILE (lines 408-436)</td>
 *       <td>{@code cardCrossReferenceRepository.findByXrefAcctId()}</td>
 *       <td>VSAM AIX READ → JPA query</td></tr>
 *   <tr><td>STARTBR/READPREV/ENDBR-TRANSACT-FILE (lines 441-505)</td>
 *       <td>{@link #generateTransactionId()}</td>
 *       <td>Browse-to-end auto-ID pattern</td></tr>
 *   <tr><td>WRITE-TRANSACT-FILE (lines 510-547)</td>
 *       <td>{@code transactionRepository.save()}</td>
 *       <td>VSAM WRITE → JPA save</td></tr>
 *   <tr><td>GET-CURRENT-TIMESTAMP (lines 249-267)</td>
 *       <td>{@code LocalDateTime.now()}</td>
 *       <td>Replaces CICS ASKTIME/FORMATTIME</td></tr>
 *   <tr><td>MAIN-PARA (lines 99-149)</td>
 *       <td>Controller routing (not in this service)</td>
 *       <td>CICS pseudo-conversational entry point</td></tr>
 *   <tr><td>SEND/RECEIVE-BILLPAY-SCREEN</td>
 *       <td>REST controller (not in this service)</td>
 *       <td>BMS SEND/RECEIVE MAP → REST endpoints</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO (lines 319-338)</td>
 *       <td>N/A — REST metadata</td>
 *       <td>Screen header not applicable to REST</td></tr>
 *   <tr><td>RETURN-TO-PREV-SCREEN (lines 273-284)</td>
 *       <td>N/A — REST navigation</td>
 *       <td>CICS XCTL not applicable to REST</td></tr>
 *   <tr><td>CLEAR-CURRENT-SCREEN (lines 552-555)</td>
 *       <td>N/A — REST stateless</td>
 *       <td>Screen clear not applicable</td></tr>
 *   <tr><td>INITIALIZE-ALL-FIELDS (lines 560-566)</td>
 *       <td>N/A — REST stateless</td>
 *       <td>Field init not applicable</td></tr>
 * </table>
 *
 * <h3>Transaction and Concurrency Semantics</h3>
 * <p>The {@code @Transactional(rollbackFor = Exception.class)} annotation ensures
 * atomicity: if the transaction write succeeds but the account balance update fails,
 * both operations are rolled back. The {@code @Version} field on the {@link Account}
 * entity provides optimistic locking, replacing the COBOL CICS READ UPDATE
 * semantics (COBIL00C.cbl lines 345-354).</p>
 *
 * <h3>Decimal Precision</h3>
 * <p>All monetary operations use {@link BigDecimal} exclusively — zero
 * {@code float}/{@code double} substitution per AAP §0.8.2. Balance comparisons
 * use {@code compareTo(BigDecimal.ZERO)}, never {@code equals()}, to avoid
 * scale-sensitivity issues.</p>
 *
 * @see com.cardemo.model.entity.Account
 * @see com.cardemo.model.entity.Transaction
 * @see com.cardemo.model.entity.CardCrossReference
 */
@Service
public class BillPaymentService {

    private static final Logger log = LoggerFactory.getLogger(BillPaymentService.class);

    /**
     * Transaction type code for bill payments — maps COBOL:
     * {@code MOVE '02' TO TRAN-TYPE-CD} (COBIL00C.cbl line 220).
     */
    private static final String BILL_PAYMENT_TYPE_CD = "02";

    /**
     * Transaction category code for bill payments — maps COBOL:
     * {@code MOVE 2 TO TRAN-CAT-CD} (COBIL00C.cbl line 221, PIC 9(04)).
     * The entity stores this as {@code Short} per the JPA mapping.
     */
    private static final short BILL_PAYMENT_CAT_CD = 2;

    /**
     * Transaction source for bill payments — maps COBOL:
     * {@code MOVE 'POS TERM' TO TRAN-SOURCE} (COBIL00C.cbl line 222).
     */
    private static final String BILL_PAYMENT_SOURCE = "POS TERM";

    /**
     * Transaction description for bill payments — maps COBOL:
     * {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC} (COBIL00C.cbl line 223).
     */
    private static final String BILL_PAYMENT_DESC = "BILL PAYMENT - ONLINE";

    /**
     * Merchant ID for bill payments — maps COBOL:
     * {@code MOVE 999999999 TO TRAN-MERCHANT-ID} (COBIL00C.cbl line 226, PIC 9(09)).
     * Stored as String to preserve the 9-character format.
     */
    private static final String BILL_PAYMENT_MERCHANT_ID = "999999999";

    /**
     * Merchant name for bill payments — maps COBOL:
     * {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME} (COBIL00C.cbl line 227).
     */
    private static final String BILL_PAYMENT_MERCHANT_NAME = "BILL PAYMENT";

    /**
     * Merchant city for bill payments — maps COBOL:
     * {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY} (COBIL00C.cbl line 228).
     */
    private static final String BILL_PAYMENT_MERCHANT_CITY = "N/A";

    /**
     * Merchant ZIP for bill payments — maps COBOL:
     * {@code MOVE 'N/A' TO TRAN-MERCHANT-ZIP} (COBIL00C.cbl line 229).
     */
    private static final String BILL_PAYMENT_MERCHANT_ZIP = "N/A";

    /**
     * Format string for transaction IDs — 16-character zero-padded numeric string,
     * matching COBOL PIC X(16) / PIC 9(16) for TRAN-ID field.
     */
    private static final String TRANSACTION_ID_FORMAT = "%016d";

    /**
     * Default starting ID when no transactions exist in the database.
     * Maps COBOL: READPREV returning ENDFILE → MOVE ZEROS TO TRAN-ID,
     * then ADD 1 TO WS-TRAN-ID-NUM (COBIL00C.cbl lines 487-488, 217).
     */
    private static final long DEFAULT_STARTING_ID = 1L;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Metrics configuration for recording transaction amount distribution.
     * Per AAP §0.7.1: {@code carddemo.transaction.amount.total} (distribution summary).
     */
    private final MetricsConfig metricsConfig;

    /**
     * Constructs a new {@code BillPaymentService} with required repository and
     * observability dependencies.
     *
     * <p>Spring auto-wires all dependencies via constructor injection. This
     * replaces the COBOL FILE SECTION declarations for ACCTDAT, TRANSACT, and
     * CXACAIX datasets in COBIL00C.cbl's WORKING-STORAGE SECTION (lines 40-42).</p>
     *
     * @param accountRepository             repository for Account entity (ACCTDAT VSAM KSDS)
     * @param transactionRepository         repository for Transaction entity (TRANSACT VSAM KSDS)
     * @param cardCrossReferenceRepository  repository for CardCrossReference entity (CXACAIX VSAM AIX)
     * @param metricsConfig                 observability metrics configuration for recording
     *                                       transaction amount distribution; must not be {@code null}
     */
    public BillPaymentService(AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              CardCrossReferenceRepository cardCrossReferenceRepository,
                              MetricsConfig metricsConfig) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.metricsConfig = metricsConfig;
    }

    /**
     * Processes a full bill payment for the specified account.
     *
     * <p>This method implements the complete COBOL {@code PROCESS-ENTER-KEY} paragraph
     * (COBIL00C.cbl lines 154-244) with the following steps executed atomically
     * within a single database transaction:</p>
     *
     * <ol>
     *   <li><strong>Account ID validation</strong> — Checks that the account ID is
     *       not null or blank (maps COBIL00C.cbl lines 159-167:
     *       {@code WHEN ACTIDINI OF COBIL0AI = SPACES OR LOW-VALUES})</li>
     *   <li><strong>Account lookup</strong> — Reads the account record via
     *       {@code accountRepository.findById()} (maps {@code READ-ACCTDAT-FILE},
     *       lines 343-372, {@code EXEC CICS READ DATASET(WS-ACCTDAT-FILE) UPDATE})</li>
     *   <li><strong>Balance validation</strong> — Verifies account balance is positive
     *       (maps COBIL00C.cbl lines 197-206: {@code IF ACCT-CURR-BAL <= ZEROS})</li>
     *   <li><strong>Credit limit validation</strong> — Validates the payment does not
     *       violate credit limit constraints (AAP requirement)</li>
     *   <li><strong>Cross-reference lookup</strong> — Resolves the card number from
     *       the account ID via the CXACAIX alternate index (maps
     *       {@code READ-CXACAIX-FILE}, lines 408-436)</li>
     *   <li><strong>Transaction ID generation</strong> — Auto-generates a new
     *       sequential transaction ID (maps {@code STARTBR/READPREV/ENDBR},
     *       lines 441-505)</li>
     *   <li><strong>Transaction creation</strong> — Builds and persists the bill
     *       payment transaction record (maps COBIL00C.cbl lines 218-232 and
     *       {@code WRITE-TRANSACT-FILE}, lines 510-547)</li>
     *   <li><strong>Balance update</strong> — Subtracts the payment amount from
     *       the account balance (maps {@code COMPUTE ACCT-CURR-BAL =
     *       ACCT-CURR-BAL - TRAN-AMT}, line 234, and {@code UPDATE-ACCTDAT-FILE},
     *       lines 377-403)</li>
     * </ol>
     *
     * <p>The {@code @Transactional(rollbackFor = Exception.class)} annotation ensures
     * that if any step fails after a partial write, all changes are rolled back.
     * This preserves the atomicity semantics of the COBOL CICS
     * READ UPDATE + WRITE + REWRITE sequence.</p>
     *
     * @param accountId the 11-character account identifier to process payment for
     *                  (maps COBOL ACCT-ID PIC 9(11))
     * @return the newly created {@link Transaction} entity representing the bill payment
     * @throws IllegalArgumentException       if {@code accountId} is null or blank
     *         (maps COBIL00C.cbl line 161: "Acct ID can NOT be empty...")
     * @throws RecordNotFoundException        if the account is not found
     *         (maps COBIL00C.cbl line 361: "Account ID NOT found...")
     *         or if no card cross-reference exists for the account
     *         (maps COBIL00C.cbl line 424: DFHRESP(NOTFND))
     * @throws IllegalStateException          if the account balance is zero or negative
     *         (maps COBIL00C.cbl line 201: "You have nothing to pay...")
     * @throws CreditLimitExceededException   if the payment would violate the credit limit
     *         (AAP requirement — maps reject code 102)
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionDto processPayment(String accountId) {

        // Step 1: Validate account ID (COBIL00C.cbl lines 159-167)
        // Maps COBOL: WHEN ACTIDINI OF COBIL0AI = SPACES OR LOW-VALUES
        if (accountId == null || accountId.isBlank()) {
            log.warn("Bill payment attempted with empty account ID");
            throw new IllegalArgumentException("Acct ID can NOT be empty");
        }

        log.info("Processing bill payment for account: {}", accountId);

        // Step 2: Read account record (← READ-ACCTDAT-FILE, lines 343-372)
        // Maps COBOL: EXEC CICS READ DATASET(WS-ACCTDAT-FILE) INTO(ACCOUNT-RECORD)
        //             RIDFLD(ACCT-ID) UPDATE RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
        // The findById + subsequent save with @Version provides READ-FOR-UPDATE semantics
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> {
                    log.warn("Account not found for bill payment: {}", accountId);
                    return new RecordNotFoundException("Account", accountId);
                });

        // Step 3: Validate balance > 0 (COBIL00C.cbl lines 197-206)
        // Maps COBOL: IF ACCT-CURR-BAL <= ZEROS
        // CRITICAL: Use compareTo(), NEVER equals() for BigDecimal (AAP §0.8.2)
        BigDecimal currentBalance = account.getAcctCurrBal();
        if (currentBalance == null || currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Bill payment rejected — zero or negative balance for account: {}, balance: {}",
                    accountId, currentBalance);
            throw ValidationException.of("accountId", accountId,
                    "No outstanding balance to pay. Current balance is "
                            + (currentBalance != null ? currentBalance.toPlainString() : "0.00"));
        }

        // Step 4: Credit limit validation (AAP requirement)
        // The bill payment pays the full current balance, so newBalance = 0.
        // Validate that the payment amount does not violate credit limit constraints.
        // For a full-balance payment, newBalance will be zero, which should always pass.
        // This check is included for safety and future extensibility per AAP requirements.
        BigDecimal creditLimit = account.getAcctCreditLimit();
        if (creditLimit != null && currentBalance.compareTo(creditLimit) > 0) {
            log.warn("Credit limit validation failed for account: {}, balance: {}, limit: {}",
                    accountId, currentBalance, creditLimit);
            throw new CreditLimitExceededException(
                    accountId, currentBalance, creditLimit, currentBalance);
        }

        // Step 5: Read cross-reference (← READ-CXACAIX-FILE, lines 408-436)
        // Maps COBOL: EXEC CICS READ DATASET(WS-CXACAIX-FILE) INTO(CARD-XREF-RECORD)
        //             RIDFLD(XREF-ACCT-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
        // CXACAIX is the VSAM Alternate Index on account ID — returns first matching xref
        List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(accountId);
        if (xrefs.isEmpty()) {
            log.warn("Card cross-reference not found for account: {}", accountId);
            throw new RecordNotFoundException("CardCrossReference for account", accountId);
        }
        CardCrossReference xref = xrefs.get(0);

        // Step 6: Generate transaction ID (← STARTBR + READPREV + ENDBR, lines 441-505)
        // Maps COBOL: MOVE HIGH-VALUES TO TRAN-ID / PERFORM STARTBR-TRANSACT-FILE /
        //             PERFORM READPREV-TRANSACT-FILE / PERFORM ENDBR-TRANSACT-FILE /
        //             MOVE TRAN-ID TO WS-TRAN-ID-NUM / ADD 1 TO WS-TRAN-ID-NUM
        String newTransactionId = generateTransactionId();

        // Step 7: Create transaction record (COBIL00C.cbl lines 218-232)
        // Maps COBOL field-by-field MOVE statements with exact values
        Transaction transaction = new Transaction();
        transaction.setTranId(newTransactionId);
        transaction.setTranTypeCd(BILL_PAYMENT_TYPE_CD);                // MOVE '02' TO TRAN-TYPE-CD (line 220)
        transaction.setTranCatCd(BILL_PAYMENT_CAT_CD);                  // MOVE 2 TO TRAN-CAT-CD (line 221, PIC 9(04))
        transaction.setTranSource(BILL_PAYMENT_SOURCE);                 // MOVE 'POS TERM' TO TRAN-SOURCE (line 222)
        transaction.setTranDesc(BILL_PAYMENT_DESC);                     // MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC (line 223)
        transaction.setTranAmt(currentBalance);                         // MOVE ACCT-CURR-BAL TO TRAN-AMT (line 224)
        transaction.setTranCardNum(xref.getXrefCardNum());              // MOVE XREF-CARD-NUM TO TRAN-CARD-NUM (line 225)
        transaction.setTranMerchantId(BILL_PAYMENT_MERCHANT_ID);        // MOVE 999999999 TO TRAN-MERCHANT-ID (line 226)
        transaction.setTranMerchantName(BILL_PAYMENT_MERCHANT_NAME);    // MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME (line 227)
        transaction.setTranMerchantCity(BILL_PAYMENT_MERCHANT_CITY);    // MOVE 'N/A' TO TRAN-MERCHANT-CITY (line 228)
        transaction.setTranMerchantZip(BILL_PAYMENT_MERCHANT_ZIP);      // MOVE 'N/A' TO TRAN-MERCHANT-ZIP (line 229)

        // GET-CURRENT-TIMESTAMP (lines 249-267) — replaces EXEC CICS ASKTIME + FORMATTIME
        LocalDateTime now = LocalDateTime.now();
        transaction.setTranOrigTs(now);                                 // MOVE WS-TIMESTAMP TO TRAN-ORIG-TS (line 231)
        transaction.setTranProcTs(now);                                 // MOVE WS-TIMESTAMP TO TRAN-PROC-TS (line 232)

        // Step 8: Write transaction (← WRITE-TRANSACT-FILE, lines 510-547)
        // Maps COBOL: EXEC CICS WRITE DATASET(WS-TRANSACT-FILE) FROM(TRAN-RECORD)
        //             RIDFLD(TRAN-ID) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
        // DFHRESP(DUPKEY)/DFHRESP(DUPREC) maps to DataIntegrityViolationException
        // propagated from JPA, which triggers @Transactional rollback
        transactionRepository.save(transaction);

        // Record transaction amount metric for observability dashboard
        // Per AAP §0.7.1: carddemo.transaction.amount.total distribution summary
        metricsConfig.recordTransactionAmount(transaction.getTranAmt().doubleValue());

        // Step 9: Update account balance (← UPDATE-ACCTDAT-FILE, lines 377-403)
        // Maps COBOL: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (line 234)
        //             EXEC CICS REWRITE DATASET(WS-ACCTDAT-FILE) FROM(ACCOUNT-RECORD)
        // CRITICAL: Use BigDecimal.subtract() — ZERO float/double arithmetic (AAP §0.8.2)
        // The @Version on Account entity provides optimistic locking (CICS READ UPDATE semantics)
        BigDecimal newBalance = currentBalance.subtract(transaction.getTranAmt());
        account.setAcctCurrBal(newBalance);
        accountRepository.save(account);

        // Step 10: Log success and return the created transaction
        // Maps COBOL: STRING 'Payment successful. Your Transaction ID is ' ...
        //             INTO WS-MESSAGE (lines 527-531)
        log.info("Bill payment successful. Account: {}, Transaction ID: {}, Amount: {}, New Balance: {}",
                accountId, newTransactionId, transaction.getTranAmt(), newBalance);

        return toTransactionDto(transaction);
    }

    /**
     * Converts a Transaction entity to TransactionDto with consistent field naming.
     * Ensures the billing response uses the same shortened field names as
     * TransactionController (tranMerchId, tranMerchName, etc.) instead of the
     * full JPA entity names (tranMerchantId, tranMerchantName, etc.).
     *
     * @param entity the persisted Transaction entity
     * @return TransactionDto with standardized field names
     */
    private TransactionDto toTransactionDto(Transaction entity) {
        TransactionDto dto = new TransactionDto();
        dto.setTranId(entity.getTranId());
        dto.setTranTypeCd(entity.getTranTypeCd());
        dto.setTranCatCd(entity.getTranCatCd() != null
                ? String.valueOf(entity.getTranCatCd()) : null);
        dto.setTranSource(entity.getTranSource());
        dto.setTranDesc(entity.getTranDesc());
        dto.setTranAmt(entity.getTranAmt());
        dto.setTranCardNum(entity.getTranCardNum());
        dto.setTranMerchId(entity.getTranMerchantId());
        dto.setTranMerchName(entity.getTranMerchantName());
        dto.setTranMerchCity(entity.getTranMerchantCity());
        dto.setTranMerchZip(entity.getTranMerchantZip());
        dto.setTranOrigTs(entity.getTranOrigTs());
        dto.setTranProcTs(entity.getTranProcTs());
        return dto;
    }

    /**
     * Generates the next sequential transaction ID by querying the maximum existing
     * transaction ID and incrementing by 1.
     *
     * <p>This method maps the COBOL auto-ID generation pattern from COBIL00C.cbl
     * (lines 212-217):</p>
     * <pre>
     * MOVE HIGH-VALUES TO TRAN-ID
     * PERFORM STARTBR-TRANSACT-FILE    (lines 441-467)
     * PERFORM READPREV-TRANSACT-FILE   (lines 472-496)
     * PERFORM ENDBR-TRANSACT-FILE      (lines 501-505)
     * MOVE TRAN-ID TO WS-TRAN-ID-NUM
     * ADD 1 TO WS-TRAN-ID-NUM
     * </pre>
     *
     * <p>The COBOL approach opens a browse cursor at HIGH-VALUES (the end of the
     * dataset), reads the previous record to find the highest transaction ID,
     * then increments it by 1. The Java equivalent uses a JPQL {@code MAX()}
     * aggregate query via {@code TransactionRepository.findMaxTransactionId()}
     * to achieve the same result without cursor-based browsing.</p>
     *
     * <p>Edge cases handled:</p>
     * <ul>
     *   <li>Empty table (no transactions) — starts at {@code "0000000000000001"}
     *       (maps COBOL READPREV returning ENDFILE → MOVE ZEROS TO TRAN-ID,
     *       then ADD 1 = 1)</li>
     *   <li>Non-numeric max ID — falls back to starting ID of 1
     *       (defensive handling for data integrity)</li>
     * </ul>
     *
     * @return a 16-character zero-padded transaction ID string matching
     *         COBOL PIC X(16) / PIC 9(16) format (e.g., "0000000000000001")
     */
    private String generateTransactionId() {
        Optional<String> maxId = transactionRepository.findMaxTransactionId();

        long nextId = maxId.map(id -> {
            try {
                return Long.parseLong(id.trim()) + 1;
            } catch (NumberFormatException e) {
                log.warn("Non-numeric max transaction ID encountered: '{}', starting from default", id);
                return DEFAULT_STARTING_ID;
            }
        }).orElse(DEFAULT_STARTING_ID);

        String transactionId = String.format(TRANSACTION_ID_FORMAT, nextId);
        log.info("Generated new transaction ID: {}", transactionId);
        return transactionId;
    }
}
