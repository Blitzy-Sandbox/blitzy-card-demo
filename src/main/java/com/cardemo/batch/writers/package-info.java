/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.batch.writers
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Spring Batch item-writer layer)
 * Function    : The fixed-width output boundary. Three ItemWriters
 *               emitting the four legacy record geometries that Gate 1
 *               compares byte for byte: 350 for a transaction image,
 *               430 for a reject record (350 + 80), and 80 and 100 for
 *               the two statement outputs. GDG generation references
 *               become deterministic object keys over a versioned
 *               bucket.
 * Source      : app/cbl/CBTRN02C.cbl:L176-L182 (the reject record: a
 *               350-byte image plus a 4-digit reason and a 76-character
 *               description) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L442-L465 (2500-WRITE-REJECT-REC
 *               and 2900-WRITE-TRANSACTION-FILE) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L562-L579 (the write guard this
 *               package reproduces) @ 7756d89
 * Source      : app/jcl/POSTTRAN.jcl (LRECL=430 on the reject dataset,
 *               confirming 350 + 80) @ 7756d89
 * Source      : app/cpy/CVTRA05Y.cpy (350-byte transaction layout; the
 *               fourteen fields and their one-based offsets) @ 7756d89
 * Source      : app/jcl/CREASTMT.JCL:STEP040 (STMTFILE LRECL=80 and
 *               HTMLFILE LRECL=100; the 80-vs-100 mismatch against the
 *               pre-delete step is a logged legacy defect) @ 7756d89
 * Source      : app/cbl/CBSTM03A.CBL:L45 (FD-STMTFILE-REC PIC X(80)),
 *               :L149 (HTML-FIXED-LN PIC X(100)), :L293 (OPEN OUTPUT),
 *               :L339 (CLOSE) @ 7756d89
 * Source      : app/cbl/CBSTM03A.CBL:L558-L672 (customer name and the
 *               three address lines interpolated into markup with NO
 *               escaping) @ 7756d89
 * Source      : app/jcl/DEFGDGB.jcl, DALYREJS.jcl, REPTFILE.jcl (the
 *               GDG bases; note LIMIT(5) vs LIMIT(10) for TRANREPT)
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
 * The output boundary: three {@code ItemWriter} implementations that emit the batch stream's fixed-width
 * records.
 *
 * <p><strong>Record geometry is the contract of this package.</strong> Everywhere else in the tree a field width
 * is a validation concern; here it is the deliverable. The parity gate compares emitted bytes against the legacy
 * baseline, so a record that is 429 or 431 bytes rather than 430 is a failure even when every field value is
 * correct, and it shifts every record after it.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li>{@link com.cardemo.batch.writers.TransactionWriter} - {@code 2900-WRITE-TRANSACTION-FILE} at
 *       {@code app/cbl/CBTRN02C.cbl}. Inserts the rows and emits one <strong>350-byte</strong> image per
 *       transaction, concatenated with no separator, at the fourteen offsets
 *       {@code app/cpy/CVTRA05Y.cpy} declares: identifier 1-16, type 17-18, category 19-22, source 23-32,
 *       description 33-132, amount 133-143, merchant identifier 144-152, merchant name 153-202, merchant city
 *       203-252, merchant postal code 253-262, card number 263-278, originating timestamp 279-304, processing
 *       timestamp 305-330, filler 331-350.</li>
 *   <li>{@link com.cardemo.batch.writers.RejectWriter} - {@code 2500-WRITE-REJECT-REC}. Emits
 *       <strong>430 bytes</strong>: the same 350-byte image followed by an 80-byte trailer of a four-digit
 *       reason code and a 76-character description ({@code app/cbl/CBTRN02C.cbl:L176-L182}). The arithmetic is
 *       confirmed independently by {@code LRECL=430} on the reject dataset in {@code app/jcl/POSTTRAN.jcl}.</li>
 *   <li>{@link com.cardemo.batch.writers.StatementWriter} - the two outputs of
 *       {@code app/jcl/CREASTMT.JCL:STEP040}: a plain-text statement at <strong>80</strong> bytes a line
 *       ({@code FD-STMTFILE-REC PIC X(80)}, {@code app/cbl/CBSTM03A.CBL:L45}) and a markup statement at
 *       <strong>100</strong> ({@code HTML-FIXED-LN PIC X(100)}, {@code :L149}).</li>
 *   </ul>
 *
 * <h3>Where the markup is escaped, and how the delivery boundary is hardened as well</h3>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL:L558-L672} interpolates the customer name and the three address lines straight
 * into markup with <strong>no escaping anywhere</strong>, and every emitted line is a fixed
 * {@code PIC X(100)} record. The obvious objection to escaping is that it lengthens the interpolated fields and
 * so moves the offsets of every affected line, which would be a parity break - and that objection is why the
 * escape does not live in this package. It lives in
 * {@code com.cardemo.batch.processors.StatementProcessor}, the class that <em>composes</em> the record and can
 * therefore shorten the raw value until its escaped form fits the room the line already leaves. No entity is
 * ever split and every emitted line is still exactly 100 characters, so the escape and the record geometry
 * hold at the same time. <strong>The writers in this package apply no escape of their own, deliberately:</strong>
 * a second escaper would be a second policy to keep in step with the first, and a value escaped twice renders
 * its own entities as text. {@code StatementWriter} receives finished fixed-width lines and pads them, and its
 * fail-closed character guard is a backstop against a control byte, not an escaper.
 *
 * <p>Escaping removes the markup; it does not decide how the object is <em>served</em>, so the delivery
 * boundary is hardened in addition rather than instead. Both statement objects are stored with a
 * {@code Content-Disposition: attachment} header whose file name is derived from the object key and never from
 * record content, and with {@code Cache-Control: no-store}. The disposition is what removes the rendering
 * path - a browser pointed at the markup object saves it rather than executing it in the bucket's origin - and
 * it costs <strong>not one byte of the payload</strong>. The content type states the encoding the bytes are
 * actually in, {@code text/html; charset=ISO-8859-1} for the markup and {@code text/plain; charset=ISO-8859-1}
 * for the text, which closes the companion hole: an encoding sniffed rather than declared is a documented way
 * to smuggle markup past an escape that was correct in the encoding actually used. Withholding the media type
 * instead would buy nothing the disposition does not already buy, and would give up the charset.
 * {@code StatementWriterDeliveryTest} asserts every header, asserts that this package's writer is
 * byte-transparent so that the escape stays the sole property of the composer, and asserts that no controller
 * in the tree produces {@code text/html} - because an HTML-producing endpoint would serve statement content
 * from the application's own origin and reopen the door the disposition closes.
 *
 * <h3>Generation data groups become object keys</h3>
 *
 * <p>A {@code (+1)} relative reference becomes a new object under a strictly greater, zero-padded generation
 * segment, so the <strong>lexicographic order of the keys equals the numeric order of the generations</strong> -
 * the property a relative reference depends on. A {@code (0)} reference is served by reading the concrete key
 * the writer publishes into the step execution context, never by re-resolving "the latest generation", which is
 * what lets a {@code (+1)} written by an earlier step be read as {@code (+1)} by a later step in the same job.
 * Retention limits are <strong>documented rather than enforced</strong>: no lifecycle rule is created. Note the
 * one legacy inconsistency that had to be resolved rather than reproduced -
 * {@code app/jcl/DEFGDGB.jcl} declares {@code LIMIT(5)} for the report group while
 * {@code app/jcl/REPTFILE.jcl} declares {@code LIMIT(10)} - resolved in favour of 10 because a single value
 * must be chosen, and logged rather than silently absorbed.
 *
 * <h3>Why every writer here is step scoped</h3>
 *
 * <p>Each writer needs something no {@code ItemWriter} method argument carries: the job-instance identifier that
 * scopes an object key, the write count that orders objects within a step, and the execution context the created
 * key is published into. As singletons they obtained it from a {@code @BeforeStep} callback into a mutable
 * field, which is safe publication and <strong>not</strong> isolation - two overlapping executions shared one
 * field, so one job's objects could be keyed by another job's instance identifier, and for a relative generation
 * reference that means the wrong generation is read back downstream. They are now {@code @StepScope}, so each
 * execution receives its own instance and the callback populates a field confined to that execution. The
 * callback is declared by implementing {@code StepExecutionListener} rather than by annotating a method,
 * because step scope proxies by subclassing and a proxy presents its target's interfaces reliably while an
 * annotation on the target's method is found only if the framework unwraps the proxy.
 * {@code StatementWriter} is deliberately <strong>not {@code final}</strong>, because a CGLIB scoped proxy must
 * subclass it. {@code BatchWriterScopeIsolationTest} proves the isolation across nested and concurrent step
 * executions rather than asserting the annotations alone.
 *
 * <h2>How to run, build and test</h2>
 *
 * <ul>
 *   <li><strong>Build.</strong> {@code ./mvnw clean verify}. Maven 3.9.11 from the pinned wrapper, Java
 *       {@code [25,)} enforced, {@code release} 25, {@code -Xlint:all -Werror failOnWarning}.</li>
 *   <li><strong>Run.</strong> These are {@code ItemWriter} beans driven by a step; they are never invoked
 *       directly, and being step scoped they cannot be resolved outside a step context. They need both the
 *       database and the object-storage emulator up - {@code docker compose up -d} - and the environment loaded
 *       with {@code set -a; . ./.env; set +a}.</li>
 *   <li><strong>Test.</strong> {@code StatementWriterDeliveryTest} covers the delivery contract and the payload
 *       parity of the statement outputs; {@code BatchWriterScopeIsolationTest} covers the scoping and
 *       per-execution key derivation of both the transaction and statement writers.
 *       <strong>Not available, measured 3 August 2026:</strong> no test class covers
 *       {@code RejectWriter}, and no <em>geometry</em> suite covers the 350-byte transaction image, so no
 *       coverage figure quoted anywhere is evidence about either. The strongest available assertion, and the one
 *       owed, is a byte-identical round trip of every record of {@code app/data/ASCII/dailytran.txt}, whose
 *       staging layout {@code app/cpy/CVTRA06Y.cpy} is field-for-field the same 350-byte geometry as
 *       {@code app/cpy/CVTRA05Y.cpy}. Note when writing it that the fixture is 105,300 bytes of 300 records at a
 *       <strong>351</strong>-byte stride - 350 data bytes plus a line feed - so a 350-byte read misaligns after
 *       the first record.</li>
 *   <li><strong>Coverage.</strong> JaCoCo enforces an 80 percent LINE floor on the merged bundle at
 *       {@code verify} with {@code haltOnFailure} and no exclusions for this package. This file is documentation
 *       only and contributes no executable lines.</li>
 *   <li><strong>Toolchain actually present, measured 3 August 2026</strong> at commit {@code 2e087c4}:
 *       OpenJDK and {@code javac} 25.0.3, Maven 3.9.11, Docker Engine 29.7.0 with {@code docker compose}
 *       v5.3.1. Readings, not requirements - the container runtime is what provides the object-storage
 *       emulator.</li>
 *   </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>{@code carddemo.aws.s3.batch-output-bucket} - <strong>required, never defaulted</strong>. A blank value
 *       fails at startup rather than at the first write.</li>
 *   <li>{@code carddemo.aws.s3.transaction-object-prefix}, default {@code transact} - the base-name segment
 *       every transaction object key starts with. Declared in {@code application.yml} at exactly the in-code
 *       default, because a key read at runtime but absent from every profile is invisible to whoever operates
 *       the job.</li>
 *   <li>{@code carddemo.aws.s3.statements-bucket}, default {@code carddemo-statements} - receives both
 *       statement objects, keyed by account and month under a zero-padded generation segment.</li>
 *   <li>The AWS endpoint override and its static placeholder credentials, declared with no defaults in the
 *       <strong>base</strong> profile so every profile inherits the indirection.
 *       {@code com.cardemo.config.AwsConfig} parses the endpoint and refuses any non-allowlisted host
 *       <em>before</em> any client bean exists, so no writer here can reach a live endpoint.</li>
 *   <li>The fixed-width charset is named explicitly, and the content length is declared to the storage client
 *       from the encoded byte count, so a stored object's size is an exact multiple of its record width. Neither
 *       may fall back to a platform default.</li>
 *   <li>{@code carddemo.batch.chunk-size} bounds how many record images are held at once - chunk size
 *       multiplied by the record width - so the whole output is never materialised.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: a stored object's size is not an exact multiple of its record width.</strong>
 *       Cause: a field width drifted, a separator was introduced, or the charset was left to the platform
 *       default. <em>Remediation:</em> restore the offsets from the cited copybook and name the charset
 *       explicitly. <strong>Severity: Blocker</strong> - every record after the first bad one is
 *       misaligned.</p></li>
 *   <li><p><strong>Symptom: the reject stream is 350 or 80 bytes a record rather than 430.</strong> Cause: the
 *       trailer was omitted, or the image and trailer were written as separate records.
 *       <em>Remediation:</em> emit one 430-byte record: 350 data bytes, then a four-digit reason, then a
 *       76-character description. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: statement markup renders in a browser.</strong> Cause: the {@code attachment}
 *       disposition was dropped, or a controller began serving the object from the application's own origin.
 *       <em>Remediation:</em> restore the disposition and keep the controller surface free of
 *       {@code text/html}. Do <strong>not</strong> respond by adding an escaper here: the escape belongs to
 *       {@code com.cardemo.batch.processors.StatementProcessor}, which fits the escaped form to the room the
 *       line leaves, and a value escaped twice renders its own entities as text.
 *       <strong>Severity: Medium</strong>, and it is the exposure the delivery decision exists to close.</p></li>
 *   <li><p><strong>Symptom: a statement object carries no charset, or the wrong one.</strong> Cause: the
 *       content type was reduced to a bare media type or to {@code application/octet-stream}.
 *       <em>Remediation:</em> restore {@code text/html; charset=ISO-8859-1} and
 *       {@code text/plain; charset=ISO-8859-1}. An omitted charset invites a consumer to sniff one, and a
 *       sniffed encoding is how markup gets past an escape that was correct in the encoding actually used.
 *       <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: object keys from two concurrent runs share a generation prefix.</strong> Cause: a
 *       {@code @StepScope} annotation was removed, or per-run state moved back to a mutable field.
 *       <em>Remediation:</em> restore the scope. <strong>Severity: High</strong> - a relative generation
 *       reference then resolves to another job's output.</p></li>
 *   <li><p><strong>Symptom: a writer fails immediately with an abend naming no step context.</strong> Cause: it
 *       was constructed outside a step, so no job instance exists to key the object on.
 *       <em>Remediation:</em> register it on a step, or in a unit test pass a {@code StepExecution} built with a
 *       job execution and a job instance to the constructor. <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: a duplicate-identifier failure on a re-run.</strong> Cause: <em>designed</em>. The
 *       identifier generation the source uses is a descending browse of the maximum key plus one, which is
 *       inherently racy and is preserved as such; a re-used date parameter produces colliding generated
 *       identifiers. <em>Remediation:</em> do not retry and do not merge over the colliding row - re-drive the
 *       job with an unused date parameter. Substituting a database sequence would change generated values and
 *       break the baseline comparison. <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: an object exists but the database row does not.</strong> Cause: the caller's
 *       transaction rolled back after emission. Object storage is not transactional and this package declares no
 *       transaction boundary of its own - it participates in the caller's. <em>Remediation:</em> none needed:
 *       keys are unique per job instance and ordinal, so such an orphan is identifiable and is superseded by the
 *       next run rather than corrupting it. <strong>Severity: Low</strong>, and stated rather than
 *       hidden.</p></li>
 *   <li><p><strong>Symptom: a card number, an amount or a merchant detail appears in a message or a log
 *       line.</strong> Cause: a diagnostic quoted a value. <em>Remediation:</em> name the COBOL field and the
 *       widths involved, never the value. Two of these fields are the card number and the transaction
 *       identifier. <strong>Severity: Blocker.</strong></p></li>
 *   </ol>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li><strong>Geometry before everything.</strong> 350, 430, 133, 100 and 80 are parity contracts, not
 *       maxima. A record that overflows its declared width is refused rather than truncated - which is a
 *       different decision from field-level truncation, where a value longer than its picture clause <em>is</em>
 *       truncated, exactly as a COBOL {@code MOVE} would.</li>
 *   <li><strong>The two encoders are held separately on purpose.</strong> The reject record embeds the
 *       transaction image, and extracting a shared codec would couple two layouts whose only relationship is
 *       that one contains the other - so a change to the reject trailer could silently alter the transaction
 *       geometry the parity gate compares. Each is verified against its own frozen fixture.</li>
 *   <li><strong>No transaction annotation.</strong> These writers participate in the caller's boundary; the
 *       service layer owns the single unit of work. That collapsing of the source's three independent commits is
 *       a <em>labelled deviation</em> rather than parity.</li>
 *   <li><strong>Step scoped, so lifecycle state is confined rather than shared.</strong> No
 *       {@code @BeforeStep} annotation and no {@code volatile} slot: the step execution arrives through the
 *       {@code StepExecutionListener} interface into a field one execution owns. {@code TransactionWriter}
 *       has exactly one such field and no other mutable state; {@code StatementWriter} accumulates one
 *       statement at a time and so has more, all of them equally confined.</li>
 *   <li><strong>No {@code float} or {@code double} on any financial path</strong>, and no absolute-value
 *       normalisation: a negative amount renders with a negative overpunch.</li>
 *   <li><strong>No declaration in this package carries an intentional-no-op marker</strong>, the per-artefact
 *       form in which a retained-for-parity artefact is justified, so Rule 1 Clause B binds this package at full
 *       strength with no exemption.</li>
 *   <li><strong>The frozen corpus stays frozen.</strong> Nothing here reads {@code app/} at build or run
 *       time; those files are cited as evidence and must survive byte for byte.</li>
 *   </ul>
 */
package com.cardemo.batch.writers;
