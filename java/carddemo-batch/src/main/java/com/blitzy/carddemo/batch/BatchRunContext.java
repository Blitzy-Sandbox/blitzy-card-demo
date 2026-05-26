/*
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
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.batch;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

// Note: java.lang.ScopedValue (JEP 506, finalized in Java 25) is auto-imported
// via java.lang.*. No explicit import is required. The class moved from its
// preview-status location (java.util.concurrent) to java.lang when finalized.

/**
 * Immutable per-job context for a CardDemo batch run, propagated across method
 * calls and child virtual threads via the JEP 506 {@link ScopedValue} mechanism
 * (finalized in Java 25).
 *
 * <p>This record carries the three pieces of information that every batch
 * driver in {@code carddemo-batch} needs to consult and that the original COBOL
 * programs would have read from JCL {@code PARM}s, the system date, or
 * {@code WORKING-STORAGE} fields:
 * <ul>
 *   <li>{@code runId} &mdash; uniquely identifies this batch run. Typically a
 *       UUID-based string or a human-readable timestamped identifier such as
 *       {@code "POSTTRAN-2025-01-15T08:00:00Z"}. Must be non-{@code null} and
 *       non-blank.</li>
 *   <li>{@code processingDate} &mdash; the date the run treats as &quot;today&quot;
 *       for date-sensitive logic (interest accrual, statement period
 *       boundaries, the date portion of the JCL {@code yyyyMMddhh} PARM). Must
 *       be non-{@code null}.</li>
 *   <li>{@code tenant} &mdash; tenant identifier for multi-tenant deployments.
 *       Single-tenant deployments use {@link #DEFAULT_TENANT}
 *       ({@code "DEFAULT"}). Must be non-{@code null} and non-blank.</li>
 * </ul>
 *
 * <h2>Canonical usage pattern (AAP &sect;0.6.6, JEP 506)</h2>
 * {@snippet lang = "java":
 * ScopedValue.where(BatchRunContext.BATCH_CTX,
 *                   new BatchRunContext(runId, processingDate, tenant))
 *            .run(() -> postTransactionsBatch.execute());
 * }
 *
 * <p>Inside any callee on the same thread, or on a child virtual thread spawned
 * by the runnable, {@code BatchRunContext.BATCH_CTX.get()} (or the convenience
 * helper {@link #current()}) retrieves the bound context. The convenience
 * helper {@link #runWith(BatchRunContext, Runnable)} is offered for callsites
 * that already have a {@code BatchRunContext} instance and want to reduce the
 * boilerplate of the {@code ScopedValue.where(...).run(...)} idiom.
 *
 * <h2>Replaces {@code ThreadLocal}</h2>
 * Per AAP &sect;0.6.6 and &sect;0.7.4, {@link ThreadLocal} is <strong>forbidden
 * in new CardDemo Java code</strong>. {@code ScopedValue} is dramatically
 * lighter than {@code ThreadLocal} when the carrier count is large (e.g.,
 * millions of virtual threads each carrying their own thread-local map is
 * prohibitive); the scope binding has no per-thread heap cost. Translators
 * MUST NOT introduce any {@code ThreadLocal} reference in the
 * {@code carddemo-batch} module.
 *
 * <h2>Immutability and thread safety</h2>
 * This record is immutable: its three components are {@link String},
 * {@link LocalDate}, and {@link String}, all of which are themselves immutable.
 * Sharing a {@code BatchRunContext} instance across threads is therefore safe
 * by construction; no synchronization is required.
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP &sect;0.6.6 (Batch Throughput Strategy) &mdash; verbatim example
 *       of the {@code ScopedValue.where(...).run(...)} idiom</li>
 *   <li>AAP &sect;0.7.3 (Mandated Java 25 features) &mdash; records,
 *       {@link ScopedValue} (JEP 506 Final), Flexible Constructor Bodies
 *       (JEP 513 Final), and {@link java.time}</li>
 *   <li>AAP &sect;0.7.4 (Explicitly forbidden) &mdash; {@code ThreadLocal} in
 *       new code</li>
 *   <li>AAP &sect;0.1.3 (Surfaced implicit requirements) &mdash;
 *       &quot;ScopedValue replaces ThreadLocal entirely&quot;</li>
 *   <li>{@code java/application.properties.example} &sect;5 &mdash;
 *       configuration property names and precedence consumed by
 *       {@link #fromEnvironment()}</li>
 * </ul>
 *
 * <h2>JEP references</h2>
 * <ul>
 *   <li><a href="https://openjdk.org/jeps/506">JEP 506</a> &mdash; Scoped
 *       Values (Final in Java 25)</li>
 *   <li><a href="https://openjdk.org/jeps/513">JEP 513</a> &mdash; Flexible
 *       Constructor Bodies (Final in Java 25); enables the validation-before
 *       -binding idiom used by the compact constructor below</li>
 * </ul>
 *
 * @param runId          unique batch-run identifier; must be non-{@code null}
 *                       and non-blank
 * @param processingDate the date this run treats as &quot;today&quot;; must be
 *                       non-{@code null}
 * @param tenant         tenant identifier; must be non-{@code null} and
 *                       non-blank
 * @since 1.0.0
 */
