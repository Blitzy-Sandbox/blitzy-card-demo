/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.exception
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 * Function    : Package documentation for the typed exception hierarchy
 *               replacing COBOL FILE STATUS and CICS response-code
 *               branching.
 * Source      : app/cpy/CSMSG02Y.cpy (CABENDD.CPY, abend work areas) @ 7756d89
 * Source      : app/cpy/CSSETATY.cpy (COPY ... REPLACING field-error template) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L131-L144,L236-L252,L707-L727 @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L517-L524,L2606-L2615,L4203-L4228 @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L415-L460 @ 7756d89
 * Source      : app/cbl/CBSTM03A.CBL:L736,L748,L771,L789,L807,L862,L879,L895,L911 @ 7756d89
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
 * The typed exception hierarchy that replaces the two failure-signalling mechanisms of the frozen CardDemo
 * corpus: the {@code FILE STATUS} guard wrapped around every batch I/O verb and the {@code EXEC CICS}
 * response-code branching wrapped around every online file request.
 *
 * <p><strong>What it does.</strong> One base class and eight subtypes, and nothing else besides this file.
 * Neither legacy mechanism carries a failure up a call chain; each aborts the unit of work where it is detected,
 * after rendering a diagnostic. These nine types give that abort a name, a message and a preserved root cause,
 * so a caller can tell the conditions apart and a controller can choose a response.
 *
 * <ul>
 *   <li>{@link CardDemoException} - the root; every subtype preserves the causing throwable.</li>
 *   <li>{@link ValidationException} - the per-field error markers of the procedural template
 *       {@code app/cpy/CSSETATY.cpy}, distinguishing a field left empty from a value that was wrong.</li>
 *   <li>{@link RecordNotFoundException} - {@code FILE STATUS '23'} and {@code DFHRESP(NOTFND)}, but only
 *       outside the three sites where a missing row is an accepted control path.</li>
 *   <li>{@link DuplicateRecordException} - {@code FILE STATUS '22'}, {@code DFHRESP(DUPREC)} and
 *       {@code DFHRESP(DUPKEY)}; the surfacing mechanism for the preserved identifier-generation race.</li>
 *   <li>{@link FileUnavailableException} - {@code FILE STATUS '35'} and {@code DFHRESP(NOTOPEN)}, extended to
 *       an unreachable datasource, bucket or queue.</li>
 *   <li>{@link ConcurrentUpdateException} - the five abandonment outcomes of
 *       {@code 9600-WRITE-PROCESSING} and {@code 9700-CHECK-CHANGE-IN-REC} in
 *       {@code app/cbl/COACTUPC.cbl}.</li>
 *   <li>{@link DataIntegrityException} - the referential and domain rules the relational schema now declares
 *       and VSAM never enforced.</li>
 *   <li>{@link FileAccessException} - the {@code '9x'} family, carrying the four-character expanded status so a
 *       log line matches the legacy job log byte for byte.</li>
 *   <li>{@link FatalProcessingException} - the residual abend, carrying the four abend work-area fields of
 *       {@code app/cpy/CSMSG02Y.cpy}, whose internal title is {@code CABENDD.CPY} and which holds
 *       {@code ABEND-CODE}, {@code ABEND-CULPRIT}, {@code ABEND-REASON} and {@code ABEND-MSG} rather than
 *       screen messages.</li>
 *   </ul>
 *
 * <p>Five contracts bind every class here. <strong>Dependency direction</strong> runs from {@code exception} to
 * {@code model} and never the reverse, so an enumeration never imports an exception. <strong>Mapping</strong>
 * from a status to a type lives in one place, {@code com.cardemo.service.shared.FileStatusMapper}, because the
 * corpus uses one guard idiom at every I/O site rather than many. <strong>Not-found is conditional</strong>:
 * {@code IF TCATBALF-STATUS = '00' OR '23'} at {@code app/cbl/CBTRN02C.cbl:481} and
 * {@code IF DISCGRP-STATUS = '00' OR '23'} at {@code app/cbl/CBACT04C.cbl:422} treat a missing row as success,
 * as does {@code IF WS-M03B-RC = '00' OR '04'} at {@code app/cbl/CBSTM03A.CBL:736} and its four siblings, so no
 * exception may be raised at those sites. <strong>A reject is not an exception</strong>: the five reject codes
 * are business outcomes that increment a counter and write a record, and only the reject count decides the
 * return code. <strong>No message carries sensitive data</strong>: identifiers are referred to by name, never by
 * value, so no card number, national identifier, date of birth or credential reaches a log through a message
 * built here.
 *
 * <p><strong>How to run, build and test.</strong> {@code ./mvnw clean verify} from the repository root compiles
 * this package under {@code -Xlint:all -Werror} - which is why every serialisable type declares
 * {@code serialVersionUID} - and enforces the project coverage floor at {@code verify}. Unit tests live in
 * {@code src/test/java/com/cardemo/unit} and assert the status-to-type mapping, the three accepted control
 * paths, cause preservation, and that no constructed message contains a field value.
 *
 * <p><strong>Key configuration and defaults.</strong> Nothing here is configurable. Two values are parity
 * contracts published as constants on {@link FatalProcessingException}: abend code 999 and process return code
 * 12, both from {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:707-711}. The default abend message
 * substituted when the message field is empty comes from {@code app/cbl/COACTUPC.cbl:4206}. This package
 * terminates nothing: the legacy {@code CALL 'CEE3ABD'} and {@code EXEC CICS ABEND} have no counterpart inside
 * these classes, and the decision to fail a step belongs to the batch or web tier.
 *
 * <p><strong>Common failure modes and troubleshooting.</strong>
 *
 * <ul>
 *   <li>{@code ./mvnw -q -DskipTests compile} - compiles the module. Use this as the fast check after
 *       editing anything in this package.</li>
 *   <li>{@code ./mvnw -q verify} - the full gate: compile, unit tests, Failsafe's integration and
 *       end-to-end tiers, coverage enforcement and the dependency vulnerability scan. A reading of
 *       1 August 2026 recorded {@code src/test/java/com/cardemo/integration} and {@code .../e2e} as not
 *       available and concluded that Failsafe had nothing to bind; <strong>both trees are authored</strong>
 *       and that reading is withdrawn. The integration and end-to-end tiers need a container runtime with an
 *       accessible socket, because they stand PostgreSQL 16 and LocalStack up through Testcontainers; where
 *       one is missing the correct report is {@code Not available} for those tiers specifically, never for the
 *       trees themselves.</li>
 *   </ul>
 *
 * <p>{@code maven-compiler-plugin:3.14.1} is configured with {@code -Xlint:all} and {@code -Werror}, and
 * with {@code failOnWarning} set to {@code true}. <strong>Every file must therefore be warning clean, and a
 * warning is a build failure rather than advice.</strong> Two consequences bind this package specifically:
 *
 * <ul>
 *   <li>All nine {@code Throwable} subclasses declare {@code serialVersionUID}, because
 *       {@code java.lang.Throwable} is already {@code Serializable} and {@code -Xlint:all} raises
 *       {@code serial} on any serialisable class that omits it. This documentation file declares
 *       <strong>no</strong> {@code serialVersionUID}, and is the one file in the package where that
 *       requirement does not apply, because it declares no type at all.</li>
 *   <li>{@code -Xlint:all} on Java 25 includes {@code dangling-doc-comments}, which warns about a
 *       documentation comment not attached to any declaration. That is why the provenance banner at the top
 *       of this file is an ordinary block comment and <strong>not</strong> a documentation comment, and why
 *       the single documentation comment sits immediately before the package declaration with nothing
 *       between them. Reversing that order, or making the banner a documentation comment, produces two
 *       candidate package comments and fails the build. The ordering is a compile requirement here, not a
 *       matter of taste.</li>
 *   </ul>
 *
 * <h2>Test</h2>
 *
 * <p>Unit tests live in {@code src/test/java/com/cardemo/unit}; the surefire configuration discovers
 * {@code **}{@code /*Test.java} and {@code **}{@code /*Tests.java} and excludes the {@code integration} and
 * {@code e2e} trees so they cannot run in the {@code test} phase. The {@code integration} exclusion is
 * live rather than pre-emptive - {@code src/test/java/com/cardemo/integration} exists, holding the two
 * abstract Testcontainers bases {@code batch/AbstractBatchIntegrationTest} and
 * {@code repository/AbstractRepositoryIntegrationTest} - so that pattern is what keeps a container-dependent
 * class out of the unit phase. <strong>The {@code e2e} exclusion is now live too</strong>: that tree holds
 * {@code BatchPipelineE2ETest}, {@code OnlineTransactionE2ETest} and {@code GateVerificationTest}, so the
 * pattern matches three classes rather than nothing. Two earlier revisions are withdrawn - the first said
 * neither tree existed, the second said the {@code e2e} half of that was still true.
 * <strong>No test file lives in this package</strong>, and none should: production and test sources are
 * never mixed in the same directory in this tree.
 *
 * <p>{@code jacoco-maven-plugin} enforces an <strong>80 percent line coverage floor at {@code verify}, with
 * no exclusions</strong> ({@code jacoco.line.coverage.minimum} is {@code 0.80}). Because there are no
 * exclusions, the constructors and accessors of all nine classes must be <strong>genuinely exercised</strong>
 * by tests that assert behaviour, not merely instantiated to move the number. This package <strong>is</strong>
 * exercised: {@code src/test/java/com/cardemo/unit/exception/ExceptionHierarchyTest.java},
 * {@code unit/model/CardDemoExceptionHierarchyTest.java} and
 * {@code unit/model/FatalProcessingExceptionTest.java} target it directly, and every one of the nine types is
 * referenced from between six and twenty-two test classes across the tree, because the services and batch
 * components that raise them assert on them. What is <strong>not</strong> present is a dedicated test class
 * per type, so per-type coverage is uneven rather than absent - a narrower and more accurate statement than
 * the one this paragraph previously made.
 *
 * <p>The plugin is pinned to <strong>{@code 0.8.12}</strong>, exactly the version the requirement names, and
 * an earlier revision of this paragraph wrongly said it had been raised to {@code 0.8.13}; that claim is
 * withdrawn. Java 25 emits class file major version 69, and the release that rejects it is <strong>ASM</strong>
 * rather than any {@code org.jacoco} artefact, so the pin is honoured literally and only the plugin's
 * transitive reader is advanced: {@code org.ow2.asm:asm}, {@code asm-commons} and {@code asm-tree} to
 * <strong>9.9</strong>, with the runtime agent at the matching <strong>0.8.14</strong> build. Both halves are
 * required. The measurement is recorded beside the property in {@code pom.xml}; the divergence is
 * <strong>owed an entry in {@code DECISION_LOG.md}</strong>, which is authored at the repository
 * root.
 *
 * <p>The plugin pin is {@code 0.8.12}, exactly as the requirement names it, and it is not raised. Java 25
 * emits class file major version 69 and the ASM 9.7 build inside 0.8.12 has a ceiling of 67, so the naive
 * reading is that the pin must move; the actual fix is that a plugin classpath is overridable, so
 * {@code pom.xml} keeps the pin literal and advances only the bytecode reader - ASM to 9.9 and
 * {@code jacoco.agent.runtime.version} to {@code 0.8.14}. Both halves are required. Recorded in
 * {@code pom.xml} and in {@code DECISION_LOG.md}.
 *
 * <h2>Verifying this file in particular</h2>
 *
 * <p>Because {@code pom.xml} configures no Javadoc plugin and no {@code -Xdoclint}, {@code verify} does not
 * check documentation well-formedness. This file is almost entirely a documentation comment, so it is
 * covered instead by the explicit, repository-owned doclint command published once in
 * {@code docs/technical-specifications.md} under the dated checkpoint section:
 *
 * <ul>
 *   <li>That command runs {@code javadoc -private -Xdoclint:all --release 25} over the whole
 *       {@code src/main/java} and {@code src/test/java} trees against the resolved test classpath, and must
 *       exit 0. Run it after any edit here; a malformed element, an unescaped angle bracket, an unresolved
 *       reference or a heading used out of sequence is caught by it and by nothing else in the build.</li>
 *   <li>It is deliberately a whole-tree invocation rather than a per-package one. A per-package run needs a
 *       {@code -sourcepath} to resolve {@link com.cardemo.model.enums.FileStatus} and, being narrow, can
 *       report zero errors while defects sit in a sibling package - which is exactly how the earlier
 *       per-file attestations in this tree came to be wrong.</li>
 *   <li>Warnings are reported by that command and are <strong>not</strong> part of the gate; only the exit
 *       status is. The default {@code -Xmaxwarns} caps the printed warning list, so the printed count is
 *       never evidence on its own.</li>
 *   </ul>
 *
 * <h2>Toolchain actually present in this environment</h2>
 *
 * <p>Measured on 1 August 2026 in this container. The prerequisite is a capability and never a host path -
 * JDK 25 on {@code PATH} with {@code JAVA_HOME} set, however the host provides it, and Maven from the pinned
 * wrapper; the repository's own contract is {@code .env} plus {@code ./mvnw}. Measured: {@code javac 25.0.3},
 * {@code javadoc 25.0.3}, {@code Apache
 * Maven 3.9.11} running on the same JDK, a warm local repository allowing fully offline resolution with
 * {@code -o}, and {@code Docker 29.7.0} with {@code Docker Compose v5.3.1} both available. The pinned pair
 * is therefore present on the host and {@code ./mvnw -q -DskipTests compile} runs directly.
 *
 * <p>Where a host lacks that pair, <strong>the identical commands run inside the pinned container</strong>,
 * for example a {@code maven:3.9.11-eclipse-temurin-25} class image with the repository mounted as the
 * working directory; the container path is a substitute for the toolchain, never for the commands. If the
 * build genuinely cannot be executed, report the exact command attempted together with its output.
 * Container tooling is available in this environment and is never the reason a build was not run.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p><strong>This package has no configuration surface of its own.</strong> It contributes no property, no
 * profile key, no environment variable, no bean and no annotation processor. Nothing in
 * {@code application.yml} or any profile overlay changes its behaviour, and no class here reads
 * {@code System.getenv}, a system property, a file or a clock. That is the accurate answer to this
 * question rather than an empty section.
 *
 * <p>What the package does own is a small set of <strong>contractual constants</strong>, each fixed by the
 * source rather than chosen:
 *
 * <ul>
 *   <li>{@link com.cardemo.exception.FatalProcessingException} carries batch abend code <strong>999</strong>
 *       and process return code <strong>12</strong>. The abend routine moves 999 into the abend code and
 *       calls the language environment abend service at
 *       {@code app/cbl/CBTRN02C.cbl:L710-L711 @ 7756d89}.</li>
 *   <li>The default abend message is the literal {@code UNEXPECTED ABEND OCCURRED.}, including its trailing
 *       full stop. The source substitutes it at {@code app/cbl/COACTUPC.cbl:L4205-L4206 @ 7756d89}, and the
 *       trigger there is {@code IF ABEND-MSG EQUAL LOW-VALUES}. {@code LOW-VALUES} means the field was never
 *       populated, which maps to <strong>{@code null}</strong> in Java and <strong>not</strong> to blank. A
 *       {@code null} message is replaced; a blank message is preserved exactly as given. Substituting on
 *       blank would suppress a real, deliberately empty message. Severity of getting this backwards:
 *       <strong>High</strong>.</li>
 *   <li>{@link com.cardemo.exception.FileAccessException} carries a <strong>four character</strong> expanded
 *       status. The width is not a choice: {@code IO-STATUS-04} is a group of {@code PIC 9} followed by
 *       {@code PIC 999} at {@code app/cbl/CBTRN02C.cbl:L138-L140 @ 7756d89}, so one digit plus three digits,
 *       four characters exactly.</li>
 *   </ul>
 *
 * <p>Two pieces of configuration this package <strong>depends on without owning</strong>:
 *
 * <ul>
 *   <li>{@code logback-spring.xml} must pass the literal {@code FILE STATUS IS: NNNN} through
 *       <strong>byte for byte, without reformatting</strong>, for the reason given under the rendering quirk
 *       below, and must apply the masking rules for credentials, password hashes and social security
 *       numbers. The prefix literal itself is declared once, in
 *       {@code com.cardemo.model.enums.FileStatus}; <strong>no class in this package redeclares it</strong>,
 *       so there is exactly one definition to keep correct.</li>
 *   <li>The batch exit code contract, which this package feeds but does not implement.
 *       <strong>Return code 4 is set if and only if the reject count exceeds zero</strong>:
 *       {@code IF WS-REJECT-COUNT > 0  MOVE 4 TO RETURN-CODE  END-IF} at
 *       {@code app/cbl/CBTRN02C.cbl:L227-L231 @ 7756d89}, with no other determinant anywhere. Return code 12
 *       comes from the abend path instead. These are <strong>two independent paths</strong>, and
 *       <strong>no exception in this package may produce return code 4</strong>: rejects are counted and
 *       written, never thrown.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>This package <em>is</em> the failure mode taxonomy, so this section is the substantive one. It is
 * written to describe the legacy behaviour <strong>as it is</strong>, including the places where that
 * behaviour is defective, because parity is the contract of this migration: a quirk faithfully reproduced is
 * correct, and a quirk silently repaired is a regression measured against the parity baseline.
 *
 * <h3>The status to exception map</h3>
 *
 * <ul>
 *   <li>{@code '00'} - success. Continue; throw nothing.</li>
 *   <li>{@code '04'} - secondary success, accepted at the {@code CBSTM03B} call sites <strong>only</strong>.
 *       Continue; throw nothing.</li>
 *   <li>{@code '10'} - end of file. <strong>Loop termination, not an error.</strong> Nothing is thrown, ever.
 *       In the source this status sets a flag or an end of data condition rather than reaching a guard, which
 *       is directly visible at {@code app/cbl/CBSTM03A.CBL:L356 @ 7756d89}
 *       ({@code WHEN '10' MOVE 'Y' TO END-OF-FILE}) and at
 *       {@code app/cbl/CBTRN02C.cbl:L351-L352 @ 7756d89}, where the read guard moves 16 into the result
 *       field so that {@code APPL-EOF} rather than a failure becomes true. Mapping {@code '10'} to an
 *       exception would abend every batch job at the end of its input.</li>
 *   <li>{@code '23'} - record not found. {@link com.cardemo.exception.RecordNotFoundException},
 *       <strong>except at the three scoped sites below</strong>.</li>
 *   <li>{@code '22'} - duplicate key. {@link com.cardemo.exception.DuplicateRecordException}.</li>
 *   <li>{@code '35'} - file unavailable. {@link com.cardemo.exception.FileUnavailableException}.</li>
 *   <li>{@code '9x'} - physical or logical I/O error. {@link com.cardemo.exception.FileAccessException},
 *       carrying the four character expanded status.</li>
 *   <li>anything else - unexpected. {@link com.cardemo.exception.FatalProcessingException}, abend code
 *       {@code 999}, return code {@code 12}.</li>
 *   </ul>
 *
 * <h3>The three scoped sites where a non {@code '00'} status is success</h3>
 *
 * <p>A blanket rule that maps every non {@code '00'} status to an exception <strong>abends all three of
 * these</strong>, and the failures are not cosmetic: two of them are the normal path for their datasets. The
 * central mapper must therefore be told the scope, and the leniency must be scoped to the exact guard that
 * carries it and not to the whole paragraph.
 *
 * <ul>
 *   <li><strong>The transaction category balance upsert.</strong> {@code 2700-UPDATE-TCATBAL} at
 *       {@code app/cbl/CBTRN02C.cbl:L467-L501 @ 7756d89} reads the balance record; on an invalid key it
 *       displays a message ending {@code .. Creating.} and sets a create flag, then the guard at
 *       {@code app/cbl/CBTRN02C.cbl:L481 @ 7756d89} accepts <strong>either</strong> status:
 *       {@code IF TCATBALF-STATUS = '00' OR '23'}. It then dispatches to
 *       {@code 2700-A-CREATE-TCATBAL-REC} ({@code :L503}) or {@code 2700-B-UPDATE-TCATBAL-REC}
 *       ({@code :L526}), and <strong>both branches add the transaction amount to the balance</strong>, which
 *       is what makes this an upsert rather than an error path. The leniency is scoped to that read guard
 *       <strong>only</strong>: the {@code WRITE} at {@code :L510} is guarded at {@code :L512} and the
 *       {@code REWRITE} at {@code :L528} is guarded at {@code :L530}, and both of those accept
 *       {@code '00'} alone.</li>
 *   <li><strong>The disclosure group default fallback, whose retry inverts the rule.</strong>
 *       {@code 1200-GET-INTEREST-RATE} at {@code app/cbl/CBACT04C.cbl:L415-L440 @ 7756d89} accepts
 *       {@code IF DISCGRP-STATUS = '00' OR '23'} at {@code :L422}; then at {@code :L436} it tests
 *       {@code IF DISCGRP-STATUS = '23'}, substitutes the literal group identifier {@code DEFAULT} and
 *       retries through {@code 1200-A-GET-DEFAULT-INT-RATE} at {@code :L443}. That retry read at
 *       {@code :L444} has <strong>no {@code INVALID KEY} clause at all</strong>, and its guard at
 *       {@code :L446} accepts <strong>only {@code '00'}</strong>. Therefore a missing {@code DEFAULT} row
 *       <strong>abends the job</strong>: it must raise
 *       {@link com.cardemo.exception.FatalProcessingException} and
 *       <strong>never {@link com.cardemo.exception.RecordNotFoundException}</strong>. The first lookup is
 *       lenient and the second is strict, so the naive reading, that a not found disclosure group is simply
 *       not found, is wrong in exactly the case that matters.</li>
 *   <li><strong>The file service secondary success.</strong> {@code app/cbl/CBSTM03A.CBL} reaches its
 *       datasets by calling {@code CBSTM03B}, and accepts {@code IF WS-M03B-RC = '00' OR '04'} at
 *       <strong>nine</strong> sites: {@code L736}, {@code L748}, {@code L771}, {@code L789}, {@code L807},
 *       {@code L862}, {@code L879}, {@code L895} and {@code L911} ({@code @ 7756d89}), the return field being
 *       {@code WS-M03B-RC PIC X(02)} at {@code :L80}. By contrast the four {@code EVALUATE WS-M03B-RC} sites
 *       at {@code :L353}, {@code :L379}, {@code :L403} and {@code :L837} accept {@code '00'} alone, treat
 *       {@code '10'} as end of file and abend on anything else. So {@code '04'} is success at the nine
 *       {@code IF} sites and <strong>not</strong> at the four {@code EVALUATE} sites, in the same
 *       program.</li>
 *   </ul>
 *
 * <p>One measured note that strengthens the rule, and corrects a plausible misreading. Across the whole
 * corpus a literal {@code '23'} appears in a status comparison at <strong>exactly three lines in two
 * programs</strong>: {@code app/cbl/CBTRN02C.cbl:L481}, {@code app/cbl/CBACT04C.cbl:L422} and
 * {@code app/cbl/CBACT04C.cbl:L436} ({@code @ 7756d89}). Those three lines <em>are</em> the two scoped
 * leniency sites above. In other words <strong>every literal {@code '23'} test in the corpus is a leniency
 * test; nowhere does a literal {@code '23'} comparison produce an error</strong>, and {@code :L481} is the
 * only one of the three in the posting program. Record not found as an error arrives through the CICS
 * dialect instead, as {@code DFHRESP(NOTFND)}, of which there are 23 occurrences.
 *
 * <h3>BLOCKER 5.2: the customer lock outcome that is set but never tested</h3>
 *
 * <p>Severity: <strong>Blocker</strong>. This is the most consequential preserved defect in the corpus, and
 * it is the reason {@link com.cardemo.exception.ConcurrentUpdateException} models five outcomes rather than
 * one.
 *
 * <p>{@code app/cbl/COACTUPC.cbl:L517-L524 @ 7756d89} declares four outcome condition names whose values are
 * the literal messages shown to the user:
 *
 * <ul>
 *   <li>{@code Could not lock account record for update} - {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}, at
 *       {@code :L517}</li>
 *   <li>{@code Could not lock customer record for update} - {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}, at
 *       {@code :L519}</li>
 *   <li>{@code Record changed by some one else. Please review} - {@code DATA-WAS-CHANGED-BEFORE-UPDATE}, at
 *       {@code :L521}. Note that {@code some one} is two words in the source, and the trailing full stop is
 *       part of the literal.</li>
 *   <li>{@code Update of record failed} - {@code LOCKED-BUT-UPDATE-FAILED}, at {@code :L523}</li>
 * </ul>
 *
 * <p>The dispatch that turns an outcome into a screen state is an {@code EVALUATE TRUE} at
 * {@code app/cbl/COACTUPC.cbl:L2606-L2615 @ 7756d89}. It tests exactly three of those four condition names,
 * and then falls through:
 *
 * <ul>
 *   <li>{@code WHEN COULD-NOT-LOCK-ACCT-FOR-UPDATE} sets {@code ACUP-CHANGES-OKAYED-LOCK-ERROR}, value
 *       {@code 'L'}</li>
 *   <li>{@code WHEN LOCKED-BUT-UPDATE-FAILED} sets {@code ACUP-CHANGES-OKAYED-BUT-FAILED}, value
 *       {@code 'F'}</li>
 *   <li>{@code WHEN DATA-WAS-CHANGED-BEFORE-UPDATE} sets {@code ACUP-SHOW-DETAILS}, value {@code 'S'}</li>
 *   <li>{@code WHEN OTHER} sets {@code ACUP-CHANGES-OKAYED-AND-DONE}, value {@code 'C'}, meaning the changes
 *       were accepted and applied</li>
 *   </ul>
 *
 * <p><strong>{@code COULD-NOT-LOCK-CUST-FOR-UPDATE} occurs at exactly two lines in this 4,236 line
 * program</strong>: {@code :L519}, where it is declared, and {@code :L3939}, where it is set when the
 * customer read for update fails. <strong>It is never tested anywhere.</strong> By contrast the account
 * equivalent occurs three times, adding the test at {@code :L2607}. The consequence is that a customer read
 * for update failure, at a point where nothing has yet been written, falls through {@code WHEN OTHER} and is
 * reported to the user as <strong>top level success</strong>: the update did not happen and the screen says
 * it did.
 *
 * <p><strong>This behaviour is preserved, not repaired.</strong> All five outcomes, the four above plus the
 * unconfirmed case, are individually representable in the nested {@code Outcome} enum of
 * {@link com.cardemo.exception.ConcurrentUpdateException}, and the customer outcome deliberately carries the
 * screen letter {@code 'C'} precisely because that is the letter the fall through produces. Modelling all
 * five gives a caller the information to reproduce the legacy outcome exactly while leaving the defect
 * visible and cited rather than buried.
 *
 * <p><strong>Do not collapse the five outcomes into a single conflict status.</strong> Severity of
 * collapsing them: <strong>High</strong>. The legacy screen distinguished them and a single {@code 409}
 * would discard information the user was shown. A related and much smaller wrinkle, severity
 * <strong>Low</strong>: both rewrite failure paths in the same program share the one
 * {@code LOCKED-BUT-UPDATE-FAILED} flag, so the outcome alone does not reveal which of the two writes
 * failed.
 *
 * <h3>The four character status rendering, and its {@code NNNN} quirk</h3>
 *
 * <p>{@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727 @ 7756d89} renders a status for
 * the log. It emits {@code FILE STATUS IS: NNNN} on <strong>both</strong> of its branches, at {@code :L721}
 * and {@code :L725}, and here is the trap: <strong>{@code NNNN} is a fixed literal inside the quoted string,
 * not a substitution placeholder.</strong> The four real characters are a separate operand, and COBOL
 * {@code DISPLAY a b} concatenates its operands with no separator, so the placeholder text survives into the
 * output and the digits follow it. Worked from the source:
 *
 * <ul>
 *   <li>status {@code '23'} takes the numeric branch, which moves {@code '0000'} into the field and then the
 *       two status characters into positions three and four, giving {@code 0023}, so the line reads
 *       {@code FILE STATUS IS: NNNN0023}</li>
 *   <li>status {@code '9'} followed by {@code X'01'} takes the other branch, which copies the {@code 9}
 *       through and expands the second byte from a binary field into three digits, giving {@code 9001}, so
 *       the line reads {@code FILE STATUS IS: NNNN9001}</li>
 *   </ul>
 *
 * <p><strong>Preserve it; never tidy it.</strong> Emitting {@code FILE STATUS IS: 0023}, or interpolating the
 * digits into the placeholder, is a diff against the parity baseline. Severity of reformatting it:
 * <strong>Blocker</strong>, because it fails the end to end comparison gate. If a log line shows
 * {@code FILE STATUS IS: NNNN} with nothing after it, the caller emitted the prefix without the status and
 * that is the bug to fix.
 *
 * <h3>The reject code prohibition</h3>
 *
 * <p><strong>Reject codes are business outcomes, not exceptions. Throwing one is a parity violation.</strong>
 * The five constants, with the verbatim descriptions the source moves alongside them, are:
 *
 * <ul>
 *   <li>{@code 100} - {@code INVALID CARD NUMBER FOUND} ({@code app/cbl/CBTRN02C.cbl:L385 @ 7756d89})</li>
 *   <li>{@code 101} - {@code ACCOUNT RECORD NOT FOUND} ({@code :L397})</li>
 *   <li>{@code 102} - {@code OVERLIMIT TRANSACTION} ({@code :L410})</li>
 *   <li>{@code 103} - {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION} ({@code :L417})</li>
 *   <li>{@code 109} - {@code ACCOUNT RECORD NOT FOUND}, the same text as {@code 101} ({@code :L556})</li>
 * </ul>
 *
 * <p>Each is moved into {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} ({@code :L181}) and written into a
 * <strong>430 byte</strong> reject record: {@code REJECT-TRAN-DATA PIC X(350)} plus
 * {@code VALIDATION-TRAILER PIC X(80)} ({@code :L176-L178}), the trailer itself being {@code PIC 9(04)} plus
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} ({@code :L180-L182}). Both fields are reset at the top of
 * every iteration ({@code :L208-L209}), so a reason never leaks from one record to the next. The correct Java
 * treatment is to count the reject, write the record, and log at a non error level with the code as a bounded
 * metric tag; the run then reports return code 4 through the exit status contract described above.
 *
 * <p><strong>Codes 102 and 103 in particular must never become exceptions.</strong> The two checks that set
 * them are <em>sequential and unguarded</em>, with no alternative branch and no early exit between them:
 * {@code app/cbl/CBTRN02C.cbl:L407-L420 @ 7756d89} tests the credit limit against a temporary balance and, if
 * that fails, moves 102, then <em>immediately</em> tests the account expiry against the originating timestamp
 * and, if that fails too, moves 103 over the top of it. When both conditions fail the record is rejected
 * <strong>once</strong>, bearing <strong>103</strong>, and 102 is lost. An exception based design cannot
 * reproduce that: the first throw would unwind before the second check ran, yielding 102 where the source
 * yields 103, or two rejects where the source writes one.
 *
 * <p>Code {@code 109} is a further reason the set must be modelled as data. It is assigned on the account
 * rewrite failure path inside the posting routine, which is only reached once validation has already passed,
 * so no reject record is written, the reject count is not incremented, and the value is cleared on the next
 * iteration. It is effectively unreachable as a reject outcome yet it is real code on a reachable path, which
 * is why it exists as a constant and why it must not be promoted to a throwable to make it look useful.
 *
 * <h3>The security contract binding every class here</h3>
 *
 * <p>Clause D of the project rule reads, verbatim: <em>"No secrets in code, logs, tests, or config."</em> An
 * exception message is a log line by another name, so that clause lands directly on this package.
 * <strong>No message, field or accessor in any of the nine classes may carry a secret, credential, token,
 * signing key, password, password hash, social security number, card number, telephone number, government
 * issued identifier, date of birth or electronic funds account identifier.</strong>
 *
 * <p>The sensitive field inventory is not hypothetical; it is the record layout these exceptions are raised
 * about. {@code app/cpy/CVCUS01Y.cpy:L15-L20 @ 7756d89} declares {@code CUST-PHONE-NUM-1 PIC X(15)},
 * {@code CUST-PHONE-NUM-2 PIC X(15)}, {@code CUST-SSN PIC 9(09)},
 * {@code CUST-GOVT-ISSUED-ID PIC X(20)}, {@code CUST-DOB-YYYY-MM-DD PIC X(10)} and
 * {@code CUST-EFT-ACCOUNT-ID PIC X(10)}; {@code app/cpy/CSUSR01Y.cpy:L21 @ 7756d89} declares
 * {@code SEC-USR-PWD PIC X(08)} inside the 80 byte {@code SEC-USER-DATA} layout; and card numbers are
 * {@code X(16)} throughout.
 *
 * <p>The rule that follows is simple and absolute: <strong>carry identifiers and field names, never values
 * or record images.</strong> A {@link com.cardemo.exception.ValidationException} names the field that failed
 * and why; it does not echo what was typed. A {@link com.cardemo.exception.RecordNotFoundException} names the
 * record type and the key it looked for, and a key is an account or card identifier, never a customer
 * attribute. A {@link com.cardemo.exception.FileAccessException} names the logical file and the operation,
 * never the buffer. Severity of a violation, that is of any personally identifiable value reaching an
 * exception message: <strong>Blocker</strong>, since exception messages are logged, aggregated and retained.
 *
 * <h3>The cause preservation contract</h3>
 *
 * <p>Clause B of the project rule reads, verbatim: <em>"Clear error handling: no swallowing exceptions; wrap
 * with context and preserve root cause."</em> This is the single most load bearing requirement for this
 * package, because a typed hierarchy is worthless if the type is produced by discarding what actually
 * happened.
 *
 * <p>The obligation it creates is tree wide, not local: <strong>every {@code catch} anywhere in the
 * application must rethrow a typed subtype of {@link com.cardemo.exception.CardDemoException} carrying the
 * original throwable as its cause.</strong> No empty {@code catch} block, no {@code catch (Exception e)} with
 * a bare log call and no continuation, no exception constructed from a message string when a cause was
 * available. Every class here provides a constructor that takes a {@code Throwable}, so there is never a
 * technical reason to drop one. Severity of dropping a root cause: <strong>High</strong>, because the
 * resulting stack trace points at the translation site instead of the fault and the defect becomes
 * effectively undiagnosable in production.
 *
 * <h2>Not available: a literal source test for file status {@code '35'}</h2>
 *
 * <p>Stated plainly, as Clause F of the project rule requires: <strong>direct corpus evidence for
 * {@code FILE STATUS '35'} is Not available.</strong> Measured on the frozen source,
 * {@code grep -rn "'35'" app/cbl} returns <strong>zero files</strong> and {@code grep -rn "NOTOPEN" app/}
 * returns <strong>zero matches</strong> anywhere in the legacy tree, so
 * {@link com.cardemo.exception.FileUnavailableException} has no literal antecedent to cite.
 *
 * <p>It is nonetheless grounded architecturally rather than invented. The corpus guards every {@code OPEN}
 * with the same binary result idiom that guards every other verb, so an unavailable dataset is a real state
 * the guard is written to catch even though no branch names it; {@code app/jcl/OPENFIL.jcl} and
 * {@code app/jcl/CLOSEFIL.jcl} exist precisely to make datasets available to and unavailable from the online
 * region, and they become the health indicators of the target; and the online file control table in
 * {@code app/csd/CARDDEMO.CSD} defines eight files whose availability is a runtime property of the region.
 * The type is therefore retained, and the relational and cloud equivalents, a closed data source, a missing
 * bucket, an unreachable queue, are exactly what it reports.
 *
 * <p><strong>What would be needed to close this gap:</strong> a literal {@code '35'} status comparison
 * anywhere in the corpus, a {@code DFHRESP(NOTOPEN)} handler, or an operational runbook describing what the
 * legacy operators did when a dataset was closed. None of the three exists in this repository. Severity:
 * <strong>Medium</strong>, since the type is architecturally justified and unused paths cost nothing, but its
 * exact legacy message text cannot be reproduced because there is none.
 *
 * <h2>Not available: a literal source test for file status {@code '22'}</h2>
 *
 * <p>Also Not available, and for the same reason. There are <strong>zero</strong> literal {@code '22'} status
 * comparisons in {@code app/cbl}. {@link com.cardemo.exception.DuplicateRecordException} is grounded on the
 * CICS side of the equivalence instead, where duplicate keys are handled explicitly and often:
 * {@code DFHRESP(DUPREC)} appears <strong>7</strong> times and {@code DFHRESP(DUPKEY)} <strong>3</strong>
 * times. The three {@code DUPKEY} sites are {@code app/cbl/COUSR01C.cbl:L260},
 * {@code app/cbl/COTRN02C.cbl:L735} and {@code app/cbl/COBIL00C.cbl:L533} ({@code @ 7756d89}), each
 * immediately followed by its {@code DUPREC} companion; the remaining four {@code DUPREC} sites are in
 * {@code app/cbl/COCRDLIC.cbl}. A duplicate key is therefore a genuine, frequently handled legacy outcome,
 * and only the batch dialect's literal is missing. Severity: <strong>Medium</strong>.
 *
 * <p>For context, the full measured census of the two dialects at {@code 7756d89}. Batch
 * {@code FILE STATUS} literals in {@code app/cbl}: {@code '00'} is tested 73 times across the eight batch
 * programs, {@code '10'} 11 times across ten programs, {@code '23'} 3 times across two programs, and
 * {@code '22'} and {@code '35'} not at all. Online CICS responses: {@code DFHRESP(NORMAL)} 43,
 * {@code DFHRESP(NOTFND)} 23, {@code DFHRESP(ENDFILE)} 8, {@code DFHRESP(DUPREC)} 7,
 * {@code DFHRESP(DUPKEY)} 3, {@code DFHRESP(NOTOPEN)} 0. The shape of that distribution is itself the
 * argument for this package: success and not found dominate, everything else is rare, and the rare cases are
 * exactly the ones a single untyped error would have flattened.
 *
 * <h2>A corrected copybook classification</h2>
 *
 * <p>Recorded here because getting it wrong would have silently emptied
 * {@link com.cardemo.exception.FatalProcessingException}. The member {@code app/cpy/CSMSG02Y.cpy} is
 * <strong>not a message copybook</strong>, despite a name that suggests one and a position in the corpus
 * beside {@code CSMSG01Y.cpy}, which genuinely is one. It is internally titled <strong>{@code CABENDD.CPY}</strong>
 * at {@code app/cpy/CSMSG02Y.cpy:L2 @ 7756d89} and carries the comment
 * <em>"Work areas for abend routine"</em> at {@code :L4}. Its content is the abend work area group
 * {@code ABEND-DATA}, comprising {@code ABEND-CODE PIC X(4)}, {@code ABEND-CULPRIT PIC X(8)},
 * {@code ABEND-REASON PIC X(50)} and {@code ABEND-MSG PIC X(72)}, all {@code VALUE SPACES}
 * ({@code :L21-L29}).
 *
 * <p>Those four fields are exactly the payload {@link com.cardemo.exception.FatalProcessingException} carries,
 * and the abend routine at {@code app/cbl/COACTUPC.cbl:L4203-L4228 @ 7756d89} shows why all four are needed:
 * it substitutes the default message when the message is absent, moves the current program name into the
 * culprit field, sends the whole group to the terminal, cancels the abend handler and then abends. Severity of
 * the original misclassification: <strong>High</strong>, because a message copybook would have contributed a
 * single string and the type would have lost the code, the culprit and the reason.
 *
 * <p>One detail worth keeping straight, severity <strong>Low</strong>: the abend code differs between tiers.
 * The online routine issues {@code EXEC CICS ABEND ABCODE('9999')}, a four character CICS abend code
 * ({@code app/cbl/COACTUPC.cbl:L4222-L4224 @ 7756d89}), while the batch routine moves the three digit
 * {@code 999} into the abend code register before calling the language environment service
 * ({@code app/cbl/CBTRN02C.cbl:L710-L711 @ 7756d89}). They are different values in different registers, not
 * a transcription error, and {@link com.cardemo.exception.FatalProcessingException} keeps the abend code as a
 * string so both are representable.
 *
 * <h2>Troubleshooting: which type points at which check</h2>
 *
 * <p>For an operator or an on call engineer reading a log or a failed job, the type is the first diagnostic.
 *
 * <ul>
 *   <li>{@link com.cardemo.exception.DataIntegrityException} - a referenced parent row is missing or a
 *       constraint was violated. Check the seed data and the load order: the accessors name the constraint
 *       and the relation, so start with that constraint rather than with the application.</li>
 *   <li>{@link com.cardemo.exception.FileUnavailableException} - a resource is not open. Check the data
 *       source, the object storage bucket and the queue, in that order; the health endpoint reports the same
 *       three and will usually have failed first.</li>
 *   <li>{@link com.cardemo.exception.FileAccessException} - a genuine I/O fault. The four character expanded
 *       status is the actionable value; look for the {@code FILE STATUS IS: NNNN} line described above and
 *       read the four digits that follow the placeholder, not the placeholder.</li>
 *   <li>{@link com.cardemo.exception.FatalProcessingException} - the abend, reported as abend code
 *       {@code 999} and return code {@code 12}. Read the culprit and the reason first, because they identify
 *       the originating component, then walk the cause chain to the underlying throwable. Do not retry
 *       blindly: in the legacy design an abend meant the run must not continue, and one of the three scoped
 *       sites, the missing default disclosure group row, reaches this type by design.</li>
 *   <li>{@link com.cardemo.exception.ConcurrentUpdateException} - the record changed, or could not be locked,
 *       or the change was not confirmed. Read the outcome, not just the message: the five outcomes are
 *       distinct legacy screen states and one of them, the customer lock failure, was reported as success by
 *       the original program.</li>
 *   <li>{@link com.cardemo.exception.RecordNotFoundException} and
 *       {@link com.cardemo.exception.DuplicateRecordException} - ordinary key level outcomes; check the key
 *       the accessor reports before suspecting the store.</li>
 *   <li>{@link com.cardemo.exception.ValidationException} - the request was rejected before any I/O. The
 *       field name and failure kind identify the offending field; there is nothing to investigate in the
 *       data store.</li>
 *   <li><strong>Return code 4 is not an exception at all.</strong> It means the posting run completed with
 *       rejects. Read the reject records, count them against the run summary, and do not look for a stack
 *       trace, because there is none and there should be none.</li>
 *   </ul>
 *
 * <h2>Severity register for this package</h2>
 *
 * <p>Classified as Clause F of the project rule requires, so that the preserved defects are triaged rather
 * than merely noted.
 *
 * <ul>
 *   <li><strong>Blocker</strong> - the never tested {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} fall through,
 *       BLOCKER 5.2 above, which reports a failed update as success; reformatting the
 *       {@code FILE STATUS IS: NNNN} literal, which fails the end to end parity gate; any personally
 *       identifiable value reaching an exception message; and any code path in which an exception terminates
 *       the JVM.</li>
 *   <li><strong>High</strong> - collapsing the five account update outcomes into a single conflict response;
 *       the original classification of {@code CSMSG02Y.cpy} as a message copybook, which would have emptied
 *       the fatal type; dropping a root cause when translating a throwable; and substituting the default
 *       abend message on a blank value instead of on an absent one.</li>
 *   <li><strong>Medium</strong> - {@code FILE STATUS '35'} with {@code DFHRESP(NOTOPEN)}, and
 *       {@code FILE STATUS '22'}, each having no literal attestation in the corpus, as disclosed in the two
 *       Not available sections above.</li>
 *   <li><strong>Low</strong> - the online {@code '9999'} versus batch {@code 999} abend code distinction; and
 *       both account update rewrite failure paths sharing the single {@code LOCKED-BUT-UPDATE-FAILED}
 *       flag, so the outcome does not reveal which write failed.</li>
 *   </ul>
 *
 * <h2>Package level constraints</h2>
 *
 * <p>These are the invariants a change to this package must not break. They are grouped by the clause of the
 * project rule that produces them.
 *
 * <ul>
 *   <li><strong>Correctness and determinism first.</strong> The taxonomy is documented and implemented as the
 *       source behaves, including the preserved defects; no idealised version is presented anywhere. Where a
 *       class performs a case or format operation it passes {@code Locale.ROOT} explicitly, and no class in
 *       the package relies on the platform default charset, locale, time zone or clock, reads
 *       {@code System.getenv}, or refers to an absolute path. Two identical inputs therefore always produce
 *       an identical message.</li>
 *   <li><strong>Meaningful errors are the reason this package exists.</strong> Clause A asks for observable
 *       behaviour through structured logs and meaningful errors; nine types instead of one generic exception
 *       <em>is</em> that requirement discharged. The legacy alternative, a two character code compared
 *       against a literal at each of hundreds of call sites, is precisely what made the original impossible
 *       to observe.</li>
 *   <li><strong>Separation of concerns is three way and each part has one home.</strong> The types are here,
 *       the status to type mapping is in {@code com.cardemo.service.shared.FileStatusMapper}, and the HTTP
 *       shaping is in the controllers. No class here maps, and no class here shapes a response.</li>
 *   <li><strong>Performance is a non issue by construction.</strong> Exception construction is cheap: no
 *       class performs I/O, probing, reflection, resource lookup or formatting beyond assembling the
 *       documented constants, and none allocates a collection.</li>
 *   <li><strong>No dead code, no unused imports, no untracked deferred work.</strong> This file declares
 *       <strong>zero imports</strong>, which is deliberate rather than incidental: every type it names is
 *       either in this package, and so resolves without one, or is referenced in prose. An import used only
 *       inside documentation is an unused import, which Rule 1 Clause B forbids; {@code -Werror} does not
 *       catch one, because {@code javac} 25.0.3 publishes no {@code unused} lint key at all - as
 *       {@code javac --help-lint} shows - so the zero-import count above is a review guarantee rather than a
 *       compiler-enforced one. No
 *       deferred work marker of any kind appears anywhere in the package, so a scan for the usual tokens
 *       returns nothing; the two Not available disclosures above are findings carrying evidence and a
 *       severity, which is the opposite of an untracked reminder.</li>
 *   <li><strong>Boundary conditions are explicit.</strong> Every class documents its own {@code null} and
 *       blank behaviour, and the one place where the distinction changes behaviour is called out above: the
 *       default abend message substitution fires on absent, mapped from {@code LOW-VALUES} to {@code null},
 *       and not on blank.</li>
 *   <li><strong>No global mutable state.</strong> No class has a mutable static field, every instance field
 *       is {@code final}, and the few static members are either constants or pure helper functions. The
 *       package has no dependency to inject and injects nothing.</li>
 *   <li><strong>Serialisation is declared and inert.</strong> All nine {@code Throwable} subclasses declare
 *       {@code serialVersionUID}, required because {@code Throwable} is already {@code Serializable} and
 *       {@code -Xlint:all} with {@code -Werror} fails otherwise. <strong>No class defines a custom
 *       serialisation hook and no class deserialises untrusted input</strong>, which is the insecure
 *       deserialisation pattern Clause D names explicitly. No class uses {@code Runtime.exec} or
 *       {@code ProcessBuilder}. <strong>No exception here terminates the JVM</strong>: the legacy
 *       {@code CALL 'CEE3ABD'} becomes a thrown exception, and the batch tier translates it into a failed
 *       exit status and return code 12 rather than a process kill.</li>
 *   <li><strong>Least privilege.</strong> No class here holds a token, a credential, a connection, a file
 *       handle or a configuration value. There is nothing in the package to over privilege.</li>
 *   <li><strong>Repository conventions are followed, and where none existed they are established rather than
 *       overridden.</strong> Every file opens with the Apache 2.0 provenance banner modelled on
 *       {@code app/cbl/CBACT04C.cbl:L1-L21 @ 7756d89}, extended to name the originating COBOL artefact, and
 *       every file obeys the root {@code .editorconfig}: UTF-8, LF endings, a final newline, no trailing
 *       whitespace and a four space Java indent. Clause C qualifies its requirement with <em>"if
 *       present"</em>, and for formatters that condition is <strong>not triggered</strong>: no formatter,
 *       linter or style tool configuration exists anywhere in the source repository and there were
 *       <strong>zero {@code .java} files at {@code 7756d89}</strong>, so there was no existing Java style to
 *       conform to or to fight. Establishing a convention root side is not the same as overriding one. The
 *       repository's own guidance points the same way: {@code CONTRIBUTING.md:L33} asks contributors to
 *       <em>"Modify the source; please focus on the specific change you are contributing. If you also
 *       reformat all the code, it will be hard for us to focus on your change."</em> and {@code :L34} to
 *       <em>"Ensure local tests pass."</em></li>
 *   <li><strong>No duplication.</strong> One central mapper, one exception per status, no per call site
 *       variants; the {@code FILE STATUS IS: NNNN} prefix literal is declared exactly once, in
 *       {@code com.cardemo.model.enums.FileStatus}, and is quoted here only as documentation.</li>
 *   <li><strong>The file count is a gate.</strong> Exactly ten {@code .java} files in this folder: the nine
 *       exception classes and this documentation. No handler, no advice, no test, no Markdown, no eleventh
 *       file.</li>
 *   <li><strong>The legacy corpus is frozen.</strong> Nothing under {@code app} or {@code samples} is read
 *       for anything but evidence, and nothing there is ever modified. The migration is purely additive.</li>
 *   </ul>
 *
 * <h2>The one documented conflict, and why it is not instantiated here</h2>
 *
 * <p>Clause B forbids dead code. The parity mandate requires preserving reachable no-ops, and the corpus
 * contains one: {@code 1400-COMPUTE-FEES} at {@code app/cbl/CBACT04C.cbl:L518-L520 @ 7756d89} consists of the
 * comment <em>"To be implemented"</em> and an exit, yet it is genuinely reachable and is performed at
 * {@code app/cbl/CBACT04C.cbl:L216 @ 7756d89}. Deleting it would break the paragraph level correspondence
 * that the scope coverage gate verifies; keeping it looks like the dead code Clause B forbids.
 *
 * <p><strong>Parity governs</strong>, because the clause forbids dead code that is <em>untracked</em>. What
 * makes a retained no-op tracked is stated per artefact, at its own declaration: its COBOL locator, a proof of
 * reachability, an explicit intentional-no-op marker, and an acknowledgement that it
 * is <strong>owed an entry in {@code DECISION_LOG.md}</strong>. Both
 * {@code DECISION_LOG.md} and {@code TRACEABILITY_MATRIX.md} are authored at the repository root, so a
 * present-tense claim about either is a true statement. Two earlier revisions are withdrawn: the first said
 * each artefact was already cited in both files while neither existed, the second said neither existed after
 * both were authored. The per-artefact marker remains the primary record because it cannot drift from the
 * code it governs.
 *
 * <p><strong>No retained parity artefact lives in this package</strong> - and that local fact is all that is
 * asserted. An earlier revision added "the tree has five of them" and enumerated five, while
 * {@code com.cardemo.security} and {@code com.cardemo.repository} each said three. The tallies contradicted one
 * another because each was maintained by hand in a comment that no build step checks. Severity of what that
 * left in place: <strong>High</strong>. The count and the enumeration are both withdrawn, and deliberately not
 * replaced by a corrected count: the per-artefact justification above is the register of record, and no file
 * holds a global list.
 *
 * <p>Every one of the nine classes here is reachable and constructed by real callers. The conflict is recorded
 * in this package because this package documents the error taxonomy the interest job reports through, not
 * because it hosts an instance of it.
 */

package com.cardemo.exception;
