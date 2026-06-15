package com.cardemo.unit.service.card;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.card.CardDetailService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fast, fully-mocked unit test for {@link CardDetailService} &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.x translation of the online CICS program {@code app/cbl/COCRDSLC.cbl}
 * (transaction {@code CCDL}, BMS map {@code CCRDSLA}), which edits an account number and a card
 * number and performs a <strong>single keyed read of {@code CARDDAT} by card number</strong>.
 *
 * <h2>Test strategy</h2>
 * <p>These are millisecond, in-memory, pure-Mockito unit tests: there is <em>no</em> Spring context,
 * no database, no Testcontainers and no I/O. The {@link CardRepository} collaborator is a Mockito
 * {@code @Mock} and the system under test is wired via constructor injection by {@code @InjectMocks}.
 * The class runs under {@link MockitoExtension} (default {@code STRICT_STUBS}), so validation tests
 * stub nothing and assert {@link org.mockito.Mockito#verifyNoInteractions(Object...) verifyNoInteractions}
 * to prove the early throw, while read tests stub <em>only</em> {@code findById(...)} with the exact
 * sixteen-character key the call uses (no {@code lenient()}, no {@code any()}).</p>
 *
 * <h2>The two defining parity behaviours (AAP &sect;0.7.1&ndash;&sect;0.7.2)</h2>
 * <ol>
 *   <li><strong>First-error-wins validation with verbatim {@code COCRDSLC} messages.</strong> Both
 *       inputs are required; the account edit runs before the card edit and an account message beats a
 *       card message (the COBOL {@code WS-RETURN-MSG-OFF} guard). The cross-field rule is the one
 *       exception: when <em>both</em> inputs are not supplied, {@code "No input received"}
 *       <em>unconditionally overrides</em> any per-field message. The five messages are asserted
 *       byte-for-byte, including the comma-without-following-space and the {@code 11}/{@code 16} digit
 *       counts in the {@code FILTER} messages.</li>
 *   <li><strong>The account is format-validated only &mdash; never matched.</strong> The COBOL
 *       {@code 9100-GETCARD-BYACCTCARD} read is keyed on the card number alone (the account-id key
 *       {@code MOVE} is commented out, and {@code 9150-GETCARD-BYACCT} is dead code). A card whose
 *       owning account differs from the supplied account is therefore <em>still returned</em>; the
 *       parity test {@link #getCardDetail_cardAccountDiffersFromInput_stillReturned_parity()} is the
 *       guardrail against a well-meaning but incorrect "account must match" check.</li>
 * </ol>
 *
 * <p>Golden values are taken from the first record of {@code app/data/ASCII/carddata.txt}: card number
 * {@code 0500024453765740}, owning account {@code 50}, embossed name {@code "Aniya Von"}, expiry
 * {@code 2023-03-09}, active status {@code "Y"}. The COBOL is read-only reference at the frozen
 * baseline commit SHA {@code 27d6c6f} and is never copied into this repository &mdash; only its
 * observable contract is asserted.</p>
 *
 * @see CardDetailService
 * @see CardRepository
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardDetailService — COCRDSLC single keyed card read (card-number-only)")
class CardDetailServiceTest {

    /** The 16-character golden card number (carddata.txt line 1, {@code CARD-NUM}). */
    private static final String GOLDEN_CARD_NUMBER = "0500024453765740";

    /** The 11-digit zero-padded form of the golden owning account ({@code %011d} of 50). */
    private static final String GOLDEN_ACCOUNT_ID = "00000000050";

    @Mock
    private CardRepository cardRepository;

    @InjectMocks
    private CardDetailService service;

    /**
     * Builds an in-memory {@link Card} from golden-style field values, matching the
     * {@link CardRepository#findById(Object)} result the service consumes.
     *
     * <p>Note the entity contract verified against {@code model/entity/Card.java}: the CVV is an
     * {@link Integer} and the expiration date is a {@link LocalDate} (rendered to the canonical
     * {@code YYYY-MM-DD} text by the service). The {@code expiry} argument is supplied as an
     * ISO {@code YYYY-MM-DD} string for readability and parsed to a {@link LocalDate} here.</p>
     *
     * @param num    the 16-character card number ({@code CARD-NUM}, primary key)
     * @param acctId the owning account id ({@code CARD-ACCT-ID}); mapped to {@link Long}
     * @param name   the embossed cardholder name ({@code CARD-EMBOSSED-NAME})
     * @param expiry the expiration date as ISO {@code YYYY-MM-DD} text ({@code CARD-EXPIRAION-DATE})
     * @param status the single-character active-status flag ({@code CARD-ACTIVE-STATUS})
     * @return a populated {@link Card} test fixture
     */
    private static Card card(String num, long acctId, String name, String expiry, String status) {
        Card c = new Card();
        c.setCardNum(num);
        c.setCardAcctId(acctId);
        c.setCardEmbossedName(name);
        c.setCardExpirationDate(LocalDate.parse(expiry)); // "YYYY-MM-DD" (ISO_LOCAL_DATE)
        c.setCardActiveStatus(status);
        c.setCardCvvCd(747);
        return c;
    }

    // -------------------------------------------------------------------------------------------
    // Happy path — mapping, expiry split and %011d account zero-pad (COCRDSLC 1200-SETUP-SCREEN-VARS)
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("valid account + present card → mapped DTO with split expiry and %011d account")
    void getCardDetail_validAndPresent_returnsMappedDtoWithSplitExpiry() {
        when(cardRepository.findById(GOLDEN_CARD_NUMBER))
                .thenReturn(Optional.of(card(GOLDEN_CARD_NUMBER, 50L, "Aniya Von", "2023-03-09", "Y")));

        CardDto dto = service.getCardDetail(GOLDEN_ACCOUNT_ID, GOLDEN_CARD_NUMBER);

        assertThat(dto.getCardNumber()).isEqualTo(GOLDEN_CARD_NUMBER);
        assertThat(dto.getAccountId()).isEqualTo("00000000050"); // %011d of 50L
        assertThat(dto.getEmbossedName()).isEqualTo("Aniya Von");
        assertThat(dto.getActiveStatus()).isEqualTo("Y");
        assertThat(dto.getExpiryYear()).isEqualTo("2023");
        assertThat(dto.getExpiryMonth()).isEqualTo("03");
        assertThat(dto.getExpiryDay()).isEqualTo("09");
        verify(cardRepository).findById(GOLDEN_CARD_NUMBER);
    }

    // -------------------------------------------------------------------------------------------
    // PARITY — account is NOT a filter; the read keys on card number ONLY (COCRDSLC 9100)
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("card whose account differs from input is STILL returned (key on card number only) [parity]")
    void getCardDetail_cardAccountDiffersFromInput_stillReturned_parity() {
        // Card belongs to account 999, but the caller passes account 50 — COBOL keys on card number only.
        when(cardRepository.findById(GOLDEN_CARD_NUMBER))
                .thenReturn(Optional.of(card(GOLDEN_CARD_NUMBER, 999L, "Aniya Von", "2023-03-09", "Y")));

        CardDto dto = service.getCardDetail(GOLDEN_ACCOUNT_ID, GOLDEN_CARD_NUMBER);

        assertThat(dto.getCardNumber()).isEqualTo(GOLDEN_CARD_NUMBER); // returned despite acct mismatch
        assertThat(dto.getAccountId()).isEqualTo("00000000999");       // reflects the CARD's account, %011d of 999
        verify(cardRepository).findById(GOLDEN_CARD_NUMBER);           // exact 16-char key, no acct in the call
    }

    // -------------------------------------------------------------------------------------------
    // Not found — NOTFND on the keyed card read (COCRDSLC DID-NOT-FIND-ACCTCARD-COMBO)
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("no card for the card number → RecordNotFoundException (verbatim message)")
    void getCardDetail_notFound_throwsRecordNotFound() {
        when(cardRepository.findById("9999999999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCardDetail(GOLDEN_ACCOUNT_ID, "9999999999999999"))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find cards for this search condition");
    }

    // -------------------------------------------------------------------------------------------
    // Validation — verbatim messages, first-error-wins, NO repository interaction (throws before read)
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("account blank → 'Account number not provided'; no DB read")
    void getCardDetail_accountBlank_throwsAccountNotProvided() {
        assertThatThrownBy(() -> service.getCardDetail("   ", GOLDEN_CARD_NUMBER))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number not provided");
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("account non-numeric → 11-digit ACCOUNT FILTER message; no DB read")
    void getCardDetail_accountNonNumeric_throwsAccount11Digit() {
        assertThatThrownBy(() -> service.getCardDetail("12A45678901", GOLDEN_CARD_NUMBER))
                .isInstanceOf(ValidationException.class)
                .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("card blank (account valid) → 'Card number not provided'; no DB read")
    void getCardDetail_cardBlank_accountValid_throwsCardNotProvided() {
        assertThatThrownBy(() -> service.getCardDetail(GOLDEN_ACCOUNT_ID, "  "))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card number not provided");
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("card non-numeric (account valid) → 16-digit CARD ID FILTER message; no DB read")
    void getCardDetail_cardNonNumeric_accountValid_throwsCard16Digit() {
        assertThatThrownBy(() -> service.getCardDetail(GOLDEN_ACCOUNT_ID, "12A4567890123456"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("both blank → unconditional 'No input received' override; no DB read")
    void getCardDetail_bothBlank_throwsNoInputReceived() {
        assertThatThrownBy(() -> service.getCardDetail("", ""))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("both null → 'No input received' (null treated as not supplied); no DB read")
    void getCardDetail_bothNull_throwsNoInputReceived() {
        assertThatThrownBy(() -> service.getCardDetail(null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No input received");
        verifyNoInteractions(cardRepository);
    }

    @Test
    @DisplayName("account blank but card supplied (non-numeric) → account message wins; override does NOT fire")
    void getCardDetail_accountBlankAndCardNonNumeric_accountMessageWins() {
        // Card "12A4567890123456" is supplied (not blank), so the both-blank override is NOT triggered;
        // account-then-card precedence (first-error-wins) selects the account-not-provided message.
        assertThatThrownBy(() -> service.getCardDetail("", "12A4567890123456"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number not provided");
        verifyNoInteractions(cardRepository);
    }
}