public record BatchRunContext(String runId, LocalDate processingDate, String tenant) {

    // -----------------------------------------------------------------------
    // Public static fields
    // -----------------------------------------------------------------------

    /**
     * The thread-scoped binding for this run's context. Establish a binding at
     * job entry with {@code ScopedValue.where(BATCH_CTX, ctx).run(body)} (or
     * the convenience helper {@link #runWith(BatchRunContext, Runnable)}) and
     * read it from any callee&mdash;including newly created virtual
     * threads&mdash;via {@code BATCH_CTX.get()} (or the convenience helper
     * {@link #current()}).
     *
     * <p>This field is the sole {@code ScopedValue} for the
     * {@code carddemo-batch} module. It <strong>replaces {@link ThreadLocal}
     * entirely</strong> in new code, per AAP &sect;0.6.6.
     *
     * <p>The instance is created exactly once at class-initialization time via
     * {@link ScopedValue#newInstance()} and is intentionally a public mutable-
     * looking surface (only the field is {@code static final}; the
     * {@code ScopedValue} itself has no mutable state).
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    /**
     * Tenant identifier used when no tenant is configured. Single-tenant
     * deployments leave {@code carddemo.batch.tenant} blank and let
     * {@link #fromEnvironment()} substitute this value.
     *
     * <p>The value is {@code "DEFAULT"} per
     * {@code java/application.properties.example} &sect;5.
     */
    public static final String DEFAULT_TENANT = "DEFAULT";

    // -----------------------------------------------------------------------
    // Private configuration keys (consumed by fromEnvironment())
    // -----------------------------------------------------------------------

    /**
     * JVM system-property key for the run-id override. Documented in
     * {@code java/application.properties.example} &sect;5.
     */
    private static final String PROP_RUN_ID = "carddemo.batch.run-id";

    /**
     * Environment-variable key for the run-id override. Documented in
     * {@code java/application.properties.example} &sect;5.
     */
    private static final String ENV_RUN_ID = "CARDDEMO_RUN_ID";

    /**
     * JVM system-property key for the processing-date override
     * ({@link java.time.format.DateTimeFormatter#ISO_LOCAL_DATE}).
     */
    private static final String PROP_PROCESSING_DATE = "carddemo.batch.processing-date";

    /**
     * Environment-variable key for the processing-date override
     * ({@link java.time.format.DateTimeFormatter#ISO_LOCAL_DATE}).
     */
    private static final String ENV_PROCESSING_DATE = "CARDDEMO_PROCESSING_DATE";

    /** JVM system-property key for the tenant override. */
    private static final String PROP_TENANT = "carddemo.batch.tenant";

    /** Environment-variable key for the tenant override. */
    private static final String ENV_TENANT = "CARDDEMO_TENANT";

