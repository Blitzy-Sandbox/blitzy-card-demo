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
package com.carddemo.unit.service;

import java.util.Optional;

import com.carddemo.dto.CardDto;
import com.carddemo.entity.Card;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;
import com.carddemo.service.CardDetailService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link CardDetailService}, the Java
 * realization of the CICS pseudo-conversational program {@code COCRDSLC} (card
 * detail, transaction {@code CCDL}) at source commit {@code 27d6c6f}. The service
 * backs {@code GET /api/cards/{cardNum}}: it accepts an optional owning-account
 * filter plus a sixteen-digit card number, reads the {@code CARDDAT} record
 * (keyed on {@code CARD-NUM}) through the {@link CardRepository}, and maps it onto
 * a {@link CardDto.Detail} projection (AAP&nbsp;&sect;0.4.1.1).
 *
 * <p><strong>Framework-free isolation.</strong> The suite bootstraps
 * <strong>no</strong> Spring {@code ApplicationContext}: there is no
 * {@code @SpringBootTest}, no {@code MockMvc}, no Testcontainers, and no database,
 * AWS, or network access. The sole collaborator, {@link CardRepository}, is a
 * Mockito {@code @Mock} injected into the {@code @InjectMocks} service via
 * constructor injection. {@link MockitoExtension} runs with its default
 * {@code STRICT_STUBS} strictness, so {@code findById(...)} is stubbed only in the
 * tests that actually reach the repository lookup; the field-validation tests
 * assert the lookup is never invoked.</p>
 *
 * <p><strong>Byte-exact message parity (Gate&nbsp;1 / Gate&nbsp;4).</strong> Every
 * asserted message is verbatim from the compiled service, which in turn preserves
 * the {@code COCRDSLC} {@code WS-RETURN-MSG} / {@code WS-LONG-MSG} 88-level
 * literals. The reachable messages are: {@code "No input received"},
 * {@code "Card number not provided"},
 * {@code "Card number if supplied must be a 16 digit number"},
 * {@code "Account number must be a non zero 11 digit number"},
 * {@code "Did not find this account in cards database"}, and
 * {@code "Did not find cards for this search condition"}.</p>
 *
 * <p><strong>Documented COBOL&rarr;Java deviation.</strong> {@code COCRDSLC}
 * also defines the literal {@code "Account number not provided"}
 * ({@code WS-PROMPT-FOR-ACCT}). The compiled {@link CardDetailService} makes the
 * account <em>optional</em>: a {@code null} or zero account identifier is treated
 * as "not supplied", so a blank card with no account yields
 * {@code "No input received"} and a valid card with no account returns the detail.
 * Consequently <em>no</em> code path in the service can emit
 * {@code "Account number not provided"}; that message is unreachable. In keeping
 * with "assert verbatim where reachable" and "compiled source wins", this suite
 * does not assert the unreachable literal and instead pins the actual
 * optional-account behavior via
 * {@link #getCardAccountOptionalReturnsDetail()} and
 * {@link #getCardZeroAccountTreatedAsNotSupplied()}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardDetailService - COCRDSLC (CCDL) GET /api/cards/{cardNum} @ 27d6c6f")
class CardDetailServiceTest {

    /** Canonical sixteen-digit card number used across the lookup tests. */
    private static final String VALID_CARD = "0500000000000001";

    /** A different card number guaranteed to be absent from the mocked store. */
    private static final String MISSING_CARD = "0500000000000009";

    /** Owning account of {@link #sampleCard()} (a non-zero eleven-digit number). */
    private static final long VALID_ACCOUNT = 100000001L;

    /** A valid eleven-digit account that does <em>not</em> own {@link #sampleCard()}. */
    private static final long OTHER_ACCOUNT = 999999999L;

    @Mock
    private CardRepository cardRepository;

    @InjectMocks
    private CardDetailService service;

    /**
     * Builds the canonical card fixture: card number {@link #VALID_CARD}, owned by
     * account {@link #VALID_ACCOUNT}, CVV {@code 123}, embossed name
     * {@code "JOHN Q PUBLIC"}, expiration {@code "2024-12-31"}, active status
     * {@code "Y"}, version {@code 0}. A fresh instance is returned per call so no
     * mutable state is shared between tests.
     *
     * @return a fully populated {@link Card}
     */
    private static Card sampleCard() {
        return new Card(VALID_CARD, VALID_ACCOUNT, 123, "JOHN Q PUBLIC",
                "2024-12-31", "Y", 0L);
    }

    // -----------------------------------------------------------------
    // Phase 1 - happy path (9000-READ-CARD success, COCRDSLC detail map)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getCard: maps every Detail field from the persisted card (account zero-padded to 11 digits, expiry split MM/DD/YYYY)")
    void getCardHappyPathMapsAllFields() {
        when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(sampleCard()));

        CardDto.Detail detail = service.getCard(VALID_ACCOUNT, VALID_CARD);

        // accountId is the CARD's owning account formatted PIC 9(11) (zero-padded),
        // not the supplied filter value.
        assertThat(detail.accountId()).isEqualTo("00100000001");
        assertThat(detail.cardNumber()).isEqualTo(VALID_CARD);
        assertThat(detail.cardholderName()).isEqualTo("JOHN Q PUBLIC");
        assertThat(detail.cardStatus()).isEqualTo("Y");
        assertThat(detail.expiryMonth()).isEqualTo("12");
        assertThat(detail.expiryYear()).isEqualTo("2024");
        // F-08-2: the day segment of the persisted expiration date (2024-12-31) is
        // now surfaced on read so a stateless client can round-trip the card update.
        assertThat(detail.expiryDay()).isEqualTo("31");
        // The optimistic-locking version token is surfaced for the read-modify-write echo.
        assertThat(detail.version()).isEqualTo(0L);

        // The lookup key passed to CARDDAT equals the supplied card number.
        verify(cardRepository).findById(VALID_CARD);
    }

    @Test
    @DisplayName("getCard: maximum eleven-digit account (99,999,999,999) is accepted and matched")
    void getCardMaxAccountBoundaryAccepted() {
        Card card = new Card(VALID_CARD, 99_999_999_999L, 123, "JANE DOE",
                "2030-06-30", "N", 0L);
        when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(card));

        CardDto.Detail detail = service.getCard(99_999_999_999L, VALID_CARD);

        assertThat(detail.accountId()).isEqualTo("99999999999");
        assertThat(detail.cardStatus()).isEqualTo("N");
        assertThat(detail.expiryMonth()).isEqualTo("06");
        assertThat(detail.expiryYear()).isEqualTo("2030");
        assertThat(detail.expiryDay()).isEqualTo("30"); // F-08-2: day segment of 2030-06-30
    }

    @Test
    @DisplayName("getCard: account filter is optional - card-number-only lookup returns the detail (account derived from the card)")
    void getCardAccountOptionalReturnsDetail() {
        when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(sampleCard()));

        CardDto.Detail detail = service.getCard(null, VALID_CARD);

        assertThat(detail.cardNumber()).isEqualTo(VALID_CARD);
        assertThat(detail.accountId()).isEqualTo("00100000001");
        verify(cardRepository).findById(VALID_CARD);
    }

    // -----------------------------------------------------------------
    // Phase 2 - record not found (DID-NOT-FIND-ACCT-IN-CARDXREF)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getCard: unknown card number -> RecordNotFoundException 'Did not find this account in cards database'")
    void getCardNotFoundThrowsRecordNotFound() {
        when(cardRepository.findById(MISSING_CARD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCard(VALID_ACCOUNT, MISSING_CARD))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find this account in cards database");
    }

    // -----------------------------------------------------------------
    // Phase 3 - account/card mismatch (DID-NOT-FIND-ACCTCARD-COMBO)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getCard: card owned by a different account -> RecordNotFoundException 'Did not find cards for this search condition'")
    void getCardAccountMismatchThrowsRecordNotFound() {
        when(cardRepository.findById(VALID_CARD)).thenReturn(Optional.of(sampleCard()));

        assertThatThrownBy(() -> service.getCard(OTHER_ACCOUNT, VALID_CARD))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find cards for this search condition");
    }

    // -----------------------------------------------------------------
    // Phase 4 - field validation (edits precede the CARDDAT read)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("getCard: blank card number with a supplied account -> ValidationException 'Card number not provided' (no lookup)")
    void getCardBlankCardNumberThrowsValidation() {
        assertThatThrownBy(() -> service.getCard(VALID_ACCOUNT, "   "))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card number not provided");

        verify(cardRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getCard: null card number with a supplied account -> ValidationException 'Card number not provided' (no lookup)")
    void getCardNullCardNumberThrowsValidation() {
        assertThatThrownBy(() -> service.getCard(VALID_ACCOUNT, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card number not provided");

        verify(cardRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getCard: card number shorter than 16 digits -> ValidationException 'Card number if supplied must be a 16 digit number' (no lookup)")
    void getCardTooShortCardNumberThrowsValidation() {
        assertThatThrownBy(() -> service.getCard(VALID_ACCOUNT, "12345"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card number if supplied must be a 16 digit number");

        verify(cardRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getCard: 17-digit card number -> ValidationException 'Card number if supplied must be a 16 digit number' (no lookup)")
    void getCardTooLongCardNumberThrowsValidation() {
        assertThatThrownBy(() -> service.getCard(VALID_ACCOUNT, "05000000000000019"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card number if supplied must be a 16 digit number");

        verify(cardRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getCard: non-numeric 16-character card number -> ValidationException 'Card number if supplied must be a 16 digit number' (no lookup)")
    void getCardNonNumericCardNumberThrowsValidation() {
        assertThatThrownBy(() -> service.getCard(VALID_ACCOUNT, "ABCD123456789012"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card number if supplied must be a 16 digit number");

        verify(cardRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getCard: account above eleven digits -> ValidationException 'Account number must be a non zero 11 digit number' (no lookup)")
    void getCardOutOfRangeAccountThrowsValidation() {
        assertThatThrownBy(() -> service.getCard(100000000000L, VALID_CARD))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number must be a non zero 11 digit number");

        verify(cardRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getCard: negative account fails the non-zero 11-digit rule -> ValidationException 'Account number must be a non zero 11 digit number' (no lookup)")
    void getCardNegativeAccountThrowsValidation() {
        assertThatThrownBy(() -> service.getCard(-1L, VALID_CARD))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number must be a non zero 11 digit number");

        verify(cardRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getCard: neither card nor account supplied (null/null) -> ValidationException 'No input received' (no lookup)")
    void getCardNoInputThrowsValidation() {
        assertThatThrownBy(() -> service.getCard(null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");

        verify(cardRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getCard: zero account is treated as 'not supplied' - blank card + zero account -> 'No input received' (no lookup)")
    void getCardZeroAccountTreatedAsNotSupplied() {
        assertThatThrownBy(() -> service.getCard(0L, ""))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");

        verify(cardRepository, never()).findById(any());
    }
}
