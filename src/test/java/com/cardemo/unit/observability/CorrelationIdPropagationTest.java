/*
 * Program     : CorrelationIdPropagationTest
 * Application : CardDemo
 * Type        : JAVA TEST
 * Function    : Verifies the thread-boundary diagnostic context propagation published by
 *               CorrelationIdFilter, on which the batch listener of app/cbl/CBACT04C.cbl depends.
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cardemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cardemo.observability.CorrelationIdFilter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Contract of {@link CorrelationIdFilter#propagateJobInstanceId(String)}, the thread-boundary helper added so
 * that a batch listener can label a pooled thread and then put back exactly what it displaced.
 *
 * <p>This exists because finding M-03 was resolved by moving the discipline here rather than duplicating it in
 * {@code InterestCalculationJob}. The behaviour is security-relevant on two counts - the diagnostic context
 * feeds a text-based log format, so an unvalidated value is a log-injection vector, and a restore that could
 * itself throw would leave a pooled thread mislabelled - so both are pinned here rather than left to the one
 * happy path the job exercises.
 *
 * <p>It also covers {@link CorrelationIdFilter#enterBatchScope(long, String)} and
 * {@link CorrelationIdFilter#exitBatchScope()}, for the same reason one step further on. Findings H-02, H-03
 * and M-02 were one root cause - five hand-rolled copies of park-and-restore, two of them under private
 * re-spellings of the key names - and the remedy was to reduce them to the single implementation those helpers
 * provide. A property proved once on that implementation holds for every caller; the same property proved five
 * times in five job tests is five things that can drift apart again. The final nested class asserts the key
 * names structurally, because key drift raises no error and produces no output.
 */
@DisplayName("Diagnostic context propagation across a thread boundary (M-03, H-02, H-03, M-02)")
class CorrelationIdPropagationTest {

    private static final String KEY = CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID;

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Nested
    @DisplayName("A run establishes its own value and reports what it displaced")
    class RoundTrip {

        @Test
        @DisplayName("an absent entry is reported as null and the restore removes it")
        void absentEntryRoundTrips() {
            assertThat(CorrelationIdFilter.propagateJobInstanceId("4242")).isNull();
            assertThat(MDC.get(KEY)).isEqualTo("4242");

            assertThat(CorrelationIdFilter.propagateJobInstanceId(null)).isEqualTo("4242");
            assertThat(MDC.get(KEY))
                    .as("nothing may leak onto the next task to borrow this pooled thread")
                    .isNull();
        }

        @Test
        @DisplayName("an inherited entry is reported and put back, never destroyed")
        void inheritedEntryIsPutBack() {
            MDC.put(KEY, "7");

            assertThat(CorrelationIdFilter.propagateJobInstanceId("4242")).isEqualTo("7");
            assertThat(MDC.get(KEY)).isEqualTo("4242");

            CorrelationIdFilter.propagateJobInstanceId("7");
            assertThat(MDC.get(KEY))
                    .as("context owned by an outer scope survives the inner run")
                    .isEqualTo("7");
        }