    /**
     * Prefix prepended to the random UUID when {@link #fromEnvironment()}
     * auto-generates a {@code runId}. The prefix makes auto-generated
     * identifiers visually distinguishable from caller-supplied ones in log
     * lines and JFR event metadata.
     */
    private static final String GENERATED_RUN_ID_PREFIX = "carddemo-";

    // -----------------------------------------------------------------------
    // Compact canonical constructor (JEP 513 Flexible Constructor Bodies)
    // -----------------------------------------------------------------------

    /**
     * Compact canonical constructor. Validates every component for null and
     * blank-ness <strong>before</strong> the implicit field assignment&mdash;
     * the standard JEP 513 idiom for records.
     *
     * <p>For a record's compact constructor, the language has always permitted
     * this validation-before-binding pattern; JEP 513 generalises the freedom
     * to any constructor (including ones that call {@code super(...)} or
     * {@code this(...)}). The code below is therefore compatible with both
     * pre- and post-JEP-513 semantics.
     *
     * @throws NullPointerException     if any component is {@code null}; the
     *                                  exception message names the failing
     *                                  component (e.g.,
     *                                  {@code "runId"})
     * @throws IllegalArgumentException if {@code runId} or {@code tenant} is
     *                                  blank (per
     *                                  {@link String#isBlank()})
     */
    public BatchRunContext {
        Objects.requireNonNull(runId, "runId");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(processingDate, "processingDate");
        Objects.requireNonNull(tenant, "tenant");
        if (tenant.isBlank()) {
            throw new IllegalArgumentException("tenant must not be blank");
        }
    }

