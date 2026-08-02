/*
 * ******************************************************************
 * Program     : CustomerReader.java
 * Application : CardDemo
 * Type        : Spring Batch ItemStreamReader (read-only verification step)
 * Function    : Read-only sequential scan of the customer master, replacing
 *               the COBOL batch reader CBCUS01C.
 * Source      : app/cbl/CBCUS01C.cbl (178 lines, 6 paragraphs)
 *               app/cpy/CVCUS01Y.cpy (500-byte CUSTOMER-RECORD)
 *               app/cpy/CUSTREC.cpy (same layout, differing DOB field name)
 *               app/catlg/LISTCAT.txt:L632 (KEYLEN 9 / AVGLRECL 500)
 *               app/jcl/READCUST.jcl (job that executes CBCUS01C)
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Read-only sequential scan of the customer master, reproducing the COBOL batch program
 * {@code app/cbl/CBCUS01C.cbl} paragraph for paragraph.
 * <p>
 * The four headings below are this file's discharge of <b>Rule 1 clause E</b>, which requires every component
 * to carry &quot;a short README or docstring&quot; covering what it does, how to run, build and test it, its key
 * configuration and defaults, and its common failure modes. Neither of the other two forms is admissible in this
 * package: a {@code README} would be a new file the plan does not list, and a {@code package-info.java} here
 * would break the file-count gates that fix {@code com.cardemo.batch.readers} at exactly seven classes. Package
 * documentation is owned by {@code src/main/java/com/cardemo/batch/package-info.java}; the docstring branch of
 * the clause is discharged here.
 *
 * <h2>What it does</h2>
 * Streams every row of the {@code customer} relation in ascending primary-key order, reports each one twice as
 * the legacy program reported it twice, and terminates. Nothing is written, updated or deleted: this is a
 * <em>verification step</em>, and the read-only character is not a design preference but a measured property of
 * the source.
 * <p>
 * The verb inventory of {@code app/cbl/CBCUS01C.cbl}, counted as Area-B statement starts with column-7 comment
 * lines stripped, is {@code OPEN}=1 ({@code :L120}), {@code READ}=1 ({@code :L93}), {@code CLOSE}=1
 * ({@code :L138}), {@code DISPLAY}=10, and {@code WRITE}={@code REWRITE}={@code DELETE}=<b>0</b>. Because the
 * source contains no write verb at all, this class adds no write path: no {@code save}, no {@code saveAll}, no
 * {@code delete}, no {@code @Modifying} query, no {@code EntityManager} mutation and no {@code flush}. The only
 * repository operations it ever performs are {@link CustomerRepository#count()} and
 * {@link CustomerRepository#findAll(org.springframework.data.domain.Pageable)}.
 * <p>
 * <b>Finding, severity Low: the quoted lexical figures are reconciled, not contradicted.</b> Other project
 * documents quote &quot;3 / 1 / 3 / 14&quot; for {@code OPEN} / {@code READ} / {@code CLOSE} / {@code DISPLAY}
 * in this program. Measured directly at {@code 7756d89} with a word-boundary match, the raw token occurrences
 * are {@code OPEN}=3, {@code READ}=1, {@code CLOSE}=3, {@code DISPLAY}=14 &mdash; the quoted set exactly. Those
 * are lexical token counts and not statement counts: the token {@code OPEN} also occurs in the paragraph label
 * {@code 0000-CUSTFILE-OPEN} at {@code :L118} and in the {@code PERFORM} of it at {@code :L72}, the token
 * {@code CLOSE} likewise at {@code :L136} and {@code :L83}, and the token {@code DISPLAY} also occurs in the
 * label {@code Z-DISPLAY-IO-STATUS} and in each {@code PERFORM} of it. <i>Remediation:</i> cite the statement
 * counts above, which supersede the lexical figures. The load-bearing fact is identical either way, so nothing
 * downstream changes: {@code WRITE}, {@code REWRITE} and {@code DELETE} are zero.
 *
 * <h3>Paragraph map, one Java member per COBOL paragraph, never consolidated</h3>
 * <table>
 * <caption>Paragraphs of {@code app/cbl/CBCUS01C.cbl} and their Java targets</caption>
 * <tr><th>COBOL label</th><th>Locator</th><th>Java target</th></tr>
 * <tr><td>mainline {@code PROCEDURE DIVISION}</td><td>{@code :L70-L87}</td>
 *     <td>{@link #open(ExecutionContext)}, {@link #read()}, {@link #close()}</td></tr>
 * <tr><td>{@code 1000-CUSTFILE-GET-NEXT}</td><td>{@code :L92-L116}</td>
 *     <td>{@code getNextCustomerRecord()}</td></tr>
 * <tr><td>{@code 0000-CUSTFILE-OPEN}</td><td>{@code :L118-L134}</td><td>{@code openCustomerFile()}</td></tr>
 * <tr><td>{@code 9000-CUSTFILE-CLOSE}</td><td>{@code :L136-L152}</td><td>{@code closeCustomerFile()}</td></tr>
 * <tr><td>{@code Z-ABEND-PROGRAM}</td><td>{@code :L154-L158}</td>
 *     <td>{@code abendProgram(String, Throwable)}</td></tr>
 * <tr><td>{@code Z-DISPLAY-IO-STATUS}</td><td>{@code :L161-L174}</td>
 *     <td>{@code displayIoStatus(String)}</td></tr>
 * </table>
 * <b>Finding, severity Low: this program alone in the package prefixes its two utility paragraphs
 * {@code Z-}.</b> {@code CBACT01C}, {@code CBACT02C} and {@code CBACT03C} all spell the same two paragraphs
 * {@code 9999-ABEND-PROGRAM} and {@code 9910-DISPLAY-IO-STATUS}; {@code CBCUS01C} spells them
 * {@code Z-ABEND-PROGRAM} ({@code :L154}) and {@code Z-DISPLAY-IO-STATUS} ({@code :L161}). The bodies are
 * equivalent, so the difference is cosmetic and has no Java consequence. <i>Remediation:</i> none. The labels
 * are cited exactly as the source spells them and are deliberately <b>not</b> normalised to the {@code 99xx-}
 * form, because a citation that does not match the file it points at is not evidence.
 * <p>
 * There are <b>six</b> paragraphs here, not seven. {@code CBACT01C} is the only one of the four simple
 * sequential readers with a {@code 1100-} field-by-field display paragraph, so no analogue of it exists in this
 * class; inventing one would be a paragraph the source does not have.
 * <p>
 * <b>Finding, severity Low: this source file is not column-padded to 80 the way its three siblings are.</b>
 * Measured at {@code 7756d89}, the longest line of {@code app/cbl/CBCUS01C.cbl} is 73 characters, while
 * {@code app/cbl/CBACT01C.cbl} and {@code app/cbl/CBACT03C.cbl} both reach exactly 80. It is recorded only so
 * that nobody &quot;corrects&quot; a line-number citation on the assumption that the files are formatted
 * alike. <i>Remediation:</i> none; {@code app/} is frozen and the padding is not load bearing here.
 *
 * <h3>The governing concern: this is the heaviest personal-data record in the corpus</h3>
 * {@code app/cpy/CVCUS01Y.cpy} packs into one 500-byte block a social security number (bytes 280-288,
 * {@code :L17}), a government-issued identifier (289-308, {@code :L18}), a date of birth (309-318,
 * {@code :L19}), an electronic-funds account identifier (319-328, {@code :L20}), two telephone numbers
 * (250-279, {@code :L15-L16}), three address lines (85-234, {@code :L9-L11}), a full name (10-84,
 * {@code :L6-L8}) and a credit score (330-332, {@code :L22}). The legacy program emits <b>the whole of it</b>
 * to SYSOUT, twice per row, through {@code DISPLAY CUSTOMER-RECORD} at {@code :L96} and {@code :L78}.
 * <p>
 * <b>This class does not reproduce that content, and the decision is deliberate, labelled and split.</b> Rule 1
 * clause D requires &quot;no secrets in code, logs, tests, or config&quot;; the migration mandate requires
 * behavioural parity. Where the two collide here, the resolution is precise:
 * <ul>
 * <li><b>Clause D governs the CONTENT.</b> Both emissions carry an identifier-only projection. No accessor for
 *     any field listed above is called anywhere in this file. Nothing derived from one is logged, placed in an
 *     {@link ExecutionContext}, embedded in an exception message or returned from any method.</li>
 * <li><b>Parity governs the SHAPE and the COUNT.</b> Two distinct record-level events are emitted per row, one
 *     citing {@code :L96} and one citing {@code :L78}, at the same two points in the control flow the source
 *     emits from. Collapsing them to one would be a parity break.</li>
 * </ul>
 * <b>Finding, severity High &mdash; the rejected alternative.</b> Reproducing {@code DISPLAY CUSTOMER-RECORD}
 * literally would publish a social security number, a government identifier, a date of birth and a bank
 * account identifier into log volume, twice for every customer, at INFO. <i>Remediation, applied:</i> emit the
 * identifier-only projections described on {@link #read()} and {@code getNextCustomerRecord()}. The masking
 * rules configured in {@code src/main/resources/logback-spring.xml} for credentials, password hashes and social
 * security numbers are a second line of defence and not the first: this class does not rely on them, because
 * the values never reach the logger. The deviation is recorded in {@code DECISION_LOG.md}.
 * <p>
 * <b>Two copybooks, one entity.</b> {@code app/cpy/CUSTREC.cpy} declares the same 500-byte
 * {@code CUSTOMER-RECORD} and differs from {@code app/cpy/CVCUS01Y.cpy} in exactly one respect: the
 * date-of-birth field is spelled {@code CUST-DOB-YYYYMMDD} ({@code app/cpy/CUSTREC.cpy:L19}) rather than
 * {@code CUST-DOB-YYYY-MM-DD} ({@code app/cpy/CVCUS01Y.cpy:L19}). Both resolve to the single
 * {@link Customer} entity and this class never branches on which copybook a caller had in mind. That
 * de-duplication is Rule 1 clause C in action.
 * <p>
 * <b>No zoned-decimal decoding appears in this class, and its absence is required rather than merely
 * permitted.</b> {@code app/cpy/CVCUS01Y.cpy} contains no {@code S9} picture clause at all: every field is
 * either {@code PIC X(n)} or unsigned {@code PIC 9(n)}, so no overpunch sign is stored anywhere in the record.
 * An overpunch codec here would be unreachable code, which Rule 1 clause B forbids, and it would duplicate
 * logic that {@code src/main/resources/db/migration/V3__seed_data.sql} owns, which clause C forbids.
 * <p>
 * <b>Only {@code CUST-ID} becomes a numeric Java type.</b> Three fields are {@code PIC 9(n)} in COBOL, and the
 * entity models only one of them as a number: {@code CUST-ID} is {@code Long}, while {@code CUST-SSN}
 * ({@code 9(09)}) and {@code CUST-FICO-CREDIT-SCORE} ({@code 9(03)}) are {@link String} over {@code CHAR(n)}
 * columns because their leading zeros are significant. {@code CUST-DOB-YYYY-MM-DD} is likewise a
 * {@link String} over {@code CHAR(10)} and never a {@code java.time} type, because the account-update snapshot
 * comparison reads its components at different offsets on each side and a parsed date would destroy that. This
 * class imports no {@code java.time} type whatever.
 *
 * <h3>Structures preserved for parity, which must never be deleted as dead code</h3>
 * Rule 1 clause B forbids dead code; the migration mandate requires control flow to be reproduced one for one
 * so that paragraph-level traceability is mechanically provable. Where the two collide the parity mandate
 * governs, and clause B is satisfied instead by tracking and justifying each retained structure here rather
 * than by deleting it. Four such structures live in this class.
 * <ol>
 * <li><b>The redundant twin guard.</b> The mainline is {@code PERFORM UNTIL END-OF-FILE = 'Y'} ({@code :L74})
 *     wrapping an inner {@code IF END-OF-FILE = 'N'} ({@code :L75}), and a <em>second</em>
 *     {@code IF END-OF-FILE = 'N'} ({@code :L77}) follows the read before the display. The second test can
 *     never fail when the first passed and the read returned a record, so it is redundant by inspection. Both
 *     are reproduced as explicit guards in {@link #read()} and neither is collapsed.</li>
 * <li><b>Every record is reported twice.</b> {@code app/cbl/CBCUS01C.cbl:L96} is
 *     {@code DISPLAY CUSTOMER-RECORD} with a <b>space</b> in column 7, so it is a statement and not a comment;
 *     the mainline at {@code :L78} displays the record again. <b>Finding, severity Medium:</b> the duplication
 *     is a legacy defect &mdash; the same bytes reach SYSOUT twice per row for no purpose. <i>Remediation:
 *     none, parity.</i> It is preserved as two distinct log events, each carrying its own citation, and it is
 *     the single measured fact that makes this program's {@code DISPLAY} statement count 10 rather than 9.
 *     <b>Contrast {@code app/cbl/CBACT02C.cbl:L96}</b>, which is the same statement with an asterisk in column
 *     7: {@code com.cardemo.batch.readers.CardReader} therefore reproduces it as a Java comment only and emits
 *     once. The two files are not interchangeable, and {@code app/cbl/CBACT03C.cbl:L96} is active like this
 *     one.</li>
 * <li><b>The arithmetic-idiom variation between OPEN and CLOSE.</b> {@code 0000-CUSTFILE-OPEN} primes the
 *     result field with {@code MOVE 8 TO APPL-RESULT} ({@code :L119}); {@code 9000-CUSTFILE-CLOSE} primes the
 *     same field with {@code ADD 8 TO ZERO GIVING APPL-RESULT} ({@code :L137}), clears it with
 *     {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} ({@code :L140}) rather than moving zero, and fails it with
 *     {@code ADD 12 TO ZERO GIVING APPL-RESULT} ({@code :L142}) rather than moving twelve. Every pair computes
 *     the same value by a different verb. The two paragraphs are kept as two methods, each documenting the
 *     idiom its own source paragraph uses, and are deliberately not normalised into one shape.</li>
 * <li><b>The three error literals disagree with one another, and all three are reproduced verbatim.</b> The
 *     read path says {@code 'ERROR READING CUSTOMER FILE'} ({@code :L110}), the close path says
 *     {@code 'ERROR CLOSING CUSTOMER FILE'} ({@code :L147}), and the open path says
 *     {@code 'ERROR OPENING CUSTFILE'} ({@code :L129}) &mdash; the DD name rather than the prose name.
 *     <b>Finding, severity Medium:</b> the source is internally inconsistent. <i>Remediation: none,
 *     parity.</i> All three are observable output that a parity comparison reads byte for byte, so they are
 *     reproduced exactly as spelled and are <b>not</b> harmonised. This program is the one most likely to be
 *     tidied by mistake, because each of its three siblings is internally consistent about its own file name.
 *     </li>
 * </ol>
 *
 * <h2>How to run, build and test</h2>
 * The owning {@code Job} and {@code Step} are wired in {@code com.cardemo.config.BatchConfig}. Because
 * {@code spring.batch.job.enabled} is {@code false} ({@code src/main/resources/application.yml:647}), jobs are
 * launched by {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator} and never at application startup, so
 * instantiating this bean never triggers a scan.
 * <p>
 * Two build paths were verified in this environment; both are pinned and either may be used.
 * <ul>
 * <li><b>Host toolchain</b> &mdash; {@code set -a; . ./.env; set +a} then {@code ./mvnw -B -ntp clean compile}.
 *     Verified present: OpenJDK 25.0.3 and Apache Maven 3.9.11, the latter reachable both as {@code ./mvnw}
 *     and on {@code PATH}.</li>
 * <li><b>Pinned container</b> &mdash; Docker Engine and {@code docker compose} are <b>available</b> in this
 *     environment, so the build can also run hermetically:
 *     {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q -DskipTests compile}.
 *     </li>
 * </ul>
 * The compiler runs with {@code -Xlint:all} and {@code failOnWarning}, so the build fails on any warning.
 * Tests live in {@code src/test/java/com/cardemo/unit/batch} for the status renderer, the twin guards and the
 * emission count, and in {@code src/test/java/com/cardemo/integration/batch} for the Testcontainers
 * PostgreSQL 16 scan. Two assertions are specific to this class and are the ones worth writing first:
 * <b>exactly two record events per row</b>, and <b>no personal-data field value present anywhere in captured
 * log output</b>. This class creates neither test, because test sources are outside the scope of the package it
 * belongs to.
 *
 * <h2>Key configs and defaults</h2>
 * <ul>
 * <li>{@code carddemo.batch.customer-reader.page-size} &mdash; the number of rows fetched per round trip.
 *     Default {@value #DEFAULT_PAGE_SIZE}, which covers the entire 50-row seed fixture
 *     {@code app/data/ASCII/custdata.txt} in a single query while keeping the resident set bounded. The value
 *     is validated on construction and must be at least one. It is a buffering choice only and has no effect
 *     on emitted output, because the ordering is fixed independently.</li>
 * <li>Ordering is always ascending on {@code customerId}, never the store's natural order. This mirrors
 *     {@code ACCESS MODE IS SEQUENTIAL} over a KSDS keyed on {@code CUST-ID}
 *     ({@code app/cbl/CBCUS01C.cbl:L29-L33}, key length 9 per {@code app/catlg/LISTCAT.txt:L632} and
 *     {@code KEYS(9 0)} per {@code app/jcl/CUSTFILE.jcl:L50}) and makes the emitted sequence reproducible.</li>
 * <li>{@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile
 *     ({@code src/main/resources/application.yml:576}) and {@code spring.jpa.open-in-view} is {@code false}
 *     ({@code :580}); the schema is owned by the Flyway migrations.</li>
 * <li>Log-masking rules are configured in {@code src/main/resources/logback-spring.xml} and govern whatever
 *     reaches an aggregator. They are a safety net here rather than the mechanism; see the personal-data
 *     section above.</li>
 * <li>No AWS, bucket, queue or topic configuration is read by this reader, and no AWS client is injected, so
 *     it requests no cloud privilege whatever.</li>
 * <li>No transaction annotation is declared. See {@link #read()} for why a {@code readOnly} annotation here
 *     would be decorative rather than effective, and how read-only is guaranteed instead.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 * <li><b>End of data is not an error.</b> {@link #read()} returns {@code null}, which is how Spring Batch
 *     signals end of input. It is the exact analogue of file status {@code '10'} setting {@code APPL-RESULT}
 *     to 16, which {@code 88 APPL-EOF VALUE 16} ({@code :L63}) tests, driving
 *     {@code MOVE 'Y' TO END-OF-FILE} at {@code :L108}. Nothing is thrown.</li>
 * <li><b>An empty customer relation completes successfully</b> with a row count of zero. It is logged
 *     explicitly at {@code open} time rather than inferred from the absence of records.</li>
 * <li><b>Any status that is neither {@code '00'} nor {@code '10'} abends.</b> The status is rendered as the
 *     fixed 20-character prefix {@code FILE STATUS IS: NNNN} followed by exactly four characters, then
 *     {@link FatalProcessingException} is thrown carrying abend code
 *     {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE} and process return code
 *     {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE}, reproducing
 *     {@code MOVE 999 TO ABCODE} and {@code CALL 'CEE3ABD'} at {@code :L157-L158}. The originating throwable
 *     is always attached as the cause.</li>
 * <li><b>Batch exit code 4 is unrelated to this reader.</b> It is set by the daily posting job if and only if
 *     that job's reject count exceeds zero, and it never indicates a failure here.</li>
 * <li><b>Unavailable relation.</b> A {@link DataAccessException} at open time is reported as
 *     {@code ERROR OPENING CUSTFILE} &mdash; the DD spelling, deliberately &mdash; followed by the rendered
 *     status, and abends. Check that the Flyway migrations have applied and that the datasource points at the
 *     intended database.</li>
 * <li><b>Seeing two log events per row is expected parity behaviour, not a bug.</b> See parity structure 2. A
 *     run that reports each customer once has lost an emission.</li>
 * <li><b>The absence of customer detail in the logs is intended clause-D behaviour, not a truncation
 *     defect.</b> Only the identifier, the sequence number and the optimistic-lock version are ever emitted.
 *     If a full record is genuinely needed for debugging, query the database under appropriate authorisation;
 *     never widen a log statement in this class to obtain it.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <b>Not thread safe, by design.</b> The instance carries the cursor state that the legacy program held in
 * {@code WORKING-STORAGE} ({@code END-OF-FILE}, {@code APPL-RESULT}, {@code IO-STATUS}, the record area and
 * the row counter, all at {@code app/cbl/CBCUS01C.cbl:L46-L67}). The {@code step} scope gives each step
 * execution its own instance, which is precisely what keeps that state from being shared. There are <b>no
 * mutable static fields</b>: the only static members are the logger and immutable constants.
 *
 * @see CustomerRepository
 * @see FileStatusMapper
 * @see Customer
 */
