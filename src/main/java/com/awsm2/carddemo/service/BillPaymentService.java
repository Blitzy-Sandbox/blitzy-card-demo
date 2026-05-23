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
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.BillPaymentDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bill-payment service &mdash; the Java target for the COBOL/CICS program
 * {@code app/cbl/COBIL00C.cbl} (CICS transaction id {@code CB00}).
 *
 * <p>This service implements the &quot;pay-full-balance&quot; flow modeled
 * on the COBOL source: read the account, resolve the cardholder's primary
 * card via the {@code CARDXREF} alternate index, assign the next
 * transaction ID (MAX-TRAN-ID + 1), write a new {@code TRAN-RECORD} whose
 * amount equals the current balance, and update the account's
 * {@code ACCT-CURR-BAL} to zero. The transaction's type, category,
 * source, description, and merchant fields are <b>literal constants</b>
 * carried over verbatim from the COBOL source per AAP &sect;0.7.3 Minimal
 * Change Clause.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COBIL00C.cbl} (CICS TRANID
 *       {@code 'CB00'}, files {@code 'ACCTDAT'} +
 *       {@code 'CARDXREF'} + {@code 'TRANSACT'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COBIL00.bms} (mapset
 *       {@code COBIL00}, map {@code COBIL0A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COBIL00.CPY}.</li>
 *   <li><b>Record layouts:</b> {@code app/cpy/CVACT01Y.cpy} (account),
 *       {@code app/cpy/CVTRA05Y.cpy} (transaction),
 *       {@code app/cpy/CVACT03Y.cpy} (cross-reference).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation (lines 209&ndash;240 of COBIL00C.cbl)</h2>
 * <table>
 *   <caption>COBIL00C.cbl &harr; BillPaymentService.payBill(...)</caption>
 *   <tr><th>COBOL paragraph / statement</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PERFORM READ-CXACAIX-FILE}</td>
 *       <td>{@link CardCrossReferenceRepository#findByXrefAcctId(Long)}
 *       &rarr; first card</td></tr>
 *   <tr><td>{@code MOVE HIGH-VALUES TO TRAN-ID; STARTBR; READPREV;
 *       ENDBR; ADD 1 TO WS-TRAN-ID-NUM}</td>
 *       <td>{@link TransactionRepository#findMaxTranId()} + 1 with
 *       zero-pad to 16 digits (AAP &sect;0.6.2)</td></tr>
 *   <tr><td>{@code INITIALIZE TRAN-RECORD} + {@code MOVE '02' TO
 *       TRAN-TYPE-CD} + {@code MOVE 2 TO TRAN-CAT-CD} + {@code MOVE
 *       'POS TERM' TO TRAN-SOURCE} + {@code MOVE 'BILL PAYMENT -
 *       ONLINE' TO TRAN-DESC} + {@code MOVE 999999999 TO TRAN-MERCHANT-
 *       ID} + {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME} +
 *       {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY/ZIP}</td>
 *       <td>Literal constants defined as {@code private static final}
 *       fields on this class &mdash; carried over verbatim per
 *       Minimal Change Clause</td></tr>
 *   <tr><td>{@code MOVE ACCT-CURR-BAL TO TRAN-AMT}</td>
 *       <td>{@code tranAmt = account.getAcctCurrBal()} (BigDecimal,
 *       scale 2, RoundingMode.HALF_EVEN preserved)</td></tr>
 *   <tr><td>{@code PERFORM WRITE-TRANSACT-FILE}</td>
 *       <td>{@link TransactionRepository#save(Object)}</td></tr>
 *   <tr><td>{@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}</td>
 *       <td>{@code newBalance = currentBalance.subtract(tranAmt)
 *       .setScale(2, RoundingMode.HALF_EVEN)} with explicit
 *       {@link OnSizeErrorException} guard (replaces COBOL
 *       {@code ON SIZE ERROR} per AAP &sect;0.7.1)</td></tr>
 *   <tr><td>{@code PERFORM UPDATE-ACCTDAT-FILE}</td>
 *       <td>{@link AccountRepository#save(Object)} within the same
 *       {@link Transactional @Transactional} boundary as the
 *       transaction write</td></tr>
 *   <tr><td>{@code SYNCPOINT} (implicit on task end)</td>
 *       <td>{@link Transactional @Transactional(rollbackFor =
 *       Exception.class)} &mdash; atomic 2-row write</td></tr>
 *   <tr><td>(NEW) MSK event publication</td>
 *       <td>{@link KafkaEventPublisher#publishTransactionPosted(Long,
 *       TransactionAddDto)} AND
 *       {@link KafkaEventPublisher#publishAccountUpdated(Long,
 *       AccountUpdateDto)} &mdash; both partitioned by the OWNING
 *       ACCOUNT ID per AAP &sect;0.6.5</td></tr>
 * </table>
 *
 * <h2>Transactional integrity (AAP &sect;0.6.2)</h2>
 *
 * <p>The transaction-write + account-update pair is the
 * <i>second</i> explicit dual-dataset transactional boundary in the
 * source code base after {@code COACTUPC.cbl} (the
 * {@code COACTUPC} program declares an explicit
 * {@code SYNCPOINT ROLLBACK} on customer-update failure; the COBIL00C
 * program relies on the implicit CICS task-end {@code SYNCPOINT}
 * because both writes are in the same task). Both are translated to
 * {@code @Transactional(rollbackFor = Exception.class)} on the
 * service method.</p>
 *
 * <h2>ON SIZE ERROR guard</h2>
 *
 * <p>The COBOL {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}
 * implicitly carries an {@code ON SIZE ERROR} clause across the
 * compilation unit. The Java target enforces the same precision rule
 * explicitly: the result is validated against
 * {@code @Column(precision = 12, scale = 2)} on
 * {@link Account#getAcctCurrBal()} before save; overflow raises
 * {@link OnSizeErrorException} (HTTP 422 via
 * {@code GlobalExceptionHandler}).</p>
 *
 * @see AccountRepository
 * @see TransactionRepository
 * @see CardCrossReferenceRepository
 * @see BillPaymentDto
 */
@Service
public class BillPaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(BillPaymentService.class);

    /** COBOL: MOVE '02' TO TRAN-TYPE-CD (bill payment type). */
    private static final String TRAN_TYPE_BILL_PAYMENT = "02";
    /** COBOL: MOVE 2 TO TRAN-CAT-CD. */
    private static final Integer TRAN_CAT_BILL_PAYMENT = 2;
    /** COBOL: MOVE 'POS TERM' TO TRAN-SOURCE. */
    private static final String TRAN_SOURCE_POS_TERM = "POS TERM";
    /** COBOL: MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC. */
    private static final String TRAN_DESC = "BILL PAYMENT - ONLINE";
    /** COBOL: MOVE 999999999 TO TRAN-MERCHANT-ID. */
    private static final Long MERCHANT_ID = 999999999L;
    /** COBOL: MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME. */
    private static final String MERCHANT_NAME = "BILL PAYMENT";
    /** COBOL: MOVE 'N/A' TO TRAN-MERCHANT-CITY. */
    private static final String MERCHANT_CITY = "N/A";
    /** COBOL: MOVE 'N/A' TO TRAN-MERCHANT-ZIP. */
    private static final String MERCHANT_ZIP = "N/A";

    /** Maximum tranId numeric value (16-digit ceiling). */
    private static final BigDecimal MAX_TRAN_ID = new BigDecimal("9999999999999999");

    /** Seed when transactions table is empty (mirrors HIGH-VALUES). */
    private static final String SEED_TRAN_ID = "0000000000000000";

    /** Account balance precision per {@code CVACT01Y.cpy} (PIC S9(10)V99). */
    private static final BigDecimal MAX_ACCOUNT_BALANCE = new BigDecimal("99999999999.99");

    /** Cache namespace; mirrors AccountViewService. */
    private static final String CACHE_NS_ACCOUNT = "accountView";

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final TransactionRepository transactionRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final CacheService cacheService;
    private final AuditLogService auditLogService;

    public BillPaymentService(AccountRepository accountRepository,
                              CustomerRepository customerRepository,
                              CardCrossReferenceRepository cardCrossReferenceRepository,
                              TransactionRepository transactionRepository,
                              KafkaEventPublisher kafkaEventPublisher,
                              CacheService cacheService,
                              AuditLogService auditLogService) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository");
        this.customerRepository = Objects.requireNonNull(customerRepository,
                "customerRepository");
        this.cardCrossReferenceRepository = Objects.requireNonNull(cardCrossReferenceRepository,
                "cardCrossReferenceRepository");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository");
        this.kafkaEventPublisher = Objects.requireNonNull(kafkaEventPublisher,
                "kafkaEventPublisher");
        this.cacheService = Objects.requireNonNull(cacheService,
                "cacheService");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService");
    }

    /**
     * Pay the full outstanding balance of the supplied account.
     *
     * @param request the {@link BillPaymentDto} carrying
     *                {@code accountId} and {@code confirm = "Y"}
     * @return a populated {@link BillPaymentDto} with the new
     *         {@code transactionId}, {@code postedAt} timestamp,
     *         {@code amountPaid}, and updated {@code currentBalance}
     *         (will be {@code 0.00} on success when the full balance
     *         was paid)
     * @throws ValidationException     if account ID is missing/invalid
     *                                 or {@code confirm} is not
     *                                 {@code "Y"}
     * @throws RecordNotFoundException if no account, customer, or
     *                                 cross-reference exists
     * @throws OnSizeErrorException    if the post-payment balance
     *                                 would overflow the COBOL
     *                                 precision ceiling
     */
    @Transactional(rollbackFor = Exception.class)
    public BillPaymentDto payBill(BillPaymentDto request) {
        Objects.requireNonNull(request, "request");

        // ---- COBOL: validate inputs ------------------------------------
        if (request.accountId() == null || request.accountId().isBlank()) {
            throw new ValidationException(
                    "MISSING_ACCOUNT_ID",
                    "accountId is required",
                    List.of(new ValidationException.FieldError(
                            "accountId", "accountId is required")));
        }
        if (!request.accountId().matches("^\\d{1,11}$")) {
            throw new ValidationException(
                    "INVALID_ACCOUNT_ID",
                    "accountId must be 1 to 11 digits",
                    List.of(new ValidationException.FieldError(
                            "accountId", "accountId must be 1 to 11 digits")));
        }
        // COBOL: IF CONF-PAY-YES — operator must confirm with 'Y'.
        if (!"Y".equalsIgnoreCase(request.confirm())) {
            throw new ValidationException(
                    "NOT_CONFIRMED",
                    "Operator did not confirm the bill payment (confirm must be 'Y')",
                    List.of(new ValidationException.FieldError(
                            "confirm", "Set confirm='Y' to commit the bill payment")));
        }

        Long acctId = Long.parseLong(request.accountId());

        // ---- COBOL READ-ACCTDAT-FILE -----------------------------------
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "ACCOUNT_NOT_FOUND", "Account not found"));

        // ---- COBOL READ-CXACAIX-FILE (resolve a primary card) ----------
        List<CardCrossReference> xrefs = cardCrossReferenceRepository
                .findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            throw new RecordNotFoundException(
                    "XREF_NOT_FOUND",
                    "No card cross-reference for account " + acctId);
        }
        String cardNum = xrefs.get(0).getXrefCardNum();

        // ---- COBOL: 'You have nothing to pay...' branch ----------------
        BigDecimal currentBalance = safeBalance(account.getAcctCurrBal());
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException(
                    "NOTHING_TO_PAY",
                    "You have nothing to pay",
                    List.of(new ValidationException.FieldError(
                            "currentBalance",
                            "Current balance is non-positive; payment is not allowed")));
        }

        // ---- COBOL ADD-TRANSACTION (MAX-TRAN-ID + 1) -------------------
        String nextTranId = nextTransactionId();

        // ---- COBOL INITIALIZE TRAN-RECORD + MOVE * TO TRAN-* -----------
        LocalDateTime now = LocalDateTime.now();
        BigDecimal tranAmt = currentBalance.setScale(2, RoundingMode.HALF_EVEN);
        Transaction tran = new Transaction(
                nextTranId,
                TRAN_TYPE_BILL_PAYMENT,
                TRAN_CAT_BILL_PAYMENT,
                TRAN_SOURCE_POS_TERM,
                TRAN_DESC,
                tranAmt,
                MERCHANT_ID,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_ZIP,
                cardNum,
                now,
                now);

        // ---- COBOL WRITE-TRANSACT-FILE ---------------------------------
        Transaction savedTran = transactionRepository.save(tran);

        // ---- COBOL COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT ----
        // Explicit ON SIZE ERROR guard per AAP §0.7.1: the post-payment
        // balance must fit within the entity column precision.
        BigDecimal newBalance = currentBalance.subtract(tranAmt)
                .setScale(2, RoundingMode.HALF_EVEN);
        if (newBalance.abs().compareTo(MAX_ACCOUNT_BALANCE) > 0) {
            throw new OnSizeErrorException(
                    "BALANCE_OVERFLOW",
                    "Post-payment balance exceeds PIC S9(10)V99 ceiling");
        }
        account.setAcctCurrBal(newBalance);

        // ---- COBOL UPDATE-ACCTDAT-FILE ---------------------------------
        Account savedAccount = accountRepository.save(account);

        // ---- Cache eviction (cache-aside invalidation) -----------------
        String cacheKey = String.format("%011d", savedAccount.getAcctId());
        try {
            cacheService.evict(CACHE_NS_ACCOUNT, cacheKey);
        } catch (RuntimeException rex) {
            LOG.warn("BillPaymentService: cache eviction failed; tx commit will proceed: {}",
                    rex.getMessage());
        }

        // ---- MSK events: BOTH transaction.posted AND account.updated --
        // Both partitioned by acctId per AAP §0.6.5 for per-account
        // ordering across event types.
        TransactionAddDto txEvent = new TransactionAddDto(
                String.format("%011d", savedAccount.getAcctId()),
                savedTran.getTranCardNum(),
                savedTran.getTranTypeCd(),
                savedTran.getTranCatCd(),
                savedTran.getTranSource(),
                savedTran.getTranDesc(),
                savedTran.getTranAmt(),
                savedTran.getTranOrigTs(),
                savedTran.getTranProcTs(),
                savedTran.getTranMerchantId(),
                savedTran.getTranMerchantName(),
                savedTran.getTranMerchantCity(),
                savedTran.getTranMerchantZip(),
                "Y");
        kafkaEventPublisher.publishTransactionPosted(savedAccount.getAcctId(), txEvent);

        // Build a representative AccountUpdateDto event payload.
        // Customer fields are required by the DTO contract; fetched
        // best-effort here. If absent, customer-derived fields are null.
        Customer customer = customerRepository.findById(savedAccount.getAcctId()).orElse(null);
        AccountUpdateDto acctEvent = buildAccountUpdateEvent(savedAccount, customer);
        kafkaEventPublisher.publishAccountUpdated(savedAccount.getAcctId(), acctEvent);

        // ---- Audit -----------------------------------------------------
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", savedTran.getTranId());
        payload.put("accountId", savedAccount.getAcctId());
        payload.put("amountPaid", tranAmt);
        payload.put("newBalance", newBalance);
        auditLogService.auditEvent("bill.paid", "system", payload);

        LOG.info("Bill payment posted tranId={} acctId={} amountPaid={} newBalance={}",
                savedTran.getTranId(), savedAccount.getAcctId(), tranAmt, newBalance);

        // ---- Build response --------------------------------------------
        return new BillPaymentDto(
                String.format("%011d", savedAccount.getAcctId()),
                newBalance,
                "Y",
                savedTran.getTranId(),
                savedTran.getTranProcTs(),
                tranAmt);
    }

    /**
     * Computes the next 16-digit zero-padded transaction ID by reading
     * {@link TransactionRepository#findMaxTranId()} and adding 1.
     * Implements the MAX-TRAN-ID + 1 idiom per AAP &sect;0.6.2.
     */
    private String nextTransactionId() {
        String maxId = transactionRepository.findMaxTranId().orElse(SEED_TRAN_ID);
        BigDecimal numeric;
        try {
            numeric = new BigDecimal(maxId);
        } catch (NumberFormatException nfe) {
            LOG.warn("BillPaymentService: non-numeric tran_id encountered; reseeding");
            numeric = BigDecimal.ZERO;
        }
        BigDecimal nextNumeric = numeric.add(BigDecimal.ONE);
        if (nextNumeric.compareTo(MAX_TRAN_ID) > 0) {
            throw new OnSizeErrorException(
                    "TRAN_ID_OVERFLOW",
                    "Transaction ID space exhausted (max 9999999999999999)");
        }
        return String.format("%016d", nextNumeric.longValueExact());
    }

    private BigDecimal safeBalance(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Builds an {@link AccountUpdateDto} event payload from the
     * just-persisted {@link Account} and (optional) {@link Customer}.
     * Customer-derived fields default to {@code null} when the
     * customer record cannot be located &mdash; the MSK event
     * recipients (audit, reporting) treat null fields as
     * "unchanged" / "not present".
     */
    private AccountUpdateDto buildAccountUpdateEvent(Account a, Customer c) {
        return new AccountUpdateDto(
                a.getAcctId(),
                a.getAcctActiveStatus(),
                a.getAcctCurrBal(),
                a.getAcctCreditLimit(),
                a.getAcctCashCreditLimit(),
                a.getAcctOpenDate(),
                a.getAcctExpirationDate(),
                a.getAcctReissueDate(),
                a.getAcctCurrCycCredit(),
                a.getAcctCurrCycDebit(),
                a.getAcctAddrZip(),
                a.getAcctGroupId(),
                c == null ? null : c.getCustId(),
                c == null ? null : c.getCustFirstName(),
                c == null ? null : c.getCustMiddleName(),
                c == null ? null : c.getCustLastName(),
                c == null ? null : c.getCustSsn(),
                c == null ? null : c.getCustPhoneNum1(),
                c == null ? null : c.getCustPhoneNum2(),
                c == null ? null : c.getCustAddrLine1(),
                c == null ? null : c.getCustAddrLine2(),
                c == null ? null : c.getCustAddrLine3(),
                c == null ? null : c.getCustAddrStateCd(),
                c == null ? null : c.getCustAddrCountryCd(),
                c == null ? null : c.getCustAddrZip(),
                c == null ? null : c.getCustDobYyyyMmDd(),
                c == null ? null : c.getCustGovtIssuedId(),
                c == null ? null : c.getCustEftAccountId(),
                c == null ? null : c.getCustPriCardHolderInd(),
                c == null ? null : c.getCustFicoCreditScore(),
                a.getVersion());
    }
}
