package com.cardemo.unit.service;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.card.CardDetailService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CardDetailService} — validates COCRDSLC.cbl single card
 * keyed read migrated to Java.  Tests cover input validation (null/blank card
 * number and account ID), RecordNotFoundException on not-found, successful 6-field
 * Card→CardDto mapping, account-based list retrieval, and repository invocation
 * verification.
 *
 * <p>Uses pure Mockito (no Spring context) with AssertJ assertions.</p>
 */
@ExtendWith(MockitoExtension.class)
class CardDetailServiceTest {

    /** Mock for the card repository — VSAM CARDDAT / CARDAIX access layer. */
    @Mock
    private CardRepository cardRepository;

    /** Service under test — receives mocked CardRepository via constructor injection. */
    @InjectMocks
    private CardDetailService cardDetailService;

    // ── Test fixture data ─────────────────────────────────────────────────────

    /** A well-formed 16-digit card number used across success and verification tests. */
    private static final String CARD_NUM = "4111111111111111";

    /** An 11-digit account ID used for account-based lookup tests. */
    private static final String ACCT_ID = "00000000001";

    /** A valid 3-digit CVV code. */
    private static final String CVV = "123";

    /** A valid 50-character-max embossed name. */
    private static final String EMBOSSED_NAME = "JOHN DOE";

    /** Card expiration date — December 1, 2025. */
    private static final LocalDate EXP_DATE = LocalDate.of(2025, 12, 1);

    /** Card active status — active. */
    private static final String ACTIVE_STATUS = "Y";

    /** Reusable Card entity built from the fixture constants. */
    private Card card;

