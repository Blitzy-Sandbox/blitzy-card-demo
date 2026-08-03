/*
 ******************************************************************************
 * Program     : ReportControllerTest
 * Application : CardDemo
 * Type        : Java unit test (JUnit 5)
 * Function    : Verifies the wire contract of ReportController, the REST
 *               replacement for CICS transaction CR00 and COBOL program
 *               app/cbl/CORPT00C.cbl. Asserts that the accepted arm of
 *               :L447-L454 and the declined arm of :L480-L482 stay
 *               distinguishable over HTTP, and that the cursor, highlight and
 *               navigation members of the service map never reach the wire.
 ******************************************************************************
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
 ******************************************************************************
 */
package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.cardemo.controller.ReportController;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.model.dto.ReportSubmissionResponse;
import com.cardemo.service.report.ReportSubmissionService;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * Unit tests for {@link ReportController}.
 *
 * <p>The service is mocked. This tier owns two decisions and no business logic: whether the submission was
 * accepted or declined becomes a status, and which members of the service's map are publishable.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("ReportController - the wire contract for CR00")
class ReportControllerTest {

    /** The success notice of {@code app/cbl/CORPT00C.cbl:L447-L454}. */
    private static final String SUCCESS_MESSAGE = "Report submitted for printing ...";

    /** The {@code CURSOR} field name the map carries and the wire must not. */
    private static final String CURSOR_FIELD = "CONFIRMI";

    /** The {@code XCTL} target the map carries and the wire must not. */
    private static final String NAVIGATION_TARGET = "COMEN01C";

    /** The report-submission service, mocked because this tier reaches no queue. */
    @Mock
    private ReportSubmissionService reportSubmissionService;

    /** An authenticated principal, the stateless counterpart of a non-zero {@code EIBCALEN}. */
    private final Authentication principal =
            new TestingAuthenticationToken("USER0001", "n/a", "ROLE_USER");

    /**
     * Builds the controller under test.
     *
     * @return a controller wired to the mocked service
     */
    private ReportController controller() {
        return new ReportController(this.reportSubmissionService);
    }

    /**
     * Builds the inbound form.
     *
     * @param confirmation the {@code CONFIRMI} value to submit
     * @return a populated request selecting the monthly period
     */
    private static ReportRequest request(final String confirmation) {
        return new ReportRequest("CR00", "CardDemo", "08/03/26", "CORPT00C", "Transaction Reports",
                "10:15:30", "Y", null, null, null, null, null, null, null, null, confirmation, null);
    }

    /**
     * Builds the map the service reports, carrying every presentation member the wire must drop.
     *
     * @param errorFlagOn  whether the source raised {@code WS-ERR-FLG}
     * @param errorMessage the {@code ERRMSGO} caption
     * @return a populated map
     */
    private static ReportSubmissionService.ReportSubmissionScreen screen(final boolean errorFlagOn,
                                                                        final String errorMessage) {
        final ReportRequest form = new ReportRequest("CR00", "CardDemo", "08/03/26", "CORPT00C",
                "Transaction Reports", "10:15:30", "Y", null, null, null, null, null, null, null, null,
                "Y", errorMessage);
        return new ReportSubmissionService.ReportSubmissionScreen(form, errorFlagOn, !errorFlagOn,
                CURSOR_FIELD, NAVIGATION_TARGET);
    }

    /**
     * Stubs the service to report the supplied map.
     *
     * @param map the map to report
     */
    private void arrange(final ReportSubmissionService.ReportSubmissionScreen map) {
        when(this.reportSubmissionService.submitScreen(
                eq(ReportSubmissionService.AttentionIdentifier.ENTER), any()))
                .thenReturn(map);
    }

    /**
     * The two endings keep their own statuses and their own {@code published} value.
     */
    @Nested
    @DisplayName("the two endings stay distinguishable")
    class OutcomeContract {

        @Test
        @DisplayName(":L447-L454 an accepted submission answers 202 with published true")
        void anAcceptedSubmissionAnswers202() {
            arrange(screen(false, SUCCESS_MESSAGE));

            final ResponseEntity<ReportSubmissionResponse> response =
                    controller().submitReport(principal, request("Y"));

            assertThat(response.getStatusCode())
                    .as("the batch tier produces the report asynchronously, so this is accepted not completed")
                    .isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().published()).isTrue();
            assertThat(response.getBody().message()).isEqualTo(SUCCESS_MESSAGE);
        }

        /**
         * The queue write at {@code :L498-L508} sits inside {@code IF NOT ERR-FLG-ON}, so the declined arm
         * published nothing. The request was understood and answered, which is why it is {@code 200} rather
         * than {@code 202} or {@code 400}.
         */
        @Test
        @DisplayName(":L480-L482 a declined submission answers 200 with published false")
        void aDeclinedSubmissionAnswers200() {
            arrange(screen(true, ""));

            final ResponseEntity<ReportSubmissionResponse> response =
                    controller().submitReport(principal, request("N"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().published())
                    .as("the queue write sits inside IF NOT ERR-FLG-ON, so nothing reached the queue")
                    .isFalse();
        }
    }