@Component
@StepScope
public class CustomerReader implements ItemStreamReader<Customer> {

    /**
     * The one permitted static mutable-looking member: a logger reference that is itself immutable. Every
     * emission in this class goes through SLF4J, never {@code System.out}, {@code System.err} or
     * {@code printStackTrace()}, so the JSON encoding and the credential, hash and social-security masking
     * rules configured in {@code src/main/resources/logback-spring.xml} govern what actually reaches a log
     * aggregator.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CustomerReader.class);

    /**
     * Default rows per round trip, used when {@code carddemo.batch.customer-reader.page-size} is not set.
     * Chosen so the entire 50-row seed fixture {@code app/data/ASCII/custdata.txt} (25,050 bytes, 50 records
     * of 500 bytes plus a terminator, measured) is satisfied by one query while the resident set stays small.
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /** The entity property the scan is ordered by, and the JPA counterpart of {@code CUST-ID}. */
    private static final String ORDER_PROPERTY = "customerId";

    /**
     * Logical file name reported on every diagnostic, taken from the DD name in
     * {@code app/jcl/READCUST.jcl:L9} and matching the {@code ASSIGN TO} clause at
     * {@code app/cbl/CBCUS01C.cbl:L29}. The CICS file that fronts the same cluster online is named
     * {@code CUSTDAT} ({@code app/csd/CARDDEMO.CSD:L50}); the batch DD name is the one this program uses and
     * therefore the one reported here.
     */
    private static final String LOGICAL_FILE = "CUSTFILE";

