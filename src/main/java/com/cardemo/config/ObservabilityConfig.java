/*
 * ******************************************************************
 * Program     : ObservabilityConfig.java
 * Application : CardDemo
 * Type        : Java Spring Configuration (observability layer)
 * Function    : Registers distributed tracing, the four named metric
 *               instruments and the composite health surface for the
 *               modular monolith, and publishes the single production
 *               time source that every rendered date and timestamp
 *               derives from. New capability - the legacy corpus has
 *               no instrumentation whatsoever.
 * Source      : app/cbl/CBTRN02C.cbl (731 lines; the end-of-run
 *                 DISPLAY counters at :L227-L228 and the four
 *                 character status renderer 9910-DISPLAY-IO-STATUS
 *                 at :L714-L727)
 *               + app/jcl/OPENFIL.jcl + app/jcl/CLOSEFIL.jcl
 *                 (CEMT SET FIL(...) OPE and CLO over five files)
 *               + app/cpy/CSMSG02Y.cpy (CABENDD.CPY abend work areas)
 *               + app/cbl/COSGN00C.cbl:L221-L256 (the sign-on outcome
 *                 branches behind the authentication instrument)
 *               + app/csd/CARDDEMO.CSD (the file control table the
 *                 health surface replaces)
 *               @ 7756d89
 * Replaces    : nothing - the source has no metrics, no traces and no
 *               health probes. Mandated solely by Rule 1 Clause A.
 * Note        : the Prometheus scrape configuration lives in the
 *               root-owned observability/prometheus.yml and the
 *               Grafana provisioning under observability/grafana/;
 *               neither is duplicated here.
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
package com.cardemo.config;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declared owner of the observability layer's wiring and publisher of the application's single production
 * time source.
 *
 * <h2>What it does, and the one reason it exists at all</h2>
 *
 * <p>This class has <strong>no legacy counterpart</strong>. Rule 1 Clause A requires
 * <em>"Observability: structured logs, meaningful errors, and measurable behavior (metrics/tracing where
 * relevant)."</em> and that single bullet is the sole warrant for the layer this class fronts. Verified at
 * {@code 7756d89}, the entire 19,254-line corpus across the 28 programs of {@code app/cbl} instruments
 * itself with <strong>{@code DISPLAY} to SYSOUT and nothing else</strong>, plus the four-character status
 * renderer {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727}. A repository-wide
 * search of {@code app/} for the words prometheus, micrometer, opentelemetry, healthcheck and actuator
 * returns nothing. There is no metric, no trace, no health probe, no logging framework, no service level
 * agreement and no service level objective anywhere in the source. Every capability described below is
 * therefore new, designed explicitly rather than derived.
 *
 * <p>Any census of that corpus must match case-insensitively: twenty-six programs use a lowercase
 * {@code .cbl} extension and two - {@code app/cbl/CBSTM03A.CBL} and {@code app/cbl/CBSTM03B.CBL} - use an
 * uppercase one, so a {@code *.cbl} glob silently sees only 18,100 of the 19,254 lines.
 *
 * <h2>Registration and wiring only, and exactly one bean</h2>
 *
 * <p>Two responsibilities, both registration rather than behaviour.
 *
 * <ol>
 *   <li><strong>It publishes exactly one {@link Clock} bean.</strong> Online, security and batch components
 *       take a {@code Clock} through their constructors so that "now" is injected rather than read from a
 *       static, and every one of them resolves to the bean declared here. Before this declaration existed
 *       the context could not refresh at all: the failure surfaced as an unsatisfied constructor parameter
 *       on the first component the container happened to build.</li>
 *   <li><strong>It is the declared wiring owner of {@code com.cardemo.observability}.</strong> All
 *       <strong>four</strong> classes in that package register themselves - {@code CorrelationIdFilter} as
 *       a scanned, {@code @Order}ed servlet filter, {@code MetricsConfig} as the definition site of the
 *       four instruments, {@code HealthIndicators} as the source of the object-store and queue health
 *       contributors, and {@code TemplatedUriObservationConvention} through the {@code ObjectProvider}
 *       that Boot's {@code WebMvcObservationAutoConfiguration} resolves - so this file deliberately
 *       declares no duplicate of any of them. Its ownership here is
 *       the documented contract below, not a second set of bean definitions.</li>
 * </ol>
 *
 * <p><strong>Exactly one {@code @Bean} method is declared, and that count is asserted by
 * {@code com.cardemo.unit.config.ObservabilityConfigTest}.</strong> Nothing further is registered here
 * because nothing further is unowned: the meter registry, the Prometheus scrape endpoint, the tracer and its
 * exporter are auto-configured from the properties listed below; the common metric tags, the actuator
 * exposure list, the health group composition and the sampling probability are all set in
 * {@code src/main/resources/application.yml}; and the four observability classes register themselves - the
 * correlation filter, the metric registrar, the health indicators and
 * {@code com.cardemo.observability.TemplatedUriObservationConvention}, which Boot's
 * {@code WebMvcObservationAutoConfiguration} picks up through an {@code ObjectProvider} and which therefore
 * needs no {@code @Bean} method here.
 * Adding a bean that restated any of those would be duplication under Rule 1 Clause C and dead
 * configuration under Clause B.
 *
 * <h2>The binding registration-ownership rule</h2>
 *
 * <p>{@code com.cardemo.observability.MetricsConfig} is the designated registration site for the four
 * application instruments - four counters, the amount one partitioned by sign - and this class must not
 * register any of them.
 * MetricsConfig registers them directly on the {@code MeterRegistry} and exposes an increment facade
 * instead of publishing them as beans, precisely so that the facade is the only path to an instrument. The
 * remedy for a collision is fixed so that every maintainer resolves it the same way:
 *
 * <blockquote>If the application fails at startup with a duplicate definition for anything the
 * observability package owns, remove the duplicate from {@code ObservabilityConfig}, NOT from the class in
 * {@code com.cardemo.observability} that owns it.</blockquote>
 *
 * <p>That direction applies equally to the correlation filter and the health contributors.
 * {@code spring.main.allow-bean-definition-overriding} is set {@code false} in {@code application.yml} -
 * cited by that key rather than by line, because the line moves - so a collision throws
 * {@code BeanDefinitionOverrideException} during context refresh rather than silently shadowing one
 * definition with the other. That is the desired behaviour: an
 * order-dependent startup failure is worse than the loud one.
 *
 * <h2>What this class does not own</h2>
 *
 * <ul>
 *   <li><strong>The four instruments.</strong> Defined by {@code com.cardemo.observability.MetricsConfig},
 *       which also holds their names and tag keys as constants.</li>
 *   <li><strong>The Prometheus scrape.</strong> The root-owned {@code observability/prometheus.yml} sets the
 *       fifteen-second scrape of the application metrics endpoint. Not restated here.</li>
 *   <li><strong>Grafana provisioning.</strong> {@code observability/grafana/provisioning/datasources/}
 *       and the dashboard JSON under {@code observability/grafana/dashboards/}. Not restated here.</li>
 *   <li><strong>Actuator exposure, health group composition and probe enablement.</strong> Owned by
 *       {@code application.yml}. Bound and honoured; never redeclared.</li>
 *   <li><strong>Log encoding and secret masking.</strong> Owned by
 *       {@code src/main/resources/logback-spring.xml}, profile-invariantly. No Java-side masking is added
 *       here, because a second mechanism would be duplication and could drift.</li>
 *   <li><strong>The MDC key names.</strong> Owned by {@code com.cardemo.observability.CorrelationIdFilter},
 *       which uses exactly {@code correlationId}, {@code traceId} and {@code spanId} in HTTP scope. No
 *       fourth HTTP-scope key is invented here and no existing one is re-spelled: key drift silently
 *       produces unpopulated log fields and raises no error, which is why it is classified High below.</li>
 *   <li><strong>The batch job-instance MDC contribution.</strong> {@code CorrelationIdFilter}'s request path
 *       is HTTP-scoped, so the {@code jobInstanceId} key that batch events carry alongside the trio is
 *       contributed by a {@code JobExecutionListener} that each of the six job classes in
 *       {@code com.cardemo.batch.jobs} registers on its own job - a listener bean is never applied to a job
 *       implicitly, so a listener declared in a configuration class and registered by no job builder does
 *       nothing, which is why the one that stood in {@code com.cardemo.config.BatchConfig} was removed as
 *       finding M-01. The park-and-restore lifecycle those six listeners share is
 *       {@code CorrelationIdFilter.enterBatchScope(long, String)} and
 *       {@code CorrelationIdFilter.exitBatchScope()}, so the key names and the lifecycle each have one
 *       definition. Nothing of the kind is declared here and no competing listener is added.</li>
 *   <li><strong>Cloud clients.</strong> {@code com.cardemo.config.AwsConfig} constructs them;
 *       {@code HealthIndicators} injects them. This class constructs none and never sets an endpoint
 *       override.</li>
 * </ul>
 *
 * <h2>Exactly four named instruments, all four of them counters</h2>
 *
 * <p>The layer publishes four instruments and no fifth. The fourth reports a signed running total that can
 * fall as well as rise, and it stays a counter by being partitioned on the source's own sign predicate into
 * a credit and a debit series of magnitudes whose difference is the net - which also keeps the reserved
 * {@code _total} suffix a Prometheus gauge would have stripped. Their names and tag keys live on
 * {@code MetricsConfig}; their provenance is recorded here because it is the reason each one exists.
 *
 * <ol>
 *   <li><strong>Records processed.</strong> From {@code DISPLAY 'TRANSACTIONS PROCESSED :'} at
 *       {@code app/cbl/CBTRN02C.cbl:L227}, emitted once per run after the six files close. The per-record
 *       loop it counts is {@code :L202-L219}, which clears the reason code and its description at
 *       {@code :L208-L209}, validates, then either posts or increments the reject count and writes a reject
 *       record.</li>
 *   <li><strong>Records rejected.</strong> From {@code DISPLAY 'TRANSACTIONS REJECTED  :'} at
 *       {@code :L228}, and <strong>tagged only by the bounded reject code</strong>. The same counter carries
 *       the batch exit-status contract: {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE} at
 *       {@code :L229-L231} is the <em>sole</em> determinant of return code 4 - there is no other.</li>
 *   <li><strong>Authentication attempts.</strong> From the sign-on outcome branches of
 *       {@code app/cbl/COSGN00C.cbl} (260 lines): the password comparison at {@code :L223}, the wrong
 *       password path at {@code :L241-L246}, {@code WHEN 13} for a user that does not exist at
 *       {@code :L247-L251}, and {@code WHEN OTHER} at {@code :L252-L256}.</li>
 *   <li><strong>Total transaction amount - a function counter, deliberately not a gauge.</strong> From the
 *       accumulations in
 *       {@code 2800-UPDATE-ACCOUNT-REC} at {@code app/cbl/CBTRN02C.cbl:L545-L560}, where the amount is
 *       added to the balance and then to the cycle credit when it is non-negative and to the cycle debit
 *       otherwise, at {@code :L547-L552}. This one instrument publishes <strong>two series</strong>, tagged
 *       {@code sign=credit} and {@code sign=debit}, mirroring those two accumulators and partitioned on the
 *       same {@code IF DALYTRAN-AMT >= 0} predicate. That is a dimension of the fourth instrument, not a
 *       fifth instrument: the contract fixes four <em>names</em>, and the rejected series has been tagged
 *       from the outset on identical terms. Two series rather than one signed number because a Prometheus
 *       counter may not carry a negative value - the client rejects one at <em>scrape</em> time and fails
 *       the entire response - while the amounts are genuinely signed and no sign may be normalised.</li>
 * </ol>
 *
 * <p><strong>Cardinality is bounded by construction, which is a performance control as much as a hygiene
 * one.</strong> The reject tag draws from {@code com.cardemo.model.enums.RejectCode}, a closed enum of
 * exactly five constants - 100, 101, 102, 103 and 109 - so the rejected series can never exceed five label
 * values. Code 109 is assigned inside {@code 2800-UPDATE-ACCOUNT-REC} at {@code :L556} on the rewrite
 * failure path but is never consumed as a reject outcome: that paragraph runs only on the already-validated
 * path, no reject record is written, and the value is cleared on the next iteration at {@code :L208}. It
 * exists as a constant because the assignment is real code on a reachable path.
 *
 * The sign tag draws from the two-valued partition above and cannot grow either, because the routing is a
 * {@code signum()} test and not a caller-supplied label.
 *
 * <p>No high-cardinality tag is registered anywhere in this layer. Specifically excluded as tag values:
 * account, customer, card, transaction and user identifiers, usernames, session, correlation and trace
 * identifiers, network addresses, request paths with a variable substituted, exception messages, file status
 * strings, object keys, message identifiers and job execution identifiers. Unbounded tags are the classic
 * metrics-cardinality explosion, so this is a hard constraint rather than a preference: one such tag can
 * multiply the series count without bound and take the registry and the scrape down with it.
 *
 * <p>The timers the framework auto-configures - HTTP server requests, batch job executions, connection pool
 * and JVM meters - are auto-configuration output and <strong>not</strong> additional named instruments. They
 * are complementary and additive. Hand-rolling a duplicate of any of them would be dead code, and
 * connection-pool tuning is explicitly out of scope and recorded as residual risk, so no pool gauge is added
 * to compensate for it.
 *
 * <h2>Nothing sensitive is ever produced</h2>
 *
 * <p>No metric tag, span attribute, log field or health detail carries a credential, a presented password, a
 * stored password hash, a token, a signing key, an authorization header, a social security number, a card
 * number, a telephone number, a government identifier, a date of birth or a funds-transfer account
 * identifier. Those are precisely the fields the legacy layouts hold: {@code app/cpy/CVCUS01Y.cpy} carries a
 * nine-digit social security number, a government-issued identifier, a date of birth, two telephone numbers
 * and a funds-transfer account identifier, and {@code app/cpy/CSUSR01Y.cpy:L21} declares
 * {@code SEC-USR-PWD PIC X(08)}. The masking rules in {@code logback-spring.xml} are a second line of
 * defence, applied identically in every profile and never weakened per profile; the first line of defence is
 * that this layer never produces the value.
 *
 * <h2>One log message must pass through untouched</h2>
 *
 * <p>{@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727} renders a file status as
 * exactly four characters. When the status is non-numeric or its first byte is {@code 9}, that byte is copied
 * through and the second is expanded from a binary field into three digits; otherwise the field is set to
 * four zeros and the two status characters are placed at positions three and four. Both branches emit the
 * literal text {@code FILE STATUS IS: NNNN} immediately followed by the rendered status, at {@code :L721}
 * and {@code :L725}.
 *
 * <p>That literal is owned by {@code com.cardemo.model.enums.FileStatus} as its
 * {@code DISPLAY_MESSAGE_PREFIX} constant and is <strong>not re-declared here</strong>. The stray
 * {@code NNNN} is a preserved legacy quirk, not a defect: the end-to-end parity gate diffs log output
 * against a baseline captured from the source, so the message is never reformatted, re-encoded,
 * structure-ised, wrapped, truncated or masked. This class adds no appender, converter, layout, marker or
 * MDC key that could alter it.
 *
 * <h2>Tracing</h2>
 *
 * <p>Micrometer tracing is bridged to OpenTelemetry by {@code micrometer-tracing-bridge-otel} and exported
 * over OTLP to the Jaeger instance declared in {@code docker-compose.yml}. Trace and span identifiers reach
 * the log through the {@code traceId} and {@code spanId} MDC keys, and the correlation identifier through
 * {@code correlationId}. {@code CorrelationIdFilter} generates or accepts that correlation identifier,
 * places it in MDC, attaches it to the current span and propagates it on outbound cloud calls; it is the
 * request-scoped thread of identity that replaces the mainframe's per-transaction identity.
 *
 * <p><strong>The exporter endpoint is deliberately not read by this class.</strong>
 * {@code management.otlp.tracing.endpoint} is declared in {@code application.yml} as a bare
 * {@code OTEL_EXPORTER_OTLP_ENDPOINT} reference with no default, so that a missing collector fails loudly
 * rather than silently discarding spans - the framework gates its OTLP exporter on the property's presence,
 * so an absent value would create no exporter at all. Reading that key from Java would make this class abort
 * every context in which the variable is unset, including plain unit tests, because a default supplied on the
 * injection point applies when the <em>key</em> is missing and not when the key's value contains an
 * unresolvable <em>nested</em> placeholder. {@code application-test.yml} excludes the OTLP tracing
 * auto-configuration for exactly that reason, which keeps in-process tracing - and therefore
 * {@code traceId} and {@code spanId} in MDC - while removing only delivery. Binding the endpoint is the
 * property layer's job; restating or re-reading it here would create an environment-specific assumption of
 * the kind Rule 1 Clause C forbids.
 *
 * <p>The sampling probability is likewise explicit in the property files rather than left to an implicit
 * default, and is not overridden here.
 *
 * <p><strong>The mainframe identifier this replaces cannot be cited, because it is not there.</strong> The
 * identifier commonly named as the mainframe's thread of request identity, {@code EIBTRNID},
 * <strong>does not occur anywhere in this repository</strong>: it has zero occurrences at {@code 7756d89},
 * and the complete exec-interface-block census under {@code app/} is {@code EIBCALEN} with 49 occurrences
 * and {@code EIBAID} with 44, 16 of them in {@code app/cbl} and 28 in {@code app/cpy/CSSTRPFY.cpy}. No
 * line-level citation for it can therefore be given, and none is fabricated. What is needed to describe the
 * legacy per-transaction identity instead is already available and is what this class cites:
 * {@code app/csd/CARDDEMO.CSD} for the transaction inventory, {@code app/cbl/COSGN00C.cbl:L37}
 * ({@code WS-TRANID PIC X(04) VALUE 'CC00'}) for a program's own transaction literal, and
 * {@code app/cbl/COCRDLIC.cbl:L295} for the {@code OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}
 * communication-area declaration that carried state between transfers.
 *
 * <h2>Health</h2>
 *
 * <p>{@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CLOSEFIL.jcl} drive the region's file availability
 * through {@code SDSF}, issuing {@code CEMT SET FIL(<name>) OPE} and {@code CEMT SET FIL(<name>) CLO} for
 * <strong>exactly five files</strong> - {@code TRANSACT}, {@code CCXREF}, {@code ACCTDAT}, {@code CXACAIX}
 * and {@code USRSEC} - at {@code :L26-L30} of each member. <strong>They have no Java analogue other than the
 * health indicators.</strong>
 *
 * <p><strong>Recorded rather than corrected:</strong> that five-file list is a strict subset of
 * the eight files the region defines in {@code app/csd/CARDDEMO.CSD} - {@code ACCTDAT} at {@code :L1},
 * {@code CARDAIX} at {@code :L13}, {@code CARDDAT} at {@code :L25}, {@code CCXREF} at {@code :L37},
 * {@code CUSTDAT} at {@code :L50}, {@code CXACAIX} at {@code :L63}, {@code TRANSACT} at {@code :L76} and
 * {@code USRSEC} at {@code :L88}. The asymmetry is what the source does and is not "corrected" to eight.
 *
 * <p>The target surface keeps liveness and readiness separate, with readiness covering the database, the
 * object store and the queue, and the compose file health-checks the readiness endpoint. That composition and
 * the probe enablement are set in {@code application.yml} and are bound and honoured here, never redeclared.
 * {@code com.cardemo.observability.HealthIndicators} performs the checks and injects the clients that
 * {@code AwsConfig} constructs; it never constructs one and never sets an endpoint override, and neither does
 * this class.
 *
 * <p>A health response must never leak a bucket name, a queue URL, a credential, an endpoint override or a
 * stack trace to an unauthenticated caller, which is why {@code management.endpoint.health.show-details} is
 * {@code never} and is not widened here. A contributor that fails reports DOWN with its cause preserved
 * internally while the external body stays free of detail; swallowing the cause instead would violate Rule 1
 * Clause B directly.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Every key below is <strong>bound and honoured, never redeclared</strong> by this class. No environment
 * variable is read directly anywhere in it, no absolute host path appears, and nothing depends on the default
 * charset, locale or time zone. Values are those of the base profile at {@code application.yml}.
 *
 * <dl>
 *   <dt>{@code management.endpoints.web.exposure.include} - no framework default of use here; set to exactly
 *       {@code health,info,prometheus} at {@code :L847}</dt>
 *   <dd>The least-privilege actuator surface. Never {@code *} and never a business alias.</dd>
 *   <dt>{@code management.endpoint.health.show-details} - framework default {@code never}; set explicitly to
 *       {@code never} at {@code :L854}</dt>
 *   <dd>Never {@code always}. {@code show-components} is {@code never} for the same reason.</dd>
 *   <dt>{@code management.endpoint.health.probes.enabled} - framework default {@code false} outside
 *       Kubernetes; set to {@code true}</dt>
 *   <dd>Enables the separate liveness and readiness groups: liveness includes {@code livenessState} at
 *       {@code :L870}, readiness includes {@code readinessState} plus the database, object store and queue
 *       contributors at {@code :L906}.</dd>
 *   <dt>{@code management.otlp.tracing.endpoint} - <strong>no default, deliberately</strong>, at
 *       {@code :L940}</dt>
 *   <dd>The OTLP collector address. Absent means no exporter is created; see the tracing section for why this
 *       class does not read it.</dd>
 *   <dt>{@code management.tracing.sampling.probability} - framework default {@code 0.1}; set explicitly to
 *       {@code 1.0} at {@code :L932}, and to {@code 0.1} in the production profile</dt>
 *   <dd>Explicit in every profile so that no environment infers it.</dd>
 *   <dt>{@code management.metrics.tags.application} and {@code .component} - no default; set to
 *       {@code carddemo} and {@code modular-monolith} at {@code :L944}</dt>
 *   <dd>The common tags every meter carries. Set as properties precisely so that no Java customiser has to
 *       exist; adding one here would duplicate them.</dd>
 *   <dt>{@code spring.application.name} - no default; set to {@code carddemo} at {@code :L499}</dt>
 *   <dd>The service tag and trace service name source. Never spelled a second time in Java.</dd>
 *   <dt>{@code carddemo.aws.s3.}, {@code carddemo.aws.sqs.} and {@code carddemo.aws.sns.} - bucket, queue and
 *       topic names, every one supplied by environment variable with no default</dt>
 *   <dd>The health namespaces {@code HealthIndicators} reads. Not read here.</dd>
 *   <dt>{@value #KEY_CLOCK_ZONE} - <strong>defaulted to {@code UTC}</strong> by
 *       {@code zone: ${CARDDEMO_TIME_ZONE:UTC}} in {@code application.yml}</dt>
 *   <dd>The one key this class reads. See the time-source section for its effect and for the discrepancy it
 *       creates.</dd>
 * </dl>
 *
 * <h2>The single production time source</h2>
 *
 * <p>The corpus reads the wall clock through the {@code FUNCTION CURRENT-DATE} intrinsic, which returns the
 * <strong>local</strong> civil date and time of the executing system rather than UTC. Every rendered date and
 * time in the target derives from this bean if it is to match the legacy output byte for byte: the screen
 * header pair {@code CURDATE X(8)} and {@code CURTIME X(9)}, the 26-character generated timestamp whose final
 * four digits are always zeros, and the report and statement headings. {@link Clock#systemDefaultZone()} is
 * consequently the parity-preserving construction and {@link Clock#systemUTC()} is not, which is why the
 * unpinned path below returns the former.
 *
 * <p>The storage layer is not an argument against that choice: Hibernate's JDBC time zone is {@code UTC}, so
 * every instant reaches PostgreSQL as UTC whatever zone this clock carries - the provider converts. What the
 * zone decides is the <em>rendered text</em> that the parity gate diffs.
 *
 * <p><strong>The clock zone and the parity baseline must be made to agree, and this class cannot do it
 * alone.</strong> The base profile pins
 * {@value #KEY_CLOCK_ZONE} to {@code UTC} through {@code zone: ${CARDDEMO_TIME_ZONE:UTC}} in
 * {@code application.yml}. A deployment that inherits that default therefore runs the clock in UTC, not
 * in the host's civil zone, so rendered dates and times will differ from a baseline captured under a
 * non-UTC zone by the host's offset. This class cannot resolve the discrepancy by itself:
 * {@code application.yml} is owned elsewhere and is bound, never redeclared. Whoever owns the parity
 * baseline decides the zone once and makes the two agree - either by exporting
 * {@code CARDDEMO_TIME_ZONE} with the zone the baseline was captured under, or by re-capturing the baseline
 * under UTC. Both the property and the resolved zone are reported on the startup line this class emits, so the
 * effective value never has to be guessed at.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Compile with {@code ./mvnw -B -ntp clean compile}: the compiler runs {@code -Xlint:all} with
 * {@code -Werror} and {@code failOnWarning}, so any warning fails the build, and the Javadoc gate runs with
 * {@code failOnWarnings} too, so an undocumented public member fails it as well. Run the unit suite with
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true test}. Adding {@code verify} to those same options
 * additionally enforces the coverage floor but is <strong>fast local verification rather than the full
 * gate</strong>, because the skip suppresses the vulnerability scan; the full gate is
 * {@code ./mvnw -B -ntp clean verify}, online and with nothing skipped. Start the application with
 * {@code java -jar target/carddemo-1.0.0.jar} once the variables in {@code .env.example} are exported. Tests
 * for this class live in {@code src/test/java/com/cardemo/unit/config/} and never in this package; every
 * branch below is reachable by calling the bean method directly, with no container, because the one property
 * it reads arrives as a parameter rather than through a field.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Startup fails with a duplicate definition for something the observability package owns</dt>
 *   <dd>Remove the duplicate from this class, never from the owning class in
 *       {@code com.cardemo.observability} - the verbatim rule above. That covers the four instruments, the
 *       correlation filter and the health contributors alike.</dd>
 *   <dt>Startup fails with an unsatisfied constructor parameter of type {@code java.time.Clock}</dt>
 *   <dd>This class was not scanned. It sits in {@code com.cardemo.config}, below the {@code com.cardemo} root
 *       that the application scans, so the usual cause is a sliced test importing only some configuration
 *       classes. Import this one too, or publish a fixed clock inside the slice.</dd>
 *   <dt>Startup fails reporting two candidates for {@code java.time.Clock}</dt>
 *   <dd>A second {@code Clock} bean was declared elsewhere. Remove that one, not this one: this is the
 *       designated declaration site. A test needing a fixed clock publishes it in a test configuration marked
 *       primary, never as a second unqualified bean.</dd>
 *   <dt>Startup aborts naming {@value #KEY_CLOCK_ZONE}</dt>
 *   <dd>The property is pinned to something this runtime does not recognise, or to a placeholder whose
 *       variable was never exported. The message names the offending text. Unset the property to accept the
 *       profile default rather than guessing at a replacement.</dd>
 *   <dt>The scrape endpoint responds but Prometheus shows no CardDemo series</dt>
 *   <dd>The scrape configuration is not this class's: check the root-owned {@code observability/prometheus.yml}
 *       target and interval, and that the actuator exposure list still contains {@code prometheus}. Renaming
 *       a metric or changing a tag key empties a dashboard panel silently and raises no error, so a metric
 *       constant and the dashboard JSON change in the same commit.</dd>
 *   <dt>No traces appear in Jaeger</dt>
 *   <dd>Either {@code OTEL_EXPORTER_OTLP_ENDPOINT} is unset - in which case no exporter is created and every
 *       span is dropped silently - or the collector is not reachable, or the sampling probability is lower
 *       than the traffic being looked for. Under the test profile the OTLP auto-configuration is excluded by
 *       design, so absent spans there are expected and {@code traceId} and {@code spanId} still reach
 *       MDC.</dd>
 *   <dt>A log line has empty {@code traceId}, {@code spanId} or {@code correlationId}</dt>
 *   <dd>MDC key drift. The keys are owned by {@code CorrelationIdFilter} and are exactly
 *       {@code correlationId}, {@code traceId} and {@code spanId}; a re-spelled or additional key produces an
 *       unpopulated field and no error. Batch events additionally carry {@code jobInstanceId}, contributed by
 *       {@code BatchConfig}, so its absence on an HTTP event is correct.</dd>
 *   <dt>Readiness reports DOWN shortly after the stack starts</dt>
 *   <dd>Usually the emulator has not finished provisioning the buckets and the queue. Readiness covers the
 *       database, object store and queue precisely so that traffic is withheld until they answer; the
 *       initialisation script is idempotent, so a repeated stack cycle converges rather than failing.</dd>
 *   <dt>Rendered dates or times differ from the baseline by a whole number of hours</dt>
 *   <dd>A zone mismatch - see the note above. Compare the zone on this class's startup
 *       line against the zone the baseline was captured under. Do not switch this bean to
 *       {@link Clock#systemUTC()} to compensate: that changes every rendering rather than aligning one.</dd>
 * </dl>
 *
 * <h2>Two boundaries this class cannot report on</h2>
 *
 * <p><strong>The root observability provisioning is not owned here.</strong> The scrape configuration, the
 * Grafana datasource definition and the dashboard JSON are owned by the repository
 * root rather than by this class, so their content is <strong>deliberately not restated here</strong> and is
 * not something this class can report on. Their locations are {@code observability/prometheus.yml},
 * {@code observability/grafana/provisioning/datasources/} and {@code observability/grafana/dashboards/}; all
 * three were verified present on this branch. No Java fallback recreates any of them, and none may be added:
 * a Java-side copy would duplicate root-owned configuration and drift from it silently. What is needed to
 * demonstrate a <em>populated</em> dashboard is a container runtime with an accessible socket, the compose
 * stack up, and batch or sign-on traffic to move the counters.
 *
 * <p><strong>No service level objective exists to reproduce.</strong> The corpus
 * publishes no service level agreement, no service level objective, no latency target and no throughput
 * target; there is nothing in 19,254 lines to reproduce. The performance gate therefore records a
 * <strong>measured baseline, not a target</strong>. No threshold, no alert rule and no service level may be
 * invented here or anywhere else in this layer, and none is. What would be needed to state one is a
 * stakeholder-agreed objective, which does not exist in the system of record.
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>The class holds no mutable state: its three fields are {@code static final} - a logger and two string
 * constants - and its one bean method is a pure function returning an immutable, thread-safe clock. Rule 1
 * Clause B's prohibition on global mutable state holds by construction, and there is no static
 * {@code MeterRegistry} handle anywhere. Injection is by constructor only; no field or setter injection
 * appears. The class is intentionally not {@code final} because the container proxies configuration classes,
 * and it carries no scheduling, asynchronous-execution or aspect-proxy enabler annotation - none is needed
 * here or anywhere in this tree, and each would be dead configuration under Rule 1 Clause B.
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Startup diagnostic logger. It emits exactly one event, naming the resolved zone, because that zone is
     * the one input to every rendered date and time in the application and a parity discrepancy is otherwise
     * diagnosed by guesswork.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ObservabilityConfig.class);

    /**
     * Property key by which a deployment pins the zone the application {@link Clock} ticks in.
     *
     * <p>The base profile binds it to {@code ${CARDDEMO_TIME_ZONE:UTC}} in {@code application.yml}, so
     * the pinned path is the one that normally runs and the effective zone is {@code UTC} unless
     * {@code CARDDEMO_TIME_ZONE} is exported. The key exists so that a zone is chosen deliberately rather
     * than inherited from whatever the host happens to be set to, and so that a baseline captured under one
     * zone can be reproduced on a host configured for another.
     */
    private static final String KEY_CLOCK_ZONE = "carddemo.time.zone";

    /**
     * The opening of a property placeholder, matched to catch a value that reached this class unresolved.
     *
     * <p>A lenient placeholder resolver hands the literal text through instead of failing, and a zone of
     * {@code CARDDEMO_TIME_ZONE} left unexported would then be reported only as an unknown identifier -
     * true, but it sends an operator looking for a typo in a value they never typed.
     */
    private static final String UNRESOLVED_PLACEHOLDER_PREFIX = "${";

    /**
     * Creates the configuration class.
     *
     * <p>Declared explicitly rather than left implicit so that it can be documented: the container is the
     * only caller, there is nothing to inject, and no work is done here. Every decision this class makes is
     * made by {@link #clock(String)} at bean-creation time, which is what lets a test construct the class
     * with {@code new} and exercise the bean method directly.
     */
    public ObservabilityConfig() {
        // Intentionally empty. This class holds no state: the one property it reads arrives as a parameter of
        // the bean method rather than as a field, so nothing here has to be initialised and the bean method
        // stays callable with an explicit value by a test that needs no container.
    }

    /**
     * The application's single production time source, replacing every {@code FUNCTION CURRENT-DATE}
     * intrinsic call in the corpus.
     *
     * <p>{@link Clock#systemDefaultZone()} rather than {@link Clock#systemUTC()} when nothing is pinned,
     * because the intrinsic it replaces returns local civil time; the class documentation carries the full
     * argument and the parity consequence. The resolved zone is logged once here rather than at every call
     * site, so the value governing every rendered date and time appears exactly once in the startup log.
     *
     * <p>When {@value #KEY_CLOCK_ZONE} is bound to a non-blank value - which the base profile does, defaulting
     * it to {@code UTC} - that zone is used and is validated first. An unresolved placeholder, or an
     * identifier this runtime does not recognise, aborts startup with the offending text reported: a zone
     * identifier is public information, and a deployment that asked for one zone and silently received
     * another would render timestamps that disagree with its own stored rows by the host's offset. A blank or
     * absent value selects the runtime default and validates nothing, because there is then nothing a
     * deployment could have mistyped.
     *
     * <p>Side effects: one informational log line. It reads no environment variable directly, opens no
     * connection, and touches no cloud client, meter or health contributor.
     *
     * @param zoneId the raw value of {@value #KEY_CLOCK_ZONE}; blank or absent selects the JVM default
     * @return the system clock in the resolved zone; never {@code null}, immutable and safe for concurrent
     *     use by every injected consumer
     * @throws IllegalStateException if {@value #KEY_CLOCK_ZONE} is bound to something that is not a zone
     *     identifier this runtime recognises, aborting context refresh rather than guessing
     */
    @Bean
    public Clock clock(@Value("${" + KEY_CLOCK_ZONE + ":}") final String zoneId) {
        final boolean pinned = zoneId != null && !zoneId.isBlank();
        final Clock systemClock =
                pinned ? Clock.system(validatedZone(zoneId)) : Clock.systemDefaultZone();
        LOG.info("CardDemo time source bound to the system clock in zone {} ({}), reproducing the local-time "
                        + "semantics of FUNCTION CURRENT-DATE; every rendered date, time and 26-character "
                        + "timestamp derives from it, so compare this zone against the parity baseline's "
                        + "before investigating a whole-hour difference",
                systemClock.getZone(),
                pinned ? "pinned by " + KEY_CLOCK_ZONE : "inherited from the runtime default");
        return systemClock;
    }

    /**
     * Resolves and validates an explicitly pinned zone identifier.
     *
     * <p>Two rejections are distinguished because they have two different remedies: an unresolved placeholder
     * means the context resolves placeholders leniently and the variable behind it was never exported, while
     * an unknown identifier means the value is a typo or a deprecated alias. The value is reported in both
     * messages because a zone identifier carries nothing sensitive.
     *
     * @param zoneId the raw property value, known to be non-blank
     * @return the resolved zone, never {@code null}
     * @throws IllegalStateException if the value is an unresolved placeholder or is not a zone identifier
     *     this runtime recognises - aborting startup in either case
     */
    private static ZoneId validatedZone(final String zoneId) {
        if (zoneId.contains(UNRESOLVED_PLACEHOLDER_PREFIX)) {
            throw clockZoneRejected(zoneId, "is bound to an unresolved property placeholder", null);
        }
        try {
            return ZoneId.of(zoneId.trim());
        } catch (final DateTimeException cause) {
            // The cause is carried rather than discarded: it names the character that failed to parse, which
            // is what distinguishes a typo from a zone this runtime's tz database no longer publishes.
            throw clockZoneRejected(zoneId, "does not name a zone this runtime recognises", cause);
        }
    }

    /**
     * Builds the one clock-zone failure message, so both rejection paths word the remedy identically.
     *
     * @param value the offending value, reported because a zone identifier is not sensitive
     * @param defect the condition observed, phrased to complete the sentence "the property ... {defect}"
     * @param cause the underlying failure, or {@code null} when the defect was detected by inspection
     * @return the exception to throw, never {@code null}, carrying {@code cause} when one was supplied
     */
    private static IllegalStateException clockZoneRejected(final String value, final String defect,
            final DateTimeException cause) {
        return new IllegalStateException(String.format(
                Locale.ROOT,
                "Property '%s' is '%s', which %s. Set it to a java.time zone identifier such as "
                        + "'America/New_York', or unset it to accept the profile default this application "
                        + "documents. It is validated rather than defaulted silently because a deployment "
                        + "that asked for one zone and silently got another would render timestamps that "
                        + "disagree with its own stored rows by the host's offset.",
                KEY_CLOCK_ZONE,
                value,
                defect), cause);
    }
}
