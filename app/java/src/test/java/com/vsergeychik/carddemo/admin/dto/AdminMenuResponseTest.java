package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
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
 * Tests for {@link AdminMenuResponse}, the outbound projection of {@code COADM1AO} in
 * {@code app/cpy-bms/COADM01.CPY} - the response payload of {@code GET /api/admin/menu}, CICS
 * transaction {@code CA00}, program {@code COADM01C}, mapset {@code COADM01}, map {@code COADM1A}.
 *
 * <h2>User rules</h2>
 *
 * <p><strong>No user-specified rules were provided for this project.</strong> {@code review_rules}
 * returns exactly one line - "No user rules provided." - and that single line is the whole document;
 * there is nothing further to page through. <strong>No user rule therefore governs this file.</strong>
 * None has been invented, and their absence is emphatically <em>not</em> licence to assert less. The
 * twelve enterprise practices the Agent Action Plan substitutes in their place (section 0.10.2) are
 * binding here, and each is discharged by a named construct below:
 *
 * <ul>
 *   <li><strong>B1</strong> - no dependency and no version literal is introduced. Only what
 *       {@code app/java/pom.xml} already declares is used: JUnit 5 and AssertJ through
 *       {@code spring-boot-starter-test}, Jackson through {@code spring-boot-starter-web}.</li>
 *   <li><strong>B2</strong> - JUnit 5 and Spring Boot 3.5.x era APIs only, and <em>no Spring context
 *       at all</em>: no {@code @SpringBootTest}, no {@code MockMvc}, no {@code JobLauncher}. The
 *       subject is a record, so it needs none of them.</li>
 *   <li><strong>B3</strong> - the legacy sources are read-only and are <em>never opened at run
 *       time</em>. Every expectation below is a Java literal carrying the source line it was
 *       transcribed from, so the copybook remains the authority while this file stays independent of
 *       the file system.</li>
 *   <li><strong>B4</strong> - the duplication between this screen and the main menu is documented,
 *       not removed. See {@link DuplicationIsDocumentedNotRemoved}.</li>
 *   <li><strong>B5</strong> - dead and unreachable structure is preserved and asserted rather than
 *       tidied away: all twelve option slots (the program can fill at most ten, and fills four), and
 *       the abandoned title literal at {@code app/cpy/COTTL01Y.cpy:21}, which is named in
 *       {@link HeaderLiterals} precisely so that it is never asserted.</li>
 *   <li><strong>B6</strong> - <strong>no subject.</strong> The admin menu screen carries no
 *       credential field, so there is nothing here to mask, hash or redact and no such assertion is
 *       invented. Recording the absence is the point; silence would read as an oversight.</li>
 *   <li><strong>B7</strong> - deterministic and non-interactive. Every date and time value comes from
 *       {@link Clock#fixed(Instant, java.time.ZoneId)}; there is no {@code now()}, no randomness, no
 *       sleep, no file or network access, and no ordering dependence between tests. The suite must
 *       pass under {@code mvn -B ... clean verify} (gate G54).</li>
 *   <li><strong>B8</strong> - explicit over implicit. Widths are asserted against the subject's named
 *       constant <em>and</em> against the transcribed integer literal, so a constant that drifted
 *       cannot silently agree with itself; and every byte-level operation states its code page
 *       explicitly by constructing {@link FixedWidthCodec} with {@link #MAP_CHARSET}, never
 *       {@code String.getBytes()} with a platform default. A rounding mode has no subject here -
 *       there is no decimal {@code PICTURE} anywhere on this screen.</li>
 *   <li><strong>B9</strong> - this class declares no mutable field. Its fields are
 *       {@code private static final} lists built by {@link List#of} over {@code String} and
 *       {@code Integer}, so they are deeply immutable constants rather than shared state, and
 *       everything with a lifecycle is built where it is used. The subject is held to the same
 *       standard reflectively in {@link Statelessness}.</li>
 *   <li><strong>B10</strong> - these tests ship in the same phase as the record they cover.</li>
 *   <li><strong>B11</strong> - the width, stride and total-width arithmetic is written out by hand.
 *       No copybook parser is used, and every reflective sweep is paired with explicitly named
 *       assertions so a reader can see which cases were checked instead of trusting a loop.</li>
 *   <li><strong>B12</strong> - every expected literal cites its source line, because this baseline is
 *       <strong>statically derived</strong> from the COBOL rather than captured from a run of it. The
 *       legacy programs cannot be executed in this environment (Agent Action Plan risk <strong>R-A</strong>,
 *       eight verified blockers in section 0.7.6), so the citation is the only audit trail a reviewer
 *       has. Relatedly, the {@link BmsAttributes} colour values asserted in {@link MessageColour}
 *       originate in <strong>IBM CICS documentation</strong>, because {@code DFHBMSCA} and
 *       {@code DFHATTR} are referenced by the online programs but are absent from this repository
 *       (risk <strong>R-D</strong>).</li>
 * </ul>
 *
 * <h2>Gates</h2>
 *
 * <p>Applied: <strong>G9</strong> (every payload member traces to a {@code DFHMDF} definition and
 * every width to a symbolic-map {@code PICTURE} clause), <strong>G21</strong> ({@code FILLER} spans
 * are accounted for - omit one and the total breaks immediately), <strong>G34</strong> (the
 * {@code COADM1AO REDEFINES COADM1AI} pair: two typed views over one 820-byte span),
 * <strong>G37</strong> (no server-side conversation state), <strong>G40</strong>
 * ({@code nextProgram} / {@code nextMapset} / {@code nextMap} in place of {@code EXEC CICS XCTL}),
 * <strong>G49</strong> (branch coverage for {@code admin.dto}), <strong>G52</strong> (no wildcard
 * imports, including static ones), <strong>G53</strong> (no static mutable state) and
 * <strong>G54</strong> (non-interactive).
 *
 * <p>No subject, and therefore deliberately not simulated: <strong>G22-G29</strong> - there is no
 * decimal {@code PICTURE}, no arithmetic and no {@code CobolDecimal} on this screen;
 * <strong>G35</strong> - no {@code AbendException} path; <strong>G44-G47</strong> - no repository, no
 * dataset and no {@code FileStatus}.
 *
 * <h2>What is asserted</h2>
 *
 * <p>Every expected value below is transcribed from the legacy sources - the symbolic map, the
 * mapset and the program - and never read back from the class under test, so the copybook stays the
 * authority. A width, a name or an ordering that drifted in either place fails here.
 *
 * <p>The suite is organised around the five properties of this screen that determine the
 * implementation, so that a failure names a translation decision rather than merely a value:
 *
 * <ol>
 *   <li>There are <strong>20</strong> payload fields, in map order, and their widths sum to
 *       <strong>668</strong> inside an <strong>820</strong>-byte image.</li>
 *   <li>There are <strong>12</strong> option slots even though the program fills 4 and can reach
 *       only 10 - the sharpest dead-code trap in this folder.</li>
 *   <li>{@code OPTIONO} is <strong>textual and zero-filled</strong>: option 1 is {@code "01"}, and
 *       "nothing entered yet" is spaces.</li>
 *   <li>The message colour defaults to {@code DFHRED} by map declaration and is moved to
 *       {@code DFHGREEN} by the program, and it is the <strong>only</strong> attribute byte that
 *       reaches the payload.</li>
 *   <li>No conversation state is held server-side: the communication area and the three navigation
 *       members travel in the body.</li>
 * </ol>
 */
@DisplayName("AdminMenuResponse - the COADM1AO output projection of the CA00 admin menu")
class AdminMenuResponseTest {

    /**
     * The twenty symbolic-map names in map order, transcribed from
     * {@code app/cpy-bms/COADM01.CPY} lines 146 to 260.
     */
    private static final List<String> EXPECTED_FIELDS = List.of("TRNNAMEO",
            "TITLE01O",
            "CURDATEO",
            "PGMNAMEO",
            "TITLE02O",
            "CURTIMEO",
            "OPTN001O",
            "OPTN002O",
            "OPTN003O",
            "OPTN004O",
            "OPTN005O",
            "OPTN006O",
            "OPTN007O",
            "OPTN008O",
            "OPTN009O",
            "OPTN010O",
            "OPTN011O",
            "OPTN012O",
            "OPTIONO",
            "ERRMSGO");

    /**
     * The twenty declared widths in the same order, transcribed from the same lines: {@code X(4)},
     * {@code X(40)}, {@code X(8)}, {@code X(8)}, {@code X(40)}, {@code X(8)}, twelve {@code X(40)},
     * {@code X(2)}, {@code X(78)}.
     */
    private static final List<Integer> EXPECTED_WIDTHS = List.of(4,
            40,
            8,
            8,
            40,
            8,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            2,
            78);

    /**
     * The JSON property names this type publishes, in declaration order. Twenty screen fields, the
     * communication area, three navigation members, the colour attribute and the repaint flag.
     */
    private static final List<String> EXPECTED_JSON_KEYS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "optn001",
            "optn002",
            "optn003",
            "optn004",
            "optn005",
            "optn006",
            "optn007",
            "optn008",
            "optn009",
            "optn010",
            "optn011",
            "optn012",
            "option",
            "errMsg",
            "navigationContext",
            "nextProgram",
            "nextMapset",
            "nextMap");

    /**
     * The two components that are deliberately not members of THIS document: the {@code ERRMSGC}
     * attribute byte and the clear-the-screen signal. Both are {@code @JsonIgnore}d, because
     * {@code app/cpy-bms/COADM01.CPY:256} declares {@code ERRMSGC} as metadata and the reset signal
     * corresponds to no copybook item at all - so neither belongs in a 1:1 projection of
     * {@code DFHMDF} fields.
     *
     * <p>Absent from this document is not the same as absent from the response: both travel to the
     * client through {@link AdminMenuResponse#screenMetadata()}, which a handler publishes beside this
     * screen. That is what the Agent Action Plan's section 0.3.9 means by keeping metadata separate and
     * available, and it is asserted in {@code TheMetadataEnvelope} below.
     */
    private static final List<String> UNPUBLISHED_MEMBERS =
            List.of("messageColour", "resetAllOutputFields");

    /** The four attribute bytes of the output view plus the three metadata items of the input view. */
    private static final List<String> METADATA_SUFFIXES = List.of("L", "F", "A", "C", "P", "H", "V");

    /**
     * The code page of every byte-level operation in this file, <strong>named explicitly</strong> and
     * never derived from the platform (practice B8).
     *
     * <p>{@code US-ASCII} is what the {@code app/data/ASCII} fixtures are; {@code IBM037} is the
     * EBCDIC alternative, and {@link FixedWidthCodec} demands one or the other be stated at
     * construction precisely so that neither can be assumed.
     */
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    /**
     * The instant every date and time in this file is derived from: the version stamp of
     * {@code app/cbl/COADM01C.cbl:267}, {@code 2022-07-19 23:12:32}.
     *
     * <p>A real instant taken from the source makes the expected {@code 07/19/22} and {@code 23:12:32}
     * traceable rather than arbitrary, and reading it through {@link Clock#fixed} rather than the wall
     * clock is what makes this suite deterministic (practice B7).
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:32Z");

    /**
     * The 80-byte {@code WS-MESSAGE} of {@code app/cbl/COADM01C.cbl:38} narrows to the 78-byte
     * {@code ERRMSGO} of {@code app/cpy-bms/COADM01.CPY:260}. Two bytes are lost, and <em>which</em>
     * two is the whole question.
     */
    private static final int WS_MESSAGE_LENGTH = 80;

    /**
     * The four {@code CDEMO-ADMIN-OPT-NAME} labels of {@code app/cpy/COADM02Y.cpy}, each
     * {@code PIC X(35)} and each transcribed character-for-character including its trailing spaces:
     * lines 25-26, 30-31, 35-36 and 40-41.
     *
     * <p>The trailing spaces are significant. {@code BUILD-MENU-OPTIONS} concatenates the whole
     * {@code X(35)} field {@code DELIMITED BY SIZE} - not a trimmed word - so they end up on the
     * screen, and an implementation that trimmed them would produce a shorter line at a different
     * width.
     */
    private static final List<String> ADMIN_OPTION_NAMES = List.of(
            "User List (Security)               ",
            "User Add (Security)                ",
            "User Update (Security)             ",
            "User Delete (Security)             ");

    /**
     * The four {@code CDEMO-ADMIN-OPT-PGMNAME} targets of {@code app/cpy/COADM02Y.cpy}, each
     * {@code PIC X(08)}: lines 27, 32, 37 and 42. These are the {@code XCTL} targets of
     * {@code app/cbl/COADM01C.cbl:142-145}.
     */
    private static final List<String> ADMIN_OPTION_PROGRAMS =
            List.of("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");

    /**
     * {@code CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4} [{@code app/cpy/COADM02Y.cpy:20}] - the number
     * of slots {@code BUILD-MENU-OPTIONS} fills, which is neither the ten its {@code EVALUATE} can
     * reach nor the twelve the mapset declares.
     */
    private static final int ADMIN_OPTION_COUNT = 4;

    /**
     * The validation message of {@code app/cbl/COADM01C.cbl:131-132}, transcribed verbatim.
     *
     * <p>Its length is <strong>37</strong> characters, counted mechanically from the source literal
     * including the trailing three-dot ellipsis. The per-file build prompt describes it as 36; the
     * copybook and program are the authority under practice B3, so 37 stands and the divergence is
     * recorded here rather than quietly reconciled (practice B4).
     */
    private static final String VALIDATION_MESSAGE = "Please enter a valid option number...";

    /**
     * The coming-soon message of {@code app/cbl/COADM01C.cbl:149-153}.
     *
     * <p>{@code STRING 'This option ' DELIMITED BY SIZE 'is coming soon ...' DELIMITED BY SIZE INTO
     * WS-MESSAGE}, with the two lines that would have interpolated
     * {@code CDEMO-ADMIN-OPT-NAME(WS-OPTION)} <strong>commented out at 150-151</strong>. The trailing
     * space of {@code 'This option '} therefore survives into the result, so the text reads
     * {@code This option is coming soon ...} with a space before {@code is}.
     *
     * <p>This is the <em>opposite</em> of the sibling defect in {@code COMEN01C}, where the equivalent
     * interpolation is live and {@code CDEMO-MENU-OPT-NAME ... DELIMITED BY SPACE} closes the gap up.
     * The two must never be harmonised: each screen emits the text its own program emits.
     */
    private static final String COMING_SOON_MESSAGE = "This option " + "is coming soon ...";

    private static String spaces(final int width) {
        return " ".repeat(width);
    }

    /**
     * A codec for {@link #MAP_CHARSET}, constructed per call so no test can observe state another
     * test left behind (practice B9).
     *
     * @return a codec whose code page is stated explicitly, never defaulted
     */
    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    /**
     * Reproduces one line of {@code BUILD-MENU-OPTIONS} exactly as
     * {@code app/cbl/COADM01C.cbl:231-236} produces it.
     *
     * <p>{@code MOVE SPACES TO WS-ADMIN-OPT-TXT} clears the {@code PIC X(40)} work field, then
     * {@code STRING} concatenates {@code CDEMO-ADMIN-OPT-NUM PIC 9(02)}, the two characters
     * {@code '. '} and {@code CDEMO-ADMIN-OPT-NAME PIC X(35)}, all {@code DELIMITED BY SIZE}:
     * 2 + 2 + 35 = <strong>39</strong> characters. {@code STRING} does not pad, so the fortieth
     * character is the space the {@code MOVE SPACES} left there - which is why the value that reaches
     * {@code OPTN00nO PIC X(40)} is 40 characters with exactly one trailing space, not 39.
     *
     * @param cobolSubscript the one-based {@code WS-IDX} value, 1 to {@link #ADMIN_OPTION_COUNT}
     * @return the 40-character option line
     */
    private static String adminOptionLine(final int cobolSubscript) {
        final FixedWidthCodec codec = codec();
        // CDEMO-ADMIN-OPT-NUM is PIC 9(02): a numeric-display field, so it is zero-filled on the LEFT.
        final String number = codec.movePic9(cobolSubscript, 2);
        final String stringed = codec.concatenateDelimitedBySize(number,
                ". ",
                ADMIN_OPTION_NAMES.get(cobolSubscript - 1));
        // The receiver is WS-ADMIN-OPT-TXT PIC X(40), pre-set to SPACES: a PIC X move pads on the right.
        return codec.movePicX(stringed, AdminMenuResponse.OPTION_LINE_LENGTH);
    }

    /**
     * A fully populated response, built the way {@code AdminMenuService} would after
     * {@code POPULATE-HEADER-INFO} and {@code BUILD-MENU-OPTIONS} have run on a four-option screen.
     */
    private static AdminMenuResponse populated() {
        return AdminMenuResponse.builder()
                .trnName("CA00")
                .title01("      AWS Mainframe Modernization       ")
                .curDate("07/19/22")
                .pgmName("COADM01C")
                .title02("              CardDemo                  ")
                .curTime("23:12:32")
                // Forty characters each, not thirty-nine: the STRING produces 39 and the fortieth is
                // the space MOVE SPACES left in WS-ADMIN-OPT-TXT PIC X(40). See adminOptionLine.
                .optn001("01. User List (Security)                ")
                .optn002("02. User Add (Security)                 ")
                .optn003("03. User Update (Security)              ")
                .optn004("04. User Delete (Security)              ")
                .option("01")
                .errMsg(spaces(AdminMenuResponse.ERR_MSG_LENGTH))
                .navigationContext(NavigationContext.empty()
                        .withUserId("ADMIN001")
                        .withUserTypeAdmin()
                        .withPgmReenter()
                        .withFromTranid("CA00")
                        .withFromProgram("COADM01C"))
                .nextProgram("COUSR00C")
                .build();
    }

    // =================================================================================================

    @Nested
    @DisplayName("Geometry - 20 of 28 DFHMDF fields, 668 payload bytes inside an 820-byte image")
    class Geometry {

        @Test
        @DisplayName("exactly 20 payload fields, in map order, named as the copybook names them")
        void payloadFieldsMatchTheCopybookExactly() {
            assertThat(AdminMenuResponse.PAYLOAD_FIELDS)
                    .as("the xxxO items of COADM1AO, in map order")
                    .containsExactlyElementsOf(EXPECTED_FIELDS)
                    .hasSize(20);
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_COUNT).isEqualTo(20);
            assertThat(AdminMenuResponse.NAMED_MAP_FIELD_COUNT).isEqualTo(20);
        }

        @Test
        @DisplayName("28 DFHMDF definitions in the mapset, 20 named and 8 unnamed literals")
        void mapsetFieldCountsSplitTwentyEight() {
            assertThat(AdminMenuResponse.MAPSET_FIELD_COUNT).isEqualTo(28);
            assertThat(AdminMenuResponse.UNNAMED_MAP_FIELD_COUNT).isEqualTo(8);
            assertThat(AdminMenuResponse.NAMED_MAP_FIELD_COUNT
                    + AdminMenuResponse.UNNAMED_MAP_FIELD_COUNT)
                    .isEqualTo(AdminMenuResponse.MAPSET_FIELD_COUNT);
        }

        @Test
        @DisplayName("the declared widths are 4, 40, 8, 8, 40, 8, forty times twelve, 2, 78")
        void declaredWidthsMatchThePictureClauses() {
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_LENGTHS)
                    .containsExactlyElementsOf(EXPECTED_WIDTHS);
            assertThat(AdminMenuResponse.TRN_NAME_LENGTH).isEqualTo(4);
            assertThat(AdminMenuResponse.TITLE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuResponse.CUR_DATE_LENGTH).isEqualTo(8);
            assertThat(AdminMenuResponse.PGM_NAME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuResponse.CUR_TIME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuResponse.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);
            assertThat(AdminMenuResponse.OPTION_LENGTH).isEqualTo(2);
            assertThat(AdminMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);
        }

        @Test
        @DisplayName("ERRMSGO is 78 - never the 80 of WS-MESSAGE and never the 50 of the invalid-key text")
        void errMsgIsSeventyEightNotEightyNorFifty() {
            assertThat(AdminMenuResponse.ERR_MSG_LENGTH).isEqualTo(78).isNotEqualTo(80).isNotEqualTo(50);
        }

        @Test
        @DisplayName("the twenty widths sum to 668")
        void payloadWidthsSumToSixHundredAndSixtyEight() {
            assertThat(EXPECTED_WIDTHS.stream().mapToInt(Integer::intValue).sum()).isEqualTo(668);
            assertThat(AdminMenuResponse.PAYLOAD_DATA_LENGTH).isEqualTo(668);
        }

        @Test
        @DisplayName("the whole image is 12 + 20 times 7 + 668 = 820 bytes")
        void symbolicMapImageIsEightHundredAndTwenty() {
            assertThat(AdminMenuResponse.TIOAPFX_FILLER_LENGTH).isEqualTo(12);
            assertThat(AdminMenuResponse.ATTRIBUTE_FILLER_LENGTH).isEqualTo(3);
            assertThat(AdminMenuResponse.ATTRIBUTE_PREFIX_LENGTH)
                    .as("FILLER X(3) plus the four attribute bytes xxxC, xxxP, xxxH, xxxV")
                    .isEqualTo(7);
            assertThat(AdminMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(12 + 20 * 7 + 668)
                    .isEqualTo(820);
        }

        @Test
        @DisplayName("the output stride equals the input stride, which is why the REDEFINES is legal")
        void theOutputStrideEqualsTheInputStride() {
            // Gate G34, stated as arithmetic rather than as prose. The two views of the same storage:
            //
            //   COADM1AI (input, app/cpy-bms/COADM01.CPY:17-138)
            //     FILLER PIC X(12)                                     <- TIOAPFX=YES
            //     per field:  xxxL COMP PIC S9(4)  = 2 bytes
            //                 xxxF PICTURE X       = 1 byte   (xxxA REDEFINES it, adding nothing)
            //                 FILLER PICTURE X(4)  = 4 bytes
            //                 xxxI PIC X(n)        = n bytes            stride = 2 + 1 + 4 = 7
            //
            //   COADM1AO (output, app/cpy-bms/COADM01.CPY:139-260)
            //     FILLER PIC X(12)
            //     per field:  FILLER PICTURE X(3)  = 3 bytes
            //                 xxxC, xxxP, xxxH, xxxV, each PICTURE X = 4 bytes
            //                 xxxO PIC X(n)        = n bytes            stride = 3 + 4 = 7
            //
            // Both prefixes are seven bytes and both trailers are the same n, so field k of the output
            // view begins at exactly the byte field k of the input view begins at. That identity is not
            // a coincidence - it is the precondition COBOL imposes on REDEFINES, and it is why one
            // 820-byte area can carry both. The arithmetic is written out by hand, with no copybook
            // parser anywhere near it (practice B11).
            final int inputStride = 2 + 1 + 4;
            final int outputStride = AdminMenuResponse.ATTRIBUTE_FILLER_LENGTH + 4;

            assertThat(outputStride)
                    .as("FILLER X(3) plus xxxC, xxxP, xxxH, xxxV")
                    .isEqualTo(AdminMenuResponse.ATTRIBUTE_PREFIX_LENGTH)
                    .isEqualTo(inputStride)
                    .isEqualTo(7);

            // Walk both views field by field and prove every payload item lands on the same offset.
            int inputOffset = AdminMenuResponse.TIOAPFX_FILLER_LENGTH;
            int outputOffset = AdminMenuResponse.TIOAPFX_FILLER_LENGTH;
            for (int index = 0; index < EXPECTED_WIDTHS.size(); index++) {
                final int width = EXPECTED_WIDTHS.get(index);
                assertThat(outputOffset + outputStride)
                        .as("%s begins at the same byte in both views", EXPECTED_FIELDS.get(index))
                        .isEqualTo(inputOffset + inputStride);
                inputOffset += inputStride + width;
                outputOffset += outputStride + width;
            }

            assertThat(outputOffset)
                    .as("both views end at the same byte")
                    .isEqualTo(inputOffset)
                    .isEqualTo(AdminMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(820);
        }

        @Test
        @DisplayName("composing the twenty fields really does produce 668 bytes, and omitting one fails it")
        void composingTheTwentyFieldsProducesSixHundredSixtyEightBytes() {
            // The assertions above are claims about constants; this is the claim carried out. Each of
            // the twenty values is moved into its own declared width under the COBOL PIC X rule and the
            // images are concatenated in map order, so the total is produced rather than restated.
            //
            // The code page is stated EXPLICITLY (practice B8): US-ASCII, which is what the
            // app/data/ASCII fixtures are. The bytes come from the codec rather than from
            // String.getBytes(), which would consult a platform default and would silently substitute
            // for anything the page cannot represent - writing a value no COBOL program could produce.
            final FixedWidthCodec codec = codec();
            assertThat(codec.charset()).isEqualTo(MAP_CHARSET).isEqualTo(StandardCharsets.US_ASCII);

            final List<String> values = populated().payloadValues();
            final StringBuilder image = new StringBuilder();
            for (int index = 0; index < values.size(); index++) {
                image.append(codec.movePicX(values.get(index), EXPECTED_WIDTHS.get(index)));
            }

            assertThat(image.length()).isEqualTo(AdminMenuResponse.PAYLOAD_DATA_LENGTH).isEqualTo(668);
            assertThat(codec.encodeImage(image.toString(), "COADM1AO screen data")).hasSize(668);

            // Gate G21 in its sharpest form. Omitting a span - a forgotten FILLER, a forgotten field -
            // is the one defect class that leaves every later offset plausible-looking, so the total is
            // the check that catches it. Drop a single forty-byte option line and 668 is unreachable.
            assertThat(image.length() - AdminMenuResponse.OPTION_LINE_LENGTH)
                    .as("omitting one forty-byte option line must not still total 668")
                    .isNotEqualTo(AdminMenuResponse.PAYLOAD_DATA_LENGTH);

            // And the whole overlay, prefix and strides included, is the 820 bytes both views share.
            final FixedWidthRecord area =
                    new FixedWidthRecord(AdminMenuResponse.SYMBOLIC_MAP_LENGTH, MAP_CHARSET);
            assertThat(area.recordLength()).isEqualTo(820);
            assertThat(area.recordLength()
                    - AdminMenuResponse.TIOAPFX_FILLER_LENGTH
                    - AdminMenuResponse.PAYLOAD_FIELD_COUNT * AdminMenuResponse.ATTRIBUTE_PREFIX_LENGTH)
                    .as("what remains after the prefix and the twenty strides is the screen data")
                    .isEqualTo(668);
        }

        @Test
        @DisplayName("the screen is 24 by 80, as the single DFHMDI declares")
        void screenIsTwentyFourByEighty() {
            assertThat(AdminMenuResponse.SCREEN_ROWS).isEqualTo(24);
            assertThat(AdminMenuResponse.SCREEN_COLUMNS).isEqualTo(80);
        }

        @Test
        @DisplayName("names, widths and values stay positionally aligned across all three views")
        void namesWidthsAndValuesAreAligned() {
            final AdminMenuResponse response = populated();
            assertThat(AdminMenuResponse.PAYLOAD_FIELDS).hasSameSizeAs(EXPECTED_FIELDS);
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_LENGTHS).hasSameSizeAs(EXPECTED_FIELDS);
            assertThat(response.payloadValues()).hasSameSizeAs(EXPECTED_FIELDS);
            assertThat(response.payloadValues().get(0)).isEqualTo(response.trnName());
            assertThat(response.payloadValues().get(6)).isEqualTo(response.optn001());
            assertThat(response.payloadValues().get(17)).isEqualTo(response.optn012());
            assertThat(response.payloadValues().get(18)).isEqualTo(response.option());
            assertThat(response.payloadValues().get(19)).isEqualTo(response.errMsg());
        }

        @Test
        @DisplayName("the screen identity is CA00 / COADM01C / COADM01 / COADM1A")
        void screenIdentityMatchesTheCsdTheProgramAndTheMapset() {
            assertThat(AdminMenuResponse.TRANSACTION_ID).isEqualTo("CA00");
            assertThat(AdminMenuResponse.PROGRAM_NAME).isEqualTo("COADM01C");
            assertThat(AdminMenuResponse.MAPSET_NAME).isEqualTo("COADM01");
            assertThat(AdminMenuResponse.MAP_NAME).isEqualTo("COADM1A");
            assertThat(AdminMenuResponse.TRANSACTION_ID).hasSize(AdminMenuResponse.TRN_NAME_LENGTH);
            assertThat(AdminMenuResponse.PROGRAM_NAME).hasSize(AdminMenuResponse.PGM_NAME_LENGTH);
            assertThat(AdminMenuResponse.MAPSET_NAME)
                    .as("a mapset name is X(7), because the eighth character is the direction suffix")
                    .hasSize(7);
            assertThat(AdminMenuResponse.MAP_NAME).hasSize(7);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Twelve option slots - four filled, ten reachable, twelve declared")
    class TwelveSlots {

        @Test
        @DisplayName("all twelve slots exist and are addressable on a blank response")
        void allTwelveSlotsAreAddressableWhenUnset() {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            assertThat(blank.optionLines())
                    .hasSize(12)
                    .allSatisfy(line -> assertThat(line)
                            .isNotNull()
                            .hasSize(AdminMenuResponse.OPTION_LINE_LENGTH)
                            .isBlank());
        }

        @Test
        @DisplayName("slots 5 to 12 are addressable even though the program never fills them")
        void slotsFiveToTwelveAreAddressableThoughNeverFilled() {
            final AdminMenuResponse response = populated();
            final String blankLine = spaces(AdminMenuResponse.OPTION_LINE_LENGTH);
            assertThat(response.optn005()).isEqualTo(blankLine);
            assertThat(response.optn006()).isEqualTo(blankLine);
            assertThat(response.optn007()).isEqualTo(blankLine);
            assertThat(response.optn008()).isEqualTo(blankLine);
            assertThat(response.optn009()).isEqualTo(blankLine);
            assertThat(response.optn010()).isEqualTo(blankLine);
            assertThat(response.optn011())
                    .as("OPTN011O has no assignment anywhere in COADM01C, yet the mapset declares it")
                    .isEqualTo(blankLine);
            assertThat(response.optn012())
                    .as("OPTN012O likewise")
                    .isEqualTo(blankLine);
        }

        @ParameterizedTest(name = "WS-IDX = {0} addresses OPTN0{0}O")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        @DisplayName("optionLine accepts every COBOL subscript from 1 to 12")
        void optionLineAcceptsEveryCobolSubscript(final int cobolSubscript) {
            final String marker = "line " + cobolSubscript;
            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .optionLine(cobolSubscript, marker)
                    .build();
            assertThat(response.optionLine(cobolSubscript)).isEqualTo(marker);
            assertThat(response.optionLines().get(cobolSubscript - 1))
                    .as("a one-based COBOL subscript is a zero-based Java index")
                    .isEqualTo(marker);
        }

        @ParameterizedTest(name = "subscript {0} is rejected")
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 13, 99, Integer.MAX_VALUE})
        @DisplayName("optionLine rejects a subscript outside the declared twelve, on both sides")
        void optionLineRejectsOutOfRangeSubscripts(final int cobolSubscript) {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> blank.optionLine(cobolSubscript))
                    .withMessageContaining("1 to 12")
                    .withMessageContaining("COADM01");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuResponse.builder().optionLine(cobolSubscript, "x"))
                    .withMessageContaining("1 to 12");
        }

        @Test
        @DisplayName("the twelve slot names are OPTN001O to OPTN012O in order")
        void slotNamesRunFromOptn001oToOptn012o() {
            assertThat(AdminMenuResponse.OPTION_LINE_FIELDS)
                    .containsExactly("OPTN001O",
                            "OPTN002O",
                            "OPTN003O",
                            "OPTN004O",
                            "OPTN005O",
                            "OPTN006O",
                            "OPTN007O",
                            "OPTN008O",
                            "OPTN009O",
                            "OPTN010O",
                            "OPTN011O",
                            "OPTN012O");
        }

        @Test
        @DisplayName("the slot count is twelve - not the four the program fills, not the ten it can reach")
        void slotCountIsTwelveNotFourAndNotTen() {
            assertThat(AdminMenuResponse.OPTION_LINE_COUNT).isEqualTo(12).isNotEqualTo(4).isNotEqualTo(10);
            assertThat(AdminMenuResponse.OPTION_LINE_FIELDS).hasSize(12);
            assertThat(AdminMenuResponse.empty().optionLines()).hasSize(12);
        }

        @Test
        @DisplayName("each named slot setter writes only its own slot")
        void eachNamedSetterWritesOnlyItsOwnSlot() {
            final List<UnaryOperator<AdminMenuResponse.Builder>> setters = List.of(
                    builder -> builder.optn001("v"),
                    builder -> builder.optn002("v"),
                    builder -> builder.optn003("v"),
                    builder -> builder.optn004("v"),
                    builder -> builder.optn005("v"),
                    builder -> builder.optn006("v"),
                    builder -> builder.optn007("v"),
                    builder -> builder.optn008("v"),
                    builder -> builder.optn009("v"),
                    builder -> builder.optn010("v"),
                    builder -> builder.optn011("v"),
                    builder -> builder.optn012("v"));

            for (int slot = 1; slot <= setters.size(); slot++) {
                final AdminMenuResponse response =
                        setters.get(slot - 1).apply(AdminMenuResponse.builder()).build();
                assertThat(response.optionLine(slot)).as("slot %d written", slot).isEqualTo("v");
                assertThat(response.optionLines())
                        .as("only slot %d written", slot)
                        .filteredOn("v"::equals)
                        .hasSize(1);
            }
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The initial state - MOVE LOW-VALUES TO COADM1AO before the first SEND")
    class InitialState {

        @Test
        @DisplayName("every text field is space-filled to its own declared width")
        void everyTextFieldIsSpaceFilledToItsDeclaredWidth() {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            final List<String> values = blank.payloadValues();
            for (int i = 0; i < values.size(); i++) {
                assertThat(values.get(i))
                        .as("%s is spaces at its declared width", EXPECTED_FIELDS.get(i))
                        .isEqualTo(spaces(EXPECTED_WIDTHS.get(i)));
            }
        }

        @Test
        @DisplayName("the communication area is the initial 160-byte COMMAREA")
        void communicationAreaIsTheInitialCommarea() {
            assertThat(AdminMenuResponse.empty().navigationContext())
                    .isEqualTo(NavigationContext.empty());
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        }

        @Test
        @DisplayName("the screen to render is this screen, and the next program is left undecided")
        void nextScreenIsThisScreenAndNextProgramIsUndecided() {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            assertThat(blank.nextMapset()).isEqualTo("COADM01");
            assertThat(blank.nextMap()).isEqualTo("COADM1A");
            assertThat(blank.nextProgram())
                    .as("choosing an XCTL target is a decision, and decisions belong to the service")
                    .isEqualTo(spaces(AdminMenuResponse.NEXT_PROGRAM_LENGTH))
                    .isBlank();
        }

        @Test
        @DisplayName("the repaint signal is off on a blank instance")
        void repaintSignalIsOffOnABlankInstance() {
            assertThat(AdminMenuResponse.empty().resetAllOutputFields()).isFalse();
            assertThat(AdminMenuResponse.builder().resetAllOutputFields(true).build()
                    .resetAllOutputFields()).isTrue();
        }

        @Test
        @DisplayName("builder() and empty() cannot drift apart")
        void builderAndEmptyAgree() {
            assertThat(AdminMenuResponse.builder().build()).isEqualTo(AdminMenuResponse.empty());
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Absent members become their COBOL-initial values, because COBOL has no null")
    class NullNormalisation {

        @Test
        @DisplayName("an absent character member becomes spaces at its declared width")
        void absentCharacterMembersBecomeSpaces() {
            final AdminMenuResponse response = new AdminMenuResponse(null,
                    null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    BmsAttributes.DFHRED, false);

            assertThat(response.trnName()).isEqualTo(spaces(4));
            assertThat(response.title01()).isEqualTo(spaces(40));
            assertThat(response.curDate()).isEqualTo(spaces(8));
            assertThat(response.pgmName()).isEqualTo(spaces(8));
            assertThat(response.title02()).isEqualTo(spaces(40));
            assertThat(response.curTime()).isEqualTo(spaces(8));
            assertThat(response.optionLines()).allSatisfy(line -> assertThat(line).isEqualTo(spaces(40)));
            assertThat(response.option()).isEqualTo(spaces(2));
            assertThat(response.errMsg()).isEqualTo(spaces(78));
            assertThat(response.nextProgram()).isEqualTo(spaces(8));
            assertThat(response.nextMapset()).isEqualTo(spaces(7));
            assertThat(response.nextMap()).isEqualTo(spaces(7));
        }

        @Test
        @DisplayName("an absent communication area becomes the initial COMMAREA, never null")
        void absentCommunicationAreaBecomesTheInitialCommarea() {
            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .navigationContext(null)
                    .build();
            assertThat(response.navigationContext())
                    .isNotNull()
                    .isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a supplied value is stored verbatim - never padded, never trimmed, never truncated")
        void suppliedValuesAreStoredVerbatim() {
            final String shorterThanDeclared = "1";
            final String longerThanDeclared = "x".repeat(200);
            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .option(shorterThanDeclared)
                    .errMsg(longerThanDeclared)
                    .navigationContext(NavigationContext.empty().withUserId("USER0001"))
                    .build();

            assertThat(response.option())
                    .as("padding is FixedWidthCodec's MOVE rule, not this type's")
                    .isEqualTo("1");
            assertThat(response.errMsg())
                    .as("narrowing X(80) to X(78) is the controller's work, not this type's")
                    .isEqualTo(longerThanDeclared);
            assertThat(response.navigationContext().userId()).isEqualTo("USER0001");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("OPTIONO is textual and zero-filled - MOVE WS-OPTION PIC 9(02) TO OPTIONO PIC X(2)")
    class OptionEcho {

        @ParameterizedTest(name = "WS-OPTION {0} renders as \"{1}\"")
        @CsvSource({"1,01", "2,02", "3,03", "4,04", "9,09", "10,10", "12,12", "99,99"})
        @DisplayName("a single-digit option keeps its leading zero")
        void singleDigitOptionsKeepTheirLeadingZero(final String entered, final String rendered) {
            final AdminMenuResponse response = AdminMenuResponse.builder().option(rendered).build();
            assertThat(response.option())
                    .as("WS-OPTION %s moved into a PIC X(2) receiver", entered)
                    .isEqualTo(rendered)
                    .hasSize(AdminMenuResponse.OPTION_LENGTH);
        }

        @Test
        @DisplayName("\"nothing entered yet\" is spaces - a third state no integer has")
        void nothingEnteredYetIsSpaces() {
            assertThat(AdminMenuResponse.empty().option()).isEqualTo("  ").isNotEqualTo("00");
        }

        @Test
        @DisplayName("the echo is a String, so \"01\" and \"1\" stay distinct")
        void theEchoIsTextualSoLeadingZerosAreSignificant() {
            assertThat(AdminMenuResponse.builder().option("01").build().option())
                    .isNotEqualTo(AdminMenuResponse.builder().option("1").build().option());
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Navigation - EXEC CICS XCTL becomes three response members")
    class Navigation {

        @ParameterizedTest(name = "nextProgram can carry {0}")
        @ValueSource(strings = {"COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C", "COSGN00C"})
        @DisplayName("both XCTL sites are expressible: the four option targets and the sign-on return")
        void bothXctlSitesAreExpressible(final String target) {
            final AdminMenuResponse response =
                    AdminMenuResponse.builder().nextProgram(target).build();
            assertThat(response.nextProgram())
                    .isEqualTo(target)
                    .hasSizeLessThanOrEqualTo(AdminMenuResponse.NEXT_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("the sign-on literal of COADM01C is recorded, but is not a default")
        void signonProgramIsRecordedButNotDefaulted() {
            assertThat(AdminMenuResponse.SIGNON_PROGRAM).isEqualTo("COSGN00C");
            assertThat(AdminMenuResponse.empty().nextProgram())
                    .isNotEqualTo(AdminMenuResponse.SIGNON_PROGRAM);
        }

        @Test
        @DisplayName("the navigation widths come from the COMMAREA: a program is 8, a map is 7")
        void navigationWidthsComeFromTheCommarea() {
            assertThat(AdminMenuResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(8)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(AdminMenuResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(AdminMenuResponse.NEXT_MAP_LENGTH)
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("the screen to render is overridable, so the client can be sent elsewhere")
        void theScreenToRenderIsOverridable() {
            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .nextMapset("COUSR00")
                    .nextMap("COUSR0A")
                    .build();
            assertThat(response.nextMapset()).isEqualTo("COUSR00");
            assertThat(response.nextMap()).isEqualTo("COUSR0A");
        }
    }

    // =================================================================================================

    /**
     * The error-message colour: {@code DFHRED} by map declaration, {@code DFHGREEN} by program
     * override, and metadata either way.
     *
     * <p><strong>Provenance of the two constant values (Agent Action Plan risk R-D, practice B12).</strong>
     * {@code DFHRED} and {@code DFHGREEN} are defined in the IBM-supplied copybooks {@code DFHBMSCA}
     * and {@code DFHATTR}, which {@code app/cbl/COADM01C.cbl:60-61} copies but which are
     * <strong>absent from this repository</strong> - they ship with CICS in {@code SDFHCOB} and were
     * never checked in. Their byte values are therefore reproduced in
     * {@link com.vsergeychik.carddemo.common.BmsAttributes} from <strong>IBM CICS documentation</strong>
     * rather than read from source, and asserting them here is what pins that transcription down. No
     * dependency was added to obtain them; the alternative to hand-writing them is an IBM artefact this
     * migration is not permitted to introduce.
     *
     * <p>The values happen to be the EBCDIC code points of the digits {@code '2'} and {@code '4'} -
     * {@code 0xF2} and {@code 0xF4} - because CICS declares these constants as {@code PIC X} character
     * literals, so a colour is carried as a character rather than as a number.
     */
    @Nested
    @DisplayName("The message colour - DFHRED by declaration, DFHGREEN by override, metadata either way")
    class MessageColour {

        @Test
        @DisplayName("a freshly built response carries DFHRED, as the mapset's COLOR=RED declares")
        void freshResponseCarriesDfhred() {
            // app/bms/COADM01.bms:154-157 declares ERRMSG with COLOR=RED on line 155. Red is what the
            // terminal paints unless the program moves something else in, so red is the default here.
            assertThat(AdminMenuResponse.empty().messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(AdminMenuResponse.builder().build().messageColour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(populated().messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(BmsAttributes.DFHRED).isEqualTo((byte) 0xF2);
        }

        @Test
        @DisplayName("the coming-soon path can move DFHGREEN in, exactly as COADM01C:148 does")
        void comingSoonPathCanMoveDfhgreenIn() {
            final AdminMenuResponse response = populated().toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .errMsg("This option is coming soon ...")
                    .build();
            assertThat(response.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(BmsAttributes.DFHGREEN).isEqualTo((byte) 0xF4);
            assertThat(response.errMsg())
                    .as("the admin text carries no option name - COADM01C:150-151 are commented out")
                    .isEqualTo("This option is coming soon ...")
                    .doesNotContain("User List");
        }

        @Test
        @DisplayName("the colour renders as its DFHBMSCA mnemonic and as unsigned hex")
        void colourRendersAsMnemonicAndHex() {
            assertThat(AdminMenuResponse.empty().messageColourMnemonic())
                    .isEqualTo(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED));
            assertThat(AdminMenuResponse.empty().messageColourHex())
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED));
            assertThat(AdminMenuResponse.builder().messageColour(BmsAttributes.DFHGREEN).build()
                    .messageColourMnemonic())
                    .isEqualTo(BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN));
        }

        @Test
        @DisplayName("the default is never the terminal default colour")
        void defaultIsNeverDfhdfcol() {
            assertThat(AdminMenuResponse.empty().messageColour())
                    .as("a builder whose primitive defaulted would give DFHDFCOL")
                    .isNotEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("the colour is metadata: carried, but never under the ERRMSGC field name")
        void theColourIsMetadataNotAnErrMsgCPayloadMember() throws Exception {
            // app/cpy-bms/COADM01.CPY:256 declares ERRMSGC PICTURE X as one of the four attribute bytes
            // that precede ERRMSGO. Attribute bytes are presentation metadata, not screen text, so the
            // colour must be reachable in Java without ever appearing as a payload field named errMsgC -
            // which would be a metadata item masquerading as one of the twenty DFHMDF fields (gate G9).
            final AdminMenuResponse response = populated().toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .build();

            // Carried: the service sets it and this assertion reads it.
            assertThat(response.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(response.screenMetadata().messageColour())
                    .isEqualTo(Byte.toUnsignedInt(BmsAttributes.DFHGREEN));

            // But not a payload member, under any spelling of the copybook's own name.
            final List<String> components = Stream.of(AdminMenuResponse.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
            assertThat(components)
                    .as("ERRMSGC is metadata; the colour rides as messageColour instead")
                    .doesNotContain("errMsgC", "errmsgc", "ERRMSGC")
                    .contains("messageColour");

            // And it is @JsonIgnore, so it is absent from the document as well as from the field set.
            assertThat(AdminMenuResponse.class
                    .getRecordComponents()[components.indexOf("messageColour")]
                    .getAccessor()
                    .isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class))
                    .isTrue();
            assertThat(new ObjectMapper().writeValueAsString(response))
                    .doesNotContain("errMsgC")
                    .doesNotContain("messageColour");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("JSON - twenty screen fields plus five carriers, and no attribute metadata")
    class Json {

        private final ObjectMapper mapper = new ObjectMapper();

        private Map<String, Object> serialize(final AdminMenuResponse response) throws Exception {
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = mapper.readValue(mapper.writeValueAsString(response),
                    LinkedHashMap.class);
            return map;
        }

        @Test
        @DisplayName("the published keys are exactly the twenty-six declared members")
        void publishedKeysAreExactlyTheDeclaredMembers() throws Exception {
            assertThat(serialize(populated()).keySet())
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_KEYS);
        }

        @Test
        @DisplayName("a non-default colour and reset signal do not survive a round trip, by design")
        void theColourDoesNotTravel() throws Exception {
            final AdminMenuResponse coloured = populated().toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .resetAllOutputFields(true)
                    .build();

            final AdminMenuResponse after =
                    mapper.readValue(mapper.writeValueAsString(coloured), AdminMenuResponse.class);

            assertThat(after.messageColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(after.resetAllOutputFields()).isFalse();
            // ...while everything that traces to a DFHMDF definition crosses unchanged.
            assertThat(after.payloadValues()).containsExactlyElementsOf(coloured.payloadValues());
            assertThat(after.navigationContext()).isEqualTo(coloured.navigationContext());
        }

        @Test
        @DisplayName("the ERRMSGC colour byte and the reset signal are not JSON properties")
        void theTwoMetadataMembersAreNotPublished() throws Exception {
            final AdminMenuResponse response = populated().toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .resetAllOutputFields(true)
                    .build();

            assertThat(serialize(response).keySet()).doesNotContainAnyElementsOf(UNPUBLISHED_MEMBERS);

            // Both are still components of the record - reachable in Java, where the service sets them
            // and this assertion reads them - which is what "internal" means here.
            assertThat(response.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(response.resetAllOutputFields()).isTrue();
            assertThat(Stream.of(AdminMenuResponse.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).toList())
                    .containsAll(UNPUBLISHED_MEMBERS);
        }

        @Test
        @DisplayName("no xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item leaks into the payload")
        void noAttributeMetadataLeaksIntoThePayload() throws Exception {
            final List<String> keys = new ArrayList<>(serialize(populated()).keySet());
            final List<String> forbidden = new ArrayList<>();
            for (final String base : EXPECTED_JSON_KEYS.subList(0, 20)) {
                for (final String suffix : METADATA_SUFFIXES) {
                    forbidden.add(base + suffix);
                }
            }
            for (final String base : EXPECTED_FIELDS) {
                final String stem = base.substring(0, base.length() - 1);
                for (final String suffix : METADATA_SUFFIXES) {
                    forbidden.add(stem + suffix);
                }
            }
            assertThat(keys).doesNotContainAnyElementsOf(forbidden);

            // The reflective sweep above is paired with assertions that NAME their cases, because a
            // loop that silently iterated over an empty list would also "pass" (practice B11). One
            // named case per attribute plane of the output view, so no plane can be forgotten:
            //   xxxC - the colour byte    (COADM01.CPY:256 ERRMSGC)
            //   xxxP - programmed symbol  (COADM01.CPY:251 OPTIONP)
            //   xxxH - the highlight byte (COADM01.CPY:150 TITLE01H)
            //   xxxV - the validation byte(COADM01.CPY:157 CURDATEV)
            assertThat(keys)
                    .as("the four attribute bytes are metadata; the colour is carried separately")
                    .doesNotContain("errMsgC", "ERRMSGC", "errMsgA", "trnNameL", "optn001C")
                    .doesNotContain("optionP", "OPTIONP")
                    .doesNotContain("title01H", "TITLE01H")
                    .doesNotContain("curDateV", "CURDATEV");

            // Nor are they record components under those names. The colour IS carried - by
            // messageColour, asserted in MessageColour below - but never under the copybook's own
            // ERRMSGC name, because that name belongs to a metadata byte rather than to screen text.
            final List<String> components = Stream.of(AdminMenuResponse.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
            assertThat(components)
                    .doesNotContain("errMsgC", "optionP", "title01H", "curDateV")
                    .contains("messageColour");
        }

        @Test
        @DisplayName("no derived view is published")
        void noDerivedViewIsPublished() throws Exception {
            assertThat(serialize(populated()).keySet())
                    .doesNotContain("optionLines",
                            "payloadValues",
                            "messageColourMnemonic",
                            "messageColourHex");
        }

        @Test
        @DisplayName("a round trip preserves all twenty fields, spaces and leading zeros included")
        void roundTripPreservesEveryField() throws Exception {
            final AdminMenuResponse original = populated();
            final String json = mapper.writeValueAsString(original);
            final AdminMenuResponse restored = mapper.readValue(json, AdminMenuResponse.class);

            // Every published member survives; the two ignored ones do not travel, so whole-object
            // equality is deliberately NOT the property under test here - see theColourDoesNotTravel.
            assertThat(restored.payloadValues()).containsExactlyElementsOf(original.payloadValues());
            assertThat(restored.option()).isEqualTo("01");
            assertThat(restored.optn012())
                    .as("an all-spaces value must not be trimmed away")
                    .isEqualTo(spaces(AdminMenuResponse.OPTION_LINE_LENGTH));
            assertThat(restored.navigationContext()).isEqualTo(original.navigationContext());
            // The colour is not on the wire, so it comes back as DFHDFCOL - the terminal's default
            // colour, X'00' - rather than being carried. That is the point of ignoring it: it is a
            // presentation attribute the server decides on each response, not state a client echoes.
            assertThat(restored.messageColour()).isEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("a blank response round-trips too, and absent keys normalise rather than fail")
        void blankResponseRoundTrips() throws Exception {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            final AdminMenuResponse afterBlank =
                    mapper.readValue(mapper.writeValueAsString(blank), AdminMenuResponse.class);

            // Every published member survives a blank round trip...
            assertThat(afterBlank.payloadValues()).containsExactlyElementsOf(blank.payloadValues());
            assertThat(afterBlank.navigationContext()).isEqualTo(blank.navigationContext());
            assertThat(afterBlank.nextMapset()).isEqualTo(blank.nextMapset());
            assertThat(afterBlank.nextMap()).isEqualTo(blank.nextMap());

            // ...and the two ignored members come back at the Java defaults the canonical constructor
            // receives, because nothing about them reached the document. DFHDFCOL is X'00', the
            // terminal's own default colour, so the result is a coherent response rather than a
            // nonsense attribute.
            assertThat(afterBlank.messageColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(afterBlank.resetAllOutputFields()).isFalse();
            assertThat(mapper.readValue("{}", AdminMenuResponse.class).optionLines())
                    .hasSize(12)
                    .allSatisfy(line -> assertThat(line).isEqualTo(spaces(40)));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Immutability - a handed-out response cannot change underneath its holder")
    class Immutability {

        @Test
        @DisplayName("the twelve option lines are handed back unmodifiable")
        void optionLinesAreUnmodifiable() {
            final List<String> lines = AdminMenuResponse.empty().optionLines();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> lines.set(0, "mutated"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> lines.add("appended"));
            assertThat(AdminMenuResponse.empty().optionLines().get(0)).isEqualTo(spaces(40));
        }

        @Test
        @DisplayName("the twenty payload values are handed back unmodifiable")
        void payloadValuesAreUnmodifiable() {
            final List<String> values = populated().payloadValues();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> values.set(0, "mutated"));
            assertThat(populated().trnName()).isEqualTo("CA00");
        }

        @Test
        @DisplayName("the static name and width tables are unmodifiable")
        void staticTablesAreUnmodifiable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> AdminMenuResponse.PAYLOAD_FIELDS.set(0, "mutated"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> AdminMenuResponse.OPTION_LINE_FIELDS.clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> AdminMenuResponse.PAYLOAD_FIELD_LENGTHS.set(0, 99));
            assertThat(AdminMenuResponse.PAYLOAD_FIELDS).containsExactlyElementsOf(EXPECTED_FIELDS);
        }

        @Test
        @DisplayName("toBuilder produces a copy, leaving the original untouched")
        void toBuilderProducesACopy() {
            final AdminMenuResponse original = populated();
            final AdminMenuResponse modified = original.toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .optn001("changed")
                    .nextProgram("COSGN00C")
                    .build();

            assertThat(original.messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(original.optn001()).isEqualTo("01. User List (Security)                ");
            assertThat(original.nextProgram()).isEqualTo("COUSR00C");
            assertThat(modified.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(modified.optn001()).isEqualTo("changed");
            assertThat(modified.nextProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("reusing a builder does not disturb a value it already produced")
        void reusingABuilderDoesNotDisturbAnAlreadyBuiltValue() {
            final AdminMenuResponse.Builder builder = AdminMenuResponse.builder().optn001("first");
            final AdminMenuResponse first = builder.build();
            builder.optn001("second").optn002("also second");
            final AdminMenuResponse second = builder.build();

            assertThat(first.optn001()).isEqualTo("first");
            assertThat(first.optn002()).isEqualTo(spaces(40));
            assertThat(second.optn001()).isEqualTo("second");
            assertThat(second.optn002()).isEqualTo("also second");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Statelessness - the conversation travels in the body, not on the server")
    class Statelessness {

        @Test
        @DisplayName("the echoed COMMAREA carries the user type and the program context back")
        void echoedCommareaCarriesUserTypeAndProgramContext() {
            final AdminMenuResponse response = populated();
            assertThat(response.navigationContext().isAdmin()).isTrue();
            assertThat(response.navigationContext().isUser()).isFalse();
            assertThat(response.navigationContext().isReenter()).isTrue();
            assertThat(response.navigationContext().isEnter()).isFalse();
            assertThat(response.navigationContext().userId()).isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName("the first-entry repaint is a payload flag, not a server-side screen buffer")
        void firstEntryRepaintIsAPayloadFlag() {
            final AdminMenuResponse firstEntry = AdminMenuResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmReenter())
                    .resetAllOutputFields(true)
                    .build();
            assertThat(firstEntry.resetAllOutputFields()).isTrue();
            assertThat(firstEntry.navigationContext().isReenter()).isTrue();
            assertThat(firstEntry.payloadValues())
                    .as("LOW-VALUES leaves every output field blank")
                    .allSatisfy(value -> assertThat(value).isBlank());
        }

        @Test
        @DisplayName("there is no server-side session state of any kind")
        void thereIsNoServerSideSessionState() {
            // Gates G37 and G53, practice B9, rule R6. A record's component fields are private and
            // final, so the only place state could hide is a static field - and a static mutable holder
            // would be a session by another name, breaking request isolation exactly as an HttpSession
            // would and making tests order-dependent into the bargain.
            //
            // SYNTHETIC FIELDS ARE SKIPPED, and that is not a loophole. Under the JaCoCo agent - which
            // app/java/pom.xml attaches to both `test` and `verify` - every instrumented class gains a
            // synthetic `private static transient boolean[] $jacocoData`, which is static and NOT final.
            // Asserting over synthetic members would pass under plain compilation and fail under the
            // coverage gate while proving nothing about declared state either way.
            final List<Field> declaredState = Stream.of(AdminMenuResponse.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .toList();

            assertThat(declaredState).as("a record declares one field per component").isNotEmpty();
            for (final Field field : declaredState) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final - no static mutable state",
                                    field.getName())
                            .isTrue();
                } else {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                }
            }

            assertThat(AdminMenuResponse.class.isRecord()).isTrue();

            // Named as well as swept (practice B11): no servlet, session or thread-local type may appear
            // among the component types. Everything the conversation needs is a String, a byte, a
            // boolean or the NavigationContext that carries the COMMAREA.
            assertThat(Stream.of(AdminMenuResponse.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder("java.lang.String",
                            "byte",
                            "boolean",
                            NavigationContext.class.getName());
        }

        @Test
        @DisplayName("the carried COMMAREA really is 160 bytes, at the code page stated explicitly")
        void theCarriedCommareaIsOneHundredSixtyBytes() {
            // app/cpy/COCOM01Y.cpy: CDEMO-GENERAL-INFO 34 (4+8+4+8+8+1+1) + CDEMO-CUSTOMER-INFO 84
            // (9+25+25+25) + CDEMO-ACCOUNT-INFO 12 (11+1) + CDEMO-CARD-INFO 16 + CDEMO-MORE-INFO 14
            // (7+7) = 160. The arithmetic is written out by hand (practice B11).
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(34 + 84 + 12 + 16 + 14)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(160);

            // CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are PIC X(7) at COCOM01Y.cpy:43-44 - seven, not the
            // eight a reader would infer from CDEMO-FROM-PROGRAM PIC X(08) twenty lines earlier. Seven
            // is right, because a map name is seven characters: COADM1A. The total depends on it.
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(AdminMenuResponse.MAP_NAME).hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(AdminMenuResponse.MAPSET_NAME).hasSize(NavigationContext.LAST_MAPSET_LENGTH);

            // And the image the carried context renders really is that wide, with the code page named
            // explicitly rather than defaulted (practice B8).
            final FixedWidthCodec codec = codec();
            assertThat(codec.charset()).isEqualTo(MAP_CHARSET);
            assertThat(populated().navigationContext().toFixedWidth(codec)).hasSize(160);
            assertThat(AdminMenuResponse.empty().navigationContext().toFixedWidth(codec)).hasSize(160);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Value semantics - every one of the twenty-six members participates in equality")
    class ValueSemantics {

        static Stream<Arguments> oneChangePerMember() {
            return Stream.of(
                    Arguments.of("trnName", op(builder -> builder.trnName("ZZZZ"))),
                    Arguments.of("title01", op(builder -> builder.title01("changed"))),
                    Arguments.of("curDate", op(builder -> builder.curDate("01/01/00"))),
                    Arguments.of("pgmName", op(builder -> builder.pgmName("ZZZZZZZZ"))),
                    Arguments.of("title02", op(builder -> builder.title02("changed"))),
                    Arguments.of("curTime", op(builder -> builder.curTime("00:00:00"))),
                    Arguments.of("optn001", op(builder -> builder.optn001("changed"))),
                    Arguments.of("optn002", op(builder -> builder.optn002("changed"))),
                    Arguments.of("optn003", op(builder -> builder.optn003("changed"))),
                    Arguments.of("optn004", op(builder -> builder.optn004("changed"))),
                    Arguments.of("optn005", op(builder -> builder.optn005("changed"))),
                    Arguments.of("optn006", op(builder -> builder.optn006("changed"))),
                    Arguments.of("optn007", op(builder -> builder.optn007("changed"))),
                    Arguments.of("optn008", op(builder -> builder.optn008("changed"))),
                    Arguments.of("optn009", op(builder -> builder.optn009("changed"))),
                    Arguments.of("optn010", op(builder -> builder.optn010("changed"))),
                    Arguments.of("optn011", op(builder -> builder.optn011("changed"))),
                    Arguments.of("optn012", op(builder -> builder.optn012("changed"))),
                    Arguments.of("option", op(builder -> builder.option("99"))),
                    Arguments.of("errMsg", op(builder -> builder.errMsg("changed"))),
                    Arguments.of("navigationContext",
                            op(builder -> builder.navigationContext(
                                    NavigationContext.empty().withUserId("OTHER001")))),
                    Arguments.of("nextProgram", op(builder -> builder.nextProgram("COSGN00C"))),
                    Arguments.of("nextMapset", op(builder -> builder.nextMapset("COMEN01"))),
                    Arguments.of("nextMap", op(builder -> builder.nextMap("COMEN1A"))),
                    Arguments.of("messageColour",
                            op(builder -> builder.messageColour(BmsAttributes.DFHGREEN))),
                    Arguments.of("resetAllOutputFields",
                            op(builder -> builder.resetAllOutputFields(true))));
        }

        private static UnaryOperator<AdminMenuResponse.Builder> op(
                final UnaryOperator<AdminMenuResponse.Builder> operator) {
            return operator;
        }

        @ParameterizedTest(name = "changing {0} breaks equality")
        @MethodSource("oneChangePerMember")
        @DisplayName("a difference in any single member is observable")
        void aDifferenceInAnySingleMemberIsObservable(
                final String member, final UnaryOperator<AdminMenuResponse.Builder> change) {
            final AdminMenuResponse original = populated();
            final AdminMenuResponse changed = change.apply(original.toBuilder()).build();

            assertThat(changed).as("%s must participate in equals", member).isNotEqualTo(original);
            assertThat(changed.hashCode())
                    .as("%s must participate in hashCode", member)
                    .isNotEqualTo(original.hashCode());
        }

        @Test
        @DisplayName("two identically built responses are equal and share a hash code")
        void identicallyBuiltResponsesAreEqual() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(AdminMenuResponse.empty()).isEqualTo(AdminMenuResponse.empty());
        }

        @Test
        @DisplayName("a response is not equal to null or to an unrelated type")
        void aResponseIsNotEqualToNullOrToAnUnrelatedType() {
            final AdminMenuResponse response = populated();
            assertThat(response).isNotEqualTo(null).isNotEqualTo("CA00").isEqualTo(response);
        }

        @Test
        @DisplayName("toString names the type and carries the screen fields")
        void toStringNamesTheTypeAndCarriesTheScreenFields() {
            assertThat(populated().toString())
                    .contains("AdminMenuResponse")
                    .contains("trnName=CA00")
                    .contains("option=01");
        }
    }
    // =================================================================================================
    // The metadata envelope - separate from the field projection, and present
    // =================================================================================================

    @Nested
    @DisplayName("screenMetadata - the two values no JSON member of this record carries")
    class TheMetadataEnvelope {

        @Test
        @DisplayName("it publishes the message colour and the repaint signal, and nothing else")
        void itPublishesTheTwoMetadataValues() {
            final ScreenMetadata metadata = AdminMenuResponse.builder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .resetAllOutputFields(true)
                    .build()
                    .screenMetadata();

            assertThat(metadata.messageColour())
                    .as("published unsigned: DFHGREEN is 0xF4, which as a byte would read -12")
                    .isEqualTo(Byte.toUnsignedInt(BmsAttributes.DFHGREEN));
            assertThat(metadata.resetAllOutputFields()).isTrue();
        }

        @Test
        @DisplayName("the field map is empty, because COADM01 declares no per-field attribute quads")
        void theFieldMapIsAccuratelyEmpty() {
            assertThat(AdminMenuResponse.empty().screenMetadata().fields()).isEmpty();
        }

        @Test
        @DisplayName("no cursor is named: COADM01C contains no MOVE -1 TO <field>L at all")
        void noCursorIsNamed() {
            assertThat(AdminMenuResponse.empty().screenMetadata().cursorField()).isNull();
            assertThat(populated().screenMetadata().cursorField()).isNull();
        }

        @Test
        @DisplayName("the default state is DFHRED and no repaint, matching the mapset's COLOR=RED")
        void theDefaultStateIsTheMapsetDeclaration() {
            final ScreenMetadata metadata = AdminMenuResponse.empty().screenMetadata();

            assertThat(metadata.messageColour())
                    .isEqualTo(Byte.toUnsignedInt(BmsAttributes.DFHRED));
            assertThat(metadata.resetAllOutputFields()).isFalse();
        }

        @Test
        @DisplayName("the projection is itself @JsonIgnore, so it adds no member to this document")
        void theProjectionIsNotAJsonMember() throws Exception {
            assertThat(AdminMenuResponse.class.getMethod("screenMetadata")
                    .isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)).isTrue();
            assertThat(new ObjectMapper().writeValueAsString(populated()))
                    .doesNotContain("screenMetadata")
                    .doesNotContain("messageColour")
                    .doesNotContain("resetAllOutputFields");
        }
    }

    // =================================================================================================
    // ERRMSGO is X(78) and receives an X(80) image. Which two bytes are lost is the question.
    // =================================================================================================

    /**
     * The narrowing on the way to {@code ERRMSGO}.
     *
     * <p>{@code COADM01C} composes every message in {@code WS-MESSAGE PIC X(80)}
     * [{@code app/cbl/COADM01C.cbl:38}] and {@code app/cbl/COADM01C.cbl:177} moves it to
     * {@code ERRMSGO}, declared {@code PIC X(78)} at {@code app/cpy-bms/COADM01.CPY:260} and
     * {@code LENGTH=78} at {@code app/bms/COADM01.bms:156}. A cross-width <em>alphanumeric</em>
     * {@code MOVE} fills the receiver from its leftmost position and discards the overflow, so the two
     * bytes lost are positions <strong>79 and 80</strong> - never 1 and 2.
     *
     * <p>{@link AdminMenuResponse} does not perform that narrowing; the controller does, through
     * {@link FixedWidthCodec}. What is proven here is therefore twofold: the record's declared width is
     * 78, and the codec's {@code PIC X} rule truncates in the direction COBOL truncates.
     */
    @Nested
    @DisplayName("ERRMSGO - an X(80) message narrowed to X(78) by RIGHT truncation")
    class ErrMsgNarrowing {

        @Test
        @DisplayName("a distinguishable 80-character probe loses positions 79 and 80, not 1 and 2")
        void aDistinguishableProbeProvesTruncationIsOnTheRight() {
            // The real messages cannot answer this question. The validation text is 37 characters and
            // CCDA-MSG-INVALID-KEY is 50 - both shorter than 78, so both survive either direction
            // unchanged and a test built only from them would pass under LEFT truncation too. Hence a
            // synthetic probe that is exactly 80 characters and whose every position is identifiable.
            final StringBuilder probe = new StringBuilder();
            for (int position = 1; position <= WS_MESSAGE_LENGTH; position++) {
                // Every tenth position carries a distinct decade marker, so a shifted result is visible
                // at a glance rather than merely unequal.
                probe.append(position % 10 == 0 ? Character.forDigit(position / 10, 16) : '.');
            }
            assertThat(probe.length()).isEqualTo(80);

            final FixedWidthCodec codec = codec();
            assertThat(codec.charset()).isEqualTo(MAP_CHARSET);

            final String narrowed = codec.movePicX(probe.toString(), AdminMenuResponse.ERR_MSG_LENGTH);

            assertThat(narrowed).hasSize(78).isEqualTo(probe.substring(0, 78));
            assertThat(narrowed.charAt(0))
                    .as("position 1 must survive - a left truncation would have discarded it")
                    .isEqualTo(probe.charAt(0));
            assertThat(narrowed.charAt(69))
                    .as("the decade marker at position 70 must still be at position 70")
                    .isEqualTo('7');
            assertThat(narrowed)
                    .as("the position-80 marker is the byte that must be gone")
                    .doesNotContain("8");

            // The same claim in the simplest possible form: two characters that exist only at 79 and 80.
            final String tagged = "A".repeat(78) + "YZ";
            assertThat(tagged).hasSize(80);
            assertThat(codec.movePicX(tagged, AdminMenuResponse.ERR_MSG_LENGTH))
                    .hasSize(78)
                    .isEqualTo("A".repeat(78))
                    .doesNotContain("Y")
                    .doesNotContain("Z");

            // And the field the narrowed image lands in is 78 wide, never 80.
            assertThat(AdminMenuResponse.builder().errMsg(narrowed).build().errMsg())
                    .hasSize(AdminMenuResponse.ERR_MSG_LENGTH)
                    .hasSize(78);
            assertThat(WS_MESSAGE_LENGTH - AdminMenuResponse.ERR_MSG_LENGTH)
                    .as("exactly two bytes are lost")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a message shorter than the field is padded on the right, to exactly 78")
        void aShortMessageIsRightPaddedToSeventyEight() {
            final FixedWidthCodec codec = codec();
            final String padded = codec.movePicX(VALIDATION_MESSAGE, AdminMenuResponse.ERR_MSG_LENGTH);

            assertThat(VALIDATION_MESSAGE).hasSize(37);
            assertThat(padded)
                    .hasSize(78)
                    .startsWith(VALIDATION_MESSAGE)
                    .endsWith(spaces(78 - 37));
            assertThat(AdminMenuResponse.builder().errMsg(padded).build().errMsg())
                    .isEqualTo(padded)
                    .hasSize(78);
        }

        @Test
        @DisplayName("no message at all is 78 spaces - never null, never empty")
        void noMessageIsSeventyEightSpaces() {
            // app/cbl/COADM01C.cbl:79-80 opens MAIN-PARA with MOVE SPACES TO WS-MESSAGE, ERRMSGO OF
            // COADM1AO. Spaces, not a null and not a zero-length string: COBOL has no such state, and a
            // client painting a 78-column screen row needs 78 characters to paint.
            assertThat(AdminMenuResponse.empty().errMsg())
                    .isNotNull()
                    .isNotEmpty()
                    .hasSize(78)
                    .isEqualTo(spaces(AdminMenuResponse.ERR_MSG_LENGTH))
                    .isBlank();
            assertThat(codec().movePicX("", AdminMenuResponse.ERR_MSG_LENGTH))
                    .isEqualTo(spaces(78))
                    .hasSize(78);
        }

        @ParameterizedTest(name = "{0} narrows to exactly 78 characters")
        @MethodSource(
                "com.vsergeychik.carddemo.admin.dto.AdminMenuResponseTest#everyMessagePathOfCoadm01c")
        @DisplayName("every message path of COADM01C reaches ERRMSGO at exactly 78 characters")
        void everyMessagePathReachesSeventyEightCharacters(final String path, final String message) {
            final String narrowed = codec().movePicX(message, AdminMenuResponse.ERR_MSG_LENGTH);

            assertThat(narrowed).as("%s", path).hasSize(78).hasSize(AdminMenuResponse.ERR_MSG_LENGTH);
            assertThat(narrowed.stripTrailing())
                    .as("%s keeps its text, only its padding differs", path)
                    .isEqualTo(message.stripTrailing());
            assertThat(AdminMenuResponse.builder().errMsg(narrowed).build().errMsg()).hasSize(78);
        }

        @Test
        @DisplayName("the coming-soon text keeps the space before \"is\", unlike COMEN01C's")
        void theComingSoonTextKeepsTheSpaceBeforeIs() {
            // app/cbl/COADM01C.cbl:149-153. The STRING concatenates 'This option ' and
            // 'is coming soon ...' DELIMITED BY SIZE with the CDEMO-ADMIN-OPT-NAME interpolation
            // COMMENTED OUT at 150-151, so the trailing space of the first operand survives.
            //
            // COMEN01C's equivalent is live and uses DELIMITED BY SPACE, which closes the gap up and
            // produces the no-space form. The two texts genuinely differ and must not be harmonised
            // (practice B4): each screen emits what its own program emits.
            assertThat(COMING_SOON_MESSAGE)
                    .isEqualTo("This option is coming soon ...")
                    .contains("option is coming")
                    .doesNotContain("optionis")
                    .hasSize(30);

            final String narrowed = codec().movePicX(COMING_SOON_MESSAGE,
                    AdminMenuResponse.ERR_MSG_LENGTH);
            final AdminMenuResponse response = populated().toBuilder()
                    .errMsg(narrowed)
                    .messageColour(BmsAttributes.DFHGREEN)
                    .build();

            assertThat(response.errMsg())
                    .hasSize(78)
                    .startsWith("This option is coming soon ...")
                    .as("no option name is interpolated on the admin screen")
                    .doesNotContain("User List")
                    .doesNotContain("Security");
            assertThat(response.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
        }

        @Test
        @DisplayName("the invalid-key path carries CSMSG01Y's X(50) text, narrowed to 78")
        void theInvalidKeyPathCarriesTheFiftyByteText() {
            // app/cbl/COADM01C.cbl:101, on the WHEN OTHER arm of EVALUATE EIBAID: MOVE
            // CCDA-MSG-INVALID-KEY TO WS-MESSAGE. The sending field is PIC X(50)
            // [app/cpy/CSMSG01Y.cpy:20-21], so it lands in the 80-byte WS-MESSAGE left-justified and
            // reaches ERRMSGO padded, never truncated - 50 is well inside 78.
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(50);
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);

            final String narrowed = codec().movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                    AdminMenuResponse.ERR_MSG_LENGTH);

            assertThat(narrowed)
                    .hasSize(78)
                    .startsWith("Invalid key pressed. Please see below...")
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY + spaces(78 - 50));
            assertThat(AdminMenuResponse.builder().errMsg(narrowed).build().errMsg()).hasSize(78);
        }

        @Test
        @DisplayName("PIC X truncates right where PIC 9 truncates left - the helpers cannot be swapped")
        void picXAndPic9TruncateInOppositeDirections() {
            // Stated together, in one place, because the two rules are mirror images and a codebase
            // that swapped them would still compile and would still produce values of the right width.
            //   PIC X receiver: filled from the LEFT, overflow discarded from the RIGHT.
            //   PIC 9 receiver: aligned on the implied decimal point, so the HIGH-order digits go.
            final FixedWidthCodec codec = codec();

            assertThat(codec.movePicX("ABCDEF", 4)).isEqualTo("ABCD").isNotEqualTo("CDEF");
            assertThat(codec.movePic9("123456", 4)).isEqualTo("3456").isNotEqualTo("1234");

            // The two screen fields that exercise them on this map are ERRMSGO and OPTIONO.
            assertThat(AdminMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);
            assertThat(AdminMenuResponse.OPTION_LENGTH).isEqualTo(2);
        }
    }

    /**
     * Every path by which {@code COADM01C} puts text into {@code WS-MESSAGE}, each cited to the line
     * that writes it. Used by {@link ErrMsgNarrowing#everyMessagePathReachesSeventyEightCharacters}.
     *
     * @return one case per message path: a description and the 80-byte sending text
     */
    static Stream<Arguments> everyMessagePathOfCoadm01c() {
        return Stream.of(
                Arguments.of("MOVE SPACES TO WS-MESSAGE (COADM01C:79)", ""),
                Arguments.of("'Please enter a valid option number...' (COADM01C:131-132)",
                        VALIDATION_MESSAGE),
                Arguments.of("MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE (COADM01C:101)",
                        SystemMessages.CCDA_MSG_INVALID_KEY),
                Arguments.of("STRING 'This option ' 'is coming soon ...' (COADM01C:149-153)",
                        COMING_SOON_MESSAGE));
    }

    // =================================================================================================
    // OPTIONO is the two-digit zero-filled image of WS-OPTION PIC 9(02).
    // =================================================================================================

    /**
     * The option echo, produced by {@code MOVE WS-OPTION TO OPTIONO OF COADM1AO}
     * [{@code app/cbl/COADM01C.cbl:125}] where {@code WS-OPTION} is {@code PIC 9(02)}
     * [{@code app/cbl/COADM01C.cbl:46}].
     *
     * <p>A {@code PIC 9} store is <strong>left</strong> zero-filled, which is the exact opposite of the
     * {@code PIC X} rule that governs {@link ErrMsgNarrowing}. {@code app/bms/COADM01.bms:145-149}
     * corroborates it independently with {@code JUSTIFY=(RIGHT,ZERO)}.
     */
    @Nested
    @DisplayName("OPTIONO - the two-digit, LEFT zero-filled image of WS-OPTION PIC 9(02)")
    class OptionZeroFill {

        @ParameterizedTest(name = "WS-OPTION = {0} renders as \"{1}\"")
        @CsvSource({"0,00", "1,01", "3,03", "4,04", "9,09", "10,10", "12,12", "99,99"})
        @DisplayName("the zero-fill happens on the left, through the codec's PIC 9 path")
        void theZeroFillHappensOnTheLeft(final int wsOption, final String expected) {
            // Routed through FixedWidthCodec rather than String.format so the rule under test is the
            // one the production path uses, with the code page stated explicitly (practice B8).
            final FixedWidthCodec codec = codec();
            final String rendered = codec.movePic9(wsOption, AdminMenuResponse.OPTION_LENGTH);

            assertThat(rendered)
                    .isEqualTo(expected)
                    .hasSize(2)
                    .hasSize(AdminMenuResponse.OPTION_LENGTH);
            assertThat(AdminMenuResponse.builder().option(rendered).build().option())
                    .isEqualTo(expected)
                    .hasSize(AdminMenuResponse.OPTION_LENGTH);
        }

        @Test
        @DisplayName("zero renders as \"00\", which the validation path really does reach")
        void zeroRendersAsDoubleZero() {
            // Not a hypothetical. WS-OPTION is declared VALUE 0 (COADM01C:46); PROCESS-ENTER-KEY
            // normalises a blank entry to zeros by INSPECT ... REPLACING ALL ' ' BY '0' (COADM01C:123);
            // and the guard at COADM01C:127-129 tests WS-OPTION = ZEROS and still PERFORMs
            // SEND-MENU-SCREEN - after :125 has already echoed the value. So "00" is a value the
            // screen genuinely carries, and it is not the same value as "  ".
            final String rendered = codec().movePic9(0, AdminMenuResponse.OPTION_LENGTH);

            assertThat(rendered).isEqualTo("00").hasSize(2);
            assertThat(AdminMenuResponse.builder().option(rendered).build().option())
                    .isEqualTo("00")
                    .isNotEqualTo("  ")
                    .isNotEqualTo("0")
                    .hasSize(2);
            assertThat(AdminMenuResponse.empty().option())
                    .as("nothing entered yet is spaces; entering zero is \"00\"")
                    .isEqualTo("  ")
                    .isNotEqualTo(rendered);
        }

        @Test
        @DisplayName("every option the admin menu accepts renders at exactly two characters")
        void everyAcceptedOptionRendersAtTwoCharacters() {
            final FixedWidthCodec codec = codec();
            for (int option = 1; option <= ADMIN_OPTION_COUNT; option++) {
                assertThat(codec.movePic9(option, AdminMenuResponse.OPTION_LENGTH))
                        .as("option %d", option)
                        .hasSize(2)
                        .isEqualTo("0" + option);
            }
            assertThat(ADMIN_OPTION_COUNT).isEqualTo(4);
        }
    }

    // =================================================================================================
    // The option lines themselves, as BUILD-MENU-OPTIONS composes them.
    // =================================================================================================

    /**
     * The twelve {@code OPTN00nO} images.
     *
     * <p>{@code BUILD-MENU-OPTIONS} [{@code app/cbl/COADM01C.cbl:226-263}] fills slots 1 to
     * {@code CDEMO-ADMIN-OPT-COUNT}, which {@code app/cpy/COADM02Y.cpy:20} sets to 4. Its
     * {@code EVALUATE WS-IDX} [{@code app/cbl/COADM01C.cbl:238-261}] enumerates {@code WHEN 1} through
     * {@code WHEN 10} and then {@code WHEN OTHER CONTINUE}, so slots 5 to 10 are reachable but never
     * reached, and {@code OPTN011O} and {@code OPTN012O} have <strong>no assignment anywhere in the
     * program</strong> - the table is {@code OCCURS 9} [{@code app/cpy/COADM02Y.cpy:45}] while the map
     * declares twelve.
     *
     * <p>None of that is licence to prune the type (practice B5): the screen contract is twelve.
     */
    @Nested
    @DisplayName("OPTN001O to OPTN012O - four composed, twelve declared, none pruned")
    class OptionLineImages {

        @ParameterizedTest(name = "slot {0} is the 40-character option line")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("slots 1 to 4 carry num + '. ' + name, 39 characters padded to 40")
        void slotsOneToFourCarryTheComposedOptionLine(final int slot) {
            final String expected = adminOptionLine(slot);

            // 2 (PIC 9(02)) + 2 ('. ') + 35 (PIC X(35)) = 39, and the fortieth character is the space
            // MOVE SPACES left in WS-ADMIN-OPT-TXT PIC X(40) at COADM01C:231.
            assertThat(expected)
                    .as("slot %d is exactly the declared width", slot)
                    .hasSize(40)
                    .hasSize(AdminMenuResponse.OPTION_LINE_LENGTH)
                    .endsWith(" ");
            assertThat(expected.stripTrailing()).hasSizeLessThanOrEqualTo(39);
            assertThat(expected.substring(0, 4)).isEqualTo("0" + slot + ". ");
            assertThat(expected.substring(4)).isEqualTo(ADMIN_OPTION_NAMES.get(slot - 1) + " ");

            assertThat(populated().optionLine(slot))
                    .as("the response carries the composed line verbatim")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("the four lines are exactly COADM02Y's four labels, in COADM02Y's order")
        void theFourLinesAreTheFourLabelsInOrder() {
            // app/cpy/COADM02Y.cpy lines 24-27, 29-32, 34-37 and 39-42.
            assertThat(ADMIN_OPTION_NAMES)
                    .hasSize(4)
                    .allSatisfy(name -> assertThat(name)
                            .as("CDEMO-ADMIN-OPT-NAME is PIC X(35)")
                            .hasSize(35));

            assertThat(populated().optionLines().subList(0, 4)).containsExactly(
                    "01. User List (Security)                ",
                    "02. User Add (Security)                 ",
                    "03. User Update (Security)              ",
                    "04. User Delete (Security)              ");

            assertThat(ADMIN_OPTION_NAMES.get(0)).startsWith("User List (Security)");
            assertThat(ADMIN_OPTION_NAMES.get(1)).startsWith("User Add (Security)");
            assertThat(ADMIN_OPTION_NAMES.get(2)).startsWith("User Update (Security)");
            assertThat(ADMIN_OPTION_NAMES.get(3)).startsWith("User Delete (Security)");
        }

        @Test
        @DisplayName("slots 5 to 12 are forty spaces on a populated screen, and are not pruned")
        void slotsFiveToTwelveAreFortySpacesAndSurvive() {
            final AdminMenuResponse response = populated();
            final String blankLine = spaces(AdminMenuResponse.OPTION_LINE_LENGTH);

            for (int slot = ADMIN_OPTION_COUNT + 1; slot <= AdminMenuResponse.OPTION_LINE_COUNT; slot++) {
                assertThat(response.optionLine(slot))
                        .as("slot %d is unwritten, and unwritten means forty spaces", slot)
                        .isNotNull()
                        .isEqualTo(blankLine)
                        .hasSize(40);
            }

            // Named, not merely swept (practice B11). Slots 11 and 12 are the two the EVALUATE has no
            // arm for at all, so they are the ones a well-meaning cleanup would delete first.
            assertThat(response.optn011())
                    .as("OPTN011O: no WHEN 11 arm exists in COADM01C:238-261")
                    .isEqualTo(blankLine);
            assertThat(response.optn012())
                    .as("OPTN012O: no WHEN 12 arm either")
                    .isEqualTo(blankLine);
            assertThat(AdminMenuResponse.OPTION_LINE_COUNT)
                    .as("twelve declared - not the four filled, not the ten reachable")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the four XCTL targets of the option table are the four COUSR programs")
        void theFourXctlTargetsAreTheFourCousrPrograms() {
            // app/cpy/COADM02Y.cpy:27, :32, :37, :42 - the CDEMO-ADMIN-OPT-PGMNAME PIC X(08) values
            // that app/cbl/COADM01C.cbl:142-145 transfers control to. They become nextProgram (G40).
            assertThat(ADMIN_OPTION_PROGRAMS)
                    .containsExactly("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C")
                    .allSatisfy(program -> assertThat(program)
                            .hasSize(AdminMenuResponse.NEXT_PROGRAM_LENGTH)
                            .hasSize(8));

            for (int slot = 1; slot <= ADMIN_OPTION_COUNT; slot++) {
                assertThat(populated().toBuilder()
                        .nextProgram(ADMIN_OPTION_PROGRAMS.get(slot - 1))
                        .build()
                        .nextProgram())
                        .as("selecting option %d names its own target", slot)
                        .isEqualTo(ADMIN_OPTION_PROGRAMS.get(slot - 1));
            }
        }
    }

    // =================================================================================================
    // The header literals, and the decoy that sits between a declaration and its value.
    // =================================================================================================

    /**
     * The four header fields {@code POPULATE-HEADER-INFO} writes
     * [{@code app/cbl/COADM01C.cbl:202-221}], and the constants they come from.
     *
     * <p>The two titles are byte-exact 40-character literals whose leading and trailing spaces are
     * part of the value. Three further near-identical constants exist in two different copybooks at two
     * different widths, and substituting one for another would be invisible at a glance - so each is
     * asserted here by value, by width and by owner.
     */
    @Nested
    @DisplayName("Header literals - byte-exact titles, and the COTTL01Y:21 decoy left untouched")
    class HeaderLiterals {

        @Test
        @DisplayName("TITLE01O is CCDA-TITLE01: six leading and seven trailing spaces, 40 in all")
        void titleOneIsCcdaTitle01() {
            // app/cpy/COTTL01Y.cpy - declaration at :18, value at :19. Moved to TITLE01O OF COADM1AO by
            // app/cbl/COADM01C.cbl:206.
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .isEqualTo("      AWS Mainframe Modernization       ")
                    .hasSize(40)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(AdminMenuResponse.TITLE_LENGTH)
                    .startsWith(spaces(6) + "AWS")
                    .endsWith("Modernization" + spaces(7));

            assertThat(populated().title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(AdminMenuResponse.TITLE_LENGTH);
        }

        @Test
        @DisplayName("TITLE02O is the ACTIVE CCDA-TITLE02 value from line 22, not the line 21 decoy")
        void titleTwoIsTheActiveCcdaTitle02Value() {
            // app/cpy/COTTL01Y.cpy declares CCDA-TITLE02 at :20 and gives its value at :22.
            //
            // DECOY, and it is a sharp one: line 21 - sitting BETWEEN the declaration and the value -
            // holds a COMMENTED-OUT earlier wording,
            //     *     '  Credit Card Demo Application (CCDA)   '.
            // which is itself exactly 40 characters, so a width check would not catch the substitution.
            // It is dead source, preserved in the copybook and deliberately unused (practice B5): the
            // literal is named in this comment so a reader knows it was considered, and it is asserted
            // NOWHERE in this file.
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .isEqualTo("              CardDemo                  ")
                    .hasSize(40)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("CardDemo")
                    .as("the line 21 wording must never reach the screen")
                    .doesNotContain("Credit Card Demo Application")
                    .doesNotContain("(CCDA)");

            assertThat(populated().title02())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(AdminMenuResponse.TITLE_LENGTH);
        }

        @Test
        @DisplayName("the three thank-you and invalid-key constants are distinct in value and width")
        void theThreeNearIdenticalConstantsAreDistinct() {
            // Three constants, two copybooks, two widths. The pair that is easiest to confuse differs
            // in the application it names AND in its declared width, so asserting both makes a future
            // substitution fail loudly instead of shifting a screen row by ten bytes.
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .as("COTTL01Y.cpy:23-24, PIC X(40), names the CCDA application")
                    .isEqualTo("Thank you for using CCDA application... ")
                    .hasSize(40)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("CCDA application")
                    .doesNotContain("CardDemo application");

            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .as("CSMSG01Y.cpy:18-19, PIC X(50), names the CardDemo application")
                    .startsWith("Thank you for using CardDemo application...")
                    .hasSize(50)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .contains("CardDemo application")
                    .doesNotContain("CCDA application");

            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("CSMSG01Y.cpy:20-21, PIC X(50)")
                    .startsWith("Invalid key pressed. Please see below...")
                    .hasSize(50)
                    .hasSize(SystemMessages.MESSAGE_LENGTH);

            // The assertion that makes a swap impossible to miss.
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .as("the X(40) title and the X(50) message are not interchangeable")
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(ScreenTitles.CCDA_THANK_YOU.length())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.length());
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("CURDATEO is mm/dd/yy and CURTIMEO is hh:mm:ss, both exactly 8, from a fixed Clock")
        void theDateAndTimeHeadersAreEightCharactersEach() {
            // app/cpy/CSDAT01Y.cpy:30-35 declares WS-CURDATE-MM-DD-YY as 9(02) '/' 9(02) '/' 9(02) and
            // :36-41 declares WS-CURTIME-HH-MM-SS as 9(02) ':' 9(02) ':' 9(02) - eight characters each.
            // app/cbl/COADM01C.cbl:215 and :221 move them into CURDATEO and CURTIMEO.
            //
            // The Clock is FIXED (practice B7). DateHeader never calls now() of its own accord, which is
            // what makes a header comparable byte for byte against an expected parity image; a test that
            // read the wall clock could not assert a value at all.
            final DateHeader header =
                    DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

            assertThat(header.wsCurdateMmDdYy())
                    .isEqualTo("07/19/22")
                    .hasSize(8)
                    .hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH)
                    .hasSize(AdminMenuResponse.CUR_DATE_LENGTH)
                    .matches("\\d{2}/\\d{2}/\\d{2}");
            assertThat(header.wsCurtimeHhMmSs())
                    .isEqualTo("23:12:32")
                    .hasSize(8)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH)
                    .hasSize(AdminMenuResponse.CUR_TIME_LENGTH)
                    .matches("\\d{2}:\\d{2}:\\d{2}");

            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .curDate(header.wsCurdateMmDdYy())
                    .curTime(header.wsCurtimeHhMmSs())
                    .build();

            assertThat(response.curDate()).isEqualTo("07/19/22").hasSize(8);
            assertThat(response.curTime()).isEqualTo("23:12:32").hasSize(8);

            // Reading the same fixed Clock twice must give the same answer - the property that makes
            // this suite reproducible under repeated `mvn verify` runs (gate G54).
            assertThat(DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC))
                    .wsCurtimeHhMmSs())
                    .isEqualTo(header.wsCurtimeHhMmSs());
        }

        @Test
        @DisplayName("TRNNAMEO and PGMNAMEO are the transaction and program literals of this program")
        void theTransactionAndProgramHeadersAreThisProgramsLiterals() {
            // app/cbl/COADM01C.cbl:37 WS-TRANID VALUE 'CA00' and :36 WS-PGMNAME VALUE 'COADM01C',
            // moved to TRNNAMEO and PGMNAMEO at :208 and :209.
            assertThat(populated().trnName())
                    .isEqualTo(AdminMenuResponse.TRANSACTION_ID)
                    .isEqualTo("CA00")
                    .hasSize(AdminMenuResponse.TRN_NAME_LENGTH);
            assertThat(populated().pgmName())
                    .isEqualTo(AdminMenuResponse.PROGRAM_NAME)
                    .isEqualTo("COADM01C")
                    .hasSize(AdminMenuResponse.PGM_NAME_LENGTH);
        }
    }

    // =================================================================================================
    // The duplication with the main menu: recorded, not repaired.
    // =================================================================================================

    /**
     * The admin menu and the main menu are the same screen geometry, and they stay two Java types.
     *
     * <p>{@code diff app/cpy-bms/COADM01.CPY app/cpy-bms/COMEN01.CPY} reports differences at
     * <strong>exactly two lines</strong>, verbatim:
     *
     * <pre>
     * 17c17
     * &lt;        01  COADM1AI.
     * ---
     * &gt;        01  COMEN1AI.
     * 139c139
     * &lt;        01  COADM1AO REDEFINES COADM1AI.
     * ---
     * &gt;        01  COMEN1AO REDEFINES COMEN1AI.
     * </pre>
     *
     * <p>Every field name, every {@code PICTURE} clause and every byte of geometry is otherwise
     * identical. The two {@code .bms} mapsets differ only in the mapset name (line 19), the map name
     * (line 26), a comment (line 2), a version timestamp (line 166) and one <strong>unnamed</strong>
     * literal label - {@code app/bms/COADM01.bms:77-79} declares {@code LENGTH=10} with
     * {@code INITIAL='Admin Menu'} where {@code app/bms/COMEN01.bms:77-79} declares {@code LENGTH=9}
     * with {@code INITIAL='Main Menu'}. Being unnamed it yields no symbolic-map item at all, which is
     * precisely why the two {@code .CPY} files can be byte-identical apart from their group names.
     *
     * <p>The duplication is therefore real, and it is <strong>documented rather than collapsed</strong>
     * (practice B4). There is no shared base class, no shared fixture builder, no
     * {@code package-info.java} and no parameterised suite spanning both response types: the screens
     * are driven by distinct programs behind distinct transactions ({@code CA00} against {@code CM00}),
     * backed by distinct option tables ({@code COADM02Y}, four entries, no authorisation column;
     * {@code COMEN02Y}, ten entries with an {@code X(01)} user-type column), and they already diverge
     * in observable behaviour - the coming-soon text of {@link ErrMsgNarrowing} is the proof. Folding
     * them together would couple two contracts the legacy system keeps apart.
     */
    @Nested
    @DisplayName("The main-menu duplication is documented, not deduplicated (practice B4)")
    class DuplicationIsDocumentedNotRemoved {

        @Test
        @DisplayName("AdminMenuResponse and MainMenuResponse are distinct Java types")
        void adminAndMainMenuResponsesAreDistinctTypes() {
            // The single assertion that would fail if a later change collapsed the two screens into one
            // type or introduced a shared supertype to "remove the duplication".
            assertThat(AdminMenuResponse.class)
                    .as("two screens, two types - see this class's documentation for why")
                    .isNotEqualTo(MainMenuResponse.class);
            assertThat(AdminMenuResponse.class.getName()).isNotEqualTo(MainMenuResponse.class.getName());

            assertThat(MainMenuResponse.class.isAssignableFrom(AdminMenuResponse.class)).isFalse();
            assertThat(AdminMenuResponse.class.isAssignableFrom(MainMenuResponse.class)).isFalse();
            assertThat(AdminMenuResponse.class.getSuperclass())
                    .as("a record extends java.lang.Record and nothing else - no shared base class")
                    .isEqualTo(Record.class);
            assertThat(AdminMenuResponse.class.getInterfaces())
                    .as("and no shared interface either")
                    .isEmpty();
        }

        @Test
        @DisplayName("the identical geometry is asserted independently for this screen, not shared")
        void theIdenticalGeometryIsAssertedIndependently() {
            // The geometry really is the same, and each type states it for itself. Reading COADM01's
            // numbers out of MainMenuResponse would make this file pass while proving nothing about the
            // copybook it is supposed to be the projection of, so the expectations here are literals
            // transcribed from COADM01.CPY (practice B12) and the comparison below is one-directional.
            assertThat(AdminMenuResponse.SYMBOLIC_MAP_LENGTH).isEqualTo(820);
            assertThat(AdminMenuResponse.PAYLOAD_DATA_LENGTH).isEqualTo(668);
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_COUNT).isEqualTo(20);
            assertThat(AdminMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);

            assertThat(AdminMenuResponse.MAPSET_NAME).isEqualTo("COADM01").isNotEqualTo("COMEN01");
            assertThat(AdminMenuResponse.MAP_NAME).isEqualTo("COADM1A").isNotEqualTo("COMEN1A");
            assertThat(AdminMenuResponse.TRANSACTION_ID)
                    .as("CA00 is the admin menu; CM00 is the main menu")
                    .isEqualTo("CA00")
                    .isNotEqualTo("CM00");
            assertThat(AdminMenuResponse.PROGRAM_NAME).isEqualTo("COADM01C").isNotEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("the admin option table is four entries, where the main menu's is ten")
        void theAdminOptionTableIsFourEntries() {
            // app/cpy/COADM02Y.cpy:20 - CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4, and the table carries
            // no user-type authorisation column, where COMEN02Y's ten entries each carry an X(01) one.
            // Distinct tables are the second reason the two response types stay apart.
            assertThat(ADMIN_OPTION_COUNT).isEqualTo(4).isNotEqualTo(10);
            assertThat(ADMIN_OPTION_NAMES).hasSize(4);
            assertThat(ADMIN_OPTION_PROGRAMS).hasSize(4);
            assertThat(ADMIN_OPTION_COUNT)
                    .as("four filled, but twelve slots declared - the count is not the slot count")
                    .isLessThan(AdminMenuResponse.OPTION_LINE_COUNT);
        }
    }
}
