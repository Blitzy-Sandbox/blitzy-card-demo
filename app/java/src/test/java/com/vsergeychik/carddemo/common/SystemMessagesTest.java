package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link SystemMessages}, the Java form of two COBOL copybooks that the migration
 * deliberately merges into one class:
 *
 * <ul>
 *   <li>{@code app/cpy/CSMSG01Y.cpy} lines 17-21 - group item {@code CCDA-COMMON-MESSAGES}, two
 *       {@code PIC X(50)} message fields copied by all 17 CICS online programs.</li>
 *   <li>{@code app/cpy/CSMSG02Y.cpy} lines 21-29 - group item {@code ABEND-DATA}, four
 *       {@code VALUE SPACES} fields totalling 134 bytes.</li>
 * </ul>
 *
 * <h2>What these tests are protecting</h2>
 * A COBOL {@code VALUE} clause becomes a Java constant, and the migration requires that literal
 * text match byte-for-byte. These messages are moved onto fixed-width screen fields, so a message
 * that is one character short is a parity defect even though it reads perfectly: every field after
 * it on the wire shifts, and the field-for-field differ reports it as a diff. The abend work area
 * is subject to the same rule at four widths at once.
 *
 * <h2>THE CENTRAL TRAP - a 49-character literal in a 50-byte field</h2>
 * Both {@code CSMSG01Y} fields are declared {@code PIC X(50)}, but each source {@code VALUE}
 * literal measures exactly <strong>49</strong> characters. COBOL right-pads a short alphanumeric
 * {@code VALUE} literal with spaces up to the declared width, so the field actually holds
 * <strong>50</strong> characters. A Java constant transcribed verbatim from the copybook is
 * therefore 49 characters and <em>wrong</em> - wrong by one invisible trailing space, which no
 * reviewer will ever catch by eye.
 *
 * <p>Every expectation for those two constants is consequently built here as
 * {@code <transcribed 49-character literal> + " "}, never pasted in as an opaque 50-character
 * string. The concatenation is the documentation: it shows the reader both numbers and the single
 * space that reconciles them. {@link #sourceLiteralsMeasureFortyNineCharacters()} then asserts the
 * transcription really is 49 characters, so a mangled transcription fails with a message that names
 * the actual problem instead of surfacing later as a puzzling inequality.
 *
 * <p>The corollary matters just as much: <strong>nothing here trims</strong> in order to make an
 * assertion pass. Where a test does call {@link String#trim()} it is to pin the visible text
 * <em>separately from</em> the padding, so that a failure distinguishes "the wording changed" from
 * "the width changed". The 50-byte width is the contract.
 *
 * <h2>How the expectations were derived</h2>
 * The legacy programs cannot be executed in this environment, so no captured COBOL output exists to
 * compare against. Every expected value below is instead derived statically from the copybook
 * source and carries a {@code source: app/cpy/...} provenance comment naming the file and lines it
 * came from. The copybooks are read at authoring time only: this test performs no file I/O of any
 * kind, and no path under {@code app/} appears anywhere in it except inside a comment.
 *
 * <h2>Scope</h2>
 * A pure unit test - no Spring context, no Mockito, no fixtures. It exercises exactly one class.
 * In particular it does <strong>not</strong> reference {@code ScreenTitles}, even though the
 * "do not conflate" group below is entirely about the difference between the two; that comparison
 * is made against transcribed copybook facts rather than against the sibling class, which keeps the
 * two suites independently diagnosable.
 *
 * <p>Every guard clause in the class under test is driven from both sides, because the build
 * enforces at least 90% branch coverage independently for each package.
 */
@DisplayName("SystemMessages - CSMSG01Y common messages and the CSMSG02Y abend work area")
class SystemMessagesTest {

    /**
     * The {@code CCDA-MSG-THANK-YOU} {@code VALUE} literal exactly as it appears between the quotes
     * in the copybook: 43 characters of text followed by 6 spaces, measuring
     * {@value #SOURCE_LITERAL_LENGTH} characters in total.
     *
     * <p>source: app/cpy/CSMSG01Y.cpy:L18-L19 -
     * {@code 05 CCDA-MSG-THANK-YOU PIC X(50) VALUE 'Thank you for using CardDemo application...      '.}
     *
     * <p>Written as two concatenated pieces so the trailing spaces are countable rather than
     * trailing invisibly off the end of a long literal.
     */
    private static final String THANK_YOU_SOURCE_LITERAL =
            "Thank you for using CardDemo application..." + "      ";

    /**
     * The {@code CCDA-MSG-INVALID-KEY} {@code VALUE} literal exactly as it appears between the
     * quotes in the copybook: 40 characters of text followed by 9 spaces, measuring
     * {@value #SOURCE_LITERAL_LENGTH} characters in total.
     *
     * <p>source: app/cpy/CSMSG01Y.cpy:L20-L21 -
     * {@code 05 CCDA-MSG-INVALID-KEY PIC X(50) VALUE 'Invalid key pressed. Please see below...         '.}
     */
    private static final String INVALID_KEY_SOURCE_LITERAL =
            "Invalid key pressed. Please see below..." + "         ";

    /**
     * Length of both source {@code VALUE} literals: <strong>49</strong>.
     *
     * <p>This is deliberately <em>not</em> the declared field width. It is one less, and that
     * one-character difference is the whole point of this test class.
     */
    private static final int SOURCE_LITERAL_LENGTH = 49;

    /**
     * Declared width of both {@code CCDA-COMMON-MESSAGES} fields: {@code PIC X(50)}
     * [source: app/cpy/CSMSG01Y.cpy:L18 and :L20]. Transcribed independently of
     * {@link SystemMessages#MESSAGE_LENGTH} so that the constant can be asserted against the
     * copybook rather than against itself.
     */
    private static final int DECLARED_MESSAGE_WIDTH = 50;

    /**
     * The value {@code CCDA-MSG-THANK-YOU} actually holds: the 49-character source literal plus the
     * <strong>one</strong> space COBOL supplies implicitly to fill {@code PIC X(50)}. 49 + 1 = 50.
     */
    private static final String EXPECTED_THANK_YOU = THANK_YOU_SOURCE_LITERAL + " ";

    /**
     * The value {@code CCDA-MSG-INVALID-KEY} actually holds: the 49-character source literal plus
     * the <strong>one</strong> space COBOL supplies implicitly to fill {@code PIC X(50)}.
     */
    private static final String EXPECTED_INVALID_KEY = INVALID_KEY_SOURCE_LITERAL + " ";

    /** The visible wording of the thank-you message, with the fixed-width padding removed. */
    private static final String THANK_YOU_VISIBLE_TEXT =
            "Thank you for using CardDemo application...";

    /** The visible wording of the invalid-key message, with the fixed-width padding removed. */
    private static final String INVALID_KEY_VISIBLE_TEXT =
            "Invalid key pressed. Please see below...";

    /**
     * The wording of the <em>screen-title</em> thank-you string, which lives in a different
     * copybook, has a different width and must never be substituted for this class's message.
     *
     * <p>source: app/cpy/COTTL01Y.cpy - {@code 05 CCDA-THANK-YOU PIC X(40) VALUE 'Thank you for
     * using CCDA application... '}. Transcribed here as a string to assert against; the owning Java
     * class is intentionally not referenced.
     */
    private static final String SCREEN_TITLE_THANK_YOU_WORDING = "CCDA application";

    /** Declared width of the screen-title thank-you field: {@code PIC X(40)}, not 50. */
    private static final int SCREEN_TITLE_WIDTH = 40;

    /** {@code ABEND-CODE PIC X(4)} [source: app/cpy/CSMSG02Y.cpy:L22-L23]. */
    private static final int CODE_WIDTH = 4;

    /** {@code ABEND-CULPRIT PIC X(8)} [source: app/cpy/CSMSG02Y.cpy:L24-L25]. */
    private static final int CULPRIT_WIDTH = 8;

    /** {@code ABEND-REASON PIC X(50)} [source: app/cpy/CSMSG02Y.cpy:L26-L27]. */
    private static final int REASON_WIDTH = 50;

    /** {@code ABEND-MSG PIC X(72)} [source: app/cpy/CSMSG02Y.cpy:L28-L29]. */
    private static final int MSG_WIDTH = 72;

    /**
     * Total width of the {@code ABEND-DATA} group item, written as the sum of its four components
     * so the arithmetic is visible in the source: 4 + 8 + 50 + 72 = <strong>134</strong>
     * [source: app/cpy/CSMSG02Y.cpy:L21-L29].
     */
    private static final int EXPECTED_ABEND_DATA_WIDTH =
            CODE_WIDTH + CULPRIT_WIDTH + REASON_WIDTH + MSG_WIDTH;

    /** Offset of {@code ABEND-CODE} in the serialised group item. */
    private static final int CODE_OFFSET = 0;

    /** Offset of {@code ABEND-CULPRIT}: 0 + 4. */
    private static final int CULPRIT_OFFSET = CODE_OFFSET + CODE_WIDTH;

    /** Offset of {@code ABEND-REASON}: 0 + 4 + 8. */
    private static final int REASON_OFFSET = CULPRIT_OFFSET + CULPRIT_WIDTH;

    /** Offset of {@code ABEND-MSG}: 0 + 4 + 8 + 50. */
    private static final int MSG_OFFSET = REASON_OFFSET + REASON_WIDTH;

    /** A four-character abend code, exactly filling {@code PIC X(4)}. */
    private static final String SAMPLE_CODE = "0001";

    /** An eight-character program name, exactly filling {@code PIC X(8)}. */
    private static final String SAMPLE_CULPRIT = "COACTVWC";

    /** A reason shorter than its 50-character field, so the padding path is exercised. */
    private static final String SAMPLE_REASON = "Account record not found";

    /** A message shorter than its 72-character field, so the padding path is exercised. */
    private static final String SAMPLE_MSG = "Unexpected file status returned by ACCTDAT";

    /**
     * The two {@code CCDA-COMMON-MESSAGES} fields, each paired with its COBOL name so a failure
     * report names the copybook field rather than an index.
     *
     * <p>Package-private and {@code static} as {@code @MethodSource} requires. It builds a fresh
     * stream on every call and closes over nothing, so it introduces no shared state.
     *
     * @return one argument pair per common message
     */
    static Stream<Arguments> commonMessages() {
        return Stream.of(
                Arguments.of("CCDA-MSG-THANK-YOU", SystemMessages.CCDA_MSG_THANK_YOU),
                Arguments.of("CCDA-MSG-INVALID-KEY", SystemMessages.CCDA_MSG_INVALID_KEY));
    }

    /**
     * The four {@code ABEND-DATA} fields as (COBOL name, declared width) pairs, transcribed from
     * the copybook.
     *
     * @return one argument pair per abend field, in copybook declaration order
     */
    static Stream<Arguments> abendFields() {
        return Stream.of(
                Arguments.of("ABEND-CODE", CODE_WIDTH),
                Arguments.of("ABEND-CULPRIT", CULPRIT_WIDTH),
                Arguments.of("ABEND-REASON", REASON_WIDTH),
                Arguments.of("ABEND-MSG", MSG_WIDTH));
    }

    /**
     * The serialised byte image of an abend work area: the four fields at their declared widths,
     * concatenated in copybook declaration order.
     *
     * <p>{@link SystemMessages.AbendData} deliberately exposes no serialiser of its own - it models
     * a {@code WORKING-STORAGE} work area, which is never persisted - so the image is composed here
     * from the record's accessors. That is the point of the exercise: it proves the four components
     * really do lay out to {@value #EXPECTED_ABEND_DATA_WIDTH} bytes in the declared order, using
     * only what the class guarantees.
     *
     * @param area the work area to render, in any state
     * @return the {@value #EXPECTED_ABEND_DATA_WIDTH}-character image
     */
    private static String serialise(SystemMessages.AbendData area) {
        SystemMessages.AbendData canonical = area.toDeclaredWidths();
        return canonical.abendCode()
                + canonical.abendCulprit()
                + canonical.abendReason()
                + canonical.abendMsg();
    }

    /**
     * A work area populated with the four sample values, none of which is null and two of which are
     * shorter than their declared field.
     *
     * @return a freshly constructed area; the record is immutable, so callers cannot disturb it
     */
    private static SystemMessages.AbendData sampleArea() {
        return new SystemMessages.AbendData(SAMPLE_CODE, SAMPLE_CULPRIT, SAMPLE_REASON, SAMPLE_MSG);
    }

    @Test
    @DisplayName("the transcribed source literals measure 49 characters, one short of PIC X(50)")
    void sourceLiteralsMeasureFortyNineCharacters() {
        // This is the accuracy check for the transcription itself. If someone edits a literal above
        // and drops or adds a space, this fails first and says so plainly, instead of the failure
        // surfacing as an opaque string inequality further down.
        assertThat(THANK_YOU_SOURCE_LITERAL).hasSize(SOURCE_LITERAL_LENGTH);
        assertThat(INVALID_KEY_SOURCE_LITERAL).hasSize(SOURCE_LITERAL_LENGTH);

        // 49 and 50 are two different, independently true facts: the literal length and the
        // declared field width. Exactly one space of COBOL padding reconciles them.
        assertThat(SOURCE_LITERAL_LENGTH).isEqualTo(DECLARED_MESSAGE_WIDTH - 1);
        assertThat(EXPECTED_THANK_YOU).hasSize(DECLARED_MESSAGE_WIDTH);
        assertThat(EXPECTED_INVALID_KEY).hasSize(DECLARED_MESSAGE_WIDTH);
    }

    @ParameterizedTest(name = "{0} is exactly MESSAGE_LENGTH characters")
    @MethodSource("commonMessages")
    @DisplayName("every common message is exactly MESSAGE_LENGTH characters wide")
    void everyCommonMessageIsDeclaredWidth(String cobolName, String message) {
        // The group invariant, asserted over the whole group rather than message by message, so a
        // third message added to CSMSG01Y later cannot quietly break the rule.
        // source: app/cpy/CSMSG01Y.cpy:L17-L21 - the group item and both of its PIC X(50) items.
        assertThat(message)
                .as("CSMSG01Y declares %s as PIC X(50)", cobolName)
                .hasSize(SystemMessages.MESSAGE_LENGTH);
    }

    @ParameterizedTest(name = "{0} keeps its fixed-width padding")
    @MethodSource("commonMessages")
    @DisplayName("every common message is space-padded, not trimmed")
    void everyCommonMessageKeepsItsPadding(String cobolName, String message) {
        assertThat(message)
                .as("%s must retain the trailing spaces COBOL moves onto the screen", cobolName)
                .endsWith(" ")
                .isNotEqualTo(message.trim())
                .isNotBlank();
    }

    @Nested
    @DisplayName("CCDA-COMMON-MESSAGES - the two PIC X(50) message fields")
    class CommonMessages {

        @Test
        @DisplayName("MESSAGE_LENGTH is 50, the declared PIC X(50) width")
        void messageLengthIsFifty() {
            // source: app/cpy/CSMSG01Y.cpy:L18 and :L20 - both items declare PIC X(50).
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(DECLARED_MESSAGE_WIDTH);
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
        }

        @Test
        @DisplayName("MESSAGE_LENGTH is the field width, not the 49-character literal length")
        void messageLengthIsNotTheLiteralLength() {
            // The single most plausible mistake in this class is to take 49 - the length of the
            // text actually typed into the copybook - as the width. It is not.
            assertThat(SystemMessages.MESSAGE_LENGTH).isNotEqualTo(SOURCE_LITERAL_LENGTH);
        }

        @Test
        @DisplayName("CCDA-MSG-THANK-YOU is 50 characters long")
        void thankYouIsFiftyCharacters() {
            // Asserted on its own, separately from the value, so that a width regression is
            // reported as a width regression rather than as a wall of near-identical text.
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(50);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.length())
                    .isEqualTo(SystemMessages.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("CCDA-MSG-THANK-YOU is the 49-character source literal plus one pad space")
        void thankYouIsSourceLiteralPlusOnePadSpace() {
            // source: app/cpy/CSMSG01Y.cpy:L18-L19. The source literal is 49 characters; PIC X(50)
            // right-pads it by exactly one. Expressed as a concatenation rather than as an opaque
            // 50-character string, so the reader can see where the fiftieth character comes from.
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isEqualTo(THANK_YOU_SOURCE_LITERAL + " ")
                    .isEqualTo(EXPECTED_THANK_YOU);
        }

        @Test
        @DisplayName("CCDA-MSG-THANK-YOU keeps all seven trailing spaces: six typed plus one padded")
        void thankYouKeepsItsTrailingSpaces() {
            String message = SystemMessages.CCDA_MSG_THANK_YOU;

            assertThat(message).endsWith(" ");
            // Trimming must change it - that is the proof the padding survived translation.
            assertThat(message).isNotEqualTo(message.trim());
            // Six spaces typed inside the quotes plus one supplied by COBOL to fill the field.
            assertThat(message.length() - message.stripTrailing().length()).isEqualTo(7);
        }

        @Test
        @DisplayName("CCDA-MSG-THANK-YOU's visible text is pinned independently of its padding")
        void thankYouVisibleText() {
            // Pinning the wording separately from the width means one assertion tells you which of
            // the two things broke.
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.trim()).isEqualTo(THANK_YOU_VISIBLE_TEXT);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.trim())
                    .isEqualTo("Thank you for using CardDemo application...")
                    .hasSize(43);
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY is 50 characters long")
        void invalidKeyIsFiftyCharacters() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(50);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.length())
                    .isEqualTo(SystemMessages.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY is the 49-character source literal plus one pad space")
        void invalidKeyIsSourceLiteralPlusOnePadSpace() {
            // source: app/cpy/CSMSG01Y.cpy:L20-L21. Same 49-plus-1 construction as the thank-you
            // message, spelled out rather than folded into a single opaque literal.
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .isEqualTo(INVALID_KEY_SOURCE_LITERAL + " ")
                    .isEqualTo(EXPECTED_INVALID_KEY);
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY keeps all ten trailing spaces: nine typed plus one padded")
        void invalidKeyKeepsItsTrailingSpaces() {
            String message = SystemMessages.CCDA_MSG_INVALID_KEY;

            assertThat(message).endsWith(" ");
            assertThat(message).isNotEqualTo(message.trim());
            assertThat(message.length() - message.stripTrailing().length()).isEqualTo(10);
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY's visible text is pinned independently of its padding")
        void invalidKeyVisibleText() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.trim())
                    .isEqualTo(INVALID_KEY_VISIBLE_TEXT);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.trim())
                    .isEqualTo("Invalid key pressed. Please see below...")
                    .hasSize(40);
        }

        @Test
        @DisplayName("the two messages are distinct values")
        void theTwoMessagesAreDistinct() {
            // Cheap, but it catches a copy-paste that assigns the same literal to both fields -
            // which would still satisfy every width assertion above.
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @ParameterizedTest(name = "the message text contains \"{0}\"")
        @ValueSource(strings = {"Thank you", "CardDemo", "application..."})
        @DisplayName("the thank-you wording is reproduced fragment by fragment")
        void thankYouContainsItsFragments(String fragment) {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).contains(fragment);
        }

        @ParameterizedTest(name = "the message text contains \"{0}\"")
        @ValueSource(strings = {"Invalid key pressed.", "Please see below", "..."})
        @DisplayName("the invalid-key wording is reproduced fragment by fragment")
        void invalidKeyContainsItsFragments(String fragment) {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).contains(fragment);
        }
    }

    @Nested
    @DisplayName("Not the screen-title thank-you - two similar strings that are not interchangeable")
    class NotTheScreenTitleThankYou {

        // There are two "thank you for using ... application..." strings in this codebase:
        //   * CCDA-MSG-THANK-YOU, app/cpy/CSMSG01Y.cpy, PIC X(50), names the CardDemo application.
        //     That is this class's constant, and the one asserted here.
        //   * CCDA-THANK-YOU,     app/cpy/COTTL01Y.cpy, PIC X(40), names the CCDA application.
        //     That one belongs to the screen-title copybook and a different Java class, which is
        //     deliberately NOT referenced from this file: this stays a single-class unit test, and
        //     the distinction is asserted against transcribed copybook facts instead.
        // Substituting one for the other produces output that reads perfectly and still fails
        // field-for-field diffing, because both the wording and the width differ.

        @Test
        @DisplayName("this message names the CardDemo application, not the CCDA application")
        void namesCardDemoAndNotCcda() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).contains("CardDemo");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .doesNotContain(SCREEN_TITLE_THANK_YOU_WORDING)
                    .doesNotContain("CCDA application");
        }

        @Test
        @DisplayName("this message is 50 characters wide, not the screen title's 40")
        void isFiftyWideAndNotForty() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(DECLARED_MESSAGE_WIDTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.length())
                    .isNotEqualTo(SCREEN_TITLE_WIDTH);
        }

        @Test
        @DisplayName("this message is not the screen-title string truncated or padded to 50")
        void isNotTheScreenTitleStringReshaped() {
            // The screen-title value, transcribed from app/cpy/COTTL01Y.cpy at its declared
            // PIC X(40) width, then widened to 50 - i.e. the value a well-meaning substitution
            // would produce. It must not match.
            String screenTitleWidenedToFifty =
                    "Thank you for using CCDA application... " + " ".repeat(10);

            assertThat(screenTitleWidenedToFifty).hasSize(DECLARED_MESSAGE_WIDTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).isNotEqualTo(screenTitleWidenedToFifty);
        }
    }


    @Nested
    @DisplayName("ABEND-DATA - the four declared widths and the 134-byte total")
    class AbendDataWidths {

        @Test
        @DisplayName("ABEND-CODE is PIC X(4)")
        void abendCodeWidth() {
            // source: app/cpy/CSMSG02Y.cpy:L22-L23.
            assertThat(SystemMessages.ABEND_CODE_LENGTH).isEqualTo(CODE_WIDTH).isEqualTo(4);
        }

        @Test
        @DisplayName("ABEND-CULPRIT is PIC X(8), one COBOL program name")
        void abendCulpritWidth() {
            // source: app/cpy/CSMSG02Y.cpy:L24-L25.
            assertThat(SystemMessages.ABEND_CULPRIT_LENGTH).isEqualTo(CULPRIT_WIDTH).isEqualTo(8);
        }

        @Test
        @DisplayName("ABEND-REASON is PIC X(50)")
        void abendReasonWidth() {
            // source: app/cpy/CSMSG02Y.cpy:L26-L27. It happens to be 50, the same number as the
            // CSMSG01Y message width, but the two are independent facts from different copybooks
            // and are asserted separately rather than aliased to one another.
            assertThat(SystemMessages.ABEND_REASON_LENGTH).isEqualTo(REASON_WIDTH).isEqualTo(50);
        }

        @Test
        @DisplayName("ABEND-MSG is PIC X(72)")
        void abendMsgWidth() {
            // source: app/cpy/CSMSG02Y.cpy:L28-L29.
            assertThat(SystemMessages.ABEND_MSG_LENGTH).isEqualTo(MSG_WIDTH).isEqualTo(72);
        }

        @Test
        @DisplayName("the group item totals 134 bytes: 4 + 8 + 50 + 72")
        void abendDataTotalsOneHundredAndThirtyFour() {
            // The sum is written out so the arithmetic is checkable by eye, and then also asserted
            // against the literal 134 so both the addition and the total are pinned.
            assertThat(EXPECTED_ABEND_DATA_WIDTH).isEqualTo(4 + 8 + 50 + 72).isEqualTo(134);
            assertThat(SystemMessages.ABEND_DATA_LENGTH).isEqualTo(EXPECTED_ABEND_DATA_WIDTH);
            assertThat(SystemMessages.ABEND_CODE_LENGTH
                    + SystemMessages.ABEND_CULPRIT_LENGTH
                    + SystemMessages.ABEND_REASON_LENGTH
                    + SystemMessages.ABEND_MSG_LENGTH)
                    .isEqualTo(SystemMessages.ABEND_DATA_LENGTH);
        }

        @ParameterizedTest(name = "{0} is declared {1} bytes wide")
        @MethodSource("com.vsergeychik.carddemo.common.SystemMessagesTest#abendFields")
        @DisplayName("every abend field's declared width is positive and no wider than the group")
        void everyAbendFieldWidthIsWithinTheGroup(String cobolName, int width) {
            assertThat(width)
                    .as("%s declared width", cobolName)
                    .isPositive()
                    .isLessThanOrEqualTo(EXPECTED_ABEND_DATA_WIDTH);
        }

        @Test
        @DisplayName("the field offsets are 0, 4, 12 and 62, ending exactly at 134")
        void fieldOffsetsFollowDeclarationOrder() {
            assertThat(CODE_OFFSET).isZero();
            assertThat(CULPRIT_OFFSET).isEqualTo(4);
            assertThat(REASON_OFFSET).isEqualTo(12);
            assertThat(MSG_OFFSET).isEqualTo(62);
            assertThat(MSG_OFFSET + MSG_WIDTH).isEqualTo(EXPECTED_ABEND_DATA_WIDTH);
        }
    }

    @Nested
    @DisplayName("ABEND-DATA - VALUE SPACES is the declared initial state")
    class AbendDataDefaults {

        @Test
        @DisplayName("spaces() gives every field a run of spaces at its own declared width")
        void spacesGivesEveryFieldItsOwnWidthOfSpaces() {
            // source: app/cpy/CSMSG02Y.cpy:L21-L29 - all four items are declared VALUE SPACES.
            SystemMessages.AbendData area = SystemMessages.AbendData.spaces();

            assertThat(area.abendCode()).isEqualTo(" ".repeat(CODE_WIDTH)).hasSize(4);
            assertThat(area.abendCulprit()).isEqualTo(" ".repeat(CULPRIT_WIDTH)).hasSize(8);
            assertThat(area.abendReason()).isEqualTo(" ".repeat(REASON_WIDTH)).hasSize(50);
            assertThat(area.abendMsg()).isEqualTo(" ".repeat(MSG_WIDTH)).hasSize(72);
        }

        @Test
        @DisplayName("the default is spaces - not null, and not the empty string")
        void theDefaultIsSpacesNotNullAndNotEmpty() {
            // COBOL has no null. An empty string would be equally wrong: SPACES in a PIC X(4) field
            // is four spaces, and any consumer padding it later would be padding twice.
            SystemMessages.AbendData area = SystemMessages.AbendData.spaces();

            assertThat(area.abendCode()).isNotNull().isNotEmpty().isBlank();
            assertThat(area.abendCulprit()).isNotNull().isNotEmpty().isBlank();
            assertThat(area.abendReason()).isNotNull().isNotEmpty().isBlank();
            assertThat(area.abendMsg()).isNotNull().isNotEmpty().isBlank();
        }

        @Test
        @DisplayName("the default area serialises to 134 spaces")
        void theDefaultAreaSerialisesToOneHundredAndThirtyFourSpaces() {
            String image = serialise(SystemMessages.AbendData.spaces());

            assertThat(image).hasSize(EXPECTED_ABEND_DATA_WIDTH);
            assertThat(image).isEqualTo(" ".repeat(EXPECTED_ABEND_DATA_WIDTH));
        }

        @Test
        @DisplayName("spaces() is already at its declared widths, so normalising it changes nothing")
        void normalisingTheDefaultAreaIsIdempotent() {
            // This drives the "value length already equals the declared width" path of the PIC X
            // move for all four fields at once, and pins the value semantics of the record: an
            // already-canonical area normalises to something equal to itself.
            SystemMessages.AbendData area = SystemMessages.AbendData.spaces();

            assertThat(area.toDeclaredWidths()).isEqualTo(area);
            assertThat(area.toDeclaredWidths().toDeclaredWidths()).isEqualTo(area);
        }
    }

    @Nested
    @DisplayName("ABEND-DATA - PIC X move semantics: right-pad when short, right-truncate when long")
    class AbendDataMoveSemantics {

        @Test
        @DisplayName("a value that already fills its field is stored unchanged")
        void exactWidthValueIsStoredUnchanged() {
            SystemMessages.AbendData area =
                    SystemMessages.AbendData.spaces().withAbendCode(SAMPLE_CODE);

            assertThat(SAMPLE_CODE).hasSize(CODE_WIDTH);
            assertThat(area.abendCode()).isEqualTo("0001").hasSize(CODE_WIDTH);
        }

        @Test
        @DisplayName("an eight-character program name exactly fills ABEND-CULPRIT")
        void exactWidthCulpritIsStoredUnchanged() {
            SystemMessages.AbendData area =
                    SystemMessages.AbendData.spaces().withAbendCulprit(SAMPLE_CULPRIT);

            assertThat(SAMPLE_CULPRIT).hasSize(CULPRIT_WIDTH);
            assertThat(area.abendCulprit()).isEqualTo("COACTVWC").hasSize(CULPRIT_WIDTH);
        }

        @Test
        @DisplayName("a short value is padded on the right, never on the left")
        void shortValueIsPaddedOnTheRight() {
            // A seven-character program name in an eight-character field.
            SystemMessages.AbendData area =
                    SystemMessages.AbendData.spaces().withAbendCulprit("CBACT01");

            assertThat(area.abendCulprit())
                    .hasSize(CULPRIT_WIDTH)
                    .isEqualTo("CBACT01 ")
                    .startsWith("CBACT01")
                    // Left-padding would produce " CBACT01", which is a different eight bytes and
                    // the single most likely way to get the direction wrong.
                    .isNotEqualTo(" CBACT01");
        }

        @Test
        @DisplayName("a long value is truncated on the right, keeping its leading characters")
        void longValueIsTruncatedOnTheRight() {
            // Nine characters offered to the eight-character ABEND-CULPRIT field.
            SystemMessages.AbendData area =
                    SystemMessages.AbendData.spaces().withAbendCulprit("COACTUPCX");

            assertThat(area.abendCulprit())
                    .hasSize(CULPRIT_WIDTH)
                    .isEqualTo("COACTUPC")
                    // Left truncation - correct for numeric PIC 9, wrong for alphanumeric PIC X -
                    // would keep the tail and produce "OACTUPCX".
                    .isNotEqualTo("OACTUPCX");
        }

        // ignoreLeadingAndTrailingWhitespace is switched OFF deliberately: the expected values are
        // space-padded, and the default CSV behaviour would strip exactly the characters under test.
        @ParameterizedTest(name = "moving \"{0}\" into ABEND-CODE stores \"{1}\"")
        @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "0001|0001",
            "1|1   ",
            "12|12  ",
            "123|123 ",
            "12345|1234",
            "123456|1234",
        })
        @DisplayName("ABEND-CODE pads or truncates on the right to exactly four characters")
        void abendCodeIsAlwaysFourCharacters(String moved, String expected) {
            SystemMessages.AbendData area =
                    SystemMessages.AbendData.spaces().withAbendCode(moved);

            assertThat(expected).hasSize(CODE_WIDTH);
            assertThat(area.abendCode()).isEqualTo(expected).hasSize(CODE_WIDTH);
        }

        @Test
        @DisplayName("each with... method changes one field and leaves the other three alone")
        void eachWithMethodChangesExactlyOneField() {
            SystemMessages.AbendData blank = SystemMessages.AbendData.spaces();

            SystemMessages.AbendData withCode = blank.withAbendCode(SAMPLE_CODE);
            assertThat(withCode.abendCode()).isEqualTo(SAMPLE_CODE);
            assertThat(withCode.abendCulprit()).isEqualTo(blank.abendCulprit());
            assertThat(withCode.abendReason()).isEqualTo(blank.abendReason());
            assertThat(withCode.abendMsg()).isEqualTo(blank.abendMsg());

            SystemMessages.AbendData withCulprit = blank.withAbendCulprit(SAMPLE_CULPRIT);
            assertThat(withCulprit.abendCulprit()).isEqualTo(SAMPLE_CULPRIT);
            assertThat(withCulprit.abendCode()).isEqualTo(blank.abendCode());
            assertThat(withCulprit.abendReason()).isEqualTo(blank.abendReason());
            assertThat(withCulprit.abendMsg()).isEqualTo(blank.abendMsg());

            SystemMessages.AbendData withReason = blank.withAbendReason(SAMPLE_REASON);
            assertThat(withReason.abendReason()).startsWith(SAMPLE_REASON).hasSize(REASON_WIDTH);
            assertThat(withReason.abendCode()).isEqualTo(blank.abendCode());
            assertThat(withReason.abendCulprit()).isEqualTo(blank.abendCulprit());
            assertThat(withReason.abendMsg()).isEqualTo(blank.abendMsg());

            SystemMessages.AbendData withMsg = blank.withAbendMsg(SAMPLE_MSG);
            assertThat(withMsg.abendMsg()).startsWith(SAMPLE_MSG).hasSize(MSG_WIDTH);
            assertThat(withMsg.abendCode()).isEqualTo(blank.abendCode());
            assertThat(withMsg.abendCulprit()).isEqualTo(blank.abendCulprit());
            assertThat(withMsg.abendReason()).isEqualTo(blank.abendReason());
        }

        @Test
        @DisplayName("a populated area still serialises to exactly 134 bytes")
        void populatedAreaStillSerialisesToOneHundredAndThirtyFour() {
            String image = serialise(sampleArea());

            assertThat(image).hasSize(EXPECTED_ABEND_DATA_WIDTH);
            assertThat(image).hasSize(SystemMessages.ABEND_DATA_LENGTH);
        }

        @Test
        @DisplayName("the serialised image lays the fields out at offsets 0, 4, 12 and 62")
        void serialisedImageHonoursDeclarationOrder() {
            // Reading the image back by absolute offset is what proves the declaration order is
            // CODE, CULPRIT, REASON, MSG, rather than merely that all four fields are present.
            String image = serialise(sampleArea());

            assertThat(image.substring(CODE_OFFSET, CODE_OFFSET + CODE_WIDTH))
                    .isEqualTo(SAMPLE_CODE);
            assertThat(image.substring(CULPRIT_OFFSET, CULPRIT_OFFSET + CULPRIT_WIDTH))
                    .isEqualTo(SAMPLE_CULPRIT);
            assertThat(image.substring(REASON_OFFSET, REASON_OFFSET + REASON_WIDTH))
                    .startsWith(SAMPLE_REASON)
                    .hasSize(REASON_WIDTH);
            assertThat(image.substring(MSG_OFFSET, MSG_OFFSET + MSG_WIDTH))
                    .startsWith(SAMPLE_MSG)
                    .hasSize(MSG_WIDTH);
        }

        @Test
        @DisplayName("every serialised field is right-space-padded to its own declared width")
        void serialisedFieldsAreRightPaddedToTheirOwnWidth() {
            SystemMessages.AbendData canonical = sampleArea().toDeclaredWidths();

            assertThat(canonical.abendCode()).hasSize(CODE_WIDTH);
            assertThat(canonical.abendCulprit()).hasSize(CULPRIT_WIDTH);
            assertThat(canonical.abendReason())
                    .hasSize(REASON_WIDTH)
                    .isEqualTo(SAMPLE_REASON + " ".repeat(REASON_WIDTH - SAMPLE_REASON.length()));
            assertThat(canonical.abendMsg())
                    .hasSize(MSG_WIDTH)
                    .isEqualTo(SAMPLE_MSG + " ".repeat(MSG_WIDTH - SAMPLE_MSG.length()));
        }

        @Test
        @DisplayName("an over-long value in every field is truncated when normalised")
        void overLongValuesInEveryFieldAreTruncatedOnNormalisation() {
            // Constructed directly, so each component starts one character wider than its field and
            // toDeclaredWidths() has to shorten all four.
            SystemMessages.AbendData tooWide = new SystemMessages.AbendData(
                    "a".repeat(CODE_WIDTH + 1),
                    "b".repeat(CULPRIT_WIDTH + 1),
                    "c".repeat(REASON_WIDTH + 1),
                    "d".repeat(MSG_WIDTH + 1));

            SystemMessages.AbendData canonical = tooWide.toDeclaredWidths();

            assertThat(canonical.abendCode()).isEqualTo("a".repeat(CODE_WIDTH));
            assertThat(canonical.abendCulprit()).isEqualTo("b".repeat(CULPRIT_WIDTH));
            assertThat(canonical.abendReason()).isEqualTo("c".repeat(REASON_WIDTH));
            assertThat(canonical.abendMsg()).isEqualTo("d".repeat(MSG_WIDTH));
            assertThat(serialise(canonical)).hasSize(EXPECTED_ABEND_DATA_WIDTH);
        }

        @Test
        @DisplayName("a short value in every field is padded when normalised")
        void shortValuesInEveryFieldArePaddedOnNormalisation() {
            // The mirror image of the previous test: every component one character narrower than its
            // field, so the padding branch runs for all four.
            SystemMessages.AbendData tooNarrow = new SystemMessages.AbendData(
                    "a".repeat(CODE_WIDTH - 1),
                    "b".repeat(CULPRIT_WIDTH - 1),
                    "c".repeat(REASON_WIDTH - 1),
                    "d".repeat(MSG_WIDTH - 1));

            SystemMessages.AbendData canonical = tooNarrow.toDeclaredWidths();

            // Three characters plus one pad space, and seven plus one: the pad lands on the right.
            assertThat(canonical.abendCode()).isEqualTo("aaa ");
            assertThat(canonical.abendCulprit()).isEqualTo("bbbbbbb ");
            assertThat(canonical.abendReason()).endsWith(" ").hasSize(REASON_WIDTH);
            assertThat(canonical.abendMsg()).endsWith(" ").hasSize(MSG_WIDTH);
            assertThat(serialise(canonical)).hasSize(EXPECTED_ABEND_DATA_WIDTH);
        }

        @Test
        @DisplayName("an empty value is padded to a full field of spaces")
        void emptyValueIsPaddedToAFullFieldOfSpaces() {
            // The boundary case of the padding branch: nothing moved in at all still yields a field
            // of the declared width, matching VALUE SPACES.
            SystemMessages.AbendData area = SystemMessages.AbendData.spaces().withAbendCode("");

            assertThat(area.abendCode()).isEqualTo(" ".repeat(CODE_WIDTH)).hasSize(CODE_WIDTH);
        }
    }


    @Nested
    @DisplayName("ABEND-DATA - null has no COBOL counterpart and is rejected")
    class AbendDataNullGuards {

        // COBOL has no null: the absent value of a VALUE SPACES field is a run of spaces. Each of
        // the four components is therefore guarded separately, and each guard is driven here from
        // its true side, while every test that constructs a valid area drives all four false sides.

        @Test
        @DisplayName("a null ABEND-CODE is rejected, naming the COBOL field")
        void nullAbendCodeIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SystemMessages.AbendData(
                            null, SAMPLE_CULPRIT, SAMPLE_REASON, SAMPLE_MSG))
                    .withMessageContaining("abendCode")
                    .withMessageContaining("ABEND-CODE")
                    .withMessageContaining("VALUE SPACES");
        }

        @Test
        @DisplayName("a null ABEND-CULPRIT is rejected, naming the COBOL field")
        void nullAbendCulpritIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SystemMessages.AbendData(
                            SAMPLE_CODE, null, SAMPLE_REASON, SAMPLE_MSG))
                    .withMessageContaining("abendCulprit")
                    .withMessageContaining("ABEND-CULPRIT")
                    .withMessageContaining("VALUE SPACES");
        }

        @Test
        @DisplayName("a null ABEND-REASON is rejected, naming the COBOL field")
        void nullAbendReasonIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SystemMessages.AbendData(
                            SAMPLE_CODE, SAMPLE_CULPRIT, null, SAMPLE_MSG))
                    .withMessageContaining("abendReason")
                    .withMessageContaining("ABEND-REASON")
                    .withMessageContaining("VALUE SPACES");
        }

        @Test
        @DisplayName("a null ABEND-MSG is rejected, naming the COBOL field")
        void nullAbendMsgIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SystemMessages.AbendData(
                            SAMPLE_CODE, SAMPLE_CULPRIT, SAMPLE_REASON, null))
                    .withMessageContaining("abendMsg")
                    .withMessageContaining("ABEND-MSG")
                    .withMessageContaining("VALUE SPACES");
        }

        @Test
        @DisplayName("four non-null components are accepted, exercising every guard's false side")
        void fourNonNullComponentsAreAccepted() {
            SystemMessages.AbendData area = sampleArea();

            assertThat(area.abendCode()).isEqualTo(SAMPLE_CODE);
            assertThat(area.abendCulprit()).isEqualTo(SAMPLE_CULPRIT);
            assertThat(area.abendReason()).isEqualTo(SAMPLE_REASON);
            assertThat(area.abendMsg()).isEqualTo(SAMPLE_MSG);
        }

        @Test
        @DisplayName("moving null into ABEND-CODE is rejected by the move, not by the constructor")
        void nullMovedIntoAbendCodeIsRejected() {
            // The with... methods apply PIC X semantics before constructing, so the failure comes
            // from the move and its message leads with the COBOL field name rather than the Java
            // component name. Asserting that distinction keeps the two guard sites diagnosable.
            assertThatNullPointerException()
                    .isThrownBy(() -> SystemMessages.AbendData.spaces().withAbendCode(null))
                    .withMessageStartingWith("ABEND-CODE must not be null");
        }

        @Test
        @DisplayName("moving null into ABEND-CULPRIT is rejected by the move")
        void nullMovedIntoAbendCulpritIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SystemMessages.AbendData.spaces().withAbendCulprit(null))
                    .withMessageStartingWith("ABEND-CULPRIT must not be null");
        }

        @Test
        @DisplayName("moving null into ABEND-REASON is rejected by the move")
        void nullMovedIntoAbendReasonIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SystemMessages.AbendData.spaces().withAbendReason(null))
                    .withMessageStartingWith("ABEND-REASON must not be null");
        }

        @Test
        @DisplayName("moving null into ABEND-MSG is rejected by the move")
        void nullMovedIntoAbendMsgIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SystemMessages.AbendData.spaces().withAbendMsg(null))
                    .withMessageStartingWith("ABEND-MSG must not be null");
        }

        @Test
        @DisplayName("a rejected move leaves the original area untouched")
        void aRejectedMoveLeavesTheOriginalUntouched() {
            SystemMessages.AbendData original = sampleArea();

            assertThatNullPointerException().isThrownBy(() -> original.withAbendReason(null));

            assertThat(original.abendCode()).isEqualTo(SAMPLE_CODE);
            assertThat(original.abendCulprit()).isEqualTo(SAMPLE_CULPRIT);
            assertThat(original.abendReason()).isEqualTo(SAMPLE_REASON);
            assertThat(original.abendMsg()).isEqualTo(SAMPLE_MSG);
        }
    }

    @Nested
    @DisplayName("Shape and immutability - no mutable state anywhere")
    class ShapeAndImmutability {

        @Test
        @DisplayName("both message constants are public static final Strings")
        void messageConstantsArePublicStaticFinal() throws ReflectiveOperationException {
            for (String name : new String[] {"CCDA_MSG_THANK_YOU", "CCDA_MSG_INVALID_KEY"}) {
                Field field = SystemMessages.class.getDeclaredField(name);
                int modifiers = field.getModifiers();

                assertThat(Modifier.isPublic(modifiers)).as("%s is public", name).isTrue();
                assertThat(Modifier.isStatic(modifiers)).as("%s is static", name).isTrue();
                assertThat(Modifier.isFinal(modifiers)).as("%s is final", name).isTrue();
                assertThat(field.getType()).isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("every declared width constant is a public static final int")
        void widthConstantsArePublicStaticFinalInts() throws ReflectiveOperationException {
            String[] names = {
                "MESSAGE_LENGTH",
                "ABEND_CODE_LENGTH",
                "ABEND_CULPRIT_LENGTH",
                "ABEND_REASON_LENGTH",
                "ABEND_MSG_LENGTH",
                "ABEND_DATA_LENGTH",
            };

            for (String name : names) {
                Field field = SystemMessages.class.getDeclaredField(name);
                int modifiers = field.getModifiers();

                assertThat(Modifier.isPublic(modifiers)).as("%s is public", name).isTrue();
                assertThat(Modifier.isStatic(modifiers)).as("%s is static", name).isTrue();
                assertThat(Modifier.isFinal(modifiers)).as("%s is final", name).isTrue();
                assertThat(field.getType()).isEqualTo(int.class);
            }
        }

        @Test
        @DisplayName("no static field on either type is mutable")
        void noStaticFieldIsMutable() {
            assertNoMutableStaticFieldsOn(SystemMessages.class);
            assertNoMutableStaticFieldsOn(SystemMessages.AbendData.class);
        }

        @Test
        @DisplayName("SystemMessages is final and cannot be instantiated from outside")
        void systemMessagesIsFinalWithASinglePrivateConstructor() {
            assertThat(Modifier.isFinal(SystemMessages.class.getModifiers())).isTrue();

            Constructor<?>[] constructors = SystemMessages.class.getDeclaredConstructors();
            assertThat(constructors).hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
            assertThat(constructors[0].getParameterCount()).isZero();
        }

        @Test
        @DisplayName("AbendData is a record whose four components are in copybook order")
        void abendDataIsARecordInCopybookOrder() {
            assertThat(SystemMessages.AbendData.class.isRecord()).isTrue();

            RecordComponent[] components =
                    SystemMessages.AbendData.class.getRecordComponents();

            assertThat(components).hasSize(4);
            assertThat(components[0].getName()).isEqualTo("abendCode");
            assertThat(components[1].getName()).isEqualTo("abendCulprit");
            assertThat(components[2].getName()).isEqualTo("abendReason");
            assertThat(components[3].getName()).isEqualTo("abendMsg");
            for (RecordComponent component : components) {
                assertThat(component.getType())
                        .as("%s is alphanumeric PIC X, so it maps to String", component.getName())
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("AbendData exposes no setter and no mutable instance field")
        void abendDataExposesNoSetter() {
            for (Method method : SystemMessages.AbendData.class.getDeclaredMethods()) {
                assertThat(method.getName())
                        .as("AbendData must expose no setter")
                        .doesNotStartWith("set");
            }
            for (Field field : SystemMessages.AbendData.class.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s is final", field.getName())
                        .isTrue();
                assertThat(Modifier.isPrivate(field.getModifiers()))
                        .as("%s is private", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a with... copy returns a new instance and leaves the original unchanged")
        void withCopyLeavesTheOriginalUnchanged() {
            SystemMessages.AbendData original = sampleArea();

            SystemMessages.AbendData copy = original.withAbendCode("9999");

            assertThat(copy).isNotSameAs(original).isNotEqualTo(original);
            assertThat(copy.abendCode()).isEqualTo("9999");
            // The original is untouched: this is the property that makes the type safe to share.
            assertThat(original.abendCode()).isEqualTo(SAMPLE_CODE);
        }

        @Test
        @DisplayName("normalising to declared widths returns a new instance, not a mutation")
        void normalisingReturnsANewInstance() {
            SystemMessages.AbendData original = sampleArea();

            SystemMessages.AbendData canonical = original.toDeclaredWidths();

            assertThat(canonical).isNotSameAs(original);
            // SAMPLE_REASON is shorter than its field, so normalising genuinely changes the value -
            // and the original still holds the unpadded text.
            assertThat(canonical.abendReason()).hasSize(REASON_WIDTH);
            assertThat(original.abendReason()).isEqualTo(SAMPLE_REASON);
        }

        @Test
        @DisplayName("two areas built from the same four components are equal")
        void areasWithEqualComponentsAreEqual() {
            SystemMessages.AbendData first = sampleArea();
            SystemMessages.AbendData second = sampleArea();

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(SystemMessages.AbendData.spaces());
        }

        @Test
        @DisplayName("spaces() hands out a fresh, equal area every time")
        void spacesHandsOutAFreshEqualAreaEveryTime() {
            // Value-equal but not required to be identity-equal: there is no shared singleton to
            // accidentally mutate, which is the point.
            assertThat(SystemMessages.AbendData.spaces())
                    .isEqualTo(SystemMessages.AbendData.spaces());
        }

        /**
         * Asserts that the given type declares no non-final static field.
         *
         * <p>Synthetic fields are skipped deliberately: the coverage agent adds a synthetic,
         * non-final static array to every class it instruments, and failing on that would make the
         * assertion depend on whether the build was run with coverage enabled.
         *
         * @param type the type to inspect
         */
        private void assertNoMutableStaticFieldsOn(Class<?> type) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s.%s must be final: COBOL WORKING-STORAGE must never become mutable"
                                + " static Java state", type.getSimpleName(), field.getName())
                        .isTrue();
            }
        }
    }
}

