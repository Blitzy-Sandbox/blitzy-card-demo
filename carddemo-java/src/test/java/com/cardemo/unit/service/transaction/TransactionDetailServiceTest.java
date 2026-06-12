package com.cardemo.unit.service.transaction;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.transaction.TransactionDetailService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fast, fully-mocked unit test for {@link TransactionDetailService} &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.x translation of the online CICS program <strong>{@code app/cbl/COTRN01C.cbl}</strong>
 * (CICS transaction <strong>{@code CT01}</strong>, BMS map {@code COTRN1A}). The legacy program performed a
 * <strong>single keyed read of the {@code TRANSACT} VSAM KSDS</strong>: it edited the entered transaction id,
 * read that one record and painted its thirteen detail fields onto the screen. These tests assert that the
 * migrated service reproduces that observable behavior <em>exactly</em> (100% behavioral-parity gate,
 * AAP&nbsp;&sect;0.7.1&ndash;&sect;0.7.2).
 *
 * <h2>Test strategy &mdash; pure Mockito, no Spring, no I/O</h2>
 * <p>These are millisecond, in-memory, pure-Mockito unit tests: there is <em>no</em> {@code @SpringBootTest},
 * no Spring context, no Testcontainers, no database and no I/O. The sole collaborator
 * {@link TransactionRepository} is a Mockito {@code @Mock}; the system under test is wired by constructor
 * injection through {@code @InjectMocks} (matching the real single-argument constructor
 * {@code TransactionDetailService(TransactionRepository)}). The class runs under {@link MockitoExtension}
 * (default {@code STRICT_STUBS}), so the blank-id validation tests stub <em>nothing</em> and assert
 * {@link org.mockito.Mockito#verifyNoInteractions(Object...) verifyNoInteractions} to prove the early throw,
 * while every read test stubs <em>exactly one</em> method &mdash; the inherited
 * {@link org.springframework.data.repository.CrudRepository#findById(Object) findById(String)} &mdash; with
 * the exact sixteen-character key the call uses (no {@code lenient()}, no {@code any()}).</p>
 *
 * <h2>The two fidelity traps (verified against the generated sources, STEP&nbsp;0)</h2>
 * <ol>
 *   <li><strong>The two verbatim operator messages.</strong> A blank id raises
 *       {@link ValidationException} carrying {@code "Tran ID can NOT be empty..."} ({@code COTRN01C} L147-150)
 *       and a keyed-read miss raises {@link RecordNotFoundException} carrying
 *       {@code "Transaction ID NOT found..."} ({@code COTRN01C} L283-285). Both are asserted byte-for-byte
 *       against the COBOL literal (including the three-dot ellipsis), independent of the service constants, so
 *       any drift from the mainframe text fails the build. The COBOL {@code WHEN OTHER} fatal-read message
 *       ({@code 'Unable to lookup Transaction...'}, L289-295) is unreachable through a mocked
 *       {@link Optional}-returning repository and is therefore intentionally <em>not</em> exercised here.</li>
 *   <li><strong>The entity&rarr;DTO type conversions.</strong> Three fields change shape between the
 *       {@link Transaction} entity and the {@link TransactionDto}, each reproducing a COBOL numeric-to-display
 *       or substring {@code MOVE} (confirmed by reading the real files in STEP&nbsp;0):
 *       <ul>
 *         <li>{@code categoryCode}: entity {@link Integer} {@code TRAN-CAT-CD PIC 9(04)} &rarr; DTO
 *             {@link String} zero-padded to <strong>four</strong> characters (so {@code 5} renders as
 *             {@code "0005"}, NOT {@code "5"}).</li>
 *         <li>{@code merchantId}: entity {@link Long} {@code TRAN-MERCHANT-ID PIC 9(09)} &rarr; DTO
 *             {@link String} zero-padded to <strong>nine</strong> characters (so {@code 42} renders as
 *             {@code "000000042"}).</li>
 *         <li>{@code originationDate}/{@code processingDate}: entity {@link LocalDateTime}
 *             {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} &rarr; DTO <strong>{@link LocalDate}</strong> (the
 *             {@code PIC X(10)} {@code yyyy-MM-dd} date prefix). The DTO date fields were verified to be
 *             {@link LocalDate} (not raw {@link String}), so the {@code LocalDate} branch is implemented and
 *             asserted.</li>
 *       </ul>
 *       Width truncations are reproduced too: {@code description} (60), {@code merchantName} (30) and
 *       {@code merchantCity} (25). The monetary {@code amount} is a {@link BigDecimal} of scale&nbsp;2 and is
 *       always compared with {@link BigDecimal#compareTo(BigDecimal)} (AssertJ
 *       {@code isEqualByComparingTo}), never the scale-sensitive {@link BigDecimal#equals(Object)}
 *       (AAP&nbsp;&sect;0.7.3).</li>
 * </ol>
 *
 * <p>Golden field values are grounded in {@code app/data/ASCII/dailytran.txt} (timestamp form
 * {@code YYYY-MM-DD HH:MM:SS.ffffff}) and the documented AAP parity seed
 * {@code 2024-03-09 12.00.00.000000}. The COBOL is read-only reference at the frozen baseline commit SHA
 * {@code 27d6c6f} and is <strong>never copied</strong> into this repository &mdash; only its observable
 * contract is asserted.</p>
 *
 * @see TransactionDetailService
 * @see TransactionRepository
 * @see TransactionDto
 * @see Transaction
 * @see ValidationException
 * @see RecordNotFoundException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionDetailService — COTRN01C single transaction view (CT01)")
class TransactionDetailServiceTest {

    // ------------------------------------------------------------------------------------------------
    // Verbatim COBOL operator messages (COTRN01C). Asserted against the literal mainframe text — NOT the
    // service constants — so the test is an independent 100%-parity gate on the message contract (§0.7.2).
    // ------------------------------------------------------------------------------------------------

    /** Blank-id edit message, verbatim including the three-dot ellipsis ({@code COTRN01C} L149). */
    private static final String MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

    /** Keyed-read not-found message, verbatim including the three-dot ellipsis ({@code COTRN01C} L285). */
    private static final String MSG_TRAN_NOT_FOUND = "Transaction ID NOT found...";

    // ------------------------------------------------------------------------------------------------
    // Golden fixture values. The transaction id is the canonical 16-char zero-padded key form; the
    // remaining values exercise every conversion/passthrough/truncation branch of toDto(...).
    // ------------------------------------------------------------------------------------------------

    /** Canonical 16-char zero-padded {@code TRAN-ID} key for the happy-path read ({@code String.valueOf} of 42). */
    private static final String GOLDEN_TRAN_ID = "0000000000000042";

    /** A 16-char zero-padded key with no backing record, for the not-found path. */
    private static final String MISSING_TRAN_ID = "0000000000000099";

    /** {@code TRAN-CARD-NUM PIC X(16)} &mdash; carried verbatim. */
    private static final String GOLDEN_CARD_NUM = "1234567890123456";

    /** {@code TRAN-TYPE-CD PIC X(02)} &mdash; carried verbatim. */
    private static final String GOLDEN_TYPE_CD = "01";

    /** {@code TRAN-CAT-CD PIC 9(04)} entity value (an {@link Integer}). */
    private static final Integer GOLDEN_CAT_CD = 5;

    /** Expected DTO {@code categoryCode}: {@link #GOLDEN_CAT_CD} zero-padded to the {@code PIC X(4)} width. */
    private static final String GOLDEN_CAT_CD_RENDERED = "0005";

    /** {@code TRAN-SOURCE PIC X(10)} &mdash; carried verbatim, trailing spaces preserved (dailytran form). */
    private static final String GOLDEN_SOURCE = "POS TERM  ";

    /** {@code TRAN-DESC} shorter than the {@code TDESCI X(60)} screen width &mdash; passes through unchanged. */
    private static final String GOLDEN_DESC = "Purchase at Test Merchant";

    /** {@code TRAN-AMT PIC S9(09)V99} &mdash; scale-2 monetary value (AAP &sect;0.7.3). */
    private static final BigDecimal GOLDEN_AMOUNT = new BigDecimal("123.45");

    /** {@code TRAN-ORIG-TS} entity timestamp (a {@link LocalDateTime}); AAP parity seed 2024-03-09 12:00:00. */
    private static final LocalDateTime GOLDEN_ORIG_TS = LocalDateTime.of(2024, 3, 9, 12, 0, 0);

    /** {@code TRAN-PROC-TS} entity timestamp (a {@link LocalDateTime}); 2024-03-10 09:30:00. */
    private static final LocalDateTime GOLDEN_PROC_TS = LocalDateTime.of(2024, 3, 10, 9, 30, 0);

    /** Expected DTO {@code originationDate}: the {@code yyyy-MM-dd} date prefix of {@link #GOLDEN_ORIG_TS}. */
    private static final LocalDate GOLDEN_ORIG_DATE = LocalDate.of(2024, 3, 9);

    /** Expected DTO {@code processingDate}: the {@code yyyy-MM-dd} date prefix of {@link #GOLDEN_PROC_TS}. */
    private static final LocalDate GOLDEN_PROC_DATE = LocalDate.of(2024, 3, 10);

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} entity value (a {@link Long}); already nine digits wide. */
    private static final Long GOLDEN_MERCHANT_ID = 987654321L;

    /** Expected DTO {@code merchantId}: {@link #GOLDEN_MERCHANT_ID} rendered at the {@code PIC X(9)} width. */
    private static final String GOLDEN_MERCHANT_ID_RENDERED = "987654321";

    /** {@code TRAN-MERCHANT-NAME} shorter than the {@code MNAMEI X(30)} width &mdash; passes through unchanged. */
    private static final String GOLDEN_MERCHANT_NAME = "Test Merchant";

    /** {@code TRAN-MERCHANT-CITY} shorter than the {@code MCITYI X(25)} width &mdash; passes through unchanged. */
    private static final String GOLDEN_MERCHANT_CITY = "Test City";

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} &mdash; carried verbatim (entity and screen widths match). */
    private static final String GOLDEN_MERCHANT_ZIP = "12345";

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionDetailService service;

    // ================================================================================================
    // Test-data factory. Built via the REAL entity setters confirmed in STEP 0. Note tranCatCd is an
    // Integer, tranMerchantId is a Long, tranAmt is a BigDecimal, and tranOrigTs/tranProcTs are
    // LocalDateTime (NOT 26-char Strings) — these types drive the conversions asserted below.
    // ================================================================================================

    /**
     * Builds a fully-populated in-memory {@link Transaction} fixture matching the
     * {@link TransactionRepository#findById(Object)} result the service consumes on the happy path.
     *
     * <p>Every field is set through the real setters so the fixture cannot drift from the entity contract.
     * The merchant name/city values are deliberately shorter than their screen widths (30/25) so the
     * width-preserving fields pass through unchanged in the all-fields mapping test; dedicated truncation
     * tests supply over-width values where truncation is the behavior under test.</p>
     *
     * @return a populated {@link Transaction} test fixture keyed on {@link #GOLDEN_TRAN_ID}
     */
    private static Transaction fullTxn() {
        Transaction t = new Transaction();
        t.setTranId(GOLDEN_TRAN_ID);
        t.setTranCardNum(GOLDEN_CARD_NUM);
        t.setTranTypeCd(GOLDEN_TYPE_CD);
        t.setTranCatCd(GOLDEN_CAT_CD);
        t.setTranSource(GOLDEN_SOURCE);
        t.setTranDesc(GOLDEN_DESC);
        t.setTranAmt(GOLDEN_AMOUNT);
        t.setTranOrigTs(GOLDEN_ORIG_TS);
        t.setTranProcTs(GOLDEN_PROC_TS);
        t.setTranMerchantId(GOLDEN_MERCHANT_ID);
        t.setTranMerchantName(GOLDEN_MERCHANT_NAME);
        t.setTranMerchantCity(GOLDEN_MERCHANT_CITY);
        t.setTranMerchantZip(GOLDEN_MERCHANT_ZIP);
        return t;
    }

    // ================================================================================================
    // Blank-id guard — PROCESS-ENTER-KEY (COTRN01C L146-156). null / "" / all-blank => ValidationException
    // raised BEFORE any database access (the repository is never touched).
    // ================================================================================================

    @Nested
    @DisplayName("Blank-id guard — PROCESS-ENTER-KEY (COTRN01C L146-156)")
    class BlankIdValidation {

        @ParameterizedTest(name = "[{index}] blank id => empty-id error, no DB read")
        @NullSource
        @EmptySource
        @ValueSource(strings = {"   ", "\t", "  \t "})
        @DisplayName("null / empty / all-blank id => ValidationException 'Tran ID can NOT be empty...'; repo untouched")
        void blankId_throwsValidation_andNeverReadsRepository(String blankId) {
            // WHEN TRNIDINI = SPACES OR LOW-VALUES (L147): null covers LOW-VALUES/absent, blank covers SPACES.
            assertThatThrownBy(() -> service.getTransaction(blankId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(MSG_TRAN_ID_EMPTY);
            // The blank edit short-circuits before READ-TRANSACT-FILE — no repository interaction at all.
            verifyNoInteractions(transactionRepository);
        }
    }

    // ================================================================================================
    // Keyed-read miss — READ-TRANSACT-FILE WHEN DFHRESP(NOTFND) (COTRN01C L267-296). An empty Optional is
    // the relational analog of NOTFND and maps to RecordNotFoundException with the verbatim COBOL message.
    // ================================================================================================

    @Nested
    @DisplayName("Keyed-read miss — READ-TRANSACT-FILE NOTFND (COTRN01C L267-296)")
    class NotFound {

        @Test
        @DisplayName("empty Optional => RecordNotFoundException 'Transaction ID NOT found...' [COTRN01C L283-285]")
        void notFound_throwsRecordNotFound() {
            when(transactionRepository.findById(MISSING_TRAN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTransaction(MISSING_TRAN_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage(MSG_TRAN_NOT_FOUND);

            verify(transactionRepository).findById(MISSING_TRAN_ID);
        }

        @Test
        @DisplayName("non-numeric id is NOT numeric-gated; flows to the keyed read and misses => not found")
        void nonNumericId_flowsToReadAndMisses() {
            // COTRN01C performs no IS NUMERIC edit (unlike the COTRN00C list screen): a non-numeric id is used
            // as received (normalizeTransactionId leaves it unchanged) and simply fails to match a stored key.
            when(transactionRepository.findById("ABC")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTransaction("ABC"))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage(MSG_TRAN_NOT_FOUND);

            verify(transactionRepository).findById("ABC");
        }
    }

    // ================================================================================================
    // Key normalization — MOVE TRNIDINI TO TRAN-ID then keyed READ (COTRN01C L172-173). A bare numeric id
    // is trimmed and left zero-padded to the 16-char TRAN-ID key width before findById is called.
    // ================================================================================================

    @Nested
    @DisplayName("Key normalization — MOVE TRNIDINI TO TRAN-ID (COTRN01C L172-173)")
    class KeyNormalization {

        @Test
        @DisplayName("bare numeric id \"42\" is zero-padded to \"0000000000000042\" before the keyed read")
        void bareNumericId_zeroPaddedTo16BeforeRead() {
            when(transactionRepository.findById(GOLDEN_TRAN_ID)).thenReturn(Optional.of(fullTxn()));

            TransactionDto dto = service.getTransaction("42");

            assertThat(dto).isNotNull();
            assertThat(dto.getTransactionId()).isEqualTo(GOLDEN_TRAN_ID);
            // The repository is queried with the normalized 16-char key, never the raw "42".
            verify(transactionRepository).findById(GOLDEN_TRAN_ID);
        }

        @Test
        @DisplayName("numeric id with surrounding spaces \"  42  \" is trimmed then padded to \"0000000000000042\"")
        void idWithSurroundingSpaces_trimmedThenPadded() {
            when(transactionRepository.findById(GOLDEN_TRAN_ID)).thenReturn(Optional.of(fullTxn()));

            service.getTransaction("  42  ");

            verify(transactionRepository).findById(GOLDEN_TRAN_ID);
        }

        @Test
        @DisplayName("already-16-char numeric id is idempotent under normalization")
        void alreadyPaddedId_isIdempotent() {
            when(transactionRepository.findById(GOLDEN_TRAN_ID)).thenReturn(Optional.of(fullTxn()));

            service.getTransaction(GOLDEN_TRAN_ID);

            verify(transactionRepository).findById(GOLDEN_TRAN_ID);
        }
    }

    // ================================================================================================
    // Field mapping on hit — DFHRESP(NORMAL) branch, SEND MAP('COTRN1A') field MOVEs (COTRN01C L177-190).
    // The thirteen detail fields are copied with the exact COBOL type conversions and width truncations.
    // ================================================================================================

    @Nested
    @DisplayName("Field mapping on hit — SEND MAP COTRN1A (COTRN01C L177-190)")
    class FieldMappingOnHit {

        @Test
        @DisplayName("maps all 13 detail fields with COBOL-identical conversions [COTRN01C L178-190]")
        void mapsAllThirteenFields() {
            when(transactionRepository.findById(GOLDEN_TRAN_ID)).thenReturn(Optional.of(fullTxn()));

            TransactionDto dto = service.getTransaction(GOLDEN_TRAN_ID);

            // Verbatim passthrough fields (entity and screen widths match).
            assertThat(dto.getTransactionId()).isEqualTo(GOLDEN_TRAN_ID);   // TRNIDI  <- TRAN-ID
            assertThat(dto.getCardNumber()).isEqualTo(GOLDEN_CARD_NUM);     // CARDNUMI<- TRAN-CARD-NUM
            assertThat(dto.getTypeCode()).isEqualTo(GOLDEN_TYPE_CD);        // TTYPCDI <- TRAN-TYPE-CD
            assertThat(dto.getSource()).isEqualTo(GOLDEN_SOURCE);           // TRNSRCI <- TRAN-SOURCE
            assertThat(dto.getMerchantName()).isEqualTo(GOLDEN_MERCHANT_NAME); // MNAMEI <- TRAN-MERCHANT-NAME (<=30)
            assertThat(dto.getMerchantCity()).isEqualTo(GOLDEN_MERCHANT_CITY); // MCITYI <- TRAN-MERCHANT-CITY (<=25)
            assertThat(dto.getMerchantZip()).isEqualTo(GOLDEN_MERCHANT_ZIP);   // MZIPI  <- TRAN-MERCHANT-ZIP
            assertThat(dto.getDescription()).isEqualTo(GOLDEN_DESC);          // TDESCI <- TRAN-DESC (<=60)

            // Integer -> String, zero-padded to 4 (MOVE TRAN-CAT-CD PIC 9(04) TO TCATCDI PIC X(4)).
            assertThat(dto.getCategoryCode()).isEqualTo(GOLDEN_CAT_CD_RENDERED); // "0005"
            // Long -> String, zero-padded to 9 (MOVE TRAN-MERCHANT-ID PIC 9(09) TO MIDI PIC X(9)).
            assertThat(dto.getMerchantId()).isEqualTo(GOLDEN_MERCHANT_ID_RENDERED); // "987654321"

            // Monetary amount: BigDecimal scale 2; compared via compareTo, NEVER equals (AAP §0.7.3).
            assertThat(dto.getAmount()).isEqualByComparingTo(GOLDEN_AMOUNT);
            assertThat(dto.getAmount().scale()).isEqualTo(2);

            // Date typing: DTO originationDate/processingDate are LocalDate (verified STEP 0); the service
            // carries the date prefix of the LocalDateTime timestamps via LocalDateTime.toLocalDate().
            assertThat(dto.getOriginationDate()).isEqualTo(GOLDEN_ORIG_DATE); // 2024-03-09
            assertThat(dto.getProcessingDate()).isEqualTo(GOLDEN_PROC_DATE);  // 2024-03-10

            verify(transactionRepository).findById(GOLDEN_TRAN_ID);
        }

        @Test
        @DisplayName("categoryCode: Integer TRAN-CAT-CD rendered zero-padded to 4 chars (1 -> \"0001\")")
        void categoryCode_integerZeroPaddedToFour() {
            Transaction t = fullTxn();
            t.setTranCatCd(1);
            when(transactionRepository.findById(GOLDEN_TRAN_ID)).thenReturn(Optional.of(t));

            assertThat(service.getTransaction(GOLDEN_TRAN_ID).getCategoryCode()).isEqualTo("0001");

            verify(transactionRepository).findById(GOLDEN_TRAN_ID);
        }

        @Test
        @DisplayName("merchantId: Long TRAN-MERCHANT-ID rendered zero-padded to 9 chars (42 -> \"000000042\")")
        void merchantId_longZeroPaddedToNine() {
            Transaction t = fullTxn();
            t.setTranMerchantId(42L);
            when(transactionRepository.findById(GOLDEN_TRAN_ID)).thenReturn(Optional.of(t));

            assertThat(service.getTransaction(GOLDEN_TRAN_ID).getMerchantId()).isEqualTo("000000042");

            verify(transactionRepository).findById(GOLDEN_TRAN_ID);
        }

        @Test
        @DisplayName("amount normalized to scale 2; compared via compareTo NEVER equals [AAP §0.7.3]")
        void amount_normalizedToScale2_comparedViaCompareTo() {
            Transaction t = fullTxn();
            t.setTranAmt(new BigDecimal("123.4")); // scale 1 — the service re-scales to 2 (HALF_EVEN).
            when(transactionRepository.findById(GOLDEN_TRAN_ID)).thenReturn(Optional.of(t));

            TransactionDto dto = service.getTransaction(GOLDEN_TRAN_ID);

            // compareTo is scale-insensitive: 123.4 and 123.40 are equal by value. equals() would NOT be.
            assertThat(dto.getAmount()).isEqualByComparingTo(new BigDecimal("123.40"));
            assertThat(dto.getAmount().scale()).isEqualTo(2);

            verify(transactionRepository).findById(GOLDEN_TRAN_ID);
        }

        @Test
        @DisplayName("description: 80-char TRAN-DESC truncated to the leftmost 60 (TDESCI PIC X(60)) [COTRN01C L184]")
        void description_truncatedToSixty() {
            Transaction t = fullTxn();
            String overWidth = "X".repeat(60) + "Y".repeat(20); // 80 chars; the trailing 20 must be dropped.
            t.setTranDesc(overWidth);
            when(transactionRepository.findById(GOLDEN_TRAN_ID)).thenReturn(Optional.of(t));

            String description = service.getTransaction(GOLDEN_TRAN_ID).getDescription();

            assertThat(description).hasSize(60).isEqualTo("X".repeat(60)).doesNotContain("Y");

            verify(transactionRepository).findById(GOLDEN_TRAN_ID);
        }

        @Test
        @DisplayName("merchant name truncated to 30 (MNAMEI), merchant city truncated to 25 (MCITYI) [COTRN01C L188-189]")
        void merchantNameAndCity_truncatedToScreenWidths() {
            Transaction t = fullTxn();
            t.setTranMerchantName("N".repeat(50)); // X(50) entity -> X(30) screen
            t.setTranMerchantCity("C".repeat(50)); // X(50) entity -> X(25) screen
            when(transactionRepository.findById(GOLDEN_TRAN_ID)).thenReturn(Optional.of(t));

            TransactionDto dto = service.getTransaction(GOLDEN_TRAN_ID);

            assertThat(dto.getMerchantName()).hasSize(30).isEqualTo("N".repeat(30));
            assertThat(dto.getMerchantCity()).hasSize(25).isEqualTo("C".repeat(25));

            verify(transactionRepository).findById(GOLDEN_TRAN_ID);
        }

        @Test
        @DisplayName("null numeric/date/amount fields map to null (numeric/date null-guards)")
        void nullNumericDateAndAmountFields_mapToNull() {
            Transaction t = fullTxn();
            t.setTranCatCd(null);
            t.setTranMerchantId(null);
            t.setTranAmt(null);
            t.setTranOrigTs(null);
            t.setTranProcTs(null);
            when(transactionRepository.findById(GOLDEN_TRAN_ID)).thenReturn(Optional.of(t));

            TransactionDto dto = service.getTransaction(GOLDEN_TRAN_ID);

            assertThat(dto.getCategoryCode()).isNull();
            assertThat(dto.getMerchantId()).isNull();
            assertThat(dto.getAmount()).isNull();
            assertThat(dto.getOriginationDate()).isNull();
            assertThat(dto.getProcessingDate()).isNull();

            verify(transactionRepository).findById(GOLDEN_TRAN_ID);
        }

        @Test
        @DisplayName("null text fields map to empty string (COBOL fixed-width field renders spaces)")
        void nullTextFields_mapToEmptyString() {
            Transaction t = fullTxn();
            t.setTranDesc(null);
            t.setTranMerchantName(null);
            t.setTranMerchantCity(null);
            when(transactionRepository.findById(GOLDEN_TRAN_ID)).thenReturn(Optional.of(t));

            TransactionDto dto = service.getTransaction(GOLDEN_TRAN_ID);

            assertThat(dto.getDescription()).isEmpty();
            assertThat(dto.getMerchantName()).isEmpty();
            assertThat(dto.getMerchantCity()).isEmpty();

            verify(transactionRepository).findById(GOLDEN_TRAN_ID);
        }
    }
}
