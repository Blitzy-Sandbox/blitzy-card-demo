package com.cardemo.unit.service.card;

import com.cardemo.exception.ConcurrentModificationException; // com.cardemo.exception — NEVER java.util.ConcurrentModificationException
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.card.CardUpdateService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fast, fully-mocked unit test for {@link CardUpdateService} &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.x translation of the online CICS program {@code app/cbl/COCRDUPC.cbl}
 * (transaction {@code CCUP}, BMS map {@code CCRDUPA}), the card-update program with optimistic
 * concurrency.
 *
 * <h2>Test strategy</h2>
 * <p>These are millisecond, in-memory, pure-Mockito unit tests: there is <em>no</em> Spring context,
 * no database, no Testcontainers and no I/O. The {@link CardRepository} collaborator is a Mockito
 * {@code @Mock} and the system under test is wired via constructor injection by {@code @InjectMocks}.
 * The class runs under {@link MockitoExtension} (default {@code STRICT_STUBS}): persistence/read tests
 * stub {@code findById(...)} (and {@code saveAndFlush(...)} where the path reaches persistence) with the
 * exact key the call uses, while pure-validation tests stub <em>nothing</em> (the service edits the
 * inputs <strong>before</strong> any read) and assert {@code saveAndFlush} is never invoked.</p>
 *
 * <h2>The four defining parity behaviours (COCRDUPC &mdash; AAP &sect;0.7.1&ndash;&sect;0.7.5)</h2>
 * <ol>
 *   <li><strong>First-error-wins validation with verbatim {@code COCRDUPC} messages.</strong> The six
 *       field edits run in the fixed order account&nbsp;&rarr;&nbsp;card&nbsp;&rarr;&nbsp;name&nbsp;
 *       &rarr;&nbsp;status&nbsp;&rarr;&nbsp;month&nbsp;&rarr;&nbsp;year, and only the first failing
 *       edit's message is surfaced ({@code WS-RETURN-MSG-OFF} guard). Each message is asserted
 *       byte-for-byte, including the comma-without-following-space and the {@code 11}/{@code 16} digit
 *       counts in the {@code FILTER} messages, the uppercase-only {@code 'Y'}/{@code 'N'} status rule
 *       (lowercase {@code 'y'} is rejected), the month {@code 1..12} window and the year
 *       {@code 1950..2099} window.</li>
 *   <li><strong>No-change detection ({@code NO-CHANGES-DETECTED}, L188/L681).</strong> When the
 *       submitted editable values match the fetched record &mdash; name compared case-insensitively
 *       after trimming, status case-insensitively, and month/year numerically so {@code "03"} equals a
 *       stored month of {@code 3} &mdash; no write is performed, avoiding a needless {@code @Version}
 *       bump. The test asserts {@code saveAndFlush} is never called.</li>
 *   <li><strong>Editable-surface fidelity (COCRDUPC {@code 9200} record build).</strong> Only the
 *       embossed name, active status and expiry month/year are user-editable. The expiry <em>day</em> is
 *       carried from the stored record and the <em>CVV</em> is never written; the {@link ArgumentCaptor}
 *       assertions lock both invariants down.</li>
 *   <li><strong>Optimistic-lock conflict &rarr; the project's own exception.</strong> A JPA
 *       {@code @Version} mismatch surfaces from {@code saveAndFlush} as an
 *       {@link ObjectOptimisticLockingFailureException}, which the service translates to
 *       {@link ConcurrentModificationException} (the explicitly-imported
 *       {@code com.cardemo.exception} type &mdash; <strong>never</strong> the JDK
 *       {@code java.util.ConcurrentModificationException}) with the verbatim message
 *       {@code "Record changed by some one else. Please review"} and the original exception chained as
 *       the cause. This is the headline parity check of the suite.</li>
 * </ol>
 *
 * <p>Golden values are taken from the first record of {@code app/data/ASCII/carddata.txt}: card number
 * {@code 0500024453765740}, owning account {@code 50}, embossed name {@code "Aniya Von"}, expiry
 * {@code 2023-03-09}, active status {@code "Y"}, CVV {@code 747}. The COBOL is read-only reference at
 * the frozen baseline commit SHA {@code 27d6c6f} and is never copied into this repository &mdash; only
 * its observable contract is asserted.</p>
 *
 * @see CardUpdateService
 * @see CardRepository
 * @see ConcurrentModificationException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardUpdateService — COCRDUPC card update (validate → read → no-change → apply → @Version save)")
