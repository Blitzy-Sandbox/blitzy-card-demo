/*
 * ******************************************************************
 * Program     : AccountReader.java
 * Application : CardDemo
 * Type        : Spring Batch ItemStreamReader (read-only verification step)
 * Function    : Read-only sequential scan of the account master, replacing
 *               the COBOL batch reader CBACT01C.
 * Source      : app/cbl/CBACT01C.cbl (193 lines, 7 paragraphs)
 *               app/cpy/CVACT01Y.cpy (300-byte ACCOUNT-RECORD)
 *               app/catlg/LISTCAT.txt:L59 (KEYLEN 11 / AVGLRECL 300)
 *               app/jcl/READACCT.jcl (job that executes CBACT01C)
 *               @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.batch.readers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.repository.AccountRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Read-only sequential scan of the account master, reproducing the COBOL batch program
 * {@code app/cbl/CBACT01C.cbl} paragraph for paragraph.
 *
 * <h2>What it does</h2>
 * Streams every row of the {@code account} relation in ascending primary-key order and emits each one to the
 * log exactly as the legacy program emitted it to SYSOUT, then terminates. Nothing is written, updated or
 * deleted: this is a <em>verification step</em>, and the read-only character is not a design preference but a
 * measured property of the source.
 * <p>
 * The verb inventory of {@code app/cbl/CBACT01C.cbl}, counted as Area-B statement starts with column-7 comment
 * lines stripped, is {@code OPEN}=1 ({@code :L135}), {@code READ}=1 ({@code :L93}), {@code CLOSE}=1
 * ({@code :L153}), {@code DISPLAY}=21, and {@code WRITE}={@code REWRITE}={@code DELETE}=<b>0</b>. Because the
 * source contains no write verb at all, this class adds no write path: no {@code save}, no {@code saveAll}, no
 * {@code delete}, no {@code @Modifying} query, no {@code EntityManager} mutation and no {@code flush}. The only
 * repository operations it ever performs are {@link AccountRepository#count()} and
 * {@link AccountRepository#findByAccountIdGreaterThanOrderByAccountIdAsc(Long,
 * org.springframework.data.domain.Pageable)}.
 * <p>
 * <b>Finding, severity Low.</b> Other project documents quote &quot;OPEN 4 / READ 1 / CLOSE 3 / DISPLAY 27&quot;
 * for this program. Those are lexical token counts, not statement counts: the token {@code OPEN} also occurs in
 * the paragraph label {@code 0000-ACCTFILE-OPEN} and in the field name {@code ACCT-OPEN-DATE}, and the token
 * {@code DISPLAY} also occurs in the labels {@code 1100-DISPLAY-ACCT-RECORD} and
 * {@code 9910-DISPLAY-IO-STATUS}. Measured directly at {@code 7756d89} the raw token occurrences are
 * {@code OPEN}=6, {@code READ}=2, {@code CLOSE}=3, {@code DISPLAY}=27, which reproduces the quoted
 * {@code CLOSE} and {@code DISPLAY} figures exactly and confirms the quoted set is lexical.
 * <i>Remediation:</i> cite the statement counts above, which supersede the lexical figures. The load-bearing
 * fact is identical either way, so nothing downstream changes: {@code WRITE}, {@code REWRITE} and
 * {@code DELETE} are zero.
 *
 * <h3>Paragraph map, one Java member per COBOL paragraph, never consolidated</h3>
 * <table>
 * <caption>Paragraphs of {@code app/cbl/CBACT01C.cbl} and their Java targets</caption>
 * <tr><th>COBOL label</th><th>Locator</th><th>Java target</th></tr>
 * <tr><td>mainline {@code PROCEDURE DIVISION}</td><td>{@code :L70-L87}</td>
 *     <td>{@link #open(ExecutionContext)}, {@link #read()}, {@link #close()}</td></tr>
 * <tr><td>{@code 1000-ACCTFILE-GET-NEXT}</td><td>{@code :L92-L116}</td>
 *     <td>{@code getNextAccountRecord()}</td></tr>
 * <tr><td>{@code 1100-DISPLAY-ACCT-RECORD}</td><td>{@code :L118-L131}</td>
 *     <td>{@code displayAccountRecord(Account)}</td></tr>
 * <tr><td>{@code 0000-ACCTFILE-OPEN}</td><td>{@code :L133-L149}</td><td>{@code openAccountFile()}</td></tr>
 * <tr><td>{@code 9000-ACCTFILE-CLOSE}</td><td>{@code :L151-L167}</td><td>{@code closeAccountFile()}</td></tr>
 * <tr><td>{@code 9999-ABEND-PROGRAM}</td><td>{@code :L169-L173}</td>
 *     <td>{@code abendProgram(String, Throwable)}</td></tr>
 * <tr><td>{@code 9910-DISPLAY-IO-STATUS}</td><td>{@code :L176-L189}</td>
 *     <td>{@code displayIoStatus(String)}</td></tr>
 * </table>
 * {@code CBACT01C} is the only one of the four simple sequential readers that has a {@code 1100-} paragraph,
 * which is why its paragraph count is seven while {@code CBACT02C}, {@code CBACT03C} and {@code CBCUS01C} have
 * six.
 *
 * <h3>Structures preserved for parity, which must never be deleted as dead code</h3>
 * Rule 1 clause B forbids dead code; the migration mandate requires control flow to be reproduced one for one
 * so that paragraph-level traceability is mechanically provable. Where the two collide the parity mandate
 * governs, and clause B is satisfied instead by tracking and justifying each retained structure here rather
 * than by deleting it. Four such structures live in this class.
 * <ol>
 * <li><b>The redundant double guard.</b> The mainline is {@code PERFORM UNTIL END-OF-FILE = 'Y'} wrapping an
 *     inner {@code IF END-OF-FILE = 'N'}, and a <em>second</em> {@code IF END-OF-FILE = 'N'} follows the read
 *     before the display ({@code :L74-L81}). The second test can never fail when the first passed and the read
 *     returned a record, so it is redundant by inspection. Both are reproduced as explicit guards in
 *     {@link #read()} and neither is collapsed.</li>
 * <li><b>Every record is emitted twice, by two different mechanisms - and the two emission POINTS are
 *     preserved while their CONTENT is refused.</b> The mainline performs {@code DISPLAY ACCOUNT-RECORD} over
 *     the whole 300-byte record ({@code :L78}) while {@code 1000-ACCTFILE-GET-NEXT} separately performs
 *     {@code 1100-DISPLAY-ACCT-RECORD} ({@code :L96}), which emitted the record field by field. Both points
 *     still emit, at the same two places in the control flow, so the structure the traceability map is checked
 *     against is intact and each still produces a distinct log event per row. <b>Neither publishes a field
 *     value.</b> Between them those emissions carried the current balance, both credit limits, both
 *     current-cycle totals, the account identifier and three dates - customer financial data, which Rule 1
 *     clause D forbids on a log channel, and which a DEBUG level does not mitigate because DEBUG is a switch an
 *     operator can turn on. Masking cannot compensate either: the whole-record form is a positional byte string
 *     with no field names for a {@code <paths>} rule to key on, and no value rule can distinguish a twelve-digit
 *     money field from the eleven-digit identifier beside it. Each emission is now the logical file, the
 *     operation and the running row sequence - a complete identity for a row of this scan, because the scan is
 *     ordered by an explicit ascending sort. No gate is weakened: gate 1 compares the daily posting job's
 *     fixed-width output, and this program writes no dataset at all. See
 *     {@code displayAccountRecord(Account)}.</li>
 * <li><b>The field-by-field display omitted {@code ACCT-ADDR-ZIP} and closed with a 49-character rule.</b>
 *     {@code app/cpy/CVACT01Y.cpy} declares twelve data fields; {@code 1100-DISPLAY-ACCT-RECORD} displayed
 *     eleven of them at {@code :L119-L129}, skipping the postal code entirely, then wrote a rule of exactly
 *     49 hyphens at {@code :L130} (both figures measured). The omission is recorded here because it is the one
 *     piece of evidence that the source itself treated address data as different in kind from the rest of the
 *     record - the same judgement {@link Account#toString()} makes, and the same judgement that now applies to
 *     the other eleven fields as well. Neither the eleven values nor the rule is emitted any longer, for the
 *     reason given in the previous item, so the width of a rule that is no longer written is history rather
 *     than contract.</li>
 * <li><b>The arithmetic-idiom variation between OPEN and CLOSE.</b> {@code 0000-ACCTFILE-OPEN} primes the
 *     result field with {@code MOVE 8 TO APPL-RESULT} ({@code :L134}); {@code 9000-ACCTFILE-CLOSE} primes the
 *     same field with {@code ADD 8 TO ZERO GIVING APPL-RESULT} ({@code :L152}) and clears it with
 *     {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} ({@code :L155}) rather than moving zero. The two
 *     paragraphs are computationally identical and are deliberately <em>not</em> normalised into one shape;
 *     each Java method documents the idiom its source paragraph uses.</li>
 * </ol>
 *
 * <h2>How to run, build and test</h2>
 * <strong>The read-only verification step that owns this reader is authored, and nothing about the batch tier
 * around it is outstanding.</strong> Two earlier revisions of this paragraph are withdrawn: the first said the
 * owning {@code Job} and {@code Step} were wired in {@code com.cardemo.config.BatchConfig} and that
 * {@code com.cardemo.batch.jobs} held {@code InterestCalculationJob} only; the second said the verification
 * step was "genuinely still owed" and that no {@code Step} referenced this reader, so none was constructed at
 * runtime. The step exists:
 * {@link com.cardemo.config.BatchConfig#datasetVerificationReadAccountStep} is the
 * Java counterpart of {@code app/jcl/READACCT.jcl}, and
 * {@link com.cardemo.config.BatchConfig#datasetVerificationJob} composes it with the three
 * sibling members as one operator submission, selectable by name through the framework's own {@code spring.batch.job.name}
 * property, which is the launch signal that survives after the bespoke operator launcher was
 * withdrawn.
 * The step writes nothing: its sink reaches no relation, no object store and no queue, because
 * {@code app/cbl/CBACT01C.cbl} performs {@code OPEN}, {@code READ} and {@code CLOSE} only.
 * {@code com.cardemo.batch.jobs} holds its six target jobs and each declares its own {@code Step} beans;
 * {@code BatchConfig} owns the dataset bindings and record rendering rather than step topology.
 * {@code spring.batch.job.enabled} is {@code false} in {@code src/main/resources/application.yml}, so no job
 * runs at application startup and every submission is deliberate. This class carries {@code @Component} and
 * {@code @StepScope}, so the component scan registers a definition for it and the step scope gives each step
 * execution its own instance.
 * <p>
 * Two build paths are available; both are pinned and either may be used. Each is stated as a required
 * <em>capability</em> rather than as a dated reading of one host, because a version measured on one machine
 * image ages the moment the image changes - dated measurements live in section 0.4.5.3 of
 * {@code docs/technical-specifications.md}.
 * <ul>
 * <li><b>Host toolchain</b> &mdash; JDK 25 on {@code PATH} with {@code JAVA_HOME} set, however the host
 *     provides it, then {@code ./mvnw -B -ntp -o clean compile}. The prerequisite is a capability and never a
 *     host path; Maven 3.9.11 comes from the pinned wrapper, and {@code maven-enforcer-plugin} floors both so
 *     a wrong toolchain fails the build rather than producing surprising bytecode. Note that {@code -o} also
 *     causes Maven to skip {@code dependency-check:check}, which declares {@code requiresOnline}, and a
 *     skipped scan is never evidence that the scan passes.</li>
 * <li><b>Pinned container</b> &mdash; given a reachable container daemon, the build can also run
 *     hermetically:
 *     {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q -DskipTests compile}.
 *     </li>
 * </ul>
 * The compiler runs with {@code -Xlint:all -Werror} and {@code failOnWarning}, so the build fails on any
 * warning. <strong>Both test tiers now cover this class.</strong> An earlier revision of this paragraph said
 * neither was authored, that {@code unit/batch} held three classes none of which referenced this reader, and
 * that {@code integration/batch} held one abstract Testcontainers base with no concrete subclass beneath it;
 * every part of that is withdrawn. {@code src/test/java/com/cardemo/unit/batch} holds <strong>36</strong>
 * sources and covers the status renderer, the guard logic and the keyset scan through
 * {@code AccountReaderTest}, {@code SequentialReaderContractTest},
 * {@code SequentialReaderKeysetScanTest}, {@code ReaderSensitiveDataTest} and
 * {@code BatchLogHygieneTest}. {@code src/test/java/com/cardemo/integration/batch} holds <strong>4</strong>
 * sources - one abstract Testcontainers base and three concrete classes that execute under Failsafe against
 * PostgreSQL 16 and LocalStack. This class still creates neither, because test sources are outside the scope
 * of the package it belongs to.
 *
 * <h2>Key configs and defaults</h2>
 * <ul>
 * <li>{@code carddemo.batch.account-reader.page-size} &mdash; the number of rows fetched per round trip.
 *     Default {@value #DEFAULT_PAGE_SIZE}, which covers the entire 50-row seed fixture
 *     {@code app/data/ASCII/acctdata.txt} in a single query while keeping the resident set bounded. The value
 *     is validated on construction and must be at least one. It is a buffering choice only and has no effect
 *     on emitted output, because the ordering is fixed independently.</li>
 * <li>Ordering is always ascending on {@code accountId}, never the store's natural order. This mirrors
 *     {@code ACCESS MODE IS SEQUENTIAL} over a KSDS keyed on {@code ACCT-ID}
 *     ({@code app/cbl/CBACT01C.cbl:L29-L33}, key length 11 per {@code app/catlg/LISTCAT.txt:L59}) and makes
 *     the emitted sequence byte reproducible.</li>
 * <li>{@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile and
 *     {@code spring.jpa.open-in-view} is {@code false}; the schema is owned by the Flyway migrations.</li>
 * <li>No AWS, bucket, queue or topic configuration is read by this reader, and no AWS client is injected, so
 *     it requests no cloud privilege whatever.</li>
 * <li>No transaction annotation is declared. See {@link #read()} for why a {@code readOnly} annotation here
 *     would be decorative rather than effective, and how read-only is guaranteed instead.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 * <li><b>End of data is not an error.</b> {@link #read()} returns {@code null}, which is how Spring Batch
 *     signals end of input. It is the exact analogue of file status {@code '10'} setting
 *     {@code APPL-RESULT} to 16, which {@code 88 APPL-EOF VALUE 16} ({@code :L63}) tests, driving
 *     {@code MOVE 'Y' TO END-OF-FILE} at {@code :L108}. Nothing is thrown.</li>
 * <li><b>An empty account relation completes successfully</b> with a row count of zero. It is logged
 *     explicitly at {@code open} time rather than inferred from the absence of records.</li>
 * <li><b>Any status that is neither {@code '00'} nor {@code '10'} abends.</b> The status is rendered as the
 *     fixed 20-character prefix {@code FILE STATUS IS: NNNN} followed by exactly four characters, then
 *     {@link FatalProcessingException} is thrown carrying abend code
 *     {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} and process return code
 *     {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}, reproducing
 *     {@code MOVE 999 TO ABCODE} and {@code CALL 'CEE3ABD'} at {@code :L172-L173}. The originating throwable
 *     is always attached as the cause.</li>
 * <li><b>Batch exit code 4 is unrelated to this reader.</b> It is set by the daily posting job if and only if
 *     that job's reject count exceeds zero, and it never indicates a failure here.</li>
 * <li><b>Unavailable relation.</b> A {@link DataAccessException} at open time is reported as
 *     {@code ERROR OPENING ACCTFILE} followed by the rendered status, and abends. Check that the Flyway
 *     migrations have applied and that the datasource points at the intended database.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <b>Not thread safe, by design.</b> The instance carries the cursor state that the legacy program held in
 * {@code WORKING-STORAGE} ({@code END-OF-FILE}, {@code APPL-RESULT}, {@code IO-STATUS}, the record area and
 * the row counter). The {@code step} scope gives each step execution its own instance, which is precisely what
 * keeps that state from being shared. There are <b>no mutable static fields</b>: the only static members are
 * the logger and immutable constants.
 *
 * @see AccountRepository
 * @see FileStatusMapper
 * @see Account
 */
