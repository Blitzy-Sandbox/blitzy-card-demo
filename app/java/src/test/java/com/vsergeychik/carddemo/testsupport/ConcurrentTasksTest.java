package com.vsergeychik.carddemo.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The negative controls for {@link ConcurrentTasks}: proof that it detects each failure mode it exists to
 * detect, rather than merely being quieter about them.
 *
 * <h2>Why a harness needs its own tests</h2>
 * The three concurrency tests this harness replaced all passed, every time, for as long as the code they
 * exercised was correct. What made them worth changing was how they behaved when it was not: one dropped a
 * result to a data race, one hung the build, and all three lost a task's exception. A replacement that made
 * those same claims and had never been shown to fail would be no better founded than what it replaced - so
 * each of the four claims is exercised here against a task that deliberately breaks it.
 */
@DisplayName("ConcurrentTasks - the bounded, result-returning, exception-preserving task runner")
class ConcurrentTasksTest {

    @Test
    @DisplayName("results come back in submission order, one per task, with no shared sink")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void resultsComeBackInSubmissionOrder() {
        List<String> results = ConcurrentTasks.runAll(
                List.of(() -> "first", () -> "second", () -> "third"));

        assertThat(results)
                .as("order is the submission order, not the completion order - which is what lets a "
                        + "caller assert per task instead of falling back to anySatisfy")
                .containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("tasks really do run at once: a rendezvous between them completes")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void tasksRunConcurrently() {
        // The claim that matters for a statelessness test. If the runner executed tasks one after
        // another, this barrier would never be satisfied and the task would time out - so a passing
        // assertion here is what licenses the callers to say their two turns overlapped.
        CyclicBarrier bothArrived = new CyclicBarrier(2);
        AtomicInteger arrived = new AtomicInteger();

        List<Integer> order = ConcurrentTasks.runBoth(
                () -> {
                    bothArrived.await(ConcurrentTasks.TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return arrived.incrementAndGet();
                },
                () -> {
                    bothArrived.await(ConcurrentTasks.TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return arrived.incrementAndGet();
                });

        assertThat(order).as("both tasks passed the barrier, so both were in flight together")
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("a task's runtime exception is rethrown on the calling thread, not lost on the worker")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aTaskExceptionReachesTheCaller() {
        IllegalStateException raised = new IllegalStateException("the work area was shared");

        assertThatThrownBy(() -> ConcurrentTasks.runAll(List.<Callable<String>>of(() -> {
            throw raised;
        })))
                .as("the old shape let this die on the worker thread: the default handler printed it, "
                        + "the result list came back short, and the test failed as a wrong size")
                .isSameAs(raised);
    }

    @Test
    @DisplayName("an Error is rethrown too - including the AssertionError of an assertion inside a task")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aTaskAssertionFailureReachesTheCaller() {
        assertThatThrownBy(() -> ConcurrentTasks.runAll(List.<Callable<Void>>of(() -> {
            assertThat("actual").as("asserted inside a task").isEqualTo("expected");
            return null;
        })))
                .as("a task that asserts must be able to fail the test, which needs Error to propagate "
                        + "and not just Exception")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("asserted inside a task");
    }

    @Test
    @DisplayName("a task that never finishes fails as a timeout naming the task, and does not hang")
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void aHangingTaskFailsRatherThanHangs() {
        // A barrier of two with only one arrival: the task blocks forever, which is what a deadlock in
        // the code under test looks like from here. Under the old unbounded join this hung the build and
        // no surefire report was written for the suite at all, so the run lost the suite as well as the
        // time. The wait is bounded, so it fails - and the failure names which task and how long it was
        // given.
        CyclicBarrier nobodyElseIsComing = new CyclicBarrier(2);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> ConcurrentTasks.runAll(List.<Callable<String>>of(() -> {
            nobodyElseIsComing.await();
            return "unreachable";
        })))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("did not finish within")
                .hasMessageContaining("deadlock");
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);

        assertThat(elapsedSeconds)
                .as("it waited its bound and then gave up, rather than returning early or never")
                .isBetween(ConcurrentTasks.TIMEOUT_SECONDS, ConcurrentTasks.TIMEOUT_SECONDS * 3);
    }

    @Test
    @DisplayName("the pool is always shut down, so a task cannot outlive the test that started it")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void thePoolIsShutDownOnEveryPath() {
        // Proven by the interrupt reaching a task that is still running when the runner gives up: an
        // interrupted task can only observe that if shutdownNow ran, and shutdownNow only runs in the
        // finally block. Asserted on the success path too, since a leaked pool from a passing test is
        // just as capable of interfering with the next one.
        AtomicInteger interrupted = new AtomicInteger();
        CyclicBarrier nobodyElseIsComing = new CyclicBarrier(2);

        assertThatThrownBy(() -> ConcurrentTasks.runAll(List.<Callable<String>>of(() -> {
            try {
                nobodyElseIsComing.await();
            } catch (InterruptedException wokenByShutdown) {
                interrupted.incrementAndGet();
                Thread.currentThread().interrupt();
            }
            return "gave up";
        }))).isInstanceOf(AssertionError.class);

        assertThat(interrupted.get())
                .as("the hung task was interrupted, which only happens because shutdownNow runs in a "
                        + "finally rather than after the assertions")
                .isEqualTo(1);

        assertThatCode(() -> ConcurrentTasks.runAll(List.<Callable<String>>of(() -> "done")))
                .as("and the success path shuts down cleanly, so nothing leaks from a passing test")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the Runnable form runs every task, for tasks that assert by side effect")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void theRunnableFormRunsEveryTask() {
        AtomicInteger ran = new AtomicInteger();

        ConcurrentTasks.runAllRunnables(
                List.of(ran::incrementAndGet, ran::incrementAndGet, ran::incrementAndGet));

        assertThat(ran.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("no tasks is a caller mistake and is refused, not silently a no-op")
    void noTasksIsRefused() {
        assertThatThrownBy(() -> ConcurrentTasks.runAll(List.<Callable<String>>of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("at least one task");
    }
}
