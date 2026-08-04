/*
 * ******************************************************************
 * Program     : ErrorBodyDisclosureTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Drives every @ExceptionHandler of all eight controllers with
 *               an exception carrying distinctive internal values and proves
 *               that no logical file name, I/O verb, expanded file status,
 *               constraint, relation, record key, abend code or return code
 *               reaches the client, that every body carries a stable error
 *               code and the correlation identifier, and that the legacy
 *               screen literals the AAP mandates are still relayed.
 * Source      : app/csd/CARDDEMO.CSD           (the CC00-CU03 transactions)
 *               app/cbl/COACTUPC.cbl:L505-L512 (validation literals)
 *               app/cbl/COACTUPC.cbl:L654-L668 (ACUP-CHANGE-ACTION)
 *               app/cbl/CORPT00C.cbl:L531      (Unable to Write TDQ)
 *               app/cbl/CBTRN02C.cbl:L707-L710 (abend 999, RC 12) @ 7756d89
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
package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.cardemo.controller.AccountController;
import com.cardemo.controller.AdminController;
import com.cardemo.controller.AuthController;
import com.cardemo.controller.BillingController;
import com.cardemo.controller.CardController;
import com.cardemo.controller.MenuController;
import com.cardemo.controller.ReportController;
import com.cardemo.controller.TransactionController;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.security.SnapshotTokenService;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.account.AccountViewService;
import com.cardemo.service.admin.UserAddService;
import com.cardemo.service.admin.UserDeleteService;
import com.cardemo.service.admin.UserListService;
import com.cardemo.service.admin.UserUpdateService;
import com.cardemo.service.auth.AuthenticationService;
import com.cardemo.service.billing.BillPaymentService;
import com.cardemo.service.card.CardDetailService;
import com.cardemo.service.card.CardListService;
import com.cardemo.service.card.CardUpdateService;
import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.service.menu.MainMenuService;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.transaction.TransactionAddService;
import com.cardemo.service.transaction.TransactionDetailService;
import com.cardemo.service.transaction.TransactionListService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * What every controller error body may and may not contain.
 *
 * <p>The exception hierarchy carries the legacy diagnostic faithfully - the logical file name, the attempted
 * verb, the four-character {@code COBOL FILE STATUS}, the violated constraint, the relation, the abend code
 * and the batch return code. Each is exactly what an operator needs. None is anything a caller can act on,
 * and returning them together told a caller which internal dataset failed which verb with which status,
 * which is information exposure through an error message (CWE-209).
 *
 * <p>The disclosure was not hypothetical. {@code FileStatusMapper} composes its message as
 * {@code "<verb> of <file> reported COBOL FILE STATUS <status> (IO-STATUS-04 nnnn)"}, and the account view
 * path records a file status of {@code '23'} for an empty repository result and hands it to that mapper, so
 * a request for an account that does not exist returned the verb, the alternate-index path name and the
 * legacy status in {@code detail}.
 *
 * <p>These tests drive every {@code @ExceptionHandler} of all eight controllers with an exception carrying
 * distinctive internal values, and assert three things of each body: that no internal value appears in any
 * part of it, that the stable error code and the correlation identifier do appear, and that the legacy
 * screen literals the AAP requires are still relayed where their provenance makes that safe.
 */
@DisplayName("Controllers: an error body carries a public code and a correlation identifier, never internals")
class ErrorBodyDisclosureTest {

    /** A logical file name distinctive enough that its presence anywhere is unambiguous. */
    private static final String SECRET_FILE = "ZZINTERNALDATASET";

    /** An I/O verb, equally distinctive. */
    private static final String SECRET_OPERATION = "ZZREADFORUPDATE";

    /** A four-character expanded file status, equally distinctive. */
    private static final String SECRET_STATUS = "ZZ37";

    /** A constraint name, equally distinctive. */
    private static final String SECRET_CONSTRAINT = "ZZFKACCOUNTCUSTOMER";

    /** A relation name, equally distinctive. */
    private static final String SECRET_RELATION = "ZZACCOUNTRELATION";

    /** A record key, equally distinctive. */
    private static final String SECRET_KEY = "ZZ00000000001";

