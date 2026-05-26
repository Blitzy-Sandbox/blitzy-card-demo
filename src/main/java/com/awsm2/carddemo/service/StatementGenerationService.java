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
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Customer;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.exception.CardDemoException;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.CustomerRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Statement generation service — produces per-account billing
 * statements in BOTH plain-text (LRECL=80) AND HTML (LRECL=100)
 * formats and uploads the rendered bodies to S3 via
 * {@link S3OutputService}. This service is the Java target for the
 * mainframe COBOL programs:
 *
 * <ul>
 *   <li><b>{@code app/cbl/CBSTM03A.CBL}</b> — main statement generator
 *       (text + HTML output); invoked by JCL job
 *       {@code app/jcl/CREASTMT.JCL}.</li>
 *   <li><b>{@code app/cbl/CBSTM03B.CBL}</b> — file-service subroutine
 *       for I/O dispatch invoked by {@code CBSTM03A}; superseded in
 *       the Java target by the
 *       {@link org.springframework.data.jpa.repository.JpaRepository
 *       JpaRepository}-based persistence layer, so it has no
 *       standalone Java counterpart.</li>
 * </ul>
 *
 * <h2>Template Method pattern (AAP &sect;0.3.3)</h2>
 * <p>The shared statement-skeleton logic (header &rarr; customer block
 * &rarr; account block &rarr; transaction detail loop &rarr; total
 * &rarr; footer) lives in {@link #generateStatementForAccount}; only
 * the leaf rendering methods {@link #renderTextStatement} and
 * {@link #renderHtmlStatement} vary between the two output formats.
 * Each successful invocation produces TWO S3 objects per account.</p>
 *
 * <h2>Source provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL programs:</b>
 *       {@code app/cbl/CBSTM03A.CBL} (statement renderer) +
 *       {@code app/cbl/CBSTM03B.CBL} (file-service subroutine).</li>
 *   <li><b>Record layouts referenced:</b>
 *       {@code app/cpy/CVCUS01Y.cpy} ({@link Customer}),
 *       {@code app/cpy/CVACT01Y.cpy} ({@link Account}),
 *       {@code app/cpy/CVACT03Y.cpy} ({@link CardCrossReference}),
 *       {@code app/cpy/CVTRA05Y.cpy} ({@link Transaction}).</li>
 *   <li><b>JCL trigger:</b> {@code app/jcl/CREASTMT.JCL} (STEP10
 *       EXEC PGM=CBSTM03A, STMTFILE DD LRECL=80, HTMLFILE DD
 *       LRECL=100).</li>
 *   <li><b>Target output:</b> Two S3 objects per account written via
 *       {@link S3OutputService#writeReport(String, byte[])} — the
 *       adapter detects the {@code .html} extension and switches the
 *       Content-Type accordingly per AAP &sect;0.3.3 (Adapter
 *       Pattern).</li>
 * </ul>
 *
 * <h2>COBOL paragraph &rarr; Java method mapping (CBSTM03A.CBL)</h2>
 * <table>
 *   <caption>CBSTM03A paragraphs &harr; this service</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} / {@code 1000-MAINLINE}</td>
 *       <td>{@link #generateStatements(LocalDate)} top-level loop</td></tr>
 *   <tr><td>{@code 1000-XREFFILE-GET-NEXT}</td>
 *       <td>{@link CardCrossReferenceRepository#findByXrefAcctIdOrderByXrefCardNumAsc(Long)}</td></tr>
 *   <tr><td>{@code 2000-CUSTFILE-GET}</td>
 *       <td>{@link CustomerRepository#findById(Object)}</td></tr>
 *   <tr><td>{@code 3000-ACCTFILE-GET}</td>
 *       <td>{@link AccountRepository#findAll()} (the driver iterates
 *           accounts directly in the Java target)</td></tr>
 *   <tr><td>{@code 4000-TRNXFILE-GET} / {@code 8500-READTRNX-READ}</td>
 *       <td>{@link TransactionRepository#findByTranCardNumAndTranProcTsBetween(String, LocalDateTime, LocalDateTime, Pageable)}</td></tr>
 *   <tr><td>{@code 5000-CREATE-STATEMENT}</td>
 *       <td>{@link #generateStatementForAccount(Account, LocalDate)}</td></tr>
 *   <tr><td>{@code 5100-WRITE-HTML-HEADER} +
 *           {@code 5200-WRITE-HTML-NMADBS}</td>
 *       <td>{@link #renderHtmlStatement(StatementContext)} header
 *           block</td></tr>
 *   <tr><td>{@code 6000-WRITE-TRANS}</td>
 *       <td>{@link #renderTextStatement(StatementContext)} +
 *           {@link #renderHtmlStatement(StatementContext)} detail
 *           rows</td></tr>
 *   <tr><td>{@code ADD TRNX-AMT TO WS-TOTAL-AMT} (L429)</td>
 *       <td>{@link BigDecimal#add(BigDecimal)} with
 *           {@link RoundingMode#HALF_EVEN} scale=2; overflow guarded
 *           by {@link OnSizeErrorException}</td></tr>
 * </table>
 *
 * <h2>Implementation rules (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>{@code @Transactional(readOnly = true)}</b> &mdash; writes
 *       are to S3 only, not RDS; declarative transaction is read-only
 *       to hint the JDBC driver and flush mode.</li>
 *   <li><b>BigDecimal arithmetic with HALF_EVEN</b> &mdash;
 *       {@link BigDecimal#setScale(int, RoundingMode)} with
 *       {@link RoundingMode#HALF_EVEN} (banker's rounding) per AAP
 *       &sect;0.6.1; zero {@code float}/{@code double} substitution for
 *       any monetary field.</li>
 *   <li><b>ON SIZE ERROR</b> &mdash; the running total
 *       {@code WS-TOTAL-AMT} (COBOL {@code PIC S9(9)V99}) is guarded
 *       against its 999,999,999.99 ceiling per AAP &sect;0.6.1 and
 *       throws {@link OnSizeErrorException} on overflow.</li>
 *   <li><b>PCI-DSS Req 3.3</b> &mdash; card numbers are masked to
 *       last-4 ({@code ****-****-****-NNNN}) before rendering into
 *       either output; the full PAN never appears in the produced
 *       artefacts.</li>
 *   <li><b>Verbatim COBOL HTML constants</b> &mdash; the HTML
 *       renderer preserves the colour palette (
 *       {@code #1d1d96b3}, {@code #FFAF33}, {@code #33FF5E},
 *       {@code #f2f2f2}), the table widths
 *       ({@code 70%}, {@code 25%}, {@code 55%}, {@code 20%}), and
 *       the bank header literals ({@code Bank of XYZ},
 *       {@code 410 Terry Ave N}, {@code Seattle WA 99999}) verbatim
 *       from {@code CBSTM03A.CBL:L150-L223}.</li>
 *   <li><b>80-byte text lines</b> &mdash; every plain-text line is
 *       padded/truncated to exactly 80 chars and terminated with LF,
 *       preserving the COBOL {@code STMTFILE DD LRECL=80} byte
 *       contract; the HTML lines are content-driven and not
 *       fixed-width (per CBSTM03A working-storage, only
 *       {@code HTML-FIXED-LN} is 100 bytes wide; emitted content can
 *       be shorter).</li>
 *   <li><b>Adapter pattern</b> &mdash; all S3 and audit emission go
 *       through {@link S3OutputService} and {@link AuditLogService};
 *       no direct AWS SDK calls in this class (AAP &sect;0.3.3 /
 *       &sect;0.7.1).</li>
 *   <li><b>Constructor injection only</b> &mdash; no
 *       {@code @Autowired} on fields; all dependencies are
 *       constructor-supplied {@code final} fields.</li>
 *   <li><b>Per-account error isolation</b> &mdash; a failure
 *       generating one account's statement increments {@code errorCount}
 *       but does NOT abort the batch; the COBOL source's
 *       {@code 9999-ABEND-PROGRAM} hard-abort semantic is replaced
 *       by counted-and-continue per AAP &sect;0.7.1 (preserve
 *       behavior of producing remaining statements) and aligns with
 *       Step Functions {@code Catch} / Spring Batch
 *       {@code FaultTolerantStepBuilder} idioms.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.batch.StatementGenerationJob
 * @see AccountRepository
 * @see CardCrossReferenceRepository
 * @see CustomerRepository
 * @see TransactionRepository
 * @see S3OutputService
 * @see AuditLogService
 * @see CardDemoException (base exception for any S3 / audit transport failure)
 * @see RecordNotFoundException (typed domain exception for XREF / customer
 *      lookup misses — referenced for schema compliance; the current driver
 *      handles these as &quot;skip&quot; rather than &quot;error&quot; to mirror the COBOL
 *      WHEN &#39;10&#39; (NOTFND) continue-to-next-iteration semantic)
 */
@Service
public class StatementGenerationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(StatementGenerationService.class);

    /**
     * ON SIZE ERROR guard for COBOL {@code WS-TOTAL-AMT}
     * ({@code PIC S9(9)V99}). Per AAP &sect;0.6.1, the running total
     * must not exceed the COBOL field's 999,999,999.99 ceiling; if
     * it would, an {@link OnSizeErrorException} is thrown to mirror
     * the COBOL {@code ON SIZE ERROR} semantic.
     */
    static final BigDecimal MAX_STATEMENT_TOTAL = new BigDecimal("999999999.99");

    /**
     * Plain-text statement line width — matches CBSTM03A's
     * {@code FD-STMTFILE-REC PIC X(80)} declaration
     * ({@code STMTFILE DD LRECL=80}).
     */
    static final int TEXT_LINE_WIDTH = 80;

    /**
     * HTML statement logical line width — matches CBSTM03A's
     * {@code FD-HTMLFILE-REC PIC X(100)} declaration
     * ({@code HTMLFILE DD LRECL=100}). The COBOL HTML constants are
     * 100-byte fixed records, but the Java HTML output is
     * content-driven (browsers ignore record boundaries).
     */
    static final int HTML_LINE_WIDTH = 100;

    /**
     * Audit event identifier emitted by both the per-statement audit
     * call and the batch run-summary audit call. Indexed in
     * OpenSearch under {@code carddemo-audit} per AAP &sect;0.6.6.
     */
    static final String AUDIT_EVENT_STATEMENT_GENERATED = "statement.generated";

    /** Audit resource type for per-statement audit records. */
    static final String AUDIT_RESOURCE_STATEMENT = "STATEMENT";

    /** Audit resource type for the run-summary audit record. */
    static final String AUDIT_RESOURCE_BATCH_RUN = "BATCH_RUN";

    /**
     * Audit operator code — this service runs in the Spring Batch /
     * AWS Batch context, so the operator is always {@code BATCH}
     * (mirroring the COBOL job-level identity).
     */
    static final String AUDIT_OPERATOR_BATCH = "BATCH";

    /**
     * Date formatter used to render the statement date into log
     * messages, audit correlation IDs, and timestamp strings. The
     * pattern matches ISO-8601 {@code yyyy-MM-dd}.
     */
    private static final DateTimeFormatter STATEMENT_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Date-time formatter used to suffix the statement identifier
     * (e.g., {@code stmt-00000000001-20250131120000}) so each run
     * produces a uniquely-keyed S3 object even when multiple runs
     * land on the same date.
     */
    private static final DateTimeFormatter STATEMENT_ID_TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Wide-open lower bound on {@code tran_proc_ts} for the per-card
     * transaction lookup. The COBOL CBSTM03A driver consumes every
     * transaction on the card list (the cycle date-window bounding is
     * applied upstream by the JCL caller, not in this paragraph), so
     * the Java service queries with maximally permissive timestamp
     * bounds to preserve the COBOL semantic.
     */
    static final LocalDateTime STMT_MIN_TS = LocalDateTime.of(1900, 1, 1, 0, 0);

    /**
     * Wide-open upper bound on {@code tran_proc_ts} counterpart to
     * {@link #STMT_MIN_TS}.
     */
    static final LocalDateTime STMT_MAX_TS =
            LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999);

    // -------------------------------------------------------------------------
    // Constructor-injected dependencies (final fields, no @Autowired)
    // -------------------------------------------------------------------------

    private final AccountRepository accountRepository;
    private final CardCrossReferenceRepository xrefRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final S3OutputService s3OutputService;
    private final AuditLogService auditLogService;

    /**
     * Constructs the service with all required collaborators
     * supplied via constructor injection (no field
     * {@code @Autowired}) per the AAP §0.3.3 Layered Architecture
     * rule. Parameter order matches the schema export contract
     * exactly:
     * {@code (AccountRepository, CardCrossReferenceRepository,
     * CustomerRepository, TransactionRepository, S3OutputService,
     * AuditLogService)}.
     *
     * @param accountRepository      JPA repository over the
     *                               {@code accounts} table; used to
     *                               drive the per-account iteration
     *                               loop ({@code findAll()})
     * @param xrefRepository         JPA repository over the
     *                               {@code card_xref} table; the
     *                               {@code findByXrefAcctIdOrderByXrefCardNumAsc}
     *                               method replaces the
     *                               {@code CXACAIX} VSAM alternate
     *                               index from {@code CBSTM03A:1000-A}
     * @param customerRepository     JPA repository over the
     *                               {@code customers} table; resolves
     *                               the owning customer per
     *                               {@code CBSTM03A:2000-B}
     * @param transactionRepository  JPA repository over the
     *                               {@code transactions} table;
     *                               supplies the per-card transaction
     *                               slice via
     *                               {@code findByTranCardNumAndTranProcTsBetween}
     *                               per {@code CBSTM03A:4000-C}
     * @param s3OutputService        AWS-S3 adapter for uploading the
     *                               two rendered statement bodies
     *                               (text + HTML) — replaces
     *                               {@code WRITE FD-STMTFILE-REC} and
     *                               {@code WRITE FD-HTMLFILE-REC} per
     *                               {@code CREASTMT.JCL}
     * @param auditLogService        Audit-trail adapter that emits a
     *                               per-statement record and a
     *                               batch-summary record to
     *                               OpenSearch + CloudWatch per AAP
     *                               §0.6.6
     */
    public StatementGenerationService(
            AccountRepository accountRepository,
            CardCrossReferenceRepository xrefRepository,
            CustomerRepository customerRepository,
            TransactionRepository transactionRepository,
            S3OutputService s3OutputService,
            AuditLogService auditLogService) {
        this.accountRepository = Objects.requireNonNull(
                accountRepository, "accountRepository");
        this.xrefRepository = Objects.requireNonNull(
                xrefRepository, "xrefRepository");
        this.customerRepository = Objects.requireNonNull(
                customerRepository, "customerRepository");
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
        this.s3OutputService = Objects.requireNonNull(
                s3OutputService, "s3OutputService");
        this.auditLogService = Objects.requireNonNull(
                auditLogService, "auditLogService");
    }

    /**
     * Result summary returned by {@link #generateStatements(LocalDate)}.
     *
     * <p>The three counters mirror the high-level outcomes of a
     * statement-generation batch run: how many accounts produced a
     * successful text statement, how many produced a successful HTML
     * statement, and how many failed (regardless of cause). Because
     * every successful account produces BOTH outputs atomically,
     * {@link #textCount()} and {@link #htmlCount()} are equal in the
     * common happy-path case — they diverge only if a future
     * refactor introduces partial-success semantics. The
     * {@link #errorCount()} surface captures failures (uncaught
     * exceptions in {@link #generateStatementForAccount}) so the
     * caller can compare against the total account population for an
     * operational success-rate metric.</p>
     *
     * @param textCount  number of accounts for which the plain-text
     *                   statement was successfully written to S3
     * @param htmlCount  number of accounts for which the HTML
     *                   statement was successfully written to S3
     * @param errorCount number of accounts whose statement generation
     *                   threw an exception (the exception is logged
     *                   but not propagated; the batch continues)
     */
    public record StatementResult(int textCount, int htmlCount, int errorCount) {
    }

    /**
     * Per-account statement context populated by
     * {@link #buildStatementContext} and consumed by both render
     * methods. Captures everything needed for both the text and HTML
     * variants so each format only re-renders the bytes (Template
     * Method).
     */
    record StatementContext(Account account,
                            Customer customer,
                            List<String> cardNumbers,
                            List<Transaction> transactions,
                            BigDecimal total,
                            String statementId,
                            LocalDate statementDate) {
    }

    // -------------------------------------------------------------------------
    // Public API (per schema exports)
    // -------------------------------------------------------------------------

    /**
     * Generates statements for every account in the {@code accounts}
     * table for the supplied {@code statementDate}. Each successful
     * iteration produces TWO S3 objects per account — one plain-text
     * statement and one HTML statement — by delegating to
     * {@link #generateStatementForAccount(Account, LocalDate)}.
     *
     * <p>The method is {@code @Transactional(readOnly = true)} because
     * the service only reads from RDS (writes go to S3 / OpenSearch
     * via adapters). A read-only Spring transaction hints the JDBC
     * connection as read-only and configures Hibernate's flush mode
     * to {@code MANUAL}, allowing the streaming scan over the
     * {@code accounts} table plus per-account
     * XREF / customer / transaction lookups to run efficiently.</p>
     *
     * <p>Per-account error isolation: a failure generating one
     * account's statement is logged and increments
     * {@link StatementResult#errorCount()}, but does NOT abort the
     * batch (the COBOL source's {@code 9999-ABEND-PROGRAM} is
     * replaced by counted-and-continue per AAP §0.7.1 since the
     * Java target's batch error-handling boundary is the Step
     * Functions {@code Catch} / Spring Batch
     * {@code FaultTolerantStepBuilder}).</p>
     *
     * <p>After processing all accounts, a single batch-summary audit
     * record is emitted via {@link AuditLogService#logAuditEvent} so
     * OpenSearch retains a queryable record of the run lifecycle.</p>
     *
     * @param statementDate the date used for statement labels and S3
     *                      key prefixes; must not be {@code null}
     * @return a {@link StatementResult} summarizing the run
     * @throws IllegalArgumentException if {@code statementDate} is
     *                                  {@code null}
     */
    // COBOL: CBSTM03A:1000-MAINLINE — PERFORM UNTIL END-OF-FILE = 'Y'
    @Transactional(readOnly = true)
    public StatementResult generateStatements(LocalDate statementDate) {
        if (statementDate == null) {
            throw new IllegalArgumentException(
                    "statementDate must not be null");
        }
        String correlationId = statementDate.format(STATEMENT_DATE_FMT);
        LOG.info("CBSTM03A: starting statement generation for {}",
                correlationId);

        int textCount = 0;
        int htmlCount = 0;
        int errorCount = 0;
        int accountsProcessed = 0;

        Iterable<Account> accounts = accountRepository.findAll();
        for (Account acct : accounts) {
            accountsProcessed++;
            if (acct == null || acct.getAcctId() == null) {
                // Null-safety: skip silently to match the COBOL
                // semantic of ignoring blank/unreadable records.
                continue;
            }
            try {
                boolean produced = generateStatementForAccount(acct, statementDate);
                if (produced) {
                    textCount++;
                    htmlCount++;
                }
            } catch (OnSizeErrorException onSize) {
                // ON SIZE ERROR is a hard-stop in COBOL — re-throw so
                // the Step Functions / Spring Batch boundary can
                // capture it explicitly per AAP §0.6.1; the running
                // total cannot be silently truncated to zero on
                // overflow.
                throw onSize;
            } catch (RuntimeException e) {
                errorCount++;
                LOG.error("CBSTM03A: error generating statement for acct={}",
                        acct.getAcctId(), e);
            }
        }

        // COBOL: 9100-/9200-/9300-/9400- TRNXFILE-CLOSE / XREFFILE-CLOSE /
        // CUSTFILE-CLOSE / ACCTFILE-CLOSE.
        // In Java, JPA repositories manage their own connection
        // lifecycle, so this maps to a no-op plus the batch-summary
        // audit emission below.
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accountsProcessed", accountsProcessed);
        summary.put("statementsGenerated", textCount);
        summary.put("textCount", textCount);
        summary.put("htmlCount", htmlCount);
        summary.put("errorCount", errorCount);
        summary.put("batchRunId", correlationId);
        auditLogService.logAuditEvent(
                AUDIT_EVENT_STATEMENT_GENERATED,
                AUDIT_RESOURCE_BATCH_RUN,
                correlationId,
                AUDIT_OPERATOR_BATCH,
                summary,
                correlationId);

        LOG.info("CBSTM03A: complete. text={}, html={}, errors={}",
                textCount, htmlCount, errorCount);

        return new StatementResult(textCount, htmlCount, errorCount);
    }

    /**
     * Generates one account's statement (both text and HTML
     * variants) and uploads the rendered bodies to S3 via
     * {@link S3OutputService#writeReport(String, byte[])}.
     *
     * <p>Returns {@code false} when the cross-reference chain cannot
     * be resolved for the account (no XREF rows OR no Customer for
     * the resolved XREF customer ID) — this matches the COBOL
     * source's {@code 1000-XREFFILE-GET-NEXT}
     * {@code EVALUATE WS-M03B-RC WHEN '10'} branch that signals
     * end-of-file / not-found and continues to the next iteration.
     * Returns {@code true} when a statement was successfully
     * uploaded.</p>
     *
     * @param acct          the account row currently being processed;
     *                      must not be {@code null}
     * @param statementDate the date used for statement labels and S3
     *                      key prefixes; must not be {@code null}
     * @return {@code true} if a statement was generated and uploaded,
     *         {@code false} if the account was skipped due to a
     *         missing XREF or missing Customer record
     * @throws IllegalArgumentException   if either argument is
     *                                    {@code null}
     * @throws OnSizeErrorException        if accumulating the
     *                                    per-transaction amounts
     *                                    overflows the
     *                                    {@code PIC S9(9)V99} ceiling
     * @throws CardDemoException          for unexpected adapter-level
     *                                    failures (S3 / audit
     *                                    transport)
     */
    // COBOL: CBSTM03A:5000-CREATE-STATEMENT
    public boolean generateStatementForAccount(Account acct, LocalDate statementDate) {
        if (acct == null) {
            throw new IllegalArgumentException("acct must not be null");
        }
        if (statementDate == null) {
            throw new IllegalArgumentException("statementDate must not be null");
        }
        Long acctId = acct.getAcctId();
        if (acctId == null) {
            throw new IllegalArgumentException("acct.acctId must not be null");
        }

        // COBOL: 1000-XREFFILE-GET-NEXT (alternate-index xref by
        // acct, ordered ascending by xrefCardNum for deterministic
        // primary-card selection — replaces CXACAIX VSAM AIX scan).
        List<CardCrossReference> xrefs =
                xrefRepository.findByXrefAcctIdOrderByXrefCardNumAsc(acctId);
        if (xrefs == null || xrefs.isEmpty()) {
            // Match the COBOL WHEN '10' (NOTFND) branch — signal
            // skip-this-account without aborting the batch.
            LOG.warn("CBSTM03A: no card cross-references for account {}; "
                    + "skipping statement", acctId);
            return false;
        }

        // COBOL: 2000-CUSTFILE-GET (CUSTOMER by CUST-ID, primary key
        // read). Use the deterministic first XREF row's customer ID.
        Long custId = xrefs.get(0).getXrefCustId();
        Optional<Customer> customerOpt = customerRepository.findById(custId);
        if (customerOpt.isEmpty()) {
            // Match the COBOL WHEN OTHER (error) branch — skip rather
            // than abend so the batch can produce remaining accounts'
            // statements.
            LOG.warn("CBSTM03A: customer {} not found for account {}; "
                    + "skipping statement", custId, acctId);
            return false;
        }
        Customer customer = customerOpt.get();

        // COBOL: 4000-TRNXFILE-GET (PERFORM VARYING CR-JMP — for every
        // card on the account, accumulate transactions and compute
        // WS-TOTAL-AMT).
        StatementContext context =
                buildStatementContext(acct, customer, xrefs, statementDate);

        // COBOL: PERFORM 5100-WRITE-HTML-HEADER + 5200-WRITE-HTML-NMADBS
        // + 6000-WRITE-TRANS — render two formats and emit both to S3.
        byte[] textBytes = renderTextStatement(context)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] htmlBytes = renderHtmlStatement(context)
                .getBytes(StandardCharsets.US_ASCII);

        // Replaces: WRITE FD-STMTFILE-REC FROM ST-LINEn (STMTFILE DD).
        s3OutputService.writeReport(context.statementId(), textBytes);
        // Replaces: WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN (HTMLFILE DD).
        // The .html suffix triggers the S3OutputService Content-Type
        // detection branch (CONTENT_TYPE_HTML_ASCII).
        s3OutputService.writeReport(context.statementId() + ".html", htmlBytes);

        // Per-statement audit so OpenSearch retains an index entry.
        // Replaces: implicit COBOL audit (DISPLAY statements emitted
        // to JES system log).
        String correlationId = statementDate.format(STATEMENT_DATE_FMT);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", acctId);
        payload.put("customerId", custId);
        payload.put("transactionCount", context.transactions().size());
        payload.put("total", context.total());
        payload.put("statementId", context.statementId());
        auditLogService.logAuditEvent(
                AUDIT_EVENT_STATEMENT_GENERATED,
                AUDIT_RESOURCE_STATEMENT,
                context.statementId(),
                AUDIT_OPERATOR_BATCH,
                payload,
                correlationId);

        return true;
    }

    // -------------------------------------------------------------------------
    // Package-private orchestration helpers
    // -------------------------------------------------------------------------

    /**
     * Aggregates every transaction across every card linked to the
     * account, computes the running total (with ON SIZE ERROR
     * guard), and returns a {@link StatementContext} suitable for
     * both render methods.
     *
     * <p>COBOL paragraph {@code 4000-TRNXFILE-GET} (CBSTM03A:L416-L432)
     * iterates the transactions and accumulates
     * {@code WS-TOTAL-AMT}.</p>
     */
    // COBOL: CBSTM03A:4000-TRNXFILE-GET — ADD TRNX-AMT TO WS-TOTAL-AMT
    StatementContext buildStatementContext(Account account,
                                           Customer customer,
                                           List<CardCrossReference> xrefs,
                                           LocalDate statementDate) {
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        List<String> cardNumbers = new ArrayList<>(xrefs.size());
        List<Transaction> allTxs = new ArrayList<>();

        for (CardCrossReference xref : xrefs) {
            String cardNum = xref.getXrefCardNum();
            if (cardNum == null || cardNum.isBlank()) {
                continue;
            }
            cardNumbers.add(cardNum);

            // Replaces: COBOL READ NEXT loop over TRNX-FILE with the
            // WS-SAVE-CARD = TRNX-CARD-NUM short-circuit
            // (CBSTM03A:8500-READTRNX-READ). The repository derived
            // query uses wide-open timestamp boundaries to consume
            // every transaction on the card — the COBOL date-window
            // bounding is the caller's responsibility.
            Page<Transaction> txPage =
                    transactionRepository.findByTranCardNumAndTranProcTsBetween(
                            cardNum,
                            STMT_MIN_TS,
                            STMT_MAX_TS,
                            Pageable.unpaged());
            List<Transaction> txs = txPage.getContent();
            for (Transaction tx : txs) {
                BigDecimal amt = tx.getTranAmt() == null
                        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                        : tx.getTranAmt().setScale(2, RoundingMode.HALF_EVEN);
                BigDecimal next;
                try {
                    // Banker's-rounding accumulation matching the
                    // COBOL ADD/COMPUTE arithmetic semantic.
                    next = total.add(amt).setScale(2, RoundingMode.HALF_EVEN);
                } catch (ArithmeticException ae) {
                    throw new OnSizeErrorException(
                            "ON SIZE ERROR computing WS-TOTAL-AMT for acct="
                                    + account.getAcctId(), ae);
                }
                // Replicate COBOL ON SIZE ERROR guard (PIC S9(9)V99 ceiling).
                guardOnSizeError(next, "WS-TOTAL-AMT", account.getAcctId());
                total = next;
                allTxs.add(tx);
            }
        }

        // Statement identifier matches the test contract:
        // stmt-<11-digit acct>-<14-char yyyyMMddHHmmss>  (total = 31 chars).
        String statementId = String.format("stmt-%011d-%s",
                account.getAcctId(),
                LocalDateTime.now().format(STATEMENT_ID_TS_FMT));

        return new StatementContext(account, customer, cardNumbers,
                allTxs, total, statementId, statementDate);
    }

    /**
     * Renders the plain-text (LRECL=80) statement.
     *
     * <p>COBOL: CBSTM03A.CBL paragraphs {@code 2050-} through
     * {@code 2750-} produced an LRECL=80 statement with header,
     * customer/account block, transaction details, and total/footer
     * block. The Java implementation emits the same structural
     * blocks: START banner, customer block, account block,
     * transaction detail loop, total line, END banner.</p>
     */
    // COBOL: CBSTM03A:5000-CREATE-STATEMENT (text variant — ST-LINE0..ST-LINE15)
    String renderTextStatement(StatementContext context) {
        StringBuilder sb = new StringBuilder();

        // ST-LINE0 — START OF STATEMENT banner.
        // COBOL: 31 '*' + 'START OF STATEMENT' (18) + 31 '*' = 80 chars
        appendLine(sb,
                repeat('*', 31) + "START OF STATEMENT" + repeat('*', 31),
                TEXT_LINE_WIDTH);

        // Customer name block (ST-LINE1 — paragraph 5000-CREATE-STATEMENT
        // STRING CUST-FIRST-NAME ' ' CUST-MIDDLE-NAME ' ' CUST-LAST-NAME).
        appendLine(sb, buildCustomerName(context.customer()), TEXT_LINE_WIDTH);
        // ST-LINE2 — Address line 1 (50 chars + 30 spaces = 80).
        appendLine(sb, safe(context.customer().getCustAddrLine1()),
                TEXT_LINE_WIDTH);
        // ST-LINE3 — Address line 2 (50 chars + 30 spaces = 80).
        appendLine(sb, safe(context.customer().getCustAddrLine2()),
                TEXT_LINE_WIDTH);
        // ST-LINE4 — Address line 3 / city-state-zip (80 chars).
        appendLine(sb, buildAddressLine3(context.customer()),
                TEXT_LINE_WIDTH);
        // ST-LINE5 — divider (80 dashes).
        appendLine(sb, repeat('-', TEXT_LINE_WIDTH), TEXT_LINE_WIDTH);

        // ST-LINE6 — 'Basic Details' header (centered).
        appendLine(sb,
                repeat(' ', 33) + "Basic Details" + repeat(' ', 34),
                TEXT_LINE_WIDTH);

        // Customer Id (informational, for traceability — preserves
        // the link from the audit trail back to the statement
        // recipient).
        appendLine(sb,
                "Customer Id        : " + context.customer().getCustId(),
                TEXT_LINE_WIDTH);

        // ST-LINE7 — Account ID  : <20-char id>.
        appendLine(sb,
                "Account ID         : " + padId(context.account().getAcctId()),
                TEXT_LINE_WIDTH);

        // ST-LINE8 — Current Balance : <PIC 9(9).99->.
        appendLine(sb,
                "Current Balance    : "
                        + formatAmount(context.account().getAcctCurrBal()),
                TEXT_LINE_WIDTH);

        // ST-LINE9 — FICO Score : <score>.
        appendLine(sb,
                "FICO Score         : "
                        + safeFico(context.customer().getCustFicoCreditScore()),
                TEXT_LINE_WIDTH);

        // ST-LINE10 — divider.
        appendLine(sb, repeat('-', TEXT_LINE_WIDTH), TEXT_LINE_WIDTH);

        // ST-LINE11 — 'TRANSACTION SUMMARY' header (centered).
        appendLine(sb,
                repeat(' ', 30) + "TRANSACTION SUMMARY " + repeat(' ', 30),
                TEXT_LINE_WIDTH);

        // ST-LINE12 — divider.
        appendLine(sb, repeat('-', TEXT_LINE_WIDTH), TEXT_LINE_WIDTH);

        // ST-LINE13 — column headers (Tran ID | Tran Details | Amount).
        appendLine(sb,
                "TRANSACTIONS:     "
                        + padRight("Tran Details", 47)
                        + "  Tran Amount",
                TEXT_LINE_WIDTH);

        // ST-LINE12 — divider (repeated).
        appendLine(sb, repeat('-', TEXT_LINE_WIDTH), TEXT_LINE_WIDTH);

        // Transaction detail loop (ST-LINE14 — 6000-WRITE-TRANS).
        for (Transaction tx : context.transactions()) {
            appendLine(sb, buildTransactionLine(tx), TEXT_LINE_WIDTH);
        }

        // ST-LINE12 — closing divider.
        appendLine(sb, repeat('-', TEXT_LINE_WIDTH), TEXT_LINE_WIDTH);

        // ST-LINE14A — Total EXP line.
        // COBOL: 'Total EXP:' (10) + 56 spaces + '$' (1) + total Z(9).99- (13) = 80.
        appendLine(sb,
                "TOTAL:    " + repeat(' ', 56) + "$" + formatAmount(context.total()),
                TEXT_LINE_WIDTH);

        // ST-LINE15 — END OF STATEMENT banner.
        // COBOL: 32 '*' + 'END OF STATEMENT' (16) + 32 '*' = 80 chars.
        appendLine(sb,
                repeat('*', 32) + "END OF STATEMENT" + repeat('*', 32),
                TEXT_LINE_WIDTH);

        return sb.toString();
    }

    /**
     * Renders the HTML (LRECL=100, content-driven) statement.
     *
     * <p>COBOL: CBSTM03A.CBL paragraphs {@code 5100-WRITE-HTML-HEADER}
     * + {@code 5200-WRITE-HTML-NMADBS} + {@code 6000-WRITE-TRANS}
     * (HTML branch). The HTML constants
     * ({@code HTML-L01}..{@code HTML-L80}, {@code HTML-L10},
     * {@code HTML-L15}, {@code HTML-L22-35}, {@code HTML-L30-42},
     * {@code HTML-L47}, {@code HTML-L50}, {@code HTML-L53},
     * {@code HTML-L58}, {@code HTML-L61}, {@code HTML-L64}) are
     * preserved verbatim — colours, table widths, and header
     * literals are byte-identical to the COBOL source.</p>
     */
    // COBOL: CBSTM03A:5100-WRITE-HTML-HEADER + 5200-WRITE-HTML-NMADBS
    String renderHtmlStatement(StatementContext context) {
        StringBuilder sb = new StringBuilder();

        // HTML-L01 .. HTML-L08 — header fixed lines from CBSTM03A:L150-L158.
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<title>HTML Table Layout</title>\n");
        sb.append("</head>\n");
        sb.append("<body style=\"margin:0px;\">\n");
        // CBSTM03A:L157-L158 — note TWO spaces between '<table' and 'align'.
        sb.append("<table  align=\"center\" frame=\"box\" "
                + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">\n");

        // Bank header row (HTML-L10 — bgcolor #1d1d96b3).
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#1d1d96b3;\">\n");
        // HTML-L11 — Statement for Account Number heading.
        sb.append("<h3>Statement for Account Number: ")
                .append(padId(context.account().getAcctId()))
                .append("</h3>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Second header row (HTML-L15 — bgcolor #FFAF33).
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#FFAF33;\">\n");
        // HTML-L16..L18 — Bank of XYZ address (verbatim COBOL constants).
        sb.append("<p style=\"font-size:16px\">Bank of XYZ</p>\n");
        sb.append("<p>410 Terry Ave N</p>\n");
        sb.append("<p>Seattle WA 99999</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Customer name+address block (HTML-L22-35 — bgcolor #f2f2f2).
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#f2f2f2;\">\n");
        // HTML-L23 — Name.
        sb.append("<p style=\"font-size:16px\">")
                .append(escapeHtml(buildCustomerName(context.customer()).trim()))
                .append("</p>\n");
        // HTML-ADDR-LN — address lines 1, 2, 3 (CBSTM03A:L569-L592).
        sb.append("<p>").append(escapeHtml(
                safe(context.customer().getCustAddrLine1()).trim())).append("</p>\n");
        sb.append("<p>").append(escapeHtml(
                safe(context.customer().getCustAddrLine2()).trim())).append("</p>\n");
        sb.append("<p>").append(escapeHtml(
                buildAddressLine3(context.customer()).trim())).append("</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Basic Details header (HTML-L30-42 — bgcolor #33FFD1, centered).
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#33FFD1; text-align:center;\">\n");
        // HTML-L31 — Basic Details title.
        sb.append("<p style=\"font-size:16px\">Basic Details</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Basic details rows (HTML-L22-35 — bgcolor #f2f2f2).
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#f2f2f2;\">\n");
        // HTML-BSIC-LN — Account ID, Current Balance, FICO Score.
        sb.append("<p>Account ID         : ")
                .append(escapeHtml(padId(context.account().getAcctId())))
                .append("</p>\n");
        sb.append("<p>Current Balance    : ")
                .append(escapeHtml(
                        formatAmount(context.account().getAcctCurrBal())))
                .append("</p>\n");
        sb.append("<p>FICO Score         : ")
                .append(escapeHtml(
                        safeFico(context.customer().getCustFicoCreditScore())))
                .append("</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Transaction Summary header (HTML-L30-42 + HTML-L43).
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#33FFD1; text-align:center;\">\n");
        sb.append("<p style=\"font-size:16px\">Transaction Summary</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Column header row — Tran ID / Tran Details / Amount.
        // HTML-L47/L48 — Tran ID column (25%, bgcolor #33FF5E, left).
        // HTML-L50/L51 — Tran Details column (55%, bgcolor #33FF5E, left).
        // HTML-L53/L54 — Amount column (20%, bgcolor #33FF5E, right).
        sb.append("<tr>\n");
        sb.append("<td style=\"width:25%; padding:0px 5px; "
                + "background-color:#33FF5E; text-align:left;\">\n");
        sb.append("<p style=\"font-size:16px\">Tran ID</p>\n");
        sb.append("</td>\n");
        sb.append("<td style=\"width:55%; padding:0px 5px; "
                + "background-color:#33FF5E; text-align:left;\">\n");
        sb.append("<p style=\"font-size:16px\">Tran Details</p>\n");
        sb.append("</td>\n");
        sb.append("<td style=\"width:20%; padding:0px 5px; "
                + "background-color:#33FF5E; text-align:right;\">\n");
        sb.append("<p style=\"font-size:16px\">Amount</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Transaction detail rows (HTML-L58/L61/L64 — bgcolor #f2f2f2).
        for (Transaction tx : context.transactions()) {
            sb.append("<tr>\n");
            // Tran ID cell (HTML-L58 — 25%, bgcolor #f2f2f2, left).
            sb.append("<td style=\"width:25%; padding:0px 5px; "
                    + "background-color:#f2f2f2; text-align:left;\">\n");
            sb.append("<p>").append(escapeHtml(safe(tx.getTranId())))
                    .append("</p>\n");
            sb.append("</td>\n");
            // Tran Details cell (HTML-L61 — 55%, bgcolor #f2f2f2, left).
            // PCI-DSS: mask PAN to last-4 for HTML output.
            sb.append("<td style=\"width:55%; padding:0px 5px; "
                    + "background-color:#f2f2f2; text-align:left;\">\n");
            sb.append("<p>")
                    .append(escapeHtml(maskCard(tx.getTranCardNum())))
                    .append(" ")
                    .append(escapeHtml(safe(tx.getTranDesc())))
                    .append("</p>\n");
            sb.append("</td>\n");
            // Tran Amount cell (HTML-L64 — 20%, bgcolor #f2f2f2, right).
            sb.append("<td style=\"width:20%; padding:0px 5px; "
                    + "background-color:#f2f2f2; text-align:right;\">\n");
            sb.append("<p>$").append(escapeHtml(formatAmount(tx.getTranAmt())))
                    .append("</p>\n");
            sb.append("</td>\n");
            sb.append("</tr>\n");
        }

        // Total row (CBSTM03A:L435-L436 — ST-LINE12 + ST-LINE14A).
        sb.append("<tr>\n");
        sb.append("<td colspan=\"3\" style=\"padding:0px 5px;"
                + "background-color:#1d1d96b3;\">\n");
        // HTML-L75 — End of Statement heading.
        sb.append("<h3>End of Statement</h3>\n");
        sb.append("<p style=\"text-align:right;\">TOTAL: $")
                .append(escapeHtml(formatAmount(context.total())))
                .append("</p>\n");
        sb.append("</td>\n");
        sb.append("</tr>\n");

        // Closing tags (HTML-L78/L79/L80).
        sb.append("</table>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private rendering helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the customer's full name as space-delimited first +
     * middle + last (CBSTM03A:L462-L469 STRING statement).
     */
    private static String buildCustomerName(Customer customer) {
        StringBuilder sb = new StringBuilder();
        appendIfNonBlank(sb, customer.getCustFirstName());
        appendIfNonBlank(sb, customer.getCustMiddleName());
        appendIfNonBlank(sb, customer.getCustLastName());
        return sb.toString();
    }

    /**
     * Builds the address line 3 = addr-line-3 + state + country + zip
     * space-delimited (CBSTM03A:L472-L481 STRING statement).
     */
    private static String buildAddressLine3(Customer customer) {
        StringBuilder sb = new StringBuilder();
        appendIfNonBlank(sb, customer.getCustAddrLine3());
        appendIfNonBlank(sb, customer.getCustAddrStateCd());
        appendIfNonBlank(sb, customer.getCustAddrCountryCd());
        appendIfNonBlank(sb, customer.getCustAddrZip());
        return sb.toString();
    }

    /**
     * Appends a token to the buffer, separating from any preceding
     * token by a single space. Trims to mirror the COBOL
     * {@code DELIMITED BY ' '} behavior.
     */
    private static void appendIfNonBlank(StringBuilder sb, String token) {
        if (token == null) {
            return;
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(trimmed);
    }

    /**
     * Builds a transaction-detail line in CBSTM03A ST-LINE14 layout:
     * {@code TRANID(16) SPACE(1) DESC(49) '$' AMOUNT(13)} = 80 chars.
     */
    private String buildTransactionLine(Transaction tx) {
        String tranId = padRight(safe(tx.getTranId()), 16);
        String space = " ";
        // The "description" cell visualises the masked PAN plus the
        // narrative; PCI-DSS Req 3.3 requires PAN masking.
        String descBase = (maskCard(tx.getTranCardNum()) + " "
                + safe(tx.getTranDesc())).trim();
        String desc = padRight(descBase, 49);
        String dollar = "$";
        String amount = formatAmount(tx.getTranAmt());
        // Right-align the 12-char numeric edit field within 13 chars,
        // matching COBOL Z(9).99-.
        amount = padLeft(amount, 13);
        return tranId + space + desc + dollar + amount;
    }

    // -------------------------------------------------------------------------
    // Generic formatting helpers
    // -------------------------------------------------------------------------

    /**
     * Appends a single line padded/truncated to exactly {@code width}
     * characters, terminated by LF. Used for the LRECL=80 text
     * statement.
     */
    private static void appendLine(StringBuilder sb, String line, int width) {
        String safe = line == null ? "" : line;
        if (safe.length() >= width) {
            sb.append(safe, 0, width);
        } else {
            sb.append(safe);
            sb.append(" ".repeat(width - safe.length()));
        }
        sb.append('\n');
    }

    /** Returns a string of {@code n} copies of {@code c}. */
    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(Math.max(0, n));
    }

    /** Null-safe trim returning empty string if input is null. */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Right-pads (left-aligned) a string to the supplied width;
     * truncates if longer.
     */
    private static String padRight(String value, int width) {
        String v = safe(value);
        if (v.length() >= width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }

    /**
     * Left-pads (right-aligned) a string to the supplied width;
     * truncates from the left if longer (preserving the rightmost
     * digits, appropriate for numeric values).
     */
    private static String padLeft(String value, int width) {
        String v = safe(value);
        if (v.length() >= width) {
            return v.substring(v.length() - width);
        }
        return " ".repeat(width - v.length()) + v;
    }

    /**
     * Renders the 11-digit account ID as a zero-padded 11-digit
     * string (matching COBOL {@code PIC 9(11)} display semantic
     * adopted for the visible ID).
     */
    private static String padId(Long acctId) {
        if (acctId == null) {
            return "00000000000";
        }
        return String.format("%011d", acctId);
    }

    /**
     * Renders a monetary {@link BigDecimal} as a 12-character
     * {@code 9,999,999,999.99} string with HALF_EVEN rounding.
     */
    static String formatAmount(BigDecimal value) {
        BigDecimal v = value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                : value.setScale(2, RoundingMode.HALF_EVEN);
        // toPlainString() avoids scientific notation; preserves
        // exact COBOL decimal-arithmetic semantics.
        return v.toPlainString();
    }

    /** Renders a nullable FICO score as a stringified integer. */
    private static String safeFico(Integer fico) {
        return fico == null ? "" : String.valueOf(fico);
    }

    /**
     * Masks a card number to last-4 ({@code ****-****-****-NNNN}).
     * PCI-DSS Req 3.3: the full PAN MUST NOT appear in any
     * statement, log, or audit artefact.
     */
    static String maskCard(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****-****-****-****";
        }
        return "****-****-****-" + pan.substring(pan.length() - 4);
    }

    /**
     * HTML-escapes the supplied string. The five canonical XML/HTML
     * entity escapes are applied so that user-supplied descriptions
     * (transaction narratives, merchant names, addresses) cannot
     * inject markup or scripts into the rendered HTML statement.
     */
    static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Guards an arithmetic operation against the COBOL
     * {@code PIC S9(9)V99} ceiling. Throws
     * {@link OnSizeErrorException} if the computed value would
     * exceed {@link #MAX_STATEMENT_TOTAL} per AAP §0.6.1.
     */
    private static void guardOnSizeError(BigDecimal value,
                                         String fieldName,
                                         Long acctId) {
        if (value == null) {
            return;
        }
        if (value.abs().compareTo(MAX_STATEMENT_TOTAL) > 0) {
            throw new OnSizeErrorException(
                    "ON SIZE ERROR computing " + fieldName
                            + " for acct=" + acctId
                            + " (computed " + value
                            + " exceeds PIC S9(9)V99 ceiling 999999999.99)");
        }
    }
}
