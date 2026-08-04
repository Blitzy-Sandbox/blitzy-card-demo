/*
 * ******************************************************************
 * Program     : package-info.java
 * Component   : com.cardemo (package root)
 * Package     : com.cardemo
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 * Function    : Root package of the Java target that replaces the
 *               CardDemo z/OS COBOL, CICS, VSAM, JCL and BMS
 *               application with one deployable Spring Boot modular
 *               monolith, and the package level discharge of the
 *               documentation clause for this whole source tree.
 * Source      : app/csd/CARDDEMO.CSD (18 transactions, 18 programs,
 *               17 mapsets, 8 files, TDQUEUE(JOBS) at :L499) @ 7756d89
 * Source      : app/cbl/** (28 programs, 19,254 lines) @ 7756d89
 * Source      : app/cpy/** (28 copybooks, 11 record layouts) @ 7756d89
 * Source      : app/cpy-bms/** (17 symbolic maps, 441 input
 *               fields) @ 7756d89
 * Source      : app/jcl/** (29 members, matched case
 *               insensitively) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt (10 clusters, 3 alternate
 *               indexes, 7 GDG bases, 3,956 lines) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L1-L21 (the banner convention
 *               this header reproduces) @ 7756d89
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
 * Root package of the CardDemo credit card management application, reimplemented on Java 25 LTS and Spring
 * Boot 3.5.11 as a behaviour preserving replacement for the z/OS original.
 *
 * <p>This is the highest level documentation artefact inside the Java source tree. Everything below it is
 * organised into nine direct subpackages, each of which carries its own package documentation for the detail
 * that belongs there. This file states what the tree as a whole is, how to build, run and test it, the
 * configuration contract it publishes, and how it fails; it deliberately summarises and points rather than
 * restating what a subpackage already documents.
 *
 * <h2>What it does</h2>
 *
 * <p><strong>What it does.</strong> {@code com.cardemo} is the root of the Java implementation that replaces
 * the AWS CardDemo credit card management application, previously <strong>19,254 lines of IBM Enterprise
 * COBOL across 28 programs</strong> executing under CICS, VSAM, JCL and BMS on z/OS. The online layer was 17
 * pseudo conversational screen programs driving 3270 terminals through a COMMAREA and 17 BMS mapsets; the
 * batch layer was a JES2 job stream over 29 JCL members with DFSORT and IDCAMS utility steps; the data layer
 * was 10 VSAM KSDS clusters with 3 alternate indexes and 7 generation data group bases. The only
 * instrumentation in the entire corpus was {@code DISPLAY} to SYSOUT.
 *
 * <p>The target is <strong>a single deployable JAR: a modular monolith, and explicitly not
 * microservices</strong>. The decisive constraint is atomicity, and it is a constraint read out of the
 * source rather than a preference. {@code app/cbl/COACTUPC.cbl} performs
 * {@code EXEC CICS SYNCPOINT ROLLBACK} at {@code :L4098-L4102} across a unit of work that spans an account
 * rewrite and a customer rewrite, and {@code app/cbl/CBTRN02C.cbl} paragraph
 * {@code 2000-POST-TRANSACTION} at {@code :L424-L444} commits a transaction category balance upsert
 * ({@code :L440}), an account update ({@code :L441}) and a transaction insert ({@code :L442}) together.
 * Distributing those writes across service boundaries would require compensating transactions and sagas,
 * would change failure semantics, and would therefore forfeit parity. One process, one transaction manager,
 * one atomic boundary.
 *
 * <p>The legacy corpus in {@code app/} is <strong>frozen and read only</strong>, and is referenced only by commit SHA
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec} (short {@code 7756d89}). <strong>No COBOL file is copied into
 * {@code src/}.</strong> The migration is purely additive: the Java tree sits beside the legacy tree in the same
 * repository. That tree holds three roles simultaneously and loses all three the moment it is edited. It is the
 * parity oracle that boundary comparison runs against, the field contract source from which every entity width, DTO
 * shape and record geometry is derived, and the traceability anchor that the planned {@code TRACEABILITY_MATRIX.md}
 * will cite by SHA. The same freeze applies to {@code samples/}, which holds z/OS compile templates and binary
 * emulator bundles that {@code pom.xml} supersedes conceptually and from which nothing is ported.
 *
 * <h2>The nine direct subpackages</h2>
 *
 * <p>Counts below are stated as <strong>present / target</strong> wherever the two differ, so that a planned
 * type is never read as a delivered one. The authoritative dated inventory, with the measurement command that
 * reproduces it, is section 0.4.5.1 of {@code docs/technical-specifications.md}; it governs, and this list
 * defers to it rather than competing with it.
 *
 * <ul>
 *   <li>{@code com.cardemo.config} - <strong>6 present / 6 target</strong>. AWS, batch, JPA, observability,
 *       security and web configuration; {@code BatchConfig} and {@code ObservabilityConfig} are authored and
 *       no longer outstanding. Derived from the JCL dataset wiring, the CICS file control table of
 *       {@code app/csd/CARDDEMO.CSD} and the batch job topology.</li>
 *   <li>{@code com.cardemo.security} - 3 classes. Token issue and validation plus user details lookup,
 *       replacing COMMAREA identity propagation across {@code EXEC CICS XCTL}. Derived from
 *       {@code app/cbl/COSGN00C.cbl} (260 lines), {@code app/cpy/CSUSR01Y.cpy} (the 80 byte user security
 *       layout) and the {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} fields of
 *       {@code app/cpy/COCOM01Y.cpy}.</li>
 *   <li>{@code com.cardemo.model} - a container package with no classes of its own and four leaves:
 *       {@code entity} 11, {@code key} 3, {@code enums} 4, {@code dto} 17. Field contracts derived from the
 *       11 record layout copybooks of {@code app/cpy/**} and the 17 BMS symbolic maps of
 *       {@code app/cpy-bms/**}.</li>
 *   <li>{@code com.cardemo.repository} - 11 interfaces. Spring Data JPA over the 10 VSAM KSDS clusters
 *       catalogued in {@code app/catlg/LISTCAT.txt} plus the daily transaction staging dataset. Three
 *       derived finders replace the three alternate indexes.</li>
 *   <li>{@code com.cardemo.service} - <strong>21 present / 21 target</strong>, across 9 leaves
 *       ({@code auth} 1, {@code account} 2, {@code card} 3, {@code transaction} 3, {@code billing} 1,
 *       {@code report} 1, {@code admin} 4, {@code menu} 2, {@code shared} 4). One bean
 *       per online COBOL program, plus four shared services covering date validation, lookup tables, file
 *       status translation and the file access call contract. The service layer is complete:
 *       {@code admin/UserDeleteService} from {@code app/cbl/COUSR03C.cbl} is authored, with that program's
 *       absent self-delete guard preserved rather than closed.</li>
 *   <li>{@code com.cardemo.controller} - <strong>6 present / 8 target</strong>, exposing
 *       <strong>12 operations today of a target 17</strong>. REST adapters for the 17 sourced CICS
 *       transactions. Planned: {@code AuthController} (sign-on, 1 operation) and {@code AdminController}
 *       (4 operations at {@code /api/admin/*}), which is the reconciliation 12 + 1 + 4 = 17.</li>
 *   <li>{@code com.cardemo.batch} - a container package with four leaves: {@code jobs}
 *       <strong>1 present / 6 target</strong>, {@code processors} 5, {@code readers}
 *       <strong>5 present / 7 target</strong>, {@code writers} 3. Spring Batch replacing the JCL job stream,
 *       the DFSORT specifications and the IDCAMS control cards. The two leaf documents name each planned
 *       job and reader individually.</li>
 *   <li>{@code com.cardemo.exception} - 9 classes. A typed hierarchy replacing COBOL {@code FILE STATUS}
 *       inspection and CICS response code branching, including the abend payload of
 *       {@code app/cpy/CSMSG02Y.cpy}.</li>
 *   <li>{@code com.cardemo.observability} - 3 classes. Correlation, metrics and health. This is capability
 *       the legacy corpus <strong>entirely lacks</strong>, so it is new work rather than a translation.</li>
 *   </ul>
 *
 * <p>The single class in this root package is the application entry point,
 * {@code com.cardemo.CardDemoApplication}, declared as the Spring Boot main class through the
 * {@code start-class} property in the root {@code pom.xml}.
 *
 * <h2>Directory shape: the target is 132 {@code .java} files, of which 14 are {@code package-info.java}</h2>
 *
 * <p>The <strong>target</strong> for this tree is 132 {@code .java} files, and that count is a gate rather
 * than an observation. The target arithmetic is 1 bootstrap class, then 6 config, 3 security, 11 entity, 3
 * key, 4 enums, 17 DTO, 11 repository, 21 service, 8 controller, 6 batch jobs, 5 batch processors, 7 batch
 * readers, 3 batch writers, 9 exception and 3 observability, giving 118 production classes, plus
 * <strong>14 {@code package-info.java}</strong> files, for 132 in total.
 *
 * <p><strong>The tree has not yet reached that target, and this document does not claim it has.</strong>
 * Thirteen production classes remain to be authored: 2 config, 1 service, 2 controller, 5 batch jobs and 3
 * batch readers. Each is named individually in the document of the package that will own it, so that no
 * planned type is mistaken for a delivered one. For the measured file counts as of a specific date, together
 * with the command that reproduces them, see section 0.4.5.1 of {@code docs/technical-specifications.md},
 * which is the single authoritative dated inventory for this repository and governs over any count quoted in
 * a Javadoc comment.
 *
 * <p><strong>The fourteen-location census of package documentation is withdrawn.</strong> An earlier revision of
 * this paragraph asserted that exactly 14 such files existed "at exactly these locations and nowhere else",
 * naming {@code com/cardemo}, {@code config}, {@code security}, {@code model/entity}, {@code model/key},
 * {@code model/enums}, {@code model/dto}, {@code repository}, {@code service}, {@code controller},
 * {@code batch}, {@code batch/jobs}, {@code exception} and {@code observability}. That is no longer accurate in
 * either direction - the nine {@code service} leaves and the three further {@code batch} leaves each carry their
 * own document, while {@code batch} itself carries none - and a hand-maintained list of locations is exactly the
 * kind of claim that drifted before. It is replaced by the invariant rather than by a corrected list: one
 * document per package that contains a type, plus the bounded allow-list of intermediate packages described
 * below, asserted by {@code PackageDocumentationInventoryTest}. Measure it rather than quoting it, with
 * {@code find src/main/java -name 'package-info.java' | wc -l}.
 *
 * <p><strong>Dated readings, offered as a sample and not as a specification.</strong> At commit
 * {@code 2e087c4} on 3 August 2026: 105 types, 24 packages containing a type, and therefore 24
 * {@code package-info.java} files. Re-measure rather than quote:
 *
 * <pre>{@code find src/main/java -name '*.java' ! -name package-info.java | wc -l
 * find src/main/java -name package-info.java | wc -l
 * }</pre>
 *
 * <p>Two constraints on the shape do survive as rules, because neither is a count. The first governs the three
 * intermediate packages {@code com/cardemo/model}, {@code com/cardemo/service} and {@code com/cardemo/batch},
 * none of which contains a type, so none receives a document under the bijection above and each one's leaves
 * document themselves. <strong>{@code com/cardemo/service} is the single deliberate exception</strong>, and an
 * earlier revision of this paragraph named it alongside the other two as carrying no document - that is no longer
 * true and the claim is withdrawn. It carries a layer document because it spans nine leaves and 21 beans
 * translated from 17 separate COBOL programs, and one toolchain, one configuration contract, one exception
 * vocabulary and one paragraph-correspondence mandate bind all 21; repeating those in nine leaves is the
 * duplication Clause C forbids, and stating them nowhere fails Clause E for the layer. The exception is bounded
 * rather than open: {@code PackageDocumentationInventoryTest} holds it in an explicit allow-list, asserts that
 * every listed package really is type-less and really is documented, and still rejects every other type-less
 * package - so {@code model} and {@code batch} remain undocumented by rule and not by accident. The second
 * constraint is that <strong>no {@code README} or other Markdown file may be added anywhere under
 * {@code src/main/java}</strong>: the package documentation files are the module documentation, and Markdown
 * there would be a second, unmaintained copy of it.
 *
 * <h2>Migration mapping at a glance</h2>
 *
 * <ul>
 *   <li>17 CICS screen programs to a target of 21 service beans behind 8 controllers exposing 17
 *       operations. This mapping states the migration design; for what is authored versus planned see
 *       the present/target counts in the subpackage list above.</li>
 *   <li>10 VSAM KSDS clusters and 3 alternate indexes to 11 JPA entities, 3 Flyway migrations and 3 B-tree
 *       indexes over PostgreSQL 16.</li>
 *   <li>The JCL job stream to a 5 stage Spring Batch pipeline. JCL {@code COND} gating becomes a
 *       {@code JobExecutionDecider}; DFSORT specifications become {@code java.util.Comparator} instances;
 *       IDCAMS {@code REPRO} becomes a batched JDBC load. No external sort process is ever spawned.</li>
 *   <li>Generation data group generations to S3 keys under a timestamp or job instance prefix over a
 *       versioned bucket, with record length preserved byte exactly at the boundary.</li>
 *   <li>The {@code JOBS} transient data queue, defined at {@code app/csd/CARDDEMO.CSD:L499} as
 *       {@code TYPE(EXTRA) RECORDSIZE(80) RECORDFORMAT(FIXED)}, to an SQS FIFO queue. The JES2 internal
 *       reader becomes a queue listener.</li>
 *   <li>{@code CALL 'CSUTLDTC'} together with its two work area copybooks to one injected date validation
 *       bean. {@code CALL 'CBSTM03B'} to one injected file access bean.</li>
 *   <li>{@code PIC S9(n)V99}, {@code COMP-3} and {@code COMP} to {@code java.math.BigDecimal} over
 *       {@code NUMERIC(p,2)} columns. Plaintext passwords to BCrypt hashes. {@code FILE STATUS} values to
 *       typed exceptions on every I/O path.</li>
 *   </ul>
 *
 * <p>One CICS transaction has no Java counterpart, and its absence is documented rather than papered over.
 * {@code app/csd/CARDDEMO.CSD} defines <strong>18</strong> transactions and <strong>18</strong> programs but
 * only <strong>17</strong> mapsets. The eighteenth transaction is {@code CDV1} at {@code :L388}, which
 * fronts {@code COCRDSEC} - and <strong>{@code COCRDSEC} has no source anywhere in the repository</strong>.
 * The name occurs at exactly two places, both inside the CSD itself: {@code :L211}
 * ({@code DEFINE PROGRAM(COCRDSEC)}) and {@code :L390}. It is a dangling legacy definition with nothing to
 * translate, so <strong>no endpoint is invented for it</strong>. That is why the controller layer exposes 17
 * operations and not 18. Three further programs are absent from the CSD for the opposite reason:
 * {@code CSUTLDTC} is a statically called subprogram, and {@code CBSTM03A} and {@code CBSTM03B} are batch.
 *
 * <h2>The parity mandate</h2>
 *
 * <p><strong>Behavioural parity across all 22 catalogued features is the acceptance contract, not a goal.</strong>
 * Known legacy quirks are <strong>preserved and documented, never corrected</strong>. Where a deviation is
 * unavoidable or genuinely beneficial it is labelled as a deviation, justified in writing in the planned
 * {@code DECISION_LOG.md}, and never quietly absorbed as though it were equivalence.
 *
 * <p>This has a consequence that surprises readers of the Java code, so it is stated here at the top rather
 * than left to be discovered. Paragraph level correspondence is preserved: each private method maps to one
 * COBOL paragraph and carries a Javadoc citation naming it. Industry guidance on COBOL modernisation warns
 * against exactly this, on the grounds that transliterating {@code PERFORM} and {@code GO TO} structure into
 * Java produces unreadable code. That guidance is real and the conflict is genuine, but it is resolved in
 * favour of parity, because parity is the contract and the paragraph map must stay mechanically provable.
 * The readability concern is answered by two compensating mechanisms instead of by restructuring: every
 * method cites its source paragraph, and the planned {@code TRACEABILITY_MATRIX.md} will make the correspondence
 * navigable.
 * Where the guidance can be honoured without touching control flow, it is - naming is idiomatic,
 * {@code BigDecimal} replaces packed decimal, and framework mechanisms replace static linkage.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>The build is a single Maven module driven through the pinned wrapper, so no preinstalled Maven is
 * required and the result is reproducible. <strong>The canonical command set, the measured gate results and
 * the measured toolchain are published once, in section 0.4.5.1 of
 * {@code docs/technical-specifications.md}</strong>, and are deliberately not restated here - a coverage
 * percentage or a test count copied into a Javadoc comment is wrong the moment the tree grows. What follows is
 * only the set of build <em>invariants</em>, which are durable because they live in {@code pom.xml}:
 *
 * <ul>
 *   <li><strong>Build and verify.</strong> {@code ./mvnw -B -ntp clean verify}. The wrapper pins Maven
 *       <strong>3.9.11</strong>; the 3.9 line is retained deliberately rather than moving to Maven 4, because
 *       the plugin set this build depends on is validated against 3.9. Prerequisites are capabilities rather
 *       than paths: a JDK 25 toolchain on {@code PATH} with {@code JAVA_HOME} set, however the host provides
 *       it, plus the git-ignored {@code .env} loaded with {@code set -a; . ./.env; set +a}.</li>
 *   <li><strong>Toolchain floors.</strong> {@code maven-enforcer-plugin} asserts Java {@code [25,)} and Maven
 *       {@code [3.9.11,)}, so the build cannot silently run on a wrong toolchain.
 *       {@code maven.compiler.release} is <strong>25</strong> and <strong>no preview features</strong> are
 *       enabled.</li>
 *   <li><strong>Compilation is warning fatal.</strong> The compiler runs {@code -Xlint:all} and
 *       {@code -Werror} with {@code showWarnings}, {@code showDeprecation} and {@code failOnWarning}. A raw
 *       type, an unchecked cast, a deprecation notice or a dangling documentation comment is a build failure.
 *       An <strong>unused import is not</strong>, because {@code javac} 25 publishes no {@code unused} lint
 *       key - the only documentation-adjacent key it does publish is
 *       {@code dangling-doc-comments}. Rule 1 Clause B's prohibition on an unused import is therefore
 *       enforced at review, and malformed Javadoc is caught by a <strong>separate doclint gate</strong> rather
 *       than by the compiler. An earlier revision of this entry added that no Javadoc plugin was bound in
 *       {@code pom.xml}; that is no longer accurate and the claim is withdrawn -
 *       {@code maven-javadoc-plugin} is bound at {@code verify} as the execution {@code doclint-gate}, running
 *       {@code javadoc-no-fork} with {@code doclint} set to {@code all} and {@code failOnWarnings} true, so
 *       malformed documentation fails the build in the ordinary course of {@code clean verify}.</li>
 *   <li><strong>Tests.</strong> Surefire runs the unit tier with the integration and end to end trees excluded
 *       by path and alphabetical order for determinism; Failsafe is bound to {@code verify} and runs the tiers
 *       that use Testcontainers against PostgreSQL 16 and LocalStack.</li>
 *   <li><strong>Coverage.</strong> {@code jacoco-maven-plugin} enforces an <strong>80 percent LINE</strong>
 *       covered ratio at {@code verify}, with no core package exclusions and no getter only padding. The
 *       threshold is a {@code pom.xml} property and must never be lowered there; where a run needs to proceed
 *       below it, that is done on the command line only.</li>
 *   <li><strong>Security scan.</strong> {@code org.owasp:dependency-check-maven} fails the build on high or
 *       critical findings. Its skip property is hyphenated - {@code -Ddependency-check.skip=true} - and
 *       skipping it suppresses <em>only</em> the vulnerability scan, which needs network access to the
 *       vulnerability feed and is slow on a cold cache.</li>
 *   </ul>
 *
 * <p><strong>A dependency pinning detail that will otherwise break the build.</strong> The Testcontainers
 * 2.x line renamed every module artefact. The bare {@code localstack}, {@code postgresql} and
 * {@code junit-jupiter} coordinates that were correct in the 1.x line <strong>do not exist at 2.0.3</strong>.
 * Only the prefixed coordinates resolve: {@code testcontainers}, {@code testcontainers-localstack},
 * {@code testcontainers-postgresql} and {@code testcontainers-junit-jupiter}. Compounding this, Spring Boot
 * 3.5.11 already manages a Testcontainers version from the 1.x line, so the version is overridden through a
 * project <strong>property</strong> set to {@code 2.0.3} rather than by importing a competing bill of
 * materials, which would resolve in an order dependent way. Both halves of that remedy are required:
 * overriding without renaming resolves artefacts that do not exist, and renaming without overriding resolves
 * the wrong version.
 *
 * <p><strong>Run.</strong> {@code java -jar target/carddemo-1.0.0.jar} or {@code ./mvnw spring-boot:run}, with
 * the local topology brought up by {@code docker compose up} (PostgreSQL 16, LocalStack, Jaeger, Prometheus
 * and Grafana). The entry point is {@code com.cardemo.CardDemoApplication}.
 *
 * <p><strong>Profiles.</strong> A base profile plus three overlays. {@code local} adds the LocalStack
 * endpoint override and points at the compose PostgreSQL; {@code test} is the Testcontainers backed profile;
 * {@code prod} is least privilege with every secret externalised. The base profile and {@code prod} carry
 * <strong>no AWS endpoint override at all</strong>, so a live AWS fallback is not merely discouraged, it is
 * structurally impossible.
 *
 * <p><strong>Batch jobs do not auto run.</strong> {@code spring.batch.job.enabled} is {@code false}, so
 * starting the application does not post transactions. Launching is explicit. The two intended launch paths
 * are the planned {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator} and the planned SQS listener that
 * replaces the JES2 internal reader; <strong>neither is authored yet</strong>, so today a job is driven from a
 * test or by launching its {@code Job} bean directly.
 *
 * <p><strong>Environment prerequisite, stated plainly.</strong> A container runtime with an accessible socket
 * is required for the Testcontainers tiers and for {@code docker compose up}. Where a piece of evidence
 * cannot be produced because that prerequisite is missing, the correct report is the literal
 * <strong>{@code Not available}</strong> together with the specific prerequisite, the command attempted and
 * its exit status. An untested pass is never asserted, and neither is a blanket claim that a runtime is
 * absent.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p><strong>Key configuration and defaults.</strong> The property names below are the binding contract
 * published by {@code src/main/resources/application.yml}. They are reproduced here exactly as that file
 * spells them; alternative spellings are not interchangeable.
 *
 * <p><strong>Security.</strong> {@code carddemo.security.jwt.signing-key} resolves from
 * {@code ${JWT_SIGNING_KEY}} with <strong>no default, no example and no fallback</strong>, so an absent or
 * invalid value fails fast at context refresh. {@code carddemo.security.jwt.issuer} is
 * {@code ${JWT_ISSUER:carddemo}} and {@code carddemo.security.jwt.expiration-minutes} is
 * {@code ${JWT_EXPIRATION_MINUTES:30}}. Two spellings here were corrected in one repository-wide change and
 * are worth naming, because a note quoting either superseded form is describing a contract that no longer
 * exists. The signing-key variable was {@code JWT_SECRET}, which the tree had standardised on before the
 * mandated spelling was published; it is now {@code JWT_SIGNING_KEY} everywhere - code, all four profiles,
 * {@code .env.example}, {@code pom.xml}, {@code owasp-suppressions.xml} and {@code Dockerfile} - so there is
 * still exactly one way to configure the key and none of the drift that fail fast exists to prevent. The
 * lifetime was {@code carddemo.security.jwt.expiration-seconds} bound from {@code ${JWT_EXPIRATION_SECONDS}}
 * with a 3,600-second default, which is <em>double</em> the approved thirty-minute bearer window; it is now
 * expressed in minutes with a 30-minute default, and the conversion to a {@link java.time.Duration} happens in
 * exactly one place, {@code com.cardemo.security.JwtTokenProvider}. The lifetime and the issuer are non-secret
 * metadata, which is why they carry documented defaults where the signing key deliberately carries none.
 *
 * <p>Note that {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} and its
 * {@code public-key-location} sibling are <strong>deliberately not set</strong>.
 * {@code com.cardemo.config.SecurityConfig} builds a symmetric {@code NimbusJwtDecoder} from
 * {@code carddemo.security.jwt.signing-key}, so setting either Boot property would be dead configuration.
 * BCrypt strength is <strong>10</strong>. {@code /api/admin/*} is restricted to the administrator role, sign
 * on is the only unauthenticated operation, and the session policy is {@code STATELESS} - there is no HTTP
 * session and no CSRF state, because the COMMAREA that once carried conversation state has no counterpart.
 *
 * <p><strong>Persistence.</strong> {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in
 * <strong>every</strong> profile, {@code spring.jpa.open-in-view} is {@code false}, and the Hibernate JDBC
 * time zone is <strong>UTC</strong>. Flyway owns the schema through <strong>exactly three</strong>
 * migrations: {@code V1__create_schema.sql}, {@code V2__create_indexes.sql} and {@code V3__seed_data.sql}.
 * {@code V1} creates <strong>exactly 11 tables</strong> and {@code V2} adds the three B-tree indexes that
 * replace the three VSAM alternate indexes. The Spring Batch {@code BATCH_*} metadata tables are the
 * framework's own schema, provisioned from the script shipped in the Spring Batch artefact with
 * {@code spring.batch.jdbc.initialize-schema} set to {@code never}; they are
 * <strong>never a fourth migration and never extra tables in {@code V1}</strong>.
 * {@code validate-on-migrate} is {@code true} and {@code clean-disabled} is {@code true}.
 *
 * <p><strong>AWS.</strong> {@code spring.cloud.aws.region.static} resolves from
 * {@code ${AWS_REGION:${AWS_DEFAULT_REGION}}}. The buckets are
 * {@code carddemo.aws.s3.batch-input-bucket} from {@code ${CARDDEMO_S3_BATCH_INPUT_BUCKET}},
 * {@code batch-output-bucket} from {@code ${CARDDEMO_S3_BATCH_OUTPUT_BUCKET}} (versioned) and
 * {@code statements-bucket} from {@code ${CARDDEMO_S3_STATEMENTS_BUCKET}}. The queue is
 * {@code ${CARDDEMO_SQS_REPORT_QUEUE}} with logical name <strong>{@code carddemo-report-jobs}</strong>, and
 * the topic is {@code ${CARDDEMO_SNS_NOTIFICATION_TOPIC}}. Seven {@code gdg-prefixes} entries map the seven
 * generation data group bases onto key prefixes, and {@code gdg-retention-generations} is {@code 10} - which
 * resolves a genuine conflict in the source, where the report group is declared {@code LIMIT(5)} in one JCL
 * member and {@code LIMIT(10)} in another. <strong>The LocalStack endpoint override exists only in the
 * {@code local} and {@code test} profiles, there are no credential defaults anywhere, and all AWS interaction
 * targets LocalStack with zero live credentials.</strong>
 *
 * <p><strong>Observability.</strong> {@code management.endpoints.web.exposure.include} is exactly
 * {@code health,info,prometheus} and never a wildcard. {@code management.endpoint.health.show-details} is
 * {@code never}, as is {@code show-components}. There are <strong>separate liveness and readiness
 * groups</strong>: liveness includes {@code livenessState}, and readiness includes
 * {@code readinessState,db}. {@code management.otlp.tracing.endpoint} resolves from
 * {@code ${OTEL_EXPORTER_OTLP_ENDPOINT}}. The Prometheus scrape configuration lives in the root owned
 * {@code observability/prometheus.yml} and is <strong>not duplicated</strong> in application configuration.
 *
 * <p><strong>Metrics.</strong> Exactly <strong>four</strong> named counters, and no fifth instrument:
 * {@code carddemo.batch.records.processed}, {@code carddemo.batch.records.rejected} tagged by a
 * <strong>bounded</strong> reject code through the {@code reject-code} tag,
 * {@code carddemo.auth.attempts} and {@code carddemo.transaction.amount.total}. No high cardinality tag is
 * ever attached. These four replace the legacy end of run {@code DISPLAY 'TRANSACTIONS PROCESSED :'} and
 * {@code DISPLAY 'TRANSACTIONS REJECTED  :'} at {@code app/cbl/CBTRN02C.cbl:L227-L228}.
 *
 * <p><strong>Pagination</strong>, taken from the source and non negotiable, since each value is a literal in
 * a COBOL program rather than a tunable:
 *
 * <ul>
 *   <li>Card list <strong>7</strong> - {@code app/cbl/COCRDLIC.cbl:L177-L178},
 *       {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7}.</li>
 *   <li>Transaction list <strong>10</strong> - {@code app/cbl/COTRN00C.cbl:L290} and {@code :L344},
 *       {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10} on the forward and backward paging
 *       paths. Here the page size is a loop bound rather than a table dimension.</li>
 *   <li>User list <strong>10</strong> - {@code app/cbl/COUSR00C.cbl:L57},
 *       {@code 02 USER-REC OCCURS 10 TIMES}. Here it is a table dimension, which is a different mechanism
 *       from the transaction list reaching the same number.</li>
 *   <li>Report <strong>20 lines per page</strong> - {@code app/cbl/CBTRN03C.cbl:L131-L132},
 *       {@code 05 WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}, applied at {@code :L282} through
 *       {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE)}.</li>
 *   </ul>
 *
 * <h2>Financial precision and fixed width record geometry</h2>
 *
 * <p>Precision is derived from the picture clauses, not chosen, and the three widths differ from one another
 * in ways that matter. Account money fields are {@code S9(10)V99} and map to {@code NUMERIC(12,2)};
 * {@code TRAN-AMT} and {@code TRAN-CAT-BAL} are {@code S9(09)V99} and map to
 * <strong>{@code NUMERIC(11,2)}</strong>; {@code DIS-INT-RATE} is {@code S9(04)V99} and maps to
 * <strong>{@code NUMERIC(6,2)}</strong>. There is <strong>zero {@code float} and zero {@code double} in any
 * financial field</strong> anywhere in this tree. {@code java.math.BigDecimal} is used throughout with
 * {@code RoundingMode.HALF_EVEN} and a monetary scale of 2, and equality is tested with
 * {@code compareTo} and <strong>never</strong> {@code equals}, because {@code equals} on
 * {@code BigDecimal} distinguishes scale. Interest divides by the literal {@code 1200} in one step and is
 * never algebraically rewritten as a division by 100 followed by a division by 12, since that changes the
 * rounding.
 *
 * <p>{@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are {@code PIC X(26)} and are modelled as
 * {@code String} over {@code CHAR(26)} - <strong>never {@code LocalDateTime}, {@code Timestamp} or
 * {@code Instant}</strong>. That looks like a missed modernisation and is not one: three mutually
 * incompatible producers write these fields, a batch form, an online form and a pure pass through of
 * whatever the input record already contained, so text is the only representation that can carry all three
 * without loss. Generated values are formatted to <strong>centisecond</strong> precision followed by four
 * literal zeros - two fraction digits, not three and not nine.
 * {@code app/cbl/CBTRN02C.cbl:L159-L174} declares {@code DB2-FORMAT-TS PIC X(26)} with the fraction split
 * into {@code DB2-MIL PIC 9(002)} and {@code DB2-REST PIC X(04)}, and {@code :L700-L701} moves
 * {@code COB-MIL} into the two-digit field and the literal {@code '0000'} into the four-character remainder.
 * Emitting three or nine fraction digits produces a 27- or 33-character value, so every generated timestamp
 * would then differ from the legacy baseline.
 *
 * <p>Fixed width geometry that the S3 boundary must preserve byte exactly: transaction image
 * <strong>350</strong>; reject record <strong>430</strong>, being 350 data bytes plus an 80 byte trailer of a
 * 4 digit reason code and a 76 character description, independently confirmed by
 * {@code DCB=(RECFM=F,LRECL=430)} on the reject dataset in {@code app/jcl/POSTTRAN.jcl}; report line
 * <strong>133</strong>; statement text <strong>80</strong>; statement HTML <strong>100</strong>; queue
 * parameter record <strong>80</strong>, fixed by {@code RECORDSIZE(80) RECORDFORMAT(FIXED)} on the transient
 * data queue definition; and the statement work cluster key <strong>32</strong>.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p><strong>Common failure modes and troubleshooting.</strong>
 *
 * <ul>
 *   <li><strong>Missing or invalid {@code JWT_SIGNING_KEY}.</strong> The application fails fast at context
 *       refresh, naming the unresolvable placeholder. This is the intended path, not a bug. Remedy: export
 *       the environment variable with at least 32 bytes of entropy, which is the HS256 minimum. There is
 *       intentionally no default and <strong>none may be added</strong>. An environment still exporting the
 *       superseded {@code JWT_SECRET} produces exactly this failure and the remedy is to rename it.</li>
 *   <li><strong>Flyway validation failure at startup.</strong> Either the database schema has drifted from
 *       the three migrations, or someone added a fourth migration or extra tables to {@code V1}. Remedy:
 *       re-provision the database. <strong>Never relax {@code ddl-auto: validate}</strong> and never enable
 *       Flyway clean; a pre-migrated schema traps you with a checksum mismatch the moment a migration
 *       changes.</li>
 *   <li><strong>Testcontainers or {@code docker compose} failure.</strong> Almost always no container
 *       runtime or no accessible socket. Remedy: start the daemon. If one genuinely is not available, report
 *       the prerequisite with the command attempted and its exit status and record the affected evidence as
 *       {@code Not available}, rather than asserting an untested pass.</li>
 *   <li><strong>LocalStack not provisioned.</strong> An S3 bucket or the SQS FIFO queue is missing. Remedy:
 *       run {@code localstack-init/init-aws.sh}, which is idempotent, so repeated {@code docker compose up}
 *       cycles converge rather than failing on already existing resources.</li>
 *   <li><strong>Batch exit codes are contracts, not conventions.</strong> Return code <strong>4 if and only
 *       if the reject count exceeds zero</strong>: {@code app/cbl/CBTRN02C.cbl:L230} is
 *       {@code MOVE 4 TO RETURN-CODE}, guarded by {@code IF WS-REJECT-COUNT > 0} at {@code :L229}, and
 *       {@code RETURN-CODE} is assigned <strong>exactly once in the whole program</strong>, so there is no
 *       other determinant. Return code 8 is failure. An unexpected {@code FILE STATUS} raises
 *       <strong>abend code 999 with return code 12</strong> through
 *       {@code 9999-ABEND-PROGRAM} at {@code :L707-L711}, which displays {@code 'ABENDING PROGRAM'}, moves
 *       {@code 0 TO TIMING}, moves {@code 999 TO ABCODE} and calls {@code 'CEE3ABD'}.</li>
 *   <li><strong>Reject codes are business outcomes, never exceptions.</strong> Exactly five exist, and they
 *       drive {@code ExitStatus} and are logged at a non error level with the code as a bounded tag:
 *       {@code 100 INVALID CARD NUMBER FOUND} ({@code :L385-L386}),
 *       {@code 101 ACCOUNT RECORD NOT FOUND} ({@code :L397-L398}),
 *       {@code 102 OVERLIMIT TRANSACTION} ({@code :L410-L411}),
 *       {@code 103 TRANSACTION RECEIVED AFTER ACCT EXPIRATION} ({@code :L417-L418}) and
 *       {@code 109 ACCOUNT RECORD NOT FOUND} ({@code :L554-L559}). Note that
 *       <strong>{@code 109} is reachable but never consumed</strong> - it is assigned on the already
 *       validated path, so no reject record is written and the value is cleared on the next iteration - and
 *       it is retained for parity rather than deleted.</li>
 *   <li><strong>A transaction that is both over limit and expired yields one reject record bearing
 *       103, not 102 and not two records.</strong> The two checks are sequential and unguarded in the
 *       source, so {@code 103} overwrites {@code 102}. This is preserved behaviour; an implementation that
 *       guarded the second check or emitted two records would be the defect.</li>
 *   <li><strong>Log diagnosis.</strong> Structured JSON logging carries {@code traceId}, {@code spanId} and
 *       {@code correlationId} in MDC, and batch events additionally carry the job instance identifier.
 *       {@code traceId} and {@code spanId} come from Micrometer tracing bridged to OpenTelemetry;
 *       {@code correlationId} comes from {@code com.cardemo.observability.CorrelationIdFilter}. If the trio
 *       is present but always empty, tracing is disabled or the bridge is absent; if
 *       {@code correlationId} alone is empty, the filter is not in the chain.</li>
 *   <li><strong>The four character I/O status literal is a parity contract.</strong> It renders as exactly
 *       {@code FILE STATUS IS: NNNN}, produced by {@code 9910-DISPLAY-IO-STATUS} at
 *       {@code app/cbl/CBTRN02C.cbl:L714-L727} on both branches of its numeric test. It passes through the
 *       logging configuration byte for byte and <strong>must never be reformatted</strong>. A masking rule
 *       that matches inside this literal is a misconfiguration, because boundary comparison diffs it against
 *       the legacy baseline.</li>
 *   <li><strong>Never expect a secret in a log.</strong> Credentials, presented passwords, stored BCrypt
 *       hashes, tokens, signing keys, authorisation headers, social security numbers, card numbers, phone
 *       numbers, government identifiers, dates of birth and electronic funds account identifiers are
 *       masked, and the rules are <strong>profile invariant</strong> - they may not be relaxed for a test
 *       run. More importantly, these values are not logged or serialised in the first place; masking is the
 *       second line of defence, not the first.</li>
 *   <li><strong>A concurrent account update conflict is not a generic 409.</strong> Two layers guard the
 *       write: JPA {@code @Version} for the store level check, <strong>plus</strong> an explicit field by
 *       field snapshot comparison, because the source compares business field values rather than a version
 *       counter ({@code app/cbl/COACTUPC.cbl:L669-L756}, the {@code ACUP-OLD-DETAILS} and
 *       {@code ACUP-NEW-DETAILS} groups). A version counter detects <em>that</em> a row changed; the source
 *       detects <em>which fields</em> changed. Four distinct outcomes - account lock failure, customer lock
 *       failure, data changed before update, and locked but update failed - each surface distinguishably,
 *       because collapsing them loses information the legacy screen displayed.</li>
 *   </ul>
 *
 * <h2>Documented deviations, corrections and retained parity artefacts</h2>
 *
 * <p>Findings are classified by severity and each carries its locator. Full detail will live in the planned
 * {@code DECISION_LOG.md} and {@code TRACEABILITY_MATRIX.md}; this is the index, kept short deliberately.
 *
 * <p><strong>Where the record lives, stated once for the whole tree.</strong> Both evidence registers are
 * scheduled artefacts that do not exist in this branch, so no file in this tree may say a decision is
 * recorded, tracked or cited <em>in</em> either of them: such a sentence sends a reader to a document that is
 * not here. The convention is a forward reference - a decision is tracked <em>for</em> the planned register -
 * with the decision itself in the docstring of the file it governs, which is the one place it cannot drift
 * from the code it explains. This paragraph is that authoritative statement, so no other file has to qualify
 * every mention.
 *
 * <p><strong>The retained-for-parity register is bounded and enumerated here.</strong> A construct is
 * "retained for parity" when it would otherwise read as dead code and is kept only because deleting it would
 * change observable behaviour or break the paragraph map.
 * Exactly three sites carry that retained-for-parity status, and they are as follows.
 * {@code RejectCode}, whose fifth constant is assigned on a reachable path and never
 * consumed as a reject outcome; {@code computeFees1400}, an empty but genuinely performed paragraph of
 * {@code app/cbl/CBACT04C.cbl:L518-L520}; and the statement processor's redundant index assignment before a
 * varying loop that re-initialises it anyway. A preserved COBOL {@code CONTINUE}, a preserved asymmetry and a
 * preserved absent guard are <em>not</em> register entries - they are documented source behaviour at their own
 * locators - and counting them is what made the term look over-applied.
 *
 * <p><strong>Corrections against the specification prose, all resolved in favour of the primary source.</strong>
 *
 * <ul>
 *   <li><strong>Blocker.</strong> The Testcontainers 2.x module artefacts were renamed, so the unprefixed
 *       coordinates do not resolve at all. Remedy in the build section above; both halves are required.</li>
 *   <li><strong>High.</strong> The apparent final flush in {@code app/cbl/CBACT04C.cbl} is
 *       <strong>unreachable</strong>. The {@code ELSE PERFORM 1050-UPDATE-ACCOUNT} at {@code :L219-L220}
 *       belongs to {@code IF END-OF-FILE = 'N'} at {@code :L189}, so it can only be taken once
 *       {@code END-OF-FILE} is {@code 'Y'} - precisely the state in which the enclosing test before
 *       {@code PERFORM UNTIL END-OF-FILE = 'Y'} at {@code :L188} has already exited. The last account in key
 *       order therefore never receives its accrued interest and keeps stale cycle accumulators.</li>
 *   <li><strong>High.</strong> The monthly report range in {@code app/cbl/CORPT00C.cbl:L213-L238} is the
 *       <strong>full current calendar month</strong>, not month to date: the start day is forced to
 *       {@code '01'} at {@code :L219}, and the end is the first of next month ({@code :L224}) minus one day
 *       ({@code :L230}).</li>
 *   <li><strong>Medium.</strong> The BMS input field census is <strong>441</strong> fields across the 17
 *       symbolic maps, with {@code COACTVW} at <strong>37</strong>. The widely quoted 460 and 36 are both
 *       wrong; {@code COACTVW} has 37 length fields but only 36 input fields, one field being output only,
 *       which is how the lower figure arose.</li>
 *   <li><strong>Medium.</strong> {@code app/cpy/CSMSG02Y.cpy} is internally titled {@code CABENDD.CPY} and
 *       holds <strong>abend work areas</strong>, not messages: {@code ABEND-CODE X(4)},
 *       {@code ABEND-CULPRIT X(8)}, {@code ABEND-REASON X(50)} and {@code ABEND-MSG X(72)}. Reading it as a
 *       message copybook would have cost {@code FatalProcessingException} its entire field set. Relatedly,
 *       {@code app/cbl/COSGN00C.cbl} is <strong>260</strong> lines.</li>
 *   <li><strong>Medium.</strong> The card cross reference alternate index sits at <strong>{@code AXRKP 25}</strong>
 *       ({@code app/catlg/LISTCAT.txt:L486}), an offset omitted upstream. The three alternate index offsets
 *       are 16, 25 and 304.</li>
 *   <li><strong>Low, CLOSED.</strong> The {@code Source} banner line of
 *       {@code src/main/java/com/cardemo/model/dto/package-info.java} recorded the superseded
 *       {@code 460 input fields} figure as a statement of fact. An earlier revision of this entry left the
 *       finding open with the note "noted rather than edited"; that is no longer true and the note is
 *       withdrawn. The banner now reads {@code 441 input fields, of which COACTVW contributes 37}, so the
 *       whole package - banner line and prose alike - carries one figure.</li>
 *   <li><strong>Low.</strong> {@code catalog-info.yaml:L35} declares {@code type: website} for what is a
 *       REST and Actuator backend with no browser rendered interface at all. Remediation: {@code service}.
 *       Noted rather than edited, since it predates the migration and is unrelated to it.</li>
 *   </ul>
 *
 * <p><strong>Labelled deviations</strong> - behaviour that intentionally differs, justified in writing rather
 * than presented as equivalence.
 *
 * <ul>
 *   <li>The three independent COBOL commits of daily posting collapse into <strong>one atomic Java
 *       unit</strong>, which closes an orphan write hazard on the {@code 109} path where the legacy system
 *       could leave an orphaned category balance row and an orphaned transaction row. A genuine improvement,
 *       and therefore labelled rather than claimed as parity.</li>
 *   <li>The statement program's hard <strong>510 transaction ceiling</strong> - 51 cards by 10 transactions
 *       at {@code app/cbl/CBSTM03A.CBL:L225-L230}, incremented with no bounds check whatsoever - is removed
 *       by streaming over unbounded collections. This eliminates a latent storage overrun.</li>
 *   <li>{@code EXEC CICS SYNCPOINT ROLLBACK} is replaced by <strong>transactional scoping</strong>. The
 *       source's rollback appears on only one of two failure paths, which reads like a defect and is not
 *       one: at the earlier point nothing has yet been written, while at the later point the account rewrite
 *       has already occurred. Scoping both writes into one transaction reproduces both branches with no
 *       conditional logic, which is why no Java statement corresponds to the rollback verb.</li>
 *   <li><strong>Correction.</strong> An earlier revision of this list claimed the interest job "performs its
 *       final account flush on the end of data condition, supplying the update the source's unreachable branch
 *       never performs". It does not, and it must not: supplying an update the source never performs is a
 *       behaviour change, and on top of the control-break flush it can double-apply the last account. The
 *       unreachable branch at {@code app/cbl/CBACT04C.cbl:L219-L220} is authoritative, so the last account in
 *       key order receives no accrued interest and keeps stale cycle accumulators - exactly as in the legacy
 *       system. {@code com.cardemo.batch.processors.InterestCalculationProcessor},
 *       {@code com.cardemo.batch.jobs.InterestCalculationJob} and
 *       {@code com.cardemo.repository.TransactionCategoryBalanceRepository} all state this, and
 *       {@code InterestCalculationJobTest} asserts it.</li>
 *   </ul>
 *
 * <p><strong>Retained for parity</strong> - artefacts that look like dead code and are deliberately kept.
 *
 * <p>What legitimises each one is recorded <strong>at its own declaration and nowhere else</strong>: the COBOL
 * locator, a proof of reachability, an explicit intentional-no-op marker, and - because neither
 * {@code DECISION_LOG.md} nor {@code TRACEABILITY_MATRIX.md} exists at this commit - an acknowledgement that it
 * is <strong>owed</strong> an entry in the planned files. An earlier revision of this sentence asserted that each
 * was already cited and already tracked in both of those files. Neither file exists at this commit, so the
 * assertion was false and is corrected here - an artefact may be described as <em>owed</em> an entry, never as
 * having one.
 *
 * <p>The list below is a <strong>reading aid, not a register and not a count</strong>. Several package
 * documentation files each used to carry their own tally of this set and they disagreed - three in
 * {@code com.cardemo.security} and {@code com.cardemo.repository}, five in {@code com.cardemo.exception} - which
 * is what a census maintained by hand in unrelated comments decays into. Severity of what that left in place:
 * <strong>High</strong>. Those tallies are withdrawn, no global cardinality is asserted anywhere, and the
 * per-artefact marker is authoritative over anything written here.
 *
 * <ul>
 *   <li>{@code 1400-COMPUTE-FEES} at {@code app/cbl/CBACT04C.cbl:L518-L520} - a comment and an
 *       {@code EXIT}, empty but genuinely <strong>reachable</strong>, performed at {@code :L216}.</li>
 *   <li>The unreachable final flush branch at {@code app/cbl/CBACT04C.cbl:L219-L220}.</li>
 *   <li>{@code 9150-GETCARD-BYACCT} at {@code app/cbl/COCRDSLC.cbl:L779-L810} - confirmed dead, since no
 *       {@code PERFORM 9150} exists anywhere in {@code app/cbl}.</li>
 *   <li>The redundant {@code MOVE 1 TO CR-JMP} at {@code app/cbl/CBSTM03A.CBL:L324}, immediately before a
 *       {@code PERFORM VARYING CR-JMP FROM 1 BY 1} at {@code :L417} that re-initialises it anyway.</li>
 *   <li>Reject code {@code 109}, reachable but never consumed, as described above.</li>
 * </ul>
 *
 * <p>Three business quirks are likewise preserved rather than repaired: reject code {@code 103} overwriting
 * {@code 102} when a transaction fails both checks; the transaction report's control break triggering on the
 * <strong>card number</strong> while the emitted label reads {@code 'Account Total'}; and the absence of any
 * self delete guard in user deletion, which never compares the target user identifier against the signed on
 * one. No guard is added where the source has none, unless removing the resulting hazard is explicitly
 * labelled as a deviation above.
 *
 * <h2>Rule compliance: Build Verify, clauses A to F</h2>
 *
 * <p>Exactly <strong>one</strong> project rule applies, a global coding and design standard organised into
 * six lettered clauses. This file is itself an artefact of that rule rather than of any functional
 * requirement, so its compliance is stated explicitly.
 *
 * <ul>
 *   <li><strong>A, engineering principles.</strong> Correctness, determinism and explicit behaviour over
 *       cleverness. Every count and citation above was verified by direct inspection of the frozen corpus at
 *       {@code 7756d89}, which is why several widely quoted figures are corrected rather than repeated. The
 *       structure documented here is the actual one, not an idealised one.</li>
 *   <li><strong>B, code quality.</strong> No dead code, no unused imports, and no untracked deferred work.
 *       This file declares <strong>zero imports, zero annotations and zero members</strong> - a
 *       {@code package-info.java} legally contains only comments and a package declaration, and adding a
 *       nullability annotation would introduce an unused dependency or a deprecation warning that
 *       {@code -Werror} treats as fatal. The clause's requirement to document public interfaces with their
 *       purpose, inputs, outputs, side effects and error modes is discharged at package granularity here and
 *       in the {@code package-info.java} of every other package that contains a type - one per such package,
 *       by the bijection stated above rather than by a fixed number.</li>
 *   <li><strong>C, repository hygiene.</strong> The banner above reproduces the universal Apache-2.0 source
 *       header convention of the legacy corpus, present on 28 of 28 {@code app/cbl} members, adapted to a
 *       syntactically valid Java block comment and extended with the {@code Source} lines that name the
 *       originating members. Formatting follows the root {@code .editorconfig}: UTF-8, LF endings, a final
 *       newline, no trailing whitespace, 4 space Java indentation and a 120 column limit. Note that the
 *       clause's "if present" condition is <strong>not triggered</strong> for formatters, because no
 *       formatter, linter or style configuration exists anywhere in the source repository - so
 *       configuration here is <em>established</em> rather than inherited, which is not the same thing as
 *       overriding an existing style. Duplication is avoided by summarising and pointing rather than
 *       restating what a subpackage documents.</li>
 *   <li><strong>D, security standards.</strong> No secrets in code, logs, tests or configuration. Every
 *       credential reference above is an environment variable placeholder and <strong>no real or example
 *       secret value appears anywhere in this file</strong>. Least privilege is recorded concretely: the
 *       administrator only routes, the absence of any live AWS path, and the deliberate absence of an
 *       endpoint override outside the {@code local} and {@code test} profiles. Dependencies are pinned to
 *       exact coordinates with no ranges and no floating versions. The clause's named risky patterns are
 *       addressed rather than merely absent: there is no {@code eval} or {@code exec} equivalent and no
 *       shell invocation anywhere in this tree - notably the DFSORT replacement spawns
 *       <strong>no external sort process</strong> - and deserialisation is hardened centrally at the mapper
 *       rather than per call site, with {@code fail-on-unknown-properties} and
 *       {@code fail-on-null-for-primitives} enabled, {@code use-big-decimal-for-floats} enabled so that no
 *       monetary value can ever land in a binary float, and {@code accept-case-insensitive-enums}
 *       disabled.</li>
 *   <li><strong>E, documentation standards.</strong> The clause requires a short README <em>or</em>
 *       docstring covering what it does, how to run, build and test, key configuration and defaults, and
 *       common failure modes and troubleshooting. This tree takes the <strong>docstring</strong> option of
 *       that either/or uniformly, and every package that contains a type carries one - which is the bijection
 *       stated above and asserted by {@code PackageDocumentationInventoryTest}. All four topics appear in each
 *       as headed, substantive sections. A README under {@code src/main/java} is not the alternative that was
 *       chosen and must not be added: it would be a second copy of this documentation that no build step
 *       keeps in step with the tree.</li>
 *   <li><strong>F, output requirements.</strong> Evidence based, citing paths and symbols throughout;
 *       findings classified by severity; remediation stated with each. Where information is genuinely
 *       unavailable it is said to be unavailable rather than invented - which is why no service level
 *       objective, throughput target or latency target appears anywhere here. None exists in the source, so
 *       the performance gate records a <strong>measured baseline</strong>, never an improvement target.</li>
 *   </ul>
 *
 * <p><strong>The one documented conflict, and its resolution.</strong> Clause B forbids dead code, while the parity
 * mandate requires preserving reachable no-ops so the paragraph map stays provable. These collide at identifiable
 * sites, listed under retained for parity above. <strong>Parity governs</strong>, and Clause B is satisfied by a
 * different mechanism: the clause forbids dead code and deferred work <em>without an owner or tracking
 * reference</em>, and every retained artefact is cited, owed an entry in the planned {@code DECISION_LOG.md} and
 * {@code TRACEABILITY_MATRIX.md}, and marked in code with an explicit intentional no-op comment. Deleting them would
 * produce a tree that is marginally cleaner and demonstrably less traceable, failing a stated acceptance criterion to
 * satisfy a stylistic one. That is why apparent dead code exists in this tree, and why it must not be tidied away.
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */

package com.cardemo;