    /** The composed diagnostic the status mapper would put on the exception message. */
    private static final String SECRET_MESSAGE =
            SECRET_OPERATION + " of " + SECRET_FILE + " reported COBOL FILE STATUS 23 (IO-STATUS-04 0023)";

    /** An abend culprit program name, equally distinctive. */
    private static final String SECRET_CULPRIT = "ZZCOACTUP";

    /**
     * The legacy duplicate-identifier caption of {@code app/cbl/COUSR01C.cbl:L263}, relayed byte for byte by
     * the user-administration duplicate arm. The source's own spelling - "exist", not "exists" - is preserved
     * because the parity comparison is made on text.
     */
    private static final String USER_ID_TAKEN_CAPTION = "User ID already exist...";

    /** An abend reason, equally distinctive. */
    private static final String SECRET_REASON = "ZZUNEXPECTEDFILESTATUS";

    /** Every value that must never appear in a body, in one place so no test can forget one. */
    private static final List<String> EVERY_INTERNAL_VALUE = List.of(
            SECRET_FILE, SECRET_OPERATION, SECRET_STATUS, SECRET_CONSTRAINT, SECRET_RELATION, SECRET_KEY,
            SECRET_CULPRIT, SECRET_REASON, "COBOL FILE STATUS", "IO-STATUS-04");

    /** The correlation identifier placed in the diagnostic context for the duration of each test. */
    private static final String CORRELATION_ID = "c0rrel4t10n-1d-f0r-th3-t3st";

    @BeforeEach
    void placeCorrelationIdentifier() {
        MDC.put(CorrelationIdFilter.MDC_KEY_CORRELATION_ID, CORRELATION_ID);
    }

    @AfterEach
    void clearCorrelationIdentifier() {
        MDC.remove(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);
    }

    /**
     * Builds the eight controllers over mocked services. None is invoked: only the handlers are driven.
     *
     * @return the eight controllers, never {@code null}
     */
    private static Controllers controllers() {
        return new Controllers(
                new AccountController(mock(AccountViewService.class), mock(AccountUpdateService.class)),
                new AdminController(mock(UserListService.class), mock(UserAddService.class),
                        mock(UserUpdateService.class), mock(UserDeleteService.class)),
                new AuthController(mock(AuthenticationService.class)),
                new BillingController(mock(BillPaymentService.class)),
                new CardController(mock(CardListService.class), mock(CardDetailService.class),
                        mock(CardUpdateService.class), mock(SnapshotTokenService.class)),
                new MenuController(mock(MainMenuService.class), mock(AdminMenuService.class)),
                new ReportController(mock(ReportSubmissionService.class)),
                new TransactionController(mock(TransactionListService.class),
                        mock(TransactionDetailService.class), mock(TransactionAddService.class)));
    }

    /**
     * The eight controllers under test.
     *
     * @param account the account resource group
     * @param admin the user-administration resource group
     * @param auth the sign-on resource group
     * @param billing the bill-payment resource group
     * @param card the card resource group
     * @param menu the menu resource group
     * @param report the report-submission resource group
     * @param transaction the transaction resource group
     */
    private record Controllers(AccountController account, AdminController admin, AuthController auth,
            BillingController billing, CardController card, MenuController menu, ReportController report,
            TransactionController transaction) {
    }

    /**
     * One handler invocation: a label for the failure report and the body it produced.
     *
     * @param label the controller and handler, for assertion messages
     * @param response the response the handler built
     */
    private record Answer(String label, ResponseEntity<ProblemDetail> response) {
    }

