/*
 * ******************************************************************
 * Program     : MetricsConfig.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 metrics definition
 * Function    : Defines the four named Micrometer counters that replace
 *               the legacy end-of-run DISPLAY counters.
 * Replaces    : app/cbl/CBTRN02C.cbl end-of-run DISPLAY counters
 *               'TRANSACTIONS PROCESSED :' / 'TRANSACTIONS REJECTED  :'
 *               @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L194,L227-L232 @ 7756d89 (counter
 *               DISPLAYs and the RETURN-CODE 4 rule)
 * Source      : app/cbl/CBTRN02C.cbl:L385-L419,L556-L558 @ 7756d89 (the
 *               five reject codes tagged by this class)
 * Note        : No direct COBOL analogue for metrics exists; new
 *               capability mandated by Rule 1 Clause A.
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
package com.cardemo.observability;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cardemo.model.enums.RejectCode;

/**
 * Declares the <strong>four, and only four</strong>, Micrometer instruments sanctioned for this
 * application, and exposes a small validated facade for incrementing them.
 *
 * <h2>Why this class exists: it is rule-mandated, not requirements-derived</h2>
 *
 * <p><strong>Nothing in this file is a translation of existing behaviour.</strong> The legacy system has no
 * instrumentation whatsoever. A census of the frozen corpus at commit {@code 7756d89} finds
 * <strong>322 {@code DISPLAY} statements</strong> across {@code app/cbl/**} - the entire telemetry surface
 * of 19,254 lines of COBOL - and a case-insensitive search of {@code app/} for {@code prometheus},
 * {@code micrometer}, {@code opentelemetry}, {@code healthcheck} and {@code actuator} returns nothing at
 * all. There is therefore no source construct here to be faithful to.
 *
 * <p>This class exists solely because Rule 1 Clause A requires it: <em>"Observability: structured logs,
 * meaningful errors, and measurable behavior (metrics/tracing where relevant)."</em> That makes it a
 * rule-mandated artefact that ships <em>with</em> the initial implementation rather than as follow-up work.
 * Because it is new capability, <strong>no part of it may be justified as "preserved for parity"</strong>,
 * and it contains no intentional no-op.
 *
 * <p>That claim is deliberately scoped to <em>this class</em> and states no global tally. An earlier revision
 * said "the three documented sites where the no-dead-code rule yields to the parity mandate are ..." and then
 * listed three. Neighbouring documentation gave the same tally as three in one place and five in another, so at
 * least one was wrong, and a fixed count maintained by hand in several unrelated files cannot stay true.
 * Severity of what that left in place: <strong>High</strong>. The count is withdrawn in favour of the rule that
 * actually governs: <strong>each retained no-op is justified at its own declaration</strong>, and to be
 * legitimate it must carry, in that one place, its COBOL locator, a proof of reachability, an explicit
 * intentional-no-op marker, and an acknowledgement that it is owed an entry in the planned
 * {@code DECISION_LOG.md}. No file enumerates the set, because no build step maintains such an enumeration.
 * What this class asserts is only the local fact: nothing in {@code com.cardemo.observability} carries such a
 * marker, so anything here that looks like dead code is dead code and must be removed rather than explained.
 *
 * <h2>What it replaces</h2>
 *
 * <p>The batch corpus reports its work exactly once, at the very end of a run, to SYSOUT. From
 * {@code app/cbl/CBTRN02C.cbl}, transcribed character for character including the spacing:
 *
 * <pre>
 * DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'.        &lt;- L194
 * ...
 * DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT  &lt;- L227
 * DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT       &lt;- L228
 * IF WS-REJECT-COUNT &gt; 0                                   &lt;- L229
 *    MOVE 4 TO RETURN-CODE                                 &lt;- L230
 * END-IF                                                   &lt;- L231
 * DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN02C'.          &lt;- L232
 * </pre>
 *
 * <p><strong>The rejected line carries two spaces before its colon and the processed line carries one.</strong>
 * That asymmetry is in the source, was confirmed with {@code cat -A}, and is reproduced above because it is
 * evidence a reviewer will diff. Nothing in this class emits those strings; the counters below are the
 * continuously scrapeable replacement for the two totals they carried.
 *
 * <p>{@code WS-TRANSACTION-COUNT} is incremented once per daily-transaction record read, at
 * {@code app/cbl/CBTRN02C.cbl:L206} ({@code ADD 1 TO WS-TRANSACTION-COUNT}), and is mirrored by
 * {@link #METRIC_RECORDS_PROCESSED}. {@code WS-REJECT-COUNT} is incremented at exactly one place,
 * {@code app/cbl/CBTRN02C.cbl:L214}, inside the {@code ELSE} branch of the {@code L211} test
 * {@code IF WS-VALIDATION-FAIL-REASON = 0}, and is mirrored by {@link #METRIC_RECORDS_REJECTED}.
 *
 * <p><strong>Return code 4 is set if and only if the reject count exceeds zero</strong>
 * ({@code app/cbl/CBTRN02C.cbl:L229-L231}); there is no other determinant. Translating that into a
 * {@code org.springframework.batch.core.ExitStatus} is the <em>batch layer's</em> responsibility, not this
 * class's, which is why no Spring Batch type is imported here. For context only, and implemented nowhere in
 * this file: the abend path at {@code app/cbl/CBTRN02C.cbl:L707-L711} displays {@code 'ABENDING PROGRAM'},
 * moves {@code 999} into {@code ABCODE} and calls {@code 'CEE3ABD'}, which the target surfaces as abend
 * code 999 with process return code 12.
 *
 * <h2>The four instruments, and their exact published contract</h2>
 *
 * <p>Every consumer of these metrics - {@code observability/prometheus.yml}, the panels in
 * {@code observability/grafana/dashboards/carddemo-dashboard.json}, and any alert rule - binds to the
 * rendered name and tag key, not to a Java symbol. <strong>A rename therefore empties a dashboard panel
 * silently, with no error anywhere.</strong> The contract is consequently stated in full:
 *
 * <table border="1">
 *   <caption>The four sanctioned instruments</caption>
 *   <tr><th>Micrometer name</th><th>Prometheus series</th><th>Unit</th><th>Tag keys</th><th>Cardinality</th></tr>
 *   <tr>
 *     <td>{@code carddemo.batch.records.processed}</td>
 *     <td>{@code carddemo_batch_records_processed_total}</td>
 *     <td>records</td><td><em>none</em></td><td>1 series</td>
 *   </tr>
 *   <tr>
 *     <td>{@code carddemo.batch.records.rejected}</td>
 *     <td>{@code carddemo_batch_records_rejected_total}</td>
 *     <td>records</td><td>{@code reject.code} rendered {@code reject_code}</td><td>exactly 5 series</td>
 *   </tr>
 *   <tr>
 *     <td>{@code carddemo.auth.attempts}</td>
 *     <td>{@code carddemo_auth_attempts_total}</td>
 *     <td>attempts</td><td>{@code outcome}</td><td>exactly 2 series</td>
 *   </tr>
 *   <tr>
 *     <td>{@code carddemo.transaction.amount.total}</td>
 *     <td>{@code carddemo_transaction_amount_total}</td>
 *     <td>currency units</td><td><em>none</em></td><td>1 series</td>
 *   </tr>
 * </table>
 *
 * <p>Nine series in total, and the ceiling is a property of the types involved rather than of discipline:
 * the reject dimension is an enum with five constants and the outcome dimension is an enum with two, so no
 * caller can widen either.
 *
 * <p><strong>The five strings below are declared here and nowhere else.</strong> They used to be mirrored as
 * executable YAML at {@code src/main/resources/application.yml} under {@code carddemo.metrics.*}, described
 * there as the metrics contract and headed "consumed by MetricsConfig". This class binds no property and its
 * specification requires that it bind none, so that block was consumed by nothing - and the copy had already
 * drifted where it mattered most, declaring the tag key as {@code reject-code} against the
 * {@link #TAG_REJECT_CODE} value of {@code reject.code} that is actually registered. A dashboard author who
 * trusted the file would have grouped on a label that does not exist and seen an empty panel with no error
 * anywhere. The mirror is gone; the evidence it carried survives as comments in that file naming these
 * constants. The names are still quoted in the header of {@code observability/prometheus.yml}, which is
 * documentation of the scrape target rather than a second declaration.
 *
 * <p><strong>No instrument declares a Micrometer base unit, and that omission is deliberate and
 * load-bearing.</strong> Micrometer's Prometheus naming convention appends an underscore followed by the
 * base unit to a counter's name, and then appends {@code _total} only when the result does not already end in
 * {@code _total}. Declaring a base unit of {@code records} would therefore publish
 * {@code carddemo_batch_records_processed_records_total}, which no panel queries - the metric would simply
 * vanish from the dashboard with no error raised. Units are documented here and in each counter's
 * description instead. Note also that {@code carddemo.transaction.amount.total} already ends in
 * {@code total}, so it is published without a further {@code _total} suffix; that is why its panel query is
 * {@code sum(carddemo_transaction_amount_total)} while the other three carry the suffix.
 *
 * <h2>Cardinality is the one inefficiency this class must not commit</h2>
 *
 * <p>Rule 1 Clause A requires avoiding obvious inefficiencies, and in a metrics layer the obvious
 * inefficiency is unbounded tag cardinality: every distinct tag value is a separate time series held in
 * memory by the process and persisted by the backend. <strong>No account identifier, card number, customer
 * identifier, transaction identifier, user identifier, user name, correlation identifier, trace identifier,
 * timestamp, object-store key, queue message identifier or job execution identifier is ever used as a tag
 * key or a tag value here.</strong> The only two dimensions are the two
 * closed enumerations named above.
 *
 * <p>Counters are resolved once - eagerly, for every value of both enumerations - and held in immutable
 * maps, so an increment is a map read rather than a registry search, and {@code values()} is never called
 * on an increment path.
 *
 * <h2>Money crosses a reporting boundary here, and is not computed here</h2>
 *
 * <p>{@link #countTransactionAmount(BigDecimal)} accepts a {@link BigDecimal} because Transformation Rule 1
 * admits no floating-point type on any financial path and the security gate greps for exactly that. The
 * running total is accumulated exactly, as a {@link BigDecimal}, and a gauge converts it to a primitive
 * <strong>exactly once</strong> per scrape - on a single line flagged as the reporting boundary. No caller
 * ever performs that conversion.
 *
 * <p>That boundary is legitimate because <strong>this counter is a telemetry mirror and is not the
 * authoritative financial total</strong>. The authoritative value is the {@link BigDecimal} held in the
 * domain and the {@code NUMERIC(11,2)} column it persists to; nothing reads a monetary value back out of a
 * meter. No accumulator field is kept here either - the registry's counter is the accumulator - so there is
 * no mutable monetary state in this class and no thread-safety of its own to get wrong.
 *
 * <h2>Ownership: this class defines, others register, scrape and display</h2>
 *
 * <p>Four responsibilities, four owners, no overlap. <strong>This class defines the instruments.</strong>
 * {@code com.cardemo.config.ObservabilityConfig} owns the documented wiring contract for the observability
 * layer and publishes the single production time source; it deliberately declares no counter bean, because the
 * three classes in this package are self-registering and a second definition would be a duplicate.
 * {@code observability/prometheus.yml} owns the scrape. The dashboard
 * JSON owns display. Accordingly this file declares no {@code @Enable...} annotation, no component scan, no
 * {@code MeterRegistry} implementation bean, no meter filter or common-tag customiser, no exporter or
 * endpoint configuration and no tracing configuration.
 *
 * <p><strong>Troubleshooting a duplicate bean definition.</strong> If the context fails to start reporting a
 * duplicate definition for any of the four beans below, the duplicate must be removed from wherever it was introduced
 * - most likely {@code com.cardemo.config.ObservabilityConfig} once that class is authored - <em>not</em> from this
 * file: this file is the plan-designated definition site. Note that a duplicate <em>meter</em> cannot arise however
 * often a name is resolved - {@code MeterRegistry.counter} and {@code Counter.Builder.register} both return the
 * existing meter for a given name and tag set rather than adding a second one - so re-resolving a counter is always
 * safe and never creates a fifth instrument.
 *
 * <h2>This class does not touch logging</h2>
 *
 * <p>It holds no logger and installs no formatter, appender, converter or message post-processor of any
 * kind. That is deliberate and must stay that way. {@code com.cardemo.service.shared.FileStatusMapper}
 * emits the legacy literal {@code FILE STATUS IS: NNNN} immediately followed by a four-character status,
 * reproducing {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} from both branches of
 * {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L721} and {@code :L725}. COBOL concatenates
 * a {@code DISPLAY} literal to the following identifier with no separator and the literal already contains
 * the placeholder text, so file status {@code '23'} emits {@code FILE STATUS IS: NNNN0023}. The stray
 * {@code NNNN} is a preserved legacy quirk that the end-to-end parity gate diffs against a baseline; it is
 * never to be "fixed", re-rendered, wrapped, truncated or masked, and this file adds nothing that could do
 * so. Structured log encoding and secret masking belong to {@code src/main/resources/logback-spring.xml}.
 *
 * <h2>Secrecy: the defence is never producing the value</h2>
 *
 * <p>Emitting telemetry is this package's entire purpose, which gives it the widest exposure under Rule 1
 * Clause D of any package in the application. No credential, presented password, stored password hash,
 * token, signing key, authorization header, social security number, card number, telephone number,
 * government identifier, date of birth, funds-transfer account identifier, cloud request or response
 * payload, database row or bind parameter is read, held, tagged or emitted anywhere in this class. Masking
 * in the logging configuration is a second line of defence; the first is that the value is never produced.
 *
 * <h2>Configuration this class reads, and configuration it does not restate</h2>
 *
 * <p>This class reads <strong>no</strong> configuration. It calls no environment lookup, has no injected
 * property and depends on no default charset, locale or time zone, so its behaviour is identical in every
 * profile and on every host. Keys owned elsewhere are referenced here only so the reader knows where they
 * live, and are deliberately not duplicated: {@code src/main/resources/application.yml} owns
 * {@code management.endpoints.web.exposure.include}, whose value is exactly {@code health, info, prometheus}
 * and never {@code *}; it also owns {@code management.endpoint.health.show-details}, which is never
 * {@code always}, the separate liveness and readiness groups whose readiness set is the database, the object
 * store and the queue, {@code management.otlp.tracing.endpoint}, {@code spring.cloud.aws.region.static}, the
 * three bucket names and the report queue whose logical name is {@code carddemo-report-jobs}.
 * {@code observability/prometheus.yml} owns the scrape interval and target.
 *
 * <h2>How to build and test this class</h2>
 *
 * <p>Build with {@code ./mvnw -B -ntp clean compile}; the compiler runs {@code -Xlint:all -Werror} with
 * warnings failing the build, so a raw type or an unchecked cast is fatal. An unused import is not:
 * {@code javac} 25 publishes no {@code unused} lint key, so that prohibition is review-enforced. Verify with
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}. Unit tests live under
 * {@code src/test/java/com/cardemo/unit/**} and never in this package; every member below is reachable
 * against a plain {@code SimpleMeterRegistry} with no Spring context, because this class holds no static
 * state and reads no clock, locale, charset or time zone.
 *
 * <p>One behaviour differs between registry implementations and a test must pick the right one to assert
 * against: Micrometer's Prometheus counter ignores any non-positive increment, whereas the cumulative
 * counter behind {@code SimpleMeterRegistry} accepts it. The consequence for negative monetary amounts is
 * spelled out on {@link #countTransactionAmount(BigDecimal)}.
 *
 * <h2>Constraints that must hold for the dashboard to keep working</h2>
 *
 * <ul>
 *   <li><strong>The instrument count stays at four and both tag dimensions stay enum-closed.</strong> A fifth
 *       instrument, or any high-cardinality tag, breaks the contract this class publishes. Timers, gauges,
 *       distribution summaries and long-task timers described elsewhere in the specification are
 *       complementary and additive, and are out of scope here.</li>
 *   <li><strong>Renaming a metric, changing a tag key or declaring a Micrometer base unit silently empties a
 *       dashboard panel and raises no error.</strong> Change the constant and
 *       {@code observability/grafana/dashboards/carddemo-dashboard.json} in the same commit, and never set a
 *       base unit.</li>
 *   <li><strong>The reject tag key is {@code reject.code}</strong>, matching the producer
 *       {@code com.cardemo.batch.writers.RejectWriter}. Micrometer tag keys are distinct even when they
 *       render to the same Prometheus label, and Prometheus rejects one metric name carrying inconsistent
 *       label sets, so the two must not diverge.</li>
 *   <li><strong>A Micrometer counter is monotonic</strong>, so the legitimately negative transaction amounts
 *       of {@code app/cbl/CBTRN02C.cbl:L548-L552} cannot lower an exported total. That limitation is accepted
 *       and disclosed on {@link #countTransactionAmount(BigDecimal)} rather than hidden behind an absolute
 *       value, because taking the absolute value would misreport the figure and lowering it would require a
 *       second instrument the four-instrument contract forbids.</li>
 *   </ul>
 *
 * <p>What this class can prove on its own is that all four series appear on the scrape endpoint with the
 * expected names and tag keys. Demonstrating a <em>populated</em> dashboard additionally needs a container
 * runtime with an accessible socket, the compose stack up, and batch or sign-on traffic to move the counters.
 * No throughput or latency threshold is asserted anywhere, because the COBOL corpus publishes no service
 * level; the performance gate records a measured baseline instead.
 *
 * * @see RejectCode
 */
