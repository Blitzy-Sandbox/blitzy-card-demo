/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.observability
 * Application : CardDemo
 * Type        : Java package documentation (observability layer)
 * Function    : Documents the com.cardemo.observability package - the
 *               correlation filter, the Micrometer counter definitions
 *               and the composite health indicators. This layer is NEW
 *               CAPABILITY: the frozen corpus has no instrumentation
 *               beyond DISPLAY to SYSOUT and the four-character
 *               9910-DISPLAY-IO-STATUS renderer.
 * Source      : app/cbl/CBTRN02C.cbl:L194,L227-L232 (end-of-run counter
 *                 DISPLAYs and the RETURN-CODE 4 rule)
 *               + app/cbl/CBTRN02C.cbl:L714-L731 (9910-DISPLAY-IO-STATUS)
 *               + app/jcl/OPENFIL.jcl + app/jcl/CLOSEFIL.jcl (CEMT SET
 *                 FIL file availability, replaced by health probes)
 *               + app/csd/CARDDEMO.CSD (8 DEFINE FILE, DEFINE
 *                 TDQUEUE(JOBS)) @ 7756d89
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
 * Correlation, metrics and health for CardDemo - the one layer of this migration that is <strong>new
 * capability rather than translation</strong>.
 *
 * <h2>What it does</h2>
 *
 * <p>The frozen corpus has no instrumentation. The entire observability surface of the legacy system is
 * {@code DISPLAY} to SYSOUT plus the {@code 9910-DISPLAY-IO-STATUS} paragraph that renders a file status as
 * exactly four characters. There is no metric, no trace, no health probe and no per-request identifier
 * anywhere in the 19,254 lines. Everything in this package is therefore <em>designed</em> rather than derived,
 * and it exists because Rule 1 Clause A requires observable, measurable behaviour - not because a COBOL
 * paragraph asked for it.
 *
 * <p>Because it is additive, this package is documented differently from the rest of the tree: each class
 * names the legacy facility it <em>replaces</em> where one existed, and says plainly where none did.
 *
 * <h2>The three classes, all present</h2>
 *
 * <ul>
 *   <li>{@link com.cardemo.observability.CorrelationIdFilter} - generates or accepts a per-request
 *       correlation identifier, places it in the logging context, attaches it to the trace span and
 *       propagates it on outbound cloud-service calls. It stands in for the role {@code EIBTRNID} played as
 *       the per-request thread of identity. Note the precise claim: {@code EIBTRNID} is a CICS-supplied
 *       field and appears <strong>nowhere in {@code app/**}</strong> - zero occurrences - so this is an
 *       additive correlation capability that fills the same role, not a translation of source code that
 *       exists.</li>
 *   <li>{@link com.cardemo.observability.MetricsConfig} - defines the four named Micrometer counters that
 *       replace the legacy end-of-run {@code DISPLAY} statements: records processed, records rejected
 *       <strong>tagged by reject code</strong>, authentication attempts, and total transaction amount. The
 *       reject-code tag is what turns the five reject constants into an operable signal rather than a single
 *       opaque number.</li>
 *   <li>{@link com.cardemo.observability.HealthIndicators} - composite readiness and liveness over
 *       PostgreSQL, S3 object storage and the SQS report queue. This replaces the purpose served by
 *       {@code app/jcl/OPENFIL.jcl} and {@code app/jcl/CLOSEFIL.jcl}, whose {@code CEMT SET FIL} steps made
 *       datasets available to the online region. No COBOL analogue for health <em>probing</em> exists.</li>
 *   </ul>
 *
 * <p>All three types specified for this package exist; nothing here is outstanding. The related
 * {@code ObservabilityConfig} in {@code com.cardemo.config} is still planned, but its absence does not
 * disable anything in this package - these three classes are self-registering.
 *
 * <h2>One legacy output format is a contract</h2>
 *
 * <p>{@code 9910-DISPLAY-IO-STATUS} renders a file status as exactly four characters: when the status is
 * non-numeric or its first byte is {@code '9'}, the first byte is copied through and the second is expanded
 * from a binary field into three digits; otherwise the field is four zeros with the two status characters at
 * positions three and four. <strong>Java must emit the identical four-character rendering</strong>, because
 * the end-to-end gate compares log output against the legacy baseline and a differently formatted status is a
 * diff. That makes this the one place where a log format, not just a log level, is load-bearing.
 *
 * <h2>Masking is a backstop, not the primary control</h2>
 *
 * <p>Credentials, password hashes and social security numbers are masked in log output by rules in
 * {@code src/main/resources/logback-spring.xml}. That masking is a <strong>safety net</strong> and must not be
 * relied on as the mechanism: it matches on configured labels and field names, so a value that reaches a log
 * under an unexpected label, inside a concatenated message, or nested in a serialised object can pass through
 * unmasked. <strong>The primary control is non-emission</strong> - sensitive values are never passed to a
 * logger in the first place. Treating masking as universal is precisely how a card number or a hash reaches an
 * aggregator.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Compile with {@code ./mvnw -B -ntp clean compile}; unit tests with {@code ./mvnw -B -ntp test}; the full
 * gate with {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}. That flag skips only the OWASP
 * vulnerability scan, which needs network access to the vulnerability feed; drop it when the scan is wanted.
 * Compilation runs {@code -Xlint:all -Werror} with {@code failOnWarning} at release 25, so a raw type,
 * unchecked cast or dangling documentation comment here fails the build. An unused import does <em>not</em>,
 * because {@code javac} 25 publishes no {@code unused} lint key - that prohibition is enforced at review.
 *
 * <p>Prerequisites are capabilities rather than paths: a JDK 25 toolchain on {@code PATH} with
 * {@code JAVA_HOME} set, and Maven from the pinned repository wrapper. Load the git-ignored {@code .env} with
 * {@code set -a; . ./.env; set +a} first.
 *
 * <p>To see this layer working end to end, bring up the Compose topology with {@code docker compose up -d} and
 * run under the {@code local} profile. That stack supplies PostgreSQL, LocalStack, a trace collector, a
 * metrics scraper and a dashboard, all provisioned from checked-in files under {@code observability/} - the
 * scrape configuration, the datasource definition and the dashboard definition - so no manual configuration
 * step is needed to get a populated dashboard. Metrics are exposed at {@code /actuator/prometheus} and
 * scraped every fifteen seconds; health is at {@code /actuator/health} with distinct
 * {@code /actuator/health/liveness} and {@code /actuator/health/readiness} groups.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Properties are cited by key, never by line number in {@code application.yml}, because keys are stable and
 * line numbers drift.
 *
 * <ul>
 *   <li>{@code management.endpoints.web.exposure.include} is {@code health,info,prometheus} and nothing else.
 *       Widening it is a least-privilege regression under Rule 1 Clause D.</li>
 *   <li>{@code management.endpoint.health.probes.enabled} is {@code true}, which is <em>not</em> the framework
 *       default, so the liveness and readiness groups exist.</li>
 *   <li>{@code management.endpoint.health.validate-group-membership} is left at its secure default of
 *       {@code true}. This is why naming a health contributor before the class exists makes the application
 *       unbootable.</li>
 *   <li>{@code management.endpoint.health.group.readiness.include} names the substrate contributors;
 *       {@code management.endpoint.health.group.liveness.include} names only {@code livenessState}, so a
 *       database outage takes the instance out of rotation <strong>without</strong> triggering a liveness
 *       restart.</li>
 *   <li>{@code management.tracing.sampling.probability} is {@code 1.0} in the base profile so that every
 *       request and every batch step is traceable during a parity investigation. Production lowers it.</li>
 *   <li>{@code management.otlp.tracing.endpoint} - <strong>no default</strong>, from
 *       {@code OTEL_EXPORTER_OTLP_ENDPOINT}.</li>
 *   <li><strong>No property names a counter or a tag.</strong> A {@code carddemo.metrics.*} block once
 *       mirrored the four counter names and the reject tag key in {@code application.yml}, and it was bound by
 *       nothing: {@code com.cardemo.observability.MetricsConfig} declares all five strings as constants and
 *       binds no property. The copy had already drifted where it mattered - it documented the tag key as
 *       {@code reject-code} while the registered key is {@code reject.code} - so a dashboard author who
 *       trusted it would have grouped on a label that does not exist and seen an empty panel with no error.
 *       The mirror was deleted rather than corrected, because correcting it would have preserved the very
 *       duplication that allowed the drift. {@code MetricsConfig#TAG_REJECT_CODE} is now the only
 *       spelling.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>A dashboard panel is silently empty</dt>
 *   <dd>A metric was renamed, a tag key changed, or a Micrometer base unit was declared on a counter. Each
 *       empties a panel and raises no error. Change the constant and the dashboard definition in the same
 *       commit, and never set a base unit on these counters.</dd>
 *
 *   <dt>{@code /actuator/health/readiness} returns 404</dt>
 *   <dd>{@code management.endpoint.health.probes.enabled} is not {@code true}. The framework default is
 *       {@code false}, so this must be set explicitly - and the container image's health check depends on
 *       that path resolving.</dd>
 *
 *   <dt>The application will not boot after adding a health contributor to a group</dt>
 *   <dd>{@code validate-group-membership} is {@code true}, so a group naming a contributor that does not
 *       exist fails the context. Add the class and the group membership in one change.</dd>
 *
 *   <dt>{@code /actuator/prometheus} returns 404</dt>
 *   <dd>The exposure list does not include {@code prometheus}. Add it there rather than widening the list to
 *       {@code *}.</dd>
 *
 *   <dt>Log lines carry no {@code traceId}, {@code spanId} or {@code correlationId}</dt>
 *   <dd>Either the correlation filter is not in the chain, or work moved to a thread the logging context did
 *       not follow. Batch steps additionally propagate the job instance identifier, which is what makes
 *       per-run object prefixes and per-run logs correlatable.</dd>
 *
 *   <dt>Traces do not reach the collector</dt>
 *   <dd>{@code OTEL_EXPORTER_OTLP_ENDPOINT} is unset or the collector is not up. There is deliberately no
 *       default endpoint.</dd>
 *
 *   <dt>A secret, hash or card number appears in a log line</dt>
 *   <dd>Treat this as a defect in the emitting code, not a gap in the masking rules. Masking is a backstop
 *       that matches on labels and field names; the control is not passing the value to a logger at all.</dd>
 *
 *   <dt>A file status appears in a log with a different shape than the baseline</dt>
 *   <dd>The four-character rendering was not reproduced. See the format contract above - the end-to-end gate
 *       diffs on it.</dd>
 *   </dl>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li>No business logic, no repository access and no security decision belongs here.</li>
 *   <li>No counter may be renamed, retagged or given a base unit without the dashboard definition changing in
 *       the same commit.</li>
 *   <li>No sensitive value may be emitted on the assumption that masking will catch it.</li>
 *   <li>The Actuator exposure list may not be widened, and health group-membership validation may not be
 *       disabled to work around a missing contributor.</li>
 *   <li>Because this layer has no COBOL antecedent, every claim it makes about a legacy facility must either
 *       cite a real artefact under {@code app/**} or state plainly that none exists.</li>
 *   </ul>
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License, Version 2.0</a>
 */

package com.cardemo.observability;