    /**
     * Drives every {@code @ExceptionHandler} of all eight controllers with a fully populated exception.
     *
     * @return one answer per handler, never {@code null} and never empty
     */
    private static List<Answer> everyHandlerAnswer() {
        final Controllers group = controllers();
        final List<Answer> answers = new ArrayList<>();

        final ValidationException rejection = new ValidationException("Credit Limit must be supplied",
                "creditLimit", ValidationException.FailureKind.BLANK);
        final RecordNotFoundException absent =
                new RecordNotFoundException(SECRET_MESSAGE, SECRET_FILE, SECRET_KEY);
        final DuplicateRecordException collision =
                new DuplicateRecordException(SECRET_MESSAGE, SECRET_FILE, SECRET_KEY);
        // The user-administration duplicate arm relays its message, because that message is the legacy screen
        // caption of app/cbl/COUSR01C.cbl:L263 - "exist", not "exists" - and the parity comparison is made on
        // text. It is therefore driven with the caption its service actually raises, exactly as the validation
        // arms are, while its logical file and colliding key remain the internal sentinels: those two are what
        // must not reach a body, and the sweep below proves they do not.
        final DuplicateRecordException captionedCollision =
                new DuplicateRecordException(USER_ID_TAKEN_CAPTION, SECRET_FILE, SECRET_KEY);
        final FileUnavailableException unavailable =
                new FileUnavailableException(SECRET_MESSAGE, SECRET_FILE, new IllegalStateException("x"));
        final FileAccessException accessFailure =
                new FileAccessException(SECRET_MESSAGE, SECRET_STATUS, SECRET_FILE, SECRET_OPERATION);
        final DataIntegrityException integrity =
                new DataIntegrityException(SECRET_MESSAGE, SECRET_CONSTRAINT, SECRET_RELATION);
        final ConcurrentUpdateException conflict = new ConcurrentUpdateException(
                ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT,
                "Could not lock account record for update", SECRET_FILE, null);
        final FatalProcessingException abend =
                new FatalProcessingException("999", SECRET_CULPRIT, SECRET_REASON, SECRET_MESSAGE);
        final CardDemoException typed = new CardDemoException(SECRET_MESSAGE) {
            private static final long serialVersionUID = 1L;
        };

        answers.add(new Answer("Account.validation", group.account().handleValidationFailure(rejection)));
        answers.add(new Answer("Account.notFound", group.account().handleRecordNotFound(absent)));
        answers.add(new Answer("Account.conflict", group.account().handleConcurrentUpdate(conflict)));
        answers.add(new Answer("Account.integrity", group.account().handleDataIntegrity(integrity)));
        answers.add(new Answer("Account.unavailable", group.account().handleFileUnavailable(unavailable)));
        answers.add(new Answer("Account.ioFailure", group.account().handleFileAccessFailure(accessFailure)));
        answers.add(new Answer("Account.abend", group.account().handleAbend(abend)));
        answers.add(new Answer("Account.typed", group.account().handleTypedFailure(typed)));

        answers.add(new Answer("Admin.validation", group.admin().handleValidationFailure(rejection)));
        answers.add(new Answer("Admin.notFound", group.admin().handleRecordNotFound(absent)));
        answers.add(new Answer("Admin.duplicate",
                group.admin().handleDuplicateRecord(captionedCollision)));
        answers.add(new Answer("Admin.unavailable", group.admin().handleFileUnavailable(unavailable)));
        answers.add(new Answer("Admin.ioFailure", group.admin().handleFileAccessFailure(accessFailure)));
        answers.add(new Answer("Admin.abend", group.admin().handleAbend(abend)));
        answers.add(new Answer("Admin.typed", group.admin().handleTypedFailure(typed)));

        // The sign-on handlers are swept here for the same reason as every other: an unauthenticated caller
        // is the one who reaches them, so a disclosed dataset name, file status or culprit program would be
        // the cheapest reconnaissance on the whole surface.
        answers.add(new Answer("Auth.validation", group.auth().handleValidationFailure(rejection)));
        answers.add(new Answer("Auth.notFound", group.auth().handleRecordNotFound(absent)));
        answers.add(new Answer("Auth.unavailable", group.auth().handleFileUnavailable(unavailable)));
        answers.add(new Answer("Auth.ioFailure", group.auth().handleFileAccessFailure(accessFailure)));
        answers.add(new Answer("Auth.abend", group.auth().handleAbend(abend)));
        answers.add(new Answer("Auth.typed", group.auth().handleTypedFailure(typed)));

        answers.add(new Answer("Billing.validation", group.billing().handleValidationFailure(rejection)));
        answers.add(new Answer("Billing.notFound", group.billing().handleRecordNotFound(absent)));
        answers.add(new Answer("Billing.duplicate", group.billing().handleDuplicateRecord(collision)));
        answers.add(new Answer("Billing.unavailable", group.billing().handleFileUnavailable(unavailable)));
        answers.add(new Answer("Billing.ioFailure", group.billing().handleFileAccessFailure(accessFailure)));
        answers.add(new Answer("Billing.abend", group.billing().handleAbend(abend)));
        answers.add(new Answer("Billing.typed", group.billing().handleTypedFailure(typed)));

        answers.add(new Answer("Card.validation", group.card().handleValidationFailure(rejection)));
        answers.add(new Answer("Card.notFound", group.card().handleRecordNotFound(absent)));
        answers.add(new Answer("Card.conflict", group.card().handleConcurrentUpdate(conflict)));
        answers.add(new Answer("Card.unavailable", group.card().handleFileUnavailable(unavailable)));
        answers.add(new Answer("Card.ioFailure", group.card().handleFileAccessFailure(accessFailure)));
        answers.add(new Answer("Card.abend", group.card().handleAbend(abend)));
        answers.add(new Answer("Card.typed", group.card().handleTypedFailure(typed)));

        answers.add(new Answer("Menu.validation", group.menu().handleValidationFailure(rejection)));
        answers.add(new Answer("Menu.abend", group.menu().handleAbend(abend)));
        answers.add(new Answer("Menu.typed", group.menu().handleTypedFailure(typed)));

        answers.add(new Answer("Report.validation", group.report().handleRejection(rejection)));
        answers.add(new Answer("Report.unavailable", group.report().handleQueueUnavailable(unavailable)));
        answers.add(new Answer("Report.abend", group.report().handleAbend(abend)));
        answers.add(new Answer("Report.typed", group.report().handleTypedFailure(typed)));

        answers.add(new Answer("Transaction.validation",
                group.transaction().handleValidationFailure(rejection)));
        answers.add(new Answer("Transaction.notFound", group.transaction().handleRecordNotFound(absent)));
        answers.add(new Answer("Transaction.duplicate",
                group.transaction().handleDuplicateRecord(collision)));
        answers.add(new Answer("Transaction.unavailable",
                group.transaction().handleFileUnavailable(unavailable)));
        answers.add(new Answer("Transaction.ioFailure",
                group.transaction().handleFileAccessFailure(accessFailure)));
        answers.add(new Answer("Transaction.abend", group.transaction().handleAbend(abend)));
        answers.add(new Answer("Transaction.typed", group.transaction().handleTypedFailure(typed)));

        return answers;
    }

