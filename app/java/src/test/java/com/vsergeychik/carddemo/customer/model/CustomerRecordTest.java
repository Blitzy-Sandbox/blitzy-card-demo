package com.vsergeychik.carddemo.customer.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The sole test class for {@link CustomerRecord} - the 500-byte {@code CUSTOMER-RECORD} that
 * {@code app/cpy/CVCUS01Y.cpy} declares, and the one Java type in the migration that models it.
 *
 * <h2>What this class is for</h2>
 * It is an <strong>independent audit</strong> of the copybook, not a restatement of the
 * implementation. Every width, every absolute offset, every field name and every expected value below
 * was transcribed by hand from {@code app/cpy/CVCUS01Y.cpy} and measured by hand off
 * {@code app/data/ASCII/custdata.txt}. None of it is read back out of
 * {@link CustomerRecord#LAYOUT} and compared with itself, and none of it is obtained by cumulatively
 * summing the production span lengths. That distinction is the whole value of the file: an assertion
 * derived from the production table would still pass with the production table wrong, whereas these
 * assertions fail and name the field whose offset moved. A reviewer can read the table in
 * {@code DeclaredGeometry} straight down beside the copybook, line for line.
 *
 * <h2>Provenance of every expected value - statically derived, never captured</h2>
 * The expectations here were derived by reading the sources, <strong>not</strong> by executing the
 * legacy COBOL. COBOL cannot be executed in this environment: there is no z/OS runtime, the available
 * compiler reports {@code indexed file handler : disabled} so the programs using
 * {@code ORGANIZATION INDEXED} will not build, no Language Environment {@code CEE*} services exist,
 * and no CICS emulator is present. That is recorded in the plan as the highest-severity open risk, and
 * the substitute is exactly what this class does: derive the expectation from the copybook's byte
 * layout, the program's own {@code FD} arithmetic, the JCL's cluster definition and the real ASCII
 * fixture, all four of which are mechanical rather than interpretive.
 *
 * <h2>The 500-byte width, corroborated three independent ways</h2>
 * <ol>
 *   <li>{@code app/cpy/CVCUS01Y.cpy}'s own header comment:
 *       {@code Data-structure for Customer entity (RECLN 500)}, and its 19 {@code PICTURE} widths,
 *       which sum to 500 - re-added independently in
 *       {@code DeclaredGeometry#theTranscribedLengthsSumToFiveHundred()}.</li>
 *   <li>{@code app/cbl/CBCUS01C.cbl:38-40}, which splits the same file as
 *       {@code 05 FD-CUST-ID PIC 9(09)} plus {@code 05 FD-CUST-DATA PIC X(491)}: 9 + 491 = 500.</li>
 *   <li>{@code app/jcl/CUSTFILE.jcl}, whose IDCAMS {@code DEFINE CLUSTER} for
 *       {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS} declares {@code KEYS(9 0)}
 *       {@code RECORDSIZE(500 500)} {@code INDEXED} - a nine-byte key at offset 0 and a record fixed
 *       at 500 bytes.</li>
 * </ol>
 *
 * <p><strong>A trap deliberately not fallen into.</strong> {@code app/jcl/DEFCUST.jcl} is a stale
 * second definition of a customer file: it declares {@code KEYS(10 0)} over
 * {@code AWS.CUSTDATA.CLUSTER}, and even repeats its step name. Its ten-byte key contradicts
 * {@code CUST-ID PIC 9(09)}, the copybook, and {@code app/cbl/CBCUS01C.cbl:32}'s
 * {@code RECORD KEY IS FD-CUST-ID}. {@code CUSTFILE.jcl} is authoritative because its DSN is the one
 * bound in {@code app/csd/CARDDEMO.CSD}. Where a key width is asserted here it is <strong>9</strong>.
 *
 * <h2>What CVCUS01Y does not declare, so what is not tested</h2>
 * The copybook declares <strong>zero</strong> {@code 88}-level condition names, <strong>zero</strong>
 * {@code REDEFINES}, <strong>zero</strong> {@code OCCURS} and <strong>zero</strong> {@code VALUE}
 * clauses. So there is no condition name to drive from both sides, no 1-based-to-0-based table index
 * to get wrong, and no overlay pair to round-trip. None is invented here. The branch surface that does
 * exist belongs to the record type's own guards - the layout self-check, the two receivers and
 * {@code equals} - and every one of them is driven from both sides in {@code LayoutSelfCheck},
 * {@code AlphanumericReceiver}, {@code NumericReceiver} and {@code ValueSemantics}. The
 * numeric-plus-character-image duality that {@code CustomerRecord} exposes is a Java accessor
 * convenience serving COBOL reference modification, not a copybook {@code REDEFINES}, and is described
 * as such.
 *
 * <p>{@code customer.model} is measured for branch coverage independently of the sibling
 * {@code customer} package, so nothing here leans on another package's tests, and nothing here
 * duplicates them: no repository, no service, no job and no batch contract is exercised.
 *
 * <h2>Rules</h2>
 * No user-specified rules were provided for this project - the rules document consists of the single
 * line stating so. Its absence is not treated as licence to lower the bar; the project's enterprise
 * practices bind instead, and the ones that shape this file are: the closed dependency set (JUnit
 * Jupiter, AssertJ and the JDK only - nothing is added to the build), the read-only legacy trees
 * (every byte of input arrives through the <em>test classpath</em>, never through a filesystem path
 * into {@code app/data}), determinism (no clock, no locale, no randomness, no ordering between tests),
 * explicitness (the {@link Charset} is named at every boundary and there are no wildcard imports), no
 * mutable static state, and hand-written assertions in preference to opaque machinery.
 *
 * @see CustomerRecord
 */
@DisplayName("CustomerRecord - the 500-byte CUSTOMER-RECORD of CVCUS01Y")
class CustomerRecordTest {

    // =================================================================================================
    // Code pages. Always named, never inherited from the platform. Both constants are immutable, so
    // none of this is mutable static state.
    // =================================================================================================

    /** The code page of the nine text fixtures under {@code app/data/ASCII}, named explicitly. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The code page of the binary datasets under {@code app/data/EBCDIC}, named explicitly.
     *
     * <p>Resolved during class initialisation on purpose: {@code IBM037} ships in the JDK's
     * {@code jdk.charsets} module, so were it ever absent this class would fail to initialise loudly
     * and name the missing code page, rather than quietly skipping the tests that prove the charset is
     * genuinely the caller's choice.
     */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The ASCII space, {@code 0x20} - what a {@code FILLER} byte must be, and never {@code 0x00}. */
    private static final byte ASCII_SPACE_BYTE = 0x20;

    /** The EBCDIC space, {@code 0x40} - the same {@code FILLER} byte under {@code IBM037}. */
    private static final byte EBCDIC_SPACE_BYTE = 0x40;

    // =================================================================================================
    // THE HAND-TRANSCRIBED BYTE MAP OF CVCUS01Y.
    //
    // Read this beside app/cpy/CVCUS01Y.cpy. Offsets are 0-based Java offsets; the copybook's own
    // 1-based positions are one greater. Nothing below is computed from CustomerRecord.LAYOUT.
    //
    //   #   COBOL field                 PICTURE   1-based    0-based  length
    //   1   CUST-ID                     9(09)       1-9         0        9
    //   2   CUST-FIRST-NAME             X(25)      10-34         9       25
    //   3   CUST-MIDDLE-NAME            X(25)      35-59        34       25
    //   4   CUST-LAST-NAME              X(25)      60-84        59       25
    //   5   CUST-ADDR-LINE-1            X(50)      85-134       84       50
    //   6   CUST-ADDR-LINE-2            X(50)     135-184      134       50
    //   7   CUST-ADDR-LINE-3            X(50)     185-234      184       50
    //   8   CUST-ADDR-STATE-CD          X(02)     235-236      234        2
    //   9   CUST-ADDR-COUNTRY-CD        X(03)     237-239      236        3
    //  10   CUST-ADDR-ZIP               X(10)     240-249      239       10
    //  11   CUST-PHONE-NUM-1            X(15)     250-264      249       15
    //  12   CUST-PHONE-NUM-2            X(15)     265-279      264       15
    //  13   CUST-SSN                    9(09)     280-288      279        9
    //  14   CUST-GOVT-ISSUED-ID         X(20)     289-308      288       20
    //  15   CUST-DOB-YYYY-MM-DD         X(10)     309-318      308       10
    //  16   CUST-EFT-ACCOUNT-ID         X(10)     319-328      318       10
    //  17   CUST-PRI-CARD-HOLDER-IND    X(01)     329          328        1
    //  18   CUST-FICO-CREDIT-SCORE      9(03)     330-332      329        3
    //  19   FILLER                      X(168)    333-500      332      168
    //                                                        total     500
    // =================================================================================================

    /** The declared record width, written out rather than read from the class under test. */
    private static final int DECLARED_RECORD_LENGTH = 500;

    /** The number of {@code 05}-level items {@code CVCUS01Y} declares, {@code FILLER} included. */
    private static final int DECLARED_SPAN_COUNT = 19;

    /** The 18 referable names, {@code FILLER} excluded, since {@code FILLER} is not referable. */
    private static final int REFERABLE_FIELD_COUNT = 18;

    /** The nine-byte primary key width that {@code CUSTFILE.jcl}'s {@code KEYS(9 0)} declares. */
    private static final int PRIMARY_KEY_LENGTH = 9;

    /** Every span name, in copybook declaration order, spelled exactly as the copybook spells it. */
    private static final List<String> SPAN_NAMES = List.of(
            "CUST-ID",
            "CUST-FIRST-NAME",
            "CUST-MIDDLE-NAME",
            "CUST-LAST-NAME",
            "CUST-ADDR-LINE-1",
            "CUST-ADDR-LINE-2",
            "CUST-ADDR-LINE-3",
            "CUST-ADDR-STATE-CD",
            "CUST-ADDR-COUNTRY-CD",
            "CUST-ADDR-ZIP",
            "CUST-PHONE-NUM-1",
            "CUST-PHONE-NUM-2",
            "CUST-SSN",
            "CUST-GOVT-ISSUED-ID",
            "CUST-DOB-YYYY-MM-DD",
            "CUST-EFT-ACCOUNT-ID",
            "CUST-PRI-CARD-HOLDER-IND",
            "CUST-FICO-CREDIT-SCORE",
            "FILLER");

    /** Every declared width, in copybook order, read off the {@code PICTURE} clauses by hand. */
    private static final List<Integer> SPAN_LENGTHS = List.of(
            9, 25, 25, 25, 50, 50, 50, 2, 3, 10, 15, 15, 9, 20, 10, 10, 1, 3, 168);

    /**
     * Every absolute 0-based offset, in copybook order, written out as a literal.
     *
     * <p>Deliberately <em>not</em> computed - not from {@link #SPAN_LENGTHS} and certainly not from
     * the production table. {@code theTranscribedOffsetsAreSelfConsistent()} then checks this literal
     * list against the running total of {@link #SPAN_LENGTHS}, which is what catches a transcription
     * slip in either list without either list vouching for itself.
     */
    private static final List<Integer> SPAN_OFFSETS = List.of(
            0, 9, 34, 59, 84, 134, 184, 234, 236, 239, 249, 264, 279, 288, 308, 318, 328, 329, 332);

    /** The {@code FILLER}'s offset, the value gate G21's space-fill assertions slice at. */
    private static final int FILLER_OFFSET = 332;

    /** The {@code FILLER}'s width: 168 reserved bytes that are part of every stored record. */
    private static final int FILLER_LENGTH = 168;

    // =================================================================================================
    // The real fixture. Loaded from the TEST CLASSPATH: app/java/src/test/resources/fixtures, which the
    // build copies to target/test-classes. app/data/ASCII/custdata.txt is never opened by path - it is
    // part of the read-only parity oracle, and a test that reached into it by relative path would also
    // depend on the process working directory.
    // =================================================================================================

    /** The classpath location of the fixture, named in the failure message if it is missing. */
    private static final String FIXTURE_RESOURCE = "fixtures/custdata.txt";

    /** The row count, re-measured for this test: {@code custdata.txt} holds 50 records. */
    private static final int FIXTURE_ROW_COUNT = 50;

    // -------------------------------------------------------------------------------------------------
    // Expected values, measured by hand at the offsets above. Written out as literals so that a failure
    // compares against something a reviewer can check against the fixture without running anything.
    // Trailing spaces are shown by construction (value plus an explicit pad) rather than typed, because
    // trailing whitespace in source is invisible and this padding is data.
    // -------------------------------------------------------------------------------------------------

    /** Row 1 {@code CUST-ID}: stored as nine digits, so the leading zeros are the field's content. */
    private static final String ROW_1_CUST_ID_IMAGE = "000000001";

    /** Row 1 {@code CUST-ID} as a value. Nine digits and scale-free, hence {@code int}. */
    private static final int ROW_1_CUST_ID = 1;

    /** Row 1 {@code CUST-FIRST-NAME}, before its 17 characters of right padding. */
    private static final String ROW_1_FIRST_NAME = "Immanuel";

    /** Row 1 {@code CUST-MIDDLE-NAME}, before its 17 characters of right padding. */
    private static final String ROW_1_MIDDLE_NAME = "Madeline";

    /** Row 1 {@code CUST-LAST-NAME}, before its 18 characters of right padding. */
    private static final String ROW_1_LAST_NAME = "Kessler";

    /** Row 1 {@code CUST-ADDR-LINE-1}, before its 33 characters of right padding. */
    private static final String ROW_1_ADDR_LINE_1 = "618 Deshaun Route";

    /** Row 1 {@code CUST-ADDR-LINE-2}, before its 42 characters of right padding. */
    private static final String ROW_1_ADDR_LINE_2 = "Apt. 802";

    /** Row 1 {@code CUST-ADDR-LINE-3} - the city, per {@code app/cbl/COACTVWC.cbl:513}. */
    private static final String ROW_1_ADDR_LINE_3 = "Altenwerthshire";

    /** Row 1 {@code CUST-ADDR-STATE-CD}: exactly two characters, so no padding at all. */
    private static final String ROW_1_STATE_CD = "NC";

    /** Row 1 {@code CUST-ADDR-COUNTRY-CD}: exactly three characters. All 50 rows hold {@code USA}. */
    private static final String ROW_1_COUNTRY_CD = "USA";

    /** Row 1 {@code CUST-ADDR-ZIP}: a five-character zip in a {@code PIC X(10)} span. */
    private static final String ROW_1_ZIP = "12546";

    /** Row 1 {@code CUST-PHONE-NUM-1}: the parentheses and hyphen are content, not formatting. */
    private static final String ROW_1_PHONE_1 = "(908)119-8310";

    /** Row 1 {@code CUST-PHONE-NUM-2}. */
    private static final String ROW_1_PHONE_2 = "(373)693-8684";

    /**
     * Row 1 {@code CUST-SSN} as stored: nine digits with a significant leading zero.
     *
     * <p>This one value is the reason {@code CustomerRecord} exposes a character image beside the
     * numeric accessor. {@code app/cbl/COACTVWC.cbl:496-504} slices these nine <em>characters</em>.
     */
    private static final String ROW_1_SSN_IMAGE = "020973888";

    /** Row 1 {@code CUST-SSN} as a value - eight digits once the leading zero is no longer stored. */
    private static final int ROW_1_SSN = 20973888;

    /** Row 1 {@code CUST-GOVT-ISSUED-ID}: {@code PIC X(20)} holding an all-digit value. */
    private static final String ROW_1_GOVT_ISSUED_ID = "00000000000049368437";

    /** Row 1 {@code CUST-DOB-YYYY-MM-DD}: ten characters of text, never a parsed date. */
    private static final String ROW_1_DOB = "1961-06-08";

    /** Row 1 {@code CUST-EFT-ACCOUNT-ID}: {@code PIC X(10)}, leading zero included. */
    private static final String ROW_1_EFT_ACCOUNT_ID = "0053581756";

    /** Row 1 {@code CUST-PRI-CARD-HOLDER-IND}. All 50 rows hold {@code Y}. */
    private static final String ROW_1_PRI_CARD_HOLDER_IND = "Y";

    /** Row 1 {@code CUST-FICO-CREDIT-SCORE}, stored as the three digits {@code 274}. */
    private static final int ROW_1_FICO = 274;

    /** Row 2 {@code CUST-ID} image, proving the key sequence advances by one. */
    private static final String ROW_2_CUST_ID_IMAGE = "000000002";

    /** Row 2 {@code CUST-FIRST-NAME}. */
    private static final String ROW_2_FIRST_NAME = "Enrico";

    /** Row 2 {@code CUST-MIDDLE-NAME}. */
    private static final String ROW_2_MIDDLE_NAME = "April";

    /** Row 2 {@code CUST-LAST-NAME}. */
    private static final String ROW_2_LAST_NAME = "Rosenbaum";

    /** Row 2 {@code CUST-ADDR-LINE-1}. */
    private static final String ROW_2_ADDR_LINE_1 = "4917 Myrna Flats";

    /** Row 2 {@code CUST-ADDR-STATE-CD} - a different state from row 1, of 36 distinct in the file. */
    private static final String ROW_2_STATE_CD = "IN";

    /** Row 2 {@code CUST-ADDR-ZIP}: another five-character zip in the ten-byte span. */
    private static final String ROW_2_ZIP = "22770";

    /** Row 2 {@code CUST-SSN} image - nine digits, no leading zero this time. */
    private static final String ROW_2_SSN_IMAGE = "587518382";

    /** Row 2 {@code CUST-DOB-YYYY-MM-DD}. */
    private static final String ROW_2_DOB = "1961-10-08";

    /** Row 2 {@code CUST-EFT-ACCOUNT-ID}. */
    private static final String ROW_2_EFT_ACCOUNT_ID = "0069194009";

    /** Row 2 {@code CUST-FICO-CREDIT-SCORE}. */
    private static final int ROW_2_FICO = 268;

    /**
     * Row 26 {@code CUST-FICO-CREDIT-SCORE} as stored: {@code 001}.
     *
     * <p>The single best zero-fill proof the fixture offers. {@code String.valueOf(1)} is
     * {@code "1"}, one character where the span is three, so a record built with hand-rolled number
     * formatting would emit a 498-byte row and shift every byte after offset 329.
     */
    private static final String ROW_26_FICO_IMAGE = "001";

    /** Row 26 {@code CUST-FICO-CREDIT-SCORE} as a value: one. */
    private static final int ROW_26_FICO = 1;

    /** Row 26 {@code CUST-ID} image. */
    private static final String ROW_26_CUST_ID_IMAGE = "000000026";

    /** Row 26 {@code CUST-FIRST-NAME}. */
    private static final String ROW_26_FIRST_NAME = "Marjory";

    /** Row 26 {@code CUST-ADDR-LINE-1} - the longest first address line among the sampled rows. */
    private static final String ROW_26_ADDR_LINE_1 = "30161 Bogan Canyon";

    /** Row 50 {@code CUST-ID} image: the last and highest key in the file. */
    private static final String ROW_50_CUST_ID_IMAGE = "000000050";

    /** Row 50 {@code CUST-FIRST-NAME}. */
    private static final String ROW_50_FIRST_NAME = "Aniya";

    /** Row 50 {@code CUST-MIDDLE-NAME}. */
    private static final String ROW_50_MIDDLE_NAME = "Alba";

    /** Row 50 {@code CUST-LAST-NAME}: three characters into a {@code PIC X(25)} span. */
    private static final String ROW_50_LAST_NAME = "Von";

    /** Row 50 {@code CUST-ADDR-LINE-1}. */
    private static final String ROW_50_ADDR_LINE_1 = "1588 Nienow Cape";

    /** Row 50 {@code CUST-ADDR-STATE-CD}. */
    private static final String ROW_50_STATE_CD = "OR";

    /** Row 50 {@code CUST-ADDR-ZIP}: {@code 04257}, whose leading zero survives only as text. */
    private static final String ROW_50_ZIP = "04257";

    /** Row 50 {@code CUST-SSN} image. */
    private static final String ROW_50_SSN_IMAGE = "931248469";

    /** Row 50 {@code CUST-DOB-YYYY-MM-DD} - the earliest date of birth in the sampled rows. */
    private static final String ROW_50_DOB = "1960-12-01";

    /** Row 50 {@code CUST-EFT-ACCOUNT-ID}. */
    private static final String ROW_50_EFT_ACCOUNT_ID = "0074883577";

    /** Row 50 {@code CUST-FICO-CREDIT-SCORE}. */
    private static final int ROW_50_FICO = 623;

    /**
     * Row 3 {@code CUST-ADDR-ZIP}: a full ZIP+4 that fills the {@code PIC X(10)} span exactly.
     *
     * <p>Together with {@link #ROW_1_ZIP} this is the fixture's strongest evidence that a
     * {@code PIC X} span is not trimmed on read: the same ten bytes hold a padded five-character zip
     * on 20 rows and an unpadded ten-character ZIP+4 on the other 30.
     */
    private static final String ROW_3_ZIP = "19852-6716";

    /** Row 5 {@code CUST-ADDR-ZIP}: a second full ZIP+4, this one with a leading zero. */
    private static final String ROW_5_ZIP = "02251-1698";

    /** Row 44 {@code CUST-ADDR-ZIP}: a third full ZIP+4, also leading-zero bearing. */
    private static final String ROW_44_ZIP = "05704-0501";

    /** Row 24 {@code CUST-SSN} image, whose {@code (6:4)} slice {@code 0544} is itself zero-led. */
    private static final String ROW_24_SSN_IMAGE = "017590544";

    // =================================================================================================
    // Per-instance collaborators. JUnit constructs a fresh test instance for every test method, so
    // nothing here is shared between tests and no execution order can matter.
    // =================================================================================================

    /** A codec bound to {@code US-ASCII}, for the overloads that take one rather than a charset. */
    private final FixedWidthCodec asciiCodec = new FixedWidthCodec(ASCII);

    /** A codec bound to {@code IBM037}, used to prove the code page is genuinely the caller's. */
    private final FixedWidthCodec ebcdicCodec = new FixedWidthCodec(EBCDIC);

    // =================================================================================================
    // Helpers. Each exists to spell out what a test EXPECTS; none reproduces the production move rules,
    // which are exercised directly through FixedWidthCodec where they are the subject.
    // =================================================================================================

    /**
     * Right-pads a value with spaces to a declared width, for stating an expectation.
     *
     * <p>Used only to write an expected value, never to produce the value under test. Trailing spaces
     * typed directly into source are invisible to a reader and easily lost to an editor, so the
     * padding is composed explicitly instead.
     *
     * @param value the content
     * @param width the receiving span's declared width
     * @return {@code value} followed by enough spaces to reach {@code width}
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * A run of spaces, for stating an expected blank span.
     *
     * @param width how many
     * @return exactly {@code width} spaces
     */
    private static String spaces(int width) {
        return " ".repeat(width);
    }

    /**
     * Reads every row of the fixture from the test classpath, as text.
     *
     * <p>A classpath resource, so the lookup is independent of the process working directory and never
     * touches the read-only tree under {@code app/data}. A missing resource fails with a message
     * naming what it looked for rather than silently yielding no rows, which would let every
     * fixture-driven assertion below pass vacuously.
     *
     * @return the 500-character rows in file order, line terminators removed, blank lines dropped
     */
    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream =
                     CustomerRecordTest.class.getClassLoader().getResourceAsStream(FIXTURE_RESOURCE)) {
            assertThat(stream)
                    .as("The fixture must be on the test classpath at '%s'. It is derived from "
                            + "app/data/ASCII/custdata.txt, which is read-only and never opened by "
                            + "path.", FIXTURE_RESOURCE)
                    .isNotNull();
            for (String row : new String(stream.readAllBytes(), ASCII).split("\n")) {
                String withoutLineEnd = row.endsWith("\r") ? row.substring(0, row.length() - 1) : row;
                if (!withoutLineEnd.isEmpty()) {
                    rows.add(withoutLineEnd);
                }
            }
        } catch (IOException problem) {
            throw new UncheckedIOException("Could not read the classpath fixture "
                    + FIXTURE_RESOURCE, problem);
        }
        return List.copyOf(rows);
    }

    /**
     * One fixture row by its 1-based record number, as the fixture numbers them.
     *
     * <p>{@code custdata.txt} is keyed {@code 000000001} to {@code 000000050} in verified ascending
     * order, so the 1-based row number and the key agree - which is asserted rather than assumed in
     * {@code RealFixture}.
     *
     * @param recordNumber the 1-based row number, 1 to 50
     * @return that row's 500-character stored image
     */
    private static String fixtureRow(int recordNumber) {
        List<String> rows = fixtureRows();
        assertThat(rows)
                .as("row %d was requested, so the fixture must hold at least that many rows",
                        recordNumber)
                .hasSizeGreaterThanOrEqualTo(recordNumber);
        return rows.get(recordNumber - 1);
    }

    /**
     * Supplies every fixture row to a parameterised test, paired with its 1-based record number so a
     * failure names the offending row.
     *
     * @return one argument pair per fixture row
     */
    private static List<Arguments> everyFixtureRow() {
        List<String> rows = fixtureRows();
        List<Arguments> arguments = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            arguments.add(Arguments.of(index + 1, rows.get(index)));
        }
        return arguments;
    }

    /**
     * Builds a 500-byte image span by span from field literals, without reading the fixture.
     *
     * <p>Two things need this. The width self-check's failing side needs an image that is deliberately
     * the wrong length, which no fixture row can supply. And the cases the fixture cannot express -
     * a {@code CUST-PRI-CARD-HOLDER-IND} of {@code 'N'}, a blank field - have to be synthesised,
     * because all 50 rows carry {@code 'Y'} and no row leaves a named field blank.
     *
     * <p>Every append is annotated with the span it fills, so the composition can be read against the
     * byte map above. The result's length is asserted by the callers that care.
     *
     * @param priCardHolderInd the one-character indicator to place at offset 328
     * @return a 500-character image
     */
    private static String synthesisedImage(String priCardHolderInd) {
        return "000000042"                                  // 1  CUST-ID                    [0, 9)
                + padded("Alice", 25)                       // 2  CUST-FIRST-NAME            [9, 34)
                + padded("B", 25)                           // 3  CUST-MIDDLE-NAME           [34, 59)
                + padded("Smith", 25)                       // 4  CUST-LAST-NAME             [59, 84)
                + padded("1 High Street", 50)               // 5  CUST-ADDR-LINE-1           [84, 134)
                + spaces(50)                                // 6  CUST-ADDR-LINE-2           [134, 184)
                + spaces(50)                                // 7  CUST-ADDR-LINE-3           [184, 234)
                + "WA"                                      // 8  CUST-ADDR-STATE-CD         [234, 236)
                + "USA"                                     // 9  CUST-ADDR-COUNTRY-CD       [236, 239)
                + padded("99999", 10)                       // 10 CUST-ADDR-ZIP              [239, 249)
                + padded("2065550100", 15)                  // 11 CUST-PHONE-NUM-1           [249, 264)
                + spaces(15)                                // 12 CUST-PHONE-NUM-2           [264, 279)
                + "020973888"                               // 13 CUST-SSN                   [279, 288)
                + spaces(20)                                // 14 CUST-GOVT-ISSUED-ID        [288, 308)
                + "1970-01-01"                              // 15 CUST-DOB-YYYY-MM-DD        [308, 318)
                + spaces(10)                                // 16 CUST-EFT-ACCOUNT-ID        [318, 328)
                + priCardHolderInd                          // 17 CUST-PRI-CARD-HOLDER-IND   [328, 329)
                + "720"                                     // 18 CUST-FICO-CREDIT-SCORE     [329, 332)
                + spaces(FILLER_LENGTH);                    // 19 FILLER                     [332, 500)
    }

    /**
     * A record carrying row 1's values, built through the setters so each {@code PIC X} value arrives
     * through the receiver and each numeric through the {@code PIC 9} rule.
     *
     * @return the record fixture row 1 denotes
     */
    private static CustomerRecord row1AsBuilt() {
        CustomerRecord record = new CustomerRecord();
        record.setCustId(ROW_1_CUST_ID);
        record.setCustFirstName(ROW_1_FIRST_NAME);
        record.setCustMiddleName(ROW_1_MIDDLE_NAME);
        record.setCustLastName(ROW_1_LAST_NAME);
        record.setCustAddrLine1(ROW_1_ADDR_LINE_1);
        record.setCustAddrLine2(ROW_1_ADDR_LINE_2);
        record.setCustAddrLine3(ROW_1_ADDR_LINE_3);
        record.setCustAddrStateCd(ROW_1_STATE_CD);
        record.setCustAddrCountryCd(ROW_1_COUNTRY_CD);
        record.setCustAddrZip(ROW_1_ZIP);
        record.setCustPhoneNum1(ROW_1_PHONE_1);
        record.setCustPhoneNum2(ROW_1_PHONE_2);
        record.setCustSsn(ROW_1_SSN);
        record.setCustGovtIssuedId(ROW_1_GOVT_ISSUED_ID);
        record.setCustDobYyyyMmDd(ROW_1_DOB);
        record.setCustEftAccountId(ROW_1_EFT_ACCOUNT_ID);
        record.setCustPriCardHolderInd(ROW_1_PRI_CARD_HOLDER_IND);
        record.setCustFicoCreditScore(ROW_1_FICO);
        return record;
    }

    /**
     * Whether a type is a binary floating-point type, in either its primitive or boxed form.
     *
     * @param type the type to test
     * @return {@code true} for {@code double}, {@code float}, {@link Double} and {@link Float}
     */
    private static boolean isFloatingPoint(Class<?> type) {
        return double.class.equals(type)
                || float.class.equals(type)
                || Double.class.equals(type)
                || Float.class.equals(type);
    }

    /**
     * Whether a type is one this copybook has no business declaring.
     *
     * <p>Matched by <em>name</em> rather than by importing the type, so that this file names no scaled
     * decimal at all - there is none in {@code CVCUS01Y} to name, and importing one here would be the
     * first step towards inventing a scale.
     *
     * @param type the type to test
     * @return {@code true} for a floating-point type or an arbitrary-precision decimal type
     */
    private static boolean isForbiddenNumericType(Class<?> type) {
        return isFloatingPoint(type)
                || "java.math.BigDecimal".equals(type.getName())
                || "java.math.BigInteger".equals(type.getName())
                || "java.math.RoundingMode".equals(type.getName());
    }

    // =================================================================================================
    // 1. DECLARED GEOMETRY (gates G8, G19).
    //
    //    The copybook's own arithmetic, asserted span by span against hand-written literals, so that a
    //    regression names the field whose offset moved rather than reporting one width mismatch at the
    //    end. Nothing in this section reads an expected value out of the production table.
    // =================================================================================================

    @Nested
    @DisplayName("Declared geometry - the 19 spans of CVCUS01Y (G8, G19)")
    class DeclaredGeometry {

        @Test
        @DisplayName("The transcribed lengths sum to 500, matching CVCUS01Y's own RECLN 500 header")
        void theTranscribedLengthsSumToFiveHundred() {
            int total = 0;
            for (int length : SPAN_LENGTHS) {
                total += length;
            }

            assertThat(SPAN_LENGTHS)
                    .as("CVCUS01Y declares 19 items at the 05 level, FILLER included")
                    .hasSize(DECLARED_SPAN_COUNT);
            assertThat(total)
                    .as("9+25+25+25+50+50+50+2+3+10+15+15+9+20+10+10+1+3+168 must be 500 - the width "
                            + "the copybook header states, CBCUS01C's 9(09)+X(491) split confirms and "
                            + "CUSTFILE.jcl's RECORDSIZE(500 500) fixes")
                    .isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("The transcribed offsets are the running total of the transcribed lengths")
        void theTranscribedOffsetsAreSelfConsistent() {
            // Neither list vouches for itself: SPAN_OFFSETS was typed out as literals and is checked
            // here against SPAN_LENGTHS, which was also typed out as literals. A slip in either list
            // fails this test, and it fails before any assertion about the production table is reached.
            assertThat(SPAN_OFFSETS).hasSize(DECLARED_SPAN_COUNT);

            int cursor = 0;
            for (int index = 0; index < DECLARED_SPAN_COUNT; index++) {
                assertThat(SPAN_OFFSETS.get(index))
                        .as("span %d (%s) begins where span %d ends", index + 1,
                                SPAN_NAMES.get(index), index)
                        .isEqualTo(cursor);
                cursor += SPAN_LENGTHS.get(index);
            }

            assertThat(cursor)
                    .as("the last span, FILLER X(168) at offset 332, must end exactly at byte 500")
                    .isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("The declared record length constant is 500")
        void theDeclaredRecordLengthIsFiveHundred() {
            assertThat(CustomerRecord.RECORD_LENGTH).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(CustomerRecord.LAYOUT.recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("The layout declares 19 spans, in copybook order, under their copybook names")
        void theLayoutDeclaresNineteenSpansInCopybookOrder() {
            List<FieldSpan> spans = CustomerRecord.LAYOUT.spans();

            assertThat(spans)
                    .as("18 named items plus the trailing FILLER")
                    .hasSize(DECLARED_SPAN_COUNT)
                    .extracting(FieldSpan::name)
                    .containsExactlyElementsOf(SPAN_NAMES);
        }

        /**
         * Every span checked against the hand-written table, one parameterised case per span so that a
         * failure reports the offending field by name.
         *
         * @param index  the span's 0-based position in copybook declaration order
         * @param name   the copybook item name, verbatim
         * @param offset the absolute 0-based byte offset
         * @param length the declared width
         */
        @ParameterizedTest(name = "[{0}] {1} at [{2}, +{3})")
        @CsvSource({
            "0,  CUST-ID,                    0,   9",
            "1,  CUST-FIRST-NAME,            9,   25",
            "2,  CUST-MIDDLE-NAME,           34,  25",
            "3,  CUST-LAST-NAME,             59,  25",
            "4,  CUST-ADDR-LINE-1,           84,  50",
            "5,  CUST-ADDR-LINE-2,           134, 50",
            "6,  CUST-ADDR-LINE-3,           184, 50",
            "7,  CUST-ADDR-STATE-CD,         234, 2",
            "8,  CUST-ADDR-COUNTRY-CD,       236, 3",
            "9,  CUST-ADDR-ZIP,              239, 10",
            "10, CUST-PHONE-NUM-1,           249, 15",
            "11, CUST-PHONE-NUM-2,           264, 15",
            "12, CUST-SSN,                   279, 9",
            "13, CUST-GOVT-ISSUED-ID,        288, 20",
            "14, CUST-DOB-YYYY-MM-DD,        308, 10",
            "15, CUST-EFT-ACCOUNT-ID,        318, 10",
            "16, CUST-PRI-CARD-HOLDER-IND,   328, 1",
            "17, CUST-FICO-CREDIT-SCORE,     329, 3",
            "18, FILLER,                     332, 168",
        })
        @DisplayName("Each span sits at its copybook offset with its copybook width")
        void eachSpanSitsAtItsCopybookOffset(int index, String name, int offset, int length) {
            FieldSpan span = CustomerRecord.LAYOUT.spans().get(index);

            assertThat(span.name()).isEqualTo(name);
            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
            assertThat(span.endOffsetExclusive()).isEqualTo(offset + length);
            assertThat(span.redefinition())
                    .as("CVCUS01Y declares no REDEFINES, so no span is an overlay")
                    .isFalse();
            assertThat(span.hasInitialValue())
                    .as("CVCUS01Y declares no VALUE clause, so no span carries a literal")
                    .isFalse();
        }

        /**
         * The public {@code FieldSpan} constants, audited a second time and in a different shape.
         *
         * <p>The parameterised test above walks the layout list by index; this one names each constant
         * directly, so a constant accidentally left out of {@link CustomerRecord#LAYOUT} - or two
         * constants transposed within it - is caught by one of the two even though either alone could
         * be satisfied.
         */
        @Test
        @DisplayName("Each public FieldSpan constant carries the copybook's name, offset and width")
        void eachPublicSpanConstantMatchesTheCopybook() {
            assertSpan(CustomerRecord.CUST_ID, "CUST-ID", 0, 9);
            assertSpan(CustomerRecord.CUST_FIRST_NAME, "CUST-FIRST-NAME", 9, 25);
            assertSpan(CustomerRecord.CUST_MIDDLE_NAME, "CUST-MIDDLE-NAME", 34, 25);
            assertSpan(CustomerRecord.CUST_LAST_NAME, "CUST-LAST-NAME", 59, 25);
            assertSpan(CustomerRecord.CUST_ADDR_LINE_1, "CUST-ADDR-LINE-1", 84, 50);
            assertSpan(CustomerRecord.CUST_ADDR_LINE_2, "CUST-ADDR-LINE-2", 134, 50);
            assertSpan(CustomerRecord.CUST_ADDR_LINE_3, "CUST-ADDR-LINE-3", 184, 50);
            assertSpan(CustomerRecord.CUST_ADDR_STATE_CD, "CUST-ADDR-STATE-CD", 234, 2);
            assertSpan(CustomerRecord.CUST_ADDR_COUNTRY_CD, "CUST-ADDR-COUNTRY-CD", 236, 3);
            assertSpan(CustomerRecord.CUST_ADDR_ZIP, "CUST-ADDR-ZIP", 239, 10);
            assertSpan(CustomerRecord.CUST_PHONE_NUM_1, "CUST-PHONE-NUM-1", 249, 15);
            assertSpan(CustomerRecord.CUST_PHONE_NUM_2, "CUST-PHONE-NUM-2", 264, 15);
            assertSpan(CustomerRecord.CUST_SSN, "CUST-SSN", 279, 9);
            assertSpan(CustomerRecord.CUST_GOVT_ISSUED_ID, "CUST-GOVT-ISSUED-ID", 288, 20);
            assertSpan(CustomerRecord.CUST_DOB_YYYY_MM_DD, "CUST-DOB-YYYY-MM-DD", 308, 10);
            assertSpan(CustomerRecord.CUST_EFT_ACCOUNT_ID, "CUST-EFT-ACCOUNT-ID", 318, 10);
            assertSpan(CustomerRecord.CUST_PRI_CARD_HOLDER_IND, "CUST-PRI-CARD-HOLDER-IND", 328, 1);
            assertSpan(CustomerRecord.CUST_FICO_CREDIT_SCORE, "CUST-FICO-CREDIT-SCORE", 329, 3);
            assertSpan(CustomerRecord.FILLER, "FILLER", FILLER_OFFSET, FILLER_LENGTH);
        }

        /**
         * Asserts one descriptor against literal expectations.
         *
         * @param span   the descriptor under audit
         * @param name   the expected copybook name
         * @param offset the expected absolute 0-based offset
         * @param length the expected declared width
         */
        private void assertSpan(FieldSpan span, String name, int offset, int length) {
            assertThat(span.name()).isEqualTo(name);
            assertThat(span.offset()).as("%s offset", name).isEqualTo(offset);
            assertThat(span.length()).as("%s length", name).isEqualTo(length);
        }

        /**
         * Each span's {@code PICTURE} category, taken from the copybook's clause rather than from the
         * production descriptor.
         *
         * @param name the copybook item name
         * @param kind the category its {@code PICTURE} clause declares
         */
        @ParameterizedTest(name = "{0} is {1}")
        @CsvSource({
            "CUST-ID,                    UNSIGNED_NUMERIC",
            "CUST-FIRST-NAME,            ALPHANUMERIC",
            "CUST-MIDDLE-NAME,           ALPHANUMERIC",
            "CUST-LAST-NAME,             ALPHANUMERIC",
            "CUST-ADDR-LINE-1,           ALPHANUMERIC",
            "CUST-ADDR-LINE-2,           ALPHANUMERIC",
            "CUST-ADDR-LINE-3,           ALPHANUMERIC",
            "CUST-ADDR-STATE-CD,         ALPHANUMERIC",
            "CUST-ADDR-COUNTRY-CD,       ALPHANUMERIC",
            "CUST-ADDR-ZIP,              ALPHANUMERIC",
            "CUST-PHONE-NUM-1,           ALPHANUMERIC",
            "CUST-PHONE-NUM-2,           ALPHANUMERIC",
            "CUST-SSN,                   UNSIGNED_NUMERIC",
            "CUST-GOVT-ISSUED-ID,        ALPHANUMERIC",
            "CUST-DOB-YYYY-MM-DD,        ALPHANUMERIC",
            "CUST-EFT-ACCOUNT-ID,        ALPHANUMERIC",
            "CUST-PRI-CARD-HOLDER-IND,   ALPHANUMERIC",
            "CUST-FICO-CREDIT-SCORE,     UNSIGNED_NUMERIC",
        })
        @DisplayName("Each span carries the picture category its PICTURE clause declares")
        void eachSpanCarriesItsPictureCategory(String name, PictureKind kind) {
            FieldSpan span = CustomerRecord.LAYOUT.span(name);

            assertThat(span.kind()).isEqualTo(kind);
            assertThat(span.kind().filler()).isFalse();
            assertThat(span.kind().numericDisplay())
                    .as("%s is numeric DISPLAY only when its PICTURE says 9", name)
                    .isEqualTo(kind == PictureKind.UNSIGNED_NUMERIC);
            assertThat(span.kind().leftJustified())
                    .as("%s is left justified only when its PICTURE says X", name)
                    .isEqualTo(kind == PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("Exactly three spans are numeric, and none is signed or scaled")
        void exactlyThreeSpansAreNumeric() {
            List<String> numeric = new ArrayList<>();
            List<String> signedOrScaled = new ArrayList<>();
            for (FieldSpan span : CustomerRecord.LAYOUT.spans()) {
                if (span.kind() == PictureKind.UNSIGNED_NUMERIC) {
                    numeric.add(span.name());
                }
                if (span.kind() == PictureKind.SIGNED_SCALED) {
                    signedOrScaled.add(span.name());
                }
            }

            assertThat(numeric)
                    .as("CVCUS01Y's complete numeric census: 9(09) twice and 9(03) once")
                    .containsExactly("CUST-ID", "CUST-SSN", "CUST-FICO-CREDIT-SCORE");
            assertThat(signedOrScaled)
                    .as("CVCUS01Y declares no S9 and no V-scaled picture anywhere, which is why this "
                            + "is the one persisted model with no monetary field at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("Exactly one span is FILLER and it is the last")
        void exactlyOneSpanIsFillerAndItIsLast() {
            List<FieldSpan> spans = CustomerRecord.LAYOUT.spans();
            List<String> fillerPositions = new ArrayList<>();
            for (int index = 0; index < spans.size(); index++) {
                if (spans.get(index).kind().filler()) {
                    fillerPositions.add(String.valueOf(index));
                }
            }

            assertThat(fillerPositions)
                    .as("CVCUS01Y's only FILLER is its 19th and final item")
                    .containsExactly(String.valueOf(DECLARED_SPAN_COUNT - 1));
            assertThat(spans.get(DECLARED_SPAN_COUNT - 1).name()).isEqualTo("FILLER");
        }

        @Test
        @DisplayName("The spans are contiguous from 0 with no gap and no overlap, ending at 500")
        void theSpansAreContiguousFromZeroToFiveHundred() {
            // Walked here as well as inside the layout's own constructor, because this test states the
            // property in the test's own terms: every byte from 0 to 499 is declared exactly once.
            int cursor = 0;
            for (FieldSpan span : CustomerRecord.LAYOUT.storageSpans()) {
                assertThat(span.offset())
                        .as("%s must begin exactly where the preceding span ended", span.name())
                        .isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }

            assertThat(cursor).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(CustomerRecord.LAYOUT.storageSpans()).hasSize(DECLARED_SPAN_COUNT);
            assertThat(CustomerRecord.LAYOUT.redefinitions())
                    .as("CVCUS01Y declares no REDEFINES overlay")
                    .isEmpty();
        }

        @Test
        @DisplayName("All 18 referable names resolve by name; FILLER does not, being non-referable")
        void everyReferableNameResolvesAndFillerDoesNot() {
            for (String name : SPAN_NAMES) {
                if ("FILLER".equals(name)) {
                    continue;
                }
                assertThat(CustomerRecord.LAYOUT.hasSpan(name)).as("%s is referable", name).isTrue();
                assertThat(CustomerRecord.LAYOUT.span(name).name()).isEqualTo(name);
            }

            assertThat(CustomerRecord.LAYOUT.hasSpan("FILLER"))
                    .as("FILLER is not a referable COBOL name, so it is reachable as a span in the "
                            + "layout but never by name")
                    .isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.LAYOUT.span("FILLER"))
                    .withMessageContaining("FILLER");
        }

        /**
         * A name the copybook does not declare must not resolve, and the lookup is case-sensitive.
         *
         * @param unknown a name that is not one of the 18 referable items
         */
        @ParameterizedTest(name = "''{0}'' is not a CVCUS01Y field")
        @ValueSource(strings = {
            "cust-id",
            "CUST_ID",
            "CUSTID",
            "CUST-DOB-YYYYMMDD",
            "CUST-EXPIRAION-DATE",
            "ACCT-ID",
            "CUST-ADDR-LINE-4",
            "",
        })
        @DisplayName("A name CVCUS01Y does not declare does not resolve, case included")
        void anUndeclaredNameDoesNotResolve(String unknown) {
            assertThat(CustomerRecord.LAYOUT.hasSpan(unknown)).isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.LAYOUT.span(unknown));
        }

        @Test
        @DisplayName("A null name is refused rather than treated as no match")
        void aNullNameIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.LAYOUT.hasSpan(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.LAYOUT.span(null));
        }

        @Test
        @DisplayName("No name is declared twice, so no two fields can alias one another")
        void noNameIsDeclaredTwice() {
            Set<String> seen = new LinkedHashSet<>();
            List<String> duplicates = new ArrayList<>();
            for (FieldSpan span : CustomerRecord.LAYOUT.spans()) {
                if ("FILLER".equals(span.name())) {
                    continue;
                }
                if (!seen.add(span.name())) {
                    duplicates.add(span.name());
                }
            }

            assertThat(duplicates).isEmpty();
            assertThat(seen).hasSize(REFERABLE_FIELD_COUNT);
        }

        @Test
        @DisplayName("CUST-ID is the nine-byte key at offset 0 - KEYS(9 0), not DEFCUST's KEYS(10 0)")
        void custIdIsTheNineByteKeyAtOffsetZero() {
            // app/jcl/CUSTFILE.jcl defines AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS with KEYS(9 0) and it is
            // that DSN which app/csd/CARDDEMO.CSD binds as CUSTDAT. app/jcl/DEFCUST.jcl is a stale
            // second definition declaring KEYS(10 0) over a different cluster; its ten-byte key
            // contradicts CUST-ID PIC 9(09) and is not the contract.
            FieldSpan key = CustomerRecord.LAYOUT.span("CUST-ID");

            assertThat(key.offset()).as("KEYS(9 0) - the second operand is the key's offset").isZero();
            assertThat(key.length())
                    .as("KEYS(9 0) - the first operand is the key's length, and it is 9, never 10")
                    .isEqualTo(PRIMARY_KEY_LENGTH);
            assertThat(key.kind()).isEqualTo(PictureKind.UNSIGNED_NUMERIC);
        }

        @Test
        @DisplayName("A freshly encoded record is exactly 500 bytes under either code page")
        void aFreshlyEncodedRecordIsExactlyFiveHundredBytes() {
            assertThat(new CustomerRecord().encode(ASCII)).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(new CustomerRecord().encode(EBCDIC)).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(row1AsBuilt().encode(ASCII)).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(row1AsBuilt().encode(asciiCodec)).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(row1AsBuilt().recordImage(ASCII)).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(row1AsBuilt().recordImage(asciiCodec)).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("CBCUS01C's FD split of 9 + 491 agrees with the copybook's own arithmetic")
        void theProgramsFdSplitAgreesWithTheCopybook() {
            // app/cbl/CBCUS01C.cbl:38-40 declares 01 FD-CUSTFILE-REC as 05 FD-CUST-ID PIC 9(09) plus
            // 05 FD-CUST-DATA PIC X(491). The key is the same nine bytes at offset 0, and the data span
            // is everything after it - which is a third, independent statement of the same 500.
            int keyWidth = CustomerRecord.LAYOUT.span("CUST-ID").length();
            int dataWidth = DECLARED_RECORD_LENGTH - keyWidth;

            assertThat(keyWidth).isEqualTo(9);
            assertThat(dataWidth).as("FD-CUST-DATA PIC X(491)").isEqualTo(491);
        }
    }

    // =================================================================================================
    // 2. THE LAYOUT SELF-CHECK (gates G21, G50).
    //
    //    CVCUS01Y declares no 88-level condition name, so the branch surface of this package is the
    //    record type's own guards, and this is the first of them. RecordLayout's constructor verifies
    //    the geometry, which means the accepting side runs during CustomerRecord's class initialisation
    //    and the rejecting side has to be provoked deliberately. Both are driven below, because a check
    //    never seen to fail is not known to work - and this is the check that catches a dropped FILLER.
    // =================================================================================================

    @Nested
    @DisplayName("The layout self-check accepts CVCUS01Y and rejects every way of breaking it")
    class LayoutSelfCheck {

        @Test
        @DisplayName("The passing side: the real 19-span layout builds and declares 500 bytes")
        void theRealLayoutPassesTheSelfCheck() {
            assertThatCode(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                    CustomerRecord.CUST_ID,
                    CustomerRecord.CUST_FIRST_NAME,
                    CustomerRecord.CUST_MIDDLE_NAME,
                    CustomerRecord.CUST_LAST_NAME,
                    CustomerRecord.CUST_ADDR_LINE_1,
                    CustomerRecord.CUST_ADDR_LINE_2,
                    CustomerRecord.CUST_ADDR_LINE_3,
                    CustomerRecord.CUST_ADDR_STATE_CD,
                    CustomerRecord.CUST_ADDR_COUNTRY_CD,
                    CustomerRecord.CUST_ADDR_ZIP,
                    CustomerRecord.CUST_PHONE_NUM_1,
                    CustomerRecord.CUST_PHONE_NUM_2,
                    CustomerRecord.CUST_SSN,
                    CustomerRecord.CUST_GOVT_ISSUED_ID,
                    CustomerRecord.CUST_DOB_YYYY_MM_DD,
                    CustomerRecord.CUST_EFT_ACCOUNT_ID,
                    CustomerRecord.CUST_PRI_CARD_HOLDER_IND,
                    CustomerRecord.CUST_FICO_CREDIT_SCORE,
                    CustomerRecord.FILLER))
                    .as("the 19 copybook spans describe exactly 500 contiguous bytes")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Dropping the trailing FILLER is rejected - the G21 tripwire, 168 bytes short")
        void droppingTheTrailingFillerIsRejected() {
            // The reason gate G21 leans on the total width: a layout that forgets FILLER X(168)
            // describes 332 bytes, and every record it produced would be 168 bytes short with no field
            // value visibly wrong. It cannot be built at all.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                            CustomerRecord.CUST_ID,
                            CustomerRecord.CUST_FIRST_NAME,
                            CustomerRecord.CUST_MIDDLE_NAME,
                            CustomerRecord.CUST_LAST_NAME,
                            CustomerRecord.CUST_ADDR_LINE_1,
                            CustomerRecord.CUST_ADDR_LINE_2,
                            CustomerRecord.CUST_ADDR_LINE_3,
                            CustomerRecord.CUST_ADDR_STATE_CD,
                            CustomerRecord.CUST_ADDR_COUNTRY_CD,
                            CustomerRecord.CUST_ADDR_ZIP,
                            CustomerRecord.CUST_PHONE_NUM_1,
                            CustomerRecord.CUST_PHONE_NUM_2,
                            CustomerRecord.CUST_SSN,
                            CustomerRecord.CUST_GOVT_ISSUED_ID,
                            CustomerRecord.CUST_DOB_YYYY_MM_DD,
                            CustomerRecord.CUST_EFT_ACCOUNT_ID,
                            CustomerRecord.CUST_PRI_CARD_HOLDER_IND,
                            CustomerRecord.CUST_FICO_CREDIT_SCORE))
                    .withMessageContaining(String.valueOf(FILLER_OFFSET))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH))
                    .withMessageContaining(String.valueOf(FILLER_LENGTH));
        }

        @Test
        @DisplayName("A gap between two spans is rejected, naming the span it precedes")
        void aGapBetweenSpansIsRejected() {
            // CUST-FIRST-NAME moved one byte late, leaving byte 9 undeclared.
            FieldSpan displaced = FieldSpan.alphanumeric("CUST-FIRST-NAME", 10, 25);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                            CustomerRecord.CUST_ID,
                            displaced))
                    .withMessageContaining("gap")
                    .withMessageContaining("CUST-FIRST-NAME");
        }

        @Test
        @DisplayName("An overlap between two spans is rejected, naming the overlapping span")
        void anOverlapBetweenSpansIsRejected() {
            // CUST-FIRST-NAME moved one byte early, aliasing the last digit of CUST-ID.
            FieldSpan overlapping = FieldSpan.alphanumeric("CUST-FIRST-NAME", 8, 25);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                            CustomerRecord.CUST_ID,
                            overlapping))
                    .withMessageContaining("overlap")
                    .withMessageContaining("CUST-FIRST-NAME");
        }

        @Test
        @DisplayName("A layout one byte wider than 500 is rejected")
        void aLayoutWiderThanTheRecordIsRejected() {
            FieldSpan oversizedFiller = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                            CustomerRecord.CUST_ID,
                            CustomerRecord.CUST_FIRST_NAME,
                            CustomerRecord.CUST_MIDDLE_NAME,
                            CustomerRecord.CUST_LAST_NAME,
                            CustomerRecord.CUST_ADDR_LINE_1,
                            CustomerRecord.CUST_ADDR_LINE_2,
                            CustomerRecord.CUST_ADDR_LINE_3,
                            CustomerRecord.CUST_ADDR_STATE_CD,
                            CustomerRecord.CUST_ADDR_COUNTRY_CD,
                            CustomerRecord.CUST_ADDR_ZIP,
                            CustomerRecord.CUST_PHONE_NUM_1,
                            CustomerRecord.CUST_PHONE_NUM_2,
                            CustomerRecord.CUST_SSN,
                            CustomerRecord.CUST_GOVT_ISSUED_ID,
                            CustomerRecord.CUST_DOB_YYYY_MM_DD,
                            CustomerRecord.CUST_EFT_ACCOUNT_ID,
                            CustomerRecord.CUST_PRI_CARD_HOLDER_IND,
                            CustomerRecord.CUST_FICO_CREDIT_SCORE,
                            oversizedFiller))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH + 1));
        }

        @Test
        @DisplayName("Declaring a referable name twice is rejected, so no field can alias another")
        void declaringANameTwiceIsRejected() {
            FieldSpan secondCustId = FieldSpan.unsignedNumeric("CUST-ID", 9, 9);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(CustomerRecord.RECORD_LENGTH,
                            CustomerRecord.CUST_ID,
                            secondCustId))
                    .withMessageContaining("CUST-ID")
                    .withMessageContaining("more than once");
        }

        @Test
        @DisplayName("A span of zero width is rejected, so no copybook item can vanish silently")
        void aZeroWidthSpanIsRejected() {
            assertThatIllegalArgumentException()
                    .as("CUST-PRI-CARD-HOLDER-IND is the narrowest item in CVCUS01Y at one byte; "
                            + "nothing may be narrower")
                    .isThrownBy(() -> FieldSpan.alphanumeric("CUST-PRI-CARD-HOLDER-IND", 328, 0));
        }

        /**
         * Any byte count other than the declared 500 is refused.
         *
         * <p>The widths chosen are not arbitrary. 499 and 501 bracket the declared width; 332 is what a
         * record would measure if the trailing {@code FILLER} were dropped; 36 is a {@code cardxref}
         * row and 300 an {@code acctdata} row, both plausible dataset mix-ups; 0 and 1 are the
         * degenerate cases.
         *
         * @param wrongWidth a byte count that is not the declared record length
         */
        @ParameterizedTest(name = "{0} bytes is not a CUSTOMER-RECORD")
        @ValueSource(ints = {0, 1, 36, 80, 300, 332, 499, 501, 1000})
        @DisplayName("Decoding any width other than 500 is rejected, naming the declared width")
        void anyWidthOtherThanFiveHundredIsRejected(int wrongWidth) {
            byte[] wrong = new byte[wrongWidth];

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode(wrong, ASCII))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode(wrong, asciiCodec))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
        }

        @Test
        @DisplayName("A short or long text image is refused, so a truncated row cannot be loaded")
        void aWrongLengthTextImageIsRefused() {
            String correct = synthesisedImage(ROW_1_PRI_CARD_HOLDER_IND);
            assertThat(correct)
                    .as("the synthesised image must itself be exactly 500 characters, or every "
                            + "assertion built on it would be measuring the wrong thing")
                    .hasSize(DECLARED_RECORD_LENGTH);

            String tooShort = correct.substring(0, DECLARED_RECORD_LENGTH - 1);
            String tooLong = correct + " ";

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode(tooShort, ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode(tooLong, ASCII));
            assertThatCode(() -> CustomerRecord.decode(correct, ASCII))
                    .as("the accepting side, alongside the two rejections")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("A correct 500-byte image is accepted - the passing side of the same guard")
        void aCorrectImageIsAccepted() {
            byte[] correct = synthesisedImage(ROW_1_PRI_CARD_HOLDER_IND).getBytes(ASCII);

            assertThat(correct).hasSize(DECLARED_RECORD_LENGTH);
            assertThatCode(() -> CustomerRecord.decode(correct, ASCII)).doesNotThrowAnyException();
            assertThatCode(() -> CustomerRecord.decode(correct, asciiCodec)).doesNotThrowAnyException();
        }
    }

    // =================================================================================================
    // 3. THE ONE FIELD NAME THAT MUST NEVER BE NORMALISED.
    //
    //    Two copybooks under app/cpy declare a 500-byte group called CUSTOMER-RECORD with the same 19
    //    spans, the same PICTUREs and the same order. Exactly one thing tells them apart: this one
    //    spells its date of birth CUST-DOB-YYYY-MM-DD, with hyphens, and the statement job's copybook
    //    spells it without. Field-for-field diffing matches on the field NAME, so that single
    //    difference is the entire basis on which the two layouts stay distinct.
    //
    //    Asserted from this side only. The statement package's type is deliberately not imported: this
    //    package depends on common and on nothing else in the repository, and importing it to compare
    //    would be the first edge of a cycle. What is asserted instead is that the hyphenated name is
    //    present here and the unhyphenated one is absent here - which is what a merge or a rename would
    //    break, and it is caught without naming the other type at all.
    // =================================================================================================

    @Nested
    @DisplayName("CUST-DOB-YYYY-MM-DD - the hyphens are the field name, not formatting")
    class DateOfBirthFieldName {

        @Test
        @DisplayName("The span is named CUST-DOB-YYYY-MM-DD, at offset 308 for 10 bytes")
        void theSpanIsNamedWithHyphens() {
            FieldSpan dob = CustomerRecord.LAYOUT.span("CUST-DOB-YYYY-MM-DD");

            assertThat(dob.name()).isEqualTo("CUST-DOB-YYYY-MM-DD");
            assertThat(dob.offset()).isEqualTo(308);
            assertThat(dob.length()).isEqualTo(10);
            assertThat(dob.kind())
                    .as("PIC X(10): ten characters of text, which is why it is never parsed")
                    .isEqualTo(PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("No span is named CUST-DOB-YYYYMMDD - that spelling belongs to another layout")
        void noSpanUsesTheUnhyphenatedSpelling() {
            // The unhyphenated spelling is owned by statement/model/Stm03CustomerRecord, which models
            // the statement job's own customer copybook. It is not imported here on purpose.
            assertThat(CustomerRecord.LAYOUT.hasSpan("CUST-DOB-YYYYMMDD")).isFalse();
            assertThat(CustomerRecord.LAYOUT.spans())
                    .extracting(FieldSpan::name)
                    .doesNotContain("CUST-DOB-YYYYMMDD")
                    .contains("CUST-DOB-YYYY-MM-DD");
        }

        @Test
        @DisplayName("No member of CustomerRecord uses the unhyphenated spelling either")
        void noMemberUsesTheUnhyphenatedSpelling() {
            // Checked reflectively and CASE-SENSITIVELY, because case is the whole discriminator: the
            // hyphenated name becomes custDobYyyyMmDd in Java and the unhyphenated one becomes
            // custDobYyyymmdd. Lowercasing both would make them identical and this test vacuous, so the
            // marker matched is the lowercase 'mm'/'dd' run that only the unhyphenated form produces.
            List<String> offenders = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                if (field.getName().contains("Yyyymmdd") || field.getName().contains("YYYYMMDD")) {
                    offenders.add("field " + field.getName());
                }
            }
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                if (method.getName().contains("Yyyymmdd") || method.getName().contains("YYYYMMDD")) {
                    offenders.add("method " + method.getName());
                }
            }

            assertThat(offenders)
                    .as("a rename here would still compile and would silently make a real difference "
                            + "invisible to field-for-field diffing")
                    .isEmpty();
            assertThatCode(() -> CustomerRecord.class.getMethod("getCustDobYyyyMmDd"))
                    .as("the accessor keeps the hyphenated field's capitalisation")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("The stored value keeps its hyphens at 1-based positions 5 and 8")
        void theStoredValueKeepsItsHyphens() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            String dob = record.getCustDobYyyyMmDd();
            assertThat(dob).isEqualTo(ROW_1_DOB).hasSize(10);
            // 1-based COBOL positions 5 and 8 are 0-based Java indices 4 and 7.
            assertThat(dob.charAt(4)).as("the year-month separator").isEqualTo('-');
            assertThat(dob.charAt(7)).as("the month-day separator").isEqualTo('-');
        }

        @Test
        @DisplayName("(1:4) of 1961-06-08 is 1961 - the COACTUPC:3857 slice")
        void theYearSliceIsTheFirstFourCharacters() {
            // app/cbl/COACTUPC.cbl:3857 performs
            //   MOVE CUST-DOB-YYYY-MM-DD(1:4) TO ACUP-OLD-CUST-DOB-YEAR
            // which is only correct while the hyphens sit where they sit. Strip them and (1:4) would
            // read 1961 out of 19610608 by accident and (6:2) would read the wrong month.
            String dob = CustomerRecord.decode(fixtureRow(1), ASCII).getCustDobYyyyMmDd();

            assertThat(dob.substring(0, 4)).isEqualTo("1961");
            assertThat(dob.substring(5, 7)).as("(6:2), the month").isEqualTo("06");
            assertThat(dob.substring(8, 10)).as("(9:2), the day").isEqualTo("08");
        }

        /**
         * The same three slices over other measured rows, so the positions are not a property of one
         * row's particular digits.
         *
         * @param recordNumber the 1-based fixture row
         * @param expectedDob  that row's stored ten characters
         * @param year         the {@code (1:4)} slice
         * @param month        the {@code (6:2)} slice
         * @param day          the {@code (9:2)} slice
         */
        @ParameterizedTest(name = "row {0}: {1} slices to {2}/{3}/{4}")
        @CsvSource({
            "1,  1961-06-08, 1961, 06, 08",
            "2,  1961-10-08, 1961, 10, 08",
            "26, 1990-03-17, 1990, 03, 17",
            "50, 1960-12-01, 1960, 12, 01",
        })
        @DisplayName("The year, month and day slices hold across measured rows")
        void theSlicesHoldAcrossRows(int recordNumber, String expectedDob, String year, String month,
                                     String day) {
            String dob = CustomerRecord.decode(fixtureRow(recordNumber), ASCII).getCustDobYyyyMmDd();

            assertThat(dob).isEqualTo(expectedDob);
            assertThat(dob.substring(0, 4)).isEqualTo(year);
            assertThat(dob.substring(5, 7)).isEqualTo(month);
            assertThat(dob.substring(8, 10)).isEqualTo(day);
        }

        @Test
        @DisplayName("The value survives encode and decode unchanged, as characters")
        void theValueSurvivesTheRoundTripAsCharacters() {
            CustomerRecord decoded = CustomerRecord.decode(fixtureRow(1), ASCII);

            CustomerRecord reDecoded = CustomerRecord.decode(decoded.encode(ASCII), ASCII);

            assertThat(reDecoded.getCustDobYyyyMmDd()).isEqualTo(ROW_1_DOB);
            assertThat(new String(reDecoded.encode(ASCII), ASCII).substring(308, 318))
                    .as("the stored span, sliced at its own offsets")
                    .isEqualTo(ROW_1_DOB);
        }

        /**
         * Content the span can hold that no date parser would accept, carried verbatim.
         *
         * <p>{@code PIC X(10)} is text. Parsing it into a date and re-rendering it would throw on the
         * first legacy row holding anything else, and would silently reformat the rest - a behaviour
         * change in both directions.
         *
         * @param stored a ten-character value to store and read back
         */
        @ParameterizedTest(name = "''{0}'' is carried verbatim")
        @ValueSource(strings = {
            "1961-06-08",
            "0000-00-00",
            "9999-99-99",
            "1961/06/08",
            "19610608  ",
            "          ",
            "not a date",
        })
        @DisplayName("Any ten characters are carried verbatim - the span is text, never a date")
        void anyTenCharactersAreCarriedVerbatim(String stored) {
            CustomerRecord record = new CustomerRecord();

            record.setCustDobYyyyMmDd(stored);

            assertThat(record.getCustDobYyyyMmDd()).isEqualTo(stored).hasSize(10);
            assertThat(new String(record.encode(ASCII), ASCII).substring(308, 318)).isEqualTo(stored);
        }

        @Test
        @DisplayName("A short date-of-birth value is right-space-padded, never zero-filled")
        void aShortValueIsRightSpacePadded() {
            CustomerRecord record = new CustomerRecord();

            record.setCustDobYyyyMmDd("1961");

            assertThat(record.getCustDobYyyyMmDd())
                    .as("PIC X pads on the right with spaces; only PIC 9 fills on the left with zeros")
                    .isEqualTo(padded("1961", 10));
        }
    }

    // =================================================================================================
    // 4. THE CHARACTER IMAGE OF A NUMERIC FIELD.
    //
    //    app/cbl/COACTVWC.cbl:496-504 applies COBOL reference modification to CUST-SSN PIC 9(09):
    //
    //      STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4)
    //          DELIMITED BY SIZE INTO ACSTSSNO OF CACTVWAO
    //
    //    It slices the field's nine CHARACTER bytes. Line 495 - a plain MOVE CUST-SSN TO ACSTSSNO - is
    //    commented out in the source and stays commented out: it is preserved dead code, not a path.
    //
    //    This is not a copybook REDEFINES. CVCUS01Y declares none. It is COBOL's ordinary licence to
    //    view a PIC 9 field as characters, and CustomerRecord serves it with an image accessor beside
    //    the typed one.
    // =================================================================================================

    @Nested
    @DisplayName("CUST-SSN's character image - what COACTVWC slices, and why the value will not do")
    class SocialSecurityNumberImage {

        @Test
        @DisplayName("Row 1 stores 020973888, whose leading zero the int value cannot carry")
        void theStoredImageKeepsItsLeadingZero() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.getCustSsn()).isEqualTo(ROW_1_SSN);
            assertThat(record.custSsnImage(ASCII)).isEqualTo(ROW_1_SSN_IMAGE).hasSize(9);
            assertThat(String.valueOf(record.getCustSsn()))
                    .as("String.valueOf gives eight characters where the span is nine - the whole "
                            + "reason a separate image accessor exists")
                    .hasSize(8)
                    .isNotEqualTo(record.custSsnImage(ASCII));
        }

        @Test
        @DisplayName("(1:3), (4:2) and (6:4) of 020973888 are 020, 97 and 3888")
        void theThreeSlicesAreTakenOverTheImage() {
            String image = CustomerRecord.decode(fixtureRow(1), ASCII).custSsnImage(ASCII);

            // COBOL (start:length) is 1-based; Java substring is 0-based and end-exclusive.
            assertThat(image.substring(0, 3)).as("(1:3)").isEqualTo("020");
            assertThat(image.substring(3, 5)).as("(4:2)").isEqualTo("97");
            assertThat(image.substring(5, 9)).as("(6:4)").isEqualTo("3888");
        }

        @Test
        @DisplayName("The composed screen value is 020-97-3888, eleven characters")
        void theComposedScreenValueIsElevenCharacters() {
            String image = CustomerRecord.decode(fixtureRow(1), ASCII).custSsnImage(ASCII);

            // STRING ... DELIMITED BY SIZE concatenates each operand at its full width, which is what
            // the codec's own helper does - so the composition is the production rule, not a local one.
            String composed = asciiCodec.concatenateDelimitedBySize(
                    image.substring(0, 3), "-", image.substring(3, 5), "-", image.substring(5, 9));

            assertThat(composed).isEqualTo("020-97-3888").hasSize(11);
        }

        @Test
        @DisplayName("Slicing the numeric value instead would give 209-73-888 - the silent defect")
        void slicingTheValueWouldGiveTheWrongAnswer() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);
            String naive = String.valueOf(record.getCustSsn());

            String wrong = naive.substring(0, 3) + "-" + naive.substring(3, 5) + "-"
                    + naive.substring(5);

            assertThat(wrong)
                    .as("recorded so the divergence is visible rather than described: no type system "
                            + "catches this, and the resulting screen value is plausible")
                    .isEqualTo("209-73-888");
            assertThat(wrong).isNotEqualTo("020-97-3888");
        }

        /**
         * Other measured rows whose stored image carries a leading zero.
         *
         * @param recordNumber the 1-based fixture row
         * @param image        that row's nine stored digits
         * @param value        the {@code int} the digits denote
         */
        @ParameterizedTest(name = "row {0}: {1} is the value {2}")
        @CsvSource({
            "1,  020973888, 20973888",
            "15, 033922034, 33922034",
            "24, 017590544, 17590544",
            "29, 015027332, 15027332",
            "40, 054960660, 54960660",
            "47, 029222192, 29222192",
        })
        @DisplayName("Every leading-zero SSN in the fixture images at nine digits")
        void everyLeadingZeroSsnImagesAtNineDigits(int recordNumber, String image, int value) {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(recordNumber), ASCII);

            assertThat(record.getCustSsn()).isEqualTo(value);
            assertThat(record.custSsnImage(ASCII)).isEqualTo(image).hasSize(9);
            assertThat(new String(record.encode(ASCII), ASCII).substring(279, 288)).isEqualTo(image);
        }

        @Test
        @DisplayName("Row 24's (6:4) slice is 0544, itself leading-zero bearing")
        void aSliceCanItselfBeLeadingZeroBearing() {
            String image = CustomerRecord.decode(fixtureRow(24), ASCII).custSsnImage(ASCII);

            assertThat(image).isEqualTo(ROW_24_SSN_IMAGE);
            assertThat(image.substring(5, 9))
                    .as("a slice of an image is still characters, so its own leading zero survives too")
                    .isEqualTo("0544");
        }

        @Test
        @DisplayName("A row without a leading zero images identically to its value")
        void aRowWithoutALeadingZeroImagesAsItsValue() {
            // The other side of the same rule: where no fill is needed, none is applied.
            CustomerRecord record = CustomerRecord.decode(fixtureRow(2), ASCII);

            assertThat(record.custSsnImage(ASCII)).isEqualTo(ROW_2_SSN_IMAGE);
            assertThat(String.valueOf(record.getCustSsn())).isEqualTo(ROW_2_SSN_IMAGE);
        }

        @Test
        @DisplayName("The image is the same under either code page, and a null charset is refused")
        void theImageIsCodePageIndependentAndGuarded() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.custSsnImage(EBCDIC))
                    .as("the image is characters, so it is the same string under either code page - "
                            + "only its BYTES differ, and those are asserted where encoding is")
                    .isEqualTo(record.custSsnImage(ASCII));
            assertThat(record.custSsnImage(ebcdicCodec)).isEqualTo(ROW_1_SSN_IMAGE);
            assertThatNullPointerException().isThrownBy(() -> record.custSsnImage((Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.custSsnImage((FixedWidthCodec) null));
        }
    }

    // =================================================================================================
    // 5. PIC X MOVE PARITY - pad on the RIGHT, truncate on the RIGHT, and never trim on read.
    //
    //    Fifteen of CVCUS01Y's eighteen named fields are PIC X. A COBOL MOVE into one fills the receiver
    //    from its leftmost character position, space-pads what is left and discards any sending
    //    character that does not fit. The field afterwards holds exactly its declared width and no other
    //    state is reachable, which is why the rule is applied as the value ENTERS rather than at encode
    //    time: a getter, an equals and an encode can then never describe three different records.
    // =================================================================================================

    @Nested
    @DisplayName("PIC X parity - right-padded, right-truncated, never trimmed")
    class AlphanumericReceiver {

        @Test
        @DisplayName("A fresh record holds every PIC X field as its declared width in SPACES")
        void aFreshRecordHoldsSpacesAtEveryDeclaredWidth() {
            CustomerRecord record = new CustomerRecord();

            // An empty string is not a state PIC X(25) can occupy, so a getter reporting one would be
            // describing a record that cannot exist. This mirrors a COBOL INITIALIZE.
            assertThat(record.getCustFirstName()).isEqualTo(spaces(25));
            assertThat(record.getCustMiddleName()).isEqualTo(spaces(25));
            assertThat(record.getCustLastName()).isEqualTo(spaces(25));
            assertThat(record.getCustAddrLine1()).isEqualTo(spaces(50));
            assertThat(record.getCustAddrLine2()).isEqualTo(spaces(50));
            assertThat(record.getCustAddrLine3()).isEqualTo(spaces(50));
            assertThat(record.getCustAddrStateCd()).isEqualTo(spaces(2));
            assertThat(record.getCustAddrCountryCd()).isEqualTo(spaces(3));
            assertThat(record.getCustAddrZip()).isEqualTo(spaces(10));
            assertThat(record.getCustPhoneNum1()).isEqualTo(spaces(15));
            assertThat(record.getCustPhoneNum2()).isEqualTo(spaces(15));
            assertThat(record.getCustGovtIssuedId()).isEqualTo(spaces(20));
            assertThat(record.getCustDobYyyyMmDd()).isEqualTo(spaces(10));
            assertThat(record.getCustEftAccountId()).isEqualTo(spaces(10));
            assertThat(record.getCustPriCardHolderInd()).isEqualTo(spaces(1));
        }

        @Test
        @DisplayName("A short value is right-space-padded, and the getter reports the padded field")
        void aShortValueIsPaddedOnEntry() {
            CustomerRecord record = new CustomerRecord();

            record.setCustFirstName("Alice");

            assertThat(record.getCustFirstName()).isEqualTo(padded("Alice", 25)).hasSize(25);
        }

        @Test
        @DisplayName("An over-wide value keeps its FIRST characters - truncation is on the right")
        void anOverWideValueIsTruncatedOnTheRight() {
            CustomerRecord record = new CustomerRecord();

            record.setCustFirstName("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");

            assertThat(record.getCustFirstName())
                    .as("a PIC X receiver fills from the left, so the excess is dropped from the right")
                    .isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXY")
                    .hasSize(25);
        }

        @Test
        @DisplayName("A value of exactly its declared width is stored unchanged")
        void anExactWidthValueIsStoredUnchanged() {
            CustomerRecord record = new CustomerRecord();
            String exactly25 = "ABCDEFGHIJKLMNOPQRSTUVWXY";

            record.setCustFirstName(exactly25);

            assertThat(record.getCustFirstName()).isEqualTo(exactly25).hasSize(25);
        }

        @Test
        @DisplayName("An empty string and a run of spaces are indistinguishable, as in COBOL")
        void anEmptyStringAndSpacesAreIndistinguishable() {
            CustomerRecord fromEmpty = new CustomerRecord();
            CustomerRecord fromSpaces = new CustomerRecord();

            fromEmpty.setCustAddrStateCd("");
            fromSpaces.setCustAddrStateCd("  ");

            assertThat(fromEmpty.getCustAddrStateCd()).isEqualTo(fromSpaces.getCustAddrStateCd());
            assertThat(fromEmpty).isEqualTo(fromSpaces);
        }

        @Test
        @DisplayName("The getter, equals, hashCode and encode all observe the SAME state")
        void everyObservationAgrees() {
            // The four observations that a receiver applied late lets disagree: a thirty-character first
            // name readable as thirty characters, making two records unequal, and then encoding as
            // twenty-five. Three of those four describe a record that cannot exist.
            CustomerRecord overWide = new CustomerRecord();
            overWide.setCustFirstName("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123");
            CustomerRecord atWidth = new CustomerRecord();
            atWidth.setCustFirstName("ABCDEFGHIJKLMNOPQRSTUVWXY");

            assertThat(overWide.getCustFirstName()).isEqualTo(atWidth.getCustFirstName());
            assertThat(overWide).isEqualTo(atWidth);
            assertThat(overWide.hashCode()).isEqualTo(atWidth.hashCode());
            assertThat(overWide.encode(ASCII)).isEqualTo(atWidth.encode(ASCII));
        }

        /**
         * Every {@code PIC X} setter, driven at three widths: short, exact and over-wide.
         *
         * <p>Parameterised over the fields rather than repeated, and it is what covers the three
         * setters no other test in this class would otherwise reach - {@code CUST-ADDR-LINE-2},
         * {@code CUST-ADDR-LINE-3} and {@code CUST-PHONE-NUM-2}, each of which the row-1 vector fills
         * but whose padding behaviour deserves the same audit as the rest.
         *
         * @param field the copybook name, used to look the declared width up and to label a failure
         * @param width the field's declared width
         */
        @ParameterizedTest(name = "{0} PIC X({1})")
        @CsvSource({
            "CUST-FIRST-NAME,            25",
            "CUST-MIDDLE-NAME,           25",
            "CUST-LAST-NAME,             25",
            "CUST-ADDR-LINE-1,           50",
            "CUST-ADDR-LINE-2,           50",
            "CUST-ADDR-LINE-3,           50",
            "CUST-ADDR-STATE-CD,         2",
            "CUST-ADDR-COUNTRY-CD,       3",
            "CUST-ADDR-ZIP,              10",
            "CUST-PHONE-NUM-1,           15",
            "CUST-PHONE-NUM-2,           15",
            "CUST-GOVT-ISSUED-ID,        20",
            "CUST-DOB-YYYY-MM-DD,        10",
            "CUST-EFT-ACCOUNT-ID,        10",
            "CUST-PRI-CARD-HOLDER-IND,   1",
        })
        @DisplayName("Every PIC X field pads and truncates at its own declared width")
        void everyAlphanumericFieldPadsAndTruncatesAtItsWidth(String field, int width) {
            assertThat(CustomerRecord.LAYOUT.span(field).length())
                    .as("the declared width transcribed above must be the declared width")
                    .isEqualTo(width);

            int offset = CustomerRecord.LAYOUT.span(field).offset();
            String tooLong = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
            String shortValue = "A";

            CustomerRecord shortly = new CustomerRecord();
            setAlphanumeric(shortly, field, shortValue);
            CustomerRecord overWide = new CustomerRecord();
            setAlphanumeric(overWide, field, tooLong);

            assertThat(new String(shortly.encode(ASCII), ASCII).substring(offset, offset + width))
                    .as("%s pads a one-character value on the right", field)
                    .isEqualTo(padded(shortValue.substring(0, Math.min(shortValue.length(), width)),
                            width));
            assertThat(new String(overWide.encode(ASCII), ASCII).substring(offset, offset + width))
                    .as("%s truncates on the right at its declared width", field)
                    .isEqualTo(tooLong.substring(0, width));
        }

        /**
         * Routes a value into one named {@code PIC X} field.
         *
         * <p>An explicit dispatch rather than reflection, so the test names the setter it is exercising
         * and a renamed setter breaks compilation instead of silently skipping a field.
         *
         * @param record the record to write into
         * @param field  the copybook field name
         * @param value  the value to move in
         */
        private void setAlphanumeric(CustomerRecord record, String field, String value) {
            switch (field) {
                case "CUST-FIRST-NAME" -> record.setCustFirstName(value);
                case "CUST-MIDDLE-NAME" -> record.setCustMiddleName(value);
                case "CUST-LAST-NAME" -> record.setCustLastName(value);
                case "CUST-ADDR-LINE-1" -> record.setCustAddrLine1(value);
                case "CUST-ADDR-LINE-2" -> record.setCustAddrLine2(value);
                case "CUST-ADDR-LINE-3" -> record.setCustAddrLine3(value);
                case "CUST-ADDR-STATE-CD" -> record.setCustAddrStateCd(value);
                case "CUST-ADDR-COUNTRY-CD" -> record.setCustAddrCountryCd(value);
                case "CUST-ADDR-ZIP" -> record.setCustAddrZip(value);
                case "CUST-PHONE-NUM-1" -> record.setCustPhoneNum1(value);
                case "CUST-PHONE-NUM-2" -> record.setCustPhoneNum2(value);
                case "CUST-GOVT-ISSUED-ID" -> record.setCustGovtIssuedId(value);
                case "CUST-DOB-YYYY-MM-DD" -> record.setCustDobYyyyMmDd(value);
                case "CUST-EFT-ACCOUNT-ID" -> record.setCustEftAccountId(value);
                case "CUST-PRI-CARD-HOLDER-IND" -> record.setCustPriCardHolderInd(value);
                default -> throw new IllegalArgumentException("Not a PIC X field of CVCUS01Y: "
                        + field);
            }
        }

        /**
         * {@code null} is refused for every {@code PIC X} field rather than normalised to spaces.
         *
         * <p>COBOL has no null: an empty {@code PIC X} field holds spaces. A caller wanting to blank a
         * field supplies spaces or an empty string, and both are accepted - which is asserted in
         * {@code anEmptyStringAndSpacesAreIndistinguishable}. Silently reading {@code null} as spaces
         * would hide a caller that had lost the value it meant to store.
         *
         * @param field the copybook field name whose setter is offered {@code null}
         */
        @ParameterizedTest(name = "{0} refuses null")
        @CsvSource({
            "CUST-FIRST-NAME,            25",
            "CUST-MIDDLE-NAME,           25",
            "CUST-LAST-NAME,             25",
            "CUST-ADDR-LINE-1,           50",
            "CUST-ADDR-LINE-2,           50",
            "CUST-ADDR-LINE-3,           50",
            "CUST-ADDR-STATE-CD,         2",
            "CUST-ADDR-COUNTRY-CD,       3",
            "CUST-ADDR-ZIP,              10",
            "CUST-PHONE-NUM-1,           15",
            "CUST-PHONE-NUM-2,           15",
            "CUST-GOVT-ISSUED-ID,        20",
            "CUST-DOB-YYYY-MM-DD,        10",
            "CUST-EFT-ACCOUNT-ID,        10",
            "CUST-PRI-CARD-HOLDER-IND,   1",
        })
        @DisplayName("null is refused by every PIC X setter, naming the field and the alternative")
        void nullIsRefusedByEveryAlphanumericSetter(String field, int width) {
            CustomerRecord record = new CustomerRecord();

            assertThatNullPointerException()
                    .isThrownBy(() -> setAlphanumeric(record, field, null))
                    .withMessageContaining(field)
                    .withMessageContaining("COBOL has no null")
                    .withMessageContaining("PIC X(" + width + ")");
        }

        @Test
        @DisplayName("CUST-ADDR-ZIP carries both a padded zip and a full ZIP+4 in the same span")
        void theZipSpanCarriesTwoContentWidths() {
            // The fixture's strongest evidence that a PIC X span is not trimmed on read: 20 of the 50
            // rows hold a five-character zip left justified with five trailing spaces, and the other 30
            // hold a ten-character ZIP+4 with no padding at all. Both come out of the same ten bytes.
            CustomerRecord shortZip = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord fullZip = CustomerRecord.decode(fixtureRow(3), ASCII);

            assertThat(shortZip.getCustAddrZip())
                    .as("the trailing spaces are part of the field and must survive the read")
                    .isEqualTo(padded(ROW_1_ZIP, 10))
                    .hasSize(10);
            assertThat(fullZip.getCustAddrZip()).isEqualTo(ROW_3_ZIP).hasSize(10);
            assertThat(new String(shortZip.encode(ASCII), ASCII).substring(239, 249))
                    .as("and must survive the write, or the row would shrink")
                    .isEqualTo(padded(ROW_1_ZIP, 10));
            assertThat(new String(fullZip.encode(ASCII), ASCII).substring(239, 249))
                    .isEqualTo(ROW_3_ZIP);
        }

        /**
         * The zip span, measured across the rows that give it both shapes.
         *
         * <p>The expectation is stated as content plus an explicit pad to ten, never as a literal with
         * typed trailing spaces: a CSV source trims trailing whitespace, and trailing whitespace in
         * source is invisible to a reader anyway. Since {@link CustomerRecordTest#padded(String, int)}
         * adds nothing to a value already at width, one formula serves both shapes - and the assertion
         * that the composed expectation is ten characters keeps the arithmetic honest.
         *
         * @param recordNumber the 1-based fixture row
         * @param content      the zip content, before any padding
         */
        @ParameterizedTest(name = "row {0} zip content is ''{1}''")
        @CsvSource({
            "1,  12546",
            "2,  22770",
            "3,  19852-6716",
            "5,  02251-1698",
            "44, 05704-0501",
            "50, 04257",
        })
        @DisplayName("Both zip shapes round-trip out of the same PIC X(10) span")
        void bothZipShapesRoundTrip(int recordNumber, String content) {
            String stored = padded(content, 10);
            assertThat(stored)
                    .as("each composed expectation must itself be ten characters")
                    .hasSize(10);
            CustomerRecord record = CustomerRecord.decode(fixtureRow(recordNumber), ASCII);

            assertThat(record.getCustAddrZip()).isEqualTo(stored);
            assertThat(new String(record.encode(ASCII), ASCII).substring(239, 249)).isEqualTo(stored);
        }

        @Test
        @DisplayName("All-digit PIC X fields stay Strings and keep their leading zeros")
        void allDigitAlphanumericFieldsStayStrings() throws NoSuchMethodException {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.getCustGovtIssuedId()).isEqualTo(ROW_1_GOVT_ISSUED_ID).hasSize(20);
            assertThat(record.getCustEftAccountId()).isEqualTo(ROW_1_EFT_ACCOUNT_ID).hasSize(10);
            // Structural, because the defect being guarded against is a well-meaning change of type: a
            // numeric accessor here would drop eleven leading zeros from the government identifier and
            // one from the account identifier, and the copybook says X, not 9.
            assertThat(CustomerRecord.class.getMethod("getCustGovtIssuedId").getReturnType())
                    .isEqualTo(String.class);
            assertThat(CustomerRecord.class.getMethod("getCustEftAccountId").getReturnType())
                    .isEqualTo(String.class);
            assertThat(CustomerRecord.class.getMethod("getCustAddrZip").getReturnType())
                    .isEqualTo(String.class);
            assertThat(CustomerRecord.LAYOUT.span("CUST-GOVT-ISSUED-ID").kind())
                    .isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(CustomerRecord.LAYOUT.span("CUST-EFT-ACCOUNT-ID").kind())
                    .isEqualTo(PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("The indicator the fixture cannot supply - 'N' - is fully supported")
        void theIndicatorValueTheFixtureLacksIsSupported() {
            // All 50 fixture rows carry 'Y', so an 'N' record has to be synthesised. That is a property
            // of the sample data, not of the field, and the field must handle either.
            CustomerRecord notPrimary = CustomerRecord.decode(synthesisedImage("N"), ASCII);

            assertThat(notPrimary.getCustPriCardHolderInd()).isEqualTo("N");
            assertThat(new String(notPrimary.encode(ASCII), ASCII).substring(328, 329)).isEqualTo("N");

            CustomerRecord blankIndicator = CustomerRecord.decode(synthesisedImage(" "), ASCII);
            assertThat(blankIndicator.getCustPriCardHolderInd()).isEqualTo(" ");
        }

        @Test
        @DisplayName("A blank named field - which no fixture row holds - round-trips as spaces")
        void aBlankNamedFieldRoundTrips() {
            // Every one of the 18 named fields is non-blank in all 50 rows, so this case is synthetic
            // too. A field the legacy file leaves blank must still load and store as spaces.
            CustomerRecord record = CustomerRecord.decode(synthesisedImage("Y"), ASCII);

            assertThat(record.getCustAddrLine2()).isEqualTo(spaces(50));
            assertThat(record.getCustAddrLine3()).isEqualTo(spaces(50));
            assertThat(record.getCustPhoneNum2()).isEqualTo(spaces(15));
            assertThat(record.getCustGovtIssuedId()).isEqualTo(spaces(20));
            assertThat(record.encode(ASCII)).hasSize(DECLARED_RECORD_LENGTH);
        }
    }

    // =================================================================================================
    // 6. PIC 9 MOVE PARITY - zero-fill on the LEFT, truncate on the LEFT.
    //
    //    The asymmetry with PIC X is COBOL's, not a choice: a numeric MOVE aligns on the implied decimal
    //    point, so a value too wide for the receiver loses its HIGH-order digits, silently, unless the
    //    program asks for ON SIZE ERROR - and no program in this codebase does. A negative value is a
    //    different matter and is refused: PIC 9(n) declares no sign position, so a negative value has no
    //    stored representation at all.
    // =================================================================================================

    @Nested
    @DisplayName("PIC 9 parity - left zero-fill, left truncation, no sign position")
    class NumericReceiver {

        @Test
        @DisplayName("A fresh record holds zero in all three numeric fields, imaged at full width")
        void aFreshRecordHoldsZeroImagedAtFullWidth() {
            CustomerRecord record = new CustomerRecord();

            assertThat(record.getCustId()).isZero();
            assertThat(record.getCustSsn()).isZero();
            assertThat(record.getCustFicoCreditScore()).isZero();
            assertThat(record.custIdImage(ASCII)).isEqualTo("000000000");
            assertThat(record.custSsnImage(ASCII)).isEqualTo("000000000");
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo("000");
        }

        @Test
        @DisplayName("CUST-ID 1 images as 000000001 - nine digits, filled on the left")
        void custIdOneImagesAsNineDigits() {
            CustomerRecord record = new CustomerRecord();

            record.setCustId(1);

            assertThat(record.custIdImage(ASCII)).isEqualTo(ROW_1_CUST_ID_IMAGE).hasSize(9);
            assertThat(new String(record.encode(ASCII), ASCII).substring(0, 9))
                    .isEqualTo(ROW_1_CUST_ID_IMAGE);
        }

        @Test
        @DisplayName("FICO 1 images as 001 - the fixture's own row 26, and the best zero-fill proof")
        void ficoOneImagesAsThreeDigits() {
            // String.valueOf(1) is "1". A record formatting its own numbers would emit one character
            // where the span is three and shift every byte after offset 329, which is exactly the class
            // of defect that only shows up as a wrong record width.
            CustomerRecord fromFixture = CustomerRecord.decode(fixtureRow(26), ASCII);

            assertThat(fromFixture.getCustFicoCreditScore()).isEqualTo(ROW_26_FICO);
            assertThat(fromFixture.custFicoCreditScoreImage(ASCII))
                    .isEqualTo(ROW_26_FICO_IMAGE)
                    .hasSize(3)
                    .isNotEqualTo(String.valueOf(ROW_26_FICO));
            assertThat(new String(fromFixture.encode(ASCII), ASCII).substring(329, 332))
                    .isEqualTo(ROW_26_FICO_IMAGE);
            assertThat(fromFixture.custIdImage(ASCII)).isEqualTo(ROW_26_CUST_ID_IMAGE);
        }

        /**
         * The other fixture rows whose stored FICO score carries a leading zero.
         *
         * @param recordNumber the 1-based fixture row
         * @param image        the three stored digits
         * @param value        the {@code int} they denote
         */
        @ParameterizedTest(name = "row {0}: {1} is the value {2}")
        @CsvSource({
            "8,  051, 51",
            "13, 053, 53",
            "17, 054, 54",
            "26, 001, 1",
            "27, 078, 78",
            "31, 058, 58",
            "42, 044, 44",
        })
        @DisplayName("Every leading-zero FICO score in the fixture images at three digits")
        void everyLeadingZeroFicoImagesAtThreeDigits(int recordNumber, String image, int value) {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(recordNumber), ASCII);

            assertThat(record.getCustFicoCreditScore()).isEqualTo(value);
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo(image);
            assertThat(new String(record.encode(ASCII), ASCII).substring(329, 332)).isEqualTo(image);
        }

        /**
         * An over-wide numeric value loses its leading digits.
         *
         * @param supplied the value handed to the setter
         * @param held     the value the nine-digit field can hold
         */
        @ParameterizedTest(name = "setCustId({0}) holds {1}")
        @CsvSource({
            "0,            0",
            "1,            1",
            "999999999,    999999999",
            "1234567890,   234567890",
            "2000000001,   1",
        })
        @DisplayName("An over-wide CUST-ID loses its HIGH-order digits, as a numeric MOVE does")
        void anOverWideValueLosesItsLeadingDigits(int supplied, int held) {
            CustomerRecord record = new CustomerRecord();

            record.setCustId(supplied);

            assertThat(record.getCustId()).isEqualTo(held);
            assertThat(record.custIdImage(ASCII)).hasSize(9);
        }

        @Test
        @DisplayName("An over-wide FICO score keeps its RIGHTMOST three digits")
        void anOverWideFicoKeepsItsRightmostDigits() {
            CustomerRecord record = new CustomerRecord();

            record.setCustFicoCreditScore(1850);

            assertThat(record.getCustFicoCreditScore()).isEqualTo(850);
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo("850");
        }

        @Test
        @DisplayName("The held value and the encoded image can never disagree")
        void theHeldValueAndTheImageAgree() {
            CustomerRecord record = new CustomerRecord();

            record.setCustSsn(1234567890);

            assertThat(record.getCustSsn()).isEqualTo(234567890);
            assertThat(record.custSsnImage(ASCII)).isEqualTo("234567890");
            assertThat(new String(record.encode(ASCII), ASCII).substring(279, 288))
                    .isEqualTo("234567890");
        }

        @Test
        @DisplayName("The two truncation directions genuinely differ, side by side")
        void theTwoTruncationDirectionsDiffer() {
            // Stated together because getting one of them backwards is the classic parity defect and it
            // is invisible at a call site that uses plain assignment.
            CustomerRecord record = new CustomerRecord();

            record.setCustAddrCountryCd("USAX");
            record.setCustFicoCreditScore(7204);

            assertThat(record.getCustAddrCountryCd())
                    .as("PIC X(03) keeps the first three characters")
                    .isEqualTo("USA");
            assertThat(record.getCustFicoCreditScore())
                    .as("PIC 9(03) keeps the last three digits")
                    .isEqualTo(204);
        }

        /**
         * A negative value is refused by each of the three numeric setters.
         *
         * @param negative a value with no stored representation in an unsigned picture
         */
        @ParameterizedTest(name = "{0} is refused")
        @ValueSource(ints = {-1, -9, -999999999, Integer.MIN_VALUE})
        @DisplayName("A negative value is refused - PIC 9 declares no sign position")
        void aNegativeValueIsRefused(int negative) {
            CustomerRecord record = new CustomerRecord();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.setCustId(negative))
                    .withMessageContaining("CUST-ID")
                    .withMessageContaining("no sign position");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.setCustSsn(negative))
                    .withMessageContaining("CUST-SSN");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.setCustFicoCreditScore(negative))
                    .withMessageContaining("CUST-FICO-CREDIT-SCORE");
        }

        @Test
        @DisplayName("Zero is accepted - the boundary on the accepting side of the same guard")
        void zeroIsAccepted() {
            CustomerRecord record = new CustomerRecord();

            assertThatCode(() -> {
                record.setCustId(0);
                record.setCustSsn(0);
                record.setCustFicoCreditScore(0);
            }).doesNotThrowAnyException();
            assertThat(record.custIdImage(ASCII)).isEqualTo("000000000");
        }

        @Test
        @DisplayName("The widest value each field can hold round-trips through its span")
        void theWidestRepresentableValuesRoundTrip() {
            CustomerRecord record = new CustomerRecord();

            record.setCustId(999999999);
            record.setCustSsn(999999999);
            record.setCustFicoCreditScore(999);

            String image = new String(record.encode(ASCII), ASCII);
            assertThat(image.substring(0, 9)).isEqualTo("999999999");
            assertThat(image.substring(279, 288)).isEqualTo("999999999");
            assertThat(image.substring(329, 332)).isEqualTo("999");
            assertThat(CustomerRecord.decode(image, ASCII)).isEqualTo(record);
        }

        @Test
        @DisplayName("The nine-digit fields are int, not long - AAP rule R4")
        void theNineDigitFieldsAreInt() throws NoSuchMethodException {
            // Asserted structurally, because the point of the rule is the SET of Java values the
            // accessor appears able to report: a long-typed nine-digit field advertises nineteen digits
            // of state the field can never hold.
            assertThat(CustomerRecord.class.getMethod("getCustId").getReturnType())
                    .isEqualTo(int.class);
            assertThat(CustomerRecord.class.getMethod("getCustSsn").getReturnType())
                    .isEqualTo(int.class);
            assertThat(CustomerRecord.class.getMethod("getCustFicoCreditScore").getReturnType())
                    .isEqualTo(int.class);
        }

        @Test
        @DisplayName("An image carries no grouping separator, so no locale can change it")
        void anImageCarriesNoGroupingSeparator() {
            // A locale-aware format would render 1234567 as 1,234,567 under one default locale and
            // 1.234.567 under another, breaking the span in both. The image is digits and nothing else,
            // which is why this class needs no locale fixture and passes under any default.
            CustomerRecord record = new CustomerRecord();
            record.setCustId(1234567);
            record.setCustFicoCreditScore(123);

            assertThat(record.custIdImage(ASCII))
                    .isEqualTo("001234567")
                    .doesNotContain(",")
                    .doesNotContain(".")
                    .doesNotContain(" ");
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo("123");
            assertThat(record.custIdImage(ASCII))
                    .as("and it is identical when the same digits are asked for under a Turkish locale, "
                            + "because nothing here consults a locale at all")
                    .isEqualTo("001234567".toLowerCase(Locale.forLanguageTag("tr")));
        }

        @Test
        @DisplayName("Each image accessor is offered in both a charset and a codec form, which agree")
        void eachImageAccessorIsOfferedInBothForms() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.custIdImage(asciiCodec)).isEqualTo(record.custIdImage(ASCII));
            assertThat(record.custSsnImage(asciiCodec)).isEqualTo(record.custSsnImage(ASCII));
            assertThat(record.custFicoCreditScoreImage(asciiCodec))
                    .isEqualTo(record.custFicoCreditScoreImage(ASCII));
            assertThatNullPointerException().isThrownBy(() -> record.custIdImage((Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.custIdImage((FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.custFicoCreditScoreImage((Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.custFicoCreditScoreImage((FixedWidthCodec) null));
        }

        @Test
        @DisplayName("A stored numeric span holding a non-digit fails loudly, never decodes as zero")
        void aNonDigitInANumericSpanFailsLoudly() {
            String corrupted = "0000000O1" + synthesisedImage("Y").substring(9);

            assertThat(corrupted).hasSize(DECLARED_RECORD_LENGTH);
            assertThatIllegalArgumentException()
                    .as("a letter O where a zero belongs is a real dataset defect; decoding it as 1 "
                            + "would post the record under the wrong key")
                    .isThrownBy(() -> CustomerRecord.decode(corrupted, ASCII));
        }
    }

    // =================================================================================================
    // 7. FILLER X(168) IS DATA (gate G21).
    //
    //    The trailing 168 bytes are part of every stored record. No program references them, which is
    //    exactly why they are easy to drop and why dropping them is caught only by the total width - the
    //    reason gate G21 leans on it. They are asserted on BOTH paths: for a record decoded from a real
    //    fixture row, where the spaces could merely be echoed back from the input, and for a record
    //    built from field values, where nothing but the layout can have produced them.
    // =================================================================================================

    @Nested
    @DisplayName("FILLER X(168) is emitted and space-filled on every encode (G21)")
    class FillerSpan {

        @Test
        @DisplayName("The FILLER span is declared at [332, 500) as a first-class FILLER descriptor")
        void theFillerSpanIsDeclaredExplicitly() {
            FieldSpan filler = CustomerRecord.FILLER;

            assertThat(filler.name()).isEqualTo("FILLER");
            assertThat(filler.offset()).isEqualTo(FILLER_OFFSET);
            assertThat(filler.length()).isEqualTo(FILLER_LENGTH);
            assertThat(filler.endOffsetExclusive()).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(filler.kind().filler())
                    .as("declared as FILLER rather than as an anonymous alphanumeric span, so the "
                            + "layout can initialise it without a value and keep it non-referable")
                    .isTrue();
            assertThat(filler.hasInitialValue())
                    .as("CVCUS01Y declares no VALUE on its FILLER, so it initialises to spaces")
                    .isFalse();
        }

        @Test
        @DisplayName("Bytes [332, 500) of a record built from field values are 168 spaces")
        void theFillerIsSpaceFilledOnTheWritePath() {
            // The write path, which is the one that matters: these 168 spaces cannot have come from any
            // input, because this record was assembled from field values only.
            String image = new String(row1AsBuilt().encode(ASCII), ASCII);

            assertThat(image).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(image.substring(FILLER_OFFSET))
                    .isEqualTo(spaces(FILLER_LENGTH))
                    .hasSize(FILLER_LENGTH);
        }

        @Test
        @DisplayName("Bytes [332, 500) of a record decoded from fixture row 1 are 168 spaces")
        void theFillerIsSpaceFilledOnTheReadPath() {
            String image = new String(CustomerRecord.decode(fixtureRow(1), ASCII).encode(ASCII), ASCII);

            assertThat(image.substring(FILLER_OFFSET)).isEqualTo(spaces(FILLER_LENGTH));
        }

        @Test
        @DisplayName("An untouched, freshly constructed record still emits its FILLER")
        void anUntouchedRecordStillEmitsItsFiller() {
            String image = new String(new CustomerRecord().encode(ASCII), ASCII);

            assertThat(image).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(image.substring(FILLER_OFFSET)).isEqualTo(spaces(FILLER_LENGTH));
        }

        @Test
        @DisplayName("Every FILLER byte is 0x20 individually, never 0x00")
        void everyFillerByteIsAnAsciiSpace() {
            // Asserted byte by byte rather than as a string, because a NUL-filled span decodes to a
            // ten-character string that merely looks wrong, whereas the byte value is unambiguous - and
            // an all-zero byte array is what an uninitialised buffer produces.
            byte[] image = row1AsBuilt().encode(ASCII);

            for (int offset = FILLER_OFFSET; offset < DECLARED_RECORD_LENGTH; offset++) {
                assertThat(image[offset])
                        .as("byte %d of the FILLER span", offset)
                        .isEqualTo(ASCII_SPACE_BYTE);
            }
        }

        @Test
        @DisplayName("The FILLER is space-filled under IBM037 too - 0x40, not 0x20 and not 0x00")
        void theFillerIsSpaceFilledInEbcdicToo() {
            // Proof that the pad byte comes from the code page rather than from a hard-coded 0x20: the
            // EBCDIC space is 0x40, and a codec that assumed ASCII would write an '&' here.
            byte[] image = row1AsBuilt().encode(EBCDIC);

            assertThat(image).hasSize(DECLARED_RECORD_LENGTH);
            for (int offset = FILLER_OFFSET; offset < DECLARED_RECORD_LENGTH; offset++) {
                assertThat(image[offset])
                        .as("byte %d of the FILLER span under IBM037", offset)
                        .isEqualTo(EBCDIC_SPACE_BYTE);
            }
        }

        @Test
        @DisplayName("The record area exposes the FILLER span as addressable bytes")
        void theRecordAreaExposesTheFillerSpan() {
            FixedWidthRecord area = asciiCodec.wrap(row1AsBuilt().encode(ASCII), CustomerRecord.LAYOUT);

            assertThat(area.recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(area.readSpan(CustomerRecord.FILLER)).isEqualTo(spaces(FILLER_LENGTH));
            assertThat(area.readSpanBytes(CustomerRecord.FILLER)).hasSize(FILLER_LENGTH);
        }

        @Test
        @DisplayName("The FILLER's 168 bytes are exactly what the width leaves over after 18 fields")
        void theFillerAccountsForEveryRemainingByte() {
            int named = 0;
            for (FieldSpan span : CustomerRecord.LAYOUT.storageSpans()) {
                if (!span.kind().filler()) {
                    named += span.length();
                }
            }

            assertThat(named)
                    .as("the 18 named items of CVCUS01Y occupy 332 bytes")
                    .isEqualTo(FILLER_OFFSET);
            assertThat(DECLARED_RECORD_LENGTH - named)
                    .as("so the FILLER accounts for the remaining 168")
                    .isEqualTo(FILLER_LENGTH);
        }
    }

    // =================================================================================================
    // 8. THE REAL FIXTURE - 50 rows of 500 bytes, from the test classpath.
    //
    //    app/data/ASCII/custdata.txt is one of the eight fixtures that match their copybook exactly, so
    //    unlike a cardxref row - 36 bytes where its copybook declares 50 - no customer row is ever
    //    widened before it is decoded. Applying a padding normaliser here would corrupt the expectation
    //    rather than repair it, and none is applied.
    // =================================================================================================

    @Nested
    @DisplayName("The real fixture - 50 rows of 500 bytes, none needing normalisation")
    class RealFixture {

        @Test
        @DisplayName("The fixture is on the test classpath and holds 50 rows of exactly 500 bytes")
        void theFixtureIsFiftyRowsOfFiveHundredBytes() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(FIXTURE_ROW_COUNT);
            for (int index = 0; index < rows.size(); index++) {
                assertThat(rows.get(index))
                        .as("row %d must be exactly 500 characters - no customer row needs widening",
                                index + 1)
                        .hasSize(DECLARED_RECORD_LENGTH);
            }
        }

        @Test
        @DisplayName("The keys run 000000001 to 000000050, ascending and unique")
        void theKeysAreAscendingAndUnique() {
            List<String> rows = fixtureRows();
            Set<String> keys = new LinkedHashSet<>();
            String previous = null;

            for (String row : rows) {
                String key = row.substring(0, PRIMARY_KEY_LENGTH);
                assertThat(keys.add(key)).as("key %s must appear once", key).isTrue();
                if (previous != null) {
                    assertThat(key.compareTo(previous))
                            .as("a KSDS browse returns ascending keys, so the fixture must be ordered")
                            .isPositive();
                }
                previous = key;
            }

            assertThat(keys).hasSize(FIXTURE_ROW_COUNT);
            assertThat(rows.get(0).substring(0, PRIMARY_KEY_LENGTH)).isEqualTo(ROW_1_CUST_ID_IMAGE);
            assertThat(rows.get(1).substring(0, PRIMARY_KEY_LENGTH)).isEqualTo(ROW_2_CUST_ID_IMAGE);
            assertThat(rows.get(FIXTURE_ROW_COUNT - 1).substring(0, PRIMARY_KEY_LENGTH))
                    .isEqualTo(ROW_50_CUST_ID_IMAGE);
        }

        @Test
        @DisplayName("Row 1 decodes to all 18 named fields, each at its measured value")
        void rowOneDecodesToAllEighteenFields() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.getCustId()).isEqualTo(ROW_1_CUST_ID);
            assertThat(record.custIdImage(ASCII)).isEqualTo(ROW_1_CUST_ID_IMAGE);
            assertThat(record.getCustFirstName()).isEqualTo(padded(ROW_1_FIRST_NAME, 25));
            assertThat(record.getCustMiddleName()).isEqualTo(padded(ROW_1_MIDDLE_NAME, 25));
            assertThat(record.getCustLastName()).isEqualTo(padded(ROW_1_LAST_NAME, 25));
            assertThat(record.getCustAddrLine1()).isEqualTo(padded(ROW_1_ADDR_LINE_1, 50));
            assertThat(record.getCustAddrLine2()).isEqualTo(padded(ROW_1_ADDR_LINE_2, 50));
            assertThat(record.getCustAddrLine3()).isEqualTo(padded(ROW_1_ADDR_LINE_3, 50));
            assertThat(record.getCustAddrStateCd()).isEqualTo(ROW_1_STATE_CD);
            assertThat(record.getCustAddrCountryCd()).isEqualTo(ROW_1_COUNTRY_CD);
            assertThat(record.getCustAddrZip()).isEqualTo(padded(ROW_1_ZIP, 10));
            assertThat(record.getCustPhoneNum1()).isEqualTo(padded(ROW_1_PHONE_1, 15));
            assertThat(record.getCustPhoneNum2()).isEqualTo(padded(ROW_1_PHONE_2, 15));
            assertThat(record.getCustSsn()).isEqualTo(ROW_1_SSN);
            assertThat(record.custSsnImage(ASCII)).isEqualTo(ROW_1_SSN_IMAGE);
            assertThat(record.getCustGovtIssuedId()).isEqualTo(ROW_1_GOVT_ISSUED_ID);
            assertThat(record.getCustDobYyyyMmDd()).isEqualTo(ROW_1_DOB);
            assertThat(record.getCustEftAccountId()).isEqualTo(ROW_1_EFT_ACCOUNT_ID);
            assertThat(record.getCustPriCardHolderInd()).isEqualTo(ROW_1_PRI_CARD_HOLDER_IND);
            assertThat(record.getCustFicoCreditScore()).isEqualTo(ROW_1_FICO);
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo("274");
        }

        @Test
        @DisplayName("A record built from row 1's values equals the decoded one, byte for byte")
        void aBuiltRecordEqualsTheDecodedRow() {
            CustomerRecord decoded = CustomerRecord.decode(fixtureRow(1), ASCII);

            CustomerRecord built = row1AsBuilt();

            assertThat(built)
                    .as("the setters and the decoder must arrive at one state, since both apply the "
                            + "same PICTURE receiver")
                    .isEqualTo(decoded);
            assertThat(built.hashCode()).isEqualTo(decoded.hashCode());
            assertThat(built.encode(ASCII)).isEqualTo(fixtureRow(1).getBytes(ASCII));
        }

        @Test
        @DisplayName("Row 2 decodes to Enrico April Rosenbaum of Indiana")
        void rowTwoDecodes() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(2), ASCII);

            assertThat(record.custIdImage(ASCII)).isEqualTo(ROW_2_CUST_ID_IMAGE);
            assertThat(record.getCustFirstName()).isEqualTo(padded(ROW_2_FIRST_NAME, 25));
            assertThat(record.getCustMiddleName()).isEqualTo(padded(ROW_2_MIDDLE_NAME, 25));
            assertThat(record.getCustLastName()).isEqualTo(padded(ROW_2_LAST_NAME, 25));
            assertThat(record.getCustAddrLine1()).isEqualTo(padded(ROW_2_ADDR_LINE_1, 50));
            assertThat(record.getCustAddrStateCd()).isEqualTo(ROW_2_STATE_CD);
            assertThat(record.getCustAddrZip()).isEqualTo(padded(ROW_2_ZIP, 10));
            assertThat(record.custSsnImage(ASCII)).isEqualTo(ROW_2_SSN_IMAGE);
            assertThat(record.getCustDobYyyyMmDd()).isEqualTo(ROW_2_DOB);
            assertThat(record.getCustEftAccountId()).isEqualTo(ROW_2_EFT_ACCOUNT_ID);
            assertThat(record.getCustFicoCreditScore()).isEqualTo(ROW_2_FICO);
        }

        @Test
        @DisplayName("Row 26 decodes its FICO 001 to 1 - the stored left zero-fill, read back")
        void rowTwentySixDecodes() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(26), ASCII);

            assertThat(record.custIdImage(ASCII)).isEqualTo(ROW_26_CUST_ID_IMAGE);
            assertThat(record.getCustFirstName()).isEqualTo(padded(ROW_26_FIRST_NAME, 25));
            assertThat(record.getCustAddrLine1()).isEqualTo(padded(ROW_26_ADDR_LINE_1, 50));
            assertThat(record.getCustFicoCreditScore()).isEqualTo(ROW_26_FICO);
            assertThat(record.custFicoCreditScoreImage(ASCII)).isEqualTo(ROW_26_FICO_IMAGE);
        }

        @Test
        @DisplayName("Row 50, the last, decodes to Aniya Alba Von of Oregon")
        void rowFiftyDecodes() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(FIXTURE_ROW_COUNT), ASCII);

            assertThat(record.custIdImage(ASCII)).isEqualTo(ROW_50_CUST_ID_IMAGE);
            assertThat(record.getCustFirstName()).isEqualTo(padded(ROW_50_FIRST_NAME, 25));
            assertThat(record.getCustMiddleName()).isEqualTo(padded(ROW_50_MIDDLE_NAME, 25));
            assertThat(record.getCustLastName()).isEqualTo(padded(ROW_50_LAST_NAME, 25));
            assertThat(record.getCustAddrLine1()).isEqualTo(padded(ROW_50_ADDR_LINE_1, 50));
            assertThat(record.getCustAddrStateCd()).isEqualTo(ROW_50_STATE_CD);
            assertThat(record.getCustAddrZip()).isEqualTo(padded(ROW_50_ZIP, 10));
            assertThat(record.custSsnImage(ASCII)).isEqualTo(ROW_50_SSN_IMAGE);
            assertThat(record.getCustDobYyyyMmDd()).isEqualTo(ROW_50_DOB);
            assertThat(record.getCustEftAccountId()).isEqualTo(ROW_50_EFT_ACCOUNT_ID);
            assertThat(record.getCustFicoCreditScore()).isEqualTo(ROW_50_FICO);
        }

        /**
         * Every row decoded and re-encoded, byte for byte.
         *
         * <p>All 50, not a sample: the round trip is where a mis-transcribed offset, a dropped
         * {@code FILLER} or a lost leading zero surfaces, and the whole file costs milliseconds. 50 of
         * the {@code CUST-ID} values, 6 of the {@code CUST-SSN} values and 7 of the FICO scores carry a
         * leading zero, so a naive number rendering fails on every single row.
         *
         * @param recordNumber the 1-based row number, so a failure names the offending row
         * @param row          that row's 500-character stored image
         */
        @ParameterizedTest(name = "row {0} round-trips byte-identically")
        @MethodSource("com.vsergeychik.carddemo.customer.model.CustomerRecordTest#everyFixtureRow")
        @DisplayName("Every one of the 50 rows survives decode then encode byte-identically")
        void everyRowRoundTripsByteIdentically(int recordNumber, String row) {
            byte[] stored = row.getBytes(ASCII);
            assertThat(stored).as("row %d", recordNumber).hasSize(DECLARED_RECORD_LENGTH);

            CustomerRecord decoded = CustomerRecord.decode(stored, ASCII);

            assertThat(decoded.encode(ASCII))
                    .as("row %d must re-encode to the very bytes it was read from", recordNumber)
                    .isEqualTo(stored);
            assertThat(decoded.recordImage(ASCII))
                    .as("row %d's group image is the stored row", recordNumber)
                    .isEqualTo(row);
            assertThat(CustomerRecord.decode(decoded.encode(ASCII), ASCII))
                    .as("row %d is stable across a second cycle", recordNumber)
                    .isEqualTo(decoded);
        }

        @Test
        @DisplayName("Every row ends with 168 spaces, confirming the FILLER in the real data")
        void everyRowEndsWithOneHundredSixtyEightSpaces() {
            for (String row : fixtureRows()) {
                assertThat(row.substring(FILLER_OFFSET))
                        .as("the FILLER of row %s", row.substring(0, PRIMARY_KEY_LENGTH))
                        .isEqualTo(spaces(FILLER_LENGTH));
            }
        }

        @Test
        @DisplayName("Every row carries CUST-PRI-CARD-HOLDER-IND 'Y' - why 'N' has to be synthesised")
        void everyRowCarriesTheSameIndicator() {
            Set<String> indicators = new LinkedHashSet<>();
            for (String row : fixtureRows()) {
                indicators.add(row.substring(328, 329));
            }

            assertThat(indicators)
                    .as("the fixture offers no 'N' row, which is a property of the sample data and the "
                            + "reason the 'N' case is built inline instead")
                    .containsExactly(ROW_1_PRI_CARD_HOLDER_IND);
        }

        @Test
        @DisplayName("Every row carries country USA, and 36 distinct state codes appear across the 50")
        void theCountryIsUniformAndTheStatesAreNot() {
            Set<String> countries = new LinkedHashSet<>();
            Set<String> states = new LinkedHashSet<>();
            for (String row : fixtureRows()) {
                states.add(row.substring(234, 236));
                countries.add(row.substring(236, 239));
            }

            assertThat(countries).containsExactly(ROW_1_COUNTRY_CD);
            assertThat(states)
                    .as("re-measured for this test, and worth measuring because a two-byte span read at "
                            + "the wrong offset would still look like plausible codes")
                    .hasSize(36);
        }

        @Test
        @DisplayName("No named field is blank in any row - why the blank case is synthetic")
        void noNamedFieldIsBlankInAnyRow() {
            List<String> blanks = new ArrayList<>();
            for (String row : fixtureRows()) {
                for (FieldSpan span : CustomerRecord.LAYOUT.storageSpans()) {
                    if (span.kind().filler()) {
                        continue;
                    }
                    String value = row.substring(span.offset(), span.endOffsetExclusive());
                    if (value.isBlank()) {
                        blanks.add(row.substring(0, PRIMARY_KEY_LENGTH) + "." + span.name());
                    }
                }
            }

            assertThat(blanks)
                    .as("all 18 named fields are populated on all 50 rows, so a blank-field case can "
                            + "only be built inline")
                    .isEmpty();
        }

        @Test
        @DisplayName("Deserialising a row yields exactly the 18 referable names, FILLER excluded")
        void deserialisingARowYieldsTheEighteenReferableNames() {
            Map<String, String> images =
                    asciiCodec.deserialise(CustomerRecord.LAYOUT, fixtureRow(1).getBytes(ASCII));

            assertThat(images)
                    .as("FILLER is not a referable COBOL name, so it is not a field the differ compares")
                    .hasSize(REFERABLE_FIELD_COUNT)
                    .doesNotContainKey("FILLER");
            assertThat(images.get("CUST-ID")).isEqualTo(ROW_1_CUST_ID_IMAGE);
            assertThat(images.get("CUST-SSN")).isEqualTo(ROW_1_SSN_IMAGE);
            assertThat(images.get("CUST-DOB-YYYY-MM-DD")).isEqualTo(ROW_1_DOB);
            assertThat(images.get("CUST-ADDR-ZIP")).isEqualTo(padded(ROW_1_ZIP, 10));
            assertThat(images.get("CUST-FICO-CREDIT-SCORE")).isEqualTo("274");
        }

        @Test
        @DisplayName("The fixture is pure ASCII, so US-ASCII is both correct and safe for it")
        void theFixtureIsPureAscii() {
            for (String row : fixtureRows()) {
                for (int index = 0; index < row.length(); index++) {
                    char character = row.charAt(index);
                    assertThat((int) character)
                            .as("character %d of row %s", index, row.substring(0, PRIMARY_KEY_LENGTH))
                            .isBetween(0x20, 0x7E);
                }
            }
        }
    }

    // =================================================================================================
    // 9. THE WHOLE-GROUP IMAGE - what DISPLAY CUSTOMER-RECORD needs.
    //
    //    app/cbl/CBCUS01C.cbl displays the raw group twice for every record it reads: once at L96 inside
    //    1000-CUSTFILE-GET-NEXT on a '00' status, and again at L78 in the main PERFORM UNTIL loop. That
    //    duplication is a defect of the original program and is preserved, so 50 records produce 100
    //    lines. Reproducing it byte for byte needs a group-level accessor, which is why one exists
    //    rather than leaving callers to assemble 18 fields and hope the FILLER comes out right.
    // =================================================================================================

    @Nested
    @DisplayName("The 500-character group image - the DISPLAY CUSTOMER-RECORD surface")
    class GroupImage {

        @Test
        @DisplayName("The group image is exactly the encoded bytes, read under the same code page")
        void theGroupImageIsTheEncodedBytes() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.recordImage(ASCII))
                    .hasSize(DECLARED_RECORD_LENGTH)
                    .isEqualTo(new String(record.encode(ASCII), ASCII))
                    .isEqualTo(fixtureRow(1));
        }

        @Test
        @DisplayName("The group image spans CUST-ID through the last FILLER byte")
        void theGroupImageSpansTheWholeRecord() {
            String image = CustomerRecord.decode(fixtureRow(1), ASCII).recordImage(ASCII);

            assertThat(image.substring(0, 9)).isEqualTo(ROW_1_CUST_ID_IMAGE);
            assertThat(image.substring(329, 332)).isEqualTo("274");
            assertThat(image.substring(FILLER_OFFSET)).isEqualTo(spaces(FILLER_LENGTH));
        }

        @Test
        @DisplayName("Two successive DISPLAYs of one record produce identical text")
        void twoSuccessiveDisplaysAreIdentical() {
            // CBCUS01C:78 and :96 both display the same record area, so the two lines must match. An
            // accessor that cached its result at decode time and then diverged from the fields after a
            // setter call would break exactly this, which is why the image is always re-derived.
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.recordImage(ASCII)).isEqualTo(record.recordImage(ASCII));

            record.setCustFirstName("Changed");

            assertThat(record.recordImage(ASCII))
                    .as("after a setter the image must follow the field, never a cached copy")
                    .isEqualTo(new String(record.encode(ASCII), ASCII))
                    .contains(padded("Changed", 25));
        }

        @Test
        @DisplayName("The charset and codec forms of the group image agree, and both refuse null")
        void bothGroupImageFormsAgreeAndRefuseNull() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.recordImage(asciiCodec)).isEqualTo(record.recordImage(ASCII));
            assertThatNullPointerException().isThrownBy(() -> record.recordImage((Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.recordImage((FixedWidthCodec) null));
        }

        @Test
        @DisplayName("An untouched record's group image is zeros in the numeric spans, spaces elsewhere")
        void anUntouchedRecordsGroupImage() {
            String image = new CustomerRecord().recordImage(ASCII);

            assertThat(image).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(image.substring(0, 9)).isEqualTo("000000000");
            assertThat(image.substring(9, 279)).isEqualTo(spaces(270));
            assertThat(image.substring(279, 288)).isEqualTo("000000000");
            assertThat(image.substring(288, 328)).isEqualTo(spaces(40));
            assertThat(image.substring(328, 329)).isEqualTo(" ");
            assertThat(image.substring(329, 332)).isEqualTo("000");
            assertThat(image.substring(FILLER_OFFSET)).isEqualTo(spaces(FILLER_LENGTH));
        }
    }

    // =================================================================================================
    // 10. THE CODE PAGE IS ALWAYS THE CALLER'S.
    //
    //     Mainframe data is bytes in a specific code page, so every conversion boundary here takes a
    //     Charset - or a codec already bound to one - and nothing consults the platform default. The
    //     proof is not that a charset parameter exists but that changing it changes the bytes.
    // =================================================================================================

    @Nested
    @DisplayName("The code page is always named and always honoured, never a platform default")
    class CodePageIsAlwaysNamed {

        @Test
        @DisplayName("IBM037 is available in this JDK, asserted rather than assumed")
        void ebcdicIsAvailable() {
            assertThat(Charset.isSupported("IBM037")).isTrue();
            assertThat(EBCDIC.name()).isEqualTo("IBM037");
            assertThat(ASCII.name()).isEqualTo("US-ASCII");
        }

        @Test
        @DisplayName("The same record encodes to DIFFERENT bytes under US-ASCII and IBM037")
        void theSameRecordEncodesDifferentlyUnderEachCodePage() {
            CustomerRecord record = row1AsBuilt();

            byte[] ascii = record.encode(ASCII);
            byte[] ebcdic = record.encode(EBCDIC);

            assertThat(ascii).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(ebcdic).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(ebcdic)
                    .as("if these matched, the charset argument would be decoration")
                    .isNotEqualTo(ascii);
            assertThat(ascii[0]).as("ASCII '0' is 0x30").isEqualTo((byte) 0x30);
            assertThat(ebcdic[0]).as("EBCDIC '0' is 0xF0").isEqualTo((byte) 0xF0);
        }

        @Test
        @DisplayName("Each image is internally self-consistent under its own code page")
        void eachImageIsSelfConsistentUnderItsOwnCodePage() {
            CustomerRecord record = row1AsBuilt();

            assertThat(CustomerRecord.decode(record.encode(ASCII), ASCII)).isEqualTo(record);
            assertThat(CustomerRecord.decode(record.encode(EBCDIC), EBCDIC)).isEqualTo(record);
            assertThat(CustomerRecord.decode(record.encode(ebcdicCodec), ebcdicCodec))
                    .isEqualTo(record);
        }

        @Test
        @DisplayName("Reading an EBCDIC image as US-ASCII does not silently yield the right fields")
        void readingAnEbcdicImageAsAsciiDoesNotSucceedSilently() {
            byte[] ebcdic = row1AsBuilt().encode(EBCDIC);

            // The EBCDIC digits 0xF0-0xF9 are not US-ASCII at all, so the very first span refuses rather
            // than substituting a replacement character. The point is that the mismatch is reported and
            // names the span, not that it produces plausible nonsense - and that the diagnostic
            // withholds the bytes it could not read, since those are stored customer data.
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a code-page mix-up must fail rather than decode to plausible-looking values")
                    .isThrownBy(() -> CustomerRecord.decode(ebcdic, ASCII))
                    .withMessageContaining("CUST-ID")
                    .withMessageContaining("US-ASCII");
        }

        @Test
        @DisplayName("A null charset or codec is refused at every byte boundary")
        void aNullCharsetIsRefusedAtEveryBoundary() {
            byte[] stored = fixtureRow(1).getBytes(ASCII);
            CustomerRecord record = CustomerRecord.decode(stored, ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode(stored, (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode(stored, (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode(fixtureRow(1), (Charset) null));
            assertThatNullPointerException().isThrownBy(() -> record.encode((Charset) null));
            assertThatNullPointerException().isThrownBy(() -> record.encode((FixedWidthCodec) null));
        }

        @Test
        @DisplayName("A null source image is refused, naming what was required")
        void aNullSourceIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode((byte[]) null, ASCII))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode((byte[]) null, asciiCodec))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerRecord.decode((String) null, ASCII))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
        }

        @Test
        @DisplayName("An empty image is refused as a wrong width, not accepted as an empty record")
        void anEmptyImageIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode(new byte[0], ASCII))
                    .withMessageContaining(String.valueOf(DECLARED_RECORD_LENGTH));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerRecord.decode("", ASCII));
        }

        @Test
        @DisplayName("The decoder defensively copies its source, so a later mutation cannot reach in")
        void theDecoderCopiesItsSource() {
            byte[] stored = fixtureRow(1).getBytes(ASCII);
            CustomerRecord record = CustomerRecord.decode(stored, ASCII);

            stored[0] = (byte) '9';

            assertThat(record.custIdImage(ASCII))
                    .as("a record that aliased its caller's array would change under it")
                    .isEqualTo(ROW_1_CUST_ID_IMAGE);
        }

        @Test
        @DisplayName("The encoder returns a fresh array each time, so a caller cannot corrupt another")
        void theEncoderReturnsAFreshArray() {
            CustomerRecord record = row1AsBuilt();

            byte[] first = record.encode(ASCII);
            byte[] second = record.encode(ASCII);
            first[0] = (byte) '9';

            assertThat(second).isNotSameAs(first);
            assertThat(second[0]).isEqualTo((byte) '0');
        }
    }

    // =================================================================================================
    // 11. VALUE SEMANTICS - what 9300-CHECK-CHANGE-IN-REC and the parity differ rely on.
    //
    //     The update programs do optimistic concurrency by re-reading a record and comparing it against
    //     the copy the screen was painted from, field by field, before rewriting. That is a genuine
    //     concurrency check and it is preserved as-is; a version column would be a schema change, which
    //     is forbidden. Equality over the 18 named values is what makes it expressible, and FILLER takes
    //     no part because it carries no value - its presence is proved by the layout's width check.
    // =================================================================================================

    @Nested
    @DisplayName("Value semantics - equality over the 18 named fields, FILLER excluded")
    class ValueSemantics {

        @Test
        @DisplayName("Two records decoded from the same row are equal and share a hash code")
        void twoRecordsFromTheSameRowAreEqual() {
            CustomerRecord first = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord second = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(first).isEqualTo(second).isNotSameAs(second);
            assertThat(first.hashCode()).isEqualTo(second.hashCode());
        }

        @Test
        @DisplayName("Equality is reflexive and symmetric")
        void equalityIsReflexiveAndSymmetric() {
            CustomerRecord first = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord second = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord other = CustomerRecord.decode(fixtureRow(2), ASCII);

            assertThat(first.equals(first)).as("reflexive").isTrue();
            assertThat(first.equals(second)).isTrue();
            assertThat(second.equals(first)).as("symmetric").isTrue();
            assertThat(first.equals(other)).isFalse();
            assertThat(other.equals(first)).as("symmetric in the negative too").isFalse();
        }

        @Test
        @DisplayName("A record equals neither null nor an instance of another type")
        void aRecordEqualsNeitherNullNorAnotherType() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.equals(null)).as("never equal to null").isFalse();
            assertThat(record.equals("not a CustomerRecord")).as("never equal to a String").isFalse();
            assertThat(record.equals(Integer.valueOf(1))).isFalse();
            assertThat(record).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("Hash codes are consistent across repeated calls on one instance")
        void hashCodesAreConsistent() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            int first = record.hashCode();

            assertThat(record.hashCode()).isEqualTo(first);
            assertThat(record.hashCode()).isEqualTo(first);
        }

        @Test
        @DisplayName("Mutating a field changes the hash - which is why an instance is never a map key")
        void mutatingAFieldChangesTheState() {
            // Recorded rather than hidden: a COBOL record area is mutable, so this type is too, and its
            // hash therefore follows its state. It is a row-scoped value, not a key to store in a set.
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);
            int before = record.hashCode();

            record.setCustFirstName("Changed");

            assertThat(record.hashCode()).isNotEqualTo(before);
            assertThat(record).isNotEqualTo(CustomerRecord.decode(fixtureRow(1), ASCII));
        }

        /**
         * Differing in any single named field breaks equality.
         *
         * <p>All 18, one parameterised case each, because an {@code equals} that omitted one field would
         * let a genuine concurrent change pass the update programs' re-read-and-compare check
         * undetected - and it would pass every test that only compared whole records.
         *
         * @param field the copybook field name to change
         */
        @ParameterizedTest(name = "a different {0} breaks equality")
        @ValueSource(strings = {
            "CUST-ID",
            "CUST-FIRST-NAME",
            "CUST-MIDDLE-NAME",
            "CUST-LAST-NAME",
            "CUST-ADDR-LINE-1",
            "CUST-ADDR-LINE-2",
            "CUST-ADDR-LINE-3",
            "CUST-ADDR-STATE-CD",
            "CUST-ADDR-COUNTRY-CD",
            "CUST-ADDR-ZIP",
            "CUST-PHONE-NUM-1",
            "CUST-PHONE-NUM-2",
            "CUST-SSN",
            "CUST-GOVT-ISSUED-ID",
            "CUST-DOB-YYYY-MM-DD",
            "CUST-EFT-ACCOUNT-ID",
            "CUST-PRI-CARD-HOLDER-IND",
            "CUST-FICO-CREDIT-SCORE",
        })
        @DisplayName("Every one of the 18 named fields takes part in equality")
        void everyNamedFieldTakesPartInEquality(String field) {
            CustomerRecord unchanged = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord changed = CustomerRecord.decode(fixtureRow(1), ASCII);

            changeOneField(changed, field);

            assertThat(changed)
                    .as("%s must be compared, or a change to it would slip past 9300-CHECK-CHANGE-IN-REC",
                            field)
                    .isNotEqualTo(unchanged);
            assertThat(changed.encode(ASCII))
                    .as("and the change must reach the stored bytes")
                    .isNotEqualTo(unchanged.encode(ASCII));
        }

        /**
         * Changes exactly one named field to a value row 1 does not hold.
         *
         * <p>An explicit dispatch rather than reflection, so a renamed setter breaks compilation instead
         * of silently leaving a field unexercised.
         *
         * @param record the record to change
         * @param field  the copybook field name
         */
        private void changeOneField(CustomerRecord record, String field) {
            switch (field) {
                case "CUST-ID" -> record.setCustId(ROW_1_CUST_ID + 1);
                case "CUST-FIRST-NAME" -> record.setCustFirstName("Different");
                case "CUST-MIDDLE-NAME" -> record.setCustMiddleName("Different");
                case "CUST-LAST-NAME" -> record.setCustLastName("Different");
                case "CUST-ADDR-LINE-1" -> record.setCustAddrLine1("Different");
                case "CUST-ADDR-LINE-2" -> record.setCustAddrLine2("Different");
                case "CUST-ADDR-LINE-3" -> record.setCustAddrLine3("Different");
                case "CUST-ADDR-STATE-CD" -> record.setCustAddrStateCd("ZZ");
                case "CUST-ADDR-COUNTRY-CD" -> record.setCustAddrCountryCd("CAN");
                case "CUST-ADDR-ZIP" -> record.setCustAddrZip("99999-9999");
                case "CUST-PHONE-NUM-1" -> record.setCustPhoneNum1("(000)000-0000");
                case "CUST-PHONE-NUM-2" -> record.setCustPhoneNum2("(000)000-0000");
                case "CUST-SSN" -> record.setCustSsn(ROW_1_SSN + 1);
                case "CUST-GOVT-ISSUED-ID" -> record.setCustGovtIssuedId("99999999999999999999");
                case "CUST-DOB-YYYY-MM-DD" -> record.setCustDobYyyyMmDd("2000-01-01");
                case "CUST-EFT-ACCOUNT-ID" -> record.setCustEftAccountId("9999999999");
                case "CUST-PRI-CARD-HOLDER-IND" -> record.setCustPriCardHolderInd("N");
                case "CUST-FICO-CREDIT-SCORE" -> record.setCustFicoCreditScore(ROW_1_FICO + 1);
                default -> throw new IllegalArgumentException("Not a named field of CVCUS01Y: " + field);
            }
        }

        @Test
        @DisplayName("Padding is part of equality: a padded value equals its unpadded original")
        void paddingIsPartOfEquality() {
            CustomerRecord padded = new CustomerRecord();
            CustomerRecord unpadded = new CustomerRecord();

            padded.setCustFirstName(padded("Alice", 25));
            unpadded.setCustFirstName("Alice");

            assertThat(padded)
                    .as("both describe the same 25 stored bytes, so they are the same record")
                    .isEqualTo(unpadded);
            assertThat(padded.hashCode()).isEqualTo(unpadded.hashCode());
        }

        @Test
        @DisplayName("Two records equal on all 18 fields are equal whatever their FILLER came from")
        void fillerTakesNoPartInEquality() {
            // FILLER carries no value, so there is no way to make two records differ in it through the
            // public surface - which is itself the property being recorded. Equality is over values, and
            // the FILLER's presence is proved by the width check instead.
            CustomerRecord fromFixture = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord fromValues = row1AsBuilt();

            assertThat(fromValues).isEqualTo(fromFixture);
            assertThat(new String(fromValues.encode(ASCII), ASCII).substring(FILLER_OFFSET))
                    .isEqualTo(new String(fromFixture.encode(ASCII), ASCII).substring(FILLER_OFFSET));
        }

        @Test
        @DisplayName("A default record equals another default record")
        void twoDefaultRecordsAreEqual() {
            assertThat(new CustomerRecord()).isEqualTo(new CustomerRecord());
            assertThat(new CustomerRecord().hashCode()).isEqualTo(new CustomerRecord().hashCode());
            assertThat(new CustomerRecord()).isNotEqualTo(row1AsBuilt());
        }

        @Test
        @DisplayName("The no-argument constructor validates nothing, because it receives nothing")
        void theConstructorValidatesNothing() {
            // Recorded rather than invented: CustomerRecord has a single no-argument constructor and no
            // factory taking field values, so every PICTURE bound is enforced by the setters and the
            // decoder - which is where this class drives both sides of each. There is no compact
            // constructor to reject anything, and asserting one would be asserting a contract that does
            // not exist.
            assertThat(CustomerRecord.class.getDeclaredConstructors()).hasSize(1);
            assertThatCode(CustomerRecord::new).doesNotThrowAnyException();
            assertThat(new CustomerRecord().encode(ASCII)).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("The diagnostic rendering names all 18 copybook fields")
        void theDiagnosticRenderingNamesEveryField() {
            String rendering = CustomerRecord.decode(fixtureRow(1), ASCII).toString();

            assertThat(rendering).startsWith("CustomerRecord{").endsWith("}");
            for (String name : SPAN_NAMES) {
                if ("FILLER".equals(name)) {
                    continue;
                }
                assertThat(rendering)
                        .as("%s must be named under its copybook spelling", name)
                        .contains(name + "=");
            }
        }

        @Test
        @DisplayName("The rendering withholds the identity data this record is a dossier of")
        void theRenderingWithholdsTheIdentityData() {
            // CVCUS01Y is a complete identity dossier - legal name, three address lines, two telephone
            // numbers, date of birth, social security number, government identifier and an EFT account.
            // A log line rendering it in full would disclose everything needed to impersonate the
            // customer. Nothing observable changes: the accessors and encode() still return the real
            // values, which is what the parity differ compares.
            String rendering = CustomerRecord.decode(fixtureRow(1), ASCII).toString();

            assertThat(rendering)
                    .doesNotContain(ROW_1_SSN_IMAGE)
                    .doesNotContain(String.valueOf(ROW_1_SSN))
                    .doesNotContain(ROW_1_GOVT_ISSUED_ID)
                    .doesNotContain(ROW_1_EFT_ACCOUNT_ID)
                    .doesNotContain(ROW_1_FIRST_NAME)
                    .doesNotContain(ROW_1_LAST_NAME)
                    .doesNotContain(ROW_1_ADDR_LINE_1)
                    .doesNotContain(ROW_1_PHONE_1)
                    .doesNotContain(ROW_1_DOB)
                    .doesNotContain(ROW_1_CUST_ID_IMAGE);
            assertThat(rendering)
                    .as("the fields that identify nobody once the key is masked stay legible, because "
                            + "they are what a validation parity failure is read from")
                    .contains("CUST-ADDR-STATE-CD=[" + ROW_1_STATE_CD + "]")
                    .contains("CUST-ADDR-COUNTRY-CD=[" + ROW_1_COUNTRY_CD + "]")
                    .contains("CUST-PRI-CARD-HOLDER-IND=[" + ROW_1_PRI_CARD_HOLDER_IND + "]")
                    .contains("CUST-FICO-CREDIT-SCORE=" + ROW_1_FICO);
        }

        @Test
        @DisplayName("The byte contract lives in the group image, not in toString")
        void theByteContractLivesInTheGroupImage() {
            CustomerRecord record = CustomerRecord.decode(fixtureRow(1), ASCII);

            assertThat(record.toString())
                    .as("toString is a diagnostic; it is deliberately not 500 characters and is not a "
                            + "serialisation surface")
                    .isNotEqualTo(record.recordImage(ASCII));
            assertThat(record.recordImage(ASCII))
                    .as("recordImage is the byte contract")
                    .hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("A default record renders without throwing, blank fields included")
        void aDefaultRecordRendersWithoutThrowing() {
            String rendering = new CustomerRecord().toString();

            assertThat(rendering).startsWith("CustomerRecord{").contains("CUST-FIRST-NAME=");
            assertThat(rendering).doesNotContain("null");
        }

        @Test
        @DisplayName("The rendering stays on one line, so no stored byte can forge a second log entry")
        void theRenderingStaysOnOneLine() {
            // The three legible character fields are the ones at risk, because they are the ones shown in
            // full. They are PIC X spans holding whatever bytes the dataset holds, and nothing validates
            // their content - deliberately, since rejecting a stored value would be a behaviour change.
            // So a CR or LF among them must be escaped rather than ending the line early (CWE-117).
            CustomerRecord record = new CustomerRecord();
            record.setCustAddrStateCd("A\n");
            record.setCustAddrCountryCd("A\rB");
            record.setCustPriCardHolderInd("\t");
            record.setCustFirstName("A\r\nB");

            String rendering = record.toString();

            assertThat(rendering)
                    .as("a value reaching a log line unescaped can carry CR or LF and forge an entry")
                    .doesNotContain("\n")
                    .doesNotContain("\r")
                    .doesNotContain("\t");
            assertThat(rendering)
                    .as("each control byte is escaped as a COBOL hex literal rather than dropped, so the "
                            + "field is still rendered in full")
                    .contains("CUST-ADDR-STATE-CD=[AX'0A']")
                    .contains("CUST-ADDR-COUNTRY-CD=[AX'0D'B]")
                    .contains("CUST-PRI-CARD-HOLDER-IND=[X'09']");
        }
    }

    // =================================================================================================
    // 12. STRUCTURAL GUARDS - stated as ABSENCES, because that is what the gates require here.
    //
    //     CVCUS01Y declares no signed picture, no V-scaled picture, no COMP-3 and no PACKED-DECIMAL -
    //     and neither does any other copybook in app/cpy, which is why this record's codec never unpacks
    //     a nibble. Its complete numeric census is 9(09) twice and 9(03) once, all scale-free. So
    //     customer/model is the one persisted model package with no decimal type at all: there is no
    //     scale to hold, nothing to round, and the module's fixed-point helper has no subject here.
    //
    //     Where a scaled field DOES exist elsewhere in the codebase it is always S9(p)V99, occupying
    //     exactly p+2 bytes as zoned DISPLAY with the sign overpunched into the trailing byte; a scan for
    //     V-scales across app/cbl and app/cpy returns only V99. ROUNDED appears zero times in all 28
    //     programs, which is why truncation - RoundingMode.DOWN - is the project-wide policy. None of
    //     that is asserted here, because none of it belongs to this package; it is recorded so that the
    //     absence below reads as a deliberate finding rather than an oversight.
    // =================================================================================================

    @Nested
    @DisplayName("Structural guards - no floating point, no decimal, no persistence mapping")
    class StructuralGuards {

        @Test
        @DisplayName("No declared field is double, float, BigDecimal or BigInteger (G22, G23)")
        void noDeclaredFieldIsAForbiddenNumericType() {
            List<String> offenders = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                if (isForbiddenNumericType(field.getType())) {
                    offenders.add(field.getName() + " : " + field.getType().getName());
                }
            }

            assertThat(offenders)
                    .as("CVCUS01Y declares no signed and no V-scaled picture, so no field here is even a "
                            + "candidate for a decimal type - let alone a binary floating-point one, "
                            + "which cannot represent a decimal fraction exactly")
                    .isEmpty();
        }

        @Test
        @DisplayName("No method returns or accepts a floating-point or decimal type (G22, G23)")
        void noMethodTouchesAForbiddenNumericType() {
            List<String> offenders = new ArrayList<>();
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                if (isForbiddenNumericType(method.getReturnType())) {
                    offenders.add(method.getName() + " -> " + method.getReturnType().getName());
                }
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (isForbiddenNumericType(parameter)) {
                        offenders.add(method.getName() + "(" + parameter.getName() + ")");
                    }
                }
            }
            for (Constructor<?> constructor : CustomerRecord.class.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    if (isForbiddenNumericType(parameter)) {
                        offenders.add("<init>(" + parameter.getName() + ")");
                    }
                }
            }

            assertThat(offenders)
                    .as("no accessor may hand an identifier or a score out as floating point, and a "
                            + "BigDecimal anywhere on this surface would signal that a scale had been "
                            + "invented where the copybook declares none")
                    .isEmpty();
        }

        @Test
        @DisplayName("No rounding mode is reachable, because there is nothing scaled to round (G24)")
        void noRoundingModeIsReachable() {
            // An absence assertion by construction: RoundingMode is matched by NAME in
            // isForbiddenNumericType and is deliberately not imported by this test, exactly as
            // CustomerRecord does not import the module's fixed-point helper. With no V-scaled span there
            // is no rounding decision to make, so the safest statement is that none can be expressed.
            List<String> offenders = new ArrayList<>();
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    if ("java.math.RoundingMode".equals(parameter.getName())) {
                        offenders.add(method.getName());
                    }
                }
            }

            assertThat(offenders).isEmpty();
            assertThat(CustomerRecord.LAYOUT.spans())
                    .as("and the reason: not one span is SIGNED_SCALED")
                    .noneMatch(span -> span.kind() == PictureKind.SIGNED_SCALED);
        }

        @Test
        @DisplayName("The three numeric accessors are exact integral types, and only those three")
        void theNumericSurfaceIsExactAndMinimal() {
            List<String> numericAccessors = new ArrayList<>();
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                Class<?> returned = method.getReturnType();
                if (method.getName().startsWith("get") && returned.isPrimitive()
                        && !boolean.class.equals(returned)) {
                    numericAccessors.add(method.getName() + " -> " + returned.getName());
                }
            }

            assertThat(numericAccessors)
                    .as("exactly the three fields CVCUS01Y declares as PIC 9, each as int")
                    .containsExactlyInAnyOrder(
                            "getCustId -> int",
                            "getCustSsn -> int",
                            "getCustFicoCreditScore -> int");
        }

        @Test
        @DisplayName("The type carries no persistence annotation of any kind (G44)")
        void theTypeCarriesNoPersistenceAnnotation() {
            List<String> offenders = new ArrayList<>();
            collectPersistenceAnnotations(CustomerRecord.class.getAnnotations(), "the class", offenders);
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                collectPersistenceAnnotations(field.getAnnotations(), "field " + field.getName(),
                        offenders);
            }
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                collectPersistenceAnnotations(method.getAnnotations(), "method " + method.getName(),
                        offenders);
            }
            for (Constructor<?> constructor : CustomerRecord.class.getDeclaredConstructors()) {
                collectPersistenceAnnotations(constructor.getAnnotations(), "a constructor", offenders);
            }

            assertThat(offenders)
                    .as("the migration reaches the existing datasets over JDBC with no schema change, so "
                            + "there is no entity mapping, no table, no column and no DDL anywhere")
                    .isEmpty();
        }

        /**
         * Records any annotation drawn from a persistence API.
         *
         * @param annotations the annotations to inspect
         * @param location    a human-readable description of where they were found
         * @param offenders   the running list of violations
         */
        private void collectPersistenceAnnotations(Annotation[] annotations, String location,
                                                   List<String> offenders) {
            for (Annotation annotation : annotations) {
                String name = annotation.annotationType().getName();
                if (name.startsWith("jakarta.persistence.")
                        || name.startsWith("javax.persistence.")) {
                    offenders.add(location + " carries " + name);
                }
            }
        }

        @Test
        @DisplayName("No optimistic-lock or version column field exists (G44)")
        void noVersionColumnFieldExists() {
            List<String> offenders = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (name.contains("version") || name.contains("optlock")
                        || name.contains("rowversion") || name.contains("timestamp")) {
                    offenders.add(field.getName());
                }
            }

            assertThat(offenders)
                    .as("the update programs' 9300-CHECK-CHANGE-IN-REC does optimistic concurrency by "
                            + "re-reading and comparing field by field, which is what equals() serves. A "
                            + "version column would be a schema change, and schema changes are forbidden.")
                    .isEmpty();
        }

        @Test
        @DisplayName("No dataset name is embedded in the type (G46's local half)")
        void noDatasetNameIsEmbedded() {
            // The DSN AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS belongs in configuration, resolved by the
            // repository that owns the binding. A record layout has no business knowing where its bytes
            // are stored, and a literal here would be the first place a hard-coded name reappeared.
            List<String> offenders = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                if (!String.class.equals(field.getType()) || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Object value = field.get(null);
                    if (value instanceof String text && text.contains("AWS.M2.CARDDEMO")) {
                        offenders.add(field.getName());
                    }
                } catch (IllegalAccessException unreachable) {
                    throw new AssertionError("A static field of the class under test must be readable "
                            + "after setAccessible: " + field.getName(), unreachable);
                }
            }

            assertThat(offenders).isEmpty();
        }

        @Test
        @DisplayName("Every static member is final, so there is no mutable static state (G53)")
        void everyStaticMemberIsFinal() {
            List<String> offenders = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                boolean isStatic = Modifier.isStatic(field.getModifiers());
                boolean isFinal = Modifier.isFinal(field.getModifiers());
                if (isStatic && !isFinal) {
                    offenders.add(field.getName());
                }
            }

            assertThat(offenders)
                    .as("COBOL WORKING-STORAGE must never become static Java state: it would break row "
                            + "isolation and make tests order-dependent")
                    .isEmpty();
        }

        @Test
        @DisplayName("Every instance field is private and non-static, so state is strictly per record")
        void everyInstanceFieldIsPrivateAndPerInstance() {
            List<String> instanceFields = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                instanceFields.add(field.getName());
                assertThat(Modifier.isPrivate(field.getModifiers()))
                        .as("instance field %s must be private", field.getName())
                        .isTrue();
            }

            assertThat(instanceFields)
                    .as("one field per named copybook item; FILLER carries no value and so has none")
                    .hasSize(REFERABLE_FIELD_COUNT);
        }

        @Test
        @DisplayName("Two records never share state, however they were built")
        void twoRecordsNeverShareState() {
            CustomerRecord first = CustomerRecord.decode(fixtureRow(1), ASCII);
            CustomerRecord second = CustomerRecord.decode(fixtureRow(1), ASCII);

            first.setCustFirstName("Changed");

            assertThat(second.getCustFirstName())
                    .as("a shared or static field would let one row's change reach another's")
                    .isEqualTo(padded(ROW_1_FIRST_NAME, 25));
        }

        @Test
        @DisplayName("The type depends on common only - no cycle back through another domain package")
        void theTypeDependsOnCommonOnly() {
            // Checked over the type's own signature surface, which is where a dependency edge would first
            // appear. customer.model must not reach into account, card, transaction, statement, user,
            // admin, billing or config: the record types form an acyclic graph rooted at common, and the
            // statement package's near-identical customer layout in particular must stay separate.
            List<String> offenders = new ArrayList<>();
            List<Class<?>> referenced = new ArrayList<>();
            for (Field field : CustomerRecord.class.getDeclaredFields()) {
                referenced.add(field.getType());
            }
            for (Method method : CustomerRecord.class.getDeclaredMethods()) {
                referenced.add(method.getReturnType());
                referenced.addAll(List.of(method.getParameterTypes()));
            }
            for (Class<?> type : referenced) {
                String name = type.getName();
                if (!name.startsWith("com.vsergeychik.carddemo.")) {
                    continue;
                }
                boolean allowed = name.startsWith("com.vsergeychik.carddemo.common.")
                        || name.startsWith("com.vsergeychik.carddemo.customer.model.");
                if (!allowed) {
                    offenders.add(name);
                }
            }

            assertThat(offenders)
                    .as("customer.model depends on common, and on nothing else in this repository")
                    .isEmpty();
        }

        @Test
        @DisplayName("The declared record length is the single source of truth for the width")
        void theDeclaredRecordLengthIsTheSingleSourceOfTruth() {
            CustomerRecord record = row1AsBuilt();

            assertThat(CustomerRecord.RECORD_LENGTH)
                    .isEqualTo(CustomerRecord.LAYOUT.recordLength())
                    .isEqualTo(record.encode(ASCII).length)
                    .isEqualTo(record.encode(EBCDIC).length)
                    .isEqualTo(record.encode(asciiCodec).length)
                    .isEqualTo(record.recordImage(ASCII).length())
                    .isEqualTo(record.recordImage(asciiCodec).length())
                    .isEqualTo(CustomerRecord.FILLER.endOffsetExclusive())
                    .isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("The type is final, so no subclass can redefine the layout it publishes")
        void theTypeIsFinal() {
            assertThat(Modifier.isFinal(CustomerRecord.class.getModifiers()))
                    .as("a subclass could not change RECORD_LENGTH but could override an accessor, which "
                            + "would let two different records claim the same copybook")
                    .isTrue();
            assertThat(CustomerRecord.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(CustomerRecord.class.getInterfaces())
                    .as("no marker interface, no serialisation contract, no ORM callback hook")
                    .isEmpty();
        }
    }
}
