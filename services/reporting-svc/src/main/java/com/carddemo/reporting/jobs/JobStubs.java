package com.carddemo.reporting.jobs;

import java.util.UUID;

/**
 * Shared constants for the {@code [DEFERRED]} reporting job-stub handlers.
 *
 * <p>This is a package-private, non-instantiable holder for the values that keep every
 * job stub in this package <strong>honest</strong>: because none of the reporting jobs
 * actually execute in the walking skeleton, they must never emit an identifier or status
 * that fabricates work that never happened (see finding P4-M06 / AAP-35 "No fabricated
 * completion").</p>
 */
final class JobStubs {

    /**
     * The nil UUID ({@code 00000000-0000-0000-0000-000000000000}, RFC&nbsp;4122 §4.1.7).
     *
     * <p>Every {@code [DEFERRED]} job stub returns this sentinel as its {@code jobId}. It is a
     * valid {@code format: uuid} value (so the frozen OpenAPI contract's {@code jobId} field is
     * satisfied) while being universally recognizable as the "no real identifier" placeholder.
     * Crucially it is <em>not</em> a freshly-minted {@link UUID#randomUUID() random} identifier,
     * which would masquerade as a genuine server-assigned tracking id for a job that never ran and
     * is never stored — exactly the fabrication that finding P4-M06 flags.</p>
     */
    static final UUID DEFERRED_JOB_ID = new UUID(0L, 0L);

    private JobStubs() {
        // Non-instantiable constants holder.
    }
}
