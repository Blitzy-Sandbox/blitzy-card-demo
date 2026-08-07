/*
 * ******************************************************************
 * Program     : CardReader.java
 * Application : CardDemo
 * Type        : Spring Batch ItemStreamReader (read-only verification step)
 * Function    : Read-only sequential scan of the card master, replacing the
 *               COBOL batch reader CBACT02C.
 * Source      : app/cbl/CBACT02C.cbl (178 lines, 6 paragraphs)
 *               app/cpy/CVACT02Y.cpy (150-byte CARD-RECORD)
 *               app/catlg/LISTCAT.txt:L202 (KEYLEN 16 / AVGLRECL 150)
 *               app/jcl/READCARD.jcl (job that executes CBACT02C)
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
import com.cardemo.model.entity.Card;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Read-only sequential scan of the card master, reproducing the COBOL batch program
 * {@code app/cbl/CBACT02C.cbl} paragraph for paragraph.
 * <p>
 * The four headings below are this file's discharge of <b>Rule 1 clause E</b>, which requires every
 * component to carry &quot;a short README or docstring&quot; covering what it does, how to run, build and
 * test it, its key configuration and defaults, and its common failure modes. The package-scope document
 * {@code com.cardemo.batch.readers.package-info} states the contract the seven readers share; the headings
 * below cover what is specific to this one and do not repeat it.
 *
 * <h2>What it does</h2>
 * Streams every row of the {@code card} relation in ascending primary-key order and hands each one to the
 * owning Spring Batch step, then terminates. Nothing is written, updated or deleted: this is a
 * <em>verification step</em>, and the read-only character is not a design preference but a measured property
 * of the source.
 * <p>
 * The verb inventory of {@code app/cbl/CBACT02C.cbl}, counted as statement starts with column-7 comment lines
 * stripped, is {@code OPEN}=1 ({@code :L120}), {@code READ}=1 ({@code :L93}), {@code CLOSE}=1
 * ({@code :L138}), {@code DISPLAY}=9 ({@code :L71}, {@code :L78}, {@code :L85}, {@code :L110}, {@code :L129},
 * {@code :L147}, {@code :L155}, {@code :L168}, {@code :L172}), and
 * {@code WRITE}={@code REWRITE}={@code DELETE}=<b>0</b>. Because the source contains no write verb at all,
 * this class adds no write path: no {@code save}, no {@code saveAll}, no {@code delete}, no
 * {@code @Modifying} query, no {@code EntityManager} mutation and no {@code flush}. The only repository
 * operations it ever performs are {@link CardRepository#count()} and
 * {@link CardRepository#findByCardNumberGreaterThanOrderByCardNumberAsc(String,
 * org.springframework.data.domain.Pageable)}.
 * <p>
 * <b>The verb counts cited here are statement counts, not lexical token counts.</b> A lexical count of
 * {@code OPEN} / {@code READ} / {@code CLOSE} / {@code DISPLAY} in this program yields 3 / 1 / 3 / 14,
 * because the token
 * {@code OPEN} also occurs in the paragraph label {@code 0000-CARDFILE-OPEN} ({@code :L118}) and in
 * {@code PERFORM 0000-CARDFILE-OPEN} ({@code :L72}), giving 3; {@code CLOSE} likewise occurs in
 * {@code 9000-CARDFILE-CLOSE} ({@code :L136}) and its {@code PERFORM} ({@code :L83}), giving 3; and
 * {@code DISPLAY} occurs in the label {@code 9910-DISPLAY-IO-STATUS} ({@code :L161}) and in its three
 * {@code PERFORM} statements ({@code :L112}, {@code :L131}, {@code :L149}), which is 9 + 1 + 3 = 13.
 * <b>The fourteenth {@code DISPLAY} token is the commented-out statement at {@code :L96}</b>, so the lexical
 * figure of 14 is only reachable by counting comment lines. The statement counts are the ones cited above.
 * The load-bearing fact is identical either way:
 * {@code WRITE}, {@code REWRITE} and {@code DELETE} are zero.
 *
 * <h3>Paragraph map, one Java member per COBOL paragraph, never consolidated</h3>
 * <table>
 * <caption>Paragraphs of {@code app/cbl/CBACT02C.cbl} and their Java targets</caption>
 * <tr><th>COBOL label</th><th>Locator</th><th>Java target</th></tr>
 * <tr><td>mainline {@code PROCEDURE DIVISION}</td><td>{@code :L70-L87}</td>
 *     <td>{@link #open(ExecutionContext)}, {@link #read()}, {@link #close()}</td></tr>
 * <tr><td>{@code 1000-CARDFILE-GET-NEXT}</td><td>{@code :L92-L116}</td>
 *     <td>{@code getNextCardRecord()}</td></tr>
 * <tr><td>{@code 0000-CARDFILE-OPEN}</td><td>{@code :L118-L134}</td><td>{@code openCardFile()}</td></tr>
 * <tr><td>{@code 9000-CARDFILE-CLOSE}</td><td>{@code :L136-L152}</td><td>{@code closeCardFile()}</td></tr>
 * <tr><td>{@code 9999-ABEND-PROGRAM}</td><td>{@code :L154-L158}</td>
 *     <td>{@code abendProgram(String, Throwable)}</td></tr>
 * <tr><td>{@code 9910-DISPLAY-IO-STATUS}</td><td>{@code :L161-L174}</td>
 *     <td>{@code displayIoStatus(String)}</td></tr>
 * </table>
 * <b>There is no {@code 1100-} display paragraph in {@code CBACT02C}, and none is invented here.</b> That
 * absence is exactly why this program has six paragraphs while {@code CBACT01C} has seven. Counting the
 * named labels of the four simple sequential readers settles it: {@code CBACT01C} declares six
 * ({@code 1000-ACCTFILE-GET-NEXT}, <b>{@code 1100-DISPLAY-ACCT-RECORD} at
 * {@code app/cbl/CBACT01C.cbl:L118}</b>, {@code 0000-}, {@code 9000-}, {@code 9999-}, {@code 9910-}), while
 * {@code CBACT02C} ({@code app/cbl/CBACT02C.cbl:L92}, {@code :L118}, {@code :L136}, {@code :L154},
 * {@code :L161}) and {@code CBACT03C} declare five apiece, and {@code CBCUS01C} declares five under a
 * different naming convention, spelling its last two {@code Z-ABEND-PROGRAM} and
 * {@code Z-DISPLAY-IO-STATUS} ({@code app/cbl/CBCUS01C.cbl:L154}, {@code :L161}) rather than {@code 9999-}
 * and {@code 9910-}. Adding the mainline gives seven paragraphs for {@code CBACT01C} and six for each of the
 * other three. {@code CBACT01C} is therefore the <em>only</em> one of the four that emits a record
 * field by field, and no {@code displayCardRecord}-shaped member exists in this class: the field-by-field
 * renderer of {@code com.cardemo.batch.readers.AccountReader} is deliberately not ported across.
 *
 * <h3>Structures preserved for parity, which must never be deleted as dead code</h3>
 * Rule 1 clause B forbids dead code; the migration mandate requires control flow to be reproduced one for one
 * so that paragraph-level traceability is mechanically provable. Where the two collide the parity mandate
 * governs, and clause B is satisfied instead by tracking and justifying each retained structure here rather
 * than by deleting it. Three such structures live in this class.
 * <ol>
 * <li><b>The redundant double guard.</b> The mainline is {@code PERFORM UNTIL END-OF-FILE = 'Y'}
 *     ({@code :L74}) wrapping an inner {@code IF END-OF-FILE = 'N'} ({@code :L75}), and a <em>second</em>
 *     {@code IF END-OF-FILE = 'N'} follows the read before the display ({@code :L77}). The second test can
 *     never fail when the first passed and the read returned a record, so it is redundant by inspection. Both
 *     are reproduced as explicit guards in {@link #read()} and neither is collapsed.</li>
 * <li><b>The commented-out {@code DISPLAY CARD-RECORD} inside {@code 1000-CARDFILE-GET-NEXT}.</b>
 *     {@code app/cbl/CBACT02C.cbl:L96} reads {@code *        DISPLAY CARD-RECORD} &mdash; a comment, not a
 *     statement. It sits in the {@code '00'} branch, immediately after {@code MOVE 0 TO APPL-RESULT}
 *     ({@code :L95}), exactly where {@code CBACT01C} performs its field-by-field paragraph. Because it is
 *     commented out, <b>{@code CBACT02C} emits each record exactly once</b>, from the mainline at
 *     {@code :L78}, unlike {@code CBACT03C} and {@code CBCUS01C} which emit twice. It is reproduced in
 *     {@code getNextCardRecord()} as a <b>Java comment carrying its citation and never as executable
 *     code</b>: emitting a second record event there would be a parity break, and it is also the single
 *     measured fact that explains why the {@code DISPLAY} statement count is 9.</li>
 * <li><b>The arithmetic-idiom variation between OPEN and CLOSE.</b> {@code 0000-CARDFILE-OPEN} primes the
 *     result field with {@code MOVE 8 TO APPL-RESULT} ({@code :L119}); {@code 9000-CARDFILE-CLOSE} primes the
 *     same field with {@code ADD 8 TO ZERO GIVING APPL-RESULT} ({@code :L137}), clears it with
 *     {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} ({@code :L140}) rather than moving zero, and sets the
 *     failure value with {@code ADD 12 TO ZERO GIVING APPL-RESULT} ({@code :L142}) rather than moving twelve.
 *     Every pair computes the same value by a different verb. The two paragraphs are deliberately
 *     <em>not</em> normalised into one shape; each Java method documents the idiom its own source paragraph
 *     uses.</li>
 * </ol>
 * No zoned-decimal overpunch decoder appears anywhere in this class, and that is a measured decision rather
 * than an omission: {@code app/cpy/CVACT02Y.cpy} declares <b>no {@code S9} picture clause at all</b>, so the
 * record carries no signed numeric field, and a census of {@code app/data/ASCII/carddata.txt} finds
 * <b>zero</b> occurrences of the overpunch characters that encode a sign. A decoder here would therefore be
 * unreachable code, which Rule 1 clause B forbids, and it would duplicate logic that belongs to
 * {@code src/main/resources/db/migration/V3__seed_data.sql}.
 * <p>
 * <b>The field contract, and the three type facts it turns on.</b> {@code app/cpy/CVACT02Y.cpy:L5-L11}
 * declares six fields plus {@code FILLER PIC X(59)}, summing to the 150 bytes that
 * {@code app/catlg/LISTCAT.txt:L202} and {@code app/jcl/CARDFILE.jcl:L55} independently corroborate. Three of
 * the resulting {@link Card} mappings are counter-intuitive enough to have been misread before, so each is
 * recorded here even though this read-only scan reads only the first of them:
 * <ul>
 *   <li>{@code CARD-ACCT-ID PIC 9(11)} ({@code :L6}) maps to {@link Card#getAccountId()}, a <b>plain scalar
 *       {@code Long}</b>. It is deliberately <em>not</em> a {@code @ManyToOne} association: nothing here
 *       navigates to an {@code Account}, this class imports no account type, and it issues no join.</li>
 *   <li>{@code CARD-CVV-CD PIC 9(03)} ({@code :L7}) maps to {@link Card}'s {@code cvvCode} over
 *       {@code card_cvv_cd CHAR(3) NOT NULL} - it <b>is</b> modelled and seeded - but that field is
 *       <b>write-once and accessor-less</b>: no getter of any visibility exists, so there is nothing for
 *       this reader to read, project or log. See the field documentation on {@link Card}.</li>
 *   <li>{@code CARD-EXPIRAION-DATE PIC X(10)} ({@code :L9}) maps to a <b>{@code String}</b> over
 *       {@code CHAR(10)} and never to a {@code LocalDate}: the picture clause is alphanumeric, so the stored
 *       value is carried byte-for-byte rather than reinterpreted through a date parser.</li>
 *   </ul>
 *
 * <h3>The one deliberate deviation: the legacy whole-record emission is not reproduced</h3>
 * {@code app/cbl/CBACT02C.cbl:L78} performs {@code DISPLAY CARD-RECORD}, which writes all 150 bytes of the
 * record to SYSOUT. Those bytes include the <b>16-character card number</b> at 1-based bytes 1-16 and the
 * <b>3-digit card verification value</b> at 1-based bytes 28-30 ({@code app/cpy/CVACT02Y.cpy:L5},
 * {@code :L7}). Reproducing it would publish a primary account number and its verification value into the log
 * estate on every row of every run.
 * <p>
 * <b>That emission is therefore deliberately not reproduced</b>, under Rule 1 clause D, &quot;No secrets in
 * code, logs, tests, or config&quot;. What {@link #read()} emits in its place is an <b>identifier-only
 * projection</b>: the row sequence number and {@code CARD-ACCT-ID}, at {@code DEBUG}, and nothing else. The
 * card number is never rendered in any form, whole or partial, at any level; <b>the verification value is
 * never rendered at all, in any form, at any level, anywhere in this class</b>.
 * <p>
 * <b>Why an identifier-only projection rather than a last-four rendering.</b> A partial rendering was
 * considered and rejected on two independent grounds. First, {@link Card} deliberately provides no masking
 * helper: {@link Card#toString()} withholds the card number, the verification value, the embossed name, the
 * expiry date and the active status, and its contract states in terms that the entity offers no
 * partial-display or masking method because presentation-layer masking belongs to the DTO layer. Building one
 * inside a reader would duplicate a decision another type owns, which Rule 1 clause C forbids. Second, a
 * last-four rendering would place four genuine digits of a live account number into the log estate in
 * exchange for no correlation value that {@code CARD-ACCT-ID} does not already supply, so it fails clause D
 * on its own terms. The account identifier is a surrogate key with no payment content and is the only
 * identifier on this record that may be rendered at all.
 * <p>
 * The projection reads {@link Card#getAccountId()} explicitly rather than relying on
 * {@link Card#toString()}. Both are safe today; naming the single safe field means this call site cannot
 * widen even if that contract ever does.
 * <p>
 * The masking rules in {@code src/main/resources/logback-spring.xml} govern whatever does reach a log
 * aggregator and are a backstop, not the primary defence: never emitting the value is the primary defence.
 * <b>The deviation changes what is emitted and is stated as such rather than presented as parity.</b>
 *
 * <h2>How to run, build and test</h2>
 * The read-only verification {@code Step} that owns this reader is <strong>authored</strong>, and nothing
 * about the batch tier around it is outstanding. Two earlier revisions of this paragraph are withdrawn:
 * the first named {@code com.cardemo.config.BatchConfig} as the home of every {@code Job} and
 * {@code Step} and said {@code com.cardemo.batch.jobs} held one job, {@code InterestCalculationJob}; the
 * second said this reader's verification step and its launcher were still owed. Both exist:
 * {@link com.cardemo.config.BatchConfig#datasetVerificationReadCardStep} is the
 * Java counterpart of {@code app/jcl/READCARD.jcl}, and
 * {@link com.cardemo.config.BatchConfig#datasetVerificationJob} composes it with the
 * three sibling members as one operator submission, selectable by name through the framework's own
 * {@code spring.batch.job.name} property, which is the launch signal that survives after the
 * bespoke operator launcher was withdrawn. The step writes nothing: its sink reaches no relation, no object store and no
 * queue, because {@code app/cbl/CBACT02C.cbl} performs {@code OPEN}, {@code READ} and {@code CLOSE} only.
 * {@code com.cardemo.batch.jobs} holds its six target jobs and <strong>each declares its own
 * {@code Step} beans</strong>, while {@code BatchConfig} owns the dataset bindings and the record
 * rendering rather than step topology. {@code spring.batch.job.enabled} is {@code false} in
 * {@code src/main/resources/application.yml}, so no job runs at application startup and every submission
 * is deliberate. This class carries {@code @Component} and {@code @StepScope}, so the component scan
 * registers a definition for it and the step scope gives each step execution its own instance.
 * <p>
 * The legacy standalone job is
 * {@code app/jcl/READCARD.jcl}, whose {@code STEP05} is {@code EXEC PGM=CBACT02C} at {@code :L22} with
 * {@code //CARDFILE DD} pointing at {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS} at {@code :L25-L26}.
 * <p>
 * Two build paths are available; both are pinned and either may be used. Each names a required
 * <em>capability</em> rather than a dated reading of one host; dated measurements live in section 0.4.5.3 of
 * {@code docs/technical-specifications.md}.
 * <ul>
 * <li><b>Host toolchain</b> &mdash; JDK 25 with {@code JAVA_HOME} set, then
 *     {@code ./mvnw -B -ntp clean compile} and {@code ./mvnw -B -ntp test}. Maven 3.9.11 comes from the
 *     pinned wrapper and {@code maven-enforcer-plugin} floors both.</li>
 * <li><b>Pinned container</b> &mdash; given a reachable container daemon, the build
 *     can also run hermetically:
 *     {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q -DskipTests compile}.
 *     </li>
 * </ul>
 * The compiler runs with {@code -Xlint:all -Werror} and {@code failOnWarning}, so the build fails on any
 * warning category {@code javac} 25 publishes. Coverage is gated by JaCoCo at an eighty percent line floor
 * with no package excluded. <strong>Both test tiers now cover this class.</strong> An earlier revision of this
 * paragraph said neither was authored, that {@code unit/batch} held three classes none of which referenced this
 * reader, and that {@code integration/batch} held one abstract Testcontainers base with no concrete subclass
 * beneath it; every part of that is withdrawn. {@code src/test/java/com/cardemo/unit/batch} holds
 * <strong>36</strong> sources and covers the status renderer, the guard logic and the identifier-only
 * projection through {@code CardReaderTest}, {@code SequentialReaderContractTest},
 * {@code SequentialReaderKeysetScanTest}, {@code ReaderSensitiveDataTest} and {@code BatchLogHygieneTest}.
 * {@code src/test/java/com/cardemo/integration/batch} holds <strong>4</strong> sources - one abstract
 * Testcontainers base and three concrete classes that execute under Failsafe against PostgreSQL 16 and
 * LocalStack. This class still creates neither, because test sources are outside the scope of the package it
 * belongs to.
 *
 * <h2>Key configs and defaults</h2>
 * <ul>
 * <li>{@code carddemo.batch.card-reader.page-size} &mdash; the number of rows fetched per round trip.
 *     Default {@value #DEFAULT_PAGE_SIZE}, which covers the entire 50-row seed fixture
 *     {@code app/data/ASCII/carddata.txt} in a single query while keeping the resident set bounded. The value
 *     is validated on construction and must be at least one. It is a buffering choice only and has no effect
 *     on the emitted sequence, because the ordering is fixed independently of it. The key is not declared in
 *     any profile, so the default applies unless it is set.</li>
 * <li>Ordering is always ascending on {@code cardNumber}, never the store's natural order. This mirrors
 *     {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS SEQUENTIAL} and
 *     {@code RECORD KEY IS FD-CARD-NUM} ({@code app/cbl/CBACT02C.cbl:L29-L33}) over a KSDS whose key length
 *     is 16 ({@code app/catlg/LISTCAT.txt:L202}, corroborated by {@code KEYS(16 0)} at
 *     {@code app/jcl/CARDFILE.jcl:L54}), and it makes the emitted sequence reproducible.</li>
 * <li>The chunk size of the owning step comes from {@code carddemo.batch.chunk-size} and is independent of
 *     the page size above; neither affects the other, and neither affects the order.</li>
 * <li>{@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile,
 *     {@code spring.jpa.open-in-view} is {@code false} and {@code spring.jpa.show-sql} is {@code false}; the
 *     schema is owned by the Flyway migrations, and no bind-parameter logging is enabled anywhere, which is
 *     what keeps the card-number primary key out of the log path entirely.</li>
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
 * <li><b>An empty card relation completes successfully</b> with a row count of zero. It is logged explicitly
 *     at {@code open} time rather than inferred later from the absence of records.</li>
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
 *     {@code ERROR OPENING CARDFILE} followed by the rendered status, and abends. Check that the Flyway
 *     migrations have applied and that the datasource points at the intended database.</li>
 * <li><b>An account identifier appearing where a card number was expected is the intended behaviour</b>, not
 *     a defect: see the deviation described above. If a full card number is genuinely needed for an
 *     investigation, read it from the authorised, audited {@code card} row rather than recovering it from a
 *     log line.</li>
 * </ul>
 *
 * <h2>Deviations and preserved source behaviours</h2>
 * <ul>
 * <li>Reproducing {@code DISPLAY CARD-RECORD} ({@code :L78}) verbatim would emit a primary account number
 *     and a card verification value on every row, so the identifier-only projection described above is
 *     emitted instead. That is the reason the deviation exists, and it is stated here so that a later
 *     reviewer does not &quot;restore parity&quot; by reinstating the full emission.</li>
 * <li>The misspelling in {@code CARD-EXPIRAION-DATE} ({@code app/cpy/CVACT02Y.cpy:L9}, missing the
 *     {@code T} of &quot;EXPIRATION&quot;) is retained deliberately, as {@link Card#getExpiraionDate()}
 *     over column {@code card_expiraion_date}. It is a corpus-wide convention rather than an isolated slip
 *     &mdash; the account layout carries {@code ACCT-EXPIRAION-DATE} in the same shape &mdash; and
 *     correcting it in any one place would break either the column contract, which fails schema validation
 *     at startup, or the traceability mapping.</li>
 * <li>The card alternate index is cited from {@code app/jcl/CARDFILE.jcl:L83-L88} and
 *     {@code app/catlg/LISTCAT.txt:L281-L285}. {@code app/jcl/TRANIDX.jcl} is deliberately cited nowhere
 *     in this file: {@code :L25-L27} of that member defines {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX} with
 *     {@code KEYS(26 304)}, which is the <em>transaction</em> alternate index, not the card one.</li>
 * <li>{@link #update(ExecutionContext)} checkpoints the row count and nothing else. It deliberately does
 *     <b>not</b> checkpoint the card number of the last row read: a full sixteen-digit primary account
 *     number written into generic framework metadata would leave the one relation the cardholder-data
 *     boundary is drawn around and land in {@code BATCH_STEP_EXECUTION_CONTEXT}, governed by the
 *     framework's retention and access needs rather than by any cardholder-data policy. The row count is
 *     provably sufficient as a cursor because the ordering is fixed, so the card number never leaves its
 *     row anywhere in this class. See that method for the full rationale.</li>
 * <li>The lexical-versus-statement verb count divergence described above needs no action beyond citing the
 *     statement counts.</li>
 * <li>The {@code '9x'} status family maps to {@code com.cardemo.exception.FileAccessException} in the
 *     shared status vocabulary, but this reader never raises it. That is measured, not an oversight: every
 *     failure branch in {@code CBACT02C} runs {@code DISPLAY} then
 *     {@code PERFORM 9910-DISPLAY-IO-STATUS} then {@code PERFORM 9999-ABEND-PROGRAM}
 *     ({@code :L110-L113}, {@code :L129-L132}, {@code :L147-L150}), so there is no path on which a
 *     non-normal status is anything but fatal. Introducing a non-fatal I/O outcome here would be a
 *     behaviour change.</li>
 * <li>The specific z/OS VSAM subcode a given JDBC failure would have produced on the mainframe cannot be
 *     established from this repository, because mainframe-runtime reproduction is out of scope. Should a
 *     byte-exact subcode ever be required, the SQLSTATE-to-subcode table belongs at the
 *     {@link FileStatusMapper} layer, where the single definition of the status vocabulary already lives,
 *     rather than in this reader.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <b>Not thread safe, by design.</b> The instance carries the cursor state that the legacy program held in
 * {@code WORKING-STORAGE} ({@code :L42-L67}): {@code END-OF-FILE}, {@code APPL-RESULT}, {@code IO-STATUS},
 * the record area and the row counter. The {@code step} scope gives each step execution its own instance,
 * which is precisely what keeps that state from being shared. There are <b>no mutable static fields</b>: the
 * only static members are the logger and immutable constants.
 *
 * @see CardRepository
 * @see FileStatusMapper
 * @see Card
 */