@Component
@StepScope
public class AccountReader implements ItemStreamReader<Account> {

    /**
     * The one permitted static mutable-looking member: a logger reference that is itself immutable. Every
     * emission in this class goes through SLF4J, never {@code System.out}, {@code System.err} or
     * {@code printStackTrace()}, so the JSON encoding and the credential, hash and social-security masking
     * rules configured in {@code src/main/resources/logback-spring.xml} govern what actually reaches a log
     * aggregator.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AccountReader.class);

    /**
     * Default rows per round trip, used when {@code carddemo.batch.account-reader.page-size} is not set.
     * Chosen so the entire 50-row seed fixture {@code app/data/ASCII/acctdata.txt} (15,050 bytes, 50 records
     * of 300 bytes plus a terminator, measured) is satisfied by one query while the resident set stays small.
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /** The entity property the scan is ordered by, and the JPA counterpart of {@code ACCT-ID}. */
    private static final String ORDER_PROPERTY = "accountId";

    /**
     * Logical file name reported on every diagnostic, taken from the CICS file name in
     * {@code app/csd/CARDDEMO.CSD} and the DD name in {@code app/jcl/READACCT.jcl:L25}.
     */
    private static final String LOGICAL_FILE = "ACCTFILE";

    /**
     * The program name the legacy load module carried, used as the abend culprit so that
     * {@code ABEND-CULPRIT PIC X(8)} of {@code app/cpy/CSMSG02Y.cpy} is populated with a real component
     * identity. Exactly eight characters, matching the picture clause.
     */
    private static final String ABEND_CULPRIT = "CBACT01C";

    // ----------------------------------------------------------------------------------------------------
    // Legacy DISPLAY literals, reproduced byte for byte. Each is followed by its measured inner length so a
    // reviewer can confirm fidelity without opening the source. Rule 1 clause F: every assertion is cited.
    // ----------------------------------------------------------------------------------------------------

    /** {@code app/cbl/CBACT01C.cbl:L71}, 38 characters. */
    private static final String START_OF_EXECUTION_MESSAGE = "START OF EXECUTION OF PROGRAM CBACT01C";

    /** {@code app/cbl/CBACT01C.cbl:L85}, 36 characters. */
    private static final String END_OF_EXECUTION_MESSAGE = "END OF EXECUTION OF PROGRAM CBACT01C";

    /** {@code app/cbl/CBACT01C.cbl:L110}, 26 characters. */
    private static final String ERROR_READING_MESSAGE = "ERROR READING ACCOUNT FILE";

    /**
     * {@code app/cbl/CBACT01C.cbl:L144}, 22 characters.
     * <p>
     * <b>The wording really is {@code ACCTFILE} here and {@code ACCOUNT FILE} on the read and close paths.</b>
     * The source is internally inconsistent and the inconsistency is preserved rather than tidied, because the
     * three strings are observable output that a parity comparison reads. Anyone tempted to align them should
     * note that {@link #ERROR_READING_MESSAGE} and {@link #ERROR_CLOSING_MESSAGE} are both 26 characters while
     * this one is 22.
     */
    private static final String ERROR_OPENING_MESSAGE = "ERROR OPENING ACCTFILE";

    /** {@code app/cbl/CBACT01C.cbl:L162}, 26 characters. */
    private static final String ERROR_CLOSING_MESSAGE = "ERROR CLOSING ACCOUNT FILE";

    /** {@code app/cbl/CBACT01C.cbl:L170}, 16 characters. */
    private static final String ABENDING_PROGRAM_MESSAGE = "ABENDING PROGRAM";

    /**
     * The rule that closes {@code 1100-DISPLAY-ACCT-RECORD} at {@code app/cbl/CBACT01C.cbl:L130}: exactly 49
     * hyphens, measured, and neither 48 nor 50. Preserved verbatim as parity structure 3.
     */
    private static final String RECORD_SEPARATOR = "-".repeat(49);

    /**
     * Name of the isolated parity-output logger. {@code OFF} in every shipped profile; see {@link #PARITY_LOG}.
     *
     * <p>The suffix is the originating COBOL program, so a parity run can enable exactly one program's output
     * rather than the whole tree.
     */
    private static final String PARITY_LOGGER_NAME = "com.cardemo.parity.CBACT01C";

    /**
     * Parity-output logger, and the ONLY channel through which a record image or a financial value may leave
     * this class.
     *
     * <p><strong>Why it exists.</strong> The translated {@code DISPLAY} statements reproduce the source's own
     * SYSOUT output, and that output contains customer financial data: account balances, both credit limits,
     * both current-cycle totals, transaction and category amounts, and whole fixed-width record images. Emitting
     * those through the class logger put them in the application's ordinary log stream at DEBUG, where the
     * masking rules in {@code logback-spring.xml} could not reach them - masking matches labelled values, and an
     * unlabelled 300-byte record image presents nothing to match. Rule 1 Clauses A and D forbid that. Severity:
     * <strong>Medium</strong>.
     *
     * <p><strong>What it changes.</strong> The parity emissions move to the dedicated logger name
     * {@value #PARITY_LOGGER_NAME}, which {@code src/main/resources/application.yml} sets to {@code OFF} for the
     * whole {@code com.cardemo.parity} tree in every shipped profile. So no deployment emits them, and a parity
     * comparison enables the one logger deliberately, in an isolated run, with the output routed where a
     * baseline diff needs it. The class logger keeps counters, statuses and masked identifiers - the diagnostics
     * an operator actually needs - and never carries a value again.
     */
    private static final Logger PARITY_LOG = LoggerFactory.getLogger(PARITY_LOGGER_NAME);

    /**
     * The eleven field labels of {@code 1100-DISPLAY-ACCT-RECORD} ({@code app/cbl/CBACT01C.cbl:L119-L129}) in
     * source order, each measured at exactly 25 characters with the colon in column 25. The source concatenates
     * label and value with no separator, so {@code ACCT-ID} renders as
     * {@code "ACCT-ID                 :00000000001"}.
     * <p>
     * <b>{@code ACCT-ADDR-ZIP} is absent and that absence is deliberate</b> &mdash; see parity structure 3 in
     * the class documentation. The array is declared in the order the paragraph displays the fields, and
     * {@code displayAccountRecord(Account)} consumes it positionally so the two cannot drift apart.
     */
    private static final String[] FIELD_LABELS = {
        "ACCT-ID                 :",
        "ACCT-ACTIVE-STATUS      :",
        "ACCT-CURR-BAL           :",
        "ACCT-CREDIT-LIMIT       :",
        "ACCT-CASH-CREDIT-LIMIT  :",
        "ACCT-OPEN-DATE          :",
        "ACCT-EXPIRAION-DATE     :",
        "ACCT-REISSUE-DATE       :",
        "ACCT-CURR-CYC-CREDIT    :",
        "ACCT-CURR-CYC-DEBIT     :",
        "ACCT-GROUP-ID           :",
    };

