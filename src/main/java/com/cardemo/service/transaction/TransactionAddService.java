/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * CardDemo Application — Transaction Add Service
 * Migrated from COBOL program COTRN02C.cbl (783 lines) — "Add a Transaction"
 * CICS online program (transaction ID CT02).
 *
 * COBOL source reference: app/cbl/COTRN02C.cbl (commit 27d6c6f)
 * Copybook references: CVTRA05Y.cpy, CVACT03Y.cpy, COCOM01Y.cpy, CSUTLDPY.cpy, CSUTLDWY.cpy
 */
package com.cardemo.service.transaction;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.DateValidationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Spring service for adding new transactions to the TRANSACT repository.
 *
 * <p>Migrates COBOL program {@code COTRN02C.cbl} (783 lines) — the "Add a Transaction"
 * CICS online program (transaction ID CT02). This is the most complex of the three
 * transaction services, implementing auto-ID generation, cross-reference resolution,
 * comprehensive field validation, and transactional write operations.</p>
 *
 * <h2>COBOL Paragraph to Java Method Mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA / PROCESS-ENTER-KEY} &rarr; {@link #addTransaction(TransactionDto)}</li>
 *   <li>{@code VALIDATE-INPUT-KEY-FIELDS} &rarr; {@link #resolveCardAccountReference(TransactionDto)}</li>
 *   <li>{@code VALIDATE-INPUT-DATA-FIELDS} &rarr; {@link #validateDataFields(TransactionDto)}</li>
 *   <li>{@code GENERATE-NEXT-TRAN-ID} &rarr; {@link #generateNextTransactionId()}</li>
 *   <li>{@code WRITE-TRANSACT-FILE} &rarr; {@code transactionRepository.save()}</li>
 *   <li>{@code COPY-LAST-TRAN-DATA} &rarr; {@link #copyFromTransaction(String)}</li>
 *   <li>{@code EDIT-DATE-CCYY-MM-DD} &rarr; {@code dateValidationService.validateDate()}</li>
 * </ul>
 *
 * <h2>Key Technical Constraints</h2>
 * <ul>
 *   <li>All monetary values use {@link BigDecimal} — zero floating-point substitution (AAP section 0.8.2)</li>
 *   <li>Transaction IDs are 16-character zero-padded strings matching COBOL PIC 9(16)</li>
 *   <li>Cross-reference resolution supports both card-to-account and account-to-card paths</li>
 *   <li>Write operations use {@code @Transactional} for atomicity (AAP section 0.8.4)</li>
 *   <li>Service is fully stateless and thread-safe</li>
 * </ul>
 *
 * @see TransactionDto
 * @see Transaction
 * @see TransactionRepository
 */
@Service
public class TransactionAddService {

    private static final Logger log = LoggerFactory.getLogger(TransactionAddService.class);

    /** Transaction ID length matching COBOL PIC 9(16) — 16 digits zero-padded. */
    private static final int TRAN_ID_LENGTH = 16;

    /** Format string for 16-digit zero-padded transaction ID. */
    private static final String TRAN_ID_FORMAT = "%016d";

    /** First-ever transaction ID when no records exist (maps COBOL NOTFND handling, MOVE ZEROS + ADD 1). */
    private static final String FIRST_TRANSACTION_ID = "0000000000000001";

    /**
     * Maximum allowed transaction amount — COBOL PIC S9(09)V99.
     * Nine digits before the decimal, two after: 999,999,999.99.
     */
    private static final BigDecimal MAX_TRANSACTION_AMOUNT = new BigDecimal("999999999.99");

    /**
     * Minimum allowed transaction amount — negative of MAX for signed field PIC S9(09)V99.
     * Value: -999,999,999.99.
     */
    private static final BigDecimal MIN_TRANSACTION_AMOUNT = new BigDecimal("-999999999.99");

    /** Maximum decimal scale for transaction amounts — V99 equals 2 decimal positions. */
    private static final int MAX_AMOUNT_SCALE = 2;

    /** DateTimeFormatter for converting LocalDateTime to CCYYMMDD string for date validation. */
    private static final DateTimeFormatter CCYYMMDD_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TransactionRepository transactionRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final AccountRepository accountRepository;
    private final DateValidationService dateValidationService;

    /**
     * Constructs a new TransactionAddService with required dependencies.
     *
     * <p>All dependencies are constructor-injected for immutability and testability.
     * Spring resolves these beans automatically via component scanning.</p>
     *
     * @param transactionRepository       repository for Transaction CRUD operations (TRANSACT VSAM)
     * @param cardCrossReferenceRepository repository for card-to-account cross-reference lookups (CARDXREF / CXACAIX)
     * @param accountRepository            repository for account existence validation (ACCTDAT)
     * @param dateValidationService        shared date validation service replacing CSUTLDTC.cbl subprogram
     */
    public TransactionAddService(
            TransactionRepository transactionRepository,
            CardCrossReferenceRepository cardCrossReferenceRepository,
            AccountRepository accountRepository,
            DateValidationService dateValidationService) {
        this.transactionRepository = transactionRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.accountRepository = accountRepository;
        this.dateValidationService = dateValidationService;
    }

    // -----------------------------------------------------------------------
    // Public API Methods
    // -----------------------------------------------------------------------

    /**
     * Adds a new transaction to the TRANSACT repository with auto-generated ID.
     *
     * <p>Maps COBOL PROCESS-ENTER-KEY through WRITE-TRANSACT-FILE
     * (COTRN02C.cbl lines 140-587). Execution sequence:</p>
     * <ol>
     *   <li>Resolve card-to-account cross-reference ({@code VALIDATE-INPUT-KEY-FIELDS})</li>
     *   <li>Validate all data fields ({@code VALIDATE-INPUT-DATA-FIELDS})</li>
     *   <li>Generate next sequential transaction ID ({@code GENERATE-NEXT-TRAN-ID})</li>
     *   <li>Build Transaction entity and persist ({@code WRITE-TRANSACT-FILE})</li>
     * </ol>
     *
     * @param request the transaction data DTO (maps BMS screen input fields from COTRN02.CPY)
     * @return TransactionDto with the generated transaction ID and all committed fields
     * @throws ValidationException        if any input field fails validation
     * @throws RecordNotFoundException    if card number or account ID not found in cross-reference
     * @throws DuplicateRecordException   if generated transaction ID already exists (FILE STATUS 22 — DUPKEY/DUPREC)
     * @throws CardDemoException          if an unexpected error occurs during write
     */
    @Transactional
    public TransactionDto addTransaction(TransactionDto request) {
        log.debug("Adding new transaction - card: {}, type: {}, amount: {}",
                request.getTranCardNum(), request.getTranTypeCd(), request.getTranAmt());

        // Step 1 — Validate key fields and resolve cross-reference
        // Maps VALIDATE-INPUT-KEY-FIELDS (COTRN02C.cbl lines 214-325)
        resolveCardAccountReference(request);

        // Step 2 — Validate all data fields exhaustively
        // Maps VALIDATE-INPUT-DATA-FIELDS (COTRN02C.cbl lines 330-498)
        validateDataFields(request);

        // Step 3 — Generate next sequential transaction ID
        // Maps GENERATE-NEXT-TRAN-ID (COTRN02C.cbl lines 503-546)
        String generatedId = generateNextTransactionId();

        // Step 4 — Build entity and write to database
        // Maps WRITE-TRANSACT-FILE (COTRN02C.cbl lines 551-587)
        Transaction entity = toEntity(request, generatedId);

        Transaction saved;
        try {
            saved = transactionRepository.save(entity);
        } catch (DataIntegrityViolationException ex) {
            // Maps COBOL DFHRESP(DUPKEY)/DFHRESP(DUPREC) at COTRN02C.cbl lines 569-578
            // "Transaction ID already Exists..."
            log.error("Duplicate transaction ID detected: {} - {}", generatedId, ex.getMessage());
            throw new DuplicateRecordException("Transaction", generatedId);
        } catch (RuntimeException ex) {
            // Maps COBOL OTHER response handling at COTRN02C.cbl lines 580-585
            // "Unable to Write Transaction..."
            log.error("Unable to write transaction {}: {}", generatedId, ex.getMessage(), ex);
            throw new CardDemoException(
                    "Unable to write transaction " + generatedId + ": " + ex.getMessage(), ex);
        }

        // Step 5 — Build response DTO with generated ID
        TransactionDto response = toDto(saved);

        // Step 6 — Structured logging for observability (AAP section 0.7.1)
        log.info("Transaction {} added successfully - card: {}, amount: {}",
                generatedId, saved.getTranCardNum(), saved.getTranAmt());

        return response;
    }

    /**
     * Returns a pre-populated TransactionDto based on an existing transaction,
     * copying all data fields except the transaction ID.
     *
     * <p>Maps COPY-LAST-TRAN-DATA (COTRN02C.cbl lines 595-650, PF5 key). The COBOL
     * program copies data fields from the last viewed/entered transaction to pre-populate
     * the add screen for convenience. In the REST API context, this method reads a
     * specified transaction and returns a template DTO suitable for modification and
     * submission via {@link #addTransaction(TransactionDto)}.</p>
     *
     * @param sourceTransactionId the transaction ID to copy from (16-char PIC X(16))
     * @return TransactionDto with data fields pre-populated (transaction ID is null)
     * @throws RecordNotFoundException if the source transaction ID does not exist
     */
    @Transactional(readOnly = true)
    public TransactionDto copyFromTransaction(String sourceTransactionId) {
        log.debug("Copying transaction data from source: {}", sourceTransactionId);

        // Read source transaction — maps COBOL READ TRANSACT for copy
        Optional<Transaction> sourceOpt = transactionRepository.findById(sourceTransactionId);
        if (sourceOpt.isEmpty()) {
            log.warn("Source transaction not found for copy: {}", sourceTransactionId);
            throw new RecordNotFoundException("Transaction", sourceTransactionId);
        }
        Transaction source = sourceOpt.get();

        // Build template DTO with all data fields — but NO transaction ID
        // The ID will be auto-generated when addTransaction() is called
        TransactionDto template = new TransactionDto();
        // tranId intentionally left null — auto-generated on actual add
        template.setTranTypeCd(source.getTranTypeCd());
        template.setTranCatCd(source.getTranCatCd() != null
                ? String.valueOf(source.getTranCatCd()) : null);
        template.setTranSource(source.getTranSource());
        template.setTranDesc(source.getTranDesc());
        template.setTranAmt(source.getTranAmt());
        template.setTranCardNum(source.getTranCardNum());
        template.setTranMerchId(source.getTranMerchantId());
        template.setTranMerchName(source.getTranMerchantName());
        template.setTranMerchCity(source.getTranMerchantCity());
        template.setTranMerchZip(source.getTranMerchantZip());
        template.setTranOrigTs(source.getTranOrigTs());
        template.setTranProcTs(source.getTranProcTs());

        log.info("Transaction data copied from source: {}", sourceTransactionId);
        return template;
    }

    // -----------------------------------------------------------------------
    // Private — Cross-Reference Resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves card number to account ID cross-reference bidirectionally.
     *
     * <p>Maps VALIDATE-INPUT-KEY-FIELDS (COTRN02C.cbl lines 214-325). The COBOL program
     * supports two resolution paths via the CARDXREF dataset and its CXACAIX alternate index:</p>
     * <ul>
     *   <li><strong>Card number provided</strong> — looks up cross-reference by primary key
     *       (CCXREF READ, lines 267-306) to verify card exists and resolve account</li>
     *   <li><strong>Account ID as fallback</strong> — if input is not found as a card number,
     *       attempts lookup via CXACAIX alternate index (STARTBR/READNEXT, lines 227-266)
     *       to resolve from account ID to card number</li>
     * </ul>
     *
     * <p>Mutates the request DTO by setting the resolved card number.</p>
     *
     * @param request the transaction DTO to resolve references for
     * @throws RecordNotFoundException if the card number or account ID is not found
     * @throws ValidationException     if no card number is provided
     */
    private void resolveCardAccountReference(TransactionDto request) {
        String inputValue = trimToNull(request.getTranCardNum());

        // Case 4: Neither provided — maps COBOL lines 308-325
        // "Account ID or Card Num is required"
        if (isBlank(inputValue)) {
            log.warn("Transaction add failed: no card number or account ID provided");
            throw new ValidationException("Account ID or Card Number is required");
        }

        // Path 2: Try as card number first (primary resolution path)
        // Maps COBOL lines 267-306: READ CCXREF INTO(CARD-XREF-RECORD) RIDFLD(XREF-CARD-NUM)
        Optional<CardCrossReference> xrefByCard = cardCrossReferenceRepository.findById(inputValue);

        if (xrefByCard.isPresent()) {
            CardCrossReference xref = xrefByCard.get();
            String resolvedAcctId = xref.getXrefAcctId();

            // Verify the resolved account exists in the account table
            if (!isBlank(resolvedAcctId)) {
                accountRepository.findById(resolvedAcctId)
                        .orElseThrow(() -> {
                            log.warn("Account ID {} from cross-reference not found in accounts", resolvedAcctId);
                            return new RecordNotFoundException("Account", resolvedAcctId);
                        });
            }

            // Set the canonical card number from the cross-reference
            request.setTranCardNum(xref.getXrefCardNum());
            log.debug("Card number {} resolved to account {}", xref.getXrefCardNum(), resolvedAcctId);
            return;
        }

        // Path 1: Input was not a valid card number — try as account ID
        // Maps COBOL lines 227-266: STARTBR CXACAIX RIDFLD(XREF-ACCT-ID) / READNEXT
        List<CardCrossReference> xrefsByAcct = cardCrossReferenceRepository.findByXrefAcctId(inputValue);

        if (!xrefsByAcct.isEmpty()) {
            // Take the first cross-reference record (maps COBOL READNEXT — first sequential match)
            CardCrossReference xref = xrefsByAcct.get(0);

            // Verify the account actually exists in the account table
            accountRepository.findById(xref.getXrefAcctId())
                    .orElseThrow(() -> {
                        log.warn("Account ID {} not found in accounts table", inputValue);
                        return new RecordNotFoundException("Account", inputValue);
                    });

            // Set the resolved card number on the DTO — maps COBOL "MOVE XREF-CARD-NUM TO CARDNUMI"
            request.setTranCardNum(xref.getXrefCardNum());
            log.debug("Account ID {} resolved to card number {}", inputValue, xref.getXrefCardNum());
            return;
        }

        // Neither card number nor account ID resolved — maps "Card Number NOT Found"
        log.warn("Card/account reference not found: {}", inputValue);
        throw new RecordNotFoundException("Card", inputValue);
    }

    // -----------------------------------------------------------------------
    // Private — Data Field Validation
    // -----------------------------------------------------------------------

    /**
     * Validates all transaction data fields exhaustively.
     *
     * <p>Maps VALIDATE-INPUT-DATA-FIELDS (COTRN02C.cbl lines 330-498). All validation errors
     * are accumulated before throwing a single {@link ValidationException}, matching the COBOL
     * pattern where {@code WS-ERR-FLG} is set but processing continues through all checks.</p>
     *
     * <p>Field validations include:</p>
     * <ol>
     *   <li>Type Code — non-blank, numeric (lines 333-345)</li>
     *   <li>Category Code — non-blank, numeric (lines 347-362)</li>
     *   <li>Transaction Source — non-blank (lines 364-373)</li>
     *   <li>Description — non-blank (lines 375-384)</li>
     *   <li>Amount — non-null, non-zero, valid scale, valid range (lines 386-423)</li>
     *   <li>Origination Date — non-null, valid via DateValidationService (lines 430-448)</li>
     *   <li>Processing Date — non-null, valid via DateValidationService (lines 450-468)</li>
     *   <li>Merchant ID — non-blank, numeric (lines 470-482)</li>
     *   <li>Merchant Name — non-blank (lines 484-493)</li>
     *   <li>Merchant City — non-blank (lines 495-498)</li>
     *   <li>Merchant ZIP — non-blank</li>
     * </ol>
     *
     * @param request the transaction DTO to validate
     * @throws ValidationException if any field fails validation (accumulated errors)
     */
    private void validateDataFields(TransactionDto request) {
        List<String> errors = new ArrayList<>();

        // ---- 1. Type Code (COBOL lines 333-345) ----
        // COBOL: IF TTYPCDI = SPACES OR LOW-VALUES -> "Type Code can NOT be empty"
        // COBOL: IF TTYPCDI IS NOT NUMERIC -> "Type Code must be numeric"
        if (isBlank(request.getTranTypeCd())) {
            errors.add("Type Code cannot be empty");
        } else if (!isNumeric(request.getTranTypeCd().trim())) {
            errors.add("Type Code must be numeric");
        }

        // ---- 2. Category Code (COBOL lines 347-362) ----
        // COBOL: IF TCATCDI = SPACES OR LOW-VALUES -> "Category Code can NOT be empty"
        // COBOL: IF TCATCDI IS NOT NUMERIC -> "Category Code must be numeric"
        if (isBlank(request.getTranCatCd())) {
            errors.add("Category Code cannot be empty");
        } else if (!isNumeric(request.getTranCatCd().trim())) {
            errors.add("Category Code must be numeric");
        }

        // ---- 3. Transaction Source (COBOL lines 364-373) ----
        // COBOL: IF TRNSRCI = SPACES OR LOW-VALUES -> "Transaction Source can NOT be empty"
        if (isBlank(request.getTranSource())) {
            errors.add("Transaction Source cannot be empty");
        }

        // ---- 4. Description (COBOL lines 375-384) ----
        // COBOL: IF TDESCI = SPACES OR LOW-VALUES -> "Description can NOT be empty"
        if (isBlank(request.getTranDesc())) {
            errors.add("Description cannot be empty");
        }

        // ---- 5. Amount (COBOL lines 386-423) ----
        // COBOL: PIC S9(09)V99 COMP-3 — exact decimal, NEVER float/double (AAP section 0.8.2)
        BigDecimal amount = request.getTranAmt();
        if (amount == null) {
            errors.add("Amount cannot be empty");
        } else {
            // Check for zero amount — COBOL rejects zero-valued transactions
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                errors.add("Amount cannot be zero");
            }
            // Validate decimal scale — V99 means at most 2 decimal places
            if (amount.scale() > MAX_AMOUNT_SCALE) {
                errors.add("Amount cannot have more than " + MAX_AMOUNT_SCALE + " decimal places");
            }
            // Validate range — PIC S9(09)V99: -999999999.99 to +999999999.99
            if (amount.compareTo(MAX_TRANSACTION_AMOUNT) > 0) {
                errors.add("Amount exceeds maximum allowed value of " + MAX_TRANSACTION_AMOUNT);
            }
            if (amount.compareTo(MIN_TRANSACTION_AMOUNT) < 0) {
                errors.add("Amount is below minimum allowed value of " + MIN_TRANSACTION_AMOUNT);
            }
        }

        // ---- 6. Origination Date (COBOL lines 430-448) ----
        // COBOL: IF TORIGDTI = SPACES -> "Orig. Date can NOT be empty"
        // COBOL: PERFORM EDIT-DATE-CCYY-MM-DD -> calls CSUTLDTC (LE CEEDAYS)
        LocalDateTime origTs = request.getTranOrigTs();
        if (origTs == null) {
            errors.add("Origination Date cannot be empty");
        } else {
            // Convert LocalDateTime to CCYYMMDD format string for DateValidationService
            String origDateStr = origTs.format(CCYYMMDD_FORMATTER);
            DateValidationService.DateValidationResult origResult =
                    dateValidationService.validateDate(origDateStr, "Origination Date");
            if (origResult == null || !origResult.valid()) {
                errors.add("Origination Date is not valid");
            }
        }

        // ---- 7. Processing Date (COBOL lines 450-468) ----
        // COBOL: IF TPROCDTI = SPACES -> "Proc. Date can NOT be empty"
        // COBOL: PERFORM EDIT-DATE-CCYY-MM-DD -> calls CSUTLDTC (LE CEEDAYS)
        LocalDateTime procTs = request.getTranProcTs();
        if (procTs == null) {
            errors.add("Processing Date cannot be empty");
        } else {
            String procDateStr = procTs.format(CCYYMMDD_FORMATTER);
            DateValidationService.DateValidationResult procResult =
                    dateValidationService.validateDate(procDateStr, "Processing Date");
            if (procResult == null || !procResult.valid()) {
                errors.add("Processing Date is not valid");
            }
        }

        // ---- 8. Merchant ID (COBOL lines 470-482) ----
        // COBOL: IF MIDI = SPACES -> "Merchant ID can NOT be empty"
        // COBOL: IF MIDI IS NOT NUMERIC -> "Merchant ID must be numeric"
        if (isBlank(request.getTranMerchId())) {
            errors.add("Merchant ID cannot be empty");
        } else if (!isNumeric(request.getTranMerchId().trim())) {
            errors.add("Merchant ID must be numeric");
        }

        // ---- 9. Merchant Name (COBOL lines 484-493) ----
        // COBOL: IF MNAMEI = SPACES -> "Merchant Name can NOT be empty"
        if (isBlank(request.getTranMerchName())) {
            errors.add("Merchant Name cannot be empty");
        }

        // ---- 10. Merchant City (COBOL lines 495-498) ----
        // COBOL validation for city — non-empty check
        if (isBlank(request.getTranMerchCity())) {
            errors.add("Merchant City cannot be empty");
        }

        // ---- 11. Merchant ZIP — non-empty validation ----
        // Consistent non-empty validation applied across all merchant fields
        if (isBlank(request.getTranMerchZip())) {
            errors.add("Merchant ZIP cannot be empty");
        }

        // After all checks: throw accumulated errors if any
        // Maps COBOL WS-ERR-FLG pattern — all fields checked before reporting
        if (!errors.isEmpty()) {
            StringJoiner joiner = new StringJoiner("; ");
            for (String error : errors) {
                joiner.add(error);
            }
            log.warn("Transaction validation failed with {} error(s): {}", errors.size(), joiner);
            throw new ValidationException(joiner.toString());
        }
    }

    // -----------------------------------------------------------------------
    // Private — Auto-ID Generation
    // -----------------------------------------------------------------------

    /**
     * Generates the next sequential transaction ID by reading the max existing ID and incrementing.
     *
     * <p>Maps GENERATE-NEXT-TRAN-ID (COTRN02C.cbl lines 503-546). The COBOL pattern:</p>
     * <ol>
     *   <li>{@code MOVE HIGH-VALUES TO TRAN-ID} — position at end of file</li>
     *   <li>{@code STARTBR TRANSACT RIDFLD(TRAN-ID) GTEQ} — start browse at or after max</li>
     *   <li>{@code READPREV TRANSACT} — read the last record</li>
     *   <li>{@code ENDBR TRANSACT} — end browse</li>
     *   <li>{@code MOVE TRAN-ID TO WS-TRAN-ID-NUM} — convert to numeric PIC 9(16)</li>
     *   <li>{@code ADD 1 TO WS-TRAN-ID-NUM} — increment</li>
     *   <li>{@code MOVE WS-TRAN-ID-NUM TO TRAN-ID} — format back to string</li>
     * </ol>
     *
     * <p>Java equivalent uses {@code SELECT MAX(t.tranId) FROM Transaction t} via
     * {@link TransactionRepository#findMaxTransactionId()}.</p>
     *
     * @return 16-character zero-padded transaction ID string (PIC 9(16))
     * @throws CardDemoException if the existing max ID is corrupted and cannot be parsed
     */
    private String generateNextTransactionId() {
        Optional<String> maxIdOpt = transactionRepository.findMaxTransactionId();

        // If no records exist — first ever transaction
        // Maps COBOL NOTFND handling at line 523: MOVE ZEROS TO TRAN-ID then ADD 1
        if (maxIdOpt.isEmpty()) {
            log.debug("No existing transactions found, starting at {}", FIRST_TRANSACTION_ID);
            return FIRST_TRANSACTION_ID;
        }

        String maxId = maxIdOpt.get();

        try {
            // Parse the 16-character string to long, increment, and format back
            // Maps COBOL: MOVE TRAN-ID TO WS-TRAN-ID-NUM then ADD 1
            long currentMax = Long.parseLong(maxId.trim());
            long nextId = currentMax + 1;

            // Format as 16-digit zero-padded string matching COBOL PIC 9(16)
            String formattedId = String.format(TRAN_ID_FORMAT, nextId);

            // Safety check: ensure ID length matches expected format
            if (formattedId.length() > TRAN_ID_LENGTH) {
                log.error("Generated transaction ID exceeds {} digits: {}", TRAN_ID_LENGTH, formattedId);
                throw new CardDemoException(
                        "Transaction ID overflow: generated ID " + formattedId
                                + " exceeds maximum " + TRAN_ID_LENGTH + " digits");
            }

            log.debug("Generated next transaction ID: {} (previous max: {})", formattedId, maxId);
            return formattedId;
        } catch (NumberFormatException ex) {
            // Corrupted ID in database — cannot parse to numeric
            log.error("Corrupted transaction ID in database: '{}' - cannot parse to numeric", maxId, ex);
            throw new CardDemoException(
                    "Corrupted transaction ID in database: " + maxId, ex);
        }
    }

    // -----------------------------------------------------------------------
    // Private — Entity/DTO Conversion
    // -----------------------------------------------------------------------

    /**
     * Constructs a Transaction entity from a TransactionDto and the auto-generated ID.
     *
     * <p>Maps WRITE-TRANSACT-FILE record construction (COTRN02C.cbl lines 551-566) where
     * all BMS screen fields are moved to the TRAN-RECORD structure before the WRITE.</p>
     *
     * @param dto         the validated transaction DTO with all field values
     * @param generatedId the auto-generated 16-character transaction ID
     * @return a fully populated Transaction entity ready for persistence
     */
    private Transaction toEntity(TransactionDto dto, String generatedId) {
        Transaction entity = new Transaction();

        // 1. TRAN-ID — auto-generated, not from DTO
        entity.setTranId(generatedId);

        // 2. TRAN-TYPE-CD — PIC X(02) type code
        entity.setTranTypeCd(dto.getTranTypeCd());

        // 3. TRAN-CAT-CD — DTO is String, Entity is Short (type conversion required)
        // COBOL PIC X(04) in DTO maps to SMALLINT in Entity
        if (dto.getTranCatCd() != null && !dto.getTranCatCd().trim().isEmpty()) {
            entity.setTranCatCd(Short.parseShort(dto.getTranCatCd().trim()));
        }

        // 4. TRAN-SOURCE — PIC X(10)
        entity.setTranSource(dto.getTranSource());

        // 5. TRAN-DESC — PIC X(100)
        entity.setTranDesc(dto.getTranDesc());

        // 6. TRAN-AMT — BigDecimal (PIC S9(09)V99 COMP-3) — CRITICAL: no float/double
        entity.setTranAmt(dto.getTranAmt());

        // 7. TRAN-MERCHANT-ID — PIC X(09) — note: DTO uses shortened name 'tranMerchId'
        entity.setTranMerchantId(dto.getTranMerchId());

        // 8. TRAN-MERCHANT-NAME — PIC X(50)
        entity.setTranMerchantName(dto.getTranMerchName());

        // 9. TRAN-MERCHANT-CITY — PIC X(50)
        entity.setTranMerchantCity(dto.getTranMerchCity());

        // 10. TRAN-MERCHANT-ZIP — PIC X(10)
        entity.setTranMerchantZip(dto.getTranMerchZip());

        // 11. TRAN-CARD-NUM — PIC X(16) — resolved from cross-reference
        entity.setTranCardNum(dto.getTranCardNum());

        // 12. TRAN-ORIG-TS — origination timestamp
        entity.setTranOrigTs(dto.getTranOrigTs());

        // 13. TRAN-PROC-TS — processing timestamp
        entity.setTranProcTs(dto.getTranProcTs());

        return entity;
    }

    /**
     * Converts a Transaction entity to a TransactionDto for API response.
     *
     * <p>Maps all 14 entity fields to DTO fields, including the generated transaction ID.
     * Handles the type conversion for tranCatCd (Short to String) and the merchant field
     * naming differences between entity and DTO.</p>
     *
     * @param entity the persisted Transaction entity
     * @return a fully populated TransactionDto suitable for REST API response
     */
    private TransactionDto toDto(Transaction entity) {
        TransactionDto dto = new TransactionDto();

        // Transaction ID — the auto-generated value
        dto.setTranId(entity.getTranId());

        // Type code
        dto.setTranTypeCd(entity.getTranTypeCd());

        // Category code — Short to String conversion
        dto.setTranCatCd(entity.getTranCatCd() != null
                ? String.valueOf(entity.getTranCatCd()) : null);

        // Transaction source
        dto.setTranSource(entity.getTranSource());

        // Description
        dto.setTranDesc(entity.getTranDesc());

        // Amount — BigDecimal, exact precision preserved
        dto.setTranAmt(entity.getTranAmt());

        // Card number
        dto.setTranCardNum(entity.getTranCardNum());

        // Merchant fields — entity uses full names, DTO uses shortened names
        dto.setTranMerchId(entity.getTranMerchantId());
        dto.setTranMerchName(entity.getTranMerchantName());
        dto.setTranMerchCity(entity.getTranMerchantCity());
        dto.setTranMerchZip(entity.getTranMerchantZip());

        // Timestamps
        dto.setTranOrigTs(entity.getTranOrigTs());
        dto.setTranProcTs(entity.getTranProcTs());

        return dto;
    }

    // -----------------------------------------------------------------------
    // Private — Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Checks if a string is null, empty, or contains only whitespace.
     * Maps COBOL {@code SPACES} and {@code LOW-VALUES} checks.
     *
     * @param value the string to check
     * @return true if the value is blank (null, empty, or whitespace-only)
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Checks if a string contains only numeric digits (0-9).
     * Maps COBOL {@code IS NUMERIC} test which verifies all characters are digits.
     *
     * <p>Returns false for null, empty, or any string containing non-digit characters.
     * This is a strict digit-only check — signs, decimals, and spaces are rejected.</p>
     *
     * @param value the string to check
     * @return true if the value contains only digits
     */
    private boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Trims a string value and returns null if the result is empty.
     * Normalizes whitespace-only strings to null for consistent blank checking.
     *
     * @param value the string to trim
     * @return the trimmed string, or null if the input was null or whitespace-only
     */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
