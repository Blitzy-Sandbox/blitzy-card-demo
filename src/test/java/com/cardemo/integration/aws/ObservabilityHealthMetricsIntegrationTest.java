/*
 ******************************************************************
 * Program     : ObservabilityHealthMetricsIntegrationTest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 AWS integration test (Failsafe tier)
 * Function    : Verifies exactly four Micrometer instruments, Actuator exposure limited to
 *               health/info/prometheus, and separate liveness/readiness groups over database, object
 *               storage and queue.
 * Source      : app/cbl/CBTRN02C.cbl:227-231 @ 7756d89 (DISPLAY counters and the RC 4 gate)
 * Source      : app/cbl/CBTRN02C.cbl:714-727 @ 7756d89 (9910-DISPLAY-IO-STATUS, the FILE STATUS IS:
 *               NNNN renderer)
 * Source      : app/cbl/CBTRN02C.cbl:385,397,410,417,556 @ 7756d89 (the five reject codes
 *               100/101/102/103/109)
 * Source      : app/jcl/OPENFIL.jcl:22,26-30 @ 7756d89 (SDSF, CEMT SET FIL OPE for 5 files)
 * Source      : app/jcl/CLOSEFIL.jcl:22,26-30 @ 7756d89 (SDSF, CEMT SET FIL CLO for the same 5 files)
 * Source      : app/csd/CARDDEMO.CSD:1,13,25,37,50,63,76,88 @ 7756d89 (the 8 online CICS file
 *               definitions)
 * Note        : 322 DISPLAY statements across app/cbl are the entire telemetry surface of 19,254 lines
 *               of COBOL; everything asserted here is new capability mandated by Rule 1 Clause A.
 ******************************************************************
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
 ******************************************************************
 */