    // ----------------------------------------------------------------------------------------------------
    // Record geometry, retained as documentation only.
    //
    // app/cpy/CVACT01Y.cpy declares the 300-byte layout, corroborated four independent ways: the FD at
    // app/cbl/CBACT01C.cbl:L37-L40 (FD-ACCT-ID PIC 9(11) + FD-ACCT-DATA PIC X(289) = 300), the catalogue entry
    // at app/catlg/LISTCAT.txt:L59 (KEYLEN 11 / AVGLRECL 300 / MAXLRECL 300), the cluster definition at
    // app/jcl/ACCTFILE.jcl:L40-L41 (KEYS(11 0) / RECORDSIZE(300 300)) and the measured row width of
    // app/data/ASCII/acctdata.txt. The field offsets are: identifier 1-11, active status 12, current balance
    // 13-24, credit limit 25-36, cash credit limit 37-48, open date 49-58, expiration date 59-68, reissue date
    // 69-78, current-cycle credit 79-90, current-cycle debit 91-102, postal code 103-112, group identifier
    // 113-122, filler 123-300.
    //
    // WHY THESE ARE NO LONGER CONSTANTS. Eight width and padding constants used to live here, and every one of
    // them existed to serve the two DISPLAY reproductions this class no longer performs: the field-by-field
    // emission of 1100-DISPLAY-ACCT-RECORD and the whole-record image of :L78, both of which published customer
    // financial data to a log channel and are now refused - see displayAccountRecord(Account) for the full
    // reasoning. With their only callers gone the constants were unreachable, and Rule 1 clause B forbids dead
    // code, so they were removed rather than left as a promise this class no longer keeps. The geometry itself
    // is provenance worth keeping legible, which is what this comment is for. This reader consumes mapped rows
    // through JPA and parses no fixed-width byte anywhere, so it needs no width at runtime; the byte-exact
    // rendering of a record belongs to the fixed-width writers, which own the zoned-decimal codec.
    //
    // ONE FINDING SURVIVES FROM THOSE DECLARATIONS AND MUST NOT BE LOST, severity Medium:
    // ACCT-EXPIRAION-DATE IS MISSPELLED IN THE COPYBOOK AND THE MISSPELLING IS DELIBERATE HERE.
    // app/cpy/CVACT01Y.cpy:L11 spells it ACCT-EXPIRAION-DATE, missing the T of "EXPIRATION", so the entity
    // property is Account.getExpiraionDate() over column acct_expiraion_date, and app/cbl/CBACT01C.cbl:L125
    // emits a label carrying the same misspelling. Severity is Medium rather than Low because it is a standing
    // naming trap: a maintainer who "corrects" the spelling in any one of the places it appears - copybook
    // citation, entity property, column name - breaks compilation against the entity contract or the schema.
    // Remediation: do not rename it. A sanctioned rename must be atomic across the entity, the Flyway
    // migration, every reader and writer and the expected-output baselines, and is owed an entry in the
    // DECISION_LOG.md as a deliberate divergence from the frozen corpus.
    //
    // The three PIC X(10) date fields are carried as String over CHAR(10) columns and are never parsed into a
    // java.time type by this reader: a verification scan must be able to hold a value no date parser would
    // accept. Parsing and validation belong to com.cardemo.service.shared.DateValidationService, which replaces
    // CALL 'CSUTLDTC'.
    // ----------------------------------------------------------------------------------------------------

    /** {@code ACCT-ID PIC 9(11)}, bytes 1-11 of the record. */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /** {@code ACCT-ACTIVE-STATUS PIC X(01)}, byte 12. */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /**
     * Digit positions occupied by one {@code PIC S9(10)V99} money field: ten integer digits plus two decimal
     * digits, twelve characters, with the decimal point implied and never stored. Applies to
     * {@code ACCT-CURR-BAL} (13-24), {@code ACCT-CREDIT-LIMIT} (25-36), {@code ACCT-CASH-CREDIT-LIMIT}
     * (37-48), {@code ACCT-CURR-CYC-CREDIT} (79-90) and {@code ACCT-CURR-CYC-DEBIT} (91-102).
     */
    private static final int MONEY_DIGITS = 12;

    /**
     * Width of each {@code PIC X(10)} field: the three text dates {@code ACCT-OPEN-DATE} (49-58),
     * {@code ACCT-EXPIRAION-DATE} (59-68) and {@code ACCT-REISSUE-DATE} (69-78), plus
     * {@code ACCT-ADDR-ZIP} (103-112) and {@code ACCT-GROUP-ID} (113-122).
     * <p>
     * All three dates are {@code PIC X(10)} text, not dates. They are carried as {@link String} over
     * {@code CHAR(10)} columns and are never parsed into a {@code java.time} type by this reader, because a
     * sequential verification scan must render exactly the ten bytes the record holds &mdash; including a value
     * that no date parser would accept. Parsing and validation are the job of
     * {@code com.cardemo.service.shared.DateValidationService}, which replaces {@code CALL 'CSUTLDTC'}.
     * <p>
     * <b>Finding, severity Medium: the misspelling in {@code ACCT-EXPIRAION-DATE} is retained deliberately.</b>
     * The copybook spells the field {@code ACCT-EXPIRAION-DATE} &mdash; missing the {@code T} of
     * &quot;EXPIRATION&quot; &mdash; at {@code app/cpy/CVACT01Y.cpy:L11}, so the entity property is
     * {@link Account#getExpiraionDate()} over column {@code acct_expiraion_date}, and the field label emitted by
     * {@code 1100-DISPLAY-ACCT-RECORD} reproduces it verbatim
     * ({@code app/cbl/CBACT01C.cbl:L125}). <i>Severity rationale:</i> Medium rather than Low because it is a
     * standing naming trap &mdash; a maintainer who &quot;corrects&quot; the spelling in any one of the four
     * places it appears (copybook citation, entity property, column name, display label) breaks either
     * compilation against the entity contract or byte-comparison of the emitted label, and the two failures
     * surface in different gates. <i>Remediation:</i> do not rename it. Should a rename ever be sanctioned, it
     * must be applied atomically across the entity, the Flyway migration, every reader and writer, and the
     * expected-output baselines, and owed an entry in the {@code DECISION_LOG.md} as a deliberate divergence
     * from the frozen corpus.
     *
     * @see Account#getExpiraionDate()
     */
    private static final int TEN_CHARACTER_WIDTH = 10;

    /** {@code FILLER PIC X(178)}, bytes 123-300, present in the record image and modelled by no property. */
    private static final int TRAILING_FILLER_WIDTH = 178;

    /** Total record length, 300 bytes, asserted by the four independent artefacts cited above. */
    private static final int RECORD_LENGTH = 300;

    /** The character a COBOL {@code MOVE} into an alphanumeric item pads with on the right. */
    private static final char ALPHANUMERIC_PAD = ' ';

    /** The character a COBOL {@code MOVE} into a numeric display item pads with on the left. */
    private static final char NUMERIC_PAD = '0';

    /**
     * The same-width stand-in emitted in place of every monetary value in this class's two diagnostic
     * emissions.
     * <p>
     * It is exactly {@value #MONEY_DIGITS} characters - the width is derived from {@code MONEY_DIGITS} rather
     * than hand-counted, so it cannot drift from the picture clause - and substituting it therefore preserves
     * the field width, the field order and the 300-character record geometry that the emissions exist to
     * prove. What it does not preserve
     * is the value, and that is deliberate: the current balance, the credit limit, the cash credit limit and
     * the two cycle accumulators are customer financial data, and no rule in
     * {@code src/main/resources/logback-spring.xml} masks a bare digit run - nor could one, because the same
     * digit run is a legitimate account identifier. Emitting the values and relying on downstream masking left
     * them in routine log volume (CWE-532); redacting here is the only place the distinction between a balance
     * and an identifier is knowable.
     * <p>
     * The token is not a run of a numeric character, so it can never be mistaken for a value that happened to
     * be zero.
     * <p>
     * <b>Why a run of {@code '*'} rather than a bracketed word.</b> An earlier revision composed this constant
     * from a {@code "[REDACTED]"} marker right-padded out to the field width. That spelling is withdrawn, for
     * two reasons that only became visible once every redaction site could be compared side by side. It was
     * the sole divergence among the emitter-side redactions: {@code TransactionReportProcessor} withholds
     * {@code TRAN-AMT}, {@code InterestCalculationProcessor} withholds {@code TRAN-CAT-BAL}, and
     * {@code TransactionDetailService} withholds both an amount and a merchant identifier, each as a run of
     * {@code '*'} sized from its own picture clause - so one vocabulary now covers every position a reader
     * might compare, which is what Rule 1 clause C asks of a convention. And {@code [REDACTED]} is the value
     * of the {@code REDACTION} property in {@code src/main/resources/logback-spring.xml}, which is what the
     * masking layer substitutes when one of its rules <em>matches</em> text that has already been rendered.
     * Reserving that token to the pipeline keeps two different facts about a log line distinguishable: that a
     * rule scrubbed a value that had been written, and that the emitter never wrote one at all.
     */
    private static final String REDACTED_MONEY = "*".repeat(MONEY_DIGITS);

    // ----------------------------------------------------------------------------------------------------
    // Execution-context keys for the restart cursor. Namespaced by simple class name so two readers in the
    // same step cannot collide.
    // ----------------------------------------------------------------------------------------------------

    /** Key under which the number of rows already emitted is checkpointed. */
    private static final String CONTEXT_KEY_RECORDS_READ = "AccountReader.recordsRead";

    /** Key under which the identifier of the most recently emitted row is checkpointed. */
    private static final String CONTEXT_KEY_LAST_ACCOUNT_ID = "AccountReader.lastAccountId";

    /**
     * Exclusive lower bound seeding the first keyset window, chosen to sit provably below the entire key
     * space so that {@code ACCT-ID > } this value selects the true first row.
     * <p>
     * The proof, not an assumption: {@code ACCT-ID} is declared {@code PIC 9(11)} at
     * {@code app/cpy/CVACT01Y.cpy}, is materialised as {@code NUMERIC(11) NOT NULL} by
     * {@code src/main/resources/db/migration/V1__create_schema.sql}, and {@code Account} rejects any value
     * below its own {@code MIN_ACCOUNT_ID} of zero. An unsigned display field admits no negative member at
     * all, so {@code -1} is below every value the column can hold and below every value the entity will
     * accept. It is a bound, never a key: no row can equal it, so no row can be skipped by it.
     */
    private static final long SEED_ACCOUNT_ID = -1L;

    /** {@code END-OF-FILE PIC X(01) VALUE 'N'} in its initial state ({@code app/cbl/CBACT01C.cbl:L65}). */
    private static final String END_OF_FILE_NO = "N";

    /** {@code END-OF-FILE} after {@code MOVE 'Y' TO END-OF-FILE} ({@code app/cbl/CBACT01C.cbl:L108}). */
    private static final String END_OF_FILE_YES = "Y";

    // ----------------------------------------------------------------------------------------------------
    // File-status literals, derived from com.cardemo.model.enums.FileStatus rather than restated, so that the
    // single definition of each code stays single (Rule 1 clause C3, avoid duplication).
    // ----------------------------------------------------------------------------------------------------

    /**
     * The {@code '0'} that occupies the second byte of {@link #STATUS_PHYSICAL_IO_ERROR}.
     * <p>
     * It is the character a COBOL {@code MOVE} into a numeric display item pads with on the left, which is why
     * it is the right stand-in for an unavailable subcode rather than a space.
     */
    private static final char NUMERIC_SUBCODE_NONE = '0';

    /** {@code '00'}: the status the source tests at {@code app/cbl/CBACT01C.cbl:L94}, {@code :L136}, {@code :L154}. */
    private static final String STATUS_SUCCESS = requireExactCode(FileStatus.SUCCESS);

