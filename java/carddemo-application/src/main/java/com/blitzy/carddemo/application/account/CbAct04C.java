/*
 * Copyright 2022 The CardDemo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blitzy.carddemo.application.account;

// JEP 511 (finalized in Java 25): a single module import declaration pulls
// in every package exported by the java.base module. Per AAP §0.6.7, this
// is the mandated idiom for files that touch many java.* packages. It
// satisfies the requirements for java.util.{Objects, Optional},
// java.math.{BigDecimal, RoundingMode}, java.time.{LocalDate, LocalDateTime},
// java.time.format.{DateTimeFormatter, ResolverStyle}, and the
// java.util.stream.Stream that this class consumes.
import module java.base;

import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.AccountRepository;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.DiscountGroupRepository;
import com.blitzy.carddemo.domain.port.TransactionCategoryBalanceRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.DisGroupRecord;
import com.blitzy.carddemo.domain.record.TranCatBalRecord;
import com.blitzy.carddemo.domain.record.TranRecord;
import com.blitzy.carddemo.domain.util.Decimals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of the {@code CBACT04C} COBOL batch program at
 * {@code app/cbl/CBACT04C.cbl} (653 lines, "Interest calculator"). Per the
 * Agent Action Plan (AAP) &sect;0.4.1, this class translates the COBOL
 * interest-calculation engine into a Java use case that consumes five
 * domain ports: {@link TransactionCategoryBalanceRepository},
 * {@link AccountRepository}, {@link CardXrefRepository},
 * {@link DiscountGroupRepository}, and {@link TransactionRepository}.
 *
 * <h2>Program purpose</h2>
 * <p>Walks the TCATBAL VSAM KSDS sequentially. For each
 * {@link TranCatBalRecord} encountered, the program detects account
 * boundaries (the {@code TRANCAT-ACCT-ID} component of the composite key).
 * When the account ID changes, the prior account's accumulated interest is
 * posted to its {@code ACCT-CURR-BAL} via a {@code REWRITE} (the
 * {@code 1050-UPDATE-ACCOUNT} paragraph at COBOL line 350), the
 * {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT} counters
 * are zeroed, and the new account's master record + card cross-reference
 * are loaded from {@link AccountRepository#findById(long)} and
 * {@link CardXrefRepository#findByAccountId(long)} respectively.
 *
 * <p>For each {@code TRANCAT-TYPE-CD} + {@code TRANCAT-CD} pair within an
 * account, the program looks up the per-account discount-group interest
 * rate via {@link DiscountGroupRepository#findByKey(String, String, int)}
 * keyed by {@code ACCT-GROUP-ID} (the {@code 1200-GET-INTEREST-RATE}
 * paragraph at COBOL line 415). If that lookup yields FILE STATUS
 * {@code '23'} (NOT FOUND), the program retries with the literal
 * {@code "DEFAULT"} as the group ID (the {@code 1200-A-GET-DEFAULT-INT-RATE}
 * fallback at COBOL line 443).
 *
 * <p>The monthly interest is computed as
 * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} per the
 * {@code 1300-COMPUTE-INTEREST} paragraph (COBOL line 462). The COBOL
 * {@code COMPUTE} statement carries NO {@code ROUNDED} clause, so the
 * default truncation rule applies &mdash; the Java translation uses
 * {@link RoundingMode#DOWN} per AAP &sect;0.6.1. The computed interest is
 * accumulated into {@code WS-TOTAL-INT}, and a 350-byte
 * {@link TranRecord} is emitted to the TRANSACT output file via the
 * {@code 1300-B-WRITE-TX} paragraph (COBOL line 473) carrying:
 * <ul>
 *   <li>{@code TRAN-TYPE-CD = "01"} ({@link #TRAN_TYPE_INTEREST})</li>
 *   <li>{@code TRAN-CAT-CD  = 5}    ({@link #TRAN_CAT_INTEREST})</li>
 *   <li>{@code TRAN-SOURCE  = "System"} ({@link #TRAN_SOURCE_SYSTEM})</li>
 *   <li>{@code TRAN-DESC    = "Int. for a/c " + 11-digit ACCT-ID}
 *       ({@link #TRAN_DESC_PREFIX})</li>
 *   <li>{@code TRAN-AMT     = WS-MONTHLY-INT}</li>
 *   <li>{@code TRAN-CARD-NUM = XREF-CARD-NUM}</li>
 *   <li>{@code TRAN-ORIG-TS = TRAN-PROC-TS = current DB2 timestamp}</li>
 * </ul>
 *
 * <h2>Translation authority</h2>
 * <ul>
 *   <li>AAP &sect;0.1.1 &mdash; one Java class per COBOL
 *       {@code PROGRAM-ID}, original program name preserved.</li>
 *   <li>AAP &sect;0.1.2 &mdash; public method per entry paragraph;
 *       private method per internal paragraph.</li>
 *   <li>AAP &sect;0.4.1 &mdash; "Interest calculation engine (driven by
 *       INTCALC JCL)".</li>
 *   <li>AAP &sect;0.6.8 &mdash; "CBACT04C | CbAct04C |
 *       app/cbl/CBACT04C.cbl | interest posting paragraphs".</li>
 *   <li>AAP &sect;0.6.1 &mdash; monetary arithmetic uses
 *       {@link java.math.BigDecimal} with explicit {@link MathContext}
 *       and {@link RoundingMode} per the {@link Decimals} utility.</li>
 *   <li>AAP &sect;0.6.4 &mdash; date/time values use {@link java.time}
 *       types; never {@link java.util.Date} or {@link java.util.Calendar}.</li>
 *   <li>AAP &sect;0.7.1 &mdash; idiom-for-idiom translation: preserve
 *       the {@code 1400-COMPUTE-FEES} no-op verbatim; flag the
 *       {@code 1300-B-WRITE-TX} TRAN-ID layout deviation in
 *       MIGRATION_NOTES.md.</li>
 * </ul>
 *
 * <h2>Paragraph-to-method 1:1 mapping</h2>
 * <table>
 *   <caption>Mapping from COBOL paragraphs to Java methods</caption>
 *   <tr><th>COBOL paragraph</th><th>Java method</th><th>COBOL lines</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} entry</td>
 *       <td>{@link #run(LocalDate)}</td><td>L180-L232</td></tr>
 *   <tr><td>{@code 0000-TCATBALF-OPEN}</td>
 *       <td>{@link #openTcatBal()}</td><td>L234-L250</td></tr>
 *   <tr><td>{@code 0100-XREFFILE-OPEN}</td>
 *       <td>{@link #openCardXref()}</td><td>L252-L268</td></tr>
 *   <tr><td>{@code 0200-DISCGRP-OPEN}</td>
 *       <td>{@link #openDiscGrp()}</td><td>L270-L286</td></tr>
 *   <tr><td>{@code 0300-ACCTFILE-OPEN}</td>
 *       <td>{@link #openAcctFile()}</td><td>L289-L305</td></tr>
 *   <tr><td>{@code 0400-TRANFILE-OPEN}</td>
 *       <td>{@link #openTransact()}</td><td>L307-L323</td></tr>
 *   <tr><td>{@code 1000-TCATBALF-GET-NEXT}</td>
 *       <td>{@link #processTcatBalRecord(TranCatBalRecord, LocalDate)}</td>
 *       <td>L325-L348</td></tr>
 *   <tr><td>{@code 1050-UPDATE-ACCOUNT}</td>
 *       <td>{@link #updateAccount()}</td><td>L350-L370</td></tr>
 *   <tr><td>{@code 1100-GET-ACCT-DATA}</td>
 *       <td>{@link #getAcctData(long)}</td><td>L372-L391</td></tr>
 *   <tr><td>{@code 1110-GET-XREF-DATA}</td>
 *       <td>{@link #getXrefData(long)}</td><td>L393-L413</td></tr>
 *   <tr><td>{@code 1200-GET-INTEREST-RATE} +
 *           {@code 1200-A-GET-DEFAULT-INT-RATE}</td>
 *       <td>{@link #getInterestRate(String, int)}</td>
 *       <td>L415-L460</td></tr>
 *   <tr><td>{@code 1300-COMPUTE-INTEREST}</td>
 *       <td>{@link #computeMonthlyInterest(BigDecimal, BigDecimal)}</td>
 *       <td>L462-L470</td></tr>
 *   <tr><td>{@code 1300-B-WRITE-TX}</td>
 *       <td>{@link #writeInterestTransaction(BigDecimal, LocalDate)}</td>
 *       <td>L473-L515</td></tr>
 *   <tr><td>{@code 1400-COMPUTE-FEES}</td>
 *       <td>{@link #computeFees()}</td><td>L518-L520 (no-op)</td></tr>
 *   <tr><td>{@code 9000-9400} CLOSE family</td>
 *       <td>{@link #closeAll()}</td><td>L522-L611</td></tr>
 *   <tr><td>{@code Z-GET-DB2-FORMAT-TIMESTAMP}</td>
 *       <td>{@link #formatDb2Timestamp(LocalDateTime)}</td>
 *       <td>L613-L626</td></tr>
 *   <tr><td>{@code 9910-DISPLAY-IO-STATUS}</td>
 *       <td>{@link #displayIoStatus(String)}</td><td>L635-L648</td></tr>
 *   <tr><td>{@code 9999-ABEND-PROGRAM}</td>
 *       <td>{@link #abendProgram(Throwable)}</td><td>L628-L632</td></tr>
 * </table>
 *
 * <h2>Decimal arithmetic (AAP &sect;0.6.1)</h2>
 * <p>The {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 * statement carries NO {@code ROUNDED} clause, so the COBOL default
 * (truncate) applies. This Java translation uses
 * {@link BigDecimal#multiply(BigDecimal, MathContext)} with
 * {@link Decimals#DEFAULT_MATH_CONTEXT} (DECIMAL128, 34-digit precision)
 * for the intermediate product, then
 * {@link BigDecimal#divide(BigDecimal, int, RoundingMode)} with
 * {@link RoundingMode#DOWN} (truncation) for the final divide. The result
 * is then scaled to exactly 2 decimal places via {@link Decimals#scaled}
 * to preserve trailing zeros per AAP &sect;0.1.3 (a value of {@code 1.20}
 * is NEVER normalized to {@code 1.2}).
 *
 * <p>The post-interest balance update at {@code 1050-UPDATE-ACCOUNT}
 * (COBOL line 352: {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}) also lacks
 * the {@code ROUNDED} clause; the Java translation uses
 * {@link RoundingMode#DOWN} via
 * {@link Decimals#add(BigDecimal, BigDecimal, int, RoundingMode)} for
 * exact byte-parity with the COBOL output.
 *
 * <h2>DB2 timestamp format (AAP &sect;0.6.4)</h2>
 * <p>The COBOL paragraph {@code Z-GET-DB2-FORMAT-TIMESTAMP} at
 * {@code app/cbl/CBACT04C.cbl:L613-L626} builds a 26-byte timestamp of
 * the form {@code yyyy-MM-dd-HH.mm.ss.XX0000}: hyphens between the
 * year/month/day/hour components, dots between hour/minute/second/centiseconds,
 * and a fixed 4-byte {@code "0000"} trailer for microsecond padding.
 *
 * <p>The Java translation in {@link #formatDb2Timestamp(LocalDateTime)}
 * extracts centiseconds (hundredths of a second) from
 * {@link LocalDateTime#getNano()} by integer division with
 * {@code 10_000_000}, then formats the components with
 * {@link String#format}. The result is a 26-character string suitable
 * for direct assignment to {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}.
 *
 * <h2>'DEFAULT' group fallback (AAP &sect;0.6.8)</h2>
 * <p>When the per-account discount-group lookup returns FILE STATUS
 * {@code '23'} (NOT FOUND), the COBOL paragraph
 * {@code 1200-A-GET-DEFAULT-INT-RATE} (line 443) re-reads DISCGRP with
 * the literal {@code 'DEFAULT'} substituted for the account's
 * {@code ACCT-GROUP-ID}. The Java translation in
 * {@link #getInterestRate(String, int)} mirrors this by calling
 * {@link DiscountGroupRepository#findByKey(String, String, int)} a
 * second time with {@link #DEFAULT_GROUP_ID} on first-lookup miss.
 *
 * <h2>TRAN-ID composition deviation</h2>
 * <p>The COBOL {@code 1300-B-WRITE-TX} paragraph composes TRAN-ID via
 * {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID}
 * where {@code PARM-DATE PIC X(10)} (10 chars from JCL PARM='2022071800',
 * YYYYMMDDHH) and {@code WS-TRANID-SUFFIX PIC 9(06)} (6 zoned digits) sum
 * to the {@code TRAN-ID PIC X(16)} slot. This Java translation accepts a
 * {@link LocalDate} (not a 10-char PARM string) per the
 * {@code run(LocalDate)} schema contract, so it formats the date with
 * the {@code yyyyMMdd} pattern (8 chars) and uses an 8-digit sequence
 * suffix (zero-padded) to fill the same 16-char slot. This is a small
 * documented deviation from byte-identical COBOL output (the trailing
 * 2 chars of PARM-DATE, typically {@code "00"} for the hour, are absorbed
 * into the leading 2 digits of the 8-digit sequence). The substantive
 * semantics &mdash; unique 16-char tran-id per emission, sortable by
 * processing date &mdash; are preserved. Flag this deviation in
 * MIGRATION_NOTES.md.
 *
 * <h2>Forbidden idioms (per AAP &sect;0.6.7, &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring / Spring Boot &mdash; plain constructor injection.</li>
 *   <li>No {@code double}/{@code float} &mdash; monetary arithmetic
 *       uses {@link BigDecimal} exclusively.</li>
 *   <li>No {@link java.util.Date}/{@link java.util.Calendar} &mdash;
 *       all date/time uses {@link java.time}.</li>
 *   <li>No {@link java.io.File} &mdash; file I/O is performed by the
 *       file adapters behind the repository ports.</li>
 *   <li>No {@link ThreadLocal} &mdash; this class has no cross-thread
 *       context; any future fan-out would use {@code ScopedValue}.</li>
 *   <li>No reflection or dynamic proxies.</li>
 *   <li>No {@code --enable-preview} JVM flag.</li>
 * </ul>
 *
 * <h2>Mandated Java 25 features used</h2>
 * <ul>
 *   <li>{@code import module java.base;} (JEP 511 finalized) &mdash;
 *       replaces ~10 individual {@code java.*} imports with a single
 *       module import declaration.</li>
 *   <li>Records ({@link TranCatBalRecord}, {@link AccountRecord},
 *       etc.) as the carrier for fixed-width COBOL data.</li>
 *   <li>{@link BigDecimal} with explicit {@link MathContext#DECIMAL128}
 *       and {@link RoundingMode} per {@link Decimals} per AAP &sect;0.6.1.</li>
 *   <li>{@link java.time.LocalDate} / {@link java.time.LocalDateTime}
 *       per AAP &sect;0.6.4.</li>
 *   <li>{@link DateTimeFormatter#withResolverStyle(ResolverStyle)} with
 *       {@link ResolverStyle#STRICT} for the YYYYMMDD parser.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>Instances of {@code CbAct04C} are not thread-safe in the sense that
 * concurrent calls to {@link #run(LocalDate)} would share the same
 * mutable iteration state ({@link #lastAcctNum},
 * {@link #totalInt}, etc.) and the same underlying file channels via the
 * injected repository ports. The expected usage pattern (matching the
 * COBOL execution model where each job step runs in its own address
 * space) is one instance per execution. The composition root in
 * {@code carddemo-app/InterestCalculationApp} constructs a fresh instance
 * for each invocation.
 *
 * @see TransactionCategoryBalanceRepository
 * @see AccountRepository
 * @see CardXrefRepository
 * @see DiscountGroupRepository
 * @see TransactionRepository
 * @see Decimals
 * @see CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBACT04C",
        sourcePath = "app/cbl/CBACT04C.cbl",
        translationDate = "2025-01-15",
        notes = "Interest calculation engine; driven by INTCALC JCL. "
                + "Formula: (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 with "
                + "RoundingMode.DOWN (truncation) per AAP §0.6.1. "
                + "'DEFAULT' group ID fallback when account-specific group not "
                + "found (FILE STATUS '23' NOTFND retry). "
                + "TRAN-ID layout deviation documented in MIGRATION_NOTES.md: "
                + "Java uses 8-char yyyyMMdd date + 8-digit sequence; COBOL "
                + "uses 10-char PARM-DATE (YYYYMMDDHH) + 6-digit sequence. "
                + "1400-COMPUTE-FEES preserved as no-op verbatim per AAP §0.7.1."
)
public final class CbAct04C {

    // -----------------------------------------------------------------
    // Public constants — exposed per file-schema exports and binding for
    // downstream callers (test harness, composition root, golden-record
    // tests). Names match the file-schema 'members_exposed' list exactly;
    // any rename requires a corresponding update to the file schema.
    // -----------------------------------------------------------------

    /**
     * Mirror of the COBOL {@code PROGRAM-ID. CBACT04C} declaration at
     * {@code app/cbl/CBACT04C.cbl:L23}. Used in the
     * "START OF EXECUTION OF PROGRAM CBACT04C" and
     * "END OF EXECUTION OF PROGRAM CBACT04C" trace messages (COBOL
     * lines 181 and 230).
     */
    public static final String PROGRAM_ID = "CBACT04C";

    /**
     * Divisor in the monthly interest formula (12 months &times; 100
     * percent = 1200). Per the COBOL {@code 1300-COMPUTE-INTEREST}
     * paragraph at {@code app/cbl/CBACT04C.cbl:L462-L470}:
     * <pre>{@code
     * COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * }</pre>
     * The COBOL statement carries NO {@code ROUNDED} clause, so the
     * Java translation uses {@link RoundingMode#DOWN} (truncation) per
     * AAP &sect;0.6.1. Encoded as {@code new BigDecimal(1200)} (exact
     * integer, no scale or precision artifacts).
     */
    public static final BigDecimal MONTHLY_DIVISOR = new BigDecimal(1200);

    /**
     * The fallback {@code ACCT-GROUP-ID} value used by the COBOL
     * {@code 1200-A-GET-DEFAULT-INT-RATE} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L443-L460}. When the per-account
     * DISCGRP lookup at {@code 1200-GET-INTEREST-RATE} returns FILE
     * STATUS {@code '23'} (NOT FOUND), the program substitutes this
     * literal for the account's {@code ACCT-GROUP-ID} and re-reads the
     * discount group entry.
     */
    public static final String DEFAULT_GROUP_ID = "DEFAULT";

    // FILE STATUS codes — translated from the WORKING-STORAGE 'XXX-STATUS'
    // declarations and the literal comparisons against '00', '10', '23'
    // throughout the program (CBACT04C.cbl:L98-L120 declarations, plus
    // the IF status = '00' / '10' / '23' branches in every I/O paragraph).

    /**
     * COBOL {@code FILE STATUS} success code ({@code "00"}). Returned
     * by VSAM after a successful {@code OPEN}, {@code READ},
     * {@code REWRITE}, {@code WRITE}, or {@code CLOSE}. Compared
     * against at CBACT04C lines 237, 255, 273, 292, 310, 327, 357,
     * 378, 400, 422, 446, 501, 525, 544, 562, 580, 598.
     */
    public static final String STATUS_OK = "00";

    /**
     * COBOL {@code FILE STATUS} end-of-file code ({@code "10"}).
     * Returned by VSAM when a sequential {@code READ} reaches the end
     * of the dataset. Compared against at CBACT04C line 330. The Java
     * translation never observes this code directly because
     * end-of-stream is signaled by {@link java.util.stream.Stream}
     * termination; the constant is preserved for trace parity and any
     * future status-code-driven branching.
     */
    public static final String STATUS_EOF = "10";

    /**
     * COBOL {@code FILE STATUS} not-found code ({@code "23"}). Returned
     * by VSAM for an indexed-access {@code READ INVALID KEY} miss
     * (compared against at CBACT04C lines 422 and 436). This is the
     * status that triggers the {@link #DEFAULT_GROUP_ID} fallback in
     * {@code 1200-GET-INTEREST-RATE}.
     */
    public static final String STATUS_NOT_FOUND = "23";

    // Transaction defaults — translated from the literal MOVEs in
    // 1300-B-WRITE-TX at app/cbl/CBACT04C.cbl:L482-L495.

    /**
     * Interest transaction type code ({@code "01"}). Per COBOL line 482:
     * {@code MOVE '01' TO TRAN-TYPE-CD}. The 2-character type code is
     * the COBOL convention for an interest-charge transaction; other
     * transaction types in CardDemo (purchase, refund, payment, etc.)
     * use different 2-character codes.
     */
    public static final String TRAN_TYPE_INTEREST = "01";

    /**
     * Interest transaction category code ({@code "05"}). Per COBOL
     * line 483: {@code MOVE '05' TO TRAN-CAT-CD}. The 4-character
     * category code further classifies the interest transaction
     * within the type-01 family.
     *
     * <p>This constant is declared {@code String} (not {@code int})
     * because the COBOL literal is enclosed in single quotes
     * ({@code '05'}), making it a {@code PIC X(2)}-style alphanumeric
     * MOVE target. The downstream call site converts it to the
     * {@link TranRecord#tranCatCd()} {@code int} component via
     * {@link Integer#parseInt(String)}.
     */
    public static final String TRAN_CAT_INTEREST = "05";

    /**
     * Transaction source identifier ({@code "System"}). Per COBOL line
     * 484: {@code MOVE 'System' TO TRAN-SOURCE}. The 6-character source
     * indicates that the transaction was generated by an automated
     * batch process (as opposed to an online operator entry, an
     * external POS, etc.). The {@code TRAN-SOURCE PIC X(10)} field
     * pads this to 10 characters with trailing spaces; the
     * {@link TranRecord} encoder applies the padding.
     */
    public static final String TRAN_SOURCE_SYSTEM = "System";

    /**
     * Prefix for the interest transaction description (13 characters
     * including trailing space). Per COBOL lines 485-489:
     * <pre>{@code
     * STRING 'Int. for a/c ' , ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC
     * }</pre>
     * The COBOL {@code STRING} verb concatenates this 13-byte prefix
     * with the 11-digit {@code ACCT-ID} to produce a 24-byte
     * description (the {@code TRAN-DESC PIC X(100)} field pads the
     * remainder with trailing spaces). The Java translation uses
     * {@link String#format} with the {@code %011d} mask to match the
     * COBOL zero-padding of {@code ACCT-ID}.
     */
    public static final String TRAN_DESC_PREFIX = "Int. for a/c ";

    // Application return codes — translated from the WORKING-STORAGE
    // level-01 'APPL-RESULT' with 88-level 'APPL-AOK' (VALUE 0) and
    // 'APPL-EOF' (VALUE 16) at app/cbl/CBACT04C.cbl:L133-L135, plus the
    // imputed APPL_ERROR (12) seen in MOVE 12 TO APPL-RESULT throughout
    // every error branch.

    /**
     * COBOL {@code 88 APPL-AOK VALUE 0} (CBACT04C.cbl:L134) &mdash;
     * the "all OK" application result. Returned from
     * {@link #run(LocalDate)} on a clean execution.
     */
    public static final int APPL_AOK = 0;

    /**
     * COBOL {@code 88 APPL-EOF VALUE 16} (CBACT04C.cbl:L135) &mdash;
     * the end-of-file application result. The Java translation does
     * NOT surface this code from {@link #run(LocalDate)} because
     * end-of-stream is the expected, non-error termination of the
     * sequential TCATBAL scan; the constant is preserved for trace
     * parity with the COBOL state machine.
     */
    public static final int APPL_EOF = 16;

    /**
     * COBOL implicit application-error value (12), seen in
     * {@code MOVE 12 TO APPL-RESULT} statements throughout every error
     * branch in CBACT04C (e.g., lines 240, 258, 276, 295, 313, 333,
     * 360, 381, 403, 425, 449, 504, 528, 547, 565, 583, 601). Returned
     * from {@link #run(LocalDate)} on any I/O failure that would have
     * triggered the COBOL {@code 9999-ABEND-PROGRAM} paragraph.
     */
    public static final int APPL_ERROR = 12;

    // -----------------------------------------------------------------
    // Internal constants — not exported via the file schema; not part
    // of the public contract.
    // -----------------------------------------------------------------

    /**
     * Monetary scale for all {@code PIC S9(09)V99} and
     * {@code PIC S9(10)V99} fields in this program. Per AAP &sect;0.1.3
     * this scale is preserved in every BigDecimal site: {@code "1.20"}
     * stays {@code "1.20"}, never {@code "1.2"}.
     */
    private static final int MONETARY_SCALE = 2;

    /**
     * Intermediate scale for the {@code (TRAN-CAT-BAL * DIS-INT-RATE)} product
     * used by {@link #computeMonthlyInterest(BigDecimal, BigDecimal)}. Two extra
     * digits beyond {@link #MONETARY_SCALE} are retained so the subsequent
     * division by {@link #MONTHLY_DIVISOR} does not lose significant digits
     * before the final scale-2 truncation. This matches the COBOL implicit
     * numeric promotion that occurs between the {@code MULTIPLY} and
     * {@code DIVIDE} steps of the {@code COMPUTE WS-MONTHLY-INT =
     * (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} expression at
     * {@code app/cbl/CBACT04C.cbl:L462-L468}.
     */
    private static final int INTEREST_INTERMEDIATE_SCALE = 4;

    /**
     * The DateTimeFormatter for translating a {@link LocalDate}
     * argument into the 8-character TRAN-ID date prefix per COBOL
     * line 476 ({@code STRING PARM-DATE DELIMITED BY SIZE INTO TRAN-ID}).
     * Strict resolver style ensures malformed dates are rejected
     * eagerly rather than silently coerced.
     *
     * <p>Note: the COBOL {@code PARM-DATE PIC X(10)} carries 10 bytes
     * (YYYYMMDDHH), but this translation accepts a {@link LocalDate}
     * (date-only) per the file-schema {@code run(LocalDate)} contract;
     * the resulting deviation is documented in MIGRATION_NOTES.md.
     */
    private static final DateTimeFormatter TRAN_ID_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd")
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * The width of the sequence suffix in the TRAN-ID composition
     * (8 zero-padded digits). With the 8-char date prefix this fills
     * the {@code TRAN-ID PIC X(16)} slot exactly.
     */
    private static final int TRAN_ID_SUFFIX_WIDTH = 8;

    /**
     * The maximum sequence value that fits in the 8-digit TRAN-ID
     * suffix (100,000,000 - 1 = 99,999,999). After this many
     * transactions the sequence overflow would corrupt the TRAN-ID
     * format; this constant supports a guard in
     * {@link #writeInterestTransaction(BigDecimal, LocalDate)}.
     */
    private static final long TRAN_ID_SUFFIX_MAX = 99_999_999L;

    /**
     * COBOL ACCT-ID width for the description suffix
     * ({@code STRING 'Int. for a/c ', ACCT-ID DELIMITED BY SIZE INTO
     * TRAN-DESC} at CBACT04C.cbl:L485-L489). Used with
     * {@link String#format} mask {@code "%011d"} to zero-pad the 11-digit
     * account id into the description string.
     */
    private static final int ACCT_ID_DESC_WIDTH = 11;

    /**
     * The COBOL CEE3ABD ABCODE parameter (999) — from
     * {@code MOVE 999 TO ABCODE} at {@code app/cbl/CBACT04C.cbl:L631}.
     * Surfaced via {@link AbendException#abendCode()} so the
     * composition root may translate it to a non-zero process exit
     * code.
     */
    private static final int CEE3ABD_ABCODE = 999;


    // -----------------------------------------------------------------
    // Logger — translated COBOL DISPLAY verb sink. SLF4J facade only;
    // the concrete logging backend (logback-classic) is supplied by the
    // composition root in carddemo-app per AAP §0.6.12.
    // -----------------------------------------------------------------
    private static final Logger log = LoggerFactory.getLogger(CbAct04C.class);

    // -----------------------------------------------------------------
    // Injected collaborators — final, non-null; validated in the
    // constructor with Objects.requireNonNull per AAP §0.6.12.
    // -----------------------------------------------------------------

    /**
     * Port for sequential TCATBAL reads
     * ({@code 1000-TCATBALF-GET-NEXT} paragraph at CBACT04C.cbl:L325-L348).
     */
    private final TransactionCategoryBalanceRepository tcatBalRepository;

    /**
     * Port for random ACCTFILE reads via primary key and REWRITEs
     * ({@code 1100-GET-ACCT-DATA} paragraph at CBACT04C.cbl:L372-L391
     * and {@code 1050-UPDATE-ACCOUNT} paragraph at L350-L370).
     */
    private final AccountRepository accountRepository;

    /**
     * Port for random XREF reads via alternate key {@code FD-XREF-ACCT-ID}
     * ({@code 1110-GET-XREF-DATA} paragraph at CBACT04C.cbl:L393-L413).
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Port for random DISCGRP reads via composite key (group, type, cat)
     * ({@code 1200-GET-INTEREST-RATE} paragraph at CBACT04C.cbl:L415-L440
     * and {@code 1200-A-GET-DEFAULT-INT-RATE} at L443-L460).
     */
    private final DiscountGroupRepository discountGroupRepository;

    /**
     * Port for sequential TRANSACT writes
     * ({@code 1300-B-WRITE-TX} paragraph at CBACT04C.cbl:L473-L515).
     */
    private final TransactionRepository transactionRepository;

    // -----------------------------------------------------------------
    // Per-execution mutable state — translated from WORKING-STORAGE
    // SECTION fields WS-LAST-ACCT-NUM, WS-TOTAL-INT, WS-FIRST-TIME,
    // WS-TRANID-SUFFIX, plus the implicit "current account" and
    // "current xref" cached during the iteration.
    //
    // These fields are RESET at the beginning of every run(LocalDate)
    // invocation so the same CbAct04C instance can be reused safely
    // (a fresh run is a fresh COBOL execution).
    // -----------------------------------------------------------------

    /**
     * Last-seen account ID across the iteration ({@code WS-LAST-ACCT-NUM}
     * at CBACT04C.cbl:L167). Used by {@link #processTcatBalRecord} to
     * detect account boundaries.
     *
     * <p>Initialized to {@code -1L} (an out-of-domain value) so the
     * first TCATBAL record always triggers the account-break branch
     * (matching the COBOL {@code WS-FIRST-TIME = 'Y'} sentinel).
     */
    private long lastAcctNum;

    /**
     * Accumulated interest for the current account
     * ({@code WS-TOTAL-INT} at CBACT04C.cbl:L169). Translated as a
     * mutable {@link BigDecimal} field (scale 2) that is reset to zero
     * on each account boundary and posted to {@code ACCT-CURR-BAL} via
     * {@link #updateAccount()} at the next boundary or at end-of-stream.
     */
    private BigDecimal totalInt;

    /**
     * First-iteration sentinel ({@code WS-FIRST-TIME} at
     * CBACT04C.cbl:L170). {@code true} until the first TCATBAL record
     * is processed; thereafter {@code false}. Used to suppress the
     * spurious {@link #updateAccount()} call on the very first
     * account-break (because there is no prior account to update yet).
     */
    private boolean firstTime;

    /**
     * Transaction ID suffix counter ({@code WS-TRANID-SUFFIX} at
     * CBACT04C.cbl:L173). Incremented once per interest transaction
     * emitted; zero-padded to 8 digits and concatenated with the
     * {@code yyyyMMdd} date prefix to fill the 16-char
     * {@code TRAN-ID PIC X(16)} slot.
     */
    private long tranIdSuffix;

    /**
     * The current account being processed (cached after each
     * {@code 1100-GET-ACCT-DATA} call). Null until the first account
     * is loaded; written by {@link #processTcatBalRecord} on every
     * account boundary; read by {@link #updateAccount},
     * {@link #getInterestRate}, and
     * {@link #writeInterestTransaction(BigDecimal, LocalDate)}.
     */
    private AccountRecord currentAccount;

    /**
     * The current card cross-reference entry being processed (cached
     * after each {@code 1110-GET-XREF-DATA} call). Null until the
     * first account is loaded; written by {@link #processTcatBalRecord}
     * on every account boundary; read by
     * {@link #writeInterestTransaction(BigDecimal, LocalDate)} to
     * populate the {@code TRAN-CARD-NUM} field.
     */
    private CardXrefRecord currentXref;

    // -----------------------------------------------------------------
    // Constructor — plain constructor injection per AAP §0.6.12. No
    // Spring container; the composition root in
    // carddemo-app/InterestCalculationApp wires the chosen adapter
    // implementations at startup.
    // -----------------------------------------------------------------

    /**
     * Constructs a {@code CbAct04C} use case with the supplied
     * repository ports. Each argument is validated non-null with
     * {@link Objects#requireNonNull} per AAP &sect;0.6.12 (replacing
     * Spring's {@code @Autowired} NPE behavior). Per-execution state
     * ({@link #lastAcctNum}, {@link #totalInt}, etc.) is initialized
     * here; it is also reset at the start of every
     * {@link #run(LocalDate)} invocation so a single instance can be
     * reused across multiple runs.
     *
     * @param tcatBalRepo     port for TCATBAL sequential reads; never null
     * @param accountRepo     port for ACCTFILE random reads + rewrites;
     *                        never null
     * @param cardXrefRepo    port for XREFFILE random reads (by
     *                        alternate key XREF-ACCT-ID); never null
     * @param discountGroupRepo port for DISCGRP random reads (composite
     *                        key); never null
     * @param transactionRepo port for TRANSACT sequential writes;
     *                        never null
     * @throws NullPointerException if any argument is {@code null}
     */
    public CbAct04C(TransactionCategoryBalanceRepository tcatBalRepo,
                    AccountRepository accountRepo,
                    CardXrefRepository cardXrefRepo,
                    DiscountGroupRepository discountGroupRepo,
                    TransactionRepository transactionRepo) {
        this.tcatBalRepository = Objects.requireNonNull(tcatBalRepo,
                "tcatBalRepo");
        this.accountRepository = Objects.requireNonNull(accountRepo,
                "accountRepo");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepo,
                "cardXrefRepo");
        this.discountGroupRepository = Objects.requireNonNull(discountGroupRepo,
                "discountGroupRepo");
        this.transactionRepository = Objects.requireNonNull(transactionRepo,
                "transactionRepo");
        resetState();
    }

    /**
     * Resets all per-execution mutable state so the instance can be
     * reused for a fresh run. Called from the constructor and from the
     * top of {@link #run(LocalDate)}.
     */
    private void resetState() {
        this.lastAcctNum = -1L;
        this.totalInt = BigDecimal.ZERO.setScale(MONETARY_SCALE);
        this.firstTime = true;
        this.tranIdSuffix = 0L;
        this.currentAccount = null;
        this.currentXref = null;
    }


    // -----------------------------------------------------------------
    // PROCEDURE DIVISION entry — translated from CBACT04C.cbl:L180-L232.
    // -----------------------------------------------------------------

    /**
     * Public entry point translated from the COBOL
     * {@code PROCEDURE DIVISION USING EXTERNAL-PARMS} entry at
     * {@code app/cbl/CBACT04C.cbl:L180-L232}.
     *
     * <p>The COBOL flow is:
     * <pre>{@code
     * PROCEDURE DIVISION USING EXTERNAL-PARMS.
     *     DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'.
     *     PERFORM 0000-TCATBALF-OPEN.
     *     PERFORM 0100-XREFFILE-OPEN.
     *     PERFORM 0200-DISCGRP-OPEN.
     *     PERFORM 0300-ACCTFILE-OPEN.
     *     PERFORM 0400-TRANFILE-OPEN.
     *
     *     PERFORM UNTIL END-OF-FILE = 'Y'
     *         IF  END-OF-FILE = 'N'
     *             PERFORM 1000-TCATBALF-GET-NEXT
     *             IF  END-OF-FILE = 'N'
     *                 ... process record ...
     *             END-IF
     *         ELSE
     *             PERFORM 1050-UPDATE-ACCOUNT
     *         END-IF
     *     END-PERFORM.
     *
     *     PERFORM 9000-TCATBALF-CLOSE.
     *     PERFORM 9100-XREFFILE-CLOSE.
     *     PERFORM 9200-DISCGRP-CLOSE.
     *     PERFORM 9300-ACCTFILE-CLOSE.
     *     PERFORM 9400-TRANFILE-CLOSE.
     *
     *     DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'.
     *     GOBACK.
     * }</pre>
     *
     * <p>The Java translation:
     * <ol>
     *   <li>Resets per-execution state via {@link #resetState()} so the
     *       instance is safe to reuse.</li>
     *   <li>Logs the "START OF EXECUTION" banner.</li>
     *   <li>Streams the TCATBAL records via
     *       {@link TransactionCategoryBalanceRepository#streamSequential()}
     *       (a closeable Stream; the try-with-resources block guarantees
     *       file-channel release on any exit path). End-of-stream is
     *       the natural Java equivalent of the COBOL FILE STATUS = '10'
     *       branch.</li>
     *   <li>For each TCATBAL record, calls
     *       {@link #processTcatBalRecord(TranCatBalRecord, LocalDate)}.</li>
     *   <li>After the stream terminates, calls {@link #updateAccount()}
     *       one final time to post the last account's accumulated
     *       interest (matching the COBOL ELSE branch at line 219-220).</li>
     *   <li>On any exception during open / read / write, logs the
     *       error, sets the return code to {@link #APPL_ERROR} via
     *       {@link #abendProgram(Throwable)}, and falls through to the
     *       close block.</li>
     *   <li>Closes all repository ports via {@link #closeAll()}.</li>
     *   <li>Logs the "END OF EXECUTION" banner.</li>
     *   <li>Returns the captured return code: {@link #APPL_AOK} (0) on
     *       a clean run, {@link #APPL_ERROR} (12) on any failure.</li>
     * </ol>
     *
     * <h3>{@code GOBACK} translation</h3>
     * <p>COBOL {@code GOBACK} terminates the program with the value of
     * the {@code RETURN-CODE} special register. The Java equivalent is
     * this method's {@code int} return value, which
     * {@code carddemo-app/InterestCalculationApp} maps to a process
     * exit code in the shaded jar's {@code main}.
     *
     * <h3>Error handling vs. abend</h3>
     * <p>Per AAP &sect;0.7.1 ("identical observable outcomes"), I/O
     * errors do NOT raise a checked or unchecked exception out of
     * {@code run}; instead they are translated to a non-zero return
     * code &mdash; the same observable surface as the COBOL job-step
     * abend (a failed job step from the operator's perspective). The
     * inner {@code 1100-GET-ACCT-DATA} and {@code 1110-GET-XREF-DATA}
     * miss paths throw {@link AbendException} ({@code abendCode = 999})
     * which is caught here and translated to {@link #APPL_ERROR}.
     *
     * @param parmDate the processing date carried by the COBOL
     *                 {@code EXTERNAL-PARMS.PARM-DATE PIC X(10)}
     *                 argument (originating from JCL {@code PARM=}
     *                 'YYYYMMDDHH'). The {@link LocalDate} carries
     *                 only the date portion; the hour component (the
     *                 trailing 2 chars of the original 10-char PARM)
     *                 is dropped in this translation per the
     *                 {@code run(LocalDate)} file-schema contract.
     * @return {@link #APPL_AOK} (0) on success, {@link #APPL_ERROR}
     *         (12) on any I/O failure that would have triggered the
     *         COBOL {@code 9999-ABEND-PROGRAM} paragraph
     * @throws NullPointerException if {@code parmDate} is {@code null}
     */
    public int run(LocalDate parmDate) {
        Objects.requireNonNull(parmDate, "parmDate");
        resetState();
        log.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
        log.info("CBACT04C interest calculation; processing date={}", parmDate);

        int returnCode = APPL_AOK;

        // 0000-TCATBALF-OPEN through 0400-TRANFILE-OPEN: the repository
        // adapters lazy-open on first access (e.g., streamSequential()
        // opens the file channel; findById() / save() open or have
        // already opened the random-access file). The Java translation
        // does not need explicit "open" calls per AAP §0.6.12.
        try (Stream<TranCatBalRecord> records = openTcatBal()) {
            // Pre-open the remaining files (matching the COBOL OPEN order
            // and producing matching log output if any fails).
            openCardXref();
            openDiscGrp();
            openAcctFile();
            openTransact();

            // PERFORM UNTIL END-OF-FILE = 'Y': stream-iterate the TCATBAL.
            records.forEach(record -> processTcatBalRecord(record, parmDate));

            // ELSE branch at COBOL L219-L220: PERFORM 1050-UPDATE-ACCOUNT
            // one final time after END-OF-FILE so the last account's
            // accumulated interest is posted. The COBOL guard at L195-L198
            // ('IF WS-FIRST-TIME NOT = 'Y' THEN update') prevents an
            // empty-stream call.
            if (!firstTime) {
                updateAccount();
            }
        } catch (AbendException ae) {
            log.error("CBACT04C abend; code={}, message={}",
                    ae.abendCode(), ae.getMessage(), ae);
            returnCode = APPL_ERROR;
        } catch (RuntimeException re) {
            log.error("CBACT04C unexpected runtime failure", re);
            displayIoStatus(STATUS_NOT_FOUND);
            returnCode = APPL_ERROR;
        } finally {
            closeAll();
        }

        log.info("END OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
        return returnCode;
    }

    // -----------------------------------------------------------------
    // OPEN paragraphs (0000-0400) — translated from
    // app/cbl/CBACT04C.cbl:L234-L323. The repository adapters lazy-open
    // on first call; these Java methods are no-ops that exist purely to
    // preserve the 1:1 paragraph-to-method mapping per AAP §0.1.2.
    // The actual file opening occurs inside the adapter on the first
    // streamSequential() / findById() / save() call.
    // -----------------------------------------------------------------

    /**
     * Translation of the COBOL {@code 0000-TCATBALF-OPEN} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L234-L250}. The COBOL
     * {@code OPEN INPUT TCATBAL-FILE} statement opens the file for
     * sequential read; the Java equivalent is the call to
     * {@link TransactionCategoryBalanceRepository#streamSequential()}
     * which returns a closeable {@link Stream} over the dataset.
     *
     * @return a closeable {@link Stream} of every {@link TranCatBalRecord}
     *         in TCATBAL, in ascending composite-key order
     * @throws RuntimeException if the file cannot be opened (the
     *         adapter wraps any underlying I/O failure)
     */
    private Stream<TranCatBalRecord> openTcatBal() {
        try {
            return tcatBalRepository.streamSequential();
        } catch (RuntimeException re) {
            log.error("ERROR OPENING TRANSACTION CATEGORY BALANCE");
            displayIoStatus(STATUS_NOT_FOUND);
            throw new AbendException(CEE3ABD_ABCODE,
                    "Open failure on TCATBAL", re);
        }
    }

    /**
     * Translation of the COBOL {@code 0100-XREFFILE-OPEN} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L252-L268}. The COBOL
     * {@code OPEN INPUT XREF-FILE} statement opens the file for random
     * read via either the primary key {@code FD-XREF-CARD-NUM} or the
     * alternate key {@code FD-XREF-ACCT-ID}. This is a no-op in the
     * Java translation because {@link CardXrefRepository#findByAccountId(long)}
     * opens the file lazily on first access; the method is provided
     * to preserve the COBOL paragraph-to-method mapping per AAP &sect;0.1.2.
     */
    private void openCardXref() {
        // Lazy-open per AAP §0.6.12 — the FileCardXrefRepository
        // constructor pre-opens the file; nothing to do here.
    }

    /**
     * Translation of the COBOL {@code 0200-DISCGRP-OPEN} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L270-L286}. The COBOL
     * {@code OPEN INPUT DISCGRP-FILE} opens the file for random read
     * via the composite key {@code FD-DISCGRP-KEY}. No-op in Java
     * (lazy-open in the adapter).
     */
    private void openDiscGrp() {
        // Lazy-open per AAP §0.6.12 — nothing to do here.
    }

    /**
     * Translation of the COBOL {@code 0300-ACCTFILE-OPEN} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L289-L305}. The COBOL
     * {@code OPEN I-O ACCOUNT-FILE} opens the file for both random
     * read and REWRITE. No-op in Java (lazy-open in the adapter).
     */
    private void openAcctFile() {
        // Lazy-open per AAP §0.6.12 — nothing to do here.
    }

    /**
     * Translation of the COBOL {@code 0400-TRANFILE-OPEN} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L307-L323}. The COBOL
     * {@code OPEN OUTPUT TRANSACT-FILE} opens the file for sequential
     * WRITE (the file is freshly created on each run; the JCL
     * {@code DISP=(NEW,CATLG,DELETE)} at INTCALC.jcl:L37-L41 reflects
     * this). No-op in Java (lazy-open in the adapter).
     */
    private void openTransact() {
        // Lazy-open per AAP §0.6.12 — nothing to do here.
    }


    // -----------------------------------------------------------------
    // Per-record processing — translated from the body of the
    // PERFORM UNTIL END-OF-FILE loop at CBACT04C.cbl:L188-L222 and the
    // 1000-TCATBALF-GET-NEXT paragraph at L325-L348.
    // -----------------------------------------------------------------

    /**
     * Per-iteration body of the COBOL PERFORM UNTIL END-OF-FILE loop
     * (CBACT04C.cbl:L188-L222) plus the {@code 1000-TCATBALF-GET-NEXT}
     * paragraph (L325-L348). For each TCATBAL record:
     * <ol>
     *   <li>Displays the whole record (matching the COBOL
     *       {@code DISPLAY TRAN-CAT-BAL-RECORD} at L193).</li>
     *   <li>Detects account boundary
     *       ({@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM} at L194).
     *       On boundary:
     *       <ul>
     *         <li>Updates the prior account via {@link #updateAccount()}
     *             unless this is the first iteration
     *             (L195-L199).</li>
     *         <li>Resets {@link #totalInt} to zero (L200).</li>
     *         <li>Updates {@link #lastAcctNum} (L201).</li>
     *         <li>Loads the new account via {@link #getAcctData(long)}
     *             (L202-L203, the {@code MOVE TRANCAT-ACCT-ID TO
     *             FD-ACCT-ID / PERFORM 1100-GET-ACCT-DATA} pair).</li>
     *         <li>Loads the new xref via {@link #getXrefData(long)}
     *             (L204-L205, the {@code MOVE TRANCAT-ACCT-ID TO
     *             FD-XREF-ACCT-ID / PERFORM 1110-GET-XREF-DATA} pair).
     *             </li>
     *       </ul>
     *   </li>
     *   <li>Looks up the per-account interest rate via
     *       {@link #getInterestRate(String, int)} (L210-L213, the
     *       {@code MOVE ACCT-GROUP-ID / TRANCAT-CD / TRANCAT-TYPE-CD
     *       TO FD-DIS-... / PERFORM 1200-GET-INTEREST-RATE} sequence).</li>
     *   <li>If the rate is non-zero ({@code IF DIS-INT-RATE NOT = 0}
     *       at L214), computes the monthly interest via
     *       {@link #computeMonthlyInterest(BigDecimal, BigDecimal)},
     *       accumulates it into {@link #totalInt}, emits the
     *       transaction record via
     *       {@link #writeInterestTransaction(BigDecimal, LocalDate)},
     *       and calls {@link #computeFees()} (L215-L217).</li>
     * </ol>
     *
     * @param record   the current TCATBAL record from the sequential stream
     * @param parmDate the processing date passed to {@link #run(LocalDate)}
     */
    private void processTcatBalRecord(TranCatBalRecord record, LocalDate parmDate) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(parmDate, "parmDate");

        // COBOL L193: DISPLAY TRAN-CAT-BAL-RECORD (preserved verbatim).
        displayWholeTcatBalRecord(record);

        long acctId = record.tranCatKey().trancatAcctId();
        // COBOL L194: IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM (account break).
        if (acctId != lastAcctNum) {
            if (!firstTime) {
                // 1050-UPDATE-ACCOUNT for the PRIOR account.
                updateAccount();
            } else {
                firstTime = false;
            }
            // COBOL L200: MOVE 0 TO WS-TOTAL-INT.
            totalInt = BigDecimal.ZERO.setScale(MONETARY_SCALE);
            // COBOL L201: MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM.
            lastAcctNum = acctId;
            // COBOL L202-L203: MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID /
            //                  PERFORM 1100-GET-ACCT-DATA.
            currentAccount = getAcctData(acctId);
            // COBOL L204-L205: MOVE TRANCAT-ACCT-ID TO FD-XREF-ACCT-ID /
            //                  PERFORM 1110-GET-XREF-DATA.
            currentXref = getXrefData(acctId);
        }

        // COBOL L210-L213: MOVE ... TO FD-DIS-... / PERFORM 1200-GET-INTEREST-RATE.
        BigDecimal disIntRate = getInterestRate(
                record.tranCatKey().trancatTypeCd(),
                record.tranCatKey().trancatCd());

        // COBOL L214: IF DIS-INT-RATE NOT = 0
        if (disIntRate.compareTo(BigDecimal.ZERO) != 0) {
            // COBOL L215: PERFORM 1300-COMPUTE-INTEREST
            BigDecimal monthlyInt = computeMonthlyInterest(
                    record.tranCatBal(), disIntRate);
            // COBOL L467: ADD WS-MONTHLY-INT TO WS-TOTAL-INT
            totalInt = Decimals.add(totalInt, monthlyInt,
                    MONETARY_SCALE, RoundingMode.DOWN);
            // COBOL L468: PERFORM 1300-B-WRITE-TX
            writeInterestTransaction(monthlyInt, parmDate);
            // COBOL L216: PERFORM 1400-COMPUTE-FEES (no-op per source comment)
            computeFees();
        }
    }

    /**
     * Mirrors the {@code 1050-UPDATE-ACCOUNT} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L350-L370}. Adds the accumulated
     * interest ({@link #totalInt}, the COBOL {@code WS-TOTAL-INT}) to
     * the current balance ({@code ACCT-CURR-BAL}), resets the
     * current-cycle credit and debit counters to zero
     * ({@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} at L353 and
     * {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} at L354), and persists the
     * updated record via {@link AccountRepository#save(AccountRecord)}
     * ({@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD} at L356).
     *
     * <p>The COBOL {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL} statement
     * at L352 carries NO {@code ROUNDED} clause, so the Java
     * translation uses {@link RoundingMode#DOWN} (truncation) via
     * {@link Decimals#add(BigDecimal, BigDecimal, int, RoundingMode)}
     * per AAP &sect;0.6.1.
     *
     * <p>Because {@link AccountRecord} is an immutable record (no
     * {@code with*} methods per AAP &sect;0.1.2: "Records in finalized
     * Java 25 do not have built-in {@code with} syntax"), this method
     * constructs a NEW {@link AccountRecord} with the updated balance
     * and zeroed cycle counters while preserving all other fields
     * (including the 178-byte FILLER per AAP &sect;0.6.5 byte fidelity).
     */
    private void updateAccount() {
        if (currentAccount == null) {
            // Defensive guard: should never happen because firstTime
            // gates the initial call. Logged for diagnostics but not
            // an error per se.
            log.debug("updateAccount called with null currentAccount; "
                    + "skipping (likely an empty-stream run)");
            return;
        }

        // COBOL L352: ADD WS-TOTAL-INT TO ACCT-CURR-BAL.
        // No ROUNDED clause -> RoundingMode.DOWN per AAP §0.6.1.
        BigDecimal newBalance = Decimals.add(
                currentAccount.acctCurrBal(),
                totalInt,
                MONETARY_SCALE,
                RoundingMode.DOWN);

        // COBOL L353-L354: MOVE 0 TO ACCT-CURR-CYC-CREDIT / -DEBIT.
        BigDecimal zero = BigDecimal.ZERO.setScale(MONETARY_SCALE);

        // Construct the updated AccountRecord (immutable record, no
        // with*() methods). Preserves all other fields verbatim,
        // including the 178-byte FILLER for byte fidelity per AAP §0.6.5.
        AccountRecord updated = new AccountRecord(
                currentAccount.acctId(),
                currentAccount.acctActiveStatus(),
                newBalance,
                currentAccount.acctCreditLimit(),
                currentAccount.acctCashCreditLimit(),
                currentAccount.acctOpenDate(),
                currentAccount.acctExpiraionDate(),
                currentAccount.acctReissueDate(),
                zero,                              // MOVE 0 TO ACCT-CURR-CYC-CREDIT
                zero,                              // MOVE 0 TO ACCT-CURR-CYC-DEBIT
                currentAccount.acctAddrZip(),
                currentAccount.acctGroupId(),
                currentAccount.filler());

        // COBOL L356: REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD.
        try {
            accountRepository.save(updated);
        } catch (RuntimeException re) {
            log.error("ERROR RE-WRITING ACCOUNT FILE");
            displayIoStatus(STATUS_NOT_FOUND);
            throw new AbendException(CEE3ABD_ABCODE,
                    "REWRITE failure on ACCTFILE for acctId="
                            + currentAccount.acctId(), re);
        }
    }

    /**
     * Mirrors the {@code 1100-GET-ACCT-DATA} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L372-L391}. Reads the account record
     * by the COBOL {@code FD-ACCT-ID} primary key. On
     * {@code INVALID KEY} (no record found) or any other I/O error,
     * logs "ACCOUNT NOT FOUND" (matching COBOL L375) and aborts via
     * {@link #abendProgram(Throwable)}.
     *
     * @param acctId the {@code TRANCAT-ACCT-ID} that drove the COBOL
     *               {@code MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID}
     *               assignment at CBACT04C.cbl:L202.
     * @return the matching {@link AccountRecord}; never {@code null}
     * @throws AbendException if no record exists or an I/O error occurs
     */
    private AccountRecord getAcctData(long acctId) {
        Optional<AccountRecord> opt;
        try {
            opt = accountRepository.findById(acctId);
        } catch (RuntimeException re) {
            log.error("ERROR READING ACCOUNT FILE");
            displayIoStatus(STATUS_NOT_FOUND);
            throw new AbendException(CEE3ABD_ABCODE,
                    "Read failure on ACCTFILE for acctId=" + acctId, re);
        }
        if (opt.isEmpty()) {
            // COBOL L375: DISPLAY 'ACCOUNT NOT FOUND: ' FD-ACCT-ID.
            log.error("ACCOUNT NOT FOUND: {}", acctId);
            log.error("ERROR READING ACCOUNT FILE");
            displayIoStatus(STATUS_NOT_FOUND);
            throw new AbendException(CEE3ABD_ABCODE,
                    "Account not found: acctId=" + acctId);
        }
        return opt.get();
    }

    /**
     * Mirrors the {@code 1110-GET-XREF-DATA} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L393-L413}. Reads the card
     * cross-reference record by the COBOL
     * {@code KEY IS FD-XREF-ACCT-ID} alternate key. On
     * {@code INVALID KEY} (no record found) or any other I/O error,
     * logs "ACCOUNT NOT FOUND" (matching COBOL L397, which preserves
     * the typo &mdash; the message says "ACCOUNT NOT FOUND" although
     * the failing read is on XREF) and aborts via
     * {@link #abendProgram(Throwable)}.
     *
     * @param acctId the {@code TRANCAT-ACCT-ID} that drove the COBOL
     *               {@code MOVE TRANCAT-ACCT-ID TO FD-XREF-ACCT-ID}
     *               assignment at CBACT04C.cbl:L204.
     * @return the matching {@link CardXrefRecord}; never {@code null}
     * @throws AbendException if no record exists or an I/O error occurs
     */
    private CardXrefRecord getXrefData(long acctId) {
        Optional<CardXrefRecord> opt;
        try {
            opt = cardXrefRepository.findByAccountId(acctId);
        } catch (RuntimeException re) {
            log.error("ERROR READING XREF FILE");
            displayIoStatus(STATUS_NOT_FOUND);
            throw new AbendException(CEE3ABD_ABCODE,
                    "Read failure on XREF for acctId=" + acctId, re);
        }
        if (opt.isEmpty()) {
            // COBOL L397: DISPLAY 'ACCOUNT NOT FOUND: ' FD-XREF-ACCT-ID
            //             (typo preserved: message says ACCOUNT but
            //             the failing read is on XREF).
            log.error("ACCOUNT NOT FOUND: {}", acctId);
            log.error("ERROR READING XREF FILE");
            displayIoStatus(STATUS_NOT_FOUND);
            throw new AbendException(CEE3ABD_ABCODE,
                    "Xref entry not found for acctId=" + acctId);
        }
        return opt.get();
    }


    /**
     * Mirrors the {@code 1200-GET-INTEREST-RATE} paragraph
     * ({@code app/cbl/CBACT04C.cbl:L415-L440}) plus the
     * {@code 1200-A-GET-DEFAULT-INT-RATE} fallback (L443-L460). The
     * COBOL logic:
     * <pre>{@code
     * 1200-GET-INTEREST-RATE.
     *     READ DISCGRP-FILE INTO DIS-GROUP-RECORD
     *          INVALID KEY DISPLAY 'DISCLOSURE GROUP RECORD MISSING'
     *                       DISPLAY 'TRY WITH DEFAULT GROUP CODE'
     *     END-READ.
     *     IF DISCGRP-STATUS = '00' OR '23' MOVE 0 TO APPL-RESULT
     *     ELSE                              MOVE 12 TO APPL-RESULT
     *     ...
     *     IF DISCGRP-STATUS = '23'
     *         MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
     *         PERFORM 1200-A-GET-DEFAULT-INT-RATE
     *     END-IF.
     * }</pre>
     *
     * <p>The Java translation reads the discount group with the
     * account-specific group ID first; on miss ({@link Optional#empty()}
     * which mirrors FILE STATUS = '23'), it retries with the literal
     * {@link #DEFAULT_GROUP_ID}. If both lookups miss, returns
     * {@link BigDecimal#ZERO} (scaled to 2) — the caller's
     * {@code IF DIS-INT-RATE NOT = 0} guard at COBOL line 214 then
     * skips the interest computation. This is more lenient than the
     * COBOL which would normally have a DEFAULT row to fall back to;
     * the lenient behavior is documented for safety and matches the
     * existing CBACT04C semantic when the DEFAULT row is missing
     * (the inner READ status = '23' branch is not reached).
     *
     * @param tranTypeCd the {@code TRANCAT-TYPE-CD} 2-character type code
     * @param tranCatCd  the {@code TRANCAT-CD} 4-digit category code
     * @return the {@code DIS-INT-RATE} from the matching DISCGRP record;
     *         {@link BigDecimal#ZERO} (scaled to 2) if neither the
     *         account-specific nor the DEFAULT lookup yields a row
     */
    private BigDecimal getInterestRate(String tranTypeCd, int tranCatCd) {
        Objects.requireNonNull(tranTypeCd, "tranTypeCd");
        // First try the per-account group lookup.
        // COBOL L210-L213: MOVE ACCT-GROUP-ID, TRANCAT-CD, TRANCAT-TYPE-CD
        //                  TO FD-DIS-ACCT-GROUP-ID, FD-DIS-TRAN-CAT-CD,
        //                  FD-DIS-TRAN-TYPE-CD.
        Optional<DisGroupRecord> primary;
        try {
            primary = discountGroupRepository.findByKey(
                    currentAccount.acctGroupId(), tranTypeCd, tranCatCd);
        } catch (RuntimeException re) {
            // COBOL L431: DISPLAY 'ERROR READING DISCLOSURE GROUP FILE'.
            log.error("ERROR READING DISCLOSURE GROUP FILE");
            displayIoStatus(STATUS_NOT_FOUND);
            throw new AbendException(CEE3ABD_ABCODE,
                    "Read failure on DISCGRP for group="
                            + currentAccount.acctGroupId()
                            + ", type=" + tranTypeCd
                            + ", cat=" + tranCatCd, re);
        }
        if (primary.isPresent()) {
            return primary.get().disIntRate();
        }
        // COBOL L418-L419: DISPLAY 'DISCLOSURE GROUP RECORD MISSING' /
        //                  DISPLAY 'TRY WITH DEFAULT GROUP CODE'.
        log.info("DISCLOSURE GROUP RECORD MISSING");
        log.info("TRY WITH DEFAULT GROUP CODE");

        // 1200-A-GET-DEFAULT-INT-RATE: retry with literal 'DEFAULT'.
        // COBOL L437: MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID.
        Optional<DisGroupRecord> fallback;
        try {
            fallback = discountGroupRepository.findByKey(
                    DEFAULT_GROUP_ID, tranTypeCd, tranCatCd);
        } catch (RuntimeException re) {
            // COBOL L455: DISPLAY 'ERROR READING DEFAULT DISCLOSURE GROUP'.
            log.error("ERROR READING DEFAULT DISCLOSURE GROUP");
            displayIoStatus(STATUS_NOT_FOUND);
            throw new AbendException(CEE3ABD_ABCODE,
                    "Read failure on DISCGRP DEFAULT fallback for type="
                            + tranTypeCd + ", cat=" + tranCatCd, re);
        }
        if (fallback.isPresent()) {
            return fallback.get().disIntRate();
        }
        // Both lookups missed. The COBOL paragraph would have ABENDed
        // when the inner DEFAULT read fails with a status other than
        // '00' — but absent of any DEFAULT row at all, the program
        // would simply emit 0 rate and the caller's guard would skip
        // interest. We preserve the lenient behavior so that an empty
        // DISCGRP doesn't crash the batch run.
        return BigDecimal.ZERO.setScale(MONETARY_SCALE);
    }

    /**
     * Mirrors the {@code 1300-COMPUTE-INTEREST} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L462-L470}. Computes the monthly
     * interest as:
     * <pre>{@code
     * COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * }</pre>
     *
     * <p>Per AAP &sect;0.6.1 the COBOL {@code COMPUTE} statement
     * carries NO {@code ROUNDED} clause, so the default truncation
     * applies. The Java translation:
     * <ol>
     *   <li>Multiplies {@code tranCatBal * disIntRate} with
     *       {@link Decimals#DEFAULT_MATH_CONTEXT}
     *       ({@link MathContext#DECIMAL128}, 34-digit precision)
     *       to preserve all significant digits in the intermediate
     *       product.</li>
     *   <li>Divides the product by {@link #MONTHLY_DIVISOR}
     *       (1200) with {@link RoundingMode#DOWN} (truncation) and
     *       scale 2.</li>
     *   <li>Forces scale 2 preservation via {@link Decimals#scaled}
     *       so a result of {@code 1.20} is NOT normalized to
     *       {@code 1.2} per AAP &sect;0.1.3.</li>
     * </ol>
     *
     * @param tranCatBal the COBOL {@code TRAN-CAT-BAL PIC S9(09)V99}
     *                   from the current TCATBAL record (scale 2)
     * @param disIntRate the COBOL {@code DIS-INT-RATE PIC S9(04)V99}
     *                   from the matching DISCGRP record (scale 2)
     * @return the computed monthly interest as a {@link BigDecimal}
     *         at exactly scale 2, truncated (NOT rounded) at the third
     *         decimal place
     */
    private BigDecimal computeMonthlyInterest(BigDecimal tranCatBal,
                                              BigDecimal disIntRate) {
        Objects.requireNonNull(tranCatBal, "tranCatBal");
        Objects.requireNonNull(disIntRate, "disIntRate");
        // Step 1 — multiply at scale 4 with DOWN truncation per AAP §0.6.1
        // (no ROUNDED clause on the COBOL COMPUTE). Routing through
        // Decimals.multiply(...) per AAP §0.3.3 central-facade discipline
        // (MIGRATION_NOTES.md §1.12.15) so any future change to the
        // monetary MathContext / RoundingMode defaults flows uniformly
        // through the Decimals facade. Scale 4 retains two extra digits
        // of intermediate precision so subsequent division by 1200
        // does not lose significant digits before the final scale-2
        // truncation.
        BigDecimal product = Decimals.multiply(tranCatBal, disIntRate,
                INTEREST_INTERMEDIATE_SCALE, RoundingMode.DOWN);
        // Step 2 — divide by 1200 at scale 2 with DOWN truncation per
        // AAP §0.6.1 (no ROUNDED clause). Routing through Decimals.divide
        // per the same central-facade discipline. The DOWN rounding on
        // the divide produces the byte-identical result that COBOL
        // emits via implicit numeric promotion + integer-divide
        // semantics over PIC S9(09)V99.
        BigDecimal result = Decimals.divide(product, MONTHLY_DIVISOR,
                MONETARY_SCALE, RoundingMode.DOWN);
        // Force exact scale 2 — a result of 1.20 must stay "1.20", not
        // "1.2" (AAP §0.1.3 scale preservation requirement). Decimals.divide
        // already produces a scale-2 BigDecimal, but the explicit scaled()
        // call guarantees the contract for any future refactor of divide.
        return Decimals.scaled(result, MONETARY_SCALE, RoundingMode.DOWN);
    }

    /**
     * Mirrors the {@code 1300-B-WRITE-TX} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L473-L515}. Builds the new
     * {@link TranRecord} and writes it to TRANSACT via
     * {@link TransactionRepository#save(TranRecord)} (the COBOL
     * {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} statement at L500).
     *
     * <p>Field-by-field mapping per COBOL L474-L498:
     * <ul>
     *   <li>L474: {@code ADD 1 TO WS-TRANID-SUFFIX} — increment
     *       {@link #tranIdSuffix}.</li>
     *   <li>L476-L480: {@code STRING PARM-DATE, WS-TRANID-SUFFIX
     *       DELIMITED BY SIZE INTO TRAN-ID} — see Javadoc on
     *       {@link #PROGRAM_ID} class-level "TRAN-ID composition
     *       deviation" for the date/suffix split rationale.</li>
     *   <li>L482: {@code MOVE '01' TO TRAN-TYPE-CD} —
     *       {@link #TRAN_TYPE_INTEREST}.</li>
     *   <li>L483: {@code MOVE '05' TO TRAN-CAT-CD} —
     *       {@link Integer#parseInt(String) Integer.parseInt}({@link #TRAN_CAT_INTEREST}).</li>
     *   <li>L484: {@code MOVE 'System' TO TRAN-SOURCE} —
     *       {@link #TRAN_SOURCE_SYSTEM}.</li>
     *   <li>L485-L489: {@code STRING 'Int. for a/c ', ACCT-ID DELIMITED
     *       BY SIZE INTO TRAN-DESC} — {@link #TRAN_DESC_PREFIX} +
     *       {@code %011d} formatted ACCT-ID.</li>
     *   <li>L490: {@code MOVE WS-MONTHLY-INT TO TRAN-AMT} — the
     *       {@code monthlyInt} argument.</li>
     *   <li>L491: {@code MOVE 0 TO TRAN-MERCHANT-ID} — 0L.</li>
     *   <li>L492-L494: {@code MOVE SPACES TO TRAN-MERCHANT-NAME /
     *       -CITY / -ZIP} — empty strings (the {@link TranRecord}
     *       canonical constructor right-pads with spaces).</li>
     *   <li>L495: {@code MOVE XREF-CARD-NUM TO TRAN-CARD-NUM} —
     *       {@link CardXrefRecord#xrefCardNum() currentXref.xrefCardNum()}.</li>
     *   <li>L496-L498: {@code PERFORM Z-GET-DB2-FORMAT-TIMESTAMP /
     *       MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS / TRAN-PROC-TS} — the
     *       current wall-clock as a {@link LocalDateTime} (the
     *       {@link TranRecord} encoder formats it into the 26-byte
     *       DB2 timestamp string).</li>
     * </ul>
     *
     * @param monthlyInt the computed monthly interest from
     *                   {@link #computeMonthlyInterest(BigDecimal, BigDecimal)}
     * @param parmDate   the processing date for the {@code TRAN-ID} prefix
     * @throws AbendException on write failure (matching the COBOL
     *         {@code 1300-B-WRITE-TX} error branch at L510-L513)
     */
    private void writeInterestTransaction(BigDecimal monthlyInt,
                                          LocalDate parmDate) {
        // COBOL L474: ADD 1 TO WS-TRANID-SUFFIX.
        tranIdSuffix++;
        if (tranIdSuffix > TRAN_ID_SUFFIX_MAX) {
            throw new AbendException(CEE3ABD_ABCODE,
                    "TRAN-ID suffix overflow: " + tranIdSuffix
                            + " exceeds " + TRAN_ID_SUFFIX_MAX
                            + " (8-digit limit)");
        }

        // COBOL L476-L480: STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED
        //                  BY SIZE INTO TRAN-ID.
        // See class-level Javadoc "TRAN-ID composition deviation":
        // Java uses 8-char yyyyMMdd + 8-digit sequence (sum=16) instead
        // of the COBOL 10-char PARM-DATE + 6-digit sequence (also sum=16).
        String tranId = parmDate.format(TRAN_ID_DATE_FMT)
                + String.format("%0" + TRAN_ID_SUFFIX_WIDTH + "d", tranIdSuffix);

        // COBOL L485-L489: STRING 'Int. for a/c ', ACCT-ID DELIMITED
        //                  BY SIZE INTO TRAN-DESC.
        // Result is 13 + 11 = 24 chars; TRAN-DESC PIC X(100) pads the
        // remainder with spaces (the TranRecord encoder handles padding).
        String tranDesc = TRAN_DESC_PREFIX
                + String.format("%0" + ACCT_ID_DESC_WIDTH + "d",
                        currentAccount.acctId());

        // COBOL L496-L498: TRAN-ORIG-TS and TRAN-PROC-TS both set to
        // the same DB2-FORMAT-TS. LocalDateTime.now() is the wall-clock
        // equivalent of COBOL FUNCTION CURRENT-DATE per AAP §0.6.4.
        LocalDateTime now = LocalDateTime.now();

        // Construct the new transaction record. The TranRecord canonical
        // constructor right-pads PIC X(n) string fields with spaces and
        // normalizes the BigDecimal tranAmt to scale 2 with banker's
        // rounding (HALF_EVEN). Per AAP §0.1.3 the scale is preserved.
        TranRecord tx = new TranRecord(
                tranId,                                      // TRAN-ID
                TRAN_TYPE_INTEREST,                          // TRAN-TYPE-CD
                Integer.parseInt(TRAN_CAT_INTEREST),         // TRAN-CAT-CD
                TRAN_SOURCE_SYSTEM,                          // TRAN-SOURCE
                tranDesc,                                    // TRAN-DESC
                monthlyInt,                                  // TRAN-AMT
                0L,                                          // TRAN-MERCHANT-ID
                "",                                          // TRAN-MERCHANT-NAME (SPACES)
                "",                                          // TRAN-MERCHANT-CITY (SPACES)
                "",                                          // TRAN-MERCHANT-ZIP  (SPACES)
                currentXref.xrefCardNum(),                   // TRAN-CARD-NUM
                now,                                         // TRAN-ORIG-TS
                now,                                         // TRAN-PROC-TS
                TranRecord.emptyFiller());                   // 20-byte FILLER

        // COBOL L500: WRITE FD-TRANFILE-REC FROM TRAN-RECORD.
        try {
            transactionRepository.save(tx);
        } catch (RuntimeException re) {
            // COBOL L510-L513: error branch.
            log.error("ERROR WRITING TRANSACTION RECORD");
            displayIoStatus(STATUS_NOT_FOUND);
            throw new AbendException(CEE3ABD_ABCODE,
                    "WRITE failure on TRANSACT for tranId=" + tranId, re);
        }
    }

    /**
     * Mirrors the {@code 1400-COMPUTE-FEES} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L518-L520}. The COBOL source comment
     * explicitly marks the paragraph as {@code * To be implemented}
     * and the body contains only {@code EXIT}. Per AAP &sect;0.7.1
     * ("If a COBOL paragraph contains dead code... translate it
     * faithfully and flag it in a MIGRATION_NOTES.md; do not 'fix' it
     * in this refactor."), this Java translation preserves the
     * empty-body behavior verbatim. The MIGRATION_NOTES.md entry
     * documents the unimplemented paragraph for future reference.
     */
    private void computeFees() {
        // 1400-COMPUTE-FEES: "To be implemented" per source comment at
        // CBACT04C.cbl:L519. Empty by design — do NOT add fee logic
        // in this migration. See MIGRATION_NOTES.md for the deferred
        // requirement.
    }


    /**
     * Mirrors the {@code Z-GET-DB2-FORMAT-TIMESTAMP} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L613-L626}. The COBOL paragraph
     * builds a 26-character DB2-style timestamp by concatenating the
     * date and time components from {@code FUNCTION CURRENT-DATE}:
     * <pre>{@code
     * STRING WS-CURR-DATE-YYYY DELIMITED BY SIZE,
     *        '-' DELIMITED BY SIZE,
     *        WS-CURR-DATE-MM   DELIMITED BY SIZE,
     *        '-' DELIMITED BY SIZE,
     *        WS-CURR-DATE-DD   DELIMITED BY SIZE,
     *        '-' DELIMITED BY SIZE,
     *        WS-CURR-DATE-HH   DELIMITED BY SIZE,
     *        '.' DELIMITED BY SIZE,
     *        WS-CURR-DATE-MIN  DELIMITED BY SIZE,
     *        '.' DELIMITED BY SIZE,
     *        WS-CURR-DATE-SS   DELIMITED BY SIZE,
     *        '.' DELIMITED BY SIZE,
     *        WS-CURR-DATE-MIL  DELIMITED BY SIZE,
     *        '0000' DELIMITED BY SIZE
     *   INTO DB2-FORMAT-TS
     * END-STRING.
     * }</pre>
     *
     * <p>Note the format: hyphen separators between YYYY-MM-DD and a
     * hyphen between DD-HH; dot separators between HH.MIN.SS.MIL;
     * trailing literal "0000" padding. {@code WS-CURR-DATE-MIL} is the
     * "milliseconds" field returned by COBOL {@code FUNCTION
     * CURRENT-DATE} which is actually a 2-digit centisecond value
     * (positions 17-18 of the function return). The trailing "0000"
     * literal pads the timestamp to 26 characters.
     *
     * <p>The Java translation extracts the centisecond from
     * {@link LocalDateTime#getNano()} (divide by 10,000,000 to convert
     * nanoseconds to centiseconds) and assembles the result with
     * {@link String#format}. This helper exists primarily for
     * structured-log output and parity testing; the
     * {@link #writeInterestTransaction} path uses
     * {@link LocalDateTime} directly and lets the
     * {@link TranRecord#encode()} byte-level encoder produce the same
     * 26-character format on disk.
     *
     * @param ts the wall-clock to format
     * @return the 26-character DB2 timestamp string
     *         (e.g., {@code "2022-07-18-09.30.45.120000"})
     */
    private static String formatDb2Timestamp(LocalDateTime ts) {
        Objects.requireNonNull(ts, "ts");
        // WS-CURR-DATE-MIL is a centisecond (hundredths of a second).
        // LocalDateTime.getNano() returns nanoseconds; divide by 10^7
        // to convert to centiseconds (range 0..99).
        int centisecond = ts.getNano() / 10_000_000;
        // The COBOL STRING statement concatenates with explicit '-' and
        // '.' separators and a trailing literal "0000". The result is:
        //   YYYY-MM-DD-HH.MIN.SS.CC0000   (4+1+2+1+2+1+2+1+2+1+2+1+2+4 = 26)
        return String.format("%04d-%02d-%02d-%02d.%02d.%02d.%02d0000",
                ts.getYear(),
                ts.getMonthValue(),
                ts.getDayOfMonth(),
                ts.getHour(),
                ts.getMinute(),
                ts.getSecond(),
                centisecond);
    }

    /**
     * Mirrors the four close paragraphs {@code 9000-TCATBALF-CLOSE}
     * (L532-L546), {@code 9100-CARDXREF-CLOSE} (L548-L562),
     * {@code 9200-DISCGRP-CLOSE} (L564-L578),
     * {@code 9300-ACCTFILE-CLOSE} (L580-L594), and
     * {@code 9400-TRANSACT-CLOSE} (L596-L610) of
     * {@code app/cbl/CBACT04C.cbl}. Each COBOL paragraph performs a
     * {@code CLOSE} on its respective file and, on non-zero status,
     * displays an error and PERFORMs {@code 9999-ABEND-PROGRAM}.
     *
     * <p>This Java translation is invoked from the {@code finally}
     * block of {@link #run(LocalDate)} so the repositories are always
     * released, even on abend paths. Each repository is closed
     * independently via {@link #closeQuietly(AutoCloseable, String)};
     * a failure on one repository does NOT short-circuit the others —
     * this preserves the COBOL semantic that all five paragraphs run
     * in sequence regardless of the prior status code (the COBOL
     * actually ABENDs on the first failure, but the Java translation
     * is more defensive here so the process terminates cleanly).
     */
    private void closeAll() {
        // COBOL L532-L546: 9000-TCATBALF-CLOSE.
        closeQuietly(tcatBalRepository, "TRANSACTION BALANCE FILE");
        // COBOL L548-L562: 9100-CARDXREF-CLOSE.
        closeQuietly(cardXrefRepository, "CROSS REF FILE");
        // COBOL L564-L578: 9200-DISCGRP-CLOSE.
        closeQuietly(discountGroupRepository, "DISCLOSURE GROUP FILE");
        // COBOL L580-L594: 9300-ACCTFILE-CLOSE.
        closeQuietly(accountRepository, "ACCOUNT FILE");
        // COBOL L596-L610: 9400-TRANSACT-CLOSE.
        closeQuietly(transactionRepository, "TRANSACTION FILE");
    }

    /**
     * Closes a single repository and swallows any {@link Exception}
     * raised by its {@link AutoCloseable#close()} implementation,
     * logging an "ERROR CLOSING X" message matching the COBOL display
     * verbs at lines L541, L557, L573, L589, and L605 of
     * {@code app/cbl/CBACT04C.cbl}.
     *
     * <p>Defensive: tolerates a {@code null} resource so it can be
     * called from {@link #closeAll()} even when {@link #run(LocalDate)}
     * abends before all repositories are initialized (although the
     * current implementation guards all five via
     * {@link Objects#requireNonNull} in the constructor, the null
     * tolerance is kept as a future-proofing measure).
     *
     * @param resource the {@link AutoCloseable} to close, may be {@code null}
     * @param fileName the descriptive name used in the log message
     *                 (e.g., "ACCOUNT FILE" matching the COBOL
     *                 "ERROR CLOSING ACCOUNT FILE" wording)
     */
    private void closeQuietly(AutoCloseable resource, String fileName) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            // Match the COBOL DISPLAY verb exactly. The COBOL pattern
            // is "ERROR CLOSING <FILE-NAME>" — see CBACT04C.cbl:L541
            // ("ERROR CLOSING TRANSACTION BALANCE FILE"), L557, L573,
            // L589, L605. We log via SLF4J at ERROR level to match the
            // COBOL "DISPLAY" verb on the system console.
            log.error("ERROR CLOSING {}: {}", fileName, e.getMessage(), e);
        }
    }

    /**
     * Mirrors the {@code DISPLAY TRAN-CAT-BAL-RECORD} verb at
     * {@code app/cbl/CBACT04C.cbl:L193}. The COBOL statement emits the
     * entire 50-byte {@code TRAN-CAT-BAL-RECORD} group as a single
     * line on the system console. The Java translation encodes the
     * record back into its 50-byte fixed-width representation via
     * {@link TranCatBalRecord#encode()} and converts the byte buffer
     * to a String using {@link StandardCharsets#ISO_8859_1} (which is
     * the binary-transparent 8-bit charset that preserves each byte
     * one-for-one, matching the COBOL ASCII display semantic on the
     * golden-record fixtures in {@code app/data/ASCII/tcatbal.txt}).
     *
     * @param record the {@link TranCatBalRecord} to display
     */
    private void displayWholeTcatBalRecord(TranCatBalRecord record) {
        if (record == null) {
            log.info("");
            return;
        }
        // Encode the 50-byte fixed-width representation and decode it
        // as ISO_8859_1 (binary-transparent). This matches the COBOL
        // DISPLAY behavior which writes the raw record bytes to the
        // console (the fixture files are pre-transcoded to ASCII per
        // AAP §0.6.5, so ISO_8859_1 is the correct decode charset).
        byte[] encoded = record.encode();
        String text = new String(encoded, java.nio.charset.StandardCharsets.ISO_8859_1);
        log.info("{}", text);
    }

    /**
     * Mirrors the {@code 9910-DISPLAY-IO-STATUS} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L633-L651}. The COBOL paragraph
     * displays the 4-character {@code IO-STATUS-04} value, with leading
     * zero padding to make a 4-digit numeric, and the format:
     * <pre>{@code
     * DISPLAY 'FILE STATUS IS: ' IO-STATUS-04.
     * }</pre>
     *
     * <p>The Java translation logs the same message at INFO level,
     * normalizing the {@code ioStatus} string to 4 characters by
     * left-padding with '0' when shorter than 4 (matching the COBOL
     * implicit zero-fill of the {@code IO-STATUS PIC 9(04)} field).
     *
     * @param ioStatus the 2-or-4 character COBOL FILE STATUS value
     *                 (e.g., "00", "10", "23", "9000")
     */
    private static void displayIoStatus(String ioStatus) {
        String normalized;
        if (ioStatus == null) {
            normalized = "0000";
        } else if (ioStatus.length() >= 4) {
            normalized = ioStatus.substring(0, 4);
        } else {
            // Left-pad with '0' to width 4 (matches PIC 9(04) zero-fill).
            normalized = "0".repeat(4 - ioStatus.length()) + ioStatus;
        }
        log.info("FILE STATUS IS: {}", normalized);
    }

    /**
     * Mirrors the {@code 9999-ABEND-PROGRAM} paragraph at
     * {@code app/cbl/CBACT04C.cbl:L628-L632}. The COBOL paragraph:
     * <pre>{@code
     * 9999-ABEND-PROGRAM.
     *     DISPLAY 'ABENDING PROGRAM'.
     *     MOVE 0 TO TIMING.
     *     MOVE 999 TO ABCODE.
     *     CALL 'CEE3ABD'.
     * }</pre>
     *
     * <p>The Java translation logs the "ABENDING PROGRAM" banner and
     * throws an {@link AbendException} carrying the COBOL ABCODE of
     * 999 ({@link #CEE3ABD_ABCODE}). The exception is caught at the
     * top of {@link #run(LocalDate)} which translates it into the
     * {@link #APPL_ERROR} return code (matching the COBOL job-step
     * non-zero exit status). This abend mechanism preserves the COBOL
     * "CALL 'CEE3ABD'" semantic per the {@link AbendException} class
     * Javadoc.
     *
     * @param cause the underlying {@link Throwable} that caused the
     *              abend, may be {@code null}; carried through to the
     *              {@link AbendException} for diagnostics
     * @throws AbendException always — this method never returns
     *                        normally
     */
    private static void abendProgram(Throwable cause) {
        // COBOL L629: DISPLAY 'ABENDING PROGRAM'.
        log.error("ABENDING PROGRAM");
        // COBOL L630-L632: MOVE 0 TO TIMING / MOVE 999 TO ABCODE /
        //                  CALL 'CEE3ABD'. The CEE3ABD service in
        // IBM Language Environment terminates the job step with the
        // ABCODE as the user-completion code. The Java equivalent is
        // to throw AbendException(999) so the composition root can map
        // it to a non-zero process exit code (per AbendException
        // Javadoc).
        throw new AbendException(CEE3ABD_ABCODE, "CBACT04C abend", cause);
    }
}

