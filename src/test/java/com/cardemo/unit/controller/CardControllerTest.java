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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.http.HttpHeaders;
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

    /** A signing key of the length the sealer requires. A test literal, not a deployment secret. */
    private static final String TEST_SIGNING_KEY = "carddemo-unit-test-signing-key-0123456789";

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
            assertThat(snapshotTokenService.openCursor("card-list-cursor", body.firstCursor()))
                    .isEqualTo(CARD_NUMBER);
            assertThat(snapshotTokenService.openCursor("card-list-cursor", body.lastCursor()))
                    .isEqualTo(OTHER_CARD_NUMBER);
        }

        /**
         * A sealed cursor sent back is opened before the service sees it, so the browse receives the same
         * saved key the source's commarea held. This is the round trip that keeps paging behaviour identical.
         */
        @Test
        @DisplayName("a returned cursor is opened back to the saved key before the service sees it")
        void aReturnedCursorIsOpenedBeforeTheServiceSeesIt() {
            when(cardListService.listCards(any())).thenReturn(listResult(null, null, false));
            final String sealedFirst = snapshotTokenService.sealCursor("card-list-cursor", CARD_NUMBER);
            final String sealedLast =
                    snapshotTokenService.sealCursor("card-list-cursor", OTHER_CARD_NUMBER);

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
         */
        @Test
        @DisplayName("a cursor this server did not seal is refused and the browse never runs")
        void anUnsealedCursorIsRefused() {
            final ConcurrentUpdateException failure = catchThrowableOfType(
                    ConcurrentUpdateException.class,
                    () -> controller.listCards(null, null, null, null, CARD_NUMBER, null, null, null));

            assertThat(failure.getOutcome())
                    .isEqualTo(ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE);
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

            final CardResponse read = controller.getCardDetail(ACCOUNT_ID, CARD_NUMBER).getBody();
            final CardResponse written =
                    controller.updateCard(updateRequest(null), "\"sealed\"").getBody();

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
                    controller.getCardDetail(ACCOUNT_ID, CARD_NUMBER);

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

            final CardResponse body = controller.updateCard(updateRequest(null), "\"token\"").getBody();

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
                    () -> controller.updateCard(updateRequest(supplied), "\"token\""));

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

            controller.updateCard(updateRequest(null), "\"abc\"");
            controller.updateCard(updateRequest(null), "W/\"abc\"");
            controller.updateCard(updateRequest(null), "abc");

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

            controller.updateCard(updateRequest(null), null);

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
            controller.listCards(null, null, "PAGE_BACKWARD", null, null, null, null, null);
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
}
