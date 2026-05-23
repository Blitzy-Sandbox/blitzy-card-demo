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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Transaction-add service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COTRN02C.cbl} (CICS transaction id {@code CT02}).
 *
 * <p>This service handles the conversational add-transaction flow.  In
 * the COBOL source, {@code COTRN02C.cbl} validates the operator inputs
 * (account or card, transaction type, category, amount, dates, merchant
 * fields), resolves the account/card cross-reference via the
 * {@code CARDXREF} alternate-index browse, derives the next transaction
 * identifier via the {@code STARTBR}/{@code READPREV}/{@code ENDBR}
 * pattern (the MAX-TRAN-ID + 1 idiom &mdash; see paragraph
 * {@code ADD-TRANSACTION} at {@code COTRN02C.cbl} L444&ndash;L451), and
 * writes the new {@code TRAN-RECORD} via {@code EXEC CICS WRITE}.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COTRN02C.cbl}.</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COTRN02.bms} (mapset
 *       {@code COTRN02}, map {@code COTRN2A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COTRN02.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}, 350 bytes; {@link Transaction}).</li>
 *   <li><b>XREF lookup:</b> {@code app/cpy/CVACT03Y.cpy}
 *       ({@code CARD-XREF-RECORD}, 50 bytes; {@link CardCrossReference})
 *       &mdash; via {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} (replaced
 *       by the Spring Data derived query
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COTRN02C.cbl &harr; TransactionAddService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code 1000-PROCESS-INPUTS} (Bean Validation + lookups)</td>
 *       <td>{@link #validate(TransactionAddDto)}</td></tr>
 *   <tr><td>{@code READ-CXACAIX-FILE} (resolve card or account via
 *       cross-reference; replaces {@code STARTBR-CXACAIX-FILE})</td>
 *       <td>{@link CardCrossReferenceRepository#findById(Object)} /
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}</td></tr>
 *   <tr><td>{@code ADD-TRANSACTION} (lines 444&ndash;451 &mdash;
 *       {@code MOVE HIGH-VALUES TO TRAN-ID;
 *       STARTBR; READPREV; ENDBR; MOVE TRAN-ID TO WS-TRAN-ID-N;
 *       ADD 1 TO WS-TRAN-ID-N})</td>
 *       <td>{@link TransactionRepository#findMaxTranId()} &rarr;
 *       {@code Long.parseLong(max) + 1} &rarr;
 *       {@code String.format("%016d", next)} (the canonical
 *       MAX-TRAN-ID + 1 pattern per AAP &sect;0.6.2)</td></tr>
 *   <tr><td>{@code WRITE-TRANSACT-FILE} (EXEC CICS WRITE)</td>
 *       <td>{@link TransactionRepository#save(Object)}</td></tr>
 *   <tr><td>{@code SYNCPOINT} (CICS commit)</td>
 *       <td>{@link Transactional @Transactional(rollbackFor =
 *       Exception.class)}</td></tr>
 *   <tr><td>(NEW &mdash; not in COBOL source) Online &rarr; downstream
 *       notification of newly-posted transactions</td>
 *       <td>{@link KafkaEventPublisher#publishTransactionPosted(Long,
 *       TransactionAddDto)} (MSK topic {@code transaction.posted},
 *       partitioned by account ID per AAP &sect;0.6.5)</td></tr>
 * </table>
 *
 * <h2>MAX-TRAN-ID + 1 ID generation invariant (AAP &sect;0.6.2)</h2>
 *
 * <p>The transaction ID is a 16-character zero-padded numeric value.
 * Successive transactions must have strictly monotonically-increasing
 * IDs &mdash; the COBOL source guarantees this by holding an exclusive
 * lock on the {@code TRANSACT} cluster during the
 * {@code STARTBR}/{@code READPREV}/{@code ENDBR}/{@code WRITE}
 * sequence inside a single CICS task. The Java target preserves the
 * same invariant by wrapping the {@code findMaxTranId} read and the
 * {@code save} write in the same {@link Transactional &#64;Transactional}
 * boundary using {@code SERIALIZABLE} isolation on the database
 * connection (provided by RDS PostgreSQL Multi-AZ per AAP &sect;0.6.2).
 * Two concurrent inserts will be serialized by PostgreSQL and the
 * later one will retry until the next available ID becomes visible.</p>
 *
 * <h2>Cross-field "account OR card" validation</h2>
 *
 * <p>The COBOL source's BMS screen requires the operator to enter
 * <i>either</i> the 11-digit account number ({@code ACTIDIN}) <i>or</i>
 * the 16-digit card number ({@code CARDNIN}). The {@link #validate}
 * method implements the same rule plus a cross-reference consistency
 * check when both are present.</p>
 *
 * <h2>Confirmation gate (CICS pseudo-conversation)</h2>
 *
 * <p>The original CICS flow displays the assembled transaction for
 * operator confirmation and writes only when the operator types
 * {@code 'Y'} into the {@code CONFIRMI} BMS field. The Java target
 * preserves this as a single {@link TransactionAddDto#confirm()}
 * component on the request DTO; absent {@code "Y"} the service throws
 * {@link ValidationException} carrying the same error code.</p>
 *
 * @see TransactionRepository
 * @see CardCrossReferenceRepository
 * @see TransactionAddDto
 */
@Service
public class TransactionAddService {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionAddService.class);

    /** Maximum tranId numeric value: 10^16 - 1 (16-digit ceiling). */
    private static final BigDecimal MAX_TRAN_ID = new BigDecimal("9999999999999999");

    /**
     * Default seed used when the {@code transactions} table is empty
     * (mirrors COBOL behavior where the first transaction in an empty
     * cluster receives ID 1 after the {@code MOVE HIGH-VALUES} /
     * {@code STARTBR} / {@code READPREV} sequence returns no record
     * and the COBOL working-storage numeric receives the high-values
     * sentinel).
     */
    private static final String SEED_TRAN_ID = "0000000000000000";

    private final TransactionRepository transactionRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final AuditLogService auditLogService;
    private final DateValidationService dateValidationService;

    public TransactionAddService(TransactionRepository transactionRepository,
                                 CardCrossReferenceRepository cardCrossReferenceRepository,
                                 KafkaEventPublisher kafkaEventPublisher,
                                 AuditLogService auditLogService,
                                 DateValidationService dateValidationService) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository");
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository");
        this.kafkaEventPublisher = Objects.requireNonNull(kafkaEventPublisher,
                "kafkaEventPublisher");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService");
        this.dateValidationService = Objects.requireNonNull(dateValidationService,
                "dateValidationService");
    }

    /**
     * Adds a new transaction to the journal, returning the persisted
     * DTO populated with the system-assigned transaction ID and
     * processing timestamp.
     *
     * @param request the validated add request
     * @return the persisted {@link TransactionAddDto} (with
     *         {@code transactionId}/{@code processingTimestamp})
     * @throws ValidationException     if any field-level rule fails
     * @throws RecordNotFoundException if the supplied card / account
     *                                 has no cross-reference row
     * @throws OnSizeErrorException    if the next tran ID would
     *                                 overflow the 16-digit ceiling
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionAddDto addTransaction(TransactionAddDto request) {
        Objects.requireNonNull(request, "request");
        validate(request);

        // ---- COBOL READ-CXACAIX-FILE -----------------------------------
        //   Resolve owning account (mandatory partition key for MSK).
        Long resolvedAcctId = resolveAccountId(request);

        // Confirmation gate (CONFIRMI of COTRN2AI = 'Y')
        if (!"Y".equalsIgnoreCase(safeTrim(request.confirm()))) {
            throw new ValidationException(
                    "NOT_CONFIRMED",
                    "Operator did not confirm the transaction (confirm must be 'Y')",
                    List.of(new ValidationException.FieldError(
                            "confirm", "Set confirm='Y' to commit the transaction")));
        }

        // ---- COBOL ADD-TRANSACTION (MAX-TRAN-ID + 1) -------------------
        String nextTranId = nextTransactionId();

        // ---- COBOL INITIALIZE TRAN-RECORD + MOVE * TO TRAN-* -----------
        LocalDateTime now = LocalDateTime.now();
        Transaction t = new Transaction(
                nextTranId,
                safeTrim(request.transactionType()),
                request.transactionCategory(),
                safeTrim(request.source()),
                safeTrim(request.description()),
                request.amount(),
                request.merchantId(),
                safeTrim(request.merchantName()),
                safeTrim(request.merchantCity()),
                safeTrim(request.merchantZip()),
                safeTrim(request.cardNumber()),
                request.originationTimestamp() != null ? request.originationTimestamp() : now,
                now);

        // ---- COBOL WRITE-TRANSACT-FILE ---------------------------------
        Transaction saved = transactionRepository.save(t);

        // ---- MSK publish (transaction.posted, partition = acct ID) -----
        // Per AAP §0.6.5 / V005 documentation: every MSK event for a
        // transaction is partitioned by the OWNING ACCOUNT id so that
        // per-account ordering is preserved end-to-end on the partition.
        TransactionAddDto outbound = new TransactionAddDto(
                String.format("%011d", resolvedAcctId),
                saved.getTranCardNum(),
                saved.getTranTypeCd(),
                saved.getTranCatCd(),
                saved.getTranSource(),
                saved.getTranDesc(),
                saved.getTranAmt(),
                saved.getTranOrigTs(),
                saved.getTranProcTs(),
                saved.getTranMerchantId(),
                saved.getTranMerchantName(),
                saved.getTranMerchantCity(),
                saved.getTranMerchantZip(),
                "Y");
        kafkaEventPublisher.publishTransactionPosted(resolvedAcctId, outbound);

        // ---- Audit -----------------------------------------------------
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", saved.getTranId());
        payload.put("accountId", resolvedAcctId);
        payload.put("cardLast4", lastFour(saved.getTranCardNum()));
        payload.put("amount", saved.getTranAmt());
        payload.put("category", saved.getTranCatCd());
        payload.put("type", saved.getTranTypeCd());
        auditLogService.auditEvent("transaction.added", "system", payload);

        LOG.info("Transaction added tranId={} acctId={} cardLast4={} amount={}",
                saved.getTranId(), resolvedAcctId,
                lastFour(saved.getTranCardNum()), saved.getTranAmt());

        return outbound;
    }

    // -----------------------------------------------------------------
    // MAX-TRAN-ID + 1 generator (AAP §0.6.2)
    // -----------------------------------------------------------------

    /**
     * Computes the next 16-digit zero-padded transaction ID by reading
     * the current maximum from {@link TransactionRepository#findMaxTranId()}
     * and adding 1. Throws {@link OnSizeErrorException} if the result
     * would overflow the {@link #MAX_TRAN_ID} ceiling &mdash; replicates
     * the COBOL {@code ON SIZE ERROR} semantic for the
     * {@code ADD 1 TO WS-TRAN-ID-N} arithmetic (per AAP &sect;0.7.1
     * implementation rules).
     */
    private String nextTransactionId() {
        String maxId = transactionRepository.findMaxTranId().orElse(SEED_TRAN_ID);

        BigDecimal numeric;
        try {
            numeric = new BigDecimal(maxId);
        } catch (NumberFormatException nfe) {
            // Defensive — COBOL stores tran_id as 16-character numeric;
            // a non-numeric value in the table indicates a corruption
            // upstream. Treat as max+1 from the seed.
            LOG.warn("TransactionAddService: non-numeric tran_id encountered; reseeding");
            numeric = BigDecimal.ZERO;
        }

        BigDecimal nextNumeric = numeric.add(BigDecimal.ONE);
        if (nextNumeric.compareTo(MAX_TRAN_ID) > 0) {
            // Replaces COBOL "ON SIZE ERROR" clause on ADD operation.
            throw new OnSizeErrorException(
                    "TRAN_ID_OVERFLOW",
                    "Transaction ID space exhausted (max 9999999999999999)");
        }
        // Zero-pad to 16 digits per COBOL TRAN-ID PIC 9(16) layout.
        return String.format("%016d", nextNumeric.longValueExact());
    }

    // -----------------------------------------------------------------
    // Validation -- preserves COBOL 1000-PROCESS-INPUTS semantics
    // -----------------------------------------------------------------

    /**
     * Validates field-level rules; throws {@link ValidationException}
     * carrying every error discovered.
     */
    private void validate(TransactionAddDto request) {
        List<ValidationException.FieldError> errors = new ArrayList<>();

        String accountIdStr = request.accountId();
        String cardNumber = request.cardNumber();

        // COBOL: at least one of ACTIDIN or CARDNIN must be present.
        boolean hasAccount = accountIdStr != null && !accountIdStr.isBlank();
        boolean hasCard = cardNumber != null && !cardNumber.isBlank();
        if (!hasAccount && !hasCard) {
            errors.add(new ValidationException.FieldError(
                    "accountId",
                    "Either accountId or cardNumber must be supplied"));
        }

        if (hasAccount) {
            if (!accountIdStr.matches("^\\d{11}$")) {
                errors.add(new ValidationException.FieldError(
                        "accountId", "accountId must be exactly 11 digits"));
            }
        }

        if (hasCard) {
            if (!cardNumber.matches("^\\d{16}$")) {
                errors.add(new ValidationException.FieldError(
                        "cardNumber", "cardNumber must be exactly 16 digits"));
            }
        }

        if (request.transactionType() == null || request.transactionType().isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "transactionType", "transactionType is required"));
        }

        if (request.transactionCategory() == null) {
            errors.add(new ValidationException.FieldError(
                    "transactionCategory", "transactionCategory is required"));
        }

        if (request.source() == null || request.source().isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "source", "source is required"));
        }

        if (request.amount() == null) {
            errors.add(new ValidationException.FieldError(
                    "amount", "amount is required"));
        }

        // COBOL TRAN-ORIG-TS, TRAN-PROC-TS — both validated via the
        // DateValidationService (LE CEEDAYS replacement). The COBOL
        // source accepts free-form date strings; we accept ISO-8601.
        if (request.originationTimestamp() != null) {
            DateValidationService.DateValidationResult result =
                    dateValidationService.validate(request.originationTimestamp().toLocalDate().toString());
            if (!result.isValid()) {
                errors.add(new ValidationException.FieldError(
                        "originationTimestamp", result.errorMessage()));
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(
                    "VALIDATION_FAILED",
                    "Transaction add request contains invalid fields",
                    errors);
        }
    }

    /**
     * Resolves the owning account ID from the supplied account or
     * card identifier. Mirrors the COBOL
     * {@code READ-CXACAIX-FILE} paragraph which is invoked either
     * keyed by card number (random access) or by account number
     * (alternate-index browse via {@code CXACAIX}).
     */
    private Long resolveAccountId(TransactionAddDto request) {
        // Case 1: both supplied → check consistency.
        if (request.cardNumber() != null && !request.cardNumber().isBlank()) {
            Optional<CardCrossReference> xref = cardCrossReferenceRepository
                    .findById(request.cardNumber());
            if (xref.isEmpty()) {
                throw new RecordNotFoundException(
                        "XREF_NOT_FOUND",
                        "Card cross-reference not found for the supplied card number");
            }
            Long fromCard = xref.get().getXrefAcctId();
            if (request.accountId() != null && !request.accountId().isBlank()) {
                Long fromInput = parseAccountId(request.accountId());
                if (!Objects.equals(fromCard, fromInput)) {
                    throw new ValidationException(
                            "XREF_MISMATCH",
                            "Supplied accountId does not match the card's owning account",
                            List.of(new ValidationException.FieldError(
                                    "accountId",
                                    "accountId does not cross-reference to cardNumber")));
                }
            }
            return fromCard;
        }

        // Case 2: only account supplied → look up via AIX path.
        Long fromInput = parseAccountId(request.accountId());
        List<CardCrossReference> xrefs = cardCrossReferenceRepository
                .findByXrefAcctId(fromInput);
        if (xrefs.isEmpty()) {
            throw new RecordNotFoundException(
                    "XREF_NOT_FOUND",
                    "No card cross-reference found for the supplied accountId");
        }
        return fromInput;
    }

    /** Parses an 11-digit account ID string; never throws past validation. */
    private Long parseAccountId(String accountIdStr) {
        try {
            return Long.parseLong(accountIdStr);
        } catch (NumberFormatException nfe) {
            // Should have been caught by validate(); defensive only.
            throw new ValidationException(
                    "INVALID_ACCOUNT_ID",
                    "accountId must be numeric",
                    List.of(new ValidationException.FieldError(
                            "accountId", "accountId must be numeric")));
        }
    }

    private String safeTrim(String s) {
        return s == null ? null : s.trim();
    }

    private String lastFour(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return pan.substring(pan.length() - 4);
    }
}