    /**
     * Renders a body the way a client would see it: the detail plus every property value.
     *
     * @param body the problem detail, never {@code null}
     * @return one string containing everything the body discloses, never {@code null}
     */
    private static String renderedBody(final ProblemDetail body) {
        final StringBuilder rendered = new StringBuilder(256);
        rendered.append(body.getTitle()).append('\n').append(body.getDetail()).append('\n')
                .append(body.getType()).append('\n').append(body.getStatus());
        final Map<String, Object> properties = body.getProperties();
        if (properties != null) {
            properties.forEach((name, value) ->
                    rendered.append('\n').append(name).append('=').append(value));
        }
        return rendered.toString();
    }

    @Nested
    @DisplayName("1. No internal value reaches any body, on any handler of any controller")
    class NoInternalValueIsDisclosed {

        @Test
        @DisplayName("No body names a dataset, verb, status, constraint, relation, key, culprit or reason")
        void noBodyDisclosesAnythingInternal() {
            // Every handler is driven inside the test rather than by a parameterised source, because the
            // correlation identifier lives in a thread-local diagnostic context that @BeforeEach populates:
            // an argument factory runs before that, and would build every body without one.
            assertThat(everyHandlerAnswer()).isNotEmpty().allSatisfy(answer -> {
                final ProblemDetail body = answer.response().getBody();
                assertThat(body).as("%s produced no body", answer.label()).isNotNull();
                final String rendered = renderedBody(body);
                for (final String internal : EVERY_INTERNAL_VALUE) {
                    assertThat(rendered)
                            .as("%s must not disclose <%s>", answer.label(), internal)
                            .doesNotContain(internal);
                }
            });
        }

