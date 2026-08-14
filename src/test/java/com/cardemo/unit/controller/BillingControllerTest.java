/*
 ******************************************************************************
 * Program     : BillingControllerTest
 * Application : CardDemo
 * Type        : Java unit test (JUnit 5)
 * Function    : Verifies the wire contract of BillingController, the REST
 *               replacement for CICS transaction CB00 and COBOL program
 *               app/cbl/COBIL00C.cbl. Asserts that the five-way confirmation
 *               gate of :173-:191 keeps its four behaviours distinguishable
 *               over HTTP, that the declined arm of :178-:181 is answered as a
 *               successful no-write termination rather than as a rejected
 *               input, and that no service-owned record reaches the wire.
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

import com.cardemo.controller.BillingController;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.model.dto.BillPaymentResponse;
import com.cardemo.service.billing.BillPaymentService;
import java.math.BigDecimal;
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
 * Unit tests for {@link BillingController}.
 *
 * <p>The service is mocked, because this tier owns exactly two decisions and neither reaches a database: which
 * HTTP status names each retained outcome, and which members of that outcome are publishable. Everything else
 * -- the confirmation classification, the balance guard, the identifier generation -- belongs to
 * {@code BillPaymentService} and is covered by its own suite.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("BillingController - the wire contract for CB00")
class BillingControllerTest {

    /** {@code ACTIDINI PIC X(11)}, the account key. Deliberately not all digits; see {@link #BALANCE_TEXT}. */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * {@code CURBALI}, the echoed balance.
     *
     * <p>Chosen so that it is <em>not</em> a substring of {@link #ACCOUNT_ID}. An all-numeric balance such as
     * {@code "0000000001"} sits inside {@code "00000000011"}, which would make a {@code doesNotContain}
     * assertion fail against a perfectly clean response.</p>
     */
    private static final String BALANCE_TEXT = "$1,940.00";

    /** {@code TRAN-ID PIC X(16)}, the generated key of {@code :211}-{@code :218}. */
    private static final String TRANSACTION_KEY = "0000000000000042";

    /** The success notice of {@code app/cbl/COBIL00C.cbl:242}. */
    private static final String SUCCESS_MESSAGE = "Payment successful. Your Balance is  0.00";

    /** The prompt of {@code app/cbl/COBIL00C.cbl:237}. */
    private static final String PROMPT_MESSAGE = "Confirm to make a bill payment...";

    /** The bill-payment service, mocked because this tier reaches no database. */
    @Mock
    private BillPaymentService billPaymentService;

    /** An authenticated principal, which is the stateless counterpart of a non-zero {@code EIBCALEN}. */
    private final Authentication principal =
            new TestingAuthenticationToken("USER0001", "n/a", "ROLE_USER");

    /**
     * Builds the controller under test.
     *
     * @return a controller wired to the mocked service
     */
    private BillingController controller() {
        return new BillingController(this.billPaymentService);
    }

    /**
     * Builds the inbound request.
     *
     * @param confirmation the {@code CONFIRMI} value to submit
     * @return a populated request
     */
    private static BillPaymentRequest request(final String confirmation) {
        return new BillPaymentRequest("CB00", "CardDemo", "08/03/26", "COBIL00C", "Bill Payment",
                "10:15:30", ACCOUNT_ID, null, confirmation, null);
    }

    /**
     * Builds the map the service reports as sent.
     *
     * @param accountId    the echoed account key, blank when the map was cleared
     * @param balance      the echoed balance, blank when the map was cleared
     * @param errorMessage the {@code ERRMSGO} caption, empty when the source emitted none
     * @return a populated map
     */
    private static BillPaymentService.BillPaymentScreen screen(final String accountId, final String balance,
                                                              final String errorMessage) {
        return new BillPaymentService.BillPaymentScreen("CB00", "CardDemo", "08/03/26", "COBIL00C",
                "Bill Payment", "10:15:30", accountId, balance, " ", errorMessage,
                BillPaymentService.MessageKind.INFORMATIONAL, BillPaymentService.CursorField.ACCOUNT_ID);
    }

    /**
     * Builds the retained outcome the controller projects.
     *
     * @param outcome the recorded outcome
     * @param map     the map the pass sent, or null to model a pass that sent none
     * @param receipt the receipt, or null when nothing was written
     * @return a populated result
     */
    private static BillPaymentService.BillPaymentResult result(
            final BillPaymentService.PaymentOutcome outcome,
            final BillPaymentService.BillPaymentScreen map,
            final BillPaymentService.PaymentReceipt receipt) {

        return new BillPaymentService.BillPaymentResult(outcome, BillPaymentService.ResponseKind.MAP,
                BillPaymentService.AidKey.ENTER, BillPaymentService.EntryMode.REENTER,
                BillPaymentService.EntryMode.REENTER, map, null,
                BillPaymentService.MessageKind.INFORMATIONAL, BillPaymentService.CursorField.ACCOUNT_ID,
                1, false, BillPaymentService.ConfirmationBranch.YES_UPPER, false, null, receipt, null,
                "CB00");
    }

    /** Builds the receipt of a settled payment. @return a populated receipt */
    private static BillPaymentService.PaymentReceipt receipt() {
        return new BillPaymentService.PaymentReceipt(TRANSACTION_KEY, new BigDecimal("1940.00"),
                BigDecimal.ZERO, "2026-08-03 10:15:30.0000", "2026-08-03 10:15:30.0000");
    }

    /**
     * Stubs the service to report the supplied outcome.
     *
     * @param outcome the outcome to report
     * @param map     the map the pass sent
     * @param receipt the receipt, or null
     */
    private void arrange(final BillPaymentService.PaymentOutcome outcome,
                         final BillPaymentService.BillPaymentScreen map,
                         final BillPaymentService.PaymentReceipt receipt) {

        when(this.billPaymentService.processRequest(any(), eq("ENTER"),
                eq(BillPaymentService.EntryMode.REENTER), eq(null)))
                .thenReturn(result(outcome, map, receipt));
    }

    /**
     * The declined arm is the finding this class exists to pin.
     *
     * <p>{@code WHEN 'N'} and {@code WHEN 'n'} at {@code app/cbl/COBIL00C.cbl:178}-{@code :181} perform
     * {@code CLEAR-CURRENT-SCREEN} and raise {@code WS-ERR-FLG}, and nothing else. The arm moves no message
     * and positions no cursor, so there is no caption to relay and nothing for the caller to correct --
     * which is exactly what disqualifies a {@code 4xx}. An earlier implementation answered {@code 400}.</p>
     */
    @Nested
    @DisplayName("declining at the confirmation prompt is a success, not a rejection")
    class CancellationContract {

        @Test
        @DisplayName(":178-:181 a declined confirmation answers 200 with CANCELLED")
        void aDeclinedConfirmationAnswers200() {
            arrange(BillPaymentService.PaymentOutcome.CONFIRMATION_DECLINED,
                    screen("           ", "          ", ""), null);

            final ResponseEntity<BillPaymentResponse> response =
                    controller().payBill(principal, request("N"));

            assertThat(response.getStatusCode())
                    .as("the operator exercised a documented choice; nothing was rejected")
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().outcome())
                    .isEqualTo(BillPaymentResponse.Outcome.CANCELLED);
        }

        @Test
        @DisplayName("the cancelled body reports no transaction identifier, because nothing was written")
        void theCancelledBodyReportsNoTransactionIdentifier() {
            arrange(BillPaymentService.PaymentOutcome.CONFIRMATION_DECLINED,
                    screen("           ", "          ", ""), null);

            final BillPaymentResponse body = controller().payBill(principal, request("n")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.transactionId())
                    .as("no write occurred, so no identifier was ever generated")
                    .isNull();
        }

        /**
         * The cleared members come back blank rather than echoed, and that is faithful rather than lossy:
         * {@code INITIALIZE-ALL-FIELDS} at {@code :560}-{@code :566} moves {@code SPACES} into
         * {@code ACTIDINI}, {@code CURBALI} and {@code CONFIRMI} before the map is sent at {@code :555}.
         * Echoing the submitted identifier back would invent data the source deliberately erased.
         */
        @Test
        @DisplayName(":560-:566 the cleared map is reported cleared, not re-populated from the request")
        void theClearedMapIsReportedCleared() {
            arrange(BillPaymentService.PaymentOutcome.CONFIRMATION_DECLINED,
                    screen("           ", "          ", ""), null);

            final BillPaymentResponse body = controller().payBill(principal, request("N")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.accountId()).isBlank();
            assertThat(body.currentBalance()).isBlank();
            assertThat(body.message()).isEmpty();
        }
    }

    /**
     * The remaining two non-failure endings keep their own statuses, so the gate stays distinguishable.
     */
    @Nested
    @DisplayName("the confirmation gate keeps its four behaviours distinguishable")
    class GateContract {

        @Test
        @DisplayName(":174-:177 a confirmed payment answers 201 with SETTLED and the generated identifier")
        void aConfirmedPaymentAnswers201() {
            arrange(BillPaymentService.PaymentOutcome.PAYMENT_SUCCESSFUL,
                    screen(ACCOUNT_ID, "0.00", SUCCESS_MESSAGE), receipt());

            final ResponseEntity<BillPaymentResponse> response =
                    controller().payBill(principal, request("Y"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().outcome()).isEqualTo(BillPaymentResponse.Outcome.SETTLED);
            assertThat(response.getBody().transactionId()).isEqualTo(TRANSACTION_KEY);
            assertThat(response.getBody().message()).isEqualTo(SUCCESS_MESSAGE);
        }

        @Test
        @DisplayName(":182-:184 the display-only arm answers 200 with CONFIRMATION_REQUIRED and no identifier")
        void theDisplayOnlyArmAnswers200() {
            arrange(BillPaymentService.PaymentOutcome.CONFIRMATION_REQUIRED,
                    screen(ACCOUNT_ID, BALANCE_TEXT, PROMPT_MESSAGE), null);

            final ResponseEntity<BillPaymentResponse> response =
                    controller().payBill(principal, request(" "));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().outcome())
                    .isEqualTo(BillPaymentResponse.Outcome.CONFIRMATION_REQUIRED);
            assertThat(response.getBody().currentBalance()).isEqualTo(BALANCE_TEXT);
            assertThat(response.getBody().transactionId()).isNull();
        }

        /**
         * All three non-failure endings are distinct values, which is what a client branches on. Asserted as a
         * set rather than one at a time, because the risk being guarded is two endings collapsing onto one.
         */
        @Test
        @DisplayName("the three non-failure endings are three distinct outcome values")
        void theThreeEndingsAreDistinct() {
            assertThat(BillPaymentResponse.Outcome.values())
                    .containsExactlyInAnyOrder(BillPaymentResponse.Outcome.SETTLED,
                            BillPaymentResponse.Outcome.CONFIRMATION_REQUIRED,
                            BillPaymentResponse.Outcome.CANCELLED);
        }
    }

    /**
     * No service-owned record may reach the wire, and no presentation member may ride along inside the body.
     */
    @Nested
    @DisplayName("the body is API-native, carrying no 3270 or CICS state")
    class WireContract {

        @Test
        @DisplayName("the declared response type is the DTO-package record, not the service map")
        void theDeclaredResponseTypeIsTheDto() throws NoSuchMethodException {
            final java.lang.reflect.Method operation = BillingController.class
                    .getMethod("payBill", Authentication.class, BillPaymentRequest.class);

            assertThat(operation.getGenericReturnType().getTypeName())
                    .as("returning BillPaymentService.BillPaymentScreen would couple HTTP to service state")
                    .isEqualTo("org.springframework.http.ResponseEntity<"
                            + "com.cardemo.model.dto.BillPaymentResponse>");
        }

        /**
         * The four presentation members the service map carries have no counterpart on the response, so they
         * cannot be serialized. Asserted structurally, because the guarantee is the shape of the type rather
         * than the value of one instance.
         */
        @Test
        @DisplayName("the response record declares no header, highlight or cursor member")
        void theResponseDeclaresNoPresentationMember() {
            assertThat(BillPaymentResponse.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("outcome", "accountId", "currentBalance", "transactionId", "message")
                    .doesNotContain("transactionName", "title01", "title02", "currentDate", "currentTime",
                            "programName", "messageKind", "cursor", "confirmation");
        }

        @Test
        @DisplayName("the settled body carries no 3270 header text")
        void theSettledBodyCarriesNoHeaderText() {
            arrange(BillPaymentService.PaymentOutcome.PAYMENT_SUCCESSFUL,
                    screen(ACCOUNT_ID, "0.00", SUCCESS_MESSAGE), receipt());

            final BillPaymentResponse body = controller().payBill(principal, request("Y")).getBody();

            assertThat(body).isNotNull();
            assertThat(body.toString())
                    .doesNotContain("CardDemo")
                    .doesNotContain("COBIL00C")
                    .doesNotContain("10:15:30");
        }
    }

    /**
     * Status translation for the paths that are genuinely failures, and for the unauthenticated case.
     */
    @Nested
    @DisplayName("failure translation agrees with the other four resources")
    class StatusContract {

        /**
         * A pass that reports neither a payment nor a retained failure is unreachable through the authored
         * service, so it is an abend rather than an empty body.
         */
        @Test
        @DisplayName("an outcome with no retained failure and no success arm abends")
        void anUnclassifiedOutcomeAbends() {
            arrange(BillPaymentService.PaymentOutcome.NOTHING_TO_PAY,
                    screen(ACCOUNT_ID, "0.00", "You have nothing to pay..."), null);
            final BillingController controller = controller();
            final BillPaymentRequest submitted = request("Y");

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> controller.payBill(principal, submitted));
        }

        @Test
        @DisplayName("a retained typed failure is rethrown unchanged for the mapper to translate")
        void aRetainedFailureIsRethrown() {
            final FileAccessException retained =
                    new FileAccessException("Unable to lookup Account...", "9010", "ACCTDAT ", "READ");
            when(billPaymentService.processRequest(any(), eq("ENTER"),
                    eq(BillPaymentService.EntryMode.REENTER), eq(null)))
                    .thenReturn(new BillPaymentService.BillPaymentResult(
                            BillPaymentService.PaymentOutcome.ACCOUNT_LOOKUP_FAILED,
                            BillPaymentService.ResponseKind.MAP, BillPaymentService.AidKey.ENTER,
                            BillPaymentService.EntryMode.REENTER, BillPaymentService.EntryMode.REENTER,
                            screen(ACCOUNT_ID, "0.00", "Unable to lookup Account..."), null,
                            BillPaymentService.MessageKind.ERROR,
                            BillPaymentService.CursorField.ACCOUNT_ID, 1, true,
                            BillPaymentService.ConfirmationBranch.YES_UPPER, false, null, null, retained,
                            "CB00"));
            final BillingController controller = controller();
            final BillPaymentRequest submitted = request("Y");

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> controller.payBill(principal, submitted))
                    .isSameAs(retained);
        }

        /**
         * {@code FileAccessException} answers {@code 502}, agreeing with the account, card, transaction and
         * report resources. This is the finding that had {@code CardController} as its single outlier at
         * {@code 500}.
         *
         * <p>Note that the detail is a fixed operator-facing sentence rather than the exception's own message.
         * That is deliberate and is not the echo-the-source-literal convention used for rejections: a
         * {@code 9x} file status is an internal storage condition, and relaying its text would disclose
         * dataset internals to a caller who can do nothing with them. The expanded status is published as a
         * problem <em>property</em> instead, where a diagnosing operator can still reach it.</p>
         */
        @Test
        @DisplayName("a file-access failure is mapped to 502, not 500, with the status as a property")
        void aFileAccessFailureMapsTo502() {
            final FileAccessException failure =
                    new FileAccessException("Unable to lookup Account...", "9010", "ACCTDAT ", "READ");

            final ResponseEntity<ProblemDetail> response = controller().handleFileAccessFailure(failure);

            assertThat(response.getStatusCode())
                    .as("four of the five resources answer 502; none may drift back to 500")
                    .isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTitle()).isEqualTo("Bill payment dataset access failed");
            assertThat(response.getBody().getProperties()).isNotNull();
            // The expanded status, the logical file and the operation are no longer relayed to the caller.
            // Each described the server's internals - a legacy file status, a dataset name and a VSAM verb -
            // and all three now go to the ERROR log, where the correlation identifier in this body is how a
            // caller reporting the failure reaches them. The body carries the stable envelope instead, and
            // the absence is asserted as well as the presence so neither can drift back.
            assertThat(response.getBody().getProperties())
                    .containsEntry("errorCode", "CARDDEMO-IO-FAILURE")
                    .containsKey("correlationId")
                    .doesNotContainKeys("ioStatus", "expandedStatus", "logicalFile", "operation");
            assertThat(response.getBody().getDetail())
                    .as("a 9x status is an internal storage condition, so its text is not relayed")
                    .doesNotContain("ACCTDAT");
        }

        @Test
        @DisplayName("a rejected input is mapped to 400 carrying the source literal verbatim")
        void aRejectedInputMapsTo400() {
            final ResponseEntity<ProblemDetail> response = controller().handleValidationFailure(
                    ValidationException.invalidField("CONFIRMI",
                            "Invalid value. Valid values are (Y/N)..."));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getDetail())
                    .isEqualTo("Invalid value. Valid values are (Y/N)...");
        }

        /**
         * Supplies the three unauthenticated states. They are genuinely distinct rather than three spellings
         * of one thing, and Rule 1 Clause B requires each to be handled explicitly.
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
        @DisplayName(":107 an absent identity answers 401 and never reaches the service")
        void anAbsentIdentityAnswers401(final String description, final Authentication unusable) {
            final ResponseEntity<BillPaymentResponse> response =
                    controller().payBill(unusable, request("Y"));

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
                    .isThrownBy(() -> new BillingController(null))
                    .withMessageContaining("COBIL00C");
        }
    }
}
