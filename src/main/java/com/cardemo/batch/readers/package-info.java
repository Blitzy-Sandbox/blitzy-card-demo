/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.batch.readers
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Spring Batch item-reader layer)
 * Function    : ItemReader implementations for the batch read paths. The
 *               four sequential scan programs each have a verb inventory
 *               of OPEN, READ and CLOSE only - no WRITE, no REWRITE, no
 *               DELETE anywhere - so each becomes a verification step
 *               that reads and reports and mutates nothing. The fifth
 *               reader supplies the 350-byte DALYTRAN input that the
 *               daily posting program consumes, and the sixth reads the
 *               350-byte TRANSACT.BKUP generation that the transaction
 *               report stream backs up before it filters and reports.
 * Source      : app/cbl/CBACT01C.cbl (193 lines; account file scan)
 *               @ 7756d89
 * Source      : app/cbl/CBACT02C.cbl (178 lines; card file scan)
 *               @ 7756d89
 * Source      : app/cbl/CBACT03C.cbl (178 lines; cross-reference scan)
 *               @ 7756d89
 * Source      : app/cbl/CBCUS01C.cbl (178 lines; customer file scan)
 *               @ 7756d89
 * Source      : app/jcl/READACCT.jcl, READCARD.jcl, READXREF.jcl,
 *               READCUST.jcl (the four jobs that ran them) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl (731 lines; the DALYTRAN input path,
 *               a keyless SEQUENTIAL SELECT over a 350-byte record) and
 *               app/cpy/CVTRA06Y.cpy (350-byte DALYTRAN-RECORD) @ 7756d89
 * Source      : app/cbl/CBACT01C.cbl:L78 (DISPLAY ACCOUNT-RECORD),
 *               :L119-L129 (eleven labelled field lines), :L130 (the
 *               49-hyphen rule) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L59, :L202, :L403 (key lengths
 *               11, 16, 16 and average record lengths 300, 150, 50)
 *               @ 7756d89
 * Source      : app/proc/TRANREPT.prc:L21-L31 (STEP01R, the backup REPRO
 *               at LRECL=350 RECFM=FB) and :L36-L37 (STEP05R reading the
 *               same (+1) generation), app/proc/REPROC.prc:L21
 *               (EXEC PGM=IDCAMS), app/ctl/REPROCT.ctl:L15 (the single
 *               REPRO control card) and app/cpy/CVTRA05Y.cpy (350-byte
 *               TRAN-RECORD). No COBOL program exists for that step.
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