    /**
     * The cursor, highlight and navigation members must not reach the wire.
     */
    @Nested
    @DisplayName("the body is API-native, carrying no cursor, highlight or navigation state")
    class WireContract {

        @Test
        @DisplayName("the declared response type is the DTO-package record, not the service map")
        void theDeclaredResponseTypeIsTheDto() throws NoSuchMethodException {
            final java.lang.reflect.Method operation = ReportController.class
                    .getMethod("submitReport", Authentication.class, ReportRequest.class);

            assertThat(operation.getGenericReturnType().getTypeName())
                    .as("returning the service map would publish cursor, highlight and XCTL state")
                    .isEqualTo("org.springframework.http.ResponseEntity<"
                            + "com.cardemo.model.dto.ReportSubmissionResponse>");
        }

        /**
         * Asserted structurally, because the guarantee is the shape of the type rather than the value of one
         * instance: a record with only these two components cannot serialize a third.
         */
        @Test
        @DisplayName("the response record declares exactly two components and no presentation member")
        void theResponseDeclaresNoPresentationMember() {
            assertThat(ReportSubmissionResponse.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("published", "message")
                    .doesNotContain("cursorField", "successHighlight", "navigationTarget", "errorFlagOn",
                            "form");
        }

        /**
         * URL routing replaces {@code EXEC CICS XCTL} outright, per AAP section 0.5.2.4, which records that
         * the communication area's {@code FROM} and {@code TO} program fields have no equivalent in the
         * target. Publishing the target program name would reintroduce exactly that coupling.
         */
        @Test
        @DisplayName("neither ending publishes the cursor field or the XCTL target")
        void neitherEndingPublishesCursorOrNavigation() {
            arrange(screen(false, SUCCESS_MESSAGE));

            final ReportSubmissionResponse body =
                    controller().submitReport(principal, request("Y")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.toString())
                    .doesNotContain(CURSOR_FIELD)
                    .doesNotContain(NAVIGATION_TARGET);
        }

        @Test
        @DisplayName("the accepted body carries no 3270 header text")
        void theAcceptedBodyCarriesNoHeaderText() {
            arrange(screen(false, SUCCESS_MESSAGE));

            final ReportSubmissionResponse body =
                    controller().submitReport(principal, request("Y")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.toString())
                    .doesNotContain("CardDemo")
                    .doesNotContain("CORPT00C")
                    .doesNotContain("10:15:30");
        }
    }

    /**
     * Status translation for the queue failure and the unauthenticated case.
     */
    @Nested
    @DisplayName("failure translation agrees with the other four resources")
    class StatusContract {

        @Test
        @DisplayName("a queue failure is mapped to 502 carrying the source literal")
        void aQueueFailureMapsTo502() {
            final ResponseEntity<ProblemDetail> response = controller().handleQueueFailure(
                    new FileAccessException("Unable to Write TDQ (JOBS)...", "9010", "JOBS", "WRITEQ"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getDetail()).isEqualTo("Unable to Write TDQ (JOBS)...");
        }

        /**
         * Supplies the three unauthenticated states, which are genuinely distinct rather than three spellings
         * of one thing.
         *
         * @return the three states with their descriptions
         */
        static Stream<Arguments> unauthenticatedStates() {
            return Stream.of(
                    Arguments.of("no filter populated the context", (Authentication) null),
                    Arguments.of("a token was presented and rejected",
                            new TestingAuthenticationToken("USER0001", "n/a")),
                    Arguments.of("the chain admitted an unidentified caller",
                            new AnonymousAuthenticationToken("key", "anonymousUser",
                                    AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))));
        }

        @ParameterizedTest(name = "401 when {0}")
        @MethodSource("unauthenticatedStates")
        @DisplayName(":L172-L174 an absent identity answers 401 and never reaches the service")
        void anAbsentIdentityAnswers401(final String description, final Authentication unusable) {
            final ResponseEntity<ReportSubmissionResponse> response =
                    controller().submitReport(unusable, request("Y"));

            assertThat(response.getStatusCode()).as(description).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNull();
        }
    }

    /**
     * The constructor refuses an absent collaborator, so a misconfigured context fails at startup.
     */
    @Nested
    @DisplayName("construction refuses an absent collaborator")
    class ConstructionContract {

        @Test
        @DisplayName("a null service is refused with a message naming the source program")
        void aNullServiceIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new ReportController(null))
                    .withMessageContaining("CORPT00C");
        }
    }
}