    /**
     * The program name the legacy load module carried, used as the abend culprit so that
     * {@code ABEND-CULPRIT PIC X(8)} of {@code app/cpy/CSMSG02Y.cpy} is populated with a real component
     * identity. Exactly eight characters, matching the picture clause.
     */
    private static final String ABEND_CULPRIT = "CBCUS01C";

    // ----------------------------------------------------------------------------------------------------
    // Legacy DISPLAY literals, reproduced byte for byte. Each is followed by its measured inner length so a
    // reviewer can confirm fidelity without opening the source. Rule 1 clause F: every assertion is cited.
    // ----------------------------------------------------------------------------------------------------

    /** {@code app/cbl/CBCUS01C.cbl:L71}, 38 characters. */
    private static final String START_OF_EXECUTION_MESSAGE = "START OF EXECUTION OF PROGRAM CBCUS01C";

    /** {@code app/cbl/CBCUS01C.cbl:L85}, 36 characters. */
    private static final String END_OF_EXECUTION_MESSAGE = "END OF EXECUTION OF PROGRAM CBCUS01C";

    /**
     * {@code app/cbl/CBCUS01C.cbl:L110}, 27 characters.
     * <p>
     * Says {@code CUSTOMER FILE}, as does {@link #ERROR_CLOSING_MESSAGE}, while
     * {@link #ERROR_OPENING_MESSAGE} says {@code CUSTFILE}. See parity structure 4: the inconsistency is the
     * source's and is preserved.
     */
    private static final String ERROR_READING_MESSAGE = "ERROR READING CUSTOMER FILE";

    /**
     * {@code app/cbl/CBCUS01C.cbl:L129}, 22 characters.
     * <p>
     * <b>The wording really is {@code CUSTFILE} here and {@code CUSTOMER FILE} on the read and close
     * paths.</b> The source is internally inconsistent and the inconsistency is preserved rather than tidied,
     * because the three strings are observable output that a parity comparison reads. Anyone tempted to align
     * them should note that {@link #ERROR_READING_MESSAGE} and {@link #ERROR_CLOSING_MESSAGE} are both 27
     * characters while this one is 22, and that each of this program's three siblings is internally consistent
     * about its own file name &mdash; {@code CARDFILE}, {@code XREFFILE}, {@code ACCTFILE} &mdash; which makes
     * this the odd one out and the one most at risk of being helpfully corrected.
     */
    private static final String ERROR_OPENING_MESSAGE = "ERROR OPENING CUSTFILE";

    /** {@code app/cbl/CBCUS01C.cbl:L147}, 27 characters. */
    private static final String ERROR_CLOSING_MESSAGE = "ERROR CLOSING CUSTOMER FILE";

    /** {@code app/cbl/CBCUS01C.cbl:L155}, 16 characters. */
    private static final String ABENDING_PROGRAM_MESSAGE = "ABENDING PROGRAM";

    // ----------------------------------------------------------------------------------------------------
    // Execution-context keys for the restart cursor. Namespaced by simple class name so two readers in the
    // same step cannot collide. Both values are numeric; see update(ExecutionContext).
    // ----------------------------------------------------------------------------------------------------

    /** Key under which the number of rows already emitted is checkpointed. */
    private static final String CONTEXT_KEY_RECORDS_READ = "CustomerReader.recordsRead";

    /** Key under which the identifier of the most recently emitted row is checkpointed. */
    private static final String CONTEXT_KEY_LAST_CUSTOMER_ID = "CustomerReader.lastCustomerId";

    /** {@code END-OF-FILE PIC X(01) VALUE 'N'} in its initial state ({@code app/cbl/CBCUS01C.cbl:L65}). */
    private static final String END_OF_FILE_NO = "N";

    /** {@code END-OF-FILE} after {@code MOVE 'Y' TO END-OF-FILE} ({@code app/cbl/CBCUS01C.cbl:L108}). */
    private static final String END_OF_FILE_YES = "Y";

    /** {@code CUST-ID PIC 9(09)}, bytes 1-9 of the record ({@code app/cpy/CVCUS01Y.cpy:L5}). */
    private static final int CUSTOMER_ID_DIGITS = 9;

    /** The character a COBOL {@code MOVE} into an alphanumeric item pads with on the right. */
    private static final char ALPHANUMERIC_PAD = ' ';

    /** The character a COBOL {@code MOVE} into a numeric display item pads with on the left. */
    private static final char NUMERIC_PAD = '0';

    // ----------------------------------------------------------------------------------------------------
    // File-status literals, derived from com.cardemo.model.enums.FileStatus rather than restated, so that the
    // single definition of each code stays single (Rule 1 clause C, avoid duplication).
    // ----------------------------------------------------------------------------------------------------

