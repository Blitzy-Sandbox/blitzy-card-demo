package com.vsergeychik.carddemo.common;

import java.util.Objects;

/**
 * Raised when a dataset operation has produced, or would produce, a state the COBOL original cannot
 * produce - so the state must not be allowed to stand.
 *
 * <h2>Why this is not a {@code FILE STATUS}</h2>
 *
 * <p>Every repository in this module reports its outcomes the way the COBOL reads them: a two-character
 * {@code FILE STATUS} for the batch programs, a {@code RESP}/{@code RESP2} pair for the online ones, and
 * the caller's guard chain decides what to do next. That model is faithful and it is deliberately kept
 * for everything the COBOL has a status for - not found, end of file, duplicate key, a backend that is
 * unavailable.
 *
 * <p>It stops being faithful for one class of outcome: a change that has already been applied to more
 * rows than the operation named. A CICS {@code REWRITE} rewrites the single record the task holds a lock
 * on, so there is no multi-record outcome for the COBOL to have a status for -
 * {@code app/cbl/CBACT04C.cbl:352-356} rewrites the record it read, {@code app/cbl/COACTUPC.cbl:4065-4081}
 * the one it locked under {@code 9600-WRITE-PROCESSING}, and {@code app/cbl/COCRDUPC.cbl:1477-1492}
 * likewise. Reporting such an outcome as a status would report damage that then commits normally on the
 * way out of the unit of work: the caller sees an error and the dataset keeps the change. Preserving
 * behaviour means the state cannot reach the dataset at all, and the only way to guarantee that from
 * inside a unit of work is to stop the commit - which is what throwing this does.
 *
 * <h2>Extends {@link IllegalStateException} on purpose</h2>
 *
 * <p>The condition <em>is</em> an illegal state, and every layer that already treats an
 * {@code IllegalStateException} from the data-access layer as a non-recoverable defect - the web error
 * boundary, the batch step's exception handling - continues to treat this one the same way without
 * change. The distinct type exists so that a test, and a reader, can tell a refused commit from an
 * ordinary precondition failure, not so that a new handling path has to be built for it.
 *
 * <h2>Carries no record content</h2>
 *
 * <p>The message names the operation, the dataset and what was counted. It never carries a record image,
 * a key value, an account number or a card number, for the same reason
 * {@link com.vsergeychik.carddemo.common.SensitiveDiagnostics} exists: a diagnostic that has to be
 * redacted before it can be read is a diagnostic nobody reads.
 *
 * @see com.vsergeychik.carddemo.config.DatasetUnitOfWork
 */
public final class DatasetIntegrityException extends IllegalStateException {

    /** Serialization identity, fixed so a rolling deployment cannot change it accidentally. */
    private static final long serialVersionUID = 1L;

    /**
     * The operation that produced the state, named as the caller's method.
     *
     * <p>Kept as a component rather than left inside the message so a test can assert on it without
     * matching prose, and so a log line can group by operation.
     */
    private final String operation;

    /**
     * Creates the exception.
     *
     * @param operation the operation being refused, named as the caller's method - never a record's
     *                  content
     * @param message   the full diagnostic, which must describe what was observed rather than what was
     *                  stored
     * @throws NullPointerException if {@code operation} or {@code message} is {@code null}
     */
    public DatasetIntegrityException(String operation, String message) {
        super(Objects.requireNonNull(message, "A message is required so a refused commit can be "
                + "diagnosed without reading the record it refused to write"));
        this.operation = Objects.requireNonNull(operation, "An operation name is required so a refused "
                + "commit can be attributed");
    }

    /**
     * The operation that was refused.
     *
     * @return the operation name the caller supplied, never {@code null}
     */
    public String operation() {
        return operation;
    }
}
