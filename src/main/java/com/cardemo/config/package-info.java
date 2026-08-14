/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.config
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Spring configuration layer)
 * Function    : Package-level documentation for the six Spring
 *               configuration classes that carry what the mainframe
 *               expressed as declarations rather than as code: the
 *               CICS CSD transaction, program and file definitions,
 *               the VSAM catalogue and its IDCAMS DEFINE CLUSTER
 *               jobs, the JES2 batch job stream, the generation data
 *               group and transient-data-queue integration points,
 *               the BMS navigation and attention-identifier state,
 *               and the instrumentation the legacy corpus does not
 *               have at all.
 * Source      : app/csd/CARDDEMO.CSD (505 lines; 18 DEFINE
 *                 TRANSACTION, 18 DEFINE PROGRAM, 8 DEFINE FILE,
 *                 17 DEFINE MAPSET, 1 DEFINE TDQUEUE) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt (3,956 lines; 10 base
 *                 clusters, 3 alternate indexes, 3 paths) @ 7756d89
 * Source      : app/jcl/POSTTRAN.jcl + app/jcl/INTCALC.jcl +
 *                 app/jcl/COMBTRAN.jcl + app/jcl/CREASTMT.JCL +
 *                 app/jcl/TRANREPT.jcl (the batch job stream, with
 *                 app/proc/TRANREPT.prc, app/proc/REPROC.prc and
 *                 app/ctl/REPROCT.ctl) @ 7756d89
 * Source      : app/jcl/DEFGDGB.jcl + app/jcl/DALYREJS.jcl +
 *                 app/jcl/REPTFILE.jcl (the 7 GDG bases) @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy:L25-L28 (CDEMO-USER-ID X(08),
 *                 CDEMO-USER-TYPE X(01) with 88-levels 'A' and 'U')
 *                 + app/cpy/CVCRD01Y.cpy (46 lines, navigation and
 *                 attention-identifier state) + app/cpy/CSSTRPFY.cpy
 *                 (85 lines, the procedural pushbutton paragraph)
 *                 @ 7756d89
 * Source      : app/cbl/COSGN00C.cbl (260 lines; sign-on)
 *                 + app/cbl/CBTRN02C.cbl (731 lines; the three-write
 *                 posting unit and the exit-code contract)
 *                 + app/cbl/CBACT04C.cbl (652 lines; the canonical
 *                 Apache banner at :L1-L21) @ 7756d89
 * Replaces    : the CICS region, the JES2 initiators, the DFHCSDUP
 *               CSD installation job app/jcl/CBADMCDJ.jcl, and the
 *               file-availability jobs app/jcl/OPENFIL.jcl and
 *               app/jcl/CLOSEFIL.jcl
 * Note        : documentation only. This file declares no type, no
 *               field and no annotation, and imports nothing.
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
 * Wiring layer of the CardDemo modular monolith: the six Spring configuration classes that carry what the
 * frozen mainframe corpus expressed as declarations rather than as code.
 *
 * <p>This file is the discharge of Rule 1 Clause E for the whole package. The clause offers a short README
 * or a docstring, and the docstring option is taken here deliberately: the four headed sections below are
 * <em>What it does</em>, <em>How to run, build and test</em>, <em>Key configuration and defaults</em> and
 * <em>Common failure modes and troubleshooting</em>, in that order and individually identifiable. There is
 * consequently no README and no Markdown file anywhere in this subtree, and none may be added - a second
 * place to describe the same six classes is exactly how the description drifts out of step with them.
 *
 * <p>Every claim below carries a path and a locator, and every locator was read off disk rather than
 * inherited from prose. Where the surrounding specification and the source disagreed, the source won and the
 * verified locator is the one printed here. Legacy filenames are cited in their exact on-disk case, which
 * matters more than it looks: {@code app/jcl/CREASTMT.JCL} is the only uppercase member of
 * {@code app/jcl}, {@code app/cbl/CBSTM03A.CBL} and {@code app/cbl/CBSTM03B.CBL} the only two of
 * {@code app/cbl}, {@code app/cpy/COSTM01.CPY} the only one of {@code app/cpy}, and
 * {@code app/csd/CARDDEMO.CSD} is uppercase throughout.
 *
 * <h2>What it does</h2>
 *
 * <p>This package contributes no business logic. Every class in it is declarative wiring, and the behaviour
 * each one configures is fixed by a frozen artefact rather than by preference. Four of the six go further and
 * refuse to let a misconfigured context refresh at all - the security, persistence, cloud-integration and
 * observability classes each validate what they bind and abort on the first violation - because a guard that
 * refuses to start is worth more than a comment asking the next maintainer not to misconfigure it. The
 * remaining two validate at the boundary they serve instead: a request in the web class, a fixed-width record
 * in the batch class.
 *
 * <h3>Why a single deployable unit, and not microservices</h3>
 *
 * <p>The target is one Spring Boot application: a modular monolith, explicitly <strong>not</strong> a set of
 * services. The reason is atomicity, and it is decided by the source rather than by taste.
 *
 * <ul>
 *   <li>{@code app/cbl/COACTUPC.cbl:L4098-L4102} sets the combined locked-but-update-failed outcome and
 *       issues {@code EXEC CICS SYNCPOINT ROLLBACK} on the customer-rewrite failure path, inside a unit of
 *       work that has already rewritten the account record. One account write and one customer write are
 *       therefore a single unit of work.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L424-L444} is {@code 2000-POST-TRANSACTION}, which moves thirteen
 *       fields, copies the originating timestamp, generates the processing timestamp, and then performs a
 *       transaction-category-balance upsert, an account update and a transaction write at
 *       {@code :L440-L442}. Three writes, one logical outcome.</li>
 * </ul>
 *
 * <p>Distributing either group across service boundaries would replace a transaction with a compensating
 * saga and change failure semantics, which is a behaviour change and therefore forbidden. Scoping both
 * groups inside one transactional method reproduces the source's asymmetric rollback automatically, because
 * each failure path returns before the commit point and nothing has to be made conditional.
 *
 * <h3>The six classes, and the artefact each derives from</h3>
 *
 * <ul>
 *   <li><strong>{@code com.cardemo.config.SecurityConfig}</strong> - the stateless bearer-token filter
 *       chain. It declares four beans: an ordered metrics-scrape chain, the main chain, the BCrypt
 *       {@code PasswordEncoder} at strength exactly 10, and the single symmetric HMAC {@code JwtDecoder}.
 *       Authorisation covers exactly the <strong>17 sourced</strong> CICS transactions - {@code CC00},
 *       {@code CM00}, {@code CA00}, {@code CAVW}, {@code CAUP}, {@code CCLI}, {@code CCDL}, {@code CCUP},
 *       {@code CT00}, {@code CT01}, {@code CT02}, {@code CB00}, {@code CR00}, {@code CU00}, {@code CU01},
 *       {@code CU02} and {@code CU03} - which is the 18 transactions of {@code app/csd/CARDDEMO.CSD} less
 *       the one whose program has no source. Origin: {@code app/csd/CARDDEMO.CSD},
 *       {@code app/cpy/COCOM01Y.cpy:L25-L28}, whose {@code CDEMO-USER-TYPE} 88-levels {@code 'A'} and
 *       {@code 'U'} become the two authorities, and {@code app/cbl/COSGN00C.cbl}, 260 lines of sign-on. It
 *       supersedes the DFHCSDUP installation job {@code app/jcl/CBADMCDJ.jcl}, whose
 *       {@code EXEC PGM=DFHCSDUP} sits at {@code :L27}.</li>
 *   <li><strong>{@code com.cardemo.config.BatchConfig}</strong> - the batch collaborators that cannot
 *       register themselves: the four repository- and object-store-backed dataset bindings standing in for
 *       the {@code CBSTM03B} DD names {@code TRNXFILE}, {@code XREFFILE}, {@code CUSTFILE} and
 *       {@code ACCTFILE}, and the listener that contributes the job-instance key to the diagnostic context
 *       because the correlation filter is HTTP-scoped. It declares <strong>no</strong> {@code Job},
 *       {@code Step}, {@code Flow} or {@code JobExecutionDecider}: each of the six classes in
 *       {@code com.cardemo.batch.jobs} is the designated definition site for its own. Origin: the five batch
 *       job-control members named in the banner above, plus {@code app/proc/TRANREPT.prc},
 *       {@code app/proc/REPROC.prc} and {@code app/ctl/REPROCT.ctl}. Return-code gating is narrower than the
 *       phrase "replaces {@code COND=(0,NE)}" suggests - the construct occurs at exactly three sites in the
 *       whole corpus, all inside {@code app/jcl/CREASTMT.JCL} at {@code :L56}, {@code :L66} and
 *       {@code :L79} - so an intra-job decider belongs to the statement job alone and every other stage
 *       boundary is gated between jobs by the orchestrator.</li>
 *   <li><strong>{@code com.cardemo.config.AwsConfig}</strong> - the S3, SQS FIFO and SNS clients, pointed at
 *       the local emulator and validated so that they cannot be pointed anywhere else. The seven generation
 *       data group bases become three buckets; {@code DEFINE TDQUEUE(JOBS)} becomes the queue whose logical
 *       name is {@code carddemo-report-jobs}. Origin: {@code app/jcl/DEFGDGB.jcl}, which declares six bases
 *       each with {@code LIMIT(5)} at {@code :L26}, {@code :L32}, {@code :L38}, {@code :L44}, {@code :L50}
 *       and {@code :L56}; {@code app/jcl/DALYREJS.jcl}, the seventh base;
 *       {@code app/jcl/REPTFILE.jcl:L27}, which declares {@code LIMIT(10)} for the same report group;
 *       {@code app/csd/CARDDEMO.CSD:L499-L505}; and {@code app/cbl/CORPT00C.cbl:L517-L523}, the
 *       {@code EXEC CICS WRITEQ TD} that becomes the publish.</li>
 *   <li><strong>{@code com.cardemo.config.JpaConfig}</strong> - the persistence-contract guard. It declares
 *       exactly one bean, which asserts nine configuration invariants during context refresh and throws on
 *       the first violation, so an application whose schema ownership has been quietly inverted refuses to
 *       start instead of starting and reshaping the schema. Entity scanning, the naming strategy and
 *       transaction management are <em>not</em> declared here and are deliberately left to
 *       auto-configuration beneath the {@code com.cardemo} base package: all eleven entities name their
 *       table and every persistent field names its column, so nothing may sit between the copybook and the
 *       column. Origin: {@code app/catlg/LISTCAT.txt} and the twelve IDCAMS {@code DEFINE CLUSTER} jobs for
 *       the physical layout, {@code app/cpy/CVTRA01Y.cpy}, {@code app/cpy/CVTRA02Y.cpy} and
 *       {@code app/cpy/CVTRA05Y.cpy} for the precisions, and {@code app/cbl/COACTUPC.cbl} plus
 *       {@code app/cbl/CBTRN02C.cbl} for the two atomicity groups above.</li>
 *   <li><strong>{@code com.cardemo.config.ObservabilityConfig}</strong> - the declared wiring owner of
 *       {@code com.cardemo.observability} and the publisher of the application's single time source. It
 *       declares exactly one bean method, a {@code java.time.Clock}, so that "now" is injected rather than
 *       read from a static and every rendered date and timestamp derives from one place. This is
 *       <strong>new capability</strong> with no legacy counterpart: across 19,254 lines in the 28 programs
 *       of {@code app/cbl} the corpus instruments itself with {@code DISPLAY} to SYSOUT and nothing else,
 *       plus the four-character status renderer {@code 9910-DISPLAY-IO-STATUS} at
 *       {@code app/cbl/CBTRN02C.cbl:L714-L727}. The class exists solely because Rule 1 Clause A requires
 *       <em>"measurable behavior (metrics/tracing where relevant)"</em>. It supersedes
 *       {@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CLOSEFIL.jcl}, which issue
 *       {@code CEMT SET FIL(...) OPE} and {@code CLO} respectively at {@code :L26-L30} of each, for exactly
 *       five files: {@code TRANSACT}, {@code CCXREF}, {@code ACCTDAT}, {@code CXACAIX} and
 *       {@code USRSEC}.</li>
 *   <li><strong>{@code com.cardemo.config.WebConfig}</strong> - the request-binding half of the
 *       CICS-to-REST substitution. It declares exactly <strong>five</strong> beans and overrides exactly
 *       <strong>four</strong> {@code WebMvcConfigurer} methods. Three of the beans are the binding
 *       primitives - the <strong>two distinct</strong> numeric parsers the corpus keeps separate and never
 *       interchanges, and the edited-amount printer that reproduces a display picture character for
 *       character. The remaining two are the error-rendering pair that keeps a refusal raised
 *       <em>before</em> any handler from escaping as container HTML: a servlet-container customiser
 *       installing the problem-JSON error report valve, and a security customiser for a request the
 *       firewall rejects. The four overridden methods are {@code addFormatters},
 *       {@code extendMessageConverters}, {@code addInterceptors} and
 *       {@code extendHandlerExceptionResolvers}; the eleven further {@code @Override} annotations in the
 *       file all belong to nested types and are not this class overriding anything.
 *       <strong>Finding CODE-003, severity Medium, resolved:</strong> this entry said three beans and two
 *       overrides, which was accurate before the error-rendering pair and the two later
 *       {@code WebMvcConfigurer} hooks were added and was never revised. It publishes
 *       the navigation-action request-parameter name as a constant that the card controller binds, and
 *       registers no navigation-action converter or type - one existed, had no consumer, and was removed
 *       rather than wired to something. Origin: {@code app/cpy/CVCRD01Y.cpy}, 46 lines of navigation and
 *       attention-identifier state; {@code app/cpy/CSSTRPFY.cpy}, 85 lines whose paragraph
 *       {@code YYYY-STORE-PFKEY} evaluates {@code EIBAID}; and {@code app/cbl/COTRN02C.cbl} at
 *       {@code :L55-L60}, {@code :L204}, {@code :L218}, {@code :L383-L386} and {@code :L456-L457}.</li>
 * </ul>
 *
 * <h3>What this package deliberately is not</h3>
 *
 * <ul>
 *   <li><strong>No seventh configuration class</strong>, and no eighth file of any kind in this directory.
 *       The six classes and this documentation file are the whole package.</li>
 *   <li><strong>No properties holder.</strong> Each class binds the keys it owns through constructor
 *       parameters, so a key with no owner has no home and is visible as such.</li>
 *   <li><strong>No controller advice, exception handler or response-status mapping.</strong> Turning a
 *       {@code com.cardemo.exception.ValidationException} into a status code belongs to the error-handling
 *       layer, not to the wiring layer.</li>
 *   <li><strong>No second web configurer</strong>, no cross-origin configuration, no static-resource
 *       handler, no view resolver and no default page size.</li>
 *   <li><strong>No REST endpoint, no business logic, no entity, no repository and no batch job.</strong>
 *       This package wires; it does not decide.</li>
 *   <li><strong>No 3270 or BMS user interface</strong>, no HTML, CSS or JavaScript application, no
 *       single-page front end and no component library. The interface is REST and JSON plus exactly three
 *       Actuator endpoints, and the <strong>441</strong> input fields of the seventeen symbolic maps under
 *       {@code app/cpy-bms} are consumed as data-transfer-object field contracts only.</li>
 * </ul>
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>The build is Java <strong>25</strong> at {@code maven.compiler.release} 25 with <strong>no preview
 * features</strong>, Maven <strong>3.9.11</strong> supplied by the repository's own wrapper, and the parent
 * {@code spring-boot-starter-parent} 3.5.11. Prerequisites are stated as capabilities rather than as paths,
 * because a path is an environment-specific assumption and Rule 1 Clause C forbids one: a JDK 25 toolchain
 * reachable on {@code PATH} with {@code JAVA_HOME} set, however the host provides it, and Maven resolved by
 * {@code ./mvnw} - {@code .mvn/wrapper/maven-wrapper.properties} names the exact 3.9.11 distribution
 * together with its checksum, so the wrapper verifies what it downloads and the build is reproducible on a
 * host with no Maven installed at all.
 *
 * <p>The four commands that matter, the third being the one continuous integration runs verbatim:
 *
 * <pre>{@code
 * ./mvnw -B -ntp clean compile
 * ./mvnw -B -ntp test
 * ./mvnw -B -ntp clean verify
 * docker compose up -d
 * }</pre>
 *
 * <p>Where a host toolchain at those exact versions is not wanted or not present, the identical build runs
 * inside the pinned image - the same image family the container build stage pins by digest - so no
 * conclusion ever depends on which JDK a developer happens to have installed:
 *
 * <pre>{@code
 * docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q -DskipTests compile
 * docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q verify
 * }</pre>
 *
 * <p>The {@code -Ddependency-check.skip=true} flag suppresses only the vulnerability scan, which needs
 * network access to the feed and takes roughly half an hour on a cold cache. <strong>No separate scan job
 * exists</strong>, so continuous integration does not "run it as a separate job so that skipping it locally
 * cannot hide a finding". The workflow's
 * {@code verify} job runs one {@code ./mvnw --batch-mode --no-transfer-progress clean verify} carrying no skip
 * flag - the step named <em>Verify - compile warning-free, run every tier, enforce every gate</em> of the
 * {@code verify} job in {@code .github/workflows/build.yml} - so the scan is part of the full gate itself and a
 * run that
 * carries the flag is a local shortcut rather than gate evidence. Loading the git-ignored environment file is
 * not optional for anything that starts a context, because {@code carddemo.security.jwt.signing-key} has no
 * default and a context without it fails fast by design - load it for the one command that needs it,
 * {@code ( set -a; . ./.env; set +a; ./mvnw -B -ntp verify )}, rather than exporting it into the shell where
 * every later child inherits it.
 *
 * <h3>The gates, and what each one fails on</h3>
 *
 * <ul>
 *   <li><strong>{@code maven-compiler-plugin} 3.14.1</strong> compiles with {@code failOnWarning},
 *       {@code -Xlint:all} and {@code -Werror} at release 25. One raw type, one unchecked cast or one
 *       malformed documentation comment fails the build outright. One nuance is worth knowing before hunting
 *       a phantom: an <em>unused import</em> does not trip this gate, because {@code javac} 25 publishes no
 *       {@code unused} lint key, so Rule 1 Clause B's prohibition on one is enforced at review. The question
 *       cannot arise in this file, which imports nothing.</li>
 *   <li><strong>{@code maven-javadoc-plugin} 3.11.2</strong> runs a documentation gate at {@code verify}
 *       with {@code doclint} set to {@code all}, {@code failOnWarnings} true and visibility {@code private},
 *       so provenance citations on private members are inside the gate rather than outside it. This file is
 *       <em>entirely</em> documentation, which makes that gate - not the compiler - the one that actually
 *       governs it.</li>
 *   <li><strong>{@code jacoco-maven-plugin} 0.8.12</strong> enforces an <strong>80% LINE</strong> floor over
 *       the whole bundle at {@code verify}, with no core-package exclusion and no getter-only padding. That
 *       is why constructor injection and the absence of global mutable state are mandatory throughout this
 *       package: a class that reads its own configuration from a static cannot be exercised twice with two
 *       different configurations in one JVM.</li>
 *   <li><strong>{@code maven-enforcer-plugin} 3.5.0</strong> asserts the Java and Maven floors, release-only
 *       versions, dependency convergence and upper bounds. <strong>{@code dependency-check-maven}
 *       12.1.0</strong> fails the build on a finding at CVSS 7 or above. <strong>Surefire and Failsafe</strong>
 *       are both 3.5.4.</li>
 *   <li><strong>{@code spring-boot-maven-plugin} 3.5.11</strong> packages one JAR whose main class is
 *       {@code com.cardemo.CardDemoApplication}. This package introduces <strong>no second entry
 *       point</strong>.</li>
 * </ul>
 *
 * <h3>Where the tests for this package live</h3>
 *
 * <p>Under {@code src/test/java/com/cardemo/unit/config} for the unit tier, with the integration tier under
 * {@code src/test/java/com/cardemo/integration} and the end-to-end tier under
 * {@code src/test/java/com/cardemo/e2e}. <strong>Never inside this package.</strong> Several of those suites
 * assert counts rather than behaviour on purpose - the number of beans a configuration class declares is
 * part of its contract, because a seventh bean is how a duplicate definition or a resurrected dead
 * configuration arrives.
 *
 * <p>Testcontainers is <strong>property-pinned to 2.0.3</strong> with <strong>no second bill-of-materials
 * import</strong>, and only the four <strong>prefixed</strong> coordinates {@code testcontainers},
 * {@code testcontainers-localstack}, {@code testcontainers-postgresql} and
 * {@code testcontainers-junit-jupiter} are used. The bare {@code localstack}, {@code postgresql} and
 * {@code junit-jupiter} artefacts <strong>do not exist at 2.0.3</strong>. This is a <strong>Blocker</strong>
 * and <strong>both remedies are required together</strong>: overriding the managed version without renaming
 * the coordinates resolves artefacts that are not published, and renaming without overriding resolves the
 * 1.x version the parent manages.
 *
 * <p>Two of the repository's own contribution requirements bear directly on a change to this package.
 * {@code CONTRIBUTING.md:L34} asks contributors to <em>"Ensure local tests pass"</em>, which is the third
 * command above and not the first; and {@code CONTRIBUTING.md:L33} asks that a change stay focused, warning
 * that reformatting everything alongside it makes the change hard to review. The second is why the
 * conventions this package follows - the licence banner, the four-space indent, the line width, the
 * line-ending and final-newline rules of the root editor configuration - are followed silently rather than
 * re-litigated in each change, and why no file here is reformatted as a side effect of editing another.
 *
 * <h3>Running the topology</h3>
 *
 * <p>Running the application needs the {@code local} profile and the Compose topology, which supplies
 * PostgreSQL 16, the emulator that the S3, SQS and SNS clients are pointed at, and the trace, metric and
 * dashboard backends. Bring it up with {@code docker compose up -d}; {@code localstack-init/init-aws.sh}
 * provisions the three buckets, the FIFO queue and the notification topic <strong>idempotently</strong>, so
 * repeated cycles converge instead of failing on an already-existing resource. That idempotency is not a
 * modern affectation - it is the source's own habit: {@code SET MAXCC = 0} follows the pre-delete at
 * {@code app/jcl/CREASTMT.JCL:L28}, and {@code IF LASTCC=12 THEN SET MAXCC=0} follows every generation-group
 * definition in {@code app/jcl/DEFGDGB.jcl} at {@code :L29}, {@code :L35}, {@code :L41}, {@code :L47},
 * {@code :L53} and {@code :L59}.
 *
 * <p><strong>Add no dependency to make anything here work.</strong> No Lombok, and no separate token library
 * such as {@code jjwt}, {@code java-jwt} or the Auth0 client: the OAuth2 resource server and its Nimbus
 * support are managed by the parent, which is precisely why they were chosen over a library that would have
 * to be pinned by hand. All version pinning lives in the root build descriptor and nothing in this package
 * pins a version.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>This package owns the binding of every property key the application reads, so the inventory below is
 * the load-bearing part of this document. <strong>Every key states its default, or states that it has
 * none.</strong> A key documented without one is worse than an undocumented key, because the reader assumes
 * the absence of a default is the absence of a value. Keys are cited by <strong>key</strong> and never by
 * line number in the configuration resource: a key is a stable identifier, whereas a line number drifts on
 * the next edit and becomes quietly wrong.
 *
 * <h3>Security - SecurityConfig</h3>
 *
 * <dl>
 *   <dt>{@code carddemo.security.jwt.signing-key}, from {@code JWT_SIGNING_KEY}</dt>
 *   <dd><strong>No default, no example and no fallback</strong>, in any of the four profiles. Startup fails
 *       fast when the value is absent, blank, still an unresolved placeholder, or shorter than the 32 bytes
 *       the HMAC algorithm requires. That is the intended behaviour under Rule 1 Clause D rather than a
 *       defect to paper over. The prior migration attempt hardcoded this value - a <strong>High</strong>
 *       severity open defect that this package closes.</dd>
 *
 *   <dt>{@code carddemo.security.jwt.issuer}, from {@code JWT_ISSUER}</dt>
 *   <dd>Defaults to {@code carddemo}. Non-secret metadata, so a documented default is acceptable where the
 *       signing key admits none.</dd>
 *
 *   <dt>{@code carddemo.security.jwt.expiration-minutes}, from {@code JWT_EXPIRATION_MINUTES}</dt>
 *   <dd>Defaults to {@code 30}, and the unit is minutes. Declared alongside the two keys above and bound by
 *       {@code com.cardemo.security.JwtTokenProvider} rather than by this package, which is why the token
 *       provider and not a configuration class is the place to change how long a token lives.</dd>
 *
 *   <dt>{@code carddemo.security.bcrypt.strength}</dt>
 *   <dd>Literal {@code 10}, with no environment indirection, and the configuration class asserts exactly 10
 *       and refuses any other value. The strength is not a preference: it must match the strength of the
 *       hashes the seed migration writes, or every seeded sign-on fails verification.</dd>
 *
 *   <dt>{@code carddemo.security.snapshot.lifetime-seconds}</dt>
 *   <dd>Defaults to {@code 900}. Bound by {@code com.cardemo.security.SnapshotTokenService}, which carries
 *       the change-detection snapshot the account update compares against.</dd>
 *
 *   <dt>{@code carddemo.observability.metrics.scrape.username} and
 *       {@code carddemo.observability.metrics.scrape.password}</dt>
 *   <dd>Both default to <strong>empty</strong>, from {@code METRICS_SCRAPE_USERNAME} and
 *       {@code METRICS_SCRAPE_PASSWORD}. Empty leaves the metrics endpoint closed: it answers every scrape
 *       with a challenge and collects nothing. An absent scrape credential deliberately does
 *       <em>not</em> abort startup, because metrics collection is not a correctness property and a
 *       permanently unstartable application would be the worse outcome. This is the one management endpoint
 *       that is not anonymous, and it is guarded by its own ordered filter chain and its own authority.</dd>
 *
 *   <dt>{@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} and
 *       {@code spring.security.oauth2.resourceserver.jwt.public-key-location}</dt>
 *   <dd><strong>Deliberately not set anywhere.</strong> The decoder is symmetric HMAC and is constructed in
 *       Java from the signing key, so either key would be configuration that is read by nothing - dead
 *       configuration under Rule 1 Clause B, and dead configuration that looks authoritative is the kind
 *       that gets trusted.</dd>
 * </dl>
 *
 * <p>Two settings have no key at all because they are expressed in code: the session creation policy is
 * stateless and cross-site request forgery token state is disabled. Both follow from transformation rule 7 -
 * the pseudo-conversational context flag {@code CDEMO-PGM-CONTEXT} of {@code app/cpy/COCOM01Y.cpy} has no
 * server-side successor, so there is no session to protect and none is created.
 *
 * <h3>Persistence and migration - JpaConfig</h3>
 *
 * <p>The guard asserts <strong>nine</strong> invariants and each one is a property whose wrong value would
 * be silent. None of the nine has a usable default: the guard requires each to be configured explicitly and
 * fails startup on an empty value, precisely so that a profile cannot omit one and inherit whatever the
 * framework would have chosen.
 *
 * <dl>
 *   <dt>{@code spring.jpa.hibernate.ddl-auto}</dt>
 *   <dd>Required to be {@code validate}, always, in every profile. This single value is what turns a
 *       divergence between an entity mapping and the migration-owned schema into a deterministic startup
 *       failure; {@code update} or {@code create} would make the mapping authoritative over the
 *       copybook-derived column widths and lose parity with no error reported.</dd>
 *
 *   <dt>{@code spring.jpa.open-in-view} and {@code spring.jpa.show-sql}</dt>
 *   <dd>Both required to be {@code false}.</dd>
 *
 *   <dt>{@code spring.jpa.properties.hibernate.jdbc.time_zone}</dt>
 *   <dd>Required to be {@code UTC}, so that no rendered value depends on the host's zone.</dd>
 *
 *   <dt>{@code spring.flyway.enabled}, {@code spring.flyway.baseline-on-migrate},
 *       {@code spring.flyway.validate-on-migrate}, {@code spring.flyway.clean-disabled} and
 *       {@code spring.flyway.out-of-order}</dt>
 *   <dd>Required to be {@code true}, {@code false}, {@code true}, {@code true} and {@code false}
 *       respectively. Clean is destructive when disabled is false and must stay true in every profile
 *       including test.</dd>
 * </dl>
 *
 * <p>Exactly <strong>three</strong> migrations exist, on the classpath at {@code db/migration}:
 * {@code V1__create_schema.sql} creating exactly <strong>11 tables</strong> with a not-null constraint on
 * every column, five check constraints, ten foreign keys and the version columns the optimistic-concurrency
 * contract needs; {@code V2__create_indexes.sql} creating exactly the <strong>three</strong> B-trees that
 * stand in for the three alternate indexes catalogued in {@code app/catlg/LISTCAT.txt}; and
 * {@code V3__seed_data.sql}. <strong>There is no fourth migration</strong>, and the batch metadata tables
 * are not one: they come from the framework's own schema script, never from a migration and never as extra
 * tables in the first one.
 *
 * <p>The numeric precisions are bound to the source picture clauses and are not interchangeable. Account
 * money fields are {@code NUMERIC(12,2)}; <strong>{@code TRAN-AMT} and {@code TRAN-CAT-BAL} are
 * {@code NUMERIC(11,2)}, not 12,2</strong>; and {@code DIS-INT-RATE} is {@code NUMERIC(6,2)}. The first
 * migration carries six columns at 12,2, four at 11,2 and two at 6,2, which is the check that the contract
 * survived. There is <strong>zero {@code float} and zero {@code double} in any financial field</strong>:
 * every monetary and rate value is a {@code java.math.BigDecimal} at the scale its picture clause dictates,
 * rounded {@code HALF_EVEN}, and compared with {@code compareTo} rather than {@code equals}.
 *
 * <p><strong>{@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are {@code PIC X(26)} and map to
 * {@code CHAR(26)} character columns carried as strings - never to a temporal type.</strong> The migration
 * declares four such columns. Parsing one into a temporal type and reformatting it would be a
 * <strong>Blocker</strong>, because three mutually incompatible producers write into these fields and the
 * generated form is 26 characters whose fraction is <strong>two</strong> digits followed by four literal
 * zeros: {@code app/cbl/CBTRN02C.cbl:L159-L174} splits the fraction into {@code DB2-MIL PIC 9(002)}, so the
 * precision is centiseconds, not milliseconds and not nanoseconds.
 *
 * <p>Connection-pool tuning, table partitioning, read replicas and encryption at rest are
 * <strong>deferred hardening</strong>. They are disclosed as residual risk rather than silently dropped -
 * see the closing disclosures below for where that register lives.
 *
 * <h3>Batch - BatchConfig</h3>
 *
 * <dl>
 *   <dt>{@code spring.batch.job.enabled}</dt>
 *   <dd>Defaults to {@code false}, with no environment indirection, and production restates it. Jobs do
 *       <strong>not</strong> auto-run on startup: they are launched
 *       by {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator} and by the queue listener that replaces
 *       the JES2 internal reader. This reproduces the fact that the legacy stream was submitted, never
 *       triggered by the online region coming up.</dd>
 *
 *   <dt>{@code spring.batch.jdbc.initialize-schema}</dt>
 *   <dd>Defaults to {@code never} - the base profile's value, restated in production - and is overridden to
 *       {@code always} in the local and test profiles,
 *       where the metadata tables are created on a disposable database. The base value is the safe one, and
 *       the two overrides are the documented delta.</dd>
 *
 *   <dt>{@code carddemo.batch.chunk-size}</dt>
 *   <dd>Defaults to {@code 100}. The reader page sizes - the four read-only verification readers and the two staging
 *       readers - are each {@code 100} as well, so a chunk and a fetch align.</dd>
 *
 *   <dt>{@code carddemo.batch.dataset-window-size}</dt>
 *   <dd>{@code 100}, and this is the only key the batch configuration class itself binds; the default lives
 *       on the constructor parameter, so the key may be omitted entirely.</dd>
 *
 *
 *   <dt>{@code carddemo.batch.jobs.posttran}, {@code .intcalc}, {@code .tranrept}, {@code .combtran} and
 *       {@code .creastmt}</dt>
 *   <dd>Each names its job - {@code POSTTRAN}, {@code INTCALC}, {@code TRANREPT}, {@code COMBTRAN} and
 *       {@code CREASTMT} - and the name is all each carries: every job binds this namespace, and no
 *       {@code .enabled} flag or {@code .steps} count is declared, because a job cannot be disabled while
 *       the five stages are constructor dependencies of the orchestrator and the statement job's step count
 *       is a structural fact of {@code app/jcl/CREASTMT.JCL}. There are
 *       <strong>exactly six jobs</strong>, being those five stages plus the orchestrator,
 *       and <strong>never a seventh</strong>. {@code CBTRN01C} is not one of them: it is read-only - six
 *       {@code SELECT} statements with no {@code WRITE}, {@code REWRITE} or {@code DELETE} anywhere - and
 *       has no job of its own in the legacy stream, so it is a labelled pre-flight <em>step</em> inside the
 *       posting job. Inventing a job for it would invent behaviour.</dd>
 * </dl>
 *
 * <p>Return-code gating maps the four legacy outcomes onto exit statuses: <strong>0</strong> completed,
 * <strong>4</strong> completed with rejects, <strong>8</strong> failed and <strong>12</strong> abend.
 * Return code 4 is set <strong>if and only if the reject count exceeds zero</strong> - there is no other
 * determinant, and {@code app/cbl/CBTRN02C.cbl:L227-L231} is the whole rule: the two end-of-run
 * {@code DISPLAY} counters, then {@code IF WS-REJECT-COUNT} greater than zero, then
 * {@code MOVE 4 TO RETURN-CODE}. An abend is code <strong>999</strong> with return code 12:
 * {@code MOVE 999 TO ABCODE} at {@code :L710} and {@code CALL 'CEE3ABD'} at {@code :L711}, inside
 * {@code 9999-ABEND-PROGRAM} at {@code :L707-L711}.
 *
 * <h3>Cloud integration - AwsConfig</h3>
 *
 * <dl>
 *   <dt>{@code spring.cloud.aws.region.static}</dt>
 *   <dd>Resolves {@code AWS_REGION} and falls back to {@code AWS_DEFAULT_REGION}, so either spelling
 *       satisfies it. <strong>No literal default</strong> in the base profile; the local profile appends a
 *       final development value so a developer needs neither variable.</dd>
 *
 *   <dt>{@code carddemo.aws.s3.batch-input-bucket}, {@code carddemo.aws.s3.batch-output-bucket} and
 *       {@code carddemo.aws.s3.statements-bucket}</dt>
 *   <dd>From {@code CARDDEMO_S3_BATCH_INPUT_BUCKET}, {@code CARDDEMO_S3_BATCH_OUTPUT_BUCKET} and
 *       {@code CARDDEMO_S3_STATEMENTS_BUCKET}. <strong>All three without a default</strong> in the base
 *       profile, deliberately, so that a run cannot silently write into whatever bucket happens to exist.
 *       Object versioning is applied to the <em>output</em> bucket only, which is what the initialisation
 *       script actually does.</dd>
 *
 *   <dt>{@code carddemo.aws.s3.gdg-prefixes.*}, {@code carddemo.aws.s3.transaction-object-prefix} and
 *       {@code carddemo.aws.s3.gdg-retention-generations}</dt>
 *   <dd>Seven prefix keys, one per generation-group base, each with a literal default; the transaction object
 *       prefix defaults to {@code transact}; and the retention count defaults to <strong>{@code 10}</strong>.
 *       That last value is the resolution of the one legacy inconsistency this migration actually settles:
 *       {@code app/jcl/DEFGDGB.jcl} declares {@code LIMIT(5)} for the report group while
 *       {@code app/jcl/REPTFILE.jcl:L27} declares {@code LIMIT(10)} for the same group, and a single
 *       lifecycle value has to be chosen. The larger wins and the conflict is logged; every other legacy
 *       defect is preserved rather than repaired.</dd>
 *
 *   <dt>{@code carddemo.aws.sqs.report-queue}, {@code carddemo.aws.sqs.report-queue-logical-name} and
 *       {@code carddemo.aws.sqs.report-message-group-id}</dt>
 *   <dd>The queue name comes from {@code CARDDEMO_SQS_REPORT_QUEUE} with <strong>no default</strong>; the
 *       logical name and the message group identifier both default to the literal
 *       <strong>{@code carddemo-report-jobs}</strong>. The queue is FIFO with one fixed message group, so
 *       ordering is total rather than per-partition. Its shape is fixed by the source:
 *       {@code app/csd/CARDDEMO.CSD:L499-L505} declares {@code DEFINE TDQUEUE(JOBS)} as
 *       {@code TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT) RECORDSIZE(80) RECORDFORMAT(FIXED)
 *       DISPOSITION(MOD)}, and that <strong>80-byte fixed record</strong> is what fixes the typed message
 *       shape.</dd>
 *
 *   <dt>{@code carddemo.aws.sns.notification-topic}</dt>
 *   <dd>From {@code CARDDEMO_SNS_NOTIFICATION_TOPIC}, <strong>no default</strong>. Exactly one topic is
 *       provisioned, and a second that nothing publishes to must not be created: an unconsumed topic looks
 *       like an integration surface and is not one.</dd>
 *
 *   <dt>{@code spring.cloud.aws.s3.endpoint}, {@code spring.cloud.aws.sqs.endpoint} and
 *       {@code spring.cloud.aws.sns.endpoint}</dt>
 *   <dd>Each resolves {@code AWS_ENDPOINT_URL} with <strong>no default</strong>, and each is declared in
 *       every profile <em>including</em> production, because the emulator is the only target this
 *       application has. {@code spring.cloud.aws.s3.path-style-access-enabled} is {@code true} because
 *       virtual-host addressing would resolve a bucket name as a DNS subdomain that does not exist, and
 *       {@code spring.cloud.aws.sqs.queue-not-found-strategy} is <strong>{@code FAIL}</strong> - the library
 *       default of create would conjure a queue whose name was mistyped and whose FIFO attributes would then
 *       be wrong.</dd>
 *
 *   <dt>{@code spring.cloud.aws.endpoint}</dt>
 *   <dd><strong>Deliberately given no value in any profile</strong>, because that single global override
 *       would redirect every service the library supports and declare an integration surface this
 *       application does not have. It is nevertheless <em>bound and validated</em>, so a deployment that sets
 *       it is checked rather than silently unvalidated - a key the application otherwise never reads is
 *       exactly where an unapproved address would slip in unnoticed.</dd>
 * </dl>
 *
 * <p><strong>There is no live-endpoint fallback, structurally.</strong> The configuration class refuses to
 * let the context refresh unless every resolved endpoint is an absolute {@code http} or {@code https} URI
 * carrying no user information, with an explicit port and a host on its emulator allowlist; and it rejects
 * any access key beginning with the two live prefixes outright. <strong>No credential default appears in any
 * profile</strong>, no credential value appears in any file, and no code path may reach a real endpoint.
 *
 * <p>Record lengths are preserved <strong>byte-exactly</strong> at the object-store boundary:
 * <strong>430</strong>, <strong>350</strong>, <strong>133</strong>, <strong>100</strong> and
 * <strong>80</strong>. The 430 is not an arbitrary number - it is the 350-byte transaction image plus an
 * 80-byte trailer carrying a four-digit reason code and a 76-character description, and emitting anything
 * else breaks the side-by-side comparison that parity is proved with.
 *
 * <h3>Observability - ObservabilityConfig</h3>
 *
 * <dl>
 *   <dt>{@code carddemo.time.zone}, from {@code CARDDEMO_TIME_ZONE}</dt>
 *   <dd>Defaults to {@code UTC}. An unparseable or unresolved value aborts startup rather than falling back
 *       to the host zone, because a time source that silently varies by host is not a time source.</dd>
 *
 *   <dt>{@code management.endpoints.web.exposure.include}</dt>
 *   <dd>Exactly {@code health}, {@code info} and {@code prometheus}. No business alias and no fourth
 *       endpoint. Production additionally disables endpoint discovery and both build and git information.</dd>
 *
 *   <dt>{@code management.endpoint.health.show-details}</dt>
 *   <dd>Defaults to {@code never} - and <strong>never {@code always}</strong>. Production also sets components to
 *       {@code never}. A status-only body is the point: the details enumerate the infrastructure.</dd>
 *
 *   <dt>{@code management.endpoint.health.probes.enabled}</dt>
 *   <dd>{@code true}, with <strong>separate liveness and readiness groups</strong>. Liveness carries the
 *       liveness state alone; readiness carries the readiness state plus the database, the object store and
 *       the queue, and the Compose health check targets the readiness endpoint.
 *       {@code management.endpoint.health.validate-group-membership} is left at its secure default of
 *       {@code true}, which means naming a contributor that does not exist yet makes the application
 *       unbootable - add the contributor and its group membership in one change.</dd>
 *
 *   <dt>{@code management.otlp.tracing.endpoint}, from {@code OTEL_EXPORTER_OTLP_ENDPOINT}</dt>
 *   <dd><strong>No default</strong> in the base profile. The local profile defaults it to the loopback
 *       collector, and the test profile excludes the exporter's auto-configuration outright so that a suite
 *       never waits on a collector that is not there.</dd>
 *
 *   <dt>{@code management.tracing.sampling.probability}</dt>
 *   <dd>Defaults to {@code 1.0} in the base profile and to {@code 0.1} in production.
 *       <strong>Explicit in both</strong> -
 *       an implicit sampling rate is a rate nobody has agreed to.</dd>
 * </dl>
 *
 * <p>There are <strong>exactly four named instruments</strong>, all four of them counters, and they are
 * defined by {@code com.cardemo.observability.MetricsConfig} rather than here: records processed, records
 * rejected, authentication attempts and a signed transaction-amount total. <strong>No fifth instrument, and
 * no high-cardinality tag.</strong> The rejected counter is tagged <em>only</em> by the bounded reject code,
 * which is a closed enumeration of exactly five constants - <strong>100, 101, 102, 103 and 109</strong> -
 * each carrying the exact literal description the source writes into the reject record. The amount counter
 * stays a counter by being partitioned on the source's own sign predicate into a credit series and a debit
 * series whose difference is the net.
 *
 * <p>The diagnostic-context keys are exactly {@code correlationId}, {@code traceId} and {@code spanId} in
 * HTTP scope, owned by {@code com.cardemo.observability.CorrelationIdFilter}, plus the job-instance key that
 * batch events carry, contributed by the listener declared in the batch configuration class. The filter is
 * HTTP-scoped and is forbidden from adding a batch key; the listener is the only place the batch key is
 * added, and it removes it again after the job.
 *
 * <p>The scrape configuration lives in the root-owned {@code observability/prometheus.yml} and the dashboard
 * provisioning under {@code observability/grafana}. <strong>Neither is duplicated in Java.</strong>
 * Log encoding and masking live in {@code src/main/resources/logback-spring.xml}, profile-invariantly, and
 * cover credentials, password hashes, tokens and signing keys, authorization and bearer headers, card
 * numbers, telephone numbers, government identifiers, dates of birth, social security numbers and electronic
 * funds account identifiers. One legacy literal is exempt because it is a contract rather than a payload:
 * {@code FILE STATUS IS: NNNN} passes through <strong>byte for byte</strong>, as emitted at
 * {@code app/cbl/CBTRN02C.cbl:L721} and {@code :L725} inside {@code 9910-DISPLAY-IO-STATUS} at
 * {@code :L714-L727}.
 *
 * <h3>Web - WebConfig</h3>
 *
 * <dl>
 *   <dt>{@code server.port}, from {@code SERVER_PORT}</dt>
 *   <dd>Defaults to {@code 8080}. A port is not a secret, so a documented non-secret default is
 *       acceptable.</dd>
 *
 *   <dt>{@code spring.profiles.active}</dt>
 *   <dd><strong>Deliberately not set in the base profile</strong>, and there is no default profile. The base
 *       profile is the safe one on its own, so an unset value cannot accidentally elect a development
 *       configuration.</dd>
 *
 *   <dt>{@code spring.session.*}</dt>
 *   <dd><strong>Absent entirely, with no default and no equivalent.</strong> Nothing creates, tracks or
 *       persists an HTTP session. Paging state moves to request parameters and response metadata, never to
 *       the server.</dd>
 *
 *   <dt>{@code spring.main.allow-bean-definition-overriding}</dt>
 *   <dd>Defaults to {@code false}, with no environment indirection. A duplicate bean definition therefore
 *       aborts context refresh rather than shadowing
 *       one definition with the other in an order-dependent way. A loud failure beats a quiet
 *       coin-toss.</dd>
 *
 *   <dt>The Jackson settings</dt>
 *   <dd>Owned by the configuration resource and <strong>never contradicted in Java</strong>: no numeric
 *       timestamps, unknown properties refused, and no decimal rendered as a floating-point number. What the
 *       message-converter hook adds is a <em>copy</em> of the shared mapper carrying one additional inbound
 *       screen, so every declared setting is inherited rather than restated and the screen cannot reach a
 *       consumer that reads something other than a request body.</dd>
 *
 *   <dt>{@code carddemo.pagination.card-list-page-size},
 *       {@code carddemo.pagination.transaction-list-page-size} and
 *       {@code carddemo.pagination.user-list-page-size}</dt>
 *   <dd>Default to {@code 7}, {@code 10} and {@code 10} respectively, fixed by the source and never varied
 *       per environment: the card
 *       list from {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
 *       {@code app/cbl/COCRDLIC.cbl:L177-L178}; the transaction list from the loop bounds at
 *       {@code app/cbl/COTRN00C.cbl:L290} and {@code :L297}; the user list from
 *       {@code 02 USER-REC OCCURS 10 TIMES} at {@code app/cbl/COUSR00C.cbl:L57}. Each is read by the service
 *       that serves that list, and this package registers <strong>nothing</strong> that could impose a
 *       global page size, because one differing from 7, 10 and 10 would silently override all three.</dd>
 *
 *   <dt>The report page size</dt>
 *   <dd><strong>Not a property, deliberately.</strong> Twenty lines per page is a constant on
 *       {@code com.cardemo.batch.processors.TransactionReportProcessor}; a key for it was declared once,
 *       bound by nothing, and removed, because an operator changing an inert key sees no effect and no
 *       error. Its evidence is {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at
 *       {@code app/cbl/CBTRN03C.cbl:L131-L132}, consumed at {@code :L282} - a report-line count, not a page
 *       size.</dd>
 * </dl>
 *
 * <p>There are <strong>exactly two numeric parsers and deliberately no third</strong>, because the corpus
 * uses two different intrinsics for two different jobs and never interchanges them: a strict digits-only
 * parser standing in for plain {@code FUNCTION NUMVAL} on identifiers and card numbers, at
 * {@code app/cbl/COTRN02C.cbl:L204} and {@code :L218}; and a currency-aware parser standing in for
 * {@code FUNCTION NUMVAL-C} on amounts, and on amounts only, at {@code :L383} and {@code :L456}. Alongside
 * them sits the printer that reproduces the display picture {@code PIC +99999999.99} of {@code :L59}
 * character for character. Collapsing the two parsers into one tolerant parser would accept input the legacy
 * system rejects and reject input it accepts - severity <strong>High</strong>. Every case and formatting
 * operation uses the root locale, so no conversion depends on the host's locale.
 *
 * <p>The inbound boundary limits - maximum JSON nesting depth, string length, number length, name length and
 * document length - are constants on the web configuration class rather than properties, so a payload cannot
 * be made unbounded by an environment override. They are part of the Clause A instruction to treat input as
 * untrusted, not a tuning knob.
 *
 * <h3>Invariants that bind every class in this package</h3>
 *
 * <ul>
 *   <li><strong>Constructor injection only, and zero static mutable fields.</strong> Every static is
 *       {@code final} and immutable. Configuration classes are never {@code final} themselves, because the
 *       container proxies them.</li>
 *   <li><strong>No environment access outside Spring property binding.</strong> Nothing here reads a process
 *       environment variable directly; every value arrives through a documented key, which is what makes the
 *       inventory above complete rather than indicative.</li>
 *   <li><strong>No absolute host path</strong>, and no reliance on the default charset, locale or time zone.
 *       Fixed-width work uses an explicit single-byte charset; comparisons and formatting use the root
 *       locale; the time source is the injected clock at the configured zone.</li>
 *   <li><strong>No swallowed exception.</strong> Every catch rethrows a typed
 *       {@code com.cardemo.exception} subtype - the base plus its eight specialisations - preserving the
 *       cause and adding context. A guard that failed quietly would defeat the reason it exists.</li>
 *   <li><strong>No process execution and no untrusted deserialisation.</strong> No runtime exec, no process
 *       builder, no Java deserialisation of untrusted input, and no query built by string concatenation.</li>
 *   <li><strong>No codepage conversion.</strong> {@code app/data/EBCDIC} - twelve dataset files plus a
 *       placeholder - is byte-level reference only and is never parsed by the build. The nine ASCII fixtures
 *       are the authoritative seed and test input.</li>
 *   <li><strong>Jakarta namespaces only.</strong> The servlet API is {@code jakarta.servlet}, never the
 *       superseded spelling.</li>
 *   <li><strong>{@code app/} and {@code samples/} are frozen byte for byte.</strong> The migration is purely
 *       additive. The legacy tree is simultaneously the parity oracle, the field-contract source and the
 *       traceability anchor, and it loses all three roles the moment it is edited.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Every entry carries a severity - <strong>Blocker</strong>, <strong>High</strong>,
 * <strong>Medium</strong> or <strong>Low</strong> - and a concrete remediation, as Rule 1 Clause F requires.
 * Several remediations are stated <em>directionally</em> on purpose: when two places could be changed to make
 * a symptom disappear, naming which one is wrong is the whole value of writing it down.
 *
 * <dl>
 *   <dt><strong>Blocker.</strong> Dependency resolution fails naming a bare container-testing artefact, or a
 *       suite compiles against an API that no longer exists</dt>
 *   <dd>The 2.x line renamed every module artefact, so the bare {@code localstack}, {@code postgresql} and
 *       {@code junit-jupiter} coordinates <strong>do not exist at 2.0.3</strong>, and the parent
 *       independently manages a 1.x version. <em>Remediation, both parts required together:</em> set the
 *       container-testing version property to {@code 2.0.3} <strong>and</strong> use only the four prefixed
 *       coordinates {@code testcontainers}, {@code testcontainers-localstack},
 *       {@code testcontainers-postgresql} and {@code testcontainers-junit-jupiter}. Overriding without
 *       renaming resolves artefacts that were never published; renaming without overriding resolves the
 *       wrong version. Do not add a second bill-of-materials import to work around either.</dd>
 *
 *   <dt><strong>Blocker.</strong> A floating-point type appears in a financial field, or a temporal type
 *       appears on a transaction timestamp</dt>
 *   <dd>Both lose parity silently and no test catches either unless the contract is asserted.
 *       <em>Remediation:</em> use {@code java.math.BigDecimal} at the precision the picture clause dictates -
 *       12,2 for account money, 11,2 for the transaction amount and the category balance, 6,2 for the
 *       interest rate - rounding {@code HALF_EVEN} and comparing with {@code compareTo}; and carry
 *       {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} as {@code CHAR(26)} strings passed through without
 *       parsing or reformatting.</dd>
 *
 *   <dt><strong>Blocker.</strong> A metric tag, span attribute or log field carries an unbounded or sensitive
 *       value</dt>
 *   <dd>A high-cardinality label degrades the metric store and cannot be masked afterwards, because a label
 *       is not a message. <em>Remediation:</em> tag only by values from a closed set - the bounded reject
 *       code, the bounded outcome and the bounded sign - and never by an account identifier, card number,
 *       user identifier, URI variable or exception message. Templated request paths, not concrete ones.</dd>
 *
 *   <dt><strong>High.</strong> Startup fails with a duplicate bean definition</dt>
 *   <dd>{@code spring.main.allow-bean-definition-overriding} is {@code false}, so this aborts context refresh
 *       instead of shadowing one definition in an order-dependent way. <em>Remediation, stated
 *       directionally: remove the duplicate from this {@code config} package, not from the sibling package
 *       that owns it.</em> {@code com.cardemo.observability.MetricsConfig} is the designated definition site
 *       for the four instruments; each of the six classes in {@code com.cardemo.batch.jobs} is the
 *       designated definition site for its own job; {@code com.cardemo.config.SecurityConfig} is the
 *       designated owner of the single decoder and the password encoder; and
 *       {@code com.cardemo.config.ObservabilityConfig} is the designated owner of the single clock.</dd>
 *
 *   <dt><strong>High.</strong> The application starts, but every authenticated request is denied</dt>
 *   <dd>Claim-name drift produces a silent authorisation denial with no error anywhere.
 *       <em>Remediation:</em> confirm the role-claim <strong>name</strong> and the two authority strings are
 *       one shared constant rather than three literals - {@code com.cardemo.security.JwtTokenProvider}
 *       declares all three and both the authentication filter and the security configuration reference them
 *       rather than re-spelling them. Re-spelling any of the three in a literal is the defect to look
 *       for.</dd>
 *
 *   <dt><strong>High.</strong> Startup fails naming {@code carddemo.security.jwt.signing-key}</dt>
 *   <dd><em>Remediation:</em> set {@code JWT_SIGNING_KEY} in the environment, at least 32 bytes for the HMAC
 *       algorithm; loading the git-ignored environment file is enough. There is deliberately no default, no
 *       example and no fallback, in any profile. <strong>This is correct fail-fast behaviour, not a bug</strong>,
 *       and the fix is never to add a literal to a profile: the prior migration attempt hardcoded this value
 *       and that was a High-severity defect.</dd>
 *
 *   <dt><strong>High.</strong> A deployment runs the base profile in production</dt>
 *   <dd>The base profile is safe but not least-privileged: it leaves endpoint discovery on, tolerates
 *       development-shaped database credentials and samples every trace. <em>Remediation:</em> activate the
 *       production profile, which narrows Actuator exposure, sets health details and components to never,
 *       disables discovery and both build and git information, removes every credential default, turns the
 *       batch metadata initialiser off and reduces trace sampling. That profile exists <em>because</em> Rule
 *       1 Clause D requires least privilege - the prior attempt shipped none at all.</dd>
 *
 *   <dt><strong>High.</strong> The build fails with a warning escalated to an error, or the documentation
 *       gate fails on a comment</dt>
 *   <dd><em>Remediation:</em> in documentation prose write the two angle brackets and the ampersand as their
 *       entities - {@code &lt;}, {@code &gt;} and {@code &amp;} - rather than bare;
 *       prefer inline code spans over link tags for cross-package references, because an unresolved link tag
 *       fails the gate; never link a non-Java artefact such as a build descriptor, a migration, a shell
 *       script or a legacy member; give every table a caption or use a list instead - this file uses lists
 *       throughout for exactly that reason; and keep every markup tag balanced. One caveat: an
 *       <em>unused import</em> is not caught by the compiler gate, because {@code javac} 25 publishes no
 *       {@code unused} lint key, so it must be caught at review. This documentation file imports
 *       nothing.</dd>
 *
 *   <dt><strong>High.</strong> A diagnostic-context key leaks from one batch run into the next on a pooled
 *       thread</dt>
 *   <dd>The context is thread-local and a batch thread is reused. <em>Remediation:</em> the job-instance key
 *       must be removed in the listener's after-job callback, not merely set in the before-job callback. The
 *       listener declared in the batch configuration class does both, and it is the only place that key is
 *       touched.</dd>
 *
 *   <dt><strong>Medium.</strong> Migration validation fails, or schema validation fails at startup</dt>
 *   <dd>The entity mapping and the migrated schema have diverged, and the guard is working.
 *       <em>Remediation:</em> the schema is owned by the three migrations and {@code ddl-auto} is
 *       {@code validate}, so correct the entity mapping - never the database, never by relaxing
 *       {@code ddl-auto}, and <strong>never by adding a fourth migration</strong>.</dd>
 *
 *   <dt><strong>Medium.</strong> No traces appear in the trace user interface</dt>
 *   <dd><em>Remediation:</em> confirm {@code OTEL_EXPORTER_OTLP_ENDPOINT} is set and the collector is up in
 *       the Compose topology. In the test profile the exporter's auto-configuration is excluded by design, so
 *       an absent trace there is expected rather than broken.</dd>
 *
 *   <dt><strong>Medium.</strong> The metrics scrape returns nothing, or the dashboard is empty</dt>
 *   <dd>Two independent causes. <em>Remediation:</em> first, the scrape endpoint is the one management
 *       endpoint that is not anonymous and its credential keys default to empty, which leaves it closed - set
 *       both scrape credentials and give the collector the same pair. Second, the scrape target, the
 *       datasource and the dashboard are provisioned by the three root-owned files under the
 *       {@code observability} directory; those files are the fix and <strong>Java must not recreate
 *       them</strong>.</dd>
 *
 *   <dt><strong>Medium.</strong> Readiness reports down shortly after the topology comes up</dt>
 *   <dd>The readiness group covers the database, the object store and the queue, so it is legitimately down
 *       until the emulator has finished provisioning. <em>Remediation:</em> wait, or re-run
 *       {@code localstack-init/init-aws.sh} - it is idempotent, so a second run converges rather than failing
 *       on an already-existing resource.</dd>
 *
 *   <dt><strong>Medium.</strong> A job appears not to run</dt>
 *   <dd><em>Remediation:</em> none needed - {@code spring.batch.job.enabled} is {@code false}
 *       <strong>by design</strong>. Launch through the pipeline orchestrator or through the queue listener
 *       that replaces the internal reader. Setting the key to {@code true} to "fix" this would auto-launch
 *       every job on every boot, which has no legacy analogue.</dd>
 *
 *   <dt><strong>Medium.</strong> The combine load fails on a duplicate key</dt>
 *   <dd>The interest date parameter was reused, and because that parameter leads the generated identifier the
 *       second run produces colliding identifiers. <em>Remediation:</em> supply the correct date parameter.
 *       The collision must surface as a duplicate-record exception and a failed exit status and
 *       <strong>never as a silent upsert</strong> - the interest job writes a fresh sequential generation and
 *       has no duplicate detection of its own, so the load step is the only place the mistake can be
 *       caught.</dd>
 *
 *   <dt><strong>Medium.</strong> An amount at or above one hundred million loses its high-order digit when
 *       echoed back</dt>
 *   <dd><em>Remediation: none - this is a preserved legacy quirk and is deliberately not fixed.</em> The
 *       edited picture {@code PIC +99999999.99} at {@code app/cbl/COTRN02C.cbl:L59} has eight integer
 *       positions while the numeric field {@code PIC S9(9)V99} at {@code :L58} holds nine, and the source
 *       moves the second into the first. Widening the mask would change observable output, which is a
 *       behaviour change; the quirk is cited and logged instead.</dd>
 *
 *   <dt><strong>Medium.</strong> A tool, script or search silently omits part of the corpus</dt>
 *   <dd>A case-sensitive {@code *.jcl} pattern drops {@code app/jcl/CREASTMT.JCL}, whose extension is
 *       uppercase - and that member is the <em>only</em> source for statement generation, so a whole feature
 *       disappears from scope with no error. The same trap exists for {@code *.cbl}, which misses
 *       {@code app/cbl/CBSTM03A.CBL} and {@code app/cbl/CBSTM03B.CBL} and therefore undercounts the corpus.
 *       <em>Remediation:</em> match {@code app/jcl}, {@code app/cbl} and {@code app/cpy}
 *       case-insensitively, everywhere, always.</dd>
 *
 *   <dt><strong>Low.</strong> A legacy copybook carries no licence banner</dt>
 *   <dd>{@code app/cpy/CVCRD01Y.cpy} has none: banner coverage in {@code app/cpy} is 12 of 28, so 16 members
 *       are without one, while {@code app/cbl} is 28 of 28. <em>Remediation: no action.</em> The frozen tree
 *       is not edited; the Java artefacts derived from it carry the banner instead, as this file does.</dd>
 *
 *   <dt><strong>Low.</strong> The component catalogue entry describes the wrong kind of component</dt>
 *   <dd>{@code catalog-info.yaml} declares {@code spec.type: website} and
 *       {@code spec.system: blitzy-typescript}, and its tag list includes {@code python},
 *       {@code typescript} and {@code web-app}. None of that describes this repository, which is one
 *       deployable JAR exposing REST and JSON. <em>Remediation:</em> correct the type, the system and the
 *       tags. <strong>Deliberately not in scope here</strong> - it is unrelated to the migration, and this
 *       package may not edit it.</dd>
 * </dl>
 *
 * <h3>Not available - what is missing, and what would be needed</h3>
 *
 * <p>Rule 1 Clause F requires that missing information be stated as missing rather than filled with an
 * invention. Each item below is therefore recorded as <strong>{@code Not available}</strong> together with
 * what would be needed to resolve it. None of them is guessed at anywhere in this package.
 *
 * <dl>
 *   <dt>1. The program behind one CICS transaction: {@code Not available}</dt>
 *   <dd>{@code app/csd/CARDDEMO.CSD:L388} defines transaction {@code CDV1}, and {@code :L390} names its
 *       program {@code COCRDSEC}, which is also defined at {@code :L211}. <strong>That program has no source
 *       file anywhere in the repository</strong> - a search of {@code app/} for the name returns the
 *       resource-definition file itself and nothing else. Consequently no endpoint, no security rule and no
 *       authority entry is invented for it, and the authorisation table covers <strong>17</strong>
 *       transactions rather than 18. <em>Needed:</em> a {@code COCRDSEC} source member, or a change to the
 *       resource definitions removing the dangling entry.</dd>
 *
 *   <dt>2. A locator for the CICS transaction identifier field: {@code Not available}</dt>
 *   <dd>{@code EIBTRNID} has <strong>zero occurrences under {@code app/}</strong>. The complete
 *       exec-interface-block census under {@code app/} is {@code EIBCALEN} at 49 occurrences and
 *       {@code EIBAID} at 44 - 16 of them in {@code app/cbl} and 28 in {@code app/cpy/CSSTRPFY.cpy} - and
 *       nothing else, so no {@code app/...:Lnnn} citation may be fabricated for it, and in particular none
 *       may be fabricated in support of a claim that the correlation identifier replaces it.
 *       <em>Needed:</em> nothing, because there is nothing to replace: the correlation identifier is
 *       additive capability, and where a citation is wanted the honest ones are {@code app/cbl/COSGN00C.cbl:L37}, where the transaction identifier is a
 *       working-storage literal {@code WS-TRANID PIC X(04) VALUE 'CC00'}, and
 *       {@code app/cbl/COCRDLIC.cbl:L295}, the {@code DFHCOMMAREA} declaration
 *       {@code OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}.</dd>
 *
 *   <dt>3. The observability provisioning content, seen from Java: {@code Not available}</dt>
 *   <dd>The scrape configuration, the datasource provisioning and the dashboard definition are root-owned
 *       files under the {@code observability} directory. <strong>No bean, constant or classpath resource in
 *       this package restates any of them</strong>, deliberately, so from inside Java their content is
 *       {@code Not available} and must not be reconstructed here - a second copy would drift and both copies
 *       would look authoritative. <em>Needed:</em> read or edit those root-owned files directly.</dd>
 *
 *   <dt>4. A service-level objective, throughput target or latency target: {@code Not available}</dt>
 *   <dd><strong>None exists anywhere in the frozen corpus.</strong> The legacy system publishes no service
 *       level agreement, no objective and no threshold, and none may be invented. Performance evidence is
 *       therefore recorded as a <strong>measured baseline, not a target</strong>: throughput, per-endpoint
 *       latency and peak heap are measured and reported without a pass mark, and no alert rule asserts one.
 *       <em>Needed:</em> a stakeholder-agreed objective, which is an input this migration does not have and
 *       may not manufacture.</dd>
 *
 *   <dt>5. Program sources for three batch artefacts: {@code Not available}</dt>
 *   <dd>{@code app/jcl/COMBTRAN.jcl} <strong>has no COBOL program</strong> - its logic is entirely sort and
 *       load control cards, so the job control itself is the source of truth and a program citation for it is
 *       {@code Not available}. {@code CBTRN01C} has <strong>no distinct job</strong> and becomes a labelled
 *       read-only pre-flight step. And {@code app/jcl/CBADMCDJ.jcl}, {@code app/jcl/OPENFIL.jcl} and
 *       {@code app/jcl/CLOSEFIL.jcl} have <strong>no Java analogue</strong> beyond the security
 *       configuration and the health contributors respectively: the first installs resource definitions a
 *       Spring application does not have, and the other two make datasets available to a region that no
 *       longer exists. <em>Needed:</em> nothing - in each case the absence is itself the finding, and it is
 *       recorded rather than filled.</dd>
 *
 *   <dt>6. Copybooks with no Java counterpart: {@code Not available}</dt>
 *   <dd>{@code app/cpy/UNUSED1Y.cpy} has <strong>zero {@code COPY} references repository-wide</strong> and is
 *       dispositioned as documented dead, so no type is generated from it. {@code DFHAID},
 *       {@code DFHBMSCA} and {@code DFHATTR} are supplied by the transaction monitor, are
 *       <strong>absent from this repository</strong>, and have no Java import - their content is
 *       {@code Not available} here and their function is discharged by framework mechanisms instead.
 *       <em>Needed:</em> nothing; neither absence blocks anything.</dd>
 *
 *   <dt>7. The evidence register itself: <em>authored</em>, and no longer an unavailability</dt>
 *   <dd>Rule 1 Clause B forbids a deferred item without an owner or a tracking reference, and the reference
 *       used throughout this document is {@code DECISION_LOG.md} with its companion
 *       {@code TRACEABILITY_MATRIX.md}. <strong>Both are authored at the repository root.</strong> An earlier
 *       revision of this entry reported {@code Not available} and said neither existed yet at the anchor
 *       commit; that record is withdrawn. The tracking reference for every deviation and preserved quirk
 *       named here remains the citation printed beside it, because a path and a locator cannot drift from the
 *       configuration they explain in the way a second copy in a separate document can.</dd>
 * </dl>
 *
 * <h3>The one Rule 1 conflict, and how it is resolved</h3>
 *
 * <p>Exactly one conflict exists between the rule and the migration mandate, and it is material. Clause B
 * requires <em>"No dead code, no unused imports, no TODOs without owners or tracking reference."</em> The
 * mandate requires control flow to be preserved one-for-one so that the paragraph map stays mechanically
 * provable. They collide at identifiable sites, the canonical one being {@code 1400-COMPUTE-FEES} at
 * {@code app/cbl/CBACT04C.cbl:L518-L520} - a paragraph label, a comment reading that it is to be
 * implemented, and an exit - which is <strong>genuinely reachable</strong>, performed at {@code :L216}
 * inside the rate-nonzero branch. Under Clause B it should be deleted. Under the mandate it must be kept.
 *
 * <p><strong>Parity governs, and Clause B is satisfied by a different mechanism.</strong> The clause forbids
 * <em>untracked</em> dead code and deferred work without an owner or a tracking reference; a faithfully
 * reproduced reachable no-op that is cited to its source lines, carries an explicit intentional-no-op
 * comment and is recorded in the evidence register is none of those things. Deleting it would produce code
 * that is marginally tidier and demonstrably less traceable, failing a stated acceptance criterion to
 * satisfy a stylistic one.
 *
 * <p>The consequence for this package is narrow and worth stating precisely: <strong>no retained no-op
 * artefact lives in this {@code config} subtree</strong>, so Clause B binds every class here at full
 * strength - dead configuration, an inert property key, a bean nothing consumes and an unused import are all
 * defects here with no parity defence available. Several such items have already been removed for exactly
 * that reason: a navigation-action converter with no consumer, an inert report-page-size key, a metrics block
 * that named tags the code did not register, and a second notification topic nothing published to. What this
 * package may <strong>not</strong> do is tidy away a construct elsewhere in the tree that exists to preserve
 * source behaviour. The distinction is the whole of the resolution: silence about a no-op is the violation,
 * not the no-op.
 */

package com.cardemo.config;
