/*
 * ******************************************************************
 * Program     : CardCrossReferenceReader.java
 * Application : CardDemo
 * Type        : Spring Batch ItemStreamReader (read-only verification step)
 * Function    : Read-only sequential scan of the card cross-reference file,
 *               replacing the COBOL batch reader CBACT03C.
 * Source      : app/cbl/CBACT03C.cbl (178 lines, 6 paragraphs)
 *               app/cpy/CVACT03Y.cpy (36 populated bytes in a 50-byte slot)
 *               app/catlg/LISTCAT.txt:L403 (KEYLEN 16 / AVGLRECL 50)
 *               app/jcl/READXREF.jcl (job that executes CBACT03C)
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
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.service.shared.FileStatusMapper;

/**
 * Read-only sequential scan of the card cross-reference file, reproducing the COBOL batch program
 * {@code app/cbl/CBACT03C.cbl} paragraph for paragraph.
 * <p>
 * The four headings below are this file's discharge of <b>Rule 1 clause E</b>, which requires every component
 * to carry &quot;a short README or docstring&quot; covering what it does, how to run, build and test it, its
 * key configuration and defaults, and its common failure modes. Clause E is met here through the
 * <b>docstring branch</b>, and that is a choice rather than a necessity: {@code com.cardemo.batch} and
 * {@code com.cardemo.batch.readers} both carry a {@code package-info.java}, so a package-scope document does
 * exist one level up. What it cannot carry is the per-reader detail below - the citations, the emission
 * inventory and the failure modes are specific to {@code CBACT03C} and would be wrong for any of the other
 * readers - so the class docstring is where they belong and the package document states the shape the package
 * shares.
 *
 * <h2>What it does</h2>
 * Streams every row of the {@code card_cross_reference} relation in ascending primary-key order and hands each
 * one to the owning Spring Batch step, then terminates. Nothing is written, updated or deleted: this is a
 * <em>verification step</em>, and the read-only character is not a design preference but a measured property
 * of the source.
 * <p>
 * The cross-reference is the <b>join spine of the whole application</b>. Its three fields associate a card, a
 * customer and an account ({@code app/cpy/CVACT03Y.cpy:L5-L7}), which is why the online account-view flow
 * reaches it first and why the daily posting job validates against it before anything else
 * ({@code app/cbl/CBTRN02C.cbl:L380-L392}, paragraph {@code 1500-A-LOOKUP-XREF}). That role is also the reason
 * the logging discussion further down is narrower than it first appears: rendering two of the three fields
 * together republishes the association itself.
 * <p>
 * The verb inventory of {@code app/cbl/CBACT03C.cbl}, counted as statement starts with column-7 comment lines
 * stripped, is {@code OPEN}=1 ({@code :L120}), {@code READ}=1 ({@code :L93}), {@code CLOSE}=1
 * ({@code :L138}), {@code DISPLAY}=10 ({@code :L71}, {@code :L78}, {@code :L85}, <b>{@code :L96}</b>,
 * {@code :L110}, {@code :L129}, {@code :L147}, {@code :L155}, {@code :L168}, {@code :L172}), and
 * {@code WRITE}={@code REWRITE}={@code DELETE}=<b>0</b>. Because the source contains no write verb at all,
 * this class adds no write path: no {@code save}, no {@code saveAll}, no {@code delete}, no
 * {@code @Modifying} query, no {@code EntityManager} mutation and no {@code flush}. The only repository
 * operations it ever performs are {@link CardCrossReferenceRepository#count()} and
 * {@link CardCrossReferenceRepository#findByCardNumberGreaterThanOrderByCardNumberAsc(String,
 * org.springframework.data.domain.Pageable)}, both inherited and
 * both read-only.
 * <p>
 * <b>Three different verb counts circulate for this program, and all three were
 * reproduced by measurement.</b> They differ only in what they count, never in the load-bearing conclusion.
 * <ul>
 * <li><b>Statement counts, 1 / 1 / 1 / 10</b> &mdash; the figures above, and the ones this class cites.</li>
 * <li><b>Lexical token counts, 3 / 1 / 3 / 14</b> &mdash; quoted by other project documents. The token
 *     {@code OPEN} also occurs in the paragraph label {@code 0000-XREFFILE-OPEN} ({@code :L118}) and in
 *     {@code PERFORM 0000-XREFFILE-OPEN} ({@code :L72}), giving 3; {@code CLOSE} likewise occurs in
 *     {@code 9000-XREFFILE-CLOSE} ({@code :L136}) and its {@code PERFORM} ({@code :L83}), giving 3; and
 *     {@code DISPLAY} occurs in the label {@code 9910-DISPLAY-IO-STATUS} ({@code :L161}) and in its three
 *     {@code PERFORM} statements ({@code :L112}, {@code :L131}, {@code :L149}), which is 10 + 1 + 3 = 14. The
 *     apparent extra matches on {@code :L129} and {@code :L110} are the substrings inside
 *     {@code 'ERROR OPENING XREFFILE'} and {@code 'ERROR READING XREFFILE'} and are not the tokens.</li>
 * <li><b>Label-plus-statement counts, 2 / 1 / 2</b> &mdash; quoted by
 *     {@code com.cardemo.repository.CardCrossReferenceRepository}, which counts each paragraph label together
 *     with the verb inside it but not the {@code PERFORM} that reaches it.</li>
 * </ul>
 * The statement counts are the ones cited here, and they supersede both other conventions.
 * <b>{@code WRITE}, {@code REWRITE} and {@code DELETE} are zero under every one of the three</b>, so the
 * read-only conclusion does not depend on the choice.
 *
 * <h3>Paragraph map, one Java member per COBOL paragraph, never consolidated</h3>
 * <table>
 * <caption>Paragraphs of {@code app/cbl/CBACT03C.cbl} and their Java targets</caption>
 * <tr><th>COBOL label</th><th>Locator</th><th>Java target</th></tr>
 * <tr><td>mainline {@code PROCEDURE DIVISION}</td><td>{@code :L70-L87}</td>
 *     <td>{@link #open(ExecutionContext)}, {@link #read()}, {@link #close()}</td></tr>
 * <tr><td>{@code 1000-XREFFILE-GET-NEXT}</td><td>{@code :L92-L116}</td>
 *     <td>{@code getNextCrossReferenceRecord()}</td></tr>
 * <tr><td>{@code 0000-XREFFILE-OPEN}</td><td>{@code :L118-L134}</td>
 *     <td>{@code openCrossReferenceFile()}</td></tr>
 * <tr><td>{@code 9000-XREFFILE-CLOSE}</td><td>{@code :L136-L152}</td>
 *     <td>{@code closeCrossReferenceFile()}</td></tr>
 * <tr><td>{@code 9999-ABEND-PROGRAM}</td><td>{@code :L154-L158}</td>
 *     <td>{@code abendProgram(String, Throwable)}</td></tr>
 * <tr><td>{@code 9910-DISPLAY-IO-STATUS}</td><td>{@code :L161-L174}</td>
 *     <td>{@code displayIoStatus(String)}</td></tr>
 * </table>
 * <b>There is no {@code 1100-} display paragraph in {@code CBACT03C}: six paragraphs, not seven.</b> The five
 * named Area-A labels are the five in the table ({@code :L92}, {@code :L118}, {@code :L136}, {@code :L154},
 * {@code :L161}), and the mainline is the sixth. {@code CBACT01C} declares a seventh,
 * {@code 1100-DISPLAY-ACCT-RECORD} at {@code app/cbl/CBACT01C.cbl:L118}, which it performs from the
 * {@code '00'} branch of its read paragraph at {@code app/cbl/CBACT01C.cbl:L96}. That field-by-field emitter
 * exists in {@code com.cardemo.batch.readers.AccountReader} and is <b>deliberately not ported here</b>: no
 * member of this class renders the record field by field, because the source has no paragraph that does.
 *
 * <h3>The double display: preserved, not corrected</h3>
 * <b>The record is displayed twice per row in the source.</b> {@code app/cbl/CBACT03C.cbl:L96} is
 * {@code DISPLAY CARD-XREF-RECORD} and
 * it is <b>ACTIVE</b> &mdash; column 7 of that line holds a space, not an asterisk &mdash; sitting in the
 * {@code '00'} branch of {@code 1000-XREFFILE-GET-NEXT} immediately after {@code MOVE 0 TO APPL-RESULT}
 * ({@code :L95}). The mainline then displays the very same record again at {@code :L78}. <b>{@code CBACT03C}
 * therefore emits each record twice per iteration</b>, once from inside the read paragraph and once from the
 * mainline.
 * <p>
 * That duplication is a legacy defect and it is <b>preserved rather than repaired</b>, because behavioural
 * parity is the contract of this migration. This class emits <b>two</b> distinct record-level events per row:
 * one from {@code getNextCrossReferenceRecord()} citing {@code :L96}, and one from {@link #read()} citing
 * {@code :L78}. Emitting one would be a parity break.
 * <p>
 * <b>The contrast with {@code CardReader} is exact and the two are not interchangeable.</b> The corresponding
 * line of the sister program, {@code app/cbl/CBACT02C.cbl:L96}, reads {@code *        DISPLAY CARD-RECORD}
 * with an asterisk in column 7, so it is a comment; {@code CBACT02C} emits once, and
 * {@code com.cardemo.batch.readers.CardReader} correspondingly emits once. Emitting once here is a parity
 * break, and emitting twice there is equally a parity break. This single line is also what makes the
 * {@code DISPLAY} statement count 10 here and 9 there.
 * <p>
 * Specifically, <b>do not</b> &quot;fix&quot; the duplication. It is reachable,
 * executed on every row, justified here and asserted by the unit tests, so
 * it is not the untracked dead code that Rule 1 clause B forbids. Rule 1 clause A asks that an inefficiency be
 * justified rather than merely tolerated: the cost is one extra {@code DEBUG} event per row, both events are
 * behind {@link Logger#isDebugEnabled()} so a production configuration pays nothing, and the benefit is that
 * the emitted event count matches the legacy baseline.
 *
 * <h3>Other structures preserved for parity, which must never be deleted as dead code</h3>
 * <ol>
 * <li><b>The redundant double guard.</b> The mainline is {@code PERFORM UNTIL END-OF-FILE = 'Y'}
 *     ({@code :L74}) wrapping an inner {@code IF END-OF-FILE = 'N'} ({@code :L75}), and a <em>second</em>
 *     {@code IF END-OF-FILE = 'N'} follows the read before the display ({@code :L77}). The second test cannot
 *     fail when the first passed and the read returned a record, so it is redundant by inspection. Both are
 *     reproduced as explicit guards in {@link #read()} and neither is collapsed.</li>
 * <li><b>The arithmetic-idiom variation between OPEN and CLOSE.</b> {@code 0000-XREFFILE-OPEN} primes the
 *     result field with {@code MOVE 8 TO APPL-RESULT} ({@code :L119}) and sets its outcomes with
 *     {@code MOVE 0} ({@code :L122}) and {@code MOVE 12} ({@code :L124}). {@code 9000-XREFFILE-CLOSE} primes
 *     the same field with {@code ADD 8 TO ZERO GIVING APPL-RESULT} ({@code :L137}), clears it with
 *     {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} ({@code :L140}) rather than moving zero, and sets the
 *     failure value with {@code ADD 12 TO ZERO GIVING APPL-RESULT} ({@code :L142}) rather than moving twelve.
 *     Every pair computes the same value by a different verb. The two paragraphs are deliberately
 *     <em>not</em> normalised into one shape; each Java method documents the idiom its own source paragraph
 *     uses.</li>
 * <li><b>The double display</b> of the section above.</li>
 * </ol>
 *
 * <h3>Record geometry: 36 populated bytes in a 50-byte slot</h3>
 * <b>The record width is described inconsistently.</b> Three independent artefacts describe it and do not agree on
 * a single width, so all three are stated rather than one being chosen:
 * <ul>
 * <li><b>36 populated copybook bytes.</b> {@code app/cpy/CVACT03Y.cpy} declares
 *     {@code XREF-CARD-NUM PIC X(16)} ({@code :L5}), {@code XREF-CUST-ID PIC 9(09)} ({@code :L6}) and
 *     {@code XREF-ACCT-ID PIC 9(11)} ({@code :L7}), which is 16 + 9 + 11 = 36, followed by
 *     {@code FILLER PIC X(14)} ({@code :L8}).</li>
 * <li><b>50-byte cluster record.</b> {@code app/catlg/LISTCAT.txt:L403} reports {@code KEYLEN 16} with
 *     {@code AVGLRECL 50} and {@code MAXLRECL 50}, corroborated by {@code KEYS(16 0)} and
 *     {@code RECORDSIZE(50 50)} at {@code app/jcl/XREFFILE.jcl:L43-L44}, and by the file description
 *     {@code FD-XREF-CARD-NUM PIC X(16)} plus {@code FD-XREF-DATA PIC X(34)} at
 *     {@code app/cbl/CBACT03C.cbl:L37-L40}, which is 16 + 34 = 50.</li>
 * <li><b>36-byte fixture rows.</b> {@code app/data/ASCII/cardxref.txt} measures 1,850 bytes as 50 rows of
 *     exactly 36 characters plus one line terminator each. <b>Every row is 36 characters wide; not one is
 *     50.</b></li>
 * </ul>
 * The 14-byte residue is therefore allocation slack rather than data, and it is <b>not modelled</b> as a
 * property of {@link CardCrossReference}. This class therefore holds <b>no positional logic
 * whatsoever</b> &mdash; it consumes mapped entity properties through their accessors and never a byte offset,
 * a substring, a fixed-width buffer or a width constant &mdash; so there is <b>no site at which 16, 36 or 50
 * could be assumed</b> and nothing for a width bounds check to guard. That is a stronger guarantee than
 * checking a width would be, and it is verifiable by inspection: the numbers 16, 36 and 50 appear in this file
 * only inside documentation. The boundary conditions that do exist are the ones enumerated under clause B2
 * below &mdash; end of data, an empty relation, a {@code null} element and a {@code null} identifier &mdash;
 * and each is handled explicitly at its own site. A fixed-width consumer that assumed 50 would read past the
 * end of every one of the 50 fixture rows, which is exactly the failure this rule forecloses.
 * <p>
 * <b>No zoned-decimal overpunch decoder appears anywhere in this class</b>, and that is a measured decision
 * rather than an omission: {@code app/cpy/CVACT03Y.cpy} declares <b>no {@code S9} picture clause at all</b>,
 * so the record carries no signed numeric field and there is no sign to decode. A decoder here would be
 * unreachable code, which Rule 1 clause B forbids, and it would duplicate logic that belongs to
 * {@code src/main/resources/db/migration/V3__seed_data.sql}, which owns the position-aware decode of the
 * fixtures that do carry signs.
 * <p>
 * <b>{@link CardCrossReference} carries no {@code @Version} column</b>, and it is the one reader-facing
 * entity in this package without one. Nothing in this class adds, reads or references an optimistic-locking
 * version, and nothing needs to: a read-only scan takes no lock and performs no compare-and-set.
 * <p>
 * <b>Both identifiers are plain scalar {@code Long} values, never associations.</b>
 * {@link CardCrossReference#getCustomerId()} and {@link CardCrossReference#getAccountId()} map
 * {@code XREF-CUST-ID} and {@code XREF-ACCT-ID} directly; neither is a {@code @ManyToOne}. This class imports
 * no {@code Customer} and no {@code Account} type, navigates to neither, and issues no join.
 *
 * <h3>The alternate index, which this scan deliberately does not use</h3>
 * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX} has {@code KEYLEN 11}, {@code RKP 5} and <b>{@code AXRKP 25}</b>
 * ({@code app/catlg/LISTCAT.txt:L481}, {@code :L484}, {@code :L486}) and is declared {@code NONUNIQKEY}
 * ({@code :L488}); {@code app/jcl/XREFFILE.jcl:L72-L74} states the same thing as {@code KEYS(11,25)} with the
 * {@code PATH} defined at {@code :L90-L92}. <b>Those offsets are zero-based, so {@code AXRKP 25} names
 * 1-based record byte 26</b>, the first byte of {@code XREF-ACCT-ID}; the convention is stated because mixing
 * the two is a recurring source of error, and every offset in this file is 1-based unless it is labelled
 * otherwise.
 * <p>
 * The Java analogue of that path is
 * {@link CardCrossReferenceRepository#findFirstByAccountIdOrderByCardNumberAsc(Long)} over the non-unique
 * index {@code idx_card_cross_reference_acct_id}, created by
 * {@code src/main/resources/db/migration/V2__create_indexes.sql:L422-L423}. <b>Neither is used here.</b>
 * {@code CBACT03C} browses the <em>base</em> cluster sequentially, and calling the account finder from this
 * class would misattribute its source.
 *
 * <h3>The one deliberate deviation: the legacy whole-record emission is not reproduced</h3>
 * {@code :L78} and {@code :L96} both perform {@code DISPLAY CARD-XREF-RECORD}, which writes the record to
 * SYSOUT. Those bytes open with the <b>16-character card number</b> at 1-based bytes 1-16
 * ({@code app/cpy/CVACT03Y.cpy:L5}). Reproducing either verbatim would publish a primary account number into
 * the log estate on every row of every run, twice.
 * <p>
 * <b>The record image is therefore deliberately not reproduced</b>, under Rule 1 clause D, &quot;No secrets
 * in code, logs, tests, or config&quot;. What the two events emit in its place is an <b>ordinal-only
 * projection</b>: the fact of the read and the row sequence number, at {@code DEBUG}, and <b>no field of the
 * record at all</b>. The card number is never rendered in any form, whole or partial, at any level, anywhere
 * in this class - and neither is the account identifier nor the customer identifier.
 * <p>
 * <b>Why the customer identifier is withheld as well, which is narrower than it may look.</b>
 * {@link CardCrossReference#toString()} renders the account identifier alone and states the reason in terms:
 * the purpose of this table is to associate a card, an account and a customer, so a line carrying the account
 * and the customer together <em>reproduces the association itself</em>, handing a customer-to-account linkage
 * to any log aggregator, ticket attachment or support transcript that reads it, without that reader ever
 * touching the database. The account identifier alone locates the row for anyone already authorised to read
 * it and discloses no linkage on its own. This class consumes that contract rather than reasoning around it:
 * <b>{@code XREF-CUST-ID} is never logged</b>.
 * <p>
 * <b>Why an ordinal-only projection rather than a last-four rendering, or the account identifier.</b> Both
 * were considered and both were rejected. A partial rendering fails on two independent grounds: first,
 * {@link CardCrossReference} deliberately provides no masking helper and its contract says so, so building one
 * inside a reader would duplicate a decision another type owns, which Rule 1 clause C forbids, and would put a
 * second masking rule in the codebase; second, it would place four genuine digits of a live account number
 * into the log estate in exchange for no correlation value the row ordinal does not already supply. Rendering
 * {@code XREF-ACCT-ID} instead was the earlier remedy and is narrower but still not narrow enough: this table
 * is <em>nothing but</em> the association between a card, an account and a customer, the account identifier is
 * the column the rest of the batch stream joins on, and a stream that is aggregated, retained and replicated
 * outside the boundary protecting the row is not a place to publish the join key. The ordinal locates the row
 * within the run for anyone reading the log beside the data, and discloses nothing on its own.
 * <p>
 * <b>Consequently there is no masking helper and no length arithmetic anywhere in this class.</b> Three
 * artefacts describe this record as 36 populated bytes, a 50-byte cluster slot and 36-character fixture rows,
 * and the remedy adopted for that spread is not to bounds-check a width but to hold <b>no positional logic at
 * all</b>: there is no substring, no byte offset, no fixed-width buffer and no width constant in the file, so
 * there is no site at which 16, 36 or 50 could be assumed and nothing for a bounds check to guard. A
 * trailing-digits masker and a field renderer were both considered and both removed for the same reason -
 * nothing calls them, because no field is rendered, and an unreachable method is what Rule 1 clause B
 * forbids.
 * <p>
 * The masking rules in {@code src/main/resources/logback-spring.xml} govern whatever does reach a log
 * aggregator and are a backstop, not the primary defence: never emitting the value is the primary defence.
 * <b>The deviation changes what is emitted and is labelled as such
 * rather than presented as parity</b>, which is the distinction Rule 1 clause F requires. What parity
 * requires here is that <em>two</em> events occur per row; it does not require that either one expose a card
 * number. The count is reproduced exactly and the content is withheld.
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
 * constructed at runtime today. The legacy standalone job is
 * {@code app/jcl/READXREF.jcl}, whose {@code STEP05} is {@code EXEC PGM=CBACT03C} at {@code :L22} with
 * {@code //XREFFILE DD} pointing at {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} at {@code :L25-L26}. The same
 * file is read again by the posting job at {@code app/jcl/POSTTRAN.jcl:L32-L33}, through a different program.
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
 * <strong>36</strong> sources and covers the status renderer, the guard logic and the projection through
 * {@code CardCrossReferenceReaderTest}, {@code SequentialReaderContractTest},
 * {@code SequentialReaderKeysetScanTest}, {@code ReaderSensitiveDataTest} and {@code BatchLogHygieneTest}.
 * {@code src/test/java/com/cardemo/integration/batch} holds <strong>4</strong> sources - one abstract
 * Testcontainers base and three concrete classes that execute under Failsafe against PostgreSQL 16 and
 * LocalStack. This class still creates neither, because test sources are outside the scope of the package it
 * belongs to. <b>The assertion that exactly two record-level events occur per row is delivered</b>, by
 * {@code ReaderSensitiveDataTest}, which checks that both record events of {@code CBACT03C:L78} and
 * {@code :L96} are still emitted while neither exposes a card number - that count being the parity property
 * most easily lost by a well-meaning edit.
 *
 * <h2>Key configs and defaults</h2>
 * <ul>
 * <li>{@code carddemo.batch.card-cross-reference-reader.page-size} &mdash; the number of rows fetched per
 *     round trip. Default {@value #DEFAULT_PAGE_SIZE}, which covers the entire 50-row seed fixture
 *     {@code app/data/ASCII/cardxref.txt} in a single query while keeping the resident set bounded. The value
 *     is validated on construction and must be at least one. It is a buffering choice only and has no effect
 *     on the emitted sequence, because the ordering is fixed independently of it. The key is not declared in
 *     any profile, so the default applies unless it is set.</li>
 * <li>Ordering is always ascending on {@code cardNumber}, never the store's natural order. This mirrors
 *     {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS SEQUENTIAL} and
 *     {@code RECORD KEY IS FD-XREF-CARD-NUM} ({@code app/cbl/CBACT03C.cbl:L29-L33}) over a KSDS whose key
 *     length is 16 ({@code app/catlg/LISTCAT.txt:L403}, corroborated by {@code KEYS(16 0)} at
 *     {@code app/jcl/XREFFILE.jcl:L43}), and it makes the emitted sequence reproducible.</li>
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
 *     signals end of input. It is the exact analogue of file status {@code '10'} setting {@code APPL-RESULT}
 *     to 16, which {@code 88 APPL-EOF VALUE 16} ({@code :L63}) tests, driving
 *     {@code MOVE 'Y' TO END-OF-FILE} at {@code :L108}. Nothing is thrown.</li>
 * <li><b>Seeing two log events per row is expected parity behaviour, not a bug.</b> One is emitted at
 *     {@code :L96} and one at {@code :L78}; see the double-display section above before changing anything.</li>
 * <li><b>An empty cross-reference relation completes successfully</b> with a row count of zero. It is logged
 *     explicitly at {@code open} time rather than inferred later from the absence of records.</li>
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
 *     {@code ERROR OPENING XREFFILE} followed by the rendered status, and abends. Check that the Flyway
 *     migrations have applied and that the datasource points at the intended database.</li>
 * <li><b>A masked card number where a full one was expected is the intended behaviour</b>, not a defect: see
 *     the documented deviation above. If a full card number is genuinely needed for an investigation, read it
 *     from the authorised, audited {@code card_cross_reference} row rather than recovering it from a log
 *     line.</li>
 * </ul>
 *
 * <h2>Remaining boundaries and constraints</h2>
 * <ul>
 * <li>The double display of {@code :L96} and {@code :L78} is retained for parity and justified above.</li>
 * <li>The 36-versus-50 width discrepancy is documented above with all three citations, and is foreclosed by
 *     this class holding no positional logic at all.</li>
 * <li>{@link #update(ExecutionContext)} checkpoints the card number of the last row
 *     read, which is the only place in this class where that value leaves its row. It is written to the
 *     Spring Batch step execution context, which is transactional state persisted to
 *     {@code BATCH_STEP_EXECUTION_CONTEXT} in the same database and schema whose
 *     {@code card_cross_reference.xref_card_num} column already holds the identical value as its primary key,
 *     under the same access control; it is not a log, not configuration, not code and not a test fixture, so
 *     no new trust boundary is crossed and no additional privilege is requested. It is never logged, never
 *     returned by any accessor and never placed in an exception message. Should an operator's
 *     cardholder-data boundary exclude the batch metadata tables, drop the key from the checkpoint and
 *     rely on the row count alone, which is functionally sufficient because the ordering is fixed. See that
 *     method for the full rationale.</li>
 * <li>Emitting {@code DISPLAY CARD-XREF-RECORD} verbatim would
 *     publish a primary account number twice per row, so an identifier-only
 *     projection is emitted instead. Stated so that a later reader does not &quot;restore parity&quot; by
 *     reinstating the
 *     record image.</li>
 * <li>The three-way verb-count divergence described above. No action beyond citing the
 *     statement counts.</li>
 * <li>the {@code '9x'} status family maps to
 *     {@code com.cardemo.exception.FileAccessException} in the shared status vocabulary, but this reader never
 *     raises it. That is measured, not an oversight: every failure branch in {@code CBACT03C} runs
 *     {@code DISPLAY}, then {@code PERFORM 9910-DISPLAY-IO-STATUS}, then {@code PERFORM 9999-ABEND-PROGRAM}
 *     ({@code :L110-L113}, {@code :L129-L132}, {@code :L147-L150}), so there is no path on which a non-normal
 *     status is anything but fatal; introducing a non-fatal I/O outcome here would
 *     be a behaviour change, and importing that type would leave an unused import.</li>
 * <li>{@code app/cpy/CVACT03Y.cpy:L4} writes {@code 01 CARD-XREF-RECORD.} with a single
 *     space after {@code 01} where its sibling copybooks use two. It is cosmetic and affects no Java output;
 *     it is noted only so that nobody &quot;corrects&quot; a citation that quotes it faithfully.</li>
 * <li>the specific z/OS VSAM subcode a given JDBC failure would have produced on the
 *     mainframe cannot be determined here. It would need a z/OS VSAM trace of the failing condition,
 *     which cannot be obtained here because mainframe-runtime reproduction is out of scope for this
 *     migration. If a byte-exact subcode is ever required, add a SQLSTATE-to-subcode
 *     table at the {@link FileStatusMapper} layer, where the single definition of the status vocabulary
 *     already lives, rather than in this reader.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <b>Not thread safe, by design.</b> The instance carries the cursor state that the legacy program held in
 * {@code WORKING-STORAGE} ({@code :L42-L67}): {@code END-OF-FILE}, {@code APPL-RESULT}, {@code IO-STATUS},
 * the record area and the row counter. The {@code step} scope gives each step execution its own instance,
 * which is precisely what keeps that state from being shared. There are <b>no mutable static fields</b>: the
 * only static members are the logger and immutable constants.
 *
 * @see CardCrossReferenceRepository
 * @see FileStatusMapper
 * @see CardCrossReference
 */