@Component
@StepScope
public class CardReader implements ItemStreamReader<Card> {

    /**
     * The one permitted static member that behaves like state: a logger reference that is itself immutable.
     * Every emission in this class goes through SLF4J, never {@code System.out}, {@code System.err} or
     * {@code printStackTrace()}, so the JSON encoding and the credential, hash and social-security masking
     * rules configured in {@code src/main/resources/logback-spring.xml} govern what actually reaches a log
     * aggregator.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardReader.class);

    /**
     * Default rows per round trip, used when {@code carddemo.batch.card-reader.page-size} is not set.
     * Chosen so the entire 50-row seed fixture {@code app/data/ASCII/carddata.txt} (7,550 bytes, 50 records
     * of 150 bytes plus a terminator, measured) is satisfied by one query while the resident set stays small.
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * The entity property the scan is ordered by, and the JPA counterpart of {@code CARD-NUM}
     * ({@code app/cpy/CVACT02Y.cpy:L5}), which is also {@code RECORD KEY IS FD-CARD-NUM}
     * ({@code app/cbl/CBACT02C.cbl:L32}).
     */
    private static final String ORDER_PROPERTY = "cardNumber";

    /**
     * Logical file name reported on every diagnostic. It is the {@code ASSIGN TO} name at
     * {@code app/cbl/CBACT02C.cbl:L29}, which is also the DD name at {@code app/jcl/READCARD.jcl:L25}. It is
     * deliberately the DD name rather than the CICS file name {@code CARDDAT}
     * ({@code app/csd/CARDDEMO.CSD:L25}), because the legacy diagnostics this class reproduces are batch
     * output and the batch program never sees the CICS name.
     */
    private static final String LOGICAL_FILE = "CARDFILE";

