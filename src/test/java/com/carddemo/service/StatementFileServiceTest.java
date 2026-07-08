package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.carddemo.dto.StatementTransactionDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Pure Mockito unit tests for {@link StatementFileService}, the typed,
 * JPA-backed replacement for the legacy COBOL statement-file subprogram
 * {@code CBSTM03B} ({@code app/cbl/CBSTM03B.CBL}) that the statement-create
 * program {@code CBSTM03A} ({@code app/cbl/CBSTM03A.CBL}) {@code CALL}s to read
 * the XREF, CUSTOMER, ACCOUNT and TRANSACT files. The frozen COBOL source is
 * referenced (never copied) at commit SHA {@code 27d6c6f} (full HEAD
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}).
 *
 * <p>The five repository collaborators are supplied as Mockito {@code @Mock}s and
 * injected through the service's single constructor by {@code @InjectMocks}, so
 * the suite loads <strong>no</strong> Spring context and touches no database,
 * Testcontainers, Docker or live AWS — satisfying the folder-wide unit-test
 * convention. Every public method is exercised, feeding the JaCoCo line-coverage
 * gate (Gate&nbsp;8).</p>
 *
 * <h2>Behavioural-parity assertions</h2>
 * <ul>
 *   <li><b>Full PAN (statement parity).</b> {@code CBSTM03A} moves
 *       {@code TRNX-CARD-NUM} into {@code WS-SAVE-CARD PIC X(16)} and writes the
 *       full sixteen-digit card number to the fixed-width statement file; no
 *       masking occurs at this layer. {@link #buildStatementLines_preservesFullPan()}
 *       pins that the DTO's {@link StatementTransactionDto#cardNumber()} carries
 *       the complete, unmasked Primary Account Number — the distinguishing
 *       contrast with the card/transaction <em>view</em> services, which mask
 *       (migration goal G4, interface-contract parity).</li>
 *   <li><b>Decimal fidelity (AAP §0.8.2).</b> {@code TRNX-AMT PIC S9(09)V99}
 *       (COMP-3) maps to {@link java.math.BigDecimal} of scale&nbsp;2; monetary
 *       assertions use {@code compareTo} plus an explicit {@code scale() == 2}
 *       check, and no {@code float}/{@code double} appears anywhere in this
 *       suite.</li>
 *   <li><b>Timestamp contract (Gates 1 &amp; 5).</b> {@code TRAN-ORIG-TS} /
 *       {@code TRAN-PROC-TS} are {@code X(26)} character fields carried across
 *       verbatim as 26-character strings.</li>
 *   <li><b>Typed dispatch parity.</b> The generic {@code CBSTM03B} DD-name
 *       dispatcher (TRNXFILE / XREFFILE / CUSTFILE / ACCTFILE) is replaced by the
 *       strongly typed accessors {@code getTransactionsForCard} /
 *       {@code getCardXrefsForAccount} / {@code getCustomer} / {@code getAccount}
 *       / {@code getCard}, each verified to delegate to the correct repository.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementFileService — CBSTM03A/CBSTM03B statement assembly (SHA 27d6c6f)")
class StatementFileServiceTest {

    /** Full, unmasked 16-digit Primary Account Number used across the suite. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The last-four masked form of {@link #CARD_NUMBER}; must NOT be produced by the service. */
    private static final String MASKED_CARD_NUMBER = "************1111";

    /** A 26-character {@code X(26)} timestamp ({@code yyyy-mm-dd-hh.mm.ss.ffffff}). */
    private static final String ORIG_TS_1 = "2024-01-15-10.30.45.123456";
    private static final String PROC_TS_1 = "2024-01-15-10.30.46.654321";
    private static final String ORIG_TS_2 = "2024-02-20-08.15.00.000001";
    private static final String PROC_TS_2 = "2024-02-20-08.15.01.000002";
    private static final String ORIG_TS_3 = "2024-03-25-23.59.59.999999";
    private static final String PROC_TS_3 = "2024-03-26-00.00.00.000000";

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private StatementFileService service;

    // ------------------------------------------------------------------
    // Fixture builders
    // ------------------------------------------------------------------

    /**
     * Builds a fully populated {@link Transaction} fixture. Every field consumed
     * by {@code StatementFileService.toStatementLine} is set so that the 1:1
     * mapping to {@link StatementTransactionDto} can be asserted end to end.
     *
     * @param cardNum      {@code TRAN-CARD-NUM} (full PAN)
     * @param tranId       {@code TRAN-ID}
     * @param typeCd       {@code TRAN-TYPE-CD}
     * @param catCd        {@code TRAN-CAT-CD} (may be {@code null})
     * @param source       {@code TRAN-SOURCE}
     * @param desc         {@code TRAN-DESC}
     * @param amt          {@code TRAN-AMT} ({@link BigDecimal}, may be {@code null})
     * @param merchantId   {@code TRAN-MERCHANT-ID} (may be {@code null})
     * @param merchantName {@code TRAN-MERCHANT-NAME}
     * @param merchantCity {@code TRAN-MERCHANT-CITY}
     * @param merchantZip  {@code TRAN-MERCHANT-ZIP}
     * @param origTs       {@code TRAN-ORIG-TS} (26 chars)
     * @param procTs       {@code TRAN-PROC-TS} (26 chars)
     * @return a populated {@link Transaction}
     */
    private static Transaction transaction(
            String cardNum, String tranId, String typeCd, Integer catCd,
            String source, String desc, BigDecimal amt, Long merchantId,
            String merchantName, String merchantCity, String merchantZip,
            String origTs, String procTs) {
        Transaction t = new Transaction();
        t.setTranCardNum(cardNum);
        t.setTranId(tranId);
        t.setTranTypeCd(typeCd);
        t.setTranCatCd(catCd);
        t.setTranSource(source);
        t.setTranDesc(desc);
        t.setTranAmt(amt);
        t.setTranMerchantId(merchantId);
        t.setTranMerchantName(merchantName);
        t.setTranMerchantCity(merchantCity);
        t.setTranMerchantZip(merchantZip);
        t.setTranOrigTs(origTs);
        t.setTranProcTs(procTs);
        return t;
    }

    /**
     * Builds a {@link CardXref} fixture (card&nbsp;&rarr;&nbsp;customer/account cross reference).
     *
     * @param cardNum {@code XREF-CARD-NUM}
     * @param custId  {@code XREF-CUST-ID}
     * @param acctId  {@code XREF-ACCT-ID}
     * @return a populated {@link CardXref}
     */
    private static CardXref xref(String cardNum, Long custId, Long acctId) {
        CardXref x = new CardXref();
        x.setXrefCardNum(cardNum);
        x.setXrefCustId(custId);
        x.setXrefAcctId(acctId);
        return x;
    }

    /**
     * Stubs {@link TransactionRepository#findByTranCardNum(String, Pageable)} for
     * {@link #CARD_NUMBER} to return a single {@link PageImpl} page wrapping the
     * supplied transactions (mirroring the unpaged read the service issues).
     *
     * @param transactions the transactions the transaction finder should return
     */
    private void stubTransactionsForCard(List<Transaction> transactions) {
        Page<Transaction> page = new PageImpl<Transaction>(transactions);
        when(transactionRepository.findByTranCardNum(eq(CARD_NUMBER), any(Pageable.class)))
                .thenReturn(page);
    }

    // ------------------------------------------------------------------
    // buildStatementLines — CBSTM03A statement-assembly loop
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildStatementLines maps each Transaction to a StatementTransactionDto, field for field, in source order")
    void buildStatementLines_mapsEachTransactionToDto() {
        Transaction t1 = transaction(CARD_NUMBER, "0000000000000001", "01", 5,
                "POS", "GROCERY STORE", new BigDecimal("12.34"), 123456789L,
                "ACME FOODS", "SEATTLE", "98101", ORIG_TS_1, PROC_TS_1);
        Transaction t2 = transaction(CARD_NUMBER, "0000000000000002", "02", 10,
                "ONLINE", "BOOK STORE", new BigDecimal("56.78"), 987654321L,
                "BOOKS INC", "PORTLAND", "97201", ORIG_TS_2, PROC_TS_2);
        Transaction t3 = transaction(CARD_NUMBER, "0000000000000003", "03", 20,
                "ATM", "CASH ADVANCE", new BigDecimal("100.00"), 555000111L,
                "FIRST BANK", "DENVER", "80202", ORIG_TS_3, PROC_TS_3);
        stubTransactionsForCard(List.of(t1, t2, t3));

        List<StatementTransactionDto> lines = service.buildStatementLines(CARD_NUMBER);

        // Size matches the number of source transactions, preserving order.
        assertThat(lines).hasSize(3)
                .extracting(StatementTransactionDto::transactionId)
                .containsExactly("0000000000000001", "0000000000000002", "0000000000000003");

        // Line 0 — every one of the thirteen components maps 1:1.
        StatementTransactionDto d1 = lines.get(0);
        assertThat(d1.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(d1.transactionId()).isEqualTo("0000000000000001");
        assertThat(d1.typeCode()).isEqualTo("01");
        assertThat(d1.categoryCode()).isEqualTo("5");
        assertThat(d1.source()).isEqualTo("POS");
        assertThat(d1.description()).isEqualTo("GROCERY STORE");
        assertThat(d1.amount()).isEqualByComparingTo("12.34");
        assertThat(d1.merchantId()).isEqualTo("123456789");
        assertThat(d1.merchantName()).isEqualTo("ACME FOODS");
        assertThat(d1.merchantCity()).isEqualTo("SEATTLE");
        assertThat(d1.merchantZip()).isEqualTo("98101");
        assertThat(d1.originalTimestamp()).isEqualTo(ORIG_TS_1);
        assertThat(d1.processedTimestamp()).isEqualTo(PROC_TS_1);

        // Line 1 — distinct values prove the mapping is per-record, not shared.
        StatementTransactionDto d2 = lines.get(1);
        assertThat(d2.typeCode()).isEqualTo("02");
        assertThat(d2.categoryCode()).isEqualTo("10");
        assertThat(d2.source()).isEqualTo("ONLINE");
        assertThat(d2.description()).isEqualTo("BOOK STORE");
        assertThat(d2.merchantId()).isEqualTo("987654321");
        assertThat(d2.merchantName()).isEqualTo("BOOKS INC");
        assertThat(d2.merchantCity()).isEqualTo("PORTLAND");
        assertThat(d2.merchantZip()).isEqualTo("97201");
        assertThat(d2.originalTimestamp()).isEqualTo(ORIG_TS_2);
        assertThat(d2.processedTimestamp()).isEqualTo(PROC_TS_2);

        verify(transactionRepository).findByTranCardNum(eq(CARD_NUMBER), any(Pageable.class));
    }

    @Test
    @DisplayName("buildStatementLines preserves the FULL, unmasked PAN on every line (statement parity, not masked)")
    void buildStatementLines_preservesFullPan() {
        Transaction t = transaction(CARD_NUMBER, "0000000000000001", "01", 5,
                "POS", "GROCERY STORE", new BigDecimal("12.34"), 123456789L,
                "ACME FOODS", "SEATTLE", "98101", ORIG_TS_1, PROC_TS_1);
        stubTransactionsForCard(List.of(t));

        List<StatementTransactionDto> lines = service.buildStatementLines(CARD_NUMBER);

        assertThat(lines).hasSize(1);
        String cardNumber = lines.get(0).cardNumber();
        // The statement layer carries the complete sixteen-digit PAN, byte-identical
        // to the fixed-width statement file — it is NOT masked here.
        assertThat(cardNumber)
                .isEqualTo(CARD_NUMBER)
                .hasSize(16)
                .doesNotContain("*")
                .isNotEqualTo(MASKED_CARD_NUMBER);
    }

    @Test
    @DisplayName("buildStatementLines yields BigDecimal amounts of scale 2 (compareTo), preserving sign and normalising input scale")
    void buildStatementLines_amountScaleTwo() {
        Transaction whole = transaction(CARD_NUMBER, "0000000000000001", "01", 1,
                "POS", "WHOLE", new BigDecimal("100"), 1L,
                "M1", "C1", "00001", ORIG_TS_1, PROC_TS_1);
        Transaction oneDp = transaction(CARD_NUMBER, "0000000000000002", "01", 1,
                "POS", "ONE-DP", new BigDecimal("50.5"), 2L,
                "M2", "C2", "00002", ORIG_TS_2, PROC_TS_2);
        Transaction negative = transaction(CARD_NUMBER, "0000000000000003", "01", 1,
                "POS", "NEGATIVE", new BigDecimal("-42.00"), 3L,
                "M3", "C3", "00003", ORIG_TS_3, PROC_TS_3);
        stubTransactionsForCard(List.of(whole, oneDp, negative));

        List<StatementTransactionDto> lines = service.buildStatementLines(CARD_NUMBER);

        // Every amount is normalised to exactly scale 2 (matching V99), never float/double.
        assertThat(lines).hasSize(3)
                .allSatisfy(line -> assertThat(line.amount().scale()).isEqualTo(2));
        assertThat(lines.get(0).amount()).isEqualByComparingTo("100.00");
        assertThat(lines.get(1).amount()).isEqualByComparingTo("50.50");
        // Sign is preserved through the round-trip.
        assertThat(lines.get(2).amount()).isEqualByComparingTo("-42.00");
        assertThat(lines.get(2).amount().signum()).isNegative();
    }

    @Test
    @DisplayName("buildStatementLines carries the 26-character X(26) timestamps across verbatim")
    void buildStatementLines_timestampLength26() {
        Transaction t = transaction(CARD_NUMBER, "0000000000000001", "01", 5,
                "POS", "GROCERY STORE", new BigDecimal("12.34"), 123456789L,
                "ACME FOODS", "SEATTLE", "98101", ORIG_TS_1, PROC_TS_1);
        stubTransactionsForCard(List.of(t));

        List<StatementTransactionDto> lines = service.buildStatementLines(CARD_NUMBER);

        assertThat(lines).hasSize(1);
        StatementTransactionDto line = lines.get(0);
        assertThat(line.originalTimestamp()).hasSize(26).isEqualTo(ORIG_TS_1);
        assertThat(line.processedTimestamp()).hasSize(26).isEqualTo(PROC_TS_1);
    }

    @Test
    @DisplayName("buildStatementLines returns an empty (non-null) list when the card has no transactions")
    void buildStatementLines_noTransactions_returnsEmptyList() {
        stubTransactionsForCard(List.of());

        List<StatementTransactionDto> lines = service.buildStatementLines(CARD_NUMBER);

        assertThat(lines).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("buildStatementLines maps null numeric fields (category, merchant id) to null strings, never the literal \"null\"")
    void buildStatementLines_nullNumericFields_mapToNullStrings() {
        Transaction t = transaction(CARD_NUMBER, "0000000000000001", "01", null,
                "POS", "NO NUMERICS", new BigDecimal("0.00"), null,
                "ACME FOODS", "SEATTLE", "98101", ORIG_TS_1, PROC_TS_1);
        stubTransactionsForCard(List.of(t));

        List<StatementTransactionDto> lines = service.buildStatementLines(CARD_NUMBER);

        assertThat(lines).hasSize(1);
        StatementTransactionDto line = lines.get(0);
        assertThat(line.categoryCode()).isNull();
        assertThat(line.merchantId()).isNull();
    }

    // ------------------------------------------------------------------
    // getTransactionsForCard — TRNXFILE sequential read (CBSTM03B DD 'TRNXFILE')
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getTransactionsForCard delegates to TransactionRepository.findByTranCardNum with an UNPAGED request and returns the page content")
    void getTransactionsForCard_delegatesUnpaged() {
        Transaction t = transaction(CARD_NUMBER, "0000000000000001", "01", 5,
                "POS", "GROCERY STORE", new BigDecimal("12.34"), 123456789L,
                "ACME FOODS", "SEATTLE", "98101", ORIG_TS_1, PROC_TS_1);
        stubTransactionsForCard(List.of(t));

        List<Transaction> result = service.getTransactionsForCard(CARD_NUMBER);

        assertThat(result).containsExactly(t);
        // Statement generation needs the complete set, so the request is unpaged.
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findByTranCardNum(eq(CARD_NUMBER), captor.capture());
        assertThat(captor.getValue().isUnpaged()).isTrue();
    }

    // ------------------------------------------------------------------
    // Typed accessors — replace the generic CBSTM03B DD-name dispatcher
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getAccount delegates to AccountRepository.findById (ACCTFILE keyed read) and returns its Optional")
    void getAccount_delegatesToRepository() {
        Account account = new Account();
        account.setAcctId(11L);
        when(accountRepository.findById(11L)).thenReturn(Optional.of(account));

        Optional<Account> result = service.getAccount(11L);

        assertThat(result).containsSame(account);
        verify(accountRepository).findById(11L);
    }

    @Test
    @DisplayName("getAccount returns Optional.empty when the account is absent")
    void getAccount_absent_returnsEmpty() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.getAccount(99L)).isEmpty();
        verify(accountRepository).findById(99L);
    }

    @Test
    @DisplayName("getCustomer delegates to CustomerRepository.findById (CUSTFILE keyed read) and returns its Optional")
    void getCustomer_delegatesToRepository() {
        Customer customer = new Customer();
        customer.setCustId(22L);
        when(customerRepository.findById(22L)).thenReturn(Optional.of(customer));

        Optional<Customer> result = service.getCustomer(22L);

        assertThat(result).containsSame(customer);
        verify(customerRepository).findById(22L);
    }

    @Test
    @DisplayName("getCard delegates to CardRepository.findById by full card number and returns its Optional")
    void getCard_delegatesToRepository() {
        Card card = new Card();
        card.setCardNum(CARD_NUMBER);
        when(cardRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(card));

        Optional<Card> result = service.getCard(CARD_NUMBER);

        assertThat(result).containsSame(card);
        verify(cardRepository).findById(CARD_NUMBER);
    }

    @Test
    @DisplayName("getCardXrefsForAccount delegates to CardXrefRepository.findByXrefAcctId (XREFFILE account AIX) and returns all rows")
    void getCardXrefsForAccount_delegatesToRepository() {
        CardXref first = xref(CARD_NUMBER, 100L, 55L);
        CardXref second = xref("4111111111112222", 100L, 55L);
        when(cardXrefRepository.findByXrefAcctId(55L)).thenReturn(List.of(first, second));

        List<CardXref> result = service.getCardXrefsForAccount(55L);

        assertThat(result).containsExactly(first, second);
        verify(cardXrefRepository).findByXrefAcctId(55L);
    }

    @Test
    @DisplayName("getCardXrefsForAccount returns an empty list when the account has no cross-reference rows")
    void getCardXrefsForAccount_noRows_returnsEmptyList() {
        when(cardXrefRepository.findByXrefAcctId(999L)).thenReturn(List.of());

        assertThat(service.getCardXrefsForAccount(999L)).isEmpty();
        verify(cardXrefRepository).findByXrefAcctId(999L);
    }
}
