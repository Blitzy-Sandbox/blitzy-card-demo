/*
 * ******************************************************************
 * Program     : ReportNotificationPublishTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the operator notification of // NOTIFY=&SYSUID is
 *               published exactly once per accepted report submission, names
 *               the job and its period, carries no user identity, and cannot
 *               fail the submission when the topic is unreachable.
 * Source      : app/cbl/CORPT00C.cbl:L83-L86  (job card + NOTIFY card)
 *               app/cbl/CORPT00C.cbl:L476-L510 (the gated write block)
 *               app/cbl/CORPT00C.cbl:L515-L537 (the queue write arm)
 *               app/csd/CARDDEMO.CSD:L499-L505 (DDNAME(INREADER)) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.report.ReportSubmissionService.AttentionIdentifier;
import com.cardemo.service.report.ReportSubmissionService.JobNotification;
import com.cardemo.service.shared.DateValidationService;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.MessagingException;

/**
 * The operator-notification publish path: {@code // NOTIFY=&SYSUID}, {@code app/cbl/CORPT00C.cbl:L85-L86}.
 *
 * <p>A notification template and a topic were configured, provisioned in {@code localstack-init/init-aws.sh}
 * and named in four profiles, but nothing published to any of it. That left the second half of card two of the
 * seventeen-card deck unmigrated: the typed queue message reproduces the job's three <em>parameters</em> and
 * has nowhere to put its <em>notification instruction</em>.
 *
 * <p>Four properties are asserted here, and each of them is a decision that could have gone the other way.
 * The notification is published on the accepted path and only there. It carries the job's identity and period
 * and <strong>no user identity at all</strong>, because the source card is a {@code FILLER ... VALUE} literal
 * the program never substitutes into. It is addressed to the configured topic by name rather than to a
 * template default, because the template deliberately has none. And a failure to publish it does not fail the
 * submission, because {@code NOTIFY=} is a courtesy JES2 performs after read-in and the source has no error
 * path for it - unlike the queue write at {@code :L515-L537}, which has an explicit {@code WHEN OTHER} arm.
 */
@DisplayName("Report submission: the operator notification that NOTIFY=&SYSUID asked for")
class ReportNotificationPublishTest {
    /**
     * The key the queue envelope code is derived from.
     *
     * <p>Local to this test and long enough to be a plausible signing key, so nothing here is a credential of
     * any deployment. Finding M-11: the producer signs every submission with a key derived from the
     * application signing key, and there is no unsigned mode, so a subject cannot be built without one.
     */
    private static final String ENVELOPE_SIGNING_KEY =
            "notification-publish-test-envelope-key-0123456789";


    /** The topic every assertion here expects to be addressed by name. */
    private static final String TOPIC = "carddemo-notifications";

    private SqsTemplate sqsTemplate;

    private SnsTemplate snsTemplate;

    private ReportSubmissionService service;

    @BeforeEach
    void buildService() {
        sqsTemplate = mock(SqsTemplate.class);
        snsTemplate = mock(SnsTemplate.class);
        // The service reads the returned message identifier for its success log, so an unstubbed mock would
        // fail the queue publish rather than the notification and every assertion here would be measuring
        // the wrong thing.
        // sendAsync, not send: the publish is asynchronous and the service then waits on the returned future
        // for a bounded deadline, so stubbing the synchronous form leaves the asynchronous one answering
        // null and the wait dereferences it.
        when(sqsTemplate.sendAsync(anySendOptions()))
                .thenReturn(CompletableFuture.completedFuture(acceptedSend()));
        final Clock clock = Clock.fixed(Instant.parse("2024-03-15T10:30:00Z"), ZoneOffset.UTC);
        // The tracer is an optional collaborator and this suite is about the notification, so the provider
        // resolves to none: a publish must happen whether or not a tracing stack is registered.
        service = new ReportSubmissionService(sqsTemplate, snsTemplate, new DateValidationService(clock),
                clock, noTracer(), "carddemo-report-jobs.fifo", "carddemo-report-jobs",
                "carddemo-report-jobs-group", TOPIC, ENVELOPE_SIGNING_KEY);
    }