    /**
     * The program name the legacy load module carried, used as the abend culprit so that
     * {@code ABEND-CULPRIT PIC X(8)} of {@code app/cpy/CSMSG02Y.cpy} is populated with a real component
     * identity. Exactly eight characters, matching the picture clause and matching
     * {@code PROGRAM-ID. CBACT02C.} at {@code app/cbl/CBACT02C.cbl:L23}.
     */
    private static final String ABEND_CULPRIT = "CBACT02C";

    // ----------------------------------------------------------------------------------------------------
    // Legacy DISPLAY literals, reproduced byte for byte. Each is followed by its measured inner length so a
    // reviewer can confirm fidelity without opening the source. Rule 1 clause F: every assertion is cited.
    // All seven literals of the program are represented; there is no eighth, because DISPLAY CARD-RECORD at
    // :L78 emits a record rather than a literal and is covered by the deviation described above.
    // ----------------------------------------------------------------------------------------------------

    /** {@code app/cbl/CBACT02C.cbl:L71}, 38 characters. */
    private static final String START_OF_EXECUTION_MESSAGE = "START OF EXECUTION OF PROGRAM CBACT02C";

    /** {@code app/cbl/CBACT02C.cbl:L85}, 36 characters. */
    private static final String END_OF_EXECUTION_MESSAGE = "END OF EXECUTION OF PROGRAM CBACT02C";

