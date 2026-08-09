/*
 * ******************************************************************
 * Program     : package-info.java (com.cardemo.observability)
 * Package     : com.cardemo.observability
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 * Function    : Documents the observability layer: correlation
 *               identity, metrics and health probes.
 * Replaces    : the DISPLAY-only instrumentation of the legacy corpus
 *               (322 DISPLAY occurrences; no metrics, tracing or
 *               health) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L194,L227-L232 @ 7756d89
 *                 (end-of-run counter DISPLAYs -> metrics)
 * Source      : app/cbl/CBTRN02C.cbl:L714-L727 @ 7756d89
 *                 (9910-DISPLAY-IO-STATUS, the only structured
 *                 renderer in the corpus)
 * Source      : app/jcl/OPENFIL.jcl:L26-L30
 *               + app/jcl/CLOSEFIL.jcl:L26-L30 @ 7756d89
 *                 (CEMT SET FIL file availability -> health probes)
 * Source      : app/csd/CARDDEMO.CSD @ 7756d89
 *                 (transaction, file and TDQUEUE inventory)
 * Note        : New capability mandated by Rule 1 Clause A; no COBOL
 *               analogue exists.
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
 * Correlation identity, metrics and health probes for CardDemo - the one layer of this migration that is
 * <strong>new capability rather than translation</strong>.
 *
 * <p><strong>Why this file exists.</strong> Rule 1 Clause E requires that <em>"every module/component must
 * have a short README or docstring"</em> covering what it does, how to run, build and test it, its key
 * configurations and defaults, and its common failure modes and troubleshooting. This docstring is that
 * artefact - chosen over a README deliberately, so the documentation travels with the compilation unit and is
 * gated by the same build - and the four headings below are those four bullets in order. Clause B adds
 * <em>"document public APIs: purpose, inputs/outputs, side effects, error modes"</em>, which the five sibling
 * classes discharge member by member; this file documents the seam between them.
 *
 * <h2>What it does</h2>
 *
 * <p><strong>Evidence for why this package exists.</strong> Measured against the frozen corpus at
 * {@code 7756d89}:
 *
 * <ul>
 *   <li><strong>322</strong> textual {@code DISPLAY} occurrences across {@code app/cbl/**} are the entire
 *       telemetry surface of 28 programs and 19,254 lines of COBOL.</li>
 *   <li>The only structured renderer anywhere is {@code 9910-DISPLAY-IO-STATUS} at
 *       {@code app/cbl/CBTRN02C.cbl:L714-L727}, which formats a file status into exactly four
 *       characters.</li>
 *   <li>A case-insensitive search of {@code app/} for {@code prometheus}, {@code micrometer},
 *       {@code opentelemetry}, {@code healthcheck} or {@code actuator} matches <strong>no file at
 *       all</strong>.</li>
 * </ul>
 *
 * <p>So nothing here is a translation of existing behaviour. Every class in this package exists solely
 * because Rule 1 Clause A requires <em>"Observability: structured logs, meaningful errors, and measurable
 * behavior (metrics/tracing where relevant)"</em>, which makes this a rule-mandated package rather than a
 * requirements-derived one, and it ships <strong>with</strong> the initial implementation rather than as
 * follow-up work.
 *
 * <p><strong>No retained-parity artefact lives here.</strong> Because the layer is additive throughout,
 * nothing in this package may ever be justified as "preserved for parity" - there is no legacy control flow
 * to preserve. The documented Clause-B-versus-parity conflict has its sites elsewhere in the tree: the
 * {@code 109} constant of {@code com.cardemo.model.enums.RejectCode}, assigned at
 * {@code app/cbl/CBTRN02C.cbl:L556} on an already-validated path and never consumed as a reject outcome; the
 * empty-but-reachable {@code 1400-COMPUTE-FEES} paragraph of {@code app/cbl/CBACT04C.cbl}; and a redundant
 * index assignment in {@code app/cbl/CBSTM03A.CBL}.
 *
 * <h3>The five classes, and what each replaces</h3>
 *
 * <dl>
 *   <dt>{@link CorrelationIdFilter}</dt>
 *   <dd>Supplies the per-request thread of identity: it accepts or mints a correlation identifier, publishes
 *       it in the diagnostic context and on the response, and tags the active span with it. The
 *       specification describes this as replacing {@code EIBTRNID}; that citation is
 *       <strong>{@code Not available}</strong> and the reason is recorded under
 *       <em>Evidence gaps</em> below. Severity <strong>Medium</strong> - a documentation-evidence gap, not a
 *       functional one.</dd>
 *
 *   <dt>{@link MetricsConfig}</dt>
 *   <dd>Replaces the end-of-run counter {@code DISPLAY} statements of {@code app/cbl/CBTRN02C.cbl}:
 *       {@code 'TRANSACTIONS PROCESSED :'} at {@code :L227} - one space before the colon - and
 *       {@code 'TRANSACTIONS REJECTED  :'} at {@code :L228}, which carries <strong>two</strong> spaces before
 *       its colon. That asymmetry is in the source and is reproduced in the metric descriptions rather than
 *       tidied away. Both statements are bracketed by
 *       {@code 'START OF EXECUTION OF PROGRAM CBTRN02C'} at {@code :L194} and
 *       {@code 'END OF EXECUTION OF PROGRAM CBTRN02C'} at {@code :L232}.</dd>
 *
 *   <dt>{@link HealthIndicators}</dt>
 *   <dd>Replaces the purpose served by {@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CLOSEFIL.jcl}, whose
 *       {@code ISFIN} streams issue one console command per file - the first of them, verbatim, being
 *       {@code /F CICSAWSA,'CEMT SET FIL(TRANSACT ) OPE'} in the open member and the same command ending
 *       {@code CLO'} in the close member. Both cover exactly five files, in the same order:
 *       {@code TRANSACT}, {@code CCXREF}, {@code ACCTDAT}, {@code CXACAIX} and {@code USRSEC}, at
 *       {@code :L26-L30} in each. Those jobs made datasets <em>available</em>; no COBOL analogue for health
 *       <em>probing</em> exists, so the probing itself is additive.</dd>
 *
 *   <dt>{@link TemplatedUriObservationConvention}</dt>
 *   <dd>Replaces nothing, because the frozen corpus exports no spans: its whole instrumentation is
 *       {@code DISPLAY} to SYSOUT plus the four-character status renderer at
 *       {@code app/cbl/CBTRN02C.cbl:L714-L727}. It exists because exporting spans introduced a disclosure the
 *       corpus could not have: Spring attaches the CONCRETE request path as the {@code http.url} span
 *       attribute, which published {@code SEC-USR-ID} of {@code app/cpy/CSUSR01Y.cpy:L18} on the two
 *       administrative routes and {@code ACCT-ID} of {@code app/cpy/CVACT01Y.cpy:L18} on the account route
 *       into the trace store. It substitutes the route template, so the attribute still says which route ran
 *       and identifies nobody. Rule 1 Clause D, principle of least privilege.</dd>
 *
 *   <dt>{@link BatchJobSpanNamingConvention}</dt>
 *   <dd>Replaces nothing, for the same reason as its sibling above: the corpus exports no spans. It exists
 *       because exporting them introduced a legibility defect the corpus could not have. Every job name in
 *       this system is a JCL member name and therefore upper case, {@code AbstractJob} hands that name to
 *       Micrometer Tracing as the observation's contextual name, and
 *       {@code SpanNameUtil.toLowerHyphen} inserts a hyphen before every upper-case character - so
 *       {@code POSTTRAN} reached the trace store as {@code p-o-s-t-t-r-a-n} and {@code CARDDEMO-PIPELINE} as
 *       {@code c-a-r-d-d-e-m-o--p-i-p-e-l-i-n-e}, traced but findable under no name an operator would search
 *       for. It supplies an already-lower-case contextual name, which that transform leaves untouched, and
 *       leaves the observation name and every tag inherited so the metric contract above is unchanged. QA
 *       finding B-12, Rule 1 Clause A, measurable behaviour. Registered on each of the seven job builders
 *       explicitly, because Spring Batch resolves no convention bean from the context.</dd>
 * </dl>
 *
 * <h3>The four instruments: four counters, ten series</h3>
 *
 * <p>{@link MetricsConfig} declares every name as a constant, and the checked-in Grafana dashboard queries
 * those published names directly. The pair is a contract: rename one side without the other and the panel
 * empties in silence.
 *
 * <ul>
 *   <li>{@link MetricsConfig#METRIC_RECORDS_PROCESSED} {@code = carddemo.batch.records.processed} - counter,
 *       published as {@code carddemo_batch_records_processed_total}, untagged.</li>
 *   <li>{@link MetricsConfig#METRIC_RECORDS_REJECTED} {@code = carddemo.batch.records.rejected} - counter,
 *       published as {@code carddemo_batch_records_rejected_total}, tagged by
 *       {@link MetricsConfig#TAG_REJECT_CODE} {@code = reject.code} and by nothing else.</li>
 *   <li>{@link MetricsConfig#METRIC_AUTHENTICATION_ATTEMPTS} {@code = carddemo.auth.attempts} - counter,
 *       published as {@code carddemo_auth_attempts_total}, tagged by
 *       {@link MetricsConfig#TAG_OUTCOME} {@code = outcome} and by nothing else.</li>
 *   <li>{@link MetricsConfig#METRIC_TRANSACTION_AMOUNT_TOTAL} {@code = carddemo.transaction.amount.total} -
 *       counter, published as {@code carddemo_transaction_amount_total} with no <em>second</em>
 *       {@code _total} suffix because the client strips the trailing {@code total} from the name and
 *       re-appends the reserved suffix. Tagged by {@link MetricsConfig#TAG_SIGN} {@code = sign} and by
 *       nothing else, so it is <strong>exactly two series</strong>, {@code credit} and {@code debit}, and
 *       the signed total is recovered as {@code credit - debit}. Each series is a
 *       {@code FunctionCounter} reading an {@code AtomicReference} that holds an exact
 *       {@code BigDecimal}; the one conversion to {@code double} happens on the scrape thread and is
 *       advisory telemetry, never a financial computation. Two series rather than one signed number for two
 *       independent reasons: a Prometheus counter <strong>may not carry a negative value</strong> - the
 *       client raises {@code IllegalArgumentException} at scrape time and fails the entire response, taking
 *       every other series with it - and {@code app/cbl/CBTRN02C.cbl:L548-L552} itself keeps two
 *       accumulators, {@code ACCT-CURR-CYC-CREDIT} and {@code ACCT-CURR-CYC-DEBIT}, partitioned on exactly
 *       {@code IF DALYTRAN-AMT >= 0}. The tag mirrors the source's own decomposition, its cardinality is
 *       two and cannot grow, and the four-name contract of AAP section 0.7.7 is untouched.</li>
 * </ul>
 *
 * <p><strong>No base unit is declared on any of the four</strong>, deliberately: Micrometer appends a
 * declared base unit to the published name, and the dashboard queries the name without it.
 *
 * <p><strong>Tag cardinality is bounded by the type system, not by convention.</strong> The reject tag takes
 * exactly five values - {@code 100}, {@code 101}, {@code 102}, {@code 103} and {@code 109} - because
 * {@link MetricsConfig#countRecordRejected} accepts only
 * {@code com.cardemo.model.enums.RejectCode}. Those five are assigned in the source at
 * {@code app/cbl/CBTRN02C.cbl:L385} ({@code INVALID CARD NUMBER FOUND}), {@code :L397}
 * ({@code ACCOUNT RECORD NOT FOUND}), {@code :L410} ({@code OVERLIMIT TRANSACTION}), {@code :L417}
 * ({@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}) and {@code :L556}. The outcome tag takes exactly two
 * values, {@code success} and {@code failure}, from {@code MetricsConfig.AuthenticationOutcome} - one
 * failure value rather than several, because distinguishing an unknown identifier from a wrong password in
 * telemetry tells an attacker which identifiers exist. <strong>No fifth instrument and no additional tag may
 * be added.</strong> Unbounded tag cardinality is precisely the "obvious inefficiency" Clause A forbids, and
 * a per-subject tag would also put identity into telemetry in breach of Clause D.
 *
 * <h3>The diagnostic context keys</h3>
 *
 * <p>{@link CorrelationIdFilter} publishes three keys, whose spellings are exactly what
 * {@code src/main/resources/logback-spring.xml} consumes through its {@code %mdc} providers:
 * {@link CorrelationIdFilter#MDC_KEY_CORRELATION_ID} {@code = correlationId},
 * {@link CorrelationIdFilter#MDC_KEY_TRACE_ID} {@code = traceId} and
 * {@link CorrelationIdFilter#MDC_KEY_SPAN_ID} {@code = spanId}. The identifier is also echoed on the
 * response under {@link CorrelationIdFilter#CORRELATION_ID_HEADER} {@code = X-Correlation-Id} and attached to
 * the active span as {@link CorrelationIdFilter#SPAN_TAG_CORRELATION_ID} {@code = correlation.id}.
 *
 * <p>Batch events additionally carry {@link CorrelationIdFilter#MDC_KEY_JOB_INSTANCE_ID}
 * {@code = jobInstanceId}, set through {@link CorrelationIdFilter#propagateJobInstanceId}. That key is what
 * makes a per-run object prefix in S3 and the log records of the same run correlatable to each other.
 *
 * <h3>Ordering, and where the boundaries lie</h3>
 *
 * <ul>
 *   <li><strong>Filter order.</strong> {@link CorrelationIdFilter#ORDER} is
 *       {@code Ordered.HIGHEST_PRECEDENCE + 2}, which places this filter after the observation filter that
 *       Spring Boot registers at {@code HIGHEST_PRECEDENCE + 1} and before the Security filter chain at
 *       {@code SecurityProperties.DEFAULT_FILTER_ORDER}, {@code -100}. The effective order is therefore
 *       {@link CorrelationIdFilter} then {@code com.cardemo.security.JwtAuthenticationFilter} then
 *       role-based authorisation - so a request rejected with 401 or 403 is still correlated. Chain
 *       composition itself belongs to {@code com.cardemo.config.SecurityConfig}, not here.</li>
 *   <li><strong>Registration versus definition, stated precisely because the two are easy to confuse.</strong>
 *       {@code com.cardemo.config.ObservabilityConfig} does not own tracing and metrics registration, nor the
 *       wiring of these three classes. What it declares is <strong>exactly one {@code @Bean}, the application
 *       {@link java.time.Clock}</strong>, and that count is asserted by its own test. The three classes in this
 *       package <strong>self-register</strong> - {@link CorrelationIdFilter} and {@link HealthIndicators}
 *       through {@code @Component}, {@link MetricsConfig} through {@code @Configuration} - so the component
 *       scan rooted at {@code com.cardemo} reaches them without any wiring being written anywhere.
 *       Framework telemetry is <strong>auto-configured</strong>, not registered here: Spring Boot supplies the
 *       {@code MeterRegistry}, the Prometheus scrape endpoint, the Micrometer tracer and the OTLP exporter from
 *       the {@code management.*} properties in {@code application.yml}. {@link MetricsConfig} <em>defines</em>
 *       the four metric names and registers their ten series against the injected registry. Nothing
 *       duplicates anything. It declares one bean and one only, and that bean defines no instrument: a
 *       {@code MeterFilter} that removes Spring Batch 5.2.4's <em>duplicate</em> registration of
 *       {@code spring.batch.job.active}, which the framework binds twice under one name with two different
 *       tag-key sets. That is de-duplicating auto-configured telemetry, not registering any, and
 *       {@code MetricsConfig} states the conflict, the order that decides it and the alternatives rejected.</li>
 *   <li><strong>Nothing here restates root-owned configuration.</strong> The scrape configuration lives only
 *       in {@code observability/prometheus.yml}; the Grafana datasource and dashboard live only in
 *       {@code observability/grafana/provisioning/datasources/datasource.yml} and
 *       {@code observability/grafana/dashboards/carddemo-dashboard.json}; health-group composition lives only
 *       in {@code application.yml}; and the {@code db} contributor is Spring Boot's auto-configured one, not
 *       a fourth bean written here.</li>
 * </ul>
 *
 * <p><strong>The stateless invariant.</strong> This filter chain is what replaces the COMMAREA as the
 * request-scoped context carrier. There is no HTTP session and no server-side state: the identifier lives in
 * the diagnostic context for the duration of one exchange and is restored on the way out. The
 * pseudo-conversational enter-versus-re-enter flag {@code CDEMO-PGM-CONTEXT} of
 * {@code app/cpy/COCOM01Y.cpy} has no Java counterpart at all.
 *
 * <h3>One legacy log literal is a byte-for-byte contract</h3>
 *
 * <p>{@code com.cardemo.service.shared.FileStatusMapper} emits the literal {@code FILE STATUS IS: NNNN}
 * immediately followed by a four-character status. In the source, {@code 9910-DISPLAY-IO-STATUS} carries
 * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} on <em>both</em> of its branches, at
 * {@code app/cbl/CBTRN02C.cbl:L721} and {@code :L725}; the first branch copies byte one through and expands
 * byte two into three digits, the second sets the field to {@code '0000'} and places the two status
 * characters at positions three and four. {@code IO-STATUS-04} is {@code PIC 9} plus {@code PIC 999} at
 * {@code :L138-L140}, so it is exactly four characters.
 *
 * <p>COBOL concatenates a literal and an identifier with no separator, and the literal already contains the
 * placeholder text, so status {@code '23'} emits {@code FILE STATUS IS: NNNN0023} and not
 * {@code FILE STATUS IS: 0023}. <strong>The stray {@code NNNN} is a preserved legacy quirk and is never
 * "fixed".</strong> The end-to-end gate diffs log output against the legacy baseline, so nothing in this
 * package may reformat, wrap, re-render, truncate, mask or JSON-escape that literal away.
 *
 * <p>Note the contrast in posture. The legacy batch reaction to an unexpected status is termination:
 * {@code app/cbl/CBTRN02C.cbl:L708} displays {@code 'ABENDING PROGRAM'}, {@code :L710} moves {@code 999} into
 * the abend code and {@code :L711} calls {@code 'CEE3ABD'} - abend code 999, process return code 12 - while
 * return code 4 is set if and only if the reject count exceeds zero at {@code :L229-L231}. The health probes
 * here deliberately take the opposite posture: they <strong>report</strong>, and never abort.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p><strong>Toolchain.</strong> Java 25 with {@code maven.compiler.release} at {@code 25} and no preview
 * features, Maven 3.9.11 through the pinned repository wrapper, parent
 * {@code org.springframework.boot:spring-boot-starter-parent:3.5.11}. Load the git-ignored {@code .env} inside a
 * subshell that also carries the command - {@code ( set -a; . ./.env; set +a; ./mvnw -B -ntp verify )} - because
 * the JWT signing key has no default and the context fails fast without it. The parentheses confine the exported
 * values to that subshell rather than leaving every later child inheriting them.
 *
 * <ul>
 *   <li>Compile: {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean compile}</li>
 *   <li>Unit tests: {@code ./mvnw -B -ntp -Ddependency-check.skip=true test}</li>
 *   <li><strong>Fast local verification</strong>, which does <strong>not</strong> satisfy the zero-warning
 *       build gate: {@code ./mvnw -B -ntp clean verify}</li>
 *   <li><strong>The full gate</strong>, online and with nothing skipped:
 *       {@code ./mvnw -B -ntp clean verify}</li>
 * </ul>
 *
 * <p><strong>The skip flag is a convenience, never a pass.</strong> Labelling the skipping invocation the
 * "full gate" invites a run that never
 * executed the vulnerability scan to be reported as satisfying a gate whose definition requires it.
 * {@code -Ddependency-check.skip=true} suppresses {@code org.owasp:dependency-check-maven}, which wants network
 * access to the vulnerability feed, and the goal then prints {@code Skipping dependency-check}. Running Maven
 * offline with {@code -o} reaches the same hole by a different route, because the goal declares
 * {@code requiresOnline} and Maven skips it with a warning. <strong>A skipped scan is not evidence that the
 * scan passes</strong>, so only the no-skip online invocation above satisfies the gate whole; the fast form is
 * for the inner development loop, where the compiler, doclint, test and coverage checks all still run.
 *
 * <p><strong>Three gates apply to this package, and one of them applies to this very file.</strong>
 *
 * <ul>
 *   <li>{@code maven-compiler-plugin:3.14.1} runs {@code -Xlint:all} with {@code -Werror} and
 *       {@code failOnWarning}, so a raw type, an unchecked cast or a dangling documentation comment fails the
 *       build outright.</li>
 *   <li>{@code maven-javadoc-plugin:3.11.2} runs a {@code doclint-gate} execution at {@code verify} with
 *       {@code doclint} set to {@code all}, {@code failOnError} and {@code failOnWarnings} both true, and
 *       {@code show} at {@code private}. Malformed HTML or an unresolvable {@code @link} in this file is a
 *       build failure, which is why cross-package references here are written as {@code @code} rather than
 *       linked.</li>
 *   <li>{@code jacoco-maven-plugin:0.8.12} enforces a merged <strong>80% line</strong> floor at
 *       {@code verify} with no exclusions - so the filter, the meter definitions and each health probe must
 *       be genuinely unit-tested rather than merely instantiated.</li>
 * </ul>
 *
 * <p><strong>Tests live outside this package, never inside it.</strong> Unit coverage sits under
 * {@code src/test/java/com/cardemo/unit/observability/**} and the LocalStack-backed integration tier under
 * {@code src/test/java/com/cardemo/integration/aws/**}.
 *
 * <p><strong>The four surfaces this layer exposes at run time.</strong>
 *
 * <ul>
 *   <li>{@code /actuator/health}, plus the two probe groups {@code /actuator/health/liveness} and
 *       {@code /actuator/health/readiness}. The container image's {@code HEALTHCHECK} probes the
 *       <em>readiness</em> path with a five-second timeout, which is the budget the probes below are sized
 *       against.</li>
 *   <li>{@code /actuator/prometheus} - the scrape target, polled every <strong>15 seconds</strong> by
 *       {@code observability/prometheus.yml} under the job name {@code carddemo-app}. It is not the only
 *       collection path: three of the four counters below are written by the batch submission process, which
 *       ends when its job ends and has no endpoint to scrape, so it <em>pushes</em> its final values to the
 *       Pushgateway and {@code observability/prometheus.yml} scrapes that under the job name
 *       {@code carddemo-pushgateway}.</li>
 *   <li><strong>Jaeger</strong> - the trace user interface in the Compose topology, receiving spans through
 *       {@code management.otlp.tracing.endpoint}.</li>
 *   <li><strong>Grafana</strong> - dashboards provisioned from
 *       {@code observability/grafana/dashboards/carddemo-dashboard.json} over the Prometheus datasource, with
 *       no manual configuration step.</li>
 * </ul>
 *
 * <p><strong>Bringing the topology up. {@code docker compose up -d} starts all seven services, and the
 * application is one of them.</strong> The application is a Compose service, so it does not have to be run
 * separately from a built JAR.
 * {@code docker-compose.yml} declares <strong>seven</strong> services on the {@code carddemo} network - the
 * {@code app} service itself, PostgreSQL 16, LocalStack, Jaeger, the Pushgateway, Prometheus and Grafana -
 * plus four named volumes. So a single {@code docker compose up -d} builds the image from the repository {@code Dockerfile},
 * brings the application up alongside its dependencies, and gives Prometheus a scrape target reachable on the
 * Compose network as {@code app:8080}, which is the literal
 * {@code observability/prometheus.yml} names and cannot resolve any other way. The application waits on the
 * dependencies' health checks rather than racing them, and its own health check is what
 * {@code docker compose ps} reports.
 *
 * <p>Two consequences are worth stating because both are easy to get wrong. First, running the JAR on the host
 * <em>instead</em> is still supported and is the right choice for a debugger, but it is a deliberate
 * substitution rather than the documented default: it needs {@code --profile}-style overrides so the host can
 * address {@code localhost} where the container addresses a service name, and Prometheus then cannot reach the
 * application at all without an {@code extra_hosts} change. Second, {@code localstack-init/init-aws.sh}
 * idempotently creates the three S3 buckets, enables versioning on the output bucket and creates the FIFO
 * queue whose logical name is {@code carddemo-report-jobs} and whose physical name carries the mandatory
 * {@code .fifo} suffix - so repeated {@code docker compose up} cycles converge rather than failing on
 * resources that already exist.
 *
 * <p><strong>Container prerequisite, stated plainly per Clause F.</strong> The integration sign-off gate -
 * the full stack up, health reporting {@code UP}, all three Flyway migrations applied - and the
 * Testcontainers-backed tiers <strong>require a working container runtime with an accessible socket</strong>.
 * Where a populated dashboard or a live {@code UP} status cannot be demonstrated, the evidence must read
 * {@code Not available} together with that prerequisite. An asserted, untested pass is never acceptable.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Every property below is owned by {@code src/main/resources} and is <em>bound</em> here, never invented
 * and never read through {@code System.getenv}. Keys are cited by name rather than by line number, because
 * keys are stable and line numbers drift.
 *
 * <ul>
 *   <li>{@code management.endpoints.web.base-path} is {@code /actuator} and
 *       {@code management.endpoints.web.exposure.include} is exactly {@code health,info,prometheus} - never
 *       {@code *}. Widening it is a least-privilege regression under Clause D.</li>
 *   <li>{@code management.endpoint.health.show-details} and {@code show-components} are both {@code never}.
 *       Neither may become {@code always}: the probe details name buckets, queues and failure reasons, and
 *       those belong in a log the operator can reach, not in an unauthenticated HTTP body.</li>
 *   <li>{@code management.endpoint.health.probes.enabled} is {@code true}, which is <em>not</em> the
 *       framework default, and it is what makes the liveness and readiness groups exist at all.</li>
 *   <li>{@code management.endpoint.health.validate-group-membership} is left at its secure default of
 *       {@code true}. This is why naming a contributor in a group before its bean exists makes the
 *       application unbootable - a fail-fast that is wanted.</li>
 *   <li>{@code management.endpoint.health.group.readiness.include} is
 *       {@code readinessState,db,s3,sqs} - the substrate, and all three substrate contributors are declared
 *       in {@link HealthIndicators}. {@code db} is deliberately <em>not</em> the auto-configured datasource
 *       contributor: that one has no deadline of its own, so it inherited the pool's 30 s
 *       {@code connection-timeout} and a measured readiness probe with the database stopped took 30 009 ms -
 *       six times the container health check's own five-second timeout - while the two AWS probes answered
 *       inside 1 500 ms. {@code management.health.db.enabled} is therefore {@code false} and
 *       {@code dbHealthIndicator} takes the same {@code db} key with the same 1 500 ms budget, so all three
 *       contributors now share one deadline. {@code group.liveness.include} is {@code livenessState}
 *       alone, so a dependency outage takes the instance out of rotation <strong>without</strong> triggering
 *       a restart.</li>
 *   <li>{@code management.tracing.sampling.probability} is set explicitly to {@code 1.0} in the base profile
 *       so every request and every batch step is traceable during a parity investigation; production lowers
 *       it. {@code management.otlp.tracing.endpoint} resolves from {@code OTEL_EXPORTER_OTLP_ENDPOINT} with
 *       <strong>no default</strong>.</li>
 *   <li>{@code management.metrics.tags} contributes the two common tags {@code application} and
 *       {@code component}. Both are fixed literals, so neither adds cardinality.</li>
 *   <li>{@code spring.cloud.aws.region.static} resolves from {@code AWS_REGION}, falling back to
 *       {@code AWS_DEFAULT_REGION}, and the credential keys resolve from {@code AWS_ACCESS_KEY_ID} and
 *       {@code AWS_SECRET_ACCESS_KEY}. <strong>None of them carries a default</strong>, so no credential
 *       value is baked into any file.</li>
 *   <li>The bucket keys {@code carddemo.aws.s3.batch-input-bucket}, {@code batch-output-bucket} and
 *       {@code statements-bucket}, and the queue keys {@code carddemo.aws.sqs.report-queue} and
 *       {@code report-queue-logical-name}, are the exact keys {@link HealthIndicators} reads. Only the output
 *       bucket is versioned, because relative generation-data-group references become object versions there.
 *       The queue replaces {@code DEFINE TDQUEUE(JOBS) ... RECORDSIZE(80)} at
 *       {@code app/csd/CARDDEMO.CSD:L499-L505}.</li>
 *   <li><strong>Four profiles:</strong> {@code application.yml}, {@code application-local.yml},
 *       {@code application-test.yml} and {@code application-prod.yml}. The base profile declares the three
 *       {@code spring.cloud.aws.*.endpoint} keys but gives them <strong>no default</strong>; only
 *       {@code local} supplies the LocalStack fallback, and {@code test} has its endpoints registered by the
 *       harness. So there is no hardcoded endpoint, <strong>no live-AWS fallback and no credential default
 *       anywhere</strong> - all cloud interaction targets LocalStack with zero live credentials.</li>
 *   <li>The <strong>JWT signing key is resolved from the environment in every profile and never appears as a
 *       literal</strong>, closing a High-severity defect carried by the prior migration attempt.</li>
 *   <li>{@code src/main/resources/logback-spring.xml} performs profile-invariant masking of credentials,
 *       presented passwords, BCrypt hashes such as {@code $2a$}, {@code $2b$} and {@code $2y$}, tokens,
 *       {@code Authorization} and bearer headers, signing keys, social security numbers, card numbers,
 *       telephone numbers, government identifiers, dates of birth, funds-transfer account identifiers, cloud
 *       payloads and presigned URLs, access-key identifiers, and database rows and bind parameters.</li>
 * </ul>
 *
 * <p><strong>Masking is the second line of defence, not the first.</strong> It matches configured labels and
 * field shapes, so a value reaching a log under an unexpected label, inside a concatenated message or nested
 * in a serialised object can pass through unmasked. <strong>The primary defence is never emitting the value
 * at all.</strong> This package has the largest Clause D exposure in the tree precisely because emitting
 * telemetry is its entire purpose.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Each entry states the symptom, its severity and the remedy.
 *
 * <dl>
 *   <dt>{@code correlationId} is missing or empty in the JSON logs - <strong>High</strong></dt>
 *   <dd>The key spelling drifted from what {@code logback-spring.xml} consumes, or the filter is not in the
 *       chain. It fails silently with no error, which is what makes it High. The keys are exactly
 *       {@code correlationId}, {@code traceId} and {@code spanId}; change a constant and the logging
 *       configuration in the same commit.</dd>
 *
 *   <dt>{@code traceId} and {@code spanId} are empty while {@code correlationId} is present - <strong>Low</strong></dt>
 *   <dd>No span was active. This is the documented, expected outcome rather than a fault: the filter leaves
 *       both keys absent and the logging configuration renders them as empty strings. Check
 *       {@code management.tracing.sampling.probability} and that the OTLP endpoint is reachable. Note that
 *       the {@code test} profile removes only the OTLP <em>export</em> auto-configuration and deliberately
 *       leaves in-process tracing on, so both keys should still populate under test - if they do not, the
 *       cause is an inactive span rather than the profile.</dd>
 *
 *   <dt>A correlation identifier appears on an unrelated request - <strong>Blocker</strong></dt>
 *   <dd>Cross-request identifier leakage. The diagnostic context was not restored, so a pooled thread
 *       carried a stale value into the next exchange. The restoration <strong>must</strong> run in a
 *       {@code finally} block and must restore the prior value rather than blanket-clearing the context,
 *       because a blanket clear destroys an enclosing batch context.</dd>
 *
 *   <dt>A Grafana dashboard panel is empty - <strong>High</strong> when a rename caused it</dt>
 *   <dd>Work the chain in order. First, is {@code /actuator/prometheus} returning the metric at all? Second,
 *       does the metric name in the panel query match the constant in {@link MetricsConfig} exactly - a
 *       rename on one side only is a silent break of the dashboard contract, and a declared base unit
 *       renames the published series just as effectively. Third, is {@code observability/prometheus.yml}
 *       scraping the right host and port.
 *       <p><strong>What this chain must not conclude is that the counter has simply never been
 *       incremented.</strong> The general
 *       Micrometer rule that a never-incremented counter is absent from the exposition rather than present at
 *       zero is real, and it does not apply to any CardDemo series.
 *       {@link MetricsConfig} registers <strong>all ten of its series eagerly at construction</strong> - one
 *       untagged processed counter, one per reject code, one per authentication outcome and one per amount
 *       sign, each amount series a strongly-referenced {@code FunctionCounter} over an exact accumulator - so
 *       every one of them appears on the scrape endpoint at
 *       {@code 0.0} before any transaction has been posted and before any sign-on has occurred. An empty
 *       CardDemo panel therefore means a name, a tag value, a scrape target or a query is wrong, never that
 *       the series is waiting to be born. Reserve the absence explanation for <em>lazily</em> registered
 *       meters, which is what a framework or third-party instrument resolved on first use will be.</p></dd>
 *
 *   <dt>Readiness is DOWN with an S3 reason - <strong>Medium</strong></dt>
 *   <dd>LocalStack is not up, or a bucket property is unset. Run {@code docker compose up -d localstack} and
 *       re-check {@code /actuator/health/readiness}; the {@code reason} detail distinguishes
 *       {@code not-configured} from {@code missing}, {@code unreachable} and {@code timeout}.</dd>
 *
 *   <dt>Readiness is DOWN with an SQS reason - <strong>Medium</strong></dt>
 *   <dd>{@code localstack-init/init-aws.sh} has not created the report queue. Re-run the script; it is
 *       idempotent, so repeated Compose cycles converge instead of failing.</dd>
 *
 *   <dt>Liveness is DOWN because a dependency is unreachable - <strong>Blocker</strong></dt>
 *   <dd>A misconfiguration, not an outage. External dependencies belong to <em>readiness</em>; an
 *       orchestrator reading them under liveness would restart a perfectly healthy process and turn a
 *       recoverable dependency blip into a crash loop. Remove the contributor from
 *       {@code group.liveness.include}.</dd>
 *
 *   <dt>Container start-up ordering fails - <strong>High</strong></dt>
 *   <dd>The image {@code HEALTHCHECK} probes {@code /actuator/health/readiness} and matches on an
 *       {@code UP} status. Renaming that path, disabling {@code probes.enabled}, or letting the aggregate
 *       probe exceed the five-second timeout each break it. Keep the path, the probe budgets and the
 *       health-check definition consistent in one change.</dd>
 *
 *   <dt>{@code /actuator/prometheus} or {@code /actuator/health/readiness} returns 404 - <strong>High</strong></dt>
 *   <dd>The exposure list omits {@code prometheus}, or {@code probes.enabled} is not {@code true}. Add the
 *       specific endpoint or set the specific flag; never widen the exposure list to {@code *}.</dd>
 *
 *   <dt>Reject-counter cardinality explosion - <strong>Blocker</strong></dt>
 *   <dd>A tag was added beyond the bounded five reject codes or two outcomes. Never tag by account
 *       identifier, card number, customer identifier, transaction identifier, user identifier, user name,
 *       correlation identifier, trace identifier, timestamp, object key, message identifier or job execution
 *       identifier. Remove the tag; if the dimension is genuinely needed, it belongs in a log record, which
 *       is unbounded by design.</dd>
 *
 *   <dt>A live-AWS fallback or a credential default appears in configuration - <strong>Blocker</strong></dt>
 *   <dd>No code path may reach a real cloud endpoint. Remove the default so the property resolves from the
 *       environment or fails.</dd>
 *
 *   <dt>A secret, hash or card number reaches a log line - <strong>Blocker</strong></dt>
 *   <dd>Treat it as a defect in the emitting code, not a gap in the masking rules. Stop passing the value to
 *       a logger; do not extend a pattern and call it fixed.</dd>
 *
 *   <dt>A file status appears in a log with a different shape than the baseline - <strong>High</strong></dt>
 *   <dd>The four-character rendering, or the literal preceding it, was not reproduced. Restore both exactly
 *       as the byte-for-byte contract above states, keeping the {@code NNNN} placeholder text and emitting
 *       the status immediately after it with no separator. Do not add a log pattern, a structured field or a
 *       masking rule that rewrites the message body; the end-to-end gate diffs on it.</dd>
 *
 *   <dt>{@code ./mvnw verify} fails on a warning, a deprecation or malformed documentation - <strong>Low</strong></dt>
 *   <dd>{@code -Xlint:all -Werror} and the {@code doclint-gate} are deliberate. Fix the source or the
 *       documentation; never relax the flag or the gate.</dd>
 *
 *   <dt>The build cannot find {@code java} or {@code mvn} - <strong>Medium</strong></dt>
 *   <dd>The pinned toolchain is not on {@code PATH}. Put a JDK 25 on {@code PATH} with {@code JAVA_HOME}
 *       set and use the repository wrapper, or build inside a Java 25 and Maven 3.9.11 container image
 *       mounting the repository. Do not retarget the build to an older release.</dd>
 * </dl>
 *
 * <h2>Evidence gaps: what is {@code Not available}</h2>
 *
 * <p>Clause F requires that missing information be stated as {@code Not available} together with what would
 * be needed. Two such gaps apply to this package and neither is filled with an invention; a third entry
 * records a preserved legacy artefact, which is a finding rather than a gap.
 *
 * <dl>
 *   <dt>A line-level citation for {@code EIBTRNID} - {@code Not available}. Severity <strong>Medium</strong></dt>
 *   <dd>The specification describes {@link CorrelationIdFilter} as replacing {@code EIBTRNID}, but that
 *       symbol <strong>cannot be cited to a line</strong>: it occurs <strong>zero</strong> times anywhere
 *       under {@code app/**}. The complete EXEC-interface-block census under {@code app/} is two fields and
 *       nothing else - {@code EIBCALEN} 49 times, and {@code EIBAID} 44 times, of which 16 sit in
 *       {@code app/cbl} and 28 in the procedural copybook {@code app/cpy/CSSTRPFY.cpy}. {@code EIBTRNID} is
 *       supplied by the transaction monitor, which is exactly why it is absent from application source.
 *       <em>What would be needed:</em> an {@code EIBTRNID} reference somewhere in {@code app/cbl/**}; there
 *       is none, and no {@code app/} locator may be fabricated for it. The per-request identity evidence
 *       that genuinely <em>is</em> present, and is cited instead:
 *       <ul>
 *         <li>{@code app/csd/CARDDEMO.CSD} - the CICS transaction identifiers, 18
 *             {@code DEFINE TRANSACTION} entries of which 17 are sourced: {@code CAUP}, {@code CAVW},
 *             {@code CA00}, {@code CB00}, {@code CCDL}, {@code CCLI}, {@code CCUP}, {@code CC00},
 *             {@code CM00}, {@code CR00}, {@code CT00}, {@code CT01}, {@code CT02}, {@code CU00},
 *             {@code CU01}, {@code CU02} and {@code CU03}. The eighteenth, {@code CDV1}, targets
 *             {@code PROGRAM(COCRDSEC)}, which has no source anywhere in the repository; it is a dangling
 *             legacy definition and no endpoint is invented for it.</li>
 *         <li>{@code EIBCALEN} - for example {@code app/cbl/COCRDLIC.cbl:L295} declares the COMMAREA as
 *             {@code OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}.</li>
 *         <li>{@code EIBAID} - evaluated in the procedural copybook {@code app/cpy/CSSTRPFY.cpy:L22}
 *             onward, matching {@code DFHENTER}, {@code DFHCLEAR}, {@code DFHPA1}, {@code DFHPA2} and the
 *             function keys in turn.</li>
 *       </ul></dd>
 *
 *   <dt>Live evidence for the container-dependent gates - what remains {@code Not available}, and what no
 *       longer does. Severity <strong>Medium</strong></dt>
 *   <dd><strong>A container runtime is present and the seven-service stack is provisioned</strong>, so the
 *       container prerequisite is not the obstacle, and {@code Not available} is reserved
 *       for evidence that is genuinely absent. What is genuinely absent is a captured run of the frozen
 *       COBOL on real z/OS: {@code src/test/java/com/cardemo/e2e} is authored and the Gate 1 expectation is
 *       committed under {@code src/test/resources/parity/gate1}, so the end-to-end parity comparison is made -
 *       what a z/OS capture would add is corroboration from the real runtime, and no running stack can
 *       conjure it. <em>What is needed:</em> that capture. The gates that need no container at all - the
 *       zero-warning build, the security audit and the scope-coverage check - are runnable unconditionally
 *       and are therefore the first evidence produced. Gate
 *       status per gate is stated in exactly one place, section 0.7.9.2 of
 *       {@code docs/technical-specifications.md}, and dated evidence in section 0.4.5.3; neither is restated
 *       here.</dd>
 *
 *   <dt>A preserved legacy artefact worth one line. Severity <strong>Low</strong></dt>
 *   <dd>{@code app/jcl/OPENFIL.jcl:L1} reads {@code //OEPNFIL JOB 'Open files in CICS'} - the job name
 *       transposes the E and the P while the member is {@code OPENFIL.jcl}. {@code CLOSEFIL.jcl:L1} is
 *       correct. It is <strong>never</strong> corrected: {@code app/} is frozen byte-for-byte as the parity
 *       oracle, the field-contract source and the traceability anchor.</dd>
 * </dl>
 *
 * <h2>Package-level constraints</h2>
 *
 * <ul>
 *   <li>No business logic, no repository access and no security decision belongs here. This layer observes;
 *       it does not decide.</li>
 *   <li>Collaborators arrive by <strong>constructor injection only</strong>. There is no static mutable
 *       state: the counters live in the {@code MeterRegistry} rather than in static fields, and the
 *       diagnostic context is thread-local by design and restored in a {@code finally} block.</li>
 *   <li>Boundary conditions are handled explicitly rather than by omission: an absent, blank or malformed
 *       inbound correlation header is replaced with a freshly generated identifier and never echoed raw; an
 *       inactive span leaves the trace keys absent; an unset bucket or queue property reports
 *       {@code not-configured} rather than throwing.</li>
 *   <li><strong>Log injection and response splitting through the inbound header is the flagged risky
 *       pattern</strong> for this package, and the defence is validation: an anchored, bounded ASCII pattern
 *       that admits no line terminator. There is no {@code Runtime.exec}, no untrusted deserialisation and
 *       no string-concatenated SQL anywhere in the package, and it adds no dependency of its own.</li>
 *   <li>A probe failure is <strong>reported</strong> as a DOWN status carrying a curated, non-sensitive
 *       reason, with the root cause logged once at WARN. It is neither swallowed nor rethrown, and a raw
 *       provider throwable is never attached to a health response.</li>
 *   <li>Nothing here depends on the ambient environment: no {@code System.getenv}, no absolute host path,
 *       and no reliance on the default charset, locale or time zone.</li>
 *   <li>No instrument may be renamed, retagged or given a base unit without the dashboard definition
 *       changing in the same commit; the actuator exposure list may not be widened; and health
 *       group-membership validation may not be disabled to work around a missing contributor.</li>
 *   <li>This directory holds exactly four Java files - {@link CorrelationIdFilter}, {@link MetricsConfig},
 *       {@link HealthIndicators} and this one - and no README: Clause E is discharged here through the
 *       docstring option deliberately, and every package in the tree carries exactly one
 *       {@code package-info.java}.</li>
 *   <li>Because this layer has no COBOL antecedent, every claim it makes about a legacy facility must either
 *       cite a real artefact under {@code app/**} or state plainly that none exists.</li>
 * </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */

package com.cardemo.observability;