        @Test
        @DisplayName("No withdrawn property name survives on any body")
        void noWithdrawnPropertyIsPresent() {
            assertThat(everyHandlerAnswer()).isNotEmpty().allSatisfy(answer -> {
                final Map<String, Object> properties = answer.response().getBody().getProperties();
                if (properties != null) {
                    assertThat(properties)
                            .as("%s carries a withdrawn property", answer.label())
                            .doesNotContainKeys("recordType", "recordKey", "constraintName", "relation",
                                    "resourceName", "logicalFileName", "logicalFile", "file", "operation",
                                    "ioStatus", "expandedStatus", "abendCode", "returnCode",
                                    "affectedRecord");
                }
            });
        }
    }

    @Nested
    @DisplayName("2. Every body carries the public envelope")
    class TheEnvelopeIsAlwaysPresent {

        @Test
        @DisplayName("Every body carries a stable CARDDEMO- error code")
        void everyBodyCarriesAnErrorCode() {
            assertThat(everyHandlerAnswer()).isNotEmpty().allSatisfy(answer -> {
                final Map<String, Object> properties = answer.response().getBody().getProperties();
                assertThat(properties).as("%s carries no properties", answer.label()).isNotNull();
                assertThat(properties.get("errorCode"))
                        .as("%s carries no errorCode", answer.label())
                        .isInstanceOf(String.class)
                        .asString()
                        .startsWith("CARDDEMO-");
            });
        }

        @Test
        @DisplayName("Every body echoes the correlation identifier from the diagnostic context")
        void everyBodyEchoesTheCorrelationIdentifier() {
            assertThat(everyHandlerAnswer()).isNotEmpty().allSatisfy(answer ->
                    assertThat(answer.response().getBody().getProperties().get("correlationId"))
                            .as("%s carries no correlationId", answer.label())
                            .isEqualTo(CORRELATION_ID));
        }

        @Test
        @DisplayName("With no correlation identifier in context the property is present and states so")
        void anAbsentCorrelationIdentifierIsStated() {
            MDC.remove(CorrelationIdFilter.MDC_KEY_CORRELATION_ID);

            assertThat(everyHandlerAnswer())
                    .isNotEmpty()
                    .allSatisfy(answer -> assertThat(
                            answer.response().getBody().getProperties().get("correlationId"))
                            .as("%s", answer.label())
                            .isEqualTo("unavailable"));
        }

        @Test
        @DisplayName("Every handler of every controller is exercised")
        void everyHandlerIsCovered() {
            // Executable @ExceptionHandler methods: Account 8, Admin 7, Auth 6, Billing 7, Card 7, Menu 3,
            // Report 5, Transaction 7 - fifty across the eight controllers. Forty-nine are driven here;
            // Report's queue-failure arm is driven separately below, because it is the one handler whose
            // detail is a relayed legacy literal and it is asserted against that literal.
            //
            // A review found this suite frozen at six controllers, so the sign-on and user-administration
            // handlers were swept by nothing. Both are now driven, and the sign-on ones matter most of the
            // eight: an unauthenticated caller is the only caller who reaches them.
            assertThat(everyHandlerAnswer()).hasSize(49);
        }
    }

    @Nested
    @DisplayName("3. The legacy screen literals the AAP requires are still relayed")
    class TheLegacyLiteralsSurvive {

        @Test
        @DisplayName("A validation refusal still carries its screen literal and its field and kind")
        void theValidationLiteralIsRelayed() {
            final ResponseEntity<ProblemDetail> answer = controllers().account().handleValidationFailure(
                    new ValidationException("Credit Limit must be supplied", "creditLimit",
                            ValidationException.FailureKind.BLANK));

            assertThat(answer.getBody().getDetail()).isEqualTo("Credit Limit must be supplied");
            assertThat(answer.getBody().getProperties()).containsEntry("field", "creditLimit");
            assertThat(answer.getBody().getProperties()).containsKey("failureKind");
        }

        @Test
        @DisplayName("A concurrency refusal still carries its outcome literal and the change-action marker")
        void theConcurrencyLiteralIsRelayed() {
            final ResponseEntity<ProblemDetail> answer = controllers().account().handleConcurrentUpdate(
                    new ConcurrentUpdateException(
                            ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT,
                            "Could not lock account record for update", SECRET_FILE, null));

            assertThat(answer.getBody().getDetail()).isEqualTo("Could not lock account record for update");
            assertThat(answer.getBody().getProperties()).containsEntry("changeAction", "L");
        }

