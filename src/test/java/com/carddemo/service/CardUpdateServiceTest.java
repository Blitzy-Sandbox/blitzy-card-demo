package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.carddemo.dto.CardUpdateRequest;
import com.carddemo.dto.CardUpdateResponse;
import com.carddemo.dto.CardViewResponse;
import com.carddemo.entity.Card;
import com.carddemo.exception.OptimisticLockConflictException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;

/**
 * Pure, fast Mockito unit tests for {@link CardUpdateService}, the Java&nbsp;25 /
 * Spring&nbsp;Boot translation of the legacy CICS program {@code COCRDUPC}
 * (online transaction {@code CCUP}, {@code app/cbl/COCRDUPC.cbl}, frozen
 * reference commit SHA {@code 27d6c6f} — read-only, not copied into this
 * repository).
 *
 * <p>The single collaborating repository is a Mockito {@code @Mock} and the
 * service is assembled with {@code @InjectMocks} through its one constructor, so
 * the suite loads <strong>no</strong> Spring context and touches no database,
 * Testcontainers, Docker, or live AWS (AAP hard conventions). Every branch of
 * the public {@link CardUpdateService#updateCard(String, CardUpdateRequest)}
 * method is exercised, feeding the JaCoCo line-coverage gate (Gate&nbsp;8) and
 * compiling cleanly under {@code -Xlint:all} (Gate&nbsp;2).</p>
 *
 * <h2>Behavioural parity assertions (COCRDUPC @ SHA {@code 27d6c6f})</h2>
 * <ul>
 *   <li><b>{@code 1220-EDIT-CARD}</b> — the search-key edit runs <em>before</em>
 *       the record is read; a non-16-digit card number is rejected with the
 *       verbatim message <em>"Card number if supplied must be a 16 digit
 *       number"</em> (line&nbsp;194) and the repository is never queried.</li>
 *   <li><b>{@code 9000-READ-DATA} / {@code DFHRESP(NOTFND)}</b> — a missing card
 *       yields the verbatim <em>"Did not find this account in cards database"</em>
 *       (line&nbsp;202) as a 404.</li>
 *   <li><b>{@code 9300-CHECK-CHANGE-IN-REC}</b> — the read-then-rewrite
 *       optimistic-concurrency precheck: a stale caller version is rejected as a
 *       409 conflict <em>before any write</em> (the COBOL
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} path), distinct from a
 *       persistence-layer optimistic-lock failure on the rewrite which is
 *       wrapped as the same 409 <em>with</em> a cause.</li>
 *   <li><b>{@code 1240-EDIT-CARDSTATUS}</b> — the active status must be exactly
 *       {@code "Y"} or {@code "N"}; otherwise the verbatim <em>"Card Active
 *       Status must be Y or N"</em> (line&nbsp;196) is reported and no write
 *       occurs.</li>
 *   <li><b>{@code 1260-EDIT-EXPIRY-YEAR}</b> — a year outside
 *       1950&ndash;2099 reports the verbatim <em>"Invalid card expiry year"</em>
 *       (line&nbsp;200).</li>
 *   <li><b>{@code 9200-WRITE-PROCESSING}</b> — the happy path rewrites
 *       <em>only</em> the embossed name, active status and expiration date; the
 *       CVV and the account id are never mutated, and the projection returned to
 *       the caller masks the PAN and carries no CVV.</li>
 * </ul>
 *
 * <p>Cards carry no monetary values, so only {@link String}, {@link Long},
 * {@link Integer} and {@link LocalDate} appear here — no {@code float}/
 * {@code double}, consistent with the migration's decimal-fidelity rules.</p>
 */
@DisplayName("CardUpdateService — COCRDUPC / CCUP read-then-rewrite optimistic locking (SHA 27d6c6f)")
@ExtendWith(MockitoExtension.class)
class CardUpdateServiceTest {

    // ------------------------------------------------------------------
    // Fixture constants
    // ------------------------------------------------------------------

    /** A valid 16-digit card number ({@code CARD-NUM PIC X(16)}), the record key. */
    private static final String CARD_NUMBER = "1234567890123456";

    /**
     * The expected PAN mask emitted by {@link CardViewResponse}: every character
     * except the trailing four is replaced with {@code '*'} while the width is
     * preserved (12 mask characters + the last four digits {@code "3456"}).
     */
    private static final String MASKED_CARD_NUMBER = "*".repeat(12) + "3456";