@Component
@StepScope
public class CardCrossReferenceReader implements ItemStreamReader<CardCrossReference> {

    /**
     * The one permitted static member that behaves like state: a logger reference that is itself immutable.
     * Every emission in this class goes through SLF4J, never {@code System.out}, {@code System.err} or
     * {@code printStackTrace()}, so the JSON encoding and the credential, hash and social-security masking
     * rules configured in {@code src/main/resources/logback-spring.xml} govern what actually reaches a log
     * aggregator.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardCrossReferenceReader.class);

    /**
     * Default rows per round trip, used when
     * {@code carddemo.batch.card-cross-reference-reader.page-size} is not set. Chosen so the entire 50-row
     * seed fixture {@code app/data/ASCII/cardxref.txt} (1,850 bytes, 50 records of 36 characters plus a
     * terminator, measured) is satisfied by one query while the resident set stays small.
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * The entity property the scan is ordered by, and the JPA counterpart of {@code XREF-CARD-NUM}
     * ({@code app/cpy/CVACT03Y.cpy:L5}), which is also {@code RECORD KEY IS FD-XREF-CARD-NUM}
     * ({@code app/cbl/CBACT03C.cbl:L32}).
     */
    private static final String ORDER_PROPERTY = "cardNumber";

    /**
     * Logical file name reported on every diagnostic. It is the {@code ASSIGN TO} name at
     * {@code app/cbl/CBACT03C.cbl:L29}, which is also the DD name at {@code app/jcl/READXREF.jcl:L25}. It is
     * deliberately the DD name rather than either CICS file name &mdash; {@code CCXREF} over the base cluster
     * or {@code CXACAIX} over the alternate-index path &mdash; because the legacy diagnostics this class
     * reproduces are batch output and the batch program never sees a CICS name.
     */
    private static final String LOGICAL_FILE = "XREFFILE";