        @Test
        @DisplayName("The user-administration duplicate arm still relays the COUSR01C.cbl:L263 caption")
        void theDuplicateUserLiteralIsRelayed() {
            final ResponseEntity<ProblemDetail> answer = controllers().admin().handleDuplicateRecord(
                    new DuplicateRecordException(USER_ID_TAKEN_CAPTION, SECRET_FILE, SECRET_KEY));

            assertThat(answer.getBody().getDetail()).isEqualTo(USER_ID_TAKEN_CAPTION);
            assertThat(answer.getBody().getProperties())
                    .containsEntry("errorCode", "CARDDEMO-DUPLICATE-RECORD")
                    .containsEntry("correlationId", CORRELATION_ID);
            // The caption reaches the wire; the dataset and the colliding identifier do not. The identifier is
            // the caller's own, but publishing it back turns the 409 into an enumeration oracle for anyone who
            // can reach the route.
            assertThat(renderedBody(answer.getBody()))
                    .doesNotContain(SECRET_FILE)
                    .doesNotContain(SECRET_KEY);
        }

        @Test
        @DisplayName("The report queue-failure arm still relays the TDQ literal of CORPT00C.cbl:L531")
        void theQueueFailureLiteralIsRelayed() {
            // The one relay that survives the CWE-209 review. ReportSubmissionService holds no reference to
            // FileStatusMapper and raises this type from a single site carrying a single literal, so the
            // provenance is static and the literal carries no internal name or status.
            final ResponseEntity<ProblemDetail> answer = controllers().report().handleQueueFailure(
                    new FileAccessException("Unable to Write TDQ (JOBS)..."));

            assertThat(answer.getBody().getDetail()).isEqualTo("Unable to Write TDQ (JOBS)...");
            assertThat(answer.getBody().getProperties())
                    .containsEntry("errorCode", "CARDDEMO-IO-FAILURE")
                    .containsEntry("correlationId", CORRELATION_ID);
            assertThat(renderedBody(answer.getBody())).doesNotContain("COBOL FILE STATUS");
        }

        @Test
        @DisplayName("The report queue-failure arm rejects any message that is not that literal")
        void aComposedDiagnosticIsNotRelayedByTheQueueFailureArm() {
            // The allow-list, exercised from the other side. If a route were ever added that let a
            // FileStatusMapper-composed FileAccessException reach this handler, the equality test replaces it
            // rather than relaying it - so the guarantee holds without depending on which collaborators
            // ReportSubmissionService holds.
            final ResponseEntity<ProblemDetail> answer = controllers().report()
                    .handleQueueFailure(new FileAccessException(SECRET_MESSAGE, SECRET_STATUS, SECRET_FILE,
                            SECRET_OPERATION));

            assertThat(answer.getBody().getDetail()).isNotEqualTo(SECRET_MESSAGE);
            assertThat(renderedBody(answer.getBody()))
                    .doesNotContain(SECRET_FILE)
                    .doesNotContain(SECRET_OPERATION)
                    .doesNotContain(SECRET_STATUS)
                    .doesNotContain("COBOL FILE STATUS");
            assertThat(answer.getBody().getProperties())
                    .containsEntry("errorCode", "CARDDEMO-IO-FAILURE");
        }

        @Test
        @DisplayName("A validation refusal is relayed because its type appears nowhere in the status mapper")
        void theValidationRelayIsTypeSafeRatherThanPathSafe() {
            // ValidationException and ConcurrentUpdateException are the only two types this application
            // relays, and the reason is local and checkable: neither appears in the switch of
            // FileStatusMapper.failureFor, so neither can carry a composed diagnostic whatever route it took.
            final ResponseEntity<ProblemDetail> answer = controllers().transaction()
                    .handleValidationFailure(new ValidationException("Tran ID must be numeric", "tranId",
                            ValidationException.FailureKind.INVALID));

            assertThat(answer.getBody().getDetail()).isEqualTo("Tran ID must be numeric");
        }
    }
}
