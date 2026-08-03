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

import java.util.List;

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
 */
@DisplayName("Diagnostic context propagation across a thread boundary (M-03)")
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
}