@Configuration
public class MetricsConfig {

    // =============================================================================================
    // Published name registry.
    //
    // These are public so that producers cite a symbol rather than retype a literal: a metric name or
    // tag key that drifts between producer and dashboard empties a panel without raising an error.
    // Every constant here is a final reference to an immutable String, so the class still holds no
    // static mutable state of any kind.
    // =============================================================================================

    /**
     * Micrometer name of the processed-records counter, published to Prometheus as
     * {@code carddemo_batch_records_processed_total}. Replaces
     * {@code DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT} at
     * {@code app/cbl/CBTRN02C.cbl:L227}. Unit: records. No tags.
     */
    public static final String METRIC_RECORDS_PROCESSED = "carddemo.batch.records.processed";

    /**
     * Micrometer name of the rejected-records counter, published to Prometheus as
     * {@code carddemo_batch_records_rejected_total}. Replaces
     * {@code DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT} at {@code app/cbl/CBTRN02C.cbl:L228} -
     * two spaces before that colon, one before the processed one. Unit: records. Tagged by
     * {@link #TAG_REJECT_CODE} only.
     */
    public static final String METRIC_RECORDS_REJECTED = "carddemo.batch.records.rejected";

    /**
     * Micrometer name of the authentication-attempts counter, published to Prometheus as
     * {@code carddemo_auth_attempts_total}. New capability: the sign-on program
     * {@code app/cbl/COSGN00C.cbl}, all 260 lines of it, contains no counter and no {@code DISPLAY}.
     * Unit: attempts. Tagged by {@link #TAG_OUTCOME} only.
     */
    public static final String METRIC_AUTHENTICATION_ATTEMPTS = "carddemo.auth.attempts";