    // -----------------------------------------------------------------------
    // Public static helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the currently-bound {@code BatchRunContext} from
     * {@link #BATCH_CTX}, or throws if no scope binding is active.
     *
     * <p>This is a convenience wrapper around {@code BATCH_CTX.get()} that
     * substitutes a more diagnostic error message than the underlying
     * {@link java.util.NoSuchElementException}: it tells the caller exactly
     * how to fix the problem (wrap the call in
     * {@code ScopedValue.where(...).run(...)}).
     *
     * @return the bound context; never {@code null}
     * @throws IllegalStateException if {@link #BATCH_CTX} is not currently
     *                               bound in the calling thread's scope
     */
    public static BatchRunContext current() {
        if (!BATCH_CTX.isBound()) {
            throw new IllegalStateException(
                    "BatchRunContext.BATCH_CTX is not bound; "
                            + "wrap the call in ScopedValue.where(BATCH_CTX, ctx).run(...)");
        }
        return BATCH_CTX.get();
    }

    /**
     * Runs the given {@link Runnable} inside a {@code ScopedValue} scope that
     * binds {@link #BATCH_CTX} to {@code ctx}. This is a thin convenience for
     * the verbatim AAP &sect;0.6.6 idiom:
     * {@snippet lang = "java":
     * ScopedValue.where(BatchRunContext.BATCH_CTX, ctx).run(body);
     * }
     *
     * <p>Within the scope of {@code body} (and any virtual threads it spawns
     * that inherit the scope), {@link #current()} and {@code BATCH_CTX.get()}
     * return {@code ctx}. When {@code body} returns&mdash;normally or by
     * throwing&mdash;the binding is automatically removed.
     *
     * @param ctx  the context to bind; must be non-{@code null}
     * @param body the runnable to execute within the scope; must be
     *             non-{@code null}
     * @throws NullPointerException if {@code ctx} or {@code body} is
     *                              {@code null}
     */
    public static void runWith(BatchRunContext ctx, Runnable body) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(body, "body");
        ScopedValue.where(BATCH_CTX, ctx).run(body);
    }

    /**
     * Builds a {@code BatchRunContext} from JVM system properties and
     * environment variables, applying the documented precedence order
     * (highest first):
     * <ol>
     *   <li>JVM system property (e.g.,
     *       {@code -Dcarddemo.batch.run-id=...})</li>
     *   <li>Environment variable (e.g., {@code CARDDEMO_RUN_ID=...})</li>
     *   <li>Compiled-in default (documented per component below)</li>
     * </ol>
     *
     * <p>Per-component defaults applied when neither a system property nor an
     * environment variable supplies a non-blank value:
     * <ul>
     *   <li>{@code runId} &mdash; an auto-generated UUID-based string of the
     *       form {@code "carddemo-<uuid>"}; this guarantees that
     *       {@link #runId()} is never blank.</li>
     *   <li>{@code processingDate} &mdash; {@link LocalDate#now()} using the
     *       JVM-default zone clock.</li>
     *   <li>{@code tenant} &mdash; {@link #DEFAULT_TENANT}
     *       ({@code "DEFAULT"}).</li>
     * </ul>
     *
     * <p>The processing-date string, when provided, MUST be parseable as
     * {@link java.time.format.DateTimeFormatter#ISO_LOCAL_DATE} (the ISO
     * {@code yyyy-MM-dd} format used by
     * {@link LocalDate#parse(CharSequence)}). If not, this method throws
     * {@link IllegalArgumentException} wrapping the underlying
     * {@link DateTimeParseException}.
     *
     * <p>The application-properties (file) precedence layer is NOT consulted
     * here; that layer is the responsibility of the calling application's
     * {@code main(...)} when it loads {@code application.properties}. This
     * method exists for callers (especially tests and ad-hoc utilities) that
     * want to obtain a context with no additional dependencies.
     *
     * @return a validated {@code BatchRunContext}; never {@code null}
     * @throws IllegalArgumentException if a configured value fails validation
     *                                  (e.g., the processing-date value is
     *                                  not a valid ISO_LOCAL_DATE; a
     *                                  configured run-id is blank but
     *                                  explicitly set; a configured tenant
     *                                  is blank but explicitly set)
     */
    public static BatchRunContext fromEnvironment() {
        // runId: lookup or auto-generate from UUID per application.properties.example §5
        String resolvedRunId = lookup(PROP_RUN_ID, ENV_RUN_ID);
        if (resolvedRunId == null || resolvedRunId.isBlank()) {
            resolvedRunId = GENERATED_RUN_ID_PREFIX + UUID.randomUUID();
        }

        // processingDate: lookup and parse, or default to LocalDate.now()
        LocalDate resolvedProcessingDate;
        String dateStr = lookup(PROP_PROCESSING_DATE, ENV_PROCESSING_DATE);
        if (dateStr == null || dateStr.isBlank()) {
            resolvedProcessingDate = LocalDate.now();
        } else {
            try {
                resolvedProcessingDate = LocalDate.parse(dateStr);
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException(
                        "carddemo.batch.processing-date value '" + dateStr
                                + "' is not a valid ISO_LOCAL_DATE (yyyy-MM-dd)",
                        ex);
            }
        }

        // tenant: lookup or default to DEFAULT_TENANT
        String resolvedTenant = lookup(PROP_TENANT, ENV_TENANT);
        if (resolvedTenant == null || resolvedTenant.isBlank()) {
            resolvedTenant = DEFAULT_TENANT;
        }

        return new BatchRunContext(resolvedRunId, resolvedProcessingDate, resolvedTenant);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves a configuration value with the documented precedence: JVM
     * system property first, environment variable second, otherwise
     * {@code null}.
     *
     * <p>The method intentionally returns {@code null} (rather than an empty
     * string) when neither source supplies a value, so that callers can
     * distinguish &quot;not set&quot; from &quot;set to empty&quot;; in
     * practice {@link #fromEnvironment()} treats both as &quot;use the
     * default&quot;.
     *
     * @param systemPropertyKey JVM system-property key (e.g.,
     *                          {@code carddemo.batch.run-id}); must be non-
     *                          {@code null}
     * @param environmentKey    environment-variable key (e.g.,
     *                          {@code CARDDEMO_RUN_ID}); must be non-
     *                          {@code null}
     * @return the resolved value, or {@code null} if neither source provides
     *         one
     */
    private static String lookup(String systemPropertyKey, String environmentKey) {
        String systemProperty = System.getProperty(systemPropertyKey);
        if (systemProperty != null) {
            return systemProperty;
        }
        return System.getenv(environmentKey);
    }
}
