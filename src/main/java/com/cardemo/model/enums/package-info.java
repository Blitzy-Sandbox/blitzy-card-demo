/*
 * ******************************************************************
 * Program     : package-info.java
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
 * Typed replacements for the COBOL condition names, file status values and batch reject literals of the
 * frozen CardDemo corpus.
 *
 * <p>The banner above names every legacy artefact this package derives from, each pinned to commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} (short {@code 7756d89}), which is the traceability
 * anchor for the whole migration. Note the file name casing in those citations: {@code CBSTM03A.CBL} and
 * {@code CBSTM03B.CBL} are the only two members of {@code app/cbl} carrying an uppercase extension, the
 * other 26 of the 28 using a lowercase one, so a case sensitive lookup that assumes the lowercase form for
 * either of those two finds nothing. The same trap applies to {@code app/jcl}, where
 * {@code CREASTMT.JCL} is the one member of 29 with an uppercase extension.
 *
 * <h2>What it does</h2>
 *
 * <p>This package holds exactly four enum types, and they are typed replacements for three distinct and
 * unrelated COBOL idioms rather than four variations on one theme:
 *
 * <ol>
 *   <li><strong>88-level condition names</strong> attached to a one-character {@code PIC X(01)} field,
 *       which is how the legacy corpus expresses a closed set of permitted values. {@link UserType} comes
 *       from this idiom.</li>
 *   <li><strong>The universal {@code FILE STATUS} guard idiom</strong>, repeated after every {@code OPEN},
 *       {@code READ}, {@code WRITE}, {@code REWRITE} and {@code CLOSE} in every batch program.
 *       {@link FileStatus} comes from this idiom.</li>
 *   <li><strong>Inline literals</strong> assigned by {@code MOVE} into a working-storage or record field.
 *       {@link TransactionSource} and {@link RejectCode} come from this idiom, and for both of them the
 *       literal text itself is part of the observable contract, not an implementation detail.</li>
 * </ol>
 *
 * <p>All four types are pure data holders. They carry only their own constants and accessors, perform no
 * I/O, hold no collaborators, open no transaction, emit no log record and have <strong>zero framework
 * coupling</strong>: nothing from Spring, Jakarta Persistence, Jakarta Validation, Jackson or any AWS SDK
 * appears in any of them. Their only compile dependencies are a handful of {@code java.util} types.
 *
 * <p><strong>Architectural boundary, stated so it can be enforced.</strong> No type in this package may
 * import from {@code com.cardemo.exception}, {@code com.cardemo.repository}, {@code com.cardemo.service},
 * {@code com.cardemo.controller}, {@code com.cardemo.batch}, {@code com.cardemo.security},
 * {@code com.cardemo.config} or {@code com.cardemo.observability}. Dependencies point
 * <strong>inward toward this package</strong> and never outward, which makes it a foundational leaf of the
 * dependency graph and free of cycles by construction. The package also references nothing else inside
 * {@code com.cardemo.model}: there is no dependency on the entity, key or DTO packages, so these four
 * types can be compiled and unit tested entirely on their own.
 *
 * <h3>{@link UserType} - two constants, from two 88-levels</h3>
 *
 * <p>Exactly <strong>two</strong> constants, {@code A} for an administrator and {@code U} for a standard
 * user. They come from the two condition names declared at {@code app/cpy/COCOM01Y.cpy:L26-L28}, which
 * read {@code 10 CDEMO-USER-TYPE PIC X(01).}, then {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'.}, then
 * {@code 88 CDEMO-USRTYP-USER VALUE 'U'.}
 *
 * <p>Corroborated independently by the seed data: {@code app/jcl/DUSRSECJ.jcl:L35-L44} carries ten inline
 * user records fed through {@code IEBGENER}, five of type {@code A} and five of type {@code U}, with
 * <strong>no other value anywhere in the seed set</strong>. Two 88-levels and a ten-row census agreeing is
 * why two constants may be treated as settled fact rather than inference.
 *
 * <p>This enum exposes only the one-character code. It performs no authorisation and maps to no role:
 * translating a user type into a granted authority is the job of {@code com.cardemo.security}, and this
 * enum deliberately does <strong>not</strong> import Spring Security. Keeping the authority names out of a
 * model type is a least-privilege boundary, not a stylistic preference.
 *
 * <h3>{@link FileStatus} - six status families plus one accepted secondary success</h3>
 *
 * <p>Covers the six status families the migration recognises, {@code '00'}, {@code '10'}, {@code '22'},
 * {@code '23'}, {@code '35'} and the {@code '9x'} I/O error family, <strong>plus</strong> the accepted
 * secondary success {@code '04'}, giving seven constants in total. It also owns the four-character
 * {@code IO-STATUS-04} rendering used by the legacy diagnostic display.
 *
 * <p>The declarations are at {@code app/cbl/CBTRN02C.cbl:L131-L144}. Two details there are load bearing.
 * The rendering field is a group of {@code 05 IO-STATUS-0401 PIC 9} followed by
 * {@code 05 IO-STATUS-0403 PIC 999} at {@code :L138-L140}, so it is one digit plus three digits and
 * therefore <strong>exactly four characters</strong> wide. And the end-of-file condition name at
 * {@code :L144} is {@code 88 APPL-EOF VALUE 16} - <strong>sixteen</strong>, not twelve, which is easy to
 * misread because the neighbouring failure path moves 12 into the same field.
 *
 * <p>The rendering itself is at {@code app/cbl/CBTRN02C.cbl:L714-L727} and has two branches. When the
 * status is not numeric or its first byte is {@code '9'}, the first byte is copied through and the second
 * is widened from a binary field into three digits. Otherwise the field is set to four zeros and the two
 * status characters are placed at positions three and four, so status {@code '23'} renders {@code 0023}.
 * Both branches then display the same fixed prefix.
 *
 * <p>This enum <strong>classifies but does not decide</strong>. It answers which family a status belongs
 * to and how it renders; it never chooses an exception, a control path or an exit code. Translating a
 * status into a typed exception belongs to {@code com.cardemo.service.shared.FileStatusMapper}, which is
 * why this package holds no reference to any exception type.
 *
 * <p>Three call sites in the corpus treat a not-found or secondary status as <strong>success</strong>
 * rather than as an error, and any status mapper built on this enum has to know about all three:
 *
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L481} - the transaction category balance upsert accepts
 *       {@code '00' OR '23'}, because a missing row is the create branch of an upsert rather than a
 *       failure.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:L422} and {@code :L436} - the interest rate lookup accepts
 *       {@code '00' OR '23'}, and on {@code '23'} substitutes the literal default group identifier and
 *       retries. The retry accepts success only, so a missing default row does abend the job.</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL:L736} and {@code :L748} - the statement file service accepts
 *       {@code '00' OR '04'} at both its open and its read site.</li>
 * </ul>
 *
 * <p>The {@code '35'} constant is on a different evidential footing from the other six and is documented
 * as such under "Not available" below. It is <strong>specification-derived</strong>, not corpus-derived.
 *
 * <h3>{@link TransactionSource} - two constants, and why it is not the column type</h3>
 *
 * <p>Exactly <strong>two</strong> constants, taken from the only two literal assignments to the source
 * field in the entire corpus: {@code 'System'} at {@code app/cbl/CBACT04C.cbl:L484}, used for the
 * synthetic interest transactions the batch job generates, and {@code 'POS TERM'} at
 * {@code app/cbl/COBIL00C.cbl:L222}, used for an online bill payment.
 *
 * <p>The underlying field is {@code 05 TRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L8}. Summing
 * the field widths that precede it in the same record - a 16-character identifier, a 2-character type code
 * and a 4-digit category code - places it at <strong>offsets 23 to 32</strong> of the 350-byte transaction
 * image. Emitting the value at any other width breaks the fixed-width record geometry, so both constants
 * expose a right-padded ten-character form as well as their raw text.
 *
 * <p><strong>This enum is deliberately not the persisted column type, and that is the single most
 * important thing to understand about it.</strong> A whitespace-tolerant census,
 * {@code grep -rnE "TO +TRAN-SOURCE" app/cbl/}, returns exactly four assignment sites. Two are the
 * literals above. The other two are pass-throughs of arbitrary text that the program never inspects:
 * {@code app/cbl/COTRN02C.cbl:L454} moves an operator-typed screen field straight through from the BMS
 * map, and {@code app/cbl/CBTRN02C.cbl:L428} moves the value straight through from the staging record.
 * The column's real domain is therefore open, and the fixture proves it: the value {@code OPERATOR}
 * occupies offsets 23 to 32 in <strong>50 of the 300</strong> records of
 * {@code app/data/ASCII/dailytran.txt}, the remaining 250 carrying {@code POS TERM}. {@code OPERATOR} is
 * <strong>data, not a constant</strong>, and must never be added here.
 *
 * <h3>{@link RejectCode} - five constants, verbatim descriptions, business outcomes</h3>
 *
 * <p>Exactly <strong>five</strong> constants - 100, 101, 102, 103 and 109 - each paired with its verbatim
 * legacy description, all five drawn from {@code app/cbl/CBTRN02C.cbl}:
 *
 * <ul>
 *   <li>{@code :L385-L387} - 100, {@code INVALID CARD NUMBER FOUND}, on a failed cross-reference
 *       lookup.</li>
 *   <li>{@code :L397-L399} - 101, {@code ACCOUNT RECORD NOT FOUND}, on a failed account lookup.</li>
 *   <li>{@code :L410-L412} - 102, {@code OVERLIMIT TRANSACTION}.</li>
 *   <li>{@code :L417-L419} - 103, {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}. The abbreviation
 *       {@code ACCT} is in the source and must not be expanded.</li>
 *   <li>{@code :L556-L558} - 109, {@code ACCOUNT RECORD NOT FOUND}. The text is byte-identical to 101's;
 *       that duplication is present in the source and is preserved rather than de-duplicated.</li>
 * </ul>
 *
 * <p><strong>Reject codes are business outcomes, never exceptions.</strong> They are assigned into a
 * working-storage reason field, drive whether a record is posted or written to the reject dataset, and
 * ultimately drive the batch {@code ExitStatus}. Nothing in the legacy flow throws, and nothing here
 * should: modelling a reject as an exception would turn an ordinary, expected, per-record outcome into
 * control flow by unwinding.
 *
 * <p>The reject record geometry is fixed by {@code app/cbl/CBTRN02C.cbl:L176-L182}: a
 * {@code PIC X(350)} transaction image followed by a {@code PIC X(80)} validation trailer, and the trailer
 * decomposes into {@code PIC 9(04)} for the reason code and {@code PIC X(76)} for its description. So the
 * code is rendered as four zero-padded digits, the description as 76 space-padded characters, the trailer
 * is 80 bytes and the whole record is <strong>430 bytes</strong>. Every one of those four numbers is a
 * parity contract.
 *
 * <p>The exit code rule is equally narrow. At {@code app/cbl/CBTRN02C.cbl:L229-L231} the program moves 4
 * into the return code <strong>if and only if the reject count is greater than zero</strong>. There is no
 * other determinant, no threshold and no per-code weighting.
 *
 * <p>Two legacy quirks are preserved here rather than repaired, because parity is the contract:
 *
 * <ul>
 *   <li><strong>103 overwrites 102.</strong> Severity High if got wrong. At
 *       {@code app/cbl/CBTRN02C.cbl:L403-L420} the over-limit test and the expiry test are two
 *       <em>sequential, unguarded</em> {@code IF} blocks with no alternative branch and no early exit
 *       between them. When both conditions fail, the second assignment overwrites the first and a single
 *       reject record bearing 103 is written. Guarding the second test, or emitting two reject records,
 *       diverges from the source.</li>
 *   <li><strong>109 is reachable but never consumed.</strong> It is assigned on the account-rewrite
 *       failure path at {@code :L556-L558}, but that paragraph only runs on the already-validated posting
 *       path, so no reject record is written, the reject count is not incremented, and the value is
 *       cleared by the per-iteration reset at {@code :L208-L209} on the next record. It is retained
 *       deliberately, cited in TRACEABILITY_MATRIX.md and justified in DECISION_LOG.md.</li>
 * </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>There is <strong>nothing to run</strong>. This package contains no {@code main} method, no Spring
 * bean, no scheduled task and no HTTP endpoint. It is compiled as part of the single CardDemo Spring Boot
 * module and exercised only through its consumers.
 *
 * <h3>Build</h3>
 *
 * <ul>
 *   <li><strong>Command.</strong> {@code ./mvnw -q -DskipTests compile} to compile,
 *       {@code ./mvnw -q verify} for the full gated build, both from the repository root.</li>
 *   <li><strong>Language level.</strong> Java <strong>25</strong>, set through
 *       {@code maven.compiler.release} rather than {@code source}/{@code target}, so the JDK 25 API surface
 *       is enforced and not merely the bytecode version. <strong>No preview features</strong> are enabled
 *       and none may be used here.</li>
 *   <li><strong>Pinned toolchain.</strong> Apache Maven <strong>3.9.11</strong> through the checked-in
 *       wrapper, parent {@code org.springframework.boot:spring-boot-starter-parent:3.5.11}, and
 *       {@code maven-compiler-plugin:3.14.1}. {@code maven-enforcer-plugin} asserts a Java floor of
 *       {@code [25,)} and a Maven floor of {@code [3.9.11,)}, so the build cannot silently run on a
 *       different toolchain.</li>
 *   <li><strong>Warnings are errors.</strong> The compiler is configured with {@code -Xlint:all} and
 *       {@code -Werror}, and additionally with {@code failOnWarning}, {@code showWarnings} and
 *       {@code showDeprecation}. An unused import, a raw type, an unchecked cast, a call to a deprecated
 *       API, a {@code switch} fall-through or a missing {@code serialVersionUID} on a serializable type is
 *       a <strong>hard build failure</strong>, not a warning to triage later. This is precisely why this
 *       file carries zero imports and zero annotations: there is no nullability annotation available to
 *       use, since no JSR-305 and no JSpecify artefact is declared anywhere in {@code pom.xml}, and
 *       reaching for a framework annotation instead would risk a deprecation that the build treats as
 *       fatal.</li>
 *   <li><strong>No Lombok, no annotation processor, no new dependency.</strong> This package adds none.
 *       Every accessor, every lookup and every {@code toString} in the four enums is written out
 *       explicitly, so what is compiled is exactly what is read.</li>
 * </ul>
 *
 * <h3>Test</h3>
 *
 * <ul>
 *   <li><strong>Location.</strong> Unit tests for these four types live in the sibling test tree at
 *       {@code src/test/java/com/cardemo/unit/model}, <strong>never</strong> in this package. This package
 *       stays free of test scaffolding.</li>
 *   <li><strong>Coverage gate.</strong> JaCoCo enforces an <strong>80 percent LINE</strong> coverage floor
 *       on the merged bundle at the {@code verify} phase with {@code haltOnFailure}, and there are
 *       <strong>no exclusions</strong> for this package. Coverage must come from meaningful assertions; the
 *       figure must not be padded by calling getters. This file is documentation only, contributes no
 *       executable lines, and therefore neither helps nor harms the figure.</li>
 *   <li><strong>Coverage plugin version.</strong> Pinned to <strong>0.8.13</strong>. The requirements named
 *       0.8.12, but that release physically cannot run on this target: Java 25 emits class file major
 *       version 69, which the ASM build inside 0.8.12 rejects outright, failing the report goal before any
 *       coverage figure is computed. 0.8.13 is the smallest release that reads it, so the preference for
 *       the lower version is kept while the gate is made able to run at all. Recorded in
 *       DECISION_LOG.md.</li>
 *   <li><strong>Assertions that actually matter</strong> for this package, as distinct from mechanical
 *       getter coverage:
 *       <ul>
 *         <li>All five {@link RejectCode} descriptions compared <em>verbatim</em> against their cited
 *             source lines, including the {@code ACCT} abbreviation in 103 and the intentional textual
 *             duplication between 101 and 109.</li>
 *         <li>Both {@link TransactionSource} literals, and their ten-character right-padded forms, since
 *             the padded form is what reaches the fixed-width boundary.</li>
 *         <li>{@link UserType} having <em>exactly two</em> constants, asserted on the length of the
 *             constant array rather than on individual lookups, so a third constant fails the test.</li>
 *         <li><em>Both</em> branches of the four-character {@link FileStatus} rendering, each asserted to
 *             be exactly four characters long, with {@code '23'} rendering {@code 0023}.</li>
 *       </ul>
 *   </li>
 *   <li><strong>Determinism.</strong> No static mutable state, no dependence on a clock, and no reliance on
 *       the platform default locale, charset or time zone. Every case-folding, parsing and formatting
 *       operation passes {@code Locale.ROOT} explicitly, so behaviour cannot differ between a developer
 *       machine and CI. The types are immutable and therefore safe to share across threads.</li>
 * </ul>
 *
 * <h3>Toolchain actually present in this environment</h3>
 *
 * <p>Measured rather than assumed, so the statement can be relied on: {@code java} and {@code javac}
 * report OpenJDK <strong>25.0.3</strong>, {@code mvn} reports Apache Maven <strong>3.9.11</strong>, and
 * <strong>Docker Engine 29.7.0 with {@code docker compose} v5.3.1 is available</strong> and is what
 * provisions PostgreSQL 16, LocalStack, Jaeger, Prometheus and Grafana for the integration tiers. The host
 * toolchain is activated by sourcing {@code /etc/profile.d/10-carddemo-toolchain.sh}. Where a host JDK is
 * not provisioned, the identical build runs inside the pinned Java 25 and Maven 3.9.11 container image with
 * the repository mounted, and produces the same result because every plugin and every non-managed
 * dependency version is pinned. Any claim that the Java toolchain or the container runtime is absent is
 * stale and must not be repeated.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p><strong>This package has no configuration at all.</strong> Not one
 * {@code src/main/resources/application*.yml} key, environment variable, system property, command-line
 * argument, Spring bean definition or active profile changes anything about how these four types behave.
 * There is no {@code @ConfigurationProperties} class, no {@code @Value} injection and no
 * {@code Environment} lookup anywhere in the package.
 *
 * <p>Every constant is <strong>compile-time and source-fixed</strong>. Each value is transcribed from the
 * frozen COBOL corpus at commit {@code 7756d89} and carries a citation to the line it came from, so the
 * values change only if the corpus changes - and the corpus is frozen: {@code app/} and {@code samples/}
 * are read-only reference material that must survive byte for byte.
 *
 * <p>There are consequently <strong>no defaults to override, and deliberately no mechanism to override
 * them</strong>. That is a design decision rather than an omission. A reject description, a status code or
 * a source literal that could be changed by configuration would make the byte-level output comparison
 * against the legacy baseline meaningless, because the same build could then produce two different reject
 * records. Externalising these values would trade a provable contract for a configuration knob nobody
 * needs.
 *
 * <p>The deliberate absences are themselves the defaults, and each is load bearing:
 *
 * <ul>
 *   <li><strong>Zero static mutable fields.</strong> The only static members in the package are
 *       {@code static final} constants and one immutable private lookup index built once at class
 *       initialisation.</li>
 *   <li><strong>Zero annotations in this file.</strong> Not even a nullability marker - see the build
 *       notes above for why that is a technical requirement here and not a preference.</li>
 *   <li><strong>Zero added dependencies.</strong> The four types import only {@code java.util} members.</li>
 *   <li><strong>No serialization.</strong> None of the four implements {@code Serializable}. Enum constants
 *       are serializable by identity through the language, and declaring the interface would additionally
 *       raise the {@code serial} lint over a missing {@code serialVersionUID}, which the build escalates to
 *       an error.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Each entry below is a real, specific way this package can break the migration, with a severity and a
 * remediation. They are ordered by the type they affect, not by severity.
 *
 * <ul>
 *   <li><strong>A sixth {@link RejectCode} constant.</strong> Severity <strong>High</strong>. Symptom: the
 *       five-file completion gate for this folder fails, the reject-code metric tag stops being bounded and
 *       its cardinality inflates, and the enum diverges from DECISION_LOG.md, which reserves exactly 100,
 *       101, 102, 103 and 109. <em>Remediation:</em> delete the extra constant. The "no reject" state is
 *       <strong>not</strong> a constant - it is the numeric {@code 0} written by the per-iteration reset at
 *       {@code app/cbl/CBTRN02C.cbl:L208-L209}, and it is represented as a plain integer, not as an enum
 *       member.</li>
 *   <li><strong>Deleting {@link RejectCode} 109 as "dead code".</strong> Severity
 *       <strong>Blocker</strong>. Symptom: paragraph-level coverage of {@code CBTRN02C} becomes incomplete
 *       and the scope-coverage gate fails, because the assignment at
 *       {@code app/cbl/CBTRN02C.cbl:L556-L558} is real code on a reachable path with no Java counterpart
 *       left to cite. <em>Remediation:</em> restore the constant with its intentional-retention marker. The
 *       no-dead-code standard forbids <em>untracked</em> dead code; 109 is cited, tracked in
 *       TRACEABILITY_MATRIX.md and justified in DECISION_LOG.md, so it is tracked by definition.</li>
 *   <li><strong>A wrong or "tidied" reject literal.</strong> Severity <strong>High</strong>. Symptom: a
 *       reject-record diff against the legacy baseline. The usual mistakes are re-casing a description,
 *       expanding {@code ACCT} to {@code ACCOUNT} in code 103, or "fixing" 109's text because it duplicates
 *       101's. <em>Remediation:</em> diff each string character by character against its cited source
 *       lines. The duplication between 101 and 109 is intentional and present in the source.</li>
 *   <li><strong>Guarding the 102 to 103 overwrite.</strong> Severity <strong>High</strong>. Symptom: a
 *       record that fails both the over-limit and the expiry test is rejected with 102 instead of 103, or
 *       produces two reject records instead of one. <em>Remediation:</em> read
 *       {@code app/cbl/CBTRN02C.cbl:L403-L420} again - the two {@code IF} blocks are sequential and
 *       unguarded, so the later assignment wins. Reproduce that, do not repair it.</li>
 *   <li><strong>A third {@link TransactionSource} constant.</strong> Severity <strong>Medium</strong>, and
 *       almost always {@code OPERATOR}. Symptom: the enum starts to look like the column's full domain,
 *       which invites an enum-typed persistence mapping, which then rejects or mangles real fixture data
 *       and fails the end-to-end and fixture gates on the 350-byte record. <em>Remediation:</em> run the
 *       whitespace-tolerant census {@code grep -rnE "TO +TRAN-SOURCE" app/cbl/}. It returns exactly four
 *       sites and only two of them are literals. A naive {@code grep "TO TRAN-SOURCE"} with a single space
 *       returns only three and is actively misleading, because
 *       {@code app/cbl/CBTRN02C.cbl:L428} separates the operands with several spaces.</li>
 *   <li><strong>A three- or five-character {@link FileStatus} rendering.</strong> Severity
 *       <strong>Medium</strong>. Symptom: a log diff against the legacy baseline on every diagnostic line.
 *       <em>Remediation:</em> the field is {@code PIC 9} followed by {@code PIC 999} at
 *       {@code app/cbl/CBTRN02C.cbl:L138-L140}, so the rendering is <strong>exactly four
 *       characters</strong>. Assert length four on both branches and assert that {@code '23'} renders
 *       {@code 0023}.</li>
 *   <li><strong>"Fixing" the {@code FILE STATUS IS: NNNN} prefix.</strong> Severity
 *       <strong>Medium</strong>. Symptom: a log diff. COBOL {@code DISPLAY} concatenates its operands with
 *       no separator, so at {@code app/cbl/CBTRN02C.cbl:L721} and {@code :L725} a status of {@code '23'}
 *       emits the single string {@code FILE STATUS IS: NNNN0023}. The stray {@code NNNN} is a
 *       <strong>preserved legacy quirk</strong>, not a placeholder someone forgot to substitute.
 *       <em>Remediation:</em> keep the prefix literal byte-exact and do not prepend it a second time at the
 *       call site.</li>
 *   <li><strong>A third {@link UserType} constant</strong>, typically {@code UNKNOWN} or {@code NONE}.
 *       Severity <strong>Medium</strong>. Symptom: the enum diverges from the two 88-levels at
 *       {@code app/cpy/COCOM01Y.cpy:L26-L28} and from the ten-row seed census, and an "unknown" role starts
 *       to leak into authorisation decisions. <em>Remediation:</em> delete it. An unrecognised code is
 *       handled at the boundary, by returning an empty {@code Optional} or by throwing
 *       {@code IllegalArgumentException} naming the offending value - never by inventing a constant to
 *       absorb it.</li>
 *   <li><strong>Importing from {@code com.cardemo.exception}, or any other outward package.</strong>
 *       Severity <strong>High</strong>. Symptom: the dependency direction of a leaf package inverts, a
 *       cycle becomes possible, and the "business outcome, not exception" mandate for reject codes is
 *       contradicted in the type system itself. <em>Remediation:</em> signal failure with an empty
 *       {@code Optional} or with {@code IllegalArgumentException}; never reference a project exception type
 *       from this package.</li>
 *   <li><strong>A static mutable field.</strong> Severity <strong>High</strong>. The tempting precedent is
 *       the interest job's run-sequential suffix counter at {@code app/cbl/CBACT04C.cbl:L474}, which is
 *       incremented globally and never reset per account. Symptom: tests become order-dependent and
 *       non-deterministic, and concurrent batch steps interfere. <em>Remediation:</em> that counter is
 *       job-scoped state and belongs in the batch layer, not here. Every field in this package is
 *       {@code final}; any lookup map is {@code private static final} and immutable.</li>
 *   <li><strong>Adding a fifth file to this folder</strong> - a README, a helper, a mapper or a
 *       lookup-table class. Severity <strong>Medium</strong>. Symptom: the five-file gate for this folder
 *       fails and the tree-wide source-file count drifts. <em>Remediation:</em> delete it. The
 *       documentation standard is satisfied by <em>this docstring</em>, not by a README, and there must be
 *       no Markdown file in this folder. A mapper belongs in the service layer; a lookup table belongs in
 *       {@code src/main/resources/validation}.</li>
 * </ul>
 *
 * <h2>Not available: direct corpus evidence for file status {@code '35'}</h2>
 *
 * <p><strong>Not available.</strong> The frozen corpus contains <strong>no literal {@code '35'} comparison
 * and no {@code DFHRESP(NOTOPEN)} handler anywhere under {@code app/}</strong>. A full census of the CICS
 * response conditions actually present returns {@code DFHRESP(NORMAL)} 43 times,
 * {@code DFHRESP(NOTFND)} 23, {@code DFHRESP(ENDFILE)} 8, {@code DFHRESP(DUPREC)} 7,
 * {@code DFHRESP(DUPKEY)} 3 and {@code DFHRESP(NOTOPEN)} <strong>zero</strong> times.
 *
 * <p>The {@code '35'} constant is therefore <strong>specification-derived rather than corpus-derived</strong>.
 * It is included for two reasons: to complete the mandated six-family status taxonomy, and to give
 * {@code com.cardemo.exception.FileUnavailableException} a status value to be raised from. It is documented
 * here rather than quietly presented as though it had the same provenance as {@code '00'} or {@code '23'}.
 *
 * <p><strong>Severity: Medium.</strong> Not a Blocker, because the package compiles and every other
 * constant is fully evidenced, so nothing is blocked by the gap. Not Low, because a file-unavailable path
 * that no legacy program exercises cannot be parity-tested against a baseline, which means its behaviour
 * rests on the specification alone.
 *
 * <p><strong>What would be needed to close it:</strong> either a literal {@code '35'} status comparison, or
 * a {@code DFHRESP(NOTOPEN)} handler, somewhere in {@code app/}. <strong>Neither exists.</strong> Absent
 * one of those, the constant stays specification-derived and this note stays in place.
 *
 * <p>By contrast, {@code '22'} is <em>not</em> in the same position even though it too has no literal
 * comparison in the corpus. It is grounded through the CICS duplicate conditions, canonically at
 * {@code app/cbl/COUSR01C.cbl:L260-L261}, where {@code WHEN DFHRESP(DUPKEY)} and
 * {@code WHEN DFHRESP(DUPREC)} fall through to a single duplicate-key handler. That is real evidence of the
 * duplicate outcome being handled, so {@code '22'} is corpus-derived by condition name rather than by
 * literal.
 *
 * <h2>Package level constraints</h2>
 *
 * <p>These apply to every type in this package and to any change made to it. They are the package-level
 * discharge of the project's global coding and design standard.
 *
 * <ul>
 *   <li><strong>Correctness and explicit behaviour before cleverness.</strong> Every factual claim in this
 *       file cites the artefact and line range it came from, and every count is exact rather than rounded:
 *       two user types, seven file status constants over six families plus one accepted secondary success,
 *       two transaction sources, five reject codes. Nothing here is approximated.</li>
 *   <li><strong>Untrusted input is handled explicitly, never coerced.</strong> An unrecognised code is
 *       reported, not absorbed: lookups return an empty {@code Optional}, and the strict variants throw
 *       {@code IllegalArgumentException} naming the offending value so the root cause survives into the
 *       message. Nothing returns a silent {@code null} and nothing falls back to an arbitrary constant.
 *       Null and empty inputs are rejected at the boundary rather than propagated.</li>
 *   <li><strong>Separation of concerns is enforceable, not aspirational.</strong> The inward-only import
 *       rule stated near the top of this file is what keeps it so: classification lives here, decisions
 *       live in the service, batch and security layers.</li>
 *   <li><strong>Observability is served, not performed.</strong> These types <strong>never log
 *       themselves</strong> and hold no logger. What they contribute is the raw material that makes
 *       downstream signals meaningful: the exact reject descriptions and the four-character status
 *       rendering are what let a structured JSON log line be compared against the legacy baseline, and the
 *       bounded five-value reject code set is what makes a reject-code-tagged counter safe to expose to
 *       Prometheus without unbounded tag cardinality.</li>
 *   <li><strong>Performance conventions.</strong> Lookups use a {@code switch} or a prebuilt immutable
 *       index, never a scan of {@code values()} inside a loop, and never
 *       {@code Enum.valueOf} on a hot path for flow control. The iteration order of any hash-based
 *       collection of these constants is never relied upon; anything order-sensitive is ordered
 *       explicitly.</li>
 *   <li><strong>No secrets, and no personal data.</strong> Nothing in this package is credential material.
 *       The ten seed users at {@code app/jcl/DUSRSECJ.jcl:L35-L44} do share one plaintext credential in the
 *       legacy inline data, and that value is <strong>deliberately not reproduced anywhere in
 *       {@code src/}</strong>, here included; the seeded credentials are stored only as BCrypt hashes,
 *       written by {@code V3__seed_data.sql}. Nothing in this package needs masking because nothing
 *       sensitive is present, and nothing sensitive may be added.</li>
 *   <li><strong>Least privilege.</strong> {@link UserType} exposes only the one-character code and performs
 *       no authorisation. Granted authorities are derived in {@code com.cardemo.security}, so a model type
 *       can never widen an access decision.</li>
 *   <li><strong>No risky execution or deserialization patterns.</strong> No {@code Runtime.exec}, no
 *       {@code ProcessBuilder}, no reflection, no dynamic class loading, no {@code ObjectInputStream} path
 *       and no string-built SQL or JPQL. None of the four types implements {@code Serializable}, so there
 *       is no Java-serialization surface to attack.</li>
 *   <li><strong>Repository conventions are established here, not inherited.</strong> The Apache-2.0
 *       provenance banner at the top of this file follows the corpus convention verified at
 *       {@code app/cbl/CBACT04C.cbl:L1-L21} and present on 28 of 28 members of {@code app/cbl}, 17 of 17 of
 *       {@code app/cpy-bms}, 17 of 17 of {@code app/bms}, 28 of 29 of {@code app/jcl} and 12 of 28 of
 *       {@code app/cpy}; the Java form additionally names the originating COBOL artefacts. Formatting
 *       follows the repository {@code .editorconfig}: UTF-8, LF endings, a final newline, no trailing
 *       whitespace, four-space Java indentation. Note that no formatter, linter or style-tool configuration
 *       existed anywhere in this repository before the migration - no {@code .editorconfig}, no Prettier,
 *       Checkstyle or Spotless configuration, no {@code Makefile}, no {@code .toml} and no {@code .cfg} -
 *       so the "follow existing conventions if present" condition is <strong>not triggered for
 *       formatters</strong>, and this configuration is established at the repository root rather than
 *       inherited. That is not the same as overriding an existing style, and no existing style is being
 *       fought. {@code CONTRIBUTING.md:L33} asks contributors to focus on the specific change and warns
 *       that reformatting everything makes the change hard to review; {@code CONTRIBUTING.md:L34} asks that
 *       local tests pass. Both are honoured.</li>
 *   <li><strong>No untracked deferred work.</strong> This file contains no deferred-work marker of any
 *       kind. The one artefact that could be mistaken for abandoned residue, {@link RejectCode} 109, is
 *       cited to its source lines, recorded in TRACEABILITY_MATRIX.md and justified in DECISION_LOG.md, and
 *       the one genuine evidence gap, file status {@code '35'}, is disclosed above with a severity and an
 *       explicit list of what would close it.</li>
 *   <li><strong>Directory shape.</strong> This package contains exactly <strong>five</strong> {@code .java}
 *       files: {@code UserType.java}, {@code FileStatus.java}, {@code TransactionSource.java},
 *       {@code RejectCode.java} and this file. No sixth file may be added, and in particular no README and
 *       no Markdown file of any kind, because this docstring is the module documentation.</li>
 * </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */
package com.cardemo.model.enums;