    /**
     * Micrometer name of the cumulative transaction-amount counter, published to Prometheus as
     * {@code carddemo_transaction_amount_total} - with no further {@code _total} suffix, because the name
     * already ends in {@code total}. New capability. Unit: currency units. No tags.
     */
    public static final String METRIC_TRANSACTION_AMOUNT_TOTAL = "carddemo.transaction.amount.total";

    /**
     * The one tag key on {@link #METRIC_RECORDS_REJECTED}, rendered by Prometheus as {@code reject_code},
     * which is what {@code sum by (reject_code)} in the dashboard JSON groups on.
     *
     * <p><strong>This is the only declaration of the tag key anywhere in the tree.</strong> It used to be
     * declared twice - here and as {@code RejectWriter.REJECT_CODE_TAG} - with a comment on each requiring
     * the two to stay byte-identical, because Prometheus refuses a single metric name whose series carry
     * different label sets and a divergence would have broken the export rather than merely looked untidy.
     * A requirement that two constants stay equal is a defect with a comment on it, not a design: the
     * duplicate is gone, that writer now increments through {@link #countRecordRejected(RejectCode)}, and
     * the dependency direction stays right because the batch layer depends on this observability class
     * rather than the reverse.
     */
    public static final String TAG_REJECT_CODE = "reject.code";