    /** {@code '10'}: end of file, which drives {@code MOVE 16 TO APPL-RESULT} at {@code app/cbl/CBACT01C.cbl:L99}. */
    private static final String STATUS_END_OF_FILE = requireExactCode(FileStatus.END_OF_FILE);

    /**
     * The member of the {@code '9x'} family this reader reports when the store rejects an operation.
     * <p>
     * {@link FileStatus#IO_ERROR} is a family rather than a value: its first byte is fixed at
     * {@link FileStatus#IO_ERROR_FIRST_BYTE} and, as that constant's own documentation records, the second byte
     * carries an implementation-defined subcode. A relational store reports a failure as a
     * {@link DataAccessException} hierarchy and a driver SQLSTATE, neither of which carries a VSAM subcode, so
     * the subcode is set to {@code '0'} to mean &quot;no further subcode available from this layer&quot;. The
     * driver's own detail is never discarded: it travels on the cause of the thrown exception.
     * <p>
     * <b>Finding, severity Low.</b> The specific z/OS VSAM subcode that a given JDBC failure would have
     * produced on the mainframe is <b>Not available</b>. <i>Prerequisite:</i> a z/OS VSAM trace of the failing
     * condition, which cannot be obtained here because EBCDIC and mainframe-runtime reproduction are out of
     * scope for this migration. <i>Remediation:</i> if a byte-exact subcode is ever required, add a
     * SQLSTATE-to-subcode table at the {@link FileStatusMapper} layer, where the single definition of the
     * status vocabulary already lives, rather than in this reader.
     */
    private static final String STATUS_PHYSICAL_IO_ERROR =
            String.valueOf(FileStatus.IO_ERROR_FIRST_BYTE) + NUMERIC_SUBCODE_NONE;

    // ----------------------------------------------------------------------------------------------------
    // Collaborators, injected through the constructor and never reassigned.
    // ----------------------------------------------------------------------------------------------------

    /** The persistence access point for the account master, replacing the {@code ACCTFILE} VSAM cluster. */
    private final AccountRepository accountRepository;

    /**
     * The central {@code FILE STATUS} translator. Consumed rather than re-implemented: it already renders the
     * {@code 9910-DISPLAY-IO-STATUS} line and already applies the {@code APPL-RESULT} arithmetic of both the
     * two-way guard and the three-way sequential-read guard, and its own documentation cites
     * {@code app/cbl/CBACT01C.cbl:L94-L103} for the latter. Duplicating any of that here would violate Rule 1
     * clause C3.
     */
    private final FileStatusMapper fileStatusMapper;

    /** Rows fetched per round trip; validated at construction and never changed afterwards. */
    private final int pageSize;

    // ----------------------------------------------------------------------------------------------------
    // Cursor state. Every field below is the Java counterpart of a WORKING-STORAGE item at
    // app/cbl/CBACT01C.cbl:L46-L67 and is therefore an INSTANCE field: never static, never shared. The step
    // scope gives each step execution its own instance.
    // ----------------------------------------------------------------------------------------------------

    /** {@code END-OF-FILE PIC X(01)} ({@code :L65}). Held as its literal {@code 'N'} or {@code 'Y'} value. */
    private String endOfFile = END_OF_FILE_NO;

    /** {@code APPL-RESULT PIC S9(9) COMP} ({@code :L61}), tested through {@code APPL-AOK} and {@code APPL-EOF}. */
    private int applResult;

    /** {@code IO-STATUS} ({@code :L50-L52}), the two-character status moved in before the renderer runs. */
    private String ioStatus = STATUS_SUCCESS;

    /** {@code ACCOUNT-RECORD}, the record area that {@code COPY CVACT01Y} declares at {@code :L45}. */
    private Account accountRecord;

    /** The rows of the page currently buffered, standing in for the VSAM read-ahead buffer. */
    private List<Account> pageBuffer = List.of();

    /** Cursor into {@link #pageBuffer}; the next row to hand out. */
    private int pageBufferIndex;

    /**
     * Keyset cursor: the highest {@code ACCT-ID} already <em>fetched</em> into {@link #pageBuffer}, and
     * therefore the exclusive lower bound of the next window. Seeded to {@value #SEED_ACCOUNT_ID}, which is
     * provably below the whole key space, so the first window starts at the true first row.
     * <p>
     * This runs ahead of {@link #lastAccountId} by up to {@link #pageSize} rows, because a window is fetched
     * before its rows are handed out. The two are distinct on purpose: this one positions the <em>next
     * query</em>, that one records the <em>last emission</em> and is what a restart resumes from.
     */
    private long fetchCursorAccountId = SEED_ACCOUNT_ID;

    /** Rows emitted so far, the counter the end-of-run summary reports. */
    private long recordsRead;

    /**
     * Identifier of the most recently emitted row. Checkpointed by {@link #update(ExecutionContext)} and, on a
     * restart, the authoritative position that {@link #fetchCursorAccountId} is re-seeded from.
     */
    private Long lastAccountId;

    /** Whether {@code openAccountFile()} has completed successfully, mirroring an open VSAM ACB. */
    private boolean fileOpen;

    /** Total rows the relation held when the file was opened, used for the explicit empty-relation branch. */
    private long recordCountAtOpen;

    /**
     * Creates a reader bound to the account master.
     *
     * @param accountRepository the account persistence access point; must not be {@code null}
     * @param fileStatusMapper the shared {@code FILE STATUS} translator; must not be {@code null}
     * @param pageSize rows per round trip, supplied by
     *     {@code carddemo.batch.account-reader.page-size} and defaulting to {@value #DEFAULT_PAGE_SIZE}; must
     *     be at least one
     * @throws NullPointerException if either collaborator is {@code null}
     * @throws IllegalArgumentException if {@code pageSize} is less than one
     */
    public AccountReader(
            AccountRepository accountRepository,
            FileStatusMapper fileStatusMapper,
            @Value("${carddemo.batch.account-reader.page-size:" + DEFAULT_PAGE_SIZE + "}") int pageSize) {
        // Only Objects.requireNonNull and private static validators are called here. Invoking an overridable
        // instance method from the constructor of a non-final class would publish a partially built reference,
        // which -Xlint:all -Werror reports as this-escape; the step scope forbids a final class because it
        // proxies by subclassing.
        this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.pageSize = requirePositivePageSize(pageSize);
    }

    // ====================================================================================================
    // Mainline PROCEDURE DIVISION, app/cbl/CBACT01C.cbl:L70-L87, realised as the ItemStream lifecycle.
    // ====================================================================================================

    /**
     * Opens the scan: emits the start-of-execution banner and performs {@code 0000-ACCTFILE-OPEN}, reproducing
     * {@code app/cbl/CBACT01C.cbl:L71-L72}.
     * <p>
     * <b>Side effects.</b> Resets all cursor state, restores the restart cursor from {@code executionContext}
     * when one is present, issues one {@code count()} round trip against the account relation, and writes two
     * or three log events.
     *
     * @param executionContext the step execution context; a restart cursor written by a previous run of the
     *     same step instance is honoured when present, and a {@code null} context is treated as a cold start
     * @throws org.springframework.batch.item.ItemStreamException never thrown directly; a store failure is
     *     reported as {@link FatalProcessingException}, which is also unchecked
     * @throws FatalProcessingException if the account relation cannot be reached, reproducing the abend at
     *     {@code app/cbl/CBACT01C.cbl:L147}
     */
    @Override
    public void open(ExecutionContext executionContext) {
        // Cold-start every cursor field first, so a reused instance cannot inherit a previous scan's position.
        endOfFile = END_OF_FILE_NO;
        applResult = FileStatusMapper.APPL_AOK;
        ioStatus = STATUS_SUCCESS;
        accountRecord = null;
        pageBuffer = List.of();
        pageBufferIndex = 0;
        fetchCursorAccountId = SEED_ACCOUNT_ID;
        recordsRead = 0L;
        lastAccountId = null;
        fileOpen = false;
        recordCountAtOpen = 0L;

        // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'.  (:L71)
        LOG.info(START_OF_EXECUTION_MESSAGE);

        // A null context is an explicit, handled case rather than a guarded assumption (Rule 1 clause B2).
        if (executionContext != null && executionContext.containsKey(CONTEXT_KEY_RECORDS_READ)) {
            restoreRestartCursor(executionContext);
        }

        // PERFORM 0000-ACCTFILE-OPEN.  (:L72)
        openAccountFile();
    }

    /**
     * Returns the next account, or {@code null} once the scan is exhausted, reproducing the mainline loop body
     * at {@code app/cbl/CBACT01C.cbl:L74-L81}.
     * <p>
     * The method body is the loop <em>body</em>, not the loop: Spring Batch drives the iteration, so
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} becomes the framework calling this method until it answers
     * {@code null}. Both of the source's guards are reproduced explicitly, in order, and neither is collapsed;
     * see parity structure 1 in the class documentation. Both emission points are retained - the one at
     * {@code :L78} here and the one {@code getNextAccountRecord()} performs at {@code :L96} - and neither
     * publishes a field value; see parity structure 2.
     * <p>
     * <b>Why there is no {@code @Transactional} annotation.</b> A chunk-oriented step already runs this method
     * inside its own transaction, and Spring silently ignores the {@code readOnly} attribute of a method that
     * merely <em>participates</em> in an existing transaction rather than starting one. Annotating
     * {@code readOnly = true} here would therefore read as an enforced guarantee while enforcing nothing, which
     * Rule 1 clause A1 rules out. Read-only is guaranteed structurally instead: the only repository operations
     * this class can reach are {@link AccountRepository#count()} and
     * {@link AccountRepository#findByAccountIdGreaterThanOrderByAccountIdAsc(Long,
     * org.springframework.data.domain.Pageable)}, and there is no mutating
     * call, no {@code @Modifying} query and no {@code EntityManager} reference anywhere in the file.
     *
     * @return the next account in ascending {@code accountId} order, or {@code null} at end of data, which is
     *     the Spring Batch end-of-input signal and the analogue of {@code MOVE 'Y' TO END-OF-FILE}
     * @throws FatalProcessingException if the store reports a status that is neither {@code '00'} nor
     *     {@code '10'}, reproducing the abend path at {@code app/cbl/CBACT01C.cbl:L110-L113}
     * @throws IllegalStateException if called before {@link #open(ExecutionContext)}, which has no legacy
     *     counterpart because the mainline performs the open unconditionally at {@code :L72}
     */
    @Override
    public Account read() {
        if (!fileOpen) {
            throw new IllegalStateException(
                    "read() called before open(ExecutionContext); app/cbl/CBACT01C.cbl:L72 performs "
                            + "0000-ACCTFILE-OPEN before the mainline loop, so the file is always open by "
                            + "the time the loop body runs");
        }

        // PERFORM UNTIL END-OF-FILE = 'Y'  (:L74) - the framework owns the iteration, so the terminating
        // condition becomes an explicit early return that keeps answering null after the scan has finished.
        if (END_OF_FILE_YES.equals(endOfFile)) {
            return null;
        }

        // IF END-OF-FILE = 'N'  (:L75) - the first of the two guards. Redundant against the loop condition
        // immediately above, and retained deliberately: parity structure 1.
        if (!END_OF_FILE_NO.equals(endOfFile)) {
            return null;
        }

        // PERFORM 1000-ACCTFILE-GET-NEXT  (:L76)
        Account account = getNextAccountRecord();

        // IF END-OF-FILE = 'N'  (:L77) - the second guard. It cannot fail when the first passed and a record
        // was returned, which is exactly why it is redundant, and exactly why it is preserved: parity
        // structure 1. The null test is the same condition expressed through the returned value.
        if (!END_OF_FILE_NO.equals(endOfFile) || account == null) {
            return null;
        }

        // DISPLAY ACCOUNT-RECORD  (:L78) - the whole 300-byte record, the FIRST of the two emissions this
        // program performs per row. Parity structure 2 is preserved as CONTROL FLOW: the emission point is
        // still here, at this exact position inside the '00' branch and before the guard, which is what made
        // the source emit every successful row twice.
        //
        // TWO INDEPENDENT CONTROLS APPLY TO THE CONTENT, AND BOTH ARE KEPT. A 300-byte account image carries
        // the current balance, both credit limits and both current-cycle totals: customer financial data.
        // Rule 1 clause D forbids that on a log channel, and a DEBUG level is not a mitigation - it is a
        // switch an operator can turn on. Nor can masking compensate: the image is a positional byte string
        // with no field names, so no <paths> rule in logback-spring.xml can reach a balance inside it, and the
        // value rules cannot either, because a twelve-digit money field is indistinguishable on shape from the
        // account identifier beside it. So (1) every monetary field is REDACTED where it is rendered, in
        // renderRedactedAccountRecord, which is the only place the difference between a balance and an
        // identifier is knowable, and (2) what remains still goes to the isolated parity channel rather than
        // to the application log. Either control alone would be defensible; together they mean no monetary
        // value reaches a log event even when a parity run enables the channel.
        //
        // Parity is not weakened by the redaction. No validation gate compares this program's DISPLAY output:
        // gate 1 compares the POSTING job's fixed-width output field by field, and CBACT01C is a read-only
        // verification step whose verb inventory is OPEN, READ and CLOSE with no WRITE anywhere. The one log
        // rendering that IS a parity contract, the four-character FILE STATUS of 9910-DISPLAY-IO-STATUS, is
        // owned by FileStatusMapper and is untouched.
        //
        // WHAT THE CLASS LOGGER CARRIES: the safe projection only - the logical file and the running count,
        // which is what makes a step's progress readable without publishing a single field value.
        if (LOG.isDebugEnabled()) {
            LOG.debug("{} record read; sequence={}", LOGICAL_FILE, Long.valueOf(recordsRead + 1));
        }

        // WHAT THE PARITY LOGGER CARRIES: the redacted record image, and nothing else does. The two channels
        // are separate on purpose. Being an unlabelled 300-character run, the image presents nothing for the
        // masking rules in logback-spring.xml to match, so the class logger is the wrong channel for it at any
        // level. PARITY_LOG is a per-program logger that every shipped profile sets to OFF and that a level
        // set on com.cardemo cannot raise, so a parity comparison enables exactly one program's output in an
        // isolated run and no deployment emits it. See PARITY_LOG.
        if (PARITY_LOG.isDebugEnabled()) {
            PARITY_LOG.debug("{}", renderRedactedAccountRecord(account));
        }

        recordsRead++;
        lastAccountId = account.getAccountId();
        return account;
    }