    /** The account id persisted on the card ({@code CARD-ACCT-ID PIC 9(11)}). */
    private static final Long ORIGINAL_ACCOUNT_ID = 100L;

    /**
     * The persisted account id as the response projects it: rendered UNPADDED via
     * {@code String.valueOf} to match the card-view GET path, so a card-update
     * round-trip returns an account-id string identical to the subsequent
     * {@code GET} (F-PADDED-ID).
     */
    private static final String EXPECTED_ORIGINAL_ACCOUNT_ID = "100";

    /**
     * The account id supplied on the request. It is deliberately different from
     * {@link #ORIGINAL_ACCOUNT_ID}: {@code updateCard} must ignore it entirely
     * (the account id is an ownership field that is never rewritten).
     */
    private static final String REQUEST_ACCOUNT_ID = "99999999999";

    /** The persisted card verification value ({@code CARD-CVV-CD PIC 9(03)}); must survive an update untouched. */
    private static final Integer ORIGINAL_CVV = 123;

    /** The embossed name on the persisted card before the update. */
    private static final String ORIGINAL_EMBOSSED_NAME = "ORIGINAL NAME";

    /** The active status on the persisted card before the update. */
    private static final String ORIGINAL_STATUS = "N";

    /** The expiration date on the persisted card before the update. */
    private static final LocalDate ORIGINAL_EXPIRATION = LocalDate.of(2020, 1, 1);

    /** The requested new embossed name. */
    private static final String NEW_EMBOSSED_NAME = "NEW EMBOSSED NAME";

    /** A valid requested active status. */
    private static final String VALID_STATUS = "Y";

    /** A valid requested expiration date (year within 1950&ndash;2099). */
    private static final LocalDate NEW_EXPIRATION = LocalDate.of(2030, 6, 30);

    /** The optimistic-lock version the caller last observed for the persisted card. */
    private static final Long BASE_VERSION = 1L;

    // Verbatim COBOL message literals (COCRDUPC @ SHA 27d6c6f) reproduced by the service.

    /** {@code DID-NOT-FIND-ACCT-IN-CARDXREF} literal (line&nbsp;202). */
    private static final String MSG_CARD_NOT_FOUND = "Did not find this account in cards database";

    /** {@code SEARCHED-CARD-NOT-NUMERIC} literal (line&nbsp;194). */
    private static final String MSG_CARD_NUMBER = "Card number if supplied must be a 16 digit number";

    /** {@code CARD-STATUS-MUST-BE-YES-NO} literal (line&nbsp;196). */
    private static final String MSG_ACTIVE_STATUS = "Card Active Status must be Y or N";

    /** {@code CARD-EXPIRY-YEAR-NOT-VALID} literal (line&nbsp;200). */
    private static final String MSG_EXPIRY_YEAR = "Invalid card expiry year";

    /** {@code CONFIRM-UPDATE-SUCCESS} literal (line&nbsp;169), emitted on a committed rewrite. */
    private static final String MSG_SUCCESS = "Changes committed to database";

    // ------------------------------------------------------------------
    // Collaborators / system under test
    // ------------------------------------------------------------------

    /** The migrated VSAM {@code CARDDATA} keyed access, mocked. */
    @Mock
    private CardRepository cardRepository;

    /** The system under test, wired with the mocked repository via constructor injection. */
    @InjectMocks
    private CardUpdateService service;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Builds a freshly persisted {@link Card} snapshot as the service would read
     * it from the repository: a known optimistic-lock version, a CVV and an
     * account id (both of which an update must leave untouched), plus the
     * mutable fields in their pre-update state.
     *
     * @return a new, independent {@link Card} instance for a single test
     */
    private static Card persistedCard() {
        Card card = new Card();
        card.setCardNum(CARD_NUMBER);
        card.setCardAcctId(ORIGINAL_ACCOUNT_ID);
        card.setCardCvvCd(ORIGINAL_CVV);
        card.setCardEmbossedName(ORIGINAL_EMBOSSED_NAME);
        card.setCardActiveStatus(ORIGINAL_STATUS);
        card.setCardExpirationDate(ORIGINAL_EXPIRATION);
        card.setVersion(BASE_VERSION);
        return card;
    }

