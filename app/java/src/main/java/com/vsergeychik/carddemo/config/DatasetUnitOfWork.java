package com.vsergeychik.carddemo.config;

import com.vsergeychik.carddemo.common.DatasetIntegrityException;
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
 *   <li>{@link #commitRefusal(String, String)} lets an operation that has already changed rows
 *       <em>stop</em> the commit, for the one case where reporting a status would report damage that
 *       then stands.</li>
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
     * The template that persists one verb on its own, independent of whatever encloses it.
     *
     * <p>{@link TransactionDefinition#PROPAGATION_REQUIRES_NEW}, which is the whole point:
     * {@link #persistVerb(String, Supplier)} exists for the non-CICS batch programs, whose datasets are
     * defined {@code RECOVERY(NONE)} ({@code app/csd/CARDDEMO.CSD:9} and its seven siblings) and which
     * issue no syncpoint at all. There, each {@code WRITE} and {@code REWRITE} is durable the moment it
     * completes, and an abend leaves everything already written in place. A suspended-and-committed
     * inner transaction reproduces that; joining the enclosing one would let a later failure undo work
     * the COBOL had already made permanent.
     */
    private final TransactionTemplate verbTemplate;

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

        TransactionTemplate verb = new TransactionTemplate(transactionManager);
        verb.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verb.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        verb.afterPropertiesSet();
        this.verbTemplate = verb;
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
     * Persists one COBOL I/O verb on its own, so that nothing which happens afterwards can undo it.
     *
     * <p><strong>This is the non-CICS batch contract, and it is the opposite of
     * {@link #execute(String, Supplier)}.</strong> The two exist because the estate has two genuinely
     * different persistence models and using one for the other loses parity in one direction or the
     * other:
     * <ul>
     *   <li>The <strong>online</strong> programs run under CICS and reach a syncpoint at task end, so a
     *       paragraph that rewrites two datasets either applies both or neither. {@code execute} joins
     *       one boundary around the pair, which is what {@code 9600-WRITE-PROCESSING} needs.</li>
     *   <li>The <strong>batch</strong> programs open their datasets directly, issue no syncpoint of any
     *       kind, and every file they touch is defined {@code RECOVERY(NONE)}
     *       ({@code app/csd/CARDDEMO.CSD:9, 21, 33, 46, 59, 72, 84, 96}). A {@code REWRITE} there is
     *       durable when it completes, and {@code CALL 'CEE3ABD'} afterwards terminates the step without
     *       taking it back. {@code CBACT04C} depends on this: {@code 1050-UPDATE-ACCOUNT} rewrites the
     *       <em>previous</em> account at {@code app/cbl/CBACT04C.cbl:356} and processing then continues
     *       into the next account group, where a failed read, a failed rate lookup or a failed
     *       {@code WRITE} abends at {@code :632}. The rewritten account stays rewritten.</li>
     * </ul>
     *
     * <p>Without this, a chunk transaction would roll the rewrite back and a re-run would apply the same
     * interest a second time to an account whose cycle amounts had never been zeroed - a financial
     * difference, not a bookkeeping one.
     *
     * <p>Suspending the enclosing transaction is deliberate and is what {@code REQUIRES_NEW} means here:
     * the step's own transaction still exists for Spring Batch's bookkeeping, and this verb commits
     * outside it.
     *
     * @param verb the COBOL verb and paragraph being persisted, used only to name it in a failure
     * @param work the body: exactly one dataset-mutating call, and nothing else
     * @param <T>  the body's result type
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
     * <p>The same independence {@link #persistVerb(String, Supplier)} gives a batch write, and for a
     * closely related reason. A disposition is the initiator's work, not the program's: when a step ends,
     * MVS applies each DD's normal or abnormal disposition, and it does so <em>after</em> the step -
     * outside anything the step itself did. A disposition enrolled in the step's transaction would be
     * undone by the very failure that triggered it, which is the one outcome that cannot be right.
     *
     * <p>Separate from {@code persistVerb} in name only - both need one new boundary - because a call site
     * that reads {@code persistVerb("DELETE")} would suggest the program issued a verb it does not have.
     * {@code CBACT04C} contains no statement that discards its output; {@code app/jcl/INTCALC.jcl:37}
     * does, through {@code DISP=(NEW,CATLG,DELETE)}.
     *
     * @param <T>         what applying it reports
     * @param disposition the DD name and disposition, for attribution in a diagnostic
     * @param work        the body that applies it
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

    /**
     * Builds the refusal to let the current unit of work commit, because something it has already done
     * must not stand. The caller throws what this returns.
     *
     * <h2>Why a status is not enough here</h2>
     * <p>A repository normally reports a bad outcome as a {@code FILE STATUS} and lets the caller's guard
     * chain decide, which is faithful: that is what the COBOL does. That model holds while the failed
     * operation changed nothing. It breaks for exactly one case - a rewrite that matched more rows than
     * the key names - because by the time the row count is known the rows have already been replaced. A
     * status returned from there is a report of damage, and returning it normally lets
     * {@link #execute(String, java.util.function.Supplier)} commit on the way out. The caller sees an
     * error and the dataset keeps the change.
     *
     * <p>A CICS {@code REWRITE} cannot produce that state. It writes the record the task holds a lock on,
     * one record, so there is no multi-record outcome for the COBOL to have code for -
     * {@code app/cbl/CBACT04C.cbl:352-356} rewrites the record it read,
     * {@code app/cbl/COACTUPC.cbl:4065-4081} the one it locked, {@code app/cbl/COCRDUPC.cbl:1477-1492}
     * likewise. Preserving behaviour therefore means the state cannot reach the dataset, and the only way
     * to guarantee that is to stop the commit.
     *
     * <h2>Why the mechanism is a throw and not a rollback-only flag</h2>
     * <p>Marking the transaction rollback-only would need the {@code TransactionStatus} this module's
     * boundary is holding, and that status is reachable only from the declarative interceptor's own
     * scope - {@code TransactionAspectSupport.currentTransactionStatus()} raises
     * {@code NoTransactionException} inside a {@link TransactionTemplate} body even though a real
     * transaction is open, because the template does not publish it. The alternative, publishing it
     * through a thread-local of this class's own, would be exactly the static mutable state this module
     * refuses everywhere else.
     *
     * <p>Throwing needs none of that and is stronger. {@link TransactionTemplate} rolls back on any
     * unchecked exception, so inside {@link #execute(String, java.util.function.Supplier)} the throw
     * <em>is</em> the rollback and the caller cannot swallow the refusal by ignoring a return value. The
     * type thrown extends {@link IllegalStateException}, so every layer that already treats one of those
     * from the data-access layer as non-recoverable keeps working unchanged.
     *
     * <h2>Why no transaction is still a throw</h2>
     * <p>Without a transaction the change was already committed by the connection's own autocommit before
     * this method was reached. There is nothing left to roll back and nothing truthful to report as a
     * status, so the honest response is to fail loudly at the wiring defect that allowed a rewrite outside
     * a unit of work. That is the same trade {@link #requireActive(String, String)} makes, and for the
     * same reason: a loud wiring bug is worth far more than a silent correctness one. Only the message
     * differs between the two cases, because only the remedy does.
     *
     * @param operation the operation being refused, named as the caller's method
     * @param reason    what went wrong, in terms of what was observed - never a record's content
     * @return the exception the caller must throw, so that the refusal is visible as a throw at the call
     *         site rather than hidden inside a helper
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