    /**
     * A provider that resolves to no tracer.
     *
     * <p>{@code ObjectProvider} declares more than one abstract method, so it is not a functional interface
     * and cannot be supplied as a lambda; the constructor also refuses a {@code null} provider, because an
     * absent provider and a provider yielding nothing are different facts. What the service tolerates is the
     * second, and this is it.
     *
     * @return a provider whose {@code getIfAvailable} yields {@code null}, never {@code null} itself
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<Tracer> noTracer() {
        final ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Nested
    @DisplayName("1. The notification is published on the accepted path, and only there")
    class PublishedOnlyOnAcceptance {

        @Test
        @DisplayName("A confirmed monthly submission publishes exactly one notification")
        void aConfirmedSubmissionPublishesOne() {
            service.submitScreen(AttentionIdentifier.ENTER, confirmedMonthlyRequest());

            verify(snsTemplate).sendNotification(anyString(), any(JobNotification.class), anyString());
        }

        @Test
        @DisplayName("A declined confirmation publishes nothing, because the source's write is gated too")
        void aDeclinedSubmissionPublishesNothing() {
            // app/cbl/CORPT00C.cbl:L476-L510 wraps the whole write block in IF NOT ERR-FLG-ON, so the
            // declined arm at :L480-L483 never reaches the queue - and must not reach the topic either.
            service.submitScreen(AttentionIdentifier.ENTER, monthlyRequestWithConfirmation("N"));

            verifyNoInteractions(snsTemplate);
            verifyNoInteractions(sqsTemplate);
        }

        @Test
        @DisplayName("An unconfirmed submission publishes nothing")
        void anUnconfirmedSubmissionPublishesNothing() {
            try {
                service.submitScreen(AttentionIdentifier.ENTER, monthlyRequestWithConfirmation(" "));
            } catch (RuntimeException expected) {
                // :L464-L474 raises the confirmation prompt; the assertion is about what was NOT published.
                assertThat(expected).isNotNull();
            }

            verifyNoInteractions(snsTemplate);
        }
    }

    @Nested
    @DisplayName("2. What the notification carries, and what it deliberately does not")
    class WhatItCarries {

        @Test
        @DisplayName("It names the job and its description from the first card, and the resolved period")
        void itCarriesTheJobIdentityAndThePeriod() {
            service.submitScreen(AttentionIdentifier.ENTER, confirmedMonthlyRequest());

            final JobNotification published = capturePayload();
            // app/cbl/CORPT00C.cbl:L83-L84, //TRNRPT00 JOB 'TRAN REPORT'.
            assertThat(published.jobName()).isEqualTo("TRNRPT00");
            assertThat(published.jobDescription()).isEqualTo("TRAN REPORT");
            // :L213-L238, the monthly arm. The start is the first of the current month, :L217-L219. The end
            // is the LAST day of the current month, not the current day: :L223-L231 sets the day to 1, adds
            // one to the month, rolls the year when it passes twelve, and then computes
            // DATE-OF-INTEGER(INTEGER-OF-DATE(that) - 1) - the day before the first of next month. March 2024
            // therefore ends on the 31st even though the clock reads the 15th.
            assertThat(published.reportName()).isEqualTo("Monthly");
            assertThat(published.startDate()).isEqualTo("2024-03-01");
            assertThat(published.endDate()).isEqualTo("2024-03-31");
        }

        @Test
        @DisplayName("The subject is the job name and description, as JES2 would have reported them")
        void theSubjectNamesTheJob() {
            service.submitScreen(AttentionIdentifier.ENTER, confirmedMonthlyRequest());

            final ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
            verify(snsTemplate).sendNotification(anyString(), any(JobNotification.class), subject.capture());
            assertThat(subject.getValue()).isEqualTo("TRNRPT00 TRAN REPORT");
        }

        @Test
        @DisplayName("The topic is addressed by name, because the template carries no default destination")
        void theTopicIsAddressedByName() {
            service.submitScreen(AttentionIdentifier.ENTER, confirmedMonthlyRequest());

            final ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
            verify(snsTemplate).sendNotification(topic.capture(), any(JobNotification.class), anyString());
            assertThat(topic.getValue()).isEqualTo(TOPIC);
        }

        @Test
        @DisplayName("No user identity of any kind appears in the payload")
        void noIdentityIsPublished() {
            service.submitScreen(AttentionIdentifier.ENTER, confirmedMonthlyRequest());

            // The source card is a FILLER ... VALUE literal that the program never substitutes into, so
            // &SYSUID is resolved by the reading environment rather than by this program. Publishing a
            // signed-on identifier would invent a binding the source does not have.
            assertThat(capturePayload().toString())
                    .doesNotContain("SYSUID")
                    .doesNotContain("ADMIN")
                    .doesNotContain("USER");
        }

        @Test
        @DisplayName("The payload rejects a null component rather than publishing the word null")
        void thePayloadRejectsNulls() {
            assertThat(new JobNotification("TRNRPT00", "TRAN REPORT", "Monthly",
                    "2024-03-01", "2024-03-15")).isNotNull();

            for (int absent = 0; absent < 5; absent++) {
                final String[] parts = {"TRNRPT00", "TRAN REPORT", "Monthly", "2024-03-01", "2024-03-15"};
                parts[absent] = null;
                final int index = absent;
                assertThat(catchNullRejection(parts))
                        .as("component %d must be rejected", Integer.valueOf(index))
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("3. A failed notification does not fail the submission")
    class FailureIsNonFatal {

        @Test
        @DisplayName("The submission succeeds when the topic is unreachable")
        void theSubmissionSurvivesAnUnreachableTopic() {
            doThrow(new MessagingException("topic unreachable"))
                    .when(snsTemplate).sendNotification(anyString(), any(JobNotification.class), anyString());

            final ReportSubmissionService.ReportSubmissionScreen screen =
                    service.submitScreen(AttentionIdentifier.ENTER, confirmedMonthlyRequest());

            // NOTIFY= is a courtesy JES2 performs after read-in: the job runs whether or not anyone is told.
            // The queue write, which does have a WHEN OTHER arm at :L528-L534, already succeeded.
            assertThat(screen.errorFlagOn()).isFalse();
            verify(sqsTemplate).sendAsync(anySendOptions());
        }

        @Test
        @DisplayName("A failed queue write is still fatal, and stops before the notification")
        void aFailedQueueWriteStillFails() {
            doThrow(new MessagingException("queue unreachable"))
                    .when(sqsTemplate).sendAsync(ReportNotificationPublishTest.anySendOptions());

            try {
                service.submitScreen(AttentionIdentifier.ENTER, confirmedMonthlyRequest());
                assertThat(false).as("the queue failure must propagate").isTrue();
            } catch (RuntimeException expected) {
                assertThat(expected).hasMessage("Unable to Write TDQ (JOBS)...");
            }

            // :L507 performs the write; the notification follows it, so a failed write reaches neither.
            verify(snsTemplate, never()).sendNotification(anyString(), any(JobNotification.class), anyString());
        }
    }

    /**
     * A successful queue publish result, as the real template returns.
     *
     * @return the result, never {@code null}
     */
    private static SendResult<ReportSubmissionService.JobSubmissionMessage> acceptedSend() {
        return new SendResult<>(UUID.fromString("00000000-0000-4000-8000-000000000001"),
                "carddemo-report-jobs.fifo", null, Map.of());
    }

