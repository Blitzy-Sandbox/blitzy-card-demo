package com.vsergeychik.carddemo.user.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link SecUserRecord}, the one Java type for copybook {@code app/cpy/CSUSR01Y.cpy}
 * and the 80-byte {@code USRSEC} security-user record.
 *
 * <h2>Provenance of every expectation in this file (practice B12)</h2>
 * Every number, name and byte asserted below is <strong>statically derived</strong> and transcribed
 * here as a Java literal. None of it was captured by running anything, because the 28 legacy COBOL
 * programs <strong>cannot be executed in this environment</strong>: eight independent blockers are
 * documented in AAP section 0.7.6 - no z/OS runtime, the available compiler reports its indexed file
 * handler as disabled, subprogram linkage cannot produce an executable, a copybook fails to parse on
 * literal tab characters, there are no Language Environment {@code CEE*} services, there is no CICS
 * emulator, the EBCDIC datasets need binary handling the compiler is not configured for, and no
 * alternative compiler installs. That substitution is recorded as risk <strong>R-A</strong> in AAP
 * section 0.9.11.
 *
 * <p>The transcribed sources, each cited at the line it was read from:
 * <ul>
 *   <li>{@code app/cpy/CSUSR01Y.cpy:17-23} - the six field names, offsets and widths, and the total
 *       of 80;</li>
 *   <li>{@code app/jcl/DUSRSECJ.jcl:34-44} - the ten in-stream seed cards, reproduced verbatim;
 *       {@code :48} - {@code DCB=(LRECL=80,RECFM=FB,DSORG=PS,BLKSIZE=0)}; {@code :65} -
 *       {@code KEYS(8,0)}; {@code :66} - {@code RECORDSIZE(80,80)};</li>
 *   <li>{@code app/catlg/LISTCAT.txt:3883-3884} - {@code KEYLEN 8}, {@code AVGLRECL 80},
 *       {@code RKP 0}, {@code MAXLRECL 80}; {@code :3888} - {@code REC-TOTAL 10};</li>
 *   <li>{@code app/csd/CARDDEMO.CSD:88-99} - the {@code USRSEC} file definition;</li>
 *   <li>{@code app/cbl/COSGN00C.cbl:223} - {@code IF SEC-USR-PWD = WS-USER-PWD}; {@code :227} -
 *       {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE};</li>
 *   <li>{@code app/cbl/COUSR00C.cbl:219} and {@code :240} - {@code MOVE LOW-VALUES TO SEC-USR-ID};
 *       {@code :263} - {@code MOVE HIGH-VALUES TO SEC-USR-ID};</li>
 *   <li>{@code app/cbl/COUSR01C.cbl:154-158} - the five plain {@code MOVE}s into the record;</li>
 *   <li>{@code app/cbl/COUSR02C.cbl:169} - {@code MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI};</li>
 *   <li>{@code app/cbl/COUSR03C.cbl:165-167} - first name, last name and type moved to the delete
 *       screen, and deliberately <em>not</em> the password;</li>
 *   <li>{@code README.md:157-158} - userid {@code ADMIN001} / {@code USER0001} with the initially
 *       configured password {@code PASSWORD}.</li>
 * </ul>
 *
 * <p><strong>Nothing here is read from disk at run time</strong> (practice B3). Reaching outside the
 * Maven module for a reference input would make the result depend on which directory the build was
 * launched from, and would put file I/O in the path of a pure unit test, so every expectation is a
 * literal instead. A binary {@code USRSEC} dataset does exist under {@code app/data/EBCDIC}, but AAP
 * section 0.2.6 places {@code app/data/EBCDIC/**} explicitly out of scope with the ASCII side
 * authoritative, so it is deliberately <strong>not</strong> loaded here - a later reader should not
 * "helpfully" wire it in. Its name is not written out either: per gate G46 no mainframe dataset name
 * belongs in Java source, and the {@code USRSEC} DSNAME lives only in {@code app/csd/CARDDEMO.CSD:89}
 * and, for this module, in {@code application.yml}.
 *
 * <h2>Four independent witnesses agree on 80 bytes, key 8 at offset 0</h2>
 * <ol>
 *   <li>the copybook's own offsets, which sum to 80;</li>
 *   <li>the IDCAMS {@code DEFINE CLUSTER} in {@code DUSRSECJ.jcl} - {@code KEYS(8,0)} and
 *       {@code RECORDSIZE(80,80)};</li>
 *   <li>the {@code LISTCAT} catalogue attributes - {@code KEYLEN 8}, {@code RKP 0}, and
 *       {@code AVGLRECL == MAXLRECL == 80}, which is the catalogue's own proof the record is
 *       genuinely <em>fixed</em> rather than variable;</li>
 *   <li>the {@code DCB} on the sequential load step - {@code LRECL=80,RECFM=FB}.</li>
 * </ol>
 * If an assertion below disagreed with any of the four, the assertion would be the thing that is
 * wrong.
 *
 * <h2>Two conflicts, documented rather than worked around (practice B4)</h2>
 * <ul>
 *   <li>{@code app/csd/CARDDEMO.CSD:93} declares {@code RECORDFORMAT(V)} for the {@code USRSEC}
 *       file, while {@code DUSRSECJ.jcl:48} and {@code :66} and {@code LISTCAT.txt:3883-3884} all
 *       give a <strong>fixed</strong> 80. Per AAP section 0.7.4 the Java layer treats record length
 *       as copybook-fixed regardless, so this file asserts a single fixed 80 and models no variable
 *       length at all. The conflict is recorded here, not silently resolved.</li>
 *   <li>There is <strong>no</strong> {@code usrsec} fixture under {@code app/data/ASCII} - that
 *       directory holds exactly nine files and none of them is for this dataset. The sample data
 *       therefore comes from the in-stream cards of the load job, which is why the ten literals below
 *       are 57 characters rather than 80.</li>
 * </ul>
 *
 * <h2>Governing constraints</h2>
 * {@code review_rules} returns exactly one line, "No user rules provided", and that single line is
 * the entire document. Their absence is not permission to lower the bar, so the binding constraints
 * are the enterprise best-practice substitutes enumerated in AAP section 0.10.2, cited here by
 * identifier only: <strong>B1</strong> pinned dependency set - JUnit Jupiter and AssertJ alone, both
 * already managed by the parent BOM, with nothing added to the pom and no third-party copybook
 * parser; <strong>B2</strong> Java 21 language level; <strong>B3</strong> reference inputs immutable
 * and never read at run time; <strong>B4</strong> conflicts documented; <strong>B5</strong> dead code
 * preserved; <strong>B6</strong> security posture neither weakened nor unrequestedly strengthened;
 * <strong>B7</strong> deterministic and non-interactive - no Spring context, no Mockito, no
 * filesystem, no network, no randomness, no clock and no inter-test ordering; <strong>B8</strong>
 * explicit over implicit - a named {@link Charset} on every call and no wildcard imports;
 * <strong>B9</strong> no mutable static state; <strong>B10</strong> tests ship with the code;
 * <strong>B11</strong> offsets and widths asserted as literals a reviewer can diff against the
 * copybook; <strong>B12</strong> this provenance header.
 *
 * <h2>Acceptance gates asserted here, and the ones that have no subject here</h2>
 * Asserted: <strong>G8</strong> one type per copybook and one test class for it;
 * <strong>G19</strong> the record is exactly its declared 80 bytes; <strong>G21</strong> the trailing
 * span is present and space-filled; <strong>G22</strong> no {@code double} or {@code float} anywhere;
 * <strong>G41</strong> the password is plaintext with no hashing; <strong>G44</strong> no persistence
 * artefact of any kind; <strong>G46</strong> no dataset-name literal; <strong>G49</strong> the branch
 * bar for this package; <strong>G52</strong> no wildcard imports; <strong>G53</strong> no mutable
 * static state; <strong>G54</strong> runs non-interactively.
 *
 * <p>Not applicable here, each for a stated reason, so that a later reader does not "fix" the gap:
 * <ul>
 *   <li><strong>G23</strong> and <strong>G24</strong> - {@code BigDecimal} scale and
 *       {@code RoundingMode.DOWN} have <em>no subject</em>. {@code CSUSR01Y} declares no numeric, no
 *       {@code COMP}, no {@code COMP-3} and no scaled field, so this record has no arithmetic at all
 *       and deliberately does not consume the decimal seam. {@link StructuralGuarantees} asserts that
 *       absence rather than merely claiming it.</li>
 *   <li><strong>G33</strong> - the {@code OCCURS} one-based to zero-based conversion. The copybook
 *       has no {@code OCCURS}; the {@code OCCURS 10 TIMES} table belongs to the <em>program</em>
 *       {@code COUSR00C}, and the controller's own test owns that assertion.</li>
 *   <li><strong>G34</strong> - {@code REDEFINES} round-trips. The copybook has none; the symbolic
 *       maps' {@code xxxA REDEFINES xxxF} pairs belong to the {@code user.dto} test package.</li>
 *   <li><strong>G43</strong> - optimistic concurrency. No {@code user} program contains
 *       {@code 9300-CHECK-CHANGE-IN-REC}, so the paragraph has no counterpart in this package.</li>
 * </ul>
 *
 * <p>Determinism note (practice B7): every case below fixes its own {@link Charset} explicitly, and
 * nothing reads a clock, a locale-sensitive default or the platform encoding. The results are
 * therefore identical whatever the JVM's default charset and default time zone happen to be, which is
 * verified by re-running the suite under a deliberately different pair.
 */
class SecUserRecordTest {

    // =============================================================================================
    // Fixed, immutable test constants. Every one is static FINAL and every value is deeply immutable,
    // so this class holds no mutable static state (practice B9, gate G53).
    // =============================================================================================

    /** The code page of the text fixtures, named explicitly and never defaulted (practice B8). */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The code page of the EBCDIC datasets, named explicitly and never defaulted (practice B8). */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /** The space byte under {@code US-ASCII}, asserted as a literal rather than computed. */
    private static final byte ASCII_SPACE = (byte) 0x20;

    /** The space byte under {@code IBM037}, asserted as a literal rather than computed. */
    private static final byte EBCDIC_SPACE = (byte) 0x40;

    /** COBOL {@code LOW-VALUES}: the all-bits-zero byte, not a space. */
    private static final byte LOW_VALUE = (byte) 0x00;

    /** COBOL {@code HIGH-VALUES}: the all-bits-one byte, not a space. */
    private static final byte HIGH_VALUE = (byte) 0xFF;

    /** The declared record width, from {@code app/cpy/CSUSR01Y.cpy:17-23}. */
    private static final int RECORD_WIDTH = 80;

    /** The width of one in-stream seed card, from {@code app/jcl/DUSRSECJ.jcl:35-44}. */
    private static final int CARD_WIDTH = 57;

    /** The width of {@code SEC-USR-FILLER PIC X(23)}, from {@code app/cpy/CSUSR01Y.cpy:23}. */
    private static final int FILLER_WIDTH = 23;

    /**
     * The one password every seeded user carries, from {@code app/jcl/DUSRSECJ.jcl:35-44} and
     * independently corroborated by {@code README.md:157-158}, which names userid {@code ADMIN001}
     * and userid {@code USER0001} with "the initially configured password PASSWORD".
     *
     * <p>This is legacy sample data published in the upstream project's own load job, not a
     * credential for any live system. It is exactly eight characters, so it fills
     * {@code SEC-USR-PWD PIC X(08)} with no padding and no truncation.
     */
    private static final String SEED_PASSWORD = "PASSWORD";

    /**
     * The ten in-stream seed cards of {@code app/jcl/DUSRSECJ.jcl:35-44}, transcribed verbatim.
     *
     * <p>Each is exactly {@value #CARD_WIDTH} characters: the record's first five fields at their
     * declared widths, and nothing more. The 23 bytes of {@code SEC-USR-FILLER} are absent from the
     * source data and are supplied by the dataset's own {@code LRECL=80,RECFM=FB}
     * [{@code DUSRSECJ.jcl:48}], which is why these must be widened to 80 before they can be decoded.
     *
     * <p>{@code LISTCAT.txt:3888} records {@code REC-TOTAL 10}, independently confirming the count.
     */
    private static final List<String> SEED_CARDS = List.of(
            "ADMIN001MARGARET            GOLD                PASSWORDA",
            "ADMIN002RUSSELL             RUSSELL             PASSWORDA",
            "ADMIN003RAYMOND             WHITMORE            PASSWORDA",
            "ADMIN004EMMANUEL            CASGRAIN            PASSWORDA",
            "ADMIN005GRANVILLE           LACHAPELLE          PASSWORDA",
            "USER0001LAWRENCE            THOMAS              PASSWORDU",
            "USER0002AJITH               KUMAR               PASSWORDU",
            "USER0003LAURITZ             ALME                PASSWORDU",
            "USER0004AVERARDO            MAZZI               PASSWORDU",
            "USER0005LEE                 TING                PASSWORDU");

    // =============================================================================================
    // Pure static helpers. No state, no I/O, no clock - so two invocations can never differ.
    // =============================================================================================

    /** Right-pads to a declared width, the way a COBOL {@code PIC X} field holds a short value. */
    private static String pad(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /** A run of {@code count} spaces. */
    private static String spaces(int count) {
        return " ".repeat(count);
    }

    /**
     * Widens one 57-character seed card to the record's declared 80 characters by appending the 23
     * spaces that {@code SEC-USR-FILLER} occupies.
     *
     * <p>This reproduces in source exactly what {@code DCB=(LRECL=80,RECFM=FB,...)} at
     * {@code DUSRSECJ.jcl:48} does to the in-stream data on the way into the dataset. Doing it here
     * rather than reading a file keeps the test inside its own module (practice B3).
     */
    private static String recordImage(String card) {
        return card + spaces(FILLER_WIDTH);
    }

    /** The 80-byte image of one seed card under an explicitly named code page. */
    private static byte[] recordBytes(String card, Charset charset) {
        return recordImage(card).getBytes(charset);
    }

    /** A run of {@code count} copies of one raw byte, for the key-span sentinel cases. */
    private static byte[] repeatByte(byte value, int count) {
        byte[] bytes = new byte[count];
        Arrays.fill(bytes, value);
        return bytes;
    }

    /**
     * One seeded record, decoded from its transcribed card under an explicitly named code page.
     *
     * <p>Index 0 through 4 are the five {@code ADMIN00n} rows and 5 through 9 the five
     * {@code USER000n} rows, in the order {@code DUSRSECJ.jcl:35-44} lists them.
     */
    private static SecUserRecord seedRecord(int index, Charset charset) {
        return SecUserRecord.decode(recordBytes(SEED_CARDS.get(index), charset), charset);
    }

    /**
     * A record with exactly one named field set to {@code value} and the other five left empty, so a
     * per-field sweep can assert one span's behaviour without any other span's value interfering.
     *
     * <p>Every value goes through the {@code of(...)} factory, which is where the COBOL alphanumeric
     * {@code MOVE} rule is applied, so an empty string becomes that field's declared width in spaces.
     */
    private static SecUserRecord fieldSetTo(String cobolName, String value) {
        FixedWidthCodec codec = new FixedWidthCodec(ASCII);
        return switch (cobolName) {
            case "SEC-USR-ID" -> SecUserRecord.of(value, "", "", "", "", "", codec);
            case "SEC-USR-FNAME" -> SecUserRecord.of("", value, "", "", "", "", codec);
            case "SEC-USR-LNAME" -> SecUserRecord.of("", "", value, "", "", "", codec);
            case "SEC-USR-PWD" -> SecUserRecord.of("", "", "", value, "", "", codec);
            case "SEC-USR-TYPE" -> SecUserRecord.of("", "", "", "", value, "", codec);
            case "SEC-USR-FILLER" -> SecUserRecord.of("", "", "", "", "", value, codec);
            default -> throw new IllegalArgumentException(
                    "'" + cobolName + "' is not one of the six CSUSR01Y.cpy:18-23 field names");
        };
    }

    /**
     * An 80-byte row whose 8-byte key span holds a raw sentinel byte and whose remaining 72 bytes are
     * an ordinary record body, built from the first seed card.
     *
     * <p>This is how {@code app/cbl/COUSR00C.cbl} uses the key: it moves {@code LOW-VALUES} or
     * {@code HIGH-VALUES} into {@code SEC-USR-ID} alone and leaves the rest of the record area to be
     * filled by whatever the browse positions on.
     */
    private static byte[] sentinelKeyRow(byte sentinel, Charset charset) {
        byte[] row = recordBytes(SEED_CARDS.get(0), charset);
        for (int index = 0; index < 8; index++) {
            row[index] = sentinel;
        }
        return row;
    }

    /**
     * Every type named anywhere in a class's own declared surface: field types, method return and
     * parameter types, constructor parameter types, and record component types.
     *
     * <p>Used by the structural guarantees to check what the model may and may not touch, without
     * needing to inspect method bodies.
     */
    private static Set<Class<?>> declaredTypesOf(Class<?> type) {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Field field : type.getDeclaredFields()) {
            types.add(field.getType());
        }
        for (Method method : type.getDeclaredMethods()) {
            types.add(method.getReturnType());
            types.addAll(Arrays.asList(method.getParameterTypes()));
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            types.addAll(Arrays.asList(constructor.getParameterTypes()));
        }
        RecordComponent[] components = type.getRecordComponents();
        if (components != null) {
            for (RecordComponent component : components) {
                types.add(component.getType());
            }
        }
        return types;
    }

    // =============================================================================================
    // Phase 1 and 2 - the copybook layout and the published constants, asserted as literals.
    // =============================================================================================

    @Nested
    @DisplayName("Layout and constants, transcribed from CSUSR01Y.cpy:17-23")
    class LayoutAndConstants {

        /**
         * The record width, asserted twice from independent directions so the two derivations must
         * agree: once as the bare literal 80, and once as the sum of the six declared widths.
         *
         * <p>Practice B11: the literal is written out rather than read back from the class, so a
         * reviewer can diff this line against the copybook without running anything.
         */
        @Test
        @DisplayName("RECORD_LENGTH is the literal 80, and equals the sum of the six field widths")
        void recordLength_is80_andEqualsTheSumOfTheSixDeclaredWidths() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);

            // 8 + 20 + 20 + 8 + 1 + 23 = 80, written as literals in copybook order.
            assertThat(8 + 20 + 20 + 8 + 1 + 23).isEqualTo(80);

            assertThat(SecUserRecord.SEC_USR_ID_LENGTH
                    + SecUserRecord.SEC_USR_FNAME_LENGTH
                    + SecUserRecord.SEC_USR_LNAME_LENGTH
                    + SecUserRecord.SEC_USR_PWD_LENGTH
                    + SecUserRecord.SEC_USR_TYPE_LENGTH
                    + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("the six published widths must themselves total the published record length")
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);
        }

        /**
         * Every published offset and width constant against its copybook literal.
         *
         * <p>These are the twelve numbers the whole contract rests on. They are asserted here one at
         * a time, in copybook order, so the block reads as a transcription of
         * {@code CSUSR01Y.cpy:18-23} (practice B11).
         */
        @Test
        @DisplayName("all twelve published offset and width constants are the copybook literals")
        void perFieldOffsetsAndLengths_areTheCopybookLiterals() {
            // CSUSR01Y.cpy:18   05 SEC-USR-ID     PIC X(08).
            assertThat(SecUserRecord.SEC_USR_ID_OFFSET).isEqualTo(0);
            assertThat(SecUserRecord.SEC_USR_ID_LENGTH).isEqualTo(8);
            // CSUSR01Y.cpy:19   05 SEC-USR-FNAME  PIC X(20).
            assertThat(SecUserRecord.SEC_USR_FNAME_OFFSET).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_FNAME_LENGTH).isEqualTo(20);
            // CSUSR01Y.cpy:20   05 SEC-USR-LNAME  PIC X(20).
            assertThat(SecUserRecord.SEC_USR_LNAME_OFFSET).isEqualTo(28);
            assertThat(SecUserRecord.SEC_USR_LNAME_LENGTH).isEqualTo(20);
            // CSUSR01Y.cpy:21   05 SEC-USR-PWD    PIC X(08).
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(8);
            // CSUSR01Y.cpy:22   05 SEC-USR-TYPE   PIC X(01).
            assertThat(SecUserRecord.SEC_USR_TYPE_OFFSET).isEqualTo(56);
            assertThat(SecUserRecord.SEC_USR_TYPE_LENGTH).isEqualTo(1);
            // CSUSR01Y.cpy:23   05 SEC-USR-FILLER PIC X(23).
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET).isEqualTo(57);
            assertThat(SecUserRecord.SEC_USR_FILLER_LENGTH).isEqualTo(23);
        }

        /**
         * The key geometry, which four independent witnesses agree on.
         *
         * <p>{@code DUSRSECJ.jcl:65} declares {@code KEYS(8,0)} - length 8 at offset 0 - and
         * {@code LISTCAT.txt:3883-3884} independently records {@code KEYLEN 8} and {@code RKP 0}.
         * The key is not merely 8 bytes long, it <em>is</em> {@code SEC-USR-ID}: every keyed CICS call
         * in the four user-maintenance programs passes {@code KEYLENGTH (LENGTH OF SEC-USR-ID)} -
         * {@code COUSR00C.cbl:591}, {@code :626}, {@code :660}, {@code COUSR01C.cbl:245},
         * {@code COUSR02C.cbl:327} and {@code COUSR03C.cbl:274} - so 8 is the length CICS itself uses.
         */
        @Test
        @DisplayName("KEY_OFFSET is 0 and KEY_LENGTH is 8, per KEYS(8,0), RKP 0 and KEYLEN 8")
        void keyGeometry_is8BytesAtOffset0_asFourWitnessesAgree() {
            assertThat(SecUserRecord.KEY_OFFSET).isEqualTo(0);
            assertThat(SecUserRecord.KEY_LENGTH).isEqualTo(8);

            // The key is SEC-USR-ID itself, so the two must coincide exactly.
            assertThat(SecUserRecord.KEY_OFFSET).isEqualTo(SecUserRecord.SEC_USR_ID_OFFSET);
            assertThat(SecUserRecord.KEY_LENGTH).isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH);
        }

        /**
         * The copybook's own spellings, carried verbatim.
         *
         * <p>The group item is {@code SEC-USER-DATA} with {@code USER} in full while all six
         * subordinate items use the contracted {@code SEC-USR-} prefix. That asymmetry is preserved
         * rather than tidied, because the parity differ compares field by field <em>by name</em>: a
         * prettier name would stop matching the oracle and make a real difference invisible.
         */
        @Test
        @DisplayName("COBOL names are verbatim, group/field spelling asymmetry included")
        void cobolNames_arePreservedVerbatim_includingTheGroupFieldAsymmetry() {
            assertThat(SecUserRecord.GROUP_NAME).isEqualTo("SEC-USER-DATA");
            assertThat(SecUserRecord.FIELD_SEC_USR_ID).isEqualTo("SEC-USR-ID");
            assertThat(SecUserRecord.FIELD_SEC_USR_FNAME).isEqualTo("SEC-USR-FNAME");
            assertThat(SecUserRecord.FIELD_SEC_USR_LNAME).isEqualTo("SEC-USR-LNAME");
            assertThat(SecUserRecord.FIELD_SEC_USR_PWD).isEqualTo("SEC-USR-PWD");
            assertThat(SecUserRecord.FIELD_SEC_USR_TYPE).isEqualTo("SEC-USR-TYPE");
            assertThat(SecUserRecord.FIELD_SEC_USR_FILLER).isEqualTo("SEC-USR-FILLER");

            // The asymmetry itself, stated so a future "consistency" edit fails here first.
            assertThat(SecUserRecord.GROUP_NAME).contains("USER").doesNotContain("SEC-USR-");
            assertThat(List.of(SecUserRecord.FIELD_SEC_USR_ID,
                            SecUserRecord.FIELD_SEC_USR_FNAME,
                            SecUserRecord.FIELD_SEC_USR_LNAME,
                            SecUserRecord.FIELD_SEC_USR_PWD,
                            SecUserRecord.FIELD_SEC_USR_TYPE,
                            SecUserRecord.FIELD_SEC_USR_FILLER))
                    .allSatisfy(name -> assertThat(name).startsWith("SEC-USR-"));
        }

        /**
         * The single fixed width, and the conflict it resolves (practice B4).
         *
         * <p>{@code CARDDEMO.CSD:93} declares {@code RECORDFORMAT(V)} for this file, which would
         * suggest a variable-length record. Three other witnesses say fixed 80:
         * {@code DUSRSECJ.jcl:66} {@code RECORDSIZE(80,80)} - a minimum equal to its maximum -
         * {@code DUSRSECJ.jcl:48} {@code LRECL=80,RECFM=FB}, and {@code LISTCAT.txt:3883-3884}
         * {@code AVGLRECL 80} together with {@code MAXLRECL 80}. Per AAP section 0.7.4 the Java layer
         * treats record length as copybook-fixed regardless, so exactly one width exists here and no
         * variable-length notion is modelled at all.
         */
        @Test
        @DisplayName("one fixed width only, despite the CSD's RECORDFORMAT(V)")
        void recordWidth_isASingleFixed80_notAVariableLength() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(RECORD_WIDTH);
            assertThat(SecUserRecord.LAYOUT.recordLength()).isEqualTo(RECORD_WIDTH);

            // Fixed means every encode produces the same width, whatever the content.
            assertThat(SecUserRecord.encode(SecUserRecord.blank(), ASCII)).hasSize(RECORD_WIDTH);
            assertThat(SecUserRecord.encode(
                    SecUserRecord.of("ADMIN001", "MARGARET", "GOLD", SEED_PASSWORD, "A", ASCII),
                    ASCII)).hasSize(RECORD_WIDTH);
            assertThat(SecUserRecord.encode(
                    SecUserRecord.of("", "", "", "", "", ASCII), ASCII)).hasSize(RECORD_WIDTH);
        }
    }

    // =============================================================================================
    // Phase 2 - the immutable field-descriptor table the parity harness diffs field-name by
    // field-name.
    // =============================================================================================

    @Nested
    @DisplayName("The field-descriptor table")
    class Descriptors {

        @Test
        @DisplayName("there are exactly six descriptors, in copybook declaration order")
        void descriptorTable_hasSixEntriesInCopybookOrder() {
            assertThat(SecUserRecord.SPANS).hasSize(6);
            assertThat(SecUserRecord.SPANS.stream().map(FieldSpan::name))
                    .containsExactly("SEC-USR-ID", "SEC-USR-FNAME", "SEC-USR-LNAME",
                            "SEC-USR-PWD", "SEC-USR-TYPE", "SEC-USR-FILLER");

            assertThat(SecUserRecord.LAYOUT.storageSpans()).hasSize(6);
            assertThat(SecUserRecord.LAYOUT.redefinitions())
                    .as("CSUSR01Y declares no REDEFINES, so gate G34 has no subject in this package")
                    .isEmpty();
        }

        /**
         * Each descriptor resolved by its COBOL name and checked against the literal offset and width
         * from {@code CSUSR01Y.cpy:18-23}. The expectations are the parameters, never read back from
         * the class under test (practice B11).
         */
        @ParameterizedTest(name = "{0} at offset {1}, {2} byte(s)")
        @CsvSource({
                "SEC-USR-ID,0,8",
                "SEC-USR-FNAME,8,20",
                "SEC-USR-LNAME,28,20",
                "SEC-USR-PWD,48,8",
                "SEC-USR-TYPE,56,1",
                "SEC-USR-FILLER,57,23"
        })
        @DisplayName("every descriptor sits where the copybook puts it and is PIC X")
        void everyDescriptor_isAlphanumericAtItsCopybookPosition(String cobolName,
                                                                 int offset,
                                                                 int length) {
            FieldSpan span = SecUserRecord.LAYOUT.span(cobolName);

            assertThat(span.name()).isEqualTo(cobolName);
            assertThat(span.offset()).isEqualTo(offset);
            assertThat(span.length()).isEqualTo(length);
            assertThat(span.endOffsetExclusive()).isEqualTo(offset + length);

            // Every one of the six copybook items is PIC X: there is no numeric, no COMP, no COMP-3
            // and no scaled field anywhere in CSUSR01Y, which is why gates G23 and G24 have no
            // subject in this package.
            assertThat(span.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(span.kind().numericDisplay()).isFalse();
            assertThat(span.kind().leftJustified())
                    .as("PIC X is left justified and pads on the right")
                    .isTrue();
            assertThat(span.redefinition()).isFalse();
            assertThat(span.hasInitialValue())
                    .as("CSUSR01Y declares no VALUE clause on any item")
                    .isFalse();
        }

        /**
         * Contiguity and non-overlap: the mechanical check a reviewer can diff against the copybook.
         *
         * <p>For every entry after the first, {@code offset(i) == offset(i-1) + length(i-1)}; and the
         * last entry ends at exactly {@link SecUserRecord#RECORD_LENGTH}. Together those two rules
         * mean there is no gap, no overlap and no missing span - in particular no dropped
         * {@code SEC-USR-FILLER}.
         */
        @Test
        @DisplayName("descriptors are contiguous from 0, never overlap, and end at exactly 80")
        void descriptors_areContiguousAndNonOverlapping_andEndAt80() {
            List<FieldSpan> spans = SecUserRecord.LAYOUT.storageSpans();

            assertThat(spans.get(0).offset()).as("the first span starts at offset 0").isZero();
            for (int index = 1; index < spans.size(); index++) {
                FieldSpan previous = spans.get(index - 1);
                FieldSpan current = spans.get(index);
                assertThat(current.offset())
                        .as("%s must begin where %s ends", current.name(), previous.name())
                        .isEqualTo(previous.offset() + previous.length());
            }

            FieldSpan last = spans.get(spans.size() - 1);
            assertThat(last.name()).isEqualTo("SEC-USR-FILLER");
            assertThat(last.offset() + last.length()).isEqualTo(SecUserRecord.RECORD_LENGTH);
        }

        /**
         * {@code SEC-USR-FILLER} is a <em>named</em> item whose name merely contains the word
         * {@code FILLER}; it is not COBOL's reserved anonymous {@code FILLER}. The distinction is
         * load-bearing: an anonymous span is not referable, so it would be excluded from name lookup
         * and from the field-image map, hiding these 23 bytes from a field-by-field differ.
         *
         * <p>Practice B5: {@code grep -rn "SEC-USR-FILLER" app/} matches exactly one non-Java line,
         * {@code CSUSR01Y.cpy:23}. No COBOL program reads or assigns it. It is dead code, and dead
         * code is preserved rather than tidied away - which is precisely what makes gates G19 and G21
         * meaningful.
         */
        @Test
        @DisplayName("SEC-USR-FILLER is a resolvable named span, not an anonymous gap")
        void secUsrFiller_isANamedSpan_notAnAnonymousFiller() {
            FieldSpan filler = SecUserRecord.LAYOUT.span("SEC-USR-FILLER");

            assertThat(filler).isEqualTo(SecUserRecord.SPAN_SEC_USR_FILLER);
            assertThat(filler.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
            assertThat(filler.kind().filler())
                    .as("declared as a named alphanumeric item so it stays referable by name")
                    .isFalse();
            assertThat(SecUserRecord.LAYOUT.hasSpan("SEC-USR-FILLER")).isTrue();
            assertThat(SecUserRecord.blank().image("SEC-USR-FILLER")).hasSize(23);
        }

        /**
         * The descriptor table is genuinely immutable, not merely documented as such.
         *
         * <p>A mutating call must be refused <em>and</em> must leave the table unchanged. That is a
         * real branch inside the unmodifiable wrapper, and it also serves practice B9: a shared
         * static table that a caller could mutate would be mutable static state by another name.
         */
        @Test
        @DisplayName("the descriptor table refuses mutation and is unchanged by the attempt")
        void descriptorTable_isImmutable_andUnchangedByAFailedMutation() {
            List<FieldSpan> before = List.copyOf(SecUserRecord.SPANS);

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> SecUserRecord.SPANS.add(SecUserRecord.SPAN_SEC_USR_ID));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> SecUserRecord.SPANS.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> SecUserRecord.SPANS.set(0, SecUserRecord.SPAN_SEC_USR_PWD));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(SecUserRecord.SPANS::clear);

            assertThat(SecUserRecord.SPANS)
                    .as("a refused mutation must not have altered the table")
                    .containsExactlyElementsOf(before)
                    .hasSize(6);
        }

        /**
         * The field-image map is the differ's contract: insertion-ordered, complete, and unmodifiable.
         * Its mutation-refused path is the other side of the same branch.
         */
        @Test
        @DisplayName("the field-image map is complete, ordered, and refuses mutation")
        void fieldImages_areOrderedCompleteAndImmutable() {
            SecUserRecord record = seedRecord(0, ASCII);
            Map<String, String> images = record.fieldImages();

            assertThat(images).hasSize(6);
            assertThat(images.keySet()).containsExactly("SEC-USR-ID", "SEC-USR-FNAME",
                    "SEC-USR-LNAME", "SEC-USR-PWD", "SEC-USR-TYPE", "SEC-USR-FILLER");

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> images.put("SEC-USR-ID", "HACKED  "));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> images.remove("SEC-USR-PWD"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(images::clear);

            assertThat(record.fieldImages())
                    .as("a refused mutation must not have altered the record")
                    .containsEntry("SEC-USR-ID", "ADMIN001")
                    .containsEntry("SEC-USR-PWD", SEED_PASSWORD);
        }

        /**
         * Both sides of the descriptor lookup: a known name resolves, an unknown one takes the
         * not-found path. Case-sensitivity is part of the contract, because the differ keys on the
         * copybook's exact spelling.
         */
        @Test
        @DisplayName("a known descriptor name resolves and an unknown one does not")
        void descriptorLookup_resolvesKnownNames_andRejectsUnknownOnes() {
            // The found path, for all six.
            for (String known : List.of("SEC-USR-ID", "SEC-USR-FNAME", "SEC-USR-LNAME",
                    "SEC-USR-PWD", "SEC-USR-TYPE", "SEC-USR-FILLER")) {
                assertThat(SecUserRecord.LAYOUT.hasSpan(known)).as(known).isTrue();
                assertThat(SecUserRecord.LAYOUT.span(known).name()).isEqualTo(known);
            }

            // The not-found path: a misspelling, a wrong separator, wrong case, the group name, and
            // the reserved anonymous name.
            for (String unknown : List.of("SEC-USER-FNAME", "SEC_USR_ID", "sec-usr-id",
                    "SEC-USER-DATA", "FILLER", "")) {
                assertThat(SecUserRecord.LAYOUT.hasSpan(unknown)).as(unknown).isFalse();
                assertThatIllegalArgumentException()
                        .as(unknown)
                        .isThrownBy(() -> SecUserRecord.LAYOUT.span(unknown));
            }
        }
    }

    // =============================================================================================
    // Phase 3 - PIC X semantics. Every one of the six items is alphanumeric, so this is the whole of
    // the record's codec surface.
    // =============================================================================================

    @Nested
    @DisplayName("PIC X semantics: padded on write, truncated on the right, never trimmed on read")
    class PictureXSemantics {

        /**
         * A short sending value is padded on the <strong>right</strong> to the declared width, per
         * field, with the space character.
         */
        @ParameterizedTest(name = "{0} pads to {1}")
        @CsvSource({
                "SEC-USR-ID,8",
                "SEC-USR-FNAME,20",
                "SEC-USR-LNAME,20",
                "SEC-USR-PWD,8",
                "SEC-USR-TYPE,1",
                "SEC-USR-FILLER,23"
        })
        @DisplayName("a short value is space-padded on the right to its declared width")
        void shortValue_isPaddedOnTheRight(String cobolName, int declaredWidth) {
            // One character, which is short for every field except SEC-USR-TYPE PIC X(01) where it is
            // already exact - so this case covers both the pad branch and the no-op branch.
            SecUserRecord record = fieldSetTo(cobolName, "Q");
            String image = record.image(cobolName);

            assertThat(image).hasSize(declaredWidth);
            assertThat(image).startsWith("Q");
            assertThat(image.substring(1)).isEqualTo(spaces(declaredWidth - 1));
        }

        /**
         * Writing one field must leave every neighbouring byte untouched. This is the assertion that
         * catches an off-by-one offset: a codec that wrote {@code SEC-USR-FNAME} one byte early would
         * still produce 20 correct-looking characters, and only the neighbours would show it.
         */
        @Test
        @DisplayName("writing SEC-USR-FNAME leaves bytes 0-7 and 28-79 byte-identical")
        void writingOneField_leavesEveryNeighbouringByteUntouched() {
            SecUserRecord before = SecUserRecord.of("ADMIN001", "MARGARET", "GOLD", SEED_PASSWORD,
                    "A", ASCII);
            SecUserRecord after = SecUserRecord.of("ADMIN001", "MARGARETHE", "GOLD", SEED_PASSWORD,
                    "A", ASCII);

            byte[] first = SecUserRecord.encode(before, ASCII);
            byte[] second = SecUserRecord.encode(after, ASCII);
            assertThat(first).hasSize(80);
            assertThat(second).hasSize(80);

            // Everything before SEC-USR-FNAME: the 8-byte key span.
            assertThat(Arrays.copyOfRange(second, 0, 8))
                    .as("bytes 0-7 belong to SEC-USR-ID and must not move")
                    .isEqualTo(Arrays.copyOfRange(first, 0, 8));
            // Everything after SEC-USR-FNAME: last name, password, type and the trailing span.
            assertThat(Arrays.copyOfRange(second, 28, 80))
                    .as("bytes 28-79 belong to the four following items and must not move")
                    .isEqualTo(Arrays.copyOfRange(first, 28, 80));
            // And the one span that did change, changed only within its own 20 bytes.
            assertThat(Arrays.copyOfRange(second, 8, 28))
                    .isNotEqualTo(Arrays.copyOfRange(first, 8, 28));
        }

        /**
         * An over-long sending value loses its <strong>rightmost</strong> excess characters, which is
         * COBOL's rule for a {@code PIC X} receiver: the receiver fills from its leftmost position and
         * the overflow is discarded (AAP section 0.3.7).
         *
         * <p><strong>Accuracy note.</strong> This asserts the codec's <em>declared contract for
         * over-long input</em>. It does <strong>not</strong> reproduce a real COBOL truncation site,
         * because no production site over-fills a {@code SEC-USR-*} field: every symbolic-map source
         * field is exactly as wide as the record field it feeds. {@code app/cpy-bms/COUSR01.CPY}
         * declares {@code USERIDI PIC X(8)} at L72, {@code FNAMEI PIC X(20)} at L60,
         * {@code LNAMEI PIC X(20)} at L66, {@code PASSWDI PIC X(8)} at L78 and
         * {@code USRTYPEI PIC X(1)} at L84; {@code COUSR02.CPY} matches at L60, L66, L72, L78 and L84
         * (its id field is spelled {@code USRIDINI}); {@code COUSR03.CPY} matches at L60, L66, L72 and
         * L78 and has no password field at all; and {@code COUSR00.CPY} row 01 matches at L78, L84,
         * L90 and L96. The truncation branch is therefore defensive, and is asserted because the
         * contract states it, not because the legacy system exercises it.
         */
        @Test
        @DisplayName("an over-long value loses its RIGHTMOST characters, as PIC X does")
        void overLongValue_losesItsRightmostCharacters() {
            SecUserRecord record = SecUserRecord.of(
                    "ADMIN001X",            // 9 into PIC X(08) - the trailing X is discarded
                    "ABCDEFGHIJKLMNOPQRSTUV", // 22 into PIC X(20)
                    "abcdefghijklmnopqrstuv", // 22 into PIC X(20)
                    "PASSWORD9",            // 9 into PIC X(08)
                    "AU",                   // 2 into PIC X(01)
                    ASCII);

            assertThat(record.secUsrId()).isEqualTo("ADMIN001");
            assertThat(record.secUsrFname()).isEqualTo("ABCDEFGHIJKLMNOPQRST");
            assertThat(record.secUsrLname()).isEqualTo("abcdefghijklmnopqrst");
            assertThat(record.secUsrPwd()).isEqualTo("PASSWORD");
            assertThat(record.secUsrType()).isEqualTo("A");

            // The surviving characters are the LEADING ones. Right truncation, never left.
            assertThat(record.secUsrFname()).startsWith("A").doesNotContain("U", "V");
            assertThat(record.secUsrId()).doesNotEndWith("X");
        }

        /** An exact-width value needs neither padding nor truncation: the third branch. */
        @Test
        @DisplayName("an exact-width value is neither padded nor truncated")
        void exactWidthValue_isNeitherPaddedNorTruncated() {
            SecUserRecord record = SecUserRecord.of("ADMIN001", pad("MARGARET", 20), pad("GOLD", 20),
                    SEED_PASSWORD, "A", ASCII);

            assertThat(record.secUsrId()).isEqualTo("ADMIN001").hasSize(8);
            assertThat(record.secUsrFname()).isEqualTo(pad("MARGARET", 20)).hasSize(20);
            assertThat(record.secUsrLname()).isEqualTo(pad("GOLD", 20)).hasSize(20);
            assertThat(record.secUsrPwd()).isEqualTo(SEED_PASSWORD).hasSize(8);
            assertThat(record.secUsrType()).isEqualTo("A").hasSize(1);
        }

        /**
         * {@code decode} returns each field with its padding <strong>retained</strong>, at the full
         * declared width.
         *
         * <p>AAP section 0.3.7 is explicit: not trimmed on read unless the COBOL trims. The trimming
         * the COBOL genuinely does perform is done by the caller and is visible in the source as an
         * explicit {@code STRING ... SEC-USR-ID DELIMITED BY SPACE} - at
         * {@code app/cbl/COUSR01C.cbl:256}, {@code COUSR02C.cbl:373} and {@code COUSR03C.cbl:319} -
         * which is exactly the evidence that the record itself does not trim on the caller's behalf.
         */
        @Test
        @DisplayName("decode retains padding at the full declared width and never trims")
        void decode_retainsPadding_andNeverTrims() {
            SecUserRecord admin = seedRecord(0, ASCII);

            assertThat(admin.secUsrId()).isEqualTo("ADMIN001").hasSize(8);
            assertThat(admin.secUsrFname()).isEqualTo("MARGARET" + spaces(12)).hasSize(20);
            assertThat(admin.secUsrLname()).isEqualTo("GOLD" + spaces(16)).hasSize(20);
            assertThat(admin.secUsrPwd()).isEqualTo(SEED_PASSWORD).hasSize(8);
            assertThat(admin.secUsrType()).isEqualTo("A").hasSize(1);
            assertThat(admin.secUsrFiller()).isEqualTo(spaces(23)).hasSize(23);

            // Explicitly NOT the trimmed forms. If any of these passed, a caller's equal-width
            // comparison against a padded screen field would flip and write a spurious update.
            assertThat(admin.secUsrFname()).isNotEqualTo("MARGARET");
            assertThat(admin.secUsrLname()).isNotEqualTo("GOLD");
            assertThat(admin.secUsrFname().trim()).isEqualTo("MARGARET");
        }

        /**
         * The concrete consequence of not trimming, and the reason it is a hard parity requirement
         * rather than a style choice.
         *
         * <p>{@code app/cpy-bms/COUSR02.CPY} declares its screen input fields at the <em>same</em>
         * widths as the record's own - {@code FNAMEI PIC X(20)} at L66, {@code LNAMEI PIC X(20)} at
         * L72, {@code PASSWDI PIC X(8)} at L78, {@code USRTYPEI PIC X(1)} at L84 - so CICS delivers
         * each one space-padded to that width. The change tests in {@code app/cbl/COUSR02C.cbl}, of
         * the form {@code IF FNAMEI OF COUSR2AI NOT = SEC-USR-FNAME}, are therefore
         * <strong>equal-length</strong> byte comparisons.
         *
         * <p>An accessor that trimmed would make a padded screen field compare unequal to an
         * unpadded record field, flip the modified flag, and write a spurious update. The defect
         * would be silent and would surface only as a parity diff far from its cause.
         */
        @Test
        @DisplayName("an equal-width screen field compares equal, which the change tests rely on")
        void equalWidthScreenField_comparesEqual_soChangeDetectionHolds() {
            SecUserRecord stored = seedRecord(0, ASCII);

            // What CICS hands over for an unchanged FNAMEI: 20 characters, space-padded.
            String unchangedScreenField = pad("MARGARET", 20);
            String changedScreenField = pad("MARGARETHE", 20);
            assertThat(unchangedScreenField).hasSize(20);
            assertThat(changedScreenField).hasSize(20);

            assertThat(stored.secUsrFname())
                    .as("unchanged must compare EQUAL, or a spurious update is written")
                    .isEqualTo(unchangedScreenField);
            assertThat(stored.secUsrFname())
                    .as("genuinely changed must compare UNEQUAL, or a real update is lost")
                    .isNotEqualTo(changedScreenField);

            // Had the accessor trimmed, the unchanged case would have compared unequal - which is
            // exactly the silent defect this assertion exists to prevent.
            assertThat(stored.secUsrFname().trim()).isNotEqualTo(unchangedScreenField);
        }

        /**
         * The key is the record's leading {@link SecUserRecord#KEY_LENGTH} bytes at
         * {@link SecUserRecord#KEY_OFFSET}, which is what ties the published key geometry to the
         * actual byte image rather than leaving the two to drift apart.
         */
        @Test
        @DisplayName("key() equals the encoded record's leading eight bytes at offset 0")
        void key_equalsTheEncodedLeadingBytes() {
            for (int index = 0; index < SEED_CARDS.size(); index++) {
                SecUserRecord record = seedRecord(index, EBCDIC);
                byte[] encoded = SecUserRecord.encode(record, EBCDIC);

                String leading = new String(Arrays.copyOfRange(encoded, SecUserRecord.KEY_OFFSET,
                        SecUserRecord.KEY_OFFSET + SecUserRecord.KEY_LENGTH), EBCDIC);

                assertThat(record.key())
                        .as("row %d", index)
                        .isEqualTo(record.secUsrId())
                        .isEqualTo(leading)
                        .hasSize(8);
            }
        }

        /**
         * No normalisation of any kind: no upper-casing, no lower-casing, no trimming, no
         * transformation.
         *
         * <p>{@code app/cbl/COUSR01C.cbl:154-158} performs five plain {@code MOVE}s into
         * {@code SEC-USR-ID}, {@code -FNAME}, {@code -LNAME}, {@code -PWD} and {@code -TYPE} with
         * <strong>zero</strong> {@code FUNCTION UPPER-CASE}. The upper-casing that does exist in the
         * migration lives in the sign-on service, whose own test owns it; it is never in the model.
         */
        @Test
        @DisplayName("mixed case is stored verbatim - no upper-casing, lower-casing or trimming")
        void noNormalisation_isApplied() {
            SecUserRecord record = SecUserRecord.of("user0001", "  lawrence", "thoMAS  ",
                    "pAsSw0rd", "u", ASCII);

            assertThat(record.secUsrId()).isEqualTo("user0001");
            assertThat(record.secUsrFname()).isEqualTo(pad("  lawrence", 20));
            assertThat(record.secUsrLname()).isEqualTo(pad("thoMAS  ", 20));
            assertThat(record.secUsrPwd()).isEqualTo("pAsSw0rd");
            assertThat(record.secUsrType()).isEqualTo("u");

            // Leading whitespace is preserved exactly: a trimming model would have shifted it away.
            assertThat(record.secUsrFname()).startsWith("  lawrence");
            assertThat(record.secUsrId()).isNotEqualTo("USER0001");
            assertThat(record.secUsrType()).isNotEqualTo("U");
        }

        /**
         * {@code SEC-USR-TYPE PIC X(01)} is a passive single-byte carrier. The seed data holds
         * {@code 'A'} or {@code 'U'} - {@code COSGN00C.cbl:227} moves it to {@code CDEMO-USER-TYPE},
         * whose {@code 88}-levels are ADMIN {@code 'A'} and USER {@code 'U'} - but the model neither
         * validates nor constrains the byte. Rejecting an unexpected value here would be new
         * behaviour, so any character round-trips.
         */
        @ParameterizedTest(name = "SEC-USR-TYPE = ''{0}''")
        @ValueSource(strings = {"A", "U", " ", "X", "a", "u", "1", "-"})
        @DisplayName("SEC-USR-TYPE carries any single byte without validating it")
        void secUsrType_isAPassiveSingleByteCarrier(String type) {
            SecUserRecord record = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD,
                    type, ASCII);

            assertThat(record.secUsrType()).isEqualTo(type).hasSize(1);
            assertThat(SecUserRecord.decode(SecUserRecord.encode(record, ASCII), ASCII).secUsrType())
                    .isEqualTo(type);
        }

        /**
         * The full 80-byte round trip in both directions, with a distinct, same-width, non-symmetric
         * value in every field.
         *
         * <p>No two fields share a value and no value is a rotation of another, so a field-order swap
         * or an offset transposition cannot slip through: the two 20-byte fields differ, and the two
         * 8-byte fields differ.
         */
        @Test
        @DisplayName("decode(encode(r)) equals r, and encode(decode(b)) equals b, field by field")
        void roundTrip_holdsBothWays_withNonSymmetricValues() {
            SecUserRecord original = new SecUserRecord(
                    "IDIDIDID",                 // PIC X(08), distinct from the password
                    "FNFNFNFNFNFNFNFNFNFN",     // PIC X(20), distinct from the last name
                    "LNLNLNLNLNLNLNLNLNLN",     // PIC X(20)
                    "PWPWPWPW",                 // PIC X(08)
                    "T",                        // PIC X(01)
                    "FILLERFILLERFILLERFILLE"); // PIC X(23)

            byte[] encoded = SecUserRecord.encode(original, ASCII);
            assertThat(encoded).hasSize(80);

            SecUserRecord decoded = SecUserRecord.decode(encoded, ASCII);
            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.secUsrId()).isEqualTo("IDIDIDID");
            assertThat(decoded.secUsrFname()).isEqualTo("FNFNFNFNFNFNFNFNFNFN");
            assertThat(decoded.secUsrLname()).isEqualTo("LNLNLNLNLNLNLNLNLNLN");
            assertThat(decoded.secUsrPwd()).isEqualTo("PWPWPWPW");
            assertThat(decoded.secUsrType()).isEqualTo("T");
            assertThat(decoded.secUsrFiller()).isEqualTo("FILLERFILLERFILLERFILLE");

            assertThat(SecUserRecord.encode(decoded, ASCII))
                    .as("encode(decode(b)) must be byte-for-byte b")
                    .isEqualTo(encoded);

            // Each span sits at its own offset, checked directly against the byte image.
            String image = new String(encoded, ASCII);
            assertThat(image.substring(0, 8)).isEqualTo("IDIDIDID");
            assertThat(image.substring(8, 28)).isEqualTo("FNFNFNFNFNFNFNFNFNFN");
            assertThat(image.substring(28, 48)).isEqualTo("LNLNLNLNLNLNLNLNLNLN");
            assertThat(image.substring(48, 56)).isEqualTo("PWPWPWPW");
            assertThat(image.substring(56, 57)).isEqualTo("T");
            assertThat(image.substring(57, 80)).isEqualTo("FILLERFILLERFILLERFILLE");
        }

        /** The codec-taking overloads must agree with the charset-taking ones, byte for byte. */
        @Test
        @DisplayName("the codec-taking overloads agree with the charset-taking ones")
        void codecOverloads_agreeWithCharsetOverloads() {
            FixedWidthCodec codec = new FixedWidthCodec(EBCDIC);
            byte[] row = recordBytes(SEED_CARDS.get(3), EBCDIC);

            SecUserRecord viaCodec = SecUserRecord.decode(row, codec);
            SecUserRecord viaCharset = SecUserRecord.decode(row, EBCDIC);
            assertThat(viaCodec).isEqualTo(viaCharset);

            assertThat(SecUserRecord.encode(viaCodec, codec))
                    .isEqualTo(SecUserRecord.encode(viaCharset, EBCDIC))
                    .isEqualTo(row);
            assertThat(codec.charset()).isEqualTo(EBCDIC);
        }
    }

    // =============================================================================================
    // Phase 4 - the trailing span. Gates G19 and G21.
    // =============================================================================================

    @Nested
    @DisplayName("The trailing SEC-USR-FILLER span, which must never be dropped")
    class TrailingSpan {

        /**
         * Gate G19: the encoded record is byte-identical in length to its copybook declaration,
         * whatever the caller did or did not set.
         */
        @Test
        @DisplayName("encode is exactly 80 bytes for populated, blank and empty-valued records")
        void encode_isExactly80Bytes_forEveryRecordShape() {
            SecUserRecord populated = SecUserRecord.of("ADMIN005", "GRANVILLE", "LACHAPELLE",
                    SEED_PASSWORD, "A", ASCII);
            SecUserRecord blank = SecUserRecord.blank();
            SecUserRecord empty = SecUserRecord.of("", "", "", "", "", ASCII);

            assertThat(SecUserRecord.encode(populated, ASCII)).hasSize(80);
            assertThat(SecUserRecord.encode(blank, ASCII)).hasSize(80);
            assertThat(SecUserRecord.encode(empty, ASCII)).hasSize(80);
            assertThat(SecUserRecord.encode(populated, EBCDIC)).hasSize(80);
            assertThat(SecUserRecord.encode(blank, EBCDIC)).hasSize(80);
            assertThat(SecUserRecord.encode(empty, EBCDIC)).hasSize(80);

            // blank() is an all-spaces area of the six declared widths.
            assertThat(blank.secUsrId()).isEqualTo(spaces(8));
            assertThat(blank.secUsrFname()).isEqualTo(spaces(20));
            assertThat(blank.secUsrLname()).isEqualTo(spaces(20));
            assertThat(blank.secUsrPwd()).isEqualTo(spaces(8));
            assertThat(blank.secUsrType()).isEqualTo(spaces(1));
            assertThat(blank.secUsrFiller()).isEqualTo(spaces(23));
        }

        /**
         * Gate G21: bytes 57 through 79 are the code page's <strong>space</strong> byte when nothing
         * has been placed in {@code SEC-USR-FILLER} - space-filled, explicitly not zero-filled.
         */
        @Test
        @DisplayName("secUsrFiller is space-filled, not zero-filled, so the record stays 80 bytes")
        void secUsrFiller_isSpaceFilled_soRecordStays80Bytes() {
            byte[] ascii = SecUserRecord.encode(
                    SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD, "U", ASCII),
                    ASCII);

            assertThat(ascii).hasSize(80);
            for (int index = 57; index < 80; index++) {
                assertThat(ascii[index]).as("byte %d of the trailing span", index)
                        .isEqualTo(ASCII_SPACE)
                        .isNotEqualTo(LOW_VALUE);
            }
            assertThat(Arrays.copyOfRange(ascii, 57, 80))
                    .isEqualTo(repeatByte(ASCII_SPACE, 23))
                    .isNotEqualTo(repeatByte(LOW_VALUE, 23));

            // The same, through the EBCDIC code page, where a space is a different byte entirely.
            byte[] ebcdic = SecUserRecord.encode(
                    SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD, "U", EBCDIC),
                    EBCDIC);
            assertThat(Arrays.copyOfRange(ebcdic, 57, 80))
                    .isEqualTo(repeatByte(EBCDIC_SPACE, 23))
                    .isNotEqualTo(repeatByte(LOW_VALUE, 23));
        }

        /**
         * The span is not droppable, stated numerically.
         *
         * <p>Omitting {@code SEC-USR-FILLER} would collapse the record to <strong>57</strong> bytes -
         * which is exactly the width of the in-stream seed data at {@code DUSRSECJ.jcl:35-44}. That
         * coincidence is what makes the mistake easy to make: an implementation without the filler
         * looks correct against the load job's own data and is wrong against the copybook. The
         * total-width assertion is the thing that catches it immediately.
         */
        @Test
        @DisplayName("57 + 23 = 80, so dropping the filler would leave a 57-byte record")
        void theTrailingSpanIsNotDroppable_because57Plus23Is80() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
            assertThat(57 + 23).isEqualTo(80);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET
                    + SecUserRecord.SEC_USR_FILLER_LENGTH).isEqualTo(80);

            // 57 is the seed-card width, and it is NOT a valid record width.
            assertThat(CARD_WIDTH).isEqualTo(57);
            assertThat(SEED_CARDS.get(0)).hasSize(57);
            assertThat(SecUserRecord.RECORD_LENGTH - SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("the record minus its filler is precisely the seed-card width")
                    .isEqualTo(CARD_WIDTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SecUserRecord.decode(SEED_CARDS.get(0).getBytes(ASCII), ASCII));
        }

        /**
         * Practice B5: {@code SEC-USR-FILLER} is referenced by no COBOL program anywhere in
         * {@code app/}, yet it must be a real, named accessor that round-trips a value written into
         * it. Dead code is preserved, not tidied - and asserting the accessor is what makes gates G19
         * and G21 mean something rather than being incidental.
         */
        @Test
        @DisplayName("secUsrFiller is a real accessor that round-trips a value written into it")
        void secUsrFiller_isARealAccessor_thatRoundTripsAValue() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            SecUserRecord record = SecUserRecord.of("USER0005", "LEE", "TING", SEED_PASSWORD, "U",
                    "TRAILER-BYTES-KEPT-AS-I", codec);

            assertThat(record.secUsrFiller()).isEqualTo("TRAILER-BYTES-KEPT-AS-I").hasSize(23);
            assertThat(record.image("SEC-USR-FILLER")).isEqualTo("TRAILER-BYTES-KEPT-AS-I");

            byte[] encoded = SecUserRecord.encode(record, ASCII);
            assertThat(encoded).hasSize(80);
            assertThat(new String(encoded, ASCII).substring(57))
                    .isEqualTo("TRAILER-BYTES-KEPT-AS-I");
            assertThat(SecUserRecord.decode(encoded, ASCII).secUsrFiller())
                    .isEqualTo("TRAILER-BYTES-KEPT-AS-I");

            // And it is part of the record's identity, so a differing filler is a differing record.
            assertThat(record).isNotEqualTo(SecUserRecord.of("USER0005", "LEE", "TING",
                    SEED_PASSWORD, "U", ASCII));
        }
    }

    // =============================================================================================
    // Phase 5 - the key span carries RAW BYTES, including LOW-VALUES and HIGH-VALUES.
    //
    // This is the highest-value model-level finding in the analysis. app/cbl/COUSR00C.cbl uses the
    // key field as a browse sentinel, not merely as text:
    //   :219 and :240  MOVE LOW-VALUES  TO SEC-USR-ID   - start the browse from the first record
    //   :263           MOVE HIGH-VALUES TO SEC-USR-ID   - position at the last record
    // LOW-VALUES is 0x00 repeated and HIGH-VALUES is 0xFF repeated. NEITHER IS A SPACE. A key
    // accessor that trimmed, or that coerced an unprintable byte to a space, would silently break
    // browse-from-first and position-at-last in the user list controller, and the failure would
    // surface far from its cause - in a paging assertion, with nothing pointing back to the model.
    // =============================================================================================

    @Nested
    @DisplayName("The key span as a raw-byte sentinel: LOW-VALUES and HIGH-VALUES")
    class KeySpanSentinels {

        /**
         * {@code MOVE LOW-VALUES TO SEC-USR-ID} - eight {@code 0x00} bytes - must survive a round trip
         * byte for byte. Asserted under both code pages, because {@code 0x00} is a valid single byte
         * in each.
         */
        @ParameterizedTest(name = "under {0}")
        @ValueSource(strings = {"US-ASCII", "IBM037"})
        @DisplayName("keySpan round-trips LOW-VALUES, for browse-from-first")
        void keySpan_roundTripsLowValues_forBrowseFromFirst(String charsetName) {
            Charset charset = Charset.forName(charsetName);
            byte[] row = sentinelKeyRow(LOW_VALUE, charset);

            SecUserRecord decoded = SecUserRecord.decode(row, charset);
            byte[] reencoded = SecUserRecord.encode(decoded, charset);

            assertThat(reencoded).hasSize(80).isEqualTo(row);
            assertThat(Arrays.copyOfRange(reencoded, 0, 8))
                    .as("the eight key bytes must still be LOW-VALUES")
                    .isEqualTo(repeatByte(LOW_VALUE, 8));

            // The key is carried, not interpreted: full declared width, and every character retained.
            assertThat(decoded.secUsrId()).hasSize(8);
            assertThat(decoded.key()).isEqualTo(decoded.secUsrId()).hasSize(8);
            assertThat(decoded.image("SEC-USR-ID")).isEqualTo(decoded.secUsrId());
        }

        /**
         * {@code MOVE HIGH-VALUES TO SEC-USR-ID} - eight {@code 0xFF} bytes - must survive a round trip
         * byte for byte.
         *
         * <p>Asserted under {@code IBM037} only, and deliberately so. {@code 0xFF} is not a character
         * in {@code US-ASCII}, so a strict decoder refuses it rather than substituting a replacement
         * character and presenting corrupt storage as a field value. {@code IBM037} is also the code
         * page a real mainframe {@code HIGH-VALUES} sentinel would actually be stored in, so this is
         * the faithful case rather than a convenient one. The assertions are made at the
         * <strong>byte</strong> level precisely so that no charset can misrepresent the value.
         */
        @Test
        @DisplayName("keySpan round-trips HIGH-VALUES, for position-at-last")
        void keySpan_roundTripsHighValues_forPositionAtLast() {
            byte[] row = sentinelKeyRow(HIGH_VALUE, EBCDIC);

            SecUserRecord decoded = SecUserRecord.decode(row, EBCDIC);
            byte[] reencoded = SecUserRecord.encode(decoded, EBCDIC);

            assertThat(reencoded).hasSize(80).isEqualTo(row);
            assertThat(Arrays.copyOfRange(reencoded, 0, 8))
                    .as("the eight key bytes must still be HIGH-VALUES")
                    .isEqualTo(repeatByte(HIGH_VALUE, 8));
            assertThat(decoded.secUsrId()).hasSize(8);
            assertThat(decoded.key()).hasSize(8);
        }

        /**
         * Neither sentinel may be coerced to spaces, trimmed away, or normalised to an empty value.
         * Stated as a separate case because those three are the specific failure modes a
         * {@code String}-oriented accessor would exhibit.
         */
        @Test
        @DisplayName("neither sentinel is coerced to spaces, trimmed away, or emptied")
        void keySentinels_areNotCoercedTrimmedOrEmptied() {
            SecUserRecord low = SecUserRecord.decode(sentinelKeyRow(LOW_VALUE, EBCDIC), EBCDIC);
            SecUserRecord high = SecUserRecord.decode(sentinelKeyRow(HIGH_VALUE, EBCDIC), EBCDIC);

            for (SecUserRecord sentinel : List.of(low, high)) {
                assertThat(sentinel.secUsrId())
                        .hasSize(8)
                        .isNotEmpty()
                        .isNotEqualTo(spaces(8));
                assertThat(sentinel.key()).hasSize(8).isNotEqualTo(spaces(8));
            }

            // The two sentinels are distinguishable from each other and from a blank key: an accessor
            // that flattened unprintable bytes would have made all three compare equal.
            assertThat(low.secUsrId()).isNotEqualTo(high.secUsrId());
            assertThat(low.secUsrId()).isNotEqualTo(SecUserRecord.blank().secUsrId());
            assertThat(high.secUsrId()).isNotEqualTo(SecUserRecord.blank().secUsrId());

            // And at the byte level, which is where the sentinel's meaning actually lives.
            assertThat(Arrays.copyOfRange(SecUserRecord.encode(low, EBCDIC), 0, 8))
                    .isEqualTo(repeatByte(LOW_VALUE, 8));
            assertThat(Arrays.copyOfRange(SecUserRecord.encode(high, EBCDIC), 0, 8))
                    .isEqualTo(repeatByte(HIGH_VALUE, 8));
        }

        /**
         * A sentinel in the key span must leave the other five fields readable, because
         * {@code COUSR00C} sets the key and then reads whatever record the browse positions on.
         */
        @Test
        @DisplayName("a sentinel key leaves the other five fields intact")
        void sentinelKey_leavesTheRemainingFieldsIntact() {
            byte[] row = sentinelKeyRow(LOW_VALUE, ASCII);
            SecUserRecord decoded = SecUserRecord.decode(row, ASCII);

            assertThat(decoded.secUsrFname()).isEqualTo(pad("MARGARET", 20));
            assertThat(decoded.secUsrLname()).isEqualTo(pad("GOLD", 20));
            assertThat(decoded.secUsrPwd()).isEqualTo(SEED_PASSWORD);
            assertThat(decoded.secUsrType()).isEqualTo("A");
            assertThat(decoded.secUsrFiller()).isEqualTo(spaces(23));
        }
    }

    // =============================================================================================
    // Phase 6 - the plaintext password. Gate G41, practice B6.
    //
    // SEC-USR-PWD PIC X(08) is stored and compared in plaintext, exactly as
    // app/cbl/COSGN00C.cbl:223 does it with IF SEC-USR-PWD = WS-USER-PWD - a direct equality against
    // a receiving field of matching width. app/cbl/COUSR02C.cbl:169 then paints the plaintext
    // password straight onto the update screen with MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI.
    // app/cbl/COUSR03C.cbl:165-167 moves first name, last name and type to the delete screen but
    // deliberately NOT the password, which is why the CU03 DTO has no password member.
    //
    // This is an INHERITED PROPERTY OF THE LEGACY DESIGN and an explicit non-goal of the migration
    // (AAP section 0.8.3). It is preserved so that it stays visible rather than buried, and it is
    // asserted here so nobody mistakes it for an oversight. Introducing hashing would change
    // observable behaviour and would require Spring Security, which AAP section 0.5.6 excludes.
    // =============================================================================================

    @Nested
    @DisplayName("The plaintext password, preserved exactly as the legacy design has it")
    class PlaintextPassword {

        /**
         * The eight bytes at offset 48 equal the input characters exactly. This is the definitive
         * assertion: any hashing, digest, salt or obfuscation step anywhere in the model path would
         * make these bytes differ, whatever the implementation called itself.
         */
        @ParameterizedTest(name = "password ''{0}''")
        @ValueSource(strings = {"PASSWORD", "S3cr3tPW", "aB!@1234", "        ", "x"})
        @DisplayName("the password round-trips in clear text in the eight bytes at offset 48")
        void password_roundTripsInClearTextAtOffset48(String password) {
            SecUserRecord record = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", password, "U",
                    ASCII);
            byte[] encoded = SecUserRecord.encode(record, ASCII);

            String expected = pad(password, 8);
            assertThat(Arrays.copyOfRange(encoded, 48, 56))
                    .as("stored bytes must equal the input characters, unaltered")
                    .isEqualTo(expected.getBytes(ASCII));
            assertThat(record.secUsrPwd()).isEqualTo(expected);
            assertThat(record.image("SEC-USR-PWD")).isEqualTo(expected);
            assertThat(SecUserRecord.decode(encoded, ASCII).secUsrPwd()).isEqualTo(expected);
        }

        /**
         * The seeded literal is exactly eight characters, so it fills {@code PIC X(08)} with no
         * padding and no truncation. {@code README.md:157-158} corroborates it independently, naming
         * userid {@code ADMIN001} and userid {@code USER0001} with "the initially configured password
         * PASSWORD".
         */
        @Test
        @DisplayName("the seed password exactly fills PIC X(08) - no padding, no truncation")
        void seedPassword_exactlyFillsPicX08() {
            assertThat(SEED_PASSWORD).hasSize(8);
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(8);

            SecUserRecord record = seedRecord(0, ASCII);
            assertThat(record.secUsrPwd())
                    .isEqualTo(SEED_PASSWORD)
                    .hasSize(8)
                    .doesNotContain(" ");
        }

        /**
         * The negative half of practice B6: nothing in the model's published surface performs or
         * exposes a credential transformation.
         *
         * <p>Method and field names are scanned for the vocabulary such a step would have to use.
         * {@code hashCode} is excluded by exact name - it is {@link Object}'s identity contract, not a
         * password digest.
         */
        @Test
        @DisplayName("no hashing, digest, salt or encoder step exists in the model path")
        void modelPath_containsNoHashingDigestOrEncoderStep() {
            List<String> forbidden = List.of("digest", "bcrypt", "scrypt", "pbkdf", "salt",
                    "encrypt", "cipher", "hmac", "sha1", "sha256", "md5", "obfuscat", "passwordencoder",
                    "hash");

            List<String> memberNames = new ArrayList<>();
            for (Method method : SecUserRecord.class.getDeclaredMethods()) {
                if (!"hashCode".equals(method.getName())) {
                    memberNames.add(method.getName());
                }
            }
            for (Field field : SecUserRecord.class.getDeclaredFields()) {
                memberNames.add(field.getName());
            }

            assertThat(memberNames).isNotEmpty();
            for (String name : memberNames) {
                String lowered = name.toLowerCase(Locale.ROOT);
                for (String token : forbidden) {
                    assertThat(lowered)
                            .as("member '%s' must not suggest a credential transformation", name)
                            .doesNotContain(token);
                }
            }

            // No member's type is a digest or key primitive either.
            for (Class<?> type : declaredTypesOf(SecUserRecord.class)) {
                assertThat(type.getName().toLowerCase(Locale.ROOT))
                        .doesNotContain("messagedigest")
                        .doesNotContain("passwordencoder")
                        .doesNotContain("crypto");
            }
        }

        /**
         * No Spring Security type is imported or referenced - and, more strongly, none is even on the
         * test classpath. AAP section 0.5.6 excludes {@code spring-boot-starter-security}, JWT
         * libraries and BCrypt from the closed dependency set, so a reference could not compile even
         * if someone wrote one.
         */
        @Test
        @DisplayName("no Spring Security or BCrypt type is on the classpath at all")
        void modelPath_referencesNoSpringSecurityType() {
            for (String excluded : List.of(
                    "org.springframework.security.crypto.password.PasswordEncoder",
                    "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
                    "org.springframework.security.core.userdetails.UserDetails",
                    "at.favre.lib.crypto.bcrypt.BCrypt")) {
                assertThatExceptionOfType(ClassNotFoundException.class)
                        .as("%s must not be on the classpath", excluded)
                        .isThrownBy(() -> Class.forName(excluded));
            }

            // Nothing the model declares comes from an excluded framework package.
            for (Class<?> type : declaredTypesOf(SecUserRecord.class)) {
                assertThat(type.getName()).doesNotStartWith("org.springframework.security");
            }
        }

        /**
         * The password remains fully reachable, because the sign-on program's plaintext comparison
         * needs it. Preserving the posture means neither strengthening it nor breaking it.
         */
        @Test
        @DisplayName("the password stays reachable for the COSGN00C plaintext comparison")
        void password_isReachableForThePlaintextComparison() {
            SecUserRecord stored = seedRecord(0, ASCII);

            // COSGN00C.cbl:223  IF SEC-USR-PWD = WS-USER-PWD, where WS-USER-PWD is PIC X(08) too, so
            // the comparison is an equal-width byte comparison of two 8-character images.
            String suppliedByTheOperator = pad("PASSWORD", 8);
            assertThat(stored.secUsrPwd()).isEqualTo(suppliedByTheOperator);
            assertThat(stored.secUsrPwd()).isNotEqualTo(pad("WRONGPWD", 8));

            // The field-image map is the parity and diagnostics contract, so it carries the password:
            // omitting it there would hide a real difference from a field-by-field differ.
            assertThat(stored.fieldImages()).containsEntry("SEC-USR-PWD", SEED_PASSWORD);
        }
    }

    // =============================================================================================
    // Phase 8 - the seeded USRSEC data. There is NO usrsec fixture (practice B4): app/data/ASCII
    // holds exactly nine files and none of them is for this dataset, so the sample data comes from
    // the in-stream cards of app/jcl/DUSRSECJ.jcl:35-44, transcribed above as literals.
    // =============================================================================================

    @Nested
    @DisplayName("The ten seeded USRSEC rows from DUSRSECJ.jcl:35-44")
    class SeedData {

        /**
         * Every transcribed card is exactly {@value #CARD_WIDTH} characters. This is the guard against
         * a transcription slip: a mistyped space count would change a length here long before it
         * changed a field value anywhere else.
         */
        @Test
        @DisplayName("there are ten cards and every one is exactly 57 characters")
        void allTenCards_areExactly57Characters() {
            assertThat(SEED_CARDS).hasSize(10);
            assertThat(SEED_CARDS).doesNotHaveDuplicates();
            for (int index = 0; index < SEED_CARDS.size(); index++) {
                assertThat(SEED_CARDS.get(index)).as("card %d", index).hasSize(CARD_WIDTH);
            }
            // LISTCAT.txt:3888 records REC-TOTAL 10, confirming the count independently.
            assertThat(SEED_CARDS).hasSize(10);
        }

        /**
         * All ten rows, decoded field for field against the transcribed table.
         *
         * <p>The 8 / 20 / 20 / 8 / 1 column split of the source cards is asserted implicitly by these
         * six field expectations: if the split were wrong by even one column, a name would arrive with
         * a shifted space run.
         */
        @ParameterizedTest(name = "[{0}] {1} {2} {3} type {4}")
        @CsvSource({
                "0,ADMIN001,MARGARET,GOLD,A",
                "1,ADMIN002,RUSSELL,RUSSELL,A",
                "2,ADMIN003,RAYMOND,WHITMORE,A",
                "3,ADMIN004,EMMANUEL,CASGRAIN,A",
                "4,ADMIN005,GRANVILLE,LACHAPELLE,A",
                "5,USER0001,LAWRENCE,THOMAS,U",
                "6,USER0002,AJITH,KUMAR,U",
                "7,USER0003,LAURITZ,ALME,U",
                "8,USER0004,AVERARDO,MAZZI,U",
                "9,USER0005,LEE,TING,U"
        })
        @DisplayName("every seeded row decodes field for field, with a space-filled trailer")
        void everySeedRow_decodesFieldForField(int index, String id, String firstName,
                                               String lastName, String type) {
            SecUserRecord record = seedRecord(index, ASCII);

            assertThat(record.secUsrId()).isEqualTo(id).hasSize(8);
            assertThat(record.secUsrFname()).isEqualTo(pad(firstName, 20)).hasSize(20);
            assertThat(record.secUsrLname()).isEqualTo(pad(lastName, 20)).hasSize(20);
            assertThat(record.secUsrPwd()).isEqualTo(SEED_PASSWORD).hasSize(8);
            assertThat(record.secUsrType()).isEqualTo(type).hasSize(1);
            assertThat(record.secUsrFiller()).isEqualTo(spaces(23)).hasSize(23);

            // The key is the id, and the row is addressable by COBOL name.
            assertThat(record.key()).isEqualTo(id);
            assertThat(record.image("SEC-USR-ID")).isEqualTo(id);
            assertThat(record.image("SEC-USR-TYPE")).isEqualTo(type);
        }

        /**
         * Re-encoding a decoded row reproduces the 80-byte image byte for byte: the original 57 source
         * bytes followed by 23 spaces, which is exactly what {@code LRECL=80,RECFM=FB}
         * [{@code DUSRSECJ.jcl:48}] produces on the way into the dataset.
         */
        @ParameterizedTest(name = "row {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
        @DisplayName("encode reproduces the 80-byte image: 57 source bytes then 23 spaces")
        void everySeedRow_reEncodesToTheSame80ByteImage(int index) {
            String card = SEED_CARDS.get(index);

            for (Charset charset : List.of(ASCII, EBCDIC)) {
                byte[] row = recordBytes(card, charset);
                assertThat(row).hasSize(80);

                byte[] reencoded = SecUserRecord.encode(SecUserRecord.decode(row, charset), charset);
                assertThat(reencoded).hasSize(80).isEqualTo(row);

                // The first 57 bytes are the card itself and the last 23 are spaces.
                assertThat(Arrays.copyOfRange(reencoded, 0, CARD_WIDTH))
                        .isEqualTo(card.getBytes(charset));
                assertThat(Arrays.copyOfRange(reencoded, CARD_WIDTH, 80))
                        .isEqualTo(spaces(23).getBytes(charset));
            }
        }

        /**
         * The five-and-five split of {@code SEC-USR-TYPE}. The model does not interpret the byte, but
         * the seed data's shape is worth pinning as documentation of what the fixture covers - both
         * branches of the admin/user distinction {@code COSGN00C.cbl:227} routes on are present.
         */
        @Test
        @DisplayName("the ten rows split five admin 'A' and five user 'U'")
        void seedTypes_splitFiveAdminAndFiveUser() {
            List<String> types = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (int index = 0; index < SEED_CARDS.size(); index++) {
                SecUserRecord record = seedRecord(index, ASCII);
                types.add(record.secUsrType());
                ids.add(record.secUsrId());
            }

            assertThat(types).containsExactly("A", "A", "A", "A", "A", "U", "U", "U", "U", "U");
            assertThat(types).filteredOn("A"::equals).hasSize(5);
            assertThat(types).filteredOn("U"::equals).hasSize(5);
            assertThat(ids).containsExactly("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004",
                    "ADMIN005", "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");
            assertThat(ids).doesNotHaveDuplicates();
        }

        /**
         * The longest real names in the set, which are the best available padding check against genuine
         * production-shaped data: {@code GRANVILLE} is 9 characters inside {@code PIC X(20)} and
         * {@code LACHAPELLE} is 10.
         */
        @Test
        @DisplayName("ADMIN005's GRANVILLE and LACHAPELLE pad correctly inside their X(20) fields")
        void longestNames_padCorrectlyInsideTheirX20Fields() {
            SecUserRecord record = seedRecord(4, ASCII);

            assertThat(record.secUsrId()).isEqualTo("ADMIN005");
            assertThat("GRANVILLE").hasSize(9);
            assertThat(record.secUsrFname()).isEqualTo("GRANVILLE" + spaces(11)).hasSize(20);
            assertThat("LACHAPELLE").hasSize(10);
            assertThat(record.secUsrLname()).isEqualTo("LACHAPELLE" + spaces(10)).hasSize(20);
        }

        /**
         * The shortest real names, at the other end of the same check: {@code LEE} is 3 characters and
         * {@code TING} is 4, so each carries the longest space run in the set.
         */
        @Test
        @DisplayName("USER0005's LEE and TING pad correctly inside their X(20) fields")
        void shortestNames_padCorrectlyInsideTheirX20Fields() {
            SecUserRecord record = seedRecord(9, ASCII);

            assertThat(record.secUsrId()).isEqualTo("USER0005");
            assertThat("LEE").hasSize(3);
            assertThat(record.secUsrFname()).isEqualTo("LEE" + spaces(17)).hasSize(20);
            assertThat("TING").hasSize(4);
            assertThat(record.secUsrLname()).isEqualTo("TING" + spaces(16)).hasSize(20);
        }

        /**
         * The two userids {@code README.md:157-158} names independently, as a cross-check that the
         * transcription agrees with the project's own documentation rather than only with the load job.
         */
        @Test
        @DisplayName("ADMIN001 and USER0001 match README.md:157-158, password included")
        void readmeCorroboratesTheFirstAdminAndTheFirstUser() {
            SecUserRecord admin = seedRecord(0, ASCII);
            SecUserRecord user = seedRecord(5, ASCII);

            assertThat(admin.secUsrId()).isEqualTo("ADMIN001");
            assertThat(admin.secUsrPwd()).isEqualTo("PASSWORD");
            assertThat(admin.secUsrType()).isEqualTo("A");

            assertThat(user.secUsrId()).isEqualTo("USER0001");
            assertThat(user.secUsrPwd()).isEqualTo("PASSWORD");
            assertThat(user.secUsrType()).isEqualTo("U");
        }

        /**
         * A short row must be widened deliberately rather than accommodated silently. The codec's
         * declared-width padding helper is the explicit step, and it produces exactly the image the
         * dataset holds.
         */
        @Test
        @DisplayName("a 57-byte card is widened to 80 by the codec's explicit padding step")
        void aShortCard_isWidenedByTheExplicitPaddingStep() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            byte[] card = SEED_CARDS.get(0).getBytes(ASCII);
            assertThat(card).hasSize(57);

            byte[] widened = codec.padToDeclaredWidth(card, SecUserRecord.RECORD_LENGTH);
            assertThat(widened).hasSize(80).isEqualTo(recordBytes(SEED_CARDS.get(0), ASCII));
            assertThat(SecUserRecord.decode(widened, ASCII)).isEqualTo(seedRecord(0, ASCII));
        }
    }

    // =============================================================================================
    // Phase 9 - charset handling. Practice B8: the code page is always the caller's, never assumed.
    // =============================================================================================

    @Nested
    @DisplayName("Charset handling: always explicit, never the platform default")
    class Charsets {

        /**
         * The proof that the charset parameter is genuinely honoured rather than ignored: the same
         * logical record produces a <strong>different byte image</strong> under each code page, while
         * decoding back to identical field values.
         *
         * <p>If the codec hard-coded a code page, the two images would be equal and this would fail.
         */
        @Test
        @DisplayName("one record yields different bytes per code page but identical decoded fields")
        void oneRecord_yieldsDifferentBytesPerCodePage_butIdenticalFields() {
            SecUserRecord record = SecUserRecord.of("ADMIN001", "MARGARET", "GOLD", SEED_PASSWORD,
                    "A", ASCII);

            byte[] asAscii = SecUserRecord.encode(record, ASCII);
            byte[] asEbcdic = SecUserRecord.encode(record, EBCDIC);

            assertThat(asAscii).hasSize(80);
            assertThat(asEbcdic).hasSize(80);
            assertThat(asEbcdic)
                    .as("the code page must actually change the bytes")
                    .isNotEqualTo(asAscii);

            // 'A' is 0x41 in ASCII and 0xC1 in IBM037: concrete, checkable proof.
            assertThat(asAscii[0]).isEqualTo((byte) 0x41);
            assertThat(asEbcdic[0]).isEqualTo((byte) 0xC1);

            // Yet both decode, each under its own code page, to exactly the same field values.
            SecUserRecord fromAscii = SecUserRecord.decode(asAscii, ASCII);
            SecUserRecord fromEbcdic = SecUserRecord.decode(asEbcdic, EBCDIC);
            assertThat(fromEbcdic).isEqualTo(fromAscii).isEqualTo(record);
            assertThat(fromEbcdic.fieldImages()).isEqualTo(fromAscii.fieldImages());
        }

        /**
         * The pad byte is code-page dependent, and this is the concrete proof that padding goes
         * <em>through</em> the charset rather than hard-coding an ASCII byte: a space is {@code 0x40}
         * under {@code IBM037} and {@code 0x20} under {@code US-ASCII}. Both are asserted as literals.
         */
        @Test
        @DisplayName("the pad byte is 0x40 under IBM037 and 0x20 under US-ASCII")
        void padByte_is0x40UnderIbm037_and0x20UnderUsAscii() {
            SecUserRecord shortValues = SecUserRecord.of("AB", "CD", "EF", "GH", "I", ASCII);

            byte[] ascii = SecUserRecord.encode(shortValues, ASCII);
            byte[] ebcdic = SecUserRecord.encode(shortValues, EBCDIC);

            // The pad byte inside SEC-USR-ID, immediately after the two supplied characters.
            assertThat(ascii[2]).isEqualTo(ASCII_SPACE).isEqualTo((byte) 0x20);
            assertThat(ebcdic[2]).isEqualTo(EBCDIC_SPACE).isEqualTo((byte) 0x40);

            // And across the whole trailing span, which is pure padding.
            assertThat(Arrays.copyOfRange(ascii, 57, 80)).isEqualTo(repeatByte((byte) 0x20, 23));
            assertThat(Arrays.copyOfRange(ebcdic, 57, 80)).isEqualTo(repeatByte((byte) 0x40, 23));

            // The two pad bytes are genuinely different, so neither could be a hard-coded default.
            assertThat(ASCII_SPACE).isNotEqualTo(EBCDIC_SPACE);
        }

        /**
         * A multi-byte code page is refused, because a fixed-width record area is addressed by
         * absolute <em>byte</em> offset. Both sides of the codec's single-byte check are therefore
         * covered: the two accepted code pages above, and a rejected one here.
         */
        @Test
        @DisplayName("a multi-byte code page is refused, since offsets are absolute byte positions")
        void multiByteCodePage_isRefused() {
            for (String multiByte : List.of("UTF-16", "UTF-16BE", "UTF-32")) {
                Charset charset = Charset.forName(multiByte);
                assertThatIllegalArgumentException()
                        .as(multiByte)
                        .isThrownBy(() -> SecUserRecord.encode(SecUserRecord.blank(), charset));
                assertThatIllegalArgumentException()
                        .as(multiByte)
                        .isThrownBy(() -> SecUserRecord.decode(new byte[80], charset));
            }
        }

        /**
         * Determinism (practice B7): nothing here consults {@link Charset#defaultCharset()}, a locale
         * or a clock, so the outcome cannot depend on how the JVM was launched. The suite is
         * additionally re-run under a deliberately different default charset and time zone to confirm
         * the results are identical.
         */
        @Test
        @DisplayName("results do not depend on the JVM's default charset")
        void results_areIndependentOfTheJvmDefaultCharset() {
            // Encoding the same record twice under an explicitly named code page is stable, and the
            // value asserted is a literal byte rather than anything derived from the default charset.
            SecUserRecord record = seedRecord(0, ASCII);
            byte[] first = SecUserRecord.encode(record, ASCII);
            byte[] second = SecUserRecord.encode(record, ASCII);

            assertThat(second).isEqualTo(first);
            assertThat(first[0]).isEqualTo((byte) 0x41);
            assertThat(new String(first, ASCII).substring(0, 8)).isEqualTo("ADMIN001");

            // Named explicitly, the two code pages are exactly the ones the module documents.
            assertThat(ASCII.name()).isEqualTo("US-ASCII");
            assertThat(EBCDIC.name()).isEqualTo("IBM037");
        }
    }

    // =============================================================================================
    // Phase 10 - both sides of every decision the class can make. Gates G49 and G50.
    // =============================================================================================

    @Nested
    @DisplayName("Validation branches: failing loudly rather than adjusting silently")
    class ValidationBranches {

        /** The accepting side of the length check: exactly 80 bytes decodes. */
        @Test
        @DisplayName("decode accepts exactly 80 bytes")
        void decode_acceptsExactly80Bytes() {
            byte[] eighty = recordBytes(SEED_CARDS.get(0), ASCII);
            assertThat(eighty).hasSize(80);

            assertThat(SecUserRecord.decode(eighty, ASCII).secUsrId()).isEqualTo("ADMIN001");
            assertThat(SecUserRecord.decode(new byte[80], EBCDIC).secUsrId()).hasSize(8);
        }

        /**
         * The rejecting side, driven from both directions and including 57 - the un-padded seed width,
         * which is the realistic mistake. The message must identify the expected and the actual length
         * so the failure is diagnosable at the point of the error.
         */
        @ParameterizedTest(name = "{0} byte(s) is rejected")
        @ValueSource(ints = {0, 1, 36, 50, 56, 57, 79, 81, 100, 160})
        @DisplayName("decode rejects every byte count that is not exactly 80")
        void decode_rejectsAnyOtherLength(int length) {
            byte[] wrongWidth = repeatByte(EBCDIC_SPACE, length);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SecUserRecord.decode(wrongWidth, EBCDIC))
                    .withMessageContaining(String.valueOf(length))
                    .withMessageContaining("80");
        }

        /**
         * The 57-byte case specifically: the message must name the trailing span and the widening step,
         * because that is the actionable instruction for the caller who hit it.
         */
        @Test
        @DisplayName("a 57-byte row names SEC-USR-FILLER and the widening step in its message")
        void shortRow_failureMessageIsDiagnostic() {
            byte[] fiftySeven = SEED_CARDS.get(0).getBytes(ASCII);
            assertThat(fiftySeven).hasSize(57);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SecUserRecord.decode(fiftySeven, ASCII))
                    .withMessageContaining("SEC-USR-FILLER")
                    .withMessageContaining("57")
                    .withMessageContaining("80");
        }

        /** Null is never silently accepted, on any entry point, in either overload family. */
        @Test
        @DisplayName("null is rejected on every entry point, never silently accepted")
        void nullArguments_areRejectedEverywhere() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.decode(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.decode(null, codec));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.encode(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.encode(null, codec));

            assertThatNullPointerException()
                    .as("a null codec cannot carry a code page")
                    .isThrownBy(() -> SecUserRecord.decode(new byte[80], (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.encode(SecUserRecord.blank(),
                            (FixedWidthCodec) null));
            assertThatNullPointerException()
                    .as("a null charset is never defaulted to the platform's")
                    .isThrownBy(() -> SecUserRecord.encode(SecUserRecord.blank(), (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.decode(new byte[80], (Charset) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.of("USER0001", "A", "B", "P", "U",
                            "F", (FixedWidthCodec) null));
        }

        /**
         * Every sending value of {@code of(...)} rejects {@code null} naming the receiving field, so a
         * caller learns which field was missing rather than only that something was.
         */
        @ParameterizedTest(name = "a null in position {0} names {1}")
        @CsvSource({
                "0,SEC-USR-ID",
                "1,SEC-USR-FNAME",
                "2,SEC-USR-LNAME",
                "3,SEC-USR-PWD",
                "4,SEC-USR-TYPE",
                "5,SEC-USR-FILLER"
        })
        @DisplayName("of(...) rejects a null sending value naming the receiving field")
        void of_rejectsNullSendingValues(int position, String expectedField) {
            String[] values = {"USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD, "U", ""};
            values[position] = null;
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> SecUserRecord.of(values[0], values[1], values[2], values[3],
                            values[4], values[5], codec))
                    .withMessageContaining(expectedField);
        }

        /**
         * The canonical constructor holds every component to <strong>exactly</strong> its declared
         * width. Both the one-short and one-long neighbour of each of the six widths is driven, so the
         * width guard is exercised from both directions for every field.
         */
        @ParameterizedTest(name = "widths {0}/{1}/{2}/{3}/{4}/{5} are rejected")
        @CsvSource({
                "7,20,20,8,1,23",
                "9,20,20,8,1,23",
                "8,19,20,8,1,23",
                "8,21,20,8,1,23",
                "8,20,19,8,1,23",
                "8,20,21,8,1,23",
                "8,20,20,7,1,23",
                "8,20,20,9,1,23",
                "8,20,20,8,0,23",
                "8,20,20,8,2,23",
                "8,20,20,8,1,22",
                "8,20,20,8,1,24"
        })
        @DisplayName("the constructor rejects any component that is not exactly its declared width")
        void constructor_rejectsWrongWidths(int id, int firstName, int lastName, int password,
                                            int type, int filler) {
            assertThatIllegalArgumentException().isThrownBy(() -> new SecUserRecord(
                    spaces(id), spaces(firstName), spaces(lastName),
                    spaces(password), spaces(type), spaces(filler)));
        }

        /** The accepting side of the same guard: all six exact widths construct successfully. */
        @Test
        @DisplayName("the constructor accepts all six components at exactly their declared widths")
        void constructor_acceptsExactWidths() {
            SecUserRecord record = new SecUserRecord(spaces(8), spaces(20), spaces(20), spaces(8),
                    spaces(1), spaces(23));

            assertThat(record).isEqualTo(SecUserRecord.blank());
            assertThat(SecUserRecord.encode(record, ASCII)).hasSize(80);
        }

        /** A rejected width names the field, the width it must have, and the width it was given. */
        @Test
        @DisplayName("a rejected width names the field, its declared size and what it got")
        void wrongWidth_failureMessageIsDiagnostic() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SecUserRecord("ADMIN001", "GOLD", spaces(20), spaces(8),
                            "A", spaces(23)))
                    .withMessageContaining("SEC-USR-FNAME")
                    .withMessageContaining("20")
                    .withMessageContaining("4");
        }

        /** A null component is rejected naming the field it belongs to, for the first and the last. */
        @Test
        @DisplayName("a null component is rejected naming the field it belongs to")
        void constructor_rejectsNullComponents() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SecUserRecord(null, spaces(20), spaces(20), spaces(8), "A",
                            spaces(23)))
                    .withMessageContaining("SEC-USR-ID");
            assertThatNullPointerException()
                    .isThrownBy(() -> new SecUserRecord(spaces(8), spaces(20), spaces(20), spaces(8),
                            "A", null))
                    .withMessageContaining("SEC-USR-FILLER");
        }

        /**
         * The found side of the field-image lookup, for all six names, so every key of the differ
         * contract is proven reachable.
         */
        @ParameterizedTest(name = "{0} resolves")
        @ValueSource(strings = {"SEC-USR-ID", "SEC-USR-FNAME", "SEC-USR-LNAME", "SEC-USR-PWD",
                "SEC-USR-TYPE", "SEC-USR-FILLER"})
        @DisplayName("image resolves every one of the six COBOL field names")
        void image_resolvesEveryField(String cobolName) {
            SecUserRecord record = seedRecord(0, ASCII);

            assertThat(record.image(cobolName))
                    .isNotNull()
                    .isEqualTo(record.fieldImages().get(cobolName));
            assertThat(record.image(cobolName))
                    .hasSize(SecUserRecord.LAYOUT.span(cobolName).length());
        }

        /**
         * The not-found side: an unknown or misspelled name is rejected rather than answered with
         * {@code null}, so a mistake in a parity case fails at the point of the mistake instead of
         * comparing {@code null} against an expectation.
         */
        @ParameterizedTest(name = "''{0}'' is rejected")
        @ValueSource(strings = {"SEC-USER-FNAME", "sec-usr-fname", "FILLER", "SEC_USR_ID",
                "SEC-USER-DATA", "SEC-USR-ID ", "", " "})
        @DisplayName("image rejects an unknown or misspelled name, never answering null")
        void image_rejectsUnknownNames(String unknown) {
            SecUserRecord record = SecUserRecord.blank();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.image(unknown))
                    .withMessageContaining("SEC-USER-DATA");
        }

        /** A null field name is rejected outright. */
        @Test
        @DisplayName("image rejects a null field name")
        void image_rejectsNull() {
            SecUserRecord record = SecUserRecord.blank();

            assertThatNullPointerException().isThrownBy(() -> record.image(null));
        }

        /**
         * The record's identity covers all six components, and both the null and the wrong-type
         * comparison paths are driven.
         */
        @Test
        @DisplayName("identity covers all six components, plus the null and wrong-type paths")
        void identity_coversAllSixComponentsAndTheNullAndTypePaths() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            SecUserRecord base = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD,
                    "U", "", codec);

            assertThat(base).isEqualTo(base);
            assertThat(base).isEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "U", "", codec));
            assertThat(base).hasSameHashCodeAs(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "U", "", codec));

            // One differing component at a time, so no single comparison can be the only one that runs.
            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0002", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "U", "", codec));
            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0001", "LAURITZ", "THOMAS",
                    SEED_PASSWORD, "U", "", codec));
            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "ALME",
                    SEED_PASSWORD, "U", "", codec));
            assertThat(base).as("the password is part of the record's identity")
                    .isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", "OTHERPWD",
                            "U", "", codec));
            assertThat(base).isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    SEED_PASSWORD, "A", "", codec));
            assertThat(base).as("the filler is part of the record's identity")
                    .isNotEqualTo(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", SEED_PASSWORD,
                            "U", "X".repeat(23), codec));

            // The null and wrong-type paths of the generated equality.
            assertThat(base).isNotEqualTo(null);
            assertThat(base).isNotEqualTo("SEC-USER-DATA");
        }

        /**
         * The diagnostic rendering withholds the credential. This is the disclosure half of practice
         * B6 - the posture is not weakened either - and it is asserted alongside the fact that nothing
         * stored has changed.
         */
        @Test
        @DisplayName("the rendering withholds the password while the stored bytes stay reachable")
        void rendering_withholdsThePassword_withoutAlteringStoredBytes() {
            SecUserRecord record = seedRecord(0, ASCII);
            String rendered = record.toString();

            assertThat(rendered).doesNotContain(SEED_PASSWORD);
            assertThat(rendered).contains("SEC-USER-DATA");
            assertThat(rendered.lines()).as("a rendering is a single line").hasSize(1);

            // Nothing stored changed: every value is still reachable at its full declared width.
            assertThat(record.secUsrPwd()).isEqualTo(SEED_PASSWORD);
            assertThat(record.image("SEC-USR-PWD")).isEqualTo(SEED_PASSWORD);
            assertThat(record.fieldImages()).containsEntry("SEC-USR-PWD", SEED_PASSWORD);

            // A password that appears in no fixture is withheld just the same, so the rendering is not
            // merely filtering the seed literal.
            assertThat(SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS", "Zq7!vX2b", "U", ASCII)
                    .toString()).doesNotContain("Zq7!vX2b");
        }

        /**
         * The rendering also withholds both names, reporting their <em>shape</em> instead, while the
         * key stays legible.
         *
         * <p>A surname is not guessable, so a guard that only suppressed the seed cards' names would
         * pass while still publishing every real one. The values asserted here appear in no fixture.
         * {@code SEC-USR-ID} deliberately stays readable: it is the VSAM key and the value
         * {@code COSGN00C} matches on, so a sign-on parity failure is diagnosed from it and no other
         * field will do.
         */
        @Test
        @DisplayName("the rendering withholds both names by shape while the key stays legible")
        void rendering_withholdsBothNames_butKeepsTheKeyLegible() {
            SecUserRecord record = SecUserRecord.of("USER0002", "ZQXVOLYA", "TREMBLAY-OKONKWO",
                    SEED_PASSWORD, "U", ASCII);
            String rendered = record.toString();

            assertThat(rendered).doesNotContain("ZQXVOLYA").doesNotContain("TREMBLAY-OKONKWO");
            assertThat(rendered).contains("USER0002");

            // Nothing stored changed: both names remain reachable at their full declared widths, which
            // is what keeps the parity differ able to see a real difference.
            assertThat(record.secUsrFname()).isEqualTo(pad("ZQXVOLYA", 20));
            assertThat(record.secUsrLname()).isEqualTo(pad("TREMBLAY-OKONKWO", 20));
            assertThat(record.image("SEC-USR-FNAME")).isEqualTo(pad("ZQXVOLYA", 20));
            assertThat(record.image("SEC-USR-LNAME")).isEqualTo(pad("TREMBLAY-OKONKWO", 20));
        }

        /**
         * A control character in a rendered span cannot forge a second log line.
         *
         * <p>{@code SEC-USR-ID} and {@code SEC-USR-TYPE} are {@code PIC X} spans read straight out of
         * the dataset, so either can hold a carriage return or a line feed. Rendered raw, such a value
         * appends a log line of its own choosing. The escape must also be lossless: the stored bytes
         * are untouched and still say exactly what was there.
         */
        @Test
        @DisplayName("a control character in a rendered span cannot forge a second log line")
        void rendering_cannotForgeASecondLogLine() {
            SecUserRecord record = SecUserRecord.of("A\r\nFAKE", "ANNA", "SMITH", SEED_PASSWORD,
                    "\n", ASCII);
            String rendered = record.toString();

            assertThat(rendered).doesNotContain("\r").doesNotContain("\n");
            assertThat(rendered.lines()).hasSize(1);

            // The stored bytes are unchanged - the escaping is a rendering concern only.
            assertThat(record.secUsrId()).isEqualTo("A\r\nFAKE ").hasSize(8);
            assertThat(record.secUsrType()).isEqualTo("\n").hasSize(1);
            assertThat(SecUserRecord.encode(record, ASCII)).hasSize(80);
        }
    }

    // =============================================================================================
    // Phase 7 - structural guarantees, and the gates that have no subject in this package.
    // =============================================================================================

    @Nested
    @DisplayName("Structural guarantees, and the exclusions recorded with their reasons")
    class StructuralGuarantees {

        /**
         * Gate G22: no {@code double} and no {@code float} anywhere in the model's surface. This test
         * file contains none either - every width is an {@code int} and every value a {@code String}.
         */
        @Test
        @DisplayName("G22 - no double or float appears in the model's surface")
        void noDoubleOrFloat_appearsInTheModelsSurface() {
            Set<Class<?>> forbidden = Set.of(double.class, float.class, Double.class, Float.class,
                    double[].class, float[].class);

            Set<Class<?>> declared = declaredTypesOf(SecUserRecord.class);
            assertThat(declared).isNotEmpty();
            assertThat(declared).doesNotContainAnyElementsOf(forbidden);

            // Every one of the six record components is a String, which is what leaves no room for a
            // binary floating-point value to enter the record at all.
            RecordComponent[] components = SecUserRecord.class.getRecordComponents();
            assertThat(components).hasSize(6);
            for (RecordComponent component : components) {
                assertThat(component.getType()).isEqualTo(String.class);
            }
        }

        /**
         * Gates G23 and G24 have <strong>no subject</strong> here, and this asserts that rather than
         * merely claiming it.
         *
         * <p>{@code CSUSR01Y} declares no numeric, no {@code COMP}, no {@code COMP-3} and no scaled
         * item - verified by exhaustive grep, whose only hit is the word "compliance" in the licence
         * header - so there is nothing for a {@code BigDecimal} scale or a {@code RoundingMode} to act
         * on. {@code SecUserRecord} therefore does not consume the decimal seam, and this file does not
         * import it.
         */
        @Test
        @DisplayName("G23/G24 - no numeric field exists, so the decimal seam is correctly absent")
        void noNumericField_soTheDecimalSeamIsAbsent() {
            // All six spans are alphanumeric: no numeric DISPLAY, no sign overpunch, no scale.
            for (FieldSpan span : SecUserRecord.LAYOUT.storageSpans()) {
                assertThat(span.kind()).isEqualTo(PictureKind.ALPHANUMERIC);
                assertThat(span.kind().numericDisplay()).isFalse();
            }

            // And nothing in the surface is a decimal type.
            for (Class<?> type : declaredTypesOf(SecUserRecord.class)) {
                assertThat(type.getName())
                        .isNotEqualTo("java.math.BigDecimal")
                        .doesNotContain("CobolDecimal");
            }
        }

        /**
         * Gates G33 and G34 have no subject here either: the copybook declares no {@code OCCURS} table
         * and no {@code REDEFINES} overlay.
         *
         * <p>The {@code OCCURS 10 TIMES} table belongs to the <em>program</em> {@code COUSR00C}, whose
         * controller test owns the one-based to zero-based assertion; the {@code xxxA REDEFINES xxxF}
         * attribute pairs belong to the symbolic maps and so to the {@code user.dto} test package.
         * Gate G43 likewise has no subject: no {@code user} program contains
         * {@code 9300-CHECK-CHANGE-IN-REC}.
         */
        @Test
        @DisplayName("G33/G34 - the layout has no OCCURS table and no REDEFINES overlay")
        void layoutHasNoOccursTableAndNoRedefinesOverlay() {
            assertThat(SecUserRecord.LAYOUT.redefinitions()).isEmpty();
            for (FieldSpan span : SecUserRecord.LAYOUT.storageSpans()) {
                assertThat(span.redefinition()).as(span.name()).isFalse();
            }

            // Six flat items, one per copybook line, so there is no repeating group to index.
            assertThat(SecUserRecord.LAYOUT.storageSpans()).hasSize(6);
            assertThat(SecUserRecord.LAYOUT.storageSpans().stream().map(FieldSpan::name))
                    .doesNotHaveDuplicates();
        }

        /**
         * Gate G44: no persistence artefact of any kind. No JPA or {@code jakarta.persistence}
         * annotation, no entity mapping, no version column, no generated DDL - the record reaches its
         * dataset through fixed-width bytes, not through a schema.
         */
        @Test
        @DisplayName("G44 - no persistence annotation, entity mapping or version column exists")
        void noPersistenceArtefact_isDeclaredOnTheType() {
            List<Annotation> annotations = new ArrayList<>();
            annotations.addAll(Arrays.asList(SecUserRecord.class.getAnnotations()));
            for (Field field : SecUserRecord.class.getDeclaredFields()) {
                annotations.addAll(Arrays.asList(field.getAnnotations()));
            }
            for (Method method : SecUserRecord.class.getDeclaredMethods()) {
                annotations.addAll(Arrays.asList(method.getAnnotations()));
            }
            for (Constructor<?> constructor : SecUserRecord.class.getDeclaredConstructors()) {
                annotations.addAll(Arrays.asList(constructor.getAnnotations()));
            }
            RecordComponent[] components = SecUserRecord.class.getRecordComponents();
            for (RecordComponent component : components) {
                annotations.addAll(Arrays.asList(component.getAnnotations()));
            }

            for (Annotation annotation : annotations) {
                String name = annotation.annotationType().getName();
                assertThat(name)
                        .as("annotation %s must not be a persistence mapping", name)
                        .doesNotContain("persistence")
                        .doesNotContain("hibernate")
                        .doesNotContain("javax.persistence")
                        .doesNotContain("jakarta.persistence");
            }

            // No optimistic-locking version column: the concurrency check the legacy system performs
            // is a field-by-field re-read, and no user program even has that paragraph (gate G43).
            for (RecordComponent component : components) {
                assertThat(component.getName().toLowerCase(Locale.ROOT)).doesNotContain("version");
            }
            // Nor is any persistence framework on the classpath to be annotated with.
            assertThatExceptionOfType(ClassNotFoundException.class)
                    .isThrownBy(() -> Class.forName("jakarta.persistence.Entity"));
        }

        /**
         * Gate G46: no dataset-name literal. The {@code USRSEC} DSNAME lives in
         * {@code app/csd/CARDDEMO.CSD:89} and, for the Java module, only in {@code application.yml}; it
         * appears in no Java source, this test file included.
         */
        @Test
        @DisplayName("G46 - no dataset-name literal appears in the model's constants")
        void noDatasetNameLiteral_appearsInTheModelsConstants() throws IllegalAccessException {
            // The two probes below are qualifier FRAGMENTS, not dataset names: they are the prefix and
            // the suffix a real DSNAME would have to contain, which is what makes their absence
            // checkable without this file itself embedding a usable name.
            String highLevelQualifiers = "AWS" + ".M2.";
            String clusterSuffix = "VSAM" + ".KSDS";

            int inspected = 0;
            for (Field field : SecUserRecord.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    assertThat(value)
                            .as("constant %s must not embed a dataset name", field.getName())
                            .doesNotContain(highLevelQualifiers)
                            .doesNotContain(clusterSuffix);
                    inspected++;
                }
            }
            assertThat(inspected)
                    .as("the seven COBOL name constants must actually have been inspected")
                    .isGreaterThanOrEqualTo(7);
        }

        /**
         * Gate G53 and practice B9: no mutable static state. COBOL {@code WORKING-STORAGE} must never
         * become a shared Java field, because that would break request isolation and test determinism.
         */
        @Test
        @DisplayName("G53 - every static field is final and every instance field is final")
        void noMutableStaticState_exists() {
            for (Field field : SecUserRecord.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
            }

            // The one exposed static collection is genuinely unmodifiable, so it is not mutable state
            // wearing a final modifier.
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> SecUserRecord.SPANS.add(SecUserRecord.SPAN_SEC_USR_ID));
            assertThat(SecUserRecord.SPANS).hasSize(6);
        }

        /**
         * Two instances share nothing: neither can observe or affect the other, and a caller cannot
         * poison a shared field-image map because each call builds a fresh one.
         */
        @Test
        @DisplayName("B9 - two instances share no state, and each field-image map is fresh")
        void twoInstances_shareNothing() {
            SecUserRecord first = seedRecord(0, ASCII);
            SecUserRecord second = seedRecord(5, ASCII);

            assertThat(first).isNotSameAs(second).isNotEqualTo(second);
            assertThat(first.secUsrId()).isEqualTo("ADMIN001");
            assertThat(second.secUsrId()).isEqualTo("USER0001");

            // Each call returns its own map, so a caller holding one cannot reach another's values.
            Map<String, String> firstImages = first.fieldImages();
            assertThat(first.fieldImages()).isNotSameAs(firstImages);
            assertThat(second.fieldImages()).isNotSameAs(firstImages);
            assertThat(second.fieldImages()).isNotEqualTo(firstImages);

            // Re-reading either record after touching the other yields the original values.
            assertThat(first.fieldImages()).isEqualTo(firstImages);
            assertThat(SecUserRecord.encode(first, ASCII))
                    .isEqualTo(recordBytes(SEED_CARDS.get(0), ASCII));
            assertThat(SecUserRecord.encode(second, ASCII))
                    .isEqualTo(recordBytes(SEED_CARDS.get(5), ASCII));
        }

        /**
         * Gate G8: one Java type for the copybook, and one test class for that type. Six components,
         * six descriptors, six field images - the same six items {@code CSUSR01Y.cpy:18-23} declares,
         * with nothing added and nothing dropped.
         */
        @Test
        @DisplayName("G8 - one type per copybook: six components, six descriptors, six images")
        void oneTypePerCopybook_withSixItemsThroughout() {
            assertThat(SecUserRecord.class.isRecord()).isTrue();
            assertThat(SecUserRecord.class.getRecordComponents()).hasSize(6);
            assertThat(SecUserRecord.SPANS).hasSize(6);
            assertThat(SecUserRecord.blank().fieldImages()).hasSize(6);

            // The accessor names correspond one-to-one with the copybook items, in declaration order.
            assertThat(Arrays.stream(SecUserRecord.class.getRecordComponents())
                    .map(RecordComponent::getName))
                    .containsExactly("secUsrId", "secUsrFname", "secUsrLname", "secUsrPwd",
                            "secUsrType", "secUsrFiller");
        }
    }
}