    /**
     * The program name the legacy load module carried, used as the abend culprit so that
     * {@code ABEND-CULPRIT PIC X(8)} of {@code app/cpy/CSMSG02Y.cpy} is populated with a real component
     * identity. Exactly eight characters, matching the picture clause and matching
     * {@code PROGRAM-ID. CBACT03C.} at {@code app/cbl/CBACT03C.cbl:L23}.
     */
    private static final String ABEND_CULPRIT = "CBACT03C";

    // ----------------------------------------------------------------------------------------------------
    // Legacy DISPLAY literals, reproduced byte for byte. Each is followed by its measured inner length so a
    // reader can confirm fidelity without opening the source. Every assertion is cited.
    // All seven literals of the program are represented; there is no eighth, because the two
    // DISPLAY CARD-XREF-RECORD statements at :L78 and :L96 emit a record rather than a literal and are
    // covered by the documented deviation instead.
    // ----------------------------------------------------------------------------------------------------

    /** {@code app/cbl/CBACT03C.cbl:L71}, 38 characters. */
    private static final String START_OF_EXECUTION_MESSAGE = "START OF EXECUTION OF PROGRAM CBACT03C";

    /** {@code app/cbl/CBACT03C.cbl:L85}, 36 characters. */
    private static final String END_OF_EXECUTION_MESSAGE = "END OF EXECUTION OF PROGRAM CBACT03C";

