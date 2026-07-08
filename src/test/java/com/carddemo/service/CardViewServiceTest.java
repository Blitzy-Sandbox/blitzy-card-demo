package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddemo.dto.CardViewResponse;
import com.carddemo.entity.Card;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.CardRepository;

/**
 * Pure, fast unit test for {@link CardViewService}, the single credit-card view
 * service (online transaction {@code CCDL}) migrated from the COBOL/CICS program
 * {@code COCRDSLC} ({@code app/cbl/COCRDSLC.cbl}, frozen reference SHA
 * {@code 27d6c6f} &mdash; read-only, not copied into this repository).
 *
 * <p>The sole collaborator ({@link CardRepository}) is supplied as a Mockito
 * mock and the service is created through constructor injection, so the suite
 * loads <strong>no</strong> Spring context and touches no database,
 * Testcontainers, Docker, or live AWS. Every branch of the public
 * {@link CardViewService#viewCard(String)} method is exercised, feeding the
 * JaCoCo line-coverage gate (Gate&nbsp;8).</p>
 *
 * <h2>Behavioural-parity assertions (vs. {@code COCRDSLC} @ SHA {@code 27d6c6f})</h2>
 * <ul>
 *   <li><b>{@code 2220-EDIT-CARD}</b> &mdash; the input edit that rejects a card
 *       number that is not supplied or is not a 16-digit number. Both COBOL
 *       rejection branches raise {@code 88 SEARCHED-CARD-NOT-NUMERIC}
 *       (line&nbsp;148&ndash;149), whose verbatim text is asserted here so the
 *       external message contract (Gate&nbsp;5) is preserved byte-for-byte.</li>
 *   <li><b>{@code 9100-GETCARD-BYACCTCARD}</b> &mdash; the keyed {@code CARDDAT}
 *       read whose {@code DFHRESP(NOTFND)} branch (mapped from
 *       {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF}, line&nbsp;151&ndash;152)
 *       becomes a {@link ResourceNotFoundException} carrying the verbatim legacy
 *       message.</li>
 *   <li><b>Card-data protection (AAP&nbsp;&sect;0.3.2)</b> &mdash; the Primary
 *       Account Number returned to callers is masked to the last four digits and
 *       the CVV ({@code CARD-CVV-CD}) is never emitted. Both invariants are
 *       asserted directly, the CVV check being the key security-parity test.</li>
 * </ul>
 *
 * <p>Only {@link String}, {@link Integer}, {@code long}, and {@link LocalDate}
 * values are used as fixtures; no {@code float}/{@code double} appears anywhere,
 * consistent with the migration's decimal-fidelity constraints.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardViewService — COBOL COCRDSLC / CCDL card view (SHA 27d6c6f)")
class CardViewServiceTest {

    /**
     * Verbatim rejection message from {@code 88 SEARCHED-CARD-NOT-NUMERIC}
     * ({@code COCRDSLC} line&nbsp;149). Declared as a literal (rather than
     * referencing {@code CardViewService.MSG_CARD_INVALID}) so the test fails if
     * the production text ever drifts from the legacy contract.
     */
    private static final String MSG_CARD_INVALID =
            "Card number if supplied must be a 16 digit number";

    /**
     * Verbatim not-found message from {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF}
     * ({@code COCRDSLC} line&nbsp;152); asserted independently of the production
     * constant for the same drift-detection reason.
     */
    private static final String MSG_CARD_NOT_FOUND =
            "Did not find this account in cards database";

    /** A well-formed 16-digit card number used as the happy-path lookup key. */
    private static final String VALID_PAN = "4111111111111234";

    /**
     * The expected masked PAN: every digit except the trailing four is replaced
     * with {@code '*'} while the original 16-character width is preserved.
     * Computed from {@link #VALID_PAN} so the two can never disagree.
     */
    private static final String MASKED_PAN =
            "*".repeat(VALID_PAN.length() - 4) + VALID_PAN.substring(VALID_PAN.length() - 4);

    /**
     * A CVV value deliberately set on the entity so the tests can prove it never
     * leaks into the response. The value {@code 789} does not occur as a
     * substring of any other fixture field, which makes the
     * "no response text contains the CVV" assertion robust.
     */
    private static final Integer CVV = 789;

    /** Owning account id ({@code CARD-ACCT-ID}); rendered as {@code "10000000042"}. */
    private static final long ACCT_ID = 10_000_000_042L;

    /** Embossed cardholder name ({@code CARD-EMBOSSED-NAME}). */
    private static final String EMBOSSED_NAME = "JOHN Q PUBLIC";

    /** Single-character active-status flag ({@code CARD-ACTIVE-STATUS}). */
    private static final String ACTIVE_STATUS = "Y";

    /** Card expiration date ({@code CARD-EXPIRAION-DATE}, legacy misspelling). */
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2028, 11, 30);

    /** JPA optimistic-lock version echoed back for the Card Update round-trip. */
    private static final long VERSION = 7L;

    /** Mocked card repository (migrated {@code CARDDAT} keyed access). */
    @Mock
    private CardRepository cardRepository;

    /** Class under test, with {@link #cardRepository} injected by constructor. */
    @InjectMocks
    private CardViewService service;

    /**
     * Builds a fully-populated {@link Card} fixture, including a non-null CVV, so
     * that the CVV-never-exposed assertions have a concrete secret to prove is
     * withheld. Every field the service projects is populated.
     *
     * @return a populated {@link Card} keyed by {@link #VALID_PAN}
     */
    private static Card newCard() {
        Card card = new Card();
        card.setCardNum(VALID_PAN);
        card.setCardAcctId(ACCT_ID);
        card.setCardCvvCd(CVV);
        card.setCardEmbossedName(EMBOSSED_NAME);
        card.setCardActiveStatus(ACTIVE_STATUS);
        card.setCardExpirationDate(EXPIRATION_DATE);
        card.setVersion(VERSION);
        return card;
    }

    // ------------------------------------------------------------------
    // 2220-EDIT-CARD — input edit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("viewCard: an unsupplied / non-16-digit / non-numeric card number is rejected before any read")
    void viewCard_invalidNumber_throwsValidation() {
        // Every case fails the 2220-EDIT-CARD edit: null and blank/whitespace hit
        // the "not supplied" guard; the remainder fail the exactly-16-digits rule.
        // NOTE: a 16-zero string is numeric and would pass the edit, so it is not
        // included here (the Java regex reproduces only the "16 digits" rule).
        List<String> invalidInputs = Arrays.asList(
                (String) null,          // not supplied
                "",                     // blank
                "     ",                // whitespace only
                "12345",                // fewer than 16 digits
                "12345678901234567",    // more than 16 digits
                "411111111111123X",     // 16 characters but not all digits
                "4111 1111 1111 1234"); // embedded spaces (not digits)

        for (String input : invalidInputs) {
            assertThatThrownBy(() -> service.viewCard(input))
                    .as("input <%s> must be rejected as an invalid card number", input)
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(MSG_CARD_INVALID);
        }

        // The edit must run before the keyed read, exactly as in COCRDSLC.
        verifyNoInteractions(cardRepository);
    }

    // ------------------------------------------------------------------
    // 9100-GETCARD-BYACCTCARD — keyed read, DFHRESP(NOTFND) branch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("viewCard: a well-formed card number that matches no row raises the verbatim not-found message")
    void viewCard_notFound_throwsResourceNotFound() {
        when(cardRepository.findById(VALID_PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewCard(VALID_PAN))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(MSG_CARD_NOT_FOUND);

        verify(cardRepository).findById(VALID_PAN);
    }

    // ------------------------------------------------------------------
    // Successful projection — PAN masking
    // ------------------------------------------------------------------

    @Test
    @DisplayName("viewCard: the returned PAN is masked to the last four digits and never the full card number")
    void viewCard_success_masksPan() {
        when(cardRepository.findById(VALID_PAN)).thenReturn(Optional.of(newCard()));

        CardViewResponse response = service.viewCard(VALID_PAN);

        assertThat(response.cardNumber())
                .isEqualTo(MASKED_PAN)                       // exact masked value
                .hasSize(VALID_PAN.length())                 // original width preserved
                .endsWith(VALID_PAN.substring(VALID_PAN.length() - 4)) // last four visible
                .isNotEqualTo(VALID_PAN)                      // never the raw PAN
                .doesNotContain(VALID_PAN);                  // full PAN not embedded

        // Exactly four characters remain visible; stripping the mask leaves the
        // trailing four digits and nothing more.
        assertThat(response.cardNumber().replace("*", ""))
                .isEqualTo(VALID_PAN.substring(VALID_PAN.length() - 4))
                .hasSize(4);

        // The full PAN must not appear anywhere in the serialized response.
        assertThat(response.toString()).doesNotContain(VALID_PAN);
    }

    // ------------------------------------------------------------------
    // Successful projection — CVV is never exposed (key security parity)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("viewCard: the CVV is never exposed — no cvv component on the type and no CVV value in the payload")
    void viewCard_success_neverExposesCvv() {
        when(cardRepository.findById(VALID_PAN)).thenReturn(Optional.of(newCard()));

        CardViewResponse response = service.viewCard(VALID_PAN);

        // Type-level guarantee: CardViewResponse declares no CVV record component,
        // therefore no cvv() accessor exists to call at compile time. Proven
        // reflectively over the record's canonical component list, whose exact,
        // ordered contents are pinned below.
        List<String> componentNames = Arrays.stream(CardViewResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(componentNames)
                .containsExactly("accountId", "cardNumber", "embossedName",
                        "activeStatus", "expirationDate", "version")
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("cvv"));

        // Runtime guarantee: the CVV set on the entity leaks into no response field.
        String cvvValue = String.valueOf(CVV); // "789"
        assertThat(response.toString()).doesNotContain(cvvValue);
        assertThat(response.accountId()).isNotEqualTo(cvvValue);
        assertThat(response.cardNumber()).doesNotContain(cvvValue);
        assertThat(response.embossedName()).isNotEqualTo(cvvValue);
        assertThat(response.activeStatus()).isNotEqualTo(cvvValue);
    }

    // ------------------------------------------------------------------
    // Successful projection — remaining field mapping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("viewCard: account id, embossed name, active status, expiration date and version map from the entity")
    void viewCard_success_mapsFields() {
        Card card = newCard();
        when(cardRepository.findById(VALID_PAN)).thenReturn(Optional.of(card));

        CardViewResponse response = service.viewCard(VALID_PAN);

        assertThat(response.accountId()).isEqualTo(String.valueOf(card.getCardAcctId()));
        assertThat(response.embossedName()).isEqualTo(card.getCardEmbossedName());
        assertThat(response.activeStatus()).isEqualTo(card.getCardActiveStatus());
        assertThat(response.expirationDate()).isEqualTo(card.getCardExpirationDate());
        assertThat(response.version()).isEqualTo(card.getVersion());
    }
}