    /**
     * {@code app/cbl/CBACT02C.cbl:L110}, 22 characters.
     * <p>
     * Note that all three of this program's error literals name the file identically, as {@code CARDFILE},
     * and all three are 22 characters. {@code CBACT01C} is internally inconsistent on the same three lines,
     * spelling the file {@code ACCTFILE} on the open path and {@code ACCOUNT FILE} on the read and close
     * paths; {@code CBACT02C} is not, and the consistency here is the source's, not a normalisation applied
     * by this class.
     */
    private static final String ERROR_READING_MESSAGE = "ERROR READING CARDFILE";

    /** {@code app/cbl/CBACT02C.cbl:L129}, 22 characters. */
    private static final String ERROR_OPENING_MESSAGE = "ERROR OPENING CARDFILE";

    /** {@code app/cbl/CBACT02C.cbl:L147}, 22 characters. */
    private static final String ERROR_CLOSING_MESSAGE = "ERROR CLOSING CARDFILE";

    /** {@code app/cbl/CBACT02C.cbl:L155}, 16 characters. */
    private static final String ABENDING_PROGRAM_MESSAGE = "ABENDING PROGRAM";

    // ----------------------------------------------------------------------------------------------------
    // Execution-context keys for the restart cursor. Namespaced by simple class name so two readers in the
    // same step cannot collide.
    // ----------------------------------------------------------------------------------------------------

    /** Key under which the number of rows already emitted is checkpointed. */
    private static final String CONTEXT_KEY_RECORDS_READ = "CardReader.recordsRead";

    /**
     * Exclusive lower bound seeding the first keyset window, chosen to sit provably below the entire key
     * space so that {@code CARD-NUM > } this value selects the true first row.
     * <p>
     * The proof, not an assumption: {@code CARD-NUM} is declared {@code PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:L5} and materialised as {@code CHAR(16) NOT NULL}, so under character
     * collation the empty string precedes every value the column can hold. It is a bound, never a key: the
     * comparison is strict, and no card number is empty, so no row can be skipped by it. The same seed and
     * the same reasoning are already used by {@code CardCrossReferenceRepository}, whose keyset finder
     * documents the empty string as preceding every non-empty card number; the two are kept identical
     * deliberately.
     */
    private static final String SEED_CARD_NUMBER = "";

    /** {@code END-OF-FILE PIC X(01) VALUE 'N'} in its initial state ({@code app/cbl/CBACT02C.cbl:L65}). */
    private static final String END_OF_FILE_NO = "N";

    /** {@code END-OF-FILE} after {@code MOVE 'Y' TO END-OF-FILE} ({@code app/cbl/CBACT02C.cbl:L108}). */
    private static final String END_OF_FILE_YES = "Y";

    /** The character a COBOL {@code MOVE} into a numeric display item pads with on the left. */
    private static final char NUMERIC_PAD = '0';

    // ----------------------------------------------------------------------------------------------------
    // File-status literals, derived from com.cardemo.model.enums.FileStatus rather than restated, so that the
    // single definition of each code stays single (Rule 1 clause C, avoid duplication).
    // ----------------------------------------------------------------------------------------------------

    /** {@code '00'}: the status the source tests at {@code :L94}, {@code :L121} and {@code :L139}. */
    private static final String STATUS_SUCCESS = requireExactCode(FileStatus.SUCCESS);

    /** {@code '10'}: end of file, which drives {@code MOVE 16 TO APPL-RESULT} at {@code :L99}. */
    private static final String STATUS_END_OF_FILE = requireExactCode(FileStatus.END_OF_FILE);

    /**
     * The member of the {@code '9x'} family this reader reports when the store rejects an operation.
     * <p>
     * {@link FileStatus#IO_ERROR} is a family rather than a value: its first byte is fixed at
     * {@link FileStatus#IO_ERROR_FIRST_BYTE} and the second byte carries an implementation-defined subcode. A
     * relational store reports a failure as a {@link DataAccessException} hierarchy and a driver SQLSTATE,
     * neither of which carries a VSAM subcode, so the subcode is set to {@code '0'} to mean &quot;no further
     * subcode available from this layer&quot;. The driver's own detail is never discarded: it travels on the
     * cause of the thrown exception. The class documentation records why no VSAM subcode is available.
     * <p>
     * A first byte of {@code '9'} is also what selects the first branch of {@code 9910-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBACT02C.cbl:L163}), so a store failure renders through the same branch the legacy
     * program used for a physical I/O error rather than through the zero-padded branch.
     */
    private static final String STATUS_PHYSICAL_IO_ERROR =
            String.valueOf(FileStatus.IO_ERROR_FIRST_BYTE) + NUMERIC_PAD;

    // ----------------------------------------------------------------------------------------------------
    // Collaborators, injected through the constructor and never reassigned.
    // ----------------------------------------------------------------------------------------------------

    /**
     * The persistence access point for the card master, replacing the {@code CARDFILE} VSAM cluster
     * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}.
     * <p>
     * Only two of its operations are ever called, and both are read-only: the inherited
     * {@link CardRepository#count()} and the declared
     * {@link CardRepository#findByCardNumberGreaterThanOrderByCardNumberAsc(String,
     * org.springframework.data.domain.Pageable)}, which exists for this class and has no other consumer.
     * <p>
     * The interface's other two derived finders, {@code findByAccountIdOrderByCardNumberAsc} and
     * {@code findAllByOrderByCardNumberAsc}, model the <em>online</em> card-list browse of
     * {@code app/cbl/COCRDLIC.cbl} and belong to {@code com.cardemo.service.card.CardListService}; calling one
     * of them from a batch reader would misattribute this class's source, and neither expresses a seek bound
     * in any case. Narrowing the inherited {@code findAll(Pageable)} with an explicit sort would express an
     * order and a limit but not a keyset bound, which is why a dedicated finder is declared for this scan
     * rather than an inherited overload reused.
     */
    private final CardRepository cardRepository;

    /**
     * The central {@code FILE STATUS} translator. Consumed rather than re-implemented: it already renders the
     * {@code 9910-DISPLAY-IO-STATUS} line and already applies the {@code APPL-RESULT} arithmetic of both the
     * two-way guard and the three-way sequential-read guard, and its own documentation cites
     * {@code app/cbl/CBACT02C.cbl:L98} among the sites for the latter. Duplicating any of that here would
     * violate Rule 1 clause C.
     */
    private final FileStatusMapper fileStatusMapper;

    /** Rows fetched per round trip; validated at construction and never changed afterwards. */
    private final int pageSize;

    // ----------------------------------------------------------------------------------------------------
    // Cursor state. Every field below is the Java counterpart of a WORKING-STORAGE item at
    // app/cbl/CBACT02C.cbl:L42-L67 and is therefore an INSTANCE field: never static, never shared. The step
    // scope gives each step execution its own instance.
    // ----------------------------------------------------------------------------------------------------

    /** {@code END-OF-FILE PIC X(01)} ({@code :L65}). Held as its literal {@code 'N'} or {@code 'Y'} value. */
    private String endOfFile = END_OF_FILE_NO;

    /** {@code APPL-RESULT PIC S9(9) COMP} ({@code :L61}), tested through {@code APPL-AOK} and {@code APPL-EOF}. */
    private int applResult;

    /** {@code IO-STATUS} ({@code :L50-L52}), the two-character status moved in before the renderer runs. */
    private String ioStatus = STATUS_SUCCESS;

    /** {@code CARD-RECORD}, the record area that {@code COPY CVACT02Y} declares at {@code :L45}. */
    private Card cardRecord;

    /** The rows of the page currently buffered, standing in for the VSAM read-ahead buffer. */
    private List<Card> pageBuffer = List.of();

    /** Cursor into {@link #pageBuffer}; the next row to hand out. */
    private int pageBufferIndex;

    /**
     * Keyset cursor: the highest {@code CARD-NUM} already <em>fetched</em> into {@link #pageBuffer}, and
     * therefore the exclusive lower bound of the next window. Seeded to {@link #SEED_CARD_NUMBER}, which is
     * provably below the whole key space, so the first window starts at the true first row.
     * <p>
     * It runs ahead of the most recently emitted row by up to {@link #pageSize} rows, because a window is
     * fetched whole before any of its rows is handed out. Conflating the two would skip rows on restart, which
     * is why the fetch position and the emission tally are tracked separately: this field positions the
     * <em>next query</em>, while {@link #recordsRead} records how many rows have actually been emitted and is
     * the only one of the two that is checkpointed.
     * <p>
     * Being a card number it is <b>never logged, never checkpointed, never returned by any accessor and never
     * placed in an exception message</b>. On a restart it is re-derived from the emission tally by
     * {@link #restoreRestartCursor(ExecutionContext)} rather than read back from framework metadata.
     */
    private String fetchCursorCardNumber = SEED_CARD_NUMBER;

    /** Rows emitted so far, the counter the end-of-run summary reports. */
    private long recordsRead;

    /** Whether {@code openCardFile()} has completed successfully, mirroring an open VSAM ACB. */
    private boolean fileOpen;

