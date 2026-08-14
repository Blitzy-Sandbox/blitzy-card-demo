/*
 * ******************************************************************
 * Program     : ReportSubmissionServiceMessageGroupTest.java
 * Application : CardDemo
 * Type        : Java unit test (JUnit 5)
 * Function    : Proves the construction-time validation of the FIFO message
 *               group identifier in
 *               com.cardemo.service.report.ReportSubmissionService, the target
 *               of app/cbl/CORPT00C.cbl:L515-L537 (EXEC CICS WRITEQ TD
 *               QUEUE('JOBS')) over the queue defined at
 *               app/csd/CARDDEMO.CSD:L499-L503 with DISPOSITION(MOD).
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.shared.DateValidationService;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for the message-group identifier that every report submission carries.
 *
 * <p><strong>Why this is validated at construction rather than at the first send.</strong> Amazon SQS accepts
 * a message group identifier of 1 to 128 characters drawn from the visible ASCII range, and rejects anything
 * else. Left unchecked, a bad value set once at deployment surfaces as a report that will not submit, in a
 * code path whose own validation has already passed - so the symptom points at the application and the cause
 * is a configuration property. A blank value is the worst case, because it is not {@code null} and so passed
 * every check the constructor previously made. Validating at construction converts a latent per-request
 * failure into a startup failure whose message carries the remedy.
 *
 * <p>The identifier is a single fixed value rather than one per submission because the queue this replaces was
 * declared {@code DISPOSITION(MOD)} at {@code app/csd/CARDDEMO.CSD:L503} - strict sequential append to one
 * stream - and one fixed group reproduces that ordering exactly.
 *
 * <p>No assertion here checks that the offending value appears in a message, because the class withholds it:
 * the length failure reports only the length and the character failure only the position and code point.
 */
@DisplayName("ReportSubmissionService: the FIFO message group is validated at construction")
final class ReportSubmissionServiceMessageGroupTest {
    /**
     * The key the queue envelope code is derived from.
     *
     * <p>Local to this test and long enough to be a plausible signing key, so nothing here is a credential of
     * any deployment. Finding M-11: the producer signs every submission with a key derived from the
     * application signing key, and there is no unsigned mode, so a subject cannot be built without one.
     */
    private static final String ENVELOPE_SIGNING_KEY =
            "message-group-test-envelope-key-0123456789";


    private static final String QUEUE = "carddemo-report-jobs.fifo";
    private static final String LOGICAL = "carddemo-report-jobs";
    private static final String PROPERTY = "carddemo.aws.sqs.report-message-group-id";

    /**
     * Constructs the service with a given message group, everything else valid.
     *
     * @param messageGroupId the identifier under test
     * @return the constructed service, when the identifier is accepted
     */
    private static ReportSubmissionService build(final String messageGroupId) {
        return new ReportSubmissionService(
                mock(SqsTemplate.class),
                mock(SnsTemplate.class),
                mock(DateValidationService.class),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                noTracer(),
                QUEUE,
                LOGICAL,
                messageGroupId,
                "carddemo-notifications", ENVELOPE_SIGNING_KEY);
    }

    /**
     * A provider that resolves to no tracer, which is the shape the service must tolerate: tracing is an
     * optional collaborator, and a publish still has to happen when none is registered.
     *
     * @return a provider yielding {@code null}
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<Tracer> noTracer() {
        final ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }


    @Test
    @DisplayName("the shipped identifier is accepted")
    void theShippedIdentifierIsAccepted() {
        assertThatCode(() -> build(LOGICAL)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "a blank identifier is refused: [{0}]")
    @ValueSource(strings = {"", " ", "   ", "\t"})
    void aBlankIdentifierIsRefused(final String candidate) {
        assertThatThrownBy(() -> build(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("a null identifier still fails, through the existing null check")
    void aNullIdentifierIsRefused() {
        assertThatThrownBy(() -> build(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reportMessageGroupId");
    }

    @Test
    @DisplayName("the 128-character service limit is the boundary: 128 passes, 129 does not")
    void theLengthBoundaryIsExact() {
        assertThatCode(() -> build("x".repeat(128))).doesNotThrowAnyException();
        assertThatThrownBy(() -> build("x".repeat(129)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY)
                .hasMessageContaining("129")
                .hasMessageContaining("128");
    }

    @ParameterizedTest(name = "a character outside the accepted ASCII range is refused: [{0}]")
    @ValueSource(strings = {
        "carddemo report jobs",
        "carddemo\treport",
        "carddemo\nreport",
        "carddemo-report-jobs\u00e9",
        " leading",
        "trailing "})
    void anUnacceptedCharacterIsRefused(final String candidate) {
        assertThatThrownBy(() -> build(candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY);
    }

    @Test
    @DisplayName("the offending character is reported by position and code point, and the value is not")
    void theOffendingCharacterIsLocatedWithoutEchoingTheValue() {
        assertThatThrownBy(() -> build("carddemo report"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("position 8")
                .hasMessageContaining("U+0020")
                .hasMessageNotContaining("carddemo report");
    }

    @ParameterizedTest(name = "every boundary character of the accepted range is allowed: [{0}]")
    @ValueSource(strings = {"!", "~", "a", "Z", "0", "9", "carddemo-report-jobs", "a.b_c-d:e"})
    void theAcceptedRangeIsNotNarrowerThanTheServiceLimit(final String candidate) {
        assertThatCode(() -> build(candidate)).doesNotThrowAnyException();
    }
}