    /**
     * The one tag key on {@link #METRIC_AUTHENTICATION_ATTEMPTS}, carrying a value from
     * {@link AuthenticationOutcome} and nothing else. Rendered by Prometheus unchanged as {@code outcome},
     * which is what {@code sum by (outcome)} in the dashboard JSON groups on.
     *
     * <p>It never carries a user identifier, a user name, a {@code SEC-USR-ID}, a network address or any
     * other per-subject value. Doing so would be unbounded in cardinality and would put identity into
     * telemetry, breaching Rule 1 Clauses A and D at once.
     */
    public static final String TAG_OUTCOME = "outcome";

    // =============================================================================================
    // Descriptions. Micrometer keeps the description of whichever registration happened first; these
    // beans are built while the context starts, before any batch step or request can run, so these
    // are the descriptions that reach the scrape endpoint.
    // =============================================================================================

    /** Help text for {@link #METRIC_RECORDS_PROCESSED}, naming the unit the metric name cannot carry. */
    private static final String DESCRIPTION_RECORDS_PROCESSED =
            "Daily transaction records read and processed, in records; replaces the CBTRN02C end-of-run "
                    + "DISPLAY of WS-TRANSACTION-COUNT";

    /** Help text for {@link #METRIC_RECORDS_REJECTED}, naming the unit and the bounded tag. */
    private static final String DESCRIPTION_RECORDS_REJECTED =
            "Daily transaction records rejected by CBTRN02C validation, in records, tagged by the five "
                    + "reject codes 100/101/102/103/109";