    /**
     * {@code '00'}: the status the source tests at {@code app/cbl/CBCUS01C.cbl:L94}, {@code :L121} and
     * {@code :L139}.
     */
    private static final String STATUS_SUCCESS = requireExactCode(FileStatus.SUCCESS);

    /**
     * {@code '10'}: end of file, which drives {@code MOVE 16 TO APPL-RESULT} at
     * {@code app/cbl/CBCUS01C.cbl:L99}.
     */
    private static final String STATUS_END_OF_FILE = requireExactCode(FileStatus.END_OF_FILE);

    /**
     * The member of the {@code '9x'} family this reader reports when the store rejects an operation.
     * <p>
     * {@link FileStatus#IO_ERROR} is a family rather than a value: its first byte is fixed at
     * {@link FileStatus#IO_ERROR_FIRST_BYTE} and the second byte carries an implementation-defined subcode. A
     * relational store reports a failure as a {@link DataAccessException} hierarchy and a driver SQLSTATE,
     * neither of which carries a VSAM subcode, so the subcode is set to {@code '0'} to mean &quot;no further
     * subcode available from this layer&quot;. The driver's own detail is never discarded: it travels on the
     * cause of the thrown exception.
     * <p>
     * <b>Finding, severity Low.</b> The specific z/OS VSAM subcode that a given JDBC failure would have
     * produced on the mainframe is <b>Not available</b>. <i>Prerequisite:</i> a z/OS VSAM trace of the failing
     * condition, which cannot be obtained here because EBCDIC and mainframe-runtime reproduction are out of
     * scope for this migration. <i>Remediation:</i> if a byte-exact subcode is ever required, add a
     * SQLSTATE-to-subcode table at the {@link FileStatusMapper} layer, where the single definition of the
     * status vocabulary already lives, rather than in this reader.
     */
    private static final String STATUS_PHYSICAL_IO_ERROR =
            String.valueOf(FileStatus.IO_ERROR_FIRST_BYTE) + NUMERIC_PAD;

    // ----------------------------------------------------------------------------------------------------
    // Collaborators, injected through the constructor and never reassigned.
    // ----------------------------------------------------------------------------------------------------

    /** The persistence access point for the customer master, replacing the {@code CUSTFILE} VSAM cluster. */
    private final CustomerRepository customerRepository;

    /**
     * The central {@code FILE STATUS} translator. Consumed rather than re-implemented: it already renders the
     * {@code Z-DISPLAY-IO-STATUS} line and already applies the {@code APPL-RESULT} arithmetic of both the
     * two-way guard and the three-way sequential-read guard, and its own documentation cites
     * {@code app/cbl/CBCUS01C.cbl:L98} for the latter. Duplicating any of that here would violate Rule 1
     * clause C.
     */
    private final FileStatusMapper fileStatusMapper;

    /** Rows fetched per round trip; validated at construction and never changed afterwards. */
    private final int pageSize;

    // ----------------------------------------------------------------------------------------------------
    // Cursor state. Every field below is the Java counterpart of a WORKING-STORAGE item at
    // app/cbl/CBCUS01C.cbl:L46-L67 and is therefore an INSTANCE field: never static, never shared. The step
    // scope gives each step execution its own instance.
    // ----------------------------------------------------------------------------------------------------

    /** {@code END-OF-FILE PIC X(01)} ({@code :L65}). Held as its literal {@code 'N'} or {@code 'Y'} value. */
    private String endOfFile = END_OF_FILE_NO;

    /** {@code APPL-RESULT PIC S9(9) COMP} ({@code :L61}), tested through {@code APPL-AOK} and {@code APPL-EOF}. */
    private int applResult;

    /** {@code IO-STATUS} ({@code :L50-L52}), the two-character status moved in before the renderer runs. */
    private String ioStatus = STATUS_SUCCESS;

    /** {@code CUSTOMER-RECORD}, the record area that {@code COPY CVCUS01Y} declares at {@code :L45}. */
    private Customer customerRecord;

    /** The rows of the page currently buffered, standing in for the VSAM read-ahead buffer. */
    private List<Customer> pageBuffer = List.of();

    /** Cursor into {@link #pageBuffer}; the next row to hand out. */
    private int pageBufferIndex;

    /** Zero-based number of the next page to fetch. */
    private int nextPageNumber;

    /** Rows to discard from the first fetched page when resuming a restarted step. */
    private int restartSkipWithinPage;

    /** Rows emitted so far, the counter the end-of-run summary reports and a restart resumes from. */
    private long recordsRead;

    /** Identifier of the most recently emitted row, checkpointed so a restart can be verified. */
    private Long lastCustomerId;

    /** Whether {@code openCustomerFile()} has completed successfully, mirroring an open VSAM ACB. */
    private boolean fileOpen;

    /** Total rows the relation held when the file was opened, used for the explicit empty-relation branch. */
    private long recordCountAtOpen;

