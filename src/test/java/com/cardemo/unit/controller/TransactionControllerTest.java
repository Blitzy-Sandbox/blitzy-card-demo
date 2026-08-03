/*
 * ******************************************************************
 * Program     : TransactionControllerTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Wire-contract guard for TransactionController. Pins the
 *               properties a reader cannot confirm from the service tier: that
 *               no card number leaves in the clear on any of the three
 *               operations, that the list envelope carries the turn's own
 *               ERRMSGI status text and its paging metadata, that every control
 *               token is matched exactly rather than coerced, and that an
 *               input-output failure answers 502.
 * Source      : app/cbl/COTRN00C.cbl (699 lines), app/cbl/COTRN01C.cbl (330),
 *               app/cbl/COTRN02C.cbl (783), app/csd/CARDDEMO.CSD,
 *               app/cpy-bms/COTRN00.CPY, COTRN01.CPY, COTRN02.CPY @ 7756d89
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
package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.controller.TransactionController;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.model.dto.TransactionAddRequest;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.dto.TransactionListResponse;
import com.cardemo.model.dto.TransactionResponse;
import com.cardemo.service.transaction.TransactionAddService;
import com.cardemo.service.transaction.TransactionDetailService;
import com.cardemo.service.transaction.TransactionListService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for {@link TransactionController}, the REST replacement for CICS transactions {@code CT00},
 * {@code CT01} and {@code CT02} at traceability anchor commit {@code 7756d89}.
 *
 * <p><strong>1. What it does.</strong> This class asserts the wire contract and nothing else; the three
 * services own the business behaviour and are pinned by their own suites. Four properties are asserted here,
 * each of which a service-tier test is structurally unable to observe.</p>
 *
 * <ul>
 *   <li><em>No card number leaves in the clear.</em> {@code CARDNI PIC X(16)} of
 *       {@code app/cpy-bms/COTRN01.CPY} is a primary account number, and it is tail-masked on the detail
 *       response and on both the created and the no-write paths of the add response.</li>
 *   <li><em>The list envelope carries the turn's own context.</em> {@code ERRMSGO} is how
 *       {@code app/cbl/COTRN00C.cbl} reported being at the top or the bottom of the browse - answers rather
 *       than faults, inexpressible as a status code - and it now reaches a client alongside the page
 *       metadata.</li>
 *   <li><em>Control tokens are matched, not coerced.</em> The framework's converters trim an enum token,
 *       accept a signed integer and treat six spellings as a boolean; none of that reaches this
 *       operation.</li>
 *   <li><em>One file status yields one status code.</em> An input-output failure answers {@code 502}.</li>
 * </ul>
 *
 * <p><strong>2. How to run, build and test.</strong> Bound to the Surefire tier by its {@code *Test} name
 * under {@code src/test/java/com/cardemo/unit}.</p>
 *
 * <pre>
 * ./mvnw -B -ntp test -Dtest=TransactionControllerTest
 * ./mvnw -B -ntp -Ddependency-check.skip=true clean verify
 * </pre>
 *
 * <p><strong>3. Key configuration and defaults.</strong> The three services are mocked and no Spring context
 * is started: every handler and every exception handler is a plain method call.</p>
 *
 * <p><strong>4. Common failure modes.</strong> A failure in the masking group means a card number reached a
 * response. A failure in the context group means the source's own status text or the page metadata is being
 * discarded again. A failure in the token group means a control token is being coerced. A failure in the
 * status group means one file status is producing two status codes across the API.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("TransactionController - the wire contract for CT00, CT01 and CT02")
class TransactionControllerTest {

    /** {@code TRNIDINI PIC X(16)}, the transaction key. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /** {@code ACTIDINI PIC X(11)}, the account key the add screen carries. */
    private static final String ACCOUNT_ID = "00000000011";

    /** {@code CARDNI PIC X(16)}, a primary account number that no response may carry in the clear. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The masked rendering the response must carry instead. */
    private static final String MASKED_CARD_NUMBER = "************1111";

    /** {@code ERRMSGO} as the source paints it when the browse is already at the bottom. */
    private static final String STATUS_MESSAGE = "You have reached the bottom of the page...";

    /** The transaction-list service, mocked because this tier reaches no database. */
    @Mock
    private TransactionListService transactionListService;

    /** The transaction-detail service, mocked on the same terms. */
    @Mock
    private TransactionDetailService transactionDetailService;

    /** The transaction-add service, mocked on the same terms. */
    @Mock
    private TransactionAddService transactionAddService;

    /** The controller under test, rebuilt before every test. */
    private TransactionController controller;

    /** A principal that satisfies the operation's authentication guard. */
    private Authentication principal;

    /**
     * Builds the controller and an authenticated principal, so no state survives a test.
     */
    @BeforeEach
    void createControllerUnderTest() {
        controller = new TransactionController(transactionListService, transactionDetailService,
                transactionAddService);
        principal = new TestingAuthenticationToken("USER0001", "n/a", "ROLE_USER");
    }

    /**
     * Builds the twenty-three-component projection the services produce, with the card number populated so a
     * leak would be detectable.
     *
     * @param errorMessage the {@code ERRMSGI} status line, or null
     * @param rows the list rows, or null for a detail projection
     * @return a populated projection
     */
    private static TransactionDto projection(final String errorMessage,
                                             final List<TransactionDto.TransactionListRow> rows) {
        return new TransactionDto("CT01", "CardDemo", "08/03/26", "COTRN01C", "View Transaction",
                "10:15:30", TRANSACTION_ID, TRANSACTION_ID, CARD_NUMBER, "01", "0001", "POS TERM",
                "GROCERY PURCHASE", "+00000042.50", "2026-08-01",
                "2026-08-02", "000000123", "CORNER STORE", "SEATTLE", "98101",
                errorMessage, "00000001", rows, new BigDecimal("42.50"));
    }

    /**
     * Builds one list row.
     *
     * @param transactionId the row's identifier
     * @return a populated row
     */
    private static TransactionDto.TransactionListRow row(final String transactionId) {
        return new TransactionDto.TransactionListRow(" ", transactionId, "20260801", "GROCERY PURCHASE",
                "+00000042.50");
    }

    /**
     * Builds the list screen the controller projects, with both keyset anchors populated.
     *
     * @param errorMessage the {@code ERRMSGI} status line, or null
     * @return a populated screen
     */
    private static TransactionListService.TransactionListScreen listScreen(final String errorMessage) {
        final List<TransactionDto.TransactionListRow> rows =
                List.of(row("0000000000000001"), row("0000000000000002"));
        final PageResponse<TransactionDto.TransactionListRow> page = new PageResponse<>(rows, 1, 10, true,
                "0000000000000001", "0000000000000002");
        return new TransactionListService.TransactionListScreen(projection(errorMessage, rows), page,
                new TransactionListService.TransactionListState("0000000000000001",
                        "0000000000000002", 1, true),
                null, null, null, errorMessage != null);
    }

    /**
     * Builds the add result the controller projects.
     *
     * @param outcome the recorded outcome
     * @return a populated result
     */
    private static TransactionAddService.TransactionAddResult addResult(
            final TransactionAddService.Outcome outcome) {
        return new TransactionAddService.TransactionAddResult(outcome, projection(null, null), null, null,
                null, TRANSACTION_ID);
    }

    /**
     * Builds the inbound add request the controller forwards to the service.
     *
     * <p>Every component of {@link TransactionAddRequest} is a {@code String} carrying a legacy screen field
     * from {@code app/cpy-bms/COTRN02.CPY}, so the record has no no-argument constructor. The controller under
     * test forwards this value unexamined and asserts nothing about it -- the service is mocked -- so the
     * fixture supplies only the two keys a reader needs to follow the flow and leaves the remaining
     * nineteen screen fields null.
     *
     * @return a request carrying the account and card keys only
     */
    private static TransactionAddRequest addRequest() {
        return new TransactionAddRequest(null, null, null, null, null, null, ACCOUNT_ID, CARD_NUMBER, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Runs one list request with every control token absent.
     *
     * @return the response body, never null
     */
    private TransactionListResponse listWithDefaults() {
        return controller.listTransactions(null, null, null, null, null, null, principal).getBody();
    }

    /**
     * No card number may leave in the clear, on any of the three operations.
     */
    @Nested
    @DisplayName("no card number leaves in the clear")
    class MaskingContract {

        /**
         * The detail response carries the masked rendering and not the number, and carries no 3270 header
         * field either.
         */
        @Test
        @DisplayName("the detail response masks the card number and drops the six header fields")
        void theDetailResponseMasksTheCardNumber() {
            when(transactionDetailService.viewTransaction(TRANSACTION_ID))
                    .thenReturn(new TransactionDetailService.TransactionDetailScreen(
                            projection(null, null), null, null, false));

            final TransactionResponse body =
                    controller.getTransactionDetail(TRANSACTION_ID, principal).getBody();

            assertThat(body).isNotNull();
            assertThat(body.maskedCardNumber()).isEqualTo(MASKED_CARD_NUMBER);
            assertThat(body.toString()).doesNotContain(CARD_NUMBER);
            assertThat(body.toString()).doesNotContain("CardDemo").doesNotContain("10:15:30");
        }

        /**
         * The created response masks it too, which matters most of all: a {@code 201} is the response a
         * client is likeliest to record.
         */
        @Test
        @DisplayName("the created response masks the card number and still addresses the new resource")
        void theCreatedResponseMasksTheCardNumber() {
            when(transactionAddService.addTransaction(any()))
                    .thenReturn(addResult(TransactionAddService.Outcome.ADDED));

            final ResponseEntity<TransactionResponse> response =
                    controller.addTransaction(addRequest(), principal);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getHeaders().getFirst("Location")).isNotNull();
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().maskedCardNumber()).isEqualTo(MASKED_CARD_NUMBER);
            assertThat(response.getBody().toString()).doesNotContain(CARD_NUMBER);
        }

        /**
         * The four no-write terminations answer {@code 200} with the same masked shape and no
         * {@code Location} header, because none of them created anything.
         */
        @Test
        @DisplayName("each no-write outcome answers 200 with the same masked shape and no Location")
        void everyNoWriteOutcomeAnswers200Masked() {
            when(transactionAddService.addTransaction(any()))
                    .thenReturn(addResult(TransactionAddService.Outcome.CONFIRMATION_REQUIRED))
                    .thenReturn(addResult(TransactionAddService.Outcome.SCREEN_DISPLAYED))
                    .thenReturn(addResult(TransactionAddService.Outcome.SCREEN_CLEARED))
                    .thenReturn(addResult(TransactionAddService.Outcome.INVALID_KEY));

            for (int attempt = 0; attempt < 4; attempt++) {
                final ResponseEntity<TransactionResponse> response =
                        controller.addTransaction(addRequest(), principal);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getHeaders().getFirst("Location")).isNull();
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().maskedCardNumber()).isEqualTo(MASKED_CARD_NUMBER);
            }
        }

        /**
         * No list row declares a card-number component at all, so the browse cannot leak one however its rows
         * are populated. Asserted structurally rather than by value, because the guarantee is the shape.
         */
        @Test
        @DisplayName("the list row type declares no card-number component")
        void theListRowDeclaresNoCardNumber() {
            when(transactionListService.submitScreen(any(), any(), any(), any(), any()))
                    .thenReturn(listScreen(null));

            final TransactionListResponse body = listWithDefaults();

            assertThat(body).isNotNull();
            assertThat(body.rows()).isNotEmpty();
            assertThat(body.toString()).doesNotContain(CARD_NUMBER);
        }
    }

    /**
     * The turn's own status text and its paging metadata reach a client.
     */
    @Nested
    @DisplayName("the list envelope carries the turn's context, not only its rows")
    class ContextContract {

        /**
         * The status line survives the projection, relayed byte for byte.
         */
        @Test
        @DisplayName("the ERRMSGI status line is relayed byte for byte")
        void theStatusLineIsRelayed() {
            when(transactionListService.submitScreen(any(), any(), any(), any(), any()))
                    .thenReturn(listScreen(STATUS_MESSAGE));

            final TransactionListResponse body = listWithDefaults();

            assertThat(body).isNotNull();
            assertThat(body.statusMessage()).isEqualTo(STATUS_MESSAGE);
        }

        /**
         * A turn with no status text reports none rather than an invented placeholder.
         */
        @Test
        @DisplayName("a turn with no status text reports none")
        void aTurnWithoutStatusTextReportsNone() {
            when(transactionListService.submitScreen(any(), any(), any(), any(), any()))
                    .thenReturn(listScreen(null));

            final TransactionListResponse body = listWithDefaults();

            assertThat(body).isNotNull();
            assertThat(body.statusMessage()).isNull();
        }

        /**
         * The page number, the page size in force, the next-page flag, the row list and both keyset cursors
         * all reach a client, which is what makes the browse continuable.
         */
        @Test
        @DisplayName("the page metadata and both keyset cursors are all reported")
        void theEnvelopeCarriesTheFullPagingMetadata() {
            when(transactionListService.submitScreen(any(), any(), any(), any(), any()))
                    .thenReturn(listScreen(null));

            final TransactionListResponse body = listWithDefaults();

            assertThat(body).isNotNull();
            assertThat(body.pageNumber()).isEqualTo(1);
            assertThat(body.pageSize()).isEqualTo(10);
            assertThat(body.nextPageAvailable()).isTrue();
            assertThat(body.rows()).hasSize(2);
            assertThat(body.firstCursor()).isEqualTo("0000000000000001");
            assertThat(body.lastCursor()).isEqualTo("0000000000000002");
        }
    }

    /**
     * Control tokens decide which browse arm runs. They are matched exactly.
     */
    @Nested
    @DisplayName("control tokens are matched exactly, never coerced")
    class TokenContract {

        /**
         * Absent tokens take the source's own first-entry values, so a bare request behaves as before.
         */
        @Test
        @DisplayName("absent tokens default to SUBMIT, page zero and no next page")
        void absentTokensTakeTheFirstEntryDefaults() {
            when(transactionListService.submitScreen(any(), any(), any(), any(), any()))
                    .thenReturn(listScreen(null));

            listWithDefaults();

            final ArgumentCaptor<TransactionListService.AttentionIdentifier> aid =
                    ArgumentCaptor.forClass(TransactionListService.AttentionIdentifier.class);
            final ArgumentCaptor<TransactionListService.TransactionListState> state =
                    ArgumentCaptor.forClass(TransactionListService.TransactionListState.class);
            verify(transactionListService).submitScreen(aid.capture(), any(), any(), any(),
                    state.capture());
            assertThat(aid.getValue()).isEqualTo(TransactionListService.AttentionIdentifier.ENTER);
            assertThat(state.getValue().pageNumber()).isZero();
            assertThat(state.getValue().nextPageAvailable()).isFalse();
        }

        /**
         * The three declared spellings resolve, and their attention identifiers reach the service unchanged.
         */
        @Test
        @DisplayName("the three declared action tokens resolve to their attention identifiers")
        void theThreeDeclaredActionTokensResolve() {
            when(transactionListService.submitScreen(any(), any(), any(), any(), any()))
                    .thenReturn(listScreen(null));

            controller.listTransactions(null, "SUBMIT", null, null, null, null, principal);
            controller.listTransactions(null, "PAGE_BACKWARD", null, null, null, null, principal);
            controller.listTransactions(null, "PAGE_FORWARD", null, null, null, null, principal);

            final ArgumentCaptor<TransactionListService.AttentionIdentifier> aid =
                    ArgumentCaptor.forClass(TransactionListService.AttentionIdentifier.class);
            verify(transactionListService, times(3)).submitScreen(aid.capture(), any(), any(), any(),
                    any());
            assertThat(aid.getAllValues()).containsExactly(
                    TransactionListService.AttentionIdentifier.ENTER,
                    TransactionListService.AttentionIdentifier.PF7,
                    TransactionListService.AttentionIdentifier.PF8);
        }

        /**
         * A padded or lower-cased action token is refused. The framework's enum binding would have trimmed
         * the first and the second would have failed with a less specific message.
         */
        @Test
        @DisplayName("a padded or lower-cased action token is refused")
        void aPaddedOrFoldedActionTokenIsRefused() {
            assertRefused(() -> controller.listTransactions(null, " SUBMIT ", null, null, null, null,
                    principal));
            assertRefused(() -> controller.listTransactions(null, "submit", null, null, null, null,
                    principal));
            verifyNoInteractions(transactionListService);
        }

        /**
         * An empty action token is a blank failure rather than an invalid one, keeping the two-state
         * discriminator observable on the wire.
         */
        @Test
        @DisplayName("an empty action token reports BLANK, not INVALID")
        void anEmptyActionTokenReportsBlank() {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> controller.listTransactions(null, "", null, null, null, null, principal));

            assertThat(failure.getFieldName()).isEqualTo("action");
            assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
        }

        /**
         * A signed or padded page token is refused. The framework's {@code int} binding accepts both.
         */
        @Test
        @DisplayName("a signed or padded page token is refused")
        void aSignedOrPaddedPageTokenIsRefused() {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> controller.listTransactions(null, null, "+1", null, null, null, principal));

            assertThat(failure.getFieldName()).isEqualTo("page");
            assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            assertRefused(() -> controller.listTransactions(null, null, " 1", null, null, null,
                    principal));
            verifyNoInteractions(transactionListService);
        }

        /**
         * A page token wider than the eight-digit counter is refused before it is parsed, so an oversized
         * value is a range refusal rather than an overflow.
         */
        @Test
        @DisplayName("a page token wider than the eight-digit counter is refused")
        void anOverWidePageTokenIsRefused() {
            assertRefused(() -> controller.listTransactions(null, null, "999999999", null, null, null,
                    principal));
        }

        /**
         * Every alias the framework's boolean binding would have accepted is refused, one assertion each so
         * that a regression names the alias that returned.
         */
        @Test
        @DisplayName("yes, on, 1 and their negatives are all refused as next-page tokens")
        void everyBooleanAliasIsRefused() {
            assertRefused(() -> controller.listTransactions(null, null, null, null, null, "yes",
                    principal));
            assertRefused(() -> controller.listTransactions(null, null, null, null, null, "on",
                    principal));
            assertRefused(() -> controller.listTransactions(null, null, null, null, null, "1",
                    principal));
            assertRefused(() -> controller.listTransactions(null, null, null, null, null, "no",
                    principal));
            assertRefused(() -> controller.listTransactions(null, null, null, null, null, "off",
                    principal));
            assertRefused(() -> controller.listTransactions(null, null, null, null, null, "0",
                    principal));
            assertRefused(() -> controller.listTransactions(null, null, null, null, null, "TRUE",
                    principal));
            verifyNoInteractions(transactionListService);
        }

        /**
         * The two declared spellings resolve, so refusing the aliases has not refused the values themselves.
         */
        @Test
        @DisplayName("true and false resolve as next-page tokens")
        void theTwoDeclaredBooleanTokensResolve() {
            when(transactionListService.submitScreen(any(), any(), any(), any(), any()))
                    .thenReturn(listScreen(null));

            controller.listTransactions(null, null, null, null, null, "true", principal);
            controller.listTransactions(null, null, null, null, null, "false", principal);

            final ArgumentCaptor<TransactionListService.TransactionListState> state =
                    ArgumentCaptor.forClass(TransactionListService.TransactionListState.class);
            verify(transactionListService, times(2)).submitScreen(any(), any(), any(), any(),
                    state.capture());
            assertThat(state.getAllValues())
                    .extracting(TransactionListService.TransactionListState::nextPageAvailable)
                    .containsExactly(true, false);
        }

        /**
         * Asserts that one invocation is refused as a validation failure.
         *
         * @param invocation the call to make
         */
        private void assertRefused(final Runnable invocation) {
            assertThatThrownBy(invocation::run).isInstanceOf(ValidationException.class);
        }
    }

    /**
     * One file status must yield one status code across the API.
     */
    @Nested
    @DisplayName("failure translation agrees with the other four resources")
    class StatusContract {

        /**
         * An input-output failure answers {@code 502}, carrying the four-character expanded status.
         */
        @Test
        @DisplayName("an input-output failure answers 502 with the expanded status")
        void anInputOutputFailureAnswers502() {
            final FileAccessException failure =
                    new FileAccessException("browse failed", "37", "TRANSACT", "READNEXT");

            final ResponseEntity<ProblemDetail> response = controller.handleFileAccessFailure(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isNotNull();
            // The expanded status, the logical file and the operation are no longer relayed to the caller.
            // Each described the server's internals - a legacy file status, a dataset name and a VSAM verb -
            // and all three now go to the ERROR log, where the correlation identifier in this body is how a
            // caller reporting the failure reaches them. The body carries the stable envelope instead, and
            // the absence is asserted as well as the presence so neither can drift back.
            assertThat(response.getBody().getProperties())
                    .containsEntry("errorCode", "CARDDEMO-IO-FAILURE")
                    .containsKey("correlationId")
                    .doesNotContainKeys("ioStatus", "expandedStatus", "logicalFile", "operation");
        }
    }
}