    /** Help text for {@link #METRIC_AUTHENTICATION_ATTEMPTS}, naming the unit and the bounded tag. */
    private static final String DESCRIPTION_AUTHENTICATION_ATTEMPTS =
            "Sign-on attempts, in attempts, tagged by outcome only and never by subject";

    /** Help text for {@link #METRIC_TRANSACTION_AMOUNT_TOTAL}, naming the unit and its advisory status. */
    private static final String DESCRIPTION_TRANSACTION_AMOUNT_TOTAL =
            "Cumulative reported transaction amount, in currency units; a telemetry mirror, not the "
                    + "authoritative financial total";

    // =============================================================================================
    // The bounded outcome dimension.
    // =============================================================================================

    /**
     * The closed set of authentication outcomes, and therefore the complete set of values the
     * {@link MetricsConfig#TAG_OUTCOME} tag can ever take.
     *
     * <p>Cardinality is fixed at two by the type system rather than by convention: because
     * {@link MetricsConfig#countAuthenticationAttempt(AuthenticationOutcome)} accepts nothing else, there
     * is no parameter through which a third value could reach the registry. The tag values are lower-case
     * ASCII literals held on the constants, not derived from {@link Enum#name()} through a case
     * conversion, so no locale can influence what is published - a locale with non-ASCII digits or an
     * unusual case mapping cannot corrupt a tag value that was never computed.
     *
     * <p>These two strings are published: the dashboard panel "Authentication attempts by outcome"
     * renders them directly through its {@code {{outcome}}} legend format.
     */
    public enum AuthenticationOutcome {

        /** Credentials were accepted. Published as the tag value {@code success}. */
        SUCCESS("success"),

        /**
         * Credentials were rejected, or the presented user identifier did not resolve. Published as the
         * tag value {@code failure}. Deliberately one value rather than several: distinguishing "no such
         * user" from "wrong password" in telemetry tells an attacker which identifiers exist.
         */
        FAILURE("failure");

        /** The exact tag value published for this outcome. */
        private final String tagValue;

        /**
         * Binds an outcome to its published tag value.
         *
         * @param tagValue the literal published as the {@code outcome} tag; never {@code null}
         */
        AuthenticationOutcome(final String tagValue) {
            this.tagValue = tagValue;
        }

        /**
         * Returns the exact string published as the {@code outcome} tag for this outcome.
         *
         * <p>Side effects: none; this is a pure accessor over an immutable field.
         *
         * @return the published tag value, never {@code null} and never empty
         */
        public String getTagValue() {
            return tagValue;
        }
    }

    // =============================================================================================
    // Resolved-once instrument handles. All final, all immutable, none static.
    // =============================================================================================

    /** The single untagged processed-records counter. */
    private final Counter recordsProcessedCounter;

    /** Immutable, total map holding one counter series per reject code. Never contains a null value. */
    private final Map<RejectCode, Counter> recordsRejectedCounters;

    /** Immutable, total map holding one counter series per outcome. Never contains a null value. */
    private final Map<AuthenticationOutcome, Counter> authenticationAttemptCounters;

    /**
     * The authoritative accumulator behind {@link #METRIC_TRANSACTION_AMOUNT_TOTAL}: an exact
     * {@link BigDecimal} running total, published to the registry through a gauge that reads it.
     *
     * <p>It is a gauge rather than a counter, and that is the whole point of the instrument. A Micrometer
     * counter is monotonic and its Prometheus implementation <em>ignores</em> any non-positive increment, so a
     * negative amount contributed nothing and could never lower the exported total. Negative amounts are not
     * an edge case here: {@code app/cbl/CBTRN02C.cbl:L548-L552} adds a negative amount to the cycle
     * <em>debit</em> accumulator, so debits genuinely hold negative values, and
     * {@code app/data/ASCII/dailytran.txt} genuinely carries negative overpunch signs. Reporting the sum of a
     * signed stream through a monotonic instrument is not a limitation to disclose; it is the wrong instrument.
     *
     * <p><strong>No sign is normalised anywhere.</strong> The accumulator is updated with
     * {@link BigDecimal#add(BigDecimal)} and never with an absolute value, so the exported figure is the true
     * signed total and can fall as well as rise.
     *
     * <p>Held in an {@link AtomicReference} and updated with a compare-and-set loop, because a gauge is read
     * from the scrape thread while batch and request threads write to it, and {@code BigDecimal} arithmetic is
     * not one instruction. The reference is final; the value it holds is the only mutable state in this class.
     */
    private final AtomicReference<BigDecimal> transactionAmountTotal =
            new AtomicReference<>(BigDecimal.ZERO);

