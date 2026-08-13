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
 */
public final class ConcurrentTasks {
    public static final long TIMEOUT_SECONDS = 30L;

    private ConcurrentTasks() {
        throw new AssertionError("A holder of static helpers is not instantiated");
    }

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
            pool.shutdownNow();
            assertThat(terminated(pool))
                    .as("the pool must terminate - a task still running after shutdownNow is ignoring "
                            + "interruption, and it would outlive this test and corrupt the next")
                    .isTrue();
        }
    }

    public static <T> List<T> runBoth(Callable<T> first, Callable<T> second) {
        return runAll(List.of(first, second));
    }

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

    private static <T> T await(Future<T> pending, int ordinal) {
        try {
            return pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException notFinished) {
            pending.cancel(true);
            return fail("Task %d did not finish within %d seconds. That is a deadlock or a livelock in "
                    + "the code under test, and it is reported here rather than hanging the build.",
                    ordinal, TIMEOUT_SECONDS);
        } catch (ExecutionException failed) {
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

    private static boolean terminated(ExecutorService pool) {
        try {
            return pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
