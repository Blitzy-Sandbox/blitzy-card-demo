/*
 * ******************************************************************
 * Program     : CardControllerTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Wire-contract guard for CardController. Pins the properties a
 *               reader cannot confirm from the service tier: that no response
 *               and no page cursor carries a card number in the clear, that the
 *               as-displayed snapshot is server-issued and travels as an entity
 *               tag, that every control token is matched exactly rather than
 *               coerced, and that one file status produces one status code.
 * Source      : app/cbl/COCRDLIC.cbl (1,459 lines), app/cbl/COCRDSLC.cbl (887),
 *               app/cbl/COCRDUPC.cbl (1,560), app/csd/CARDDEMO.CSD,
 *               app/cpy-bms/COCRDLI.CPY, COCRDSL.CPY, COCRDUP.CPY @ 7756d89
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.controller.CardController;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.CardListResponse;
import com.cardemo.model.dto.CardResponse;
import com.cardemo.model.dto.CardUpdateRequest;
import com.cardemo.model.dto.PageResponse;
import com.cardemo.security.SnapshotTokenService;
import com.cardemo.service.card.CardDetailService;
import com.cardemo.service.card.CardListService;
import com.cardemo.service.card.CardUpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
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

/**
 * Unit tests for {@link CardController}, the REST replacement for CICS transactions {@code CCLI},
 * {@code CCDL} and {@code CCUP} at traceability anchor commit {@code 7756d89}.
 *
 * <p><strong>1. What it does.</strong> This class asserts the <em>wire contract</em> and nothing else.
 * Business behaviour belongs to the three services and is pinned by their own suites; what cannot be
 * pinned there is what leaves the process. Four properties are therefore asserted here, each of which
 * a service-tier test is structurally unable to observe.</p>
 *
 * <ul>
 *   <li><em>No card number leaves in the clear.</em> Every card number on a response is tail-masked, and
 *       the two page cursors - which in this browse <em>are</em> card numbers,
 *       {@code WS-CA-FIRST-CARD-NUM} at {@code app/cbl/COCRDLIC.cbl:L234} and
 *       {@code WS-CA-LAST-CARD-NUM} at {@code :L231} - travel sealed.</li>
 *   <li><em>The as-displayed snapshot is server-issued.</em> It is produced by the update service, sealed,
 *       published as an {@code ETag}, required back in {@code If-Match}, and refused if a caller tries to
 *       supply one in the body.</li>
 *   <li><em>Control tokens are matched, not coerced.</em> The framework's default converters trim an enum
 *       token, accept a signed integer and treat six spellings as a boolean; none of that reaches this
 *       operation.</li>
 *   <li><em>One file status yields one status code.</em> An input-output failure answers {@code 502} here
 *       as it does on the four other resources.</li>
 *   </ul>
 *
 * <p><strong>2. How to run, build and test.</strong> Bound to the Surefire tier by its {@code *Test} name
 * under {@code src/test/java/com/cardemo/unit}.</p>
 *
 * <pre>
 * ./mvnw -B -ntp test -Dtest=CardControllerTest
 * ./mvnw -B -ntp -Ddependency-check.skip=true clean verify
 * </pre>
 *
 * <p><strong>3. Key configuration and defaults.</strong> The three services are mocked; the snapshot sealer
 * is <em>real</em>, built over a fixed clock and a test key, because a mocked sealer would let a test hand
 * the controller any cursor or snapshot it liked - which is precisely the property the sealing removes. No
 * Spring context is started: every handler and every exception handler is a plain method call, which is what
 * makes these assertions cheap enough to keep.</p>
 *
 * <p><strong>4. Common failure modes.</strong> A failure in the masking group means a card number reached a
 * response or a cursor. A failure in the snapshot group means the update precondition became caller
 * controlled. A failure in the token group means a control token is being coerced again. A failure in the
 * status group means one file status is producing two status codes across the API.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("CardController - the wire contract for CCLI, CCDL and CCUP")
class CardControllerTest {

    /** A signing key of the length the sealer requires, generated per run so nothing is committed. */
    private static final String TEST_SIGNING_KEY = ephemeralSigningKey();

    /** Token lifetime, long enough that no test can age one out by accident. */
    private static final long TOKEN_LIFETIME_SECONDS = 900L;

    /** The instant every clock in this suite is fixed at, so nothing depends on when it runs. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-03T10:15:30Z");

    /** {@code ACCTSIDI PIC X(11)} at {@code app/cpy-bms/COCRDLI.CPY:66}. */
    private static final String ACCOUNT_ID = "00000000011";

    /** {@code CARDSIDI PIC X(16)} at {@code app/cpy-bms/COCRDLI.CPY:72}. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** A second card number, so a two-row page proves masking on more than one row. */
    private static final String OTHER_CARD_NUMBER = "5500005555555559";

    /**
     * The purpose label a page cursor is sealed under, which includes the page it belongs to.
     *
     * <p>A cursor is scoped to its page deliberately. The source held the saved key and the page number in
     * one commarea record - {@code WS-CA-FIRST-CARDKEY} at {@code app/cbl/COCRDLIC.cbl:L232-L234} and
     * {@code WS-CA-SCREEN-NUM} at {@code :L237} both sit in {@code WS-THIS-PROGCOMMAREA} at {@code :L228} -
     * so one could not arrive without the other. On the wire they are separate parameters and can be
     * mismatched, which would return a page whose number and contents disagree. Binding the page into the
     * purpose label makes the mismatch unrepresentable rather than merely discouraged.
     *
     * @param page the page the cursor positions
     * @return the purpose label for that page
     */
    private static String cursorKind(final int page) {
        return "card-list-cursor-page-" + page;
    }

    /** The card-list service, mocked because this tier reaches no database. */
    @Mock
    private CardListService cardListService;

    /** The card-detail service, mocked on the same terms. */
    @Mock
    private CardDetailService cardDetailService;

    /** The card-update service, mocked on the same terms. */
    @Mock
    private CardUpdateService cardUpdateService;

    /** The real sealer. See the class documentation for why it is not a mock. */
    private SnapshotTokenService snapshotTokenService;

    /** The controller under test, rebuilt before every test. */
    private CardController controller;

    /**
     * Builds the sealer and the controller, so no state survives a test.
     */
    @BeforeEach
    void createControllerUnderTest() {
        snapshotTokenService = new SnapshotTokenService(TEST_SIGNING_KEY, TOKEN_LIFETIME_SECONDS,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), new ObjectMapper());
        controller = new CardController(cardListService, cardDetailService, cardUpdateService,
                snapshotTokenService);
    }

    /**
     * Builds one card-list row.
     *
     * @param rowNumber the one-based screen position
     * @param cardNumber the row's card number
     * @return a populated row
     */
    private static CardDto.CardListRow row(final int rowNumber, final String cardNumber) {
        return new CardDto.CardListRow(rowNumber, " ",
                rowNumber == 1 ? null : " ", ACCOUNT_ID, cardNumber, "Y");
    }

    /**
     * Builds the service result the controller projects, with both saved keys populated as the browse
     * populates them - that is, with card numbers.
     *
     * @param informationMessage the turn's information message
     * @param errorMessage the turn's error message
     * @param rowSelectionProtected whether the selection fields were protected
     * @return a populated result
     */
    private static CardListService.CardListResult listResult(final String informationMessage,
                                                            final String errorMessage,
                                                            final boolean rowSelectionProtected) {
        final PageResponse<CardDto.CardListRow> page = new PageResponse<>(
                List.of(row(1, CARD_NUMBER), row(2, OTHER_CARD_NUMBER)), 1, 7, true,
                CARD_NUMBER, OTHER_CARD_NUMBER);
        return new CardListService.CardListResult(null, page, null, null, null, ACCOUNT_ID, CARD_NUMBER,
                false, rowSelectionProtected, false, "       ", errorMessage, informationMessage, null,
                null, "YYNNNNN", "NNNNNNN", "NNNN", true);
    }

    /**
     * Builds the detail projection the service returns.
     *
     * @return a populated card-detail payload
     */
    private static CardDto detailProjection() {
        return CardDto.detail("CCDL", "CardDemo", "08/03/26", "COCRDSLC", "View Card", "10:15:30",
                ACCOUNT_ID, CARD_NUMBER, "JOHN SMITH", "Y", "12", "2099", null, null, null);
    }

    /**
     * Builds an update request body, with the snapshot group either present or absent.
     *
     * @param oldDetails the snapshot group, or null to omit it as the contract requires
     * @return a populated request
     */
    private static CardUpdateRequest updateRequest(final CardUpdateRequest.CardDetails oldDetails) {
        return new CardUpdateRequest(null, null, null, null, null, null,
                ACCOUNT_ID, CARD_NUMBER, "JOHN SMITH", "Y", "12", "2099", "28",
                null, null, null, null, oldDetails, null);
    }

    /**
     * Nothing that identifies a card may leave in a readable form - not in a row, not in a cursor.
     */
    @Nested
    @DisplayName("no card number leaves in the clear - rows or cursors")
    class MaskingContract {

        /**
         * Every row's card number is tail-masked, and the full number appears nowhere in the response.
         */
        @Test
        @DisplayName("every list row carries a masked card number and no full one")
        void everyListRowCarriesAMaskedCardNumber() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));

            final CardListResponse body = controller.listCards(null, null, null, null, null, null, null,
                    null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.rows()).extracting(CardListResponse.CardListRowResponse::maskedCardNumber)
                    .containsExactly("************1111", "************5559");
            assertThat(body.toString()).doesNotContain(CARD_NUMBER).doesNotContain(OTHER_CARD_NUMBER);
        }

        /**
         * Neither cursor is the saved key itself. The sealed value must differ from the card number and must
         * open back to it, which together prove the browse still works from a value a caller cannot read.
         */
        @Test
        @DisplayName("both page cursors are sealed and open back to the saved keys")
        void bothPageCursorsAreSealedAndReversible() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));

            final CardListResponse body = controller.listCards(null, null, null, null, null, null, null,
                    null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.firstCursor()).isNotNull().isNotEqualTo(CARD_NUMBER);
            assertThat(body.lastCursor()).isNotNull().isNotEqualTo(OTHER_CARD_NUMBER);
            assertThat(snapshotTokenService.openCursor(cursorKind(1), body.firstCursor()))
                    .isEqualTo(CARD_NUMBER);
            assertThat(snapshotTokenService.openCursor(cursorKind(1), body.lastCursor()))
                    .isEqualTo(OTHER_CARD_NUMBER);
            // And under no other page's label, which is what makes the scoping load-bearing rather than
            // decorative.
            assertThatThrownBy(() -> snapshotTokenService.openCursor(cursorKind(2), body.firstCursor()))
                    .isInstanceOf(ConcurrentUpdateException.class);
        }

        /**
         * A sealed cursor sent back is opened before the service sees it, so the browse receives the same
         * saved key the source's commarea held. This is the round trip that keeps paging behaviour identical.
         */
        @Test
        @DisplayName("a returned cursor is opened back to the saved key before the service sees it")
        void aReturnedCursorIsOpenedBeforeTheServiceSeesIt() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));
            final String sealedFirst = snapshotTokenService.sealCursor(cursorKind(1), CARD_NUMBER);
            final String sealedLast = snapshotTokenService.sealCursor(cursorKind(1), OTHER_CARD_NUMBER);

            controller.listCards(null, null, "PAGE_FORWARD", "1", sealedFirst, sealedLast, "true", null);

            final ArgumentCaptor<CardListService.CardListRequest> captured =
                    ArgumentCaptor.forClass(CardListService.CardListRequest.class);
            verify(cardListService).listCards(captured.capture());
            assertThat(captured.getValue().firstCardNumber).isEqualTo(CARD_NUMBER);
            assertThat(captured.getValue().lastCardNumber).isEqualTo(OTHER_CARD_NUMBER);
        }

        /**
         * A cursor this server did not seal is refused rather than used, which is what stops a caller from
         * positioning the browse on an arbitrary card number of its own choosing.
         *
         * <p>It is refused as a <em>rejected request</em> and not as a concurrency conflict. A snapshot that
         * fails to verify says the record moved underneath the caller; a paging cursor that fails to verify
         * says the caller sent something this server never issued, which is a different statement and
         * belongs at {@code 400}. The field is named so the caller knows which of the two cursors is at
         * fault.
         */
        @Test
        @DisplayName("a cursor this server did not seal is refused and the browse never runs")
        void anUnsealedCursorIsRefused() {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> controller.listCards(null, null, null, null, CARD_NUMBER, null, null, null));

            assertThat(failure.getFieldName()).isEqualTo("firstKey");
            verifyNoInteractions(cardListService);
        }

        /**
         * The detail response masks its card number too, and the update response masks the refreshed one.
         */
        @Test
        @DisplayName("the detail and update responses both mask the card number")
        void theDetailAndUpdateResponsesBothMask() {
            when(cardDetailService.viewCardDetail(ACCOUNT_ID, CARD_NUMBER))
                    .thenReturn(detailProjection());
            when(cardUpdateService.issueUpdateSnapshot(ACCOUNT_ID, CARD_NUMBER)).thenReturn("sealed");
            when(cardUpdateService.updateCard(any(), any())).thenReturn(detailProjection());

            final CardResponse read = controller.getCardDetail(ACCOUNT_ID, CARD_NUMBER, null).getBody();
            final CardResponse written =
                    controller.updateCard(updateRequest(null), "\"sealed\"", null).getBody();

            assertThat(read).isNotNull();
            assertThat(written).isNotNull();
            assertThat(read.maskedCardNumber()).isEqualTo("************1111");
            assertThat(written.maskedCardNumber()).isEqualTo("************1111");
            assertThat(read.toString()).doesNotContain(CARD_NUMBER);
            assertThat(written.toString()).doesNotContain(CARD_NUMBER);
        }
    }

    /**
     * The turn's own screen text reaches a client, because HTTP cannot express it and the source reported it
     * on every turn.
     */
    @Nested
    @DisplayName("the list envelope carries the turn's context, not only its rows")
    class ContextContract {

        /**
         * Both messages and the selection flag survive the projection, and the page metadata with them.
         */
        @Test
        @DisplayName("both screen messages, the selection flag and the page metadata are all reported")
        void theEnvelopeCarriesMessagesAndMetadata() {
            when(cardListService.listCards(any()))
                    .thenReturn(listResult("No more pages to display", "Account Filter is not valid",
                            true));

            final CardListResponse body = controller.listCards(null, null, null, null, null, null, null,
                    null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.informationMessage()).isEqualTo("No more pages to display");
            assertThat(body.errorMessage()).isEqualTo("Account Filter is not valid");
            assertThat(body.rowSelectionAvailable()).isFalse();
            assertThat(body.pageNumber()).isEqualTo(1);
            assertThat(body.pageSize()).isEqualTo(7);
            assertThat(body.nextPageAvailable()).isTrue();
            assertThat(body.rows()).hasSize(2);
        }

        /**
         * When selection was not protected the flag reports it as available, so the two states stay distinct
         * rather than one being the absence of the other.
         */
        @Test
        @DisplayName("an unprotected selection is reported as available")
        void anUnprotectedSelectionIsReportedAsAvailable() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));

            final CardListResponse body = controller.listCards(null, null, null, null, null, null, null,
                    null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.rowSelectionAvailable()).isTrue();
        }
    }

    /**
     * The as-displayed snapshot is issued by this server, travels as an entity tag, and is never accepted
     * from a caller.
     */
    @Nested
    @DisplayName("the update precondition is server-issued and header-borne")
    class SnapshotContract {

        /**
         * The read publishes the sealed snapshot twice - in the body and as the entity tag - so a client may
         * use either the JSON member or the standard conditional-request idiom.
         */
        @Test
        @DisplayName("the detail read publishes the sealed snapshot in the body and as the ETag")
        void theDetailReadPublishesTheSnapshotTwice() {
            when(cardDetailService.viewCardDetail(ACCOUNT_ID, CARD_NUMBER))
                    .thenReturn(detailProjection());
            when(cardUpdateService.issueUpdateSnapshot(ACCOUNT_ID, CARD_NUMBER))
                    .thenReturn("sealed-snapshot-value");

            final ResponseEntity<CardResponse> response =
                    controller.getCardDetail(ACCOUNT_ID, CARD_NUMBER, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().snapshotToken()).isEqualTo("sealed-snapshot-value");
            assertThat(response.getHeaders().getETag()).isEqualTo("\"sealed-snapshot-value\"");
        }

        /**
         * The write response issues no snapshot: a further edit reads again, which removes any question of a
         * token outliving the state it describes.
         */
        @Test
        @DisplayName("the update response issues no snapshot of its own")
        void theUpdateResponseIssuesNoSnapshot() {
            when(cardUpdateService.updateCard(any(), any())).thenReturn(detailProjection());

            final CardResponse body = controller.updateCard(updateRequest(null), "\"token\"", null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.snapshotToken()).isNull();
        }

        /**
         * A body that carries a snapshot group is refused rather than ignored. Ignoring it would leave a
         * caller believing it controlled the write precondition when it did not.
         */
        @Test
        @DisplayName("a body-carried snapshot is refused and the service is never entered")
        void aBodyCarriedSnapshotIsRefused() {
            // Three components, not four: the group declares no card verification value, because none is
            // persisted anywhere in this system.
            final CardUpdateRequest.CardDetails supplied = new CardUpdateRequest.CardDetails(ACCOUNT_ID,
                    CARD_NUMBER, new CardUpdateRequest.CardData("JOHN SMITH",
                    new CardUpdateRequest.ExpiraionDate("2099", "12", "28"), "Y"));

            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> controller.updateCard(updateRequest(supplied), "\"token\"", null));

            assertThat(failure.getFieldName()).isEqualTo("oldDetails");
            assertThat(failure.getMessage()).contains("If-Match");
            verifyNoInteractions(cardUpdateService);
        }

        /**
         * The entity-tag quoting is stripped before the token reaches the service, and a weak-validator
         * prefix with it, so a client may return the header value verbatim or the bare token.
         */
        @Test
        @DisplayName("quoted, weak-quoted and bare If-Match values all relay the same token")
        void everyIfMatchSpellingRelaysTheSameToken() {
            when(cardUpdateService.updateCard(any(), eq("abc"))).thenReturn(detailProjection());

            controller.updateCard(updateRequest(null), "\"abc\"", null);
            controller.updateCard(updateRequest(null), "W/\"abc\"", null);
            controller.updateCard(updateRequest(null), "abc", null);

            verify(cardUpdateService, org.mockito.Mockito.times(3)).updateCard(any(), eq("abc"));
        }

        /**
         * An absent header relays null, which the service reports as an unmet precondition. The controller
         * does not invent a token and does not refuse the request itself: the service owns that outcome and
         * reports it as {@code CHANGES_NOT_CONFIRMED}.
         */
        @Test
        @DisplayName("an absent If-Match relays null rather than a fabricated value")
        void anAbsentIfMatchRelaysNull() {
            when(cardUpdateService.updateCard(any(), eq(null))).thenReturn(detailProjection());

            controller.updateCard(updateRequest(null), null, null);

            verify(cardUpdateService).updateCard(any(), eq(null));
        }
    }

    /**
     * Control tokens decide which browse runs. They are matched exactly, because accepting a spelling the
     * operation never declared is accepting an instruction it never declared.
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
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));

            controller.listCards(null, null, null, null, null, null, null, null);

            final ArgumentCaptor<CardListService.CardListRequest> captured =
                    ArgumentCaptor.forClass(CardListService.CardListRequest.class);
            verify(cardListService).listCards(captured.capture());
            assertThat(captured.getValue().attentionIdentifier).isEqualTo("ENTER");
            assertThat(captured.getValue().pageNumber).isZero();
            assertThat(captured.getValue().nextPageAvailable).isFalse();
        }

        /**
         * The three declared spellings resolve, and their attention identifiers reach the service unchanged.
         */
        @Test
        @DisplayName("the three declared action tokens resolve to their attention identifiers")
        void theThreeDeclaredActionTokensResolve() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));

            controller.listCards(null, null, "SUBMIT", null, null, null, null, null);
            // A backward step carries the page it is moving back from. Page one is the top-of-file arm, so
            // it needs no saved key - see BackwardPagingPrecondition for the whole matrix.
            controller.listCards(null, null, "PAGE_BACKWARD", "1", null, null, null, null);
            controller.listCards(null, null, "PAGE_FORWARD", null, null, null, null, null);

            final ArgumentCaptor<CardListService.CardListRequest> captured =
                    ArgumentCaptor.forClass(CardListService.CardListRequest.class);
            verify(cardListService, org.mockito.Mockito.times(3)).listCards(captured.capture());
            assertThat(captured.getAllValues())
                    .extracting(request -> request.attentionIdentifier)
                    .containsExactly("ENTER", "PF07", "PF08");
        }

        /**
         * A padded action token is refused. The framework's default enum binding would have trimmed it.
         */
        @Test
        @DisplayName("a padded action token is refused rather than trimmed")
        void aPaddedActionTokenIsRefused() {
            assertThatThrownBy(() -> controller.listCards(null, null, " SUBMIT ", null, null, null, null,
                    null))
                    .isInstanceOf(ValidationException.class);
            verifyNoInteractions(cardListService);
        }

        /**
         * A lower-cased action token is refused too, for the same reason.
         */
        @Test
        @DisplayName("a lower-cased action token is refused rather than folded")
        void aLowerCasedActionTokenIsRefused() {
            assertThatThrownBy(() -> controller.listCards(null, null, "submit", null, null, null, null,
                    null))
                    .isInstanceOf(ValidationException.class);
            verifyNoInteractions(cardListService);
        }

        /**
         * An empty action token is a blank failure rather than an invalid one, which keeps the two-state
         * discriminator of {@code app/cpy/CSSETATY.cpy:L18-L19} observable on the wire.
         */
        @Test
        @DisplayName("an empty action token reports BLANK, not INVALID")
        void anEmptyActionTokenReportsBlank() {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> controller.listCards(null, null, "", null, null, null, null, null));

            assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
        }

        /**
         * A signed page token is refused. The framework's default {@code int} binding would have accepted it.
         */
        @Test
        @DisplayName("a signed page token is refused")
        void aSignedPageTokenIsRefused() {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> controller.listCards(null, null, null, "+1", null, null, null, null));

            assertThat(failure.getFieldName()).isEqualTo("page");
            assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            verifyNoInteractions(cardListService);
        }

        /**
         * A padded page token is refused on the same grounds.
         */
        @Test
        @DisplayName("a padded page token is refused")
        void aPaddedPageTokenIsRefused() {
            assertThatThrownBy(() -> controller.listCards(null, null, null, " 1", null, null, null, null))
                    .isInstanceOf(ValidationException.class);
        }

        /**
         * A page token outside the single digit the field declares is refused by the domain check.
         */
        @Test
        @DisplayName("a page token outside the single-digit domain is refused")
        void aPageTokenOutsideTheDomainIsRefused() {
            assertThatThrownBy(() -> controller.listCards(null, null, null, "10", null, null, null, null))
                    .isInstanceOf(ValidationException.class);
        }

        /**
         * The six boolean aliases the framework accepts are all refused, one assertion each so that a
         * regression names the alias that returned.
         */
        @Test
        @DisplayName("yes, on and 1 are refused as next-page tokens, and so are their negatives")
        void theBooleanAliasesAreAllRefused() {
            assertThatThrownBy(() -> controller.listCards(null, null, null, null, null, null, "yes", null))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> controller.listCards(null, null, null, null, null, null, "on", null))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> controller.listCards(null, null, null, null, null, null, "1", null))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> controller.listCards(null, null, null, null, null, null, "no", null))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> controller.listCards(null, null, null, null, null, null, "off", null))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> controller.listCards(null, null, null, null, null, null, "0", null))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> controller.listCards(null, null, null, null, null, null, "TRUE",
                    null))
                    .isInstanceOf(ValidationException.class);
            verifyNoInteractions(cardListService);
        }

        /**
         * The two declared spellings resolve, so refusing the aliases has not refused the values themselves.
         */
        @Test
        @DisplayName("true and false resolve as next-page tokens")
        void theTwoDeclaredBooleanTokensResolve() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));

            controller.listCards(null, null, null, null, null, null, "true", null);
            controller.listCards(null, null, null, null, null, null, "false", null);

            final ArgumentCaptor<CardListService.CardListRequest> captured =
                    ArgumentCaptor.forClass(CardListService.CardListRequest.class);
            verify(cardListService, org.mockito.Mockito.times(2)).listCards(captured.capture());
            assertThat(captured.getAllValues())
                    .extracting(request -> request.nextPageAvailable)
                    .containsExactly(true, false);
        }
    }

    /**
     * One file status must produce one status code across the API, and one concurrency outcome one status.
     */
    @Nested
    @DisplayName("failure translation agrees with the other four resources")
    class StatusContract {

        /**
         * An input-output failure answers {@code 502}, the status the account, transaction, billing and
         * report resources already answer for the identical exception.
         */
        @Test
        @DisplayName("an input-output failure answers 502, carrying the expanded status")
        void anInputOutputFailureAnswers502() {
            final FileAccessException failure =
                    new FileAccessException("read failed", "37", "CARDDAT", "READ");

            final ResponseEntity<ProblemDetail> response =
                    controller.handleFileAccessFailure(failure);

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
                    .doesNotContainKeys("ioStatus", "expandedStatus", "file", "operation");
            assertThat(response.getBody().getDetail())
                    .doesNotContain("CARDDAT", failure.getExpandedStatus());
        }

        /**
         * An absent precondition answers {@code 428} and a failed one {@code 412}; a lock refusal and a
         * failed write answer {@code 409}. One assertion per outcome, none consolidated, because each is a
         * separate contract a client acts on differently.
         */
        @Test
        @DisplayName("each concurrency outcome answers its own status")
        void eachConcurrencyOutcomeAnswersItsOwnStatus() {
            assertThat(statusOf(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED))
                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
            assertThat(statusOf(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE))
                    .isEqualTo(HttpStatus.PRECONDITION_FAILED);
            assertThat(statusOf(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT))
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(statusOf(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER))
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(statusOf(ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED))
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        /**
         * The outcome reaches the response so that the five stay separable, and the affected-record
         * reference does not, because on this resource it is a card reference.
         */
        @Test
        @DisplayName("the outcome is reported and no record reference is")
        void theOutcomeIsReportedAndNoRecordReferenceIs() {
            final ResponseEntity<ProblemDetail> response = controller.handleConcurrentUpdate(
                    new ConcurrentUpdateException(
                            ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE,
                            "Record changed by some one else. Please review", "************1111", null));

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getProperties())
                    .containsEntry("outcome",
                            ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE);
            assertThat(response.getBody().getProperties()).doesNotContainKey("recordKey");
        }

        /**
         * Resolves the status this resource answers for one outcome.
         *
         * @param outcome the outcome to translate
         * @return the status the handler selected
         */
        private HttpStatus statusOf(final ConcurrentUpdateException.Outcome outcome) {
            return HttpStatus.valueOf(controller.handleConcurrentUpdate(
                    new ConcurrentUpdateException(outcome, "message")).getStatusCode().value());
        }
    }

    /**
     * A null collaborator is a wiring defect and must surface at context refresh rather than on a request.
     */
    @Nested
    @DisplayName("construction refuses a null collaborator")
    class ConstructionContract {

        /**
         * Each of the four collaborators is refused when null, and the message names the one that was.
         */
        @Test
        @DisplayName("all four collaborators are required")
        void allFourCollaboratorsAreRequired() {
            assertThatThrownBy(() -> new CardController(null, cardDetailService, cardUpdateService,
                    snapshotTokenService))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cardListService");
            assertThatThrownBy(() -> new CardController(cardListService, null, cardUpdateService,
                    snapshotTokenService))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cardDetailService");
            assertThatThrownBy(() -> new CardController(cardListService, cardDetailService, null,
                    snapshotTokenService))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cardUpdateService");
            assertThatThrownBy(() -> new CardController(cardListService, cardDetailService,
                    cardUpdateService, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("snapshotTokenService");
        }
    }

    /**
     * Generates a single-use signing key for this suite.
     *
     * <p>Rule 1 Clause D forbids secrets in code, in configuration and <em>in tests</em>, with no carve-out
     * for material that happens to be synthetic: a literal key in a committed file is still committed key
     * material, indexable and copyable into a deployment, and it teaches the pattern the clause exists to
     * stop. Generating it removes the class of problem instead of declaring one instance of it harmless. The
     * value exists only in memory for the lifetime of this class, so there is nothing to leak or rotate, and
     * no assertion anywhere depends on its content - only on its being long enough and internally consistent.
     *
     * <p>Thirty-two bytes of entropy is the HS256 minimum the sealer enforces; URL-safe unpadded encoding
     * widens that to forty-three characters, so the length guard passes with room to spare.
     *
     * @return a freshly generated key, never {@code null}, never logged and never persisted
     */
    private static String ephemeralSigningKey() {
        final byte[] keyMaterial = new byte[32];
        new SecureRandom().nextBytes(keyMaterial);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(keyMaterial);
    }


    /**
     * A masked row has to remain a usable row. These tests pin the reference that makes it one.
     *
     * <p><strong>Finding, severity Medium - remediated.</strong> A list row published {@code rowNumber},
     * {@code accountNumber}, {@code maskedCardNumber} and {@code statusCode}, and
     * {@code GET /api/cards/detail} accepted none of them: it requires an eleven-digit account
     * <em>and</em> a full sixteen-digit card number. An API-only client could therefore enumerate every
     * page and still not reach a single card, and {@code PUT /api/cards} carrying no identifier answered a
     * body-unreadable code that named neither the field nor the reason.
     *
     * <p>The 3270 row published the full number and an operator selected it in place
     * [{@code app/cbl/COCRDLIC.cbl:L115-L116}]. Masking that column is a deliberate divergence, so the
     * column's <em>function</em> has to be replaced rather than dropped: each row carries an opaque
     * reference, sealed by the same construction that seals the snapshot, which both the detail and the
     * update operation accept in place of the two filters.
     *
     * <p>What these tests are careful to prove is not merely that the reference works, but that it cannot
     * be abused: it is opaque, it is scoped to its own purpose so a paging cursor cannot stand in for it,
     * it refuses to coexist with a filter rather than silently preferring one, and it is not a
     * substitute for the write precondition.
     */
    @Nested
    @DisplayName("a masked row stays traversable - the opaque row reference")
    class RowReferenceContract {

        /**
         * Every row carries a reference, and none of them is, or contains, the card number it refers to.
         */
        @Test
        @DisplayName("every row carries an opaque reference that is not the card number")
        void everyRowCarriesAnOpaqueReference() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));

            final CardListResponse body = controller.listCards(null, null, null, null, null, null, null,
                    null).getBody();

            assertThat(body).isNotNull();
            assertThat(body.rows()).allSatisfy(r -> assertThat(r.cardKey())
                    .isNotNull()
                    .isNotBlank()
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(OTHER_CARD_NUMBER)
                    .doesNotContain(ACCOUNT_ID));
            assertThat(body.rows()).extracting(CardListResponse.CardListRowResponse::cardKey)
                    .doesNotHaveDuplicates();
        }

        /**
         * The reference resolves to the row's own account and card number before the service is entered, so
         * the two edits the source runs first see exactly the values a filter pair would have supplied.
         */
        @Test
        @DisplayName("a row's reference resolves to that row's account and card number")
        void aRowReferenceResolvesToThatRowsIdentifiers() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));
            when(cardDetailService.viewCardDetail(ACCOUNT_ID, CARD_NUMBER))
                    .thenReturn(detailProjection());
            when(cardUpdateService.issueUpdateSnapshot(ACCOUNT_ID, CARD_NUMBER)).thenReturn("sealed");
            final String reference = controller.listCards(null, null, null, null, null, null, null, null)
                    .getBody().rows().getFirst().cardKey();

            controller.getCardDetail(null, null, reference);

            verify(cardDetailService).viewCardDetail(ACCOUNT_ID, CARD_NUMBER);
        }

        /**
         * The detail read issues a reference of its own, so a client that arrived by filter can continue by
         * reference without having to keep the number it was given.
         */
        @Test
        @DisplayName("the detail read issues a reference of its own")
        void theDetailReadIssuesItsOwnReference() {
            when(cardDetailService.viewCardDetail(ACCOUNT_ID, CARD_NUMBER))
                    .thenReturn(detailProjection());
            when(cardUpdateService.issueUpdateSnapshot(ACCOUNT_ID, CARD_NUMBER)).thenReturn("sealed");

            final CardResponse read = controller.getCardDetail(ACCOUNT_ID, CARD_NUMBER, null).getBody();

            assertThat(read).isNotNull();
            assertThat(read.cardKey()).isNotNull().isNotBlank().doesNotContain(CARD_NUMBER);
            assertThat(read.toString()).doesNotContain(CARD_NUMBER);
        }

        /**
         * A write issues neither a reference nor a snapshot: it consumes the precondition, and a caller that
         * intends a further edit re-reads, which issues both afresh. Publishing a stale pair would invite a
         * second write against a snapshot the first one invalidated.
         */
        @Test
        @DisplayName("the update response issues no reference and no snapshot")
        void theUpdateResponseIssuesNeither() {
            when(cardUpdateService.updateCard(any(), any())).thenReturn(detailProjection());

            final CardResponse written =
                    controller.updateCard(updateRequest(null), "\"sealed\"", null).getBody();

            assertThat(written).isNotNull();
            assertThat(written.cardKey()).isNull();
            assertThat(written.snapshotToken()).isNull();
        }

        /**
         * On the update, the reference supplies the two identifiers and nothing else. Every other component
         * of the submitted body has to survive untouched, or the caller's edit would be silently altered by
         * the act of routing it.
         */
        @Test
        @DisplayName("on an update the reference replaces only the two identifiers")
        void onUpdateTheReferenceReplacesOnlyTheIdentifiers() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));
            when(cardUpdateService.updateCard(any(), any())).thenReturn(detailProjection());
            final String reference = controller.listCards(null, null, null, null, null, null, null, null)
                    .getBody().rows().getFirst().cardKey();
            final CardUpdateRequest submitted = new CardUpdateRequest(null, null, null, null, null, null,
                    null, null, "MARY JONES", "N", "06", "2031", "15",
                    null, null, null, null, null, null);

            controller.updateCard(submitted, "\"sealed\"", reference);

            final ArgumentCaptor<CardUpdateRequest> captured =
                    ArgumentCaptor.forClass(CardUpdateRequest.class);
            verify(cardUpdateService).updateCard(captured.capture(), eq("sealed"));
            final CardUpdateRequest relayed = captured.getValue();
            assertThat(relayed.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(relayed.cardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(relayed.cardholderName()).isEqualTo("MARY JONES");
            assertThat(relayed.cardStatusCode()).isEqualTo("N");
            assertThat(relayed.expiryMonth()).isEqualTo("06");
            assertThat(relayed.expiryYear()).isEqualTo("2031");
            assertThat(relayed.expiryDay()).isEqualTo("15");
        }

        /**
         * A reference alongside either filter is two different statements of which card is meant. It is
         * refused rather than resolved by precedence, because a precedence rule silently discards half of
         * what the caller asked for.
         */
        @Test
        @DisplayName("a reference alongside either filter is refused, naming the reference")
        void aReferenceAlongsideAFilterIsRefused() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));
            final String reference = controller.listCards(null, null, null, null, null, null, null, null)
                    .getBody().rows().getFirst().cardKey();

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> controller.getCardDetail(ACCOUNT_ID, null, reference)).getFieldName())
                    .isEqualTo("cardKey");
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> controller.getCardDetail(null, CARD_NUMBER, reference)).getFieldName())
                    .isEqualTo("cardKey");
            verifyNoInteractions(cardDetailService);
        }

        /**
         * A reference this server did not seal is refused, which is what stops a caller from naming a card
         * it was never shown.
         */
        @Test
        @DisplayName("a forged reference is refused and no read runs")
        void aForgedReferenceIsRefused() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> controller.getCardDetail(null, null, "not-a-reference")).getFieldName())
                    .isEqualTo("cardKey");
            verifyNoInteractions(cardDetailService);
        }

        /**
         * A page cursor is authentic - this server sealed it - and is still refused as a card reference,
         * because it was sealed for a different purpose. Without that scoping, one authentic token would be
         * accepted anywhere another was expected.
         */
        @Test
        @DisplayName("an authentic page cursor is refused as a card reference")
        void aPageCursorIsRefusedAsACardReference() {
            final String cursor = snapshotTokenService.sealCursor(cursorKind(1), CARD_NUMBER);

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> controller.getCardDetail(null, null, cursor)).getFieldName())
                    .isEqualTo("cardKey");
            verifyNoInteractions(cardDetailService);
        }

        /**
         * The reference is not a precondition. Supplying one on an update does not excuse the missing
         * {@code If-Match}: the reference says which card, the snapshot says what the caller was shown, and
         * the comparison the source performs needs both.
         */
        @Test
        @DisplayName("a reference does not substitute for the write precondition")
        void aReferenceIsNotAPrecondition() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));
            when(cardUpdateService.updateCard(any(), any())).thenReturn(detailProjection());
            final String reference = controller.listCards(null, null, null, null, null, null, null, null)
                    .getBody().rows().getFirst().cardKey();
            final CardUpdateRequest submitted = new CardUpdateRequest(null, null, null, null, null, null,
                    null, null, "MARY JONES", "N", "06", "2031", "15",
                    null, null, null, null, null, null);

            controller.updateCard(submitted, null, reference);

            final ArgumentCaptor<CardUpdateRequest> captured =
                    ArgumentCaptor.forClass(CardUpdateRequest.class);
            verify(cardUpdateService).updateCard(captured.capture(), eq(null));
            assertThat(captured.getValue().cardNumber()).isEqualTo(CARD_NUMBER);
        }
    }

    /**
     * The positioning state a backward step needs, and what happens when it is not sent.
     *
     * <p><strong>Finding, severity Medium - remediated.</strong>
     * {@code GET /api/cards?action=PAGE_BACKWARD} with no saved key answered {@code 502} with an
     * input-output failure code, reporting a store problem for what was a malformed request.
     *
     * <p>The cause is in the source and is structural rather than accidental. {@code 9100-READ-BACKWARDS}
     * [{@code app/cbl/COCRDLIC.cbl:L1264}] has <strong>no end-of-data arm</strong>: its
     * {@code EVALUATE WS-RESP-CD} [{@code :L1304-L1318}] carries {@code DFHRESP(NORMAL)},
     * {@code DFHRESP(DUPREC)} and {@code WHEN OTHER} only, so a backward read from an unpositioned browse
     * is a file error <em>by construction</em>. That state is unreachable in the source because the saved
     * key and the page number are fields of one commarea record [{@code :L228-L237}], so one could not
     * arrive without the other. Putting them on the wire separated them, which turned a commarea invariant
     * into a request precondition - and a precondition has to be checked.
     *
     * <p>The remedy is at the boundary, not in the browse: {@code 9100-READ-BACKWARDS} is left transcribed
     * exactly and no end-of-data arm is invented for it.
     */
    @Nested
    @DisplayName("backward paging states its precondition instead of failing as input-output")
    class BackwardPagingPrecondition {

        /**
         * No page at all names the page. It is the one value a backward step cannot infer: there is no
         * page to move back from.
         */
        @Test
        @DisplayName("a backward step with no page names the page, and the browse never runs")
        void aBackwardStepWithNoPageNamesThePage() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> controller.listCards(null, null, "PAGE_BACKWARD", null, null, null, null, null))
                    .getFieldName()).isEqualTo("page");
            verifyNoInteractions(cardListService);
        }

        /**
         * Beyond the first page the saved key is required, and it is named rather than defaulted. Defaulting
         * it is precisely what produced the input-output failure.
         */
        @Test
        @DisplayName("a backward step beyond the first page names the missing saved key")
        void aBackwardStepBeyondTheFirstPageNamesTheKey() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> controller.listCards(null, null, "PAGE_BACKWARD", "2", null, null, null, null))
                    .getFieldName()).isEqualTo("firstKey");
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> controller.listCards(null, null, "PAGE_BACKWARD", "2", "   ", null, "true",
                            null)).getFieldName()).isEqualTo("firstKey");
            verifyNoInteractions(cardListService);
        }

        /**
         * Backward from the first page is not refused. The source re-reads forward from a space-filled key
         * and reports its top-of-page literal, which is an ordinary successful turn, so no precondition is
         * imposed on the one page that genuinely has no predecessor.
         */
        @Test
        @DisplayName("backward from the first page is admitted, with no saved key")
        void backwardFromTheFirstPageIsAdmitted() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));

            controller.listCards(null, null, "PAGE_BACKWARD", "1", null, null, null, null);

            final ArgumentCaptor<CardListService.CardListRequest> captured =
                    ArgumentCaptor.forClass(CardListService.CardListRequest.class);
            verify(cardListService).listCards(captured.capture());
            assertThat(captured.getValue().attentionIdentifier).isEqualTo("PF07");
            assertThat(captured.getValue().firstCardNumber).isNull();
        }

        /**
         * A cursor minted for one page is refused when presented as another's. Honouring it would return a
         * page whose reported number and actual contents disagree, which is worse than refusing it.
         */
        @Test
        @DisplayName("a cursor minted for another page is refused, naming the key")
        void aCursorMintedForAnotherPageIsRefused() {
            final String pageOneKey = snapshotTokenService.sealCursor(cursorKind(1), CARD_NUMBER);

            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> controller.listCards(null, null, "PAGE_BACKWARD", "2", pageOneKey, null, null,
                            null)).getFieldName()).isEqualTo("firstKey");
            verifyNoInteractions(cardListService);
        }

        /**
         * And the matching pair round-trips, so the scoping refuses only mismatches.
         */
        @Test
        @DisplayName("a cursor presented with its own page opens back to the saved key")
        void aCursorPresentedWithItsOwnPageOpens() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));
            final String pageTwoKey = snapshotTokenService.sealCursor(cursorKind(2), CARD_NUMBER);

            controller.listCards(null, null, "PAGE_BACKWARD", "2", pageTwoKey, null, null, null);

            final ArgumentCaptor<CardListService.CardListRequest> captured =
                    ArgumentCaptor.forClass(CardListService.CardListRequest.class);
            verify(cardListService).listCards(captured.capture());
            assertThat(captured.getValue().firstCardNumber).isEqualTo(CARD_NUMBER);
        }

        /**
         * A forward step imposes none of this. It positions from the last key and, absent one, re-reads from
         * the top - an ordinary outcome, not an error - so the precondition is placed only where the source
         * lacks an end-of-data arm.
         */
        @Test
        @DisplayName("a forward step with no state at all is still admitted")
        void aForwardStepWithNoStateIsAdmitted() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));

            controller.listCards(null, null, "PAGE_FORWARD", null, null, null, null, null);

            verify(cardListService).listCards(any());
        }
    }

    /**
     * A page past the first must arrive with the cursor that addresses it.
     *
     * <p>The page number is a display counter, not an address:
     * {@code action=PAGE_FORWARD&page=8} with no cursor used to answer {@code 200} reporting
     * {@code pageNumber=8} while serving page one's seven rows, and {@code action=SUBMIT&page=8} did the
     * same. A response that reports a page it did not serve cannot be acted on, so the incomplete request is
     * refused and the cursor that completes it is named.
     */
    @Nested
    @DisplayName("a page past the first is refused without the cursor that addresses it")
    class ForwardPagingPrecondition {

        /**
         * Forward past the first page needs the last key: that is the position being advanced from.
         */
        @Test
        @DisplayName("a forward step past the first page names the missing last key")
        void aForwardStepPastTheFirstPageNamesTheLastKey() {
            final ValidationException refused = catchThrowableOfType(ValidationException.class,
                    () -> controller.listCards(null, null, "PAGE_FORWARD", "8", null, null, null, null));

            assertThat(refused.getFieldName()).isEqualTo("lastKey");
            assertThat(refused.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> controller.listCards(null, null, "PAGE_FORWARD", "2", null, "   ", "true", null))
                    .getFieldName()).isEqualTo("lastKey");
            verifyNoInteractions(cardListService);
        }

        /**
         * Any other action redisplays the named page, so it needs the first key instead. This is the arm that
         * {@code action=SUBMIT&page=8} took.
         */
        @Test
        @DisplayName("a redisplay past the first page names the missing first key")
        void aRedisplayPastTheFirstPageNamesTheFirstKey() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> controller.listCards(null, null, "SUBMIT", "8", null, null, null, null))
                    .getFieldName()).isEqualTo("firstKey");
            verifyNoInteractions(cardListService);
        }

        /**
         * The first page, and a request naming no page at all, are admitted untouched: a space-filled key
         * positions the browse at the start of the file, which is what the source intends.
         */
        @Test
        @DisplayName("the first page and an unnamed page are admitted with no cursor")
        void theFirstPageAndAnUnnamedPageAreAdmitted() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));

            controller.listCards(null, null, "PAGE_FORWARD", "1", null, null, null, null);
            controller.listCards(null, null, "PAGE_FORWARD", null, null, null, null, null);
            controller.listCards(null, null, "SUBMIT", "1", null, null, null, null);

            verify(cardListService, org.mockito.Mockito.times(3)).listCards(any());
        }

        /**
         * And a complete forward request still runs, so the precondition refuses only the incomplete one.
         */
        @Test
        @DisplayName("a forward step carrying its last key runs the browse")
        void aForwardStepCarryingItsLastKeyRuns() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));
            final String pageTwoKey = snapshotTokenService.sealCursor(cursorKind(2), CARD_NUMBER);

            controller.listCards(null, null, "PAGE_FORWARD", "2", null, pageTwoKey, "true", null);

            verify(cardListService).listCards(any());
        }
    }

}