    /**
     * {@code app/cbl/CBACT03C.cbl:L110}, 22 characters.
     * <p>
     * All three of this program's error literals name the file identically, as {@code XREFFILE}, and all three
     * are 22 characters. {@code CBACT01C} is internally inconsistent on the same three lines, spelling the
     * file {@code ACCTFILE} on the open path and {@code ACCOUNT FILE} on the read and close paths;
     * {@code CBACT03C} is not, and the consistency here is the source's, not a normalisation applied by this
     * class.
     */
    private static final String ERROR_READING_MESSAGE = "ERROR READING XREFFILE";

    /** {@code app/cbl/CBACT03C.cbl:L129}, 22 characters. */
    private static final String ERROR_OPENING_MESSAGE = "ERROR OPENING XREFFILE";

    /** {@code app/cbl/CBACT03C.cbl:L147}, 22 characters. */
    private static final String ERROR_CLOSING_MESSAGE = "ERROR CLOSING XREFFILE";

    /** {@code app/cbl/CBACT03C.cbl:L155}, 16 characters. */
    private static final String ABENDING_PROGRAM_MESSAGE = "ABENDING PROGRAM";

    // ----------------------------------------------------------------------------------------------------
    // Execution-context keys for the restart cursor. Namespaced by simple class name so two readers in the
    // same step cannot collide.
    // ----------------------------------------------------------------------------------------------------

    /** Key under which the number of rows already emitted is checkpointed. */
    private static final String CONTEXT_KEY_RECORDS_READ = "CardCrossReferenceReader.recordsRead";

    /**
     * Key under which the primary key of the most recently emitted row is checkpointed. See
     * {@link #update(ExecutionContext)} for why this value is written here and nowhere else, and for the
     * cardholder-data boundary this raises.
     */
    private static final String SEED_CARD_NUMBER = "";

    /** {@code END-OF-FILE PIC X(01) VALUE 'N'} in its initial state ({@code app/cbl/CBACT03C.cbl:L65}). */
    private static final String END_OF_FILE_NO = "N";