        @Test
        @DisplayName("round-tripping the returned value always succeeds, so a restore cannot fail")
        void restoreNeverThrows() {
            for (final String inherited : List.of("0", "-1", "9223372036854775807", "!!not-a-number!!", "")) {
                MDC.clear();
                MDC.put(KEY, inherited);
                final String previous = CorrelationIdFilter.propagateJobInstanceId("1");
                assertThatCode(() -> CorrelationIdFilter.propagateJobInstanceId(previous))
                        .as("a restore of the value reported for inherited '%s' must not throw", inherited)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("The value is validated, because it reaches a text-based log format")
    class Validation {

        @Test
        @DisplayName("every value Long.toString can produce is accepted")
        void longDomainIsAccepted() {
            for (final long candidate : List.of(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)) {
                MDC.clear();
                final String rendered = Long.toString(candidate);
                assertThatCode(() -> CorrelationIdFilter.propagateJobInstanceId(rendered))
                        .as("Long.toString(%d) must be accepted", candidate)
                        .doesNotThrowAnyException();
                assertThat(MDC.get(KEY)).isEqualTo(rendered);
            }
        }

        @Test
        @DisplayName("a value carrying log-format or line structure is refused")
        void injectionVectorsAreRefused() {
            for (final String rejected : List.of("4242 INFO fabricated", "42\n42", "42\r\n", "4-2", "4 2",
                    "0x2a", "42;DROP", "%d", "{}")) {
                assertThatThrownBy(() -> CorrelationIdFilter.propagateJobInstanceId(rejected))
                        .as("must refuse %s", rejected)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("a non-ASCII decimal digit is refused, which Character.isDigit would have accepted")
        void unicodeDigitsAreRefused() {
            // Arabic-Indic four and Devanagari four. Character.isDigit returns true for both, and
            // Long.parseLong would reject them; an explicit ASCII range is the only check that agrees.
            for (final String rejected : List.of("\u0664\u0662", "\u096A\u0968")) {
                assertThat(Character.isDigit(rejected.charAt(0)))
                        .as("the hazard only exists because isDigit accepts this code point")
                        .isTrue();
                assertThatThrownBy(() -> CorrelationIdFilter.propagateJobInstanceId(rejected))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("an empty value, a lone sign and an oversized value are refused")
        void degenerateValuesAreRefused() {
            final String oversized = "1".repeat(CorrelationIdFilter.MAX_JOB_INSTANCE_ID_LENGTH + 1);
            for (final String rejected : List.of("", "-", oversized)) {
                assertThatThrownBy(() -> CorrelationIdFilter.propagateJobInstanceId(rejected))
                        .as("must refuse '%s'", rejected)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("the bound covers the widest signed 64-bit value, so no real instance is refused")
        void boundCoversTheDomain() {
            assertThat(Long.toString(Long.MIN_VALUE).length())
                    .isLessThanOrEqualTo(CorrelationIdFilter.MAX_JOB_INSTANCE_ID_LENGTH);
        }

        @Test
        @DisplayName("the rejection message never repeats the rejected value")
        void rejectionWithholdsTheValue() {
            assertThatThrownBy(() -> CorrelationIdFilter.propagateJobInstanceId("4242 INFO fabricated"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("fabricated")
                    .hasMessageContaining("withheld");
        }

        @Test
        @DisplayName("a refused value does not modify the entry it was going to replace")
        void refusalLeavesTheEntryIntact() {
            MDC.put(KEY, "7");
            assertThatThrownBy(() -> CorrelationIdFilter.propagateJobInstanceId("not-a-number"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(MDC.get(KEY)).isEqualTo("7");
        }
    }

    @Nested
    @DisplayName("A malformed inherited value is cleared rather than propagated")
    class MalformedInheritance {

        @Test
        @DisplayName("it is reported as absent, so the restore removes it instead of putting it back")
        void malformedInheritedValueIsReportedAbsent() {
            MDC.put(KEY, "4242 INFO fabricated");

            final String previous = CorrelationIdFilter.propagateJobInstanceId("1");
            assertThat(previous)
                    .as("a value that could inject into the log format is not something to preserve")
                    .isNull();

            CorrelationIdFilter.propagateJobInstanceId(previous);
            assertThat(MDC.get(KEY)).isNull();
        }

        @Test
        @DisplayName("the correlation identifier applies the same rule, so the two helpers agree")
        void correlationIdFollowsTheSameRule() {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "not a valid id!");
            assertThat(CorrelationIdFilter.currentCorrelationId()).isNull();
            assertThat(CorrelationIdFilter.propagate("valid-id")).isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)).isEqualTo("valid-id");
        }
    }

    /**
     * Contract of the batch scope pair, which is the one implementation five job listeners now share.
     *
     * <p>Findings H-02, H-03 and M-02 were all one root cause: five hand-rolled copies of park-and-restore,
     * two of them under private re-spellings of the key names. The copies diverged, and each assertion below
     * pins the specific divergence that cost something. They are pinned here, on the mechanism, because a
     * property proved once on the shared implementation holds for every caller, whereas the same property
     * proved five times in five job tests is five things that can drift apart again.
     */
    @Nested
    @DisplayName("The shared batch scope establishes two entries, fabricates none, and restores exactly")
    class BatchScope {

        /** A job instance identifier standing in for a real run. */
        private static final long INSTANCE_ID = 4242L;

        /** The identifier a listener would mint for a run that inherits none. */
        private static final String MINTED = "combtran-77";

        /** The deepest nesting any test in this class establishes. */
        private static final int MAX_NESTING = 4;

        /**
         * Closes any scope a test opened, before the enclosing class clears the diagnostic context.
         *
         * <p>The displacement stack is thread-confined and this class runs its tests on one thread, so an
         * unbalanced {@code enterBatchScope} would leave an entry that the <em>next</em> test's
         * {@code exitBatchScope} consumed - and that next test's assertion would then fail for a reason
         * belonging to a different test. Draining is bounded rather than conditional because an empty stack
         * makes {@code exitBatchScope} a documented no-op, so an extra call is free and no termination
         * condition has to be inferred. This is test hygiene, not a production concern: Spring Batch invokes
         * {@code afterJob} from a {@code finally}, which is what guarantees the pairing at run time.
         */
        @AfterEach
        void releaseAnyScopeLeftOpen() {
            for (int depth = 0; depth < MAX_NESTING; depth++) {
                CorrelationIdFilter.exitBatchScope();
            }
        }

        @Test
        @DisplayName("it publishes the instance identifier under the shared key, not a re-spelling")
        void itPublishesTheInstanceIdentifier() {
            CorrelationIdFilter.enterBatchScope(INSTANCE_ID, MINTED);

            assertThat(MDC.get(KEY))
                    .as("the key is CorrelationIdFilter's own constant, so it has exactly one definition")
                    .isEqualTo(Long.toString(INSTANCE_ID));
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)).isEqualTo(MINTED);
        }

        @Test
        @DisplayName("finding H-02: it fabricates no trace or span identifier, so no log names a false trace")
        void itFabricatesNoTraceIdentity() {
            CorrelationIdFilter.enterBatchScope(INSTANCE_ID, MINTED);

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID))
                    .as("an untraced batch run has no trace to name; a minted value would point a reader at "
                            + "a trace no backend holds, which is worse than an absent field")
                    .isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID)).isNull();
        }

        @Test
        @DisplayName("an inherited trace context is left exactly as the tracing bridge published it")
        void anInheritedTraceContextSurvivesUntouched() {
            MDC.put(CorrelationIdFilter.MDC_KEY_TRACE_ID, "0af7651916cd43dd8448eb211c80319c");
            MDC.put(CorrelationIdFilter.MDC_KEY_SPAN_ID, "b7ad6b7169203331");

            CorrelationIdFilter.enterBatchScope(INSTANCE_ID, MINTED);
            CorrelationIdFilter.exitBatchScope();

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_TRACE_ID))
                    .as("the scope owns two entries and must not touch the two the tracer owns")
                    .isEqualTo("0af7651916cd43dd8448eb211c80319c");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_SPAN_ID)).isEqualTo("b7ad6b7169203331");
        }