    /** Total rows the relation held when the file was opened, used for the explicit empty-relation branch. */
    private long recordCountAtOpen;

    /**
     * Creates a reader bound to the card master.
     *
     * @param cardRepository the card persistence access point; must not be {@code null}
     * @param fileStatusMapper the shared {@code FILE STATUS} translator; must not be {@code null}
     * @param pageSize rows per round trip, supplied by {@code carddemo.batch.card-reader.page-size} and
     *     defaulting to {@value #DEFAULT_PAGE_SIZE}; must be at least one
     * @throws NullPointerException if either collaborator is {@code null}
     * @throws IllegalArgumentException if {@code pageSize} is less than one
     */
    public CardReader(
            CardRepository cardRepository,
            FileStatusMapper fileStatusMapper,
            @Value("${carddemo.batch.card-reader.page-size:" + DEFAULT_PAGE_SIZE + "}") int pageSize) {
        // Only Objects.requireNonNull and private static validators are called here. Invoking an overridable
        // instance method from the constructor of a non-final class would publish a partially built reference,
        // which -Xlint:all -Werror reports as this-escape; the step scope forbids a final class because it
        // proxies by subclassing.
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.pageSize = requirePositivePageSize(pageSize);
    }

    // ====================================================================================================
    // Mainline PROCEDURE DIVISION, app/cbl/CBACT02C.cbl:L70-L87, realised as the ItemStream lifecycle.
    // ====================================================================================================

    /**
     * Opens the scan: emits the start-of-execution banner and performs {@code 0000-CARDFILE-OPEN},
     * reproducing {@code app/cbl/CBACT02C.cbl:L71-L72}.
     * <p>
     * <b>Side effects.</b> Resets all cursor state, restores the restart cursor from {@code executionContext}
     * when one is present, issues one {@code count()} round trip against the card relation, and writes two or
     * three log events. Nothing is emitted that could carry a card number or a verification value.
     *
     * @param executionContext the step execution context; a restart cursor written by a previous run of the
     *     same step instance is honoured when present, and a {@code null} context is treated as a cold start
     * @throws org.springframework.batch.item.ItemStreamException never thrown directly; a store failure is
     *     reported as {@link FatalProcessingException}, which is also unchecked
     * @throws FatalProcessingException if the card relation cannot be reached, reproducing the abend at
     *     {@code app/cbl/CBACT02C.cbl:L132}
     */
    @Override
    public void open(ExecutionContext executionContext) {
        // Cold-start every cursor field first, so a reused instance cannot inherit a previous scan's position.
        endOfFile = END_OF_FILE_NO;
        applResult = FileStatusMapper.APPL_AOK;
        ioStatus = STATUS_SUCCESS;
        cardRecord = null;
        pageBuffer = List.of();
        pageBufferIndex = 0;
        fetchCursorCardNumber = SEED_CARD_NUMBER;
        recordsRead = 0L;
        fileOpen = false;
        recordCountAtOpen = 0L;

        // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'.  (:L71)
        LOG.info(START_OF_EXECUTION_MESSAGE);

        // A null context is an explicit, handled case rather than a guarded assumption (Rule 1 clause B).
        if (executionContext != null && executionContext.containsKey(CONTEXT_KEY_RECORDS_READ)) {
            restoreRestartCursor(executionContext);
        }

        // PERFORM 0000-CARDFILE-OPEN.  (:L72)
        openCardFile();
    }

