package com.cardemo.unit.service;

import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.card.CardListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CardListService} — migrated from COCRDLIC.cbl (1,459 lines)
 * paginated card browse with 7 rows per page (WS-MAX-SCREEN-LINES VALUE 7), ascending
 * sort by card number (VSAM KSDS STARTBR + READNEXT), account/card filtering via
 * CXACAIX alternate index resolution, and Card entity to CardDto field mapping.
 *
 * <p>Uses JUnit 5 + Mockito (NO Spring context loading). Verifies:
 * <ul>
 *   <li>PAGE_SIZE = 7 (CRITICAL — matching COBOL BMS screen 7 rows per COCRDLIC.cbl)</li>
 *   <li>Sort.by("cardNum").ascending() — preserving VSAM KSDS key sequence</li>
 *   <li>Account ID filter via findByCardAcctId; Card number filter via stream startsWith</li>
 *   <li>Combined filter precedence — account filter takes lookup priority (COBOL logic)</li>
 *   <li>listCardsByAccount() via CardCrossReferenceRepository.findByXrefAcctId() (CXACAIX)</li>
 *   <li>All 6 Card entity fields correctly mapped to CardDto</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CardListServiceTest {

    /**
     * Expected page size matching COBOL COCRDLIC.cbl BMS screen rows.
     * COCRDLIC uses WS-MAX-SCREEN-LINES VALUE 7 to populate up to 7
     * card rows on the 3270 terminal display.
     */
    private static final int PAGE_SIZE = 7;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @InjectMocks
    private CardListService cardListService;

    private Card testCard;
    private CardCrossReference testCrossRef;
    private LocalDate testExpDate;

    /**
     * Initializes a fully populated Card entity fixture with all 6 data fields
     * matching CVACT02Y.cpy CARD-RECORD layout (150 bytes), and a CardCrossReference
     * fixture matching CVACT03Y.cpy (50 bytes). Uses no-arg Card constructor with
     * setters per schema requirement. No float/double — all precision via exact types.
     */
    @BeforeEach
    void setUp() {
        testExpDate = LocalDate.of(2025, 12, 31);

        // Card entity fixture using no-arg constructor + setters (CVACT02Y.cpy mapping)
        testCard = new Card();
        testCard.setCardNum("4111111111111111");       // PIC X(16)
        testCard.setCardAcctId("00000000001");          // PIC 9(11)
        testCard.setCardCvvCd("123");                   // PIC X(3)
        testCard.setCardEmbossedName("JOHN DOE");       // PIC X(50)
        testCard.setCardExpDate(testExpDate);            // PIC X(10) -> LocalDate
        testCard.setCardActiveStatus("Y");              // PIC X(1) 'Y'/'N'

        // Cross-reference fixture using all-args constructor (CVACT03Y.cpy mapping)
        testCrossRef = new CardCrossReference(
                "4111111111111111",  // xrefCardNum  — PIC X(16)
                "000000001",         // xrefCustId   — PIC 9(9)
                "00000000001"        // xrefAcctId   — PIC 9(11)
        );
    }

    // -------------------------------------------------------------------------
    // PAGE_SIZE Constant — CRITICAL: must be exactly 7
    // -------------------------------------------------------------------------

    /**
     * CRITICAL: Verifies that the service uses page size 7, matching the COBOL
     * COCRDLIC.cbl BMS screen definition where WS-MAX-SCREEN-LINES VALUE 7
     * defines 7 card rows per 3270 terminal screen page.
     */
    @Test
    void testListCards_pageSize7() {
        // Arrange — stub repository to return a single-item page
        Pageable expectedPageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum").ascending());
        Page<Card> mockPage = new PageImpl<>(List.of(testCard), expectedPageable, 1);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        cardListService.listCards(0, null, null);

        // Assert — capture Pageable argument and verify page size is exactly 7
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(7);
    }

    // -------------------------------------------------------------------------
    // Pagination
    // -------------------------------------------------------------------------

    /**
     * Verifies that requesting page 0 returns a non-null Page of CardDto
     * with the expected content size and total element count.
     */
    @Test
    void testListCards_firstPage_returnsPage() {
        // Arrange
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum").ascending());
        Page<Card> mockPage = new PageImpl<>(List.of(testCard), pageable, 1);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        Page<CardDto> result = cardListService.listCards(0, null, null);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    /**
     * Verifies that when no cards exist, the service returns an empty page
     * without throwing exceptions — matching COBOL COCRDLIC behavior where an
     * empty STARTBR result simply displays an empty screen.
     */
    @Test
    void testListCards_emptyResult_returnsEmptyPage() {
        // Arrange
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum").ascending());
        Page<Card> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);

        // Act
        Page<CardDto> result = cardListService.listCards(0, null, null);

        // Assert — empty page, no exception
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
    }

    /**
     * Verifies that the service requests Sort.by("cardNum").ascending(), preserving
     * the VSAM KSDS ascending key sequence used by COBOL STARTBR + READNEXT in
     * COCRDLIC.cbl 9000-READ-FORWARD paragraph.
     */
    @Test
    void testListCards_sortByCardNumAscending() {
        // Arrange
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum").ascending());
        Page<Card> mockPage = new PageImpl<>(List.of(testCard), pageable, 1);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        cardListService.listCards(0, null, null);

        // Assert — capture Pageable and verify ascending sort by cardNum
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findAll(pageableCaptor.capture());
        Sort sort = pageableCaptor.getValue().getSort();
        Sort.Order order = sort.getOrderFor("cardNum");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    // -------------------------------------------------------------------------
    // Account ID Filtering
    // -------------------------------------------------------------------------

    /**
     * Verifies that a non-null, non-blank acctIdFilter triggers findByCardAcctId()
     * instead of findAll(Pageable) — mapping the COBOL COCRDLIC STARTBR with
     * account-based positioning for the CXACAIX alternate index browse.
     */
    @Test
    void testListCards_withAcctIdFilter_filtersCorrectly() {
        // Arrange
        String acctId = "00000000001";
        List<Card> cards = List.of(testCard);
        when(cardRepository.findByCardAcctId(acctId)).thenReturn(cards);

        // Act
        Page<CardDto> result = cardListService.listCards(0, acctId, null);

        // Assert — filtered query invoked, findAll(Pageable) NOT invoked
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getCardAcctId()).isEqualTo(acctId);
        verify(cardRepository).findByCardAcctId(acctId);
        verify(cardRepository, never()).findAll(any(Pageable.class));
    }

    /**
     * Verifies that listCardsByAccount(acctId, page) resolves cross-references
     * via CardCrossReferenceRepository.findByXrefAcctId() (CXACAIX alternate index)
     * and then retrieves cards via CardRepository.findByCardAcctId().
     * Both CardCrossReference constructors are exercised to verify JPA entity behavior.
     */
    @Test
    void testListCardsByAccount_success() {
        // Arrange
        String acctId = "00000000001";

        // Verify cross-reference entity accessors (CVACT03Y.cpy -> CardCrossReference)
        assertThat(testCrossRef.getXrefCardNum()).isEqualTo("4111111111111111");
        assertThat(testCrossRef.getXrefAcctId()).isEqualTo(acctId);

        // Verify no-arg constructor creates valid JPA entity proxy instance
        CardCrossReference defaultCrossRef = new CardCrossReference();
        assertThat(defaultCrossRef).isNotNull();

        when(cardCrossReferenceRepository.findByXrefAcctId(acctId)).thenReturn(List.of(testCrossRef));
        when(cardRepository.findByCardAcctId(acctId)).thenReturn(List.of(testCard));

        // Act
        Page<CardDto> result = cardListService.listCardsByAccount(acctId, 0);

        // Assert — cross-reference resolved and cards returned
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getCardNum()).isEqualTo("4111111111111111");
        verify(cardCrossReferenceRepository).findByXrefAcctId(acctId);
        verify(cardRepository).findByCardAcctId(acctId);
    }

    // -------------------------------------------------------------------------
    // Card Number Filtering
    // -------------------------------------------------------------------------

    /**
     * Verifies that a non-null, non-blank cardNumFilter applies an exact-match
     * filter on all cards when no account filter is present — the service
     * loads up to MAX_FILTER_RESULTS cards and applies client-side exact-match
     * filtering matching COCRDLIC 9500-FILTER-RECORDS: CARD-NUM = CC-CARD-NUM-N.
     */
    @Test
    void testListCards_withCardNumFilter_filtersCorrectly() {
        // Arrange — create cards: one matching exact card number, one not
        Card matchingCard = new Card(
                "4111111111111111", "00000000001", "123",
                "JOHN DOE", testExpDate, "Y");
        Card nonMatchingCard = new Card(
                "5222222222222222", "00000000002", "456",
                "JANE DOE", testExpDate, "Y");

        List<Card> allCards = List.of(matchingCard, nonMatchingCard);
        Page<Card> mockPage = new PageImpl<>(allCards,
                PageRequest.of(0, 1000, Sort.by("cardNum").ascending()), 2);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act — exact card number filter (COBOL exact match, not prefix)
        Page<CardDto> result = cardListService.listCards(0, null, "4111111111111111");

        // Assert — only card matching exact card number is returned
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getCardNum()).isEqualTo("4111111111111111");
    }

    // -------------------------------------------------------------------------
    // Combined Filtering
    // -------------------------------------------------------------------------

    /**
     * Verifies that null filters result in findAll(Pageable) being called
     * without any filtering — equivalent to COBOL STARTBR from LOW-VALUES
     * (beginning of file) when no filter fields are entered.
     */
    @Test
    void testListCards_noFilters_returnsAll() {
        // Arrange
        Card card1 = new Card(
                "4111111111111111", "00000000001", "123",
                "JOHN DOE", testExpDate, "Y");
        Card card2 = new Card(
                "5222222222222222", "00000000002", "456",
                "JANE DOE", testExpDate, "Y");

        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum").ascending());
        Page<Card> mockPage = new PageImpl<>(List.of(card1, card2), pageable, 2);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        Page<CardDto> result = cardListService.listCards(0, null, null);

        // Assert — all cards returned, account filter NOT invoked
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(2);
        verify(cardRepository).findAll(any(Pageable.class));
        verify(cardRepository, never()).findByCardAcctId(any(String.class));
    }

    /**
     * Verifies that when both acctIdFilter and cardNumFilter are set, the account
     * filter takes precedence as the primary lookup mechanism — mapping the COBOL
     * COCRDLIC logic where account-based browse is the primary access path via
     * CXACAIX alternate index. Card number filter further refines the result set.
     */
    @Test
    void testListCards_bothFilters_acctIdTakesPrecedence() {
        // Arrange — account filter fetches candidates, card filter refines via exact match
        String acctId = "00000000001";
        String cardNumFilter = "4111111111111111";

        Card matchingCard = new Card(
                "4111111111111111", acctId, "123",
                "JOHN DOE", testExpDate, "Y");
        Card nonMatchingCard = new Card(
                "5222222222222222", acctId, "456",
                "JANE DOE", testExpDate, "Y");

        when(cardRepository.findByCardAcctId(acctId))
                .thenReturn(List.of(matchingCard, nonMatchingCard));

        // Act — both filters set; COBOL 9500-FILTER-RECORDS uses AND logic
        Page<CardDto> result = cardListService.listCards(0, acctId, cardNumFilter);

        // Assert — account filter used as primary lookup, card filter refines result
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getCardNum()).isEqualTo("4111111111111111");
        // Prove account filter takes precedence: findByCardAcctId called, findAll NOT called
        verify(cardRepository).findByCardAcctId(acctId);
        verify(cardRepository, never()).findAll(any(Pageable.class));
    }

    // -------------------------------------------------------------------------
    // DTO Mapping
    // -------------------------------------------------------------------------

    /**
     * Verifies that all 6 fields from Card entity (CVACT02Y.cpy) are correctly
     * mapped to CardDto via the service's toCardDto() helper: cardNum, cardAcctId,
     * cardEmbossedName, cardExpDate, cardActiveStatus, cardCvvCd.
     */
    @Test
    void testListCards_entityToDtoMapping() {
        // Arrange — verify source entity fields are set correctly (Card entity accessors)
        assertThat(testCard.getCardNum()).isEqualTo("4111111111111111");
        assertThat(testCard.getCardAcctId()).isEqualTo("00000000001");

        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum").ascending());
        Page<Card> mockPage = new PageImpl<>(List.of(testCard), pageable, 1);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        Page<CardDto> result = cardListService.listCards(0, null, null);

        // Assert — all 6 DTO fields match entity values
        assertThat(result.getContent()).hasSize(1);
        CardDto dto = result.getContent().get(0);
        assertThat(dto.getCardNum()).isEqualTo("4111111111111111");
        assertThat(dto.getCardAcctId()).isEqualTo("00000000001");
        assertThat(dto.getCardEmbossedName()).isEqualTo("JOHN DOE");
        assertThat(dto.getCardExpDate()).isEqualTo(testExpDate);
        assertThat(dto.getCardActiveStatus()).isEqualTo("Y");
        assertThat(dto.getCardCvvCd()).isEqualTo("123");
    }

    /**
     * Verifies that multiple Card entities are all correctly mapped to CardDto
     * objects with unique field values preserved for each card. Uses ArrayList
     * for mutable list construction and LocalDate.now() for dynamic date fixture.
     */
    @Test
    void testListCards_multipleCards() {
        // Arrange — 3 distinct Card entities with unique field values
        LocalDate futureExpDate = LocalDate.now().plusYears(1);

        Card card1 = new Card(
                "4111111111111111", "00000000001", "123",
                "JOHN DOE", testExpDate, "Y");
        Card card2 = new Card(
                "4222222222222222", "00000000002", "456",
                "JANE DOE", futureExpDate, "Y");
        Card card3 = new Card(
                "4333333333333333", "00000000003", "789",
                "BOB SMITH", LocalDate.of(2027, 3, 15), "N");

        ArrayList<Card> cardList = new ArrayList<>();
        cardList.add(card1);
        cardList.add(card2);
        cardList.add(card3);

        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by("cardNum").ascending());
        Page<Card> mockPage = new PageImpl<>(cardList, pageable, 3);
        when(cardRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        Page<CardDto> result = cardListService.listCards(0, null, null);

        // Assert — all 3 cards mapped correctly with unique values
        assertThat(result.getContent()).hasSize(3);
        assertThat(result.getContent().get(0).getCardNum()).isEqualTo("4111111111111111");
        assertThat(result.getContent().get(1).getCardNum()).isEqualTo("4222222222222222");
        assertThat(result.getContent().get(1).getCardExpDate()).isEqualTo(futureExpDate);
        assertThat(result.getContent().get(2).getCardNum()).isEqualTo("4333333333333333");
        assertThat(result.getContent().get(2).getCardActiveStatus()).isEqualTo("N");
        assertThat(result.getTotalElements()).isEqualTo(3);
    }
}
