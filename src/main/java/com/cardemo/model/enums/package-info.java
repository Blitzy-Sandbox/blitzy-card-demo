/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.model.enums
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 * Function    : Documents com.cardemo.model.enums - typed COBOL 88-levels,
 *               FILE STATUS and reject codes.
 * Source      : app/cpy/COCOM01Y.cpy:L26-L28 @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L131-L144,L385-L419,L556-L558,L714-L727 @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L484 @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl:L222 @ 7756d89
 * Source      : app/cpy/CVTRA05Y.cpy:L8 @ 7756d89
 * Source      : app/cbl/CBSTM03A.CBL:L736,L748 @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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
 * Typed replacements for the COBOL literals and 88-level condition names that the frozen CardDemo corpus tests
 * by value: user class, file status, transaction source and reject reason.
 *
 * <h2>What it does</h2>
 *
 * <p>Four enumerations, and nothing else besides this file. Each closes a set that the source leaves open as
 * bare literals, so a value outside the set becomes unrepresentable rather than merely unusual.
 *
 * <ul>
 *   <li>{@link UserType} - two constants, {@code 'A'} and {@code 'U'}, from the two 88-levels declared on
 *       {@code CDEMO-USER-TYPE} at {@code app/cpy/COCOM01Y.cpy:L26-L28}. It drives role-based authorisation
 *       and the main-menu eligibility gate, and performs no authorisation itself.</li>
 *   <li>{@link FileStatus} - seven constants: the six exact statuses the batch corpus tests, {@code '00'},
 *       {@code '04'}, {@code '10'}, {@code '22'}, {@code '23'} and {@code '35'}, plus the {@code '9x'} family
 *       as a single {@code IO_ERROR} member. It also owns the four-character {@code IO-STATUS-04} rendering of
 *       {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727}.</li>
 *   <li>{@link TransactionSource} - two constants, the only {@code TRAN-SOURCE} literals assigned anywhere in
 *       the corpus: {@code System} at {@code app/cbl/CBACT04C.cbl:L484} and {@code POS TERM} at
 *       {@code app/cbl/COBIL00C.cbl:L222}. It deliberately does <strong>not</strong> type the persisted
 *       column: {@code TRAN-SOURCE} is {@code PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L8} and holds free
 *       text, so the entity keeps a {@code String} and this enumeration names only the two values written.</li>
 *   <li>{@link RejectCode} - exactly five constants, 100, 101, 102, 103 and 109, each carrying verbatim the
 *       description literal the posting program moves into the reject trailer. The "no reject" state is not a
 *       constant: it is the numeric {@code 0} written by the per-iteration reset at
 *       {@code app/cbl/CBTRN02C.cbl:L208-L209}.</li>
 *   </ul>
 *
 * <p>Every constant is immutable, every lookup is a total function returning {@code Optional} or throwing
 * {@code IllegalArgumentException} that names the offending value, and nothing here performs I/O, reads
 * configuration or logs. Imports are inward-only: nothing in this package references
 * {@code com.cardemo.exception} or any other outward package, which is what keeps a leaf package acyclic and
 * keeps the "reject codes are outcomes, not exceptions" rule true in the type system itself.
 *
 * <h2>The four parity contracts this package carries</h2>
 *
 * <ul>
 *   <li><strong>Status classification is not context-free.</strong> {@code '23'} is an accepted control path
 *       at {@code app/cbl/CBTRN02C.cbl:481} and {@code app/cbl/CBACT04C.cbl:422}, and {@code '04'} is accepted
 *       as success at every file-service call site from {@code app/cbl/CBSTM03A.CBL:736} onwards, so a caller
 *       must classify with its call site in mind. Everywhere else an unrecognised status abends, with code 999
 *       and process return code 12 ({@code app/cbl/CBTRN02C.cbl:707-711}).</li>
 *   <li><strong>The reject record geometry is fixed</strong> by {@code app/cbl/CBTRN02C.cbl:L176-L182}: a
 *       {@code PIC X(350)} transaction image plus a {@code PIC X(80)} trailer that decomposes into
 *       {@code PIC 9(04)} for the reason code and {@code PIC X(76)} for its description. The code renders as
 *       four zero-padded digits, the description as 76 space-padded characters, and the record is
 *       <strong>430 bytes</strong>. All four numbers are parity contracts and are published as constants
 *       derived from one another where the source derives them.</li>
 *   <li><strong>103 overwrites 102.</strong> At {@code app/cbl/CBTRN02C.cbl:L403-L420} the over-limit and
 *       expiry tests are two sequential, unguarded {@code IF} blocks with no alternative branch and no early
 *       exit between them, so when both conditions fail the second assignment wins and a single reject record
 *       bearing 103 is written. Guarding the second test, or emitting two records, diverges from the
 *       source.</li>
 *   <li><strong>109 is reachable but never consumed.</strong> It is assigned on the account-rewrite failure
 *       path at {@code :L556-L558}, but that paragraph runs only on the already-validated posting path, so no
 *       reject record is written, the reject count is not incremented, and the value is cleared by the reset at
 *       {@code :L208-L209}. The constant is retained deliberately, and marked as such at its declaration,
 *       because the assignment is real code on a reachable path. Its text duplicating 101's is likewise
 *       present in the source.</li>
 *   </ul>
 *
 * <p>The batch exit-code rule is equally narrow: at {@code app/cbl/CBTRN02C.cbl:L229-L231} the program moves 4
 * into the return code <strong>if and only if the reject count exceeds zero</strong>. There is no threshold and
 * no per-code weighting.
 *
 * <h2>File status {@code '35'} is specification-derived, not corpus-derived</h2>
 *
 * <p>The frozen corpus contains <strong>no literal {@code '35'} comparison and no {@code DFHRESP(NOTOPEN)}
 * handler anywhere under {@code app/}</strong>. The census of response conditions actually present is
 * {@code DFHRESP(NORMAL)} 43 times, {@code DFHRESP(NOTFND)} 23, {@code DFHRESP(ENDFILE)} 8,
 * {@code DFHRESP(DUPREC)} 7, {@code DFHRESP(DUPKEY)} 3 and {@code DFHRESP(NOTOPEN)} <strong>zero</strong>.
 * The constant completes the mandated status taxonomy and gives
 * {@code com.cardemo.exception.FileUnavailableException} a value to be raised from; a file-unavailable path
 * that no legacy program exercises cannot be parity-tested against a baseline, so its behaviour rests on the
 * design mapping alone. That is stated here rather than presented as though it had the same provenance as
 * {@code '00'} or {@code '23'}.
 *
 * <p>{@code '22'} is not in the same position even though it too has no literal comparison: it is grounded
 * through the CICS duplicate conditions, canonically at {@code app/cbl/COUSR01C.cbl:L260-L261}, where
 * {@code WHEN DFHRESP(DUPKEY)} and {@code WHEN DFHRESP(DUPREC)} fall through to one duplicate-key handler. It
 * is corpus-derived by condition name rather than by literal.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>There is nothing to run: no {@code main} method, no Spring bean, no scheduled task, no endpoint. The
 * package is compiled as part of the single CardDemo module and exercised through its consumers.
 * {@code ./mvnw -B -ntp clean verify} from the repository root compiles it under {@code -Xlint:all -Werror}
 * and enforces the project coverage floor. Unit tests live in {@code src/test/java/com/cardemo/unit/model} and
 * assert the exact constant sets, the verbatim reject literals, the four-character status rendering including
 * its {@code NNNN} form, and the round trip of every lookup.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p><strong>There is no configuration at all</strong> - no property, environment variable, system property,
 * profile, {@code @ConfigurationProperties} class, {@code @Value} injection or {@code Environment} lookup.
 * Every constant is compile-time and transcribed from the frozen corpus, so there are no defaults to override
 * and deliberately no mechanism to override them: a reject description or status code that configuration could
 * change would make the byte-level comparison against the legacy baseline meaningless, because one build could
 * then produce two different reject records. The deliberate absences are the defaults - no static mutable
 * field, no annotation in this file, no added dependency beyond {@code java.util}, and no
 * {@code Serializable} (enum constants serialise by identity through the language, and declaring the interface
 * would raise the {@code serial} lint over a missing {@code serialVersionUID}, which the build escalates to an
 * error).
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A working batch path abends on a missing row</strong> - {@code '23'} was mapped to an
 *       exception unconditionally. Three sites accept it, or {@code '04'}, as success.</li>
 *   <li><strong>Reject output stops matching the baseline</strong> - a description literal was re-cased,
 *       re-worded or repunctuated. The usual mistakes are expanding {@code ACCT} to {@code ACCOUNT} in 103 and
 *       "fixing" 109's text because it duplicates 101's. Diff each string character by character against its
 *       cited source lines.</li>
 *   <li><strong>Two reject records for one input record</strong>, or a both-failed record bearing 102 - the
 *       over-limit and expiry tests were guarded apart. Reproduce the overwrite; do not repair it.</li>
 *   <li><strong>Reject code 109 never appears in a reject file</strong> - it cannot, by the contract above. It
 *       exists for fidelity, and deleting it as dead code breaks paragraph coverage of {@code CBTRN02C}.</li>
 *   <li><strong>A status renders as three or five characters</strong> - the field is {@code PIC 9} followed by
 *       {@code PIC 999} at {@code app/cbl/CBTRN02C.cbl:L138-L140}, so the rendering is exactly four
 *       characters and {@code '23'} renders {@code 0023}. The {@code 9x} family renders its second byte as
 *       three expanded digits, not as the raw character.</li>
 *   <li><strong>Someone "fixes" the {@code FILE STATUS IS: NNNN} prefix</strong> - COBOL {@code DISPLAY}
 *       concatenates operands with no separator, so at {@code app/cbl/CBTRN02C.cbl:L721} and {@code :L725} a
 *       status of {@code '23'} emits {@code FILE STATUS IS: NNNN0023}. The stray {@code NNNN} is a preserved
 *       legacy quirk, not an unsubstituted placeholder.</li>
 *   <li><strong>A third {@link TransactionSource} constant appears</strong>, almost always {@code OPERATOR} -
 *       the enum then looks like the column's full domain, which invites an enum-typed persistence mapping
 *       that mangles real fixture data. The whitespace-tolerant census
 *       {@code grep -rnE "TO +TRAN-SOURCE" app/cbl/} returns four sites, only two of them literals; a
 *       single-space {@code grep} returns three and is actively misleading, because
 *       {@code app/cbl/CBTRN02C.cbl:L428} separates the operands with several spaces.</li>
 *   <li><strong>A third {@link UserType} constant appears</strong>, typically {@code UNKNOWN} or
 *       {@code NONE} - an "unknown" role then leaks into authorisation decisions. An unrecognised code is
 *       handled at the boundary, never by inventing a constant to absorb it.</li>
 *   <li><strong>An unexpected source value fails to bind</strong> - the persisted column is free text. Bind
 *       the raw {@code String} and resolve it through the lookup, which returns empty rather than
 *       throwing.</li>
 *   <li><strong>A static mutable field is introduced</strong> - tests become order-dependent and concurrent
 *       batch steps interfere. The tempting precedent, the interest job's run-sequential suffix counter at
 *       {@code app/cbl/CBACT04C.cbl:L474}, is job-scoped state and belongs in the batch layer.</li>
 *   </ul>
 *
 * <h2>Package level constraints</h2>
 *
 * <p>This package contains exactly <strong>five</strong> {@code .java} files - {@code UserType.java},
 * {@code FileStatus.java}, {@code TransactionSource.java}, {@code RejectCode.java} and this file. No sixth may
 * be added, and in particular no README and no Markdown file, because this docstring is the module
 * documentation: a mapper belongs in the service layer and a lookup table in
 * {@code src/main/resources/validation}. Nothing here is credential material or personal data, and none may be
 * added; lookups use a {@code switch} or a prebuilt immutable index rather than scanning {@code values()} on a
 * hot path, and no hash-based iteration order is relied upon.
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */

package com.cardemo.model.enums;