    /**
     * Builds a fresh Card entity before each test, ensuring full isolation
     * between test cases.
     */
    @BeforeEach
    void setUp() {
        card = new Card();
        card.setCardNum(CARD_NUM);
        card.setCardAcctId(ACCT_ID);
        card.setCardCvvCd(CVV);
        card.setCardEmbossedName(EMBOSSED_NAME);
        card.setCardExpDate(EXP_DATE);
        card.setCardActiveStatus(ACTIVE_STATUS);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  INPUT VALIDATION — getCardDetail
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: Passing a null card number must throw an
     * {@link IllegalArgumentException} before any repository access.
     * Maps COCRDSLC.cbl guard check for blank/missing card number.
     */
    @Test
    void testGetCardDetail_nullCardNum_throwsValidationError() {
        assertThatThrownBy(() -> cardDetailService.getCardDetail(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Test 2: Passing a blank (empty) card number must throw an
     * {@link IllegalArgumentException}.
     */
    @Test
    void testGetCardDetail_blankCardNum_throwsValidationError() {
        assertThatThrownBy(() -> cardDetailService.getCardDetail(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  CARD NOT FOUND — getCardDetail
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test 3: When the repository returns an empty Optional, the service must
     * throw {@link RecordNotFoundException} — maps COBOL FILE STATUS 23 /
     * DFHRESP(NOTFND) in COCRDSLC.cbl paragraph 9100-GETCARD-BYACCTCARD.
     */
    @Test
    void testGetCardDetail_notFound_throwsRecordNotFound() {
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cardDetailService.getCardDetail(CARD_NUM))
                .isInstanceOf(RecordNotFoundException.class);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SUCCESSFUL READ — getCardDetail
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test 4: A successful keyed read returns a non-null, fully populated
     * {@link CardDto} containing all expected field values.
     */
    @Test
    void testGetCardDetail_success_returnsPopulatedCardDto() {
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));

        CardDto result = cardDetailService.getCardDetail(CARD_NUM);

        assertThat(result).isNotNull();
        assertThat(result.getCardNum()).isEqualTo(CARD_NUM);
        assertThat(result.getCardAcctId()).isEqualTo(ACCT_ID);
        assertThat(result.getCardActiveStatus()).isEqualTo(ACTIVE_STATUS);
        assertThat(result.getCardEmbossedName()).isEqualTo(EMBOSSED_NAME);
        assertThat(result.getCardExpDate()).isEqualTo(EXP_DATE);
        assertThat(result.getCardCvvCd()).isEqualTo(CVV);
    }

    /**
     * Test 5: Each of the 6 Card entity fields must map exactly to the
     * corresponding CardDto getter — no field is silently dropped or swapped.
     */
    @Test
    void testGetCardDetail_success_allFieldsMapped() {
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));

        CardDto dto = cardDetailService.getCardDetail(CARD_NUM);

        // Verify all 6 fields individually
        assertThat(dto.getCardNum()).as("cardNum").isEqualTo(card.getCardNum());
        assertThat(dto.getCardAcctId()).as("cardAcctId").isEqualTo(card.getCardAcctId());
        assertThat(dto.getCardCvvCd()).as("cardCvvCd").isEqualTo(card.getCardCvvCd());
        assertThat(dto.getCardEmbossedName()).as("cardEmbossedName")
                .isEqualTo(card.getCardEmbossedName());
        assertThat(dto.getCardExpDate()).as("cardExpDate").isEqualTo(card.getCardExpDate());
        assertThat(dto.getCardActiveStatus()).as("cardActiveStatus")
                .isEqualTo(card.getCardActiveStatus());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  GET CARDS BY ACCOUNT — getCardsByAccountId
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test 6: Passing a null account ID must throw an
     * {@link IllegalArgumentException} — maps COCRDSLC.cbl guard for missing
     * account filter.
     */
    @Test
    void testGetCardsByAccountId_nullAcctId_throwsValidationError() {
        assertThatThrownBy(() -> cardDetailService.getCardsByAccountId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Test 7: When the repository returns an empty list for the given account
     * ID, the service must throw {@link RecordNotFoundException} — maps
     * COCRDSLC.cbl paragraph 9150-GETCARD-BYACCT DFHRESP(NOTFND).
     */
    @Test
    void testGetCardsByAccountId_notFound_throwsRecordNotFound() {
        when(cardRepository.findByCardAcctId(ACCT_ID))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> cardDetailService.getCardsByAccountId(ACCT_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    /**
     * Test 8: A successful account-based lookup returns a non-empty
     * {@link List} of {@link CardDto} with correctly mapped fields.
     */
    @Test
    void testGetCardsByAccountId_success_returnsList() {
        when(cardRepository.findByCardAcctId(ACCT_ID))
                .thenReturn(List.of(card));

        List<CardDto> result = cardDetailService.getCardsByAccountId(ACCT_ID);

        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);

        CardDto dto = result.get(0);
        assertThat(dto.getCardNum()).isEqualTo(CARD_NUM);
        assertThat(dto.getCardAcctId()).isEqualTo(ACCT_ID);
        assertThat(dto.getCardActiveStatus()).isEqualTo(ACTIVE_STATUS);
    }

    /**
     * Test 9: When multiple cards belong to the same account, the service
     * returns all of them correctly mapped.
     */
    @Test
    void testGetCardsByAccountId_success_multipleCards() {
        // Build a second card with a different card number but the same account
        Card card2 = new Card();
        card2.setCardNum("5222222222222222");
        card2.setCardAcctId(ACCT_ID);
        card2.setCardCvvCd("456");
        card2.setCardEmbossedName("JANE DOE");
        card2.setCardExpDate(LocalDate.of(2026, 6, 15));
        card2.setCardActiveStatus("N");

        when(cardRepository.findByCardAcctId(ACCT_ID))
                .thenReturn(List.of(card, card2));

        List<CardDto> result = cardDetailService.getCardsByAccountId(ACCT_ID);

        assertThat(result).hasSize(2);

        // Verify first card
        assertThat(result.get(0).getCardNum()).isEqualTo(CARD_NUM);
        assertThat(result.get(0).getCardEmbossedName()).isEqualTo(EMBOSSED_NAME);

        // Verify second card
        assertThat(result.get(1).getCardNum()).isEqualTo("5222222222222222");
        assertThat(result.get(1).getCardEmbossedName()).isEqualTo("JANE DOE");
        assertThat(result.get(1).getCardActiveStatus()).isEqualTo("N");
        assertThat(result.get(1).getCardExpDate()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(result.get(1).getCardCvvCd()).isEqualTo("456");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  VERIFICATION — repository invocation
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test 10: After a successful getCardDetail call the repository's
     * {@code findById} must have been invoked exactly once with the provided
     * card number — ensuring no duplicate or missing calls.
     */
    @Test
    void testGetCardDetail_verifiesRepositoryCalled() {
        when(cardRepository.findById(CARD_NUM)).thenReturn(Optional.of(card));

        cardDetailService.getCardDetail(CARD_NUM);

        verify(cardRepository, times(1)).findById(CARD_NUM);
    }
}