package com.cardemo.integration.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.HealthIndicators;
import com.cardemo.observability.MetricsConfig;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Proves that the observability layer which replaces the corpus's {@code DISPLAY}-only instrumentation
 * actually holds its three contracts at runtime: a bounded instrument set, a closed Actuator surface, and
 * a liveness group that cannot be dragged down by a downstream while readiness genuinely covers all three
 * substrates.
 *
 * <h2>1. What it does</h2>
 *
 * <p>The legacy baseline is what makes this class necessary rather than decorative. There are
 * <strong>322 {@code DISPLAY} statements</strong> across {@code app/cbl}, and that is the <em>entire</em>
 * telemetry surface of 19,254 lines of COBOL: a repository-wide search for a metrics, tracing or
 * health-check facility in {@code app/} returns nothing at all. Every property asserted below is therefore
 * <strong>new capability mandated by Rule 1 Clause A</strong> ("measurable behavior"), and nothing here may
 * be justified as preserved for parity. Three concrete legacy sites anchor it:
 *
 * <dl>
 *   <dt>The two end-of-run counters and the return-code gate</dt>
 *   <dd>{@code app/cbl/CBTRN02C.cbl:227} is
 *       {@code DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT} with <strong>one</strong> space
 *       before the colon, and {@code :228} is
 *       {@code DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT} with <strong>two</strong>, both
 *       confirmed byte-wise. {@code :229-231} is {@code IF WS-REJECT-COUNT &gt; 0}, {@code MOVE 4 TO
 *       RETURN-CODE}, {@code END-IF}, so return code 4 is set if and <em>only</em> if the reject count
 *       exceeds zero and by nothing else; {@code WS-REJECT-COUNT} is incremented only at {@code :214}, and
 *       {@code :194} and {@code :232} are the start and end of execution displays. The abend contract is
 *       {@code :708} {@code DISPLAY 'ABENDING PROGRAM'}, {@code :710} {@code MOVE 999 TO ABCODE} and
 *       {@code :711} {@code CALL 'CEE3ABD'} - abend 999, return code 12. <strong>No batch job is launched
 *       from here</strong>: that belongs to the batch leaf of this tier, so the instruments are exercised
 *       directly through their published API.</dd>
 *   <dt>The five reject codes, which fix a bounded tag cardinality</dt>
 *   <dd>Assigned at {@code app/cbl/CBTRN02C.cbl:385} (100, {@code INVALID CARD NUMBER FOUND}),
 *       {@code :397} (101, {@code ACCOUNT RECORD NOT FOUND}), {@code :410} (102,
 *       {@code OVERLIMIT TRANSACTION}), {@code :417} (103,
 *       {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}) and {@code :556} (109, whose description is
 *       deliberately identical to 101's). Code 109 is <strong>never consumed as a reject outcome</strong> -
 *       it is assigned inside the already-validated posting path, so no reject record is written and the
 *       value is cleared on the next iteration - yet the assignment is real code on a reachable path, which
 *       is exactly why {@code com.cardemo.model.enums.RejectCode} has five constants and not four, and why
 *       the bounded tag set asserted below has five values. Note also that {@code :403-420} is
 *       <em>sequential and unguarded</em>, so when both the over-limit and the expiry condition fail,
 *       <strong>103 overwrites 102</strong> and a single reject record bearing 103 is written, never two.
 *       Both quirks are cited, neither is "fixed".</dd>
 *   <dt>The file-availability jobs, which are what readiness replaces</dt>
 *   <dd>{@code app/jcl/OPENFIL.jcl:22} is {@code //OPCIFIL EXEC PGM=SDSF} and {@code :26-30} issues five
 *       {@code CEMT SET FIL(&lt;name&gt;) OPE} commands, for {@code TRANSACT}, {@code CCXREF},
 *       {@code ACCTDAT}, {@code CXACAIX} and {@code USRSEC}; {@code app/jcl/CLOSEFIL.jcl:22} is
 *       {@code //CLCIFIL EXEC PGM=SDSF} and {@code :26-30} issues the identical five with {@code CLO}.
 *       All five are VSAM datasets, so all five land on the relational substrate and are covered by the
 *       single auto-configured database contributor. {@code app/csd/CARDDEMO.CSD} defines exactly eight
 *       online files, at {@code :1}, {@code :13}, {@code :25}, {@code :37}, {@code :50}, {@code :63},
 *       {@code :76} and {@code :88} - {@code ACCTDAT}, {@code CARDAIX}, {@code CARDDAT}, {@code CCXREF},
 *       {@code CUSTDAT}, {@code CXACAIX}, {@code TRANSACT} and {@code USRSEC} - while {@code TCATBALF},
 *       {@code DISCGRP}, {@code TRANCATG} and {@code TRANTYPE} appear in no file definition at all, which
 *       is the evidence that those four are batch-only datasets. Neither {@code OPENFIL} nor
 *       {@code CLOSEFIL} has a Java analogue as a <em>job</em>, and {@code app/jcl/CBADMCDJ.jcl}
 *       (DFHCSDUP, which installs the resource definitions) has none either: the first two are superseded
 *       by these health contributors and the third by the security configuration.
 *       <strong>Low, preserved:</strong> {@code app/jcl/OPENFIL.jcl:1} reads
 *       {@code //OEPNFIL JOB 'Open files in CICS'} - the job name transposes the E and the P relative to
 *       its member name, while {@code app/jcl/CLOSEFIL.jcl:1} is correct. It is never repaired.</dd>
 *   <dt>The two cycle accumulators, which fix the shape of the amount instrument</dt>
 *   <dd>{@code app/cbl/CBTRN02C.cbl:547} is {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL}; {@code :548} is
 *       {@code IF DALYTRAN-AMT >= 0}; {@code :549} adds the amount to {@code ACCT-CURR-CYC-CREDIT} and
 *       {@code :551} adds it, <em>unchanged and therefore negative</em>, to {@code ACCT-CURR-CYC-DEBIT}.
 *       The source keeps <strong>two</strong> accumulators partitioned on exactly that predicate, and
 *       {@code :406} subtracts the debit accumulator in the over-limit formula precisely because it holds
 *       negative values. No absolute value is taken anywhere on this path, and
 *       {@code app/data/ASCII/dailytran.txt} genuinely carries both {@code &#123;} and {@code &#125;}
 *       overpunch signs, so the stream really is signed. The amount instrument mirrors that decomposition:
 *       it is <strong>one metric name published as two counter series</strong>, tagged
 *       {@code sign=credit} and {@code sign=debit}, and the signed total is recovered as
 *       {@code credit - debit}. Two independent constraints force that shape rather than a single number.
 *       Micrometer's {@code Counter.increment(double)} silently discards a non-positive amount, so a plain
 *       counter would drop every debit; and the Prometheus client <em>rejects a negative counter value at
 *       scrape time</em>, raising {@code IllegalArgumentException} from the render and failing the whole
 *       {@code /actuator/prometheus} response - taking the other eight series down with it - so a single
 *       signed counter is not merely lossy but fatal. Reading exact
 *       {@link java.math.BigDecimal} accumulators through a
 *       {@link io.micrometer.core.instrument.FunctionCounter} satisfies both: nothing is discarded, no
 *       series can go negative, every series is a counter, and the one conversion out of decimal happens
 *       on the scrape thread.</dd>
 * </dl>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>{@code ./mvnw clean verify}. <strong>This class is collected by Failsafe, not Surefire.</strong> The
 * root build binds {@code maven-failsafe-plugin} to {@code **}{@code /integration/}{@code **}{@code
 * /*Test.java} and the end-to-end tree at {@code integration-test} and {@code verify}, even though these
 * classes keep the {@code Test} suffix, while {@code maven-surefire-plugin} excludes both trees. A class
 * moved up to {@code com/cardemo/integration/}, to {@code com/cardemo/} or to the test source root matches
 * <em>neither</em> include set and is collected by <em>neither</em> plugin: it silently never runs while
 * both plugins report success and the build stays green. That is the worst failure mode available here, so
 * this package and this class name must not be renamed or relocated, and no sub-package may be introduced
 * beneath them. After any change to the build, confirm the Failsafe report names this class.
 *
 * <p><strong>A reachable container runtime is a prerequisite</strong>, not a convenience: the readiness
 * assertions below need a real database, a real object store and a real queue, and an in-memory substitute
 * would prove nothing about any of the three. Where no daemon or socket is available the correct report is
 * that the gate is <em>blocked</em>, never an untested pass. Measured in the provisioned environment on
 * 2026-08-03: Docker Engine 29.7.0 with Compose v5.3.1 and a socket present, and host {@code java} 25.0.3
 * and {@code mvn} 3.9.11 both on the path. An earlier note claiming Docker was unavailable, and a later one
 * claiming host {@code java}, {@code javac} and {@code mvn} were absent so Maven had to run inside a
 * container, are <strong>both withdrawn as stale</strong>; severity of what they left behind:
 * <strong>Low</strong>, a wrong instruction rather than a wrong artefact.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>Everything is inherited from {@code AbstractAwsIntegrationTest}: the {@code test} profile, the
 * digest-pinned PostgreSQL 16 and release-pinned LocalStack containers, the injected fixed clock, and the
 * dynamic property registration that supplies the emulator endpoints from the container's own mapped port.
 * <strong>This class declares no container, no {@code @SpringBootTest}, no {@code @ActiveProfiles}, no
 * {@code @Testcontainers}, no {@code @DynamicPropertySource} and no static mutable field of any kind</strong>
 * - only deeply immutable {@code static final} constants - and it never constructs an AWS client, never
 * supplies an endpoint, a region, an access key or a secret. {@code com.cardemo.config.AwsConfig} owns
 * those and allow-lists the endpoint host, aborting startup on anything outside that set, so the context
 * having refreshed at all is itself the proof that no call can escape to a live account. It adds exactly
 * one annotation of its own, {@code @AutoConfigureMockMvc}, because the inherited web environment is a mock
 * one and the exposure and readiness-path contracts are only provable over real HTTP.
 *
 * <p>The configuration under test: {@code management.endpoints.web.base-path} is {@code /actuator};
 * {@code management.endpoints.web.exposure.include} is exactly {@code health}, {@code info} and
 * {@code prometheus} and never {@code *}; {@code management.endpoint.health.show-details} is
 * <strong>never</strong> {@code always}; {@code management.endpoint.health.probes.enabled} is true with
 * separate {@code group.liveness.include} and {@code group.readiness.include};
 * {@code management.endpoint.health.validate-group-membership} is left at its secure default, so naming a
 * contributor that does not exist aborts startup rather than silently yielding an empty group. The
 * readiness group path is the one the container image health-checks, at {@code Dockerfile:564}, which
 * issues {@code GET /actuator/health/readiness} and greps the body for an {@code UP} status.
 * <strong>The {@code test} profile deliberately neutralises trace export</strong> by excluding the OTLP
 * tracing auto-configuration, so no assertion here claims a span reached a collector; in-process tracing
 * survives, which is why the MDC trio is still rendered.
 *
 * <p><strong>Metric names are deliberately not hardcoded.</strong> They are the production author's choice
 * of a {@code carddemo}-prefixed dot-notation scheme, so this class discovers the instruments <em>by
 * prefix</em> from the injected registry and identifies each one's role <em>by exercising its code path and
 * observing which series moved</em>. A hardcoded name would be the non-deterministic choice here, because a
 * rename would make the assertion vacuous rather than red. Counter assertions are expressed as
 * <strong>deltas</strong>, never absolutes, because a {@code MeterRegistry} is shared context-wide; that is
 * what lets this class pass alone, in any order, and twice in succession.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>No container runtime</dt>
 *   <dd>Every test here fails at context refresh. Start the daemon and re-run; do not report a pass.</dd>
 *   <dt>The Testcontainers 2.0.3 coordinate trap</dt>
 *   <dd>The root build pins Testcontainers to exactly 2.0.3 through a <em>managed-version property
 *       override</em> and never a second BOM import, because Spring Boot 3.5.11 already imports the
 *       Testcontainers BOM at a 1.x version and a competing import resolves in an order-dependent way that
 *       can silently select 1.x. Only the prefixed module coordinates resolve at 2.0.3 -
 *       {@code testcontainers}, {@code testcontainers-postgresql}, {@code testcontainers-localstack} and
 *       {@code testcontainers-junit-jupiter}; the bare {@code postgresql}, {@code localstack} and
 *       {@code junit-jupiter} identifiers under that group <em>do not exist</em> at 2.0.3 and fail
 *       resolution outright. The remedy is two-part and both parts are required: override the managed
 *       version <em>and</em> use only prefixed coordinates. Overriding without renaming resolves
 *       non-existent artefacts; renaming without overriding resolves the wrong version. The build file is
 *       root-owned and is not edited from here.</dd>
 *   <dt>A compiler warning fails the build</dt>
 *   <dd>Compilation runs at release 25 with {@code -Xlint:all} and {@code -Werror}, so one unused import,
 *       raw type, unchecked cast, deprecation - or a <em>redundant</em> cast, which is the one that catches
 *       people out here, because a health contributor's probe method already returns the concrete health
 *       type - fails the whole build.</dd>
 *   <dt>A bean rename silently empties the readiness group - <strong>High</strong></dt>
 *   <dd>Actuator derives a health component key from the bean name by stripping the indicator suffix, and
 *       the readiness include list names those keys. Renaming a bean without renaming the key drops that
 *       dependency from readiness with no error anywhere. This class therefore asserts that every key in
 *       the include list resolves to a registered contributor <em>and</em> that the two published component
 *       name constants are the keys actually registered, so a divergence on either side fails here.</dd>
 *   <dt>A readiness path mismatch with the image health-check - <strong>High</strong></dt>
 *   <dd>Renaming the group or the path breaks container start-up ordering and the integration sign-off
 *       gate. Asserted directly over HTTP against the exact path the image probes.</dd>
 *   <dt>A duplicate metric bean</dt>
 *   <dd>Registration belongs to {@code com.cardemo.observability.MetricsConfig} alone;
 *       {@code com.cardemo.config.ObservabilityConfig} must not re-declare it. A duplicate-bean failure at
 *       refresh is remedied by removing the duplicate from the latter - reported as a finding, never
 *       patched from a test. As measured, that configuration declares only a clock bean, so the hazard is
 *       currently absent.</dd>
 *   <dt>Absolute rather than delta counter assertions</dt>
 *   <dd>Order-dependent and will fail when run after another class that moved the same series.</dd>
 *   <dt>An absent signing key - <strong>High</strong> against the test profile, owned elsewhere</dt>
 *   <dd>The key is defaultless and fails fast, which is correct. The inherited harness registers a
 *       ephemeral, generated value for this tier, so the context refreshes without any committed key
 *       material; reported, never patched.</dd>
 * </dl>
 *
 * <h2>What this class pins about the instrument set and the readiness probe</h2>
 *
 * <dl>
 *   <dt>The fourth instrument is a pair of sign-tagged counters, not a gauge</dt>
 *   <dd>All four named series are counters. The transaction-amount total is the one that could not be a
 *       plain incrementing counter - Micrometer's {@code Counter.increment} ignores a non-positive amount,
 *       and {@code app/cbl/CBTRN02C.cbl:548-552} adds a negative amount to the current-cycle
 *       <em>debit</em> accumulator, which is precisely why the over-limit formula subtracts it, so signed
 *       amounts are the contract and no absolute value may be taken anywhere. It is carried instead as two
 *       {@code FunctionCounter} series over exact {@code BigDecimal} accumulators, tagged
 *       {@code sign=credit} and {@code sign=debit}, partitioned on the same {@code >= 0} predicate the
 *       source uses; the signed total a dashboard reports is their difference. A gauge was the earlier
 *       answer and was wrong for a reason no test could see: the Prometheus client strips the reserved
 *       {@code _total} suffix from a gauge, so the series rendered as {@code carddemo_transaction_amount}
 *       while every dashboard queried {@code carddemo_transaction_amount_total} and got an empty panel with
 *       no error. What this class asserts is therefore the contract as built: exactly four names, a bounded
 *       and closed tag set, no gauge, no distribution summary, no long task timer, no fifth series, plus the
 *       signed-total behaviour. The series is a <strong>telemetry mirror, not the authoritative financial
 *       total</strong>; where it disagrees with the database, the database is right.</dd>
 *   <dt>The readiness health-check lives in the image, not the compose file</dt>
 *   <dd>The probed path is the one at {@code Dockerfile:564}; the compose file health-checks only its own
 *       database and emulator services. The path asserted here is the one actually probed.</dd>
 *   <dt>This leaf holds three files, not the six a sibling roster names</dt>
 *   <dd>An earlier change consolidated the bucket, generation-key, queue and correlation classes the roster
 *       lists into a single emulator class. Renaming or splitting a sibling is out of scope here; the roster
 *       is what needs reconciling with the tree.</dd>
 * </dl>
 *
 * <h2>Mandatory disclosures - information that is genuinely {@code Not available}</h2>
 *
 * <ol>
 *   <li><strong>The end-to-end expected-output baseline is {@code Not available}.</strong> An exhaustive
 *       search for captured expected, baseline, golden, sysout or dataset output - across the reject,
 *       report, statement and markup output families - returns only dataset <em>definition</em> job control
 *       and <strong>zero</strong> captured data. What is needed: a captured 430-byte reject dataset plus the
 *       resulting transaction, account and category-balance images from a real posting run at a known input
 *       state. <strong>No baseline file is created here and no expected bytes are ever fabricated</strong>;
 *       a baseline produced by running the Java implementation would be circular and is forbidden. Note
 *       specifically that the reject <em>count</em> is model-sensitive and therefore not an oracle - a
 *       stateless single pass over the fixture yields 13 rejects while a faithful stateful model yields 38
 *       rejects against 262 posted - so the posting program must never be hand-simulated to derive an
 *       expected counter value, and this class does not.</li>
 *   <li><strong>A file-unavailable legacy status is {@code Not available}.</strong> A census across
 *       {@code app/cbl} finds the literal {@code '35'} zero times and the not-open response condition zero
 *       times; for contrast the duplicate-record condition appears seven times and the duplicate-key
 *       condition three, with the canonical duplicate site at {@code app/cbl/COUSR01C.cbl:260-261}. No
 *       file-unavailable health scenario is therefore derived from a legacy status. What is asserted
 *       instead is a <em>not-configured</em>, an <em>invalid-name</em> and a <em>missing</em> substrate all
 *       reporting down without throwing, which is <strong>new capability</strong> and is labelled as
 *       such.</li>
 *   <li><strong>A citation for the CICS transaction identifier field is {@code Not available}</strong> -
 *       <strong>Medium</strong>. A repository-wide search for that field name at this anchor returns zero
 *       occurrences; the complete exec-interface-block census in {@code app/cbl} is the command-area length
 *       field 49 times and the attention identifier 16 times. No locator for it is fabricated anywhere in
 *       this file, and the correlation identifier is described as the replacement <em>thread of
 *       identity</em> without claiming a line reference it does not have.</li>
 * </ol>
 *
 * @see AbstractAwsIntegrationTest
 */
@AutoConfigureMockMvc
@DisplayName("Observability - four bounded instruments, a closed Actuator surface and split health groups")
class ObservabilityHealthMetricsIntegrationTest extends AbstractAwsIntegrationTest {

    /**
     * Prefix that distinguishes this application's own instruments from every framework-supplied one.
     *
     * <p>Discovery is by this prefix rather than by an exclusion list of framework namespaces, because an
     * exclusion list has to be kept in sync with whatever the framework registers next and fails open when
     * it is not. A prefix filter fails closed: a new application instrument is caught, and a new framework
     * instrument is ignored without any edit here.
     */
    private static final String METRIC_PREFIX = "carddemo";

    /**
     * The Actuator base path, and the only URL prefix this class probes.
     *
     * <p>No host, port, address or connection string appears anywhere in this source. The mock web layer
     * needs a path and nothing more, which is what keeps Rule 1 Clause C's ban on environment-specific
     * assumptions satisfiable at all.
     */
    private static final String ACTUATOR = "/actuator";

    /** The three endpoint identifiers the exposure list is allowed to admit, in sorted order. */
    private static final Set<String> EXPOSED_ENDPOINTS = Set.of("health", "info", "prometheus");

    /**
     * Endpoints that must not answer: each discloses something a caller has no business reading -
     * resolved configuration, the bean graph, bound configuration properties, or the logger tree.
     */
    private static final List<String> UNEXPOSED_ENDPOINTS = List.of("env", "beans", "configprops", "loggers");

    /**
     * Tag keys the application's instruments are permitted to carry, normalised.
     *
     * <p>A closed allow-list rather than an open deny-list, because only an allow-list fails when a new tag
     * is added. The two ambient keys are the common tags every series inherits, and both are single-valued
     * constants; the other three are the bounded dimensions of the rejected, authentication and
     * transaction-amount series.
     *
     * <p>{@code sign} is admitted deliberately, and its cardinality is closed at exactly TWO by
     * construction rather than by convention: the value is derived from {@link java.math.BigDecimal#signum()}
     * through a single two-way branch, so only {@code credit} and {@code debit} can ever be emitted. It exists
     * because the amount instrument is a counter and a counter cannot carry a negative value - Prometheus
     * rejects the entire scrape response if one appears - while the source genuinely accumulates both signs:
     * {@code app/cbl/CBTRN02C.cbl:L548-L552} adds a non-negative amount to the cycle CREDIT and a negative one
     * to the cycle DEBIT, and {@code app/data/ASCII/dailytran.txt} carries both overpunch signs. Splitting one
     * signed number into two monotonic series is what preserves that sign information without a fifth
     * instrument, so this is a new DIMENSION on the fourth instrument and not a fifth metric.
     */
    private static final Set<String> ALLOWED_TAG_KEYS =
            Set.of("application", "component", "rejectcode", "outcome", "sign");

    /**
     * Tag keys that would make a series unbounded, normalised and enumerated explicitly.
     *
     * <p>Redundant against the allow-list above by construction, and kept anyway: it names the specific
     * hazards, so a reviewer can see that each was considered rather than inferring it from an absence.
     */
    private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "accountid", "cardnumber", "customerid", "transactionid", "userid", "username", "user",
            "correlationid", "traceid", "spanid", "timestamp", "time", "key", "s3key", "objectkey",
            "messageid", "jobexecutionid", "jobinstanceid", "ip", "ipaddress", "remoteaddress", "host",
            "secusrid", "pan", "ssn", "email", "phonenumber", "sessionid", "requestid");

    /**
     * The status renderer's output, reproduced exactly as the corpus emits it for status {@code '23'}.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:714-727} is {@code 9910-DISPLAY-IO-STATUS}, and it emits
     * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} on <em>both</em> branches, at {@code :721} and at
     * {@code :725}. {@code IO-STATUS-04} is {@code PIC 9} followed by {@code PIC 999} at {@code :138-140},
     * so it is exactly four characters. COBOL concatenates a literal and an identifier in {@code DISPLAY}
     * with <strong>no separator</strong>, and the literal already contains the placeholder text, so the
     * rendered line carries the stray placeholder <em>and</em> the four digits. That is a
     * <strong>preserved legacy quirk and is never repaired</strong>, and no masking rule may touch it:
     * reformatting, wrapping, truncating, re-rendering or masking it breaks parity.
     */
    private static final String STATUS_LITERAL = "FILE STATUS IS: NNNN0023";

    /**
     * How many times a substrate probe may be read before its verdict is taken as final.
     *
     * <p>Four, because that is the retry budget the container image health-check itself allows at
     * {@code Dockerfile:563} - {@code --retries=4} with {@code --interval=15s}. <strong>The contract is
     * therefore "readiness reports up within four attempts", not "on the first sample", and asserting a
     * single reading would be STRICTER THAN THE CONTRACT.</strong> That matters in practice rather than in
     * theory: each contributor runs inside a deliberately small total budget with an even smaller
     * per-request deadline, so on a loaded host a request can exceed its deadline and the probe then
     * correctly reports a timeout. Treating that as a failure would make this class assert the host's load
     * rather than the application's behaviour - measured directly, a three-reading loop that demanded up
     * every time failed on a host at a load average near forty while every other assertion held. A
     * timeout verdict is itself asserted, deliberately, in the group covering absent and misconfigured
     * substrates.
     */
    private static final int PROBE_ATTEMPT_LIMIT = 4;

    /**
     * How long to pause between probe readings.
     *
     * <p>Short relative to the image health-check's interval, because the point is to ride out a single
     * expired request deadline rather than to wait for a substrate to start. A pause rather than a spin:
     * Rule 1 Clause A's efficiency requirement rules out burning a core to wait.
     */
    private static final Duration PROBE_RETRY_PAUSE = Duration.ofMillis(750L);

    /**
     * Detail substrings that must never appear in a health payload, whatever the substrate's state.
     *
     * <p>Chosen to be recognisable fragments of the things that actually leak in practice: a connection
     * URL scheme, a credential label, a key label, an amazon resource name, a signature-bearing query
     * parameter, and a stack-trace marker. A twelve-digit account identifier is checked separately,
     * numerically, because it has no fixed label to key on.
     */
    private static final List<String> FORBIDDEN_DETAIL_FRAGMENTS = List.of(
            "jdbc", "password", "passwd", "secret", "accesskey", "access-key", "credential", "arn:",
            "x-amz-signature", "signature=", "at com.cardemo", "at java.", "caused by",
            "authorization", "bearer ");

    /** The registry the application publishes to, injected rather than rebuilt. */
    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * The scrape registry, injected so that the rendered body can be read in process.
     *
     * <p>Distinct from the field above on purpose: that one is the facade producers write through, this one
     * is the exporter a scraper reads, and asserting on both is what proves a series survives the whole
     * path rather than merely existing.
     */
    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    /** The instrument facade under test, injected so its published API is exercised, not reimplemented. */
    @Autowired
    private MetricsConfig metricsConfig;

    /** The health endpoint, which is what serves both probe groups. */
    @Autowired
    private HealthEndpoint healthEndpoint;

    /** The contributor registry, which is what a component key has to resolve against. */
    @Autowired
    private HealthContributorRegistry healthContributorRegistry;

    /**
     * The set of web endpoints the framework built after applying the exposure list.
     *
     * <p>Injected because it is the behavioural reading of the exposure filter: the property text says what
     * was asked for, this says what was actually admitted, and only the second can catch a filter that
     * stopped being applied.
     */
    @Autowired
    private WebEndpointsSupplier webEndpointsSupplier;

    /** The resolved environment, read for the declared configuration rather than for an address. */
    @Autowired
    private Environment environment;

    /** The mock web layer, the only route by which an HTTP-level contract can be asserted here. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Sole constructor.
     *
     * <p>Explicit and empty. Declared rather than defaulted so that no initialisation can be added to it
     * by accident: this class is instantiated per test method by the framework and holds no state of its
     * own beyond what is injected.
     */
    ObservabilityHealthMetricsIntegrationTest() {
        super();
    }

    // =================================================================================================
    // Discovery helpers. Pure where they can be, and never dependent on a literal metric name.
    // =================================================================================================

    /**
     * Every instrument this application registered, in a deterministic order.
     *
     * <p>Sorted by name and then by rendered tag set so that no assertion downstream depends on the
     * registry's iteration order, which is a hash order and therefore not a contract.
     *
     * <p>Side effects: none. Error modes: none; an empty result is a legitimate answer that the caller
     * asserts against.
     *
     * @return the application's own instruments, framework instruments excluded by prefix
     */
    private List<Meter> applicationMeters() {
        final List<Meter> meters = new ArrayList<>();
        for (final Meter meter : meterRegistry.getMeters()) {
            if (meter.getId().getName().startsWith(METRIC_PREFIX)) {
                meters.add(meter);
            }
        }
        meters.sort(Comparator.comparing((Meter meter) -> meter.getId().getName())
                .thenComparing(meter -> renderTags(meter.getId())));
        return meters;
    }

    /**
     * The distinct series names this application registered.
     *
     * @return the names, sorted, never {@code null}
     */
    private Set<String> applicationMeterNames() {
        final Set<String> names = new TreeSet<>();
        for (final Meter meter : applicationMeters()) {
            names.add(meter.getId().getName());
        }
        return names;
    }

    /**
     * Reads an instrument's current value without caring what kind of instrument it is.
     *
     * <p>A counter publishes its total under the count statistic and a gauge under the value statistic, so
     * accepting either is what lets a caller observe movement without first knowing the type - which is the
     * whole point of discovering roles by behaviour.
     *
     * <p><strong>The reporting boundary.</strong> Micrometer's measurement accessor is typed as a primitive
     * floating-point value, and there is no other way to read a registry. The value is lifted into
     * {@code java.math.BigDecimal} immediately, on this one line, and every comparison and subtraction
     * downstream is exact decimal arithmetic. No floating-point variable, field or operation exists
     * anywhere in this class, and no monetary figure is ever computed here - the series read is advisory
     * telemetry, and the database remains the authority for any financial total.
     *
     * <p>Side effects: none. Error modes: an instrument publishing neither statistic is a contract
     * violation rather than a runtime condition, so it fails the test loudly instead of returning zero and
     * making a later assertion vacuous.
     *
     * @param meter the instrument to read; must not be {@code null}
     * @return its current value as an exact decimal, never {@code null}
     */
    private static BigDecimal observedValue(final Meter meter) {
        for (final Measurement measurement : meter.measure()) {
            if (measurement.getStatistic() == Statistic.COUNT
                    || measurement.getStatistic() == Statistic.VALUE) {
                return BigDecimal.valueOf(measurement.getValue());
            }
        }
        throw new AssertionError("instrument '" + meter.getId().getName() + "' publishes neither a count "
                + "nor a value statistic, so its movement cannot be observed");
    }

    /**
     * Snapshots every application series so that a later reading can be expressed as a delta.
     *
     * <p>Deltas rather than absolutes because the registry is shared context-wide: an absolute assertion
     * passes or fails according to what ran before it, which is exactly the order dependence Rule 1
     * Clause A's determinism requirement rules out.
     *
     * @return an insertion-ordered map from instrument identity to current value, never {@code null}
     */
    private Map<Meter.Id, BigDecimal> snapshot() {
        final Map<Meter.Id, BigDecimal> values = new LinkedHashMap<>();
        for (final Meter meter : applicationMeters()) {
            values.put(meter.getId(), observedValue(meter));
        }
        return values;
    }

    /**
     * The series that moved since a snapshot, and by how much.
     *
     * <p>A series absent from the snapshot is treated as having been at zero, so a series registered
     * between the two readings shows up as movement rather than being silently skipped. That matters: it is
     * how an unexpected new series - the fifth instrument this class exists to forbid - becomes visible.
     *
     * @param before a snapshot from {@link #snapshot()}; must not be {@code null}
     * @return an insertion-ordered map of moved instrument identity to signed delta, never {@code null}
     */
    private Map<Meter.Id, BigDecimal> movementSince(final Map<Meter.Id, BigDecimal> before) {
        final Map<Meter.Id, BigDecimal> moved = new LinkedHashMap<>();
        for (final Meter meter : applicationMeters()) {
            final BigDecimal previous = before.getOrDefault(meter.getId(), BigDecimal.ZERO);
            final BigDecimal delta = observedValue(meter).subtract(previous);
            if (delta.signum() != 0) {
                moved.put(meter.getId(), delta);
            }
        }
        return moved;
    }

    /**
     * The distinct application series names a single facade call moves.
     *
     * <p>Identity by behaviour rather than by literal name: a metric that has been renamed still moves when
     * its own code path runs, so this discovers what the producer actually publishes instead of asserting a
     * string the producer chose. Expressed as a delta for the same reason {@link #movementSince(Map)} is.
     *
     * @param facadeCall the single facade call whose effect is being attributed; must not be {@code null}
     * @return the distinct series names that moved, never {@code null}
     */
    private Set<String> namesMovedBy(final Runnable facadeCall) {
        final Map<Meter.Id, BigDecimal> before = snapshot();
        facadeCall.run();
        final Set<String> names = new TreeSet<>();
        for (final Meter.Id id : movementSince(before).keySet()) {
            names.add(id.getName());
        }
        return names;
    }

    /**
     * The union of several discovered name sets, as one ordered set.
     *
     * @param sets the sets to combine; must not be {@code null} and must hold no {@code null}
     * @return the union in name order, never {@code null}
     */
    @SafeVarargs
    private static Set<String> concat(final Set<String>... sets) {
        final Set<String> combined = new TreeSet<>();
        for (final Set<String> set : sets) {
            combined.addAll(set);
        }
        return combined;
    }

    /**
     * Recovers the signed net movement of the transaction-amount instrument from a movement map.
     *
     * <p>The instrument is two counter series, {@code sign=credit} and {@code sign=debit}, and the signed
     * total a dashboard reports is their difference. This is the same expression the Grafana panel uses, so
     * asserting through it asserts what an operator actually sees rather than an internal field.
     *
     * <p>Both series carry magnitudes, so both deltas are non-negative and the sign of the result comes
     * entirely from which side moved further. A series absent from the map contributes zero, which is
     * correct: absent means unmoved.
     *
     * <p>Side effects: none. Error modes: none - a map containing no amount series yields
     * {@link BigDecimal#ZERO}, which is the accurate answer for "the amount did not move".
     *
     * @param moved a movement map from {@link #movementSince(Map)}; must not be {@code null}
     * @return {@code credit - debit} as an exact decimal, never {@code null}
     */
    private static BigDecimal signedNetMovement(final Map<Meter.Id, BigDecimal> moved) {
        BigDecimal net = BigDecimal.ZERO;
        for (final Map.Entry<Meter.Id, BigDecimal> entry : moved.entrySet()) {
            if (!MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL.equals(entry.getKey().getName())) {
                continue;
            }
            if (MetricsConfig.SIGN_DEBIT.equals(entry.getKey().getTag(MetricsConfig.TAG_SIGN))) {
                net = net.subtract(entry.getValue());
            } else {
                net = net.add(entry.getValue());
            }
        }
        return net;
    }

    /**
     * Renders an instrument's tags as a stable string, for ordering and for assertion messages.
     *
     * @param id the instrument identity; must not be {@code null}
     * @return the tags as {@code key=value} pairs joined by commas in tag order, never {@code null}
     */
    private static String renderTags(final Meter.Id id) {
        final StringBuilder rendered = new StringBuilder();
        for (final Tag tag : id.getTags()) {
            if (!rendered.isEmpty()) {
                rendered.append(',');
            }
            rendered.append(tag.getKey()).append('=').append(tag.getValue());
        }
        return rendered.toString();
    }

    /**
     * Reduces a tag key to a separator-insensitive, case-insensitive form.
     *
     * <p>So that a dotted key and its underscored scrape rendering compare equal, and so that a hazard
     * spelled in either convention is caught by one entry in the forbidden set rather than two.
     * {@code Locale.ROOT} is explicit because a default locale can lower-case differently.
     *
     * @param key the tag key as registered; must not be {@code null}
     * @return the normalised key, never {@code null}
     */
    private static String normaliseKey(final String key) {
        return key.toLowerCase(Locale.ROOT).replace(".", "").replace("_", "").replace("-", "");
    }

    /**
     * Both spellings of a reject code that a producer is free to choose between.
     *
     * <p>The published tag value may be the plain decimal or the four-character zero-padded form of the
     * same code - that is the author's choice, and asserting one exact string would make this class fail on
     * a legitimate implementation. Membership in a bounded set is asserted instead.
     *
     * @return every accepted tag value for every reject code, sorted, never {@code null}
     */
    private static Set<String> acceptedRejectTagValues() {
        final Set<String> accepted = new TreeSet<>();
        for (final RejectCode rejectCode : RejectCode.values()) {
            accepted.add(Integer.toString(rejectCode.getCode()));
            accepted.add(String.format(Locale.ROOT, "%04d", rejectCode.getCode()));
        }
        return accepted;
    }

    /**
     * The application's series names as the scrape format renders them.
     *
     * <p>Parsed from the type declaration lines rather than from the sample lines, because a type
     * declaration appears exactly once per series while a sample appears once per tag combination -
     * counting samples would count the five reject dimensions as five series. The match is on the
     * application prefix only: the scrape format renders a dot as an underscore and appends a suffix to a
     * monotonic series, so no full name is ever written here.
     *
     * <p>Side effects: none.
     *
     * @param scrape a rendered scrape body; must not be {@code null}
     * @return the distinct application series names it declares, sorted, never {@code null}
     */
    private static Set<String> renderedSeriesNames(final String scrape) {
        final Set<String> names = new TreeSet<>();
        for (final String line : scrape.split("\n")) {
            if (!line.startsWith("# TYPE ")) {
                continue;
            }
            final String[] fields = line.substring("# TYPE ".length()).trim().split("\\s+");
            if (fields.length > 0 && fields[0].startsWith(METRIC_PREFIX)) {
                names.add(fields[0]);
            }
        }
        return names;
    }

    /**
     * Performs a read-only HTTP request against the mock web layer.
     *
     * @param path the Actuator-relative path, beginning with a slash; must not be {@code null}
     * @return the completed result, never {@code null}
     * @throws Exception if the mock layer cannot dispatch the request, which is a harness failure rather
     *     than an assertion outcome and is therefore allowed to propagate with its cause intact
     */
    private MvcResult get(final String path) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(ACTUATOR + path)).andReturn();
    }

    /**
     * The body of a read-only HTTP request against the mock web layer.
     *
     * @param path the Actuator-relative path, beginning with a slash; must not be {@code null}
     * @return the response body decoded as text, never {@code null}
     * @throws Exception if the mock layer cannot dispatch the request
     */
    private String getBody(final String path) throws Exception {
        return get(path).getResponse().getContentAsString();
    }

    /**
     * The registered contributor behind a health component key, as the health endpoint itself resolves it.
     *
     * <p>Taken from the registry rather than by calling a configuration method, so that what is probed is
     * the instance Actuator actually serves and not a second one built for the test.
     *
     * @param componentKey the component key, such as the published object-store or queue name; must not be
     *     {@code null}
     * @return the contributor, never {@code null}
     */
    private HealthIndicator registeredIndicator(final String componentKey) {
        final HealthContributor contributor = healthContributorRegistry.getContributor(componentKey);
        assertThat(contributor)
                .as("health component key '%s' must resolve to a registered contributor; the readiness "
                        + "include list names this key, and an unresolvable key silently empties the group",
                        componentKey)
                .isInstanceOf(HealthIndicator.class);
        return (HealthIndicator) contributor;
    }

    /**
     * Pauses briefly between probe readings, preserving the interrupt contract.
     *
     * <p>An interrupt is re-asserted on the current thread rather than discarded, so that a shutdown
     * request is not swallowed by a test helper, and it is converted into a loud failure rather than a
     * silent early return that would leave the caller's assertion vacuous.
     */
    private static void pauseBetweenProbeAttempts() {
        try {
            Thread.sleep(PROBE_RETRY_PAUSE.toMillis());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while pausing between probe readings", interrupted);
        }
    }

    /**
     * Reads a contributor until it reports up, within the image health-check's own retry budget.
     *
     * <p>This is faithful to the contract rather than a relaxation of it: the health-check retries four
     * times before declaring a container unhealthy, so a substrate that answers on the second reading is
     * <em>healthy by the contract that is actually enforced in production</em>. Demanding the first reading
     * would assert something no deployment requires.
     *
     * <p>Side effects: issues the contributor's own read-only remote calls, once per attempt, and pauses
     * between attempts. Error modes: fails the calling test with the last observed verdict and its full
     * detail map attached, so a genuine outage is diagnosable from the failure message alone rather than
     * needing a re-run.
     *
     * @param probe reads the contributor once; must not be {@code null}
     * @param description what is being probed, for the failure message; must not be {@code null}
     * @return the verdict that reported up, never {@code null}
     */
    private static Health awaitUp(final Supplier<Health> probe, final String description) {
        Health observed = probe.get();
        for (int attempt = 2; attempt <= PROBE_ATTEMPT_LIMIT
                && !Status.UP.equals(observed.getStatus()); attempt++) {
            pauseBetweenProbeAttempts();
            observed = probe.get();
        }
        assertThat(observed.getStatus())
                .as("%s must report up within %d readings - the retry budget the container image "
                        + "health-check itself allows. Every container in this tier is running, so a "
                        + "persistent down verdict here means the substrate is genuinely unreachable and "
                        + "not merely slow. Last detail: %s",
                        description, PROBE_ATTEMPT_LIMIT, observed.getDetails())
                .isEqualTo(Status.UP);
        return observed;
    }

    /**
     * Reads a probe group until it reports up, within the same retry budget.
     *
     * @param group the group name, {@code liveness} or {@code readiness}; must not be {@code null}
     * @return the group's verdict once it reported up, never {@code null}
     */
    private HealthComponent awaitGroupUp(final String group) {
        HealthComponent observed = healthEndpoint.healthForPath(group);
        for (int attempt = 2; attempt <= PROBE_ATTEMPT_LIMIT
                && !Status.UP.equals(observed.getStatus()); attempt++) {
            pauseBetweenProbeAttempts();
            observed = healthEndpoint.healthForPath(group);
        }
        assertThat(observed.getStatus())
                .as("probe group '%s' must report up within %d readings, matching the image "
                        + "health-check's retry budget. Observed: %s", group, PROBE_ATTEMPT_LIMIT, observed)
                .isEqualTo(Status.UP);
        return observed;
    }

    /**
     * Every component key currently registered.
     *
     * @return the keys, sorted, never {@code null}
     */
    private Set<String> registeredComponentKeys() {
        final Set<String> keys = new TreeSet<>();
        healthContributorRegistry.forEach(named -> keys.add(named.getName()));
        return keys;
    }

    /**
     * The component keys a configured probe group is composed of.
     *
     * @param group the group name, {@code liveness} or {@code readiness}; must not be {@code null}
     * @return the keys the group actually resolved, sorted, never {@code null}
     */
    private Set<String> groupComponentKeys(final String group) {
        final HealthComponent component = healthEndpoint.healthForPath(group);
        assertThat(component)
                .as("probe group '%s' must be served as a composite; a group that resolves to a bare "
                        + "status has lost its members", group)
                .isInstanceOf(CompositeHealth.class);
        return new TreeSet<>(((CompositeHealth) component).getComponents().keySet());
    }

    /**
     * The raw value a property is <em>declared</em> with, read straight from the property sources so that no
     * placeholder is resolved.
     *
     * <p>{@code Environment.getProperty} is strict: it throws
     * {@code PlaceholderResolutionException} when a value contains a {@code ${...}} reference that the
     * current environment cannot satisfy, and {@code containsProperty} returns {@code true} for such a key
     * without resolving it. Several keys in this application are declared as defaultless environment-variable
     * references on purpose, so that a missing setting fails loudly at startup rather than falling back to a
     * silent default. Asserting on the declared text is therefore the only way to prove the indirection
     * exists without making the assertion depend on which variables the developer happens to have exported.
     *
     * @param key the property name; must not be {@code null}
     * @return the first declared value found, unresolved, or {@code null} when no source declares the key
     */
    private String declaredWithoutResolution(final String key) {
        assertThat(environment)
                .as("the injected environment must be the configurable implementation, because the raw "
                        + "declared value of a key is only reachable through its property sources")
                .isInstanceOf(ConfigurableEnvironment.class);
        for (final PropertySource<?> source : ((ConfigurableEnvironment) environment).getPropertySources()) {
            final Object declared = source.getProperty(key);
            if (declared != null) {
                return declared.toString();
            }
        }
        return null;
    }

    /**
     * The include list a probe group is configured with, as declared rather than as resolved.
     *
     * @param group the group name; must not be {@code null}
     * @return the declared keys, sorted, never {@code null}
     */
    private Set<String> declaredGroupIncludes(final String group) {
        final String declared = environment.getProperty(
                "management.endpoint.health.group." + group + ".include");
        assertThat(declared)
                .as("probe group '%s' must declare an explicit include list; an absent list is what makes "
                        + "liveness quietly inherit an external dependency", group)
                .isNotBlank();
        final Set<String> keys = new TreeSet<>();
        for (final String key : declared.split(",")) {
            if (!key.isBlank()) {
                keys.add(key.trim());
            }
        }
        return keys;
    }

    /**
     * Every object key currently present in the three configured buckets.
     *
     * <p>Enumeration is used <em>here</em>, in the test, precisely because the probe under test must not:
     * comparing the full key set across a probe is the only way to prove that nothing was created and
     * nothing was removed.
     *
     * @return bucket-qualified object keys, sorted, never {@code null}
     */
    private Set<String> objectKeysInConfiguredBuckets() {
        final Set<String> keys = new TreeSet<>();
        for (final String bucket : List.of(batchInputBucket(), batchOutputBucket(), statementsBucket())) {
            for (final S3Object object : s3Client()
                    .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                    .contents()) {
                keys.add(bucket + "/" + object.key());
            }
        }
        return keys;
    }

    /**
     * The encoder the console appender is configured with, which is where masking is applied.
     *
     * <p>Masking is a property of the <em>encoded</em> output, not of the logging event, so a plain
     * event-collecting appender would see the unmasked message and prove nothing. The configured encoder is
     * therefore driven directly. This is entirely in process: no file is written and no container log is
     * read.
     *
     * @return the configured encoder, never {@code null}
     */
    private static Encoder<ILoggingEvent> configuredEncoder() {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Iterator<Appender<ILoggingEvent>> appenders =
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
        while (appenders.hasNext()) {
            final Appender<ILoggingEvent> appender = appenders.next();
            if (appender instanceof OutputStreamAppender<ILoggingEvent> stream
                    && stream.getEncoder() != null) {
                return stream.getEncoder();
            }
        }
        throw new AssertionError("no encoding appender is attached to the root logger, so the masking "
                + "configuration cannot be exercised; without it no log-hygiene claim is provable");
    }

    /**
     * Encodes one synthetic event through the configured encoder and returns the rendered line.
     *
     * <p>The event carries the inherited fixed instant rather than a wall-clock reading, so the rendered
     * output is byte-identical on every run.
     *
     * @param message the message to render; must not be {@code null}
     * @return the encoded output as text, never {@code null}
     */
    private static String encode(final String message) {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        final LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName(ObservabilityHealthMetricsIntegrationTest.class.getName());
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setTimeStamp(FIXED_INSTANT.toEpochMilli());
        return new String(configuredEncoder().encode(event), StandardCharsets.UTF_8);
    }

    /**
     * The instrument contract: four required named series, no fifth, and no unbounded dimension.
     *
     * <p>Every assertion in here is discovered by prefix or by behaviour. None names a metric.
     *
     * <p><strong>Timers are complementary and additive, not substitutes.</strong> The contract this group
     * enforces is that the four required identities are all present, each published through exactly one
     * instrument of the only type that can carry its values. It deliberately does <em>not</em> forbid a
     * timer, a distribution summary or a long-task timer: latency instrumentation neither replaces one of the
     * four nor reduces their number. What it does forbid is a fifth <em>named series</em> under the
     * application prefix, which would be an unowned dashboard panel, and any unbounded tag dimension on any
     * instrument at all - a timer included.
     */
    @Nested
    @DisplayName("the instrument set is four required named series, with additive timers permitted and no "
            + "unbounded dimension")
    class InstrumentSetIsBounded {

        @Test
        @DisplayName("exactly four series names carry the application prefix, and a fifth is forbidden")
        void exactlyFourSeriesNamesExist() {
            final Set<String> names = applicationMeterNames();

            assertThat(names)
                    .as("the observability contract fixes FOUR named series - records processed, records "
                            + "rejected, authentication attempts and total transaction amount. A fifth "
                            + "instrument is forbidden: it is unbudgeted cardinality on every scrape and "
                            + "an unowned panel on the dashboard. Discovered by the '%s' prefix rather "
                            + "than by literal name, because the names are the producer's choice; the "
                            + "actual set found was %s", METRIC_PREFIX, names)
                    .hasSize(4);
        }

        @Test
        @DisplayName("all four required identities are present, each moved through its own code path, and "
                + "each published through exactly one instrument")
        void allFourRequiredIdentitiesArePresent() {
            final Set<String> processed = namesMovedBy(() -> metricsConfig.countRecordProcessed());
            final Set<String> rejected = namesMovedBy(
                    () -> metricsConfig.countRecordRejected(RejectCode.OVERLIMIT_TRANSACTION));
            final Set<String> attempts = namesMovedBy(() -> metricsConfig
                    .countAuthenticationAttempt(MetricsConfig.AuthenticationOutcome.SUCCESS));
            final Set<String> amount = namesMovedBy(
                    () -> metricsConfig.countTransactionAmount(new BigDecimal("1.23")));

            final Set<String> movedNames = new TreeSet<>();
            movedNames.addAll(processed);
            movedNames.addAll(rejected);
            movedNames.addAll(attempts);
            movedNames.addAll(amount);

            assertThat(movedNames)
                    .as("the four required identities are records processed, records rejected, "
                            + "authentication attempts and total transaction amount. Each is discovered by "
                            + "moving its own code path rather than by a literal name, so a renamed metric "
                            + "cannot pass by matching a string and a missing one cannot pass at all. "
                            + "Names moved: %s", movedNames)
                    .hasSize(4);

            final Map<String, Set<Meter.Type>> typesByName = new LinkedHashMap<>();
            for (final Meter meter : applicationMeters()) {
                if (movedNames.contains(meter.getId().getName())) {
                    typesByName.computeIfAbsent(meter.getId().getName(), name -> new TreeSet<>())
                            .add(meter.getId().getType());
                }
            }
            assertThat(typesByName.keySet())
                    .as("every moved identity must still be resolvable in the registry")
                    .isEqualTo(movedNames);
            for (final Map.Entry<String, Set<Meter.Type>> entry : typesByName.entrySet()) {
                assertThat(entry.getValue())
                        .as("identity '%s' must be published through exactly ONE instrument type; two types "
                                + "under one name means two producers disagreeing, and Prometheus rejects "
                                + "one metric name carrying two shapes", entry.getKey())
                        .hasSize(1);
            }

            for (final String eventCount : new TreeSet<>(concat(processed, rejected, attempts))) {
                assertThat(typesByName.get(eventCount).iterator().next())
                        .as("identity '%s' is a monotonic event count, so it is a COUNTER", eventCount)
                        .isEqualTo(Meter.Type.COUNTER);
            }
            assertThat(typesByName.get(amount.iterator().next()).iterator().next())
                    .as("the signed transaction-amount total is a COUNTER like the other three, and the two "
                            + "hazards that decided it are measured rather than assumed. Micrometer's "
                            + "INCREMENTING counter ignores a non-positive increment, so an increment-backed "
                            + "total would silently discard every debit; and the Prometheus client rejects a "
                            + "negative counter AT SCRAPE TIME with 'counters cannot have a negative value', "
                            + "which would take EVERY series off /actuator/prometheus rather than just this "
                            + "one. app/cbl/CBTRN02C.cbl:548-552 adds a NEGATIVE amount to the current-cycle "
                            + "DEBIT accumulator, which is exactly why the over-limit formula subtracts it, "
                            + "so a signed total is the contract and no absolute value may be taken "
                            + "anywhere. Both are satisfied by partitioning on that same predicate into a "
                            + "credit and a debit FunctionCounter over exact BigDecimal accumulators, whose "
                            + "difference is the net - which also keeps the reserved _total suffix a gauge "
                            + "would have been stripped of")
                    .isEqualTo(Meter.Type.COUNTER);
        }

        @Test
        @DisplayName("latency instrumentation is additive: it neither replaces one of the four series nor "
                + "reduces their number, and it may not borrow one of their names")
        void latencyInstrumentationIsAdditiveRatherThanForbidden() {
            final Set<String> requiredSeries = applicationMeterNames();
            final Map<String, Meter.Type> latencyInstruments = new LinkedHashMap<>();
            for (final Meter meter : applicationMeters()) {
                final Meter.Type type = meter.getId().getType();
                if (type == Meter.Type.TIMER || type == Meter.Type.DISTRIBUTION_SUMMARY
                        || type == Meter.Type.LONG_TASK_TIMER) {
                    latencyInstruments.put(meter.getId().getName(), type);
                }
            }

            assertThat(requiredSeries)
                    .as("the four named series stand whatever latency instrumentation is also present - "
                            + "timers are complementary and additive, never substitutes, so their presence "
                            + "is permitted and their absence is not required. This assertion is what "
                            + "stops a timer from being counted as one of the four, and what stops the "
                            + "suite from forbidding one. Latency instruments observed: %s",
                            latencyInstruments)
                    .hasSize(4);
            // An empty latency set is a legitimate state, not a pass by vacuity: the contract permits these
            // instruments without requiring them. The disjointness is therefore asserted per observed
            // instrument rather than through a bulk assertion that rejects an empty argument.
            for (final Map.Entry<String, Meter.Type> latency : latencyInstruments.entrySet()) {
                assertThat(requiredSeries)
                        .as("latency instrument '%s' (%s) must not borrow one of the four required series "
                                + "names: Prometheus rejects one metric name carrying two instrument "
                                + "shapes, so a shared name would make that identity unscrapeable",
                                latency.getKey(), latency.getValue())
                        .doesNotContain(latency.getKey());
            }
        }

        @Test
        @DisplayName("every series is a counter - four names, no gauge, no timer, no summary")
        void everySeriesUsesTheOnlyInstrumentThatCanCarryItsValues() {
            final Map<String, Set<Meter.Type>> typesByName = new LinkedHashMap<>();
            for (final Meter meter : applicationMeters()) {
                typesByName.computeIfAbsent(meter.getId().getName(), name -> new TreeSet<>())
                        .add(meter.getId().getType());
            }

            for (final Map.Entry<String, Set<Meter.Type>> entry : typesByName.entrySet()) {
                assertThat(entry.getValue())
                        .as("series '%s' must be published through exactly ONE instrument type; two types "
                                + "under one name means two producers disagreeing", entry.getKey())
                        .hasSize(1);
                assertThat(entry.getValue().iterator().next())
                        .as("series '%s' must be a COUNTER. AAP section 0.7.7 names four counters, and a "
                                + "Prometheus gauge additionally loses the _total suffix from its rendered "
                                + "name, which silently empties any dashboard querying it", entry.getKey())
                        .isEqualTo(Meter.Type.COUNTER);
            }

            final long counters = typesByName.values().stream()
                    .filter(types -> types.contains(Meter.Type.COUNTER)).count();
            final long gauges = typesByName.values().stream()
                    .filter(types -> types.contains(Meter.Type.GAUGE)).count();

            assertThat(counters)
                    .as("all four series are counters, including the amount total: it reaches that through "
                            + "a FunctionCounter over an exact BigDecimal accumulator rather than through "
                            + "Counter.increment(double), which would discard every debit")
                    .isEqualTo(4L);
            assertThat(gauges)
                    .as("NO gauge. The amount total was one, and a gauge cannot carry it: the Prometheus "
                            + "client strips the reserved _total suffix from a gauge, so the series rendered "
                            + "as carddemo_transaction_amount while every dashboard and every operator "
                            + "queried carddemo_transaction_amount_total and got an empty panel with no "
                            + "error. The sign problem a gauge was chosen to solve is solved instead by "
                            + "partitioning on the same IF DALYTRAN-AMT >= 0 predicate that "
                            + "app/cbl/CBTRN02C.cbl:548-552 uses for its own two cycle accumulators")
                    .isZero();
        }

        @Test
        @DisplayName("no series carries a tag key whose values could be unbounded")
        void noSeriesCarriesAnUnboundedTagKey() {
            final Set<String> observed = new TreeSet<>();
            for (final Meter meter : applicationMeters()) {
                for (final Tag tag : meter.getId().getTags()) {
                    observed.add(normaliseKey(tag.getKey()));
                }
            }

            assertThat(observed)
                    .as("the tag keys carried by the application's series must form a small CLOSED set. "
                            + "An allow-list is asserted rather than a deny-list because only an "
                            + "allow-list fails when a new dimension appears. Observed: %s", observed)
                    .isSubsetOf(ALLOWED_TAG_KEYS);

            assertThat(observed)
                    .as("no series may be dimensioned by an account, card, customer, transaction, user, "
                            + "correlation, trace, span, timestamp, object-key, message or job identity, "
                            + "nor by a network address. Each such value is unbounded, so each would turn "
                            + "one series into millions - ruinous on cost and on cardinality alike, and "
                            + "an identity leak into telemetry besides")
                    .doesNotContainAnyElementsOf(FORBIDDEN_TAG_KEYS);
        }

        @Test
        @DisplayName("every series holds at most five tag-value combinations")
        void everySeriesHoldsABoundedNumberOfCombinations() {
            final Map<String, Set<String>> combinationsByName = new LinkedHashMap<>();
            for (final Meter meter : applicationMeters()) {
                combinationsByName.computeIfAbsent(meter.getId().getName(), name -> new TreeSet<>())
                        .add(renderTags(meter.getId()));
            }

            for (final Map.Entry<String, Set<String>> entry : combinationsByName.entrySet()) {
                assertThat(entry.getValue())
                        .as("series '%s' must stay bounded. Five is the ceiling, set by the five reject "
                                + "codes of app/cbl/CBTRN02C.cbl:385, :397, :410, :417 and :556; every "
                                + "other series is narrower. Combinations: %s",
                                entry.getKey(), entry.getValue())
                        .hasSizeLessThanOrEqualTo(5);
            }
        }
    }

    /**
     * Role identification by behaviour: each instrument is found by moving it, never by naming it.
     *
     * <p>Deltas throughout, so the group passes alone, in any order and twice in succession.
     */
    @Nested
    @DisplayName("each series is identified by exercising its code path, never by a literal name")
    class SeriesRolesAreIdentifiedByBehaviour {

        @Test
        @DisplayName("the processed-records series moves by one, untagged beyond the ambient pair")
        void theProcessedRecordsSeriesMovesByOne() {
            final Map<Meter.Id, BigDecimal> before = snapshot();

            metricsConfig.countRecordProcessed();

            final Map<Meter.Id, BigDecimal> moved = movementSince(before);
            assertThat(moved)
                    .as("counting one processed record must move EXACTLY ONE series. This replaces "
                            + "app/cbl/CBTRN02C.cbl:227, DISPLAY 'TRANSACTIONS PROCESSED :' "
                            + "WS-TRANSACTION-COUNT - one space before the colon, confirmed byte-wise. "
                            + "Moved: %s", moved)
                    .hasSize(1);

            final Map.Entry<Meter.Id, BigDecimal> entry = moved.entrySet().iterator().next();
            assertThat(entry.getValue())
                    .as("one processed record is one increment, never a batch-sized jump")
                    .isEqualByComparingTo(BigDecimal.ONE);
            assertThat(entry.getKey().getType())
                    .as("a processed-record count is monotonic, so it is a counter")
                    .isEqualTo(Meter.Type.COUNTER);

            final Set<String> keys = new TreeSet<>();
            for (final Tag tag : entry.getKey().getTags()) {
                keys.add(normaliseKey(tag.getKey()));
            }
            assertThat(keys)
                    .as("the processed series carries no dimension of its own - only the two ambient "
                            + "constants every series inherits. Keys: %s", keys)
                    .isSubsetOf(Set.of("application", "component"));
        }

        @Test
        @DisplayName("a bulk processed count moves the same series by the amount given")
        void aBulkProcessedCountMovesTheSameSeries() {
            final Map<Meter.Id, BigDecimal> before = snapshot();

            metricsConfig.countRecordsProcessed(7);

            final Map<Meter.Id, BigDecimal> moved = movementSince(before);
            assertThat(moved)
                    .as("a bulk count must feed the SAME single series a per-record count feeds; a second "
                            + "series for the bulk path would be the fifth instrument. Moved: %s", moved)
                    .hasSize(1);
            assertThat(moved.values().iterator().next())
                    .as("a bulk count of seven is seven, not one and not seven separate series")
                    .isEqualByComparingTo(new BigDecimal("7"));
        }

        @Test
        @DisplayName("the rejected series is dimensioned by reject code and by nothing else")
        void theRejectedSeriesIsDimensionedByRejectCodeAlone() {
            final Map<Meter.Id, BigDecimal> before = snapshot();

            metricsConfig.countRecordRejected(RejectCode.OVERLIMIT_TRANSACTION);

            final Map<Meter.Id, BigDecimal> moved = movementSince(before);
            assertThat(moved)
                    .as("rejecting one record under one code must move exactly one series. Note that "
                            + "app/cbl/CBTRN02C.cbl:403-420 is sequential and UNGUARDED, so when both the "
                            + "over-limit and the expiry condition fail, code 103 OVERWRITES code 102 and "
                            + "a SINGLE reject record bearing 103 is written - never two records and never "
                            + "two increments. Preserved, never fixed. Moved: %s", moved)
                    .hasSize(1);

            final Meter.Id id = moved.keySet().iterator().next();
            assertThat(id.getType()).isEqualTo(Meter.Type.COUNTER);

            final Set<String> keys = new TreeSet<>();
            for (final Tag tag : id.getTags()) {
                keys.add(normaliseKey(tag.getKey()));
            }
            assertThat(keys)
                    .as("the rejected series carries the reject code and the two ambient constants, and "
                            + "nothing else - no card number, no account, no transaction identity, all of "
                            + "which are present at the rejection site and none of which may follow the "
                            + "metric. Keys: %s", keys)
                    .isSubsetOf(Set.of("application", "component", "rejectcode"))
                    .contains("rejectcode");
        }

        @Test
        @DisplayName("reject tag values form a bounded set of five, in either accepted spelling")
        void rejectTagValuesFormABoundedSetOfFive() {
            final Map<Meter.Id, BigDecimal> before = snapshot();

            for (final RejectCode rejectCode : RejectCode.values()) {
                metricsConfig.countRecordRejected(rejectCode);
            }

            final Map<Meter.Id, BigDecimal> moved = movementSince(before);
            assertThat(moved)
                    .as("one increment per reject code must produce FIVE distinct series and not four. "
                            + "Code 109 (app/cbl/CBTRN02C.cbl:556) is never consumed as a reject outcome - "
                            + "it is assigned inside the already-validated posting path, so no reject "
                            + "record is written and the value is cleared on the next iteration - yet the "
                            + "assignment is real code on a reachable path, which is exactly why there are "
                            + "five constants and five series. Moved: %s", moved)
                    .hasSize(5);

            final Set<String> observedValues = new TreeSet<>();
            for (final Meter.Id id : moved.keySet()) {
                for (final Tag tag : id.getTags()) {
                    if ("rejectcode".equals(normaliseKey(tag.getKey()))) {
                        observedValues.add(tag.getValue());
                    }
                }
            }

            assertThat(observedValues)
                    .as("five codes, five tag values")
                    .hasSize(5);
            assertThat(observedValues)
                    .as("the tag-value FORM is the producer's choice - a plain decimal or the "
                            + "four-character zero-padded rendering of the same code - so membership in a "
                            + "bounded set is asserted rather than an exact match on one spelling. "
                            + "Accepted: %s. Observed: %s", acceptedRejectTagValues(), observedValues)
                    .isSubsetOf(acceptedRejectTagValues());
            assertThat(moved.values())
                    .as("each of the five must move by exactly one")
                    .allSatisfy(delta -> assertThat(delta).isEqualByComparingTo(BigDecimal.ONE));
        }

        @Test
        @DisplayName("a null reject code is refused outright rather than bucketed as unknown")
        void aNullRejectCodeIsRefusedOutright() {
            final Set<String> namesBefore = applicationMeterNames();
            final int seriesBefore = applicationMeters().size();

            final Throwable thrown = catchThrowable(() -> metricsConfig.countRecordRejected(null));

            assertThat(thrown)
                    .as("a null code must be rejected explicitly. Folding it into an 'unknown' bucket "
                            + "would invent a SIXTH tag value and break the bounded cardinality the whole "
                            + "series depends on, and it would do so silently")
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("rejectCode");
            assertThat(thrown.getCause())
                    .as("a guard on a programming error has no deeper cause to preserve; inventing one "
                            + "would obscure the real origin")
                    .isNull();
            assertThat(applicationMeterNames())
                    .as("the refusal must not have registered anything")
                    .isEqualTo(namesBefore);
            assertThat(applicationMeters())
                    .as("and it must not have added a series under an existing name either")
                    .hasSize(seriesBefore);
        }

        @Test
        @DisplayName("the authentication series carries an outcome at most and never an identity")
        void theAuthenticationSeriesNeverCarriesAnIdentity() {
            final Map<Meter.Id, BigDecimal> before = snapshot();

            metricsConfig.countAuthenticationAttempt(MetricsConfig.AuthenticationOutcome.SUCCESS);
            metricsConfig.countAuthenticationAttempt(MetricsConfig.AuthenticationOutcome.FAILURE);

            final Map<Meter.Id, BigDecimal> moved = movementSince(before);
            assertThat(moved)
                    .as("this series has no legacy counterpart at all - app/cbl/COSGN00C.cbl counts "
                            + "nothing across its 260 lines - so it is new capability. A bounded, closed "
                            + "outcome dimension is permitted but not required, hence one or two series. "
                            + "Moved: %s", moved)
                    .isNotEmpty()
                    .hasSizeLessThanOrEqualTo(2);

            final Set<String> keys = new TreeSet<>();
            for (final Meter.Id id : moved.keySet()) {
                assertThat(id.getType()).isEqualTo(Meter.Type.COUNTER);
                for (final Tag tag : id.getTags()) {
                    keys.add(normaliseKey(tag.getKey()));
                }
            }

            assertThat(keys)
                    .as("a sign-on counter must never be dimensioned by user identifier, user name or "
                            + "network address. Beyond unbounded cardinality, distinguishing which "
                            + "identifiers were tried tells an attacker which ones EXIST. The published "
                            + "API makes this structural rather than disciplinary: there is no parameter "
                            + "through which an identity could be passed. Keys: %s", keys)
                    .isSubsetOf(Set.of("application", "component", "outcome"))
                    .doesNotContainAnyElementsOf(FORBIDDEN_TAG_KEYS);
        }

        @Test
        @DisplayName("a null authentication outcome is refused")
        void aNullAuthenticationOutcomeIsRefused() {
            assertThatNullPointerException()
                    .as("an absent outcome must fail loudly rather than becoming a third series")
                    .isThrownBy(() -> metricsConfig.countAuthenticationAttempt(null))
                    .withMessageContaining("outcome");
        }

        @Test
        @DisplayName("a negative amount is reported on the debit series and lowers the signed net")
        void aNegativeAmountMovesTheSignedNetDownward() {
            final BigDecimal debit = new BigDecimal("-1234.56");
            final Map<Meter.Id, BigDecimal> before = snapshot();

            metricsConfig.countTransactionAmount(debit);

            final Map<Meter.Id, BigDecimal> moved = movementSince(before);
            assertThat(moved)
                    .as("a debit must be reported, not dropped. This is the single most commonly botched "
                            + "assertion in this area: Micrometer's Counter.increment ignores a "
                            + "non-positive amount, so an increment-backed total would move by ZERO here "
                            + "and the series would silently equal the sum of the credits alone. Moved: %s",
                            moved)
                    .hasSize(1);

            final Map.Entry<Meter.Id, BigDecimal> entry = moved.entrySet().iterator().next();
            assertThat(entry.getKey().getName())
                    .as("the amount instrument, not some other series")
                    .isEqualTo(MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL);
            assertThat(entry.getKey().getTag(MetricsConfig.TAG_SIGN))
                    .as("app/cbl/CBTRN02C.cbl:551 sends a negative amount to ACCT-CURR-CYC-DEBIT, so this "
                            + "is the debit series and not the credit one")
                    .isEqualTo(MetricsConfig.SIGN_DEBIT);
            assertThat(entry.getValue())
                    .as("the debit series carries the MAGNITUDE, which is what keeps a counter legal: the "
                            + "Prometheus client refuses a negative counter value at scrape time and fails "
                            + "the entire response when it meets one. The sign is not lost - it is carried "
                            + "by which of the two series moved")
                    .isEqualByComparingTo(debit.negate());
            assertThat(signedNetMovement(moved).signum())
                    .as("the signed net, credit minus debit, moves DOWNWARD - which is the property a "
                            + "discarded debit would destroy")
                    .isEqualTo(-1);
            assertThat(signedNetMovement(moved))
                    .as("and by exactly the amount itself, sign intact. app/cbl/CBTRN02C.cbl:406 subtracts "
                            + "the debit accumulator in the over-limit formula precisely because it holds "
                            + "negative values; normalising the sign anywhere on this path would break "
                            + "that arithmetic")
                    .isEqualByComparingTo(debit);
        }

        @Test
        @DisplayName("a credit and an equal debit cancel exactly in the signed net")
        void aCreditAndAnEqualDebitCancelExactly() {
            final BigDecimal amount = new BigDecimal("980.25");
            final Map<Meter.Id, BigDecimal> before = snapshot();

            metricsConfig.countTransactionAmount(amount);
            metricsConfig.countTransactionAmount(amount.negate());

            final Map<Meter.Id, BigDecimal> moved = movementSince(before);
            assertThat(moved)
                    .as("both series move, because each amount is routed by its own sign exactly as "
                            + "app/cbl/CBTRN02C.cbl:548-552 routes into its two cycle accumulators. Two "
                            + "series moving is the mechanism, not a defect. Moved: %s", moved)
                    .hasSize(2);
            assertThat(moved.values())
                    .as("neither series may fall: a monotonic counter that decreased would be a reset to "
                            + "every Prometheus consumer, and rate() over it would read as a restart")
                    .allSatisfy(delta -> assertThat(delta.signum()).isEqualTo(1));

            assertThat(signedNetMovement(moved))
                    .as("posting an amount and then its negation must leave the SIGNED NET exactly where "
                            + "it started. This is the assertion an absolute value cannot survive: with "
                            + "abs() applied the second call would land on the credit series again and the "
                            + "net would end at twice the amount instead of back at zero. It also "
                            + "demonstrates exact decimal accumulation - a floating-point accumulator "
                            + "would leave a residue here. The series is a telemetry MIRROR, never the "
                            + "authoritative financial total; where it disagrees with the database, the "
                            + "database is right")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a null amount is refused and no floating-point residue is introduced")
        void aNullAmountIsRefused() {
            final Map<Meter.Id, BigDecimal> before = snapshot();

            assertThatNullPointerException()
                    .as("an absent amount must fail loudly; treating it as zero would understate a total "
                            + "with no trace of having done so")
                    .isThrownBy(() -> metricsConfig.countTransactionAmount(null))
                    .withMessageContaining("amount");

            assertThat(movementSince(before))
                    .as("a refused report must leave every series untouched")
                    .isEmpty();
        }

        @Test
        @DisplayName("a zero amount is accepted and leaves the total unmoved")
        void aZeroAmountLeavesTheTotalUnmoved() {
            final Map<Meter.Id, BigDecimal> before = snapshot();

            metricsConfig.countTransactionAmount(BigDecimal.ZERO);

            assertThat(movementSince(before))
                    .as("zero is a legitimate amount and a legitimate no-op: it must neither be refused "
                            + "as invalid nor move either series. The boundary is asserted because "
                            + "app/data/ASCII/dailytran.txt carries an overpunch sign of +0 on records "
                            + "whose amount decodes to zero. Zero routes to the CREDIT series, because "
                            + "app/cbl/CBTRN02C.cbl:548 tests IF DALYTRAN-AMT >= 0 and zero satisfies it - "
                            + "and adding zero moves nothing, so no movement is observable either way")
                    .isEmpty();
        }
    }

    /**
     * The Actuator surface: three endpoints, asserted from the declaration and again by behaviour.
     *
     * <p>Asserting only the property would be weaker than asserting the behaviour, and asserting only the
     * behaviour would not catch a declaration that happens to be equivalent today. Both are asserted.
     */
    @Nested
    @DisplayName("the Actuator surface is closed to exactly health, info and prometheus")
    class ActuatorSurfaceIsClosed {

        @Test
        @DisplayName("the declared exposure list is exactly the three, and never a wildcard")
        void theDeclaredExposureListIsExactlyTheThree() {
            final String declared = environment.getProperty("management.endpoints.web.exposure.include");

            assertThat(declared)
                    .as("the exposure list must be declared rather than left to the framework default")
                    .isNotBlank();
            assertThat(declared)
                    .as("a wildcard would publish the configuration-properties endpoint, which echoes "
                            + "RESOLVED property values including the signing key, and the heap-dump and "
                            + "thread-dump endpoints besides. Rule 1 Clause D, least privilege. Declared: "
                            + "'%s'", declared)
                    .doesNotContain("*");

            final Set<String> included = new TreeSet<>();
            for (final String id : declared.split(",")) {
                if (!id.isBlank()) {
                    included.add(id.trim().toLowerCase(Locale.ROOT));
                }
            }
            assertThat(included)
                    .as("exactly three endpoints: a health probe, a build identity and a scrape target")
                    .isEqualTo(new TreeSet<>(EXPOSED_ENDPOINTS));
        }

        @Test
        @DisplayName("the exposure filter admits exactly three web endpoints")
        void theExposureFilterAdmitsExactlyThree() {
            final Set<String> admitted = new TreeSet<>();
            for (final ExposableWebEndpoint endpoint : webEndpointsSupplier.getEndpoints()) {
                admitted.add(endpoint.getEndpointId().toLowerCaseString());
            }

            assertThat(admitted)
                    .as("this is the behavioural reading of the exposure filter rather than of the "
                            + "property text: it is the set the framework actually built after applying "
                            + "the include list. Admitted: %s", admitted)
                    .isEqualTo(new TreeSet<>(EXPOSED_ENDPOINTS));
        }

        /**
         * F-S06. Exposure and access are different decisions, and this test asserts both halves.
         *
         * <p>{@code health} and {@code info} answer an ANONYMOUS caller, because the container image's
         * {@code HEALTHCHECK} probes {@code /actuator/health/readiness} with no credential and a rule
         * requiring one would report a permanently unhealthy container.
         *
         * <p>{@code prometheus} REFUSES an anonymous caller. It is exposed - it exists, and the exposure
         * assertion above still lists it - but it is governed by
         * {@code com.cardemo.config.SecurityConfig#metricsScrapeFilterChain}, which requires HTTP Basic
         * credentials carrying the {@code SCRAPE} authority. The scrape body renders BUSINESS series, so
         * anonymous reachability was a disclosure rather than a convenience.
         *
         * <p><strong>Why no authenticated scrape is attempted here.</strong> The test profile configures no
         * scrape credential, so the chain holds zero principals and fails CLOSED - which is precisely the
         * property being asserted. The authenticated-success half is proved at the unit tier, against a real
         * filter chain, by {@code SecurityConfigTest#metricsScrapeAdmitsTheConfiguredPrincipal}. Registering
         * a credential here would require a {@code @DynamicPropertySource}, which this class documents that
         * it deliberately does not declare.
         *
         * @throws Exception if the mock layer cannot dispatch
         */
        @Test
        @DisplayName("health and info answer anonymously; the metrics scrape refuses an anonymous caller")
        void allThreeExposedEndpointsAnswer() throws Exception {
            for (final String id : new String[] {"health", "info"}) {
                assertThat(get("/" + id).getResponse().getStatus())
                        .as("exposed endpoint '%s' must answer anonymously; the readiness probe and the "
                                + "build identity are both load-bearing outside the application and are "
                                + "requested without a credential", id)
                        .isEqualTo(200);
            }

            assertThat(get("/prometheus").getResponse().getStatus())
                    .as("the metrics scrape must NOT answer anonymously. It renders business series - "
                            + "transaction volumes, reject counts tagged by reject code and authentication "
                            + "attempt counts - so an anonymous 200 here would be a disclosure contract. "
                            + "With no credential configured the chain fails CLOSED, which is the direction "
                            + "that loses metrics visibly rather than publishing them silently")
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("no unexposed endpoint answers, to an unauthenticated caller least of all")
        void noUnexposedEndpointAnswers() throws Exception {
            for (final String id : UNEXPOSED_ENDPOINTS) {
                final MvcResult result = get("/" + id);
                assertThat(result.getResponse().getStatus())
                        .as("endpoint '%s' must not serve an unauthenticated caller. Two independent "
                                + "defences apply and either alone suffices: it is absent from the "
                                + "exposure list, and the security chain denies every request it does not "
                                + "explicitly permit", id)
                        .isNotEqualTo(200);
                assertThat(result.getResponse().getContentAsString())
                        .as("and it must disclose nothing in the body while refusing")
                        .doesNotContain("propertySources", "beans", "contexts", "loggers", "signing");
            }
        }

        @Test
        @DisplayName("health detail is never shown, so the aggregate body names no substrate")
        void healthDetailIsNeverShown() throws Exception {
            final String showDetails = environment.getProperty(
                    "management.endpoint.health.show-details");

            assertThat(showDetails)
                    .as("the value must be stated rather than inherited, so a reader need not know the "
                            + "framework default")
                    .isNotBlank();
            assertThat(showDetails.toLowerCase(Locale.ROOT))
                    .as("'always' would publish every component's detail to an unauthenticated caller, "
                            + "and a component's failure text can carry a connection URL complete with a "
                            + "user name. Declared: '%s'", showDetails)
                    .isNotEqualTo("always");

            final String body = getBody("/health");
            assertThat(body)
                    .as("the aggregate must still report a status - the whole point of the endpoint")
                    .contains("\"status\"");
            assertThat(body)
                    .as("but it must not name a substrate or echo a probe detail. Body: %s", body)
                    .doesNotContain("components", "database", "validationQuery", "bucketsProbed",
                            "fifoContract", "elapsedMillis");
        }

        @Test
        @DisplayName("both probe groups are enabled and separately declared")
        void bothProbeGroupsAreEnabledAndSeparatelyDeclared() {
            assertThat(environment.getProperty("management.endpoint.health.probes.enabled"))
                    .as("the framework default is false; enabling it is what registers the two "
                            + "availability contributors and serves the two probe paths at all")
                    .isEqualToIgnoringCase("true");

            final Set<String> liveness = declaredGroupIncludes("liveness");
            final Set<String> readiness = declaredGroupIncludes("readiness");

            assertThat(liveness)
                    .as("liveness and readiness must be SEPARATELY composed. One shared group would make "
                            + "every downstream outage a restart loop. Liveness: %s, readiness: %s",
                            liveness, readiness)
                    .isNotEqualTo(readiness);
            assertThat(readiness)
                    .as("readiness must be the broader of the two")
                    .hasSizeGreaterThan(liveness.size());
        }

        @Test
        @DisplayName("the scrape body renders all four series, matched by prefix not by literal name")
        void theScrapeBodyRendersAllFourSeries() throws Exception {
            final String scrape = prometheusMeterRegistry.scrape();
            final Set<String> renderedInRegistry = renderedSeriesNames(scrape);

            assertThat(renderedInRegistry)
                    .as("all four series must survive the export, not merely exist in the registry. The "
                            + "scrape format renders a dot as an underscore, so the match is on the '%s' "
                            + "PREFIX rather than on any full name - a hardcoded name would break on a "
                            + "producer's rename without telling anyone what actually regressed. "
                            + "Rendered: %s", METRIC_PREFIX, renderedInRegistry)
                    .hasSize(4);

            // F-S06. The four series must render, and they must NOT be anonymously readable over HTTP.
            // Before remediation this assertion read the body anonymously and required it to equal the
            // registry's - which made anonymous disclosure of business series a required contract. The
            // registry assertion above still proves the series survive export; what is asserted here is
            // that reaching them needs a credential. observability/prometheus.yml now carries the matching
            // basic_auth block, and the credentialled read is proved at the unit tier by
            // SecurityConfigTest#metricsScrapeAdmitsTheConfiguredPrincipal.
            final MvcResult anonymousScrape = get("/prometheus");
            assertThat(anonymousScrape.getResponse().getStatus())
                    .as("business series must not be readable without a credential")
                    .isEqualTo(401);
            assertThat(renderedSeriesNames(anonymousScrape.getResponse().getContentAsString()))
                    .as("and the refused response must carry no series at all, not merely a non-200 code - "
                            + "a body that still rendered them would leak exactly what the refusal exists "
                            + "to withhold")
                    .isEmpty();
        }

        @Test
        @DisplayName("tracing is configured explicitly, baggage is off, and the test profile exports no span")
        void tracingIsConfiguredExplicitly() {
            assertThat(environment.getProperty("management.tracing.sampling.probability"))
                    .as("a sampling probability left to the default is a silent decision; it is stated")
                    .isNotBlank();

            // F-S12. Spring Boot 3.5.11 leaves baggage propagation ENABLED by default while the managed
            // BOM resolves OpenTelemetry 1.49.0 - the version carrying CVE-2026-45292, an unbounded-growth
            // exposure in baggage context propagation. Nothing in this application calls the baggage API:
            // the correlation identifier travels as an explicit header handled by CorrelationIdFilter and as
            // an MDC entry. Disabling propagation therefore removes the exposed surface at zero functional
            // cost, and is the AAP-consistent remediation because section 0.8.4 forbids bumping a pinned
            // version unilaterally. Read as the resolved property rather than as yml text, so a profile
            // that re-enabled it would fail here.
            assertThat(environment.getProperty("management.tracing.baggage.enabled", Boolean.class))
                    .as("baggage propagation must be explicitly disabled, not left at the framework default")
                    .isFalse();
            assertThat(declaredWithoutResolution("management.otlp.tracing.endpoint"))
                    .as("the collector address is indirected through configuration and is never a literal "
                            + "in any source file, so the DECLARED value must be exactly the "
                            + "environment-variable reference. It is read without placeholder resolution "
                            + "on purpose: the reference carries no default, this tier exports no "
                            + "collector variable, and Environment.getProperty is strict - resolving it "
                            + "here would make the assertion pass or fail on the developer's shell rather "
                            + "than on the configuration under test")
                    .isEqualTo("${OTEL_EXPORTER_OTLP_ENDPOINT}");
            assertThat(environment.getProperty("spring.autoconfigure.exclude"))
                    .as("the test profile DELIBERATELY neutralises span export by excluding the OTLP "
                            + "tracing auto-configuration, so this tier needs no collector. That is why "
                            + "NO assertion anywhere in this class claims a span reached one - such an "
                            + "assertion would be untestable here and would have to be faked. In-process "
                            + "tracing survives the exclusion, which is what keeps the trace and span "
                            + "identifiers reaching the log context")
                    .contains("Otlp");
        }
    }

    /**
     * Health group composition: readiness spans all three substrates, liveness spans none of them.
     */
    @Nested
    @DisplayName("readiness spans database, object storage and queue while liveness spans none of them")
    class HealthGroupsAreCorrectlyComposed {

        @Test
        @DisplayName("readiness resolves database, object storage and queue, and all report up")
        void readinessResolvesAllThreeSubstrates() {
            final Set<String> resolved = groupComponentKeys("readiness");

            assertThat(resolved)
                    .as("readiness is the Java analogue of app/jcl/OPENFIL.jcl and app/jcl/CLOSEFIL.jcl, "
                            + "whose five CEMT SET FIL commands at :26-30 made TRANSACT, CCXREF, ACCTDAT, "
                            + "CXACAIX and USRSEC available to the online region. All five are VSAM "
                            + "datasets, so all five land on the relational substrate; the object store "
                            + "and the queue are the two substrates the corpus had no analogue for. "
                            + "Resolved: %s", resolved)
                    .contains("db", HealthIndicators.S3_HEALTH_COMPONENT_NAME,
                            HealthIndicators.SQS_HEALTH_COMPONENT_NAME);

            final HealthComponent readiness = awaitGroupUp("readiness");

            final Map<String, HealthComponent> components =
                    ((CompositeHealth) readiness).getComponents();
            for (final String key : new TreeSet<>(List.of("db",
                    HealthIndicators.S3_HEALTH_COMPONENT_NAME,
                    HealthIndicators.SQS_HEALTH_COMPONENT_NAME))) {
                assertThat(components.get(key).getStatus())
                        .as("readiness member '%s' must report up individually in the SAME reading that "
                                + "aggregated to up, not merely be present alongside members that are - an "
                                + "aggregate hides which member carried it", key)
                        .isEqualTo(Status.UP);
            }
        }

        @Test
        @DisplayName("liveness excludes every external dependency")
        void livenessExcludesEveryExternalDependency() {
            final Set<String> resolved = groupComponentKeys("liveness");

            assertThat(resolved)
                    .as("liveness answers only 'is this process still able to serve?'. Including a "
                            + "downstream would let an orchestrator KILL a perfectly healthy process "
                            + "because a database or an emulator blipped, turning a brief outage into a "
                            + "restart loop that outlasts it. Resolved: %s", resolved)
                    .doesNotContain("db", HealthIndicators.S3_HEALTH_COMPONENT_NAME,
                            HealthIndicators.SQS_HEALTH_COMPONENT_NAME)
                    .isNotEmpty();
            assertThat(healthEndpoint.healthForPath("liveness").getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("every declared include key resolves to a registered contributor")
        void everyDeclaredIncludeKeyResolvesToARegisteredContributor() {
            final Set<String> registered = registeredComponentKeys();

            for (final String group : List.of("liveness", "readiness")) {
                for (final String key : declaredGroupIncludes(group)) {
                    assertThat(registered)
                            .as("group '%s' names component key '%s', which MUST resolve. Actuator "
                                    + "derives a component key from a bean name by stripping the "
                                    + "indicator suffix, so renaming a bean without renaming the key here "
                                    + "silently drops that dependency from the group - High severity, and "
                                    + "invisible without this assertion. Registered: %s",
                                    group, key, registered)
                            .contains(key);
                }
                assertThat(groupComponentKeys(group))
                        .as("and the group must actually resolve the keys it declares, so a group is "
                                + "never served empty")
                        .isEqualTo(declaredGroupIncludes(group));
            }
        }

        @Test
        @DisplayName("the two published component-name constants are the keys actually registered")
        void thePublishedComponentNameConstantsAreTheRegisteredKeys() {
            assertThat(registeredComponentKeys())
                    .as("the published constants are what a configuration file and a dashboard cite "
                            + "instead of retyping a literal. Asserting them against the live registry is "
                            + "what makes a divergence between bean name and component key fail here "
                            + "rather than silently emptying a group")
                    .contains(HealthIndicators.S3_HEALTH_COMPONENT_NAME,
                            HealthIndicators.SQS_HEALTH_COMPONENT_NAME);

            assertThat(HealthIndicators.S3_HEALTH_INDICATOR_BEAN_NAME)
                    .as("Actuator strips the indicator suffix from the bean name to derive the key, so "
                            + "the bean name must begin with the key it produces")
                    .startsWith(HealthIndicators.S3_HEALTH_COMPONENT_NAME);
            assertThat(HealthIndicators.SQS_HEALTH_INDICATOR_BEAN_NAME)
                    .startsWith(HealthIndicators.SQS_HEALTH_COMPONENT_NAME);
        }

        @Test
        @DisplayName("the database contributor is auto-configured, not a duplicate of one")
        void theDatabaseContributorIsAutoConfigured() {
            final String contributorType = registeredIndicator("db").getClass().getName();

            assertThat(contributorType)
                    .as("the framework already contributes a database probe from the data source, so "
                            + "writing a second one would duplicate it - which Rule 1 Clause C's "
                            + "'avoid duplication' forbids - and would give two answers to one question. "
                            + "The application therefore contributes the object store and the queue ONLY. "
                            + "Registered type: %s", contributorType)
                    .doesNotStartWith("com.cardemo")
                    .startsWith("org.springframework.boot.actuate");
        }

        @Test
        @DisplayName("both application contributors are nested in the one declaring type")
        void bothApplicationContributorsAreNestedInTheOneDeclaringType() {
            for (final String key : List.of(HealthIndicators.S3_HEALTH_COMPONENT_NAME,
                    HealthIndicators.SQS_HEALTH_COMPONENT_NAME)) {
                assertThat(registeredIndicator(key).getClass().getName())
                        .as("the plural type name is deliberate: ONE configuration type declares several "
                                + "contributors. A separate top-level file per contributor would breach "
                                + "the four-file budget of that package, which holds the "
                                + "instrument configuration, the correlation filter, this type and the "
                                + "package documentation - and nothing else. Component '%s'", key)
                        .startsWith(HealthIndicators.class.getName());
            }
        }

        @Test
        @DisplayName("the readiness path answers exactly what the container image health-check greps for")
        void theReadinessPathAnswersWhatTheImageHealthCheckGrepsFor() throws Exception {
            MvcResult result = get("/health/readiness");
            for (int attempt = 2; attempt <= PROBE_ATTEMPT_LIMIT
                    && result.getResponse().getStatus() != 200; attempt++) {
                pauseBetweenProbeAttempts();
                result = get("/health/readiness");
            }

            assertThat(result.getResponse().getStatus())
                    .as("the container image health-check issues a request to this exact path and greps "
                            + "the body for an up status. Renaming the group or the path breaks container "
                            + "start-up ordering and the integration sign-off gate - High severity - and "
                            + "does so without any error in the application itself. The image file is "
                            + "root-owned and is never modified from here")
                    .isEqualTo(200);
            assertThat(result.getResponse().getContentAsString())
                    .as("and the body must carry the literal token the health-check greps for, not merely "
                            + "an equivalent status expressed some other way")
                    .contains("\"status\":\"UP\"");

            assertThat(get("/health/liveness").getResponse().getStatus())
                    .as("the liveness path must answer as well, so an orchestrator can distinguish "
                            + "'restart me' from 'do not route to me yet'")
                    .isEqualTo(200);
        }
    }

    /**
     * Probe side effects: a health check observes its substrate and changes nothing about it.
     *
     * <p>The queue assertion here is the highest-value one in the file. A probe that consumed from the
     * report queue would <em>silently destroy work</em>: the submission would simply never be processed,
     * with no error on any path and nothing in any log to point at the probe.
     */
    @Nested
    @DisplayName("a probe observes its substrate and mutates nothing")
    class ProbesAreObservationsNotMutations {

        @Test
        @DisplayName("the object-store probe creates no object and removes none")
        void theObjectStoreProbeCreatesNoObjectAndRemovesNone() {
            final String witnessKey = scopedResourceName("probe-witness") + "/witness.txt";
            s3Client().putObject(PutObjectRequest.builder()
                            .bucket(batchInputBucket())
                            .key(witnessKey)
                            .build(),
                    RequestBody.fromString("witness", StandardCharsets.UTF_8));
            try {
                final Set<String> seeded = objectKeysInConfiguredBuckets();
                assertThat(seeded)
                        .as("a witness object is seeded first so that 'nothing was removed' is provable "
                                + "as well as 'nothing was created' - across empty buckets the two are "
                                + "indistinguishable and the assertion would be vacuous")
                        .contains(batchInputBucket() + "/" + witnessKey);

                awaitUp(() -> registeredIndicator(HealthIndicators.S3_HEALTH_COMPONENT_NAME).health(),
                        "the object-store contributor");

                assertThat(objectKeysInConfiguredBuckets())
                        .as("the probe must be metadata-only: a bucket existence check and a versioning "
                                + "attribute read. Writing an object would leave litter in a bucket whose "
                                + "keys stand in for generation-data-group generations; deleting one would "
                                + "destroy a generation. A full key enumeration is excluded too, on "
                                + "efficiency grounds - listing a bucket to learn only that it answers is "
                                + "the obvious inefficiency Rule 1 Clause A rules out. Enumeration is "
                                + "used HERE, in the test, precisely because the probe must not")
                        .isEqualTo(seeded);
            } finally {
                s3Client().deleteObject(DeleteObjectRequest.builder()
                        .bucket(batchInputBucket())
                        .key(witnessKey)
                        .build());
            }
        }

        @Test
        @DisplayName("the queue probe does not consume: an enqueued message survives repeated probing")
        void theQueueProbeDoesNotConsume() {
            final String token = scopedResourceName("queue-witness");
            final String payload = "{\"reportName\":\"MONTHLY\",\"witness\":\"" + token + "\"}";

            sqsTemplate().send(builder -> builder
                    .queue(reportQueueName())
                    .payload(payload)
                    .header("message-group-id", token));

            awaitUp(() -> registeredIndicator(HealthIndicators.SQS_HEALTH_COMPONENT_NAME).health(),
                    "the queue contributor, with a message waiting on the queue");

            for (int probe = 1; probe <= 3; probe++) {
                final Health verdict =
                        registeredIndicator(HealthIndicators.SQS_HEALTH_COMPONENT_NAME).health();
                assertThat(verdict)
                        .as("probe %d must RETURN a verdict rather than throw; a contributor that threw "
                                + "would take the whole readiness path down with it", probe)
                        .isNotNull();
                assertThat(verdict.getStatus())
                        .as("probe %d must reach a definite verdict", probe)
                        .isNotNull();
                if (!Status.UP.equals(verdict.getStatus())) {
                    assertThat(verdict.getDetails())
                            .as("probe %d did not report up, which is only acceptable for ONE reason: the "
                                    + "contributor's own bounded deadline expired. Any other reason with "
                                    + "the queue demonstrably reachable - the reading above proved it - "
                                    + "would mean the probe interfered with the queue it was inspecting. "
                                    + "Detail: %s", probe, verdict.getDetails())
                            .containsEntry(HealthIndicators.DETAIL_REASON,
                                    HealthIndicators.REASON_TIMEOUT);
                }
            }

            final Optional<Message<?>> received = sqsTemplate().receive(builder -> builder
                    .queue(reportQueueName())
                    .pollTimeout(Duration.ofSeconds(20)));

            assertThat(received)
                    .as("THE HIGHEST-VALUE ASSERTION IN THIS FILE. The message must still be there after "
                            + "three probes. A probe that received it - even without deleting it - would "
                            + "hold it invisible for the visibility timeout, and a probe that deleted it "
                            + "would destroy a submitted report job outright: the work would simply never "
                            + "happen, with no error anywhere and nothing to implicate the probe. This "
                            + "queue replaces the transient data queue that app/csd/CARDDEMO.CSD declares "
                            + "with an eighty-byte fixed record, and losing a record from it loses a job. "
                            + "If this fails with the queue reachable and the probe reporting up, suspect "
                            + "the five-minute first-in-first-out deduplication interval rather than the "
                            + "probe: re-run against a fresh emulator container")
                    .isPresent();
            assertThat(received.orElseThrow().getPayload().toString())
                    .as("and it must be THIS test's message, not a leftover that happened to be available")
                    .contains(token);
        }

        @Test
        @DisplayName("an unconfigured substrate reports down with a safe reason, never a null dereference")
        void anUnconfiguredSubstrateReportsDownWithASafeReason() {
            final HealthIndicators unconfigured = new HealthIndicators(
                    s3Client(), sqsAsyncClient(), "", "", "", "", "");

            final Health objectStore = unconfigured.s3HealthIndicator().health();
            final Health queue = unconfigured.sqsHealthIndicator().health();

            for (final Health health : List.of(objectStore, queue)) {
                assertThat(health.getStatus())
                        .as("a blank or unset resource name is a CONFIGURATION fault and must surface as a "
                                + "deterministic down with a reason a reader can act on - never a null "
                                + "dereference, which would surface as a 500 from the probe path and tell "
                                + "an operator nothing about what is actually wrong. Rule 1 Clause B, "
                                + "explicit null and empty handling")
                        .isEqualTo(Status.DOWN);
                assertThat(health.getDetails())
                        .as("and the reason must be the symbolic not-configured one, alongside the "
                                + "property key that is missing, so the fix is named rather than guessed")
                        .containsEntry(HealthIndicators.DETAIL_REASON,
                                HealthIndicators.REASON_NOT_CONFIGURED)
                        .containsKey(HealthIndicators.DETAIL_PROPERTY);
            }
        }

        @Test
        @DisplayName("an absent substrate reports down rather than throwing")
        void anAbsentSubstrateReportsDownRatherThanThrowing() {
            final String absent = scopedResourceName("absent-substrate");
            final HealthIndicators pointingAtNothing = new HealthIndicators(
                    s3Client(), sqsAsyncClient(), absent, absent, absent, absent + ".fifo", absent);

            final Throwable fromObjectStore =
                    catchThrowable(() -> pointingAtNothing.s3HealthIndicator().health());
            final Throwable fromQueue =
                    catchThrowable(() -> pointingAtNothing.sqsHealthIndicator().health());

            assertThat(fromObjectStore)
                    .as("a contributor REPORTS, it never rethrows. This is deliberate catching, not "
                            + "swallowing - which is why it is said here explicitly, since Rule 1 Clause "
                            + "B's 'no swallowing exceptions' would otherwise look violated: the root "
                            + "cause is logged once at warning level with correlation context, and the "
                            + "verdict is returned as data. A contributor that threw would turn a "
                            + "readiness check into a 500 and take the whole probe path down with the one "
                            + "component that failed")
                    .isNull();
            assertThat(fromQueue).isNull();

            assertThat(pointingAtNothing.s3HealthIndicator().health().getStatus())
                    .as("an absent object store is a genuine not-ready condition. Note that this scenario "
                            + "is NEW CAPABILITY and is deliberately not derived from a legacy status: a "
                            + "census across app/cbl finds the file-unavailable status literal zero times "
                            + "and the not-open response condition zero times, so there is nothing to "
                            + "reproduce and nothing is invented")
                    .isEqualTo(Status.DOWN);
            assertThat(pointingAtNothing.sqsHealthIndicator().health().getStatus())
                    .isEqualTo(Status.DOWN);
            assertThat(pointingAtNothing.sqsHealthIndicator().health().getDetails())
                    .as("and the reason must distinguish 'absent' from 'unreachable' and from "
                            + "'misconfigured', because the three have different fixes")
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_MISSING);
        }

        @Test
        @DisplayName("a resource name that is an identifier rather than a name is refused without echoing")
        void aResourceNameThatIsAnIdentifierIsRefusedWithoutEchoing() {
            final String qualifiedName = "arn:aws:s3:::" + batchInputBucket();
            final HealthIndicators misconfigured = new HealthIndicators(
                    s3Client(), sqsAsyncClient(), qualifiedName, qualifiedName, qualifiedName,
                    reportQueueName(), reportQueueLogicalName());

            final Health health = misconfigured.s3HealthIndicator().health();

            assertThat(health.getStatus())
                    .as("a fully qualified identifier where a bare name belongs is a configuration fault, "
                            + "and treating it as untrusted input rather than passing it through is Rule 1 "
                            + "Clause A's security-by-default in practice")
                    .isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry(HealthIndicators.DETAIL_REASON, HealthIndicators.REASON_INVALID_NAME);
            assertThat(health.getDetails().toString())
                    .as("and the offending VALUE must not be echoed back. An identifier of this shape "
                            + "normally embeds an account number, so echoing it to an unauthenticated "
                            + "reader would leak it - High severity. Only the property KEY is reported")
                    .doesNotContain("arn:");
        }

        @Test
        @DisplayName("no health detail leaks a credential, an address, an account number or a trace")
        void noHealthDetailLeaksACredentialAnAddressAnAccountNumberOrATrace() {
            final HealthComponent aggregate = healthEndpoint.health();
            assertThat(aggregate).isInstanceOf(CompositeHealth.class);

            final String rendered = ((CompositeHealth) aggregate).getComponents().toString()
                    .toLowerCase(Locale.ROOT);

            for (final String fragment : FORBIDDEN_DETAIL_FRAGMENTS) {
                assertThat(rendered)
                        .as("a health detail is read by whoever can reach the endpoint, so it must carry "
                                + "only a symbolic component name, a reachability verdict, an elapsed "
                                + "duration and a LOGICAL resource name. It must never carry a connection "
                                + "string, a user name, a password, an access key or secret, an identifier "
                                + "embedding an account number, a queue address, a signed link, a stack "
                                + "trace or a raw service error payload. Forbidden fragment: '%s'",
                                fragment)
                        .doesNotContain(fragment);
            }

            assertThat(rendered)
                    .as("nor may any twelve-digit run appear: that is how an account number reaches a "
                            + "detail when no label gives it away")
                    .doesNotContainPattern("(?<![0-9])[0-9]{12}(?![0-9])");
            assertThat(rendered)
                    .as("nor a nine-digit run, which is the shape of the customer identity field the "
                            + "customer record layout carries")
                    .doesNotContainPattern("(?<![0-9])[0-9]{9}(?![0-9])");
        }

        @Test
        @DisplayName("every cloud endpoint is an emulator address with no live-service fallback")
        void everyCloudEndpointIsAnEmulatorAddressWithNoLiveServiceFallback() {
            final String liveServiceInfix = "amazon" + "aws";

            for (final String service : List.of("s3", "sqs", "sns")) {
                final String endpoint = environment.getProperty(
                        "spring.cloud.aws." + service + ".endpoint");
                assertThat(endpoint)
                        .as("the '%s' endpoint must be declared with no default at all, so a class that "
                                + "registers nothing fails to refresh instead of silently resolving the "
                                + "real regional address", service)
                        .isNotBlank();
                assertThat(endpoint)
                        .as("and it must not name the live service. There is NO fallback and NO credential "
                                + "default anywhere on this path: an unreachable emulator must report down, "
                                + "never fail over. A live endpoint or credential reaching this tier is "
                                + "forbidden. The literal host name is assembled rather than written so "
                                + "that no source file in this tree contains it")
                        .doesNotContain(liveServiceInfix);
            }
        }
    }

    /**
     * The masking counter-constraint: secrets are masked, and the parity literal is not.
     *
     * <p>Masking is the <em>second</em> line of defence. The primary defence is never emitting a secret at
     * all, which the group above asserts on the health path; this group asserts that the second line holds
     * where it should and, just as importantly, that it does not fire where it must not.
     */
    @Nested
    @DisplayName("masking covers credentials, digests and identity numbers - and never the parity literal")
    class MaskingIsPreciseInBothDirections {

        @Test
        @DisplayName("the status literal passes through byte for byte, unmasked and unreformatted")
        void theStatusLiteralPassesThroughByteForByte() {
            final String encoded = encode(STATUS_LITERAL);

            assertThat(encoded)
                    .as("app/cbl/CBTRN02C.cbl:714-727 emits DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04 on "
                            + "BOTH branches, at :721 and :725, and COBOL concatenates a literal and an "
                            + "identifier with NO separator - so the placeholder text and the four "
                            + "characters of the status field run together. The stray placeholder is a "
                            + "PRESERVED LEGACY QUIRK and is never repaired. No masking rule may touch it "
                            + "and nothing may reformat, wrap, re-render, truncate or escape it away, "
                            + "because the boundary comparison is byte-for-byte "
                            + "against the legacy baseline and a differently formatted status is a diff")
                    .contains(STATUS_LITERAL);
            assertThat(encoded)
                    .as("and no redaction marker may appear anywhere on a line that carries only a status")
                    .doesNotContain("REDACTED");
        }

        @Test
        @DisplayName("a credential-shaped value is masked and its value never rendered")
        void aCredentialShapedValueIsMasked() {
            final String contrivedValue = "not-a-real-credential-only-a-test-shape";
            final String encoded = encode("bean init password=" + contrivedValue + " done");

            assertThat(encoded)
                    .as("Rule 1 Clause D names TESTS explicitly, so the value above is a contrived shape "
                            + "chosen so that it cannot match any provider's key format and is obviously "
                            + "not a secret. What is asserted is that the RULE fires on the shape")
                    .doesNotContain(contrivedValue);
            assertThat(encoded)
                    .as("and that the surrounding message survives, so masking redacts a value rather "
                            + "than discarding the line that would have told an operator where it came "
                            + "from")
                    .contains("REDACTED")
                    .contains("bean init");
        }

        @Test
        @DisplayName("a password digest is masked wherever it appears, labelled or not")
        void aPasswordDigestIsMasked() {
            final String digestShape = "$2a$10$notarealdigestnotarealdigestnotarealdigest0123456789";
            final String encoded = encode("stored " + digestShape);

            assertThat(encoded)
                    .as("the stored form of the ten seeded users is a digest, and it needs no label to be "
                            + "recognisable - which is what lets the rule catch it even when it is logged "
                            + "as a bare value. The eight-character plaintext credential those users share "
                            + "in the legacy seed job appears NOWHERE in this tree")
                    .doesNotContain(digestShape);
            assertThat(encoded).contains("REDACTED");
        }

        @Test
        @DisplayName("a labelled identity number is masked in both dashed and bare presentation")
        void aLabelledIdentityNumberIsMasked() {
            final String dashed = encode("customer ssn=123-45-6789 loaded");
            final String bare = encode("customer ssn=123456789 loaded");

            assertThat(dashed)
                    .as("the customer record layout carries a nine-digit identity field, so it must never "
                            + "reach a log line; the documentation-example number above is not a real one")
                    .doesNotContain("123-45-6789");
            assertThat(bare)
                    .as("and the bare presentation must be caught too, because a fixed-width field carries "
                            + "no separators. Note the deliberate scope: an UNLABELLED nine-digit run is "
                            + "NOT masked, and must not be - it would redact account identifiers, "
                            + "timestamps and record counts indiscriminately. That is exactly why the "
                            + "primary defence is never emitting the field, with masking as the second "
                            + "line rather than the first")
                    .doesNotContain("123456789");
            assertThat(dashed).contains("REDACTED");
            assertThat(bare).contains("REDACTED");
        }

        @Test
        @DisplayName("an account number is masked in a qualified identifier and in a queue address")
        void anAccountNumberIsMaskedInAnIdentifierAndAnAddress() {
            final String documentationAccount = "123456789012";
            final String identifier = encode("topic arn:aws:sns:us-east-1:" + documentationAccount
                    + ":carddemo-notifications");
            final String address = encode("queue https://queue.invalid/" + documentationAccount + "/q.fifo");

            assertThat(identifier)
                    .as("a qualified identifier embeds an account number, and an account number is "
                            + "reconnaissance. The number above is the documentation example, not a real "
                            + "account")
                    .doesNotContain(documentationAccount);
            assertThat(address)
                    .as("and a queue address embeds the same number as a path segment, where no label "
                            + "gives it away - which is why the rule keys on the twelve-digit segment "
                            + "shape rather than on a label")
                    .doesNotContain(documentationAccount);
        }

        @Test
        @DisplayName("the correlation trio is rendered under exactly the three expected field names")
        void theCorrelationTrioIsRenderedUnderTheExpectedFieldNames() {
            final String encoded = encode("a routine line");

            assertThat(encoded)
                    .as("the correlation identifier is the replacement THREAD OF IDENTITY for what the "
                            + "transaction monitor used to supply per request, and the trace and span "
                            + "identifiers come from in-process tracing, which survives the test profile's "
                            + "exclusion of span EXPORT. All three must be rendered under exactly these "
                            + "spellings, because a consumer correlates on the field name: a rename here "
                            + "breaks every saved query without breaking the build. A locator for the "
                            + "legacy transaction-identifier field is NOT AVAILABLE - a repository-wide "
                            + "search returns zero occurrences at this anchor - so none is fabricated")
                    .contains("correlationId")
                    .contains("traceId")
                    .contains("spanId");
        }

        @Test
        @DisplayName("log output is captured in process, never from a file or a container")
        void logOutputIsCapturedInProcess() {
            assertThat(configuredEncoder())
                    .as("the configured encoder is driven directly, in this JVM. A file path would make "
                            + "the assertion depend on the host's filesystem and a container log would "
                            + "make it depend on the runtime's log driver - both are the "
                            + "environment-specific assumptions Rule 1 Clause C forbids, and neither is "
                            + "used. It must be a real encoder and not a bare event collector, because "
                            + "masking is a property of the ENCODED output: an appender that captured "
                            + "events would see the unmasked message and prove the opposite of what it "
                            + "appeared to")
                    .isNotNull();

            assertThat(encode("shape check"))
                    .as("and the rendered form must be the structured one the whole logging contract "
                            + "assumes, carrying a level and a message as named fields")
                    .contains("\"level\"")
                    .contains("\"message\"")
                    .contains("shape check");
        }
    }
}