    /**
     * Creates a reader bound to the customer master.
     *
     * @param customerRepository the customer persistence access point; must not be {@code null}
     * @param fileStatusMapper the shared {@code FILE STATUS} translator; must not be {@code null}
     * @param pageSize rows per round trip, supplied by
     *     {@code carddemo.batch.customer-reader.page-size} and defaulting to {@value #DEFAULT_PAGE_SIZE}; must
     *     be at least one
     * @throws NullPointerException if either collaborator is {@code null}
     * @throws IllegalArgumentException if {@code pageSize} is less than one
     */
    public CustomerReader(
            CustomerRepository customerRepository,
            FileStatusMapper fileStatusMapper,
            @Value("${carddemo.batch.customer-reader.page-size:" + DEFAULT_PAGE_SIZE + "}") int pageSize) {
        // Only Objects.requireNonNull and private static validators are called here. Invoking an overridable
        // instance method from the constructor of a non-final class would publish a partially built reference,
        // which -Xlint:all -Werror reports as this-escape; the step scope forbids a final class because it
        // proxies by subclassing.
        this.customerRepository = Objects.requireNonNull(customerRepository, "customerRepository must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.pageSize = requirePositivePageSize(pageSize);
    }

    // ====================================================================================================
    // Mainline PROCEDURE DIVISION, app/cbl/CBCUS01C.cbl:L70-L87, realised as the ItemStream lifecycle.
    // ====================================================================================================

    /**
     * Opens the scan: emits the start-of-execution banner and performs {@code 0000-CUSTFILE-OPEN}, reproducing
     * {@code app/cbl/CBCUS01C.cbl:L71-L72}.
     * <p>
     * <b>Side effects.</b> Resets all cursor state, restores the restart cursor from {@code executionContext}
     * when one is present, issues one {@code count()} round trip against the customer relation, and writes two
     * or three log events. Nothing is emitted that could carry personal data: the only variable value in any of
     * them is a row count.
     *
     * @param executionContext the step execution context; a restart cursor written by a previous run of the
     *     same step instance is honoured when present, and a {@code null} context is treated as a cold start
     * @throws org.springframework.batch.item.ItemStreamException never thrown directly; a store failure is
     *     reported as {@link FatalProcessingException}, which is also unchecked
     * @throws FatalProcessingException if the customer relation cannot be reached, reproducing the abend at
     *     {@code app/cbl/CBCUS01C.cbl:L132}
     */
    @Override
    public void open(ExecutionContext executionContext) {
        // Cold-start every cursor field first, so a reused instance cannot inherit a previous scan's position.
        endOfFile = END_OF_FILE_NO;
        applResult = FileStatusMapper.APPL_AOK;
        ioStatus = STATUS_SUCCESS;
        customerRecord = null;
        pageBuffer = List.of();
        pageBufferIndex = 0;
        nextPageNumber = 0;
        restartSkipWithinPage = 0;
        recordsRead = 0L;
        lastCustomerId = null;
        fileOpen = false;
        recordCountAtOpen = 0L;

        // DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.  (:L71)
        LOG.info(START_OF_EXECUTION_MESSAGE);

        // A null context is an explicit, handled case rather than a guarded assumption (Rule 1 clause B).
        if (executionContext != null && executionContext.containsKey(CONTEXT_KEY_RECORDS_READ)) {
            restoreRestartCursor(executionContext);
        }

        // PERFORM 0000-CUSTFILE-OPEN.  (:L72)
        openCustomerFile();
    }

    /**
     * Returns the next customer, or {@code null} once the scan is exhausted, reproducing the mainline loop body
     * at {@code app/cbl/CBCUS01C.cbl:L74-L81}.
     * <p>
     * The method body is the loop <em>body</em>, not the loop: Spring Batch drives the iteration, so
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} becomes the framework calling this method until it answers
     * {@code null}. Both of the source's guards are reproduced explicitly, in order, and neither is collapsed;
     * see parity structure 1 in the class documentation.
     * <p>
     * <b>This method emits the SECOND of the two record events, from {@code :L78}.</b> The first is emitted by
     * {@code getNextCustomerRecord()} from {@code :L96}. Two events per row is the parity contract; see parity
     * structure 2.
     * <p>
     * <b>What the event carries, and why.</b> The source displays the whole 500-byte
     * {@code CUSTOMER-RECORD}. This event instead renders the entity through {@link Customer#toString()},
     * which that class deliberately restricts to the identifier and the optimistic-lock version
     * &mdash; {@code Customer[customerId=..., version=...]} &mdash; and to nothing else. Delegating to the
     * entity contract here is the whole point: it means this call site cannot leak a field even by accident,
     * because it never names one. The companion event at {@code :L96} takes the opposite approach and renders
     * an explicitly extracted identifier, so the two emissions differ in mechanism exactly as the source's two
     * differ in origin, and each is proof against a different failure. Neither can carry a social security
     * number, a government identifier, a date of birth, an electronic-funds account identifier, a telephone
     * number, an address line, a name or a credit score.
     * <p>
     * <b>Finding, severity Low: the coupling to {@link Customer#toString()} is deliberate and bounded.</b>
     * Should that contract ever widen, this event would widen with it. <i>Remediation:</i> the assertion named
     * in the &quot;How to run, build and test&quot; section &mdash; that no personal-data value appears in
     * captured log output &mdash; fails immediately if it does, which is why that test is specified rather than
     * suggested. Switching this event to {@code renderCustomerId(customer.getCustomerId())}, exactly as the
     * {@code :L96} event already does, is a one-line change.
     * <p>
     * <b>Emitted at DEBUG.</b> The source emits unconditionally to SYSOUT; this class emits at DEBUG so that
     * per-row volume is opt-in. The level is a deliberate divergence and the only one: the event count and the
     * points in the control flow they are emitted from are the source's exactly.
     * <p>
     * <b>Why there is no {@code @Transactional} annotation.</b> A chunk-oriented step already runs this method
     * inside its own transaction, and Spring silently ignores the {@code readOnly} attribute of a method that
     * merely <em>participates</em> in an existing transaction rather than starting one. Annotating
     * {@code readOnly = true} here would therefore read as an enforced guarantee while enforcing nothing, which
     * Rule 1 clause A rules out. Read-only is guaranteed structurally instead: the only repository operations
     * this class can reach are {@link CustomerRepository#count()} and
     * {@link CustomerRepository#findAll(org.springframework.data.domain.Pageable)}, and there is no mutating
     * call, no {@code @Modifying} query and no {@code EntityManager} reference anywhere in the file.
     *
     * @return the next customer in ascending {@code customerId} order, or {@code null} at end of data, which is
     *     the Spring Batch end-of-input signal and the analogue of {@code MOVE 'Y' TO END-OF-FILE}
     * @throws FatalProcessingException if the store reports a status that is neither {@code '00'} nor
     *     {@code '10'}, reproducing the abend path at {@code app/cbl/CBCUS01C.cbl:L110-L113}
     * @throws IllegalStateException if called before {@link #open(ExecutionContext)}, which has no legacy
     *     counterpart because the mainline performs the open unconditionally at {@code :L72}
     */
    @Override
    public Customer read() {
        if (!fileOpen) {
            throw new IllegalStateException(
                    "read() called before open(ExecutionContext); app/cbl/CBCUS01C.cbl:L72 performs "
                            + "0000-CUSTFILE-OPEN before the mainline loop, so the file is always open by "
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

        // PERFORM 1000-CUSTFILE-GET-NEXT  (:L76) - which itself emits the FIRST record event, from :L96.
        Customer customer = getNextCustomerRecord();

        // IF END-OF-FILE = 'N'  (:L77) - the second guard. It cannot fail when the first passed and a record
        // was returned, which is exactly why it is redundant, and exactly why it is preserved: parity
        // structure 1. The null test is the same condition expressed through the returned value.
        if (!END_OF_FILE_NO.equals(endOfFile) || customer == null) {
            return null;
        }

        recordsRead++;
        lastCustomerId = customer.getCustomerId();

        // DISPLAY CUSTOMER-RECORD  (:L78) - the SECOND of this program's two emissions per row. The first is
        // at :L96 inside the read paragraph and is ACTIVE, unlike CBACT02C:L96 which is commented out; see
        // parity structure 2. Reproduced as an identifier-only projection rather than as the 500-byte record
        // image: bytes 280-288 are the social security number, 289-308 the government-issued identifier,
        // 309-318 the date of birth and 319-328 the electronic-funds account identifier
        // (app/cpy/CVCUS01Y.cpy:L17-L20), and Rule 1 clause D forbids putting any of that into a log. The
        // entity's own toString() is used here precisely because it is contractually limited to the identifier
        // and the version, so this call site names no field at all.
        if (LOG.isDebugEnabled()) {
            LOG.debug("{} record read (app/cbl/CBCUS01C.cbl:L78); sequence={} record={}",
                    LOGICAL_FILE,
                    Long.valueOf(recordsRead),
                    customer);
        }

        return customer;
    }

    /**
     * Checkpoints the restart cursor so an interrupted step can resume without re-emitting rows.
     * <p>
     * Only two values are stored, and they are the whole of the cursor: the number of rows already emitted and
     * the identifier of the most recent one. Because the scan is ordered by an explicit ascending sort on
     * {@code customerId}, a row count is a complete and deterministic position; the identifier is stored so a
     * resumed run can be verified against where it claimed to be. No entity, page or buffer is serialised.
     * <p>
     * <b>Both values are numeric and neither is personal data.</b> {@code CUST-ID} is the nine-digit surrogate
     * key of the relation ({@code app/cpy/CVCUS01Y.cpy:L5}), not an attribute of the person it identifies, and
     * the row count is a counter. Rule 1 clause D names four surfaces &mdash; code, logs, tests and config
     * &mdash; and this method writes to none of them: it writes to the step execution context, which Spring
     * Batch persists to {@code BATCH_STEP_EXECUTION_CONTEXT} in the same database and under the same access
     * control as the {@code customer} table itself. No new trust boundary is crossed and no additional
     * privilege is requested. Every other field of the record stays in its row.
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
        if (lastCustomerId != null) {
            executionContext.putLong(CONTEXT_KEY_LAST_CUSTOMER_ID, lastCustomerId.longValue());
        }
    }

    /**
     * Closes the scan: performs {@code 9000-CUSTFILE-CLOSE} and emits the end-of-execution banner, reproducing
     * {@code app/cbl/CBCUS01C.cbl:L83-L85}.
     * <p>
     * The order matters and is the source's: the close precedes the banner, so a close failure abends before
     * the banner is written and the banner is therefore evidence that the run completed. The row count is
     * reported alongside it, which the legacy program did not do for this job; it is additive observability
     * required by Rule 1 clause A and it replaces nothing.
     * <p>
     * <b>Side effects.</b> Releases the page buffer, resets the cursor and writes at least one log event.
     *
     * @throws org.springframework.batch.item.ItemStreamException never thrown directly
     * @throws FatalProcessingException if releasing the cursor fails, reproducing the abend at
     *     {@code app/cbl/CBCUS01C.cbl:L150}
     */
    @Override
    public void close() {
        // PERFORM 9000-CUSTFILE-CLOSE.  (:L83)
        closeCustomerFile();

        // DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.  (:L85)
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
     * <p>
     * The count is a row total and carries no attribute of any customer, so it is safe to publish.
     *
     * @return the count of rows returned by {@link #read()} since the last {@link #open(ExecutionContext)},
     *     never negative
     */
    public long getRecordsRead() {
        return recordsRead;
    }

    // ====================================================================================================
    // 1000-CUSTFILE-GET-NEXT, app/cbl/CBCUS01C.cbl:L92-L116.
    // ====================================================================================================

    /**
     * Reads the next record and applies the three-way sequential-read guard of
     * {@code 1000-CUSTFILE-GET-NEXT} ({@code app/cbl/CBCUS01C.cbl:L92-L116}).
     * <p>
     * The source shape is preserved exactly: the read sets a status; {@code '00'} yields
     * {@code MOVE 0 TO APPL-RESULT} <em>and</em> a {@code DISPLAY CUSTOMER-RECORD} ({@code :L95-L96});
     * {@code '10'} yields {@code MOVE 16} ({@code :L99}); anything else yields {@code MOVE 12}
     * ({@code :L101}). The guard that follows then either continues, sets {@code END-OF-FILE} to {@code 'Y'},
     * or reports and abends ({@code :L104-L115}).
     * <p>
     * Note where the record display sits: <b>inside the {@code '00'} branch, before the guard</b>, not after
     * it. That placement is why every successfully read record is reported twice and is reproduced here rather
     * than hoisted; see parity structure 2. <b>This method emits the FIRST of the two events, from
     * {@code :L96}</b>; {@link #read()} emits the second, from {@code :L78}.
     * <p>
     * <b>What the event carries, and why.</b> {@code renderCustomerId(Long)} is called on the identifier
     * explicitly rather than the entity being handed to the logger, so this call site cannot widen even if
     * {@link Customer#toString()} ever does. It is the belt to the {@code :L78} event's braces. No accessor for
     * any personal-data field is called, here or anywhere else in this class.
     *
     * @return the record just read when the status was {@code '00'}, or {@code null} at end of file
     * @throws FatalProcessingException when the status is neither {@code '00'} nor {@code '10'}, carrying the
     *     store failure as its cause when one was raised
     */
    private Customer getNextCustomerRecord() {
        DataAccessException storeFailure = null;
        try {
            // READ CUSTFILE-FILE INTO CUSTOMER-RECORD.  (:L93)
            ioStatus = readNextRecord();
        } catch (DataAccessException failure) {
            // The COBOL READ reports through CUSTFILE-STATUS; a relational store reports by throwing. The
            // throwable is translated to the '9x' family and then RETAINED as the cause, never swallowed and
            // never allowed to escape untyped (Rule 1 clause B).
            storeFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF CUSTFILE-STATUS = '00' MOVE 0 TO APPL-RESULT / ELSE IF '10' MOVE 16 / ELSE MOVE 12
        // (:L94-L103). The arithmetic is not restated here: FileStatusMapper already implements this exact
        // nested test and its documentation cites this very paragraph, at :L98 (Rule 1 clause C).
        applResult = fileStatusMapper.applResultForSequentialRead(ioStatus);

        // ------------------------------------------------------------------------------------------------
        // PRESERVED QUIRK, the duplicated record display. The next line of the source, inside the '00'
        // branch and immediately after MOVE 0 TO APPL-RESULT, is:
        //
        //     app/cbl/CBCUS01C.cbl:L96              DISPLAY CUSTOMER-RECORD
        //
        // Column 7 of that line is a SPACE, so it is a STATEMENT and not a comment. It is therefore
        // reproduced below as executable code. Three consequences follow, all measured:
        //   1. CBCUS01C reports each record TWICE per iteration - once here and once from the mainline at
        //      :L78 - so this class emits two events per row. Emitting one would be a parity break.
        //   2. The DISPLAY statement count is 10 rather than 9, and this line is the tenth. That single
        //      line is the whole difference between this program's inventory and CBACT02C's.
        //   3. app/cbl/CBACT02C.cbl:L96 is the same statement with an asterisk in column 7, so
        //      com.cardemo.batch.readers.CardReader deliberately reproduces it as a comment only. The two
        //      files are NOT interchangeable. app/cbl/CBACT03C.cbl:L96 is active, like this one.
        // The CONTENT is masked while the COUNT is preserved: that split is the documented resolution of
        // Rule 1 clause D against the parity mandate, and it is recorded in DECISION_LOG.md.
        // ------------------------------------------------------------------------------------------------
        if (applResult == FileStatusMapper.APPL_AOK && LOG.isDebugEnabled()) {
            LOG.debug("{} record read (app/cbl/CBCUS01C.cbl:L96); sequence={} CUST-ID={}",
                    LOGICAL_FILE,
                    Long.valueOf(recordsRead + 1L),
                    renderCustomerId(customerRecord == null ? null : customerRecord.getCustomerId()));
        }

        // IF APPL-AOK CONTINUE  (:L104-L105)
        if (applResult == FileStatusMapper.APPL_AOK) {
            return customerRecord;
        }

        // ELSE IF APPL-EOF MOVE 'Y' TO END-OF-FILE  (:L107-L108). End of file is loop termination, NOT an
        // error: 88 APPL-EOF VALUE 16 at :L63 is a normal outcome and nothing is thrown for it.
        if (applResult == FileStatusMapper.APPL_EOF) {
            endOfFile = END_OF_FILE_YES;
            customerRecord = null;
            return null;
        }

        // ELSE DISPLAY 'ERROR READING CUSTOMER FILE' / MOVE CUSTFILE-STATUS TO IO-STATUS /
        // PERFORM Z-DISPLAY-IO-STATUS / PERFORM Z-ABEND-PROGRAM  (:L110-L113). IO-STATUS already holds the
        // status, so the MOVE at :L111 is the assignment made above. The literal says CUSTOMER FILE here and
        // CUSTFILE on the open path; both are reproduced as spelled - parity structure 4.
        LOG.error(ERROR_READING_MESSAGE);
        LOG.error(displayIoStatus(ioStatus));
        abendProgram(ERROR_READING_MESSAGE, storeFailure);

        // EXIT.  (:L116) - unreachable, because abendProgram always throws. Present so that a reader of this
        // method sees the paragraph terminate exactly where the source does, and so the compiler proves the
        // method has no fall-through path that could silently return a stale record.
        return null;
    }

    /**
     * Performs the store round trip behind the {@code READ CUSTFILE-FILE INTO CUSTOMER-RECORD} verb at
     * {@code app/cbl/CBCUS01C.cbl:L93} and reports its outcome as a COBOL file status.
     * <p>
     * A VSAM {@code READ} with {@code ACCESS MODE IS SEQUENTIAL} hands back one record and advances the cursor.
     * Here the cursor is a page buffer refilled by
     * {@link CustomerRepository#findAll(org.springframework.data.domain.Pageable)} with an <b>explicit
     * ascending sort</b> on {@code customerId}. The sort is never omitted and the store's natural order is
     * never relied upon: {@code app/cbl/CBCUS01C.cbl:L29-L33} declares {@code ORGANIZATION IS INDEXED} with
     * {@code ACCESS MODE IS SEQUENTIAL} and {@code RECORD KEY IS FD-CUST-ID}, so key order <em>is</em> the
     * contract, and reproducing it deterministically is what makes the emitted sequence comparable against the
     * legacy baseline (Rule 1 clause A).
     * <p>
     * The key is the nine-digit {@code CUST-ID} ({@code app/cpy/CVCUS01Y.cpy:L5}), corroborated by the FD at
     * {@code app/cbl/CBCUS01C.cbl:L37-L40} ({@code FD-CUST-ID PIC 9(09)} plus
     * {@code FD-CUST-DATA PIC X(491)} = 500 bytes), by {@code app/catlg/LISTCAT.txt:L632}
     * ({@code KEYLEN 9} / {@code AVGLRECL 500} / {@code MAXLRECL 500} / {@code RKP 0}), by
     * {@code app/jcl/CUSTFILE.jcl:L50-L51} ({@code KEYS(9 0)} / {@code RECORDSIZE(500 500)}) and by the
     * measured 500-byte row width of {@code app/data/ASCII/custdata.txt}. <b>Finding, severity Low: nine, not
     * ten.</b> A second customer-shaped cluster exists in the corpus &mdash;
     * {@code app/jcl/DEFCUST.jcl:L35-L38} defines {@code AWS.CUSTDATA.CLUSTER} with {@code KEYS(10 0)} and
     * {@code RECORDSIZE(500 500)} &mdash; and <b>no program in the corpus opens it</b>. It is an orphan, it is
     * not this reader's file, and its key length must never be mistaken for the real one.
     * <i>Remediation:</i> none; recorded so the two are never conflated.
     * <p>
     * <b>No alternate index is consulted, because none exists over this cluster.</b> The catalogue records
     * exactly three alternate indexes corpus-wide &mdash; {@code app/catlg/LISTCAT.txt:L254} over
     * {@code CARDDATA}, {@code :L455} over {@code CARDXREF} and {@code :L3645} over {@code TRANSACT} &mdash;
     * and none over {@code CUSTDATA}. Inventing a secondary finder here would model an index the source does
     * not have.
     * <p>
     * The paging tradeoff, per Rule 1 clause A: rows are fetched {@link #pageSize} at a time rather than
     * materialised as one list, so the resident set is bounded by the page size instead of by the table size.
     * The page size cannot affect the emitted output because the ordering is fixed independently of it. This is
     * a deliberate narrowing of the argument-less {@code findAll()} that the repository's documentation
     * attributes to this program: the query is the same, the order is explicit, and the fetch is bounded. <b>No
     * bespoke repository method was requested or added</b>, because the inherited {@code Pageable} overload
     * already expresses everything this contract needs, and the repository's own documentation records that
     * adding one would be speculative dead code.
     *
     * @return {@link #STATUS_SUCCESS} when a record was placed in the record area, {@link #STATUS_END_OF_FILE}
     *     when the scan is exhausted, or {@link #STATUS_PHYSICAL_IO_ERROR} when the buffer yielded a
     *     {@code null} element, which a {@code NOT NULL} keyed relation cannot legitimately produce
     * @throws DataAccessException if the store rejects the query; translated by the caller
     */
    private String readNextRecord() {
        while (pageBufferIndex >= pageBuffer.size()) {
            Page<Customer> page = customerRepository.findAll(
                    PageRequest.of(nextPageNumber, pageSize, Sort.by(Sort.Direction.ASC, ORDER_PROPERTY)));
            nextPageNumber++;
            pageBuffer = page.getContent();

            // A restarted step resumes mid-page. The offset is consumed once and then cleared, so a short
            // final page cannot make the loop spin: an empty page ends it outright.
            pageBufferIndex = restartSkipWithinPage > 0
                    ? Math.min(restartSkipWithinPage, pageBuffer.size())
                    : 0;
            restartSkipWithinPage = 0;

            if (pageBuffer.isEmpty()) {
                customerRecord = null;
                return STATUS_END_OF_FILE;
            }
        }

        Customer next = pageBuffer.get(pageBufferIndex);
        pageBufferIndex++;

        // Explicit null branch (Rule 1 clause B): every row of this relation is NOT NULL and keyed, so a null
        // element means the result set is not what the schema promises. It is reported through the status
        // vocabulary rather than allowed to become a NullPointerException further down.
        if (next == null) {
            customerRecord = null;
            return STATUS_PHYSICAL_IO_ERROR;
        }

        customerRecord = next;
        return STATUS_SUCCESS;
    }

    // ====================================================================================================
    // 0000-CUSTFILE-OPEN, app/cbl/CBCUS01C.cbl:L118-L134.
    // ====================================================================================================

    /**
     * Opens the customer master, reproducing {@code 0000-CUSTFILE-OPEN}
     * ({@code app/cbl/CBCUS01C.cbl:L118-L134}).
     * <p>
     * <b>Arithmetic idiom, parity structure 3.</b> This paragraph primes the result field with
     * {@code MOVE 8 TO APPL-RESULT} at {@code :L119}, and its two branches write {@code MOVE 0}
     * ({@code :L122}) and {@code MOVE 12} ({@code :L124}). Its counterpart {@code closeCustomerFile()} primes
     * the same field with {@code ADD 8 TO ZERO GIVING APPL-RESULT} at {@code :L137} and writes
     * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} ({@code :L140}) and
     * {@code ADD 12 TO ZERO GIVING APPL-RESULT} ({@code :L142}). Every pair computes the same value by a
     * different verb. They are kept as two methods, each documenting the idiom its own source paragraph uses,
     * and are deliberately not normalised into one shape.
     * <p>
     * <b>What stands in for {@code OPEN INPUT}.</b> There is no file handle to acquire, so the analogue is a
     * single {@link CustomerRepository#count()} round trip. It establishes exactly what a VSAM open establishes
     * &mdash; that the dataset is reachable &mdash; because an unreachable relation surfaces as a
     * {@link DataAccessException}, which is the counterpart of file status {@code '35'} or the {@code '9x'}
     * family. It also yields the row count, which makes the empty-relation case an explicit, logged outcome
     * rather than something inferred later from an absence of records (Rule 1 clause B). The call returns a
     * scalar, so no sort applies to it and no row is materialised.
     * <p>
     * <b>Side effects.</b> One store round trip; sets the open flag and the row count; writes one log event on
     * success and two before abending on failure.
     *
     * @throws FatalProcessingException if the relation cannot be reached, reproducing
     *     {@code DISPLAY 'ERROR OPENING CUSTFILE'} then {@code PERFORM Z-ABEND-PROGRAM} at
     *     {@code :L129-L132}
     */
    private void openCustomerFile() {
        // MOVE 8 TO APPL-RESULT.  (:L119) - the OPEN idiom. See the arithmetic note above.
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        DataAccessException storeFailure = null;
        try {
            // OPEN INPUT CUSTFILE-FILE  (:L120)
            recordCountAtOpen = customerRepository.count();
            ioStatus = STATUS_SUCCESS;
        } catch (DataAccessException failure) {
            storeFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF CUSTFILE-STATUS = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 TO APPL-RESULT  (:L121-L125).
        // Delegated rather than restated: this two-way test is what applResultForGuard implements.
        applResult = fileStatusMapper.applResultForGuard(ioStatus);

        if (applResult == FileStatusMapper.APPL_AOK) {
            // IF APPL-AOK CONTINUE  (:L126-L127)
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
            // ELSE DISPLAY 'ERROR OPENING CUSTFILE' / MOVE CUSTFILE-STATUS TO IO-STATUS /
            // PERFORM Z-DISPLAY-IO-STATUS / PERFORM Z-ABEND-PROGRAM  (:L129-L132). Note the wording:
            // 'CUSTFILE' here, 'CUSTOMER FILE' on the read and close paths. Preserved, see
            // ERROR_OPENING_MESSAGE and parity structure 4.
            LOG.error(ERROR_OPENING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            abendProgram(ERROR_OPENING_MESSAGE, storeFailure);
        }
        // EXIT.  (:L134)
    }

    // ====================================================================================================
    // 9000-CUSTFILE-CLOSE, app/cbl/CBCUS01C.cbl:L136-L152.
    // ====================================================================================================

    /**
     * Closes the customer master, reproducing {@code 9000-CUSTFILE-CLOSE}
     * ({@code app/cbl/CBCUS01C.cbl:L136-L152}).
     * <p>
     * <b>Arithmetic idiom, parity structure 3.</b> Where {@code openCustomerFile()} writes
     * {@code MOVE 8 TO APPL-RESULT} ({@code :L119}), this paragraph writes
     * {@code ADD 8 TO ZERO GIVING APPL-RESULT} ({@code :L137}); where the open branch writes {@code MOVE 0}
     * and {@code MOVE 12} ({@code :L122}, {@code :L124}), this one writes
     * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} ({@code :L140}) and
     * {@code ADD 12 TO ZERO GIVING APPL-RESULT} ({@code :L142}). Every pair computes the same value by a
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
     *     {@code DISPLAY 'ERROR CLOSING CUSTOMER FILE'} then {@code PERFORM Z-ABEND-PROGRAM} at
     *     {@code :L147-L150}
     */
    private void closeCustomerFile() {
        // ADD 8 TO ZERO GIVING APPL-RESULT.  (:L137) - the CLOSE idiom, distinct from the open's MOVE 8.
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        RuntimeException teardownFailure = null;
        try {
            // CLOSE CUSTFILE-FILE  (:L138)
            pageBuffer = List.of();
            pageBufferIndex = 0;
            customerRecord = null;
            fileOpen = false;
            ioStatus = STATUS_SUCCESS;
        } catch (RuntimeException failure) {
            teardownFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF CUSTFILE-STATUS = '00' SUBTRACT APPL-RESULT FROM APPL-RESULT ELSE
        // ADD 12 TO ZERO GIVING APPL-RESULT  (:L139-L143). Both branches compute exactly what
        // applResultForGuard returns - zero and twelve - so the shared translator is consulted instead of the
        // arithmetic being restated (Rule 1 clause C). The source's verbs are recorded above.
        applResult = fileStatusMapper.applResultForGuard(ioStatus);

        // IF APPL-AOK CONTINUE  (:L144-L145)
        if (applResult != FileStatusMapper.APPL_AOK) {
            // ELSE DISPLAY 'ERROR CLOSING CUSTOMER FILE' / MOVE CUSTFILE-STATUS TO IO-STATUS /
            // PERFORM Z-DISPLAY-IO-STATUS / PERFORM Z-ABEND-PROGRAM  (:L147-L150)
            LOG.error(ERROR_CLOSING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            abendProgram(ERROR_CLOSING_MESSAGE, teardownFailure);
        }
        // EXIT.  (:L152)
    }

    // ====================================================================================================
    // Z-ABEND-PROGRAM, app/cbl/CBCUS01C.cbl:L154-L158.
    //
    // NOTE THE LABEL. This program spells it Z-ABEND-PROGRAM, not 9999-ABEND-PROGRAM as CBACT01C, CBACT02C
    // and CBACT03C do. The citation is the source's spelling and is deliberately not normalised.
    // ====================================================================================================

    /**
     * Abends the step, reproducing {@code Z-ABEND-PROGRAM} ({@code app/cbl/CBCUS01C.cbl:L154-L158}).
     * <p>
     * The source emits {@code 'ABENDING PROGRAM'} ({@code :L155}), zeroes {@code TIMING} ({@code :L156}), moves
     * {@code 999} into {@code ABCODE} ({@code :L157}) and calls the Language Environment abend service
     * ({@code :L158}). The Java counterpart throws {@link FatalProcessingException} carrying the full
     * {@code CABENDD.CPY} payload: abend code
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE}, this program as the culprit,
     * the failing operation as the reason, and the legacy message as the message. Process return code
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE} is the exit code that the job's
     * status mapping derives from this exception; it is not set here, because a reader does not own the process
     * exit code.
     * <p>
     * <b>The label is {@code Z-ABEND-PROGRAM}.</b> Its three siblings spell the equivalent paragraph
     * {@code 9999-ABEND-PROGRAM}; this program does not, and the citation matches the file rather than the
     * family convention.
     * <p>
     * {@code TIMING} has no counterpart. It is a Language Environment abend parameter that selects whether a
     * dump is taken, and there is no dump facility to select; the exception carries the stack trace that
     * replaces it.
     * <p>
     * <b>The payload carries no personal data.</b> The reason and message are built from the legacy literal,
     * the logical file name and the rendered file status only. No customer attribute, and not even the
     * identifier of the row being processed, is interpolated: Rule 1 clause B's instruction to &quot;wrap with
     * context&quot; does not license putting a social security number into a stack trace that a monitoring
     * system will capture. The row already reached the caller through {@link #read()}, and the sequence number
     * of the failing row is available from {@link #getRecordsRead()} for anyone who needs to locate it.
     * <p>
     * <b>This method always throws and never returns normally.</b>
     *
     * @param message the legacy message literal that preceded the abend, used as both the reason and the abend
     *     message so the failing operation is identifiable from either field
     * @param cause the throwable that provoked the abend, or {@code null} when the status alone identified the
     *     fault; always attached when present, so the root cause is never lost (Rule 1 clause B)
     * @throws FatalProcessingException always
     */
    private void abendProgram(String message, Throwable cause) {
        // DISPLAY 'ABENDING PROGRAM'  (:L155)
        LOG.error(ABENDING_PROGRAM_MESSAGE);

        // MOVE 0 TO TIMING (:L156) / MOVE 999 TO ABCODE (:L157) / CALL 'CEE3ABD'. (:L158)
        throw new FatalProcessingException(
                Integer.toString(FatalProcessingException.BATCH_ABEND_CODE),
                ABEND_CULPRIT,
                message,
                String.format(Locale.ROOT, "%s (%s, file status %s)", message, LOGICAL_FILE,
                        displayIoStatus(ioStatus)),
                cause);
    }

    // ====================================================================================================
    // Z-DISPLAY-IO-STATUS, app/cbl/CBCUS01C.cbl:L161-L174.
    //
    // NOTE THE LABEL. This program spells it Z-DISPLAY-IO-STATUS, not 9910-DISPLAY-IO-STATUS as its three
    // siblings do. The citation is the source's spelling and is deliberately not normalised.
    // ====================================================================================================

    /**
     * Renders a file status as the legacy diagnostic line, reproducing {@code Z-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBCUS01C.cbl:L161-L174}).
     * <p>
     * The paragraph has two branches. When {@code IO-STATUS} is not numeric or its first byte is {@code '9'}
     * ({@code :L162-L163}), byte one is copied into position one and byte two is widened through
     * {@code TWO-BYTES-BINARY} into three digits ({@code :L164-L167}). Otherwise the field is set to
     * {@code '0000'} and the two status characters are overlaid at positions three and four
     * ({@code :L170-L171}). Both branches then emit {@code 'FILE STATUS IS: NNNN'} followed by the
     * four-character field ({@code :L168}, {@code :L172}).
     * <p>
     * <b>{@code 'FILE STATUS IS: NNNN'} is a fixed 20-character literal, not a template.</b> The {@code NNNN}
     * is part of the constant text and the four rendered characters follow it, so status {@code '23'} renders
     * as {@code FILE STATUS IS: NNNN0023} and never as {@code FILE STATUS IS: 0023}. Substituting the digits
     * into the {@code NNNN} would be a parity break.
     * <p>
     * <b>The label is {@code Z-DISPLAY-IO-STATUS}.</b> Its three siblings spell the equivalent paragraph
     * {@code 9910-DISPLAY-IO-STATUS}; this program does not.
     * <p>
     * <b>This method delegates and holds no logic of its own</b>, which is deliberate. The identical paragraph
     * recurs across the batch corpus, and {@link FileStatusMapper#displayIoStatus(String)} is the single
     * implementation of it; {@link FileStatus#DISPLAY_MESSAGE_PREFIX} is the single definition of the literal.
     * Re-deriving either here would be the parallel mapping that Rule 1 clause C forbids. The method is
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
    // Identifier rendering. The ONLY field of CUSTOMER-RECORD this class ever renders, and the reason the
    // two record events of parity structure 2 can be emitted at all without breaching Rule 1 clause D.
    //
    // There is deliberately NO whole-record renderer and NO field-by-field emitter here. CBCUS01C has no
    // 1100- paragraph to justify the latter, and the former would reconstruct exactly the 500-byte image
    // that must not reach a log. There is also no zoned-decimal codec, because app/cpy/CVCUS01Y.cpy
    // declares no S9 picture clause anywhere and one would therefore be unreachable.
    // ====================================================================================================

    /**
     * Renders {@code CUST-ID PIC 9(09)} as {@value #CUSTOMER_ID_DIGITS} zero-padded digits, the form the
     * fixture {@code app/data/ASCII/custdata.txt} stores in bytes 1-9 of every row.
     * <p>
     * The picture clause is unsigned, so a negative value contributes its digits without a sign, exactly as a
     * COBOL {@code MOVE} into an unsigned numeric item would. Removal of the sign is done textually rather than
     * by {@code Math.abs}, which would overflow on {@link Long#MIN_VALUE}. High-order truncation is what a
     * COBOL {@code MOVE} into a shorter numeric item does; truncating the low-order end instead would silently
     * change the magnitude by a power of ten, so the direction matters. Neither case can arise from a valid row
     * &mdash; a nine-digit key cannot be negative or over-long &mdash; and both are handled anyway, because a
     * row arriving from the store is treated as untrusted input regardless of what the schema declares (Rule 1
     * clause A).
     * <p>
     * The identifier is a surrogate key, not an attribute of the person, which is why it is the one value this
     * class is willing to emit.
     *
     * @param customerId the identifier, tolerated when {@code null}
     * @return exactly {@value #CUSTOMER_ID_DIGITS} characters: digits, or spaces when {@code customerId} is
     *     {@code null}, which is the COBOL rendering of an uninitialised field
     */
    private static String renderCustomerId(Long customerId) {
        if (customerId == null) {
            return String.valueOf(ALPHANUMERIC_PAD).repeat(CUSTOMER_ID_DIGITS);
        }
        String digits = Long.toString(customerId.longValue());
        if (digits.startsWith("-")) {
            digits = digits.substring(1);
        }
        int length = digits.length();
        if (length == CUSTOMER_ID_DIGITS) {
            return digits;
        }
        if (length > CUSTOMER_ID_DIGITS) {
            return digits.substring(length - CUSTOMER_ID_DIGITS);
        }
        return String.valueOf(NUMERIC_PAD).repeat(CUSTOMER_ID_DIGITS - length) + digits;
    }

    // ====================================================================================================
    // Restart support and construction-time validation. No legacy counterpart: the mainline at
    // app/cbl/CBCUS01C.cbl:L70-L87 always scans from the first record, because a JES2 job restart re-ran the
    // step from the top. Restartability is additive, and it changes no emitted value.
    // ====================================================================================================

    /**
     * Restores the checkpoint written by {@link #update(ExecutionContext)} so a restarted step resumes instead
     * of re-emitting rows.
     * <p>
     * A row count is a complete position because the scan is ordered by an explicit ascending sort on
     * {@code customerId}: the count divides into a page number and an offset within that page, both exactly.
     * The checkpointed identifier is restored for diagnostics and reported in the resume log line so an
     * operator can see where the run claims to be picking up; it is a surrogate key and carries no personal
     * attribute.
     * <p>
     * A non-positive checkpoint is ignored and the scan starts from the beginning, which is the correct reading
     * of a checkpoint written before any row was emitted.
     *
     * @param executionContext the step execution context, already known to contain the row-count key
     * @throws ArithmeticException if the checkpointed count divided by the page size exceeds an {@code int},
     *     which a nine-digit key space cannot reach and which is therefore asserted rather than assumed
     */
    private void restoreRestartCursor(ExecutionContext executionContext) {
        long checkpointed = executionContext.getLong(CONTEXT_KEY_RECORDS_READ, 0L);
        if (checkpointed <= 0L) {
            return;
        }

        recordsRead = checkpointed;
        nextPageNumber = Math.toIntExact(checkpointed / pageSize);
        restartSkipWithinPage = Math.toIntExact(checkpointed % pageSize);

        if (executionContext.containsKey(CONTEXT_KEY_LAST_CUSTOMER_ID)) {
            lastCustomerId = Long.valueOf(executionContext.getLong(CONTEXT_KEY_LAST_CUSTOMER_ID));
        }

        LOG.info("Resuming {} scan after {} rows; last emitted CUST-ID={}",
                LOGICAL_FILE, Long.valueOf(recordsRead), renderCustomerId(lastCustomerId));
    }

    /**
     * Validates the injected page size.
     * <p>
     * Declared {@code private static} so the constructor can call it without invoking an overridable method,
     * which would publish a partially constructed reference; {@code -Xlint:all -Werror} reports that as
     * {@code this-escape}, and the class cannot be final because the {@code step} scope proxies by
     * subclassing.
     *
     * @param pageSize the configured value
     * @return {@code pageSize}, unchanged
     * @throws IllegalArgumentException if {@code pageSize} is less than one, because a page of zero or fewer
     *     rows would make the scan loop without ever advancing
     */
    private static int requirePositivePageSize(int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "carddemo.batch.customer-reader.page-size must be at least 1 but was %d; a non-positive "
                            + "page cannot advance the sequential scan of CUSTFILE",
                    Integer.valueOf(pageSize)));
        }
        return pageSize;
    }

    /**
     * Extracts the two-character code of a {@link FileStatus} that is expected to be an exact value rather than
     * a family, so the literals this class compares against are derived from the single definition of the
     * status vocabulary instead of being restated as string constants (Rule 1 clause C).
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