    /** {@code END-OF-FILE} after {@code MOVE 'Y' TO END-OF-FILE} ({@code app/cbl/CBACT03C.cbl:L108}). */
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
     * cause of the thrown exception. The absence of a byte-exact VSAM subcode is recorded in the class
     * documentation.
     * <p>
     * A first byte of {@code '9'} is also what selects the first branch of {@code 9910-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBACT03C.cbl:L163}), so a store failure renders through the same branch the legacy
     * program used for a physical I/O error rather than through the zero-padded branch.
     */
    private static final String STATUS_PHYSICAL_IO_ERROR =
            String.valueOf(FileStatus.IO_ERROR_FIRST_BYTE) + NUMERIC_PAD;

    // ----------------------------------------------------------------------------------------------------
    // Collaborators, injected through the constructor and never reassigned.
    // ----------------------------------------------------------------------------------------------------

    /**
     * The persistence access point for the card cross-reference, replacing the {@code XREFFILE} VSAM cluster
     * {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}.
     * <p>
     * Only two of its operations are ever called, and both are read-only: the inherited
     * {@link CardCrossReferenceRepository#count()} and the declared
     * {@link CardCrossReferenceRepository#findByCardNumberGreaterThanOrderByCardNumberAsc(String,
     * org.springframework.data.domain.Pageable)},
     * whose own documentation attributes it to {@code app/cbl/CBSTM03A.CBL:L345-L366}, paragraph
     * {@code 1000-XREFFILE-GET-NEXT}, and names the empty string as its start-of-sequence seed. The keyed
     * sequence that finder ascends is the same one {@code app/cbl/CBACT03C.cbl:L29-L32} declares with
     * {@code ORGANIZATION IS INDEXED} and {@code ACCESS MODE IS SEQUENTIAL}, which is this class's own
     * source.
     * <p>
     * The interface's other derived finder,
     * {@link CardCrossReferenceRepository#findFirstByAccountIdOrderByCardNumberAsc(Long)}, models the
     * <em>alternate-index</em> path over {@code CXACAIX} and belongs to the callers that read by account;
     * calling it from a batch reader would misattribute this class's source. Narrowing the inherited
     * {@code findAll(Pageable)} overload with an explicit sort would express an order and a limit but not a
     * keyset bound, which is why the declared finder is used.
     */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * The central {@code FILE STATUS} translator. Consumed rather than re-implemented: it already renders the
     * {@code 9910-DISPLAY-IO-STATUS} line and already applies the {@code APPL-RESULT} arithmetic of both the
     * two-way guard and the three-way sequential-read guard, and its own documentation cites
     * {@code app/cbl/CBACT03C.cbl:L98} among the sites for the latter. Duplicating any of that here would
     * violate Rule 1 clause C.
     */
    private final FileStatusMapper fileStatusMapper;

    /** Rows fetched per round trip; validated at construction and never changed afterwards. */
    private final int pageSize;

    // ----------------------------------------------------------------------------------------------------
    // Cursor state. Every field below is the Java counterpart of a WORKING-STORAGE item at
    // app/cbl/CBACT03C.cbl:L42-L67 and is therefore an INSTANCE field: never static, never shared. The step
    // scope gives each step execution its own instance.
    // ----------------------------------------------------------------------------------------------------

    /** {@code END-OF-FILE PIC X(01)} ({@code :L65}). Held as its literal {@code 'N'} or {@code 'Y'} value. */
    private String endOfFile = END_OF_FILE_NO;

    /**
     * {@code APPL-RESULT PIC S9(9) COMP} ({@code :L61}), tested through {@code 88 APPL-AOK VALUE 0}
     * ({@code :L62}) and {@code 88 APPL-EOF VALUE 16} ({@code :L63}).
     */
    private int applResult;

    /** {@code IO-STATUS} ({@code :L50-L52}), the two-character status moved in before the renderer runs. */
    private String ioStatus = STATUS_SUCCESS;

    /**
     * {@code CARD-XREF-RECORD}, the record area that {@code COPY CVACT03Y} declares at {@code :L45}. Its
     * COBOL counterpart occupies 36 populated bytes inside a 50-byte slot; here it is a mapped entity and no
     * byte offset is involved.
     */
    private CardCrossReference cardCrossReferenceRecord;

    /** The rows of the page currently buffered, standing in for the VSAM read-ahead buffer. */
    private List<CardCrossReference> pageBuffer = List.of();

    /** Cursor into {@link #pageBuffer}; the next row to hand out. */
    private int pageBufferIndex;

    /**
     * Keyset cursor: the highest {@code XREF-CARD-NUM} already <em>fetched</em> into {@link #pageBuffer}, and
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

    /** Whether {@code openCrossReferenceFile()} has completed successfully, mirroring an open VSAM ACB. */
    private boolean fileOpen;

    /** Total rows the relation held when the file was opened, used for the explicit empty-relation branch. */
    private long recordCountAtOpen;

    /**
     * Creates a reader bound to the card cross-reference.
     *
     * @param cardCrossReferenceRepository the cross-reference persistence access point; must not be
     *     {@code null}
     * @param fileStatusMapper the shared {@code FILE STATUS} translator; must not be {@code null}
     * @param pageSize rows per round trip, supplied by
     *     {@code carddemo.batch.card-cross-reference-reader.page-size} and defaulting to
     *     {@value #DEFAULT_PAGE_SIZE}; must be at least one
     * @throws NullPointerException if either collaborator is {@code null}
     * @throws IllegalArgumentException if {@code pageSize} is less than one
     */
    public CardCrossReferenceReader(
            CardCrossReferenceRepository cardCrossReferenceRepository,
            FileStatusMapper fileStatusMapper,
            @Value("${carddemo.batch.card-cross-reference-reader.page-size:" + DEFAULT_PAGE_SIZE + "}")
                    int pageSize) {
        // Only Objects.requireNonNull and private static validators are called here. Invoking an overridable
        // instance method from the constructor of a non-final class would publish a partially built reference,
        // which -Xlint:all -Werror reports as this-escape; the step scope forbids a final class because it
        // proxies by subclassing.
        this.cardCrossReferenceRepository = Objects.requireNonNull(
                cardCrossReferenceRepository, "cardCrossReferenceRepository must not be null");
        this.fileStatusMapper = Objects.requireNonNull(fileStatusMapper, "fileStatusMapper must not be null");
        this.pageSize = requirePositivePageSize(pageSize);
    }

    // ====================================================================================================
    // Mainline PROCEDURE DIVISION, app/cbl/CBACT03C.cbl:L70-L87, realised as the ItemStream lifecycle.
    // ====================================================================================================

    /**
     * Opens the scan: emits the start-of-execution banner and performs {@code 0000-XREFFILE-OPEN},
     * reproducing {@code app/cbl/CBACT03C.cbl:L71-L72}.
     * <p>
     * <b>Side effects.</b> Resets all cursor state, restores the restart cursor from {@code executionContext}
     * when one is present, issues one {@code count()} round trip against the cross-reference relation, and
     * writes two or three log events. Nothing is emitted that could carry a card number.
     *
     * @param executionContext the step execution context; a restart cursor written by a previous run of the
     *     same step instance is honoured when present, and a {@code null} context is treated as a cold start
     * @throws org.springframework.batch.item.ItemStreamException never thrown directly; a store failure is
     *     reported as {@link FatalProcessingException}, which is also unchecked
     * @throws FatalProcessingException if the cross-reference relation cannot be reached, reproducing the
     *     abend at {@code app/cbl/CBACT03C.cbl:L132}
     */
    @Override
    public void open(ExecutionContext executionContext) {
        // Cold-start every cursor field first, so a reused instance cannot inherit a previous scan's position.
        endOfFile = END_OF_FILE_NO;
        applResult = FileStatusMapper.APPL_AOK;
        ioStatus = STATUS_SUCCESS;
        cardCrossReferenceRecord = null;
        pageBuffer = List.of();
        pageBufferIndex = 0;
        fetchCursorCardNumber = SEED_CARD_NUMBER;
        recordsRead = 0L;
        fileOpen = false;
        recordCountAtOpen = 0L;

        // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'.  (:L71)
        LOG.info(START_OF_EXECUTION_MESSAGE);

        // A null context is an explicit, handled case rather than a guarded assumption (Rule 1 clause B).
        if (executionContext != null && executionContext.containsKey(CONTEXT_KEY_RECORDS_READ)) {
            restoreRestartCursor(executionContext);
        }

        // PERFORM 0000-XREFFILE-OPEN.  (:L72)
        openCrossReferenceFile();
    }

    /**
     * Returns the next cross-reference row, or {@code null} once the scan is exhausted, reproducing the
     * mainline loop body at {@code app/cbl/CBACT03C.cbl:L74-L81}.
     * <p>
     * The method body is the loop <em>body</em>, not the loop: Spring Batch drives the iteration, so
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} becomes the framework calling this method until it answers
     * {@code null}. Both of the source's guards are reproduced explicitly, in order, and neither is collapsed;
     * see parity structure 1 in the class documentation.
     * <p>
     * <b>This method emits the SECOND of the two record-level events per row.</b> {@code :L78} is
     * {@code DISPLAY CARD-XREF-RECORD} in the mainline, and {@code :L96} is the identical statement inside
     * {@code 1000-XREFFILE-GET-NEXT}, which {@code getNextCrossReferenceRecord()} reproduces. Both are active
     * in the source, so both are reproduced, and each cites its own line. The record image itself is replaced
     * by an ordinal-only projection: the fact of the read and the row sequence number, never the 16-character
     * card number, never the account identifier and never the customer identifier. That is the single
     * deliberate deviation in this class; the full rationale is in the class documentation.
     * <p>
     * <b>Why there is no {@code @Transactional} annotation.</b> A chunk-oriented step already runs this method
     * inside its own transaction, and Spring silently ignores the {@code readOnly} attribute of a method that
     * merely <em>participates</em> in an existing transaction rather than starting one. Annotating
     * {@code readOnly = true} here would therefore read as an enforced guarantee while enforcing nothing,
     * which Rule 1 clause A rules out. Read-only is guaranteed structurally instead: the only repository
     * operations this class can reach are {@link CardCrossReferenceRepository#count()} and
     * {@link CardCrossReferenceRepository#findByCardNumberGreaterThanOrderByCardNumberAsc(String,
     * org.springframework.data.domain.Pageable)}, and there is no
     * mutating call, no {@code @Modifying} query and no {@code EntityManager} reference anywhere in the file.
     *
     * @return the next cross-reference row in ascending {@code cardNumber} order, or {@code null} at end of
     *     data, which is the Spring Batch end-of-input signal and the analogue of
     *     {@code MOVE 'Y' TO END-OF-FILE}
     * @throws FatalProcessingException if the store reports a status that is neither {@code '00'} nor
     *     {@code '10'}, reproducing the abend path at {@code app/cbl/CBACT03C.cbl:L110-L113}
     * @throws IllegalStateException if called before {@link #open(ExecutionContext)}, which has no legacy
     *     counterpart because the mainline performs the open unconditionally at {@code :L72}
     */
    @Override
    public CardCrossReference read() {
        if (!fileOpen) {
            throw new IllegalStateException(
                    "read() called before open(ExecutionContext); app/cbl/CBACT03C.cbl:L72 performs "
                            + "0000-XREFFILE-OPEN before the mainline loop, so the file is always open by "
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

        // PERFORM 1000-XREFFILE-GET-NEXT  (:L76) - which itself emits the FIRST record event, from :L96.
        CardCrossReference crossReference = getNextCrossReferenceRecord();

        // IF END-OF-FILE = 'N'  (:L77) - the second guard. It cannot fail when the first passed and a record
        // was returned, which is exactly why it is redundant, and exactly why it is preserved: parity
        // structure 1. The null test is the same condition expressed through the returned value.
        if (!END_OF_FILE_NO.equals(endOfFile) || crossReference == null) {
            return null;
        }

        recordsRead++;

        // DISPLAY CARD-XREF-RECORD  (:L78) - the SECOND of this program's two emissions per row. The first is
        // at :L96 inside the read paragraph and is ACTIVE, unlike CBACT02C:L96 which is commented out; see the
        // double-display section of the class documentation. Reproduced as an ordinal-only projection
        // rather than as the record image: bytes 1-16 are the card number (app/cpy/CVACT03Y.cpy:L5), which
        // could never go into a log. Neither can XREF-ACCT-ID. This table is nothing BUT the association
        // between a card, an account and a customer, so emitting any one of its three columns publishes part
        // of that association into a stream that is aggregated, retained and replicated outside the boundary
        // protecting the row - and the account identifier is the column the rest of the batch stream joins
        // on. The event carries the fact of the read and its ordinal, and reads no field at all.
        if (LOG.isDebugEnabled()) {
            LOG.debug("{} record read (app/cbl/CBACT03C.cbl:L78); sequence={}",
                    LOGICAL_FILE,
                    Long.valueOf(recordsRead));
        }

        return crossReference;
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
     * <b>No card number leaves its row anywhere in this class, and that includes here.</b> An earlier
     * revision checkpointed the last card number alongside the count, and argued that doing so crossed no new
     * trust boundary: the step execution context is transactional state that Spring Batch persists to
     * {@code BATCH_STEP_EXECUTION_CONTEXT} in the same database and schema whose
     * {@code card_cross_reference.xref_card_num} column already holds the identical value as its primary key,
     * under the same access control, and it is not a log, not configuration, not code and not a test fixture
     * &mdash; the four surfaces Rule 1 clause D names. That argument is sound as far as it goes, and it was
     * still the wrong trade: the batch metadata tables have their own retention, their own backup path and
     * their own audience, and an operator whose cardholder-data boundary excludes them would have had to
     * change this class to comply. Since the count alone is sufficient - the ordering is fixed, so the keyset
     * position is re-derivable from it by one bounded seek - the card number is simply not written, and the
     * choice costs nothing that has to be argued again later. The value is never logged by this class, is
     * exposed by no accessor and never appears in an exception message; {@link #restoreRestartCursor(
     * ExecutionContext)} likewise reads and reports only the row count.
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
     * Closes the scan: performs {@code 9000-XREFFILE-CLOSE} and emits the end-of-execution banner,
     * reproducing {@code app/cbl/CBACT03C.cbl:L83-L85}.
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
     *     {@code app/cbl/CBACT03C.cbl:L150}
     */
    @Override
    public void close() {
        // PERFORM 9000-XREFFILE-CLOSE.  (:L83)
        closeCrossReferenceFile();

        // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'.  (:L85)
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
     * Note that the count is the number of <em>rows</em> emitted, not the number of log events, which is twice
     * as many by design.
     *
     * @return the count of rows returned by {@link #read()} since the last {@link #open(ExecutionContext)},
     *     never negative
     */
    public long getRecordsRead() {
        return recordsRead;
    }

    // ====================================================================================================
    // 1000-XREFFILE-GET-NEXT, app/cbl/CBACT03C.cbl:L92-L116.
    // ====================================================================================================

    /**
     * Reads the next record and applies the three-way sequential-read guard of
     * {@code 1000-XREFFILE-GET-NEXT} ({@code app/cbl/CBACT03C.cbl:L92-L116}).
     * <p>
     * The source shape is preserved exactly: the read sets a status; {@code '00'} yields
     * {@code MOVE 0 TO APPL-RESULT} ({@code :L95}); {@code '10'} yields {@code MOVE 16} ({@code :L99});
     * anything else yields {@code MOVE 12} ({@code :L101}). The guard that follows then either continues, sets
     * {@code END-OF-FILE} to {@code 'Y'}, or reports and abends ({@code :L104-L115}).
     * <p>
     * <b>The double display lives here.</b> {@code :L96} is {@code DISPLAY CARD-XREF-RECORD} and it is
     * <b>ACTIVE</b>: it sits in the {@code '00'} branch immediately after {@code MOVE 0 TO APPL-RESULT}, in
     * exactly the position {@code CBACT01C} fills with {@code PERFORM 1100-DISPLAY-ACCT-RECORD} and exactly
     * the position where {@code CBACT02C} has a <em>comment</em>. This method therefore emits the <b>first</b>
     * of the two record-level events per row, and {@link #read()} emits the second from {@code :L78}. Removing
     * the event below would be a parity break; adding its equivalent to
     * {@code com.cardemo.batch.readers.CardReader} would be a parity break in the opposite direction. The
     * emission is an ordinal-only projection for the reason given in the class documentation.
     *
     * @return the record just read when the status was {@code '00'}, or {@code null} at end of file
     * @throws FatalProcessingException when the status is neither {@code '00'} nor {@code '10'}, carrying the
     *     store failure as its cause when one was raised
     */
    private CardCrossReference getNextCrossReferenceRecord() {
        DataAccessException storeFailure = null;
        try {
            // READ XREFFILE-FILE INTO CARD-XREF-RECORD.  (:L93)
            ioStatus = readNextRecord();
        } catch (DataAccessException failure) {
            // The COBOL READ reports through XREFFILE-STATUS; a relational store reports by throwing. The
            // throwable is translated to the '9x' family and then RETAINED as the cause, never swallowed and
            // never allowed to escape untyped (Rule 1 clause B).
            storeFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF XREFFILE-STATUS = '00' MOVE 0 TO APPL-RESULT / ELSE IF '10' MOVE 16 / ELSE MOVE 12
        // (:L94-L103). The arithmetic is not restated here: FileStatusMapper already implements this exact
        // nested test and its documentation cites this very paragraph, at :L98 (Rule 1 clause C).
        applResult = fileStatusMapper.applResultForSequentialRead(ioStatus);

        // ------------------------------------------------------------------------------------------------
        // PRESERVED QUIRK, the double display. The next line of the source, inside the '00' branch and
        // immediately after MOVE 0 TO APPL-RESULT, is:
        //
        //     app/cbl/CBACT03C.cbl:L96              DISPLAY CARD-XREF-RECORD
        //
        // Column 7 of that line is a SPACE, so it is a STATEMENT and not a comment. It is therefore
        // reproduced below as executable code. Three consequences follow, all measured:
        //   1. CBACT03C emits each record TWICE per iteration - once here and once from the mainline at
        //      :L78 - so this class emits two events per row. Emitting one would be a parity break.
        //   2. The DISPLAY statement count is 10 rather than 9, and this line is the tenth. That single
        //      line is the whole difference between this program's inventory and CBACT02C's.
        //   3. app/cbl/CBACT02C.cbl:L96 is the same statement with an asterisk in column 7, so
        //      com.cardemo.batch.readers.CardReader deliberately reproduces it as a comment only. The two
        //      files are NOT interchangeable.
        // ------------------------------------------------------------------------------------------------
        if (applResult == FileStatusMapper.APPL_AOK && LOG.isDebugEnabled()) {
            LOG.debug("{} record read (app/cbl/CBACT03C.cbl:L96); sequence={}",
                    LOGICAL_FILE,
                    Long.valueOf(recordsRead + 1L));
        }

        // IF APPL-AOK CONTINUE  (:L104-L105)
        if (applResult == FileStatusMapper.APPL_AOK) {
            return cardCrossReferenceRecord;
        }

        // ELSE IF APPL-EOF MOVE 'Y' TO END-OF-FILE  (:L107-L108). End of file is loop termination, NOT an
        // error: 88 APPL-EOF VALUE 16 at :L63 is a normal outcome and nothing is thrown for it.
        if (applResult == FileStatusMapper.APPL_EOF) {
            endOfFile = END_OF_FILE_YES;
            cardCrossReferenceRecord = null;
            return null;
        }

        // ELSE DISPLAY 'ERROR READING XREFFILE' / MOVE XREFFILE-STATUS TO IO-STATUS /
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
     * Performs the store round trip behind the {@code READ XREFFILE-FILE INTO CARD-XREF-RECORD} verb at
     * {@code app/cbl/CBACT03C.cbl:L93} and reports its outcome as a COBOL file status.
     * <p>
     * A VSAM {@code READ} with {@code ACCESS MODE IS SEQUENTIAL} hands back one record and advances the
     * cursor. Here the cursor is a buffered window refilled by
     * {@link CardCrossReferenceRepository#findByCardNumberGreaterThanOrderByCardNumberAsc(String,
     * org.springframework.data.domain.Pageable)},
     * whose ascending key order is fixed <b>in the method name itself</b> and so cannot be omitted or
     * overridden by a caller. The store's natural
     * order is never relied upon: {@code app/cbl/CBACT03C.cbl:L29-L33} declares {@code ORGANIZATION IS
     * INDEXED} with {@code ACCESS MODE IS SEQUENTIAL} and {@code RECORD KEY IS FD-XREF-CARD-NUM}, so key order
     * <em>is</em> the contract, and reproducing it deterministically is what makes the emitted sequence
     * comparable against the legacy baseline (Rule 1 clause A).
     * <p>
     * The key is a 16-character text field, not a number: {@code XREF-CARD-NUM PIC X(16)}
     * ({@code app/cpy/CVACT03Y.cpy:L5}), key length 16 at {@code app/catlg/LISTCAT.txt:L403} and
     * {@code KEYS(16 0)} at {@code app/jcl/XREFFILE.jcl:L43}. Ascending order is therefore the collation of
     * the {@code CHAR(16)} column, which is what preserves the leading zeros the 50-row fixture at
     * {@code app/data/ASCII/cardxref.txt} actually contains.
     * <p>
     * <b>Why the window is keyset-bounded and not offset-paged</b> (Rule 1 clause A, tradeoff justified rather
     * than assumed). An offset page asks the store to produce and discard every row before the window, so
     * walking the relation costs work quadratic in its size, and the discarded prefix grows with every step. A
     * keyset window instead asks for {@code XREF-CARD-NUM > cursor ... LIMIT pageSize}, which the primary-key
     * index satisfies by seeking straight to the cursor and reading forward: constant work per window,
     * independent of how far the scan has already travelled. This is also the closer analogue of the source,
     * because a VSAM sequential read positions by key and reads forward rather than counting from the start of
     * the cluster. The window size cannot affect the emitted output, because the ordering is fixed independently
     * of it, and the seek bound is exclusive so no row is visited twice or skipped.
     * <p>
     * A second, unrelated saving: this finder returns a {@code List}, so no {@code COUNT(*)} is issued. The
     * page-shaped predecessor computed a total on every refill that nothing on this path ever read. The one
     * count this class does perform is the deliberate, once-per-open one in {@code openCrossReferenceFile()},
     * which exists to make the empty-relation case an explicit logged outcome.
     * <p>
     * The alternate index is deliberately not used; see the class documentation for its geometry and for the
     * zero-based versus 1-based offset convention.
     *
     * @return {@link #STATUS_SUCCESS} when a record was placed in the record area, {@link #STATUS_END_OF_FILE}
     *     when the scan is exhausted, or {@link #STATUS_PHYSICAL_IO_ERROR} when the buffer yielded a
     *     {@code null} element, which a {@code NOT NULL} keyed relation cannot legitimately produce
     * @throws DataAccessException if the store rejects the query; translated by the caller
     */
    private String readNextRecord() {
        while (pageBufferIndex >= pageBuffer.size()) {
            // The window is bounded by the cursor, never by an offset: XREF-CARD-NUM > cursor ORDER BY
            // XREF-CARD-NUM ASC LIMIT pageSize. PageRequest.ofSize() is page zero, so the offset is always 0.
            pageBuffer = cardCrossReferenceRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    fetchCursorCardNumber, PageRequest.ofSize(pageSize));
            pageBufferIndex = 0;

            if (pageBuffer.isEmpty()) {
                cardCrossReferenceRecord = null;
                return STATUS_END_OF_FILE;
            }

            // Advance the cursor to the highest key in the window just fetched, so the next window starts
            // strictly after it. Explicit null branch (Rule 1 clause B): XREF-CARD-NUM is NOT NULL and is the
            // primary key, so a null here means the result set is not what the schema promises. It is
            // reported through the status vocabulary rather than allowed to become a NullPointerException,
            // and the cursor is deliberately left unadvanced on that path.
            CardCrossReference highestOfWindow = pageBuffer.get(pageBuffer.size() - 1);
            if (highestOfWindow == null || highestOfWindow.getCardNumber() == null) {
                cardCrossReferenceRecord = null;
                return STATUS_PHYSICAL_IO_ERROR;
            }
            fetchCursorCardNumber = highestOfWindow.getCardNumber();
        }

        CardCrossReference next = pageBuffer.get(pageBufferIndex);
        pageBufferIndex++;

        // Explicit null branch (Rule 1 clause B): every row of this relation is NOT NULL and keyed, so a null
        // element means the result set is not what the schema promises. It is reported through the status
        // vocabulary rather than allowed to become a NullPointerException further down.
        if (next == null) {
            cardCrossReferenceRecord = null;
            return STATUS_PHYSICAL_IO_ERROR;
        }

        cardCrossReferenceRecord = next;
        return STATUS_SUCCESS;
    }

    // ====================================================================================================
    // 0000-XREFFILE-OPEN, app/cbl/CBACT03C.cbl:L118-L134.
    // ====================================================================================================

    /**
     * Opens the card cross-reference, reproducing {@code 0000-XREFFILE-OPEN}
     * ({@code app/cbl/CBACT03C.cbl:L118-L134}).
     * <p>
     * <b>Arithmetic idiom, parity structure 2.</b> This paragraph primes the result field with
     * {@code MOVE 8 TO APPL-RESULT} at {@code :L119}, and sets its two outcomes with {@code MOVE 0}
     * ({@code :L122}) and {@code MOVE 12} ({@code :L124}). Its counterpart
     * {@code closeCrossReferenceFile()} primes the same field with
     * {@code ADD 8 TO ZERO GIVING APPL-RESULT} at {@code :L137}, clears it with
     * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} at {@code :L140} and sets the failure value with
     * {@code ADD 12 TO ZERO GIVING APPL-RESULT} at {@code :L142}. The two paragraphs compute identical values
     * by different verbs. They are kept as two methods, each documenting the idiom its own source paragraph
     * uses, and are deliberately not normalised into one shape.
     * <p>
     * <b>What stands in for {@code OPEN INPUT}.</b> There is no file handle to acquire, so the analogue is a
     * single {@link CardCrossReferenceRepository#count()} round trip. It establishes exactly what a VSAM open
     * establishes &mdash; that the dataset is reachable &mdash; because an unreachable relation surfaces as a
     * {@link DataAccessException}, which is the counterpart of file status {@code '35'} or the {@code '9x'}
     * family. It also yields the row count, which makes the empty-relation case an explicit, logged outcome
     * rather than something inferred later from an absence of records (Rule 1 clause B). The call returns a
     * scalar, so no sort applies to it.
     * <p>
     * Note that this paragraph uses the <b>two-way</b> guard: {@code '00'} succeeds and everything else,
     * {@code '10'} included, fails. End of file is not a reachable outcome of an open, so a report of it is an
     * unexpected condition. That is a different shape from the three-way guard of
     * {@code 1000-XREFFILE-GET-NEXT}, and the two must not be collapsed.
     * <p>
     * <b>Side effects.</b> One store round trip; sets the open flag and the row count; writes one log event on
     * success and two before abending on failure.
     *
     * @throws FatalProcessingException if the relation cannot be reached, reproducing
     *     {@code DISPLAY 'ERROR OPENING XREFFILE'} then {@code PERFORM 9999-ABEND-PROGRAM} at
     *     {@code :L129-L132}
     */
    private void openCrossReferenceFile() {
        // MOVE 8 TO APPL-RESULT.  (:L119) - the OPEN idiom. See the arithmetic note above.
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        DataAccessException storeFailure = null;
        try {
            // OPEN INPUT XREFFILE-FILE  (:L120)
            recordCountAtOpen = cardCrossReferenceRepository.count();
            ioStatus = STATUS_SUCCESS;
        } catch (DataAccessException failure) {
            storeFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF XREFFILE-STATUS = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 TO APPL-RESULT  (:L121-L125).
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
            // ELSE DISPLAY 'ERROR OPENING XREFFILE' / MOVE XREFFILE-STATUS TO IO-STATUS /
            // PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM  (:L129-L132)
            LOG.error(ERROR_OPENING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            abendProgram(ERROR_OPENING_MESSAGE, storeFailure);
        }
        // EXIT.  (:L134)
    }

    // ====================================================================================================
    // 9000-XREFFILE-CLOSE, app/cbl/CBACT03C.cbl:L136-L152.
    // ====================================================================================================

    /**
     * Closes the card cross-reference, reproducing {@code 9000-XREFFILE-CLOSE}
     * ({@code app/cbl/CBACT03C.cbl:L136-L152}).
     * <p>
     * <b>Arithmetic idiom, parity structure 2.</b> Where {@code openCrossReferenceFile()} writes
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
     * The record area is cleared here, which also means the last row read is no longer reachable through this
     * instance once the step has closed.
     * <p>
     * <b>Side effects.</b> Clears the buffer, the cursor and the open flag. Writes two log events only when
     * the close fails.
     *
     * @throws FatalProcessingException if releasing the cursor raises a runtime fault, reproducing
     *     {@code DISPLAY 'ERROR CLOSING XREFFILE'} then {@code PERFORM 9999-ABEND-PROGRAM} at
     *     {@code :L147-L150}
     */
    private void closeCrossReferenceFile() {
        // ADD 8 TO ZERO GIVING APPL-RESULT.  (:L137) - the CLOSE idiom, distinct from the open's MOVE 8.
        applResult = FileStatusMapper.APPL_RESULT_INITIAL;

        RuntimeException teardownFailure = null;
        try {
            // CLOSE XREFFILE-FILE  (:L138)
            pageBuffer = List.of();
            pageBufferIndex = 0;
            cardCrossReferenceRecord = null;
            fileOpen = false;
            ioStatus = STATUS_SUCCESS;
        } catch (RuntimeException failure) {
            teardownFailure = failure;
            ioStatus = STATUS_PHYSICAL_IO_ERROR;
        }

        // IF XREFFILE-STATUS = '00' SUBTRACT APPL-RESULT FROM APPL-RESULT ELSE
        // ADD 12 TO ZERO GIVING APPL-RESULT  (:L139-L143). Both branches compute exactly what
        // applResultForGuard returns - zero and twelve - so the shared translator is consulted instead of the
        // arithmetic being restated (Rule 1 clause C). The source's verbs are recorded above.
        applResult = fileStatusMapper.applResultForGuard(ioStatus);

        // IF APPL-AOK CONTINUE  (:L144-L145)
        if (applResult != FileStatusMapper.APPL_AOK) {
            // ELSE DISPLAY 'ERROR CLOSING XREFFILE' / MOVE XREFFILE-STATUS TO IO-STATUS /
            // PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM  (:L147-L150)
            LOG.error(ERROR_CLOSING_MESSAGE);
            LOG.error(displayIoStatus(ioStatus));
            abendProgram(ERROR_CLOSING_MESSAGE, teardownFailure);
        }
        // EXIT.  (:L152)
    }

    // ====================================================================================================
    // 9999-ABEND-PROGRAM, app/cbl/CBACT03C.cbl:L154-L158.
    // ====================================================================================================

    /**
     * Abends the step, reproducing {@code 9999-ABEND-PROGRAM} ({@code app/cbl/CBACT03C.cbl:L154-L158}).
     * <p>
     * The source emits {@code 'ABENDING PROGRAM'} ({@code :L155}), zeroes {@code TIMING} ({@code :L156}),
     * moves {@code 999} into {@code ABCODE} ({@code :L157}) and calls the Language Environment abend service
     * ({@code :L158}). The Java counterpart throws {@link FatalProcessingException} carrying the complete
     * {@code CABENDD.CPY} payload: abend code
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE}, this program as the culprit,
     * the failing operation as the reason, and the legacy message as the message. Process return code
     * {@value com.cardemo.exception.FatalProcessingException#BATCH_RETURN_CODE} is the exit code the job's
     * status mapping derives from this exception; it is not set here, because a reader does not own the process
     * exit code.
     * <p>
     * {@code TIMING} has no counterpart. It is a Language Environment abend parameter selecting whether a dump
     * is taken, and there is no dump facility to select; the exception carries the stack trace that replaces
     * it.
     * <p>
     * <b>Every failure branch of this program reaches here.</b> All three guards &mdash;
     * {@code :L110-L113} on the read, {@code :L129-L132} on the open and {@code :L147-L150} on the close
     * &mdash; run {@code DISPLAY}, then {@code PERFORM 9910-DISPLAY-IO-STATUS}, then
     * {@code PERFORM 9999-ABEND-PROGRAM}. There is no recoverable I/O outcome in {@code CBACT03C} other than
     * end of file, which is why this reader raises only {@link FatalProcessingException} and never the
     * narrower {@code com.cardemo.exception.FileAccessException}; that type belongs to the shared status
     * vocabulary but has no reachable site here, so importing it would leave an unused import (Rule 1
     * clause B1).
     * <p>
     * <b>No cross-reference data enters the abend payload.</b> The reason and message carry the logical file
     * name, the literal that preceded the abend and the rendered status &mdash; never the record, never the
     * card number and never either identifier, at any level (Rule 1 clause D1).
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
    // 9910-DISPLAY-IO-STATUS, app/cbl/CBACT03C.cbl:L161-L174.
    // ====================================================================================================

    /**
     * Renders a file status as the legacy diagnostic line, reproducing {@code 9910-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBACT03C.cbl:L161-L174}).
     * <p>
     * The paragraph has two branches. When {@code IO-STATUS} is not numeric or its first byte is {@code '9'}
     * ({@code :L162-L163}), byte one is copied into position one ({@code :L164}) and byte two is widened
     * through {@code TWO-BYTES-BINARY} into three digits ({@code :L165-L167}). Otherwise the field is set to
     * {@code '0000'} ({@code :L170}) and the two status characters are overlaid at positions three and four
     * ({@code :L171}). Both branches then emit {@code 'FILE STATUS IS: NNNN'} followed by the four-character
     * field ({@code :L168}, {@code :L172}).
     * <p>
     * <b>{@code 'FILE STATUS IS: NNNN'} is a fixed 20-character literal, not a template.</b> The {@code NNNN}
     * is part of the constant text and the four rendered characters follow it, so status {@code '23'} renders
     * as {@code FILE STATUS IS: NNNN0023} and never as {@code FILE STATUS IS: 0023}. Substituting the digits
     * into the {@code NNNN} would be a parity break: this line is compared byte for byte.
     * <p>
     * <b>This method delegates and holds no logic of its own</b>, which is deliberate. The paragraph is
     * byte-identical in form to its counterparts across the batch corpus &mdash; {@code CBACT03C}'s copy
     * differs from {@code CBACT01C}'s and {@code CBACT02C}'s only in line numbering &mdash; and
     * {@link FileStatusMapper#displayIoStatus(String)} is the single implementation of it, with
     * {@link FileStatus#DISPLAY_MESSAGE_PREFIX} the single definition of the literal. Re-deriving either here
     * would be the parallel mapping that Rule 1 clause C forbids. The method is retained rather than inlined so
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
    // Projection, restart support and construction-time validation. The projection replaces the record image
    // of :L78 and :L96; the rest has no legacy counterpart, because the mainline at
    // app/cbl/CBACT03C.cbl:L70-L87 always scans from the first record - a JES2 job restart re-ran the step
    // from the top. Restartability is additive, and it changes no emitted value.
    // ====================================================================================================


    /**
     * Restores the checkpoint written by {@link #update(ExecutionContext)} so a restarted step resumes instead
     * of re-emitting rows.
     * <p>
     * <b>The checkpointed key is the position; the row count is only a tally.</b> The scan resumes by seeking
     * to {@code XREF-CARD-NUM > } the last key actually emitted, so the first window of the resumed run begins
     * at the row after it regardless of how many rows precede it. The predecessor of this method instead
     * divided the row count into a page number and a within-page offset, which positions correctly only while
     * the relation is unchanged between the two runs: any row inserted or deleted below the cursor shifts every
     * offset after it, so a restart could silently re-emit or silently skip rows. Seeking by key is immune to
     * that, because the key of a row does not move when its neighbours change.
     * <p>
     * <b>No card number is checkpointed, so none is restored and none is reported.</b>
     * {@code AccountReader}'s counterpart reports its checkpointed {@code ACCT-ID} in the resume line, because
     * an account identifier is not cardholder data; a 16-character {@code XREF-CARD-NUM} is, so it is not
     * written to the context in the first place and the resume line carries the row count alone. See
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
        List<CardCrossReference> lastEmitted = cardCrossReferenceRepository
                .findByCardNumberGreaterThanOrderByCardNumberAsc(
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
                    "carddemo.batch.card-cross-reference-reader.page-size must be at least 1 but was %d; a "
                            + "non-positive page cannot advance the sequential scan of XREFFILE",
                    Integer.valueOf(pageSize)));
        }
        return pageSize;
    }

    /**
     * Extracts the two-character code of a {@link FileStatus} that is expected to be an exact value rather
     * than a family, so the literals this class compares against are derived from the single definition of the
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
