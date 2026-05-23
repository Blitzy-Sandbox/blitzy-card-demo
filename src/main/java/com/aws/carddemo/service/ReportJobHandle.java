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
package com.aws.carddemo.service;

import java.util.Objects;

/**
 * Opaque, immutable handle returned by {@link ReportJobDispatcher#dispatch(ReportJobParameters)}
 * after the {@code TRNRPT00} report job has been accepted by the asynchronous
 * dispatcher. Represents the Java equivalent of the JES job-id token that the
 * COBOL {@code WIRTE-JOBSUB-TDQ} paragraph (lines 515-535 of
 * {@code app/cbl/CORPT00C.cbl}) would have observed implicitly via the
 * {@code DFHRESP(NORMAL)} response code from {@code EXEC CICS WRITEQ TD}.
 *
 * <h2>COBOL Provenance — CORPT00C.cbl</h2>
 *
 * <p>The COBOL {@code WIRTE-JOBSUB-TDQ} paragraph (lines 515-535) submits the
 * report job to the JES internal reader by writing the assembled JCL records
 * to the {@code JOBS} extra-partition transient data queue. The mainframe
 * environment then assigns a job number (e.g., {@code JOB12345}) that
 * downstream operators can use to track the job; the COBOL program does not
 * surface this identifier to the screen, but it is the load-bearing token
 * for the operator's ability to "find my job in JES". The Java migration
 * surfaces an equivalent token explicitly via this handle so the controller
 * layer can include it in the response payload for downstream callers
 * (per AAP §0.10.4 "External interfaces consumed by downstream systems
 * MUST NOT change").
 *
 * <h2>Immutability and Equality</h2>
 *
 * <p>The handle is a value object: the single {@link #identifier} field is
 * {@code final}, set exactly once at construction time, and the
 * {@link #equals(Object)} / {@link #hashCode()} contract is based on that
 * field. This matters for the {@code ReportSubmissionServiceTest}
 * {@code AsyncDispatch} group: tests use
 * {@code assertThat(result.getJobHandle()).isEqualTo(expectedHandle)} to
 * prove that the production service surfaces the dispatcher's handle
 * verbatim to the caller (no copying, no recomputation).
 *
 * <h2>Construction Contract — Factory Method Only</h2>
 *
 * <p>Instances are created exclusively via {@link #of(String)}; the
 * constructor is private. This convention matches the {@code success(...)}
 * / {@code failure(...)} factory pattern used by {@link UserDeleteResult}
 * and the rest of the service-package DTOs (AAP §0.10.10 style
 * consistency).
 *
 * @see ReportJobDispatcher
 * @see ReportJobParameters
 * @see ReportSubmissionResult
 */
public final class ReportJobHandle {

    /**
     * The opaque dispatcher-assigned identifier — the Java equivalent of a
     * JES {@code JOBnnnnn} token. Carries no further semantic content; the
     * caller treats it as an opaque key for subsequent status lookups.
     * Guaranteed non-{@code null} and non-blank by the {@link #of(String)}
     * factory.
     */
    private final String identifier;

    /**
     * Private constructor enforcing the {@link #of(String)} factory-only
     * construction contract.
     *
     * @param identifier the dispatcher-assigned identifier (validated as
     *                   non-{@code null} and non-blank by the factory)
     */
    private ReportJobHandle(String identifier) {
        this.identifier = identifier;
    }

    /**
     * Builds a {@code ReportJobHandle} carrying the supplied identifier.
     *
     * <p>The factory enforces the non-{@code null}, non-blank invariant by
     * eagerly throwing {@link NullPointerException} or
     * {@link IllegalArgumentException} so downstream code can treat
     * {@link #getIdentifier()} as never-null without additional guards.
     *
     * @param identifier the dispatcher-assigned identifier (e.g., a JES
     *                   job number or Spring Batch {@code JobExecution} id);
     *                   must be non-{@code null} and non-blank
     * @return a fresh, immutable handle wrapping {@code identifier}
     * @throws NullPointerException     if {@code identifier} is {@code null}
     * @throws IllegalArgumentException if {@code identifier} is blank
     */
    public static ReportJobHandle of(String identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        if (identifier.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "identifier must not be blank");
        }
        return new ReportJobHandle(identifier);
    }

    /**
     * @return the opaque dispatcher-assigned identifier; never {@code null}
     *         and never blank (guaranteed by {@link #of(String)})
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * Value-equality based on the wrapped {@link #identifier}. Two handles
     * with the same identifier are interchangeable; this enables
     * {@code assertThat(actualHandle).isEqualTo(expectedHandle)} assertions
     * in the {@code ReportSubmissionServiceTest} {@code AsyncDispatch} group
     * without relying on reference identity.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReportJobHandle that)) {
            return false;
        }
        return identifier.equals(that.identifier);
    }

    @Override
    public int hashCode() {
        return identifier.hashCode();
    }

    /**
     * @return a short, human-readable rendering used for diagnostic logging
     *         only (the canonical accessor is {@link #getIdentifier()}).
     *         Does not expose any PCI/PII content because the identifier is
     *         an opaque job-id token, not user data.
     */
    @Override
    public String toString() {
        return "ReportJobHandle[" + identifier + "]";
    }
}