    /**
     * A generically correct matcher for the one {@code send} overload the service uses.
     *
     * <p>{@code SqsOperations.send} takes a {@code Consumer<SqsSendOptions<T>>}; matching it with a raw
     * {@code Consumer.class} compiles only with an unchecked warning, and this build treats warnings as
     * errors. Naming the type argument the service supplies removes the warning rather than suppressing it.
     *
     * @return the matcher, never {@code null}
     */
    private static Consumer<SqsSendOptions<ReportSubmissionService.JobSubmissionMessage>> anySendOptions() {
        return any();
    }

    /**
     * Captures the single published payload.
     *
     * @return the payload, never {@code null}
     */
    private JobNotification capturePayload() {
        final ArgumentCaptor<JobNotification> payload = ArgumentCaptor.forClass(JobNotification.class);
        verify(snsTemplate).sendNotification(anyString(), payload.capture(), anyString());
        return payload.getValue();
    }

    /**
     * Answers whether constructing a notification from the given components is rejected.
     *
     * @param parts the five components in declaration order, one of them {@code null}
     * @return {@code true} if construction was rejected
     */
    private static boolean catchNullRejection(final String[] parts) {
        try {
            new JobNotification(parts[0], parts[1], parts[2], parts[3], parts[4]);
            return false;
        } catch (NullPointerException rejected) {
            return rejected.getMessage() != null;
        }
    }

    /**
     * A monthly request whose confirmation gate is affirmative.
     *
     * @return the request, never {@code null}
     */
    private static ReportRequest confirmedMonthlyRequest() {
        return monthlyRequestWithConfirmation("Y");
    }

    /**
     * A monthly request carrying the given confirmation character.
     *
     * @param confirmation the value of {@code CONFIRMI}, never {@code null}
     * @return the request, never {@code null}
     */
    private static ReportRequest monthlyRequestWithConfirmation(final String confirmation) {
        return new ReportRequest("CR00", null, null, "CORPT00C", null, null,
                "Y", null, null, null, null, null, null, null, null, confirmation, null);
    }
}
