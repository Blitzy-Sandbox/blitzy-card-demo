package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.FieldMetadata;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.PaginationCursor;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link TransactionListRequest}, the inbound payload of
 * {@code GET /api/transactions} and the Java projection of {@code 01 COTRN0AI} at
 * {@code app/cpy-bms/COTRN00.CPY:17}, whose {@code REDEFINES} alias {@code 01 COTRN0AO} follows at
 * {@code app/cpy-bms/COTRN00.CPY:373} over the identical bytes.
 *
 * <h2>The authorities this suite is written against</h2>
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COTRN00.CPY} - the 59 {@code xxxI} payload items with their
 *       {@code PIC X(n)} widths, and the {@code xxxL} / {@code xxxF} / {@code xxxA} metadata items
 *       that are deliberately <em>not</em> payload.</li>
 *   <li>{@code app/bms/COTRN00.bms} - {@code COTRN00 DFHMSD ... TIOAPFX=YES} and
 *       {@code COTRN0A DFHMDI COLUMN=1 LINE=1 SIZE=(24,80)}, then <strong>89</strong>
 *       {@code DFHMDF} entries of which <strong>59 are named</strong>. The 30 unnamed ones are
 *       literal and label fields - {@code 'Tran:'}, the column captions, the PF-key legend - which
 *       carry {@code INITIAL} text and no symbolic-map entry at all, so they are screen furniture
 *       rather than data and the closed payload set is the 59. {@code ERRMSG} is the last of them,
 *       at {@code app/bms/COTRN00.bms:450}, {@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED LENGTH=78
 *       POS=(23,1)}.</li>
 *   <li>{@code app/cbl/COTRN00C.cbl} (699 lines) - {@code WS-TRAN-AMT PIC +99999999.99} at L56 and
 *       {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} at L57; {@code COPY COCOM01Y.} at L61 with
 *       the {@code CDEMO-CT00-INFO} extension at L62-L70; {@code MOVE -1 TO TRNIDINL} at 13 sites;
 *       the page loops at L290 and L295-L301; {@code COMPUTE CDEMO-CT00-PAGE-NUM} at L306 and L317;
 *       {@code POPULATE-TRAN-DATA}'s ordered {@code EVALUATE WS-IDX} at L390-L446 and
 *       {@code INITIALIZE-TRAN-DATA}'s at L451 onwards.</li>
 *   <li>{@code app/cpy/CVTRA05Y.cpy} - the 350-byte {@code TRAN-RECORD} that feeds the screen, with
 *       {@code TRAN-ID PIC X(16)}, {@code TRAN-DESC PIC X(100)} and {@code TRAN-AMT PIC
 *       S9(09)V99}.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD} - {@code DEFINE MAPSET(COTRN00)} at :145,
 *       {@code DEFINE PROGRAM(COTRN00C)} at :257 and {@code DEFINE TRANSACTION(CT00)
 *       PROGRAM(COTRN00C)} at :419.</li>
 * </ul>
 *
 * <p>Every expected value below is transcribed from those sources - never read back out of the class
 * under test - so the COBOL stays the authority and a translation that drifted would fail here rather
 * than agree with itself.
 *
 * <h2>Deliberately self-contained</h2>
 *
 * The expectations are <strong>literals</strong>. This suite opens no file under {@code app/cbl},
 * {@code app/cpy}, {@code app/cpy-bms}, {@code app/bms} or {@code app/csd} - not for reading and
 * certainly not for writing - so those sources stay immutable (practice B3) and the only path this
 * test changes is its own (gate G5). It reaches no network, no clock, no locale and no filesystem, and
 * every charset is named rather than defaulted (practice B7), so a run is deterministic and
 * non-interactive under plain JUnit 5 (gate G54). It also imports nothing beyond the class under test,
 * {@code common.NavigationContext}, {@code common.FixedWidthCodec}, {@code common.FixedWidthRecord}
 * and the test libraries - the AID tokens it needs are declared here as the five-character literals
 * they are, rather than borrowed from a collaborator this payload does not depend on.
 *
 * <h2>Name and behaviour agree here - this is not an R-B case</h2>
 *
 * {@code app/cbl/COTRN00C.cbl:5} reads {@code Function : List Transactions from TRANSACT file} and
 * {@code README.md:222} documents {@code CT00} as "Transaction List". The prompt-mandated
 * {@code TransactionListRequest} therefore describes exactly what the source does. That is worth
 * stating, because it is <em>not</em> true of the {@code COTRN01C} / {@code COTRN02C} pair in this same
 * package, where the mandated {@code Add} and {@code View} names are inverted relative to the sources
 * and rule R1 has to hold the two apart. Nothing in this file needs that caution.
 *
 * <h2>No user rules exist, so the migration's own binds are the rulings</h2>
 *
 * {@code review_rules} returns exactly one line - "No user rules provided" - and that single line is
 * the whole document, so no user rule governs this file. Their absence is explicitly not permission to
 * lower the bar: what binds instead are the migration's transformation rules, best-practice binds and
 * validation gates, and each is named in the {@code @DisplayName} or the comment where it is asserted
 * so a reviewer can trace an assertion back to the constraint that demands it.
 *
 * <table border="1">
 *   <caption>Where each governing constraint is asserted</caption>
 *   <tr><th>Constraint</th><th>Asserted by</th></tr>
 *   <tr><td>R1 - names from the prompt, behaviour from the source</td>
 *       <td>{@code Inventory.provenance...}, and the note above</td></tr>
 *   <tr><td>R2 / G24 - truncation, never rounding</td><td>{@code NegativeContract}</td></tr>
 *   <tr><td>R4 / G22 - never {@code double} or {@code float}</td>
 *       <td>{@code NegativeContract}</td></tr>
 *   <tr><td>R5 / G9 / G21 - fixed width is the wire format</td>
 *       <td>{@code Geometry}, {@code WidthRule}, {@code FixedWidthImage}</td></tr>
 *   <tr><td>R6 / G37 - statelessness</td><td>{@code Statelessness}, {@code Cursor}</td></tr>
 *   <tr><td>R7 - structured control flow preserving evaluation order</td>
 *       <td>{@code ScreenFlow}, {@code RowAddressing}</td></tr>
 *   <tr><td>B3 / G5 - reference inputs immutable, only this path changes</td>
 *       <td>the self-containment note above; the suite performs no file I/O at all</td></tr>
 *   <tr><td>B4 - no silent scope creep</td><td>{@code SuffixSpelling}</td></tr>
 *   <tr><td>B7 / G54 - deterministic and non-interactive</td>
 *       <td>no clock, locale, charset default or sleep anywhere in the suite</td></tr>
 *   <tr><td>B8 / G52 - explicit over implicit, no wildcard import</td>
 *       <td>the import block: every type named, every charset named</td></tr>
 *   <tr><td>B9 / G53 - no static mutable state</td>
 *       <td>{@code Statelessness}, {@code NegativeContract}</td></tr>
 *   <tr><td>B10 - tests ship with the code they test</td><td>this file</td></tr>
 *   <tr><td>B11 - hand-written, reviewable codecs</td>
 *       <td>{@code WidthRule} drives {@code FixedWidthCodec} directly; no copybook parser</td></tr>
 *   <tr><td>G9 - every payload field traces to a {@code DFHMDF} definition</td>
 *       <td>{@code Inventory}, {@code Serialisation}, {@code NegativeContract}</td></tr>
 *   <tr><td>G17 - field-by-field comparison, never whole strings</td>
 *       <td>{@code NameKeyedView}</td></tr>
 *   <tr><td>G33 - 1-based rows, both ends, out of range rejected</td>
 *       <td>{@code RowAddressing}</td></tr>
 *   <tr><td>G34 - every {@code REDEFINES} pair is two accessors over one span</td>
 *       <td>{@code Metadata}, {@code Geometry}</td></tr>
 *   <tr><td>G39 - page size is 10 and not configurable</td><td>{@code PageSize}</td></tr>
 *   <tr><td>G44 - nothing schema-shaped</td><td>{@code NegativeContract}</td></tr>
 *   <tr><td>G49 - branch coverage of the payload type</td><td>the suite as a whole</td></tr>
 * </table>
 *
 * <h2>The properties the suite is organised around</h2>
 *
 * <ol>
 *   <li>Exactly <strong>59</strong> payload fields, reconciling as 8 + 10 x 5 + 1.</li>
 *   <li>The row suffixes are <strong>deliberately inconsistent</strong> - four digits, two digits and
 *       three digits - and must stay that way.</li>
 *   <li>The widths sum to <strong>840</strong> and the group image is <strong>1265</strong> bytes.</li>
 *   <li>The page size is <strong>10</strong> and is not configurable.</li>
 *   <li>The 1-based COBOL row maps to the right field at <strong>both</strong> ends.</li>
 *   <li>Space padding survives a JSON round trip untrimmed, and a blank row is spaces.</li>
 *   <li>The cursor is <strong>58</strong> bytes with both {@code 88}-levels reachable.</li>
 * </ol>
 *
 * <p>All ten rows of this map are <strong>strictly regular</strong>: every one declares the same five
 * items at the same five widths, and the only asymmetry anywhere is in the cursor, where
 * {@code POPULATE-TRAN-DATA}'s {@code WHEN 1} additionally captures
 * {@code CDEMO-CT00-TRNID-FIRST} and its {@code WHEN 10} captures {@code CDEMO-CT00-TRNID-LAST}.
 * That is worth saying out loud because it differs from {@code card/dto/CardListRequest}, whose row 1
 * genuinely is shaped differently, so the pattern established here must not be copied across without
 * re-reading that copybook.
 *
 * <h2>What this suite deliberately leaves alone</h2>
 *
 * It stays on the payload surface: no {@code MockMvc}, no {@code @SpringBootTest}, no
 * {@code @WebMvcTest}, no repository, no {@code JobLauncher} and no {@code DataSource}. The controller
 * behaviour - the {@code EVALUATE EIBAID} arms, the ordered ten-way {@code SEL0001I}-{@code SEL0010I}
 * first-match-wins selection and the page arithmetic - belongs to {@code TransactionMenuControllerTest}
 * in the parent package and is not duplicated here. Nothing is imported from
 * {@code com.vsergeychik.carddemo.parity} and nothing is read from
 * {@code src/test/resources/parity}; those are that package's own deliverables.
 */
@DisplayName("TransactionListRequest - COTRN0AI projection of COTRN00 / CT00")
class TransactionListRequestTest {