/**
 * Sequential readers: the {@code ItemReader} side of the batch stream. Four of them are the legacy programs
 * whose entire purpose was to read a dataset from start to finish and report on it; the fifth feeds the daily
 * posting job its fixed-width input; the sixth reads back the transaction backup generation that the report
 * stream takes before it filters and reports, a step that has no COBOL program at all; the seventh presents the
 * two concatenated generations of the combine step as one ordered stream, a step that likewise has no COBOL
 * program.
 *
 * <p><strong>The read-only property is proved from the source, not assumed.</strong> Each of
 * {@code app/cbl/CBACT01C.cbl}, {@code CBACT02C.cbl}, {@code CBACT03C.cbl} and {@code CBCUS01C.cbl} has a verb
 * inventory of {@code OPEN}, {@code READ} and {@code CLOSE} only - there is no {@code WRITE}, no
 * {@code REWRITE} and no {@code DELETE} anywhere in any of them. That is what makes them verification steps
 * rather than processing steps, and it is why a reader in this package that mutated anything would be in the
 * wrong package.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li>{@link com.cardemo.batch.readers.AccountReader} - {@code app/cbl/CBACT01C.cbl}, 193 lines, over the
 *       account cluster: key length 11, record length 300 ({@code app/catlg/LISTCAT.txt:L59}).</li>
 *   <li>{@link com.cardemo.batch.readers.CardReader} - {@code app/cbl/CBACT02C.cbl}, 178 lines, over the card
 *       cluster: key length 16, record length 150 ({@code app/catlg/LISTCAT.txt:L202}).</li>
 *   <li>{@link com.cardemo.batch.readers.CardCrossReferenceReader} - {@code app/cbl/CBACT03C.cbl}, 178 lines,
 *       over the cross-reference cluster: key length 16, record length 50
 *       ({@code app/catlg/LISTCAT.txt:L403}).</li>
 *   <li>{@link com.cardemo.batch.readers.CustomerReader} - {@code app/cbl/CBCUS01C.cbl}, 178 lines, over the
 *       customer cluster: key length 9, record length 500.</li>
 *   <li>{@link com.cardemo.batch.readers.DailyTransactionReader} - the DALYTRAN input path of
 *       {@code app/cbl/CBTRN02C.cbl}, a keyless {@code SEQUENTIAL} {@code SELECT} with no {@code RECORD KEY}
 *       over the 350-byte {@code DALYTRAN-RECORD} of {@code app/cpy/CVTRA06Y.cpy}. This is the one reader here
 *       that feeds a processing job rather than a verification step, and it decodes zoned-decimal overpunch
 *       signs position-aware from the {@code PIC} clauses, so a negative amount stays negative.</li>
 *   <li>{@link com.cardemo.batch.readers.TransactionBackupReader} - the {@code TRANSACT.BKUP} generation that
 *       {@code app/proc/TRANREPT.prc:L21-L31} {@code STEP01R} creates and {@code :L36-L37} {@code STEP05R}
 *       consumes, over the 350-byte {@code TRAN-RECORD} of {@code app/cpy/CVTRA05Y.cpy}. There is no COBOL
 *       program for that step: {@code app/proc/REPROC.prc:L21} runs {@code IDCAMS} and the whole of the logic
 *       is the single {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)} card at {@code app/ctl/REPROCT.ctl:L15},
 *       so the JCL, the procedure and the control card are the source of truth. It is the <strong>canonical
 *       owner of the {@code CVTRA05Y} overpunch decoder</strong>, and it resolves its generation object key
 *       once and carries it forward rather than re-resolving the newest generation mid-job.</li>
 *   <li>{@link com.cardemo.batch.readers.CombinedTransactionReader} - the concatenated {@code SORTIN} of
 *       {@code app/jcl/COMBTRAN.jcl:STEP05R}, which presents {@code TRANSACT.BKUP(0)} followed by
 *       {@code SYSTRAN(0)} as one stream ordered ascending by {@code TRAN-ID}, over the same 350-byte
 *       {@code TRAN-RECORD} of {@code app/cpy/CVTRA05Y.cpy}. There is no COBOL program for this step either:
 *       its logic is entirely DFSORT and IDCAMS control cards, so the JCL is the source of truth. It
 *       <strong>reuses</strong> the {@code CVTRA05Y} overpunch decoder owned by
 *       {@link com.cardemo.batch.readers.TransactionBackupReader} rather than re-implementing it.</li>
 *   </ul>
 *
 * <p>Re-measured 5 August 2026, this package contains the four verification readers, the daily transaction
 * reader, the transaction backup reader and the concatenated combine reader above, and no others - the full
 * set of seven.
 *
 * <p><strong>Earlier revisions of this document recorded two, and then one, absent input readers.</strong>
 * Each count was correct when it was written and is withdrawn here rather than quietly overwritten: the
 * backup-generation reader and then the concatenated-input reader have since been authored and are both listed
 * above, so <strong>no reader in this package is now absent</strong>.
 *
 * <h3>Two source behaviours that shape every reader here</h3>
 *
 * <p><strong>End of file is not an error, and everything else is.</strong> Every open, read and close in the
 * batch corpus follows one idiom: a signed binary result field is moved to 8, the verb runs, the field becomes
 * 0 when the status is {@code '00'} and 12 otherwise, and a non-zero field displays a message, renders the
 * status and abends. A status of {@code '10'} terminates the loop; a {@code '9x'} status abends with code 999
 * and return code 12. Recognising this as <em>one</em> idiom rather than hundreds of individual checks is why
 * the status-to-exception decision belongs to a single owner,
 * {@code com.cardemo.service.shared.FileStatusMapper}, and is not reimplemented per reader.
 *
 * <p><strong>The four-character status rendering is a contract.</strong>
 * {@code 9910-DISPLAY-IO-STATUS} renders a status as exactly four characters: when it is non-numeric or its
 * first byte is {@code '9'}, that byte is copied through and the second is expanded from a binary field into
 * three digits; otherwise the field is four zeros with the two status characters at positions three and four -
 * so a status of {@code '23'} renders as {@code FILE STATUS IS: NNNN0023}, in which the four {@code N}
 * characters are literal and not a placeholder. The end-to-end gate compares log output against the legacy
 * baseline, so a differently formatted status is a diff.
 *
 * <h3>Where the DISPLAY output goes, and why not to the class logger</h3>
 *
 * <p>{@code app/cbl/CBACT01C.cbl:L78} writes the whole 300-byte account record image to SYSOUT, and
 * {@code :L119-L129} then writes eleven labelled values - the current balance, both credit limits and both
 * current-cycle totals - followed by a rule of exactly 49 hyphens at {@code :L130}. That is customer financial
 * data. Emitting it through the class logger placed it in the application's ordinary log stream where the
 * masking rules of {@code src/main/resources/logback-spring.xml} cannot reach it, because masking matches
 * labelled credentials, hashes and social security numbers and an unlabelled 300-character run presents nothing
 * to match. Those emissions therefore travel on {@code com.cardemo.parity.CBACT01C}, which every shipped
 * profile sets to {@code OFF}; the content, order and formatting are unchanged, only the channel. Raising
 * {@code com.cardemo} does not enable it, because a child level overrides its parent.
 *
 * <p>The other three readers need no such channel and deliberately do not have one: each emits an
 * <strong>identifier-only projection</strong> rather than a record image, because the card record carries a card
 * number and a verification value, the customer record carries a social security number, a government
 * identifier, a date of birth and an electronic-funds account identifier, and the cross-reference record's whole
 * purpose is an association that must not be republished. The <em>count</em> is preserved while the
 * <em>content</em> is withheld.
 *
 * <h2>How to run, build and test</h2>
 *
 * <ul>
 *   <li><strong>Build.</strong> {@code ./mvnw clean verify}. Maven 3.9.11 from the pinned wrapper, Java
 *       {@code [25,)} enforced, {@code release} 25, {@code -Xlint:all -Werror failOnWarning}.</li>
 *   <li><strong>Run.</strong> These are {@code ItemReader} beans; a step drives them and they are never invoked
 *       directly. They need the database up - {@code docker compose up -d} - and the environment loaded with
 *       {@code set -a; . ./.env; set +a}.</li>
 *   <li><strong>Test.</strong> Unit tests belong in {@code src/test/java/com/cardemo/unit/batch} and
 *       repository-backed tests in {@code src/test/java/com/cardemo/integration}. {@code ParityLoggerRoutingTest}
 *       asserts that this package's account reader resolves its parity logger by the shared tree name, and
 *       {@code AccountReaderTest}, {@code CardReaderTest}, {@code CardCrossReferenceReaderTest} and
 *       {@code CustomerReaderTest} cover the four verification readers.
 *       <strong>Measured 4 August 2026:</strong> both input readers now have a test class of their own -
 *       {@code DailyTransactionReaderTest} and {@code TransactionBackupReaderTest} - so every reader in this
 *       package is covered by name. <strong>An earlier revision of this document recorded the daily
 *       transaction reader as having no test class</strong>; that statement was true when written and is
 *       withdrawn here. The assertions it listed as owed are the ones those classes now make: the fixed-width
 *       rendering matching the copybook offsets; the 49-hyphen rule being neither 48 nor 50; the eleven labels
 *       at exactly 25 characters with the colon in column 25; a status of {@code '10'} ending the scan without
 *       an exception; a {@code '9x'} status abending with code 999 and return code 12; the four-character
 *       status rendering; and that no card number, verification value or social security number appears on any
 *       channel.</li>
 *   <li><strong>Coverage.</strong> JaCoCo enforces an 80 percent LINE floor on the merged bundle at
 *       {@code verify} with {@code haltOnFailure} and no exclusions for this package. This file is documentation
 *       only and contributes no executable lines.</li>
 *   <li><strong>Toolchain actually present, measured 3 August 2026</strong> at commit {@code 2e087c4}:
 *       OpenJDK and {@code javac} 25.0.3, Maven 3.9.11, Docker Engine 29.7.0 with {@code docker compose}
 *       v5.3.1. Readings, not requirements.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>Four fetch-size keys, one per reader, each declared in {@code src/main/resources/application.yml} at
 *       exactly the value the reader's own {@code DEFAULT_PAGE_SIZE} constant holds - {@code 100} - so
 *       declaring them changes no behaviour:
 *       {@code carddemo.batch.account-reader.page-size}, {@code carddemo.batch.card-reader.page-size},
 *       {@code carddemo.batch.card-cross-reference-reader.page-size} and
 *       {@code carddemo.batch.customer-reader.page-size}. They are declared because a key that is read at
 *       runtime but appears in no profile is invisible to whoever has to operate the job, which is the defect
 *       Rule 1 Clause E names.</li>
 *   <li><strong>These are fetch sizes, not pagination contracts.</strong> The parity page sizes of 7, 10 and 10
 *       belong to the online list screens and are unrelated. 100 comfortably exceeds the 50 rows each of the
 *       four fixtures in {@code app/data/ASCII/} holds, so a fixture-sized scan makes exactly one round
 *       trip.</li>
 *   <li>{@code carddemo.batch.chunk-size} governs commit granularity for the step, not the reader's fetch.</li>
 *   <li>{@code com.cardemo.parity} is {@code OFF} in every profile, in both {@code application.yml} and
 *       {@code logback-spring.xml}. Enabling one program's output is a per-run command-line decision, for
 *       example {@code --logging.level.com.cardemo.parity.CBACT01C=DEBUG}, and never a profile.</li>
 *   <li>Ordering is explicit and ascending on the primary key, because the legacy scan was a keyed sequential
 *       read. Restart cursors depend on that order, so it may not be left to the database.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: the scan abends at end of file instead of completing.</strong> Cause: a status of
 *       {@code '10'} was mapped to an exception. <em>Remediation:</em> treat it as loop termination; only
 *       {@code '00'} is success and only {@code '10'} is a clean end. <strong>Severity: Blocker</strong> -
 *       every run fails.</p></li>
 *   <li><p><strong>Symptom: a status line reads {@code FILE STATUS IS: 0023} or similar.</strong> Cause: the
 *       literal {@code NNNN} prefix was treated as a placeholder and substituted.
 *       <em>Remediation:</em> restore the prefix verbatim from its single owner and never prefix it twice.
 *       <strong>Severity: Medium</strong> - it is a log-comparison diff.</p></li>
 *   <li><p><strong>Symptom: a record image is one byte short or long against the copybook.</strong> Cause: a
 *       field width or an offset drifted. <em>Remediation:</em> restore the offsets from the copybook cited on
 *       the rendering method; the account layout sums to exactly 300.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a money field differs from the legacy image in its final character.</strong>
 *       Cause: <em>expected, and documented.</em> A COBOL {@code DISPLAY} of a signed field emits its storage
 *       bytes with the sign overpunched onto the last digit, so {@code +194.00} reads
 *       {@code 00000001940&#123;}. These readers emit a plain digit there and preserve the 300-character
 *       geometry intact. <em>Remediation:</em> none; if a byte-exact image is ever required, call the writers'
 *       codec rather than adding a third copy of it here.
 *       <strong>Severity: Low</strong>, and stated rather than hidden.</p></li>
 *   <li><p><strong>Symptom: a card number, verification value or social security number appears in a log
 *       line.</strong> Cause: a reader emitted a record image or a raw field rather than an identifier-only
 *       projection. <em>Remediation:</em> restore the projection. Rule 1 Clause D forbids it outright.
 *       <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: a restarted step re-emits rows it already emitted.</strong> Cause: the ordering
 *       became non-deterministic, or the cursor was not checkpointed. <em>Remediation:</em> keep the explicit
 *       ascending key order and store both the emitted count and the last identifier.
 *       <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: the scan is slow and issues one round trip per row.</strong> Cause: the fetch size
 *       was lowered to 1. <em>Remediation:</em> restore 100. <strong>Severity: Low</strong> - a performance
 *       matter only, and no service-level objective exists in the source to breach.</p></li>
 *   </ol>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li><strong>Read-only, provably.</strong> No reader here may write, update or delete anything. The source
 *       programs contain no such verb, so a mutation in this package has no source to justify it.</li>
 *   <li><strong>One private method per COBOL paragraph, never consolidated</strong>, each citing its source
 *       label.</li>
 *   <li><strong>No status decision is duplicated.</strong> The file-status-to-exception mapping and the
 *       four-character rendering have exactly one owner, and readers reference it rather than restating
 *       it.</li>
 *   <li><strong>A value never reaches the class logger.</strong> Counters, statuses and masked or rendered
 *       identifiers only; record images and financial fields go to the isolated parity tree or nowhere.</li>
 *   <li><strong>No {@code float} or {@code double} anywhere</strong>, including in a rendering helper.</li>
 *   <li><strong>No declaration in this package carries an intentional-no-op marker</strong>, the per-artefact
 *       form in which a retained-for-parity artefact is justified, so Rule 1 Clause B binds this package at full
 *       strength with no exemption.</li>
 *   <li><strong>The frozen corpus stays frozen.</strong> Nothing here reads {@code app/} at build or run
 *       time; those files are cited as evidence and must survive byte for byte.</li>
 *   </ul>
 */
package com.cardemo.batch.readers;
