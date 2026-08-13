package com.vsergeychik.carddemo.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a handful of tasks at once, under a bound, and gives every result and every failure back to the
 * calling thread.
 *
 * <h2>What this replaces, and why it is worth a class</h2>
 * Three tests in this module proved statelessness by starting raw {@link Thread}s and joining them, and
 * each got some part of it wrong in a way that is invisible while the code under test is correct:
 * <ul>
 *   <li><strong>A shared, unsynchronised sink.</strong> Two threads calling {@code add} on the same
 *       {@code ArrayList} is a data race. It usually appears to work; when it does not, it drops an
 *       element or corrupts the backing array, and the test fails as a wrong <em>size</em> with nothing
 *       pointing at the race.</li>
 *   <li><strong>An unbounded {@code join()}.</strong> A task that deadlocks hangs the build instead of
 *       failing it - the run has to be killed from outside, and no report is written for the suite that
 *       hung, so the surefire record loses it entirely.</li>
 *   <li><strong>A lost exception.</strong> A throwable escaping a bare {@code Runnable} kills its thread
 *       and goes to the default handler, which prints it and moves on. The test then asserts on state the
 *       task never produced and reports a missing element rather than the exception that caused it. This
 *       is the failure mode that costs the most time to diagnose, because the real cause is in the log
 *       above the failure rather than in it.</li>
 *   <li><strong>A bounded join whose timeout is not checked.</strong> {@link Thread#join(long)} returns
 *       whether or not the thread finished. Proceeding to assert afterwards reads whatever the task had
 *       managed so far and calls it the answer.</li>
 * </ul>
 *
 * <p>{@link Future} answers all four: results come back to one thread with no shared mutable sink, the
 * wait is bounded, {@link Future#get(long, TimeUnit)} throws {@link TimeoutException} when the bound is
 * hit rather than lying about completion, and a task's throwable arrives as the cause of an
 * {@link ExecutionException} instead of disappearing. The pool is always shut down, so a task that will
 * not stop cannot outlive the test that started it.
 *
 * <p>A JUnit {@code @Timeout} on the test method is still worth having on top of this, and the three
 * callers have one: it bounds everything, including the setup and the assertions, where this class bounds
 * only the tasks.
 */
public final class ConcurrentTasks {

    /**
     * How long a task gets. Generous on purpose - this is a deadlock bound, not a performance one, and a
     * loaded CI machine running the whole module in parallel forks must not trip it.
     */
    public static final long TIMEOUT_SECONDS = 30L;

    private ConcurrentTasks() {
        throw new AssertionError("A holder of static helpers is not instantiated");
    }

    /**
     * Runs every task on its own thread and returns their results in the order the tasks were given.
     *
     * <p>Each task is submitted before any is awaited, so tasks that rendezvous with one another - through
     * a {@code CyclicBarrier}, which is how these tests prove two executions really were concurrent - can
     * do so. The pool is sized to the task count for the same reason: a pool smaller than the rendezvous
     * would deadlock every time, and it would look like a defect in the code under test.
     *
     * <p>Takes a {@link List} rather than a varargs array deliberately. A {@code Callable<T>...} parameter
     * is a generic array, which Java cannot create soundly - the compiler warns about heap pollution, and
     * the alternative of building the array with a raw type warns as well. Neither warning is
     * suppressible without hiding a real category of mistake, and a list costs the caller one
     * {@code List.of(...)}.
     *
     * @param tasks the tasks; at least one
     * @param <T>   the result type
     * @return each task's result, in the order the tasks were given
     */
    public static <T> List<T> runAll(List<? extends Callable<T>> tasks) {
        Objects.requireNonNull(tasks, "Tasks are required");
        assertThat(tasks).as("at least one task is required").isNotEmpty();

        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> pending = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                pending.add(pool.submit(task));
            }
            List<T> results = new ArrayList<>(tasks.size());
            for (int i = 0; i < pending.size(); i++) {
                results.add(await(pending.get(i), i));
            }
            return results;
        } finally {
            // Unconditional, and shutdownNow rather than shutdown: a task that timed out is still
            // running, and leaving it alive would let it interfere with the next test in the same JVM
            // fork. Interrupting it is the only way to get the thread back.
            pool.shutdownNow();
            assertThat(terminated(pool))
                    .as("the pool must terminate - a task still running after shutdownNow is ignoring "
                            + "interruption, and it would outlive this test and corrupt the next")
                    .isTrue();
        }
    }

    /**
     * Runs two tasks at once - the shape every caller in this module needs.
     *
     * @param first  the first task
     * @param second the second task
     * @param <T>    the result type
     * @return both results, first then second
     */
    public static <T> List<T> runBoth(Callable<T> first, Callable<T> second) {
        return runAll(List.of(first, second));
    }

    /**
     * Runs every task on its own thread and discards the results, for tasks that assert by side effect.
     *
     * @param tasks the tasks; at least one
     */
    public static void runAllRunnables(List<? extends Runnable> tasks) {
        Objects.requireNonNull(tasks, "Tasks are required");
        List<Callable<Void>> callables = new ArrayList<>(tasks.size());
        for (Runnable task : tasks) {
            callables.add(() -> {
                task.run();
                return null;
            });
        }
        runAll(callables);
    }

    /**
     * Waits for one task under the bound, translating both failure modes into a readable assertion.
     *
     * @param pending the task's future
     * @param ordinal its position in the submitted list, for the message
     * @param <T>     the result type
     * @return the task's result
     */
    private static <T> T await(Future<T> pending, int ordinal) {
        try {
            return pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException notFinished) {
            pending.cancel(true);
            return fail("Task %d did not finish within %d seconds. That is a deadlock or a livelock in "
                    + "the code under test, and it is reported here rather than hanging the build.",
                    ordinal, TIMEOUT_SECONDS);
        } catch (ExecutionException failed) {
            // The task's own throwable, rethrown on the calling thread so the assertion library reports
            // it as the cause. Without this it would have died on the worker thread and the test would
            // have failed as a missing result instead.
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Task " + ordinal + " failed", cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return fail("Interrupted while waiting for task %d", ordinal, interrupted);
        }
    }

    /**
     * Awaits pool termination without letting an interrupt escape as a checked exception.
     *
     * @param pool the pool being shut down
     * @return whether it terminated within the bound
     */
    private static boolean terminated(ExecutorService pool) {
        try {
            return pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
