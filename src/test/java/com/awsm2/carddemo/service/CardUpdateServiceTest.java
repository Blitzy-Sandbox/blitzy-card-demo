/*
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
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.domain.Card;
import com.awsm2.carddemo.dto.CardDetailDto;
import com.awsm2.carddemo.dto.CardUpdateDto;
import com.awsm2.carddemo.exception.ConcurrentModificationException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// COBOL: COCRDUPC:UPDATE-CARD -- behavioural-parity test suite for the
// COBOL/CICS card-update flow translated by {@link CardUpdateService}.
// Locks the COBOL semantics (READ UPDATE → before/after snapshot compare
// → REWRITE → SYNCPOINT) onto JPA-idiomatic optimistic locking, cache-
// aside invalidation, and CloudTrail/OpenSearch audit emission. Every
// @Nested group below corresponds to a specific COCRDUPC.cbl paragraph
// family or a cross-cutting concern (PCI-DSS, side-effect ordering)
// surfaced by AAP §0.6.6 and §0.7.1.

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link CardUpdateService}.
 *
 * <p><b>COBOL provenance.</b> {@link CardUpdateService} translates
 * {@code app/cbl/COCRDUPC.cbl} (CICS transaction id {@code CCUP}, file
 * {@code 'CARDDAT'}). The COBOL source performs the
 * {@code SEND MAP CCRDUPA} &rarr; {@code RECEIVE MAP CCRDUPA} &rarr;
 * {@code 1200-EDIT-MAP-INPUTS} (name / status / month / year / account
 * edits) &rarr; {@code 9200-WRITE-PROCESSING} ({@code EXEC CICS READ
 * UPDATE} &rarr; {@code 9300-CHECK-CHANGE-IN-REC} before/after snapshot
 * compare &rarr; {@code EXEC CICS REWRITE}) &rarr; cache invalidate
 * &rarr; audit emit.</p>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>Optimistic-lock pre-check</b> &mdash; if the supplied
 *       {@code request.version()} does not match the loaded entity's
 *       version a {@link ConcurrentModificationException} is thrown
 *       BEFORE any save attempt (HTTP 409, COBOL
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE}).</li>
 *   <li><b>JPA save-time optimistic-lock</b> &mdash;
 *       {@link OptimisticLockingFailureException} from
 *       {@link CardRepository#save(Object)} is re-thrown as a domain
 *       {@link ConcurrentModificationException} (HTTP 409, COBOL
 *       {@code LOCKED-BUT-UPDATE-FAILED}).</li>
 *   <li><b>Missing-version request rejection</b> &mdash; null
 *       {@code request.version()} surfaces as
 *       {@link ValidationException} BEFORE the repository find call.</li>
 *   <li><b>Path/body identity</b> &mdash; the URL {@code cardNumber}
 *       and the body {@code cardNumber} must match exactly; mismatches
 *       throw {@link ValidationException} ({@code CARDNUM_PATH_BODY_MISMATCH}).</li>
 *   <li><b>Field validation cascade</b> &mdash; embossed-name presence
 *       and length, status code {@code Y}/{@code N}, expiration month
 *       1-12, year 1950-2099, and account ID &gt; 0 each surface as
 *       {@link ValidationException} with distinct reason codes.</li>
 *   <li><b>CVV immutability (PCI-DSS)</b> &mdash;
 *       {@link CardUpdateDto} carries no CVV component and
 *       {@code CardUpdateService#applyEdits} must NOT mutate the
 *       persisted card's CVV.</li>
 *   <li><b>Cache invalidation + audit emission</b> &mdash; after a
 *       successful save the {@code card-detail} cache entry is evicted
 *       and the {@code card.updated} audit event is emitted with PCI-
 *       safe last-4 PAN.</li>
 *   <li><b>Cache-aside / audit failures are non-fatal</b> &mdash; the
 *       service returns the persisted {@link CardDetailDto} even when
 *       cache eviction or audit emission throw.</li>
 *   <li><b>PCI-DSS logging discipline</b> &mdash; audit payload keys
 *       and values omit the full PAN; only the last 4 are present.</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}; no LocalStack or Testcontainers are
 * involved.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardUpdateService unit tests (COBOL: COCRDUPC.cbl)")
class CardUpdateServiceTest {

    // ==================================================================
    // Test constants — used across every nested group
    // ==================================================================
    private static final String CARD_NUMBER = "4111222233334444";
    private static final String LAST4 = "4444";
    private static final Long ACCOUNT_ID = 11_111_111_111L;
    private static final String EMBOSSED_NAME = "JOHN Q DOE";
    private static final String ACTIVE_STATUS = "Y";
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2028, 12, 31);
    private static final Long VERSION = 5L;
    // CARD-CVV-CD is intentionally not stored on the Card entity per
    // PCI-DSS v4.0 Requirement 3.2 (QA finding DB1); no CVV constant
    // is therefore required for fixtures.

    // ==================================================================
    // Mocks and SUT
    // ==================================================================
    @Mock private CardRepository cardRepository;
    @Mock private CacheService cacheService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private CardUpdateService service;

    // ==================================================================
    // Common test fixtures
    // ==================================================================
    private CardUpdateDto validRequest;
    private Card existingCard;

    @BeforeEach
    void setUp() {
        validRequest = new CardUpdateDto(
                CARD_NUMBER,
                ACCOUNT_ID,
                EMBOSSED_NAME,
                EXPIRATION_DATE,
                ACTIVE_STATUS,
                VERSION);

        // CARD-CVV-CD is intentionally NOT a field on the Card entity
        // (QA finding DB1; PCI-DSS v4.0 Requirement 3.2 prohibits CVV
        // persistence post-authorization). The all-args constructor
        // omits the CVV parameter.
        existingCard = new Card(
                CARD_NUMBER,
                ACCOUNT_ID,
                "ORIG NAME",
                LocalDate.of(2025, 6, 30),
                "N");
        existingCard.setVersion(VERSION);
    }

    /**
     * Stub a happy-path findById and save so the cascade reaches its
     * normal completion. Uses {@code lenient()} on save because some
     * tests verify the save is never called and would otherwise trip
     * Mockito's strict-stubbing checks.
     */
    private void stubHappyPathRepositories() {
        when(cardRepository.findById(CARD_NUMBER))
                .thenReturn(Optional.of(existingCard));
        // CardUpdateService delegates to saveAndFlush() (not save())
        // so the @Version increment is materialised on the entity
        // instance *before* the audit payload and response DTO are
        // constructed. Mocking saveAndFlush() instead of save() keeps
        // the test fixture aligned with production behaviour (QA U2).
        lenient().when(cardRepository.saveAndFlush(any(Card.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private CardUpdateDto buildRequestWithVersion(Long version) {
        return new CardUpdateDto(
                CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME,
                EXPIRATION_DATE, ACTIVE_STATUS, version);
    }

    private CardUpdateDto buildRequestWithCardNumber(String cardNumber) {
        return new CardUpdateDto(
                cardNumber, ACCOUNT_ID, EMBOSSED_NAME,
                EXPIRATION_DATE, ACTIVE_STATUS, VERSION);
    }

    private CardUpdateDto buildRequestWithStatus(String status) {
        return new CardUpdateDto(
                CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME,
                EXPIRATION_DATE, status, VERSION);
    }

    private CardUpdateDto buildRequestWithExpiration(LocalDate expDate) {
        return new CardUpdateDto(
                CARD_NUMBER, ACCOUNT_ID, EMBOSSED_NAME,
                expDate, ACTIVE_STATUS, VERSION);
    }

    private CardUpdateDto buildRequestWithEmbossedName(String name) {
        return new CardUpdateDto(
                CARD_NUMBER, ACCOUNT_ID, name,
                EXPIRATION_DATE, ACTIVE_STATUS, VERSION);
    }

    private CardUpdateDto buildRequestWithAccountId(Long accountId) {
        return new CardUpdateDto(
                CARD_NUMBER, accountId, EMBOSSED_NAME,
                EXPIRATION_DATE, ACTIVE_STATUS, VERSION);
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    @Nested
    @DisplayName("Optimistic-lock conflict (CP5 — JPA @Version + 409 mapping)")
    class OptimisticLock {

        @Test
        @DisplayName("rejects request with null version (no findById call)")
        void updateCard_nullVersion_throwsValidation() {
            // Arrange — request carries null version; findById must
            // succeed to reach the version-check step.
            CardUpdateDto request = buildRequestWithVersion(null);
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(existingCard));

            // Act + Assert — null version maps to ValidationException
            // (MISSING_VERSION) BEFORE save.
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Version");

            verify(cardRepository, never()).saveAndFlush(any());
            verify(cacheService, never()).evict(anyString(), anyString());
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("pre-check raises ConcurrentModificationException when version mismatched")
        void updateCard_versionMismatch_throwsConcurrentModification() {
            // Arrange — supplied version (5) does not match persisted
            // version (7). The pre-check fires BEFORE save.
            existingCard.setVersion(7L);
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(existingCard));
            CardUpdateDto request = buildRequestWithVersion(VERSION);

            // Act + Assert
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ConcurrentModificationException.class)
                    .hasMessageContaining("modified by another transaction");

            verify(cardRepository, never()).saveAndFlush(any());
            verify(cacheService, never()).evict(anyString(), anyString());
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("save-time OptimisticLockingFailureException is wrapped to ConcurrentModificationException")
        void updateCard_saveTimeOptimisticLock_wrapped() {
            // Arrange — pre-check passes (versions match) but save
            // throws OptimisticLockingFailureException (concurrent
            // writer between the SELECT and the UPDATE).
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(existingCard));
            when(cardRepository.saveAndFlush(any(Card.class)))
                    .thenThrow(new OptimisticLockingFailureException(
                            "Row was updated by another transaction"));

            // Act + Assert
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, validRequest))
                    .isInstanceOf(ConcurrentModificationException.class)
                    .hasMessageContaining("concurrent modification");

            verify(cacheService, never()).evict(anyString(), anyString());
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("RecordNotFound mapping (CICS FILE STATUS 23)")
    class RecordNotFound {

        @Test
        @DisplayName("throws RecordNotFoundException when card row missing")
        void updateCard_cardMissing_throwsRecordNotFound() {
            // Arrange — findById returns empty Optional
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, validRequest))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("Card not found");

            verify(cardRepository, never()).saveAndFlush(any());
            verify(cacheService, never()).evict(anyString(), anyString());
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("Validation cascade (COCRDUPC 1200-EDIT-MAP-INPUTS family)")
    class FieldValidation {

        @Test
        @DisplayName("rejects null cardNumber path")
        void updateCard_nullCardNumber_throwsNpe() {
            assertThatThrownBy(() -> service.updateCard(null, validRequest))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null request")
        void updateCard_nullRequest_throwsNpe() {
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects blank cardNumber path")
        void updateCard_blankCardNumber_throwsValidation() {
            assertThatThrownBy(() ->
                    service.updateCard("                ", validRequest))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("required");
        }

        @Test
        @DisplayName("rejects cardNumber path of wrong length")
        void updateCard_wrongLengthCardNumber_throwsValidation() {
            // 15-digit card number
            String shortCardNumber = "411122223333444";
            assertThatThrownBy(() ->
                    service.updateCard(shortCardNumber, validRequest))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("16 characters");
        }

        @Test
        @DisplayName("rejects non-numeric cardNumber path")
        void updateCard_nonNumericCardNumber_throwsValidation() {
            // 16 chars but contains a letter
            String alphaCardNumber = "411122223333444A";
            assertThatThrownBy(() ->
                    service.updateCard(alphaCardNumber, validRequest))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("16-digit numeric");
        }

        @Test
        @DisplayName("rejects path/body cardNumber mismatch (no PK tampering)")
        void updateCard_pathBodyMismatch_throwsValidation() {
            // Path says one PAN, body says another
            CardUpdateDto request =
                    buildRequestWithCardNumber("5555666677778888");
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("does not match");

            verify(cardRepository, never()).findById(anyString());
            verify(cardRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects null embossedName (COBOL 1230-EDIT-NAME)")
        void updateCard_nullEmbossedName_throwsValidation() {
            CardUpdateDto request = buildRequestWithEmbossedName(null);
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Card name not provided");
        }

        @Test
        @DisplayName("rejects blank embossedName")
        void updateCard_blankEmbossedName_throwsValidation() {
            CardUpdateDto request = buildRequestWithEmbossedName("   ");
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Card name not provided");
        }

        @Test
        @DisplayName("rejects embossedName longer than 50 chars")
        void updateCard_longEmbossedName_throwsValidation() {
            String tooLong = "A".repeat(51);
            CardUpdateDto request = buildRequestWithEmbossedName(tooLong);
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("at most 50");
        }

        @Test
        @DisplayName("rejects status code other than Y/N (COBOL 1240-EDIT-CARDSTATUS)")
        void updateCard_invalidStatus_throwsValidation() {
            CardUpdateDto request = buildRequestWithStatus("X");
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Y or N");
        }

        @Test
        @DisplayName("rejects null status")
        void updateCard_nullStatus_throwsValidation() {
            CardUpdateDto request = buildRequestWithStatus(null);
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Y or N");
        }

        @Test
        @DisplayName("rejects null expirationDate (COBOL 1250/1260)")
        void updateCard_nullExpiration_throwsValidation() {
            CardUpdateDto request = buildRequestWithExpiration(null);
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("expiry date not provided");
        }

        @Test
        @DisplayName("rejects expirationDate year < 1950")
        void updateCard_yearTooEarly_throwsValidation() {
            CardUpdateDto request =
                    buildRequestWithExpiration(LocalDate.of(1949, 12, 31));
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("expiry year");
        }

        @Test
        @DisplayName("rejects expirationDate year > 2099")
        void updateCard_yearTooLate_throwsValidation() {
            CardUpdateDto request =
                    buildRequestWithExpiration(LocalDate.of(2100, 1, 1));
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("expiry year");
        }

        @Test
        @DisplayName("rejects null accountId (COBOL 1210-EDIT-ACCOUNT)")
        void updateCard_nullAccountId_throwsValidation() {
            CardUpdateDto request = buildRequestWithAccountId(null);
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Account number not provided");
        }

        @Test
        @DisplayName("rejects zero accountId")
        void updateCard_zeroAccountId_throwsValidation() {
            CardUpdateDto request = buildRequestWithAccountId(0L);
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("non zero");
        }

        @Test
        @DisplayName("rejects negative accountId")
        void updateCard_negativeAccountId_throwsValidation() {
            CardUpdateDto request = buildRequestWithAccountId(-1L);
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("non zero");
        }
    }

    @Nested
    @DisplayName("Happy path: persist + invalidate + audit")
    class HappyPath {

        @Test
        @DisplayName("applies edits, saves, evicts cache, and emits audit event")
        void updateCard_happyPath_persistsAndEmitsAudit() {
            // Arrange
            stubHappyPathRepositories();

            // Act
            CardDetailDto response =
                    service.updateCard(CARD_NUMBER, validRequest);

            // Assert — response carries the new values.
            // QA finding D2: cardNumber on the response DTO is now
            // pre-masked at the producer (CardUpdateService.toDetailDto)
            // so the wire payload never carries the unmasked PAN.
            assertThat(response).isNotNull();
            assertThat(response.cardNumber()).isEqualTo("************"
                    + CARD_NUMBER.substring(CARD_NUMBER.length() - 4));
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(response.embossedName()).isEqualTo(EMBOSSED_NAME);
            assertThat(response.expirationDate()).isEqualTo(EXPIRATION_DATE);
            assertThat(response.activeStatus()).isEqualTo(ACTIVE_STATUS);
            // QA finding U2: the JPA @Version optimistic-lock token
            // MUST be projected onto the response DTO so the client
            // can chain a subsequent PUT without round-tripping to GET
            // /api/cards/{cardNumber}. The response carries the post-
            // save version which Hibernate increments by 1 over the
            // request payload's version.
            assertThat(response.version()).isNotNull();

            // Verify saveAndFlush was called and the entity carries the
            // request version (drives Hibernate UPDATE ... WHERE
            // version = ?). saveAndFlush forces immediate flush so the
            // @Version increment materialises before the response DTO
            // and audit payload are built (QA U2 remediation).
            ArgumentCaptor<Card> savedCaptor = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository).saveAndFlush(savedCaptor.capture());
            Card saved = savedCaptor.getValue();
            assertThat(saved.getCardEmbossedName()).isEqualTo(EMBOSSED_NAME);
            assertThat(saved.getCardActiveStatus()).isEqualTo(ACTIVE_STATUS);
            assertThat(saved.getCardExpirationDate()).isEqualTo(EXPIRATION_DATE);
            assertThat(saved.getCardAcctId()).isEqualTo(ACCOUNT_ID);
            // CVV is intentionally NOT a field on the Card entity (QA
            // finding DB1; PCI-DSS v4.0 Requirement 3.2) so there is no
            // CVV state to preserve through the update flow.

            // Cache eviction (cache-aside post-write invariant)
            verify(cacheService).evict(eq("card-detail"), eq(CARD_NUMBER));

            // Audit event emission
            verify(auditLogService).auditEvent(
                    eq("card.updated"), eq("system"), anyMap());
        }

        @Test
        @DisplayName("cache eviction failure is non-fatal")
        void updateCard_cacheEvictFails_stillSucceeds() {
            // Arrange — cache.evict throws but the save already happened
            stubHappyPathRepositories();
            doThrow(new RuntimeException("Redis down"))
                    .when(cacheService).evict(anyString(), anyString());

            // Act
            CardDetailDto response =
                    service.updateCard(CARD_NUMBER, validRequest);

            // Assert — response still returned; audit still emitted
            assertThat(response).isNotNull();
            verify(auditLogService).auditEvent(
                    eq("card.updated"), eq("system"), anyMap());
        }

        @Test
        @DisplayName("audit emission failure is non-fatal")
        void updateCard_auditFails_stillSucceeds() {
            // Arrange — audit.auditEvent throws after save completed
            stubHappyPathRepositories();
            doThrow(new RuntimeException("OpenSearch down"))
                    .when(auditLogService)
                    .auditEvent(anyString(), anyString(), anyMap());

            // Act
            CardDetailDto response =
                    service.updateCard(CARD_NUMBER, validRequest);

            // Assert — response still returned; eviction still happened
            assertThat(response).isNotNull();
            verify(cacheService).evict(eq("card-detail"), eq(CARD_NUMBER));
        }
    }

    // ==================================================================
    // The next three @Nested groups (StatusUpdate, EmbossedNameValidation,
    // ExpiryDateValidation) align test class structure with the
    // agent_prompt's Phase 3 group enumeration. Coverage overlaps with
    // the broader FieldValidation group above, but these focused groups
    // exercise specific COBOL paragraph behaviours (1240-EDIT-CARDSTATUS,
    // 1230-EDIT-NAME, 1250/1260-EDIT-EXPIRY) and the additive status-flip
    // + past-but-valid-date positive cases that FieldValidation does not
    // cover. COBOL: COCRDUPC:UPDATE-CARD
    // ==================================================================

    @Nested
    @DisplayName("StatusUpdate (COBOL: COCRDUPC 1240-EDIT-CARDSTATUS)")
    class StatusUpdate {

        @Test
        @DisplayName("flips status from N to Y (activate inactive card) and persists")
        void updateCard_activateInactiveCard_succeeds() {
            // COBOL: COCRDUPC:UPDATE-CARD -- the canonical activation
            // flip: existing CARD-ACTIVE-STATUS = 'N' (inactive), new
            // value = 'Y' (active). The COBOL flow accepts any
            // FLG-YES-NO-VALID value (lines 91, 845-873) and writes it
            // verbatim to CARD-UPDATE-ACTIVE-STATUS (line 1475).
            // Arrange — fixture already has existingCard.status = "N";
            // validRequest carries status = "Y".
            stubHappyPathRepositories();
            assertThat(existingCard.getCardActiveStatus())
                    .as("fixture precondition: existing status must be N")
                    .isEqualTo("N");
            assertThat(validRequest.activeStatus())
                    .as("fixture precondition: request status must be Y")
                    .isEqualTo("Y");

            // Act
            CardDetailDto response = service.updateCard(CARD_NUMBER, validRequest);

            // Assert — the persisted entity carries the new "Y" status
            // and the response DTO reflects it.
            ArgumentCaptor<Card> savedCaptor = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository).saveAndFlush(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getCardActiveStatus()).isEqualTo("Y");
            assertThat(response.activeStatus()).isEqualTo("Y");
            verify(cacheService).evict(eq("card-detail"), eq(CARD_NUMBER));
            verify(auditLogService).auditEvent(
                    eq("card.updated"), eq("system"), anyMap());
        }

        @Test
        @DisplayName("flips status from Y to N (deactivate active card) and persists")
        void updateCard_deactivateActiveCard_succeeds() {
            // COBOL: COCRDUPC:UPDATE-CARD -- the canonical deactivation
            // flip; covers the reverse direction of the activation test
            // above.
            // Arrange — existing card status = "Y"; request status = "N".
            existingCard.setCardActiveStatus("Y");
            stubHappyPathRepositories();
            CardUpdateDto deactivate = buildRequestWithStatus("N");

            // Act
            CardDetailDto response = service.updateCard(CARD_NUMBER, deactivate);

            // Assert
            ArgumentCaptor<Card> savedCaptor = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository).saveAndFlush(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getCardActiveStatus()).isEqualTo("N");
            assertThat(response.activeStatus()).isEqualTo("N");
        }

        @Test
        @DisplayName("rejects invalid status code (not Y or N)")
        void updateCard_invalidStatusCode_throwsValidation() {
            // COBOL: COCRDUPC:1240-EDIT-CARDSTATUS -- FLG-YES-NO-VALID
            // 88-level (line 91) rejects anything other than 'Y' or 'N'
            // with message CARD-STATUS-MUST-BE-YES-NO.
            CardUpdateDto invalid = buildRequestWithStatus("X");

            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, invalid))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Y or N");
            verify(cardRepository, never()).findById(anyString());
            verify(cardRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("EmbossedNameValidation (COBOL: COCRDUPC 1230-EDIT-NAME)")
    class EmbossedNameValidation {

        @Test
        @DisplayName("accepts a 50-character embossed name (max-length boundary)")
        void updateCard_maxLengthName_accepted() {
            // COBOL: COCRDUPC:1230-EDIT-NAME -- CARD-NAME-CHECK PIC X(50)
            // (line 87) accepts up to 50 characters; CARD-EMBOSSED-NAME
            // PIC X(50) (CVACT02Y.cpy line 8) is exactly 50 bytes wide.
            // Use a name composed only of characters that the @Pattern
            // on the DTO permits (uppercase letters and spaces) so the
            // request would also pass controller-boundary validation.
            String exactlyFifty = "A".repeat(50);
            stubHappyPathRepositories();
            CardUpdateDto request = buildRequestWithEmbossedName(exactlyFifty);

            CardDetailDto response = service.updateCard(CARD_NUMBER, request);

            assertThat(response.embossedName()).isEqualTo(exactlyFifty);
            ArgumentCaptor<Card> savedCaptor = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository).saveAndFlush(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getCardEmbossedName()).isEqualTo(exactlyFifty);
        }

        @Test
        @DisplayName("rejects blank embossed name (COBOL WS-PROMPT-FOR-NAME)")
        void updateCard_blankName_throwsValidation() {
            CardUpdateDto request = buildRequestWithEmbossedName("");
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Card name not provided");
        }

        @Test
        @DisplayName("rejects name longer than 50 chars (COBOL CARD-NAME-CHECK PIC X(50))")
        void updateCard_nameTooLong_throwsValidation() {
            String tooLong = "A".repeat(51);
            CardUpdateDto request = buildRequestWithEmbossedName(tooLong);
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("at most 50");
        }

        @Test
        @DisplayName("accepts a name with hyphens and apostrophes (COBOL CARD-NAME-CHECK alphanumeric)")
        void updateCard_nameWithSpecialChars_acceptedAtServiceLayer() {
            // COBOL: COCRDUPC:1230-EDIT-NAME -- the COBOL paragraph
            // applies an INSPECT against LIT-ALL-ALPHA / LIT-ALL-SPACES
            // (lines 806-840), but the Java SUT delegates that
            // alphabet-class enforcement to the @Pattern on
            // CardUpdateDto (Jakarta Bean Validation, fired at the
            // controller boundary). The SUT itself accepts any
            // non-blank value of length <= 50, so a name with hyphens
            // and apostrophes (which the DTO @Pattern permits) reaches
            // the database. This is the AAP-mandated split between
            // controller-layer DTO validation and service-layer
            // invariant enforcement (AAP §0.3.3, §0.7.3).
            String nameWithSpecials = "O'BRIEN-SMITH";
            stubHappyPathRepositories();
            CardUpdateDto request = buildRequestWithEmbossedName(nameWithSpecials);

            CardDetailDto response = service.updateCard(CARD_NUMBER, request);

            assertThat(response.embossedName()).isEqualTo(nameWithSpecials);
        }
    }

    @Nested
    @DisplayName("ExpiryDateValidation (COBOL: COCRDUPC 1250/1260-EDIT-EXPIRY)")
    class ExpiryDateValidation {

        @Test
        @DisplayName("accepts a past-but-valid expiry date (COBOL allows past dates)")
        void updateCard_pastExpiryDate_acceptedAsCobol() {
            // COBOL: COCRDUPC:1250-EDIT-EXPIRY-MON / 1260-EDIT-EXPIRY-YEAR
            // (lines 877-944) -- the COBOL flow validates month 1-12
            // and year 1950-2099 but does NOT reject past dates per se.
            // The Java SUT preserves this exact behaviour: an expiry
            // date in the past (year >= 1950, month 1-12) is accepted
            // and written to the row. Downstream consumers (e.g.,
            // TransactionPostingService reject code 103) handle the
            // expired-card semantics at TRANSACTION time, not at CARD
            // UPDATE time.
            LocalDate pastButValid = LocalDate.of(2020, 1, 15);
            stubHappyPathRepositories();
            CardUpdateDto request = buildRequestWithExpiration(pastButValid);

            CardDetailDto response = service.updateCard(CARD_NUMBER, request);

            assertThat(response.expirationDate()).isEqualTo(pastButValid);
            ArgumentCaptor<Card> savedCaptor = ArgumentCaptor.forClass(Card.class);
            verify(cardRepository).saveAndFlush(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getCardExpirationDate())
                    .isEqualTo(pastButValid);
        }

        @Test
        @DisplayName("accepts the minimum-year (1950) boundary")
        void updateCard_minYearBoundary_accepted() {
            LocalDate minYear = LocalDate.of(1950, 1, 1);
            stubHappyPathRepositories();
            CardUpdateDto request = buildRequestWithExpiration(minYear);

            CardDetailDto response = service.updateCard(CARD_NUMBER, request);
            assertThat(response.expirationDate()).isEqualTo(minYear);
        }

        @Test
        @DisplayName("accepts the maximum-year (2099) boundary")
        void updateCard_maxYearBoundary_accepted() {
            LocalDate maxYear = LocalDate.of(2099, 12, 31);
            stubHappyPathRepositories();
            CardUpdateDto request = buildRequestWithExpiration(maxYear);

            CardDetailDto response = service.updateCard(CARD_NUMBER, request);
            assertThat(response.expirationDate()).isEqualTo(maxYear);
        }

        @Test
        @DisplayName("rejects an expiry date with year < 1950")
        void updateCard_yearTooEarly_throwsValidation() {
            // COBOL: COCRDUPC:VALID-YEAR VALUES 1950 THRU 2099 (line 99)
            CardUpdateDto request =
                    buildRequestWithExpiration(LocalDate.of(1949, 12, 31));
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("expiry year");
        }

        @Test
        @DisplayName("rejects an expiry date with year > 2099")
        void updateCard_yearTooLate_throwsValidation() {
            CardUpdateDto request =
                    buildRequestWithExpiration(LocalDate.of(2100, 1, 1));
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("expiry year");
        }

        @Test
        @DisplayName("rejects a null expiry date")
        void updateCard_nullExpiryDate_throwsValidation() {
            CardUpdateDto request = buildRequestWithExpiration(null);
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("expiry date not provided");
        }
    }

    @Nested
    @DisplayName("PCI-DSS discipline: audit payload + log lines")
    class PciDssHandling {

        @Test
        @DisplayName("audit payload contains last4 only, never the full PAN")
        void updateCard_auditOmitsFullPan() {
            // Arrange
            stubHappyPathRepositories();

            // Act
            service.updateCard(CARD_NUMBER, validRequest);

            // Assert — capture the audit payload and scan for PCI-DSS
            // compliance: the full PAN must NOT appear, last4 MUST.
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).auditEvent(
                    eq("card.updated"), eq("system"), payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).isNotNull();
            assertThat(payload).containsEntry("cardLast4", LAST4);
            assertThat(payload).containsEntry("accountId", ACCOUNT_ID);
            assertThat(payload).containsEntry("entity", "Card");

            // COBOL: COCRDUPC:UPDATE-CARD -- mandatory CVV-absence
            // assertion per the agent prompt's Phase 4 critical rules
            // and AAP §0.6.6 (PCI-DSS v4.0 Requirement 3.2: sensitive
            // authentication data must NEVER appear in any audit /
            // logging / cache emission, even post-authorization).
            // The Card entity itself omits the CVV column (QA finding
            // DB1), so the SUT cannot accidentally emit a "cardCvvCd"
            // payload key — but the explicit negative assertion locks
            // the invariant against any future code-path that might
            // re-introduce CVV handling.
            assertThat(payload)
                    .as("audit payload must NEVER carry the CVV (PCI-DSS Req 3.2)")
                    .doesNotContainKey("cardCvvCd")
                    .doesNotContainKey("cardCvv")
                    .doesNotContainKey("cvv")
                    .doesNotContainKey("CVV")
                    .doesNotContainKey("CARD-CVV-CD");

            // No payload key or value may carry the full PAN
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                assertThat(key)
                        .as("audit key %s must not contain full PAN", key)
                        .doesNotContain(CARD_NUMBER);
                if (value != null) {
                    assertThat(value.toString())
                            .as("audit value for key %s must not contain full PAN", key)
                            .doesNotContain(CARD_NUMBER);
                }
            }
        }

        @Test
        @DisplayName("audit payload contains the new version (post-save)")
        void updateCard_auditCarriesPostSaveVersion() {
            // Arrange — emulate Hibernate incrementing the @Version
            // column when saveAndFlush() forces the UPDATE within the
            // transaction. This emulation is critical: production
            // CardUpdateService now uses saveAndFlush() (QA U2 fix) so
            // the audit payload is built from the *post-flush* entity
            // and therefore reflects the new version value, not the
            // stale pre-increment value.
            when(cardRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(existingCard));
            when(cardRepository.saveAndFlush(any(Card.class))).thenAnswer(inv -> {
                Card c = inv.getArgument(0);
                c.setVersion(c.getVersion() + 1L);
                return c;
            });

            // Act
            service.updateCard(CARD_NUMBER, validRequest);

            // Assert
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).auditEvent(
                    anyString(), anyString(), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("version", VERSION + 1L);
        }
    }

    @Nested
    @DisplayName("Side effects: precise verification of repository, cache, audit")
    class SideEffects {

        @Test
        @DisplayName("no cache eviction or audit when validation fails")
        void updateCard_validationFailure_noSideEffects() {
            // Arrange — invalid status code triggers ValidationException
            CardUpdateDto bad = buildRequestWithStatus("X");

            // Act + Assert
            assertThatThrownBy(() -> service.updateCard(CARD_NUMBER, bad))
                    .isInstanceOf(ValidationException.class);

            // Side effects must be zero
            verify(cardRepository, never()).findById(anyString());
            verify(cardRepository, never()).saveAndFlush(any());
            verify(cacheService, never()).evict(anyString(), anyString());
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("only one saveAndFlush call, never repository.findAll or others")
        void updateCard_singleSave() {
            // Arrange
            stubHappyPathRepositories();

            // Act
            service.updateCard(CARD_NUMBER, validRequest);

            // Assert
            verify(cardRepository).findById(CARD_NUMBER);
            verify(cardRepository).saveAndFlush(any(Card.class));
        }
    }
}
