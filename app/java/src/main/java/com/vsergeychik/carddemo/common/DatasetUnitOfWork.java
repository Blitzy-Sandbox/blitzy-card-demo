package com.vsergeychik.carddemo.common;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The unit of work a CICS task is, made explicit: the boundary within which a read-for-update holds its
 * lock and a rewrite either lands with it or with nothing.
 */
@Component
public final class DatasetUnitOfWork {
    private final TransactionTemplate transactionTemplate;

    private final TransactionTemplate verbTemplate;

    public DatasetUnitOfWork(PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "A PlatformTransactionManager is required: a unit of "
                + "work with no manager cannot open a boundary, and a locking read outside a boundary "
                + "holds no lock");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        template.afterPropertiesSet();
        this.transactionTemplate = template;

        TransactionTemplate verb = new TransactionTemplate(transactionManager);
        verb.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verb.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        verb.afterPropertiesSet();
        this.verbTemplate = verb;
    }

    /**
     * Runs a body inside one unit of work and returns its result.
     *
     * @param <T> the body's result type
     * @param description what the unit of work is doing, used only to name it in a failure
     * @param work the body
     * @return the body's result
     * @throws NullPointerException if {@code description} or {@code work} is {@code null}
     */
    public <T> T execute(String description, Supplier<T> work) {
        Objects.requireNonNull(description, "A description is required so a failed unit of work can name "
                + "what it was doing");
        Objects.requireNonNull(work, "A body is required to run in unit of work '" + description + "'");
        return transactionTemplate.execute(status -> work.get());
    }

    public void execute(String description, Runnable work) {
        Objects.requireNonNull(work, "A body is required to run in unit of work '" + description + "'");
        execute(description, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Persists one COBOL I/O verb on its own, so that nothing which happens afterwards can undo it.
     *
     * @param <T> the body's result type
     * @param verb the COBOL verb and paragraph being persisted, used only to name it in a failure
     * @param work the body: exactly one dataset-mutating call, and nothing else
     * @return the body's result
     * @throws NullPointerException if {@code verb} or {@code work} is {@code null}
     */
    public <T> T persistVerb(String verb, Supplier<T> work) {
        Objects.requireNonNull(verb, "A verb name is required so a failed write can name what it was "
                + "persisting");
        Objects.requireNonNull(work, "A body is required to persist as verb '" + verb + "'");
        return verbTemplate.execute(status -> work.get());
    }

    /**
     * Applies a DD statement's disposition, independently of any boundary the caller is inside.
     *
     * @param <T> what applying it reports
     * @param disposition the DD name and disposition, for attribution in a diagnostic
     * @param work the body that applies it
     * @return whatever {@code work} returns
     * @throws NullPointerException if either argument is {@code null}
     */
    public <T> T persistDisposition(String disposition, Supplier<T> work) {
        Objects.requireNonNull(disposition, "A disposition name is required so a failed disposition can "
                + "name what it was applying");
        Objects.requireNonNull(work, "A body is required to apply disposition '" + disposition + "'");
        return verbTemplate.execute(status -> work.get());
    }

    /**
     * Whether a transaction is actually open on the calling thread.
     *
     * @return {@code true} when a locking read taken now would keep its lock
     */
    public static boolean active() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    /**
     * Requires a unit of work to be open, and refuses the operation when none is.
     *
     * @param operation the operation being attempted, named as the caller's method
     * @param dsname the dataset the operation targets
     * @throws NullPointerException if {@code operation} or {@code dsname} is {@code null}
     * @throws IllegalStateException if no transaction is open
     */
    public static void requireActive(String operation, String dsname) {
        Objects.requireNonNull(operation, "An operation name is required to report a missing unit of "
                + "work against");
        Objects.requireNonNull(dsname, "A dataset name is required to report a missing unit of work "
                + "against");
        if (active()) {
            return;
        }
        throw new IllegalStateException(operation + " on dataset '" + dsname + "' requests a record lock, "
                + "and no transaction is open on this thread, so the lock would be released before the "
                + "caller could use it. A read-for-update, the comparison that follows it and the "
                + "rewrite that follows that are one unit of work in CICS; run them inside "
                + DatasetUnitOfWork.class.getSimpleName() + ".execute(..) so they are one here too");
    }

    /**
     * Requires a unit of work to be open before a record is stored, and refuses the operation when none is.
     *
     * @param operation the operation being attempted, named as the caller's method and its COBOL verb
     * @param dsname the dataset the operation would have changed
     * @throws NullPointerException if {@code operation} or {@code dsname} is {@code null}
     * @throws IllegalStateException if no transaction is open, in which case nothing has been attempted
     */
    public static void requireActiveToPersist(String operation, String dsname) {
        Objects.requireNonNull(operation, "An operation name is required to report a missing unit of "
                + "work against");
        Objects.requireNonNull(dsname, "A dataset name is required to report a missing unit of work "
                + "against");
        if (active()) {
            return;
        }
        throw new IllegalStateException(operation + " on dataset '" + dsname + "' changes stored records, "
                + "and no transaction is open on this thread, so the change would be rolled back when the "
                + "connection returned to the pool - the pool is configured auto-commit: false on purpose, "
                + "because commit boundaries belong to the step and the service layer - while the row "
                + "count the statement reported would be handed back as a completed write. There is no "
                + "FILE STATUS meaning 'written, then discarded', so nothing is attempted and this is "
                + "reported as the wiring defect it is. Run the write inside "
                + DatasetUnitOfWork.class.getSimpleName() + ".execute(..) for an online task, "
                + DatasetUnitOfWork.class.getSimpleName() + ".persistVerb(..) for a batch verb, or inside "
                + "the Spring Batch step's own transaction");
    }

    /**
     * Builds the refusal to let the current unit of work commit, because something it has already done must
     * not stand.
     *
     * <p>A CICS {@code REWRITE} cannot produce that state.
     *
     * @param operation the operation being refused, named as the caller's method
     * @param reason what went wrong, in terms of what was observed - never a record's content
     * @return the exception the caller must throw, so that the refusal is visible as a throw at the call
     *     site rather than hidden inside a helper
     * @throws NullPointerException if {@code operation} or {@code reason} is {@code null}
     */
    public static DatasetIntegrityException commitRefusal(String operation, String reason) {
        Objects.requireNonNull(operation, "An operation name is required to refuse a commit against");
        Objects.requireNonNull(reason, "A reason is required so a refused commit can be diagnosed");
        if (active()) {
            return new DatasetIntegrityException(operation, operation + " must not be allowed to stand - "
                    + reason + " - so the unit of work is being rolled back rather than reported as a "
                    + "file status, because a status would let the change commit on the way out. A CICS "
                    + "REWRITE writes the one record the task holds a lock on and has no multi-record "
                    + "outcome, so this state cannot reach the dataset.");
        }
        return new DatasetIntegrityException(operation, operation + " must not be allowed to stand - "
                + reason + " - but no transaction is open on this thread, so the change has already been "
                + "committed by the connection's own autocommit and cannot be undone. A rewrite is one "
                + "half of a CICS read-for-update and rewrite pair and belongs inside "
                + DatasetUnitOfWork.class.getSimpleName() + ".execute(..); run it there so that a "
                + "refusal can actually refuse.");
    }
}