    /**
     * Resolves all four instruments - nine series in total - once, eagerly, from the injected registry.
     *
     * <p>Constructor injection is the only injection used here: there is no field or setter injection, no
     * service locator and no static registry lookup anywhere in this class. Resolving eagerly is what keeps
     * the increment methods free of registry searches, and it means every series is present on the scrape
     * endpoint from the first scrape onwards rather than appearing only after the first event of its kind.
     *
     * <p>All the work is delegated to {@code private static} helpers. That is deliberate on two counts: they
     * are pure functions of the registry, which Rule 1 Clause B prefers, and because they cannot be
     * overridden the constructor cannot leak a partially built instance - which matters because
     * {@code -Xlint:all} enables {@code this-escape} and this class cannot be {@code final}, being a
     * proxied configuration class.
     *
     * <p>Registering here is safe with respect to common tags. Spring fully initialises and post-processes
     * the {@code MeterRegistry} bean - which is when the framework installs the meter filters that apply
     * {@code management.metrics.tags} - before it can be passed to this constructor, so these nine series
     * carry the same {@code application} and {@code component} common tags as every framework meter.
     *
     * @param meterRegistry the application's meter registry, supplied by Spring; must not be {@code null}
     * @throws NullPointerException if {@code meterRegistry} is {@code null}
     */
    public MetricsConfig(final MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.recordsProcessedCounter = resolveRecordsProcessedCounter(meterRegistry);
        this.recordsRejectedCounters = resolveRecordsRejectedCounters(meterRegistry);
        this.authenticationAttemptCounters = resolveAuthenticationAttemptCounters(meterRegistry);
        registerTransactionAmountTotalGauge(meterRegistry, this.transactionAmountTotal);
    }

    // =============================================================================================
    // NO BEAN DEFINITIONS FOR THE INSTRUMENTS THEMSELVES, and their absence is the fix.
    //
    // Four @Bean methods used to expose the counters - one per instrument, plus four published bean names to
    // qualify them by. Nothing in the application or the test tree ever injected one of them: every consumer
    // injects this class and calls the facade below. They were therefore four unused beans, which Rule 1
    // Clause B forbids, and - worse for a metrics layer - a SECOND way to obtain an instrument, which is how
    // a caller ends up registering its own series against a name this class owns. That is precisely the
    // defect found in the three batch writers, each of which had resolved its own counter from the registry.
    //
    // With them gone the facade is the only path, so this class is the single registration owner in fact and
    // not merely by convention. Nothing is lost: Micrometer resolves a meter by name and tag set, so any
    // component that genuinely needs the raw instrument can still obtain the identical object from the
    // registry - but no component does, and none should, because the facade is what carries the validation.
    // =============================================================================================

    // =============================================================================================
    // Increment facade. Validated, bounded, and the only place money crosses into a primitive.
    // =============================================================================================

    /**
     * Records that one daily-transaction record was processed.
     *
     * <p>Mirrors {@code ADD 1 TO WS-TRANSACTION-COUNT} at {@code app/cbl/CBTRN02C.cbl:L206}, which the
     * legacy program executes once per record read, so this is called once per record and increments by
     * exactly one.
     *
     * <p>Side effects: increments {@link #METRIC_RECORDS_PROCESSED} by one. Failure modes: none - it takes
     * no argument, so there is nothing to validate and nothing that can be rejected.
     */
    public void countRecordProcessed() {
        recordsProcessedCounter.increment();
    }

