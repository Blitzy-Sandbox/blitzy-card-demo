/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.service.shared
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Service layer (shared collaborators))
 * Function    : The four collapse points. Three static COBOL linkages
 *               and one data copybook each become exactly ONE injected
 *               bean here, so that the date service, the file-access
 *               matrix, the file-status decision and the lookup tables
 *               live in one place each rather than at every call site.
 * Source      : app/cbl/CSUTLDTC.cbl (157 lines, 2 paragraphs; CALL 'CSUTLDTC' and the LE CEEDAYS date service) @
 *               7756d89
 * Source      : app/cpy/CSUTLDPY.cpy (375 lines, 14 paragraphs) and app/cpy/CSUTLDWY.cpy (89 lines, work area) @
 *               7756d89
 * Source      : app/cbl/CBSTM03B.CBL (230 lines; the four-file by six-operation matrix, 12 of 24 cells implemented) @
 *               7756d89
 * Source      : app/cbl/CBSTM03A.CBL:L347-L351 (CALL 'CBSTM03B' USING WS-M03B-AREA; '00' OR '04' both accepted) @
 *               7756d89
 * Source      : app/cbl/CBSTM03B.CBL:L30-L53 (FILE-CONTROL), :L83-L97 (the four WORKING-STORAGE FILE STATUS groups) @
 *               7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L142-L144 (the universal I/O guard idiom), :L714-L731 (9910-DISPLAY-IO-STATUS) @
 *               7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L467-L500 (2700-UPDATE-TCATBAL - '00' OR '23' both accepted) @ 7756d89
 * Source      : app/cpy/CSLKPCDY.cpy (1,318 lines, data only; five 88-level lookup tables) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L602, :L2296-L2298, :L2493-L2495, :L2535-L2542 (the lookup call sites) @ 7756d89
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
 * The shared services: four beans, each of which exists because one thing in the source was reachable from many
 * places at once.
 *
 * <h2>What it does</h2>
 *
 * <p>Every class here is a <strong>collapse point</strong>. The source reached each of these capabilities through
 * static linkage or a copied data table, which meant the capability had no single home; here each has exactly one,
 * and every call site injects it.
 *
 * <ul>
 *   <li>{@link com.cardemo.service.shared.DateValidationService} - <strong>three</strong> source artefacts become
 *       one bean: {@code CALL 'CSUTLDTC'} ({@code app/cbl/CSUTLDTC.cbl}, 157 lines), plus its two work-area
 *       copybooks {@code app/cpy/CSUTLDPY.cpy} (375 lines, 14 paragraphs) and {@code app/cpy/CSUTLDWY.cpy}. It
 *       replaces the Language Environment {@code CEEDAYS} service with {@code java.time}, and its contract is the
 *       source's own parameter block: a date, a format, and a result group of severity code, filler and message
 *       number. <strong>Validation outcomes must match, not merely parsing</strong> - a date {@code java.time} can
 *       parse but the legacy utility rejected must still be rejected.</li>
 *   <li>{@link com.cardemo.service.shared.FileService} - {@code app/cbl/CBSTM03B.CBL}, 230 lines, reached only by
 *       {@code CALL 'CBSTM03B' USING WS-M03B-AREA} from {@code app/cbl/CBSTM03A.CBL:L347-L351}. The shared area is a
 *       DD name, a single-character operation, a two-character return code, a key, a key length and a thousand-byte
 *       payload, and the implemented surface is <strong>12 of the 24</strong> cells of a four-dataset by
 *       six-operation matrix: every dataset implements open and close, plus exactly one read form chosen by its
 *       access mode. The eight absent cells are absent here for the same reason they are absent there.</li>
 *   <li>{@link com.cardemo.service.shared.FileStatusMapper} - the single owner of every file-status decision in the
 *       tree, replacing an idiom that appears at every I/O site in the batch corpus.</li>
 *   <li>{@link com.cardemo.service.shared.ValidationLookupService} - {@code app/cpy/CSLKPCDY.cpy}, 1,318 lines of
 *       88-level tables, becomes one service over <strong>three classpath JSON resources</strong> rather than a
 *       generated constants class that would run to over a thousand lines while adding nothing to correctness.
 *       Membership is preserved exactly.</li>
 *   </ul>
 *
 * <h3>Why the status decision has exactly one owner</h3>
 *
 * <p>Every open, read, write, rewrite and close in every batch program follows one shape
 * ({@code app/cbl/CBTRN02C.cbl:L142-L144}): a signed binary result field is moved to 8, the verb runs, the field
 * becomes 0 when the status is {@code '00'} and 12 otherwise, and a non-zero field displays a message, renders the
 * status and abends. Recognising this as <em>one</em> idiom rather than hundreds of individual checks is exactly why
 * a single central mapper is the right design and why no call site re-decides.
 *
 * <p>The mapping is: {@code '00'} continue; {@code '10'} end of file, which is loop termination and
 * <strong>not</strong> an error; {@code '23'} record not found; {@code '22'} duplicate key; {@code '35'} file
 * unavailable; the {@code '9x'} family a file-access failure carrying the four-character expanded status; and
 * anything else fatal, with abend code 999 and return code 12.
 *
 * <p><strong>Three sites are exceptions, and a blanket rule breaks all three.</strong> A record-not-found status is
 * an accepted control path - not an error - at the category-balance upsert
 * ({@code app/cbl/CBTRN02C.cbl:L467-L500}, which accepts {@code '00'} <em>or</em> {@code '23'} before dispatching
 * to create or rewrite) and at the disclosure-group rate lookup (which accepts either before substituting the
 * literal default group and retrying, where the retry accepts only success, so a missing default row abends). And
 * at every {@code CBSTM03B} call site a return code of {@code '04'} is accepted alongside {@code '00'}
 * ({@code app/cbl/CBSTM03A.CBL:L347-L351}). Mapping not-found uniformly to an exception abends the first two paths;
 * refusing {@code '04'} abends the third.
 *
 * <p>The four-character rendering is itself a contract. {@code 9910-DISPLAY-IO-STATUS}
 * ({@code app/cbl/CBTRN02C.cbl:L714-L731}) emits exactly four characters: when the status is non-numeric or its
 * first byte is {@code '9'}, that byte is copied through and the second expanded from a binary field into three
 * digits; otherwise the field is four zeros with the two status characters at positions three and four. So a status
 * of {@code '23'} renders as {@code FILE STATUS IS: NNNN0023}, in which the four {@code N} characters are
 * <strong>literal and not a placeholder</strong>. The prefix is declared exactly once, in
 * {@code com.cardemo.model.enums.FileStatus}, and is never prefixed twice.
 *
 * <h3>The one retained mutable state in this package, and why it is scoped to a job</h3>
 *
 * <p>{@code FileService} keeps one status register per DD, carrying the four WORKING-STORAGE {@code FILE STATUS}
 * groups of {@code app/cbl/CBSTM03B.CBL:L83-L97}. That state is retained <strong>deliberately</strong>, because a
 * COBOL subprogram's WORKING-STORAGE survives from one call to the next and that survival is observable: a call
 * performing no I/O leaves the previous call's status in place. A per-invocation reset would silently repair it.
 *
 * <p>The <em>scope</em> of the survival is proved from the source rather than assumed. {@code CBSTM03B} is a
 * separately compiled subprogram with no {@code INITIAL} attribute on its {@code PROGRAM-ID}, so its
 * WORKING-STORAGE persists for the life of the <strong>run unit</strong> - one enclosing job step - and is released
 * with it. A process-wide singleton would therefore be <em>more</em> shared than the source: two concurrent
 * statement jobs would read each other's residual statuses, firing the behaviour across job boundaries where the
 * source cannot produce it. The bean is consequently <strong>{@code @JobScope}</strong>, which reproduces the
 * source's own lifetime exactly - survival within one run, isolation between runs. This is an explicit exception to
 * Rule 1 Clause B's preference against retained mutable state, justified by the parity mandate, and it is
 * <strong>owed an entry in {@code DECISION_LOG.md}</strong>, which is authored at the repository root.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>The three lookup resources live at {@code src/main/resources/validation/nanpa-area-codes.json},
 *       {@code us-state-codes.json} and {@code state-zip-prefixes.json}. They are <strong>data, not
 *       configuration</strong>: membership comes from {@code app/cpy/CSLKPCDY.cpy} and may not be extended or
 *       trimmed to make a test pass.</li>
 *   <li>{@code FileService} is {@code @JobScope} and {@code DateValidationService},
 *       {@code ValidationLookupService} and {@code FileStatusMapper} are ordinary singletons - the first because
 *       it retains per-run state, the other three because they retain none.</li>
 *   <li>{@code DateValidationService} takes no {@code Clock} for validation, which is deliberate: validity is a
 *       property of the value, not of the current instant. Where a caller needs "today" it injects the
 *       {@code Clock} itself, published once as {@code Clock.systemDefaultZone()}.</li>
 *   <li>No property in this package has a default that would let a missing lookup resource pass silently: an absent
 *       or malformed resource fails at startup, not at the first validation.</li>
 *   <li>{@code carddemo.decimal.*} governs the shared rounding contract that callers of these services rely on.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: the posting job abends on a record whose category balance does not yet exist.</strong>
 *       Cause: not-found was mapped uniformly to an exception. <em>Remediation:</em> accept {@code '00'} or
 *       {@code '23'} at the two named sites. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: the statement job abends on a normal read.</strong> Cause: a return code of
 *       {@code '04'} was refused. <em>Remediation:</em> accept {@code '00'} or {@code '04'} at every
 *       {@code CBSTM03B} call site. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: a scan abends at end of file.</strong> Cause: {@code '10'} was mapped to an exception.
 *       <em>Remediation:</em> it is loop termination. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: a status line reads {@code FILE STATUS IS: 0023}.</strong> Cause: the literal
 *       {@code NNNN} was treated as a placeholder and substituted. <em>Remediation:</em> restore the prefix verbatim
 *       from its single owner. <strong>Severity: Medium</strong> - a log-comparison diff.</p></li>
 *   <li><p><strong>Symptom: a date the legacy utility rejected is accepted, or vice versa.</strong> Cause: the
 *       service returns a parse result rather than the source's severity outcome. <em>Remediation:</em> return the
 *       parameter block's severity and message number, and honour them. <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a valid area code, state or postal prefix is rejected.</strong> Cause: a lookup resource
 *       drifted from the copybook. <em>Remediation:</em> restore membership from {@code app/cpy/CSLKPCDY.cpy}. Note
 *       that the state predicate and the state-plus-postal-prefix predicate legitimately disagree on some prefixes -
 *       that disagreement is in the source and must not be reconciled.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: two concurrent statement jobs see each other's residual file statuses.</strong> Cause:
 *       the {@code @JobScope} on {@code FileService} was removed, making it a process singleton.
 *       <em>Remediation:</em> restore it. <strong>Severity: High</strong> - it fires a legacy behaviour across a
 *       boundary the source cannot produce it across.</p></li>
 *   <li><p><strong>Symptom: {@code FileService} cannot be resolved outside a job.</strong> Cause: correct - it is
 *       job scoped. <em>Remediation:</em> resolve it inside a job, or in a unit test construct it directly.
 *       <strong>Severity: Low.</strong></p></li>
 *   <li><p><strong>Symptom: an unimplemented file-and-operation combination is invoked.</strong> Cause: a caller
 *       assumed all 24 cells exist. <em>Remediation:</em> only 12 do; the caller is wrong, not the matrix.
 *       <strong>Severity: Medium.</strong></p></li>
 *   </ol>
 *
 * <h2>How to run, build and test</h2>
 *
 * <ul>
 *   <li><strong>Build.</strong> {@code ./mvnw clean verify} from the repository root. The wrapper pins Maven
 *       3.9.11, {@code maven-enforcer-plugin:3.5.0} floors the toolchain at Java {@code [25,)}, compilation is
 *       at {@code release} 25 with no preview features, and {@code -Xlint:all}, {@code -Werror} and
 *       {@code failOnWarning} together make any warning attributed to this package a
 *       <strong>build failure</strong>.</li>
 *   <li><strong>Run.</strong> These are Spring beans and are never invoked directly; a controller or a batch
 *       step calls them. A manual exercise needs the compose stack up - {@code docker compose up -d} - and the
 *       environment scoped to that one command rather than exported into the shell:
 *       {@code ( set -a; . ./.env; set +a; <command> )}. The parentheses confine the values to the subshell
 *       instead of leaving every later child inheriting them. {@code JWT_SIGNING_KEY} has no default and
 *       startup fails without it by design.</li>
 *   <li><strong>Test.</strong> Tests belong in {@code src/test/java/com/cardemo/unit/service} and
 *       {@code src/test/java/com/cardemo/unit/validation}. {@code DateValidationServiceTest},
 *       {@code DateValidationServiceSweepTest}, {@code DateValidationServiceGuardPathTest},
 *       {@code DateEditContractTest}, {@code DateWorkAreaBoundaryContractTest},
 *       {@code LanguageEnvironmentDateContractTest}, {@code FileServiceTest}, {@code FileStatusMapperTest},
 *       {@code FileStatusMapperCoverageTest} and two {@code ValidationLookupServiceTest} classes all exist - this is
 *       the best-covered package in the tree, which is appropriate for four beans that every other package depends
 *       on. The assertions that matter: the three accepted-status exceptions; the four-character rendering including
 *       its literal prefix; end of file not being an error; validation outcomes matching the legacy utility rather
 *       than merely parsing; exact lookup-table membership including the two predicates that legitimately disagree;
 *       and status survival within one job with isolation between jobs.</li>
 *   <li><strong>Coverage.</strong> JaCoCo enforces an <strong>80 percent LINE</strong> floor on the merged
 *       bundle at {@code verify} with {@code haltOnFailure} and no exclusions for this package. Coverage must
 *       come from assertions on behaviour; exercising a method to move the number is not acceptable. This file
 *       is documentation only and contributes no executable lines.</li>
 *   <li><strong>Toolchain actually present, measured 3 August 2026</strong> at commit {@code 2e087c4}: OpenJDK
 *       and {@code javac} 25.0.3, Apache Maven 3.9.11 from the pinned wrapper, Docker Engine 29.7.0 with
 *       {@code docker compose} v5.3.1. These are readings, not requirements - re-measure after a host change
 *       rather than quoting them.</li>
 *   </ul>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li><strong>One owner, always.</strong> Each capability here exists exactly once. A second date validator, a
 *       second status mapper or a per-call-site copy of a lookup table defeats the entire purpose of this
 *       package.</li>
 *   <li><strong>One documented exception to the no-retained-state preference:</strong> {@code FileService}'s status
 *       registers, scoped to a job, justified at their own declaration with the source proof, and owed an entry in
 *       the {@code DECISION_LOG.md}. Nothing else here retains anything.</li>
 *   <li><strong>One private method per COBOL paragraph, never consolidated</strong>, each carrying a Javadoc
 *       citation to its source label. That correspondence is what makes the scope-coverage gate provable by
 *       inspection rather than by assertion.</li>
 *   <li><strong>No HTTP concern and no persistence concern.</strong> A service decides; the controller
 *       translates and the repository stores. A service that built a response status or wrote SQL would be in
 *       the wrong layer.</li>
 *   <li><strong>No {@code float} or {@code double} on any financial path</strong>, and equality by
 *       {@code compareTo} rather than {@code equals}, because {@code equals} distinguishes {@code 1.0} from
 *       {@code 1.00}.</li>
 *   <li><strong>Every file status is translated by its single owner</strong>,
 *       {@code com.cardemo.service.shared.FileStatusMapper}, and never re-decided here. Nothing is swallowed
 *       and every exception preserves its cause.</li>
 *   <li><strong>No value in a message or a log line.</strong> A diagnostic names the COBOL field and the
 *       widths involved, never the content - Rule 1 Clause D, and the masking rules in
 *       {@code logback-spring.xml} cannot reach an unlabelled value.</li>
 *   <li><strong>No declaration in this package carries an intentional-no-op marker</strong>, the per-artefact
 *       form in which a retained-for-parity artefact is justified, so Rule 1 Clause B binds this package at
 *       full strength with no exemption.</li>
 *   <li><strong>The frozen corpus stays frozen.</strong> Nothing here reads {@code app/} at build or run time;
 *       those files are cited as evidence and must survive byte for byte.</li>
 *   </ul>
 */
package com.cardemo.service.shared;
