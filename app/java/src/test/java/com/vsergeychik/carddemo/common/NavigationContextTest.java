package com.vsergeychik.carddemo.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link NavigationContext}, the Java carrier for {@code 01 CARDDEMO-COMMAREA} of
 * {@code app/cpy/COCOM01Y.cpy} - the CICS pseudo-conversational state that all
 * <strong>seventeen</strong> online programs copy.
 *
 * <p>This class exists so that no server-side session does, which makes this suite the place that
 * statelessness is <em>proven</em> rather than assumed from the design. Three concerns carry the
 * weight here, and each has its own group below.
 *
 * <ol>
 *   <li><strong>The {@code X(7)} width trap.</strong> {@code CDEMO-LAST-MAP} and
 *       {@code CDEMO-LAST-MAPSET} are declared {@code PIC X(7)} at
 *       {@code app/cpy/COCOM01Y.cpy:43-44}, in a copybook where every other name field -
 *       {@code CDEMO-FROM-PROGRAM} (L22), {@code CDEMO-TO-PROGRAM} (L24) and
 *       {@code CDEMO-USER-ID} (L25) - is {@code PIC X(08)}. Eight is therefore the natural wrong
 *       guess, and it costs exactly two bytes on a 160-byte record.</li>
 *   <li><strong>Field-order blindness.</strong> A round trip asserted only on the byte array
 *       silently passes a swap between two same-width fields. Every round-trip assertion below is
 *       therefore per field, and every same-width pair is deliberately given
 *       <em>different</em> values so a swap cannot hide.</li>
 *   <li><strong>The four {@code 88}-level conditions.</strong> Each is driven true and false, and
 *       the reachable "neither" state of each pair is asserted rather than coerced into a
 *       default.</li>
 * </ol>
 *
 * <h2>Governing rules</h2>
 *
 * {@code review_rules} reports <strong>no user rules provided</strong> for this project - confirmed
 * directly for this file, twice - so no project rule governs it and none has been invented. The
 * absence is explicitly not treated as licence to lower the bar; the enterprise best-practice
 * substitutes recorded in the Agent Action Plan bind instead. The ones that shape this file are:
 *
 * <ul>
 *   <li><strong>B1 / B2</strong> - nothing outside the stack the build already pins. Only JUnit
 *       Jupiter, AssertJ and Jackson, all three arriving through {@code spring-boot-starter-test};
 *       no new dependency and no new version.</li>
 *   <li><strong>B3</strong> - the reference inputs are immutable. The layout asserted below is
 *       <em>transcribed</em> from {@code app/cpy/COCOM01Y.cpy} into the expectations and the
 *       comments; this suite never reads a file under {@code app/} at runtime and never writes one.
 *       Only {@code CSLKPCDY.cpy} is placed on the test classpath by the build, and this copybook is
 *       deliberately not.</li>
 *   <li><strong>B8</strong> - explicit over implicit. No wildcard import anywhere, including no
 *       {@code import static ...Assertions.*} (gate G52); every AssertJ entry point is named. The
 *       {@link Charset} handed to every codec is named explicitly and is never the platform
 *       default.</li>
 *   <li><strong>B9</strong> - no static mutable state (gate G53), which this suite additionally
 *       <em>asserts</em> of the class under test: COBOL {@code WORKING-STORAGE} must never become a
 *       static Java field, because that breaks request isolation.</li>
 *   <li><strong>B10</strong> - every test is enabled and asserts something. No {@code @Disabled},
 *       no empty body, no deferred work.</li>
 *   <li><strong>B12</strong> - the legacy COBOL cannot be executed in this environment (risk R-A),
 *       so every expectation here is <em>statically derived</em> from the copybook and is cited to
 *       the line it came from.</li>
 * </ul>
 *
 * <h2>Gates this file owns</h2>
 *
 * <ul>
 *   <li><strong>G37</strong> - no server-side session state exists; all conversation state travels
 *       in the request and response payloads. Asserted in {@link Statelessness}.</li>
 *   <li><strong>G38</strong> - the {@code ENTER} versus {@code REENTER} distinction that gates the
 *       error highlight. This file owns the <em>upstream</em> half, the predicate;
 *       {@code FieldAttributeSetterTest} owns the downstream half, the highlight itself.</li>
 *   <li><strong>G40</strong> - {@code XCTL} sites resolving to a next-program response field, and
 *       {@code COSGN00C}'s role routing. Asserted in {@link UserTypeConditions} and
 *       {@link Statelessness}.</li>
 *   <li><strong>G50</strong> - the four {@code 88}-level condition names driven in both their true
 *       and false states.</li>
 * </ul>
 *
 * Package-wide it also serves <strong>G49</strong> (branch coverage at or above 0.90 for
 * {@code com.vsergeychik.carddemo.common} on its own), <strong>G52</strong>, <strong>G53</strong>
 * and <strong>G54</strong> (the suite runs non-interactively).
 *
 * <h2>What this file deliberately does not cover</h2>
 *
 * {@code NavigationContextWireContractTest} already owns the {@code CDEMO-CARD-NUM} JSON string
 * form, the {@code cardNumberImage} / {@code cardNumberOfImage} helpers and the redaction performed
 * by {@code toString}. Those are not repeated here. This file covers the declared geometry, the
 * {@code X(7)} trap, the four conditions, the two full sixteen-field round trips, statelessness and
 * the construction guards.
 *
 * <h2>The transcribed layout</h2>
 *
 * <pre>
 *  Group                 Items                                          Bytes  Offset
 *  CDEMO-GENERAL-INFO    X(04) X(08) X(04) X(08) X(08) X(01) 9(01)         34       0
 *  CDEMO-CUSTOMER-INFO   9(09) X(25) X(25) X(25)                           84      34
 *  CDEMO-ACCOUNT-INFO    9(11) X(01)                                       12     118
 *  CDEMO-CARD-INFO       9(16)                                             16     130
 *  CDEMO-MORE-INFO       X(7) X(7)                                         14     146
 *                                                                         ---
 *                        34 + 84 + 12 + 16 + 14                            160
 * </pre>
 */
@DisplayName("NavigationContext - CARDDEMO-COMMAREA: 160 bytes, four conditions, and no session")
class NavigationContextTest {

    /**
     * The code page of the text fixtures, named explicitly per rule B8. {@code US-ASCII} is what
     * {@code app/data/ASCII} holds; {@code IBM037} is the EBCDIC alternative. The platform default
     * is never used, because a default that differs between two machines makes a fixed-width image
     * differ between them too.
     */
    private static final Charset ASCII = Charset.forName("US-ASCII");

    /**
     * The declared record width, transcribed from {@code app/cpy/COCOM01Y.cpy} rather than read
     * from the class under test, so that the expectation is independent of the thing it judges. A
     * test that asserts {@code COMMAREA_LENGTH == COMMAREA_LENGTH} proves nothing.
     */
    private static final int DECLARED_COMMAREA_LENGTH = 160;

    /** Declared width of {@code CDEMO-LAST-MAP} - {@code PIC X(7)}, line 43. Seven, not eight. */
    private static final int DECLARED_LAST_MAP_LENGTH = 7;

    /** Declared width of {@code CDEMO-LAST-MAPSET} - {@code PIC X(7)}, line 44. Seven, not eight. */
    private static final int DECLARED_LAST_MAPSET_LENGTH = 7;