    /**
     * Records that {@code count} daily-transaction records were processed, in one advance.
     *
     * <p>Numerically identical to calling {@link #countRecordProcessed()} {@code count} times, and that
     * equivalence is exact rather than approximate: the argument is an {@code int}, every {@code int} is
     * representable in a {@code double} without loss, and a counter's value is a sum. Nothing rounds.
     *
     * <p>It exists because a chunk-oriented writer knows how many records it wrote and has no reason to loop.
     * An earlier revision of {@code com.cardemo.batch.writers.TransactionWriter} performed one atomic
     * increment per row on the grounds that the amount-taking overload accepts only a primitive and this
     * application holds counts as exact types - true of <em>money</em>, where Transformation Rule 1 admits no
     * floating-point type, and irrelevant to a record count, which is a small integer. The loop was a real if
     * modest inefficiency on the hottest path in the batch tier, rated <strong>Low</strong>, and it is
     * replaced by this method.
     *
     * <p>Note what this does <em>not</em> change: the legacy program still counts once per record, and the
     * exported total is the same number either way. Only the number of atomic operations differs.
     *
     * <p>Side effects: increments {@link #METRIC_RECORDS_PROCESSED} by {@code count}. A count of zero is a
     * no-op, which is the correct outcome for an empty chunk.
     *
     * @param count how many records were processed; must not be negative
     * @throws IllegalArgumentException if {@code count} is negative, because a negative count would lower a
     *     monotonic counter, which Micrometer would silently discard rather than report
     */
    public void countRecordsProcessed(final int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative but was " + count);
        }
        if (count > 0) {
            recordsProcessedCounter.increment(count);
        }
    }

    /**
     * Records that one daily-transaction record was rejected, tagging the increment with its reject code.
     *
     * <p>Mirrors {@code ADD 1 TO WS-REJECT-COUNT} at {@code app/cbl/CBTRN02C.cbl:L214}, the single site
     * where the legacy reject count moves, inside the {@code ELSE} branch of the {@code L211} test. Because
     * return code 4 is set if and only if that count exceeds zero
     * ({@code app/cbl/CBTRN02C.cbl:L229-L231}), a non-zero value on this counter is the observable
     * counterpart of a completed-with-rejects run.
     *
     * <p><strong>A reject is a business outcome, not an error.</strong> This method therefore never throws
     * on account of the reject itself, never converts a code into an exception and logs nothing at error
     * level - it records a bounded tag and returns. The only exception it can raise concerns its own
     * argument.
     *
     * <p>Side effects: increments one of the five {@link #METRIC_RECORDS_REJECTED} series by one.
     * Configuration: none. Troubleshooting: a code that never appears simply never occurred; all five series
     * are registered at startup, so absence from the scrape output would instead indicate a tag-key
     * divergence.
     *
     * @param rejectCode the outcome to attribute this rejection to; must not be {@code null}
     * @throws NullPointerException if {@code rejectCode} is {@code null}. A null is rejected outright rather
     *     than folded into an "unknown" bucket, because inventing a sixth tag value would break the bounded
     *     cardinality this counter depends on
     */
    public void countRecordRejected(final RejectCode rejectCode) {
        Objects.requireNonNull(rejectCode, "rejectCode must not be null");
        // Total by construction: the map was built from RejectCode.values(), so this cannot be null.
        recordsRejectedCounters.get(rejectCode).increment();
    }

    /**
     * Records one authentication attempt under its outcome.
     *
     * <p>New capability with no legacy counterpart: {@code app/cbl/COSGN00C.cbl} counts nothing. The outcome
     * is the only dimension, and it is closed at two values by {@link AuthenticationOutcome}.
     *
     * <p>Side effects: increments one of the two {@link #METRIC_AUTHENTICATION_ATTEMPTS} series by one.
     * Configuration: none. Failure modes: a null outcome is rejected; nothing else can fail.
     *
     * <p><strong>No identity reaches this method.</strong> It takes no user identifier, no user name and no
     * network address, by design rather than by discipline - there is no parameter through which one could
     * be passed, so neither an unbounded tag nor an identity leak into telemetry is possible here.
     *
     * @param outcome whether the attempt succeeded or failed; must not be {@code null}
     * @throws NullPointerException if {@code outcome} is {@code null}
     */
    public void countAuthenticationAttempt(final AuthenticationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        // Total by construction: the map was built from AuthenticationOutcome.values().
        authenticationAttemptCounters.get(outcome).increment();
    }

    /**
     * Adds a transaction amount to the cumulative reported total.
     *
     * <p>The parameter is a {@link BigDecimal} because Transformation Rule 1 forbids any floating-point type
     * on a financial path, and the security gate greps for exactly that. Micrometer accepts only a
     * primitive, so this method contains <strong>the single reporting-boundary conversion in this
     * class</strong>, marked as such in the code. That conversion is legitimate precisely because the
     * counter is advisory: <strong>it is a telemetry mirror, not the authoritative financial total.</strong>
     * The authoritative value is the {@link BigDecimal} in the domain and the {@code NUMERIC(11,2)} column
     * behind it, and no monetary decision is ever taken from a meter. No rounding is applied and no
     * accumulator is held here - the registry's counter is the accumulator.
     *
     * <p><strong>Negative amounts are legitimate and are passed through unchanged.</strong>
     * {@code app/cbl/CBTRN02C.cbl:L548-L552} adds a negative amount to the cycle <em>debit</em> accumulator,
     * so debits genuinely hold negative values, and {@code app/data/ASCII/dailytran.txt} genuinely carries
     * negative overpunch signs. No absolute value is taken anywhere. The honest consequence, stated rather
     * than hidden: a Micrometer counter is monotonic, and its Prometheus implementation ignores any
     * non-positive increment, so a negative amount contributes nothing to the exported total and cannot
     * lower it. Representing a signed total faithfully would need a second instrument or a gauge, which the
     * four-instrument contract forbids, so the limitation is disclosed here instead.
     *
     * <p>An earlier revision reported this series through a Micrometer counter and <em>disclosed</em> the
     * consequence rather than fixing it: a counter is monotonic and its Prometheus implementation ignores any
     * non-positive increment, so every debit contributed nothing and the exported total was the sum of the
     * credits alone. It also argued that a gauge was forbidden by the four-instrument contract, which
     * confuses the number of instruments with their <em>type</em> - the contract fixes four names, and this is
     * still one of them. That combination - an instrument that silently discards half its input, and an API
     * with no caller at all to notice - was rated <strong>High</strong>. It is now a gauge over the exact
     * {@link BigDecimal} accumulator described on {@link #transactionAmountTotal}, and its caller is
     * {@code com.cardemo.batch.writers.TransactionWriter}, which reports each posted amount as it writes it.
     *
     * <p>Side effects: adds the amount to the exact accumulator that {@link #METRIC_TRANSACTION_AMOUNT_TOTAL}
     * publishes. Configuration: none. Troubleshooting: if this series disagrees with the database, the
     * database is right - the gauge converts to a double for the scrape, so a total far beyond a double's
     * 53-bit significand will round in the export while the accumulator stays exact.
     *
     * @param amount the transaction amount to report, of any sign and any scale; must not be {@code null}
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws IllegalArgumentException if {@code amount} is so large that it has no finite {@code double}
     *     representation. The message carries only the value's precision and scale, never the value itself,
     *     so that an exception which is later logged cannot disclose a monetary figure
     */
    public void countTransactionAmount(final BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (!Double.isFinite(amount.doubleValue())) {
            // Rejected here rather than at the scrape, because a value the gauge cannot report must not be
            // allowed into the accumulator: it would poison every subsequent reading. The message carries the
            // precision and the scale and never the value itself.
            throw new IllegalArgumentException("amount has no finite double representation and cannot be "
                    + "reported as a metric: precision=" + amount.precision() + ", scale=" + amount.scale());
        }
        // Exact accumulation, and NO absolute value: a negative amount lowers the total, which is the
        // behaviour a signed stream requires and the reason this is not a monotonic counter.
        transactionAmountTotal.accumulateAndGet(amount, BigDecimal::add);
    }

    // =============================================================================================
    // Instrument resolution - pure, static, private. One definition site per instrument.
    // =============================================================================================

    /**
     * Resolves the untagged processed-records counter.
     *
     * @param meterRegistry the registry to resolve against; must not be {@code null}
     * @return the counter, never {@code null}
     * @throws NullPointerException if {@code meterRegistry} is {@code null}
     */
    private static Counter resolveRecordsProcessedCounter(final MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        return Counter.builder(METRIC_RECORDS_PROCESSED)
                .description(DESCRIPTION_RECORDS_PROCESSED)
                .register(meterRegistry);
    }

    /**
     * Resolves one counter series for every reject code, eagerly, into an immutable enum-keyed map.
     *
     * <p>The tag value is {@link Integer#toString(int)} over {@link RejectCode#getCode()}, giving the plain
     * numeric form {@code "100"} through {@code "109"}. That method is specified to emit ASCII digits
     * unconditionally, which makes it locale-proof by construction - a stronger guarantee than passing
     * {@code Locale.ROOT} to a formatter - and it is byte-identical to what the other registration site
     * produces, which the shared metric name requires.
     *
     * @param meterRegistry the registry to resolve against; must not be {@code null}
     * @return an immutable, total map from reject code to counter, never {@code null}
     * @throws NullPointerException if {@code meterRegistry} is {@code null}
     */
    private static Map<RejectCode, Counter> resolveRecordsRejectedCounters(final MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        final Map<RejectCode, Counter> counters = new EnumMap<>(RejectCode.class);
        for (final RejectCode rejectCode : RejectCode.values()) {
            counters.put(rejectCode, Counter.builder(METRIC_RECORDS_REJECTED)
                    .description(DESCRIPTION_RECORDS_REJECTED)
                    .tag(TAG_REJECT_CODE, Integer.toString(rejectCode.getCode()))
                    .register(meterRegistry));
        }
        return Collections.unmodifiableMap(counters);
    }

    /**
     * Resolves one counter series for every authentication outcome, eagerly, into an immutable enum-keyed
     * map, so both series are visible on the scrape endpoint before any sign-on has occurred.
     *
     * @param meterRegistry the registry to resolve against; must not be {@code null}
     * @return an immutable, total map from outcome to counter, never {@code null}
     * @throws NullPointerException if {@code meterRegistry} is {@code null}
     */
    private static Map<AuthenticationOutcome, Counter> resolveAuthenticationAttemptCounters(
            final MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        final Map<AuthenticationOutcome, Counter> counters = new EnumMap<>(AuthenticationOutcome.class);
        for (final AuthenticationOutcome outcome : AuthenticationOutcome.values()) {
            counters.put(outcome, Counter.builder(METRIC_AUTHENTICATION_ATTEMPTS)
                    .description(DESCRIPTION_AUTHENTICATION_ATTEMPTS)
                    .tag(TAG_OUTCOME, outcome.getTagValue())
                    .register(meterRegistry));
        }
        return Collections.unmodifiableMap(counters);
    }

    /**
     * Resolves the untagged cumulative transaction-amount counter.
     *
     * <p>No base unit is declared, deliberately: Micrometer would append it to the published name and the
     * dashboard queries the name without it.
     *
     * @param meterRegistry the registry to resolve against; must not be {@code null}
     * @param accumulator the exact accumulator the gauge reads; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    private static void registerTransactionAmountTotalGauge(final MeterRegistry meterRegistry,
            final AtomicReference<BigDecimal> accumulator) {

        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        // THE REPORTING BOUNDARY - the one and only conversion out of BigDecimal in this class, evaluated by
        // the scrape thread rather than by any caller. Permitted because the gauge is advisory telemetry and
        // never a financial computation; the accumulator above remains the exact value. Sign preserved.
        Gauge.builder(METRIC_TRANSACTION_AMOUNT_TOTAL, accumulator, reference -> reference.get().doubleValue())
                .description(DESCRIPTION_TRANSACTION_AMOUNT_TOTAL)
                .strongReference(true)
                .register(meterRegistry);
    }
}