    /**
     * Builds a request that is valid in every respect (matching version, active
     * status {@code "Y"}, in-range expiry). Individual tests deviate a single
     * field to isolate the branch under exercise.
     *
     * @param activeStatus   the requested active status
     * @param expirationDate the requested expiration date
     * @param version        the optimistic-lock version the caller echoes
     * @return a populated {@link CardUpdateRequest}
     */
    private static CardUpdateRequest requestOf(String activeStatus, LocalDate expirationDate, Long version) {
        return new CardUpdateRequest(
                REQUEST_ACCOUNT_ID, CARD_NUMBER, NEW_EMBOSSED_NAME, activeStatus, expirationDate, version);
    }

    // ------------------------------------------------------------------
    // 9000-READ-DATA — DFHRESP(NOTFND) -> 404
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateCard: a null request body -> ValidationException(400) before any read/write (M3)")
    void updateCard_nullRequest_throwsValidationException() {
        // M3 (review finding): a null request body must be a typed HTTP-400
        // validation error, never an unhandled NullPointerException / HTTP 500 on
        // the request.version() dereference in the optimistic-lock precheck.
        assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, null))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage(CardUpdateService.MSG_VALIDATION_SUMMARY);

        verify(cardRepository, never()).findById(anyString());
        verify(cardRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updateCard: an unknown card number yields a 404 with the verbatim COCRDUPC not-found message and never writes")
    void updateCard_notFound_throwsResourceNotFound() {
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.empty());

        CardUpdateRequest request = requestOf(VALID_STATUS, NEW_EXPIRATION, BASE_VERSION);

        assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(MSG_CARD_NOT_FOUND);

        verify(cardRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // 9300-CHECK-CHANGE-IN-REC — optimistic-lock precheck (before any write)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateCard: a stale caller version is rejected as a 409 conflict BEFORE any write (read-then-rewrite precheck)")
    void updateCard_versionMismatch_throwsOptimisticLockConflict() {
        Card card = persistedCard();
        card.setVersion(3L);                       // the row now stands at version 3 ...
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(card));

        CardUpdateRequest staleRequest =
                requestOf(VALID_STATUS, NEW_EXPIRATION, 2L);   // ... but the caller last saw version 2

        assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, staleRequest))
                .isInstanceOf(OptimisticLockConflictException.class)
                .hasMessage(OptimisticLockConflictException.DEFAULT_MESSAGE)
                .hasNoCause();                     // precheck path uses the no-arg constructor -> no cause

        // The rewrite must never be attempted once the precheck fails.
        verify(cardRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // 9200-WRITE-PROCESSING — persistence-layer optimistic-lock failure -> 409 (wrapped)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateCard: a persistence-layer optimistic-lock failure on saveAndFlush is wrapped as a 409 conflict WITH a cause")
    void updateCard_persistOptimisticFailure_wrappedAs409() {
        Card card = persistedCard();
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(card));

        ObjectOptimisticLockingFailureException persistFailure =
                new ObjectOptimisticLockingFailureException(Card.class, CARD_NUMBER);
        when(cardRepository.saveAndFlush(any(Card.class))).thenThrow(persistFailure);

        CardUpdateRequest request = requestOf(VALID_STATUS, NEW_EXPIRATION, BASE_VERSION);

        assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                .isInstanceOf(OptimisticLockConflictException.class)
                .hasMessage(OptimisticLockConflictException.DEFAULT_MESSAGE)
                .hasCause(persistFailure);         // persist path wraps the DAO failure as the cause

        // The failure surfaced on the rewrite itself, so saveAndFlush was invoked exactly once.
        verify(cardRepository).saveAndFlush(any(Card.class));
    }

    // ------------------------------------------------------------------
    // 1240-EDIT-CARDSTATUS — active status must be Y or N
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateCard: an active status outside {Y,N} fails validation with the verbatim message and never writes")
    void updateCard_invalidActiveStatus_throwsValidation() {
        Card card = persistedCard();
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(card));

        CardUpdateRequest request = requestOf("X", NEW_EXPIRATION, BASE_VERSION);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.updateCard(CARD_NUMBER, request));
        // The verbatim COBOL literal is carried per-field in the field-error map.
        assertThat(ex.getFieldErrors()).containsEntry("activeStatus", MSG_ACTIVE_STATUS);

        verify(cardRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // 9200-WRITE-PROCESSING — happy path (only allowed fields change)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateCard: happy path rewrites only embossedName/activeStatus/expirationDate, preserves CVV & account id, returns new version + masked PAN")
    void updateCard_success_updatesOnlyAllowedFields() {
        Card card = persistedCard();
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(card));
        // Simulate the JPA @Version increment that the provider performs on flush,
        // so the response carries the *new* optimistic-lock version.
        when(cardRepository.saveAndFlush(any(Card.class))).thenAnswer(invocation -> {
            Card persisted = invocation.getArgument(0);
            persisted.setVersion(persisted.getVersion() + 1L);
            return persisted;
        });

        CardUpdateRequest request = requestOf(VALID_STATUS, NEW_EXPIRATION, BASE_VERSION);

        CardUpdateResponse response = service.updateCard(CARD_NUMBER, request);

        // --- Only the three mutable fields were applied to the persisted entity.
        ArgumentCaptor<Card> savedCaptor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).saveAndFlush(savedCaptor.capture());
        Card saved = savedCaptor.getValue();
        assertThat(saved.getCardEmbossedName()).isEqualTo(NEW_EMBOSSED_NAME);
        assertThat(saved.getCardActiveStatus()).isEqualTo(VALID_STATUS);
        assertThat(saved.getCardExpirationDate()).isEqualTo(NEW_EXPIRATION);

        // --- The CVV, the account id and the key were NOT touched by the update.
        assertThat(saved.getCardCvvCd()).isEqualTo(ORIGINAL_CVV);
        assertThat(saved.getCardAcctId()).isEqualTo(ORIGINAL_ACCOUNT_ID); // NOT the request's REQUEST_ACCOUNT_ID
        assertThat(saved.getCardNum()).isEqualTo(CARD_NUMBER);

        // --- The response carries the legacy success confirmation and the new (bumped) version.
        assertThat(response.message()).isEqualTo(MSG_SUCCESS);
        assertThat(response.version()).isEqualTo(BASE_VERSION + 1L);

        // --- The nested view masks the PAN, echoes the *original* account id, exposes no CVV.
        CardViewResponse view = response.card();
        assertThat(view).isNotNull();
        assertThat(view.version()).isEqualTo(BASE_VERSION + 1L);
        assertThat(view.cardNumber()).isEqualTo(MASKED_CARD_NUMBER);
        assertThat(view.cardNumber()).doesNotContain(CARD_NUMBER);
        assertThat(view.accountId()).isEqualTo(EXPECTED_ORIGINAL_ACCOUNT_ID);
        assertThat(view.embossedName()).isEqualTo(NEW_EMBOSSED_NAME);
        assertThat(view.activeStatus()).isEqualTo(VALID_STATUS);
        assertThat(view.expirationDate()).isEqualTo(NEW_EXPIRATION);
    }

    // ------------------------------------------------------------------
    // 1220-EDIT-CARD — search-key edit runs before the read
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateCard: a non-16-digit card number fails validation before the record is ever read")
    void updateCard_invalidCardNumber_throwsValidation() {
        CardUpdateRequest request = new CardUpdateRequest(
                REQUEST_ACCOUNT_ID, "123", NEW_EMBOSSED_NAME, VALID_STATUS, NEW_EXPIRATION, BASE_VERSION);

        assertThatThrownBy(() -> service.updateCard("123", request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_CARD_NUMBER);

        // The search-key edit precedes the keyed read, so the repository is never consulted.
        verify(cardRepository, never()).findById(anyString());
        verify(cardRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // 1260-EDIT-EXPIRY-YEAR — year must be within 1950-2099
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateCard: an expiry year outside 1950-2099 fails validation with the verbatim message and never writes")
    void updateCard_invalidExpiryYear_throwsValidation() {
        Card card = persistedCard();
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(card));

        CardUpdateRequest request = requestOf(VALID_STATUS, LocalDate.of(1949, 1, 1), BASE_VERSION);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.updateCard(CARD_NUMBER, request));
        assertThat(ex.getFieldErrors()).containsEntry("expirationDate", MSG_EXPIRY_YEAR);

        verify(cardRepository, never()).saveAndFlush(any());
    }
}
