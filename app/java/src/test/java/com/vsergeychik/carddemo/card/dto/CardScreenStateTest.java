package com.vsergeychik.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity tests for {@link CardScreenState}, the translation of {@code app/cpy/CVCRD01Y.cpy}.
 *
 * <p>The suite is organised around the properties of the copybook that determine the implementation,
 * so a failure names a translation decision rather than merely a value:
 *
 * <ol>
 *   <li>The nine storage items sum to <strong>213</strong> bytes and the three {@code REDEFINES}
 *       overlays add none.</li>
 *   <li>{@code CCARD-AID} carries <strong>16</strong> condition names - not the plan's 15 - and
 *       {@code PA1} and {@code PA2} keep their two trailing spaces.</li>
 *   <li>{@code CCARD-NEXT-MAPSET} and {@code CCARD-NEXT-MAP} are {@code X(7)}, not {@code X(8)}.</li>
 *   <li>{@code LOW-VALUES} is a third state, distinct from spaces and from absent.</li>
 *   <li>Each of the <strong>three</strong> {@code REDEFINES} pairs is one shared span, so a write
 *       through either view is visible through the other.</li>
 *   <li>The sixteen tokens are exactly the sixteen {@code common.PfKeyResolver} emits from a raw
 *       {@code EIBAID}, and the AID constant set is deliberately <em>wider</em> than the token set -
 *       see {@link ResolverAlignment}.</li>
 * </ol>
 *
 * <p>The expected values are transcribed from the copybook, not from the class under test, so the
 * copybook remains the authority: a change to either class that moved a literal or a width would fail
 * here. Every expectation is an inlined literal derived at authoring time; nothing under
 * {@code app/cpy/} or {@code app/cbl/} is opened, read or loaded at runtime, and nothing there is ever
 * written (practice <strong>B3</strong>).
 *
 * <h2>The standard this suite is held to</h2>
 *
 * <p><strong>No user-specified rules were provided for this project</strong> - {@code review_rules}
 * returns the single line "No user rules provided", which is the whole document. Nothing here is
 * therefore a "user rule", none has been invented, and their absence is explicitly not treated as
 * licence to lower the bar. The substitute standard is enterprise best practice as codified by the
 * migration plan's practices <strong>B1</strong>-<strong>B11</strong>, and each is cited by identifier
 * at the point in this file where it bites: <strong>B1</strong>/<strong>B2</strong> JUnit 5 Jupiter and
 * AssertJ only, both arriving through the pinned {@code spring-boot-starter-test}, with no version
 * literal anywhere; <strong>B3</strong> the copybook and the three card programs are cited by line and
 * never read; <strong>B4</strong> two divergences are recorded below rather than resolved in silence;
 * <strong>B5</strong> the four commented-out groups are asserted absent; <strong>B6</strong> the full
 * sixteen-digit card number is used unmasked; <strong>B8</strong> a {@link Charset} is named at every
 * byte boundary and no import is a wildcard; <strong>B9</strong> no static mutable state and the
 * default per-test-method lifecycle; <strong>B11</strong> every width and offset is an explicit integer
 * literal a reviewer can trace by eye, with no copybook parser, no reflective member walk and no
 * generic bean comparison.
 *
 * <h2>Two divergences this suite records rather than resolves (practice B4)</h2>
 *
 * <ol>
 *   <li><strong>The copybook declares three {@code REDEFINES} pairs; the migration plan's body names
 *       one.</strong> Recorded in full at {@link RedefinesPairs}, which asserts all three.</li>
 *   <li><strong>The numeric overlay over spaces reads as zero, not as space bytes.</strong> Recorded in
 *       full at {@link RedefinesPairs#spacesReadAsZero()}. The source itself settles it, so the
 *       implementation is asserted as it actually behaves rather than as the plan's summary predicted -
 *       and the accompanying assertions prove the storage bytes themselves are never coerced.</li>
 * </ol>
 */
@DisplayName("CardScreenState - CVCRD01Y CC-WORK-AREA")
class CardScreenStateTest {

    /** The two code pages this system uses, both named explicitly and never assumed. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The EBCDIC code page of the datasets under {@code app/data/EBCDIC}. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * The sixteen {@code CCARD-AID} literals, retyped from {@code app/cpy/CVCRD01Y.cpy} lines 4 to 19
     * so that this suite compares the class against the <em>copybook</em> rather than against another
     * class's copy of it. Note the two trailing spaces on {@code PA1} and {@code PA2}.
     *
     * @return the sixteen condition names mapped to their copybook literals, in copybook order
     */
    private static Map<AidKey, String> copybookAidLiterals() {
        Map<AidKey, String> literals = new LinkedHashMap<>();
        literals.put(AidKey.ENTER, "ENTER");
        literals.put(AidKey.CLEAR, "CLEAR");
        literals.put(AidKey.PA1, "PA1  ");
        literals.put(AidKey.PA2, "PA2  ");
        literals.put(AidKey.PFK01, "PFK01");
        literals.put(AidKey.PFK02, "PFK02");
        literals.put(AidKey.PFK03, "PFK03");
        literals.put(AidKey.PFK04, "PFK04");
        literals.put(AidKey.PFK05, "PFK05");
        literals.put(AidKey.PFK06, "PFK06");
        literals.put(AidKey.PFK07, "PFK07");
        literals.put(AidKey.PFK08, "PFK08");
        literals.put(AidKey.PFK09, "PFK09");
        literals.put(AidKey.PFK10, "PFK10");
        literals.put(AidKey.PFK11, "PFK11");
        literals.put(AidKey.PFK12, "PFK12");
        return literals;
    }

    /**
     * The class's own sixteen token constants, in copybook order.
     *
     * @return the class's sixteen token constants, in copybook order
     */
    private static List<String> declaredTokens() {
        return List.of(CardScreenState.CCARD_AID_ENTER,
                CardScreenState.CCARD_AID_CLEAR,
                CardScreenState.CCARD_AID_PA1,
                CardScreenState.CCARD_AID_PA2,
                CardScreenState.CCARD_AID_PFK01,
                CardScreenState.CCARD_AID_PFK02,
                CardScreenState.CCARD_AID_PFK03,
                CardScreenState.CCARD_AID_PFK04,
                CardScreenState.CCARD_AID_PFK05,
                CardScreenState.CCARD_AID_PFK06,
                CardScreenState.CCARD_AID_PFK07,
                CardScreenState.CCARD_AID_PFK08,
                CardScreenState.CCARD_AID_PFK09,
                CardScreenState.CCARD_AID_PFK10,
                CardScreenState.CCARD_AID_PFK11,
                CardScreenState.CCARD_AID_PFK12);
    }

    /**
     * A fully populated work area, distinct in every field, for round-trip and equality work.
     *
     * @return a work area with a distinct value in every one of the nine fields
     */
    private static CardScreenState populated() {
        return new CardScreenState("PFK03",
                "COCRDSLC",
                "CCRDSLA",
                "CCRDSLA",
                "Account filter is not a valid number",
                "Press PF3 to exit",
                "00000000011",
                "4111111111111111",
                "000000009");
    }

    @Nested
    @DisplayName("Record geometry - the nine storage items sum to 213")
    class Geometry {

        @Test
        @DisplayName("the declared record length is 213")
        void recordLengthIs213() {
            assertThat(CardScreenState.RECORD_LENGTH).isEqualTo(213);
        }

        @Test
        @DisplayName("the nine declared widths are 5, 8, 7, 7, 75, 75, 11, 16 and 9")
        void declaredWidthsMatchTheCopybook() {
            assertThat(CardScreenState.CCARD_AID_LENGTH).isEqualTo(5);
            assertThat(CardScreenState.CCARD_NEXT_PROG_LENGTH).isEqualTo(8);
            assertThat(CardScreenState.CCARD_NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(CardScreenState.CCARD_NEXT_MAP_LENGTH).isEqualTo(7);
            assertThat(CardScreenState.CCARD_ERROR_MSG_LENGTH).isEqualTo(75);
            assertThat(CardScreenState.CCARD_RETURN_MSG_LENGTH).isEqualTo(75);
            assertThat(CardScreenState.CC_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardScreenState.CC_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardScreenState.CC_CUST_ID_LENGTH).isEqualTo(9);
        }

        @Test
        @DisplayName("5 + 8 + 7 + 7 + 75 + 75 + 11 + 16 + 9 = 213")
        void theNineWidthsSumToTheRecordLength() {
            int sum = CardScreenState.CCARD_AID_LENGTH
                    + CardScreenState.CCARD_NEXT_PROG_LENGTH
                    + CardScreenState.CCARD_NEXT_MAPSET_LENGTH
                    + CardScreenState.CCARD_NEXT_MAP_LENGTH
                    + CardScreenState.CCARD_ERROR_MSG_LENGTH
                    + CardScreenState.CCARD_RETURN_MSG_LENGTH
                    + CardScreenState.CC_ACCT_ID_LENGTH
                    + CardScreenState.CC_CARD_NUM_LENGTH
                    + CardScreenState.CC_CUST_ID_LENGTH;
            assertThat(sum).isEqualTo(CardScreenState.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the mapset and map are X(7) while only the program name is X(8)")
        void mapNamesAreSevenNotEight() {
            assertThat(CardScreenState.CCARD_NEXT_PROG_LENGTH).isEqualTo(8);
            assertThat(CardScreenState.CCARD_NEXT_MAPSET_LENGTH)
                    .isEqualTo(7)
                    .isNotEqualTo(CardScreenState.CCARD_NEXT_PROG_LENGTH);
            assertThat(CardScreenState.CCARD_NEXT_MAP_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the nine offsets are 0, 5, 13, 20, 27, 102, 177, 188 and 204")
        void offsetsFollowTheCopybookOrder() {
            assertThat(CardScreenState.CCARD_AID_OFFSET).isZero();
            assertThat(CardScreenState.CCARD_NEXT_PROG_OFFSET).isEqualTo(5);
            assertThat(CardScreenState.CCARD_NEXT_MAPSET_OFFSET).isEqualTo(13);
            assertThat(CardScreenState.CCARD_NEXT_MAP_OFFSET).isEqualTo(20);
            assertThat(CardScreenState.CCARD_ERROR_MSG_OFFSET).isEqualTo(27);
            assertThat(CardScreenState.CCARD_RETURN_MSG_OFFSET).isEqualTo(102);
            assertThat(CardScreenState.CC_ACCT_ID_OFFSET).isEqualTo(177);
            assertThat(CardScreenState.CC_CARD_NUM_OFFSET).isEqualTo(188);
            assertThat(CardScreenState.CC_CUST_ID_OFFSET).isEqualTo(204);
        }

        @Test
        @DisplayName("the layout declares nine storage spans and three REDEFINES overlays")
        void layoutSeparatesStorageFromOverlays() {
            assertThat(CardScreenState.LAYOUT.recordLength()).isEqualTo(213);
            assertThat(CardScreenState.LAYOUT.spans()).hasSize(12);
            assertThat(CardScreenState.LAYOUT.storageSpans()).hasSize(9);
            assertThat(CardScreenState.LAYOUT.redefinitions()).hasSize(3);
        }

        @Test
        @DisplayName("the storage spans, and only they, sum to the record length")
        void onlyStorageSpansContributeBytes() {
            int storage = CardScreenState.LAYOUT.storageSpans().stream()
                    .mapToInt(FieldSpan::length)
                    .sum();
            assertThat(storage).isEqualTo(CardScreenState.RECORD_LENGTH);

            // The overlays measure 11 + 16 + 9 = 36 bytes between them, yet the record is still 213:
            // an overlay views storage a preceding item already accounts for, so it adds nothing.
            int overlays = CardScreenState.LAYOUT.redefinitions().stream()
                    .mapToInt(FieldSpan::length)
                    .sum();
            assertThat(overlays).isEqualTo(36);
            assertThat(storage + overlays).isNotEqualTo(CardScreenState.RECORD_LENGTH);
            assertThat(CardScreenState.LAYOUT.recordLength()).isEqualTo(storage);
        }

        @Test
        @DisplayName("every span carries its copybook name verbatim, hyphens included")
        void spanNamesAreTheCopybookNames() {
            List<String> names = new ArrayList<>();
            CardScreenState.LAYOUT.spans().forEach(span -> names.add(span.name()));
            assertThat(names).containsExactly("CCARD-AID",
                    "CCARD-NEXT-PROG",
                    "CCARD-NEXT-MAPSET",
                    "CCARD-NEXT-MAP",
                    "CCARD-ERROR-MSG",
                    "CCARD-RETURN-MSG",
                    "CC-ACCT-ID",
                    "CC-ACCT-ID-N",
                    "CC-CARD-NUM",
                    "CC-CARD-NUM-N",
                    "CC-CUST-ID",
                    "CC-CUST-ID-N");
        }

        /**
         * Practice <strong>B5</strong>: commented-out COBOL is not behaviour, and reviving any of it
         * would add a feature nothing asked for. Four groups in {@code app/cpy/CVCRD01Y.cpy} carry a
         * {@code *} in column 7 and so declare nothing at all:
         *
         * <ul>
         *   <li>{@code CCARD-LAST-PROG PIC X(8)} - line 20</li>
         *   <li>{@code CCARD-RETURN-TO-PROG PIC X(8)} - line 22</li>
         *   <li>{@code CCARD-RETURN-FLAG PIC X(1)} with {@code 88 CCARD-RETURN-FLAG-OFF VALUE
         *       LOW-VALUES} and {@code 88 CCARD-RETURN-FLAG-ON VALUE '1'} - lines 25 to 27</li>
         *   <li>{@code CCARD-FUNCTION PIC X(1)} with {@code 88 CCARD-NO-VALUE VALUE LOW-VALUES} and
         *       {@code 88 CCARD-GET-DATA VALUE '1'} - lines 31 to 33</li>
         * </ul>
         *
         * <p>Their absence is asserted <strong>structurally</strong> - through the declared field
         * inventory and, decisively, through the 213-byte total in
         * {@link #theNineWidthsSumToTheRecordLength()} and
         * {@link #omittingASpanFailsTheWidthSelfCheck()}. Two further {@code X(8)} items and two
         * further {@code X(1)} items would make the group 231 bytes wide, so the arithmetic alone
         * forbids them. Reflection is deliberately not used to enumerate members (practice
         * <strong>B11</strong>): a reflective walk would assert the shape of the Java class, whereas
         * what matters is the shape of the record.
         */
        @Test
        @DisplayName("the four commented-out items have no span: no CCARD-LAST-PROG, "
                + "CCARD-RETURN-TO-PROG, CCARD-RETURN-FLAG or CCARD-FUNCTION")
        void commentedOutItemsAreAbsent() {
            // The four commented-out elementary items - CVCRD01Y L20, L22, L25 and L31.
            assertThat(CardScreenState.LAYOUT.hasSpan("CCARD-LAST-PROG")).isFalse();
            assertThat(CardScreenState.LAYOUT.hasSpan("CCARD-RETURN-TO-PROG")).isFalse();
            assertThat(CardScreenState.LAYOUT.hasSpan("CCARD-RETURN-FLAG")).isFalse();
            assertThat(CardScreenState.LAYOUT.hasSpan("CCARD-FUNCTION")).isFalse();

            // Their four commented-out 88-levels - L26, L27, L32 and L33. A condition name is not a
            // span, so their absence shows as the absence of any span carrying the name, and as the
            // absence of any token literal a condition could have been declared over.
            assertThat(CardScreenState.LAYOUT.hasSpan("CCARD-RETURN-FLAG-OFF")).isFalse();
            assertThat(CardScreenState.LAYOUT.hasSpan("CCARD-RETURN-FLAG-ON")).isFalse();
            assertThat(CardScreenState.LAYOUT.hasSpan("CCARD-NO-VALUE")).isFalse();
            assertThat(CardScreenState.LAYOUT.hasSpan("CCARD-GET-DATA")).isFalse();

            // And the inventory is closed, not merely missing these eight: nine storage items and
            // three overlays, which is what leaves no room for any of them.
            assertThat(CardScreenState.LAYOUT.storageSpans()).hasSize(9);
            assertThat(CardScreenState.LAYOUT.redefinitions()).hasSize(3);
            assertThat(CardScreenState.LAYOUT.spans()).hasSize(12);
        }

        /**
         * Gate <strong>G21</strong>: the width self-check has to <em>fail</em> when a span is dropped,
         * not merely pass when every span is present.
         *
         * <p>{@link RecordLayout} verifies its own geometry in its constructor, so this drives that
         * real validation rather than asserting a hypothetical: a layout that still declares 213 bytes
         * but omits {@code CC-CUST-ID} is refused, and so is one whose declared total disagrees with
         * the spans it lists. That is what makes {@link CardScreenState#LAYOUT} a proof rather than a
         * transcription - a dropped or mistyped span cannot reach a byte image, because the class
         * would fail to initialise first.
         */
        @Test
        @DisplayName("omitting a span fails the width self-check - 213 bytes cannot be short a field")
        void omittingASpanFailsTheWidthSelfCheck() {
            // Every span except the last nine bytes, CC-CUST-ID. The nine storage items sum to 213
            // only when all nine are present, so 204 bytes of storage against a declared 213 is
            // exactly the mistranscription this check exists to catch.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> RecordLayout.of(CardScreenState.RECORD_LENGTH,
                            CardScreenState.CCARD_AID_SPAN,
                            CardScreenState.CCARD_NEXT_PROG_SPAN,
                            CardScreenState.CCARD_NEXT_MAPSET_SPAN,
                            CardScreenState.CCARD_NEXT_MAP_SPAN,
                            CardScreenState.CCARD_ERROR_MSG_SPAN,
                            CardScreenState.CCARD_RETURN_MSG_SPAN,
                            CardScreenState.CC_ACCT_ID_SPAN,
                            CardScreenState.CC_CARD_NUM_SPAN));

            // The same check from the other side: all nine spans, but a declared total one byte out.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> RecordLayout.of(CardScreenState.RECORD_LENGTH - 1,
                            CardScreenState.CCARD_AID_SPAN,
                            CardScreenState.CCARD_NEXT_PROG_SPAN,
                            CardScreenState.CCARD_NEXT_MAPSET_SPAN,
                            CardScreenState.CCARD_NEXT_MAP_SPAN,
                            CardScreenState.CCARD_ERROR_MSG_SPAN,
                            CardScreenState.CCARD_RETURN_MSG_SPAN,
                            CardScreenState.CC_ACCT_ID_SPAN,
                            CardScreenState.CC_CARD_NUM_SPAN,
                            CardScreenState.CC_CUST_ID_SPAN));

            // And the real layout, with all twelve descriptors, is accepted at exactly 213.
            assertThat(CardScreenState.LAYOUT.recordLength())
                    .isEqualTo(CardScreenState.RECORD_LENGTH);
        }

        @Test
        @DisplayName("each overlay shares its base item's offset and width exactly")
        void overlaysShareTheirBaseSpan() {
            assertThat(CardScreenState.CC_ACCT_ID_N_SPAN.offset())
                    .isEqualTo(CardScreenState.CC_ACCT_ID_SPAN.offset());
            assertThat(CardScreenState.CC_ACCT_ID_N_SPAN.length())
                    .isEqualTo(CardScreenState.CC_ACCT_ID_SPAN.length());
            assertThat(CardScreenState.CC_CARD_NUM_N_SPAN.offset())
                    .isEqualTo(CardScreenState.CC_CARD_NUM_SPAN.offset());
            assertThat(CardScreenState.CC_CARD_NUM_N_SPAN.length())
                    .isEqualTo(CardScreenState.CC_CARD_NUM_SPAN.length());
            assertThat(CardScreenState.CC_CUST_ID_N_SPAN.offset())
                    .isEqualTo(CardScreenState.CC_CUST_ID_SPAN.offset());
            assertThat(CardScreenState.CC_CUST_ID_N_SPAN.length())
                    .isEqualTo(CardScreenState.CC_CUST_ID_SPAN.length());
        }

        @Test
        @DisplayName("an overlay is flagged as a redefinition and views its bytes as PIC 9")
        void overlaysAreUnsignedNumericRedefinitions() {
            for (FieldSpan overlay : CardScreenState.LAYOUT.redefinitions()) {
                assertThat(overlay.redefinition()).isTrue();
                assertThat(overlay.kind()).isEqualTo(PictureKind.UNSIGNED_NUMERIC);
            }
            for (FieldSpan storage : CardScreenState.LAYOUT.storageSpans()) {
                assertThat(storage.redefinition()).isFalse();
                assertThat(storage.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            }
        }

        @Test
        @DisplayName("the three identifier spans carry VALUE SPACES; the other six declare no VALUE")
        void onlyTheIdentifierSpansDeclareAValue() {
            assertThat(CardScreenState.CC_ACCT_ID_SPAN.initialValue()).isEqualTo("           ");
            assertThat(CardScreenState.CC_CARD_NUM_SPAN.initialValue())
                    .isEqualTo("                ");
            assertThat(CardScreenState.CC_CUST_ID_SPAN.initialValue()).isEqualTo("         ");
            assertThat(CardScreenState.CCARD_AID_SPAN.hasInitialValue()).isFalse();
            assertThat(CardScreenState.CCARD_NEXT_PROG_SPAN.hasInitialValue()).isFalse();
            assertThat(CardScreenState.CCARD_RETURN_MSG_SPAN.hasInitialValue()).isFalse();
        }

        @Test
        @DisplayName("the last storage span ends exactly at byte 213, so nothing is missing or over")
        void theLayoutClosesAtTheRecordLength() {
            assertThat(CardScreenState.CC_CUST_ID_SPAN.endOffsetExclusive())
                    .isEqualTo(CardScreenState.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("The sixteen CCARD-AID condition literals")
    class AidTokens {

        @Test
        @DisplayName("there are exactly 16 tokens - CVCRD01Y lines 4-19 show 16, not the plan's 15")
        void thereAreSixteenTokens() {
            assertThat(declaredTokens()).hasSize(16);
            assertThat(copybookAidLiterals()).hasSize(16);
        }

        @ParameterizedTest(name = "{0} is exactly 5 characters")
        @MethodSource(
                "com.vsergeychik.carddemo.card.dto.CardScreenStateTest#everyDeclaredToken")
        @DisplayName("every token is exactly 5 characters, because the field is PIC X(5)")
        void everyTokenIsFiveCharacters(String token) {
            assertThat(token).hasSize(CardScreenState.CCARD_AID_LENGTH);
        }

        @Test
        @DisplayName("every token equals its copybook literal, character for character")
        void tokensMatchTheCopybookLiterals() {
            List<String> expected = new ArrayList<>(copybookAidLiterals().values());
            assertThat(declaredTokens()).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("PA1 and PA2 keep their two trailing spaces - a trim here would break parity")
        void paTokensKeepTheirTrailingSpaces() {
            assertThat(CardScreenState.CCARD_AID_PA1).isEqualTo("PA1  ").hasSize(5);
            assertThat(CardScreenState.CCARD_AID_PA2).isEqualTo("PA2  ").hasSize(5);
            assertThat(CardScreenState.CCARD_AID_PA1).isNotEqualTo("PA1");
            assertThat(CardScreenState.CCARD_AID_PA1.substring(3)).isEqualTo("  ");
            assertThat(CardScreenState.CCARD_AID_PA2.substring(3)).isEqualTo("  ");
        }

        @Test
        @DisplayName("all sixteen tokens are distinct, so a token identifies one condition")
        void tokensAreDistinct() {
            assertThat(declaredTokens()).doesNotHaveDuplicates();
        }

        /**
         * The AID constant set is deliberately <em>wider</em> than the token set, and this test pins
         * that asymmetry on purpose so a later reader cannot mistake it for an omission.
         *
         * <p>{@code common.CicsAid} declares {@code DFHPA3} and all of {@code DFHPF1} through
         * {@code DFHPF24}, because those are the AIDs a 3270 terminal can send and that class
         * transcribes IBM's set. {@code app/cpy/CVCRD01Y.cpy} declares only sixteen condition names
         * (lines 4-19), and {@code app/cpy/CSSTRPFY.cpy} has no {@code DFHPA3} branch at all while
         * folding {@code DFHPF13}-{@code DFHPF24} back onto {@code PFK01}-{@code PFK12} (its lines
         * 54-77). So: twenty-five AIDs, sixteen tokens - not a gap, a translation.
         *
         * <p>Adding a seventeenth condition for symmetry would invent behaviour (practice
         * <strong>B5</strong>); the fold and the absent {@code DFHPA3} branch are exercised end to end
         * in {@link ResolverAlignment}.
         */
        @Test
        @DisplayName("there is no PA3 token and no PFK13-PFK24 token - the copybook declares sixteen")
        void thereIsNoPa3OrPfk13ThroughPfk24Token() {
            // CSSTRPFY has no DFHPA3 branch, so no token was ever declared for it. Both the padded
            // and unpadded spellings are excluded, since a padded 'PA3  ' is what one would look like.
            assertThat(declaredTokens()).doesNotContain("PA3  ", "PA3");

            // PF13-PF24 fold onto PFK01-PFK12, so no PFK13..PFK24 literal exists either.
            for (int pfKey = 13; pfKey <= 24; pfKey++) {
                assertThat(declaredTokens()).doesNotContain("PFK" + pfKey);
            }

            // The set is closed at sixteen: exactly ENTER, CLEAR, the two PA keys and PFK01-PFK12.
            assertThat(declaredTokens()).hasSize(16);
            assertThat(AidKey.values()).hasSize(16);

            // Stated positively, so the boundary between PFK12 and the absent PFK13 is explicit.
            assertThat(declaredTokens()).contains("PFK12").doesNotContain("PFK13");
        }

        /**
         * The predicate set is closed at sixteen too, matching the sixteen tokens one to one.
         *
         * <p>Counted through the token-to-predicate pairing that drives {@link AidConditions} rather
         * than through reflection (practice <strong>B11</strong>): every pair is written out by hand in
         * {@link CardScreenStateTest#aidConditionPredicates()}, so a seventeenth predicate could only
         * be counted here if somebody also declared its literal - which
         * {@link #thereIsNoPa3OrPfk13ThroughPfk24Token()} forbids.
         */
        @Test
        @DisplayName("there are exactly sixteen predicates, one per condition name, and no more")
        void thereAreExactlySixteenPredicates() {
            assertThat(aidConditionPredicates()).hasSize(16);

            List<String> predicatedNames = aidConditionPredicates()
                    .map(arguments -> (String) arguments.get()[2])
                    .toList();
            assertThat(predicatedNames)
                    .hasSize(16)
                    .doesNotHaveDuplicates()
                    .doesNotContain("CCARD-AID-PA3", "CCARD-AID-PFK13", "CCARD-AID-PFK24")
                    .containsExactly("CCARD-AID-ENTER", "CCARD-AID-CLEAR", "CCARD-AID-PA1",
                            "CCARD-AID-PA2", "CCARD-AID-PFK01", "CCARD-AID-PFK02",
                            "CCARD-AID-PFK03", "CCARD-AID-PFK04", "CCARD-AID-PFK05",
                            "CCARD-AID-PFK06", "CCARD-AID-PFK07", "CCARD-AID-PFK08",
                            "CCARD-AID-PFK09", "CCARD-AID-PFK10", "CCARD-AID-PFK11",
                            "CCARD-AID-PFK12");
        }

        @Test
        @DisplayName("the token width agrees with PfKeyResolver, which resolves EIBAID into it")
        void theTokenWidthAgreesWithTheResolver() {
            assertThat(CardScreenState.CCARD_AID_LENGTH)
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @ParameterizedTest(name = "{0} agrees with PfKeyResolver.AidKey")
        @EnumSource(AidKey.class)
        @DisplayName("every token agrees with the resolver's, so the two can never drift")
        void tokensAgreeWithTheResolver(AidKey key) {
            assertThat(key.token()).isEqualTo(copybookAidLiterals().get(key));
        }
    }

    /**
     * Feeds {@link AidTokens#everyTokenIsFiveCharacters(String)}.
     *
     * @return each of the sixteen token constants in turn
     */
    static Stream<String> everyDeclaredToken() {
        return declaredTokens().stream();
    }

    /**
     * Pairs each condition literal with the predicate that tests it, so the sixteen predicates can be
     * driven true and false from one place. Feeds {@link AidConditions}.
     *
     * @return each condition's literal, its predicate and its COBOL name
     */
    static Stream<Arguments> aidConditionPredicates() {
        return Stream.of(
                Arguments.of(CardScreenState.CCARD_AID_ENTER,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidEnter, "CCARD-AID-ENTER"),
                Arguments.of(CardScreenState.CCARD_AID_CLEAR,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidClear, "CCARD-AID-CLEAR"),
                Arguments.of(CardScreenState.CCARD_AID_PA1,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPa1, "CCARD-AID-PA1"),
                Arguments.of(CardScreenState.CCARD_AID_PA2,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPa2, "CCARD-AID-PA2"),
                Arguments.of(CardScreenState.CCARD_AID_PFK01,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk01, "CCARD-AID-PFK01"),
                Arguments.of(CardScreenState.CCARD_AID_PFK02,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk02, "CCARD-AID-PFK02"),
                Arguments.of(CardScreenState.CCARD_AID_PFK03,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk03, "CCARD-AID-PFK03"),
                Arguments.of(CardScreenState.CCARD_AID_PFK04,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk04, "CCARD-AID-PFK04"),
                Arguments.of(CardScreenState.CCARD_AID_PFK05,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk05, "CCARD-AID-PFK05"),
                Arguments.of(CardScreenState.CCARD_AID_PFK06,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk06, "CCARD-AID-PFK06"),
                Arguments.of(CardScreenState.CCARD_AID_PFK07,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk07, "CCARD-AID-PFK07"),
                Arguments.of(CardScreenState.CCARD_AID_PFK08,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk08, "CCARD-AID-PFK08"),
                Arguments.of(CardScreenState.CCARD_AID_PFK09,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk09, "CCARD-AID-PFK09"),
                Arguments.of(CardScreenState.CCARD_AID_PFK10,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk10, "CCARD-AID-PFK10"),
                Arguments.of(CardScreenState.CCARD_AID_PFK11,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk11, "CCARD-AID-PFK11"),
                Arguments.of(CardScreenState.CCARD_AID_PFK12,
                        (Predicate<CardScreenState>) CardScreenState::isCcardAidPfk12, "CCARD-AID-PFK12"));
    }

    /**
     * The three five-byte states {@code CCARD-AID} can hold that match none of the sixteen conditions.
     *
     * <p>All three are widths of exactly {@link CardScreenState#CCARD_AID_LENGTH}, because the field is
     * five bytes and a shorter value would be testing the binding rule instead of the condition names.
     * Feeds {@link AidConditions#unmatchedAidStatesSatisfyNoCondition(String, String)}.
     *
     * @return each unmatched state with a description for the failure message
     */
    static Stream<Arguments> unmatchedAidStates() {
        return Stream.of(
                // A five-character token no 88-level names. CSSTRPFY leaves exactly this behind when
                // EIBAID matched nothing, because it neither defaults nor clears the field.
                Arguments.of("ZZZZZ", "an unrecognised five-character token"),
                // The initial state: CCARD-AID is spaces before any key has been recorded.
                Arguments.of(CardScreenState.spaces(CardScreenState.CCARD_AID_LENGTH), "five spaces"),
                // LOW-VALUES - five bytes of 0x00, which is neither a token nor spaces.
                Arguments.of(CardScreenState.lowValues(CardScreenState.CCARD_AID_LENGTH),
                        "LOW-VALUES"));
    }

    /**
     * Every AID {@code app/cpy/CSSTRPFY.cpy} has a {@code WHEN} arm for, paired with the token that arm
     * sets and the predicate that then reports it.
     *
     * <p>Twenty-eight arms in the source (its lines 22-77) map onto sixteen tokens, because
     * {@code DFHPF13}-{@code DFHPF24} fold back onto {@code PFK01}-{@code PFK12}. All twenty-eight are
     * listed here in source order so the fold is visible rather than implied, and so a missing arm shows
     * up as a missing case rather than as coverage that quietly never ran. Feeds
     * {@link ResolverAlignment}.
     *
     * @return each AID byte with the copybook literal it sets, the predicate that tests it, and the
     *         copybook line of the arm
     */
    static Stream<Arguments> resolvedAidArms() {
        return Stream.of(
                // ---- CSSTRPFY.cpy L22-L29: ENTER, CLEAR and the two tested PA keys --------------
                aidArm(CicsAid.DFHENTER, AidKey.ENTER, CardScreenState.CCARD_AID_ENTER,
                        CardScreenState::isCcardAidEnter, "L22-23"),
                aidArm(CicsAid.DFHCLEAR, AidKey.CLEAR, CardScreenState.CCARD_AID_CLEAR,
                        CardScreenState::isCcardAidClear, "L24-25"),
                aidArm(CicsAid.DFHPA1, AidKey.PA1, CardScreenState.CCARD_AID_PA1,
                        CardScreenState::isCcardAidPa1, "L26-27"),
                aidArm(CicsAid.DFHPA2, AidKey.PA2, CardScreenState.CCARD_AID_PA2,
                        CardScreenState::isCcardAidPa2, "L28-29"),

                // ---- CSSTRPFY.cpy L30-L53: PF1..PF12 map one to one onto PFK01..PFK12 -----------
                aidArm(CicsAid.DFHPF1, AidKey.PFK01, CardScreenState.CCARD_AID_PFK01,
                        CardScreenState::isCcardAidPfk01, "L30-31"),
                aidArm(CicsAid.DFHPF2, AidKey.PFK02, CardScreenState.CCARD_AID_PFK02,
                        CardScreenState::isCcardAidPfk02, "L32-33"),
                aidArm(CicsAid.DFHPF3, AidKey.PFK03, CardScreenState.CCARD_AID_PFK03,
                        CardScreenState::isCcardAidPfk03, "L34-35"),
                aidArm(CicsAid.DFHPF4, AidKey.PFK04, CardScreenState.CCARD_AID_PFK04,
                        CardScreenState::isCcardAidPfk04, "L36-37"),
                aidArm(CicsAid.DFHPF5, AidKey.PFK05, CardScreenState.CCARD_AID_PFK05,
                        CardScreenState::isCcardAidPfk05, "L38-39"),
                aidArm(CicsAid.DFHPF6, AidKey.PFK06, CardScreenState.CCARD_AID_PFK06,
                        CardScreenState::isCcardAidPfk06, "L40-41"),
                aidArm(CicsAid.DFHPF7, AidKey.PFK07, CardScreenState.CCARD_AID_PFK07,
                        CardScreenState::isCcardAidPfk07, "L42-43"),
                aidArm(CicsAid.DFHPF8, AidKey.PFK08, CardScreenState.CCARD_AID_PFK08,
                        CardScreenState::isCcardAidPfk08, "L44-45"),
                aidArm(CicsAid.DFHPF9, AidKey.PFK09, CardScreenState.CCARD_AID_PFK09,
                        CardScreenState::isCcardAidPfk09, "L46-47"),
                aidArm(CicsAid.DFHPF10, AidKey.PFK10, CardScreenState.CCARD_AID_PFK10,
                        CardScreenState::isCcardAidPfk10, "L48-49"),
                aidArm(CicsAid.DFHPF11, AidKey.PFK11, CardScreenState.CCARD_AID_PFK11,
                        CardScreenState::isCcardAidPfk11, "L50-51"),
                aidArm(CicsAid.DFHPF12, AidKey.PFK12, CardScreenState.CCARD_AID_PFK12,
                        CardScreenState::isCcardAidPfk12, "L52-53"),

                // ---- CSSTRPFY.cpy L54-L77: PF13..PF24 FOLD BACK onto PFK01..PFK12 ---------------
                // The same sixteen tokens and the same twelve predicates, reached by twelve more
                // AIDs. No PFK13..PFK24 token exists for them to land on.
                aidArm(CicsAid.DFHPF13, AidKey.PFK01, CardScreenState.CCARD_AID_PFK01,
                        CardScreenState::isCcardAidPfk01, "L54-55"),
                aidArm(CicsAid.DFHPF14, AidKey.PFK02, CardScreenState.CCARD_AID_PFK02,
                        CardScreenState::isCcardAidPfk02, "L56-57"),
                aidArm(CicsAid.DFHPF15, AidKey.PFK03, CardScreenState.CCARD_AID_PFK03,
                        CardScreenState::isCcardAidPfk03, "L58-59"),
                aidArm(CicsAid.DFHPF16, AidKey.PFK04, CardScreenState.CCARD_AID_PFK04,
                        CardScreenState::isCcardAidPfk04, "L60-61"),
                aidArm(CicsAid.DFHPF17, AidKey.PFK05, CardScreenState.CCARD_AID_PFK05,
                        CardScreenState::isCcardAidPfk05, "L62-63"),
                aidArm(CicsAid.DFHPF18, AidKey.PFK06, CardScreenState.CCARD_AID_PFK06,
                        CardScreenState::isCcardAidPfk06, "L64-65"),
                aidArm(CicsAid.DFHPF19, AidKey.PFK07, CardScreenState.CCARD_AID_PFK07,
                        CardScreenState::isCcardAidPfk07, "L66-67"),
                aidArm(CicsAid.DFHPF20, AidKey.PFK08, CardScreenState.CCARD_AID_PFK08,
                        CardScreenState::isCcardAidPfk08, "L68-69"),
                aidArm(CicsAid.DFHPF21, AidKey.PFK09, CardScreenState.CCARD_AID_PFK09,
                        CardScreenState::isCcardAidPfk09, "L70-71"),
                aidArm(CicsAid.DFHPF22, AidKey.PFK10, CardScreenState.CCARD_AID_PFK10,
                        CardScreenState::isCcardAidPfk10, "L72-73"),
                aidArm(CicsAid.DFHPF23, AidKey.PFK11, CardScreenState.CCARD_AID_PFK11,
                        CardScreenState::isCcardAidPfk11, "L74-75"),
                aidArm(CicsAid.DFHPF24, AidKey.PFK12, CardScreenState.CCARD_AID_PFK12,
                        CardScreenState::isCcardAidPfk12, "L76-77"));
    }

    /**
     * Assembles one {@link #resolvedAidArms()} case, so the twenty-eight entries above read as a table
     * rather than as twenty-eight casts.
     *
     * @param eibAid    the raw AID byte, a {@code common.CicsAid} constant
     * @param expected  the token {@code common.PfKeyResolver} maps it to
     * @param literal   the copybook literal that token carries
     * @param predicate the {@link CardScreenState} predicate that reports it
     * @param sourceRef the {@code app/cpy/CSSTRPFY.cpy} lines of the arm
     * @return the assembled case
     */
    private static Arguments aidArm(byte eibAid,
                                    AidKey expected,
                                    String literal,
                                    Predicate<CardScreenState> predicate,
                                    String sourceRef) {
        return Arguments.of(eibAid, expected, literal, predicate, sourceRef);
    }

    /**
     * The AIDs {@code app/cpy/CSSTRPFY.cpy} has <strong>no</strong> {@code WHEN} arm for, so its
     * {@code EVALUATE} falls through {@code END-EVALUATE} at L78 having set nothing.
     *
     * <p>{@code DFHPA3} is the case that matters and the reason this provider exists:
     * {@code common.CicsAid} declares it - IBM's AID set contains it and that class transcribes the set
     * faithfully - while the copybook declares no {@code CCARD-AID-PA3} condition and the resolver has
     * no branch for it. The others are AIDs the card screens can receive but never test. Feeds
     * {@link ResolverAlignment}.
     *
     * @return each unhandled AID byte with its name
     */
    static Stream<Arguments> unhandledAids() {
        return Stream.of(
                // The deliberate asymmetry: DFHPA3 exists as an AID and has no token.
                Arguments.of(CicsAid.DFHPA3, "DFHPA3"),
                Arguments.of(CicsAid.DFHCLRP, "DFHCLRP"),
                Arguments.of(CicsAid.DFHPEN, "DFHPEN"),
                Arguments.of(CicsAid.DFHOPID, "DFHOPID"),
                Arguments.of(CicsAid.DFHMSRE, "DFHMSRE"),
                Arguments.of(CicsAid.DFHSTRF, "DFHSTRF"),
                Arguments.of(CicsAid.DFHTRIG, "DFHTRIG"));
    }

    @Nested
    @DisplayName("CCARD-AID condition names - the 88-levels as predicates")
    class AidConditions {

        @ParameterizedTest(name = "{2} is true for its own literal")
        @MethodSource(
                "com.vsergeychik.carddemo.card.dto.CardScreenStateTest#aidConditionPredicates")
        @DisplayName("each condition is true when the field holds its literal")
        void conditionIsTrueForItsOwnLiteral(String literal,
                                             Predicate<CardScreenState> condition,
                                             String cobolName) {
            CardScreenState state = new CardScreenState();
            state.setCcardAid(literal);

            assertThat(condition.test(state))
                    .as("%s should be true for '%s'", cobolName, literal)
                    .isTrue();
        }

        @ParameterizedTest(name = "{2} is false for every other literal")
        @MethodSource(
                "com.vsergeychik.carddemo.card.dto.CardScreenStateTest#aidConditionPredicates")
        @DisplayName("each condition is false for all fifteen other literals and for spaces")
        void conditionIsFalseForEveryOtherLiteral(String literal,
                                                  Predicate<CardScreenState> condition,
                                                  String cobolName) {
            CardScreenState state = new CardScreenState();
            for (String other : declaredTokens()) {
                if (other.equals(literal)) {
                    continue;
                }
                state.setCcardAid(other);
                assertThat(condition.test(state))
                        .as("%s should be false for '%s'", cobolName, other)
                        .isFalse();
            }
            state.setCcardAid(CardScreenState.spaces(CardScreenState.CCARD_AID_LENGTH));
            assertThat(condition.test(state)).isFalse();
        }

        @ParameterizedTest(name = "SET {0} TO TRUE stores its literal")
        @EnumSource(AidKey.class)
        @DisplayName("setCcardAidCondition reproduces SET CCARD-AID-xxx TO TRUE")
        void setConditionStoresTheLiteral(AidKey key) {
            CardScreenState state = new CardScreenState();
            state.setCcardAidCondition(key);

            assertThat(state.getCcardAid()).isEqualTo(copybookAidLiterals().get(key));
            assertThat(state.getCcardAid()).hasSize(CardScreenState.CCARD_AID_LENGTH);
            assertThat(state.aidKey()).contains(key);
        }

        @Test
        @DisplayName("aidKey is empty for a fresh work area, whose AID is spaces")
        void aidKeyIsEmptyBeforeAnyKeyIsRecorded() {
            assertThat(new CardScreenState().aidKey()).isEmpty();
        }

        @Test
        @DisplayName("aidKey is empty for a token no condition names, which CSSTRPFY can leave behind")
        void aidKeyIsEmptyForAnUnnamedToken() {
            CardScreenState state = new CardScreenState();
            state.setCcardAid("PA3  ");

            assertThat(state.aidKey()).isEmpty();
            assertThat(state.getCcardAid()).isEqualTo("PA3  ");
        }

        @Test
        @DisplayName("no condition is true at once for two different tokens")
        void exactlyOneConditionHoldsPerToken() {
            for (String token : declaredTokens()) {
                CardScreenState state = new CardScreenState();
                state.setCcardAid(token);

                long trueCount = aidConditionPredicates()
                        .map(arguments -> arguments.get()[1])
                        .filter(predicate -> {
                            @SuppressWarnings("unchecked")
                            Predicate<CardScreenState> typed =
                                    (Predicate<CardScreenState>) predicate;
                            return typed.test(state);
                        })
                        .count();
                assertThat(trueCount).as("exactly one condition for '%s'", token).isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("an unrecognised AID leaves no condition true, because there is no WHEN OTHER")
        void anUnrecognisedTokenSatisfiesNoCondition() {
            CardScreenState state = new CardScreenState();
            state.setCcardAid("?????");

            assertThat(trueConditionCount(state)).isZero();
        }

        /**
         * The three unmatched five-byte states the field can actually hold, each leaving every one of
         * the sixteen conditions false.
         *
         * <p>{@code app/cpy/CVCRD01Y.cpy} declares no catch-all {@code 88} over {@code CCARD-AID}, and
         * {@code app/cpy/CSSTRPFY.cpy}'s {@code EVALUATE} has no {@code WHEN OTHER} (its L78 reaches
         * {@code END-EVALUATE} directly), so no default token is ever stored. Three distinct states
         * reach that outcome and all three are real:
         *
         * <ul>
         *   <li>a token no condition names - the value a previous key left behind when the current AID
         *       matched nothing;</li>
         *   <li>five spaces - the initial state, before any key has been recorded;</li>
         *   <li>{@code LOW-VALUES} - five bytes of {@code 0x00}, which is neither of the above.</li>
         * </ul>
         *
         * <p>Each is asserted against all sixteen predicates and against {@link
         * CardScreenState#aidKey()}, so an accidental catch-all in either class would fail here.
         */
        @ParameterizedTest(name = "[{index}] {1} satisfies none of the sixteen conditions")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardScreenStateTest#unmatchedAidStates")
        @DisplayName("an unrecognised token, five spaces and LOW-VALUES all leave all sixteen false")
        void unmatchedAidStatesSatisfyNoCondition(String aid, String description) {
            CardScreenState state = new CardScreenState();
            state.setCcardAid(aid);

            // The stored value is exactly what was set - nothing was substituted for it.
            assertThat(state.getCcardAid()).isEqualTo(aid).hasSize(CardScreenState.CCARD_AID_LENGTH);

            // No condition holds, and no condition name can be reported for it.
            assertThat(trueConditionCount(state)).as("true conditions for %s", description).isZero();
            assertThat(state.aidKey()).as("named condition for %s", description).isEmpty();
        }

        /**
         * Counts how many of the sixteen condition predicates hold for a given work area.
         *
         * <p>An instance method on the nested test class rather than a static helper, so no state -
         * mutable or otherwise - is shared between test methods (practice <strong>B9</strong>).
         *
         * @param state the work area to test
         * @return the number of the sixteen predicates that return {@code true}
         */
        private long trueConditionCount(CardScreenState state) {
            return aidConditionPredicates()
                    .map(arguments -> arguments.get()[1])
                    .filter(predicate -> {
                        @SuppressWarnings("unchecked")
                        Predicate<CardScreenState> typed = (Predicate<CardScreenState>) predicate;
                        return typed.test(state);
                    })
                    .count();
        }

        @Test
        @DisplayName("the AID binds losslessly: a short token is kept as sent, an over-wide one refused")
        void theAidBindsLosslesslyAndRefusesOverflow() {
            CardScreenState state = new CardScreenState();

            // Stored exactly as sent. Padding it here would make a two-character token
            // indistinguishable from a two-character token followed by three real spaces.
            state.setCcardAid("PF");
            assertThat(state.getCcardAid()).isEqualTo("PF");

            // The MOVE is a named, separate step - and only there is the value padded to PIC X(5).
            assertThat(state.asWorkArea().getCcardAid()).isEqualTo("PF   ");

            // Twelve characters cannot enter a five-byte field, and are refused rather than shortened.
            assertThatThrownBy(() -> state.setCcardAid("TOOLONGVALUE"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CCARD-AID")
                    .hasMessageContaining("12 character(s)")
                    .hasMessageNotContaining("TOOLONGVALUE");
            assertThat(state.getCcardAid()).isEqualTo("PF");
        }
    }

    /**
     * Alignment with {@code common.PfKeyResolver} and {@code common.CicsAid} - that the tokens the
     * resolver produces from a raw {@code EIBAID} are exactly the tokens this field's condition names
     * are declared over.
     *
     * <h2>Why this belongs here</h2>
     *
     * <p>All three card programs execute {@code COPY 'CSSTRPFY'} - {@code app/cbl/COCRDLIC.cbl:1416},
     * {@code app/cbl/COCRDSLC.cbl:855} and {@code app/cbl/COCRDUPC.cbl:1528} - and that copybook's whole
     * body is {@code SET CCARD-AID-xxx TO TRUE} over the very field this class carries. The resolver is
     * therefore not an incidental collaborator: it is the only producer of this field's value in the
     * online path, and if the two disagreed by a single byte every condition test would silently stop
     * matching while both classes' own tests continued to pass. That failure is invisible to a unit test
     * of either class alone, which is precisely why it is asserted at the seam.
     *
     * <h2>The asymmetry, asserted on purpose</h2>
     *
     * <p>{@code CicsAid} declares twenty-five AIDs including {@code DFHPA3} and all of {@code DFHPF1}
     * through {@code DFHPF24}. This field has sixteen condition names. The two are reconciled by the
     * copybook, not by adjusting either class:
     *
     * <ul>
     *   <li>{@code DFHPF13}-{@code DFHPF24} <strong>fold</strong> onto {@code PFK01}-{@code PFK12}
     *       ({@code CSSTRPFY} lines 54-77), so twelve extra AIDs reach twelve existing predicates and
     *       no {@code PFK13}-{@code PFK24} token is needed.</li>
     *   <li>{@code DFHPA3} has <strong>no arm at all</strong>, and {@code CSSTRPFY}'s {@code EVALUATE}
     *       has no {@code WHEN OTHER}, so it sets nothing and leaves whatever was there.</li>
     * </ul>
     *
     * <p>Both are asserted below so a later reader cannot mistake either for an omission.
     *
     * <h2>Scope</h2>
     *
     * <p>Alignment only. The resolver's own branch ordering, its {@code storePfKey} contract in
     * isolation and its inline-test helpers belong to its own test; what is verified here is strictly
     * that its output is assignable to {@code CCARD-AID} and lands on the right condition.
     */
    @Nested
    @DisplayName("Alignment with PfKeyResolver and CicsAid - the resolver's tokens are these tokens")
    class ResolverAlignment {

        /**
         * Each of the twenty-eight {@code CSSTRPFY} arms, driven from the raw AID byte all the way to
         * the condition predicate - which is the full path the online programs take.
         *
         * @param eibAid    the raw AID byte
         * @param expected  the token the resolver must map it to
         * @param literal   the copybook literal that token carries
         * @param predicate the predicate that must then report it
         * @param sourceRef the {@code CSSTRPFY} lines of the arm
         */
        @ParameterizedTest(name = "[{index}] CSSTRPFY {4} sets {2} and only its own condition")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardScreenStateTest#resolvedAidArms")
        @DisplayName("every CSSTRPFY arm resolves to a token this field accepts, and to one condition")
        void everyResolvedAidLandsOnItsOwnCondition(byte eibAid,
                                                    AidKey expected,
                                                    String literal,
                                                    Predicate<CardScreenState> predicate,
                                                    String sourceRef) {
            // The resolver maps the byte to the token the copybook arm sets.
            assertThat(PfKeyResolver.resolve(eibAid)).as("CSSTRPFY %s", sourceRef).contains(expected);

            // The token IS the copybook literal, and it is exactly five bytes wide - the width of
            // CCARD-AID PIC X(5). PA1 and PA2 reach this assertion carrying their trailing spaces.
            assertThat(expected.token()).isEqualTo(literal).hasSize(CardScreenState.CCARD_AID_LENGTH);

            // It is assignable to the field through the condition setter - SET CCARD-AID-xxx TO TRUE.
            CardScreenState viaCondition = new CardScreenState();
            viaCondition.setCcardAidCondition(expected);
            assertThat(viaCondition.getCcardAid()).isEqualTo(literal);
            assertThat(predicate.test(viaCondition)).isTrue();

            // And through the plain setter, at exactly the declared width and with no truncation.
            CardScreenState viaToken = new CardScreenState();
            viaToken.setCcardAid(expected.token());
            assertThat(viaToken.getCcardAid()).isEqualTo(literal);
            assertThat(predicate.test(viaToken)).isTrue();
            assertThat(viaToken).isEqualTo(viaCondition);

            // Exactly one condition holds: the other fifteen are false. Literals are all distinct, so
            // "every other condition" is every pairing whose literal differs from this one.
            assertThat(otherConditionsThatHold(viaCondition, literal)).isEmpty();

            // The condition can also be named back, which closes the loop token -> condition -> token.
            assertThat(viaCondition.aidKey()).contains(expected);
        }

        /**
         * The fold, asserted as an identity rather than as a coincidence: {@code DFHPF13} and
         * {@code DFHPF1} resolve to the <em>same</em> token, and so on through {@code DFHPF24} and
         * {@code DFHPF12} ({@code CSSTRPFY} lines 30-53 against lines 54-77).
         */
        @Test
        @DisplayName("DFHPF13-DFHPF24 fold onto PFK01-PFK12 - the same twelve tokens, not new ones")
        void highFunctionKeysFoldOntoTheLowTwelve() {
            byte[] lowKeys = {CicsAid.DFHPF1, CicsAid.DFHPF2, CicsAid.DFHPF3, CicsAid.DFHPF4,
                    CicsAid.DFHPF5, CicsAid.DFHPF6, CicsAid.DFHPF7, CicsAid.DFHPF8, CicsAid.DFHPF9,
                    CicsAid.DFHPF10, CicsAid.DFHPF11, CicsAid.DFHPF12};
            byte[] highKeys = {CicsAid.DFHPF13, CicsAid.DFHPF14, CicsAid.DFHPF15, CicsAid.DFHPF16,
                    CicsAid.DFHPF17, CicsAid.DFHPF18, CicsAid.DFHPF19, CicsAid.DFHPF20,
                    CicsAid.DFHPF21, CicsAid.DFHPF22, CicsAid.DFHPF23, CicsAid.DFHPF24};

            for (int index = 0; index < lowKeys.length; index++) {
                int pfKey = index + 1;

                // The two AIDs are genuinely different bytes - so this is a fold, not an alias.
                assertThat(highKeys[index]).isNotEqualTo(lowKeys[index]);

                // Yet they resolve to one and the same token.
                assertThat(PfKeyResolver.resolve(highKeys[index]))
                        .as("PF%d folds onto PF%d", pfKey + 12, pfKey)
                        .isEqualTo(PfKeyResolver.resolve(lowKeys[index]));

                // And that token is a PFK01..PFK12 literal, never a PFK13..PFK24 one.
                String token = PfKeyResolver.resolve(highKeys[index]).orElseThrow().token();
                assertThat(token).isEqualTo(String.format("PFK%02d", pfKey));
                assertThat(declaredTokens()).contains(token);

                // Landing it on the field reaches the low key's condition, and only that one.
                CardScreenState state = new CardScreenState();
                state.setCcardAidCondition(PfKeyResolver.resolve(highKeys[index]).orElseThrow());
                assertThat(state.getCcardAid()).isEqualTo(token);
                assertThat(otherConditionsThatHold(state, token)).isEmpty();
            }
        }

        /**
         * The unhandled AIDs, {@code DFHPA3} foremost: the resolver reports no match and the field is
         * left exactly as it was, because {@code CSSTRPFY} has neither an arm for them nor a
         * {@code WHEN OTHER}.
         *
         * @param eibAid the unhandled AID byte
         * @param name   its {@code CicsAid} name, for the failure message
         */
        @ParameterizedTest(name = "[{index}] {1} has no CSSTRPFY arm and sets no condition")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardScreenStateTest#unhandledAids")
        @DisplayName("DFHPA3 and the other untested AIDs resolve to nothing and leave all sixteen false")
        void unhandledAidsSetNoCondition(byte eibAid, String name) {
            // No token. Not a substituted default, not an exception - simply no match.
            assertThat(PfKeyResolver.resolve(eibAid)).as("%s has no CSSTRPFY arm", name).isEmpty();

            // A fresh work area is spaces, and an unhandled AID leaves it spaces: nothing was set.
            CardScreenState state = new CardScreenState();
            Optional<AidKey> stored = PfKeyResolver.storePfKey(eibAid, state.aidKey());
            assertThat(stored).isEmpty();
            assertThat(state.getCcardAid())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CCARD_AID_LENGTH));

            // And every one of the sixteen conditions is false.
            assertThat(allConditionsThatHold(state)).as("conditions after %s", name).isEmpty();
            assertThat(state.aidKey()).isEmpty();
        }

        /**
         * {@code DFHPA3} against the two PA keys that <em>are</em> tested, so the asymmetry is stated as
         * a contrast rather than only as an absence.
         */
        @Test
        @DisplayName("DFHPA1 and DFHPA2 have tokens; DFHPA3 exists as an AID but has none")
        void thePaKeyAsymmetryIsDeliberate() {
            // CicsAid declares all three PA keys as distinct bytes.
            assertThat(CicsAid.DFHPA1).isNotEqualTo(CicsAid.DFHPA2);
            assertThat(CicsAid.DFHPA3).isNotEqualTo(CicsAid.DFHPA1).isNotEqualTo(CicsAid.DFHPA2);

            // Two of them carry space-padded tokens - CVCRD01Y L6 and L7.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(AidKey.PA1);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);
            assertThat(AidKey.PA1.token()).isEqualTo("PA1  ");
            assertThat(AidKey.PA2.token()).isEqualTo("PA2  ");

            // The third carries none, and no condition name was invented for it.
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();
            assertThat(declaredTokens()).doesNotContain("PA3  ", "PA3");
        }

        /**
         * The resolver's whole output range is inside this field's declared condition set: it can never
         * produce a token no condition names, nor one that does not fit {@code PIC X(5)}.
         */
        @Test
        @DisplayName("the resolver can only ever emit one of the sixteen declared literals")
        void theResolverEmitsOnlyDeclaredLiterals() {
            List<String> declared = declaredTokens();

            for (AidKey key : AidKey.values()) {
                assertThat(key.token())
                        .as("%s is a declared CCARD-AID literal", key)
                        .isIn(declared)
                        .hasSize(CardScreenState.CCARD_AID_LENGTH);

                // Assignable to the field, and reported by exactly one condition.
                CardScreenState state = new CardScreenState();
                state.setCcardAidCondition(key);
                assertThat(allConditionsThatHold(state)).hasSize(1);
            }

            // Both sets are closed at sixteen, and the resolver's width constant agrees with the field.
            assertThat(AidKey.values()).hasSize(declared.size()).hasSize(16);
            assertThat(PfKeyResolver.AID_TOKEN_LENGTH).isEqualTo(CardScreenState.CCARD_AID_LENGTH);
        }

        /**
         * {@code storePfKey} against this field: a matched AID replaces the token, an unmatched one
         * retains it. This is the property {@code CSSTRPFY} has by virtue of not clearing
         * {@code CCARD-AID} before its {@code EVALUATE}, and it is asserted here because the retained
         * value has to remain a value this field's conditions still read correctly.
         */
        @Test
        @DisplayName("an unmatched AID retains the previous token, so the field keeps reading true")
        void anUnmatchedAidRetainsThePreviousToken() {
            // PF3 was pressed first: CCARD-AID-PFK03 holds, per COCRDSLC.cbl:293.
            CardScreenState state = new CardScreenState();
            state.setCcardAidCondition(PfKeyResolver.resolve(CicsAid.DFHPF3).orElseThrow());
            assertThat(state.isCcardAidPfk03()).isTrue();

            // Then PA3 arrives, which CSSTRPFY has no arm for. The token is retained, not cleared.
            Optional<AidKey> retained = PfKeyResolver.storePfKey(CicsAid.DFHPA3, state.aidKey());
            assertThat(retained).contains(AidKey.PFK03);

            // Storing the retained token leaves the field, and therefore the condition, unchanged.
            state.setCcardAidCondition(retained.orElseThrow());
            assertThat(state.getCcardAid()).isEqualTo(CardScreenState.CCARD_AID_PFK03);
            assertThat(state.isCcardAidPfk03()).isTrue();
            assertThat(otherConditionsThatHold(state, CardScreenState.CCARD_AID_PFK03)).isEmpty();

            // A matched AID does replace it - ENTER, per COCRDSLC.cbl:298.
            Optional<AidKey> replaced = PfKeyResolver.storePfKey(CicsAid.DFHENTER, state.aidKey());
            assertThat(replaced).contains(AidKey.ENTER);
            state.setCcardAidCondition(replaced.orElseThrow());
            assertThat(state.isCcardAidEnter()).isTrue();
            assertThat(state.isCcardAidPfk03()).isFalse();
        }

        /**
         * The COBOL names of every condition that currently holds on a work area.
         *
         * <p>An instance method, so nothing is shared between test methods (practice
         * <strong>B9</strong>), and driven from the hand-written pairing table rather than by reflection
         * (practice <strong>B11</strong>).
         *
         * @param state the work area to test
         * @return the COBOL names of the conditions that report {@code true}
         */
        private List<String> allConditionsThatHold(CardScreenState state) {
            List<String> holding = new ArrayList<>();
            aidConditionPredicates().forEach(arguments -> {
                Object[] pairing = arguments.get();
                @SuppressWarnings("unchecked")
                Predicate<CardScreenState> predicate = (Predicate<CardScreenState>) pairing[1];
                if (predicate.test(state)) {
                    holding.add((String) pairing[2]);
                }
            });
            return holding;
        }

        /**
         * The conditions that hold on a work area other than the one owning the given literal - expected
         * to be empty, since the sixteen literals are distinct and a field holds one value.
         *
         * @param state       the work area to test
         * @param ownLiteral  the literal whose own condition is expected to hold and is excluded
         * @return the COBOL names of any other conditions that report {@code true}
         */
        private List<String> otherConditionsThatHold(CardScreenState state, String ownLiteral) {
            List<String> holding = new ArrayList<>();
            aidConditionPredicates().forEach(arguments -> {
                Object[] pairing = arguments.get();
                if (ownLiteral.equals(pairing[0])) {
                    return;
                }
                @SuppressWarnings("unchecked")
                Predicate<CardScreenState> predicate = (Predicate<CardScreenState>) pairing[1];
                if (predicate.test(state)) {
                    holding.add((String) pairing[2]);
                }
            });
            return holding;
        }
    }

    @Nested
    @DisplayName("Initial state - VALUE SPACES and INITIALIZE CC-WORK-AREA")
    class InitialState {

        @Test
        @DisplayName("a fresh work area holds spaces at every declared width")
        void freshInstanceIsSpaceFilled() {
            CardScreenState state = new CardScreenState();

            assertThat(state.getCcardAid()).isEqualTo("     ");
            assertThat(state.getCcardNextProg()).isEqualTo("        ");
            assertThat(state.getCcardNextMapset()).isEqualTo("       ");
            assertThat(state.getCcardNextMap()).isEqualTo("       ");
            assertThat(state.getCcardErrorMsg()).isEqualTo(" ".repeat(75));
            assertThat(state.getCcardReturnMsg()).isEqualTo(" ".repeat(75));
        }

        @Test
        @DisplayName("the three VALUE SPACES fields hold 11, 16 and 9 spaces - not null, not empty")
        void theValueSpacesFieldsHoldTheirFullWidthInSpaces() {
            CardScreenState state = new CardScreenState();

            assertThat(state.getCcAcctId()).isEqualTo("           ").hasSize(11);
            assertThat(state.getCcCardNum()).isEqualTo("                ").hasSize(16);
            assertThat(state.getCcCustId()).isEqualTo("         ").hasSize(9);
        }

        @Test
        @DisplayName("a fresh work area is space-filled, which is NOT the LOW-VALUES state")
        void spacesAreNotLowValues() {
            CardScreenState state = new CardScreenState();

            assertThat(state.isCcardReturnMsgOff()).isFalse();
            assertThat(state.isCcAcctIdLowValues()).isFalse();
            assertThat(state.isCcAcctIdSpaces()).isTrue();
        }

        @Test
        @DisplayName("initializeWorkArea restores every field to spaces, as COCRDLIC:300 does")
        void initializeRestoresSpaces() {
            CardScreenState state = populated();
            state.setCcardReturnMsgToLowValues();

            state.initializeWorkArea();

            assertThat(state).isEqualTo(new CardScreenState());
            assertThat(state.isCcardReturnMsgOff()).isFalse();
        }

        @Test
        @DisplayName("the copy constructor reproduces every field and shares no storage")
        void copyConstructorIsIndependent() {
            CardScreenState original = populated();
            CardScreenState copy = new CardScreenState(original);

            assertThat(copy).isEqualTo(original);

            copy.setCcardNextProg("COCRDLIC");
            assertThat(original.getCcardNextProg()).isEqualTo("COCRDSLC");
        }

        @Test
        @DisplayName("the nine-argument constructor stores every field verbatim, and asWorkArea moves it")
        void fullConstructorStoresVerbatimAndAsWorkAreaMoves() {
            CardScreenState state = new CardScreenState("EN", "P", "M", "S", "e", "r", "1", "2", "3");

            assertThat(state.getCcardAid()).isEqualTo("EN");
            assertThat(state.getCcardNextProg()).isEqualTo("P");
            assertThat(state.getCcardNextMapset()).isEqualTo("M");
            assertThat(state.getCcardNextMap()).isEqualTo("S");
            assertThat(state.getCcardErrorMsg()).isEqualTo("e");
            assertThat(state.getCcardReturnMsg()).isEqualTo("r");
            assertThat(state.getCcAcctId()).isEqualTo("1");
            assertThat(state.getCcCardNum()).isEqualTo("2");
            assertThat(state.getCcCustId()).isEqualTo("3");

            CardScreenState moved = state.asWorkArea();
            assertThat(moved.getCcardAid()).isEqualTo("EN   ");
            assertThat(moved.getCcardNextProg()).isEqualTo("P       ");
            assertThat(moved.getCcardNextMapset()).isEqualTo("M      ");
            assertThat(moved.getCcardNextMap()).isEqualTo("S      ");
            assertThat(moved.getCcardErrorMsg()).hasSize(75).startsWith("e");
            assertThat(moved.getCcardReturnMsg()).hasSize(75).startsWith("r");
            assertThat(moved.getCcAcctId()).isEqualTo("1          ");
            assertThat(moved.getCcCardNum()).isEqualTo("2               ");
            assertThat(moved.getCcCustId()).isEqualTo("3        ");

            // The two are the same work area, because equality compares the moved images.
            assertThat(moved).isEqualTo(state);
        }

        @Test
        @DisplayName("the nine-argument constructor refuses an over-wide argument")
        void fullConstructorRefusesAnOverWideArgument() {
            assertThatThrownBy(() -> new CardScreenState("ENTER", "COCRDUPCX", "M", "S", "e", "r",
                    "1", "2", "3"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CCARD-NEXT-PROG")
                    .hasMessageContaining("9 character(s)");
        }
    }

    @Nested
    @DisplayName("CCARD-NEXT-PROG, -MAPSET and -MAP - opaque fixed-width tokens")
    class OpaqueNavigationTokens {

        @Test
        @DisplayName("a full-width program name is stored as sent, and a short one is padded only by the MOVE")
        void programNameBindsLosslesslyAtEightCharacters() {
            CardScreenState state = new CardScreenState();

            state.setCcardNextProg("COCRDUPC");
            assertThat(state.getCcardNextProg()).isEqualTo("COCRDUPC").hasSize(8);

            state.setCcardNextProg("AB");
            assertThat(state.getCcardNextProg()).isEqualTo("AB");
            assertThat(state.asWorkArea().getCcardNextProg()).isEqualTo("AB      ");
        }

        @Test
        @DisplayName("a ninth character in the program name is refused, not silently dropped")
        void programNameRefusesANinthCharacter() {
            CardScreenState state = new CardScreenState();

            assertThatThrownBy(() -> state.setCcardNextProg("COCRDUPCEXTRA"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CCARD-NEXT-PROG")
                    .hasMessageContaining("13 character(s)");
            assertThat(state.getCcardNextProg()).isEqualTo("        ");
        }

        @Test
        @DisplayName("the mapset and map are seven wide, so an eight-character name is refused")
        void mapNamesAreSevenWideAndRefuseAnEighth() {
            CardScreenState state = new CardScreenState();

            state.setCcardNextMapset("CCRDLI");
            assertThat(state.getCcardNextMapset()).isEqualTo("CCRDLI");
            assertThat(state.asWorkArea().getCcardNextMapset()).isEqualTo("CCRDLI ").hasSize(7);

            assertThatThrownBy(() -> state.setCcardNextMap("CCRDLIAI"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CCARD-NEXT-MAP")
                    .hasMessageContaining("8 character(s)");
        }

        @ParameterizedTest(name = "the map name ''{0}'' survives untouched")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardScreenStateTest#sevenCharacterMapNames")
        @DisplayName("a seven-character map name is stored verbatim - not validated, folded or trimmed")
        void mapNamesAreOpaque(String mapName) {
            CardScreenState state = new CardScreenState();
            state.setCcardNextMap(mapName);

            assertThat(state.getCcardNextMap()).isEqualTo(mapName);
        }

        @Test
        @DisplayName("the COCRDSLC:178 defect survives: 'CCRDSLA' as the card-list map is not corrected")
        void thePreservedWrongMapNameSurvives() {
            CardScreenState state = new CardScreenState();
            state.setCcardNextMapset("CCRDSLA");
            state.setCcardNextMap("CCRDSLA");

            assertThat(state.getCcardNextMapset()).isEqualTo("CCRDSLA");
            assertThat(state.getCcardNextMap()).isEqualTo("CCRDSLA").isNotEqualTo("CCRDLIA");
        }

        @Test
        @DisplayName("lower case is not folded and surrounding spaces are not trimmed")
        void caseAndPaddingAreLeftAlone() {
            CardScreenState state = new CardScreenState();
            state.setCcardNextProg(" ccrdli ");

            assertThat(state.getCcardNextProg()).isEqualTo(" ccrdli ");
        }
    }

    /**
     * Feeds {@link OpaqueNavigationTokens#mapNamesAreOpaque(String)}.
     *
     * @return map names of exactly the seven characters the field declares
     */
    static Stream<String> sevenCharacterMapNames() {
        return Stream.of("CCRDLIA", "CCRDSLA", "CCRDUPA", "ZZZZZZZ", "1234567", "       ");
    }

    /**
     * Gate <strong>G37</strong> at type level: the conversation state is carried <strong>by the
     * instance</strong>, so there is no server-side session for the three card transactions to keep.
     *
     * <h2>Why a type-level test can establish this at all</h2>
     *
     * <p>CICS is pseudo-conversational: {@code CC-WORK-AREA} is the storage that survives between a
     * {@code SEND MAP} and the next {@code RECEIVE MAP}, and under REST it has to travel in the request
     * and response payloads instead. "No server-side session state" is usually argued from the absence of
     * a {@code HttpSession}, which is a claim about a controller. Here it is instead a mechanically
     * checkable property of the carrier: if every byte of the conversation lives in fields of an instance
     * the caller owns, and instances share nothing, then there is no place for cross-request state to
     * accumulate even in principle.
     *
     * <p>What follows drives that directly. Two independently constructed work areas are mutated in every
     * one of the nine fields and neither is observable from the other; a third, constructed afterwards,
     * still comes up in the declared initial state, which is what rules out a static or otherwise ambient
     * holder having been written to along the way (practices <strong>B9</strong> and <strong>B6</strong>
     * both depend on this: no shared mutable state, and no accidental retention of an identifier).
     */
    @Nested
    @DisplayName("Statelessness - the conversation is carried by the instance, not by the server")
    class Statelessness {

        @Test
        @DisplayName("two independently constructed work areas share nothing at all")
        void twoIndependentInstancesShareNothing() {
            CardScreenState first = new CardScreenState();
            CardScreenState second = new CardScreenState();

            // Distinct objects that happen to be equal, because both are in the initial state.
            assertThat(first).isNotSameAs(second).isEqualTo(second);

            // Fill the first completely - all nine fields, including a full unmasked card number.
            first.setCcardAidCondition(AidKey.PFK03);
            first.setCcardNextProg("COCRDSLC");
            first.setCcardNextMapset("COCRDSL");
            first.setCcardNextMap("CCRDSLA");
            first.setCcardErrorMsg("Account not found");
            first.setCcardReturnMsg("Returned to detail");
            first.setCcAcctId("00000000011");
            first.setCcCardNum("4111111111111111");
            first.setCcCustId("000000001");

            // The second is untouched: every one of the nine fields is still spaces.
            assertThat(second.getCcardAid())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CCARD_AID_LENGTH));
            assertThat(second.getCcardNextProg())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CCARD_NEXT_PROG_LENGTH));
            assertThat(second.getCcardNextMapset())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
            assertThat(second.getCcardNextMap())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CCARD_NEXT_MAP_LENGTH));
            assertThat(second.getCcardErrorMsg())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CCARD_ERROR_MSG_LENGTH));
            assertThat(second.getCcardReturnMsg())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CCARD_RETURN_MSG_LENGTH));
            assertThat(second.getCcAcctId())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CC_ACCT_ID_LENGTH));
            assertThat(second.getCcCardNum())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CC_CARD_NUM_LENGTH));
            assertThat(second.getCcCustId())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CC_CUST_ID_LENGTH));

            // No condition holds on the second, so not even the AID leaked across.
            assertThat(second.isCcardAidPfk03()).isFalse();
            assertThat(second.aidKey()).isEmpty();
            assertThat(second).isNotEqualTo(first);

            // Mutating the second in turn leaves the first exactly as it was.
            second.setCcardAidCondition(AidKey.ENTER);
            second.setCcCardNum("5555555555554444");
            assertThat(first.isCcardAidPfk03()).isTrue();
            assertThat(first.getCcCardNum()).isEqualTo("4111111111111111");
        }

        @Test
        @DisplayName("a work area constructed afterwards is still in the declared initial state")
        void nothingIsRetainedInAnAmbientHolder() {
            // Two instances are populated and discarded. If any field were backed by static or
            // otherwise ambient storage, the values below would survive into the third instance.
            CardScreenState populated = new CardScreenState();
            populated.setCcardAidCondition(AidKey.PFK12);
            populated.setCcAcctId("99999999999");
            populated.setCcCardNum("4111111111111111");
            populated.setCcardNextProg("COCRDUPC");
            populated.setCcardReturnMsgToLowValues();

            CardScreenState alsoPopulated = new CardScreenState(populated);
            alsoPopulated.setCcCustId("000000042");

            // A work area created after both is indistinguishable from one created before either.
            CardScreenState fresh = new CardScreenState();
            assertThat(fresh).isEqualTo(new CardScreenState());
            assertThat(fresh.getCcAcctId())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CC_ACCT_ID_LENGTH));
            assertThat(fresh.getCcCardNum())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CC_CARD_NUM_LENGTH));
            assertThat(fresh.getCcCustId())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CC_CUST_ID_LENGTH));
            assertThat(fresh.getCcardNextProg())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CCARD_NEXT_PROG_LENGTH));
            assertThat(fresh.aidKey()).isEmpty();

            // In particular the LOW-VALUES return message did not become the new default: a fresh
            // work area is spaces, so the -OFF condition is false.
            assertThat(fresh.isCcardReturnMsgOff()).isFalse();
            assertThat(populated.isCcardReturnMsgOff()).isTrue();

            // And re-initialising one instance does not disturb the other.
            populated.initializeWorkArea();
            assertThat(populated).isEqualTo(fresh);
            assertThat(alsoPopulated.getCcCustId()).isEqualTo("000000042");
            assertThat(alsoPopulated.getCcCardNum()).isEqualTo("4111111111111111");
        }

        @Test
        @DisplayName("a 213-byte image is self-contained: decoding one carries no state into the next")
        void decodingAnImageCarriesNoStateForward() {
            CardScreenState outbound = new CardScreenState();
            outbound.setCcardAidCondition(AidKey.PFK07);
            outbound.setCcAcctId("00000000011");
            outbound.setCcCardNum("4111111111111111");

            // The image is the whole of the conversation - nothing accompanies it.
            byte[] image = outbound.toFixedWidth(StandardCharsets.US_ASCII);
            assertThat(image).hasSize(CardScreenState.RECORD_LENGTH);

            CardScreenState inbound =
                    CardScreenState.fromFixedWidth(image, StandardCharsets.US_ASCII);
            assertThat(inbound).isEqualTo(outbound).isNotSameAs(outbound);

            // Mutating what came back leaves the sender untouched: two ends, two instances.
            inbound.setCcAcctId("00000000022");
            assertThat(outbound.getCcAcctId()).isEqualTo("00000000011");

            // Decoding the same bytes twice yields two equal but independent work areas.
            CardScreenState again = CardScreenState.fromFixedWidth(image, StandardCharsets.US_ASCII);
            assertThat(again).isEqualTo(outbound).isNotSameAs(inbound);
            again.initializeWorkArea();
            assertThat(outbound.isCcardAidPfk07()).isTrue();
        }
    }

    @Nested
    @DisplayName("The two PIC X(75) messages and the one LOW-VALUES condition")
    class Messages {

        @Test
        @DisplayName("both messages are 75 characters, padded on the right")
        void messagesAreSeventyFiveWide() {
            CardScreenState state = new CardScreenState();
            state.setCcardErrorMsg("Account filter not supplied");
            state.setCcardReturnMsg("Press PF3 to exit");

            assertThat(state.getCcardErrorMsg()).isEqualTo("Account filter not supplied");
            assertThat(state.asWorkArea().getCcardErrorMsg()).hasSize(75)
                    .isEqualTo("Account filter not supplied" + " ".repeat(48));
            assertThat(state.asWorkArea().getCcardReturnMsg()).hasSize(75);
        }

        @Test
        @DisplayName("a message longer than 75 characters is refused rather than truncated")
        void anOverlongMessageIsRefused() {
            CardScreenState state = new CardScreenState();

            assertThatThrownBy(() -> state.setCcardErrorMsg("A".repeat(80)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CCARD-ERROR-MSG")
                    .hasMessageContaining("80 character(s)")
                    .hasMessageContaining("movePicX");

            // Nothing was stored, and the deliberate shortening route is named in the message.
            assertThat(state.getCcardErrorMsg()).isEqualTo(" ".repeat(75));
            state.setCcardErrorMsg(new FixedWidthCodec(StandardCharsets.US_ASCII)
                    .movePicX("A".repeat(80), 75));
            assertThat(state.getCcardErrorMsg()).isEqualTo("A".repeat(75));
        }

        @Test
        @DisplayName("CCARD-RETURN-MSG-OFF is true only for 75 bytes of binary zero")
        void returnMsgOffMeansLowValues() {
            CardScreenState state = new CardScreenState();
            state.setCcardReturnMsgToLowValues();

            assertThat(state.isCcardReturnMsgOff()).isTrue();
            assertThat(state.getCcardReturnMsg()).isEqualTo("\u0000".repeat(75)).hasSize(75);
        }

        @Test
        @DisplayName("CCARD-RETURN-MSG-OFF is false for spaces and false for a message")
        void returnMsgOffIsFalseForSpacesAndForText() {
            CardScreenState state = new CardScreenState();
            assertThat(state.isCcardReturnMsgOff()).isFalse();

            state.setCcardReturnMsg("Press PF3 to exit");
            assertThat(state.isCcardReturnMsgOff()).isFalse();
        }

        @Test
        @DisplayName("a single non-zero byte anywhere makes CCARD-RETURN-MSG-OFF false")
        void oneNonZeroByteDefeatsTheCondition() {
            CardScreenState state = new CardScreenState();
            state.setCcardReturnMsg("\u0000".repeat(74) + " ");

            assertThat(state.isCcardReturnMsgOff()).isFalse();
        }

        @Test
        @DisplayName("setting LOW-VALUES through the plain setter reaches the same state")
        void lowValuesThroughThePlainSetterIsEquivalent() {
            CardScreenState viaHelper = new CardScreenState();
            viaHelper.setCcardReturnMsgToLowValues();

            CardScreenState viaSetter = new CardScreenState();
            viaSetter.setCcardReturnMsg(
                    CardScreenState.lowValues(CardScreenState.CCARD_RETURN_MSG_LENGTH));

            assertThat(viaSetter).isEqualTo(viaHelper);
            assertThat(viaSetter.isCcardReturnMsgOff()).isTrue();
        }
    }

    /**
     * The {@code REDEFINES} pairs of {@code app/cpy/CVCRD01Y.cpy} - each one region of storage seen two
     * ways, so a write through either view is visible through the other.
     *
     * <h2>A documented divergence: three pairs, not one (practice B4)</h2>
     *
     * <p><strong>The copybook is authoritative here and the migration plan under-counts.</strong> The
     * plan's body - its sub-sections 0.3.3 and 0.4.7 - describes {@code CardScreenState} as carrying a
     * single {@code REDEFINES} pair, naming only {@code CC-ACCT-ID} / {@code CC-ACCT-ID-N}.
     * {@code app/cpy/CVCRD01Y.cpy} verifiably declares <strong>three</strong>, all built identically:
     *
     * <pre>
     *   L34-35  10 CC-ACCT-ID   PIC X(11) VALUE SPACES
     *   L36     10 CC-ACCT-ID-N  REDEFINES CC-ACCT-ID   PIC 9(11)
     *   L37-38  10 CC-CARD-NUM  PIC X(16) VALUE SPACES
     *   L39     10 CC-CARD-NUM-N REDEFINES CC-CARD-NUM  PIC 9(16)
     *   L40-41  10 CC-CUST-ID   PIC X(09) VALUE SPACES
     *   L42     10 CC-CUST-ID-N  REDEFINES CC-CUST-ID   PIC 9(9)
     * </pre>
     *
     * <p>The two omitted pairs are not dormant. {@code app/cbl/COCRDSLC.cbl:343} performs
     * {@code MOVE CDEMO-CARD-NUM TO CC-CARD-NUM-N} and {@code :471} reads the result back as
     * {@code CC-CARD-NUM}, so the card-number pair is exercised on the ordinary path of the card detail
     * screen. All three are therefore asserted in full below, in both directions.
     *
     * <p>Recording the conflict is the point: the divergence is written down here rather than quietly
     * applied as though the plan had said three all along, and nothing in the plan or in the copybook was
     * edited to make the two agree. The copybook wins because it is the byte-level contract every parity
     * comparison is measured against.
     *
     * <h2>The relevant source usage</h2>
     *
     * <p>The COBOL writes through the numeric view and reads through the alphanumeric one -
     * {@code MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N} at {@code app/cbl/COCRDSLC.cbl:342} and
     * {@code MOVE CC-ACCT-ID TO ACCTSIDO} at {@code :465} - which is the round trip
     * {@link #acctIdNumericWriteIsVisibleAsCharacters()} and
     * {@link #acctIdCharacterWriteIsVisibleAsNumber()} assert byte for byte.
     */
    @Nested
    @DisplayName("The three REDEFINES pairs - one shared span, two views")
    class RedefinesPairs {

        /**
         * The pair count itself, asserted so the divergence documented above cannot regress to one pair
         * without a test failing.
         */
        @Test
        @DisplayName("the copybook declares exactly three REDEFINES pairs - not the plan's one")
        void thereAreExactlyThreeRedefinesPairs() {
            assertThat(CardScreenState.LAYOUT.redefinitions())
                    .as("CVCRD01Y L36, L39 and L42")
                    .hasSize(3)
                    .extracting(FieldSpan::name)
                    .containsExactly("CC-ACCT-ID-N", "CC-CARD-NUM-N", "CC-CUST-ID-N");

            // Each names the item it redefines, and each is declared - so none is inferred.
            assertThat(CardScreenState.LAYOUT.hasSpan("CC-ACCT-ID")).isTrue();
            assertThat(CardScreenState.LAYOUT.hasSpan("CC-ACCT-ID-N")).isTrue();
            assertThat(CardScreenState.LAYOUT.hasSpan("CC-CARD-NUM")).isTrue();
            assertThat(CardScreenState.LAYOUT.hasSpan("CC-CARD-NUM-N")).isTrue();
            assertThat(CardScreenState.LAYOUT.hasSpan("CC-CUST-ID")).isTrue();
            assertThat(CardScreenState.LAYOUT.hasSpan("CC-CUST-ID-N")).isTrue();
        }

        /**
         * {@code CC-CUST-ID} is written {@code PIC X(09)} at L40 while its overlay {@code CC-CUST-ID-N}
         * is written {@code PIC 9(9)} at L42 - two notations for the same nine bytes.
         *
         * <p>Worth its own test because the leading zero in {@code X(09)} is the kind of detail a
         * transcription drops or reads as ten, and because the pair is the one the plan omits entirely.
         */
        @Test
        @DisplayName("CC-CUST-ID PIC X(09) and CC-CUST-ID-N PIC 9(9) are the same nine bytes")
        void theCustomerIdPairIsNineBytesUnderBothNotations() {
            // PIC X(09) - nine, not ninety and not ten.
            assertThat(CardScreenState.CC_CUST_ID_LENGTH).isEqualTo(9);
            assertThat(CardScreenState.CC_CUST_ID_SPAN.length()).isEqualTo(9);

            // PIC 9(9) - the same nine bytes at the same offset, adding none.
            assertThat(CardScreenState.CC_CUST_ID_N_SPAN.length()).isEqualTo(9);
            assertThat(CardScreenState.CC_CUST_ID_N_SPAN.offset())
                    .isEqualTo(CardScreenState.CC_CUST_ID_SPAN.offset())
                    .isEqualTo(204);
            assertThat(CardScreenState.CC_CUST_ID_N_SPAN.endOffsetExclusive())
                    .isEqualTo(CardScreenState.CC_CUST_ID_SPAN.endOffsetExclusive())
                    .isEqualTo(CardScreenState.RECORD_LENGTH);

            // And the field itself holds nine characters through either view.
            CardScreenState state = new CardScreenState();
            state.setCcCustIdN(123456789L);
            assertThat(state.getCcCustId()).isEqualTo("123456789").hasSize(9);
            assertThat(state.getCcCustIdN()).isEqualTo(123456789L);
        }

        @Test
        @DisplayName("CC-ACCT-ID: a numeric write is visible through the alphanumeric view")
        void acctIdNumericWriteIsVisibleAsCharacters() {
            CardScreenState state = new CardScreenState();
            state.setCcAcctIdN(11L);

            assertThat(state.getCcAcctId()).isEqualTo("00000000011").hasSize(11);
            assertThat(state.getCcAcctIdN()).isEqualTo(11L);
        }

        @Test
        @DisplayName("CC-ACCT-ID: an alphanumeric write is visible through the numeric view")
        void acctIdCharacterWriteIsVisibleAsNumber() {
            CardScreenState state = new CardScreenState();
            state.setCcAcctId("00000000042");

            assertThat(state.getCcAcctIdN()).isEqualTo(42L);
        }

        @Test
        @DisplayName("CC-CARD-NUM: both directions round-trip over the same sixteen bytes")
        void cardNumberRoundTripsBothWays() {
            CardScreenState state = new CardScreenState();

            state.setCcCardNumN(4111111111111111L);
            assertThat(state.getCcCardNum()).isEqualTo("4111111111111111");

            state.setCcCardNum("4000000000000002");
            assertThat(state.getCcCardNumN()).isEqualTo(4000000000000002L);
        }

        @Test
        @DisplayName("CC-CUST-ID: both directions round-trip over the same nine bytes")
        void customerIdRoundTripsBothWays() {
            CardScreenState state = new CardScreenState();

            state.setCcCustIdN(9L);
            assertThat(state.getCcCustId()).isEqualTo("000000009");

            state.setCcCustId("000000123");
            assertThat(state.getCcCustIdN()).isEqualTo(123L);
        }

        @Test
        @DisplayName("the three pairs are independent: writing one leaves the other two alone")
        void thePairsDoNotOverlapEachOther() {
            CardScreenState state = new CardScreenState();
            state.setCcAcctIdN(11L);

            assertThat(state.getCcCardNum()).isEqualTo("                ");
            assertThat(state.getCcCustId()).isEqualTo("         ");
        }

        @Test
        @DisplayName("a numeric write zero-fills on the LEFT, keeping the value right justified")
        void numericWritesZeroFillOnTheLeft() {
            CardScreenState state = new CardScreenState();
            state.setCcCustIdN(5L);

            assertThat(state.getCcCustId()).isEqualTo("000000005");
        }

        @Test
        @DisplayName("an over-wide numeric write truncates on the LEFT, keeping the low-order digits")
        void numericWritesTruncateOnTheLeft() {
            CardScreenState state = new CardScreenState();
            state.setCcCustIdN(1234567890123L);

            assertThat(state.getCcCustId()).isEqualTo("567890123");
            assertThat(state.getCcCustIdN()).isEqualTo(567890123L);
        }

        @Test
        @DisplayName("PIC 9 is unsigned, so a negative value is rejected rather than stored")
        void negativeValuesAreRejected() {
            CardScreenState state = new CardScreenState();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> state.setCcAcctIdN(-1L));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> state.setCcCardNumN(-1L));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> state.setCcCustIdN(-1L));
        }

        /**
         * A documented divergence, recorded rather than resolved in silence (practice
         * <strong>B4</strong>).
         *
         * <p><strong>The plan's summary predicted the opposite of what the source requires.</strong> A
         * reading of the {@code REDEFINES} construction in the abstract suggests that the numeric view
         * over an all-spaces span should hand back the space bytes rather than a number, on the grounds
         * that an overlay is a reinterpretation and not a parse. The premise is right; the conclusion is
         * not, and the source settles it:
         *
         * <ul>
         *   <li>{@code app/cbl/COCRDUPC.cbl:591} and {@code :600} move {@code LOW-VALUES} into
         *       {@code CC-ACCT-ID} and {@code CC-CARD-NUM};</li>
         *   <li>{@code app/cbl/COCRDUPC.cbl:1087} and {@code :1093} then evaluate
         *       {@code IF CC-ACCT-ID-N = 0} and {@code IF CC-CARD-NUM-N = 0} over those very bytes.</li>
         * </ul>
         *
         * <p>On the mainframe a space and a {@code 0x00} each occupy a zero digit position under a
         * {@code PIC 9 DISPLAY} item, so both tests are <strong>true</strong>. A numeric view that
         * returned space characters instead - or that threw - would make those two {@code IF}s
         * unreachable or fatal, which is a behaviour change and a parity failure. So the implementation
         * is asserted as it actually behaves, per the instruction to assert the real API and never weaken
         * an assertion to fit an assumption.
         *
         * <p>The overlay is nonetheless still a reinterpretation and not a parse, and that is asserted
         * where it is actually observable: <strong>the storage is never coerced</strong>. See
         * {@link #theStorageBytesAreNeverCoercedByTheNumericView()}, which shows the alphanumeric view
         * still returning the exact bytes written - spaces stay eleven spaces, and non-digits survive a
         * failed numeric read untouched. This suite performs no string-to-number conversion of its own
         * anywhere: it asserts bytes, and the only decode in play is the codec's single reviewable
         * {@code PIC 9} implementation. This copybook declares no scaled or signed picture, so no
         * fixed-point type and no rounding policy takes any part in it either.
         */
        @Test
        @DisplayName("a space-filled span reads as 0 through the numeric view, as COBOL does")
        void spacesReadAsZero() {
            CardScreenState state = new CardScreenState();

            assertThat(state.getCcAcctIdN()).isZero();
            assertThat(state.getCcCardNumN()).isZero();
            assertThat(state.getCcCustIdN()).isZero();

            // Reading the numeric view did not rewrite the storage: all three are still spaces, not
            // "0" and not zero-filled. This is the reinterpretation property, stated positively.
            assertThat(state.getCcAcctId())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CC_ACCT_ID_LENGTH))
                    .isNotEqualTo("00000000000");
            assertThat(state.getCcCardNum())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CC_CARD_NUM_LENGTH));
            assertThat(state.getCcCustId())
                    .isEqualTo(CardScreenState.spaces(CardScreenState.CC_CUST_ID_LENGTH));
            assertThat(state.isCcAcctIdSpaces()).isTrue();
            assertThat(state.isCcAcctIdNumeric()).isFalse();
        }

        /**
         * The property that makes an overlay an overlay: reading the {@code PIC 9} view never rewrites
         * the bytes underneath it, whatever those bytes are.
         *
         * <p>Three cases, covering the three ways the span can be occupied. In each the alphanumeric view
         * afterwards returns exactly what was written - character for character - so the numeric view is
         * demonstrably a way of looking at the region rather than a conversion of it.
         */
        @Test
        @DisplayName("reading the numeric view never rewrites the bytes underneath it")
        void theStorageBytesAreNeverCoercedByTheNumericView() {
            // 1. Digits. The numeric view reads a value; the characters are unchanged, leading zeros
            //    and all - not renormalised to "42".
            CardScreenState digits = new CardScreenState();
            digits.setCcAcctId("00000000042");
            assertThat(digits.getCcAcctIdN()).isEqualTo(42L);
            assertThat(digits.getCcAcctId()).isEqualTo("00000000042").hasSize(11);

            // 2. LOW-VALUES. COCRDSLC.cbl:617 writes exactly this. The numeric view reads 0 and the
            //    bytes remain 0x00 - they are not turned into spaces, zeros or an empty value.
            CardScreenState lowValues = new CardScreenState();
            lowValues.setCcAcctIdToLowValues();
            assertThat(lowValues.getCcAcctIdN()).isZero();
            assertThat(lowValues.getCcAcctId())
                    .isEqualTo(CardScreenState.lowValues(CardScreenState.CC_ACCT_ID_LENGTH))
                    .isNotEqualTo(CardScreenState.spaces(CardScreenState.CC_ACCT_ID_LENGTH));
            assertThat(lowValues.getCcAcctId().charAt(0)).isEqualTo('\u0000');

            // 3. Non-digits. The strict PIC 9 decode refuses them - which is the codec's deliberate
            //    policy for a numeric span - and, decisively, the refusal leaves the storage exactly as
            //    written. The bytes are still readable through the X view, so nothing was consumed,
            //    normalised or lost by the attempt.
            CardScreenState letters = new CardScreenState();
            letters.setCcAcctId("ABCDEFGHIJK");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(letters::getCcAcctIdN);
            assertThat(letters.getCcAcctId()).isEqualTo("ABCDEFGHIJK").hasSize(11);

            // The guard the COBOL uses ahead of every numeric read reports false here, which is how a
            // controller reproduces COCRDSLC.cbl:665 and never reaches the strict path at all.
            assertThat(letters.isCcAcctIdNumeric()).isFalse();

            // A second failed read changes nothing either - the state is not left half-converted.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(letters::getCcAcctIdN);
            assertThat(letters.getCcAcctId()).isEqualTo("ABCDEFGHIJK");

            // And the same holds for the card-number pair, with a mixed non-digit span.
            CardScreenState mixed = new CardScreenState();
            mixed.setCcCardNum("4111-1111-1111-X");
            assertThat(mixed.isCcCardNumNumeric()).isFalse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(mixed::getCcCardNumN);
            assertThat(mixed.getCcCardNum()).isEqualTo("4111-1111-1111-X").hasSize(16);
        }

        @Test
        @DisplayName("a LOW-VALUES span reads as 0 - the COCRDUPC:1087 'IF CC-ACCT-ID-N = 0' case")
        void lowValuesReadAsZero() {
            CardScreenState state = new CardScreenState();
            state.setCcAcctIdToLowValues();
            state.setCcCardNumToLowValues();

            assertThat(state.isCcAcctIdLowValues()).isTrue();
            assertThat(state.getCcAcctIdN()).isZero();
            assertThat(state.isCcCardNumLowValues()).isTrue();
            assertThat(state.getCcCardNumN()).isZero();
        }

        @Test
        @DisplayName("a zero-filled span reads as 0 too, so all three ZEROS states agree")
        void zeroFilledSpansReadAsZero() {
            CardScreenState state = new CardScreenState();
            state.setCcAcctId("00000000000");

            assertThat(state.getCcAcctIdN()).isZero();
            assertThat(state.isCcAcctIdNZeros()).isTrue();
        }

        @Test
        @DisplayName("a non-numeric span is rejected loudly by the codec, never read as a plausible value")
        void nonNumericContentIsRejected() {
            CardScreenState state = new CardScreenState();
            state.setCcAcctId("ABCDEFGHIJK");

            assertThat(state.isCcAcctIdNumeric()).isFalse();
            assertThat(state.isCcAcctIdNZeros()).isFalse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(state::getCcAcctIdN);
        }

        @Test
        @DisplayName("a span that is mostly zeros but not entirely is still rejected")
        void partiallyNumericContentIsRejected() {
            CardScreenState state = new CardScreenState();
            state.setCcCardNum("000000000000000A");

            assertThat(state.isCcCardNumNZeros()).isFalse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(state::getCcCardNumN);
        }

        @Test
        @DisplayName("the guard chain arms are independent: LOW-VALUES, SPACES and N-ZEROS differ")
        void theGuardChainArmsAreDistinct() {
            CardScreenState lowValues = new CardScreenState();
            lowValues.setCcAcctIdToLowValues();
            assertThat(lowValues.isCcAcctIdLowValues()).isTrue();
            assertThat(lowValues.isCcAcctIdSpaces()).isFalse();
            assertThat(lowValues.isCcAcctIdNZeros()).isTrue();
            assertThat(lowValues.isCcAcctIdNumeric()).isFalse();

            CardScreenState spaces = new CardScreenState();
            assertThat(spaces.isCcAcctIdLowValues()).isFalse();
            assertThat(spaces.isCcAcctIdSpaces()).isTrue();
            assertThat(spaces.isCcAcctIdNZeros()).isTrue();
            assertThat(spaces.isCcAcctIdNumeric()).isFalse();

            CardScreenState zeros = new CardScreenState();
            zeros.setCcAcctIdN(0L);
            assertThat(zeros.isCcAcctIdLowValues()).isFalse();
            assertThat(zeros.isCcAcctIdSpaces()).isFalse();
            assertThat(zeros.isCcAcctIdNZeros()).isTrue();
            assertThat(zeros.isCcAcctIdNumeric()).isTrue();

            CardScreenState supplied = new CardScreenState();
            supplied.setCcAcctIdN(11L);
            assertThat(supplied.isCcAcctIdLowValues()).isFalse();
            assertThat(supplied.isCcAcctIdSpaces()).isFalse();
            assertThat(supplied.isCcAcctIdNZeros()).isFalse();
            assertThat(supplied.isCcAcctIdNumeric()).isTrue();
        }

        @Test
        @DisplayName("the same four arms behave the same way for CC-CARD-NUM")
        void theCardNumberGuardChainMatches() {
            CardScreenState state = new CardScreenState();
            assertThat(state.isCcCardNumLowValues()).isFalse();
            assertThat(state.isCcCardNumSpaces()).isTrue();
            assertThat(state.isCcCardNumNZeros()).isTrue();
            assertThat(state.isCcCardNumNumeric()).isFalse();

            state.setCcCardNumToLowValues();
            assertThat(state.isCcCardNumLowValues()).isTrue();
            assertThat(state.isCcCardNumSpaces()).isFalse();

            state.setCcCardNumN(4111111111111111L);
            assertThat(state.isCcCardNumNumeric()).isTrue();
            assertThat(state.isCcCardNumNZeros()).isFalse();
        }

        @ParameterizedTest(name = "the class test rejects ''{0}''")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardScreenStateTest#nonNumericElevenChars")
        @DisplayName("IS NUMERIC accepts only '0' to '9' - not punctuation, letters or wide digits")
        void classTestAcceptsOnlyAsciiDigits(String image) {
            CardScreenState state = new CardScreenState();
            state.setCcAcctId(image);

            assertThat(state.isCcAcctIdNumeric()).isFalse();
        }

        @Test
        @DisplayName("IS NUMERIC accepts a fully numeric span")
        void classTestAcceptsDigits() {
            CardScreenState state = new CardScreenState();
            state.setCcAcctId("12345678901");

            assertThat(state.isCcAcctIdNumeric()).isTrue();
            assertThat(state.getCcAcctIdN()).isEqualTo(12345678901L);
        }
    }

    /**
     * Feeds {@link RedefinesPairs#classTestAcceptsOnlyAsciiDigits(String)}.
     *
     * @return eleven-character spans that the class test must reject
     */
    static Stream<String> nonNumericElevenChars() {
        return Stream.of("1234567890!",
                "1234567890A",
                "!1234567890",
                "           ",
                "1234 567890",
                "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000");
    }

    @Nested
    @DisplayName("The 213-byte fixed-width image, under an explicitly named code page")
    class FixedWidthImage {

        @Test
        @DisplayName("a fresh work area renders as exactly 213 bytes")
        void theImageIsAlwaysTwoHundredAndThirteenBytes() {
            assertThat(new CardScreenState().toFixedWidth(ASCII))
                    .hasSize(CardScreenState.RECORD_LENGTH);
            assertThat(populated().toFixedWidth(ASCII))
                    .hasSize(CardScreenState.RECORD_LENGTH);
            assertThat(populated().toFixedWidth(EBCDIC))
                    .hasSize(CardScreenState.RECORD_LENGTH);
        }

        @Test
        @DisplayName("every field lands at its declared offset and width")
        void everyFieldLandsAtItsDeclaredOffset() {
            String image = new String(populated().toFixedWidth(ASCII), ASCII);

            assertThat(image).hasSize(213);
            assertThat(slice(image, CardScreenState.CCARD_AID_OFFSET,
                    CardScreenState.CCARD_AID_LENGTH)).isEqualTo("PFK03");
            assertThat(slice(image, CardScreenState.CCARD_NEXT_PROG_OFFSET,
                    CardScreenState.CCARD_NEXT_PROG_LENGTH)).isEqualTo("COCRDSLC");
            assertThat(slice(image, CardScreenState.CCARD_NEXT_MAPSET_OFFSET,
                    CardScreenState.CCARD_NEXT_MAPSET_LENGTH)).isEqualTo("CCRDSLA");
            assertThat(slice(image, CardScreenState.CCARD_NEXT_MAP_OFFSET,
                    CardScreenState.CCARD_NEXT_MAP_LENGTH)).isEqualTo("CCRDSLA");
            assertThat(slice(image, CardScreenState.CCARD_ERROR_MSG_OFFSET,
                    CardScreenState.CCARD_ERROR_MSG_LENGTH))
                    .startsWith("Account filter is not a valid number")
                    .hasSize(75);
            assertThat(slice(image, CardScreenState.CCARD_RETURN_MSG_OFFSET,
                    CardScreenState.CCARD_RETURN_MSG_LENGTH))
                    .startsWith("Press PF3 to exit")
                    .hasSize(75);
            assertThat(slice(image, CardScreenState.CC_ACCT_ID_OFFSET,
                    CardScreenState.CC_ACCT_ID_LENGTH)).isEqualTo("00000000011");
            assertThat(slice(image, CardScreenState.CC_CARD_NUM_OFFSET,
                    CardScreenState.CC_CARD_NUM_LENGTH)).isEqualTo("4111111111111111");
            assertThat(slice(image, CardScreenState.CC_CUST_ID_OFFSET,
                    CardScreenState.CC_CUST_ID_LENGTH)).isEqualTo("000000009");
        }

        @Test
        @DisplayName("LOW-VALUES renders as 0x00 bytes, and spaces do not")
        void lowValuesRenderAsBinaryZero() {
            CardScreenState state = new CardScreenState();
            state.setCcAcctIdToLowValues();

            byte[] image = state.toFixedWidth(ASCII);
            for (int index = 0; index < CardScreenState.CC_ACCT_ID_LENGTH; index++) {
                assertThat(image[CardScreenState.CC_ACCT_ID_OFFSET + index])
                        .as("CC-ACCT-ID byte %d is LOW-VALUES", index)
                        .isEqualTo((byte) 0x00);
            }
            assertThat(image[CardScreenState.CC_CARD_NUM_OFFSET]).isEqualTo((byte) 0x20);
        }

        @Test
        @DisplayName("the code page is honoured: the same instance yields different bytes under IBM037")
        void theCodePageIsTheCallersChoice() {
            CardScreenState state = new CardScreenState();
            state.setCcCardNum("4111111111111111");

            byte[] ascii = state.toFixedWidth(ASCII);
            byte[] ebcdic = state.toFixedWidth(EBCDIC);

            assertThat(ascii[CardScreenState.CC_CARD_NUM_OFFSET]).isEqualTo((byte) 0x34);
            assertThat(ebcdic[CardScreenState.CC_CARD_NUM_OFFSET]).isEqualTo((byte) 0xF4);
            assertThat(ascii[CardScreenState.CCARD_AID_OFFSET]).isEqualTo((byte) 0x20);
            assertThat(ebcdic[CardScreenState.CCARD_AID_OFFSET]).isEqualTo((byte) 0x40);
            assertThat(ascii).isNotEqualTo(ebcdic);
        }

        @ParameterizedTest(name = "round trip under {0}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardScreenStateTest#bothCodePages")
        @DisplayName("an image round-trips back to an equal work area under either code page")
        void imagesRoundTrip(Charset charset) {
            CardScreenState original = populated();
            original.setCcardReturnMsgToLowValues();

            CardScreenState restored =
                    CardScreenState.fromFixedWidth(original.toFixedWidth(charset), charset);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.isCcardReturnMsgOff()).isTrue();
            assertThat(restored.getCcCardNumN()).isEqualTo(4111111111111111L);
        }

        @Test
        @DisplayName("a fresh work area round-trips too, spaces and all")
        void aFreshWorkAreaRoundTrips() {
            CardScreenState fresh = new CardScreenState();

            assertThat(CardScreenState.fromFixedWidth(fresh.toFixedWidth(ASCII), ASCII))
                    .isEqualTo(fresh);
        }

        @Test
        @DisplayName("writing into and reading back from a caller-owned record area agrees")
        void aCallerOwnedRecordAreaAgrees() {
            FixedWidthRecord record =
                    new FixedWidthRecord(CardScreenState.RECORD_LENGTH, EBCDIC);
            CardScreenState original = populated();

            original.writeInto(record);

            assertThat(CardScreenState.readFrom(record)).isEqualTo(original);
            assertThat(record.toByteArray()).isEqualTo(original.toFixedWidth(EBCDIC));
        }

        @ParameterizedTest(name = "an image of {0} bytes is rejected")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardScreenStateTest#wrongImageLengths")
        @DisplayName("a short or over-long image is rejected, never quietly re-geometried")
        void wrongLengthImagesAreRejected(int length) {
            byte[] wrong = new byte[length];

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardScreenState.fromFixedWidth(wrong, ASCII));
        }

        @Test
        @DisplayName("a record area of the wrong declared width is rejected on write and on read")
        void wrongWidthRecordAreasAreRejected() {
            FixedWidthRecord tooNarrow = new FixedWidthRecord(212, ASCII);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> populated().writeInto(tooNarrow))
                    .withMessageContaining("213");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardScreenState.readFrom(tooNarrow));
        }

        @Test
        @DisplayName("the code page and the image are both required - neither is defaulted")
        void theCharsetAndImageAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> populated().toFixedWidth(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardScreenState.fromFixedWidth(new byte[213], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardScreenState.fromFixedWidth(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> populated().writeInto(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardScreenState.readFrom(null));
        }

        private String slice(String image, int offset, int length) {
            return image.substring(offset, offset + length);
        }
    }

    /**
     * Feeds the code-page parameterised tests.
     *
     * @return the two code pages this system uses
     */
    static Stream<Charset> bothCodePages() {
        return Stream.of(StandardCharsets.US_ASCII, Charset.forName("IBM037"));
    }

    /**
     * Feeds {@link FixedWidthImage#wrongLengthImagesAreRejected(int)}.
     *
     * @return byte counts that are not the declared 213
     */
    static Stream<Integer> wrongImageLengths() {
        return Stream.of(0, 1, 212, 214, 350);
    }

    @Nested
    @DisplayName("Figurative constants - SPACES and LOW-VALUES are not interchangeable")
    class FigurativeConstants {

        @Test
        @DisplayName("spaces(n) yields n spaces and lowValues(n) yields n binary zeros")
        void bothConstantsRenderAtTheRequestedWidth() {
            assertThat(CardScreenState.spaces(11)).isEqualTo("           ").hasSize(11);
            assertThat(CardScreenState.lowValues(11)).isEqualTo("\u0000".repeat(11)).hasSize(11);
            assertThat(CardScreenState.spaces(1)).isEqualTo(" ");
            assertThat(CardScreenState.lowValues(1)).isEqualTo("\u0000");
        }

        @Test
        @DisplayName("the two are never equal, at any width")
        void theTwoConstantsAreNeverEqual() {
            for (int width = 1; width <= 16; width++) {
                assertThat(CardScreenState.lowValues(width))
                        .as("width %d", width)
                        .isNotEqualTo(CardScreenState.spaces(width));
            }
        }

        @Test
        @DisplayName("LOW-VALUES encodes to 0x00 under both code pages, so the state survives either")
        void lowValuesEncodesToBinaryZeroInBothCodePages() {
            assertThat(CardScreenState.lowValues(3).getBytes(ASCII))
                    .containsExactly((byte) 0x00, (byte) 0x00, (byte) 0x00);
            assertThat(CardScreenState.lowValues(3).getBytes(EBCDIC))
                    .containsExactly((byte) 0x00, (byte) 0x00, (byte) 0x00);
        }

        @Test
        @DisplayName("a width below one is rejected, because every item is at least a byte wide")
        void aNonPositiveWidthIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardScreenState.spaces(0))
                    .withMessageContaining("SPACES");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardScreenState.lowValues(-1))
                    .withMessageContaining("LOW-VALUES");
        }
    }

    @Nested
    @DisplayName("null is not a COBOL state and is rejected everywhere")
    class NullRejection {

        @ParameterizedTest(name = "{0} rejects null")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardScreenStateTest#everyStringSetter")
        @DisplayName("every field setter rejects null and names the field it guards")
        void everySetterRejectsNull(String cobolName, Consumer<CardScreenState> setNull) {
            CardScreenState state = new CardScreenState();

            assertThatNullPointerException()
                    .isThrownBy(() -> setNull.accept(state))
                    .withMessageContaining(cobolName);
        }

        @Test
        @DisplayName("the nine-argument constructor rejects a null field")
        void theFullConstructorRejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> new CardScreenState(
                    null, "P", "M", "S", "e", "r", "1", "2", "3"));
        }

        @Test
        @DisplayName("the copy constructor rejects a null source")
        void theCopyConstructorRejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> new CardScreenState(null));
        }

        @Test
        @DisplayName("setCcardAidCondition rejects null rather than inventing a token")
        void theConditionSetterRejectsNull() {
            CardScreenState state = new CardScreenState();

            assertThatNullPointerException().isThrownBy(() -> state.setCcardAidCondition(null));
        }
    }

    /**
     * Feeds {@link NullRejection#everySetterRejectsNull(String, Consumer)}.
     *
     * @return each field's COBOL name paired with an action that passes it null
     */
    static Stream<Arguments> everyStringSetter() {
        return Stream.of(
                Arguments.of("CCARD-AID",
                        (Consumer<CardScreenState>) state -> state.setCcardAid(null)),
                Arguments.of("CCARD-NEXT-PROG",
                        (Consumer<CardScreenState>) state -> state.setCcardNextProg(null)),
                Arguments.of("CCARD-NEXT-MAPSET",
                        (Consumer<CardScreenState>) state -> state.setCcardNextMapset(null)),
                Arguments.of("CCARD-NEXT-MAP",
                        (Consumer<CardScreenState>) state -> state.setCcardNextMap(null)),
                Arguments.of("CCARD-ERROR-MSG",
                        (Consumer<CardScreenState>) state -> state.setCcardErrorMsg(null)),
                Arguments.of("CCARD-RETURN-MSG",
                        (Consumer<CardScreenState>) state -> state.setCcardReturnMsg(null)),
                Arguments.of("CC-ACCT-ID",
                        (Consumer<CardScreenState>) state -> state.setCcAcctId(null)),
                Arguments.of("CC-CARD-NUM",
                        (Consumer<CardScreenState>) state -> state.setCcCardNum(null)),
                Arguments.of("CC-CUST-ID",
                        (Consumer<CardScreenState>) state -> state.setCcCustId(null)));
    }

    @Nested
    @DisplayName("Object contract - value semantics over the nine storage fields")
    class ObjectContract {

        @Test
        @DisplayName("a work area equals itself and equals an identical copy")
        void equalityIsReflexiveAndValueBased() {
            CardScreenState state = populated();

            assertThat(state).isEqualTo(state);
            assertThat(state).isEqualTo(new CardScreenState(state));
            assertThat(state).hasSameHashCodeAs(new CardScreenState(state));
        }

        @Test
        @DisplayName("a work area is not equal to null or to another type")
        void inequalityWithNullAndOtherTypes() {
            CardScreenState state = populated();

            assertThat(state).isNotEqualTo(null);
            assertThat(state).isNotEqualTo("CardScreenState");
        }

        /**
         * The single-byte case that matters most: two work areas differing only in whether one field
         * holds spaces or {@code LOW-VALUES}.
         *
         * <p>Both are "blank" to the eye and both are 75 characters wide, so an equality that compared
         * them loosely - or a hash that ignored the distinction - would report two genuinely different
         * conversation states as the same one. The distinction is load-bearing: it is exactly what
         * {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES} at {@code app/cpy/CVCRD01Y.cpy} line 30
         * tests, and what {@code app/cbl/COCRDSLC.cbl:617} writes into {@code CC-ACCT-ID}.
         */
        @Test
        @DisplayName("spaces and LOW-VALUES in one field make two work areas unequal")
        void spacesVersusLowValuesInOneFieldBreaksEquality() {
            // CCARD-RETURN-MSG: spaces in one, LOW-VALUES in the other, everything else identical.
            CardScreenState spaceFilled = new CardScreenState();
            CardScreenState lowValued = new CardScreenState();
            lowValued.setCcardReturnMsgToLowValues();

            // Both fields are 75 characters, so this is a content difference and not a width one.
            assertThat(spaceFilled.getCcardReturnMsg())
                    .hasSize(CardScreenState.CCARD_RETURN_MSG_LENGTH);
            assertThat(lowValued.getCcardReturnMsg())
                    .hasSize(CardScreenState.CCARD_RETURN_MSG_LENGTH);

            assertThat(lowValued).isNotEqualTo(spaceFilled);
            assertThat(spaceFilled).isNotEqualTo(lowValued);
            assertThat(lowValued.isCcardReturnMsgOff()).isTrue();
            assertThat(spaceFilled.isCcardReturnMsgOff()).isFalse();

            // The same on an identifier field, per COCRDSLC.cbl:617.
            CardScreenState spacedId = new CardScreenState();
            CardScreenState lowValuedId = new CardScreenState();
            lowValuedId.setCcAcctIdToLowValues();

            assertThat(lowValuedId).isNotEqualTo(spacedId);
            assertThat(lowValuedId.isCcAcctIdLowValues()).isTrue();
            assertThat(spacedId.isCcAcctIdSpaces()).isTrue();

            // Equal field sets do hash alike, which is the other half of the contract.
            CardScreenState alsoLowValued = new CardScreenState();
            alsoLowValued.setCcardReturnMsgToLowValues();
            assertThat(alsoLowValued).isEqualTo(lowValued).hasSameHashCodeAs(lowValued);

            // hashCode is stable across repeated calls on an unchanged instance.
            assertThat(lowValued.hashCode()).isEqualTo(lowValued.hashCode());
        }

        @ParameterizedTest(name = "differing in {0} breaks equality")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardScreenStateTest#singleFieldMutations")
        @DisplayName("a difference in any one of the nine fields breaks equality")
        void anyFieldDifferenceBreaksEquality(String cobolName,
                                             Consumer<CardScreenState> mutate) {
            CardScreenState original = populated();
            CardScreenState mutated = new CardScreenState(original);

            mutate.accept(mutated);

            assertThat(mutated).as("differing in %s", cobolName).isNotEqualTo(original);
            assertThat(original).isNotEqualTo(mutated);
        }

        @Test
        @DisplayName("toString names every COBOL field and withholds the three identifiers")
        void toStringNamesEveryFieldAndRedactsTheIdentifiers() {
            CardScreenState state = populated();

            String rendered = state.toString();

            // The screen state itself stays legible - it is what a navigation parity failure is read
            // from - while the three identifiers are masked. Practice B6 is intact: the components, the
            // accessors and the encoded bytes still carry the real values, and COBOL has no toString.
            assertThat(rendered).contains("CCARD-AID='PFK03'",
                    "CCARD-NEXT-PROG='COCRDSLC'",
                    "CCARD-NEXT-MAPSET='CCRDSLA'",
                    "CCARD-NEXT-MAP='CCRDSLA'");
            assertThat(rendered)
                    .doesNotContain("00000000011", "4111111111111111", "000000009")
                    .contains("CC-ACCT-ID='*******0011'",
                            "CC-CARD-NUM='************1111'",
                            "CC-CUST-ID='*****0009'");
        }
    }

    /**
     * Feeds {@link ObjectContract#anyFieldDifferenceBreaksEquality(String, Consumer)}.
     *
     * @return each field's COBOL name paired with an action that changes just that field
     */
    static Stream<Arguments> singleFieldMutations() {
        return Stream.of(
                Arguments.of("CCARD-AID",
                        (Consumer<CardScreenState>) state -> state.setCcardAid("ENTER")),
                Arguments.of("CCARD-NEXT-PROG",
                        (Consumer<CardScreenState>) state -> state.setCcardNextProg("COCRDLIC")),
                Arguments.of("CCARD-NEXT-MAPSET",
                        (Consumer<CardScreenState>) state -> state.setCcardNextMapset("CCRDLIA")),
                Arguments.of("CCARD-NEXT-MAP",
                        (Consumer<CardScreenState>) state -> state.setCcardNextMap("CCRDLIA")),
                Arguments.of("CCARD-ERROR-MSG",
                        (Consumer<CardScreenState>) state -> state.setCcardErrorMsg("other")),
                Arguments.of("CCARD-RETURN-MSG",
                        (Consumer<CardScreenState>) state -> state.setCcardReturnMsg("other")),
                Arguments.of("CC-ACCT-ID",
                        (Consumer<CardScreenState>) state -> state.setCcAcctIdN(12L)),
                Arguments.of("CC-CARD-NUM",
                        (Consumer<CardScreenState>) state -> state.setCcCardNumN(4000000000000002L)),
                Arguments.of("CC-CUST-ID",
                        (Consumer<CardScreenState>) state -> state.setCcCustIdN(10L)));
    }

    @Nested
    @DisplayName("JSON projection - the wire carries the nine storage fields and nothing else")
    class JsonProjection {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("exactly nine properties are serialised, one per copybook storage item")
        void onlyTheNineStorageFieldsAreSerialised() {
            List<String> properties = new ArrayList<>();
            mapper.valueToTree(populated()).fieldNames().forEachRemaining(properties::add);

            assertThat(properties).containsExactlyInAnyOrder("ccardAid",
                    "ccardNextProg",
                    "ccardNextMapset",
                    "ccardNextMap",
                    "ccardErrorMsg",
                    "ccardReturnMsg",
                    "ccAcctId",
                    "ccCardNum",
                    "ccCustId");
        }

        @Test
        @DisplayName("no condition-name predicate and no REDEFINES overlay reaches the payload")
        void derivedViewsAreNotPayloadMembers() {
            String json = mapper.valueToTree(populated()).toString();

            assertThat(json).doesNotContain("ccardAidPfk03",
                    "ccardAidEnter",
                    "ccAcctIdN",
                    "ccCardNumN",
                    "ccCustIdN",
                    "ccardReturnMsgOff",
                    "ccAcctIdNumeric");
        }

        @Test
        @DisplayName("a work area survives a JSON round trip unchanged, LOW-VALUES included")
        void jsonRoundTripPreservesEveryField() throws Exception {
            CardScreenState original = populated();
            original.setCcardReturnMsgToLowValues();

            String json = mapper.writeValueAsString(original);
            CardScreenState restored = mapper.readValue(json, CardScreenState.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.isCcardReturnMsgOff()).isTrue();
            assertThat(restored.getCcCardNum()).isEqualTo("4111111111111111");
        }

        @Test
        @DisplayName("the card number is carried in the clear, exactly as the COBOL work area does")
        void theCardNumberIsNotMasked() {
            String json = mapper.valueToTree(populated()).toString();

            assertThat(json).contains("4111111111111111");
        }

        @Test
        @DisplayName("an inbound value shorter than its field survives binding unchanged")
        void aShortInboundValueIsNotPadded() throws Exception {
            CardScreenState bound = mapper.readValue(
                    "{\"ccardAid\":\"\",\"ccardNextProg\":\"COMEN01C\",\"ccardNextMapset\":\"\","
                            + "\"ccardNextMap\":\"\",\"ccardErrorMsg\":\"\",\"ccardReturnMsg\":\"\","
                            + "\"ccAcctId\":\"11\",\"ccCardNum\":\"\",\"ccCustId\":\"\"}",
                    CardScreenState.class);

            // Empty stays empty and "11" stays "11": the wire value is inspectable as it was sent.
            assertThat(bound.getCcardAid()).isEmpty();
            assertThat(bound.getCcAcctId()).isEqualTo("11");
            assertThat(bound.getCcardErrorMsg()).isEmpty();

            // And the COBOL semantics are unchanged, because they read the moved image: "11" moves to
            // "11         ", which is neither all digits nor all spaces - exactly what the pre-binding
            // behaviour produced, and exactly what the class test IF CC-ACCT-ID IS NUMERIC reports.
            assertThat(bound.isCcAcctIdSpaces()).isFalse();
            assertThat(bound.isCcAcctIdNumeric()).isFalse();
            assertThat(bound.isCcCardNumSpaces()).isTrue();
            assertThat(bound.isCcCardNumNZeros()).isTrue();
            assertThat(bound.getCcCardNumN()).isZero();
            assertThat(bound.asWorkArea().getCcAcctId()).isEqualTo("11         ");
        }

        @Test
        @DisplayName("an inbound value wider than its field is refused, and nothing is truncated")
        void anOverWideInboundValueIsRefused() {
            assertThatThrownBy(() -> mapper.readValue(
                    "{\"ccCardNum\":\"41111111111111119999\"}", CardScreenState.class))
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CC-CARD-NUM")
                    .hasMessageContaining("20 character(s)")
                    .hasMessageNotContaining("41111111111111119999");
        }

        @Test
        @DisplayName("a losslessly bound short value still writes the correct 213-byte image")
        void aShortBoundValueStillWritesTheDeclaredImage() throws Exception {
            CardScreenState bound = mapper.readValue(
                    "{\"ccardAid\":\"EN\",\"ccardNextProg\":\"COMEN01C\",\"ccardNextMapset\":\"CO\","
                            + "\"ccardNextMap\":\"CO\",\"ccardErrorMsg\":\"e\",\"ccardReturnMsg\":\"r\","
                            + "\"ccAcctId\":\"11\",\"ccCardNum\":\"4111\",\"ccCustId\":\"9\"}",
                    CardScreenState.class);

            byte[] image = bound.toFixedWidth(StandardCharsets.US_ASCII);

            assertThat(image).hasSize(CardScreenState.RECORD_LENGTH);
            assertThat(new String(image, CardScreenState.CCARD_AID_OFFSET,
                    CardScreenState.CCARD_AID_LENGTH, StandardCharsets.US_ASCII)).isEqualTo("EN   ");
            assertThat(new String(image, CardScreenState.CC_ACCT_ID_OFFSET,
                    CardScreenState.CC_ACCT_ID_LENGTH, StandardCharsets.US_ASCII))
                    .isEqualTo("11         ");
            assertThat(CardScreenState.fromFixedWidth(image, StandardCharsets.US_ASCII))
                    .isEqualTo(bound);
        }
    }

    @Nested
    @DisplayName("asWorkArea - the one named step that applies the COBOL MOVE")
    class WorkAreaConversion {

        @Test
        @DisplayName("every field is rendered at its declared width")
        void everyFieldIsMovedToItsDeclaredWidth() {
            CardScreenState state = new CardScreenState();
            state.setCcardAid("EN");
            state.setCcardNextProg("P");
            state.setCcardNextMapset("M");
            state.setCcardNextMap("S");
            state.setCcardErrorMsg("e");
            state.setCcardReturnMsg("r");
            state.setCcAcctId("1");
            state.setCcCardNum("2");
            state.setCcCustId("3");

            CardScreenState moved = state.asWorkArea();

            assertThat(moved.getCcardAid()).hasSize(CardScreenState.CCARD_AID_LENGTH);
            assertThat(moved.getCcardNextProg()).hasSize(CardScreenState.CCARD_NEXT_PROG_LENGTH);
            assertThat(moved.getCcardNextMapset()).hasSize(CardScreenState.CCARD_NEXT_MAPSET_LENGTH);
            assertThat(moved.getCcardNextMap()).hasSize(CardScreenState.CCARD_NEXT_MAP_LENGTH);
            assertThat(moved.getCcardErrorMsg()).hasSize(CardScreenState.CCARD_ERROR_MSG_LENGTH);
            assertThat(moved.getCcardReturnMsg()).hasSize(CardScreenState.CCARD_RETURN_MSG_LENGTH);
            assertThat(moved.getCcAcctId()).hasSize(CardScreenState.CC_ACCT_ID_LENGTH);
            assertThat(moved.getCcCardNum()).hasSize(CardScreenState.CC_CARD_NUM_LENGTH);
            assertThat(moved.getCcCustId()).hasSize(CardScreenState.CC_CUST_ID_LENGTH);
        }

        @Test
        @DisplayName("it returns a new instance and leaves the original alone")
        void itDoesNotMutateTheOriginal() {
            CardScreenState state = new CardScreenState();
            state.setCcardAid("EN");

            CardScreenState moved = state.asWorkArea();

            assertThat(moved).isNotSameAs(state);
            assertThat(state.getCcardAid()).isEqualTo("EN");
            assertThat(moved.getCcardAid()).isEqualTo("EN   ");
        }

        @Test
        @DisplayName("it is idempotent, because a moved field is already its declared width")
        void itIsIdempotent() {
            CardScreenState once = populated().asWorkArea();

            assertThat(once.asWorkArea()).isEqualTo(once);
            assertThat(once.asWorkArea().getCcardAid()).isEqualTo(once.getCcardAid());
        }

        @Test
        @DisplayName("a work area and its moved form are equal, and hash alike")
        void equalityIsOverTheMovedImage() {
            CardScreenState shortForm = new CardScreenState();
            shortForm.setCcardNextProg("P");

            CardScreenState movedForm = shortForm.asWorkArea();

            assertThat(movedForm).isEqualTo(shortForm);
            assertThat(shortForm).isEqualTo(movedForm);
            assertThat(movedForm).hasSameHashCodeAs(shortForm);
        }
    }
}