    /**
     * Checkpoints the restart cursor so an interrupted step can resume without re-emitting rows.
     * <p>
     * Only two scalars are stored, and together they are the whole of the cursor: the identifier of the most
     * recently emitted row, which is the <b>position</b> a restart seeks to, and the number of rows emitted
     * so far, which is the <b>tally</b> the end-of-run summary continues from. The identifier is what makes
     * the position durable: because the scan is ordered by {@code accountId} and the next window is selected
     * by {@code ACCT-ID > } that identifier, the resume point survives rows being inserted or deleted
     * elsewhere in the relation between the two runs. No entity, window or buffer is serialised.
     * <p>
     * <b>Side effects.</b> Mutates {@code executionContext} only. Performs no I/O and logs nothing.
     *
     * @param executionContext the step execution context to write into; a {@code null} context is ignored,
     *     which makes the reader usable outside a step for unit testing
     * @throws org.springframework.batch.item.ItemStreamException never thrown; this method cannot fail
     */
    @Override
    public void update(ExecutionContext executionContext) {
        if (executionContext == null) {
            return;
        }
        executionContext.putLong(CONTEXT_KEY_RECORDS_READ, recordsRead);
        if (lastAccountId != null) {
            executionContext.putLong(CONTEXT_KEY_LAST_ACCOUNT_ID, lastAccountId.longValue());
        }
    }

    /**
     * Closes the scan: performs {@code 9000-ACCTFILE-CLOSE} and emits the end-of-execution banner, reproducing
     * {@code app/cbl/CBACT01C.cbl:L83-L85}.
     * <p>
     * The order matters and is the source's: the close precedes the banner, so a close failure abends before
     * the banner is written and the banner is therefore evidence that the run completed. The row count is
     * reported alongside it, which the legacy program did not do for this job; it is additive observability
     * required by Rule 1 clause A4 and it replaces nothing.
     * <p>
     * <b>Side effects.</b> Releases the page buffer, resets the cursor and writes at least one log event.
     *
     * @throws org.springframework.batch.item.ItemStreamException never thrown directly
     * @throws FatalProcessingException if releasing the cursor fails, reproducing the abend at
     *     {@code app/cbl/CBACT01C.cbl:L165}
     */
    @Override
    public void close() {
        // PERFORM 9000-ACCTFILE-CLOSE.  (:L83)
        closeAccountFile();

        // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'.  (:L85)
        LOG.info("{} recordsRead={}", END_OF_EXECUTION_MESSAGE, Long.valueOf(recordsRead));

        // GOBACK.  (:L87) - returning from close() is the return to the caller; Spring Batch then completes
        // the step. No RETURN-CODE is set here: for this read-only verification step the only non-zero
        // outcome is the abend, which propagates as an exception and fails the step on its own.
    }

    /**
     * Returns the number of rows emitted so far.
     * <p>
     * Exposed so the sibling-owned Micrometer &quot;records processed&quot; counter can observe this step
     * without this class registering an instrument of its own. <b>No meter, timer or gauge is created here</b>:
     * the four named counters are owned by {@code com.cardemo.observability.MetricsConfig}, and adding a fifth
     * instrument from a reader would duplicate that ownership.
     *
     * @return the count of rows returned by {@link #read()} since the last {@link #open(ExecutionContext)},
     *     never negative
     */
    public long getRecordsRead() {
        return recordsRead;
    }

    // ====================================================================================================
    // 1000-ACCTFILE-GET-NEXT, app/cbl/CBACT01C.cbl:L92-L116.
    // ====================================================================================================

