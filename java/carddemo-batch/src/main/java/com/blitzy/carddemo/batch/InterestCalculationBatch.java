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
package com.blitzy.carddemo.batch;

import com.blitzy.carddemo.application.account.CbAct04C;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.DiscountGroupRepository;
import com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain-Java batch driver for the {@code INTCALC} JCL flow
 * ({@code app/jcl/INTCALC.jcl}). This driver is the {@code carddemo-batch}
 * orchestration-layer wrapper around the {@link CbAct04C} use case (translated
 * from the COBOL program {@code app/cbl/CBACT04C.cbl}, "Interest calculator").
 *
 * <h2>Source lineage</h2>
 *
 * <h3>JCL &mdash; {@code app/jcl/INTCALC.jcl}</h3>
 * The originating JCL job runs a single step {@code STEP15} that invokes
 * {@code PGM=CBACT04C} with {@code PARM='2022071800'} and the following DD
 * allocations (one repository port per DD, wired by the composition root in
 * {@code carddemo-app}):
 * <ul>
 *   <li><b>TCATBALF</b> &mdash; {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS},
 *       {@code DISP=SHR}; INPUT sequential read of 50-byte
 *       transaction-category balance records (copybook
 *       {@code app/cpy/CVTRA01Y.cpy}, 17-byte composite key = ACCT-ID
 *       PIC 9(11) + TYPE-CD PIC X(02) + CAT-CD PIC 9(04)). Drives the
 *       primary loop in {@link CbAct04C#run(LocalDate)} via
 *       {@link TransactionCategoryBalanceRepository}. Lines 27-28 of
 *       {@code app/jcl/INTCALC.jcl}.</li>
 *   <li><b>XREFFILE</b> &mdash; {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS},
 *       {@code DISP=SHR}; INPUT random read by card number (primary key,
 *       PIC X(16)) of the 50-byte card cross-reference record (copybook
 *       {@code app/cpy/CVACT03Y.cpy}). Lines 29-30.</li>
 *   <li><b>XREFFIL1</b> &mdash; {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH},
 *       {@code DISP=SHR}; INPUT random read by account id via the
 *       alternate index of the same CARDXREF KSDS. The
 *       {@code 1110-GET-XREF-DATA} paragraph at
 *       {@code app/cbl/CBACT04C.cbl:L393-L413} uses this path to translate
 *       an ACCT-ID into a CARD-NUM for the emitted interest transaction's
 *       {@code TRAN-CARD-NUM} field. Both DDs are exposed through the
 *       single {@link CardXrefRepository} port. Lines 31-32.</li>
 *   <li><b>ACCTFILE</b> &mdash; {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS},
 *       {@code DISP=SHR}; INPUT/OUTPUT (I-O) random read + REWRITE of the
 *       300-byte account master record (copybook
 *       {@code app/cpy/CVACT01Y.cpy}). Read in the
 *       {@code 1100-GET-ACCT-DATA} paragraph
 *       ({@code app/cbl/CBACT04C.cbl:L372-L391}) and rewritten in
 *       {@code 1050-UPDATE-ACCOUNT}
 *       ({@code app/cbl/CBACT04C.cbl:L350-L370}) after the accumulated
 *       monthly interest is added to {@code ACCT-CURR-BAL}. Lines 33-34.</li>
 *   <li><b>DISCGRP</b> &mdash; {@code AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS},
 *       {@code DISP=SHR}; INPUT random read of the 50-byte discount-group
 *       interest-rate record (copybook {@code app/cpy/CVTRA02Y.cpy},
 *       16-byte composite key = ACCT-GROUP-ID PIC X(10) + TRAN-TYPE-CD
 *       PIC X(02) + TRAN-CAT-CD PIC 9(04)). The {@code 1200-GET-INTEREST-RATE}
 *       paragraph at {@code app/cbl/CBACT04C.cbl:L415-L460} reads by the
 *       per-account group ID; on FILE STATUS {@code '23'} (NOT FOUND), the
 *       {@code 1200-A-GET-DEFAULT-INT-RATE} fallback at
 *       {@code app/cbl/CBACT04C.cbl:L443} retries with the literal
 *       {@code "DEFAULT"} as the group ID (the
 *       {@link CbAct04C#DEFAULT_GROUP_ID} constant). Lines 35-36.</li>
 *   <li><b>TRANSACT</b> &mdash; {@code AWS.M2.CARDDEMO.SYSTRAN(+1)},
 *       {@code DISP=(NEW,CATLG,DELETE)}, {@code RECFM=F},
 *       {@code LRECL=350}; OUTPUT sequential write of new GDG generation
 *       holding the 350-byte interest transactions (copybook
 *       {@code app/cpy/CVTRA05Y.cpy}). The {@code 1300-B-WRITE-TX}
 *       paragraph at {@code app/cbl/CBACT04C.cbl:L473} emits one record
 *       per (account, type, category) tuple carrying the computed
 *       interest amount. Lines 37-41.</li>
 * </ul>
 *
 * <h3>JCL {@code PARM='2022071800'} translation</h3>
 * The JCL passes a 10-character PARM string of the form
 * {@code yyyyMMddHH} (year-month-day-hour). The trailing two characters
 * ({@code "00"} in the originating JCL) denote the hour; CBACT04C consumes
 * only the date portion. The {@link #parseParmDate(String)} static helper
 * decodes the PARM string into a {@link LocalDate} (dropping the hour).
 * Callers may also pass the date directly to
 * {@link #execute(LocalDate)} or rely on the bound
 * {@link BatchRunContext#processingDate()} via the no-arg
 * {@link #execute()} overload.
 *
 * <h3>COBOL &mdash; {@code app/cbl/CBACT04C.cbl}</h3>
 * The translated use case ({@link CbAct04C}) implements the full interest
 * calculation engine and exposes
 * <ul>
 *   <li>{@link CbAct04C#run(LocalDate)} &mdash; opens the five datasets
 *       (paragraphs {@code 0000-TCATBALF-OPEN} through
 *       {@code 0400-TRANFILE-OPEN}), drives the main TCATBAL loop with
 *       account-boundary detection, per-category discount-group lookup,
 *       interest computation, transaction emission, and account-balance
 *       update, and closes them (paragraphs {@code 9000-TCATBALF-CLOSE}
 *       through {@code 9400-TRANFILE-CLOSE}). Returns the COBOL
 *       {@code RETURN-CODE}: {@code 0} for a clean run, {@code 12} for an
 *       I/O failure equivalent to the {@code 9999-ABEND-PROGRAM}
 *       paragraph.</li>
 * </ul>
 *
 * <h3>Interest formula (AAP &sect;0.6.1)</h3>
 * The {@code 1300-COMPUTE-INTEREST} paragraph at
 * {@code app/cbl/CBACT04C.cbl:L462-L470} computes
 * <pre>{@code
 * COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 * }</pre>
 * The {@code COMPUTE} statement carries NO {@code ROUNDED} clause, so the
 * COBOL default (truncate, not banker's round) applies. The Java
 * translation in {@link CbAct04C} uses {@link java.math.RoundingMode#DOWN}
 * (truncation) via the {@code com.blitzy.carddemo.domain.util.Decimals}
 * facade per AAP &sect;0.6.1. <strong>All</strong> monetary arithmetic for
 * this batch routes through {@code Decimals} &mdash; this driver itself
 * performs zero arithmetic and merely orchestrates the use-case call.
 *
 * <h2>Plain-Java batch driver (AAP &sect;0.6.12 architectural override)</h2>
 * <p>This driver is intentionally <strong>not</strong> a Spring Batch
 * {@code Step} or {@code Tasklet}. Per AAP &sect;0.6.12 the previously
 * documented Spring Boot / Spring Batch architecture in
 * {@code docs/technical-specifications.md} is REPLACED by plain Java
 * drivers with constructor injection and {@link ScopedValue} propagation.
 * This class therefore has no framework annotations, no
 * {@code @Service}/{@code @Component}, no
 * {@code @StepScope}/{@code @JobScope}, no
 * {@code ItemReader}/{@code ItemWriter}, and no XML/JavaConfig wiring.
 *
 * <h2>Virtual-thread fan-out: delegated to the use case, not performed here</h2>
 * <p>Per AAP &sect;0.6.6 and the file's agent prompt, "virtual-thread
 * fan-out is PERMITTED for independent per-account interest computation
 * since per-account interest is independent of other accounts &mdash; but
 * output writes to SYSTRAN must preserve sort order". That permission
 * applies <strong>inside</strong> the {@link CbAct04C} use case if/when it
 * implements fan-out internally. The <strong>batch driver</strong> here
 * does NOT itself fan out &mdash; it makes a single sequential call to
 * {@link CbAct04C#run(LocalDate)} and lets the use case decide whether to
 * parallelise. This separation keeps the driver responsibility limited to
 * JCL PARM parsing, timing/logging, and {@code ScopedValue} context
 * retrieval per AAP &sect;0.6.6.
 *
 * <p>The constraint that survives whatever the use case chooses: any
 * fan-out MUST collect results and write them in the original sort order
 * (preserves SYSTRAN ordering per AAP &sect;0.1.3 byte-for-byte fidelity
 * mandate). This driver does not duplicate that enforcement; it is the
 * use case's responsibility.
 *
 * <h2>{@link BatchRunContext} consumption (AAP &sect;0.6.6, JEP 506 Final)</h2>
 * <p>{@link #execute()} (the no-arg overload) reads the active
 * {@code BatchRunContext} from {@link BatchRunContext#BATCH_CTX} (the
 * JEP 506 {@link ScopedValue} finalized in Java 25) and delegates to the
 * explicit {@link #execute(LocalDate)} overload with
 * {@code ctx.processingDate()}. The caller &mdash; typically the
 * composition root {@code carddemo-app/InterestCalculationApp} &mdash; MUST
 * establish the binding via
 * {@code ScopedValue.where(BatchRunContext.BATCH_CTX, ctx).run(() ->
 * interestCalculationBatch.execute())} or the convenience helper
 * {@code BatchRunContext.runWith(ctx, () -> ...)}. If the binding is not
 * present at the start of {@link #execute()}, the method throws
 * {@link IllegalStateException} with a diagnostic message rather than
 * proceeding with a missing run context.
 *
 * <p>Per AAP &sect;0.6.6 and &sect;0.7.4, {@link ThreadLocal} is
 * <strong>forbidden in new CardDemo Java code</strong>; this driver
 * reads its run context exclusively via {@code ScopedValue}.
 *
 * <h2>Exit code semantics (AAP &sect;0.7.1)</h2>
 * <p>The COBOL {@code RETURN-CODE} convention (preserved verbatim per
 * AAP &sect;0.7.1) is:
 * <ul>
 *   <li><b>0</b> &mdash; success; interest calculation completed without
 *       I/O failure ({@link CbAct04C#APPL_AOK}).</li>
 *   <li><b>12</b> &mdash; hard failure equivalent to the COBOL
 *       {@code 9999-ABEND-PROGRAM} paragraph
 *       ({@link CbAct04C#APPL_ERROR}); a non-recoverable I/O error
 *       occurred during OPEN, READ, REWRITE, WRITE, or CLOSE.</li>
 * </ul>
 *
 * <h2>Hexagonal ports (AAP &sect;0.3.6)</h2>
 * <p>Six dependencies are accepted by the constructor; all are
 * <strong>ports</strong> (domain-defined interfaces from
 * {@code carddemo-domain.port}) or the use-case class itself, never
 * concrete adapters. The composition root in {@code carddemo-app} selects
 * either the file-backed implementation
 * ({@code carddemo-adapter-file}) or an optional JDBC implementation
 * ({@code carddemo-adapter-db}) at startup and passes them in. The use
 * case ({@link CbAct04C}) is itself constructor-injected with the same
 * five repository ports; this driver accepts them as separate parameters
 * in addition to the use-case instance so the composition root sees an
 * explicit list of every port that INTCALC exercises (matches the
 * {@code INTCALC.jcl} DD inventory one-for-one).
 *
 * <h2>Thread safety</h2>
 * <p>Instances of this class are immutable after construction (all
 * fields are {@code final}) but the underlying repositories and use case
 * may not be re-entrant. A single {@code InterestCalculationBatch}
 * instance MUST NOT be invoked concurrently from multiple threads; one
 * {@link #execute()} or {@link #execute(LocalDate)} call per JCL job is
 * the expected usage pattern.
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP &sect;0.2.1 &mdash; supplementary files mandate.</li>
 *   <li>AAP &sect;0.3.1 &mdash; {@code carddemo-batch} module layout.</li>
 *   <li>AAP &sect;0.3.6 &mdash; hexagonal architecture diagram (ports,
 *       not adapters).</li>
 *   <li>AAP &sect;0.4.1 &mdash; {@code InterestCalculationApp} listing
 *       (this driver is the {@code carddemo-batch} sibling).</li>
 *   <li>AAP &sect;0.6.1 &mdash; decimal arithmetic fidelity (formula
 *       routes through {@code Decimals} in the use case).</li>
 *   <li>AAP &sect;0.6.6 &mdash; virtual-thread fan-out rule and
 *       {@code ScopedValue} pattern.</li>
 *   <li>AAP &sect;0.6.12 &mdash; Spring Batch architectural override.</li>
 *   <li>AAP &sect;0.7.1 &mdash; refactor discipline (preserve exit codes
 *       verbatim).</li>
 *   <li>AAP &sect;0.7.3 &mdash; mandated Java 25 features
 *       ({@link ScopedValue}, {@code java.time}, records, sealed types).</li>
 *   <li>AAP &sect;0.7.4 &mdash; forbidden features (no
 *       {@link ThreadLocal}, no preview JEPs, no Spring, no Lombok, no
 *       {@code double}/{@code float}, no {@link java.util.Date}, no
 *       {@link java.io.File}, no {@code System.out.println}).</li>
 * </ul>
 *
 * @see CbAct04C
 * @see BatchRunContext
 * @see TransactionCategoryBalanceRepository
 * @see AccountRepository
 * @see CardXrefRepository
 * @see DiscountGroupRepository
 * @see TransactionRepository
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBACT04C",
        sourcePath = "app/cbl/CBACT04C.cbl",
        translationDate = "2026-05-26",
        notes = "INTCALC JCL flow (app/jcl/INTCALC.jcl STEP15 EXEC PGM=CBACT04C,"
                + "PARM='2022071800'). Thin plain-Java batch driver per AAP "
                + "§0.6.12 — wraps a single sequential call to "
                + "CbAct04C.run(LocalDate) with BatchRunContext binding checks, "
                + "JCL PARM parsing (yyyyMMddHH 10-char format), timing, and "
                + "structured logging. All monetary arithmetic for interest "
                + "computation routes through Decimals inside CbAct04C per "
                + "AAP §0.6.1 — this driver performs zero arithmetic. "
                + "Virtual-thread fan-out is permitted for per-account work "
                + "INSIDE CbAct04C (independent per-account interest); not "
                + "performed at this driver layer."
)
public final class InterestCalculationBatch {

    // -----------------------------------------------------------------------
    // Static fields
    // -----------------------------------------------------------------------

    /**
     * SLF4J logger for batch lifecycle events. Used to emit:
     * <ul>
     *   <li>INFO at the start of {@link #execute(LocalDate)} &mdash;
     *       processing date.</li>
     *   <li>INFO at the end of {@link #execute(LocalDate)} &mdash; exit
     *       code and elapsed wall-clock duration.</li>
     * </ul>
     * The concrete logger implementation (logback-classic per AAP
     * &sect;0.5.1) is supplied by the composition root in
     * {@code carddemo-app}; this module depends only on the SLF4J API
     * per the {@code carddemo-batch} POM. {@code System.out} and
     * {@code java.util.logging.Logger} are explicitly forbidden per AAP
     * &sect;0.7.4 forbidden-patterns list.
     */
    private static final Logger LOG = LoggerFactory.getLogger(InterestCalculationBatch.class);

    /**
     * The {@link DateTimeFormatter} for the JCL {@code PARM='2022071800'}
     * 10-character string of the form {@code yyyyMMddHH} (year + month +
     * day + hour-of-day, all zero-padded). Produced by
     * {@link #parseParmDate(String)} and consumed by
     * {@link LocalDate#parse(CharSequence, DateTimeFormatter)}.
     *
     * <p>The trailing {@code HH} (hour) field is accepted by the
     * formatter but ignored by {@code LocalDate.parse} which extracts
     * only the date components &mdash; this matches the COBOL behavior
     * in CBACT04C where {@code PARM-DATE} is treated as a date-granular
     * processing date and the hour portion (typically {@code "00"}) is
     * unused.
     *
     * <p>The formatter is created with {@link DateTimeFormatter#ofPattern(String)}
     * which produces a formatter with the system default
     * {@link java.time.format.ResolverStyle#SMART SMART} resolver style;
     * this is the same resolver behavior as the COBOL {@code MOVE
     * PARM-DATE} statement (the COBOL program does no validation of the
     * date portion; an invalid date causes downstream FILE STATUS
     * errors, not a PARM parse failure). The two-stage validation in
     * {@link #parseParmDate(String)} (explicit length check before
     * parsing) provides a clear error surface for malformed JCL PARMs
     * before the {@code DateTimeFormatter} sees them.
     */
    private static final DateTimeFormatter PARM_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    // -----------------------------------------------------------------------
    // Instance fields — constructor-injected; all final and never null
    // -----------------------------------------------------------------------

    /**
     * Port for the {@code TCATBALF} VSAM KSDS (copybook
     * {@code app/cpy/CVTRA01Y.cpy}, 50-byte records, 17-byte composite
     * key = ACCT-ID PIC 9(11) + TYPE-CD PIC X(02) + CAT-CD PIC 9(04)).
     * Drives the primary sequential read loop inside
     * {@link CbAct04C#run(LocalDate)} (the {@code 1000-TCATBALF-GET-NEXT}
     * paragraph at {@code app/cbl/CBACT04C.cbl:L325-L348}). Wired by the
     * INTCALC.jcl DD statement {@code //TCATBALF DD
     * DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS} at
     * {@code app/jcl/INTCALC.jcl:L27-L28}.
     *
     * <p>Held by this driver purely so the composition root has an
     * explicit DD-by-DD inventory in front of it; the use case
     * ({@link CbAct04C}) is itself constructor-injected with the same
     * port and is the actual consumer.
     */
    private final TransactionCategoryBalanceRepository tranCatBalRepository;

    /**
     * Port for the {@code ACCTFILE} VSAM KSDS (copybook
     * {@code app/cpy/CVACT01Y.cpy}, 300-byte records, 11-digit ACCT-ID
     * key). Opened in I-O mode by CBACT04C:
     * {@code 1100-GET-ACCT-DATA} ({@code app/cbl/CBACT04C.cbl:L372-L391})
     * reads, then {@code 1050-UPDATE-ACCOUNT}
     * ({@code app/cbl/CBACT04C.cbl:L350-L370}) REWRITEs the record after
     * the accumulated monthly interest has been posted to
     * {@code ACCT-CURR-BAL}. Wired by the INTCALC.jcl DD statement
     * {@code //ACCTFILE DD DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS} at
     * {@code app/jcl/INTCALC.jcl:L33-L34}.
     */
    private final AccountRepository accountRepository;

    /**
     * Port for the {@code CARDXREF} VSAM KSDS + alternate index
     * (copybook {@code app/cpy/CVACT03Y.cpy}). The
     * {@code 1110-GET-XREF-DATA} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L393-L413} uses this port to translate
     * an ACCT-ID into a CARD-NUM for the
     * {@code TRAN-CARD-NUM} field of the emitted interest transaction.
     * Wired by the INTCALC.jcl DD statements {@code //XREFFILE} (primary
     * KSDS) at {@code app/jcl/INTCALC.jcl:L29-L30} and
     * {@code //XREFFIL1} (alternate-index PATH) at
     * {@code app/jcl/INTCALC.jcl:L31-L32}; both DDs are surfaced through
     * this single port.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Port for the {@code DISCGRP} VSAM KSDS (copybook
     * {@code app/cpy/CVTRA02Y.cpy}, 50-byte records, 16-byte composite
     * key = ACCT-GROUP-ID PIC X(10) + TRAN-TYPE-CD PIC X(02) +
     * TRAN-CAT-CD PIC 9(04)). Powers the per-(group, type, category)
     * interest-rate lookup with the {@code "DEFAULT"} fallback semantics
     * implemented in the {@code 1200-GET-INTEREST-RATE} and
     * {@code 1200-A-GET-DEFAULT-INT-RATE} paragraphs at
     * {@code app/cbl/CBACT04C.cbl:L415-L460}. Wired by the INTCALC.jcl
     * DD statement {@code //DISCGRP DD
     * DSN=AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS} at
     * {@code app/jcl/INTCALC.jcl:L35-L36}.
     */
    private final DiscountGroupRepository discountGroupRepository;

    /**
     * Port for the {@code TRANSACT} sequential output file
     * (copybook {@code app/cpy/CVTRA05Y.cpy}, 350-byte records). The
     * {@code 1300-B-WRITE-TX} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L473} emits one record per
     * (account, type, category) tuple via
     * {@link TransactionRepository#save}. Wired by the INTCALC.jcl
     * OUTPUT DD statement {@code //TRANSACT DD DISP=(NEW,CATLG,DELETE),
     * DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)} at
     * {@code app/jcl/INTCALC.jcl:L37-L41}. The {@code (+1)} generation
     * rolling is managed by the file adapter; this driver delegates to
     * the use case which writes through this port.
     */
    private final TransactionRepository transactionRepository;

    /**
     * The translated CBACT04C interest-calculation use case
     * ({@link CbAct04C}). This driver is a thin wrapper that delegates
     * the actual interest computation, account-balance update, and
     * SYSTRAN transaction emission to a single sequential
     * {@link CbAct04C#run(LocalDate)} call. The exit code returned by
     * that call is the COBOL {@code RETURN-CODE} value the program
     * would have placed in the {@code RETURN-CODE} special register
     * just before {@code GOBACK} (per the
     * {@code app/cbl/CBACT04C.cbl:L232} translation).
     *
     * <p>The use case is itself constructor-injected with the same five
     * repository ports above; this driver does NOT re-wire them &mdash;
     * the composition root constructs both the use case and this driver,
     * passing the same port instances to both. This driver retains its
     * own references to the ports so the composition root sees an
     * explicit DD-by-DD inventory matching the JCL.
     */
    private final CbAct04C cbAct04C;

    // -----------------------------------------------------------------------
    // Constructor — constructor injection per AAP §0.3.6
    // -----------------------------------------------------------------------

    /**
     * Constructs an {@code InterestCalculationBatch} with the six
     * dependencies required to express the {@code INTCALC} JCL flow
     * (five repository ports &mdash; one per non-SYSPRINT DD in
     * {@code app/jcl/INTCALC.jcl} STEP15 &mdash; plus the
     * {@link CbAct04C} use case).
     *
     * <p>All parameters are validated for non-nullness via
     * {@link Objects#requireNonNull(Object, String)}; a null argument
     * produces a {@link NullPointerException} whose message names the
     * offending parameter for fast diagnostic feedback at the composition
     * root. Replaces Spring's {@code @Autowired} NPE behavior per AAP
     * &sect;0.6.12 (no Spring container; plain constructor injection
     * only).
     *
     * @param tranCatBalRepository     port for TCATBALF sequential read
     *                                 (50-byte records, 17-byte composite
     *                                 key); never {@code null}
     * @param accountRepository        port for ACCTFILE random I-O
     *                                 read/REWRITE (300-byte records,
     *                                 11-digit ACCT-ID key); never
     *                                 {@code null}
     * @param cardXrefRepository       port for XREFFILE + XREFFIL1
     *                                 (primary key by card-num and AIX
     *                                 by acct-id, 50-byte records); never
     *                                 {@code null}
     * @param discountGroupRepository  port for DISCGRP random read
     *                                 (50-byte records, 16-byte composite
     *                                 key) with {@code "DEFAULT"} group
     *                                 fallback; never {@code null}
     * @param transactionRepository    port for TRANSACT sequential write
     *                                 (350-byte records to SYSTRAN GDG
     *                                 +1); never {@code null}
     * @param cbAct04C                 translated CBACT04C interest
     *                                 calculation use case; never
     *                                 {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public InterestCalculationBatch(
            TransactionCategoryBalanceRepository tranCatBalRepository,
            AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            DiscountGroupRepository discountGroupRepository,
            TransactionRepository transactionRepository,
            CbAct04C cbAct04C) {
        this.tranCatBalRepository = Objects.requireNonNull(
                tranCatBalRepository, "tranCatBalRepository");
        this.accountRepository = Objects.requireNonNull(
                accountRepository, "accountRepository");
        this.cardXrefRepository = Objects.requireNonNull(
                cardXrefRepository, "cardXrefRepository");
        this.discountGroupRepository = Objects.requireNonNull(
                discountGroupRepository, "discountGroupRepository");
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
        this.cbAct04C = Objects.requireNonNull(cbAct04C, "cbAct04C");
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Executes the interest calculation using the processing date from
     * the currently-bound {@link BatchRunContext}. The caller MUST have
     * established a {@link ScopedValue} scope binding
     * {@link BatchRunContext#BATCH_CTX} before invoking this method
     * &mdash; typically via
     * {@code ScopedValue.where(BatchRunContext.BATCH_CTX, ctx).run(() ->
     * interestCalculationBatch.execute())} or the convenience helper
     * {@code BatchRunContext.runWith(ctx, () -> ...)}.
     *
     * <p>This overload is the natural entry point for callers that have
     * already established a run context (e.g., from a parent batch
     * job or a composition root that read the processing date from
     * environment variables / system properties via
     * {@code BatchRunContext.fromEnvironment()}). Callers that have the
     * JCL PARM string verbatim should use
     * {@link #parseParmDate(String)} + {@link #execute(LocalDate)} or
     * pass a constructed {@link LocalDate} directly to
     * {@link #execute(LocalDate)}.
     *
     * <p>If {@link BatchRunContext#BATCH_CTX} is not currently bound,
     * this method throws {@link IllegalStateException} with a diagnostic
     * message naming this class and instructing the caller how to bind
     * the context. This avoids silently proceeding with a missing run
     * context which would produce output that cannot be correlated with
     * a batch run identity (per AAP &sect;0.6.6).
     *
     * @return the COBOL {@code RETURN-CODE}: {@code 0} for a clean run
     *         ({@link CbAct04C#APPL_AOK}), {@code 12} on any I/O failure
     *         that would have triggered the COBOL
     *         {@code 9999-ABEND-PROGRAM} paragraph
     *         ({@link CbAct04C#APPL_ERROR})
     * @throws IllegalStateException if {@link BatchRunContext#BATCH_CTX}
     *                               is not bound in the calling thread's
     *                               scope at the time of the call
     */
    public int execute() {
        if (!BatchRunContext.BATCH_CTX.isBound()) {
            throw new IllegalStateException(
                    "InterestCalculationBatch.execute() requires BatchRunContext.BATCH_CTX "
                            + "to be bound; wrap the call in "
                            + "ScopedValue.where(BatchRunContext.BATCH_CTX, ctx).run(...)");
        }
        BatchRunContext ctx = BatchRunContext.BATCH_CTX.get();
        // Defensive: ScopedValue.get() on a bound key should never return
        // null on the happy path, but guarding against an unexpected null
        // protects against misuse of the raw ScopedValue API (e.g., a
        // binding established via reflection to a null value). The
        // requireNonNull message names the failing component so the
        // diagnostic is actionable at the call site.
        Objects.requireNonNull(ctx, "BatchRunContext.BATCH_CTX bound value");
        return execute(ctx.processingDate());
    }

    /**
     * Executes the interest calculation for an explicit processing date,
     * equivalent to JCL {@code EXEC PGM=CBACT04C,PARM='yyyyMMddHH'}.
     *
     * <p>This method:
     * <ol>
     *   <li>Validates {@code processingDate} for non-nullness via
     *       {@link Objects#requireNonNull}.</li>
     *   <li>Logs the start of the run at INFO level with the processing
     *       date so operators and log-aggregation tooling can correlate
     *       the run with its date scope.</li>
     *   <li>Captures the wall-clock start time via
     *       {@link Instant#now()}.</li>
     *   <li>Delegates the actual interest-calculation work to
     *       {@link CbAct04C#run(LocalDate)}. This single call drives the
     *       entire COBOL PROCEDURE DIVISION: OPEN all five datasets,
     *       loop over TCATBAL with account-boundary detection,
     *       per-category discount-group lookup with
     *       {@link CbAct04C#DEFAULT_GROUP_ID} fallback, interest
     *       computation via the {@code Decimals} utility, SYSTRAN
     *       transaction emission, and account-balance update; finally
     *       CLOSE all five datasets and set the COBOL
     *       {@code RETURN-CODE}.</li>
     *   <li>Reads the exit code from the use case's return value.</li>
     *   <li>Computes the elapsed {@link Duration} from the captured
     *       start time and the current {@link Instant#now()}.</li>
     *   <li>Logs the end of the run at INFO level with exit code and
     *       elapsed duration.</li>
     *   <li>Returns the exit code to the caller (the composition root
     *       in {@code carddemo-app/InterestCalculationApp} typically
     *       passes it to {@link System#exit(int)} so the JCL-equivalent
     *       shell script can branch on it).</li>
     * </ol>
     *
     * <p>Per AAP &sect;0.6.6, this driver does NOT itself perform
     * virtual-thread fan-out. Any parallelisation of per-account
     * interest computation is the responsibility of the use case
     * ({@link CbAct04C}); the use case must collect results and emit
     * SYSTRAN transactions in the original sort order to preserve
     * byte-for-byte output fidelity per AAP &sect;0.1.3.
     *
     * <p>Per AAP &sect;0.6.1, this method performs no monetary
     * arithmetic itself. The interest formula
     * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} with
     * {@link java.math.RoundingMode#DOWN} (truncation, not banker's
     * rounding) is computed inside {@link CbAct04C} via the
     * {@code com.blitzy.carddemo.domain.util.Decimals} facade. The
     * driver merely orchestrates.
     *
     * <p>Any exception thrown by {@link CbAct04C#run(LocalDate)}
     * (typically {@code AbendException} for a hard COBOL ABEND-
     * equivalent failure, or {@link RuntimeException} for a
     * dataset-level I/O failure not caught by the use case)
     * propagates out of this method unchanged. The caller is
     * responsible for surfacing the exception to its process exit
     * handler; this driver does not catch or wrap exceptions because
     * doing so would obscure the failure surface and lose the original
     * stack trace.
     *
     * @param processingDate the processing date the run treats as
     *                       "today" for interest accrual (originating
     *                       from JCL {@code PARM=}'yyyyMMddHH'); never
     *                       {@code null}
     * @return the COBOL {@code RETURN-CODE}: {@code 0} for a clean run
     *         ({@link CbAct04C#APPL_AOK}), {@code 12} on any I/O failure
     *         that would have triggered the COBOL
     *         {@code 9999-ABEND-PROGRAM} paragraph
     *         ({@link CbAct04C#APPL_ERROR})
     * @throws NullPointerException if {@code processingDate} is
     *                              {@code null}
     */
    public int execute(LocalDate processingDate) {
        Objects.requireNonNull(processingDate, "processingDate");

        // Log the start of the run at INFO so operators and log-
        // aggregation tooling can correlate the run with its date scope.
        // The SLF4J {} placeholder avoids string concatenation when
        // INFO is disabled (defensive parameterisation per the standard
        // SLF4J idiom).
        LOG.info("INTCALC start: processingDate={}", processingDate);

        // Capture the wall-clock start time for elapsed-time computation
        // only. Instant.now() reads from the system clock; we do NOT use
        // it for any business-logic date/time decisions (those are
        // driven by the explicit processingDate parameter).
        Instant start = Instant.now();

        // Delegate to the use case. CbAct04C.run(LocalDate) is a single
        // sequential call that drives the entire CBACT04C PROCEDURE
        // DIVISION. Any AbendException or RuntimeException propagates
        // out of execute() unchanged so the caller's exit handler sees
        // the original failure stack (AAP §0.7.1: "identical observable
        // outcomes").
        int exitCode = cbAct04C.run(processingDate);

        // Compute elapsed wall-clock time for the completion log line.
        // Duration.between() handles the Instant arithmetic; we call
        // Instant.now() exactly once here so the end-of-run log line
        // reflects a consistent timestamp.
        Duration elapsed = Duration.between(start, Instant.now());

        // Emit the end-of-run INFO log line. The exit code and elapsed
        // duration are the two pieces of information operators need to
        // know "did the job succeed and how long did it take"; the use
        // case itself emits more granular per-record DEBUG/TRACE logs
        // when enabled.
        LOG.info("INTCALC end: exitCode={}, elapsed={}", exitCode, elapsed);

        // Return the exit code so the composition root can pass it to
        // System.exit() or its equivalent. Per AAP §0.7.1 ("preserve
        // return codes exactly") this value MUST be the same value the
        // COBOL program would have set in RETURN-CODE just before
        // GOBACK.
        return exitCode;
    }

    /**
     * Parses a 10-character JCL PARM string of the form
     * {@code yyyyMMddHH} into a {@link LocalDate}. The trailing two hour
     * characters are accepted but ignored (per CBACT04C behavior &mdash;
     * interest accrual is date-granular, not hour-granular; the COBOL
     * {@code PARM-DATE PIC X(10)} field at
     * {@code app/cbl/CBACT04C.cbl:L178} is moved to
     * {@code TRAN-ID} but never decomposed into hour components).
     *
     * <p>The expected PARM format is the same shape as the JCL
     * statement {@code EXEC PGM=CBACT04C,PARM='2022071800'} at
     * {@code app/jcl/INTCALC.jcl:L22}: a 10-character string of
     * zero-padded year-month-day-hour with no separators.
     *
     * <p>Validation is performed in three stages to produce
     * actionable error messages for malformed PARMs:
     * <ol>
     *   <li>Null and blank check &mdash; rejects {@code null},
     *       {@code ""}, and whitespace-only strings.</li>
     *   <li>Length check &mdash; rejects strings whose stripped length
     *       is not exactly 10 characters; this catches truncation,
     *       extra characters, and the common mistake of passing an
     *       8-character {@code yyyyMMdd} date instead.</li>
     *   <li>Format check &mdash; delegates to
     *       {@link LocalDate#parse(CharSequence, DateTimeFormatter)}
     *       with {@link #PARM_FORMAT}; any
     *       {@link DateTimeParseException} thrown is wrapped in an
     *       {@link IllegalArgumentException} with a diagnostic message
     *       naming the rejected PARM string and the original parse
     *       cause.</li>
     * </ol>
     *
     * <p>This method is provided as a convenience for callers that
     * receive the JCL PARM string verbatim (e.g., from a main-method
     * {@code args[0]} in {@code carddemo-app/InterestCalculationApp}).
     * Callers that already have a {@link LocalDate} should invoke
     * {@link #execute(LocalDate)} directly; callers that have a bound
     * {@link BatchRunContext} should invoke the no-arg
     * {@link #execute()} overload.
     *
     * <h3>Examples</h3>
     * <ul>
     *   <li>{@code parseParmDate("2022071800")} returns
     *       {@code LocalDate.of(2022, 7, 18)} &mdash; matches the JCL
     *       sample PARM verbatim.</li>
     *   <li>{@code parseParmDate("2022071823")} returns
     *       {@code LocalDate.of(2022, 7, 18)} &mdash; same date,
     *       different hour (hour is dropped).</li>
     *   <li>{@code parseParmDate(null)} &rArr;
     *       {@link IllegalArgumentException}.</li>
     *   <li>{@code parseParmDate("20220718")} (8 chars, missing hour)
     *       &rArr; {@link IllegalArgumentException}.</li>
     *   <li>{@code parseParmDate("2022071832")} (invalid hour)
     *       &rArr; succeeds; the formatter accepts hours 00-23 but
     *       parsers may permit 24+ depending on resolver style
     *       &mdash; downstream behavior is identical to the COBOL
     *       baseline which performs no validation on the hour
     *       portion.</li>
     *   <li>{@code parseParmDate("ABCDEFGHIJ")} (non-digit) &rArr;
     *       {@link IllegalArgumentException} wrapping the underlying
     *       {@link DateTimeParseException}.</li>
     * </ul>
     *
     * @param parm 10-character PARM string of the form
     *             {@code yyyyMMddHH} (e.g., {@code "2022071800"}); the
     *             string is stripped of leading/trailing whitespace
     *             before length validation
     * @return the parsed processing date with the hour portion dropped
     * @throws IllegalArgumentException if {@code parm} is {@code null},
     *                                  blank, not exactly 10 characters
     *                                  after stripping, or not parseable
     *                                  as {@code yyyyMMddHH}; the cause
     *                                  of a parse failure is the
     *                                  underlying
     *                                  {@link DateTimeParseException}
     */
    public static LocalDate parseParmDate(String parm) {
        if (parm == null || parm.isBlank()) {
            throw new IllegalArgumentException(
                    "INTCALC PARM is required, expected format yyyyMMddHH");
        }
        String trimmed = parm.strip();
        if (trimmed.length() != 10) {
            throw new IllegalArgumentException(
                    "INTCALC PARM must be 10 characters (yyyyMMddHH), got "
                            + trimmed.length() + ": " + trimmed);
        }
        try {
            return LocalDate.parse(trimmed, PARM_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "INTCALC PARM is not a valid yyyyMMddHH date: " + trimmed, e);
        }
    }
}