class CardUpdateServiceTest {

    /** The 16-character golden card number (carddata.txt line 1, {@code CARD-NUM}). */
    private static final String CARD = "0500024453765740";

    /** The 11-digit zero-padded golden account input ({@code %011d} of 50); valid throughout. */
    private static final String ACCOUNT = "00000000050";

    @Mock
    private CardRepository cardRepository;

    @InjectMocks
    private CardUpdateService service;

    /**
     * Builds an in-memory {@link Card} matching the {@link CardRepository#findById(Object)} result the
     * service consumes.
     *
     * <p>Entity contract verified against {@code model/entity/Card.java}: the CVV is an {@link Integer}
     * (carried unchanged by the update) and the expiration date is a {@link LocalDate}. The
     * {@code expiry} argument is supplied as ISO {@code YYYY-MM-DD} text for readability and parsed to a
     * {@link LocalDate} here.</p>
     *
     * @param name   the embossed cardholder name ({@code CARD-EMBOSSED-NAME})
     * @param status the single-character active-status flag ({@code CARD-ACTIVE-STATUS})
     * @param expiry the expiration date as ISO {@code YYYY-MM-DD} text ({@code CARD-EXPIRAION-DATE})
     * @return a populated {@link Card} test fixture (card number, account 50 and CVV 747 are fixed)
     */
    private static Card existing(String name, String status, String expiry) {
        Card c = new Card();
        c.setCardNum(CARD);
        c.setCardAcctId(50L);
        c.setCardEmbossedName(name);
        c.setCardActiveStatus(status);
        c.setCardExpirationDate(LocalDate.parse(expiry)); // "YYYY-MM-DD" (ISO_LOCAL_DATE) -> LocalDate
        c.setCardCvvCd(747);                              // Integer CVV; must remain untouched by update
        c.setVersion(0L);
        return c;
    }

    /**
     * Builds the user-edited request payload.
     *
     * <p>DTO contract verified against {@code model/dto/CardDto.java}: the editable card fields are
     * {@code embossedName} and {@code activeStatus} (not {@code nameOnCard}/{@code cardStatus}), and the
     * expiry month/year are raw {@link String} components. No {@code version} is set, so the service's
     * client-supplied stale-form check is inert and the JPA {@code @Version} column carries the
     * concurrency contract.</p>
     *
     * @param name   the new embossed name ({@code CCUP-NEW-CRDNAME})
     * @param status the new active-status flag ({@code CCUP-NEW-CRDSTCD})
     * @param month  the new expiry month component ({@code CCUP-NEW-EXPMON})
     * @param year   the new expiry year component ({@code CCUP-NEW-EXPYEAR})
     * @return a populated {@link CardDto} request
     */
    private static CardDto request(String name, String status, String month, String year) {
        CardDto d = new CardDto();
        d.setEmbossedName(name);   // CardDto field is embossedName (NOT nameOnCard)
        d.setActiveStatus(status); // CardDto field is activeStatus (NOT cardStatus)
        d.setExpiryMonth(month);
        d.setExpiryYear(year);
        return d;
    }

