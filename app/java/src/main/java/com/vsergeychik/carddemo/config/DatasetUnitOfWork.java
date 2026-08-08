package com.vsergeychik.carddemo.config;

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
 *
 * <h2>What CICS gives for free and JDBC does not</h2>
 * <p>In the online programs a task <em>is</em> a unit of work. {@code EXEC CICS READ ... UPDATE} takes a
 * lock on the record, that lock is held until the implicit syncpoint at task {@code RETURN}, and
 * everything the task does in between - a second locking read, the comparison, the rewrite - happens
 * under it. {@code app/cbl/COACTUPC.cbl}'s {@code 9600-WRITE-PROCESSING} depends on precisely that: it
 * locks the account, locks the customer, performs {@code 9700-CHECK-CHANGE-IN-REC} to compare each
 * record against the copy the screen was painted from, and only then rewrites. The comparison is the
 * program's concurrency control, and it is only sound because no one else can change the records between
 * the read that fed it and the rewrite that follows.
 *
 * <p>JDBC gives none of that by default. A connection in auto-commit mode commits after every statement,
 * so a {@code SELECT ... FOR UPDATE} releases its lock the instant it returns, and the next statement may
 * arrive on a different pooled connection entirely. A read-compare-rewrite sequence run that way reads a
 * record, satisfies itself that nobody changed it, and then writes over whatever is there now - which is
 * the interleaving the COBOL check exists to prevent, reintroduced by the transport rather than by the
 * logic. The check still passes. It just no longer means anything.
 *
 * <h2>What this class does</h2>
 * <p>It is the one place a unit of work is opened, and it does two things:
 * <ul>
 *   <li>{@link #execute(String, Supplier)} runs a body inside a transaction, joining a caller's
 *       transaction if one is already open, so every statement inside it shares one connection and one
 *       commit. That is the Java equivalent of the CICS task boundary.</li>
 *   <li>{@link #requireActive(String, String)} lets a locking read <em>refuse</em> to run outside one.
 *       Issuing {@code FOR UPDATE} with nothing to hold the lock is not a smaller guarantee than a real
 *       lock - it is the appearance of one, which is worse, because it silences the question rather than
 *       answering it. A repository that cannot take the lock it was asked for says so.</li>
 * </ul>
 *
 * <h2>Deliberately not an optimistic-locking mechanism</h2>
 * <p>No version column, no {@code @Version}, no ETag, no last-modified timestamp is introduced here or
 * anywhere else. Adding one would be a schema change, which is excluded, and it would also duplicate a
 * check the COBOL already performs field by field - the migration's job is to make that existing check
 * effective, not to replace it with a different mechanism that behaves differently in the cases where
 * they disagree.
 *
 * <h2>Isolation is the deployment's, not this class's</h2>
 * <p>{@link TransactionDefinition#ISOLATION_DEFAULT} is used, so the backend's configured isolation
 * governs. Naming a level here would override a deployment decision this module has no basis to make,
 * and the lock the {@code FOR UPDATE} takes - which is what the COBOL relies on - is explicit rather
 * than a side effect of an isolation level.
 *
 * @see com.vsergeychik.carddemo.common.DatasetRelation#selectByKeyForUpdate(String)
 */
@Component
public final class DatasetUnitOfWork {

    /**
     * The template that opens and closes the boundary.
     *
     * <p>{@link TransactionDefinition#PROPAGATION_REQUIRED} so that a unit of work opened by a service
     * that spans two repositories - which is exactly what {@code 9600-WRITE-PROCESSING} is - is joined
     * rather than nested. Two nested transactions would commit the account rewrite and the customer
     * rewrite separately, which is one more commit than the COBOL has.
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * @param transactionManager the module's single transaction manager, declared by
     *                           {@link BatchConfig#transactionManager(javax.sql.DataSource)} over the
     *                           same {@code DataSource} the repositories read through - the same manager
     *                           and the same source, or the boundary would not enclose the statements
     * @throws NullPointerException if {@code transactionManager} is {@code null}
     */
    public DatasetUnitOfWork(PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "A PlatformTransactionManager is required: a unit of "
                + "work with no manager cannot open a boundary, and a locking read outside a boundary "
                + "holds no lock");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        template.afterPropertiesSet();
        this.transactionTemplate = template;
    }

    /**
     * Runs a body inside one unit of work and returns its result.
     *
     * <p>Commit on normal return, rollback on any exception. Both matter for parity: the commit is the
     * task's implicit syncpoint, and the rollback is what keeps a half-applied update - the account
     * rewritten, the customer not - from reaching the datasets, which is a state the CICS original cannot
     * produce and therefore has no code to recover from.
     *
     * @param description what the unit of work is doing, used only to name it in a failure
     * @param work        the body
     * @param <T>         the body's result type
     * @return the body's result
     * @throws NullPointerException if {@code description} or {@code work} is {@code null}
     */
    public <T> T execute(String description, Supplier<T> work) {
        Objects.requireNonNull(description, "A description is required so a failed unit of work can name "
                + "what it was doing");
        Objects.requireNonNull(work, "A body is required to run in unit of work '" + description + "'");
        return transactionTemplate.execute(status -> work.get());
    }

    /**
     * Runs a body inside one unit of work.
     *
     * @param description what the unit of work is doing
     * @param work        the body
     * @throws NullPointerException if {@code description} or {@code work} is {@code null}
     */
    public void execute(String description, Runnable work) {
        Objects.requireNonNull(work, "A body is required to run in unit of work '" + description + "'");
        execute(description, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Whether a transaction is actually open on the calling thread.
     *
     * <p>"Actually" is the operative word: this reports a real transaction with a real connection bound
     * to the thread, not merely a synchronisation that some frameworks activate for read-only work. Only
     * a real one holds a lock.
     *
     * @return {@code true} when a locking read taken now would keep its lock
     */
    public static boolean active() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    /**
     * Requires a unit of work to be open, and refuses the operation when none is.
     *
     * <p>Called by every locking read before it issues {@code FOR UPDATE}. The alternative - issue it
     * anyway - produces a read that looks like a CICS read-for-update, reports the same outcome, and
     * protects nothing, so the concurrency defect it creates surfaces as a rare and unreproducible
     * wrong balance rather than as a failure anyone can trace. Refusing turns a silent correctness bug
     * into a loud wiring bug, which is the trade worth making.
     *
     * @param operation the operation being attempted, named as the caller's method
     * @param dsname    the dataset the operation targets
     * @throws NullPointerException  if {@code operation} or {@code dsname} is {@code null}
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
}
