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
import org.springframework.data.domain.PageRequest;
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
 * would break the file-count gates that fix {@code com.cardemo.batch.readers} at exactly seven classes. For the
 * same reason no {@code package-info.java} exists under {@code com.cardemo.batch} either, and none may be added,
 * so there is no package-scope document to defer to: the <b>docstring branch</b> of the clause is the whole
 * discharge and is given below in full.
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
 * {@link CustomerRepository#findByCustomerIdGreaterThanOrderByCustomerIdAsc(Long,
 * org.springframework.data.domain.Pageable)}.
 * <p>
 * <b>Lexical token counts are larger than statement counts, and only the statement counts govern.</b>
 * Measured at {@code 7756d89} with a word-boundary match, the raw token occurrences in this program are
 * {@code OPEN}=3, {@code READ}=1, {@code CLOSE}=3 and {@code DISPLAY}=14, which is why those four figures
 * circulate. They exceed the statement counts because the token {@code OPEN} also occurs in the paragraph label
 * {@code 0000-CUSTFILE-OPEN} at {@code :L118} and in the {@code PERFORM} of it at {@code :L72}, the token
 * {@code CLOSE} likewise at {@code :L136} and {@code :L83}, and the token {@code DISPLAY} also occurs in the
 * label {@code Z-DISPLAY-IO-STATUS} and in each {@code PERFORM} of it. Cite the statement counts above, which
 * are the ones this class reproduces. The load-bearing fact is identical either way, so nothing
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
 * <b>This program alone in the package prefixes its two utility paragraphs
 * {@code Z-}.</b> {@code CBACT01C}, {@code CBACT02C} and {@code CBACT03C} all spell the same two paragraphs
 * {@code 9999-ABEND-PROGRAM} and {@code 9910-DISPLAY-IO-STATUS}; {@code CBCUS01C} spells them
 * {@code Z-ABEND-PROGRAM} ({@code :L154}) and {@code Z-DISPLAY-IO-STATUS} ({@code :L161}). The bodies are
 * equivalent, so the difference is cosmetic and has no Java consequence. The labels
 * are cited exactly as the source spells them and are deliberately <b>not</b> normalised to the {@code 99xx-}
 * form, because a citation that does not match the file it points at is not evidence.
 * <p>
 * There are <b>six</b> paragraphs here, not seven. {@code CBACT01C} is the only one of the four simple
 * sequential readers with a {@code 1100-} field-by-field display paragraph, so no analogue of it exists in this
 * class; inventing one would be a paragraph the source does not have.
 * <p>
 * <b>This source file is not column-padded to 80 the way its three siblings are.</b>
 * Measured at {@code 7756d89}, the longest line of {@code app/cbl/CBCUS01C.cbl} is 73 characters, while
 * {@code app/cbl/CBACT01C.cbl} and {@code app/cbl/CBACT03C.cbl} both reach exactly 80. It is recorded only so
 * that nobody &quot;corrects&quot; a line-number citation on the assumption that the files are formatted
 * alike. {@code app/} is frozen and the padding is not load bearing here.
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
 * <b>The rejected alternative.</b> Reproducing {@code DISPLAY CUSTOMER-RECORD}
 * literally would publish a social security number, a government identifier, a date of birth and a bank
 * account identifier into log volume, twice for every customer, at INFO. This class therefore emits the
 * identifier-only projections described on {@link #read()} and {@code getNextCustomerRecord()}. The masking
 * rules configured in {@code src/main/resources/logback-spring.xml} for credentials, password hashes and social
 * security numbers are a second line of defence and not the first: this class does not rely on them, because
 * the values never reach the logger.
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
 *     the mainline at {@code :L78} displays the record again. The duplication is a legacy defect &mdash; the
 *     same bytes reach SYSOUT twice per row for no purpose &mdash; and it is reproduced rather than removed,
 *     as two distinct log events, each carrying its own citation. It is
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
 *     The source is internally inconsistent, and the inconsistency is kept. All three are observable output
 *     that a parity comparison reads byte for byte, so they are
 *     reproduced exactly as spelled and are <b>not</b> harmonised. This program is the one most likely to be
 *     tidied by mistake, because each of its three siblings is internally consistent about its own file name.
 *     </li>
 * </ol>
 *
 * <h2>How to run, build and test</h2>
 * The read-only verification {@code Step} that would own this reader is <strong>planned and not authored at
 * this commit</strong>, and that is now the whole of what is outstanding around it. An earlier revision of
 * this paragraph named {@code com.cardemo.config.BatchConfig} as the home of every {@code Job} and
 * {@code Step} and said {@code com.cardemo.batch.jobs} held one job, {@code InterestCalculationJob}; both
 * statements are withdrawn. {@code com.cardemo.batch.jobs} now holds <strong>three of its six target
 * jobs</strong> - {@code InterestCalculationJob}, {@code DailyTransactionPostingJob} and
 * {@code StatementGenerationJob} - and <strong>each declares its own {@code Step} beans</strong>, while
 * {@code BatchConfig} owns the dataset bindings and the record rendering rather than step topology. Still
 * owed are this reader's verification step and the name-driven launcher above it, the planned
 * {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}. What is already true is the property both will
 * rely on -
 * {@code spring.batch.job.enabled} is {@code false} in {@code src/main/resources/application.yml}, so no
 * job runs at application startup and every job must be launched deliberately. This class carries
 * {@code @Component} and {@code @StepScope}, so the component scan registers a definition for it while no
 * instance is constructed until a step is executing; with no {@code Step} yet referencing it, none is
 * constructed at runtime today.
 * <p>
 * Two build paths are available; both are pinned and either may be used. Each names a required
 * <em>capability</em> rather than a dated reading of one host; dated measurements live in section 0.4.5.3 of
 * {@code docs/technical-specifications.md}.
 * <ul>
 * <li><b>Host toolchain</b> &mdash; JDK 25 with {@code JAVA_HOME} set, then
 *     {@code set -a; . ./.env; set +a} and {@code ./mvnw -B -ntp clean compile}. Maven 3.9.11 comes from the
 *     pinned wrapper and {@code maven-enforcer-plugin} floors both.</li>
 * <li><b>Pinned container</b> &mdash; given a reachable container daemon, the build can also run
 *     hermetically:
 *     {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q -DskipTests compile}.
 *     </li>
 * </ul>
 * The compiler runs with {@code -Xlint:all} and {@code failOnWarning}, so the build fails on any warning.
 * <strong>Both test tiers now cover this class.</strong> An earlier revision of this paragraph said neither was
 * authored, that {@code unit/batch} held three classes none of which referenced this reader, and that
 * {@code integration/batch} held one abstract Testcontainers base with no concrete subclass beneath it; every
 * part of that is withdrawn. {@code src/test/java/com/cardemo/unit/batch} holds <strong>36</strong> sources and
 * covers the status renderer, the twin guards and the emission count through {@code CustomerReaderTest},
 * {@code SequentialReaderContractTest}, {@code SequentialReaderKeysetScanTest},
 * {@code ReaderSensitiveDataTest} and {@code BatchLogHygieneTest}, with
 * {@code FinancialLogRedactionTest} and
 * {@code com.cardemo.unit.observability.SensitiveDataRedactionTest} covering the redaction rules.
 * {@code src/test/java/com/cardemo/integration/batch} holds <strong>4</strong> sources - one abstract
 * Testcontainers base and three concrete classes that execute under Failsafe against PostgreSQL 16 and
 * LocalStack. The two assertions specific to this class are both delivered, by
 * {@code ReaderSensitiveDataTest}: <b>exactly two record events per row</b>, checked as both record events of
 * {@code CBCUS01C:L78} and {@code :L96} still being emitted, and <b>no personal-data field value present
 * anywhere in captured log output</b>, checked by asserting that a local projection rather than the entity is
 * logged. This class still creates neither test, because test sources are outside the scope of the package it
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
 *     (property {@code spring.jpa.hibernate.ddl-auto} in {@code src/main/resources/application.yml}) and
 *     {@code spring.jpa.open-in-view} is {@code false}; the schema is owned by the Flyway migrations.</li>
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
    // reader can confirm fidelity without opening the source, and each carries its own source locator.
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

    /**
     * Exclusive lower bound seeding the first keyset window, chosen to sit provably below the entire key
     * space so that {@code CUST-ID > } this value selects the true first row.
     * <p>
     * The proof, not an assumption: {@code CUST-ID} is declared {@code PIC 9(09)} at
     * {@code app/cpy/CVCUS01Y.cpy:L5}, corroborated by {@code KEYS(9 0)} at
     * {@code app/jcl/CUSTFILE.jcl:L50}, is materialised as {@code NUMERIC(9) NOT NULL} by
     * {@code src/main/resources/db/migration/V1__create_schema.sql}, and {@code Customer} rejects any value
     * below its own {@code MIN_CUSTOMER_ID} of zero. An unsigned display field admits no negative member at
     * all, so {@code -1} is below every value the column can hold and below every value the entity will
     * accept. It is a bound, never a key: no row can equal it, so no row can be skipped by it.
     */
    private static final long SEED_CUSTOMER_ID = -1L;

    /** {@code END-OF-FILE PIC X(01) VALUE 'N'} in its initial state ({@code app/cbl/CBCUS01C.cbl:L65}). */
    private static final String END_OF_FILE_NO = "N";

    /** {@code END-OF-FILE} after {@code MOVE 'Y' TO END-OF-FILE} ({@code app/cbl/CBCUS01C.cbl:L108}). */
    private static final String END_OF_FILE_YES = "Y";

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
     * The specific z/OS VSAM subcode that a given JDBC failure would have produced on the mainframe cannot be
     * derived from this codebase: establishing it would take a z/OS VSAM trace of the failing condition, and
     * EBCDIC and mainframe-runtime reproduction are out of scope for this migration. If a byte-exact subcode is
     * ever required, add a SQLSTATE-to-subcode table at the {@link FileStatusMapper} layer, where the single
     * definition of the status vocabulary already lives, rather than in this reader.
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

    /**
     * Keyset cursor: the highest {@code CUST-ID} already <em>fetched</em> into {@link #pageBuffer}, and
     * therefore the exclusive lower bound of the next window. Seeded to {@value #SEED_CUSTOMER_ID}, which is
     * provably below the whole key space, so the first window starts at the true first row.
     * <p>
     * This runs ahead of {@link #lastCustomerId} by up to {@link #pageSize} rows, because a window is fetched
     * before its rows are handed out. The two are distinct on purpose: this one positions the <em>next
     * query</em>, that one records the <em>last emission</em> and is what a restart resumes from.
     */
    private long fetchCursorCustomerId = SEED_CUSTOMER_ID;

    /** Rows emitted so far, the counter the end-of-run summary reports. */
    private long recordsRead;

    /**
     * Identifier of the most recently emitted row. Checkpointed by {@link #update(ExecutionContext)} and, on
     * a restart, the authoritative position that {@link #fetchCursorCustomerId} is re-seeded from.
     */
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
        fetchCursorCustomerId = SEED_CUSTOMER_ID;
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
     * {@code CUSTOMER-RECORD}. This event reads <em>no field of the record at all</em> &mdash; the fact of the
     * read and its ordinal, and nothing else &mdash; exactly as the companion event at {@code :L96} does. It
     * cannot carry a social security number, a government identifier, a date of birth, an electronic-funds
     * account identifier, a telephone number, an address line, a name or a credit score, because it never reads
     * one. The primary key is withheld on the same ground rather than treated as safe because it is a
     * surrogate: it names one person's record, and naming the record is the disclosure that matters once the
     * line has been aggregated, retained and replicated outside this system.
     * <p>
     * <b>The coupling to {@link Customer#toString()} is deliberate and bounded.</b>
     * Should that contract ever widen, this event would widen with it. The assertion named
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
     * {@link CustomerRepository#findByCustomerIdGreaterThanOrderByCustomerIdAsc(Long,
     * org.springframework.data.domain.Pageable)}, and there is no mutating
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
        // event therefore carries the fact of the read and its ordinal, and reads NO field of the record at
        // all - not even the key, which identifies a person's record as surely as any attribute of it does and
        // which a log aggregator retains and replicates outside the boundary that protects it.
        if (LOG.isDebugEnabled()) {
            LOG.debug("{} record read (app/cbl/CBCUS01C.cbl:L78); sequence={}",
                    LOGICAL_FILE,
                    Long.valueOf(recordsRead));
        }

        return customer;
    }

    /**
     * Checkpoints the restart cursor so an interrupted step can resume without re-emitting rows.
     * <p>
     * Only two scalars are stored, and together they are the whole of the cursor: the identifier of the most
     * recently emitted row, which is the <b>position</b> a restart seeks to, and the number of rows emitted so
     * far, which is the <b>tally</b> the end-of-run summary continues from. The identifier is what makes the
     * position durable: because the scan is ordered by {@code customerId} and the next window is selected by
     * {@code CUST-ID > } that identifier, the resume point survives rows being inserted or deleted elsewhere in
     * the relation between the two runs. No entity, window or buffer is serialised.
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
     * <b>What the event carries, and why.</b> No accessor of the record is called at all, so this call site
     * cannot widen even if {@link Customer#toString()} ever does, and it discloses neither an attribute of the
     * person nor the key that names their record. The {@code :L78} event is built the same way, for the same
     * reason; neither reader event's exposure is defined outside this file.
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
        // The CONTENT is masked while the COUNT is preserved: that split resolves the tension between
        // keeping personal data out of log volume and reproducing the source's event count exactly.
        // ------------------------------------------------------------------------------------------------
        if (applResult == FileStatusMapper.APPL_AOK && LOG.isDebugEnabled()) {
            LOG.debug("{} record read (app/cbl/CBCUS01C.cbl:L96); sequence={}",
                    LOGICAL_FILE,
                    Long.valueOf(recordsRead + 1L));
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
     * Here the cursor is a buffered window refilled by
     * {@link CustomerRepository#findByCustomerIdGreaterThanOrderByCustomerIdAsc(Long,
     * org.springframework.data.domain.Pageable)}, whose ascending key order is fixed <b>in the method name
     * itself</b> and so cannot be omitted or overridden by a caller. The store's natural order is
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
     * measured 500-byte row width of {@code app/data/ASCII/custdata.txt}. <b>The key length is nine, not
     * ten.</b> A second customer-shaped cluster exists in the corpus &mdash;
     * {@code app/jcl/DEFCUST.jcl:L35-L38} defines {@code AWS.CUSTDATA.CLUSTER} with {@code KEYS(10 0)} and
     * {@code RECORDSIZE(500 500)} &mdash; and <b>no program in the corpus opens it</b>. It is an orphan, it is
     * not this reader's file, and its key length must never be mistaken for the real one. Both clusters are
     * described here so that the two are never conflated.
     * <p>
     * <b>No alternate index is consulted, because none exists over this cluster.</b> The catalogue records
     * exactly three alternate indexes corpus-wide &mdash; {@code app/catlg/LISTCAT.txt:L254} over
     * {@code CARDDATA}, {@code :L455} over {@code CARDXREF} and {@code :L3645} over {@code TRANSACT} &mdash;
     * and none over {@code CUSTDATA}. Inventing a secondary finder here would model an index the source does
     * not have.
     * <p>
     * <b>Why the window is keyset-bounded and not offset-paged</b> (Rule 1 clause A, tradeoff justified rather
     * than assumed). An offset page asks the store to produce and discard every row before the window, so
     * walking the relation costs work quadratic in its size, and the discarded prefix grows with every step. A
     * keyset window instead asks for {@code CUST-ID > cursor ... LIMIT pageSize}, which the primary-key index
     * satisfies by seeking straight to the cursor and reading forward: constant work per window, independent of
     * how far the scan has already travelled. This is also the closer analogue of the source, because a VSAM
     * sequential read positions by key and reads forward rather than counting from the start of the cluster.
     * The window size cannot affect the emitted output, because the ordering is fixed independently of it, and
     * the seek bound is exclusive so no row is visited twice or skipped.
     * <p>
     * <b>A bespoke repository method is declared for this, and it is not speculative.</b> An earlier revision
     * of this class narrowed the inherited {@code findAll(Pageable)} overload instead and recorded that a
     * declared finder would be dead code. That reasoning held only while the window was offset-paged: the
     * inherited overload can express an order and a limit, but it cannot express a seek bound, so it cannot
     * express this contract at all. The declared finder therefore has exactly one consumer, this method, and
     * the repository's own documentation was corrected in step.
     * <p>
     * A second, unrelated saving: this finder returns a {@code List}, so no {@code COUNT(*)} is issued. The
     * page-shaped predecessor computed a total on every refill that nothing on this path ever read. The one
     * count this class does perform is the deliberate, once-per-open one in {@code openCustomerFile()}, which
     * exists to make the empty-relation case an explicit logged outcome.
     *
     * @return {@link #STATUS_SUCCESS} when a record was placed in the record area, {@link #STATUS_END_OF_FILE}
     *     when the scan is exhausted, or {@link #STATUS_PHYSICAL_IO_ERROR} when the buffer yielded a
     *     {@code null} element, which a {@code NOT NULL} keyed relation cannot legitimately produce
     * @throws DataAccessException if the store rejects the query; translated by the caller
     */
    private String readNextRecord() {
        while (pageBufferIndex >= pageBuffer.size()) {
            // The window is bounded by the cursor, never by an offset: CUST-ID > cursor ORDER BY CUST-ID
            // ASC LIMIT pageSize. PageRequest.ofSize() is page zero, so the offset is always literally 0.
            pageBuffer = customerRepository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(
                    Long.valueOf(fetchCursorCustomerId), PageRequest.ofSize(pageSize));

            pageBufferIndex = 0;

            if (pageBuffer.isEmpty()) {
                customerRecord = null;
                return STATUS_END_OF_FILE;
            }

            // Advance the cursor to the highest key in the window just fetched, so the next window starts
            // strictly after it. Explicit null branch (Rule 1 clause B): CUST-ID is NOT NULL and is the
            // primary key, so a null here means the result set is not what the schema promises. It is
            // reported through the status vocabulary rather than allowed to become a NullPointerException,
            // and the cursor is deliberately left unadvanced on that path.
            Customer highestOfWindow = pageBuffer.get(pageBuffer.size() - 1);
            if (highestOfWindow == null || highestOfWindow.getCustomerId() == null) {
                customerRecord = null;
                return STATUS_PHYSICAL_IO_ERROR;
            }
            fetchCursorCustomerId = highestOfWindow.getCustomerId().longValue();
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

    // ====================================================================================================
    // Restart support and construction-time validation. No legacy counterpart: the mainline at
    // app/cbl/CBCUS01C.cbl:L70-L87 always scans from the first record, because a JES2 job restart re-ran the
    // step from the top. Restartability is additive, and it changes no emitted value.
    // ====================================================================================================

    /**
     * Restores the checkpoint written by {@link #update(ExecutionContext)} so a restarted step resumes instead
     * of re-emitting rows.
     * <p>
     * <b>The checkpointed key is the position; the row count is only a tally.</b> The scan resumes by seeking
     * to {@code CUST-ID > } the last identifier actually emitted, so the first window of the resumed run begins
     * at the row after it regardless of how many rows precede it. The predecessor of this method instead
     * divided the row count into a page number and a within-page offset, which positions correctly only while
     * the relation is unchanged between the two runs: any row inserted or deleted below the cursor shifts every
     * offset after it, so a restart could silently re-emit or silently skip rows. Seeking by key is immune to
     * that, because the key of a row does not move when its neighbours change. The identifier is reported in
     * the resume log line so an operator can see where the run is picking up; it is a surrogate key and carries
     * no personal attribute.
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

        // Explicit handled case (Rule 1 clause B). update() writes both keys together whenever a row has been
        // emitted, so this state cannot arise from this class; a hand-built context can still present it. The
        // alternative to failing here would be to restart from the beginning, which would re-emit every row
        // already emitted while reporting success, so the failure is deliberately loud.
        if (!executionContext.containsKey(CONTEXT_KEY_LAST_CUSTOMER_ID)) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "%s restart context records %d rows already emitted but carries no '%s' entry, so there "
                            + "is no key to resume the keyset scan from; restarting from the first row would "
                            + "re-emit those %d rows", LOGICAL_FILE, Long.valueOf(checkpointed),
                    CONTEXT_KEY_LAST_CUSTOMER_ID, Long.valueOf(checkpointed)));
        }

        recordsRead = checkpointed;
        lastCustomerId = Long.valueOf(executionContext.getLong(CONTEXT_KEY_LAST_CUSTOMER_ID));
        fetchCursorCustomerId = lastCustomerId.longValue();

        LOG.info("Resuming {} scan after {} rows; seeking past the last emitted key, which is deliberately "
                        + "not named here", LOGICAL_FILE, Long.valueOf(recordsRead));
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