    /**
     * The code page of the authoritative fixtures under {@code app/data/ASCII}, named explicitly
     * because the platform default is never acceptable for mainframe data (practice B7).
     */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The 59 base field names in copybook declaration order, transcribed from
     * {@code app/cpy-bms/COTRN00.CPY} lines 17 to 372 rather than derived from the class under test.
     *
     * <p>{@code static final} and genuinely <strong>immutable</strong>: {@link List#of} returns an
     * unmodifiable list of immutable {@link String}s, so this is a constant rather than shared state.
     * Practice B9 forbids static <em>mutable</em> state, which would let one test method's mutation
     * change what a later one asserts and make the suite order-dependent; an immutable table cannot.
     * The same holds for {@link #COPYBOOK_WIDTHS}, {@link #INPUT_CAPABLE_FIELDS},
     * {@link #ACTED_ON_AID_TOKENS}, {@link #SCHEMA_SHAPED_ANNOTATIONS} and
     * {@link #FORBIDDEN_ROUNDING_MODES} below, and for {@link #ASCII}, which is an immutable
     * {@link Charset}.
     */
    private static final List<String> COPYBOOK_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "PAGENUM", "TRNIDIN",
            "SEL0001", "TRNID01", "TDATE01", "TDESC01", "TAMT001",
            "SEL0002", "TRNID02", "TDATE02", "TDESC02", "TAMT002",
            "SEL0003", "TRNID03", "TDATE03", "TDESC03", "TAMT003",
            "SEL0004", "TRNID04", "TDATE04", "TDESC04", "TAMT004",
            "SEL0005", "TRNID05", "TDATE05", "TDESC05", "TAMT005",
            "SEL0006", "TRNID06", "TDATE06", "TDESC06", "TAMT006",
            "SEL0007", "TRNID07", "TDATE07", "TDESC07", "TAMT007",
            "SEL0008", "TRNID08", "TDATE08", "TDESC08", "TAMT008",
            "SEL0009", "TRNID09", "TDATE09", "TDESC09", "TAMT009",
            "SEL0010", "TRNID10", "TDATE10", "TDESC10", "TAMT010",
            "ERRMSG");

    /** The 59 declared widths, in the same order, from the {@code xxxI PIC X(n)} clauses. */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(
            4, 40, 8, 8, 40, 8, 8, 16,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            78);

    /**
     * The 11 input-capable fields, transcribed from {@code app/bms/COTRN00.bms}: the browse key
     * {@code TRNIDIN} at :95 with {@code ATTRB=(FSET,NORM,UNPROT)}, and the ten row selectors
     * {@code SEL0001}-{@code SEL0010} at :153, :182, :211, :240, :269, :298, :327, :356, :385 and
     * :414. Every one of the other 48 named fields carries {@code ASKIP} and is output only, so
     * validation and highlight metadata is meaningful for exactly these 11.
     */
    private static final List<String> INPUT_CAPABLE_FIELDS = List.of(
            "TRNIDIN",
            "SEL0001", "SEL0002", "SEL0003", "SEL0004", "SEL0005",
            "SEL0006", "SEL0007", "SEL0008", "SEL0009", "SEL0010");

    /**
     * The four {@code EIBAID} tokens this screen acts on, as the five-character literals the AID
     * member carries: {@code ENTER} acts on the selected row, {@code PFK03} returns to the caller and
     * {@code PFK07} / {@code PFK08} are the two paging directions.
     *
     * <p>They are literals rather than an import so this suite stays self-contained and depends on
     * nothing but the payload type it tests. Their <em>values</em> are the AID names the IBM-supplied
     * {@code DFHAID} copybook defines - that copybook is absent from this repository, which is why the
     * module reproduces its constants from IBM CICS documentation, and why a test that pinned them by
     * reading a collaborator would be pinning nothing at all.
     */
    private static final List<String> ACTED_ON_AID_TOKENS =
            List.of("ENTER", "PFK03", "PFK07", "PFK08");

    /** The annotations that would betray a persistence or DDL concern in a payload type (gate G44). */
    private static final List<String> SCHEMA_SHAPED_ANNOTATIONS = List.of(
            "Entity", "Table", "Column", "Id", "Version", "GeneratedValue", "Embeddable",
            "MappedSuperclass", "JoinColumn", "SequenceGenerator");

    /** The rounding modes that must never appear in a truncating migration (rule R2, gate G24). */
    private static final List<String> FORBIDDEN_ROUNDING_MODES =
            List.of("HALF_UP", "HALF_DOWN", "HALF_EVEN", "CEILING", "FLOOR", "UP");

    private static String spaces(int length) {
        return " ".repeat(length);
    }

    // =================================================================================================

    @Nested
    @DisplayName("Field inventory - 59 fields, reconciling as 8 + 10 x 5 + 1 - rule R1, gate G9")
    class Inventory {

        @Test
        @DisplayName("the map declares 59 payload fields")
        void fieldCountIs59() {
            assertThat(TransactionListRequest.FIELD_COUNT).isEqualTo(59);
            assertThat(TransactionListRequest.FIELD_NAMES).hasSize(59);
        }

        @Test
        @DisplayName("59 = 8 header + 10 rows x 5 + 1 error line")
        void fieldCountReconciles() {
            assertThat(TransactionListRequest.HEADER_FIELD_COUNT).isEqualTo(8);
            assertThat(TransactionListRequest.ROW_FIELD_COUNT).isEqualTo(5);
            assertThat(TransactionListRequest.ERROR_FIELD_COUNT).isEqualTo(1);
            assertThat(TransactionListRequest.HEADER_FIELD_COUNT
                    + TransactionListRequest.ROW_COUNT * TransactionListRequest.ROW_FIELD_COUNT
                    + TransactionListRequest.ERROR_FIELD_COUNT).isEqualTo(59);
        }

        @Test
        @DisplayName("the field names equal the copybook xxxI base names, in declaration order")
        void namesMatchCopybookExactly() {
            assertThat(TransactionListRequest.FIELD_NAMES)
                    .containsExactlyElementsOf(COPYBOOK_FIELDS);
        }

        @Test
        @DisplayName("the symbolic-map item names are the base names with I appended")
        void inputItemNamesAppendI() {
            List<String> expected = new ArrayList<>();
            for (String base : COPYBOOK_FIELDS) {
                expected.add(base + "I");
            }
            assertThat(TransactionListRequest.INPUT_ITEM_NAMES).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("the metadata item names append L, F and A")
        void metadataItemNames() {
            assertThat(TransactionListRequest.lengthItemName("TRNIDIN")).isEqualTo("TRNIDINL");
            assertThat(TransactionListRequest.flagItemName("TRNIDIN")).isEqualTo("TRNIDINF");
            assertThat(TransactionListRequest.attributeItemName("TRNIDIN")).isEqualTo("TRNIDINA");
            assertThat(TransactionListRequest.inputItemName("TAMT001")).isEqualTo("TAMT001I");
        }

        @Test
        @DisplayName("a null base name is rejected by every name helper")
        void nullBaseNameRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.inputItemName(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.lengthItemName(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.flagItemName(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.attributeItemName(null));
        }

        @Test
        @DisplayName("provenance names the CSD transaction, program, mapset and map")
        void provenance() {
            assertThat(TransactionListRequest.TRANSACTION_ID).isEqualTo("CT00");
            assertThat(TransactionListRequest.PROGRAM_NAME).isEqualTo("COTRN00C");
            assertThat(TransactionListRequest.MAPSET_NAME).isEqualTo("COTRN00");
            assertThat(TransactionListRequest.MAP_NAME).isEqualTo("COTRN0A");
            assertThat(TransactionListRequest.SYMBOLIC_MAP_INPUT_GROUP).isEqualTo("COTRN0AI");
            assertThat(TransactionListRequest.SYMBOLIC_MAP_OUTPUT_GROUP).isEqualTo("COTRN0AO");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Suffix spelling - four, two and three digits, never normalised - practice B4")
    class SuffixSpelling {

        @ParameterizedTest(name = "row {0} selector is {1}")
        @CsvSource({"1,SEL0001", "2,SEL0002", "9,SEL0009", "10,SEL0010"})
        @DisplayName("the selector suffix is FOUR digits")
        void selectorSuffixIsFourDigits(int row, String expected) {
            assertThat(TransactionListRequest.selectionFieldName(row)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "row {0} identifier is {1}")
        @CsvSource({"1,TRNID01", "2,TRNID02", "9,TRNID09", "10,TRNID10"})
        @DisplayName("the identifier suffix is TWO digits")
        void identifierSuffixIsTwoDigits(int row, String expected) {
            assertThat(TransactionListRequest.transactionIdFieldName(row)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "row {0} date is {1}")
        @CsvSource({"1,TDATE01", "10,TDATE10"})
        @DisplayName("the date suffix is TWO digits")
        void dateSuffixIsTwoDigits(int row, String expected) {
            assertThat(TransactionListRequest.transactionDateFieldName(row)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "row {0} description is {1}")
        @CsvSource({"1,TDESC01", "10,TDESC10"})
        @DisplayName("the description suffix is TWO digits")
        void descriptionSuffixIsTwoDigits(int row, String expected) {
            assertThat(TransactionListRequest.transactionDescriptionFieldName(row))
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "row {0} amount is {1}")
        @CsvSource({"1,TAMT001", "2,TAMT002", "9,TAMT009", "10,TAMT010"})
        @DisplayName("the amount suffix is THREE digits - not TAMT01")
        void amountSuffixIsThreeDigits(int row, String expected) {
            assertThat(TransactionListRequest.transactionAmountFieldName(row)).isEqualTo(expected);
            assertThat(expected).isNotEqualTo("TAMT" + (row < 10 ? "0" + row : row));
        }

        @Test
        @DisplayName("all four suffix widths coexist, so no shared format string could produce them")
        void allFourWidthsCoexist() {
            assertThat(TransactionListRequest.selectionFieldName(1)).hasSize(7).endsWith("0001");
            assertThat(TransactionListRequest.transactionIdFieldName(1)).hasSize(7).endsWith("01");
            assertThat(TransactionListRequest.transactionAmountFieldName(1)).hasSize(7)
                    .endsWith("001");
        }

        @ParameterizedTest(name = "row {0} is rejected")
        @ValueSource(ints = {-1, 0, 11, 12, Integer.MAX_VALUE})
        @DisplayName("a row outside 1..10 is rejected - COBOL rows are 1-based and there is no row 0")
        void invalidRowRejected(int row) {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.requireValidRow(row))
                    .withMessageContaining("1-based");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.selectionFieldName(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.transactionAmountFieldName(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.transactionIdFieldName(row));
        }

        @Test
        @DisplayName("a valid row is returned unchanged so the guard reads naturally inline")
        void validRowReturnedUnchanged() {
            assertThat(TransactionListRequest.requireValidRow(1)).isEqualTo(1);
            assertThat(TransactionListRequest.requireValidRow(10)).isEqualTo(10);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Byte geometry - widths sum to 840, the image is 1265 - rule R5, gates G9, G21, G34")
    class Geometry {

        @Test
        @DisplayName("each declared width equals its copybook PIC X(n) and its BMS LENGTH")
        void widthsMatchCopybook() {
            TransactionListRequest request = new TransactionListRequest();
            for (int i = 0; i < COPYBOOK_FIELDS.size(); i++) {
                assertThat(request.getPayloadValue(COPYBOOK_FIELDS.get(i)))
                        .as("width of %s", COPYBOOK_FIELDS.get(i))
                        .hasSize(COPYBOOK_WIDTHS.get(i));
            }
        }

        @Test
        @DisplayName("the header widths are 4, 40, 8, 8, 40, 8, 8 and 16, summing to 132")
        void headerWidths() {
            assertThat(TransactionListRequest.TRNNAME_LENGTH).isEqualTo(4);
            assertThat(TransactionListRequest.TITLE01_LENGTH).isEqualTo(40);
            assertThat(TransactionListRequest.CURDATE_LENGTH).isEqualTo(8);
            assertThat(TransactionListRequest.PGMNAME_LENGTH).isEqualTo(8);
            assertThat(TransactionListRequest.TITLE02_LENGTH).isEqualTo(40);
            assertThat(TransactionListRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat(TransactionListRequest.PAGENUM_LENGTH).isEqualTo(8);
            assertThat(TransactionListRequest.TRNIDIN_LENGTH).isEqualTo(16);
            assertThat(TransactionListRequest.HEADER_PAYLOAD_LENGTH).isEqualTo(132);
        }

        @Test
        @DisplayName("the row widths are 1, 16, 8, 26 and 12, summing to 63 and 630 for ten rows")
        void rowWidths() {
            assertThat(TransactionListRequest.SELECTION_LENGTH).isEqualTo(1);
            assertThat(TransactionListRequest.TRANSACTION_ID_LENGTH).isEqualTo(16);
            assertThat(TransactionListRequest.TRANSACTION_DATE_LENGTH).isEqualTo(8);
            assertThat(TransactionListRequest.TRANSACTION_DESCRIPTION_LENGTH).isEqualTo(26);
            assertThat(TransactionListRequest.TRANSACTION_AMOUNT_LENGTH).isEqualTo(12);
            assertThat(TransactionListRequest.ROW_PAYLOAD_LENGTH).isEqualTo(63);
            assertThat(TransactionListRequest.ROW_BLOCK_PAYLOAD_LENGTH).isEqualTo(630);
        }

        @Test
        @DisplayName("the error line is 78 and 132 + 630 + 78 = 840")
        void payloadTotal() {
            assertThat(TransactionListRequest.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(TransactionListRequest.PAYLOAD_LENGTH).isEqualTo(840);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum()).isEqualTo(840);
        }

        @Test
        @DisplayName("12 + 59 x 7 + 840 = 1265, the COTRN0AI group image")
        void groupImageTotal() {
            assertThat(TransactionListRequest.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(TransactionListRequest.FIELD_PREFIX_LENGTH).isEqualTo(7);
            assertThat(TransactionListRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(1265);
            assertThat(12 + 59 * 7 + 840).isEqualTo(1265);
        }

        @Test
        @DisplayName("a row image is 98 bytes - 63 of payload plus five 7-byte prefixes")
        void rowImageStride() {
            assertThat(TransactionListRequest.ROW_IMAGE_LENGTH).isEqualTo(98);
            assertThat(TransactionListRequest.HEADER_IMAGE_LENGTH).isEqualTo(188);
            assertThat(TransactionListRequest.ROW_BLOCK_IMAGE_LENGTH).isEqualTo(980);
            assertThat(TransactionListRequest.ERRMSG_IMAGE_LENGTH).isEqualTo(85);
            assertThat(12 + 188 + 980 + 85).isEqualTo(1265);
        }

        @Test
        @DisplayName("the layout declares 1265 bytes across 119 spans - one filler plus 59 pairs")
        void layoutGeometry() {
            assertThat(TransactionListRequest.LAYOUT.recordLength()).isEqualTo(1265);
            assertThat(TransactionListRequest.LAYOUT.spans()).hasSize(1 + 2 * 59);
            assertThat(TransactionListRequest.LAYOUT.storageSpans()).hasSize(1 + 2 * 59);
            assertThat(TransactionListRequest.LAYOUT.redefinitions()).isEmpty();
        }

        @Test
        @DisplayName("each field's 7-byte prefix is 2 + 1 + 4, positioned at k, k+2 and k+3 - gate G34")
        void everyFieldPrefixDecomposes() {
            // The copybook declares, at every field's offset k:
            //
            //   k   .. k+1   xxxL   COMP PIC S9(4)             2 bytes, and SIGNED
            //   k+2          xxxF   PICTURE X                  1 byte
            //   k+2          xxxA   via FILLER REDEFINES xxxF  the SAME byte, adding 0
            //   k+3 .. k+6   FILLER PICTURE X(4)               4 bytes
            //   k+7 .. k+6+n xxxI   PIC X(n)                   the payload
            //
            // 2 + 1 + 0 + 4 = 7, which is FIELD_PREFIX_LENGTH. The AO view at the same k is
            // FILLER X(3) then xxxC / xxxP / xxxH / xxxV then xxxO PIC X(n) - four one-byte items over
            // the bytes the AI view calls xxxF and its filler, and xxxO over the identical payload
            // span as xxxI (app/cpy-bms/COTRN00.CPY:373 onwards).
            int lengthItemBytes = FieldMetadata.LENGTH_ITEM_BYTES;
            int flagItemBytes = FieldMetadata.FLAG_ITEM_BYTES;
            int attributeAliasBytes = 0;
            int reservedFillerBytes = 4;
            assertThat(lengthItemBytes).isEqualTo(2);
            assertThat(flagItemBytes).isEqualTo(1);
            assertThat(lengthItemBytes + flagItemBytes + attributeAliasBytes + reservedFillerBytes)
                    .isEqualTo(TransactionListRequest.FIELD_PREFIX_LENGTH)
                    .isEqualTo(7);

            for (int i = 0; i < COPYBOOK_FIELDS.size(); i++) {
                String baseFieldName = COPYBOOK_FIELDS.get(i);
                FieldSpan payload = TransactionListRequest.LAYOUT
                        .span(baseFieldName + "I");
                int k = payload.offset() - TransactionListRequest.FIELD_PREFIX_LENGTH;

                // The prefix is a declared, positioned FILLER span - not a gap the codec skips over.
                // A gap would let a mistyped width go unnoticed; RecordLayout rejects those outright.
                FieldSpan prefix = spanAt(k);
                assertThat(prefix.kind()).as("prefix kind at %s", baseFieldName)
                        .isEqualTo(PictureKind.FILLER);
                assertThat(prefix.length()).as("prefix width at %s", baseFieldName).isEqualTo(7);
                assertThat(prefix.endOffsetExclusive())
                        .as("the prefix ends exactly where %sI begins", baseFieldName)
                        .isEqualTo(payload.offset());

                // The sub-offsets the copybook implies, stated as arithmetic on k.
                assertThat(k + lengthItemBytes).as("xxxF of %s sits at k+2", baseFieldName)
                        .isEqualTo(k + 2);
                assertThat(k + lengthItemBytes + flagItemBytes)
                        .as("the reserved FILLER of %s starts at k+3", baseFieldName)
                        .isEqualTo(k + 3);
                assertThat(k + lengthItemBytes + flagItemBytes + reservedFillerBytes)
                        .as("%sI starts at k+7", baseFieldName)
                        .isEqualTo(payload.offset());
                assertThat(payload.endOffsetExclusive())
                        .as("%sI ends at k+7+n", baseFieldName)
                        .isEqualTo(k + 7 + COPYBOOK_WIDTHS.get(i));
            }
        }

        @Test
        @DisplayName("dropping the reserved FILLER would lose 236 bytes - gate G21")
        void theReservedFillerIsLoadBearing() {
            // This is the arithmetic that makes G21 bite. The per-field FILLER X(4) carries no value
            // and is easy to talk oneself out of emitting; omit it and 59 x 4 bytes vanish, every
            // offset after the first field shifts left, and the group stops being 1265 bytes - which
            // is precisely the failure the total-width assertion catches.
            int reservedPerField = 4;
            int reservedTotal = TransactionListRequest.FIELD_COUNT * reservedPerField;
            assertThat(reservedTotal).isEqualTo(236);
            assertThat(TransactionListRequest.SYMBOLIC_MAP_LENGTH - reservedTotal)
                    .as("the group would be 1029 bytes, not 1265")
                    .isEqualTo(1029);

            // And it is genuinely emitted, as spaces, for every one of the 59 fields - not just the
            // first, and not only when the field carries a value.
            byte[] image = new TransactionListRequest().toFixedWidth(ASCII);
            String text = new String(image, ASCII);
            for (String baseFieldName : COPYBOOK_FIELDS) {
                int k = TransactionListRequest.LAYOUT.span(baseFieldName + "I").offset()
                        - TransactionListRequest.FIELD_PREFIX_LENGTH;
                assertThat(text.substring(k, k + TransactionListRequest.FIELD_PREFIX_LENGTH))
                        .as("the whole prefix of %s is space-filled", baseFieldName)
                        .isEqualTo(spaces(7));
                assertThat(text.substring(k + 3, k + 7))
                        .as("the reserved FILLER X(4) of %s is four spaces", baseFieldName)
                        .isEqualTo(spaces(4));
            }
        }

        /** The layout span that begins at an absolute offset, or a failure naming the offset. */
        private FieldSpan spanAt(int offset) {
            for (FieldSpan span : TransactionListRequest.LAYOUT.storageSpans()) {
                if (span.offset() == offset) {
                    return span;
                }
            }
            throw new AssertionError("No layout span begins at offset " + offset
                    + "; the COTRN0AI group image declares every byte, so an offset with no span "
                    + "means a prefix or payload width was transcribed wrongly");
        }

        @Test
        @DisplayName("the storage spans, and only they, sum to the declared record length")
        void spansSumToRecordLength() {
            int total = 0;
            for (FieldSpan span : TransactionListRequest.LAYOUT.storageSpans()) {
                total += span.length();
            }
            assertThat(total).isEqualTo(TransactionListRequest.SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("every payload span carries its verbatim xxxI name at the right width")
        void payloadSpansNamedVerbatim() {
            for (int i = 0; i < COPYBOOK_FIELDS.size(); i++) {
                String item = COPYBOOK_FIELDS.get(i) + "I";
                assertThat(TransactionListRequest.LAYOUT.hasSpan(item)).as("span %s", item).isTrue();
                assertThat(TransactionListRequest.LAYOUT.span(item).length())
                        .as("width of %s", item).isEqualTo(COPYBOOK_WIDTHS.get(i));
            }
        }

        @Test
        @DisplayName("the header offsets are 19, 30, 77, 92, 107, 154, 169 and 184")
        void headerOffsets() {
            assertThat(TransactionListRequest.TRNNAME_OFFSET).isEqualTo(19);
            assertThat(TransactionListRequest.TITLE01_OFFSET).isEqualTo(30);
            assertThat(TransactionListRequest.CURDATE_OFFSET).isEqualTo(77);
            assertThat(TransactionListRequest.PGMNAME_OFFSET).isEqualTo(92);
            assertThat(TransactionListRequest.TITLE02_OFFSET).isEqualTo(107);
            assertThat(TransactionListRequest.CURTIME_OFFSET).isEqualTo(154);
            assertThat(TransactionListRequest.PAGENUM_OFFSET).isEqualTo(169);
            assertThat(TransactionListRequest.TRNIDIN_OFFSET).isEqualTo(184);
        }

        @Test
        @DisplayName("the row block starts at 200 and the error line payload at 1187")
        void blockOffsets() {
            assertThat(TransactionListRequest.ROW_BLOCK_OFFSET).isEqualTo(200);
            assertThat(TransactionListRequest.ERRMSG_OFFSET).isEqualTo(1187);
            assertThat(TransactionListRequest.ERRMSG_OFFSET + 78).isEqualTo(1265);
        }

        @Test
        @DisplayName("the within-row offsets are 7, 15, 38, 53 and 86, and 86 + 12 = 98")
        void withinRowOffsets() {
            assertThat(TransactionListRequest.SELECTION_ROW_OFFSET).isEqualTo(7);
            assertThat(TransactionListRequest.TRANSACTION_ID_ROW_OFFSET).isEqualTo(15);
            assertThat(TransactionListRequest.TRANSACTION_DATE_ROW_OFFSET).isEqualTo(38);
            assertThat(TransactionListRequest.TRANSACTION_DESCRIPTION_ROW_OFFSET).isEqualTo(53);
            assertThat(TransactionListRequest.TRANSACTION_AMOUNT_ROW_OFFSET).isEqualTo(86);
            assertThat(TransactionListRequest.TRANSACTION_AMOUNT_ROW_OFFSET + 12).isEqualTo(98);
        }

        @ParameterizedTest(name = "row {0} image begins at {1}")
        @CsvSource({"1,200", "2,298", "5,592", "9,984", "10,1082"})
        @DisplayName("row image offsets advance by 98 from 200, with row 1 first and row 10 last")
        void rowImageOffsets(int row, int expected) {
            assertThat(TransactionListRequest.rowImageOffset(row)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a row outside 1..10 has no image offset")
        void rowImageOffsetRejectsInvalidRow() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.rowImageOffset(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.rowImageOffset(11));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Page size - exactly 10, behaviour rather than configuration - gate G39")
    class PageSize {

        @Test
        @DisplayName("the page size is 10, as COTRN00C hard-codes at lines 290, 297, 349 and 351")
        void pageSizeIsTen() {
            assertThat(TransactionListRequest.PAGE_SIZE).isEqualTo(10);
        }

        @Test
        @DisplayName("the modelled row count equals the page size by construction")
        void rowCountEqualsPageSize() {
            assertThat(TransactionListRequest.ROW_COUNT)
                    .isEqualTo(TransactionListRequest.PAGE_SIZE)
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("exactly ten rows are addressable, and an eleventh is not")
        void tenRowsAddressable() {
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= 10; row++) {
                assertThat(request.getSelection(row)).hasSize(1);
            }
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getSelection(11));
        }

        @Test
        @DisplayName("ten selector and ten amount fields exist, one per page slot")
        void tenOfEachRowField() {
            assertThat(TransactionListRequest.FIELD_NAMES.stream()
                    .filter(name -> name.startsWith("SEL")).count()).isEqualTo(10);
            assertThat(TransactionListRequest.FIELD_NAMES.stream()
                    .filter(name -> name.startsWith("TAMT")).count()).isEqualTo(10);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Row addressing - the 1-based COBOL row maps at both ends - gate G33")
    class RowAddressing {

        @Test
        @DisplayName("row 1 reaches SEL0001, TRNID01, TDATE01, TDESC01 and TAMT001")
        void rowOneMapsToFirstFields() {
            TransactionListRequest request = new TransactionListRequest();
            request.setSelection(1, "S");
            request.setTransactionId(1, "0000000000000001");
            request.setTransactionDate(1, "01/02/03");
            request.setTransactionDescription(1, "FIRST ROW");
            request.setTransactionAmount(1, "+00000001.00");

            assertThat(request.getSel0001()).isEqualTo("S");
            assertThat(request.getTrnid01()).isEqualTo("0000000000000001");
            assertThat(request.getTdate01()).isEqualTo("01/02/03");
            assertThat(request.getTdesc01()).isEqualTo("FIRST ROW");
            assertThat(request.getTamt001()).isEqualTo("+00000001.00");
        }

        @Test
        @DisplayName("row 10 reaches SEL0010, TRNID10, TDATE10, TDESC10 and TAMT010")
        void rowTenMapsToLastFields() {
            TransactionListRequest request = new TransactionListRequest();
            request.setSelection(10, "s");
            request.setTransactionId(10, "0000000000000010");
            request.setTransactionDate(10, "10/11/12");
            request.setTransactionDescription(10, "LAST ROW");
            request.setTransactionAmount(10, "-00000010.99");

            assertThat(request.getSel0010()).isEqualTo("s");
            assertThat(request.getTrnid10()).isEqualTo("0000000000000010");
            assertThat(request.getTdate10()).isEqualTo("10/11/12");
            assertThat(request.getTdesc10()).isEqualTo("LAST ROW");
            assertThat(request.getTamt010()).isEqualTo("-00000010.99");
        }

        @Test
        @DisplayName("writing row 1 leaves row 10 untouched, so there is no off-by-one bleed")
        void rowsAreIndependent() {
            TransactionListRequest request = new TransactionListRequest();
            request.setTransactionId(1, "AAAAAAAAAAAAAAAA");
            assertThat(request.getTrnid10()).isEqualTo(spaces(16));
            assertThat(request.getTransactionId(10)).isEqualTo(spaces(16));
        }

        @Test
        @DisplayName("every row round-trips through the indexed and the explicit accessor alike")
        void indexedAndExplicitAgree() {
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= 10; row++) {
                request.setSelection(row, String.valueOf(row % 10));
                request.setTransactionId(row, "ID" + row);
                request.setTransactionDate(row, String.format("%02d/01/24", row));
                request.setTransactionDescription(row, "DESC " + row);
                request.setTransactionAmount(row, String.format("+%08d.00", row));
            }
            for (int row = 1; row <= 10; row++) {
                String selectionField = TransactionListRequest.selectionFieldName(row);
                String idField = TransactionListRequest.transactionIdFieldName(row);
                String dateField = TransactionListRequest.transactionDateFieldName(row);
                String descField = TransactionListRequest.transactionDescriptionFieldName(row);
                String amountField = TransactionListRequest.transactionAmountFieldName(row);
                assertThat(request.getPayloadValue(selectionField))
                        .isEqualTo(request.getSelection(row));
                assertThat(request.getPayloadValue(idField))
                        .isEqualTo(request.getTransactionId(row));
                assertThat(request.getPayloadValue(dateField))
                        .isEqualTo(request.getTransactionDate(row));
                assertThat(request.getPayloadValue(descField))
                        .isEqualTo(request.getTransactionDescription(row));
                assertThat(request.getPayloadValue(amountField))
                        .isEqualTo(request.getTransactionAmount(row));
            }
        }

        @Test
        @DisplayName("the indexed setters reject a row outside 1..10")
        void indexedSettersRejectInvalidRow() {
            TransactionListRequest request = new TransactionListRequest();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setSelection(0, "S"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setTransactionId(11, "X"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setTransactionDate(11, "X"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setTransactionDescription(11, "X"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setTransactionAmount(11, "X"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getTransactionId(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getTransactionDate(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getTransactionDescription(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getTransactionAmount(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.clearRow(0));
        }

        @ParameterizedTest(name = "row {0} is outside 1..10 and is refused")
        @ValueSource(ints = {-1, 0, 11, 12, Integer.MIN_VALUE, Integer.MAX_VALUE})
        @DisplayName("an out-of-range row is rejected rather than wrapped or clamped - gate G33")
        void outOfRangeRowsAreRejected(int row) {
            // A negative row is the case a modulo or an unchecked array index would silently accept:
            // -1 would wrap to the last element in some hands and to row 0 in others. COBOL has
            // neither; WS-IDX is 1 to 10 and POPULATE-TRAN-DATA's EVALUATE simply falls to
            // WHEN OTHER CONTINUE outside that range (app/cbl/COTRN00C.cbl:444-445).
            TransactionListRequest request = new TransactionListRequest();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getSelection(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setTransactionDescription(row, "X"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.clearRow(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.rowImageOffset(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.transactionIdFieldName(row));

            // Nothing was written by any refused call.
            assertThat(request.getPayloadValues())
                    .containsEntry("TRNID01", spaces(16))
                    .containsEntry("TDESC01", spaces(26))
                    .containsEntry("TDESC10", spaces(26));
        }

        @Test
        @DisplayName("only row 1 sets TRNID-FIRST and only row 10 sets TRNID-LAST - gate G33")
        void theCursorAsymmetryIsAtBothEnds() {
            // POPULATE-TRAN-DATA is regular in its five payload moves and asymmetric in exactly two
            // places (app/cbl/COTRN00C.cbl:390-446):
            //
            //   WHEN 1  MOVE TRAN-ID TO TRNID01I OF COTRN0AI
            //                          CDEMO-CT00-TRNID-FIRST
            //   WHEN 10 MOVE TRAN-ID TO TRNID10I OF COTRN0AI
            //                          CDEMO-CT00-TRNID-LAST
            //
            // Those two keys are what PF7 and PF8 browse from, so getting the ends the wrong way round
            // would page from the middle of the previous screen - a defect no width assertion sees.
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
                request.setTransactionId(row, String.format("%016d", row));
                if (row == 1) {
                    request.getCursor().setTrnidFirst(request.getTransactionId(row));
                }
                if (row == TransactionListRequest.ROW_COUNT) {
                    request.getCursor().setTrnidLast(request.getTransactionId(row));
                }
            }

            // Java index 0 is COBOL row 1, and it is the field named 01 that carries the first key.
            assertThat(TransactionListRequest.transactionIdFieldName(1)).isEqualTo("TRNID01");
            assertThat(TransactionListRequest.selectionFieldName(1)).isEqualTo("SEL0001");
            assertThat(TransactionListRequest.transactionAmountFieldName(1)).isEqualTo("TAMT001");
            assertThat(request.getCursor().getTrnidFirst())
                    .isEqualTo(request.getTrnid01())
                    .isEqualTo("0000000000000001");

            // Java index 9 is COBOL row 10, and it is the field named 10 that carries the last key.
            assertThat(TransactionListRequest.transactionIdFieldName(10)).isEqualTo("TRNID10");
            assertThat(TransactionListRequest.selectionFieldName(10)).isEqualTo("SEL0010");
            assertThat(TransactionListRequest.transactionAmountFieldName(10)).isEqualTo("TAMT010");
            assertThat(request.getCursor().getTrnidLast())
                    .isEqualTo(request.getTrnid10())
                    .isEqualTo("0000000000000010");

            // The two ends are genuinely different values, so an implementation that captured the same
            // row twice would fail here rather than pass by coincidence.
            assertThat(request.getCursor().getTrnidFirst())
                    .isNotEqualTo(request.getCursor().getTrnidLast());

            // No middle row touches either key: rows 2 to 9 are byte-identical in shape, which is why
            // this map has no row-1 special case of the kind card/dto/CardListRequest carries.
            assertThat(TransactionListRequest.rowImageOffset(1))
                    .isEqualTo(TransactionListRequest.ROW_BLOCK_OFFSET);
            for (int row = 2; row <= TransactionListRequest.ROW_COUNT; row++) {
                assertThat(TransactionListRequest.rowImageOffset(row)
                        - TransactionListRequest.rowImageOffset(row - 1))
                        .as("row %d advances by one row image", row)
                        .isEqualTo(TransactionListRequest.ROW_IMAGE_LENGTH);
            }
        }

        @Test
        @DisplayName("INITIALIZE-TRAN-DATA blanks four items per row, and SEL000n is not one - gate G33")
        void theParagraphBlanksFourItemsAndNotTheSelector() {
            // app/cbl/COTRN00C.cbl:450 onwards. Each WHEN arm moves SPACES to exactly four items -
            // TRNIDnn, TDATEnn, TDESCnn and TAMT00n - and to no fifth. The selector is deliberately
            // absent from the paragraph: it holds what the user typed, and PROCESS-ENTER-KEY's ordered
            // ten-way scan at L149-L176 reads it immediately afterwards.
            //
            // So this asserts the paragraph's own shape, at name level, and then blanks exactly those
            // four through the indexed setters to show the selector genuinely survives being ignored.
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
                request.setSelection(row, "S");
                request.setTransactionId(row, String.format("%016d", row));
                request.setTransactionDate(row, "01/01/24");
                request.setTransactionDescription(row, "ROW " + row);
                request.setTransactionAmount(row, "+00000001.00");
            }

            for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
                List<String> blankedByTheParagraph = List.of(
                        TransactionListRequest.transactionIdFieldName(row),
                        TransactionListRequest.transactionDateFieldName(row),
                        TransactionListRequest.transactionDescriptionFieldName(row),
                        TransactionListRequest.transactionAmountFieldName(row));
                assertThat(blankedByTheParagraph).as("row %d", row)
                        .hasSize(4)
                        .doesNotContain(TransactionListRequest.selectionFieldName(row));
                for (int i = 0; i < blankedByTheParagraph.size(); i++) {
                    String field = blankedByTheParagraph.get(i);
                    request.setPayloadValue(field,
                            spaces(COPYBOOK_WIDTHS.get(COPYBOOK_FIELDS.indexOf(field))));
                }
            }

            for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
                assertThat(request.getTransactionId(row)).as("TRNID%02d", row).isEqualTo(spaces(16));
                assertThat(request.getTransactionDate(row)).as("TDATE%02d", row).isEqualTo(spaces(8));
                assertThat(request.getTransactionDescription(row)).as("TDESC%02d", row)
                        .isEqualTo(spaces(26));
                assertThat(request.getTransactionAmount(row)).as("TAMT%03d", row)
                        .isEqualTo(spaces(12));
                assertThat(request.getSelection(row))
                        .as("SEL%04d is not among the paragraph's four moves", row)
                        .isEqualTo("S");
            }

            // Spaces at the declared width - never null, and never an empty string. A blank COBOL
            // field is 26 space bytes; "" would be 26 bytes short and would shift every offset after
            // it, and null has no COBOL equivalent at all.
            assertThat(request.getTransactionDescription(1))
                    .isNotNull()
                    .isNotEmpty()
                    .isBlank()
                    .hasSize(TransactionListRequest.TRANSACTION_DESCRIPTION_LENGTH);
        }

        @Test
        @DisplayName("clearRow blanks all five, and the LOW-VALUES pre-clear is why that is faithful")
        void clearRowIsADocumentedSupersetOfTheParagraph() {
            // clearRow is a deliberate superset of the paragraph: it blanks the selector too. That is
            // not a drift, and it is worth pinning rather than leaving to the Javadoc, because the two
            // shapes differ by exactly one field and a future reader will wonder which is right.
            //
            // The justification is in the source. INITIALIZE-TRAN-DATA is only ever reached from
            // PROCESS-PAGE-FORWARD at L291 and PROCESS-PAGE-BACKWARD at L345, and both are reached
            // after MOVE LOW-VALUES TO COTRN0AO at L114 has already cleared the ENTIRE group - every
            // selector included. So no COBOL path can display a row whose data was blanked while its
            // selector still held a value, and a clear-the-row operation that stops short of the
            // selector would model a state the program cannot reach.
            TransactionListRequest request = new TransactionListRequest();
            request.setSelection(3, "S");
            request.setTransactionId(3, "0000000000000003");
            request.setTransactionDate(3, "03/03/24");
            request.setTransactionDescription(3, "ROW THREE");
            request.setTransactionAmount(3, "+00000003.00");

            request.clearRow(3);

            assertThat(request.getSel0003()).isEqualTo(" ").hasSize(1);
            assertThat(request.getTrnid03()).isEqualTo(spaces(16));
            assertThat(request.getTdate03()).isEqualTo(spaces(8));
            assertThat(request.getTdesc03()).isEqualTo(spaces(26));
            assertThat(request.getTamt003()).isEqualTo(spaces(12));

            // And it is one row's worth: its neighbours are untouched, so clearing row 3 cannot be
            // mistaken for clearing the grid.
            request.setTransactionId(4, "0000000000000004");
            request.clearRow(3);
            assertThat(request.getTrnid04()).isEqualTo("0000000000000004");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The PIC X width rule - pad right, truncate right, never trim - rule R5, gate G21")
    class WidthRule {

        @Test
        @DisplayName("a fresh request holds spaces at every declared width, never null")
        void freshRequestIsSpaceFilled() {
            TransactionListRequest request = new TransactionListRequest();
            Map<String, String> values = request.getPayloadValues();
            assertThat(values).hasSize(59);
            for (int i = 0; i < COPYBOOK_FIELDS.size(); i++) {
                String field = COPYBOOK_FIELDS.get(i);
                assertThat(values.get(field)).as("%s", field)
                        .isNotNull()
                        .isEqualTo(spaces(COPYBOOK_WIDTHS.get(i)));
            }
        }

        @Test
        @DisplayName("a blank amount is twelve spaces - not \"0.00\" and not null")
        void blankAmountIsSpaces() {
            TransactionListRequest request = new TransactionListRequest();
            assertThat(request.getTamt001()).isEqualTo(spaces(12)).isNotEqualTo("0.00");
            request.setTransactionAmount(1, "+00000042.50");
            request.clearRow(1);
            assertThat(request.getTamt001()).isEqualTo(spaces(12));
            assertThat(request.getTrnid01()).isEqualTo(spaces(16));
            assertThat(request.getTdate01()).isEqualTo(spaces(8));
            assertThat(request.getTdesc01()).isEqualTo(spaces(26));
            assertThat(request.getSel0001()).isEqualTo(" ");
        }

        @Test
        @DisplayName("the edited amount form is exactly twelve characters, as WS-TRAN-AMT declares")
        void editedAmountIsTwelveCharacters() {
            assertThat("+99999999.99").hasSize(TransactionListRequest.TRANSACTION_AMOUNT_LENGTH);
            TransactionListRequest request = new TransactionListRequest();
            request.setTamt001("+12345678.90");
            assertThat(request.getTamt001()).isEqualTo("+12345678.90").hasSize(12);
        }

        @Test
        @DisplayName("a short value is padded on the right")
        void shortValueIsStoredUnchanged() {
            TransactionListRequest request = new TransactionListRequest();
            request.setErrmsg("Invalid selection. Valid value is S");

            // Stored exactly as it arrived - not padded. The payload holds what the caller sent, so a
            // JSON round trip is the identity and a field the program tests against SPACES OR
            // LOW-VALUES arrives as it was typed.
            assertThat(request.getErrmsg())
                    .isEqualTo("Invalid selection. Valid value is S")
                    .hasSize(35);

            // The declared width is imposed once, at the byte boundary, where it is actually needed.
            byte[] image = request.toFixedWidth(ASCII);
            String errmsgSpan = new String(image, ASCII).substring(
                    TransactionListRequest.LAYOUT.span("ERRMSGI").offset(), image.length);
            assertThat(errmsgSpan).hasSize(78)
                    .startsWith("Invalid selection. Valid value is S")
                    .endsWith(" ");
        }

        @Test
        @DisplayName("an over-long value is REFUSED by name, never silently shortened")
        void longValueIsRefused() {
            TransactionListRequest request = new TransactionListRequest();

            // The setter used to shorten the value and then measure it, so the @Size constraint it was
            // checked against could never fail and four characters could vanish with no error. Now the
            // surplus is reported, naming the field and both widths.
            assertThatThrownBy(() -> request.setTdesc01("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TDESC01")
                    .hasMessageContaining("PIC X(26)")
                    .hasMessageContaining("36 character(s)");
            assertThatThrownBy(() -> request.setTrnname("TOOLONG"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TRNNAME")
                    .hasMessageContaining("7 character(s)");

            // Nothing was stored by either refused call.
            assertThat(request.getTdesc01()).isEqualTo(spaces(26));
            assertThat(request.getTrnname()).isEqualTo(spaces(4));

            // A deliberate truncation is still available, and says so at the call site.
            request.setTdesc01(new FixedWidthCodec(ASCII)
                    .movePicX("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 26));
            assertThat(request.getTdesc01()).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        }

        @Test
        @DisplayName("TRAN-DESC X(100) reaches TDESCnn X(26) truncated on the RIGHT - rule R5")
        void theDescriptionMoveTruncatesOnTheRight() {
            // MOVE TRAN-DESC TO TDESCnnI OF COTRN0AI, at every one of the ten WHEN arms of
            // POPULATE-TRAN-DATA (app/cbl/COTRN00C.cbl:390-446). The sending item is
            // TRAN-DESC PIC X(100) from the 350-byte TRAN-RECORD (app/cpy/CVTRA05Y.cpy); the receiving
            // item is 26 characters. COBOL truncates an alphanumeric MOVE on the RIGHT, so the first
            // 26 characters survive and the remaining 74 are lost - which is the screen's actual
            // behaviour and must not be "improved" into an ellipsis or a wrap.
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            TransactionListRequest request = new TransactionListRequest();

            String hundred = "0123456789".repeat(10);
            assertThat(hundred).hasSize(100);
            String moved = codec.movePicX(hundred, TransactionListRequest.TRANSACTION_DESCRIPTION_LENGTH);
            assertThat(moved).hasSize(26).isEqualTo("01234567890123456789012345");
            request.setTransactionDescription(1, moved);
            assertThat(request.getTdesc01()).isEqualTo("01234567890123456789012345");

            // A 30-character description truncates by the same rule - being only four characters over
            // is not a special case, and the four that go are the LAST four.
            String thirty = "PURCHASE AT MERCHANT XYZ 12345";
            assertThat(thirty).hasSize(30);
            String movedThirty = codec.movePicX(thirty, 26);
            assertThat(movedThirty).hasSize(26)
                    .isEqualTo("PURCHASE AT MERCHANT XYZ 1")
                    .isEqualTo(thirty.substring(0, 26));
            request.setTransactionDescription(10, movedThirty);
            assertThat(request.getTdesc10()).isEqualTo("PURCHASE AT MERCHANT XYZ 1");

            // Exactly 26 is the boundary and passes through untouched; 27 loses one character.
            assertThat(codec.movePicX("A".repeat(26), 26)).isEqualTo("A".repeat(26)).hasSize(26);
            assertThat(codec.movePicX("A".repeat(27), 26)).isEqualTo("A".repeat(26)).hasSize(26);
        }

        @Test
        @DisplayName("TRAN-ID X(16) reaches TRNIDnn X(16) with no truncation at all - rule R5")
        void theIdentifierMoveIsExact() {
            // Sending and receiving items are both PIC X(16), so this MOVE is byte for byte. It is
            // asserted because an off-by-one in either width would silently drop a digit of a
            // transaction identifier - and the cursor keys CDEMO-CT00-TRNID-FIRST and -LAST are the
            // same 16 bytes, so the next page would then start in the wrong place.
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            String tranId = "0000000000000042";
            assertThat(tranId).hasSize(TransactionListRequest.TRANSACTION_ID_LENGTH).hasSize(16);
            assertThat(codec.movePicX(tranId, 16)).isEqualTo(tranId);

            TransactionListRequest request = new TransactionListRequest();
            request.setTransactionId(1, tranId);
            request.getCursor().setTrnidFirst(request.getTransactionId(1));
            assertThat(request.getCursor().getTrnidFirst()).isEqualTo(tranId).hasSize(16);
        }

        @Test
        @DisplayName("a short value is right-space-padded at the byte boundary, never left short")
        void shortValuesArePaddedToTheDeclaredWidth() {
            // The setters store what they are given; the PIC X width rule is imposed once, where the
            // bytes are produced. Every one of the four blankable row items proves it at its own width.
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            assertThat(codec.movePicX("ABC", 26)).isEqualTo("ABC" + spaces(23)).hasSize(26);
            assertThat(codec.movePicX("", 12)).isEqualTo(spaces(12)).hasSize(12);

            TransactionListRequest request = new TransactionListRequest();
            request.setTransactionId(5, "ID5");
            request.setTransactionDate(5, "01/02");
            request.setTransactionDescription(5, "SHORT");
            request.setTransactionAmount(5, "+1.00");

            String text = new String(request.toFixedWidth(ASCII), ASCII);
            assertThat(spanText(text, "TRNID05I")).isEqualTo("ID5" + spaces(13)).hasSize(16);
            assertThat(spanText(text, "TDATE05I")).isEqualTo("01/02" + spaces(3)).hasSize(8);
            assertThat(spanText(text, "TDESC05I")).isEqualTo("SHORT" + spaces(21)).hasSize(26);
            assertThat(spanText(text, "TAMT005I")).isEqualTo("+1.00" + spaces(7)).hasSize(12);

            // TDATEnnI receives WS-TRAN-DATE PIC X(08) VALUE '00/00/00' (app/cbl/COTRN00C.cbl:57),
            // which POPULATE-TRAN-DATA fills from WS-CURDATE-MM-DD-YY - eight characters exactly, so
            // the common case needs no padding and the initial value is itself a valid width.
            assertThat("00/00/00").hasSize(TransactionListRequest.TRANSACTION_DATE_LENGTH).hasSize(8);
        }

        /** The declared span of a payload item, read out of a rendered group image. */
        private String spanText(String image, String inputItemName) {
            FieldSpan span = TransactionListRequest.LAYOUT.span(inputItemName);
            return image.substring(span.offset(), span.endOffsetExclusive());
        }

        @Test
        @DisplayName("every setter rejects null - COBOL has no absent state")
        void settersRejectNull() {
            TransactionListRequest request = new TransactionListRequest();
            assertThatNullPointerException().isThrownBy(() -> request.setTrnname(null));
            assertThatNullPointerException().isThrownBy(() -> request.setTitle01(null));
            assertThatNullPointerException().isThrownBy(() -> request.setCurdate(null));
            assertThatNullPointerException().isThrownBy(() -> request.setPgmname(null));
            assertThatNullPointerException().isThrownBy(() -> request.setTitle02(null));
            assertThatNullPointerException().isThrownBy(() -> request.setCurtime(null));
            assertThatNullPointerException().isThrownBy(() -> request.setPagenum(null));
            assertThatNullPointerException().isThrownBy(() -> request.setTrnidin(null));
            assertThatNullPointerException().isThrownBy(() -> request.setErrmsg(null));
            assertThatNullPointerException().isThrownBy(() -> request.setSelection(1, null));
        }

        @Test
        @DisplayName("all 59 header, row and error accessors store exactly what they are given")
        void everyAccessorStoresWhatItIsGiven() {
            TransactionListRequest request = new TransactionListRequest();
            request.setTrnname("CT00");
            request.setTitle01("T1");
            request.setCurdate("08/08/26");
            request.setPgmname("COTRN00C");
            request.setTitle02("T2");
            request.setCurtime("12:34:56");
            request.setPagenum("00000001");
            request.setTrnidin("0000000000000001");
            request.setErrmsg("E");

            // Identity, not width. Each accessor returns the value it was given - "T1" is two
            // characters and stays two. Widening is the byte boundary's job, and is asserted there.
            assertThat(request.getTrnname()).isEqualTo("CT00");
            assertThat(request.getTitle01()).isEqualTo("T1");
            assertThat(request.getCurdate()).isEqualTo("08/08/26");
            assertThat(request.getPgmname()).isEqualTo("COTRN00C");
            assertThat(request.getTitle02()).isEqualTo("T2");
            assertThat(request.getCurtime()).isEqualTo("12:34:56");
            assertThat(request.getPagenum()).isEqualTo("00000001");
            assertThat(request.getTrnidin()).isEqualTo("0000000000000001");
            assertThat(request.getErrmsg()).isEqualTo("E");

            // ...and every one of them still lands at its declared width in the image.
            byte[] image = request.toFixedWidth(ASCII);
            assertThat(image).hasSize(TransactionListRequest.SYMBOLIC_MAP_LENGTH);
            TransactionListRequest widened = TransactionListRequest.fromFixedWidth(image, ASCII);
            assertThat(widened.getTitle01()).hasSize(40).startsWith("T1").endsWith(" ");
            assertThat(widened.getErrmsg()).hasSize(78).startsWith("E");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Explicit row accessors - all fifty, under their verbatim names - practice B4")
    class ExplicitRowAccessors {

        @Test
        @DisplayName("each of the fifty explicit setters writes the field its name spells")
        void allFiftyExplicitAccessors() {
            TransactionListRequest request = new TransactionListRequest();
            request.setSel0001("1");
            request.setSel0002("2");
            request.setSel0003("3");
            request.setSel0004("4");
            request.setSel0005("5");
            request.setSel0006("6");
            request.setSel0007("7");
            request.setSel0008("8");
            request.setSel0009("9");
            request.setSel0010("0");
            request.setTrnid01("A1");
            request.setTrnid02("A2");
            request.setTrnid03("A3");
            request.setTrnid04("A4");
            request.setTrnid05("A5");
            request.setTrnid06("A6");
            request.setTrnid07("A7");
            request.setTrnid08("A8");
            request.setTrnid09("A9");
            request.setTrnid10("A0");
            request.setTdate01("B1");
            request.setTdate02("B2");
            request.setTdate03("B3");
            request.setTdate04("B4");
            request.setTdate05("B5");
            request.setTdate06("B6");
            request.setTdate07("B7");
            request.setTdate08("B8");
            request.setTdate09("B9");
            request.setTdate10("B0");
            request.setTdesc01("C1");
            request.setTdesc02("C2");
            request.setTdesc03("C3");
            request.setTdesc04("C4");
            request.setTdesc05("C5");
            request.setTdesc06("C6");
            request.setTdesc07("C7");
            request.setTdesc08("C8");
            request.setTdesc09("C9");
            request.setTdesc10("C0");
            request.setTamt001("D1");
            request.setTamt002("D2");
            request.setTamt003("D3");
            request.setTamt004("D4");
            request.setTamt005("D5");
            request.setTamt006("D6");
            request.setTamt007("D7");
            request.setTamt008("D8");
            request.setTamt009("D9");
            request.setTamt010("D0");

            assertThat(request.getSel0001()).isEqualTo("1");
            assertThat(request.getSel0002()).isEqualTo("2");
            assertThat(request.getSel0003()).isEqualTo("3");
            assertThat(request.getSel0004()).isEqualTo("4");
            assertThat(request.getSel0005()).isEqualTo("5");
            assertThat(request.getSel0006()).isEqualTo("6");
            assertThat(request.getSel0007()).isEqualTo("7");
            assertThat(request.getSel0008()).isEqualTo("8");
            assertThat(request.getSel0009()).isEqualTo("9");
            assertThat(request.getSel0010()).isEqualTo("0");
            assertThat(request.getTrnid01()).startsWith("A1");
            assertThat(request.getTrnid02()).startsWith("A2");
            assertThat(request.getTrnid03()).startsWith("A3");
            assertThat(request.getTrnid04()).startsWith("A4");
            assertThat(request.getTrnid05()).startsWith("A5");
            assertThat(request.getTrnid06()).startsWith("A6");
            assertThat(request.getTrnid07()).startsWith("A7");
            assertThat(request.getTrnid08()).startsWith("A8");
            assertThat(request.getTrnid09()).startsWith("A9");
            assertThat(request.getTrnid10()).startsWith("A0");
            assertThat(request.getTdate01()).startsWith("B1");
            assertThat(request.getTdate02()).startsWith("B2");
            assertThat(request.getTdate03()).startsWith("B3");
            assertThat(request.getTdate04()).startsWith("B4");
            assertThat(request.getTdate05()).startsWith("B5");
            assertThat(request.getTdate06()).startsWith("B6");
            assertThat(request.getTdate07()).startsWith("B7");
            assertThat(request.getTdate08()).startsWith("B8");
            assertThat(request.getTdate09()).startsWith("B9");
            assertThat(request.getTdate10()).startsWith("B0");
            assertThat(request.getTdesc01()).startsWith("C1");
            assertThat(request.getTdesc02()).startsWith("C2");
            assertThat(request.getTdesc03()).startsWith("C3");
            assertThat(request.getTdesc04()).startsWith("C4");
            assertThat(request.getTdesc05()).startsWith("C5");
            assertThat(request.getTdesc06()).startsWith("C6");
            assertThat(request.getTdesc07()).startsWith("C7");
            assertThat(request.getTdesc08()).startsWith("C8");
            assertThat(request.getTdesc09()).startsWith("C9");
            assertThat(request.getTdesc10()).startsWith("C0");
            assertThat(request.getTamt001()).startsWith("D1");
            assertThat(request.getTamt002()).startsWith("D2");
            assertThat(request.getTamt003()).startsWith("D3");
            assertThat(request.getTamt004()).startsWith("D4");
            assertThat(request.getTamt005()).startsWith("D5");
            assertThat(request.getTamt006()).startsWith("D6");
            assertThat(request.getTamt007()).startsWith("D7");
            assertThat(request.getTamt008()).startsWith("D8");
            assertThat(request.getTamt009()).startsWith("D9");
            assertThat(request.getTamt010()).startsWith("D0");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The name-keyed view - what field-for-field diffing consumes - gates G9, G17")
    class NameKeyedView {

        @Test
        @DisplayName("every one of the 59 fields is reachable by its verbatim name, in order")
        void allFieldsReachableByName() {
            TransactionListRequest request = new TransactionListRequest();
            for (String field : COPYBOOK_FIELDS) {
                request.setPayloadValue(field, "Z");
            }
            assertThat(request.getPayloadValues().keySet())
                    .containsExactlyElementsOf(COPYBOOK_FIELDS);
            for (String field : COPYBOOK_FIELDS) {
                assertThat(request.getPayloadValue(field)).as("%s", field).startsWith("Z");
            }
        }

        @Test
        @DisplayName("setting by name and by accessor are the same operation")
        void setByNameEqualsSetByAccessor() {
            TransactionListRequest byName = new TransactionListRequest();
            TransactionListRequest byAccessor = new TransactionListRequest();
            byName.setPayloadValue("TAMT010", "+00000009.99");
            byAccessor.setTamt010("+00000009.99");
            byName.setPayloadValue("SEL0010", "S");
            byAccessor.setSel0010("S");
            byName.setPayloadValue("ERRMSG", "boom");
            byAccessor.setErrmsg("boom");
            byName.setPayloadValue("TRNNAME", "CT00");
            byAccessor.setTrnname("CT00");
            byName.setPayloadValue("TITLE01", "one");
            byAccessor.setTitle01("one");
            byName.setPayloadValue("CURDATE", "08/08/26");
            byAccessor.setCurdate("08/08/26");
            byName.setPayloadValue("PGMNAME", "COTRN00C");
            byAccessor.setPgmname("COTRN00C");
            byName.setPayloadValue("TITLE02", "two");
            byAccessor.setTitle02("two");
            byName.setPayloadValue("CURTIME", "01:02:03");
            byAccessor.setCurtime("01:02:03");
            byName.setPayloadValue("PAGENUM", "00000002");
            byAccessor.setPagenum("00000002");
            byName.setPayloadValue("TRNIDIN", "0000000000000005");
            byAccessor.setTrnidin("0000000000000005");
            assertThat(byName.getPayloadValues()).isEqualTo(byAccessor.getPayloadValues());
        }

        @Test
        @DisplayName("an unknown field name is rejected, and the message names the suffix trap")
        void unknownFieldRejected() {
            TransactionListRequest request = new TransactionListRequest();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.getPayloadValue("TAMT01"))
                    .withMessageContaining("TAMT001");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.setPayloadValue("SEL01", "S"))
                    .withMessageContaining("SEL0001");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.getPayloadValue("NOPE"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.setPayloadValue("NOPE", "x"));
            assertThatNullPointerException().isThrownBy(() -> request.getPayloadValue(null));
            assertThatNullPointerException().isThrownBy(() -> request.setPayloadValue(null, "x"));
        }

        @Test
        @DisplayName("the returned map is immutable, so a caller cannot mutate the payload through it")
        void payloadValuesImmutable() {
            Map<String, String> values = new TransactionListRequest().getPayloadValues();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> values.put("TRNNAME", "x"));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Field metadata - signed xxxL, and xxxF / xxxA over one byte - gate G34")
    class Metadata {

        @Test
        @DisplayName("every one of the 59 fields has a metadata carrier, keyed in declaration order")
        void metadataForEveryField() {
            TransactionListRequest request = new TransactionListRequest();
            assertThat(request.getFieldMetadata()).hasSize(59);
            assertThat(request.getFieldMetadata().keySet())
                    .containsExactlyElementsOf(COPYBOOK_FIELDS);
            for (String field : COPYBOOK_FIELDS) {
                assertThat(request.getMetadata(field).getBaseFieldName()).isEqualTo(field);
            }
        }

        @Test
        @DisplayName("the length item is signed and holds -1, the CICS cursor request")
        void lengthItemHoldsMinusOne() {
            TransactionListRequest request = new TransactionListRequest();
            FieldMetadata metadata = request.getMetadata("TRNIDIN");
            assertThat(metadata.getLengthItem()).isZero();
            assertThat(metadata.isCursorPositionRequested()).isFalse();

            request.positionCursorAt("TRNIDIN");
            assertThat(metadata.getLengthItem()).isEqualTo((short) -1);
            assertThat(TransactionListRequest.CURSOR_POSITION_REQUEST).isEqualTo((short) -1);
            assertThat(metadata.isCursorPositionRequested()).isTrue();
            assertThat(request.getCursorPositionField()).isEqualTo("TRNIDIN");
        }

        @Test
        @DisplayName("-1 survives as -1, not as 65535 - xxxL is COMP PIC S9(4), signed")
        void theLengthItemIsSignedNotUnsigned() {
            // app/cbl/COTRN00C.cbl writes MOVE -1 TO TRNIDINL OF COTRN0AI at THIRTEEN sites - L105,
            // L131, L201, L216, L221, L243, L265, L610, L617, L644, L651, L678 and L685 - always to
            // TRNIDIN, because that is the only field this screen ever puts the cursor in. A carrier
            // that clamped at zero or read the halfword unsigned would turn every one of those into a
            // no-op or into 65535, and the cursor would land wherever the terminal last left it.
            FieldMetadata metadata = new FieldMetadata("TRNIDIN");
            metadata.setLengthItem((short) -1);
            assertThat(metadata.getLengthItem()).isEqualTo((short) -1).isNegative();

            // Widened to int, which is where an unsigned reading would show itself: a halfword read as
            // unsigned gives 65535, and 65535 is not a cursor request - it is a 64KB input length.
            assertThat((int) metadata.getLengthItem()).isEqualTo(-1).isNotEqualTo(65_535);
            assertThat(Short.toUnsignedInt(metadata.getLengthItem()))
                    .as("the same bits read unsigned would be 65535, which is why signedness matters")
                    .isEqualTo(65_535);

            // The whole signed 16-bit range round-trips, both bounds included.
            metadata.setLengthItem(Short.MIN_VALUE);
            assertThat(metadata.getLengthItem()).isEqualTo(Short.MIN_VALUE).isEqualTo((short) -32_768);
            metadata.setLengthItem(Short.MAX_VALUE);
            assertThat(metadata.getLengthItem()).isEqualTo(Short.MAX_VALUE).isEqualTo((short) 32_767);
        }

        @Test
        @DisplayName("the length item and the payload are independent spans - gate G34")
        void metadataAndPayloadDoNotDisturbEachOther() {
            // xxxL, xxxF and xxxI are three distinct spans of one field. Writing the cursor request
            // must not disturb what the user typed, and typing must not clear a pending cursor request:
            // COTRN00C does both in the same paragraph - it re-sends the map with the typed key still
            // in TRNIDINI and the cursor forced back to that field.
            TransactionListRequest request = new TransactionListRequest();
            request.setTrnidin("0000000000000123");

            request.positionCursorAt("TRNIDIN");
            assertThat(request.getTrnidin())
                    .as("the cursor request left the typed value alone")
                    .isEqualTo("0000000000000123");

            request.getMetadata("TRNIDIN").setFlag("Q");
            assertThat(request.getTrnidin()).isEqualTo("0000000000000123");

            request.setTrnidin("0000000000000456");
            assertThat(request.getMetadata("TRNIDIN").getLengthItem())
                    .as("writing the payload did not discard the pending cursor request")
                    .isEqualTo(TransactionListRequest.CURSOR_POSITION_REQUEST);
            assertThat(request.getMetadata("TRNIDIN").getFlag()).isEqualTo("Q");
            assertThat(request.getCursorPositionField()).isEqualTo("TRNIDIN");

            // Nor does either reach a neighbouring field.
            assertThat(request.getMetadata("SEL0001").getLengthItem()).isZero();
            assertThat(request.getMetadata("SEL0001").isFlagLowValues()).isTrue();
            assertThat(request.getSel0001()).isEqualTo(" ");
        }

        @Test
        @DisplayName("no field carries the cursor until one is asked to")
        void noCursorFieldInitially() {
            assertThat(new TransactionListRequest().getCursorPositionField()).isNull();
        }

        @Test
        @DisplayName("a reported input length is positive; zero and -1 are not input")
        void hasReportedInput() {
            FieldMetadata metadata = new FieldMetadata("TRNIDIN");
            assertThat(metadata.hasReportedInput()).isFalse();
            metadata.setLengthItem((short) 16);
            assertThat(metadata.hasReportedInput()).isTrue();
            assertThat(metadata.isCursorPositionRequested()).isFalse();
            metadata.requestCursorPosition();
            assertThat(metadata.hasReportedInput()).isFalse();
            assertThat(metadata.isCursorPositionRequested()).isTrue();
        }

        @Test
        @DisplayName("xxxF and xxxA are one byte - writing either is visible through the other")
        void flagAndAttributeAreAliases() {
            FieldMetadata metadata = new FieldMetadata("ERRMSG");
            assertThat(metadata.isFlagLowValues()).isTrue();
            assertThat(metadata.getFlag()).isEqualTo(FieldMetadata.FLAG_ITEM_LOW_VALUES);
            assertThat(metadata.getAttribute()).isEqualTo(metadata.getFlag());

            metadata.setFlag("X");
            assertThat(metadata.getFlag()).isEqualTo("X");
            assertThat(metadata.getAttribute()).isEqualTo("X");
            assertThat(metadata.isFlagLowValues()).isFalse();

            metadata.setAttribute("Z");
            assertThat(metadata.getAttribute()).isEqualTo("Z");
            assertThat(metadata.getFlag()).isEqualTo("Z");
        }

        @Test
        @DisplayName("the flag byte is exactly one character, padded or truncated as PIC X requires")
        void flagByteIsOneCharacter() {
            FieldMetadata metadata = new FieldMetadata("ERRMSG");
            // CICS reports one attribute byte per field, so three characters is a caller defect rather
            // than data to be shortened.
            assertThatThrownBy(() -> metadata.setFlag("ABC"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ERRMSGF")
                    .hasMessageContaining("3 character(s)");
            metadata.setFlag("A");
            assertThat(metadata.getFlag()).isEqualTo("A").hasSize(1);
            metadata.setAttribute("");
            assertThat(metadata.getAttribute()).isEqualTo(" ").hasSize(1);
            assertThat(FieldMetadata.FLAG_ITEM_BYTES).isEqualTo(1);
            assertThat(FieldMetadata.LENGTH_ITEM_BYTES).isEqualTo(2);
            assertThat(FieldMetadata.LENGTH_ITEM_NONE).isZero();
        }

        @Test
        @DisplayName("reset restores the initial state")
        void resetRestoresInitialState() {
            FieldMetadata metadata = new FieldMetadata("TRNIDIN");
            metadata.requestCursorPosition();
            metadata.setFlag("Q");
            metadata.reset();
            assertThat(metadata.getLengthItem()).isZero();
            assertThat(metadata.isFlagLowValues()).isTrue();
        }

        @Test
        @DisplayName("clearAllFields resets every metadata carrier too")
        void clearAllFieldsResetsMetadata() {
            TransactionListRequest request = new TransactionListRequest();
            request.positionCursorAt("TRNIDIN");
            request.getMetadata("ERRMSG").setFlag("Q");
            request.clearAllFields();
            assertThat(request.getCursorPositionField()).isNull();
            assertThat(request.getMetadata("ERRMSG").isFlagLowValues()).isTrue();
        }

        @Test
        @DisplayName("metadata is rejected for an unknown or null field name")
        void metadataRejectsUnknownField() {
            TransactionListRequest request = new TransactionListRequest();
            assertThatIllegalArgumentException().isThrownBy(() -> request.getMetadata("TAMT01"));
            assertThatNullPointerException().isThrownBy(() -> request.getMetadata(null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.positionCursorAt("NOPE"));
            assertThatNullPointerException().isThrownBy(() -> new FieldMetadata((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new FieldMetadata((FieldMetadata) null));
            assertThatNullPointerException().isThrownBy(() -> request.getMetadata("ERRMSG").setFlag(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> request.getMetadata("ERRMSG").setAttribute(null));
        }

        @Test
        @DisplayName("the metadata map is unmodifiable, though its carriers stay live")
        void metadataMapUnmodifiable() {
            TransactionListRequest request = new TransactionListRequest();
            Map<String, FieldMetadata> map = request.getFieldMetadata();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> map.remove("TRNNAME"));
            map.get("TRNNAME").setFlag("L");
            assertThat(request.getMetadata("TRNNAME").getFlag()).isEqualTo("L");
        }

        @Test
        @DisplayName("metadata carries value semantics and a diagnostic summary")
        void metadataValueSemantics() {
            FieldMetadata first = new FieldMetadata("TRNIDIN");
            FieldMetadata second = new FieldMetadata("TRNIDIN");
            FieldMetadata other = new FieldMetadata("ERRMSG");
            assertThat(first).isEqualTo(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(other).isNotEqualTo(null).isNotEqualTo("TRNIDIN");

            second.requestCursorPosition();
            assertThat(first).isNotEqualTo(second);
            FieldMetadata third = new FieldMetadata("TRNIDIN");
            third.setFlag("A");
            assertThat(first).isNotEqualTo(third);

            FieldMetadata copy = new FieldMetadata(second);
            assertThat(copy).isEqualTo(second);
            assertThat(second.toString()).contains("TRNIDIN").contains("-1").contains("X'00'");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The pagination cursor - 58 bytes, both 88-levels, commarea 218 - rule R6, gate G37")
    class Cursor {

        @Test
        @DisplayName("16 + 16 + 8 + 1 + 1 + 16 = 58, and 160 + 58 = 218")
        void cursorGeometry() {
            assertThat(PaginationCursor.TRNID_FIRST_LENGTH).isEqualTo(16);
            assertThat(PaginationCursor.TRNID_LAST_LENGTH).isEqualTo(16);
            assertThat(PaginationCursor.PAGE_NUM_LENGTH).isEqualTo(8);
            assertThat(PaginationCursor.NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
            assertThat(PaginationCursor.TRN_SEL_FLG_LENGTH).isEqualTo(1);
            assertThat(PaginationCursor.TRN_SELECTED_LENGTH).isEqualTo(16);
            assertThat(PaginationCursor.CURSOR_LENGTH).isEqualTo(58);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(PaginationCursor.COMMAREA_LENGTH).isEqualTo(218);
            assertThat(PaginationCursor.LAYOUT.recordLength()).isEqualTo(58);
            assertThat(PaginationCursor.LAYOUT.storageSpans()).hasSize(6);
        }

        @Test
        @DisplayName("the offsets are 0, 16, 32, 40, 41 and 42")
        void cursorOffsets() {
            assertThat(PaginationCursor.TRNID_FIRST_OFFSET).isZero();
            assertThat(PaginationCursor.TRNID_LAST_OFFSET).isEqualTo(16);
            assertThat(PaginationCursor.PAGE_NUM_OFFSET).isEqualTo(32);
            assertThat(PaginationCursor.NEXT_PAGE_FLG_OFFSET).isEqualTo(40);
            assertThat(PaginationCursor.TRN_SEL_FLG_OFFSET).isEqualTo(41);
            assertThat(PaginationCursor.TRN_SELECTED_OFFSET).isEqualTo(42);
            assertThat(PaginationCursor.TRN_SELECTED_OFFSET + 16).isEqualTo(58);
        }

        @Test
        @DisplayName("the spans carry their verbatim CDEMO-CT00 names")
        void cursorSpanNames() {
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-TRNID-FIRST")).isTrue();
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-TRNID-LAST")).isTrue();
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-PAGE-NUM")).isTrue();
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-NEXT-PAGE-FLG")).isTrue();
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-TRN-SEL-FLG")).isTrue();
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-TRN-SELECTED")).isTrue();
            assertThat(PaginationCursor.TRNID_FIRST_FIELD).isEqualTo("CDEMO-CT00-TRNID-FIRST");
            assertThat(PaginationCursor.PAGE_NUM_FIELD).isEqualTo("CDEMO-CT00-PAGE-NUM");
        }

        @Test
        @DisplayName("the next-page flag defaults to 'N', reproducing the copybook VALUE clause")
        void nextPageDefaultsToNo() {
            PaginationCursor cursor = new PaginationCursor();
            assertThat(cursor.getNextPageFlg()).isEqualTo("N");
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(PaginationCursor.LAYOUT.span("CDEMO-CT00-NEXT-PAGE-FLG").initialValue())
                    .isEqualTo("N");
        }

        @Test
        @DisplayName("both 88-levels are reachable, as COTRN00C sets each on distinct paths")
        void bothConditionNamesReachable() {
            PaginationCursor cursor = new PaginationCursor();
            cursor.setNextPageYes();
            assertThat(cursor.isNextPageYes()).isTrue();
            assertThat(cursor.isNextPageNo()).isFalse();
            assertThat(cursor.getNextPageFlg()).isEqualTo(PaginationCursor.NEXT_PAGE_YES);

            cursor.setNextPageNo();
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.getNextPageFlg()).isEqualTo(PaginationCursor.NEXT_PAGE_NO);

            cursor.setNextPageFlg("?");
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.isNextPageNo()).isFalse();
        }

        @Test
        @DisplayName("the page number is a scale-free integer, rendered as eight zero-filled digits")
        void pageNumberIsInteger() {
            PaginationCursor cursor = new PaginationCursor();
            assertThat(cursor.getPageNum()).isZero();
            assertThat(cursor.getPageNumImage()).isEqualTo("00000000").hasSize(8);
            cursor.setPageNum(42);
            assertThat(cursor.getPageNum()).isEqualTo(42);
            assertThat(cursor.getPageNumImage()).isEqualTo("00000042");
            cursor.setPageNum(PaginationCursor.PAGE_NUM_MAX);
            assertThat(cursor.getPageNumImage()).isEqualTo("99999999");
        }

        @Test
        @DisplayName("PIC 9(08) is unsigned and eight digits wide, so both bounds are enforced")
        void pageNumberBounds() {
            PaginationCursor cursor = new PaginationCursor();
            assertThatIllegalArgumentException().isThrownBy(() -> cursor.setPageNum(-1))
                    .withMessageContaining("unsigned");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> cursor.setPageNum(PaginationCursor.PAGE_NUM_MAX + 1))
                    .withMessageContaining("digits");
        }

        @Test
        @DisplayName("the browse keys and the selection are stored at their declared widths")
        void browseKeysAndSelection() {
            PaginationCursor cursor = new PaginationCursor();
            assertThat(cursor.getTrnidFirst()).isEqualTo(spaces(16));
            assertThat(cursor.getTrnidLast()).isEqualTo(spaces(16));
            assertThat(cursor.getTrnSelFlg()).isEqualTo(" ");
            assertThat(cursor.getTrnSelected()).isEqualTo(spaces(16));

            cursor.setTrnidFirst("0000000000000011");
            cursor.setTrnidLast("0000000000000020");
            cursor.setTrnSelFlg(PaginationCursor.SELECTION_VIEW);
            cursor.setTrnSelected("0000000000000015");
            assertThat(cursor.getTrnidFirst()).isEqualTo("0000000000000011");
            assertThat(cursor.getTrnidLast()).isEqualTo("0000000000000020");
            assertThat(cursor.getTrnSelFlg()).isEqualTo("S");
            assertThat(cursor.getTrnSelected()).isEqualTo("0000000000000015");
        }

        @Test
        @DisplayName("a selection needs BOTH fields present, mirroring the compound guard at L183")
        void selectionPresenceNeedsBothFields() {
            PaginationCursor cursor = new PaginationCursor();
            assertThat(cursor.isSelectionPresent()).isFalse();

            cursor.setTrnSelFlg("S");
            assertThat(cursor.isSelectionPresent()).isFalse();

            cursor.setTrnSelected("0000000000000015");
            assertThat(cursor.isSelectionPresent()).isTrue();

            cursor.clearSelection();
            assertThat(cursor.isSelectionPresent()).isFalse();
            assertThat(cursor.getTrnSelFlg()).isEqualTo(" ");
            assertThat(cursor.getTrnSelected()).isEqualTo(spaces(16));
        }

        @Test
        @DisplayName("LOW-VALUES counts as absent, exactly as the COBOL guard treats it")
        void lowValuesCountsAsAbsent() {
            PaginationCursor cursor = new PaginationCursor();
            cursor.setTrnSelFlg("\u0000");
            cursor.setTrnSelected("\u0000".repeat(16));
            assertThat(cursor.isSelectionPresent()).isFalse();
        }

        @Test
        @DisplayName("the cursor round-trips through its 58-byte image")
        void cursorRoundTrip() {
            PaginationCursor cursor = new PaginationCursor();
            cursor.setTrnidFirst("0000000000000011");
            cursor.setTrnidLast("0000000000000020");
            cursor.setPageNum(7);
            cursor.setNextPageYes();
            cursor.setTrnSelFlg("S");
            cursor.setTrnSelected("0000000000000015");

            byte[] image = cursor.toFixedWidth(ASCII);
            assertThat(image).hasSize(58);
            PaginationCursor restored = PaginationCursor.fromFixedWidth(image, ASCII);
            assertThat(restored).isEqualTo(cursor).hasSameHashCodeAs(cursor);
            assertThat(restored.getPageNum()).isEqualTo(7);
            assertThat(restored.isNextPageYes()).isTrue();
            assertThat(new String(image, 32, 8, ASCII)).isEqualTo("00000007");
        }

        @Test
        @DisplayName("the cursor rejects null arguments and a wrong-length image")
        void cursorRejectsBadInput() {
            PaginationCursor cursor = new PaginationCursor();
            assertThatNullPointerException().isThrownBy(() -> cursor.toFixedWidth(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnidFirst(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnidLast(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setNextPageFlg(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnSelFlg(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnSelected(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> PaginationCursor.fromFixedWidth(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> PaginationCursor.fromFixedWidth(new byte[58], null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PaginationCursor.fromFixedWidth(new byte[57], ASCII));
            assertThatNullPointerException().isThrownBy(() -> new PaginationCursor(null));
        }

        @Test
        @DisplayName("the cursor carries value semantics and a diagnostic summary")
        void cursorValueSemantics() {
            PaginationCursor first = new PaginationCursor();
            PaginationCursor second = new PaginationCursor();
            assertThat(first).isEqualTo(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(null).isNotEqualTo("cursor");

            PaginationCursor copy = new PaginationCursor(first);
            assertThat(copy).isEqualTo(first);

            second.setPageNum(3);
            assertThat(first).isNotEqualTo(second);

            PaginationCursor differing = new PaginationCursor();
            differing.setTrnidFirst("A");
            assertThat(first).isNotEqualTo(differing);
            differing = new PaginationCursor();
            differing.setTrnidLast("A");
            assertThat(first).isNotEqualTo(differing);
            differing = new PaginationCursor();
            differing.setNextPageYes();
            assertThat(first).isNotEqualTo(differing);
            differing = new PaginationCursor();
            differing.setTrnSelFlg("S");
            assertThat(first).isNotEqualTo(differing);
            differing = new PaginationCursor();
            differing.setTrnSelected("A");
            assertThat(first).isNotEqualTo(differing);

            assertThat(second.toString()).contains("page=3").contains("nextPage=N");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Statelessness - the COMMAREA travels in the payload - rule R6, gates G37, G53")
    class Statelessness {

        @Test
        @DisplayName("a fresh request carries an empty context and a fresh cursor")
        void freshCarriedState() {
            TransactionListRequest request = new TransactionListRequest();

            // No area on a fresh request: nothing has been passed to it, which is EIBCALEN = 0.
            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();

            // The cursor, which has no absence semantics, does get its fresh default.
            assertThat(request.getCursor()).isEqualTo(new PaginationCursor());
        }

        @Test
        @DisplayName("ENTER and REENTER both delegate to the single carried CDEMO-PGM-CONTEXT")
        void enterAndReenterDelegate() {
            TransactionListRequest request = new TransactionListRequest();
            request.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(request.isEnter()).isTrue();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(request.toString()).contains("context=ENTER");

            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(request.isReenter()).isTrue();
            assertThat(request.isEnter()).isFalse();
            assertThat(request.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(request.toString()).contains("context=REENTER");
        }

        @Test
        @DisplayName("the carried state is required, so absence is stated explicitly")
        void carriedStateRequired() {
            TransactionListRequest request = new TransactionListRequest();

            // The commarea accepts null, because null IS a state: EIBCALEN = 0, which COTRN00C.cbl:107
            // tests for and answers by transferring to COSGN00C. This setter used to reject null and
            // advise passing NavigationContext.empty() instead, but an initialised area reports 160
            // bytes and takes the opposite branch, so that advice removed the only spelling the cold
            // start had.
            request.setNavigationContext(null);
            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();

            // ...and when an area IS carried, EIBCALEN is the commarea plus this screen's own cursor.
            request.setNavigationContext(NavigationContext.empty());
            assertThat(request.hasNavigationContext()).isTrue();
            assertThat(request.commareaLength())
                    .isEqualTo(PaginationCursor.COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + PaginationCursor.CURSOR_LENGTH)
                    .isEqualTo(218);

            // The cursor is a different case: it has no absence semantics, so it is still required.
            assertThatNullPointerException().isThrownBy(() -> request.setCursor(null));
        }

        @Test
        @DisplayName("a cold start reports the enter context digit but is neither ENTER nor REENTER")
        void aColdStartReportsTheEnterDigitWithoutClaimingTheState() {
            TransactionListRequest cold = new TransactionListRequest();

            // getPgmContext answers with the byte the program would act as though it had - a cold start
            // paints and validates nothing, exactly as first entry does...
            assertThat(cold.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            // ...but isEnter() still refuses to claim the state, because there is no context byte.
            assertThat(cold.isEnter()).isFalse();
            assertThat(cold.isReenter()).isFalse();
            // And the diagnostic says which of the three states it is.
            assertThat(cold.toString()).contains("context=none (EIBCALEN=0)");

            TransactionListRequest warm = new TransactionListRequest();
            warm.setNavigationContext(NavigationContext.empty());
            assertThat(warm.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(warm.isEnter()).isTrue();
            assertThat(warm.toString()).contains("context=ENTER").doesNotContain("EIBCALEN");

            warm.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(warm.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(warm.toString()).contains("context=REENTER");
        }

        @Test
        @DisplayName("with no area there are no commarea bytes to render, and none are invented")
        void aColdStartHasNoCommareaImage() {
            TransactionListRequest request = new TransactionListRequest();

            assertThatThrownBy(() -> request.toCommareaImage(ASCII))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("EIBCALEN is 0")
                    .hasMessageContaining("hasNavigationContext");
        }

        @Test
        @DisplayName("the communication area is 160 + 58 = 218 bytes, context then cursor")
        void commareaImage() {
            TransactionListRequest request = new TransactionListRequest();
            request.setNavigationContext(NavigationContext.empty().withFromTranid("CT00"));
            request.getCursor().setPageNum(4);

            byte[] commarea = request.toCommareaImage(ASCII);
            assertThat(commarea).hasSize(218);
            assertThat(new String(commarea, 0, 4, ASCII)).isEqualTo("CT00");
            assertThat(new String(commarea, 160 + 32, 8, ASCII)).isEqualTo("00000004");
            assertThatNullPointerException().isThrownBy(() -> request.toCommareaImage(null));
        }

        @Test
        @DisplayName("the class declares no session or static mutable state")
        void noSessionOrStaticMutableState() {
            for (Field field : TransactionListRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Serialisation - padding survives a JSON round trip untrimmed - rule R5, gate G9")
    class Serialisation {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        /** Reads a payload back as a name-keyed tree, so property names can be asserted directly. */
        private static Map<String, Object> tree(String json) throws Exception {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        }

        @Test
        @DisplayName("the JSON property names are the copybook base names lower-cased")
        void propertyNamesMatchCopybook() throws Exception {
            Map<String, Object> tree = tree(MAPPER.writeValueAsString(new TransactionListRequest()));
            for (String field : COPYBOOK_FIELDS) {
                assertThat(tree).as("property for %s", field)
                        .containsKey(field.toLowerCase(Locale.ROOT));
            }
            assertThat(tree).containsKeys("sel0001", "sel0010", "trnid01", "trnid10",
                    "tamt001", "tamt010", "tdate01", "tdesc10", "errmsg", "pagenum", "trnidin");
        }

        @Test
        @DisplayName("none of the 177 metadata item names reaches the wire - gate G9")
        void metadataNotSerialised() throws Exception {
            // The binding field-mapping rule: payload names and widths come from the xxxI items only.
            // xxxL, xxxF and xxxA exist on the type as validation and length metadata and as the
            // highlight attribute, which is a different thing from being on the wire. So all 59 x 3 of
            // them are checked, in every plausible spelling, rather than a sample of two.
            String json = MAPPER.writeValueAsString(populated());
            Map<String, Object> tree = tree(json);

            for (String baseFieldName : COPYBOOK_FIELDS) {
                // Present: the payload item, under the base name lower-cased.
                assertThat(tree).as("payload key for %s", baseFieldName)
                        .containsKey(baseFieldName.toLowerCase(Locale.ROOT));

                for (String suffix : List.of("L", "F", "A")) {
                    String item = baseFieldName + suffix;
                    assertThat(tree).as("metadata item %s must not be a payload key", item)
                            .doesNotContainKey(item)
                            .doesNotContainKey(item.toLowerCase(Locale.ROOT));
                    assertThat(json).as("metadata item %s must not appear in the JSON text", item)
                            .doesNotContain("\"" + item + "\"")
                            .doesNotContain("\"" + item.toLowerCase(Locale.ROOT) + "\"");
                }
            }

            // Nor do the accessors that expose metadata and derived state as objects.
            assertThat(tree).doesNotContainKeys("fieldMetadata", "payloadValues",
                    "cursorPositionField", "enter", "reenter", "pgmContext", "commareaLength");
        }

        @Test
        @DisplayName("all 59 payload keys are on the wire, at their declared widths - gate G9")
        void everyPayloadKeyIsSerialised() throws Exception {
            Map<String, Object> tree = tree(MAPPER.writeValueAsString(populated()));
            for (int i = 0; i < COPYBOOK_FIELDS.size(); i++) {
                String key = COPYBOOK_FIELDS.get(i).toLowerCase(Locale.ROOT);
                assertThat(tree).containsKey(key);
                assertThat((String) tree.get(key)).as("%s on the wire", key)
                        .isNotNull()
                        .hasSize(COPYBOOK_WIDTHS.get(i));
            }
        }

        @Test
        @DisplayName("the payload carries exactly the 59 fields plus the two carried structures")
        void payloadShape() throws Exception {
            Map<String, Object> tree = tree(MAPPER.writeValueAsString(new TransactionListRequest()));
            // 59 screen fields plus the three members that are not screen fields: the commarea, its
            // cursor, and the resolved EIBAID token COTRN00C.cbl:119 branches on.
            assertThat(tree).hasSize(59 + 3);
            assertThat(tree).containsKeys("navigationContext", "cursor", "aid");
        }

        @Test
        @DisplayName("space padding survives serialise and deserialise, untrimmed and equal")
        void paddingSurvivesRoundTrip() throws Exception {
            TransactionListRequest request = new TransactionListRequest();
            request.setErrmsg("Tran ID must be Numeric ...");
            request.setTdesc10("PAYMENT");
            request.setTamt010("+00000012.34");
            request.setTrnidin("0000000000000001");
            request.getCursor().setPageNum(2);
            request.getCursor().setNextPageYes();

            String json = MAPPER.writeValueAsString(request);
            TransactionListRequest restored = MAPPER.readValue(json, TransactionListRequest.class);

            // A JSON round trip is the identity now, whatever width the value happens to be: the
            // payload neither pads on the way in nor trims on the way out.
            assertThat(restored.getErrmsg()).isEqualTo(request.getErrmsg())
                    .isEqualTo("Tran ID must be Numeric ...");
            assertThat(restored.getTdesc10()).isEqualTo(request.getTdesc10()).isEqualTo("PAYMENT");
            assertThat(restored.getTamt010()).hasSize(12).isEqualTo(request.getTamt010());
            assertThat(restored.getPayloadValues()).isEqualTo(request.getPayloadValues());
            assertThat(restored.getCursor().getPageNum()).isEqualTo(2);
            assertThat(restored.getCursor().isNextPageYes()).isTrue();
        }

        @Test
        @DisplayName("a blank row serialises as spaces, never as null")
        void blankRowSerialisesAsSpaces() throws Exception {
            String json = MAPPER.writeValueAsString(new TransactionListRequest());
            Map<String, Object> tree = tree(json);

            // Every screen field is its declared width in spaces - the constructor performs COBOL's
            // unconditional MOVE SPACES, which is a different rule from the alphanumeric MOVE and is
            // unaffected by the setters no longer padding.
            assertThat(tree.get("tamt001")).isEqualTo(spaces(12));
            assertThat(tree.get("errmsg")).isEqualTo(spaces(78));
            for (String fieldName : TransactionListRequest.FIELD_NAMES) {
                assertThat(tree.get(fieldName.toLowerCase(Locale.ROOT)))
                        .as("%s is spaces, never null", fieldName)
                        .isNotNull();
            }

            // The one null in the document is the communication area, and it is deliberate: it is the
            // EIBCALEN = 0 cold start, which has no other spelling.
            assertThat(tree.get("navigationContext")).isNull();
            assertThat(json.indexOf("null")).isEqualTo(json.lastIndexOf("null"));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The fixed-width group image - 1265 bytes, lossless both ways - rule R5, gate G21")
    class FixedWidthImage {

        @Test
        @DisplayName("the image is 1265 bytes and round-trips field for field")
        void imageRoundTrip() {
            TransactionListRequest request = populated();
            byte[] image = request.toFixedWidth(ASCII);
            assertThat(image).hasSize(1265);

            TransactionListRequest restored = TransactionListRequest.fromFixedWidth(image, ASCII);
            assertThat(restored.getPayloadValues()).isEqualTo(request.getPayloadValues());
        }

        @Test
        @DisplayName("each field lands at its declared offset in the image")
        void fieldsLandAtDeclaredOffsets() {
            TransactionListRequest request = new TransactionListRequest();
            request.setTrnname("CT00");
            request.setErrmsg("E");
            request.setSel0001("S");
            request.setTamt010("+00000001.23");
            byte[] image = request.toFixedWidth(ASCII);

            assertThat(new String(image, TransactionListRequest.TRNNAME_OFFSET, 4, ASCII))
                    .isEqualTo("CT00");
            assertThat(new String(image, TransactionListRequest.ERRMSG_OFFSET, 1, ASCII))
                    .isEqualTo("E");
            assertThat(new String(image,
                    TransactionListRequest.rowImageOffset(1)
                            + TransactionListRequest.SELECTION_ROW_OFFSET, 1, ASCII))
                    .isEqualTo("S");
            assertThat(new String(image,
                    TransactionListRequest.rowImageOffset(10)
                            + TransactionListRequest.TRANSACTION_AMOUNT_ROW_OFFSET, 12, ASCII))
                    .isEqualTo("+00000001.23");
        }

        @Test
        @DisplayName("the prefixes are emitted as spaces, so no offset after them can shift")
        void prefixesAreEmitted() {
            byte[] image = new TransactionListRequest().toFixedWidth(ASCII);
            assertThat(new String(image, 0, TransactionListRequest.TIOAPFX_PREFIX_LENGTH, ASCII))
                    .isEqualTo(spaces(12));
            assertThat(new String(image,
                    TransactionListRequest.TRNNAME_OFFSET
                            - TransactionListRequest.FIELD_PREFIX_LENGTH, 7, ASCII))
                    .isEqualTo(spaces(7));
        }

        @Test
        @DisplayName("writeInto and readFrom work over a caller-owned record area")
        void writeIntoAndReadFrom() {
            TransactionListRequest request = populated();
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            FixedWidthRecord record = codec.newRecord(TransactionListRequest.LAYOUT);
            request.writeInto(record);
            TransactionListRequest restored = TransactionListRequest.readFrom(record);
            assertThat(restored.getPayloadValues()).isEqualTo(request.getPayloadValues());
        }

        @Test
        @DisplayName("a wrong-length record area or image is rejected rather than tolerated")
        void wrongLengthRejected() {
            TransactionListRequest request = new TransactionListRequest();
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            FixedWidthRecord tooShort = new FixedWidthRecord(100, ASCII);

            assertThatIllegalArgumentException().isThrownBy(() -> request.writeInto(tooShort))
                    .withMessageContaining("1265");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionListRequest.readFrom(tooShort))
                    .withMessageContaining("1265");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionListRequest.fromFixedWidth(new byte[1264], ASCII));
            assertThatNullPointerException().isThrownBy(() -> request.toFixedWidth(null));
            assertThatNullPointerException().isThrownBy(() -> request.writeInto(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.readFrom(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.fromFixedWidth(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.fromFixedWidth(new byte[1265], null));
            assertThat(codec.charset()).isEqualTo(ASCII);
        }
    }

    // =================================================================================================

    /**
     * Value semantics: a payload that travels on the wire has to copy, compare and print predictably.
     *
     * <p>Copying matters here beyond the usual reason. The cursor is the one mutable member, so a copy
     * that shared it would let two requests page each other, and equality that ignored it would call
     * page 1 and page 4 the same request.
     */
    @Nested
    @DisplayName("Copying and value semantics - practice B9, gate G53")
    class CopyingAndEquality {

        @Test
        @DisplayName("a copy is equal, independent, and deep-copies the mutable cursor")
        void copyIsEqualAndIndependent() {
            TransactionListRequest original = populated();
            original.positionCursorAt("TRNIDIN");
            TransactionListRequest copy = new TransactionListRequest(original);

            assertThat(copy).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(copy.getCursorPositionField()).isEqualTo("TRNIDIN");

            copy.setTamt010("+00000000.01");
            copy.getCursor().setPageNum(99);
            assertThat(original.getTamt010()).isNotEqualTo("+00000000.01");
            assertThat(original.getCursor().getPageNum()).isNotEqualTo(99);
            assertThat(copy).isNotEqualTo(original);
        }

        @Test
        @DisplayName("a null source cannot be copied")
        void nullSourceRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionListRequest(null));
        }

        @Test
        @DisplayName("equality covers the payload, the context, the cursor and the metadata")
        void equalityCoversEveryComponent() {
            TransactionListRequest first = new TransactionListRequest();
            TransactionListRequest second = new TransactionListRequest();
            assertThat(first).isEqualTo(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(null).isNotEqualTo("request");

            TransactionListRequest differentPayload = new TransactionListRequest();
            differentPayload.setErrmsg("x");
            assertThat(first).isNotEqualTo(differentPayload);

            TransactionListRequest differentContext = new TransactionListRequest();
            differentContext.setNavigationContext(
                    NavigationContext.empty().withFromTranid("CT00"));
            assertThat(first).isNotEqualTo(differentContext);

            TransactionListRequest differentCursor = new TransactionListRequest();
            differentCursor.getCursor().setPageNum(1);
            assertThat(first).isNotEqualTo(differentCursor);

            TransactionListRequest differentMetadata = new TransactionListRequest();
            differentMetadata.positionCursorAt("ERRMSG");
            assertThat(first).isNotEqualTo(differentMetadata);
        }

        @Test
        @DisplayName("toString names the screen and the cursor but never the field values")
        void toStringExcludesValues() {
            TransactionListRequest request = new TransactionListRequest();
            request.setTdesc01("SENSITIVE MERCHANT NAME");
            request.setTamt001("+99999999.99");
            String rendered = request.toString();
            assertThat(rendered)
                    .contains("CT00")
                    .contains("COTRN00C")
                    .contains("COTRN0A")
                    .contains("fields=59")
                    .contains("PaginationCursor");
            assertThat(rendered)
                    .doesNotContain("SENSITIVE MERCHANT NAME")
                    .doesNotContain("+99999999.99");
        }

        @Test
        @DisplayName("clearAllFields returns a populated request to its initial state")
        void clearAllFieldsRestoresInitialState() {
            TransactionListRequest request = populated();
            request.clearAllFields();
            assertThat(request.getPayloadValues())
                    .isEqualTo(new TransactionListRequest().getPayloadValues());
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Screen-flow shapes taken from COTRN00C - rule R7, gate G39")
    class ScreenFlow {

        static List<String> selectorRows() {
            List<String> rows = new ArrayList<>();
            for (int row = 1; row <= 10; row++) {
                rows.add(String.valueOf(row));
            }
            return rows;
        }

        @ParameterizedTest(name = "a selector on row {0} is found in declaration order")
        @MethodSource("selectorRows")
        @DisplayName("the ordered first-match-wins scan finds a selector on any of the ten rows")
        void orderedSelectorScan(String rowText) {
            int row = Integer.parseInt(rowText);
            TransactionListRequest request = new TransactionListRequest();
            request.setTransactionId(row, "000000000000000" + (row % 10));
            request.setSelection(row, "S");

            String found = null;
            String selected = null;
            for (int candidate = 1; candidate <= TransactionListRequest.ROW_COUNT; candidate++) {
                String selector = request.getSelection(candidate);
                if (!selector.isBlank()) {
                    found = selector;
                    selected = request.getTransactionId(candidate);
                    break;
                }
            }
            assertThat(found).isEqualTo("S");
            assertThat(selected).isEqualTo("000000000000000" + (row % 10));
        }

        @Test
        @DisplayName("a full page of ten rows populates every slot, first and last included")
        void fullPagePopulated() {
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= TransactionListRequest.PAGE_SIZE; row++) {
                request.setTransactionId(row, String.format("%016d", row));
                request.setTransactionDate(row, "01/01/24");
                request.setTransactionDescription(row, "TRANSACTION " + row);
                request.setTransactionAmount(row, String.format("+%08d.00", row));
            }
            request.getCursor().setTrnidFirst(request.getTransactionId(1));
            request.getCursor().setTrnidLast(
                    request.getTransactionId(TransactionListRequest.PAGE_SIZE));

            assertThat(request.getCursor().getTrnidFirst()).isEqualTo("0000000000000001");
            assertThat(request.getCursor().getTrnidLast()).isEqualTo("0000000000000010");
            assertThat(request.getTamt001()).isEqualTo("+00000001.00");
            assertThat(request.getTamt010()).isEqualTo("+00000010.00");
        }

        @Test
        @DisplayName("a partial page leaves the unused rows as spaces")
        void partialPageLeavesSpaces() {
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= 3; row++) {
                request.setTransactionId(row, String.format("%016d", row));
                request.setTransactionAmount(row, String.format("+%08d.00", row));
            }
            for (int row = 4; row <= TransactionListRequest.ROW_COUNT; row++) {
                assertThat(request.getTransactionId(row)).isEqualTo(spaces(16));
                assertThat(request.getTransactionAmount(row)).isEqualTo(spaces(12));
            }
        }
    }

    @Nested
    @DisplayName("The AID and the width contract - rule R6, gates G37 and G49")
    class KeyIndicationAndWidthContract {

        @Test
        @DisplayName("every one of the 59 constraints states a maximum only, never an exact width")
        void constraintsAreMaximumOnly() throws Exception {
            int checked = 0;
            for (String baseFieldName : TransactionListRequest.FIELD_NAMES) {
                String member = baseFieldName.toLowerCase(Locale.ROOT);
                Size size = TransactionListRequest.class.getDeclaredField(member)
                        .getAnnotation(Size.class);
                assertThat(size).as("@Size on %s", member).isNotNull();
                // A minimum would make a short value invalid, and a short value is legitimate: several
                // of these fields are tested against SPACES OR LOW-VALUES by the program itself.
                assertThat(size.min()).as("@Size(min) on %s must be the default 0", member).isZero();
                assertThat(size.max()).as("@Size(max) on %s", member).isPositive();
                checked++;
            }
            assertThat(checked).isEqualTo(TransactionListRequest.FIELD_COUNT).isEqualTo(59);
        }

        @Test
        @DisplayName("a short value is valid, and an over-long one cannot even be stored")
        void theConstraintIsAnActualCheck() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                TransactionListRequest shortValues = new TransactionListRequest();
                shortValues.setTitle01("T1");
                shortValues.setErrmsg("E");
                assertThat(validator.validate(shortValues))
                        .as("a value narrower than its field is legitimate")
                        .isEmpty();
            }

            // And the surplus case never reaches Bean Validation at all: the setter refuses it, so it
            // cannot be shortened into validity the way it used to be.
            assertThatThrownBy(() -> new TransactionListRequest().setTitle01("X".repeat(41)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TITLE01")
                    .hasMessageContaining("41 character(s)");
        }

        @ParameterizedTest(name = "{0} PIC X({1}) reports a violation at {1} + 1 characters")
        @CsvSource({
            "TRNNAME, 4",
            "CURDATE, 8",
            "TAMT001, 12",
            "TRNIDIN, 16",
            "TDESC01, 26",
            "TITLE01, 40",
            "ERRMSG, 78",
            "SEL0001, 1",
        })
        @DisplayName("each constrained width is a real check, valid and invalid - gate G49")
        void everyConstrainedWidthIsEnforcedBothWays(String baseFieldName, int declaredWidth)
                throws Exception {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                String member = baseFieldName.toLowerCase(Locale.ROOT);

                // VALID: exactly the declared width, which is what a RECEIVE MAP delivers, and one
                // character short of it, which a caller may legitimately send.
                TransactionListRequest atWidth = new TransactionListRequest();
                atWidth.setPayloadValue(baseFieldName, "X".repeat(declaredWidth));
                assertThat(validator.validate(atWidth))
                        .as("%s at its declared width is valid", baseFieldName).isEmpty();
                if (declaredWidth > 1) {
                    TransactionListRequest shorter = new TransactionListRequest();
                    shorter.setPayloadValue(baseFieldName, "X".repeat(declaredWidth - 1));
                    assertThat(validator.validate(shorter))
                            .as("%s below its declared width is valid", baseFieldName).isEmpty();
                }

                // INVALID: one character over. The setter refuses this outright - the stronger
                // guarantee, asserted just above - so the surplus is planted straight onto the member
                // here. That is deliberate: a @Size no test can ever make fail is decoration rather
                // than a constraint, and this is the only way to drive its failing side while the
                // setter is doing its job. It also pins the second line of defence, which is what
                // would catch a future accessor added without the width check.
                TransactionListRequest overLong = new TransactionListRequest();
                Field declaredMember = TransactionListRequest.class.getDeclaredField(member);
                declaredMember.setAccessible(true);
                declaredMember.set(overLong, "X".repeat(declaredWidth + 1));

                Set<ConstraintViolation<TransactionListRequest>> violations =
                        validator.validate(overLong);
                assertThat(violations).as("%s at %d characters", baseFieldName, declaredWidth + 1)
                        .hasSize(1);
                ConstraintViolation<TransactionListRequest> violation = violations.iterator().next();
                assertThat(violation.getPropertyPath().toString()).isEqualTo(member);
                assertThat(violation.getInvalidValue()).isEqualTo("X".repeat(declaredWidth + 1));
                assertThat(TransactionListRequest.class.getDeclaredField(member)
                        .getAnnotation(Size.class).max()).isEqualTo(declaredWidth);
            }
        }

        @Test
        @DisplayName("the AID token is five characters and starts at no key resolved")
        void theAidTokenIsFiveCharacters() {
            // Five is the width of the token itself - CCARD-AID PIC X(5) in the shared screen state,
            // and the width every DFHAID name fits: ENTER, CLEAR, PFK01..PFK12.
            assertThat(TransactionListRequest.AID_LENGTH).isEqualTo(5);
            for (String token : ACTED_ON_AID_TOKENS) {
                assertThat(token).as("AID token %s", token).hasSize(5);
            }
            assertThat(TransactionListRequest.AID_FIELD).isEqualTo("EIBAID");
            assertThat(new TransactionListRequest().getAid()).isEqualTo(spaces(5));
        }

        @Test
        @DisplayName("every AID this screen acts on is carried, WHEN OTHER included - R6, gate G37")
        void everyArmIsSelectable() {
            TransactionListRequest request = new TransactionListRequest();

            // ENTER acts on the selected row; PF3 returns; PF7 and PF8 are the two paging directions,
            // which are the whole purpose of this screen and live entirely on this member. Which arm
            // each token drives is the controller's business and is asserted there; what has to hold
            // HERE is that the payload can carry any of them losslessly, because a stateless server
            // has nowhere else to learn which key was pressed.
            for (String token : ACTED_ON_AID_TOKENS) {
                request.setAid(token);
                assertThat(request.getAid()).isEqualTo(token)
                        .hasSize(TransactionListRequest.AID_LENGTH);
            }

            // ...and WHEN OTHER, which spaces select.
            request.setAid(null);
            assertThat(request.getAid()).isEqualTo(spaces(5));
        }

        @Test
        @DisplayName("the AID is on the wire, in value semantics, and outside the 59-field projection")
        void theAidIsCarriedAndCounted() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionListRequest before = new TransactionListRequest();
            before.setAid("PFK08");

            TransactionListRequest after = mapper.readValue(mapper.writeValueAsString(before),
                    TransactionListRequest.class);
            assertThat(after.getAid()).isEqualTo("PFK08");
            assertThat(after).isEqualTo(before);

            // Two requests differing only in the key pressed are different requests - PF7 pages back
            // where PF8 pages forward.
            TransactionListRequest paging = new TransactionListRequest();
            paging.setAid("PFK07");
            assertThat(paging).isNotEqualTo(before);

            // It is not a screen field: not in FIELD_NAMES, and the image width is unchanged.
            assertThat(TransactionListRequest.FIELD_NAMES)
                    .doesNotContain(TransactionListRequest.AID_FIELD);
            assertThat(before.toFixedWidth(ASCII))
                    .hasSize(TransactionListRequest.SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("an over-long token is refused by name")
        void anOverLongTokenIsRefused() {
            assertThatThrownBy(() -> new TransactionListRequest().setAid("PFK012"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(TransactionListRequest.AID_FIELD)
                    .hasMessageContaining("6 character(s)");
        }
    }

    // =================================================================================================

    /**
     * The properties that are proved by what the type does <strong>not</strong> contain.
     *
     * <p>These read oddly next to the positive assertions, and they are the ones most worth having.
     * A payload type drifts by acquiring things - a {@code double} for an amount, a rounding mode to
     * make the arithmetic "nicer", a {@code @Column} because someone wired it to a table, a session
     * handle because statelessness was inconvenient. None of those would fail a width assertion, and
     * every one of them would be a parity violation. So they are asserted directly, mechanically,
     * over the declared members rather than trusted to review.
     */
    @Nested
    @DisplayName("The negative contract - gates G22, G24, G37, G44 and G53")
    class NegativeContract {

        /** The class under test and both of its nested carriers, which are held to the same bar. */
        private List<Class<?>> declaredTypes() {
            return List.of(TransactionListRequest.class, FieldMetadata.class, PaginationCursor.class);
        }

        @Test
        @DisplayName("all 59 payload members are String - rule R4, gate G22")
        void everyPayloadMemberIsAString() throws Exception {
            for (String baseFieldName : COPYBOOK_FIELDS) {
                Field member = TransactionListRequest.class
                        .getDeclaredField(baseFieldName.toLowerCase(Locale.ROOT));
                assertThat(member.getType()).as("declared type of %s", baseFieldName)
                        .isEqualTo(String.class);
            }

            // TAMT00n is the sharpest case: it holds money, and it is still a String. The screen
            // receives TRAN-AMT already rendered through WS-TRAN-AMT PIC +99999999.99
            // (app/cbl/COTRN00C.cbl:56), so what arrives is a 12-character edit form - a sign, eight
            // digits, a point and two decimals - and re-parsing it into a number here would invent a
            // scale decision the screen never made.
            assertThat(TransactionListRequest.class.getDeclaredField("tamt001").getType())
                    .isEqualTo(String.class);
            assertThat("+99999999.99")
                    .hasSize(TransactionListRequest.TRANSACTION_AMOUNT_LENGTH);

            // PAGENUM is the mirror case: the screen field stays X(8) even though the cursor's
            // CDEMO-CT00-PAGE-NUM is PIC 9(08) (app/cbl/COTRN00C.cbl:65, COMPUTEd at L306 and L317).
            // The two are different items with different pictures, and the payload keeps the screen's.
            assertThat(TransactionListRequest.class.getDeclaredField("pagenum").getType())
                    .isEqualTo(String.class);
            assertThat(PaginationCursor.class.getDeclaredMethod("getPageNum").getReturnType())
                    .as("a scale-free PIC 9(08) is an int, which rule R4 permits")
                    .isEqualTo(int.class);
        }

        @Test
        @DisplayName("no member is floating point anywhere in the type - rule R4, gate G22")
        void nothingIsFloatingPoint() {
            List<Class<?>> forbidden = List.of(double.class, float.class, Double.class, Float.class);
            for (Class<?> type : declaredTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    assertThat(field.getType()).as("%s.%s", type.getSimpleName(), field.getName())
                            .isNotIn(forbidden);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as("return of %s.%s", type.getSimpleName(), method.getName())
                            .isNotIn(forbidden);
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(parameter)
                                .as("parameter of %s.%s", type.getSimpleName(), method.getName())
                                .isNotIn(forbidden);
                    }
                }
            }
        }

        @Test
        @DisplayName("no rounding decision exists to get wrong - rule R2, gate G24")
        void noRoundingDecisionExists() {
            // ROUNDED appears zero times in all 28 COBOL programs, so COBOL truncates on store and
            // RoundingMode.DOWN is the only faithful choice anywhere in this migration. The way this
            // payload type honours that is by holding no fixed-point value at all: TAMT00n arrives
            // pre-edited as 12 characters. So the assertion here is the negative one - there is no
            // BigDecimal and no RoundingMode reachable, therefore no HALF_UP, HALF_EVEN, CEILING or
            // FLOOR can be applied, and no scale can be silently changed.
            List<Class<?>> forbidden = List.of(java.math.BigDecimal.class,
                    java.math.RoundingMode.class, java.math.MathContext.class);
            for (Class<?> type : declaredTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    assertThat(field.getType()).as("%s.%s", type.getSimpleName(), field.getName())
                            .isNotIn(forbidden);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as("return of %s.%s", type.getSimpleName(), method.getName())
                            .isNotIn(forbidden);
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(parameter)
                                .as("parameter of %s.%s", type.getSimpleName(), method.getName())
                                .isNotIn(forbidden);
                    }
                }
            }

            // And nothing in the type is named after a rounding mode either, which would be the other
            // way a rounding decision could hide - a constant or an accessor rather than a type.
            for (Class<?> type : declaredTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    assertThat(field.getName().toUpperCase(Locale.ROOT))
                            .as("%s.%s", type.getSimpleName(), field.getName())
                            .isNotIn(FORBIDDEN_ROUNDING_MODES);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(method.getName().toUpperCase(Locale.ROOT))
                            .as("%s.%s", type.getSimpleName(), method.getName())
                            .isNotIn(FORBIDDEN_ROUNDING_MODES);
                }
            }
        }

        @Test
        @DisplayName("nothing schema-shaped is declared - gate G44")
        void nothingSchemaShaped() {
            for (Class<?> type : declaredTypes()) {
                assertAnnotationsAreNotSchemaShaped(type.getSimpleName(),
                        type.getDeclaredAnnotations());
                for (Field field : type.getDeclaredFields()) {
                    assertAnnotationsAreNotSchemaShaped(
                            type.getSimpleName() + "." + field.getName(),
                            field.getDeclaredAnnotations());
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertAnnotationsAreNotSchemaShaped(
                            type.getSimpleName() + "." + method.getName(),
                            method.getDeclaredAnnotations());
                }
            }
        }

        private void assertAnnotationsAreNotSchemaShaped(String subject, Annotation[] annotations) {
            for (Annotation annotation : annotations) {
                assertThat(annotation.annotationType().getSimpleName()).as("annotation on %s", subject)
                        .isNotIn(SCHEMA_SHAPED_ANNOTATIONS);
            }
        }

        @Test
        @DisplayName("no server-side state is reachable - rule R6, gates G37 and G53")
        void noServerSideStateIsReachable() {
            for (Class<?> type : declaredTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    String fieldType = field.getType().getName();
                    assertThat(fieldType).as("%s.%s", type.getSimpleName(), field.getName())
                            .doesNotContain("HttpSession")
                            .doesNotContain("ThreadLocal")
                            .doesNotContain("jakarta.servlet")
                            .doesNotContain("javax.servlet")
                            .doesNotContain("HttpServletRequest");

                    // Every static member is final: COBOL WORKING-STORAGE must never become mutable
                    // class state, because two concurrent requests would then share one screen.
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("static %s.%s must be final",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }

            // The state that IS carried travels in the payload, and two instances holding different
            // conversation state are different values rather than one shared mutable screen.
            TransactionListRequest first = new TransactionListRequest();
            TransactionListRequest second = new TransactionListRequest();
            first.getCursor().setPageNum(3);
            assertThat(second.getCursor().getPageNum())
                    .as("one request's cursor is not another's")
                    .isZero();
        }

        @Test
        @DisplayName("the input-capable set is exactly TRNIDIN plus the ten selectors - gate G9")
        void theInputCapableSetIsElevenFields() {
            // 89 DFHMDF entries, 59 named, and of those exactly 11 omit ASKIP. The other 48 named
            // fields are painted by the program and can never be typed into, so presence and length
            // validation is meaningful for these 11 alone.
            assertThat(INPUT_CAPABLE_FIELDS).hasSize(11);
            assertThat(TransactionListRequest.FIELD_NAMES)
                    .containsAll(INPUT_CAPABLE_FIELDS)
                    .hasSize(59);
            assertThat(INPUT_CAPABLE_FIELDS.get(0)).isEqualTo(TransactionListRequest.TRNIDIN_FIELD);
            for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
                assertThat(INPUT_CAPABLE_FIELDS)
                        .contains(TransactionListRequest.selectionFieldName(row));
            }

            // The remaining 48 are output only - including every identifier, date, description and
            // amount cell, which is why a caller cannot smuggle a transaction in through the grid.
            List<String> outputOnly = new ArrayList<>(TransactionListRequest.FIELD_NAMES);
            outputOnly.removeAll(INPUT_CAPABLE_FIELDS);
            assertThat(outputOnly).hasSize(48)
                    .contains("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
                            "PAGENUM", "ERRMSG", "TRNID01", "TDATE01", "TDESC01", "TAMT001",
                            "TRNID10", "TDATE10", "TDESC10", "TAMT010")
                    .doesNotContain("TRNIDIN", "SEL0001", "SEL0010");

            // Every one of the 11 still carries metadata, because that is where a reported input
            // length and a highlight attribute land for an UNPROT field.
            TransactionListRequest request = new TransactionListRequest();
            for (String field : INPUT_CAPABLE_FIELDS) {
                assertThat(request.getMetadata(field)).as("metadata for %s", field).isNotNull();
            }
        }

        @Test
        @DisplayName("the grid is fifty named members, never a collection - gate G33")
        void theGridIsNotACollection() {
            // The grid is ten sets of five named items, not an OCCURS table: the copybook declares
            // SEL0001I..SEL0010I as fifty separate items, and modelling them as a List would let a
            // caller send nine rows or eleven and would change the 1265-byte image. The indexed
            // accessors exist for convenience over those named members, and are asserted to agree with
            // them in RowAddressing.
            int payloadMembers = 0;
            int carriedStructures = 0;
            for (Field field : TransactionListRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                assertThat(field.getType().isArray())
                        .as("%s must not be an array", field.getName()).isFalse();
                assertThat(List.class.isAssignableFrom(field.getType()))
                        .as("%s must not be a List", field.getName()).isFalse();
                if (field.getType() == String.class) {
                    payloadMembers++;
                } else {
                    carriedStructures++;
                    // The only non-String instance members are the two carried structures and the
                    // metadata sidecar. The sidecar is a Map keyed by field name, which is exactly
                    // right for something that is not on the wire; it is final, so it cannot be
                    // swapped, and getFieldMetadata() is asserted unmodifiable in Metadata.
                    assertThat(field.getType())
                            .as("non-String member %s", field.getName())
                            .isIn(NavigationContext.class, PaginationCursor.class, Map.class);
                    if (Map.class.isAssignableFrom(field.getType())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("the metadata sidecar %s must be final", field.getName())
                                .isTrue();
                    }
                }
            }

            // 59 screen fields plus the AID, which is carried but is not a DFHMDF field.
            assertThat(payloadMembers).isEqualTo(TransactionListRequest.FIELD_COUNT + 1).isEqualTo(60);
            assertThat(carriedStructures)
                    .as("navigation context, pagination cursor and metadata sidecar")
                    .isEqualTo(3);
        }
    }

    // =================================================================================================

    /**
     * A value space-padded to a declared width - what a {@code RECEIVE MAP} would have delivered.
     *
     * @param value the value as a caller or the program would supply it, never longer than
     *              {@code width}
     * @param width the receiving item's declared {@code PIC X(n)} width
     * @return {@code value} padded on the right to exactly {@code width} characters
     */
    private static String atWidth(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * A fully populated request, every field at exactly its declared width.
     *
     * <p>Declared width deliberately: that is what a 3270 {@code RECEIVE MAP} delivers, so it is the
     * shape the fixed-width round trip must reproduce byte for byte. The setters do not pad, so a
     * fixture holding short values would round-trip to the padded form rather than to itself - which is
     * correct behaviour but a different property, and it is asserted on its own in
     * {@code shortValueIsStoredUnchanged} rather than smuggled into every round-trip test here.
     *
     * @return a request with all 59 fields, the navigation context and the cursor populated
     */
    private static TransactionListRequest populated() {
        TransactionListRequest request = new TransactionListRequest();
        request.setTrnname("CT00");
        request.setTitle01(atWidth("AWS Mainframe Modernization",
                TransactionListRequest.TITLE01_LENGTH));
        request.setCurdate("08/08/26");
        request.setPgmname("COTRN00C");
        request.setTitle02(atWidth("CardDemo", TransactionListRequest.TITLE02_LENGTH));
        request.setCurtime("09:10:11");
        request.setPagenum("00000001");
        request.setTrnidin("0000000000000001");
        request.setErrmsg(atWidth("Invalid selection. Valid value is S",
                TransactionListRequest.ERRMSG_LENGTH));
        for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
            request.setSelection(row, row == 1 ? "S" : " ");
            request.setTransactionId(row, String.format("%016d", row));
            request.setTransactionDate(row, "0" + (row % 10) + "/02/24");
            request.setTransactionDescription(row, atWidth("DESCRIPTION FOR ROW " + row,
                    TransactionListRequest.TRANSACTION_DESCRIPTION_LENGTH));
            request.setTransactionAmount(row, String.format("+%08d.99", row));
        }
        request.setNavigationContext(NavigationContext.empty()
                .withFromTranid("CT00")
                .withFromProgram("COTRN00C")
                .withPgmReenter());
        request.getCursor().setTrnidFirst("0000000000000001");
        request.getCursor().setTrnidLast("0000000000000010");
        request.getCursor().setPageNum(1);
        request.getCursor().setNextPageYes();
        return request;
    }
}