        @Test
        @DisplayName("an inherited correlation identifier is kept, so a queue-driven run shares one chain")
        void anInheritedCorrelationIdentifierIsKept() {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "from-the-request");

            CorrelationIdFilter.enterBatchScope(INSTANCE_ID, MINTED);

            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("request, queue message and batch run are one causal chain and share one identifier")
                    .isEqualTo("from-the-request");
        }

        @Test
        @DisplayName("exit removes what it established, so nothing leaks onto a pooled thread")
        void exitRemovesWhatItEstablished() {
            CorrelationIdFilter.enterBatchScope(INSTANCE_ID, MINTED);
            CorrelationIdFilter.exitBatchScope();

            assertThat(MDC.get(KEY))
                    .as("a leaked entry would mislabel an unrelated later run on the same pooled thread")
                    .isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("finding H-03: exit puts back an inherited job instance identifier rather than removing it")
        void exitRestoresAnInheritedJobInstanceIdentifier() {
            MDC.put(KEY, "99");
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "pipeline-7");

            CorrelationIdFilter.enterBatchScope(INSTANCE_ID, MINTED);
            CorrelationIdFilter.exitBatchScope();

            assertThat(MDC.get(KEY))
                    .as("the entry belonged to the enclosing pipeline; an unconditional removal left every "
                            + "later pipeline event on that thread unlabelled")
                    .isEqualTo("99");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)).isEqualTo("pipeline-7");
        }

        @Test
        @DisplayName("nested scopes each restore their own caller, not the absence of one")
        void nestedScopesRestoreTheirOwnCaller() {
            CorrelationIdFilter.enterBatchScope(1L, "pipeline-1");
            CorrelationIdFilter.enterBatchScope(2L, "combtran-2");

            assertThat(MDC.get(KEY)).isEqualTo("2");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID))
                    .as("the inner scope inherits rather than replaces, so the whole stream shares one")
                    .isEqualTo("pipeline-1");

            CorrelationIdFilter.exitBatchScope();
            assertThat(MDC.get(KEY))
                    .as("this is the exact shape of H-03: a nested job must hand the thread back labelled")
                    .isEqualTo("1");

            CorrelationIdFilter.exitBatchScope();
            assertThat(MDC.get(KEY)).isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("an unmatched exit does nothing, because a diagnostic aid must never fail a run")
        void anUnmatchedExitIsTolerated() {
            MDC.put(KEY, "99");

            assertThatCode(CorrelationIdFilter::exitBatchScope).doesNotThrowAnyException();

            assertThat(MDC.get(KEY))
                    .as("a listener whose beforeJob failed before the push left nothing to undo")
                    .isEqualTo("99");
        }

        @Test
        @DisplayName("a malformed minted identifier is refused before anything is written")
        void aMalformedMintedIdentifierIsRefused() {
            assertThatThrownBy(() -> CorrelationIdFilter.enterBatchScope(INSTANCE_ID, "bad id\nINFO fake"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("withheld")
                    .hasMessageNotContaining("fake");

            assertThat(MDC.get(KEY))
                    .as("validation precedes every write, so a refused scope leaves the thread untouched")
                    .isNull();
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_CORRELATION_ID)).isNull();
        }

        @Test
        @DisplayName("the minted identifier is validated even when an inherited one makes it unused")
        void theMintedIdentifierIsValidatedEvenWhenUnused() {
            MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, "from-the-request");

            assertThatThrownBy(() -> CorrelationIdFilter.enterBatchScope(INSTANCE_ID, null))
                    .as("a defect reported only on the runs that happen to need the value is a defect that "
                            + "reaches production")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * Structural guard that the three unambiguous key names are spelled in exactly one place.
     *
     * <p>Finding M-02 was that two classes re-spelled them as private literals. A code review caught it once;
     * a tree scan catches it every build. This is asserted structurally rather than behaviourally because key
     * drift produces <em>no error and no output</em> - {@code logback-spring.xml} simply renders an empty
     * field - so no runtime assertion can be relied on to notice it.
     */
    @Nested
    @DisplayName("The MDC key names are spelled in exactly one file, and a tree scan proves it")
    class KeyNamesHaveOneDefinition {

        /**
         * The keys with no legitimate second meaning anywhere in the tree.
         *
         * <p>{@code correlationId} is deliberately excluded: it is also the name of a JSON property on the
         * failure envelope of every controller, which is a different contract that happens to share a
         * spelling. Narrowing the scan to the three unambiguous keys keeps it exact rather than approximate.
         */
        private static final List<String> UNAMBIGUOUS_KEYS = List.of(
                "\"jobInstanceId\"", "\"traceId\"", "\"spanId\"");

        /** The one file entitled to spell them. */
        private static final String OWNER = "CorrelationIdFilter.java";

        @Test
        @DisplayName("no class but the owner carries the literals, ignoring documentation that cites them")
        void onlyTheOwnerSpellsTheKeys() {
            for (final String key : UNAMBIGUOUS_KEYS) {
                assertThat(filesCarrying(key))
                        .as("%s must have one definition; a second spelling can be renamed in one place only "
                                + "and then silently empties a log field", key)
                        .containsExactly(OWNER);
            }
        }

        /**
         * The source files carrying a literal on a line of code, excluding comment and documentation lines.
         *
         * <p>Comment lines are skipped because the removal notes and the Javadoc of several classes cite these
         * key names deliberately, and a scan that counted prose would make the assertion unsatisfiable without
         * deleting the very documentation that explains the contract.
         *
         * @param literal the quoted key literal to look for
         * @return the simple names of the files carrying it, sorted and deduplicated
         */
        private List<String> filesCarrying(final String literal) {
            try (Stream<Path> tree = Files.walk(Path.of("src", "main", "java"))) {
                return tree.filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(path -> codeLinesOf(path).anyMatch(line -> line.contains(literal)))
                        .map(path -> path.getFileName().toString())
                        .distinct()
                        .sorted()
                        .toList();
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }

        /**
         * The lines of one source file that are code rather than comment or documentation.
         *
         * @param path the source file
         * @return its code lines, in order
         */
        private Stream<String> codeLinesOf(final Path path) {
            final List<String> code = new ArrayList<>();
            boolean inBlockComment = false;
            for (final String line : readLines(path)) {
                final String trimmed = line.strip();
                if (inBlockComment) {
                    inBlockComment = !trimmed.contains("*/");
                    continue;
                }
                if (trimmed.startsWith("/*")) {
                    inBlockComment = !trimmed.contains("*/");
                    continue;
                }
                if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                    continue;
                }
                code.add(line);
            }
            return code.stream();
        }

        /**
         * Reads one source file, converting the checked failure into an unchecked one.
         *
         * @param path the source file
         * @return its lines, in order
         */
        private List<String> readLines(final Path path) {
            try {
                return Files.readAllLines(path);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }
}