    /**
     * Reads the next record and applies the three-way sequential-read guard of
     * {@code 1000-ACCTFILE-GET-NEXT} ({@code app/cbl/CBACT01C.cbl:L92-L116}).
     * <p>
     * The source shape is preserved exactly: the read sets a status; {@code '00'} yields
     * {@code MOVE 0 TO APPL-RESULT} <em>and</em> performs {@code 1100-DISPLAY-ACCT-RECORD} ({@code :L95-L96});
     * {@code '10'} yields {@code MOVE 16} ({@code :L99}); anything else yields {@code MOVE 12}
     * ({@code :L101}). The guard that follows then either continues, sets {@code END-OF-FILE} to {@code 'Y'},
     * or reports and abends ({@code :L104-L115}).
     * <p>
     * Note where the field-by-field emission sits: <b>inside the {@code '00'} branch, before the guard</b>, not
     * after it. That placement is why every successfully read record produces two log events, and it is
     * reproduced here rather than hoisted; the events carry the row sequence and no field value, for the reason
     * set out on {@code displayAccountRecord(Account)}. See parity structure 2.
     *
     * @return the record just read when the status was {@code '00'}, or {@code null} at end of file
     * @throws FatalProcessingException when the status is neither {@code '00'} nor {@code '10'}, carrying the
     *     store failure as its cause when one was raised
     */
    private Account getNextAccountRecord() {
        DataAccessException storeFailure = null;
        try {
            // READ ACCTFILE-FILE INTO ACCOUNT-RECORD.  (:L93)
            ioStatus = readNextRecord();
        } catch (DataAccessException failure) {
            // The COBOL READ reports through ACCTFILE-STATUS; a relational store reports by throwing. The
            // throwable is translated to the '9x' family and then RETAINED as the cause, never swallowed and
            // never allowed to escape untyped (Rule 1 clause B4).
            storeFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF ACCTFILE-STATUS = '00' MOVE 0 TO APPL-RESULT / ELSE IF '10' MOVE 16 / ELSE MOVE 12
        // (:L94-L103). The arithmetic is not restated here: FileStatusMapper already implements this exact
        // nested test and its documentation cites this very paragraph (Rule 1 clause C3).
        applResult = fileStatusMapper.applResultForSequentialRead(ioStatus);

        if (applResult == FileStatusMapper.APPL_AOK) {
            // PERFORM 1100-DISPLAY-ACCT-RECORD  (:L96)
            displayAccountRecord(accountRecord);
        }

        // IF APPL-AOK CONTINUE  (:L104-L105)
        if (applResult == FileStatusMapper.APPL_AOK) {
            return accountRecord;
        }

        // ELSE IF APPL-EOF MOVE 'Y' TO END-OF-FILE  (:L107-L108). End of file is loop termination, NOT an
        // error: 88 APPL-EOF VALUE 16 at :L63 is a normal outcome and nothing is thrown for it.
        if (applResult == FileStatusMapper.APPL_EOF) {
            endOfFile = END_OF_FILE_YES;
            accountRecord = null;
            return null;
        }

        // ELSE DISPLAY 'ERROR READING ACCOUNT FILE' / MOVE ACCTFILE-STATUS TO IO-STATUS /
        // PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM  (:L110-L113). IO-STATUS already holds
        // the status, so the MOVE at :L111 is the assignment made above.
        LOG.error(ERROR_READING_MESSAGE);
        LOG.error(displayIoStatus(ioStatus));
        abendProgram(ERROR_READING_MESSAGE, storeFailure);

        // EXIT.  (:L116) - unreachable, because abendProgram always throws. Present so that a reader of this
        // method sees the paragraph terminate exactly where the source does, and so the compiler proves the
        // method has no fall-through path that could silently return a stale record.
        return null;
    }

    /**
     * Performs the store round trip behind the {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD} verb at
     * {@code app/cbl/CBACT01C.cbl:L93} and reports its outcome as a COBOL file status.
     * <p>
     * A VSAM {@code READ} with {@code ACCESS MODE IS SEQUENTIAL} hands back one record and advances the
     * cursor. Here the cursor is a buffered window refilled by
     * {@link AccountRepository#findByAccountIdGreaterThanOrderByAccountIdAsc(Long,
     * org.springframework.data.domain.Pageable)}, whose ascending key order is fixed <b>in the method name
     * itself</b> and so cannot be omitted or overridden by a caller. The store's natural order is never relied
     * upon: {@code app/cbl/CBACT01C.cbl:L29-L33} declares {@code ORGANIZATION IS INDEXED} with
     * {@code ACCESS MODE IS SEQUENTIAL} and {@code RECORD KEY IS FD-ACCT-ID}, so key order <em>is</em> the
     * contract, and reproducing it deterministically is what makes the emitted sequence comparable against the
     * legacy baseline (Rule 1 clause A1).
     * <p>
     * <b>Why the window is keyset-bounded and not offset-paged</b> (Rule 1 clause A5, tradeoff justified
     * rather than assumed). An offset page asks the store to produce and discard every row before the window,
     * so walking the relation costs work quadratic in its size, and the discarded prefix grows with every
     * step. A keyset window instead asks for {@code ACCT-ID > cursor ... LIMIT pageSize}, which the primary-key
     * index satisfies by seeking straight to the cursor and reading forward: constant work per window,
     * independent of how far the scan has already travelled. This is also the closer analogue of the source,
     * because a VSAM sequential read positions by key and reads forward rather than counting from the start of
     * the cluster. The window size cannot affect the emitted output, because the ordering is fixed
     * independently of it, and the seek bound is exclusive so no row is visited twice or skipped.
     * <p>
     * A second, unrelated saving: this finder returns a {@code List}, so no {@code COUNT(*)} is issued. The
     * page-shaped predecessor computed a total on every refill that nothing on this path ever read. The one
     * count this class does perform is the deliberate, once-per-open one in {@code openAccountFile()}, which
     * exists to make the empty-relation case an explicit logged outcome.
     *
     * @return {@link #STATUS_SUCCESS} when a record was placed in the record area, {@link #STATUS_END_OF_FILE}
     *     when the scan is exhausted, or {@link #STATUS_PHYSICAL_IO_ERROR} when the buffer yielded a
     *     {@code null} element, which a {@code NOT NULL} keyed relation cannot legitimately produce
     * @throws DataAccessException if the store rejects the query; translated by the caller
     */
    private String readNextRecord() {
        while (pageBufferIndex >= pageBuffer.size()) {
            // The window is bounded by the cursor, never by an offset: ACCT-ID > cursor ORDER BY ACCT-ID
            // ASC LIMIT pageSize. PageRequest.ofSize() is page zero, so the offset is always literally 0.
            pageBuffer = accountRepository.findByAccountIdGreaterThanOrderByAccountIdAsc(
                    Long.valueOf(fetchCursorAccountId), PageRequest.ofSize(pageSize));
            pageBufferIndex = 0;

            if (pageBuffer.isEmpty()) {
                accountRecord = null;
                return STATUS_END_OF_FILE;
            }

            // Advance the cursor to the highest key in the window just fetched, so the next window starts
            // strictly after it. Explicit null branch (Rule 1 clause B2): ACCT-ID is NOT NULL and is the
            // primary key, so a null here means the result set is not what the schema promises. It is
            // reported through the status vocabulary rather than allowed to become a NullPointerException,
            // and the cursor is deliberately left unadvanced on that path.
            Account highestOfWindow = pageBuffer.get(pageBuffer.size() - 1);
            if (highestOfWindow == null || highestOfWindow.getAccountId() == null) {
                accountRecord = null;
                return STATUS_PHYSICAL_IO_ERROR;
            }
            fetchCursorAccountId = highestOfWindow.getAccountId().longValue();
        }

        Account next = pageBuffer.get(pageBufferIndex);
        pageBufferIndex++;

        // Explicit null branch (Rule 1 clause B2): every row of this relation is NOT NULL and keyed, so a
        // null element means the result set is not what the schema promises. It is reported through the status
        // vocabulary rather than allowed to become a NullPointerException further down.
        if (next == null) {
            accountRecord = null;
            return STATUS_PHYSICAL_IO_ERROR;
        }

        accountRecord = next;
        return STATUS_SUCCESS;
    }

    // ====================================================================================================
    // 1100-DISPLAY-ACCT-RECORD, app/cbl/CBACT01C.cbl:L118-L131.
    // ====================================================================================================

    /**
     * The counterpart of {@code 1100-DISPLAY-ACCT-RECORD} ({@code app/cbl/CBACT01C.cbl:L118-L131}).
     * <p>
     * The paragraph exists here, is performed from the same place, and emits <b>no field value</b>.
     * <p>
     * <b>What the source did.</b> Eleven labelled lines at {@code :L119-L129}, each label a 25-character
     * literal with the colon in column 25 and concatenated with its value with no separator, followed by a rule
     * of exactly 49 hyphens at {@code :L130}. {@code ACCT-ADDR-ZIP} was not among the eleven.
     * <p>
     * <b>The five monetary values are REDACTED, and the emission goes to the parity channel.</b> Three of the
     * eleven lines carry the current balance and both credit limits and two more carry the current-cycle
     * totals, all of which are customer financial data. Each present value is replaced by
     * {@link #REDACTED_MONEY}, a stand-in of identical width, so the label, the field order, the field count
     * and the column geometry are all still those of the source while the value itself never reaches a log
     * event; {@link Account#toString()} withholds the same values for the same reason. Redaction is applied
     * <b>here</b> rather than delegated to the masking rules in {@code src/main/resources/logback-spring.xml},
     * because no rule there can distinguish a balance from an account identifier - both are bare digit runs -
     * so a rule broad enough to catch one would destroy the other.
     * <p>
     * <b>Why the lines do not go to the class logger either.</b> The two controls are independent and both are
     * applied. Emitting eleven label-prefixed positional values on the application channel at DEBUG was not a
     * mitigation but a switch: an operator enabling DEBUG on this package published a row per field for every
     * row of a scan, and the masking layer could not compensate for the reason just given. The lines therefore
     * go to {@link #PARITY_LOG}, which every shipped profile sets to {@code OFF}, and they carry no monetary
     * value even there.
     * <p>
     * <b>Why it is still emitted, on a different channel.</b> Removing the content outright would also remove
     * the only artefact a parity comparison of this program has, so the emission is <em>isolated</em> rather
     * than deleted: it goes to {@link #PARITY_LOG}, the per-program logger
     * {@value #PARITY_LOGGER_NAME}, which every shipped profile sets to {@code OFF} and which a level set on
     * {@code com.cardemo} cannot raise, because a child level overrides its parent. A parity run enables that
     * one logger deliberately and routes it where a baseline diff needs it; no deployment emits it. The
     * content, order and formatting are the source's - only the channel differs.
     * <p>
     * <b>What the class logger carries instead.</b> One line naming the logical file and the running row
     * sequence. The sequence is a complete identity for a row of this scan, because the scan is ordered by an
     * explicit ascending sort on {@code accountId} - the same property that makes a row count a complete
     * restart cursor - so a log line is still traceable to a row without naming the account.
     * <p>
     * <b>Why parity is not weakened.</b> No validation gate compares this program's {@code DISPLAY} output.
     * Gate 1 compares the daily posting job's fixed-width output field by field; {@code CBACT01C} is a
     * read-only verification step whose entire verb inventory is {@code OPEN}, {@code READ} and {@code CLOSE},
     * so it produces no dataset for any gate to compare. The one log rendering that <i>is</i> a parity
     * contract - the four-character {@code FILE STATUS} of {@code 9910-DISPLAY-IO-STATUS} - is owned by
     * {@code FileStatusMapper} and is untouched.
     * <p>
     * <b>Side effects.</b> Writes one class-logger event when DEBUG is enabled there, and twelve events to
     * {@value #PARITY_LOGGER_NAME} when that logger is enabled at DEBUG - which is not the shipped state.
     *
     * @param account the record just read; must not be {@code null}
     * @throws NullPointerException if {@code account} is {@code null}, which the caller's {@code '00'} branch
     *     makes unreachable and which is asserted rather than assumed
     */
    private void displayAccountRecord(Account account) {
        // Asserted, not assumed, even though no field of it is read: the '00' branch promises a record, and a
        // null here would mean the status vocabulary and the returned value disagree, which is a defect worth
        // failing on rather than logging past.
        Objects.requireNonNull(account, "account must not be null when the file status is '00'");

        if (LOG.isDebugEnabled()) {
            LOG.debug("{} record accepted; sequence={}", LOGICAL_FILE, Long.valueOf(recordsRead + 1));
        }

        // Rendering eleven fixed-width values costs more than the guard, so the guard comes first. When the
        // parity logger is off - which it is in every shipped profile - the paragraph produces no field output,
        // which is the only observable difference from the source.
        if (!PARITY_LOG.isDebugEnabled()) {
            return;
        }

        // Positionally paired with FIELD_LABELS, in the exact order of :L119-L129. The five monetary fields
        // are REDACTED: they are customer financial data and no masking rule in logback-spring.xml covers a
        // bare digit run, so the value must not leave this method. Field order, count and width are
        // preserved, so the emission still proves the layout the paragraph displays, and an absent field
        // still renders as spaces - see renderRedactedMoney.
        String[] values = {
            renderAccountId(account.getAccountId()),
            renderText(account.getActiveStatus(), ACTIVE_STATUS_WIDTH),
            renderRedactedMoney(account.getCurrentBalance()),
            renderRedactedMoney(account.getCreditLimit()),
            renderRedactedMoney(account.getCashCreditLimit()),
            renderText(account.getOpenDate(), TEN_CHARACTER_WIDTH),
            renderText(account.getExpiraionDate(), TEN_CHARACTER_WIDTH),
            renderText(account.getReissueDate(), TEN_CHARACTER_WIDTH),
            renderRedactedMoney(account.getCurrentCycleCredit()),
            renderRedactedMoney(account.getCurrentCycleDebit()),
            renderText(account.getGroupId(), TEN_CHARACTER_WIDTH),
        };

        for (int field = 0; field < FIELD_LABELS.length; field++) {
            PARITY_LOG.debug("{}{}", FIELD_LABELS[field], values[field]);
        }

        // DISPLAY '-------------------------------------------------'  (:L130) - 49 hyphens, measured.
        PARITY_LOG.debug(RECORD_SEPARATOR);
    }

    // ====================================================================================================
    // 0000-ACCTFILE-OPEN, app/cbl/CBACT01C.cbl:L133-L149.
    // ====================================================================================================

    /**
     * Opens the account master, reproducing {@code 0000-ACCTFILE-OPEN}
     * ({@code app/cbl/CBACT01C.cbl:L133-L149}).
     * <p>
     * <b>Arithmetic idiom, parity structure 4.</b> This paragraph primes the result field with
     * {@code MOVE 8 TO APPL-RESULT} at {@code :L134}. Its counterpart {@code closeAccountFile()} primes the
     * same field with {@code ADD 8 TO ZERO GIVING APPL-RESULT} at {@code :L152} and clears it with
     * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} at {@code :L155}. The two paragraphs compute identical
     * values by different verbs. They are kept as two methods, each documenting the idiom its own source
     * paragraph uses, and are deliberately not normalised into one shape.
     * <p>
     * <b>What stands in for {@code OPEN INPUT}.</b> There is no file handle to acquire, so the analogue is a
     * single {@link AccountRepository#count()} round trip. It establishes exactly what a VSAM open establishes
     * &mdash; that the dataset is reachable &mdash; because an unreachable relation surfaces as a
     * {@link DataAccessException}, which is the counterpart of file status {@code '35'} or the {@code '9x'}
     * family. It also yields the row count, which makes the empty-relation case an explicit, logged outcome
     * rather than something inferred later from an absence of records (Rule 1 clause B2). The call returns a
     * scalar, so no sort applies to it.
     * <p>
     * <b>Side effects.</b> One store round trip; sets the open flag and the row count; writes one log event on
     * success and two before abending on failure.
     *
     * @throws FatalProcessingException if the relation cannot be reached, reproducing
     *     {@code DISPLAY 'ERROR OPENING ACCTFILE'} then {@code PERFORM 9999-ABEND-PROGRAM} at
     *     {@code :L144-L147}
     */
    private void openAccountFile() {
        // MOVE 8 TO APPL-RESULT.  (:L134) - the OPEN idiom. See the arithmetic note above.
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        DataAccessException storeFailure = null;
        try {
            // OPEN INPUT ACCTFILE-FILE  (:L135)
            recordCountAtOpen = accountRepository.count();
            ioStatus = STATUS_SUCCESS;
        } catch (DataAccessException failure) {
            storeFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF ACCTFILE-STATUS = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 TO APPL-RESULT  (:L136-L140).
        // Delegated rather than restated: this two-way test is what applResultForGuard implements.
        applResult = fileStatusMapper.applResultForGuard(ioStatus);

        if (applResult == FileStatusMapper.APPL_AOK) {
            // IF APPL-AOK CONTINUE  (:L141-L142)
            fileOpen = true;
            if (recordCountAtOpen == 0L) {
                // An empty relation is a successful, complete run, not a fault. Stated explicitly so an
                // operator is never left to infer it from silence.
                LOG.info("{} opened and is empty; the scan will complete with a row count of zero",
                        LOGICAL_FILE);
            } else {
                LOG.info("{} opened; rows available={}", LOGICAL_FILE, Long.valueOf(recordCountAtOpen));
            }
        } else {
            // ELSE DISPLAY 'ERROR OPENING ACCTFILE' / MOVE ACCTFILE-STATUS TO IO-STATUS /
            // PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM  (:L144-L147). Note the wording:
            // 'ACCTFILE' here, 'ACCOUNT FILE' on the read and close paths. Preserved, see ERROR_OPENING_MESSAGE.
            LOG.error(ERROR_OPENING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            abendProgram(ERROR_OPENING_MESSAGE, storeFailure);
        }
        // EXIT.  (:L149)
    }

    // ====================================================================================================
    // 9000-ACCTFILE-CLOSE, app/cbl/CBACT01C.cbl:L151-L167.
    // ====================================================================================================

    /**
     * Closes the account master, reproducing {@code 9000-ACCTFILE-CLOSE}
     * ({@code app/cbl/CBACT01C.cbl:L151-L167}).
     * <p>
     * <b>Arithmetic idiom, parity structure 4.</b> Where {@code openAccountFile()} writes
     * {@code MOVE 8 TO APPL-RESULT} ({@code :L134}), this paragraph writes
     * {@code ADD 8 TO ZERO GIVING APPL-RESULT} ({@code :L152}); where the open branch writes
     * {@code MOVE 0} and {@code MOVE 12} ({@code :L137}, {@code :L139}), this one writes
     * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} ({@code :L155}) and
     * {@code ADD 12 TO ZERO GIVING APPL-RESULT} ({@code :L157}). Every pair computes the same value by a
     * different verb. The variation is recorded here and in the open paragraph rather than being tidied away,
     * and the two paragraphs remain two methods.
     * <p>
     * <b>What stands in for {@code CLOSE}.</b> Releasing the page buffer and the cursor. No store round trip is
     * needed, and none is made, because a read-only scan holds nothing that requires committing. The teardown
     * is nevertheless guarded exactly as the source guards its close, so the failure branch remains reachable
     * for any runtime fault raised while releasing the cursor rather than being unreachable by construction.
     * <p>
     * <b>Side effects.</b> Clears the buffer, the cursor and the open flag. Writes two log events only when the
     * close fails.
     *
     * @throws FatalProcessingException if releasing the cursor raises a runtime fault, reproducing
     *     {@code DISPLAY 'ERROR CLOSING ACCOUNT FILE'} then {@code PERFORM 9999-ABEND-PROGRAM} at
     *     {@code :L162-L165}
     */
    private void closeAccountFile() {
        // ADD 8 TO ZERO GIVING APPL-RESULT.  (:L152) - the CLOSE idiom, distinct from the open's MOVE 8.
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        RuntimeException teardownFailure = null;
        try {
            // CLOSE ACCTFILE-FILE  (:L153)
            pageBuffer = List.of();
            pageBufferIndex = 0;
            accountRecord = null;
            fileOpen = false;
            ioStatus = STATUS_SUCCESS;
        } catch (RuntimeException failure) {
            teardownFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF ACCTFILE-STATUS = '00' SUBTRACT APPL-RESULT FROM APPL-RESULT ELSE
        // ADD 12 TO ZERO GIVING APPL-RESULT  (:L154-L158). Both branches compute exactly what
        // applResultForGuard returns - zero and twelve - so the shared translator is consulted instead of the
        // arithmetic being restated (Rule 1 clause C3). The source's verbs are recorded above.
        applResult = fileStatusMapper.applResultForGuard(ioStatus);

        // IF APPL-AOK CONTINUE  (:L159-L160)
        if (applResult != FileStatusMapper.APPL_AOK) {
            // ELSE DISPLAY 'ERROR CLOSING ACCOUNT FILE' / MOVE ACCTFILE-STATUS TO IO-STATUS /
            // PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM  (:L162-L165)
            LOG.error(ERROR_CLOSING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            abendProgram(ERROR_CLOSING_MESSAGE, teardownFailure);
        }
        // EXIT.  (:L167)
    }

    // ====================================================================================================
    // 9999-ABEND-PROGRAM, app/cbl/CBACT01C.cbl:L169-L173.
    // ====================================================================================================

    /**
     * Abends the step, reproducing {@code 9999-ABEND-PROGRAM} ({@code app/cbl/CBACT01C.cbl:L169-L173}).
     * <p>
     * The source emits {@code 'ABENDING PROGRAM'} ({@code :L170}), zeroes {@code TIMING} ({@code :L171}), moves
     * {@code 999} into {@code ABCODE} ({@code :L172}) and calls the Language Environment abend service
     * ({@code :L173}). The Java counterpart throws {@link FatalProcessingException} carrying the full
     * {@code CABENDD.CPY} payload: abend code
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE}, this program as the culprit,
     * the failing operation as the reason, and the legacy message as the message. Process return code
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE} is the exit code that the job's
     * status mapping derives from this exception; it is not set here, because a reader does not own the
     * process exit code.
     * <p>
     * {@code TIMING} has no counterpart. It is a Language Environment abend parameter that selects whether a
     * dump is taken, and there is no dump facility to select; the exception carries the stack trace that
     * replaces it.
     * <p>
     * <b>This method always throws and never returns normally.</b>
     *
     * @param message the legacy message literal that preceded the abend, used as both the reason and the
     *     abend message so the failing operation is identifiable from either field
     * @param cause the throwable that provoked the abend, or {@code null} when the status alone identified the
     *     fault; always attached when present, so the root cause is never lost (Rule 1 clause B4)
     * @throws FatalProcessingException always
     */
    private void abendProgram(String message, Throwable cause) {
        // DISPLAY 'ABENDING PROGRAM'  (:L170)
        LOG.error(ABENDING_PROGRAM_MESSAGE);

        // MOVE 0 TO TIMING (:L171) / MOVE 999 TO ABCODE (:L172) / CALL 'CEE3ABD'. (:L173)
        throw new FatalProcessingException(
                Integer.toString(FatalProcessingException.BATCH_ABEND_CODE),
                ABEND_CULPRIT,
                message,
                String.format(Locale.ROOT, "%s (%s, file status %s)", message, LOGICAL_FILE,
                        displayIoStatus(ioStatus)),
                cause);
    }

    // ====================================================================================================
    // 9910-DISPLAY-IO-STATUS, app/cbl/CBACT01C.cbl:L176-L189.
    // ====================================================================================================

    /**
     * Renders a file status as the legacy diagnostic line, reproducing {@code 9910-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBACT01C.cbl:L176-L189}).
     * <p>
     * The paragraph has two branches. When {@code IO-STATUS} is not numeric or its first byte is {@code '9'}
     * ({@code :L177-L178}), byte one is copied into position one and byte two is widened through
     * {@code TWO-BYTES-BINARY} into three digits ({@code :L179-L182}). Otherwise the field is set to
     * {@code '0000'} and the two status characters are overlaid at positions three and four
     * ({@code :L185-L186}). Both branches then emit {@code 'FILE STATUS IS: NNNN'} followed by the four-character
     * field ({@code :L183}, {@code :L187}).
     * <p>
     * <b>{@code 'FILE STATUS IS: NNNN'} is a fixed 20-character literal, not a template.</b> The
     * {@code NNNN} is part of the constant text and the four rendered characters follow it, so status
     * {@code '23'} renders as {@code FILE STATUS IS: NNNN0023} and never as {@code FILE STATUS IS: 0023}.
     * Substituting the digits into the {@code NNNN} would be a parity break.
     * <p>
     * <b>This method delegates and holds no logic of its own</b>, which is deliberate. The identical paragraph
     * recurs across the batch corpus, and {@link FileStatusMapper#displayIoStatus(String)} is the single
     * implementation of it; {@link FileStatus#DISPLAY_MESSAGE_PREFIX} is the single definition of the literal.
     * Re-deriving either here would be the parallel mapping that Rule 1 clause C3 forbids. The method is
     * retained rather than inlined so the paragraph remains individually traceable.
     * <p>
     * It is a pure function of its argument: it reads and writes no field of this instance.
     *
     * @param fileStatus the raw status, ordinarily two characters, and tolerated when {@code null}, shorter or
     *     longer, exactly as a COBOL {@code MOVE} into a two-byte group tolerates a mismatched sending field
     * @return the complete legacy line, never {@code null}, always 24 characters: the 20-character prefix
     *     followed by exactly four rendered characters
     */
    private String displayIoStatus(String fileStatus) {
        return fileStatusMapper.displayIoStatus(fileStatus);
    }

    // ====================================================================================================
    // Fixed-width rendering, supporting DISPLAY ACCOUNT-RECORD at app/cbl/CBACT01C.cbl:L78 and the eleven
    // field lines of 1100-DISPLAY-ACCT-RECORD. Every method below is static and pure.
    // ====================================================================================================

    /**
     * Renders the whole record as one {@value #RECORD_LENGTH}-character image, standing in for
     * {@code DISPLAY ACCOUNT-RECORD} at {@code app/cbl/CBACT01C.cbl:L78}.
     * <p>
     * Fields are laid out at the byte offsets that {@code app/cpy/CVACT01Y.cpy} declares: identifier 1-11,
     * active status 12, current balance 13-24, credit limit 25-36, cash credit limit 37-48, open date 49-58,
     * expiration date 59-68, reissue date 69-78, current-cycle credit 79-90, current-cycle debit 91-102,
     * postal code 103-112, group identifier 113-122, filler 123-300. The offsets sum to exactly
     * {@value #RECORD_LENGTH}, which the FD at {@code app/cbl/CBACT01C.cbl:L37-L40}, the catalogue entry at
     * {@code app/catlg/LISTCAT.txt:L59}, the cluster definition at {@code app/jcl/ACCTFILE.jcl:L40-L41} and the
     * measured width of {@code app/data/ASCII/acctdata.txt} all independently confirm.
     * <p>
     * <b>Finding, severity Low: the zoned-decimal sign is not encoded.</b> A COBOL {@code DISPLAY} of a
     * {@code PIC S9(10)V99} item emits its twelve storage bytes, in which the sign is overpunched onto the last
     * digit &mdash; the fixture's first record reads {@code 00000001940&#123;} for {@code +194.00}, where
     * {@code &#123;} encodes both the digit zero and a positive sign
     * ({@code app/data/ASCII/acctdata.txt:L1}, decoded position-aware). This method emits the same twelve
     * character positions with a plain digit in the final one, so each money field differs from the legacy image
     * in exactly one byte and the 300-character geometry is preserved intact.
     * <i>Why:</i> the zoned-decimal codec is owned elsewhere. {@link Account#toString()} states in its own
     * contract that emitting the byte-exact record image is the job of the fixed-width writers, and the
     * position-aware decode of the fixture belongs to
     * {@code src/main/resources/db/migration/V3__seed_data.sql}. Implementing a third copy inside a read-only
     * verification reader is the duplication that Rule 1 clause C3 forbids, and it would be unreachable from
     * any writer, which clause B forbids as dead code.
     * <i>Remediation:</i> if a byte-exact image is ever required from this step, call the writers' codec once it
     * exists rather than adding one here.
     * <p>
     * <b>Emitted through {@link #PARITY_LOG}</b> by {@link #read()}, never through the class logger, and with
     * every present monetary field replaced by {@link #REDACTED_MONEY}. Both controls are deliberate: the
     * record carries balances and both credit limits, no masking rule downstream can tell one from an account
     * identifier, and being an unlabelled 300-character run the image presents nothing for the rules in
     * {@code src/main/resources/logback-spring.xml} to match. Every shipped profile sets
     * {@value #PARITY_LOGGER_NAME} to {@code OFF}, so no deployment renders it at all.
     *
     * @param account the record to render; must not be {@code null}
     * @return exactly {@value #RECORD_LENGTH} characters, never {@code null}
     * @throws NullPointerException if {@code account} is {@code null}
     */
    private static String renderRedactedAccountRecord(Account account) {
        Objects.requireNonNull(account, "account must not be null");

        StringBuilder image = new StringBuilder(RECORD_LENGTH);
        image.append(renderAccountId(account.getAccountId()));
        image.append(renderText(account.getActiveStatus(), ACTIVE_STATUS_WIDTH));
        // The five monetary fields are REDACTED, each by a token of the same width, so the geometry below
        // still lands every subsequent field at its copybook offset. See REDACTED_MONEY for why the value
        // cannot be emitted and why downstream masking cannot substitute for this, and renderRedactedMoney
        // for why an absent field still renders as spaces rather than as the token.
        image.append(renderRedactedMoney(account.getCurrentBalance()));
        image.append(renderRedactedMoney(account.getCreditLimit()));
        image.append(renderRedactedMoney(account.getCashCreditLimit()));
        image.append(renderText(account.getOpenDate(), TEN_CHARACTER_WIDTH));
        image.append(renderText(account.getExpiraionDate(), TEN_CHARACTER_WIDTH));
        image.append(renderText(account.getReissueDate(), TEN_CHARACTER_WIDTH));
        image.append(renderRedactedMoney(account.getCurrentCycleCredit()));
        image.append(renderRedactedMoney(account.getCurrentCycleDebit()));

        // ACCT-ADDR-ZIP is present HERE, in the whole-record image, because the record contains it. It is
        // absent only from the field-by-field paragraph, which is parity structure 3. The two emissions
        // legitimately differ, and that difference is the source's, not this class's.
        image.append(renderText(account.getAddressZip(), TEN_CHARACTER_WIDTH));
        image.append(renderText(account.getGroupId(), TEN_CHARACTER_WIDTH));

        // FILLER PIC X(178), bytes 123-300. Unmodelled by any property and therefore always spaces.
        image.append(String.valueOf(ALPHANUMERIC_PAD).repeat(TRAILING_FILLER_WIDTH));

        return image.toString();
    }

    /**
     * Renders one monetary field of the account record without disclosing its value.
     * <p>
     * A field that carries a value renders as {@link #REDACTED_MONEY}; a field that carries none renders as
     * {@value #MONEY_DIGITS} spaces. Both are exactly {@value #MONEY_DIGITS} characters wide, so every
     * subsequent field still lands at its copybook offset and the 300-byte geometry of {@code CVACT01Y} is
     * unchanged either way.
     * <p>
     * <b>Why the two cases are kept distinct.</b> "Withheld" and "never set" are different facts about the
     * row, and only the first is a redaction. A COBOL {@code MOVE} of an uninitialised numeric item leaves
     * spaces, so rendering an absent field as spaces is what the source does, and it discloses nothing: an
     * absent value has no digits to leak. Collapsing the two would hide a genuine data condition - a row with
     * no balance at all - behind a privacy token, which is the one thing this rendering exists to make
     * visible. The same distinction is drawn by {@code InterestCalculationProcessor} on {@code TRAN-CAT-BAL}
     * and by {@code TransactionReportProcessor} on {@code TRAN-AMT}.
     *
     * @param value the monetary field, tolerated when {@code null}
     * @return exactly {@value #MONEY_DIGITS} characters, never {@code null}
     */
    private static String renderRedactedMoney(BigDecimal value) {
        return value == null
                ? String.valueOf(ALPHANUMERIC_PAD).repeat(MONEY_DIGITS)
                : REDACTED_MONEY;
    }

    /**
     * Renders {@code ACCT-ID PIC 9(11)} as {@value #ACCOUNT_ID_DIGITS} zero-padded digits.
     * <p>
     * The picture clause is unsigned, so a negative value contributes its digits without a sign, exactly as a
     * COBOL {@code MOVE} into an unsigned numeric item would. Subtraction of the sign is done textually rather
     * than by {@code Math.abs}, which would overflow on {@link Long#MIN_VALUE}.
     *
     * @param accountId the identifier, tolerated when {@code null}
     * @return exactly {@value #ACCOUNT_ID_DIGITS} characters: digits, or spaces when {@code accountId} is
     *     {@code null}, which is the COBOL rendering of an uninitialised field
     */
    private static String renderAccountId(Long accountId) {
        if (accountId == null) {
            return String.valueOf(ALPHANUMERIC_PAD).repeat(ACCOUNT_ID_DIGITS);
        }
        String digits = Long.toString(accountId.longValue());
        if (digits.startsWith("-")) {
            digits = digits.substring(1);
        }
        return renderDigits(digits, ACCOUNT_ID_DIGITS);
    }

    /**
     * Fits a digit string to a numeric field of the given width, padding on the left with
     * {@value #NUMERIC_PAD} and truncating the <b>high-order</b> digits when the value is too long.
     * <p>
     * High-order truncation is what a COBOL {@code MOVE} into a shorter numeric item does; truncating the
     * low-order end instead would silently change the magnitude by a power of ten rather than merely losing the
     * leading digits, so the direction matters.
     *
     * @param digits the digit string, never {@code null}
     * @param width the field width in characters, always positive at every call site
     * @return exactly {@code width} characters
     */
    private static String renderDigits(String digits, int width) {
        int length = digits.length();
        if (length == width) {
            return digits;
        }
        if (length > width) {
            return digits.substring(length - width);
        }
        return String.valueOf(NUMERIC_PAD).repeat(width - length) + digits;
    }

    /**
     * Fits a value to a {@code PIC X(n)} field, padding on the right with a space and truncating on the right,
     * which is how a COBOL {@code MOVE} into an alphanumeric item behaves.
     * <p>
     * A value of only spaces is returned unchanged and is never trimmed, defaulted or treated as absent.
     * {@code ACCT-GROUP-ID} is ten spaces in all fifty rows of {@code app/data/ASCII/acctdata.txt} (measured),
     * so blank is a legitimate value for that field and normalising it would corrupt the record image.
     * <p>
     * Length is checked before any substring is taken, because a row arriving from the store is treated as
     * untrusted input regardless of the column widths the schema declares (Rule 1 clause A2).
     *
     * @param value the field value, tolerated when {@code null}
     * @param width the declared field width in characters, always positive at every call site
     * @return exactly {@code width} characters: the value padded or truncated, or spaces when {@code value} is
     *     {@code null}
     */
    private static String renderText(String value, int width) {
        if (value == null) {
            return String.valueOf(ALPHANUMERIC_PAD).repeat(width);
        }
        int length = value.length();
        if (length == width) {
            return value;
        }
        if (length > width) {
            return value.substring(0, width);
        }
        return value + String.valueOf(ALPHANUMERIC_PAD).repeat(width - length);
    }

    // ====================================================================================================
    // Restart support and construction-time validation. No legacy counterpart: the mainline at
    // app/cbl/CBACT01C.cbl:L70-L87 always scans from the first record, because a JES2 job restart re-ran the
    // step from the top. Restartability is additive, and it changes no emitted value.
    // ====================================================================================================

    /**
     * Restores the checkpoint written by {@link #update(ExecutionContext)} so a restarted step resumes instead
     * of re-emitting rows.
     * <p>
     * <b>The checkpointed key is the position; the row count is only a tally.</b> The scan resumes by seeking
     * to {@code ACCT-ID > } the last identifier actually emitted, so the first window of the resumed run begins
     * at the row after it regardless of how many rows precede it. The predecessor of this method instead
     * divided the row count into a page number and a within-page offset, which positions correctly only while
     * the relation is unchanged between the two runs: any row inserted or deleted below the cursor shifts every
     * offset after it, so a restart could silently re-emit or silently skip rows. Seeking by key is immune to
     * that, because the key of a row does not move when its neighbours change. The row count is still restored,
     * but purely so the end-of-run tally continues from where it stopped.
     * <p>
     * A non-positive checkpoint is ignored and the scan starts from the beginning, which is the correct reading
     * of a checkpoint written before any row was emitted.
     *
     * @param executionContext the step execution context, already known to contain the row-count key
     * @throws IllegalStateException if the context records that rows were emitted but carries no identifier to
     *     resume from, which leaves no position to seek to and which is reported rather than silently
     *     downgraded to a restart from the beginning
     */
    private void restoreRestartCursor(ExecutionContext executionContext) {
        long checkpointed = executionContext.getLong(CONTEXT_KEY_RECORDS_READ, 0L);
        if (checkpointed <= 0L) {
            return;
        }

        // Explicit handled case (Rule 1 clause B2). update() writes both keys together whenever a row has been
        // emitted, so this state cannot arise from this class; a hand-built context can still present it. The
        // alternative to failing here would be to restart from the beginning, which would re-emit every row
        // already emitted while reporting success, so the failure is deliberately loud.
        if (!executionContext.containsKey(CONTEXT_KEY_LAST_ACCOUNT_ID)) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "%s restart context records %d rows already emitted but carries no '%s' entry, so there "
                            + "is no key to resume the keyset scan from; restarting from the first row would "
                            + "re-emit those %d rows", LOGICAL_FILE, Long.valueOf(checkpointed),
                    CONTEXT_KEY_LAST_ACCOUNT_ID, Long.valueOf(checkpointed)));
        }

        recordsRead = checkpointed;
        lastAccountId = Long.valueOf(executionContext.getLong(CONTEXT_KEY_LAST_ACCOUNT_ID));
        fetchCursorAccountId = lastAccountId.longValue();

        LOG.info("Resuming {} scan after {} rows; seeking to ACCT-ID greater than {}",
                LOGICAL_FILE, Long.valueOf(recordsRead), lastAccountId);
    }

    /**
     * Validates the injected page size.
     * <p>
     * Declared {@code private static} so the constructor can call it without invoking an overridable method,
     * which would publish a partially constructed reference; {@code -Xlint:all -Werror} reports that as
     * {@code this-escape}, and the class cannot be final because the {@code step} scope proxies by subclassing.
     *
     * @param pageSize the configured value
     * @return {@code pageSize}, unchanged
     * @throws IllegalArgumentException if {@code pageSize} is less than one, because a page of zero or fewer
     *     rows would make the scan loop without ever advancing
     */
    private static int requirePositivePageSize(int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "carddemo.batch.account-reader.page-size must be at least 1 but was %d; a non-positive "
                            + "page cannot advance the sequential scan of ACCTFILE", Integer.valueOf(pageSize)));
        }
        return pageSize;
    }

    /**
     * Extracts the two-character code of a {@link FileStatus} that is expected to be an exact value rather than
     * a family, so the literals this class compares against are derived from the single definition of the status
     * vocabulary instead of being restated as string constants (Rule 1 clause C3).
     *
     * @param status the status constant, expected to be an exact value
     * @return its two-character code
     * @throws IllegalStateException if {@code status} is a family and exposes no exact code, which would mean
     *     the enum contract had changed underneath this class
     */
    private static String requireExactCode(FileStatus status) {
        return status.code().orElseThrow(() -> new IllegalStateException(String.format(Locale.ROOT,
                "FileStatus.%s must expose an exact two-character code; it reports itself as a family",
                status.name())));
    }
}
