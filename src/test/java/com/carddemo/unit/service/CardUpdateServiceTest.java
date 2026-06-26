/*
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
package com.carddemo.unit.service;

import java.util.Optional;
import java.util.stream.Stream;

import com.carddemo.dto.CardDto;
import com.carddemo.entity.Card;
import com.carddemo.exception.ConcurrentUpdateException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.CardUpdateService;
import com.carddemo.service.DateValidationService;

import jakarta.persistence.OptimisticLockException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link CardUpdateService}, the Java
 * realization of the CICS pseudo-conversational card-update program
 * {@code COCRDUPC} (transaction {@code CCUP}, {@code PUT /api/cards/{cardNum}})
 * at source commit {@code 27d6c6f} (AAP&nbsp;&sect;0.4.1.1).
 *
 * <p>{@code COCRDUPC} edits the card key and detail fields in a fixed order,
 * re-reads the {@code CARDDAT} record, compares it against the values originally
 * fetched ({@code 9300-CHECK-CHANGE-IN-REC}), and rewrites the record inside a
 * single CICS {@code SYNCPOINT} unit of work. The Java port reproduces that
 * behaviour as an ordered cascade of guarded checks followed by a
 * {@code @Transactional(rollbackFor = Exception.class)} persist under JPA
 * {@code @Version} optimistic locking, where a detected conflict surfaces as a
 * {@link ConcurrentUpdateException}.</p>
 *
 * <p>The suite is deliberately framework-free: it bootstraps <strong>no</strong>
 * Spring {@code ApplicationContext}, uses <strong>no</strong> {@code @SpringBootTest},
 * {@code MockMvc}, or Testcontainers, and touches no database, AWS, or network
 * resource. The two collaborators &mdash; {@link CardRepository} and
 * {@link DateValidationService} &mdash; are Mockito mocks injected into the
 * service under test. {@code MockitoExtension} runs with its default strict-stub
 * policy, so every stub declared here is exercised by the path under test.</p>
 *
 * <p><strong>Operator-message parity.</strong> Every asserted message is copied
 * byte-for-byte from {@code app/cbl/COCRDUPC.cbl} (lines&nbsp;178&ndash;204) and
 * is therefore a Gate&nbsp;1 / Gate&nbsp;4 behavioural-parity guard; the literals
 * here must never diverge from the legacy {@code 88}-level condition texts.</p>
 *
 * <p><strong>Persistence note.</strong> The compiled service writes through
 * {@link CardRepository#saveAndFlush(Object)} (so the optimistic-lock conflict
 * materializes within the service transaction), not {@code save}; the stubs and
 * verifications below target {@code saveAndFlush} accordingly.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardUpdateService - COCRDUPC (CCUP) card update / 9300-CHECK-CHANGE-IN-REC @ 27d6c6f")
class CardUpdateServiceTest {

    @Mock
    private CardRepository cardRepository;

    @Mock
    private DateValidationService dateValidationService;

    @InjectMocks
    private CardUpdateService service;

    // ----------------------------------------------------------------------
    // Fixed test data — a valid 11-digit account, a valid 16-digit card, and
    // the stored CARDDAT record the service reads before applying an update.
    // ----------------------------------------------------------------------

    /** {@code CARDSID X(16)} — the 16-digit card key, also the {@code findById} argument. */
    private static final String CARD_NUMBER = "0500000000000001";

    /** Owning account passed as the {@code Long} path variable for the ownership check. */
    private static final Long ACCOUNT_ID = 100000001L;

    /** {@code ACCTSID X(11)} — the 11-digit account string carried in the request body. */
    private static final String ACCOUNT_ID_STR = "00100000001";

    // ----------------------------------------------------------------------
    // Verbatim operator messages — COCRDUPC.cbl lines 178-204 (DO NOT EDIT).
    // ----------------------------------------------------------------------

    private static final String MSG_ACCOUNT_NOT_PROVIDED = "Account number not provided";
    private static final String MSG_CARD_NOT_PROVIDED = "Card number not provided";
    private static final String MSG_NO_INPUT = "No input received";
    private static final String MSG_NAME_NOT_PROVIDED = "Card name not provided";
    private static final String MSG_NAME_ALPHA = "Card name can only contain alphabets and spaces";
    private static final String MSG_STATUS_YES_NO = "Card Active Status must be Y or N";
    private static final String MSG_EXPIRY_MONTH = "Card expiry month must be between 1 and 12";
    private static final String MSG_EXPIRY_YEAR = "Invalid card expiry year";
    private static final String MSG_ACCOUNT_ELEVEN_DIGITS =
            "Account number must be a non zero 11 digit number";
    private static final String MSG_CARD_SIXTEEN_DIGITS =
            "Card number if supplied must be a 16 digit number";
    private static final String MSG_NO_CHANGE = "No change detected with respect to values fetched.";
    private static final String MSG_NOT_FOUND_CARD = "Did not find this account in cards database";
    private static final String MSG_NOT_FOUND_COMBO = "Did not find cards for this search condition";

    // ----------------------------------------------------------------------
    // Fixtures
    // ----------------------------------------------------------------------

    /**
     * Builds the stored {@code CARDDAT} record: owned by {@link #ACCOUNT_ID},
     * embossed {@code "JOHN DOE"}, active ({@code "Y"}), expiring 2025-12-31,
     * at optimistic-lock version 0.
     *
     * @return a fresh, mutable {@link Card} for each test
     */
    private static Card storedCard() {
        return new Card(CARD_NUMBER, ACCOUNT_ID, 123, "JOHN DOE", "2025-12-31", "Y", 0L);
    }

    /**
     * Builds an {@link CardDto.UpdateRequest} with the supplied detail fields and
     * the canonical valid account / card key.
     *
     * @param name   the cardholder name segment
     * @param status the active-status segment
     * @param month  the expiry-month segment
     * @param year   the expiry-year segment
     * @param day    the expiry-day segment
     * @return the assembled update request
     */
    private static CardDto.UpdateRequest request(String name, String status,
                                                 String month, String year, String day) {
        return new CardDto.UpdateRequest(ACCOUNT_ID_STR, CARD_NUMBER, name, status, month, year, day, 0L);
    }

    /**
     * Builds an update request that carries valid detail fields but lets the
     * caller vary the account / card key (used by the key-field edits).
     *
     * @param account the account-id segment under test
     * @param card    the card-number segment under test
     * @return the assembled update request
     */
    private static CardDto.UpdateRequest keyRequest(String account, String card) {
        return new CardDto.UpdateRequest(account, card, "JOHN", "Y", "12", "2025", "31", 0L);
    }

    /** A fully valid request that changes the name, status, and expiry of {@link #storedCard()}. */
    private static CardDto.UpdateRequest validChangingRequest() {
        return request("JANE SMITH", "N", "11", "2030", "15");
    }

    /** A fully valid request whose values exactly match {@link #storedCard()} (no change). */
    private static CardDto.UpdateRequest unchangedRequest() {
        return request("JOHN DOE", "Y", "12", "2025", "31");
    }

    /**
     * Asserts that a request rejected during the input-edit cascade raises a
     * {@link ValidationException} carrying the exact message and per-field entry,
     * and that the failure short-circuits before any collaborator is touched
     * (mirroring COCRDUPC, which edits the screen before any file I/O).
     *
     * @param invalid         the request expected to fail validation
     * @param expectedMessage the verbatim operator message
     * @param expectedField   the field key expected in {@code getFieldErrors()}
     */
    private void assertInputEditRejected(CardDto.UpdateRequest invalid,
                                         String expectedMessage, String expectedField) {
        Throwable thrown = catchThrowable(() -> service.updateCard(ACCOUNT_ID, CARD_NUMBER, invalid));

        assertThat(thrown)
                .isInstanceOf(ValidationException.class)
                .hasMessage(expectedMessage);
        assertThat(((ValidationException) thrown).getFieldErrors())
                .containsEntry(expectedField, expectedMessage);
        verifyNoInteractions(cardRepository, dateValidationService);
    }

    // ======================================================================
    // Phase 1 — successful update
    // ======================================================================

    @Test
    @DisplayName("Phase 1: valid update mutates the Card, persists via saveAndFlush, and returns the refreshed Detail")
    void updateCardAppliesChangesPersistsAndReturnsDetail() {
        Card stored = storedCard();
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(stored));
        when(cardRepository.saveAndFlush(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CardDto.Detail detail = service.updateCard(ACCOUNT_ID, CARD_NUMBER, validChangingRequest());

        // The returned Detail re-displays the updated record (COCRDUPC re-reads after rewrite).
        assertThat(detail.accountId()).isEqualTo("00100000001");
        assertThat(detail.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(detail.cardholderName()).isEqualTo("JANE SMITH");
        assertThat(detail.cardStatus()).isEqualTo("N");
        assertThat(detail.expiryMonth()).isEqualTo("11");
        assertThat(detail.expiryYear()).isEqualTo("2030");
        // F-08-2: the day segment is now surfaced on the Detail so an API-only
        // read-modify-write can echo it back (the assembled date is 2030-11-15).
        assertThat(detail.expiryDay()).isEqualTo("15");
        // The optimistic-locking token is surfaced; the mock saveAndFlush returns the
        // same instance, so the version is unchanged (0L) at the unit level.
        assertThat(detail.version()).isEqualTo(0L);

        // The Card handed to the repository carries the mutated, byte-assembled values.
        ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).saveAndFlush(captor.capture());
        Card persisted = captor.getValue();
        assertThat(persisted.getCardNum()).isEqualTo(CARD_NUMBER);
        assertThat(persisted.getEmbossedName()).isEqualTo("JANE SMITH");
        assertThat(persisted.getActiveStatus()).isEqualTo("N");
        assertThat(persisted.getExpirationDate()).isEqualTo("2030-11-15");

        // The assembled expiry date is delegated to DateValidationService in (year, month, day) order.
        verify(dateValidationService).validateDateParts("2030", "11", "15", "Card expiry date");
    }

    // ======================================================================
    // Phase 2 — record not found / account mismatch
    // ======================================================================

    @Test
    @DisplayName("Phase 2: missing card record -> RecordNotFoundException \"Did not find this account in cards database\"; never persists")
    void updateCardWhenCardMissingThrowsNotFound() {
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCard(ACCOUNT_ID, CARD_NUMBER, validChangingRequest()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_NOT_FOUND_CARD);

        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("Phase 2: card owned by another account -> RecordNotFoundException \"Did not find cards for this search condition\"; never persists")
    void updateCardWhenAccountMismatchThrowsNotFound() {
        Card otherOwner = new Card(CARD_NUMBER, 999999999L, 123, "JOHN DOE", "2025-12-31", "Y", 0L);
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(otherOwner));

        assertThatThrownBy(() -> service.updateCard(ACCOUNT_ID, CARD_NUMBER, validChangingRequest()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_NOT_FOUND_COMBO);

        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    // ======================================================================
    // Phase 3 — input-edit cascade (verbatim messages; no persistence)
    // ======================================================================

    @Test
    @DisplayName("Phase 3: blank account, card supplied -> \"Account number not provided\"")
    void rejectsMissingAccount() {
        assertInputEditRejected(keyRequest("", CARD_NUMBER), MSG_ACCOUNT_NOT_PROVIDED, "accountId");
    }

    @Test
    @DisplayName("Phase 3: account not a non-zero 11-digit number -> \"Account number must be a non zero 11 digit number\"")
    void rejectsMalformedAccount() {
        assertInputEditRejected(keyRequest("123", CARD_NUMBER), MSG_ACCOUNT_ELEVEN_DIGITS, "accountId");
    }

    @Test
    @DisplayName("Phase 3: blank card, account supplied -> \"Card number not provided\"")
    void rejectsMissingCard() {
        assertInputEditRejected(keyRequest(ACCOUNT_ID_STR, ""), MSG_CARD_NOT_PROVIDED, "cardNumber");
    }

    @Test
    @DisplayName("Phase 3: card not a 16-digit number -> \"Card number if supplied must be a 16 digit number\"")
    void rejectsMalformedCard() {
        assertInputEditRejected(keyRequest(ACCOUNT_ID_STR, "123"), MSG_CARD_SIXTEEN_DIGITS, "cardNumber");
    }

    @Test
    @DisplayName("Phase 3: neither account nor card supplied -> \"No input received\"")
    void rejectsNoInput() {
        Throwable thrown = catchThrowable(() -> service.updateCard(ACCOUNT_ID, CARD_NUMBER, keyRequest("", "")));

        assertThat(thrown)
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_NO_INPUT);
        // The "no input" branch still records both missing key fields in the per-field map.
        assertThat(((ValidationException) thrown).getFieldErrors())
                .containsEntry("accountId", MSG_ACCOUNT_NOT_PROVIDED)
                .containsEntry("cardNumber", MSG_CARD_NOT_PROVIDED);
        verifyNoInteractions(cardRepository, dateValidationService);
    }

    @Test
    @DisplayName("Phase 3: blank cardholder name -> \"Card name not provided\"")
    void rejectsBlankName() {
        assertInputEditRejected(request("", "Y", "12", "2025", "31"), MSG_NAME_NOT_PROVIDED, "cardholderName");
    }

    @Test
    @DisplayName("Phase 3: non-alphabetic cardholder name -> \"Card name can only contain alphabets and spaces\"")
    void rejectsNonAlphabeticName() {
        assertInputEditRejected(request("JOHN123", "Y", "12", "2025", "31"), MSG_NAME_ALPHA, "cardholderName");
    }

    @Test
    @DisplayName("Phase 3: active status not Y or N -> \"Card Active Status must be Y or N\"")
    void rejectsInvalidStatus() {
        assertInputEditRejected(request("JOHN", "X", "12", "2025", "31"), MSG_STATUS_YES_NO, "cardStatus");
    }

    @Test
    @DisplayName("Phase 3: expiry month outside 1..12 -> \"Card expiry month must be between 1 and 12\"")
    void rejectsExpiryMonthOutOfRange() {
        assertInputEditRejected(request("JOHN", "Y", "13", "2025", "31"), MSG_EXPIRY_MONTH, "expiryMonth");
    }

    @Test
    @DisplayName("Phase 3: expiry year outside 1950..2099 -> \"Invalid card expiry year\"")
    void rejectsExpiryYearOutOfRange() {
        assertInputEditRejected(request("JOHN", "Y", "12", "1800", "31"), MSG_EXPIRY_YEAR, "expiryYear");
    }

    @Test
    @DisplayName("Phase 3: request equal to fetched values -> \"No change detected with respect to values fetched.\"; never persists")
    void rejectsWhenNoChangeDetected() {
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));

        Throwable thrown = catchThrowable(() -> service.updateCard(ACCOUNT_ID, CARD_NUMBER, unchangedRequest()));

        assertThat(thrown)
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_NO_CHANGE);
        assertThat(((ValidationException) thrown).getFieldErrors())
                .containsEntry("cardNumber", MSG_NO_CHANGE);
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    // ======================================================================
    // Phase 4 — optimistic-lock conflict (9300-CHECK-CHANGE-IN-REC)
    // ======================================================================

    /**
     * The two conflict signals the service catches when flushing the rewrite:
     * Spring Data's {@link OptimisticLockingFailureException} and the JPA
     * {@link OptimisticLockException}. Both must translate to the same
     * {@link ConcurrentUpdateException}.
     *
     * @return a stream of representative optimistic-lock failures
     */
    static Stream<RuntimeException> optimisticLockFailures() {
        return Stream.of(
                new OptimisticLockingFailureException("row version advanced"),
                new OptimisticLockException("entity was updated by another transaction"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("optimisticLockFailures")
    @DisplayName("Phase 4: optimistic-lock conflict on flush -> ConcurrentUpdateException (DEFAULT_MESSAGE, cause preserved)")
    void translatesOptimisticLockToConcurrentUpdate(RuntimeException cause) {
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
        when(cardRepository.saveAndFlush(any(Card.class))).thenThrow(cause);

        assertThatThrownBy(() -> service.updateCard(ACCOUNT_ID, CARD_NUMBER, validChangingRequest()))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage("Record changed by some one else. Please review")
                .hasMessage(ConcurrentUpdateException.DEFAULT_MESSAGE)
                .hasCause(cause);
    }

    @Test
    @DisplayName("Phase 4: stale client version (request behind persisted record) -> ConcurrentUpdateException; never persists")
    void rejectsStaleClientVersionBeforePersist() {
        // 9300-CHECK-CHANGE-IN-REC cross-request parity (QA F-05-1): the caller read
        // the card at version 0 (the token echoed in request.version()) but the
        // persisted row has since advanced to version 2. The explicit version
        // comparison must reject the stale write with the byte-exact 409 message
        // BEFORE saveAndFlush, mirroring the already-correct parallel-contention path.
        Card advanced = new Card(CARD_NUMBER, ACCOUNT_ID, 123, "JOHN DOE", "2025-12-31", "Y", 2L);
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(advanced));

        // validChangingRequest() echoes version 0L - stale relative to the persisted 2L.
        assertThatThrownBy(() -> service.updateCard(ACCOUNT_ID, CARD_NUMBER, validChangingRequest()))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessage(ConcurrentUpdateException.DEFAULT_MESSAGE);

        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    // ======================================================================
    // Phase 5 — transactional rollback intent (propagation contract)
    // ======================================================================

    @Test
    @DisplayName("Phase 5: a non-optimistic persistence failure propagates unchanged so @Transactional rolls back")
    void propagatesPersistenceFailureForRollback() {
        // The actual commit/rollback driven by @Transactional(rollbackFor = Exception.class) at the
        // CICS-SYNCPOINT boundary is a container concern verified by the Testcontainers integration
        // suite. At the unit level we lock the contract that *enables* rollback: a persistence failure
        // that is not an optimistic-lock conflict is NOT swallowed by the service — it escapes
        // updateCard to the caller (and therefore to the surrounding transaction advice).
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(storedCard()));
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("datastore unavailable");
        when(cardRepository.saveAndFlush(any(Card.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.updateCard(ACCOUNT_ID, CARD_NUMBER, validChangingRequest()))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .isSameAs(failure);
    }
}