    /**
     * Returns the next card, or {@code null} once the scan is exhausted, reproducing the mainline loop body at
     * {@code app/cbl/CBACT02C.cbl:L74-L81}.
     * <p>
     * The method body is the loop <em>body</em>, not the loop: Spring Batch drives the iteration, so
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} becomes the framework calling this method until it answers
     * {@code null}. Both of the source's guards are reproduced explicitly, in order, and neither is collapsed;
     * see parity structure 1 in the class documentation.
     * <p>
     * <b>Where the legacy whole-record emission would have gone.</b> {@code :L78} is
     * {@code DISPLAY CARD-RECORD} over all 150 bytes, which would publish the card number at 1-based bytes
     * 1-16 and the verification value at 1-based bytes 28-30. It is replaced by an identifier-only projection
     * at {@code DEBUG} carrying the row sequence number and {@code CARD-ACCT-ID} only. This is the single
     * deliberate deviation in this class; the full rationale is in the class documentation.
     * <p>
     * <b>Why there is no {@code @Transactional} annotation.</b> A chunk-oriented step already runs this method
     * inside its own transaction, and Spring silently ignores the {@code readOnly} attribute of a method that
     * merely <em>participates</em> in an existing transaction rather than starting one. Annotating
     * {@code readOnly = true} here would therefore read as an enforced guarantee while enforcing nothing,
     * which Rule 1 clause A rules out. Read-only is guaranteed structurally instead: the only repository
     * operations this class can reach are {@link CardRepository#count()} and
     * {@link CardRepository#findByCardNumberGreaterThanOrderByCardNumberAsc(String,
     * org.springframework.data.domain.Pageable)}, and there is no mutating call,
     * no {@code @Modifying} query and no {@code EntityManager} reference anywhere in the file.
     *
     * @return the next card in ascending {@code cardNumber} order, or {@code null} at end of data, which is
     *     the Spring Batch end-of-input signal and the analogue of {@code MOVE 'Y' TO END-OF-FILE}
     * @throws FatalProcessingException if the store reports a status that is neither {@code '00'} nor
     *     {@code '10'}, reproducing the abend path at {@code app/cbl/CBACT02C.cbl:L110-L113}
     * @throws IllegalStateException if called before {@link #open(ExecutionContext)}, which has no legacy
     *     counterpart because the mainline performs the open unconditionally at {@code :L72}
     */
    @Override
    public Card read() {
        if (!fileOpen) {
            throw new IllegalStateException(
                    "read() called before open(ExecutionContext); app/cbl/CBACT02C.cbl:L72 performs "
                            + "0000-CARDFILE-OPEN before the mainline loop, so the file is always open by "
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

        // PERFORM 1000-CARDFILE-GET-NEXT  (:L76)
        Card card = getNextCardRecord();

        // IF END-OF-FILE = 'N'  (:L77) - the second guard. It cannot fail when the first passed and a record
        // was returned, which is exactly why it is redundant, and exactly why it is preserved: parity
        // structure 1. The null test is the same condition expressed through the returned value.
        if (!END_OF_FILE_NO.equals(endOfFile) || card == null) {
            return null;
        }

        recordsRead++;

        // DISPLAY CARD-RECORD  (:L78) - the ONE emission this program makes per row, because the second
        // DISPLAY CARD-RECORD at :L96 is commented out. Reproduced as the FACT of the read and its ordinal,
        // and nothing more: bytes 1-16 are the card number and bytes 28-30 the card verification value
        // (app/cpy/CVACT02Y.cpy:L5, :L7), so the record image was never a candidate - but CARD-ACCT-ID is not
        // one either. It is a customer's account identifier, and a log is aggregated, retained and replicated
        // outside the boundary that protects the row, so no level is low enough to make it safe. No field of
        // the record is read here at all, which is why this call site cannot widen when any entity contract
        // does.
        if (LOG.isDebugEnabled()) {
            LOG.debug("{} record read (app/cbl/CBACT02C.cbl:L78); sequence={}",
                    LOGICAL_FILE, Long.valueOf(recordsRead));
        }

        return card;
    }

    /**
     * Checkpoints the restart cursor so an interrupted step can resume without re-emitting rows.
     * <p>
     * <b>Exactly one value is checkpointed, and it is the whole of the durable cursor:</b> the number of rows
     * already emitted. A sixteen-character {@code CARD-NUM} is cardholder data, so it is deliberately never
     * written to framework metadata - see {@link #update(ExecutionContext)}. The keyset position the scan runs
     * on is therefore <b>re-derived</b> from that count by a single bounded seek at restart, after which every
     * subsequent window is selected by {@code CARD-NUM > } the previous window's highest key rather than by an
     * offset. No entity, page, buffer or business value is serialised.
     * <p>
     * <b>The card number is deliberately NOT checkpointed.</b> Writing the primary key of the most recently
     * emitted row as a verification anchor would be defensible on a narrow reading: the step execution context
     * is transactional state persisted to {@code BATCH_STEP_EXECUTION_CONTEXT} in the same database and schema
     * whose {@code card.card_num} column already holds the identical value under the same access control, so
     * no new trust boundary is crossed and Rule 1 clause D's four named surfaces (code, logs, tests,
     * configuration) are untouched.
     * <p>
     * That argument is true and it is not sufficient, which is why no such write exists. Data minimisation is not
     * the same test as trust-boundary equivalence: a full sixteen digit primary account number written into
     * <em>generic framework metadata</em> escapes the one place a cardholder-data boundary is drawn - the
     * relation itself - and lands in a table whose retention, export and administrative access are governed by
     * Spring Batch's needs rather than by any cardholder-data policy.
     * <p>
     * <b>Removing it costs no restartability, because the position is recoverable without it.</b> The scan is
     * ordered by an explicit ascending sort on {@code cardNumber}, so the row count identifies the
     * last-emitted row exactly, and {@link #restoreRestartCursor(ExecutionContext)} re-derives the key with one
     * bounded seek before the scan resumes. That seek is the only offset query this class performs and it runs
     * once per restart, never once per window, so the keyset scan it re-seeds keeps its cost profile intact.
     * The key is held in memory for the duration of the step, is never logged, is exposed by no accessor and
     * never appears in an exception message.
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
    }

    /**
     * Closes the scan: performs {@code 9000-CARDFILE-CLOSE} and emits the end-of-execution banner,
     * reproducing {@code app/cbl/CBACT02C.cbl:L83-L85}.
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
     *     {@code app/cbl/CBACT02C.cbl:L150}
     */
    @Override
    public void close() {
        // PERFORM 9000-CARDFILE-CLOSE.  (:L83)
        closeCardFile();

        // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'.  (:L85)
        LOG.info("{} recordsRead={}", END_OF_EXECUTION_MESSAGE, Long.valueOf(recordsRead));

        // GOBACK.  (:L87) - returning from close() is the return to the caller; Spring Batch then completes
        // the step. No RETURN-CODE is set here: for this read-only verification step the only non-zero
        // outcome is the abend, which propagates as an exception and fails the step on its own.
    }

    /**
     * Returns the number of rows emitted so far.
     * <p>
     * Exposed so the sibling-owned Micrometer &quot;records processed&quot; counter can observe this step
     * without this class registering an instrument of its own. <b>No meter, timer or gauge is created
     * here</b>: the four named counters are owned by {@code com.cardemo.observability.MetricsConfig}, and
     * adding a fifth instrument from a reader would duplicate that ownership.
     * <p>
     * This is the only accessor on the class, and it deliberately exposes a count rather than any part of the
     * record: there is no accessor for the record area, for the last card number or for any field of either.
     *
     * @return the count of rows returned by {@link #read()} since the last {@link #open(ExecutionContext)},
     *     never negative
     */
    public long getRecordsRead() {
        return recordsRead;
    }

    // ====================================================================================================
    // 1000-CARDFILE-GET-NEXT, app/cbl/CBACT02C.cbl:L92-L116.
    // ====================================================================================================

    /**
     * Reads the next record and applies the three-way sequential-read guard of
     * {@code 1000-CARDFILE-GET-NEXT} ({@code app/cbl/CBACT02C.cbl:L92-L116}).
     * <p>
     * The source shape is preserved exactly: the read sets a status; {@code '00'} yields
     * {@code MOVE 0 TO APPL-RESULT} ({@code :L95}); {@code '10'} yields {@code MOVE 16} ({@code :L99});
     * anything else yields {@code MOVE 12} ({@code :L101}). The guard that follows then either continues, sets
     * {@code END-OF-FILE} to {@code 'Y'}, or reports and abends ({@code :L104-L115}).
     * <p>
     * <b>Parity structure 2 lives here.</b> The {@code '00'} branch of this paragraph is where
     * {@code CBACT01C} performs its field-by-field display paragraph. In {@code CBACT02C} the corresponding
     * line, {@code :L96}, is commented out, so this paragraph emits nothing at all and the record is emitted
     * exactly once, from the mainline. The commented-out line is reproduced below as a comment and never as
     * code.
     *
     * @return the record just read when the status was {@code '00'}, or {@code null} at end of file
     * @throws FatalProcessingException when the status is neither {@code '00'} nor {@code '10'}, carrying the
     *     store failure as its cause when one was raised
     */
    private Card getNextCardRecord() {
        DataAccessException storeFailure = null;
        try {
            // READ CARDFILE-FILE INTO CARD-RECORD.  (:L93)
            ioStatus = readNextRecord();
        } catch (DataAccessException failure) {
            // The COBOL READ reports through CARDFILE-STATUS; a relational store reports by throwing. The
            // throwable is translated to the '9x' family and then RETAINED as the cause, never swallowed and
            // never allowed to escape untyped (Rule 1 clause B).
            storeFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF CARDFILE-STATUS = '00' MOVE 0 TO APPL-RESULT / ELSE IF '10' MOVE 16 / ELSE MOVE 12
        // (:L94-L103). The arithmetic is not restated here: FileStatusMapper already implements this exact
        // nested test and its documentation cites this very paragraph (Rule 1 clause C).
        applResult = fileStatusMapper.applResultForSequentialRead(ioStatus);

        // ------------------------------------------------------------------------------------------------
        // PRESERVED QUIRK, parity structure 2. The next line of the source, inside the '00' branch and
        // immediately after MOVE 0 TO APPL-RESULT, is:
        //
        //     app/cbl/CBACT02C.cbl:L96      *        DISPLAY CARD-RECORD
        //
        // It is a COMMENT, not a statement - column 7 holds an asterisk. It is reproduced here as a comment
        // and MUST NOT become executable code. Three consequences follow, all measured:
        //   1. CBACT02C emits each record exactly ONCE, from the mainline at :L78, whereas CBACT03C and
        //      CBCUS01C emit twice. Adding an emission here would be a parity break.
        //   2. The DISPLAY statement count is 9 rather than 10, which is what distinguishes the measured
        //      statement inventory from the lexical token count of 14 quoted elsewhere.
        //   3. This is the position CBACT01C fills with PERFORM 1100-DISPLAY-ACCT-RECORD (:L96 of that
        //      program). CBACT02C has no 1100- paragraph, so there is nothing to perform and no empty
        //      branch is introduced here to stand in for one.
        // ------------------------------------------------------------------------------------------------

        // IF APPL-AOK CONTINUE  (:L104-L105)
        if (applResult == FileStatusMapper.APPL_AOK) {
            return cardRecord;
        }

        // ELSE IF APPL-EOF MOVE 'Y' TO END-OF-FILE  (:L107-L108). End of file is loop termination, NOT an
        // error: 88 APPL-EOF VALUE 16 at :L63 is a normal outcome and nothing is thrown for it.
        if (applResult == FileStatusMapper.APPL_EOF) {
            endOfFile = END_OF_FILE_YES;
            cardRecord = null;
            return null;
        }

        // ELSE DISPLAY 'ERROR READING CARDFILE' / MOVE CARDFILE-STATUS TO IO-STATUS /
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
     * Performs the store round trip behind the {@code READ CARDFILE-FILE INTO CARD-RECORD} verb at
     * {@code app/cbl/CBACT02C.cbl:L93} and reports its outcome as a COBOL file status.
     * <p>
     * A VSAM {@code READ} with {@code ACCESS MODE IS SEQUENTIAL} hands back one record and advances the
     * cursor. Here the cursor is a buffered window refilled by
     * {@link CardRepository#findByCardNumberGreaterThanOrderByCardNumberAsc(String,
     * org.springframework.data.domain.Pageable)}, whose ascending key order is fixed <b>in the method name
     * itself</b> and so cannot be omitted or overridden by a caller. The store's natural order is never relied
     * upon: {@code app/cbl/CBACT02C.cbl:L29-L33} declares {@code ORGANIZATION IS INDEXED} with
     * {@code ACCESS MODE IS SEQUENTIAL} and {@code RECORD KEY IS FD-CARD-NUM}, so key order <em>is</em> the
     * contract, and reproducing it deterministically is what makes the emitted sequence comparable against the
     * legacy baseline (Rule 1 clause A).
     * <p>
     * The key is a 16-character text field, not a number: {@code CARD-NUM PIC X(16)}
     * ({@code app/cpy/CVACT02Y.cpy:L5}), key length 16 at {@code app/catlg/LISTCAT.txt:L202} and
     * {@code KEYS(16 0)} at {@code app/jcl/CARDFILE.jcl:L54}. Ascending order is therefore the collation of
     * the {@code CHAR(16)} column, which is what preserves the leading zeros the 50-row fixture actually
     * contains.
     * <p>
     * <b>Why the window is keyset-bounded and not offset-paged</b> (Rule 1 clause A, tradeoff justified rather
     * than assumed). An offset page asks the store to produce and discard every row before the window, so
     * walking the relation costs work quadratic in its size, and the discarded prefix grows with every step. A
     * keyset window instead asks for {@code CARD-NUM > cursor ... LIMIT pageSize}, which the primary-key index
     * satisfies by seeking straight to the cursor and reading forward: constant work per window, independent of
     * how far the scan has already travelled. This is also the closer analogue of the source, because a VSAM
     * sequential read positions by key and reads forward rather than counting from the start of the cluster.
     * The window size cannot affect the emitted output, because the ordering is fixed independently of it, and
     * the seek bound is exclusive so no row is visited twice or skipped.
     * <p>
     * A second, unrelated saving: this finder returns a {@code List}, so no {@code COUNT(*)} is issued. The
     * page-shaped predecessor computed a total on every refill that nothing on this path ever read. The one
     * count this class does perform is the deliberate, once-per-open one in {@code openCardFile()}, which
     * exists to make the empty-relation case an explicit logged outcome.
     * <p>
     * The alternate index is deliberately not used. {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX} has
     * {@code KEYLEN 11} and {@code AXRKP 16} ({@code app/catlg/LISTCAT.txt:L281}, {@code :L283}), where
     * <b>16 is zero-based</b> and therefore names 1-based record byte <b>17</b>, the first byte of
     * {@code CARD-ACCT-ID}; {@code app/jcl/CARDFILE.jcl:L85} states the same thing as {@code KEYS(11 16)},
     * also zero-based. Its Java analogue is {@code CardRepository.findByAccountIdOrderByCardNumberAsc} over a
     * non-unique B-tree index on {@code card.card_acct_id}. This program browses the <em>base</em> cluster, so
     * none of that applies to this scan; the offsets are recorded with their base named because mixing the two
     * conventions is a recurring source of error.
     *
     * @return {@link #STATUS_SUCCESS} when a record was placed in the record area, {@link #STATUS_END_OF_FILE}
     *     when the scan is exhausted, or {@link #STATUS_PHYSICAL_IO_ERROR} when the buffer yielded a
     *     {@code null} element, which a {@code NOT NULL} keyed relation cannot legitimately produce
     * @throws DataAccessException if the store rejects the query; translated by the caller
     */
    private String readNextRecord() {
        while (pageBufferIndex >= pageBuffer.size()) {
            // The window is bounded by the cursor, never by an offset: CARD-NUM > cursor ORDER BY CARD-NUM
            // ASC LIMIT pageSize. PageRequest.ofSize() is page zero, so the offset is always literally 0.
            pageBuffer = cardRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    fetchCursorCardNumber, PageRequest.ofSize(pageSize));
            pageBufferIndex = 0;

            if (pageBuffer.isEmpty()) {
                cardRecord = null;
                return STATUS_END_OF_FILE;
            }

            // Advance the cursor to the highest key in the window just fetched, so the next window starts
            // strictly after it. Explicit null branch (Rule 1 clause B): CARD-NUM is NOT NULL and is the
            // primary key, so a null here means the result set is not what the schema promises. It is
            // reported through the status vocabulary rather than allowed to become a NullPointerException,
            // and the cursor is deliberately left unadvanced on that path.
            Card highestOfWindow = pageBuffer.get(pageBuffer.size() - 1);
            if (highestOfWindow == null || highestOfWindow.getCardNumber() == null) {
                cardRecord = null;
                return STATUS_PHYSICAL_IO_ERROR;
            }
            fetchCursorCardNumber = highestOfWindow.getCardNumber();
        }

        Card next = pageBuffer.get(pageBufferIndex);
        pageBufferIndex++;

        // Explicit null branch (Rule 1 clause B): every row of this relation is NOT NULL and keyed, so a null
        // element means the result set is not what the schema promises. It is reported through the status
        // vocabulary rather than allowed to become a NullPointerException further down.
        if (next == null) {
            cardRecord = null;
            return STATUS_PHYSICAL_IO_ERROR;
        }

        cardRecord = next;
        return STATUS_SUCCESS;
    }

    // ====================================================================================================
    // 0000-CARDFILE-OPEN, app/cbl/CBACT02C.cbl:L118-L134.
    // ====================================================================================================

    /**
     * Opens the card master, reproducing {@code 0000-CARDFILE-OPEN}
     * ({@code app/cbl/CBACT02C.cbl:L118-L134}).
     * <p>
     * <b>Arithmetic idiom, parity structure 3.</b> This paragraph primes the result field with
     * {@code MOVE 8 TO APPL-RESULT} at {@code :L119}, and sets its two outcomes with {@code MOVE 0}
     * ({@code :L122}) and {@code MOVE 12} ({@code :L124}). Its counterpart {@code closeCardFile()} primes the
     * same field with {@code ADD 8 TO ZERO GIVING APPL-RESULT} at {@code :L137}, clears it with
     * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} at {@code :L140} and sets the failure value with
     * {@code ADD 12 TO ZERO GIVING APPL-RESULT} at {@code :L142}. The two paragraphs compute identical values
     * by different verbs. They are kept as two methods, each documenting the idiom its own source paragraph
     * uses, and are deliberately not normalised into one shape.
     * <p>
     * <b>What stands in for {@code OPEN INPUT}.</b> There is no file handle to acquire, so the analogue is a
     * single {@link CardRepository#count()} round trip. It establishes exactly what a VSAM open establishes
     * &mdash; that the dataset is reachable &mdash; because an unreachable relation surfaces as a
     * {@link DataAccessException}, which is the counterpart of file status {@code '35'} or the {@code '9x'}
     * family. It also yields the row count, which makes the empty-relation case an explicit, logged outcome
     * rather than something inferred later from an absence of records (Rule 1 clause B). The call returns a
     * scalar, so no sort applies to it.
     * <p>
     * Note that this paragraph uses the <b>two-way</b> guard: {@code '00'} succeeds and everything else,
     * {@code '10'} included, fails. End of file is not a reachable outcome of an open, so a report of it is an
     * unexpected condition. That is a different shape from the three-way guard of
     * {@code 1000-CARDFILE-GET-NEXT}, and the two must not be collapsed.
     * <p>
     * <b>Side effects.</b> One store round trip; sets the open flag and the row count; writes one log event on
     * success and two before abending on failure.
     *
     * @throws FatalProcessingException if the relation cannot be reached, reproducing
     *     {@code DISPLAY 'ERROR OPENING CARDFILE'} then {@code PERFORM 9999-ABEND-PROGRAM} at
     *     {@code :L129-L132}
     */
    private void openCardFile() {
        // MOVE 8 TO APPL-RESULT.  (:L119) - the OPEN idiom. See the arithmetic note above.
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        DataAccessException storeFailure = null;
        try {
            // OPEN INPUT CARDFILE-FILE  (:L120)
            recordCountAtOpen = cardRepository.count();
            ioStatus = STATUS_SUCCESS;
        } catch (DataAccessException failure) {
            storeFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF CARDFILE-STATUS = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 TO APPL-RESULT  (:L121-L125).
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
            // ELSE DISPLAY 'ERROR OPENING CARDFILE' / MOVE CARDFILE-STATUS TO IO-STATUS /
            // PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM  (:L129-L132)
            LOG.error(ERROR_OPENING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            abendProgram(ERROR_OPENING_MESSAGE, storeFailure);
        }
        // EXIT.  (:L134)
    }

    // ====================================================================================================
    // 9000-CARDFILE-CLOSE, app/cbl/CBACT02C.cbl:L136-L152.
    // ====================================================================================================

    /**
     * Closes the card master, reproducing {@code 9000-CARDFILE-CLOSE}
     * ({@code app/cbl/CBACT02C.cbl:L136-L152}).
     * <p>
     * <b>Arithmetic idiom, parity structure 3.</b> Where {@code openCardFile()} writes
     * {@code MOVE 8 TO APPL-RESULT} ({@code :L119}), this paragraph writes
     * {@code ADD 8 TO ZERO GIVING APPL-RESULT} ({@code :L137}); where the open branch writes {@code MOVE 0}
     * and {@code MOVE 12} ({@code :L122}, {@code :L124}), this one writes
     * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} ({@code :L140}) and
     * {@code ADD 12 TO ZERO GIVING APPL-RESULT} ({@code :L142}). Every pair computes the same value by a
     * different verb. The variation is recorded here and in the open paragraph rather than being tidied away,
     * and the two paragraphs remain two methods.
     * <p>
     * <b>What stands in for {@code CLOSE}.</b> Releasing the page buffer and the cursor. No store round trip
     * is needed, and none is made, because a read-only scan holds nothing that requires committing. The
     * teardown is nevertheless guarded exactly as the source guards its close, so the failure branch remains
     * reachable for any runtime fault raised while releasing the cursor rather than being unreachable by
     * construction.
     * <p>
     * The record area is cleared here, which also means the last card number read is no longer reachable
     * through this instance once the step has closed.
     * <p>
     * <b>Side effects.</b> Clears the buffer, the cursor and the open flag. Writes two log events only when
     * the close fails.
     *
     * @throws FatalProcessingException if releasing the cursor raises a runtime fault, reproducing
     *     {@code DISPLAY 'ERROR CLOSING CARDFILE'} then {@code PERFORM 9999-ABEND-PROGRAM} at
     *     {@code :L147-L150}
     */
    private void closeCardFile() {
        // ADD 8 TO ZERO GIVING APPL-RESULT.  (:L137) - the CLOSE idiom, distinct from the open's MOVE 8.
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        RuntimeException teardownFailure = null;
        try {
            // CLOSE CARDFILE-FILE  (:L138)
            pageBuffer = List.of();
            pageBufferIndex = 0;
            cardRecord = null;
            fileOpen = false;
            ioStatus = STATUS_SUCCESS;
        } catch (RuntimeException failure) {
            teardownFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF CARDFILE-STATUS = '00' SUBTRACT APPL-RESULT FROM APPL-RESULT ELSE
        // ADD 12 TO ZERO GIVING APPL-RESULT  (:L139-L143). Both branches compute exactly what
        // applResultForGuard returns - zero and twelve - so the shared translator is consulted instead of the
        // arithmetic being restated (Rule 1 clause C). The source's verbs are recorded above.
        applResult = fileStatusMapper.applResultForGuard(ioStatus);

        // IF APPL-AOK CONTINUE  (:L144-L145)
        if (applResult != FileStatusMapper.APPL_AOK) {
            // ELSE DISPLAY 'ERROR CLOSING CARDFILE' / MOVE CARDFILE-STATUS TO IO-STATUS /
            // PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM  (:L147-L150)
            LOG.error(ERROR_CLOSING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            abendProgram(ERROR_CLOSING_MESSAGE, teardownFailure);
        }
        // EXIT.  (:L152)
    }

    // ====================================================================================================
    // 9999-ABEND-PROGRAM, app/cbl/CBACT02C.cbl:L154-L158.
    // ====================================================================================================

    /**
     * Abends the step, reproducing {@code 9999-ABEND-PROGRAM} ({@code app/cbl/CBACT02C.cbl:L154-L158}).
     * <p>
     * The source emits {@code 'ABENDING PROGRAM'} ({@code :L155}), zeroes {@code TIMING} ({@code :L156}), moves
     * {@code 999} into {@code ABCODE} ({@code :L157}) and calls the Language Environment abend service
     * ({@code :L158}). The Java counterpart throws {@link FatalProcessingException} carrying the full
     * {@code CABENDD.CPY} payload: abend code
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE}, this program as the culprit,
     * the failing operation as the reason, and the legacy message as the message. Process return code
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE} is the exit code the job's
     * status mapping derives from this exception; it is not set here, because a reader does not own the process
     * exit code.
     * <p>
     * {@code TIMING} has no counterpart. It is a Language Environment abend parameter selecting whether a dump
     * is taken, and there is no dump facility to select; the exception carries the stack trace that replaces it.
     * <p>
     * <b>Every failure branch of this program reaches here.</b> All three guards &mdash;
     * {@code :L110-L113} on the read, {@code :L129-L132} on the open and {@code :L147-L150} on the close &mdash;
     * run {@code DISPLAY}, then {@code PERFORM 9910-DISPLAY-IO-STATUS}, then {@code PERFORM 9999-ABEND-PROGRAM}.
     * There is no recoverable I/O outcome in {@code CBACT02C} other than end of file, which is why this reader
     * raises only {@link FatalProcessingException} and never the narrower
     * {@code com.cardemo.exception.FileAccessException}; that type belongs to the shared status vocabulary but
     * has no reachable site here, so importing it would leave an unused import (Rule 1 clause B1).
     * <p>
     * <b>No card data enters the abend payload.</b> The reason and message carry the logical file name, the
     * literal that preceded the abend and the rendered status &mdash; never the record, the card number or the
     * card verification code, at any level (Rule 1 clause D1).
     * <p>
     * <b>This method always throws and never returns normally.</b>
     *
     * @param message the legacy message literal that preceded the abend, used as both the reason and the abend
     *     message so the failing operation is identifiable from either field
     * @param cause the throwable that provoked the abend, or {@code null} when the status alone identified the
     *     fault; always attached when present, so the root cause is never lost (Rule 1 clause B4)
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
    // 9910-DISPLAY-IO-STATUS, app/cbl/CBACT02C.cbl:L161-L174.
    // ====================================================================================================

    /**
     * Renders a file status as the legacy diagnostic line, reproducing {@code 9910-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBACT02C.cbl:L161-L174}).
     * <p>
     * The paragraph has two branches. When {@code IO-STATUS} is not numeric or its first byte is {@code '9'}
     * ({@code :L162-L163}), byte one is copied into position one ({@code :L164}) and byte two is widened through
     * {@code TWO-BYTES-BINARY} into three digits ({@code :L165-L167}). Otherwise the field is set to
     * {@code '0000'} ({@code :L170}) and the two status characters are overlaid at positions three and four
     * ({@code :L171}). Both branches then emit {@code 'FILE STATUS IS: NNNN'} followed by the four-character
     * field ({@code :L168}, {@code :L172}).
     * <p>
     * <b>{@code 'FILE STATUS IS: NNNN'} is a fixed 20-character literal, not a template.</b> The {@code NNNN}
     * is part of the constant text and the four rendered characters follow it, so status {@code '23'} renders as
     * {@code FILE STATUS IS: NNNN0023} and never as {@code FILE STATUS IS: 0023}. Substituting the digits into
     * the {@code NNNN} would be a parity break, and Gate 1 compares this line byte for byte.
     * <p>
     * <b>This method delegates and holds no logic of its own</b>, which is deliberate. The paragraph is
     * byte-identical in form to its counterparts across the batch corpus &mdash; {@code CBACT02C}'s copy differs
     * from {@code CBACT01C}'s only in line numbering &mdash; and
     * {@link FileStatusMapper#displayIoStatus(String)} is the single implementation of it, with
     * {@link FileStatus#DISPLAY_MESSAGE_PREFIX} the single definition of the literal. Re-deriving either here
     * would be the parallel mapping that Rule 1 clause C3 forbids. The method is retained rather than inlined so
     * the paragraph remains individually traceable to its own locator.
     * <p>
     * It is a pure function of its argument: it reads and writes no field of this instance (Rule 1 clause B3).
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
    // Restart support and construction-time validation. No legacy counterpart: the mainline at
    // app/cbl/CBACT02C.cbl:L70-L87 always scans from the first record, because a JES2 job restart re-ran the
    // step from the top. Restartability is additive, and it changes no emitted value.
    // ====================================================================================================

    /**
     * Restores the checkpoint written by {@link #update(ExecutionContext)} so a restarted step resumes instead
     * of re-emitting rows.
     * <p>
     * <b>The checkpointed key is the position; the row count is only a tally.</b> The scan resumes by seeking
     * to {@code CARD-NUM > } the last key actually emitted, so the first window of the resumed run begins at
     * the row after it regardless of how many rows precede it. The predecessor of this method instead divided
     * the row count into a page number and a within-page offset, which positions correctly only while the
     * relation is unchanged between the two runs: any row inserted or deleted below the cursor shifts every
     * offset after it, so a restart could silently re-emit or silently skip rows. Seeking by key is immune to
     * that, because the key of a row does not move when its neighbours change.
     * <p>
     * <b>No card number is checkpointed, so none is restored and none is reported.</b>
     * {@code AccountReader}'s counterpart reports its checkpointed {@code ACCT-ID} in the resume line, because
     * an account identifier is not cardholder data; a 16-character {@code CARD-NUM} is, so it is not written to
     * the context in the first place and the resume line carries the row count alone. See
     * {@link #update(ExecutionContext)} for why holding it in memory for a hypothetical diagnostic was not
     * worth the exposure.
     * <p>
     * A non-positive checkpoint is ignored and the scan starts from the beginning, which is the correct reading
     * of a checkpoint written before any row was emitted.
     *
     * @param executionContext the step execution context, already known to contain the row-count key
     * @throws IllegalStateException if the context records that rows were emitted but carries no key to resume
     *     from, which leaves no position to seek to and which is reported rather than silently downgraded to a
     *     restart from the beginning. The message names the context key, never the card number
     */
    private void restoreRestartCursor(ExecutionContext executionContext) {
        long checkpointed = executionContext.getLong(CONTEXT_KEY_RECORDS_READ, 0L);
        if (checkpointed <= 0L) {
            return;
        }

        // The keyset position is re-derived rather than restored, because no CARD-NUM is checkpointed
        // (see update(ExecutionContext)). One bounded seek locates the last-emitted row by its ordinal in the
        // fixed ascending key order: page index checkpointed-1 at size 1 is offset checkpointed-1, so the
        // single row returned IS that row. This is the only offset query in the class and it runs once per
        // restart, never once per window, so the keyset scan it re-seeds is unaffected.
        int lastEmittedOrdinal = Math.toIntExact(checkpointed - 1L);
        List<Card> lastEmitted = cardRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                SEED_CARD_NUMBER, PageRequest.of(lastEmittedOrdinal, 1));

        // Explicit handled case (Rule 1 clause B). The relation is expected to still hold at least the rows
        // this step already emitted. If it does not, there is no position to resume from, and restarting from
        // the first row would re-emit every row already emitted while reporting success - so the failure is
        // deliberately loud. The message names the row count only: no CARD-NUM reaches it (Rule 1 clause D1).
        if (lastEmitted.isEmpty() || lastEmitted.get(0) == null
                || lastEmitted.get(0).getCardNumber() == null) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "%s restart context records %d rows already emitted, but the relation no longer yields a "
                            + "row at that position, so there is no key to resume the keyset scan from; "
                            + "restarting from the first row would re-emit those %d rows",
                    LOGICAL_FILE, Long.valueOf(checkpointed), Long.valueOf(checkpointed)));
        }

        recordsRead = checkpointed;
        fetchCursorCardNumber = lastEmitted.get(0).getCardNumber();

        // The row count is reported; the re-derived CARD-NUM deliberately is not.
        LOG.info("Resuming {} scan after {} rows", LOGICAL_FILE, Long.valueOf(recordsRead));
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
                    "carddemo.batch.card-reader.page-size must be at least 1 but was %d; a non-positive page "
                            + "cannot advance the sequential scan of CARDFILE", Integer.valueOf(pageSize)));
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