    // ---------------------------------------------------------------------------------------------
    // Happy path — real change persists exactly once; expiry DAY preserved, CVV untouched (COCRDUPC 9200)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("real change → saveAndFlush once; new name/status/expiry applied, expiry DAY preserved, CVV untouched")
    void updateCard_realChange_savesOnce_dayPreserved_cvvUntouched() {
        Card stored = existing("Aniya Von", "Y", "2023-03-09"); // stored day = 09
        when(cardRepository.findById(CARD)).thenReturn(Optional.of(stored));
        when(cardRepository.saveAndFlush(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        CardDto req = request("Jane Smith", "N", "11", "2030");
        CardDto dto = service.updateCard(ACCOUNT, CARD, req);

        ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).saveAndFlush(captor.capture());
        Card saved = captor.getValue();
        assertThat(saved.getCardEmbossedName()).isEqualTo("Jane Smith");
        assertThat(saved.getCardActiveStatus()).isEqualTo("N");
        assertThat(saved.getCardExpirationDate()).isEqualTo(LocalDate.of(2030, 11, 9)); // new yyyy-mm, DAY 09 preserved
        assertThat(saved.getCardCvvCd()).isEqualTo(747);                                // CVV untouched (Integer)

        // Response DTO reflects the update (mapping identical in shape to CardDetailService).
        assertThat(dto.getEmbossedName()).isEqualTo("Jane Smith");
        assertThat(dto.getActiveStatus()).isEqualTo("N");
        assertThat(dto.getAccountId()).isEqualTo("00000000050");                        // %011d of 50
        assertThat(dto.getExpiryYear()).isEqualTo("2030");
        assertThat(dto.getExpiryMonth()).isEqualTo("11");
        assertThat(dto.getExpiryDay()).isEqualTo("09");
    }

    // ---------------------------------------------------------------------------------------------
    // Expiry assembly — single-digit month zero-padded; stored day carried over (COCRDUPC 9200 STRING)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("single-digit month '3' → expiry month zero-padded to '03', stored day 15 preserved (2027-03-15)")
    void updateCard_singleDigitMonth_assemblesZeroPaddedExpiry_dayFromExisting() {
        Card stored = existing("Aniya Von", "Y", "2025-08-15"); // stored day = 15
        when(cardRepository.findById(CARD)).thenReturn(Optional.of(stored));
        when(cardRepository.saveAndFlush(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        CardDto req = request("Aniya Von", "N", "3", "2027"); // status change forces a real update; month "3" -> 03
        service.updateCard(ACCOUNT, CARD, req);

        ArgumentCaptor<Card> captor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCardExpirationDate())
                .isEqualTo(LocalDate.of(2027, 3, 15)); // month zero-padded, stored day 15 kept
    }

    // ---------------------------------------------------------------------------------------------
    // Not found — NOTFND on the keyed card read (COCRDUPC DID-NOT-FIND-ACCTCARD-COMBO); never writes
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("card number absent → RecordNotFoundException (verbatim); never saves")
    void updateCard_cardAbsent_throwsRecordNotFound() {
        when(cardRepository.findById(CARD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane Smith", "Y", "11", "2030")))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Did not find cards for this search condition");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    // ---------------------------------------------------------------------------------------------
    // No-change detection — case-insensitive trimmed name, numeric month ("03"==3); NO write (no @Version bump)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("no change (name differs only by case/spaces; month '03' == stored 3) → never saves")
    void updateCard_noChange_doesNotSave() {
        Card stored = existing("Aniya Von", "Y", "2023-03-09"); // stored month component = 03
        when(cardRepository.findById(CARD)).thenReturn(Optional.of(stored));

        // Name differs only by case + surrounding spaces; month "03" equals stored 3; year & status identical.
        CardDto req = request("  aniya von  ", "Y", "03", "2023");
        CardDto dto = service.updateCard(ACCOUNT, CARD, req);

        verify(cardRepository, never()).saveAndFlush(any(Card.class)); // no @Version bump
        assertThat(dto.getCardNumber()).isEqualTo(CARD);               // current entity still mapped & returned
        assertThat(dto.getActiveStatus()).isEqualTo("Y");
    }

    @Test
    @DisplayName("no change with single-digit month '3' (numeric-equal to stored 3) → never saves")
    void updateCard_noChange_singleDigitMonthEqualsStored() {
        Card stored = existing("Aniya Von", "Y", "2023-03-09");
        when(cardRepository.findById(CARD)).thenReturn(Optional.of(stored));

        // Single-digit month "3" is numerically equal to the stored month 3; name/status/year unchanged.
        service.updateCard(ACCOUNT, CARD, request("Aniya Von", "Y", "3", "2023"));

        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    // ---------------------------------------------------------------------------------------------
    // Optimistic-lock conflict — @Version mismatch from saveAndFlush -> com.cardemo ConcurrentModificationException.
    // HEADLINE PARITY CHECK: the explicit com.cardemo.exception import defeats the java.util name clash; the
    // service rethrows with the verbatim message and the original framework exception chained as the cause.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("@Version conflict on save → com.cardemo ConcurrentModificationException (verbatim message + cause)")
    void updateCard_optimisticLockConflict_throwsCardemoConcurrentModification() {
        Card stored = existing("Aniya Von", "Y", "2023-03-09");
        when(cardRepository.findById(CARD)).thenReturn(Optional.of(stored));
        ObjectOptimisticLockingFailureException boom =
                new ObjectOptimisticLockingFailureException("conflict", new RuntimeException("v-mismatch"));
        when(cardRepository.saveAndFlush(any(Card.class))).thenThrow(boom);

        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane Smith", "N", "11", "2030")))
                .isInstanceOf(ConcurrentModificationException.class) // bound to com.cardemo.exception import
                .hasMessage("Record changed by some one else. Please review")
                .hasCause(boom);                                     // original framework exception chained as cause
    }

    // ---------------------------------------------------------------------------------------------
    // Validation — verbatim COCRDUPC messages, first-error-wins, in the fixed order
    // account → card → name → status → month → year. Validation runs BEFORE the read, so these tests
    // stub NOTHING (STRICT_STUBS) and assert saveAndFlush is never invoked.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("account blank → 'Account number not provided'; never saves")
    void updateCard_accountBlank_throwsAccountNotProvided() {
        assertThatThrownBy(() -> service.updateCard("   ", CARD, request("Jane Smith", "Y", "11", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account number not provided");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("account non-numeric → 11-digit ACCOUNT FILTER message; never saves")
    void updateCard_accountNonNumeric_throwsAccount11Digit() {
        assertThatThrownBy(() -> service.updateCard("12A45678901", CARD, request("Jane Smith", "Y", "11", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("card blank (account valid) → 'Card number not provided'; never saves")
    void updateCard_cardBlank_accountValid_throwsCardNotProvided() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, "  ", request("Jane Smith", "Y", "11", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card number not provided");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("CWE-20 null-body guard: null request (account+card valid) → 'Card name not provided'; never saves")
    void updateCard_nullRequestBody_throwsCardNameNotProvided() {
        // A JSON `null` body passes the path-driven account/card edits (which run first, preserving COBOL
        // first-error order) and fails at the first body-field (name) edit with the verbatim first-error
        // (HTTP 400) instead of NPEing into a generic 500.
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card name not provided");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("card non-numeric (account valid) → 16-digit CARD ID FILTER message; never saves")
    void updateCard_cardNonNumeric_accountValid_throwsCard16Digit() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, "12A4567890123456", request("Jane Smith", "Y", "11", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("name blank → 'Card name not provided'; never saves")
    void updateCard_nameBlank_throwsCardNameNotProvided() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request(" ", "Y", "11", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card name not provided");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("name with digits → 'Card name can only contain alphabets and spaces'; never saves")
    void updateCard_nameWithDigits_throwsAlphaSpacesOnly() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane2 Smith", "Y", "11", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card name can only contain alphabets and spaces");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("status lowercase 'y' → rejected (uppercase-only parity); never saves")
    void updateCard_statusLowercaseY_rejected() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane Smith", "y", "11", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card Active Status must be Y or N");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("status invalid letter 'X' → 'Card Active Status must be Y or N'; never saves")
    void updateCard_statusInvalidLetter_rejected() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane Smith", "X", "11", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card Active Status must be Y or N");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("month '0' → 'Card expiry month must be between 1 and 12'; never saves")
    void updateCard_monthZero_throwsMonthRange() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane Smith", "Y", "0", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card expiry month must be between 1 and 12");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("month '13' → month-range message; never saves")
    void updateCard_monthThirteen_throwsMonthRange() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane Smith", "Y", "13", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card expiry month must be between 1 and 12");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("month blank → month-range message; never saves")
    void updateCard_monthBlank_throwsMonthRange() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane Smith", "Y", " ", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card expiry month must be between 1 and 12");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("month non-numeric 'AB' → month-range message; never saves")
    void updateCard_monthNonNumeric_throwsMonthRange() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane Smith", "Y", "AB", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card expiry month must be between 1 and 12");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("year '1949' (below 1950) → 'Invalid card expiry year'; never saves")
    void updateCard_yearBelow1950_throwsInvalidYear() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane Smith", "Y", "11", "1949")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid card expiry year");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("year '2100' (above 2099) → 'Invalid card expiry year'; never saves")
    void updateCard_yearAbove2099_throwsInvalidYear() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane Smith", "Y", "11", "2100")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid card expiry year");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("year blank → 'Invalid card expiry year'; never saves")
    void updateCard_yearBlank_throwsInvalidYear() {
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request("Jane Smith", "Y", "11", " ")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid card expiry year");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }

    @Test
    @DisplayName("first-error-wins: blank name + bad month → name message (name precedes month); never saves")
    void updateCard_firstErrorWins_blankNameAndBadMonth_nameMessage() {
        // Blank name AND out-of-range month "13": the name edit runs first, so its message must surface.
        assertThatThrownBy(() -> service.updateCard(ACCOUNT, CARD, request(" ", "Y", "13", "2030")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Card name not provided");
        verify(cardRepository, never()).saveAndFlush(any(Card.class));
    }
}