    /**
     * A codec over the named code page. Built fresh per use rather than held in a static field:
     * rule B9 forbids static mutable state, and a test suite that shares a mutable helper between
     * methods makes its own results order-dependent.
     *
     * @return a codec carrying {@link #ASCII} explicitly
     */
    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(ASCII);
    }

    /**
     * A fully populated context in which <strong>every same-width field holds a different
     * value</strong>. That property is the whole point of the fixture: it is what makes a swap
     * between two fields of equal width detectable, which a symmetric payload would hide.
     *
     * <ul>
     *   <li>The two {@code X(04)} transaction identifiers differ: {@code CC00} against
     *       {@code CM00}. Both are real CardDemo transaction identifiers -
     *       {@code app/csd/CARDDEMO.CSD} binds {@code CC00} to the sign-on program and {@code CM00}
     *       to the main menu.</li>
     *   <li>The two {@code X(08)} program names differ: {@code COSGN00C} against {@code COMEN01C},
     *       the exact pair {@code app/cbl/COSGN00C.cbl:237} transfers between.</li>
     *   <li>The two {@code X(7)} map fields differ: {@code COMEN1A} against {@code COMEN01}.</li>
     *   <li>The three {@code X(25)} customer names differ from one another.</li>
     * </ul>
     *
     * <p>Every character field is sized to its declared width through
     * {@link FixedWidthCodec#movePicX(String, int)} rather than by a hand-counted run of spaces in a
     * literal. Two reasons: a miscounted literal would be a defect in the test rather than in the
     * code, and sizing every field exactly makes the fixed-width round trip perfectly symmetric, so
     * a per-field assertion compares what went in against what came back with no padding allowance
     * to reason about. The shorter-value padding path is exercised separately, in
     * {@link PaddingAndFilling}.
     *
     * @return a populated context, distinct in every same-width pair, every field at declared width
     */
    private static NavigationContext populated() {
        FixedWidthCodec codec = codec();
        return new NavigationContext(codec.movePicX("CC00", NavigationContext.FROM_TRANID_LENGTH),
                codec.movePicX("COSGN00C", NavigationContext.FROM_PROGRAM_LENGTH),
                codec.movePicX("CM00", NavigationContext.TO_TRANID_LENGTH),
                codec.movePicX("COMEN01C", NavigationContext.TO_PROGRAM_LENGTH),
                codec.movePicX("ADMIN001", NavigationContext.USER_ID_LENGTH),
                NavigationContext.USER_TYPE_ADMIN,
                NavigationContext.PGM_CONTEXT_REENTER,
                123456789,
                codec.movePicX("FIRSTNAME", NavigationContext.CUST_FNAME_LENGTH),
                codec.movePicX("MIDDLENAME", NavigationContext.CUST_MNAME_LENGTH),
                codec.movePicX("LASTNAME", NavigationContext.CUST_LNAME_LENGTH),
                12345678901L,
                "Y",
                4111111111111111L,
                codec.movePicX("COMEN1A", DECLARED_LAST_MAP_LENGTH),
                codec.movePicX("COMEN01", DECLARED_LAST_MAPSET_LENGTH));
    }

    /**
     * Decodes one field's raw span out of a {@value #DECLARED_COMMAREA_LENGTH}-byte image using the
     * offsets and widths transcribed above, so that the image is inspected by absolute position
     * rather than through the class that produced it.
     *
     * @param image  the serialised record
     * @param offset the field's 0-based offset
     * @param length the field's declared width
     * @return exactly {@code length} characters, untrimmed
     */
    private static String spanOf(byte[] image, int offset, int length) {
        return new String(image, offset, length, ASCII);
    }

    // =================================================================================================
    // The declared geometry. Every number here is transcribed from app/cpy/COCOM01Y.cpy, and every sum
    // is written out in the assertion so the arithmetic is visible to a reviewer rather than hidden
    // behind a constant.
    // =================================================================================================

    @Nested
    @DisplayName("Declared geometry - 34 + 84 + 12 + 16 + 14 = 160")
    class DeclaredGeometry {

        @Test
        @DisplayName("the serialised image is exactly 160 bytes")
        void imageIsOneHundredAndSixtyBytes() {
            byte[] image = populated().toFixedWidth(codec());

            assertThat(image)
                    .as("01 CARDDEMO-COMMAREA of app/cpy/COCOM01Y.cpy is 160 bytes wide")
                    .hasSize(DECLARED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the five group widths are 34, 84, 12, 16 and 14, and they sum to 160")
        void groupWidthsSumToTheRecordWidth() {
            // Each group width is asserted against the transcribed literal first, so a change in the
            // class cannot quietly redefine what "correct" means.
            assertThat(NavigationContext.GENERAL_INFO_LENGTH)
                    .as("CDEMO-GENERAL-INFO: X(04)+X(08)+X(04)+X(08)+X(08)+X(01)+9(01)")
                    .isEqualTo(4 + 8 + 4 + 8 + 8 + 1 + 1)
                    .isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH)
                    .as("CDEMO-CUSTOMER-INFO: 9(09)+X(25)+X(25)+X(25)")
                    .isEqualTo(9 + 25 + 25 + 25)
                    .isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH)
                    .as("CDEMO-ACCOUNT-INFO: 9(11)+X(01)")
                    .isEqualTo(11 + 1)
                    .isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH)
                    .as("CDEMO-CARD-INFO: 9(16)")
                    .isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .as("CDEMO-MORE-INFO: X(7)+X(7) - fourteen, not sixteen")
                    .isEqualTo(7 + 7)
                    .isEqualTo(14);

            // The sum is spelled out rather than folded into a single constant, because the whole
            // point of the check is that the addition is right.
            assertThat(34 + 84 + 12 + 16 + 14)
                    .as("the five group widths must account for the whole record")
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("and the declared group constants must sum to the declared record width")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the five group offsets are 0, 34, 118, 130 and 146")
        void groupOffsetsFollowTheDeclaredWidths() {
            assertThat(NavigationContext.GENERAL_INFO_OFFSET).as("the record starts here").isZero();
            assertThat(NavigationContext.CUSTOMER_INFO_OFFSET).as("0 + 34").isEqualTo(34);
            assertThat(NavigationContext.ACCOUNT_INFO_OFFSET).as("34 + 84").isEqualTo(118);
            assertThat(NavigationContext.CARD_INFO_OFFSET).as("118 + 12").isEqualTo(130);
            assertThat(NavigationContext.MORE_INFO_OFFSET).as("130 + 16").isEqualTo(146);

            // The last group must end exactly on the record boundary: 146 + 14 = 160. A gap or an
            // overlap anywhere earlier surfaces right here.
            assertThat(NavigationContext.MORE_INFO_OFFSET + NavigationContext.MORE_INFO_LENGTH)
                    .as("146 + 14 must land exactly on the end of the record")
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the layout declares all sixteen fields contiguously from 0 with no gap")
        void layoutIsContiguousAndComplete() {
            List<FieldSpan> spans = NavigationContext.LAYOUT.storageSpans();

            assertThat(spans)
                    .as("COCOM01Y declares sixteen elementary items and no FILLER")
                    .hasSize(16);
            assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(DECLARED_COMMAREA_LENGTH);

            int expectedOffset = 0;
            for (FieldSpan span : spans) {
                assertThat(span.offset())
                        .as("%s must begin where its predecessor ended", span.name())
                        .isEqualTo(expectedOffset);
                expectedOffset += span.length();
            }
            assertThat(expectedOffset)
                    .as("the spans must account for every byte of the record")
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("every field is named exactly as the copybook spells it, hyphens included")
        void fieldNamesAreTheCopybookNames() {
            // Field-for-field diffing keys on these names, so a "tidied up" name would make a real
            // difference invisible. Transcribed from app/cpy/COCOM01Y.cpy lines 21-44, in order.
            assertThat(NavigationContext.LAYOUT.storageSpans())
                    .extracting(FieldSpan::name)
                    .containsExactly("CDEMO-FROM-TRANID",
                            "CDEMO-FROM-PROGRAM",
                            "CDEMO-TO-TRANID",
                            "CDEMO-TO-PROGRAM",
                            "CDEMO-USER-ID",
                            "CDEMO-USER-TYPE",
                            "CDEMO-PGM-CONTEXT",
                            "CDEMO-CUST-ID",
                            "CDEMO-CUST-FNAME",
                            "CDEMO-CUST-MNAME",
                            "CDEMO-CUST-LNAME",
                            "CDEMO-ACCT-ID",
                            "CDEMO-ACCT-STATUS",
                            "CDEMO-CARD-NUM",
                            "CDEMO-LAST-MAP",
                            "CDEMO-LAST-MAPSET");
        }

        @ParameterizedTest(name = "{0} is declared at offset {1} and width {2}")
        @CsvSource({
            "CDEMO-FROM-TRANID,    0,  4",
            "CDEMO-FROM-PROGRAM,   4,  8",
            "CDEMO-TO-TRANID,     12,  4",
            "CDEMO-TO-PROGRAM,    16,  8",
            "CDEMO-USER-ID,       24,  8",
            "CDEMO-USER-TYPE,     32,  1",
            "CDEMO-PGM-CONTEXT,   33,  1",
            "CDEMO-CUST-ID,       34,  9",
            "CDEMO-CUST-FNAME,    43, 25",
            "CDEMO-CUST-MNAME,    68, 25",
            "CDEMO-CUST-LNAME,    93, 25",
            "CDEMO-ACCT-ID,      118, 11",
            "CDEMO-ACCT-STATUS,  129,  1",
            "CDEMO-CARD-NUM,     130, 16",
            "CDEMO-LAST-MAP,     146,  7",
            "CDEMO-LAST-MAPSET,  153,  7"
        })
        @DisplayName("each field sits at its transcribed offset and width")
        void eachFieldSitsWhereTheCopybookPutsIt(String cobolName, int offset, int length) {
            FieldSpan span = NavigationContext.LAYOUT.span(cobolName);

            assertThat(span.offset()).as("%s offset", cobolName).isEqualTo(offset);
            assertThat(span.length()).as("%s width", cobolName).isEqualTo(length);
            assertThat(span.endOffsetExclusive()).isEqualTo(offset + length);
        }

        @Test
        @DisplayName("CDEMO-LAST-MAP precedes CDEMO-LAST-MAPSET, as the copybook declares them")
        void mapPrecedesMapsetInDeclarationOrder() {
            // app/cpy/COCOM01Y.cpy declares CDEMO-LAST-MAP at line 43 and CDEMO-LAST-MAPSET at line
            // 44 - MAP first. The two are the same width, so reversing them would leave the record
            // exactly 160 bytes and would be invisible to any width check; only order catches it.
            // "Mapset then map" is the plausible wrong guess, because that is the order CVCRD01Y
            // uses for its own pair (CCARD-NEXT-MAPSET then CCARD-NEXT-MAP).
            assertThat(NavigationContext.LAST_MAP_OFFSET)
                    .as("CDEMO-LAST-MAP is declared first, at the start of CDEMO-MORE-INFO")
                    .isEqualTo(146)
                    .isLessThan(NavigationContext.LAST_MAPSET_OFFSET);
            assertThat(NavigationContext.LAST_MAPSET_OFFSET)
                    .as("CDEMO-LAST-MAPSET follows it, at 146 + 7")
                    .isEqualTo(153);
        }
    }

    // =================================================================================================
    // The X(7) trap. This is the single most likely defect in a hand-written model of this copybook.
    // =================================================================================================

    @Nested
    @DisplayName("The X(7) trap - map and mapset names are seven characters, not eight")
    class TheSevenCharacterTrap {

        @Test
        @DisplayName("CDEMO-LAST-MAP is 7 bytes")
        void lastMapIsSevenBytes() {
            // app/cpy/COCOM01Y.cpy:43 declares CDEMO-LAST-MAP as PIC X(7).
            //
            // X(8) is the natural wrong guess: every other name field in this same copybook is
            // X(08) - CDEMO-FROM-PROGRAM (L22), CDEMO-TO-PROGRAM (L24), CDEMO-USER-ID (L25) - so
            // eight is what the eye expects. Widening both map fields to eight would make
            // CDEMO-MORE-INFO sixteen bytes and the record 162.
            //
            // app/cpy/CVCRD01Y.cpy corroborates the seven independently: its CCARD-NEXT-MAPSET and
            // CCARD-NEXT-MAP are both PIC X(7), while its CCARD-NEXT-PROG is PIC X(8). Mapset and
            // map names are seven; program names are eight.
            assertThat(NavigationContext.LAST_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP is PIC X(7) at app/cpy/COCOM01Y.cpy:43")
                    .isEqualTo(DECLARED_LAST_MAP_LENGTH)
                    .isEqualTo(7);
            assertThat(NavigationContext.LAYOUT.span("CDEMO-LAST-MAP").length()).isEqualTo(7);
        }

        @Test
        @DisplayName("CDEMO-LAST-MAPSET is 7 bytes")
        void lastMapsetIsSevenBytes() {
            // app/cpy/COCOM01Y.cpy:44 declares CDEMO-LAST-MAPSET as PIC X(7), for the same reason
            // and with the same corroboration from app/cpy/CVCRD01Y.cpy's CCARD-NEXT-MAPSET.
            // Guessing X(8) here costs the second of the two surplus bytes that would take the
            // record from 160 to 162.
            assertThat(NavigationContext.LAST_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET is PIC X(7) at app/cpy/COCOM01Y.cpy:44")
                    .isEqualTo(DECLARED_LAST_MAPSET_LENGTH)
                    .isEqualTo(7);
            assertThat(NavigationContext.LAYOUT.span("CDEMO-LAST-MAPSET").length()).isEqualTo(7);
        }

        @Test
        @DisplayName("the two map fields together occupy 14 bytes, so the record is 160 and not 162")
        void theTwoMapFieldsCostFourteenNotSixteen() {
            int asDeclared = DECLARED_LAST_MAP_LENGTH + DECLARED_LAST_MAPSET_LENGTH;
            int ifBothWereEight = 8 + 8;

            assertThat(asDeclared).as("7 + 7").isEqualTo(14);
            assertThat(ifBothWereEight).as("the wrong guess, 8 + 8").isEqualTo(16);
            assertThat(34 + 84 + 12 + 16 + asDeclared)
                    .as("with the declared X(7) widths the record is 160")
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
            assertThat(34 + 84 + 12 + 16 + ifBothWereEight)
                    .as("with X(8) it would be 162 - the exact cost of the wrong guess")
                    .isEqualTo(162)
                    .isNotEqualTo(DECLARED_COMMAREA_LENGTH);
        }

        @ParameterizedTest(name = "{0} is eight characters, because it names a program")
        @CsvSource({
            "CDEMO-FROM-PROGRAM, 8",
            "CDEMO-TO-PROGRAM,   8",
            "CDEMO-USER-ID,      8"
        })
        @DisplayName("the neighbouring X(08) fields really are eight, which is what makes 7 a trap")
        void programNamesAreEightCharacters(String cobolName, int expectedLength) {
            // These are the fields that make the seven-versus-eight distinction meaningful. If every
            // field in the copybook were seven, nobody would guess eight.
            assertThat(NavigationContext.LAYOUT.span(cobolName).length()).isEqualTo(expectedLength);
        }

        @ParameterizedTest(name = "{0} is four characters, a CICS transaction identifier")
        @CsvSource({
            "CDEMO-FROM-TRANID, 4",
            "CDEMO-TO-TRANID,   4"
        })
        @DisplayName("both transaction identifiers are four characters")
        void transactionIdentifiersAreFourCharacters(String cobolName, int expectedLength) {
            assertThat(NavigationContext.LAYOUT.span(cobolName).length()).isEqualTo(expectedLength);
        }

        @Test
        @DisplayName("a seven-character map name round-trips intact")
        void sevenCharacterNameSurvivesIntact() {
            // COMEN1A is a real seven-character map name: app/bms/COMEN01.bms defines mapset
            // COMEN01 containing map COMEN1A, whose symbolic-map group items are COMEN1AI and
            // COMEN1AO - seven characters plus a one-character direction suffix, which is precisely
            // why a map name cannot be eight.
            NavigationContext context = NavigationContext.empty()
                    .withLastMap("COMEN1A")
                    .withLastMapset("COMEN01");

            byte[] image = context.toFixedWidth(codec());
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(), image);

            assertThat(image).hasSize(DECLARED_COMMAREA_LENGTH);
            assertThat(restored.lastMap()).isEqualTo("COMEN1A");
            assertThat(restored.lastMapset()).isEqualTo("COMEN01");
            assertThat(spanOf(image, 146, 7)).as("the raw span at 146").isEqualTo("COMEN1A");
            assertThat(spanOf(image, 153, 7)).as("the raw span at 153").isEqualTo("COMEN01");
        }

        @Test
        @DisplayName("an eight-character map name is refused, which is what catches an X(8) model")
        void eightCharacterNameIsRefused() {
            // This is the assertion that actually catches a model built on X(8): under a seven-byte
            // field an eight-character value does not fit, and the class refuses it rather than
            // quietly dropping a character. Under an X(8) model it would be accepted and this test
            // would fail - which is exactly the alarm wanted.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withLastMap("COACTUPX"))
                    .withMessageContaining("CDEMO-LAST-MAP")
                    .withMessageContaining("PIC X(7)");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withLastMapset("COACTUPX"))
                    .withMessageContaining("CDEMO-LAST-MAPSET")
                    .withMessageContaining("PIC X(7)");
        }

        @Test
        @DisplayName("shortening an eight-character name is explicit, and truncates on the right")
        void eightCharacterNameTruncatesOnTheRightWhenAskedTo() {
            // A caller that genuinely wants COBOL's alphanumeric MOVE behaviour asks for it, so the
            // direction of the loss is chosen deliberately and is visible at the call site. PIC X
            // truncates on the RIGHT, keeping the leading characters: COACTUPX -> COACTUP.
            String shortened = codec().movePicX("COACTUPX", NavigationContext.LAST_MAP_LENGTH);

            assertThat(shortened)
                    .as("PIC X discards the surplus from the right, keeping the leading characters")
                    .isEqualTo("COACTUP")
                    .hasSize(7);

            NavigationContext context = NavigationContext.empty().withLastMap(shortened);
            byte[] image = context.toFixedWidth(codec());

            assertThat(image).hasSize(DECLARED_COMMAREA_LENGTH);
            assertThat(spanOf(image, 146, 7)).isEqualTo("COACTUP");
        }
    }

    // =================================================================================================
    // Padding and filling. COBOL pads a PIC X receiver on the right with spaces and a PIC 9 receiver on
    // the left with zeros. The two directions are mirror images, so coding them the same way passes
    // casual review while corrupting every short value in the system.
    // =================================================================================================

    @Nested
    @DisplayName("Padding and filling - PIC X right-space-padded, PIC 9 left-zero-filled")
    class PaddingAndFilling {

        @Test
        @DisplayName("a three-character program name occupies 8 bytes with 5 trailing spaces")
        void shortCharacterValueIsRightSpacePadded() {
            // A value shorter than its field is accepted and padded by the codec when the image is
            // produced, exactly as a COBOL MOVE into a wider PIC X receiver pads.
            NavigationContext context = NavigationContext.empty().withFromProgram("ABC");

            byte[] image = context.toFixedWidth(codec());

            assertThat(image).hasSize(DECLARED_COMMAREA_LENGTH);
            assertThat(spanOf(image, 4, 8))
                    .as("CDEMO-FROM-PROGRAM: three characters then five spaces, padded on the right")
                    .isEqualTo("ABC     ")
                    .hasSize(8);
        }

        @Test
        @DisplayName("a customer id of 42 occupies 9 bytes as 000000042")
        void shortNumericValueIsLeftZeroFilled() {
            // PIC 9 aligns on its implied decimal point, so a short value is filled on the LEFT.
            // Filling on the right would turn 42 into 420000000 - a different customer entirely.
            NavigationContext context = NavigationContext.empty().withCustId(42);

            byte[] image = context.toFixedWidth(codec());

            assertThat(spanOf(image, 34, 9))
                    .as("CDEMO-CUST-ID PIC 9(09): zero-filled on the left")
                    .isEqualTo("000000042")
                    .hasSize(9);
        }

        @ParameterizedTest(name = "every PIC X span of an initialised area is all spaces: {0}")
        @CsvSource({
            "CDEMO-FROM-TRANID,    0,  4",
            "CDEMO-FROM-PROGRAM,   4,  8",
            "CDEMO-TO-TRANID,     12,  4",
            "CDEMO-TO-PROGRAM,    16,  8",
            "CDEMO-USER-ID,       24,  8",
            "CDEMO-USER-TYPE,     32,  1",
            "CDEMO-CUST-FNAME,    43, 25",
            "CDEMO-CUST-MNAME,    68, 25",
            "CDEMO-CUST-LNAME,    93, 25",
            "CDEMO-ACCT-STATUS,  129,  1",
            "CDEMO-LAST-MAP,     146,  7",
            "CDEMO-LAST-MAPSET,  153,  7"
        })
        @DisplayName("an initialised area holds spaces in every character span")
        void initialisedCharacterSpansAreSpaces(String cobolName, int offset, int length) {
            byte[] image = NavigationContext.empty().toFixedWidth(codec());

            assertThat(spanOf(image, offset, length))
                    .as("%s of an initialised CARDDEMO-COMMAREA", cobolName)
                    .isEqualTo(" ".repeat(length));
        }

        @ParameterizedTest(name = "every PIC 9 span of an initialised area is all zeros: {0}")
        @CsvSource({
            "CDEMO-PGM-CONTEXT,  33,  1",
            "CDEMO-CUST-ID,      34,  9",
            "CDEMO-ACCT-ID,     118, 11",
            "CDEMO-CARD-NUM,    130, 16"
        })
        @DisplayName("an initialised area holds zeros in every numeric span")
        void initialisedNumericSpansAreZeros(String cobolName, int offset, int length) {
            byte[] image = NavigationContext.empty().toFixedWidth(codec());

            assertThat(spanOf(image, offset, length))
                    .as("%s of an initialised CARDDEMO-COMMAREA", cobolName)
                    .isEqualTo("0".repeat(length));
        }

        @Test
        @DisplayName("a padded value comes back at full declared width, untrimmed")
        void paddingSurvivesTheRoundTripUntrimmed() {
            // Reading back raw and untrimmed is what makes the round trip byte-identical: rendering
            // the result reproduces the image it was read from, because nothing was dropped on the
            // way in. A codec that trimmed here would make a re-serialised record shorter.
            NavigationContext context = NavigationContext.empty().withUserId("USER1");

            NavigationContext restored =
                    NavigationContext.fromFixedWidth(codec(), context.toFixedWidth(codec()));

            assertThat(restored.userId())
                    .as("CDEMO-USER-ID comes back at its full declared width of 8")
                    .isEqualTo("USER1   ")
                    .hasSize(8);
            assertThat(restored.toFixedWidth(codec()))
                    .as("and re-rendering it reproduces the same 160 bytes")
                    .isEqualTo(context.toFixedWidth(codec()));
        }
    }

    // =================================================================================================
    // The four 88-level condition names, each driven TRUE and FALSE (gate G50).
    //
    // app/cpy/COCOM01Y.cpy lines 26-31:
    //     10 CDEMO-USER-TYPE               PIC X(01).
    //        88 CDEMO-USRTYP-ADMIN         VALUE 'A'.
    //        88 CDEMO-USRTYP-USER          VALUE 'U'.
    //     10 CDEMO-PGM-CONTEXT             PIC 9(01).
    //        88 CDEMO-PGM-ENTER            VALUE 0.
    //        88 CDEMO-PGM-REENTER          VALUE 1.
    //
    // Exactly four, and neither pair is exhaustive - which is why "neither" is asserted below rather
    // than being collapsed into a default.
    // =================================================================================================

    @Nested
    @DisplayName("CDEMO-USRTYP-ADMIN and CDEMO-USRTYP-USER - both states, and neither")
    class UserTypeConditions {

        @Test
        @DisplayName("CDEMO-USRTYP-ADMIN is true for 'A'")
        void adminIsTrueForA() {
            // This is the condition app/cbl/COSGN00C.cbl:230 tests. When it holds, the program
            // transfers to COADM01C at line 232; otherwise it transfers to COMEN01C at line 237.
            // Those two hard-coded routes become a role on the sign-on response - the upstream half
            // of gate G40 - so this predicate is what a controller branches on.
            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin();

            assertThat(admin.isAdmin()).as("CDEMO-USRTYP-ADMIN VALUE 'A'").isTrue();
            assertThat(admin.userType()).isEqualTo("A");
        }

        @ParameterizedTest(name = "CDEMO-USRTYP-ADMIN is false for a user type of [{0}]")
        @ValueSource(strings = {"U", " ", "X", "a", "0"})
        @DisplayName("CDEMO-USRTYP-ADMIN is false for a regular user, a space, and any other byte")
        void adminIsFalseForEverythingElse(String userType) {
            // A lower-case 'a' is included deliberately: a COBOL alphanumeric comparison is exact
            // and case-sensitive, so 'a' does not satisfy a condition declared VALUE 'A'.
            NavigationContext context = NavigationContext.empty().withUserType(userType);

            assertThat(context.isAdmin())
                    .as("only an exact 'A' satisfies CDEMO-USRTYP-ADMIN")
                    .isFalse();
        }

        @Test
        @DisplayName("CDEMO-USRTYP-USER is true for 'U'")
        void userIsTrueForU() {
            // app/cbl/COCRDLIC.cbl asserts this condition with SET CDEMO-USRTYP-USER TO TRUE, which
            // withUserTypeUser() reproduces.
            NavigationContext user = NavigationContext.empty().withUserTypeUser();

            assertThat(user.isUser()).as("CDEMO-USRTYP-USER VALUE 'U'").isTrue();
            assertThat(user.userType()).isEqualTo("U");
        }

        @ParameterizedTest(name = "CDEMO-USRTYP-USER is false for a user type of [{0}]")
        @ValueSource(strings = {"A", " ", "X", "u", "1"})
        @DisplayName("CDEMO-USRTYP-USER is false for an administrator, a space, and any other byte")
        void userIsFalseForEverythingElse(String userType) {
            NavigationContext context = NavigationContext.empty().withUserType(userType);

            assertThat(context.isUser())
                    .as("only an exact 'U' satisfies CDEMO-USRTYP-USER")
                    .isFalse();
        }

        @ParameterizedTest(name = "the two conditions are never both true for [{0}]")
        @ValueSource(strings = {"A", "U", " ", "X", "a", "u", "0"})
        @DisplayName("the two conditions are mutually exclusive for every tested value")
        void theTwoConditionsAreMutuallyExclusive(String userType) {
            NavigationContext context = NavigationContext.empty().withUserType(userType);

            assertThat(context.isAdmin() && context.isUser())
                    .as("one byte cannot be both 'A' and 'U'")
                    .isFalse();
        }

        @ParameterizedTest(name = "neither condition holds for [{0}]")
        @ValueSource(strings = {" ", "X", "a", "u", "0"})
        @DisplayName("neither condition holds for an unset user type, and that is a reachable state")
        void neitherConditionHoldsForAnUnsetUserType(String userType) {
            // COBOL 88-levels are independent predicates over one byte, not an enumeration that
            // partitions it. CDEMO-USER-TYPE is PIC X(01) and may legitimately hold a space - which
            // is exactly what a freshly initialised COMMAREA holds before sign-on - in which case
            // BOTH conditions are false. That state must not be coerced into a default.
            //
            // Writing isUser() as !isAdmin() would change behaviour: it would report an
            // unidentified user as a regular user. Note that app/cbl/COSGN00C.cbl:230 branches
            // IF CDEMO-USRTYP-ADMIN ... ELSE ..., so its ELSE covers "not an administrator", which
            // is a WIDER set than "is a regular user" - a blank user type takes the ELSE and reaches
            // COMEN01C without ever satisfying CDEMO-USRTYP-USER.
            NavigationContext context = NavigationContext.empty().withUserType(userType);

            assertThat(context.isAdmin()).isFalse();
            assertThat(context.isUser()).isFalse();
        }

        @Test
        @DisplayName("an initialised area implies no role at all")
        void initialisedAreaImpliesNoRole() {
            NavigationContext initial = NavigationContext.empty();

            assertThat(initial.userType()).as("a single space, per PIC X(01)").isEqualTo(" ");
            assertThat(initial.isAdmin()).isFalse();
            assertThat(initial.isUser()).isFalse();
        }

        @Test
        @DisplayName("the role routing COSGN00C hard-codes is reproducible from the predicate alone")
        void roleRoutingFollowsFromThePredicate() {
            // G40, upstream half. app/cbl/COSGN00C.cbl:230-240 is:
            //     IF CDEMO-USRTYP-ADMIN
            //          EXEC CICS XCTL PROGRAM ('COADM01C') ...      <- line 232
            //     ELSE
            //          EXEC CICS XCTL PROGRAM ('COMEN01C') ...      <- line 237
            //     END-IF
            // The transfer becomes a response field naming the next program; the client issues the
            // follow-up call. Reproduced here exactly, ELSE included.
            assertThat(NavigationContext.empty().withUserTypeAdmin().isAdmin() ? "COADM01C" : "COMEN01C")
                    .as("an administrator reaches the admin menu, COSGN00C:232")
                    .isEqualTo("COADM01C");
            assertThat(NavigationContext.empty().withUserTypeUser().isAdmin() ? "COADM01C" : "COMEN01C")
                    .as("a regular user reaches the main menu, COSGN00C:237")
                    .isEqualTo("COMEN01C");
            assertThat(NavigationContext.empty().isAdmin() ? "COADM01C" : "COMEN01C")
                    .as("and a blank user type takes the ELSE, exactly as the COBOL does")
                    .isEqualTo("COMEN01C");
        }
    }

    @Nested
    @DisplayName("CDEMO-PGM-ENTER and CDEMO-PGM-REENTER - both states, and neither")
    class ProgramContextConditions {

        @Test
        @DisplayName("CDEMO-PGM-ENTER is true when the context is 0")
        void enterIsTrueForZero() {
            // First entry into a transaction: the program paints its screen and validates nothing.
            // app/cbl/COSGN00C.cbl:228 asserts it with MOVE ZEROS TO CDEMO-PGM-CONTEXT.
            NavigationContext enter = NavigationContext.empty().withPgmEnter();

            assertThat(enter.pgmContext()).isEqualTo(0);
            assertThat(enter.isEnter()).as("CDEMO-PGM-ENTER VALUE 0").isTrue();
        }

        @Test
        @DisplayName("CDEMO-PGM-ENTER is false when the context is 1")
        void enterIsFalseForOne() {
            assertThat(NavigationContext.empty().withPgmReenter().isEnter()).isFalse();
        }

        @Test
        @DisplayName("CDEMO-PGM-REENTER is true when the context is 1")
        void reenterIsTrueForOne() {
            // G38, upstream half. This is the exact condition app/cpy/CSSETATY.cpy requires before
            // it applies the error highlight. Its lines 18-27 read:
            //     IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK)
            //     AND CDEMO-PGM-REENTER                                <- line 20
            //         MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O        <- line 21
            //         IF FLG-(TESTVAR1)-BLANK
            //             MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O       <- line 24
            //         END-IF
            //     END-IF
            // CDEMO-PGM-REENTER is a hard CONJUNCT: a field in error on FIRST entry is NOT
            // highlighted, because the user has not typed anything yet. This predicate is therefore
            // the upstream half of the gate, and FieldAttributeSetterTest asserts the downstream
            // half - that DFHRED and '*' are applied only when it holds.
            NavigationContext reenter = NavigationContext.empty().withPgmReenter();

            assertThat(reenter.pgmContext()).isEqualTo(1);
            assertThat(reenter.isReenter()).as("CDEMO-PGM-REENTER VALUE 1").isTrue();
        }

        @Test
        @DisplayName("CDEMO-PGM-REENTER is false when the context is 0")
        void reenterIsFalseForZero() {
            assertThat(NavigationContext.empty().withPgmEnter().isReenter()).isFalse();
        }

        @ParameterizedTest(name = "neither condition holds for a program context of {0}")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9})
        @DisplayName("neither condition holds for 2 through 9, which PIC 9(01) can represent")
        void neitherConditionHoldsForOtherDigits(int pgmContext) {
            // CDEMO-PGM-CONTEXT is PIC 9(01), so every digit 0-9 is representable and 2-9 satisfy
            // neither condition. Writing isReenter() as !isEnter() would report a context of 9 as
            // re-entry and would apply the error highlight on a screen the user has never seen.
            // The state is asserted, not coerced.
            NavigationContext context = NavigationContext.empty().withPgmContext(pgmContext);

            assertThat(context.pgmContext()).isEqualTo(pgmContext);
            assertThat(context.isEnter()).isFalse();
            assertThat(context.isReenter()).isFalse();
        }

        @ParameterizedTest(name = "the two conditions are never both true for {0}")
        @ValueSource(ints = {0, 1, 2, 5, 9})
        @DisplayName("the two conditions are mutually exclusive for every representable digit")
        void theTwoConditionsAreMutuallyExclusive(int pgmContext) {
            NavigationContext context = NavigationContext.empty().withPgmContext(pgmContext);

            assertThat(context.isEnter() && context.isReenter())
                    .as("one digit cannot be both 0 and 1")
                    .isFalse();
        }

        @Test
        @DisplayName("an initialised area is already in ENTER state, as MOVE ZEROS leaves it")
        void initialisedAreaIsInEnterState() {
            NavigationContext initial = NavigationContext.empty();

            assertThat(initial.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(initial.isEnter()).isTrue();
            assertThat(initial.isReenter()).isFalse();
        }

        @Test
        @DisplayName("the highlight condition holds only on re-entry, never on first entry")
        void highlightAppliesOnlyOnReentry() {
            // The CSSETATY conjunct, spelled out: a field flagged in error is highlighted only when
            // the program is re-entered. Both halves of the conjunction are exercised.
            boolean fieldInError = true;

            assertThat(fieldInError && NavigationContext.empty().withPgmEnter().isReenter())
                    .as("first entry: nothing typed yet, so no DFHRED and no '*'")
                    .isFalse();
            assertThat(fieldInError && NavigationContext.empty().withPgmReenter().isReenter())
                    .as("re-entry: the field really is in error, so the highlight applies")
                    .isTrue();
        }

        @Test
        @DisplayName("the four condition constants are exactly the copybook's four values")
        void theFourConditionConstantsMatchTheCopybook() {
            // Transcribed from app/cpy/COCOM01Y.cpy lines 27, 28, 30 and 31.
            assertThat(NavigationContext.USER_TYPE_ADMIN).as("L27 VALUE 'A'").isEqualTo("A");
            assertThat(NavigationContext.USER_TYPE_USER).as("L28 VALUE 'U'").isEqualTo("U");
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).as("L30 VALUE 0").isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).as("L31 VALUE 1").isEqualTo(1);
        }
    }

    // =================================================================================================
    // Round-trip fidelity, which is what the parity harness fingerprints.
    //
    // Every assertion here is PER FIELD. A byte-array comparison alone is not enough: a swap between
    // two same-width fields produces an image of the correct length whose bytes happen to match when
    // the payload is symmetric, so it would pass. The populated() fixture therefore gives every
    // same-width pair a DIFFERENT value, and each field is then asserted individually.
    // =================================================================================================

    @Nested
    @DisplayName("Fixed-width round trip - per field, so a field-order swap cannot hide")
    class FixedWidthRoundTrip {

        @Test
        @DisplayName("every one of the sixteen fields survives a round trip individually")
        void everyFieldSurvivesIndividually() {
            NavigationContext original = populated();

            byte[] image = original.toFixedWidth(codec());
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(), image);

            assertThat(image).hasSize(DECLARED_COMMAREA_LENGTH);

            // Asserted field by field rather than as one object comparison, so a failure names the
            // field that moved instead of reporting that "something" differs.
            assertThat(restored.fromTranid()).as("CDEMO-FROM-TRANID").isEqualTo("CC00");
            assertThat(restored.fromProgram()).as("CDEMO-FROM-PROGRAM").isEqualTo("COSGN00C");
            assertThat(restored.toTranid()).as("CDEMO-TO-TRANID").isEqualTo("CM00");
            assertThat(restored.toProgram()).as("CDEMO-TO-PROGRAM").isEqualTo("COMEN01C");
            assertThat(restored.userId()).as("CDEMO-USER-ID").isEqualTo("ADMIN001");
            assertThat(restored.userType()).as("CDEMO-USER-TYPE").isEqualTo("A");
            assertThat(restored.pgmContext()).as("CDEMO-PGM-CONTEXT").isEqualTo(1);
            assertThat(restored.custId()).as("CDEMO-CUST-ID").isEqualTo(123456789);
            assertThat(restored.custFname()).as("CDEMO-CUST-FNAME")
                    .isEqualTo(original.custFname()).startsWith("FIRSTNAME").hasSize(25);
            assertThat(restored.custMname()).as("CDEMO-CUST-MNAME")
                    .isEqualTo(original.custMname()).startsWith("MIDDLENAME").hasSize(25);
            assertThat(restored.custLname()).as("CDEMO-CUST-LNAME")
                    .isEqualTo(original.custLname()).startsWith("LASTNAME").hasSize(25);
            assertThat(restored.acctId()).as("CDEMO-ACCT-ID").isEqualTo(12345678901L);
            assertThat(restored.acctStatus()).as("CDEMO-ACCT-STATUS").isEqualTo("Y");
            assertThat(restored.cardNum()).as("CDEMO-CARD-NUM").isEqualTo(4111111111111111L);
            assertThat(restored.lastMap()).as("CDEMO-LAST-MAP").isEqualTo("COMEN1A");
            assertThat(restored.lastMapset()).as("CDEMO-LAST-MAPSET").isEqualTo("COMEN01");

            // Only after the per-field checks is the whole value compared, and the image re-rendered.
            assertThat(restored).isEqualTo(original);
            assertThat(restored.toFixedWidth(codec())).isEqualTo(image);
        }

        @Test
        @DisplayName("the two X(04) transaction identifiers do not swap")
        void theTwoTransactionIdentifiersDoNotSwap() {
            // CC00 and CM00 are both four characters, so a swap leaves the record 160 bytes and
            // leaves every width check satisfied. Distinct values are the only thing that catches it.
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(),
                    populated().toFixedWidth(codec()));

            assertThat(restored.fromTranid()).isEqualTo("CC00");
            assertThat(restored.toTranid()).isEqualTo("CM00");
            assertThat(restored.fromTranid()).isNotEqualTo(restored.toTranid());
        }

        @Test
        @DisplayName("the two X(08) program names do not swap")
        void theTwoProgramNamesDoNotSwap() {
            // COSGN00C and COMEN01C are the exact pair app/cbl/COSGN00C.cbl:237 transfers between,
            // so a swap here would invert the sign-on routing while looking entirely plausible.
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(),
                    populated().toFixedWidth(codec()));

            assertThat(restored.fromProgram()).isEqualTo("COSGN00C");
            assertThat(restored.toProgram()).isEqualTo("COMEN01C");
            assertThat(restored.fromProgram()).isNotEqualTo(restored.toProgram());
        }

        @Test
        @DisplayName("the two X(7) map fields do not swap")
        void theTwoMapFieldsDoNotSwap() {
            // Same width, adjacent, and the last two fields in the record - the easiest pair in the
            // whole layout to transpose. app/cpy/COCOM01Y.cpy declares MAP at line 43 and MAPSET at
            // line 44, and CVCRD01Y declares its own pair in the opposite order, so the wrong order
            // is a live possibility.
            byte[] image = populated().toFixedWidth(codec());
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(), image);

            assertThat(restored.lastMap()).as("declared first, at offset 146").isEqualTo("COMEN1A");
            assertThat(restored.lastMapset()).as("declared second, at offset 153").isEqualTo("COMEN01");
            assertThat(restored.lastMap()).isNotEqualTo(restored.lastMapset());
            assertThat(spanOf(image, 146, 7)).isEqualTo("COMEN1A");
            assertThat(spanOf(image, 153, 7)).isEqualTo("COMEN01");
        }

        @Test
        @DisplayName("the three X(25) customer names do not rotate")
        void theThreeCustomerNamesDoNotRotate() {
            // Three consecutive fields of identical width: a rotation among them is invisible to any
            // width or offset check.
            byte[] image = populated().toFixedWidth(codec());
            NavigationContext restored = NavigationContext.fromFixedWidth(codec(), image);

            assertThat(restored.custFname()).startsWith("FIRSTNAME");
            assertThat(restored.custMname()).startsWith("MIDDLENAME");
            assertThat(restored.custLname()).startsWith("LASTNAME");
            assertThat(spanOf(image, 43, 25)).startsWith("FIRSTNAME");
            assertThat(spanOf(image, 68, 25)).startsWith("MIDDLENAME");
            assertThat(spanOf(image, 93, 25)).startsWith("LASTNAME");
        }

        @Test
        @DisplayName("an initialised area serialises to 160 bytes of spaces and zeros")
        void initialisedAreaSerialisesToSpacesAndZeros() {
            byte[] image = NavigationContext.empty().toFixedWidth(codec());

            assertThat(image)
                    .as("never short, and never null")
                    .isNotNull()
                    .hasSize(DECLARED_COMMAREA_LENGTH);

            // Composed positionally from the transcribed layout: spaces in the twelve character
            // spans, zeros in the four numeric ones.
            String expected = " ".repeat(4)      // CDEMO-FROM-TRANID
                    + " ".repeat(8)              // CDEMO-FROM-PROGRAM
                    + " ".repeat(4)              // CDEMO-TO-TRANID
                    + " ".repeat(8)              // CDEMO-TO-PROGRAM
                    + " ".repeat(8)              // CDEMO-USER-ID
                    + " "                        // CDEMO-USER-TYPE
                    + "0"                        // CDEMO-PGM-CONTEXT
                    + "0".repeat(9)              // CDEMO-CUST-ID
                    + " ".repeat(25)             // CDEMO-CUST-FNAME
                    + " ".repeat(25)             // CDEMO-CUST-MNAME
                    + " ".repeat(25)             // CDEMO-CUST-LNAME
                    + "0".repeat(11)             // CDEMO-ACCT-ID
                    + " "                        // CDEMO-ACCT-STATUS
                    + "0".repeat(16)             // CDEMO-CARD-NUM
                    + " ".repeat(7)              // CDEMO-LAST-MAP
                    + " ".repeat(7);             // CDEMO-LAST-MAPSET

            assertThat(expected).as("the transcribed expectation must itself be 160 characters")
                    .hasSize(DECLARED_COMMAREA_LENGTH);
            assertThat(new String(image, ASCII)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an initialised area round-trips to an equal value, with no null field")
        void initialisedAreaRoundTrips() {
            NavigationContext initial = NavigationContext.empty();

            NavigationContext restored =
                    NavigationContext.fromFixedWidth(codec(), initial.toFixedWidth(codec()));

            assertThat(restored).isEqualTo(initial);
            // There is no null in a COBOL record: an unset PIC X field holds spaces.
            assertThat(restored.fromTranid()).isNotNull().isBlank().hasSize(4);
            assertThat(restored.fromProgram()).isNotNull().isBlank().hasSize(8);
            assertThat(restored.toTranid()).isNotNull().isBlank().hasSize(4);
            assertThat(restored.toProgram()).isNotNull().isBlank().hasSize(8);
            assertThat(restored.userId()).isNotNull().isBlank().hasSize(8);
            assertThat(restored.userType()).isNotNull().isBlank().hasSize(1);
            assertThat(restored.custFname()).isNotNull().isBlank().hasSize(25);
            assertThat(restored.custMname()).isNotNull().isBlank().hasSize(25);
            assertThat(restored.custLname()).isNotNull().isBlank().hasSize(25);
            assertThat(restored.acctStatus()).isNotNull().isBlank().hasSize(1);
            assertThat(restored.lastMap()).isNotNull().isBlank().hasSize(7);
            assertThat(restored.lastMapset()).isNotNull().isBlank().hasSize(7);
            assertThat(restored.pgmContext()).isZero();
            assertThat(restored.custId()).isZero();
            assertThat(restored.acctId()).isZero();
            assertThat(restored.cardNum()).isZero();
        }

        @Test
        @DisplayName("the layout's field names index the deserialised map, field for field")
        void deserialisedMapIsKeyedByCopybookName() {
            // This is the shape the parity harness fingerprints: a map keyed by copybook field name,
            // which is what makes the field differ able to report a difference per field rather than
            // as one opaque byte mismatch.
            Map<String, String> fields =
                    codec().deserialise(NavigationContext.LAYOUT, populated().toFixedWidth(codec()));

            assertThat(fields).hasSize(16);
            assertThat(fields.get("CDEMO-FROM-TRANID")).isEqualTo("CC00");
            assertThat(fields.get("CDEMO-TO-TRANID")).isEqualTo("CM00");
            assertThat(fields.get("CDEMO-FROM-PROGRAM")).isEqualTo("COSGN00C");
            assertThat(fields.get("CDEMO-TO-PROGRAM")).isEqualTo("COMEN01C");
            assertThat(fields.get("CDEMO-USER-TYPE")).isEqualTo("A");
            assertThat(fields.get("CDEMO-PGM-CONTEXT")).isEqualTo("1");
            assertThat(fields.get("CDEMO-CUST-ID")).as("PIC 9(09), zero-filled").isEqualTo("123456789");
            assertThat(fields.get("CDEMO-ACCT-ID")).as("PIC 9(11)").isEqualTo("12345678901");
            assertThat(fields.get("CDEMO-CARD-NUM")).as("PIC 9(16)").isEqualTo("4111111111111111");
            assertThat(fields.get("CDEMO-LAST-MAP")).isEqualTo("COMEN1A");
            assertThat(fields.get("CDEMO-LAST-MAPSET")).isEqualTo("COMEN01");
        }

        @Test
        @DisplayName("an image of the wrong length is refused rather than read short")
        void wrongLengthImageIsRefused() {
            byte[] tooShort = new byte[DECLARED_COMMAREA_LENGTH - 1];
            byte[] tooLong = new byte[DECLARED_COMMAREA_LENGTH + 1];

            assertThatIllegalArgumentException()
                    .as("159 bytes is not a CARDDEMO-COMMAREA")
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(codec(), tooShort));
            assertThatIllegalArgumentException()
                    .as("161 bytes is not one either - and 162 is the X(8) mistake")
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(codec(), tooLong));
        }

        @Test
        @DisplayName("the codec and the image are both required, and neither is defaulted")
        void codecAndImageAreRequired() {
            byte[] image = populated().toFixedWidth(codec());

            // The charset is never derived from the platform, so a missing codec is an error rather
            // than an invitation to guess one.
            assertThatNullPointerException()
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(null, image));
            assertThatNullPointerException()
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(codec(), null));
            assertThatNullPointerException()
                    .isThrownBy(() -> populated().toFixedWidth(null));
        }
    }

    // =================================================================================================
    // The JSON wire form. NavigationContext travels in request and response bodies, so JSON fidelity is
    // part of its contract - not an incidental convenience.
    //
    // The ObjectMapper is constructed plainly here. config/WebConfig is deliberately NOT loaded: this is
    // a unit test with no Spring context, and binding the assertions to the record's own annotations
    // rather than to a global configuration is what keeps them a statement about this type.
    // =================================================================================================

    @Nested
    @DisplayName("JSON wire form - all sixteen fields, padding untrimmed")
    class JsonWireForm {

        @Test
        @DisplayName("every one of the sixteen fields survives a JSON round trip individually")
        void everyFieldSurvivesAJsonRoundTrip() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            NavigationContext original = populated();

            String json = mapper.writeValueAsString(original);
            NavigationContext restored = mapper.readValue(json, NavigationContext.class);

            // Per field again, for the same reason as the fixed-width round trip: a swap between two
            // same-width fields would survive an object-level comparison of a symmetric payload.
            assertThat(restored.fromTranid()).as("CDEMO-FROM-TRANID").isEqualTo("CC00");
            assertThat(restored.fromProgram()).as("CDEMO-FROM-PROGRAM").isEqualTo("COSGN00C");
            assertThat(restored.toTranid()).as("CDEMO-TO-TRANID").isEqualTo("CM00");
            assertThat(restored.toProgram()).as("CDEMO-TO-PROGRAM").isEqualTo("COMEN01C");
            assertThat(restored.userId()).as("CDEMO-USER-ID").isEqualTo("ADMIN001");
            assertThat(restored.userType()).as("CDEMO-USER-TYPE").isEqualTo("A");
            assertThat(restored.pgmContext()).as("CDEMO-PGM-CONTEXT").isEqualTo(1);
            assertThat(restored.custId()).as("CDEMO-CUST-ID").isEqualTo(123456789);
            assertThat(restored.custFname()).as("CDEMO-CUST-FNAME").isEqualTo(original.custFname());
            assertThat(restored.custMname()).as("CDEMO-CUST-MNAME").isEqualTo(original.custMname());
            assertThat(restored.custLname()).as("CDEMO-CUST-LNAME").isEqualTo(original.custLname());
            assertThat(restored.acctId()).as("CDEMO-ACCT-ID").isEqualTo(12345678901L);
            assertThat(restored.acctStatus()).as("CDEMO-ACCT-STATUS").isEqualTo("Y");
            assertThat(restored.cardNum()).as("CDEMO-CARD-NUM").isEqualTo(4111111111111111L);
            assertThat(restored.lastMap()).as("CDEMO-LAST-MAP").isEqualTo("COMEN1A");
            assertThat(restored.lastMapset()).as("CDEMO-LAST-MAPSET").isEqualTo("COMEN01");

            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("space-padded values survive the round trip untrimmed")
        void paddedValuesAreNotTrimmed() throws Exception {
            // config/WebConfig deliberately refuses trimming and empty-to-null coercion, and the
            // record itself trims nothing either. That matters because a trimmed value re-serialises
            // to a DIFFERENT set of bytes than it arrived as - the trailing spaces are real storage
            // in a fixed-width record, not decoration.
            ObjectMapper mapper = new ObjectMapper();
            NavigationContext original = NavigationContext.empty()
                    .withUserId(codec().movePicX("USER1", NavigationContext.USER_ID_LENGTH))
                    .withFromProgram(codec().movePicX("ABC", NavigationContext.FROM_PROGRAM_LENGTH));

            NavigationContext restored = mapper.readValue(
                    mapper.writeValueAsString(original), NavigationContext.class);

            assertThat(restored.userId()).isEqualTo("USER1   ").hasSize(8);
            assertThat(restored.fromProgram()).isEqualTo("ABC     ").hasSize(8);
            assertThat(restored.custFname()).as("an all-space field stays all spaces, not null")
                    .isEqualTo(" ".repeat(25));
            assertThat(restored.toFixedWidth(codec()))
                    .as("and the fixed-width image is unchanged by the JSON detour")
                    .isEqualTo(original.toFixedWidth(codec()));
        }

        @Test
        @DisplayName("the JSON payload carries the sixteen copybook fields and no derived condition")
        void payloadCarriesFieldsNotDerivedConditions() throws Exception {
            // The four predicates are derived from CDEMO-USER-TYPE and CDEMO-PGM-CONTEXT rather than
            // stored beside them, so they are @JsonIgnore. Emitting them would put four properties on
            // the wire that the canonical constructor cannot accept back - breaking the round trip
            // outright - and would let a payload assert a role contradicting the byte it travels with.
            ObjectMapper mapper = new ObjectMapper();

            Map<?, ?> asMap = mapper.readValue(
                    mapper.writeValueAsString(populated()), Map.class);

            // The keys are copied into a typed list so the assertion can name them explicitly; the
            // map itself comes back with wildcard generics from Map.class.
            List<String> properties = new ArrayList<>();
            for (Object key : asMap.keySet()) {
                properties.add(String.valueOf(key));
            }

            assertThat(properties)
                    .as("the sixteen elementary items of COCOM01Y, and nothing else")
                    .containsExactlyInAnyOrder("fromTranid", "fromProgram", "toTranid", "toProgram",
                            "userId", "userType", "pgmContext", "custId", "custFname", "custMname",
                            "custLname", "acctId", "acctStatus", "cardNum", "lastMap", "lastMapset")
                    .hasSize(16);
            assertThat(properties)
                    .as("the derived conditions are not wire properties")
                    .doesNotContain("admin", "user", "enter", "reenter");
        }

        @Test
        @DisplayName("an initialised area round-trips through JSON unchanged")
        void initialisedAreaRoundTripsThroughJson() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            NavigationContext initial = NavigationContext.empty();

            NavigationContext restored = mapper.readValue(
                    mapper.writeValueAsString(initial), NavigationContext.class);

            assertThat(restored).isEqualTo(initial);
            assertThat(restored.isEnter()).as("still ENTER after the detour").isTrue();
            assertThat(restored.isAdmin()).isFalse();
            assertThat(restored.isUser()).isFalse();
        }

        @Test
        @DisplayName("the four conditions are recomputed from the payload, not carried in it")
        void conditionsAreRecomputedFromThePayload() throws Exception {
            // Because the predicates are derived, a deserialised instance evaluates them from the two
            // bytes that actually arrived. That is what keeps a role from being asserted independently
            // of the user-type byte it is supposed to summarise.
            ObjectMapper mapper = new ObjectMapper();
            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin().withPgmReenter();

            NavigationContext restored = mapper.readValue(
                    mapper.writeValueAsString(admin), NavigationContext.class);

            assertThat(restored.userType()).isEqualTo("A");
            assertThat(restored.isAdmin()).isTrue();
            assertThat(restored.isUser()).isFalse();
            assertThat(restored.isReenter()).isTrue();
            assertThat(restored.isEnter()).isFalse();
        }
    }

    // =================================================================================================
    // Statelessness - gates G37 and G53, and rule R6.
    //
    // CICS is pseudo-conversational: a transaction paints a screen, ends, and is re-entered from the
    // beginning, and the only state that survives is what the program handed back in its communication
    // area. This type preserves that shape exactly, which is the reason the seventeen controllers can be
    // stateless. These assertions prove that rather than inferring it from the design.
    // =================================================================================================

    @Nested
    @DisplayName("Statelessness - this type exists so that no server-side session does")
    class Statelessness {

        @Test
        @DisplayName("the class declares no non-final static field")
        void declaresNoNonFinalStaticField() {
            // Rule B9 / gate G53. COBOL WORKING-STORAGE must never become a static Java field: a
            // static holder is a session by another name, and it would additionally break request
            // isolation between two concurrent callers.
            List<String> mutableStatics = new ArrayList<>();
            for (Field field : NavigationContext.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                    mutableStatics.add(field.getName());
                }
            }

            assertThat(mutableStatics)
                    .as("a non-final static field in the COMMAREA carrier would be shared state")
                    .isEmpty();
        }

        @Test
        @DisplayName("every instance field is final, so an instance cannot change after construction")
        void everyInstanceFieldIsFinal() {
            // A record's components are final by definition; asserting it guards against the type
            // being changed to a mutable class later, which would let a context already handed to a
            // collaborator change underneath it.
            List<String> mutableInstanceFields = new ArrayList<>();
            for (Field field : NavigationContext.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                    mutableInstanceFields.add(field.getName());
                }
            }

            assertThat(mutableInstanceFields).isEmpty();
            assertThat(NavigationContext.class.isRecord())
                    .as("an immutable record, not a mutable bean")
                    .isTrue();
        }

        @Test
        @DisplayName("no field references a servlet session, a ThreadLocal or any ambient store")
        void noFieldReferencesAnAmbientStore() {
            // Gate G37 and rule R6: conversation state travels in the payload and never becomes
            // server-side state. Asserted structurally here and confirmed by the source-level greps
            // recorded during validation, which find no HttpSession, no ThreadLocal and no
            // non-final static in either this test or NavigationContext itself.
            for (Field field : NavigationContext.class.getDeclaredFields()) {
                String typeName = field.getType().getName();
                assertThat(typeName)
                        .as("field %s must not be an ambient state holder", field.getName())
                        .doesNotContain("ThreadLocal")
                        .doesNotContain("HttpSession")
                        .doesNotContain("HttpServletRequest")
                        .doesNotContain("RequestContext")
                        .doesNotContain("SessionScope");
            }
        }

        @Test
        @DisplayName("two independently built instances share no state")
        void twoInstancesShareNoState() {
            // The concrete test for request isolation: two callers, each with their own context.
            //
            // Note that the stored value is the RAW one, unpadded: a shorter value is accepted as
            // given and is padded only when the image is rendered, because padding is a property of
            // the fixed-width representation rather than of the value. The padded form is asserted in
            // PaddingAndFilling.paddingSurvivesTheRoundTripUntrimmed.
            NavigationContext first = NavigationContext.empty().withUserId("USER1");
            NavigationContext second = NavigationContext.empty().withUserId("USER2");

            assertThat(first.userId()).as("stored raw, not padded").isEqualTo("USER1");
            assertThat(second.userId()).isEqualTo("USER2");
            assertThat(first).isNotEqualTo(second);

            // Each renders its own independent 160-byte image, padded at that point and not before.
            assertThat(spanOf(first.toFixedWidth(codec()), 24, 8)).isEqualTo("USER1   ");
            assertThat(spanOf(second.toFixedWidth(codec()), 24, 8)).isEqualTo("USER2   ");
        }

        @Test
        @DisplayName("modifying a context leaves every other reference to the original untouched")
        void modifyingOneContextLeavesTheOriginalUntouched() {
            // The type is immutable, so "modification" produces a new instance. A collaborator
            // holding the original therefore cannot observe another request's change - which is
            // exactly the isolation a static holder or a mutable bean would destroy.
            NavigationContext original = NavigationContext.empty()
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin()
                    .withPgmEnter();
            NavigationContext handedToACollaborator = original;

            NavigationContext modified = original
                    .withUserId("USER0001")
                    .withUserTypeUser()
                    .withPgmReenter();

            assertThat(handedToACollaborator)
                    .as("the reference a collaborator already holds is unchanged")
                    .isSameAs(original);
            assertThat(original.userId()).isEqualTo("ADMIN001");
            assertThat(original.isAdmin()).isTrue();
            assertThat(original.isEnter()).isTrue();

            assertThat(modified.userId()).isEqualTo("USER0001");
            assertThat(modified.isUser()).isTrue();
            assertThat(modified.isReenter()).isTrue();
            assertThat(modified).isNotSameAs(original).isNotEqualTo(original);
        }

        @Test
        @DisplayName("the next program to run is readable from the payload with no server-side lookup")
        void nextProgramIsCarriedByThePayload() throws Exception {
            // Gate G40. The eight EXEC CICS XCTL sites are resolved by the CLIENT, not by a
            // server-side forward, so no redirect chain and no session affinity is introduced. The
            // three shapes, per AAP 0.4.11:
            //
            //   1. Hard-coded role routes - app/cbl/COSGN00C.cbl:232 PROGRAM('COADM01C') and :237
            //      PROGRAM('COMEN01C') - become a role on the sign-on response.
            //   2. COMMAREA-driven - COACTUPC:957, COACTVWC:350, COCRDSLC:332 and COCRDUPC:474, all
            //      XCTL PROGRAM(CDEMO-TO-PROGRAM) - become the toProgram field echoed below.
            //   3. Literal and field mixed - COCRDLIC:403 PROGRAM(LIT-MENUPGM) and COCRDLIC:539 and
            //      :567 PROGRAM(CCARD-NEXT-PROG).
            //
            // In every shape the target is DATA in the payload. This test proves it by reading the
            // target back out of a serialised-and-deserialised instance: nothing else is consulted.
            ObjectMapper mapper = new ObjectMapper();
            NavigationContext transferring = NavigationContext.empty()
                    .withFromTranid("CCLI")
                    .withFromProgram("COCRDLIC")
                    .withToTranid("CCUP")
                    .withToProgram("COCRDUPC")
                    .withLastMap("COCRDLA")
                    .withLastMapset("COCRDLI");

            NavigationContext overTheWire = mapper.readValue(
                    mapper.writeValueAsString(transferring), NavigationContext.class);
            NavigationContext overTheRecord = NavigationContext.fromFixedWidth(
                    codec(), transferring.toFixedWidth(codec()));

            for (NavigationContext carried : List.of(overTheWire, overTheRecord)) {
                assertThat(carried.toProgram())
                        .as("the XCTL target travels as data, not as a server-side forward")
                        .isEqualTo("COCRDUPC");
                assertThat(carried.toTranid()).isEqualTo("CCUP");
                assertThat(carried.lastMap()).as("the screen being left").isEqualTo("COCRDLA");
                assertThat(carried.lastMapset()).isEqualTo("COCRDLI");
                assertThat(carried.fromProgram()).as("and where it came from").isEqualTo("COCRDLIC");
                assertThat(carried.fromTranid()).isEqualTo("CCLI");
            }
        }

        @Test
        @DisplayName("the shared LAYOUT constant is immutable, so publishing it adds no shared state")
        void theSharedLayoutIsImmutable() {
            // LAYOUT is public because the parity harness and the field differ need the geometry. It
            // is a record whose span list is defensively copied, so exposing it introduces no mutable
            // static state - the one static that a G53 audit would otherwise have to justify.
            List<FieldSpan> spans = NavigationContext.LAYOUT.storageSpans();

            assertThat(spans).hasSize(16);
            assertThat(spans)
                    .as("no caller can alter the layout every parity comparison depends on")
                    .isUnmodifiable();
            assertThat(NavigationContext.LAYOUT.storageSpans())
                    .as("and a second read returns the same sixteen spans")
                    .hasSize(16)
                    .containsExactlyElementsOf(spans);
            assertThat(NavigationContext.LAYOUT.recordLength())
                    .as("the shared record width is a compile-time constant, not mutable state")
                    .isEqualTo(DECLARED_COMMAREA_LENGTH);
        }
    }

    // =================================================================================================
    // The construction guards. Every one is driven so that no branch of the validation is unreached,
    // which is what earns the branch coverage the module gate requires (G49).
    // =================================================================================================

    @Nested
    @DisplayName("Construction guards - a value that cannot be stored is refused, not shortened")
    class GuardSweep {

        @ParameterizedTest(name = "a null {0} is refused")
        @ValueSource(strings = {"fromTranid", "fromProgram", "toTranid", "toProgram", "userId",
            "userType", "custFname", "custMname", "custLname", "acctStatus", "lastMap", "lastMapset"})
        @DisplayName("a null character field is refused: there is no null in a COBOL record")
        void nullCharacterFieldIsRefused(String component) {
            // An unset PIC X field holds spaces, which is what empty() produces. Accepting null would
            // let a record exist that has no byte representation at all.
            assertThatNullPointerException()
                    .as("component %s", component)
                    .isThrownBy(() -> withNullComponent(component));
        }

        /**
         * Builds a context with exactly one character component set to {@code null}, so each guard is
         * reached individually rather than all of them sharing one assertion.
         *
         * @param component the record component to nullify
         * @return never returns normally; the canonical constructor rejects the null
         */
        private NavigationContext withNullComponent(String component) {
            return new NavigationContext("fromTranid".equals(component) ? null : "CC00",
                    "fromProgram".equals(component) ? null : "COSGN00C",
                    "toTranid".equals(component) ? null : "CM00",
                    "toProgram".equals(component) ? null : "COMEN01C",
                    "userId".equals(component) ? null : "ADMIN001",
                    "userType".equals(component) ? null : "A",
                    0,
                    0,
                    "custFname".equals(component) ? null : "F",
                    "custMname".equals(component) ? null : "M",
                    "custLname".equals(component) ? null : "L",
                    0L,
                    "acctStatus".equals(component) ? null : "Y",
                    0L,
                    "lastMap".equals(component) ? null : "COMEN1A",
                    "lastMapset".equals(component) ? null : "COMEN01");
        }

        @ParameterizedTest(name = "{0} refuses a value of {1} characters when it is declared {2}")
        @CsvSource({
            "CDEMO-FROM-TRANID,   5,  4",
            "CDEMO-FROM-PROGRAM,  9,  8",
            "CDEMO-TO-TRANID,     5,  4",
            "CDEMO-TO-PROGRAM,    9,  8",
            "CDEMO-USER-ID,       9,  8",
            "CDEMO-USER-TYPE,     2,  1",
            "CDEMO-CUST-FNAME,   26, 25",
            "CDEMO-CUST-MNAME,   26, 25",
            "CDEMO-CUST-LNAME,   26, 25",
            "CDEMO-ACCT-STATUS,   2,  1",
            "CDEMO-LAST-MAP,      8,  7",
            "CDEMO-LAST-MAPSET,   8,  7"
        })
        @DisplayName("an over-long character value is refused rather than quietly shortened")
        void overLongCharacterValueIsRefused(String cobolName, int suppliedLength, int declaredWidth) {
            // Refusing keeps toFixedWidth a LOSSLESS projection: what goes in comes out. A caller who
            // genuinely wants COBOL's MOVE truncation asks for it through movePicX, at the call site,
            // so the direction of the loss is visible. Note the last two rows: an eight-character
            // value in a seven-character map field is precisely the X(8) mistake.
            String tooLong = "X".repeat(suppliedLength);

            assertThatIllegalArgumentException()
                    .as("%s is PIC X(%d)", cobolName, declaredWidth)
                    .isThrownBy(() -> withCharacterComponent(cobolName, tooLong))
                    .withMessageContaining(cobolName)
                    .withMessageContaining("PIC X(" + declaredWidth + ")")
                    .withMessageContaining(NavigationContext.REDACTED);
        }

        @ParameterizedTest(name = "{0} accepts a value shorter than its declared width")
        @CsvSource({
            "CDEMO-FROM-TRANID,   1",
            "CDEMO-FROM-PROGRAM,  3",
            "CDEMO-CUST-FNAME,    5",
            "CDEMO-LAST-MAP,      2",
            "CDEMO-LAST-MAPSET,   2"
        })
        @DisplayName("a shorter character value is accepted, and padded when the image is rendered")
        void shorterCharacterValueIsAccepted(String cobolName, int suppliedLength) {
            String shorter = "X".repeat(suppliedLength);

            NavigationContext context = withCharacterComponent(cobolName, shorter);

            assertThat(context.toFixedWidth(codec()))
                    .as("the image is still the full declared width")
                    .hasSize(DECLARED_COMMAREA_LENGTH);
        }

        /**
         * Builds a context with exactly one character component set to the supplied value, so each
         * width guard is reached on its own field.
         *
         * @param cobolName the copybook field name to set
         * @param value     the value to place in it
         * @return the context, if the value is acceptable for that field
         */
        private NavigationContext withCharacterComponent(String cobolName, String value) {
            NavigationContext base = NavigationContext.empty();
            return switch (cobolName) {
                case "CDEMO-FROM-TRANID" -> base.withFromTranid(value);
                case "CDEMO-FROM-PROGRAM" -> base.withFromProgram(value);
                case "CDEMO-TO-TRANID" -> base.withToTranid(value);
                case "CDEMO-TO-PROGRAM" -> base.withToProgram(value);
                case "CDEMO-USER-ID" -> base.withUserId(value);
                case "CDEMO-USER-TYPE" -> base.withUserType(value);
                case "CDEMO-CUST-FNAME" -> base.withCustFname(value);
                case "CDEMO-CUST-MNAME" -> base.withCustMname(value);
                case "CDEMO-CUST-LNAME" -> base.withCustLname(value);
                case "CDEMO-ACCT-STATUS" -> base.withAcctStatus(value);
                case "CDEMO-LAST-MAP" -> base.withLastMap(value);
                case "CDEMO-LAST-MAPSET" -> base.withLastMapset(value);
                default -> throw new IllegalArgumentException(
                        "The test names a field the copybook does not declare: " + cobolName);
            };
        }

        @Test
        @DisplayName("a negative numeric value is refused: PIC 9 has no sign position")
        void negativeNumericValueIsRefused() {
            // PIC 9(n) is an UNSIGNED picture with no sign position at all, so a negative value has
            // no representation in it. Every one of the four numeric fields is driven.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withPgmContext(-1))
                    .withMessageContaining("CDEMO-PGM-CONTEXT");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withCustId(-1))
                    .withMessageContaining("CDEMO-CUST-ID");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withAcctId(-1L))
                    .withMessageContaining("CDEMO-ACCT-ID");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.empty().withCardNum(-1L))
                    .withMessageContaining("CDEMO-CARD-NUM");
        }

        @Test
        @DisplayName("a numeric value needing more digits than declared is refused")
        void overWideNumericValueIsRefused() {
            // Refused rather than losing its HIGH-ORDER digits the way a numeric MOVE would: a
            // truncated identifier still looks entirely plausible and is correspondingly hard to
            // trace. A seventeen-digit card number stored into PIC 9(16) would come back missing its
            // leading digit and would identify a different card.
            assertThatIllegalArgumentException()
                    .as("CDEMO-PGM-CONTEXT is PIC 9(01), so 10 does not fit")
                    .isThrownBy(() -> NavigationContext.empty().withPgmContext(10))
                    .withMessageContaining("CDEMO-PGM-CONTEXT")
                    .withMessageContaining("PIC 9(1)");
            assertThatIllegalArgumentException()
                    .as("CDEMO-CUST-ID is PIC 9(09), so a tenth digit does not fit")
                    .isThrownBy(() -> NavigationContext.empty().withCustId(1_000_000_000))
                    .withMessageContaining("CDEMO-CUST-ID");
            assertThatIllegalArgumentException()
                    .as("CDEMO-ACCT-ID is PIC 9(11), so a twelfth digit does not fit")
                    .isThrownBy(() -> NavigationContext.empty().withAcctId(100_000_000_000L))
                    .withMessageContaining("CDEMO-ACCT-ID");
            assertThatIllegalArgumentException()
                    .as("CDEMO-CARD-NUM is PIC 9(16), so a seventeenth digit does not fit")
                    .isThrownBy(() -> NavigationContext.empty().withCardNum(10_000_000_000_000_000L))
                    .withMessageContaining("CDEMO-CARD-NUM");
        }

        @ParameterizedTest(name = "the widest value each numeric field can hold is accepted: {0}")
        @CsvSource({
            "CDEMO-PGM-CONTEXT, 9",
            "CDEMO-CUST-ID,     999999999",
            "CDEMO-ACCT-ID,     99999999999",
            "CDEMO-CARD-NUM,    9999999999999999"
        })
        @DisplayName("the boundary value of each numeric field is accepted and renders at full width")
        void widestNumericValueIsAccepted(String cobolName, long widest) {
            // The just-fits side of every digit-count guard, so both outcomes of each are driven.
            NavigationContext context = switch (cobolName) {
                case "CDEMO-PGM-CONTEXT" -> NavigationContext.empty().withPgmContext((int) widest);
                case "CDEMO-CUST-ID" -> NavigationContext.empty().withCustId((int) widest);
                case "CDEMO-ACCT-ID" -> NavigationContext.empty().withAcctId(widest);
                case "CDEMO-CARD-NUM" -> NavigationContext.empty().withCardNum(widest);
                default -> throw new IllegalArgumentException(
                        "The test names a field the copybook does not declare: " + cobolName);
            };

            byte[] image = context.toFixedWidth(codec());
            FieldSpan span = NavigationContext.LAYOUT.span(cobolName);

            assertThat(image).hasSize(DECLARED_COMMAREA_LENGTH);
            assertThat(spanOf(image, span.offset(), span.length()))
                    .as("%s at its widest", cobolName)
                    .isEqualTo(Long.toString(widest));
        }

        @Test
        @DisplayName("zero is accepted in every numeric field, being the initialised state")
        void zeroIsAcceptedInEveryNumericField() {
            NavigationContext context = NavigationContext.empty()
                    .withPgmContext(0)
                    .withCustId(0)
                    .withAcctId(0L)
                    .withCardNum(0L);

            assertThat(context.pgmContext()).isZero();
            assertThat(context.custId()).isZero();
            assertThat(context.acctId()).isZero();
            assertThat(context.cardNum()).isZero();
            assertThat(context.toFixedWidth(codec())).hasSize(DECLARED_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("an empty character value is accepted, being SPACES moved into the field")
        void emptyCharacterValueIsAccepted() {
            // Zero characters is shorter than the declared width, not longer, so it is accepted and
            // padded - the MOVE SPACES shape.
            NavigationContext context = NavigationContext.empty()
                    .withFromProgram("")
                    .withLastMap("");

            byte[] image = context.toFixedWidth(codec());

            assertThat(spanOf(image, 4, 8)).isEqualTo(" ".repeat(8));
            assertThat(spanOf(image, 146, 7)).isEqualTo(" ".repeat(7));
        }

        @Test
        @DisplayName("a non-digit in a numeric span is refused when an image is read back")
        void nonDigitInANumericSpanIsRefused() {
            // A non-digit in a numeric span is a genuine data or alignment defect, so it fails where
            // the offending field can be named rather than becoming a plausible-looking zero. Built
            // by corrupting one byte of a valid image, so the failure is unambiguous.
            byte[] image = populated().toFixedWidth(codec());
            FieldSpan custId = NavigationContext.LAYOUT.span("CDEMO-CUST-ID");
            image[custId.offset()] = (byte) 'X';

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(codec(), image));
        }

        @Test
        @DisplayName("a space in a numeric span is refused too, not read as zero")
        void spaceInANumericSpanIsRefused() {
            // The specific corruption most likely to appear in real data: a numeric field left blank
            // rather than zero-filled. Reading it as zero would be a silent data change.
            byte[] image = populated().toFixedWidth(codec());
            FieldSpan cardNum = NavigationContext.LAYOUT.span("CDEMO-CARD-NUM");
            for (int index = 0; index < cardNum.length(); index++) {
                image[cardNum.offset() + index] = (byte) ' ';
            }

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.fromFixedWidth(codec(), image));
        }

        @ParameterizedTest(name = "a card-number image containing [{0}] is refused")
        @ValueSource(strings = {"411111111111111X", "411111111111111:", "411111111111111 ",
            "411111111111111-", "411111111111111/", "41111111111111.1", "4111111111111+11"})
        @DisplayName("the digit-range guard refuses a character on either side of '0' through '9'")
        void cardNumberImageRefusesNonDigitsOnBothSidesOfTheRange(String image) {
            // The guard is `if (character < '0' || character > '9')`, a compound condition whose two
            // halves are reached by different characters. The cases above straddle the range
            // deliberately:
            //
            //   ABOVE '9' (0x39): 'X' (0x58), ':' (0x3A)
            //   BELOW '0' (0x30): space (0x20), '-' (0x2D), '/' (0x2F), '.' (0x2E), '+' (0x2B)
            //
            // Both halves matter. A separator such as '-' is below the range, so a test using only
            // "4111-1111-1111-1111" - which is what the JSON wire-form suite exercises - short-
            // circuits on the first half and never evaluates the second. Only a character ABOVE '9'
            // reaches it. Driving both is what takes this guard, and with it the class, clear of the
            // module's branch-coverage floor rather than sitting exactly on it.
            //
            // Every one of these is a plausible real-world input: a formatted card number, a blank
            // field, a decimal point from a mis-typed numeric conversion, or a sign character.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.cardNumberOfImage(image))
                    .withMessageContaining(NavigationContext.CARD_NUM_FIELD)
                    .withMessageContaining("only the digits 0 to 9")
                    .withMessageContaining(NavigationContext.REDACTED);
        }

        @Test
        @DisplayName("a well-formed sixteen-digit image is accepted, the guard's passing path")
        void wellFormedCardNumberImageIsAccepted() {
            // The other side of the same guard: every character inside the range, so the loop runs to
            // completion and the value is returned. Without this the refusal cases above would leave
            // the guard's false outcome unproven.
            assertThat(NavigationContext.cardNumberOfImage("4111111111111111"))
                    .isEqualTo(4111111111111111L);
            assertThat(NavigationContext.cardNumberOfImage("0000000000000000")).isZero();
            assertThat(NavigationContext.cardNumberOfImage("9999999999999999"))
                    .as("the boundary characters '0' and '9' are themselves valid")
                    .isEqualTo(9999999999999999L);
        }

        @Test
        @DisplayName("the layout refuses a field name it does not declare")
        void layoutRefusesAnUndeclaredFieldName() {
            // The negative branch of span(): names are case-sensitive, exactly as the copybook writes
            // them. UNUSED1Y is the copybook with zero consumers, so it names nothing here.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> NavigationContext.LAYOUT.span("CDEMO-LAST-MAPSETS"));
            assertThat(NavigationContext.LAYOUT.hasSpan("CDEMO-LAST-MAPSET")).isTrue();
            assertThat(NavigationContext.LAYOUT.hasSpan("cdemo-last-mapset"))
                    .as("names are case-sensitive")
                    .isFalse();
        }
    }
}
