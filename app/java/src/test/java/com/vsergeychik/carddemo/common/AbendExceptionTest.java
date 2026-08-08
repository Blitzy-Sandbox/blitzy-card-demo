package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link AbendException}, the Java equivalent of the COBOL
 * {@code CALL 'CEE3ABD'} abend service.
 *
 * <p>These are plain JUnit 5 tests: no Spring context, no Mockito, no fixtures. The class under test
 * is a root of the dependency graph - it references nothing from Spring and nothing from any sibling
 * package - so it has no collaborators to stub and every decision in it is reachable by direct
 * construction.
 *
 * <h2>The nine COBOL abend sites this class replaces</h2>
 *
 * <p>{@code CALL 'CEE3ABD'} occurs exactly nine times in {@code app/cbl}, once per batch program.
 * The table is transcribed here as test data and is never read from {@code app/cbl} at run time: the
 * COBOL trees are the immutable parity oracle, and a test that reached into them would couple the
 * build to files it must never touch.</p>
 *
 * <table border="1">
 *   <caption>Every {@code CALL 'CEE3ABD'} site, with the paragraph that contains it</caption>
 *   <tr><th>#</th><th>Site</th><th>Paragraph</th><th>Sets {@code ABCODE} / {@code TIMING}?</th></tr>
 *   <tr><td>1</td><td>{@code app/cbl/CBACT01C.cbl:173}</td><td>{@code 9999-ABEND-PROGRAM}</td>
 *       <td>yes - {@code MOVE 0 TO TIMING}, {@code MOVE 999 TO ABCODE}</td></tr>
 *   <tr><td>2</td><td>{@code app/cbl/CBACT02C.cbl:158}</td><td>{@code 9999-ABEND-PROGRAM}</td>
 *       <td>yes - 0 / 999</td></tr>
 *   <tr><td>3</td><td>{@code app/cbl/CBACT03C.cbl:158}</td><td>{@code 9999-ABEND-PROGRAM}</td>
 *       <td>yes - 0 / 999</td></tr>
 *   <tr><td>4</td><td>{@code app/cbl/CBACT04C.cbl:632}</td><td>{@code 9999-ABEND-PROGRAM}</td>
 *       <td>yes - 0 / 999</td></tr>
 *   <tr><td>5</td><td>{@code app/cbl/CBCUS01C.cbl:158}</td><td>{@code Z-ABEND-PROGRAM}</td>
 *       <td>yes - 0 / 999</td></tr>
 *   <tr><td>6</td><td>{@code app/cbl/CBTRN01C.cbl:473}</td><td>{@code Z-ABEND-PROGRAM}</td>
 *       <td>yes - 0 / 999</td></tr>
 *   <tr><td>7</td><td>{@code app/cbl/CBTRN02C.cbl:711}</td><td>{@code 9999-ABEND-PROGRAM}</td>
 *       <td>yes - 0 / 999</td></tr>
 *   <tr><td>8</td><td>{@code app/cbl/CBTRN03C.cbl:630}</td><td>{@code 9999-ABEND-PROGRAM}</td>
 *       <td>yes - 0 / 999</td></tr>
 *   <tr><td>9</td><td>{@code app/cbl/CBSTM03A.CBL:923}</td><td>{@code 9999-ABEND-PROGRAM}</td>
 *       <td><strong>NO - sets neither</strong></td></tr>
 * </table>
 *
 * <p>Two independent axes run through that table and confusing them is easy. The <em>paragraph
 * name</em> is {@code Z-ABEND-PROGRAM} in {@code CBCUS01C} and {@code CBTRN01C} and
 * {@code 9999-ABEND-PROGRAM} in the other seven; that difference is cosmetic, the two bodies are
 * identical, and it is recorded only so a reader checking the count does not conclude a site was
 * missed. The <em>body</em> differs in exactly one program, and it is not one of those two:
 * {@code CBSTM03A.CBL:921-923} is only {@code DISPLAY 'ABENDING PROGRAM'} followed by
 * {@code CALL 'CEE3ABD'.}, with no {@code MOVE} of either argument.</p>
 *
 * <h2>The asymmetry these tests exist to protect</h2>
 *
 * <p>Eight sites set {@code TIMING} to <strong>zero</strong> and {@code ABCODE} to 999. The ninth
 * sets neither. Because one of the values actually written is zero, absence can never be modelled as
 * a plain {@code 0}: if it were, {@code CBSTM03A}'s abend would be indistinguishable from the other
 * eight and the divergence would vanish silently. {@link AbendException} therefore reports both
 * arguments as an {@link OptionalInt}, and the nested {@code DivergentShape} and
 * {@code ShapeDistinction} groups below are the assertions that hold it to that.</p>
 *
 * <h2>Provenance of every expectation</h2>
 *
 * <p>The legacy COBOL cannot be executed in this environment, so no expectation here was captured
 * from a live run; each one is derived statically from the source and is annotated with the
 * {@code file:line} it came from. {@code ABCODE} and {@code TIMING} are declared
 * {@code PIC S9(9) BINARY} at {@code app/cbl/CBACT01C.cbl:66-67}, which is why they are {@code int}
 * and why no binary floating-point type may appear anywhere in the class under test.</p>
 *
 * <p>{@code review_rules} reports <em>"No user rules provided."</em> for this project, so no
 * project-specific rule governs this file; the migration's own engineering practices apply instead,
 * and the ones that bind here are: only the pinned test stack (JUnit Jupiter and AssertJ, both
 * supplied by {@code spring-boot-starter-test}), no wildcard imports of any kind, no static mutable
 * state, and no disabled, empty or deferred test.</p>
 *
 * @see AbendException
 */
@DisplayName("AbendException - the Java equivalent of CALL 'CEE3ABD' at all nine COBOL abend sites")
class AbendExceptionTest {

    /**
     * A representative of the eight standard sites: {@code app/cbl/CBACT01C.cbl:173}, whose
     * paragraph moves 0 into {@code TIMING} and 999 into {@code ABCODE}. Exactly eight characters,
     * which is the {@code PIC X(8)} width of a COBOL {@code PROGRAM-ID}.
     */
    private static final String STANDARD_SITE_PROGRAM = "CBACT01C";

    /**
     * The one divergent site: {@code app/cbl/CBSTM03A.CBL:923}, whose paragraph sets neither
     * argument. Also exactly eight characters.
     */
    private static final String DIVERGENT_SITE_PROGRAM = "CBSTM03A";

    /**
     * The text {@code CBACT01C} displays immediately before its abend, from
     * {@code app/cbl/CBACT01C.cbl:144}: {@code DISPLAY 'ERROR OPENING ACCTFILE'}.
     */
    private static final String OPEN_FAILURE_REASON = "ERROR OPENING ACCTFILE";

    /**
     * The style of text {@code CBSTM03A} displays before its abend - {@code 'ERROR READING
     * XREFFILE'} followed by {@code 'RETURN CODE: '} and the {@code CBSTM03B} status, for example at
     * {@code app/cbl/CBSTM03A.CBL:359-361}.
     */
    private static final String READ_FAILURE_REASON = "ERROR READING XREFFILE";

    /**
     * A locale whose numbering system renders decimal digits as Arabic-Indic characters, used to
     * prove that the detail message is composed with {@link Locale#ROOT} and not with the platform
     * default. Under this locale a default-locale {@code %d} would render 12 as
     * {@code \u0661\u0662}.
     */
    private static final Locale ARABIC_INDIC_DIGIT_LOCALE = Locale.forLanguageTag("ar-EG-u-nu-arab");

    /** The instance field names the class carries, asserted by the immutability audit. */
    private static final List<String> CARRIED_FIELD_NAMES =
            List.of("program", "returnCode", "abendCode", "timing", "reason");

    /**
     * Types that gate G22 forbids: no COBOL numeric may be represented in binary floating point.
     *
     * <p>An immutable list rather than an array, so this constant carries no mutable state of any
     * kind. These four class literals are the only textual occurrences of {@code double} and
     * {@code float} in this file, and they appear here precisely in order to <em>forbid</em> those
     * types - no value of either type is ever declared, computed or stored.
     */
    private static final List<Class<?>> FORBIDDEN_NUMERIC_TYPES =
            List.<Class<?>>of(double.class, float.class, Double.class, Float.class);

    /**
     * Distinguishes members the coverage agent adds at class-load time from members the source
     * declares. The agent contributes a synthetic {@code $jacocoData} field and a {@code $jacocoInit}
     * method; a structural audit that did not skip them would fail only when coverage is being
     * measured, which is precisely when it needs to pass.
     *
     * @param memberName the reflective member name
     * @param synthetic  whether the member is flagged synthetic
     * @return {@code true} when the member was injected rather than declared in source
     */
    private static boolean isInstrumentationArtifact(String memberName, boolean synthetic) {
        return synthetic || memberName.startsWith("$");
    }

    /**
     * Serializes and deserializes an abend, so the carried state can be asserted to survive the
     * round trip that a distributed batch failure report would put it through.
     *
     * @param original the abend to round-trip
     * @return an independent, deserialized copy
     * @throws IOException            if the in-memory streams fail
     * @throws ClassNotFoundException if the type cannot be resolved on the way back
     */
    private static AbendException serializeAndBack(AbendException original)
            throws IOException, ClassNotFoundException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(buffer.toByteArray()))) {
            return (AbendException) in.readObject();
        }
    }

    /**
     * Raises the eight-site shape from a method that declares no {@code throws} clause. That this
     * method compiles is itself the proof that the exception is unchecked; the assertion in the test
     * merely confirms what the compiler already accepted.
     */
    private static void raiseStandardAbend() {
        throw AbendException.standard(STANDARD_SITE_PROGRAM, AbendException.RETURN_CODE_IO_ERROR,
                OPEN_FAILURE_REASON);
    }

    /** Raises the {@code CBSTM03A} shape, likewise without a {@code throws} clause. */
    private static void raiseDivergentAbend() {
        throw AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                AbendException.RETURN_CODE_IO_ERROR, READ_FAILURE_REASON);
    }

    @Nested
    @DisplayName("Type and contract")
    class TypeAndContract {

        @Test
        @DisplayName("is unchecked, so an abend needs no throws clause anywhere in the batch layer")
        void isUnchecked() {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR);

            assertThat(abend).isInstanceOf(RuntimeException.class);
            assertThat(abend).isNotInstanceOf(Error.class);
            assertThat(AbendException.class.getSuperclass()).isEqualTo(RuntimeException.class);
        }

        @Test
        @DisplayName("is final, so no subclass can widen or weaken the abend contract")
        void isFinal() {
            assertThat(Modifier.isFinal(AbendException.class.getModifiers()))
                    .as("AbendException must be final")
                    .isTrue();
        }

        @Test
        @DisplayName("exposes exactly one constructor and it is private, so only the two COBOL "
                + "shapes are constructible")
        void hasOnlyThePrivateCanonicalConstructor() {
            Constructor<?>[] constructors = AbendException.class.getDeclaredConstructors();

            assertThat(constructors).hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers()))
                    .as("the canonical constructor must be private")
                    .isTrue();
            // program, returnCode, abendCode, timing, reason, cause.
            assertThat(constructors[0].getParameterCount()).isEqualTo(6);
        }

        @Test
        @DisplayName("declares serialVersionUID explicitly rather than relying on a computed hash")
        void declaresSerialVersionUid() throws ReflectiveOperationException {
            Field field = AbendException.class.getDeclaredField("serialVersionUID");

            assertThat(field.getType()).isEqualTo(long.class);
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
            // Resolved through the serialization runtime rather than by reflecting on the value, so
            // the assertion proves what a peer JVM would actually agree to read.
            assertThat(ObjectStreamClass.lookup(AbendException.class).getSerialVersionUID())
                    .isEqualTo(1L);
            assertThat(AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR)).isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("survives a serialization round trip with every carried value intact")
        void survivesSerializationRoundTrip() throws IOException, ClassNotFoundException {
            AbendException original = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON);

            AbendException restored = serializeAndBack(original);

            assertThat(restored.getProgram()).isEqualTo(STANDARD_SITE_PROGRAM);
            assertThat(restored.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
            assertThat(restored.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(restored.getTiming()).hasValue(AbendException.STANDARD_TIMING);
            assertThat(restored.getReason()).contains(OPEN_FAILURE_REASON);
            assertThat(restored.getMessage()).isEqualTo(original.getMessage());
        }

        @Test
        @DisplayName("carries the absence of both abend parameters through serialization too")
        void survivesSerializationRoundTripWhenTheAbendParametersAreAbsent()
                throws IOException, ClassNotFoundException {
            AbendException original = AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR);

            AbendException restored = serializeAndBack(original);

            assertThat(restored.hasAbendCode()).isFalse();
            assertThat(restored.hasTiming()).isFalse();
            assertThat(restored.getAbendCode()).isEmpty();
            assertThat(restored.getTiming()).isEmpty();
            assertThat(restored.getMessage()).isEqualTo(original.getMessage());
        }

        @Test
        @DisplayName("carries exactly five private final instance fields, so instances are immutable")
        void carriedStateIsImmutable() {
            List<String> instanceFields = new ArrayList<>();
            for (Field field : AbendException.class.getDeclaredFields()) {
                if (isInstrumentationArtifact(field.getName(), field.isSynthetic())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                    instanceFields.add(field.getName());
                }
            }

            assertThat(instanceFields)
                    .containsExactlyInAnyOrderElementsOf(CARRIED_FIELD_NAMES);
        }

        @Test
        @DisplayName("exposes no mutator, so no carried value can be rewritten after construction")
        void exposesNoMutator() {
            for (Method method : AbendException.class.getDeclaredMethods()) {
                if (isInstrumentationArtifact(method.getName(), method.isSynthetic())) {
                    continue;
                }
                assertThat(method.getName())
                        .as("no method may behave as a setter")
                        .doesNotStartWith("set");
            }
        }

        @Test
        @DisplayName("gate G22: declares no double or float in any field, parameter or return type")
        void declaresNoBinaryFloatingPointType() {
            for (Field field : AbendException.class.getDeclaredFields()) {
                if (isInstrumentationArtifact(field.getName(), field.isSynthetic())) {
                    continue;
                }
                assertThat(field.getType())
                        .as("type of field %s", field.getName())
                        .isNotIn(FORBIDDEN_NUMERIC_TYPES);
            }
            for (Method method : AbendException.class.getDeclaredMethods()) {
                if (isInstrumentationArtifact(method.getName(), method.isSynthetic())) {
                    continue;
                }
                assertThat(method.getReturnType())
                        .as("return type of %s", method.getName())
                        .isNotIn(FORBIDDEN_NUMERIC_TYPES);
                assertThat(method.getParameterTypes())
                        .as("parameter types of %s", method.getName())
                        .doesNotContainAnyElementsOf(FORBIDDEN_NUMERIC_TYPES);
            }
            for (Constructor<?> constructor : AbendException.class.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("parameter types of the canonical constructor")
                        .doesNotContainAnyElementsOf(FORBIDDEN_NUMERIC_TYPES);
            }
        }

        @Test
        @DisplayName("models ABCODE and TIMING as int, matching PIC S9(9) BINARY at CBACT01C:66-67")
        void modelsTheAbendParametersAsInt() throws ReflectiveOperationException {
            assertThat(AbendException.class.getDeclaredField("STANDARD_ABEND_CODE").getType())
                    .isEqualTo(int.class);
            assertThat(AbendException.class.getDeclaredField("STANDARD_TIMING").getType())
                    .isEqualTo(int.class);
            assertThat(AbendException.class.getDeclaredMethod("getAbendCode").getReturnType())
                    .isEqualTo(OptionalInt.class);
            assertThat(AbendException.class.getDeclaredMethod("getTiming").getReturnType())
                    .isEqualTo(OptionalInt.class);
            assertThat(AbendException.class.getDeclaredMethod("getReturnCode").getReturnType())
                    .isEqualTo(int.class);
        }
    }

    @Nested
    @DisplayName("The 'ABENDING PROGRAM' display literal")
    class DisplayLiteral {

        @Test
        @DisplayName("is byte-exact with the COBOL DISPLAY at app/cbl/CBACT01C.cbl:170")
        void isByteExact() {
            // DISPLAY 'ABENDING PROGRAM' - identical at all nine sites, verified at CBACT01C:170,
            // CBACT02C:155, CBACT03C:155, CBACT04C:629, CBCUS01C:155, CBTRN01C:470, CBTRN02C:708,
            // CBTRN03C:627 and CBSTM03A:922. No punctuation, no casing change, no padding.
            assertThat(AbendException.ABEND_DISPLAY_TEXT).isEqualTo("ABENDING PROGRAM");
            assertThat(AbendException.ABEND_DISPLAY_TEXT).hasSize(16);
            assertThat(AbendException.ABEND_DISPLAY_TEXT).doesNotStartWith(" ");
            assertThat(AbendException.ABEND_DISPLAY_TEXT).doesNotEndWith(" ");
            assertThat(AbendException.ABEND_DISPLAY_TEXT).doesNotContain("  ");
            assertThat(AbendException.ABEND_DISPLAY_TEXT).doesNotContain(".");
            assertThat(AbendException.ABEND_DISPLAY_TEXT).doesNotContain(":");
            assertThat(AbendException.ABEND_DISPLAY_TEXT)
                    .isEqualTo(AbendException.ABEND_DISPLAY_TEXT.toUpperCase(Locale.ROOT));
        }

        @Test
        @DisplayName("is a shared public constant, so no call site has to retype the wording")
        void isASharedConstant() throws ReflectiveOperationException {
            Field field = AbendException.class.getDeclaredField("ABEND_DISPLAY_TEXT");

            assertThat(field.getType()).isEqualTo(String.class);
            assertThat(Modifier.isPublic(field.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("opens the detail message of both COBOL shapes")
        void opensTheMessageOfBothShapes() {
            AbendException standardShape = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON);
            AbendException divergentShape = AbendException.withoutAbendParameters(
                    DIVERGENT_SITE_PROGRAM, AbendException.RETURN_CODE_IO_ERROR,
                    READ_FAILURE_REASON);

            assertThat(standardShape.getMessage()).startsWith(AbendException.ABEND_DISPLAY_TEXT);
            assertThat(divergentShape.getMessage()).startsWith(AbendException.ABEND_DISPLAY_TEXT);
            assertThat(standardShape.getMessage()).contains(AbendException.ABEND_DISPLAY_TEXT);
            assertThat(divergentShape.getMessage()).contains(AbendException.ABEND_DISPLAY_TEXT);
        }
    }

    @Nested
    @DisplayName("Return codes - gate G35")
    class ReturnCodes {

        @Test
        @DisplayName("the named constants reproduce the complete observed COBOL vocabulary")
        void namedConstantsMatchTheCobolValues() {
            // 01 APPL-RESULT PIC S9(9) COMP with 88 APPL-AOK VALUE 0 and 88 APPL-EOF VALUE 16
            // [app/cbl/CBACT01C.cbl:61-63]; 4 from MOVE 4 TO RETURN-CODE [CBTRN02C.cbl:230];
            // 8 and 12 from the ADD n TO ZERO GIVING APPL-RESULT guards [CBACT01C.cbl:152, 157].
            assertThat(AbendException.RETURN_CODE_OK).isZero();
            assertThat(AbendException.RETURN_CODE_WARNING).isEqualTo(4);
            assertThat(AbendException.RETURN_CODE_ASSUMED_FAILURE).isEqualTo(8);
            assertThat(AbendException.RETURN_CODE_IO_ERROR).isEqualTo(12);
            assertThat(AbendException.RETURN_CODE_END_OF_FILE).isEqualTo(16);
        }

        @Test
        @DisplayName("the five named codes are distinct, so none can silently shadow another")
        void namedConstantsAreDistinct() {
            assertThat(new int[] {
                    AbendException.RETURN_CODE_OK,
                    AbendException.RETURN_CODE_WARNING,
                    AbendException.RETURN_CODE_ASSUMED_FAILURE,
                    AbendException.RETURN_CODE_IO_ERROR,
                    AbendException.RETURN_CODE_END_OF_FILE})
                    .doesNotHaveDuplicates();
        }

        @ParameterizedTest(name = "return code {0} - {1}")
        @CsvSource(delimiter = '|', value = {
            " 0 | 88 APPL-AOK VALUE 0 at app/cbl/CBACT01C.cbl:62",
            " 4 | MOVE 4 TO RETURN-CODE at app/cbl/CBTRN02C.cbl:230, the only RC-4 site in the source",
            " 8 | ADD 8 TO ZERO GIVING APPL-RESULT at app/cbl/CBACT01C.cbl:152",
            "12 | ADD 12 TO ZERO GIVING APPL-RESULT at app/cbl/CBACT01C.cbl:157",
            "16 | 88 APPL-EOF VALUE 16 at app/cbl/CBACT01C.cbl:63"
        })
        @DisplayName("every observed COBOL return code survives both construction shapes unchanged")
        void carriesEveryObservedReturnCode(int returnCode, String provenance) {
            AbendException standardShape =
                    AbendException.standard(STANDARD_SITE_PROGRAM, returnCode);
            AbendException divergentShape =
                    AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM, returnCode);

            assertThat(standardShape.getReturnCode()).as("%s", provenance).isEqualTo(returnCode);
            assertThat(divergentShape.getReturnCode()).as("%s", provenance).isEqualTo(returnCode);
            assertThat(standardShape.getMessage()).contains("RETURN-CODE=" + returnCode);
            assertThat(divergentShape.getMessage()).contains("RETURN-CODE=" + returnCode);
        }

        @ParameterizedTest(name = "an unobserved return code {0} is carried verbatim")
        @ValueSource(ints = {1, 3, 20, 99, 2_147_483_647})
        @DisplayName("a value outside the observed set is accepted verbatim rather than rejected")
        void carriesUnobservedReturnCodesVerbatim(int returnCode) {
            // Deliberate: the class documents the return code as unconstrained. An enumeration or a
            // range check would reject a value the COBOL could legitimately place in APPL-RESULT,
            // and rejecting a legitimate value would itself be a behaviour change. So the assertion
            // here is pass-through, not rejection - there is no guard to drive from the other side.
            assertThat(AbendException.standard(STANDARD_SITE_PROGRAM, returnCode).getReturnCode())
                    .isEqualTo(returnCode);
            assertThat(AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM, returnCode)
                    .getReturnCode()).isEqualTo(returnCode);
        }

        @ParameterizedTest(name = "a negative return code {0} is carried verbatim")
        @ValueSource(ints = {-1, -8, -999_999_999, -2_147_483_648})
        @DisplayName("a negative code is carried verbatim, since APPL-RESULT is PIC S9(9) - signed")
        void carriesNegativeReturnCodesVerbatim(int returnCode) {
            // No site in the 28 programs ever moves a negative value into APPL-RESULT, but the
            // PICTURE clause is signed, so a negative is representable in COBOL. The class neither
            // rejects nor normalises it.
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM, returnCode);

            assertThat(abend.getReturnCode()).isEqualTo(returnCode);
            assertThat(abend.getMessage()).contains("RETURN-CODE=" + returnCode);
            assertThat(abend.getMessage()).contains("RETURN-CODE=-");
        }

        @Test
        @DisplayName("only the success code is zero, which is what COND=(0,NE) step gating keys on")
        void onlySuccessIsZero() {
            // app/jcl/CREASTMT.JCL gates its later steps with COND=(0,NE): run only while every
            // prior step returned zero. This class carries the raw code and exposes no exit-status
            // helper of its own - translating it into a Spring Batch ExitStatus and a process exit
            // code belongs to the batch configuration, which owns that assertion. What is asserted
            // here is the property the gating depends on: exactly one of the observed codes is zero.
            assertThat(AbendException.RETURN_CODE_OK).isZero();
            assertThat(AbendException.RETURN_CODE_WARNING).isNotZero();
            assertThat(AbendException.RETURN_CODE_ASSUMED_FAILURE).isNotZero();
            assertThat(AbendException.RETURN_CODE_IO_ERROR).isNotZero();
            assertThat(AbendException.RETURN_CODE_END_OF_FILE).isNotZero();
        }

        @Test
        @DisplayName("exposes no exit-status helper, so the raw code is the whole contract")
        void exposesNoExitStatusHelper() {
            List<String> methodNames = new ArrayList<>();
            for (Method method : AbendException.class.getDeclaredMethods()) {
                if (isInstrumentationArtifact(method.getName(), method.isSynthetic())) {
                    continue;
                }
                methodNames.add(method.getName());
            }

            assertThat(methodNames).contains("getReturnCode");
            assertThat(methodNames).doesNotContain("getExitStatus", "toExitStatus", "exitStatus",
                    "getExitCode", "toExitCode");
        }
    }

    @Nested
    @DisplayName("Shape A - the eight sites that set ABCODE 999 and TIMING 0")
    class StandardShape {

        @Test
        @DisplayName("carries both CEE3ABD arguments exactly as CBACT01C:171-172 moves them")
        void carriesBothAbendParameters() {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON);

            // MOVE 0 TO TIMING [app/cbl/CBACT01C.cbl:171] and MOVE 999 TO ABCODE [:172], the two
            // statements the eight standard paragraphs share byte for byte.
            assertThat(AbendException.STANDARD_ABEND_CODE).isEqualTo(999);
            assertThat(AbendException.STANDARD_TIMING).isZero();
            assertThat(abend.getAbendCode()).hasValue(999);
            assertThat(abend.getTiming()).hasValue(0);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }

        @Test
        @DisplayName("reports both arguments present, and the present TIMING really is zero")
        void reportsBothPresent() {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR);

            assertThat(abend.hasAbendCode()).isTrue();
            assertThat(abend.hasTiming()).isTrue();
            assertThat(abend.getAbendCode()).isNotEmpty();
            assertThat(abend.getTiming()).isNotEmpty();
            // A present zero, not an absent value: getAsInt is reachable and yields 0.
            assertThat(abend.getTiming().getAsInt()).isZero();
            assertThat(abend.getAbendCode().getAsInt()).isEqualTo(999);
        }

        @Test
        @DisplayName("names both arguments in the detail message")
        void namesBothArgumentsInTheMessage() {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR);

            assertThat(abend.getMessage()).contains("ABCODE=999");
            assertThat(abend.getMessage()).contains("TIMING=0");
        }

        @Test
        @DisplayName("the two-argument factory supplies no reason and no cause")
        void twoArgumentFactoryCarriesNeitherReasonNorCause() {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_ASSUMED_FAILURE);

            assertThat(abend.hasReason()).isFalse();
            assertThat(abend.getReason()).isEmpty();
            assertThat(abend.getCause()).isNull();
            assertThat(abend.getMessage()).doesNotContain(" - ");
            assertThat(abend.getReturnCode())
                    .isEqualTo(AbendException.RETURN_CODE_ASSUMED_FAILURE);
        }

        @Test
        @DisplayName("the three-argument factory carries the reason and leaves the cause null")
        void threeArgumentFactoryCarriesTheReason() {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON);

            assertThat(abend.hasReason()).isTrue();
            assertThat(abend.getReason()).contains(OPEN_FAILURE_REASON);
            assertThat(abend.getCause()).isNull();
            assertThat(abend.getMessage()).endsWith(" - " + OPEN_FAILURE_REASON);
        }

        @Test
        @DisplayName("the four-argument factory carries the reason and retains the cause")
        void fourArgumentFactoryCarriesTheReasonAndTheCause() {
            IOException underlying = new IOException("ACCTFILE unavailable");

            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON, underlying);

            assertThat(abend.getReason()).contains(OPEN_FAILURE_REASON);
            assertThat(abend.getCause()).isSameAs(underlying);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }

        @ParameterizedTest(name = "a blank reason [{0}] is treated as absent")
        @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
        @DisplayName("a blank reason is normalised to absent, so no message ends in a dangling dash")
        void blankReasonIsTreatedAsAbsent(String blankReason) {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, blankReason);

            assertThat(abend.hasReason()).isFalse();
            assertThat(abend.getReason()).isEmpty();
            assertThat(abend.getMessage()).doesNotContain(" - ");
            assertThat(abend.getMessage()).doesNotEndWith("-");
        }

        @Test
        @DisplayName("an explicitly null reason is treated as absent")
        void nullReasonIsTreatedAsAbsent() {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, null, null);

            assertThat(abend.hasReason()).isFalse();
            assertThat(abend.getReason()).isEmpty();
            assertThat(abend.getCause()).isNull();
            assertThat(abend.getMessage()).doesNotContain(" - ");
        }

        @Test
        @DisplayName("a non-blank reason is kept untouched, never trimmed or reformatted")
        void nonBlankReasonIsKeptUntouched() {
            String padded = "  ERROR CLOSING ACCOUNT FILE  ";

            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, padded);

            assertThat(abend.getReason()).contains(padded);
            assertThat(abend.getMessage()).endsWith(" - " + padded);
        }

        @ParameterizedTest(name = "{0} builds the standard shape")
        @ValueSource(strings = {"CBACT01C", "CBACT02C", "CBACT03C", "CBACT04C", "CBCUS01C",
                "CBTRN01C", "CBTRN02C", "CBTRN03C"})
        @DisplayName("all eight standard sites build this shape, whatever their paragraph is named")
        void allEightStandardSitesBuildThisShape(String program) {
            // CBCUS01C:154-158 and CBTRN01C:469-473 name the paragraph Z-ABEND-PROGRAM; the other
            // six name it 9999-ABEND-PROGRAM. The bodies are identical, so the shape is identical -
            // the naming variance carries no behaviour and no site is missing from this list.
            AbendException abend =
                    AbendException.standard(program, AbendException.RETURN_CODE_IO_ERROR);

            assertThat(abend.getProgram()).isEqualTo(program);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }
    }

    @Nested
    @DisplayName("Shape B - CBSTM03A.CBL:923, the one site that sets neither argument")
    class DivergentShape {

        @Test
        @DisplayName("reports both arguments ABSENT and never as a silent zero")
        void reportsBothAbsentRatherThanZero() {
            AbendException abend = AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, READ_FAILURE_REASON);

            // This is the single most important assertion in the file.
            //
            // app/cbl/CBSTM03A.CBL:921-923 is exactly:
            //     9999-ABEND-PROGRAM.
            //         DISPLAY 'ABENDING PROGRAM'
            //         CALL 'CEE3ABD'.
            // There is no MOVE 999 TO ABCODE and no MOVE 0 TO TIMING, and the program does not even
            // declare the two fields in WORKING-STORAGE. Absence must therefore never be modelled
            // as 0, because 0 is precisely the value the other eight sites genuinely move into
            // TIMING [app/cbl/CBACT01C.cbl:171]. If the two were conflated this site would be
            // indistinguishable from those eight and the divergence would be lost silently.
            assertThat(abend.hasAbendCode()).isFalse();
            assertThat(abend.hasTiming()).isFalse();
            assertThat(abend.getAbendCode()).isEmpty();
            assertThat(abend.getTiming()).isEmpty();
            assertThat(abend.getAbendCode().isPresent()).isFalse();
            assertThat(abend.getTiming().isPresent()).isFalse();
            // Absent, not zero: a sentinel of -1 survives, which a defaulted 0 would have replaced.
            assertThat(abend.getTiming().orElse(-1)).isEqualTo(-1);
            assertThat(abend.getAbendCode().orElse(-1)).isEqualTo(-1);
            assertThat(abend.getTiming()).isNotEqualTo(OptionalInt.of(0));
            assertThat(abend.getTiming())
                    .isNotEqualTo(OptionalInt.of(AbendException.STANDARD_TIMING));
            assertThat(abend.getAbendCode())
                    .isNotEqualTo(OptionalInt.of(AbendException.STANDARD_ABEND_CODE));
        }

        @Test
        @DisplayName("omits both arguments from the detail message, so a log line shows the "
                + "divergence")
        void omitsBothArgumentsFromTheMessage() {
            AbendException abend = AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR);

            assertThat(abend.getMessage()).doesNotContain("ABCODE");
            assertThat(abend.getMessage()).doesNotContain("TIMING");
            assertThat(abend.getMessage()).doesNotContain("999");
        }

        @Test
        @DisplayName("the two-argument factory supplies no reason and no cause")
        void twoArgumentFactoryCarriesNeitherReasonNorCause() {
            AbendException abend = AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR);

            assertThat(abend.hasReason()).isFalse();
            assertThat(abend.getReason()).isEmpty();
            assertThat(abend.getCause()).isNull();
            assertThat(abend.getMessage()).doesNotContain(" - ");
        }

        @Test
        @DisplayName("the three-argument factory carries the reason CBSTM03A displays")
        void threeArgumentFactoryCarriesTheReason() {
            AbendException abend = AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, READ_FAILURE_REASON);

            assertThat(abend.hasReason()).isTrue();
            assertThat(abend.getReason()).contains(READ_FAILURE_REASON);
            assertThat(abend.getCause()).isNull();
            assertThat(abend.getMessage()).endsWith(" - " + READ_FAILURE_REASON);
        }

        @Test
        @DisplayName("the four-argument factory carries the reason and retains the cause")
        void fourArgumentFactoryCarriesTheReasonAndTheCause() {
            IOException underlying = new IOException("TRNXFILE unavailable");

            AbendException abend = AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, READ_FAILURE_REASON, underlying);

            assertThat(abend.getReason()).contains(READ_FAILURE_REASON);
            assertThat(abend.getCause()).isSameAs(underlying);
            assertThat(abend.hasAbendCode()).isFalse();
            assertThat(abend.hasTiming()).isFalse();
        }

        @ParameterizedTest(name = "a blank reason [{0}] is treated as absent")
        @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
        @DisplayName("a blank reason is normalised to absent on this shape too")
        void blankReasonIsTreatedAsAbsent(String blankReason) {
            AbendException abend = AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, blankReason);

            assertThat(abend.hasReason()).isFalse();
            assertThat(abend.getReason()).isEmpty();
            assertThat(abend.getMessage()).doesNotContain(" - ");
            assertThat(abend.getMessage()).doesNotEndWith("-");
        }

        @Test
        @DisplayName("an explicitly null reason and null cause are both treated as absent")
        void nullReasonAndNullCauseAreTreatedAsAbsent() {
            AbendException abend = AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, null, null);

            assertThat(abend.hasReason()).isFalse();
            assertThat(abend.getReason()).isEmpty();
            assertThat(abend.getCause()).isNull();
        }
    }

    @Nested
    @DisplayName("Telling the two shapes apart")
    class ShapeDistinction {

        @Test
        @DisplayName("presence alone separates the eight-site shape from the CBSTM03A shape")
        void presenceSeparatesTheTwoShapes() {
            AbendException standardShape = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR);
            AbendException divergentShape = AbendException.withoutAbendParameters(
                    DIVERGENT_SITE_PROGRAM, AbendException.RETURN_CODE_IO_ERROR);

            assertThat(standardShape.hasAbendCode()).isNotEqualTo(divergentShape.hasAbendCode());
            assertThat(standardShape.hasTiming()).isNotEqualTo(divergentShape.hasTiming());
            assertThat(standardShape.getAbendCode()).isNotEqualTo(divergentShape.getAbendCode());
            assertThat(standardShape.getTiming()).isNotEqualTo(divergentShape.getTiming());
        }

        @Test
        @DisplayName("a present TIMING of zero is observably different from an absent TIMING")
        void presentZeroIsObservablyDifferentFromAbsent() {
            AbendException standardShape = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR);
            AbendException divergentShape = AbendException.withoutAbendParameters(
                    DIVERGENT_SITE_PROGRAM, AbendException.RETURN_CODE_IO_ERROR);

            assertThat(standardShape.getTiming()).hasValue(0);
            assertThat(divergentShape.getTiming()).isEmpty();
            // The defaulting behaviour is where a conflated model would betray itself: a present
            // zero keeps the zero, an absent value keeps the caller's own default.
            assertThat(standardShape.getTiming().orElse(-1)).isZero();
            assertThat(divergentShape.getTiming().orElse(-1)).isEqualTo(-1);
            assertThat(standardShape.getTiming()).isNotEqualTo(divergentShape.getTiming());
        }

        @Test
        @DisplayName("the detail messages differ even when program, code and reason are identical")
        void messagesSeparateTheTwoShapesWithEverythingElseHeldEqual() {
            // Both instances name CBSTM03A on purpose, so the construction shape is the ONLY
            // variable. Building the standard shape for CBSTM03A would be the wrong translation of
            // app/cbl/CBSTM03A.CBL:923 - it is done here solely to prove the two shapes remain
            // distinguishable when nothing else differs.
            AbendException wrongShapeForComparison = AbendException.standard(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, READ_FAILURE_REASON);
            AbendException faithfulShape = AbendException.withoutAbendParameters(
                    DIVERGENT_SITE_PROGRAM, AbendException.RETURN_CODE_IO_ERROR,
                    READ_FAILURE_REASON);

            assertThat(faithfulShape.getMessage()).isNotEqualTo(wrongShapeForComparison.getMessage());
            assertThat(wrongShapeForComparison.getMessage()).contains("ABCODE=999 TIMING=0");
            assertThat(faithfulShape.getMessage()).doesNotContain("ABCODE");
            assertThat(faithfulShape.getMessage()).doesNotContain("TIMING");
        }

        @Test
        @DisplayName("no factory yields a half-present pair, matching the coupled COBOL MOVEs")
        void noFactoryProducesPartialPresence() {
            // Every paragraph that sets one argument sets both, and the one paragraph that sets
            // neither sets neither; a half-present pair has no COBOL counterpart anywhere in the
            // nine sites. The only member that could express one is the private canonical
            // constructor, which no caller outside the class can reach - see
            // TypeAndContract.hasOnlyThePrivateCanonicalConstructor. So the contract here is
            // "forbidden by construction", and this drives all six public factories to prove it.
            List<AbendException> everyFactoryResult = List.of(
                    AbendException.standard(STANDARD_SITE_PROGRAM,
                            AbendException.RETURN_CODE_IO_ERROR),
                    AbendException.standard(STANDARD_SITE_PROGRAM,
                            AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON),
                    AbendException.standard(STANDARD_SITE_PROGRAM,
                            AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON,
                            new IOException("ACCTFILE unavailable")),
                    AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                            AbendException.RETURN_CODE_IO_ERROR),
                    AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                            AbendException.RETURN_CODE_IO_ERROR, READ_FAILURE_REASON),
                    AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                            AbendException.RETURN_CODE_IO_ERROR, READ_FAILURE_REASON,
                            new IOException("TRNXFILE unavailable")));

            assertThat(everyFactoryResult).hasSize(6);
            for (AbendException abend : everyFactoryResult) {
                assertThat(abend.hasAbendCode())
                        .as("ABCODE and TIMING must be present together or absent together: %s",
                                abend.getMessage())
                        .isEqualTo(abend.hasTiming());
            }
        }
    }

    @Nested
    @DisplayName("The abending PROGRAM-ID")
    class ProgramName {

        @ParameterizedTest(name = "{0} - abend parameters present: {1}")
        @CsvSource({
            "CBACT01C, true",
            "CBACT02C, true",
            "CBACT03C, true",
            "CBACT04C, true",
            "CBCUS01C, true",
            "CBTRN01C, true",
            "CBTRN02C, true",
            "CBTRN03C, true",
            "CBSTM03A, false"
        })
        @DisplayName("all nine CEE3ABD sites are representable and only the ninth omits the "
                + "arguments")
        void everyAbendSiteIsRepresentable(String program, boolean carriesAbendParameters) {
            AbendException abend = carriesAbendParameters
                    ? AbendException.standard(program, AbendException.RETURN_CODE_IO_ERROR)
                    : AbendException.withoutAbendParameters(program,
                            AbendException.RETURN_CODE_IO_ERROR);

            assertThat(program).as("a COBOL PROGRAM-ID is PIC X(8)").hasSize(8);
            assertThat(abend.getProgram()).isEqualTo(program);
            assertThat(abend.getMessage()).contains(program);
            assertThat(abend.hasAbendCode()).isEqualTo(carriesAbendParameters);
            assertThat(abend.hasTiming()).isEqualTo(carriesAbendParameters);
        }

        @Test
        @DisplayName("is returned verbatim: never trimmed and never padded to the PIC X(8) width")
        void isReturnedVerbatim() {
            // The class documents the name as returned unchanged. Nothing pads a short name out to
            // eight characters and nothing trims a name that carries surrounding blanks, because
            // either adjustment would silently rewrite the identity of the abending program.
            assertThat(AbendException.standard("CBACT01C ", AbendException.RETURN_CODE_IO_ERROR)
                    .getProgram()).isEqualTo("CBACT01C ");
            assertThat(AbendException.standard(" CBACT01C", AbendException.RETURN_CODE_IO_ERROR)
                    .getProgram()).isEqualTo(" CBACT01C");
            assertThat(AbendException.standard("A", AbendException.RETURN_CODE_IO_ERROR)
                    .getProgram()).isEqualTo("A");
            assertThat(AbendException.withoutAbendParameters("CBSTM03AX",
                    AbendException.RETURN_CODE_IO_ERROR).getProgram()).isEqualTo("CBSTM03AX");
        }

        @Test
        @DisplayName("a trailing blank in the name survives into the detail message untouched")
        void aTrailingBlankSurvivesIntoTheMessage() {
            AbendException abend =
                    AbendException.standard("CBACT01C ", AbendException.RETURN_CODE_IO_ERROR);

            assertThat(abend.getMessage())
                    .isEqualTo("ABENDING PROGRAM CBACT01C  RETURN-CODE=12 ABCODE=999 TIMING=0");
        }

        @Test
        @DisplayName("a null name is rejected by every factory in both families")
        void nullProgramIsRejectedByEveryFactory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AbendException.standard(null,
                            AbendException.RETURN_CODE_IO_ERROR))
                    .withMessageContaining("program must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> AbendException.standard(null,
                            AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON))
                    .withMessageContaining("program must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> AbendException.standard(null,
                            AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON,
                            new IOException("ACCTFILE unavailable")))
                    .withMessageContaining("program must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> AbendException.withoutAbendParameters(null,
                            AbendException.RETURN_CODE_IO_ERROR))
                    .withMessageContaining("program must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> AbendException.withoutAbendParameters(null,
                            AbendException.RETURN_CODE_IO_ERROR, READ_FAILURE_REASON))
                    .withMessageContaining("program must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> AbendException.withoutAbendParameters(null,
                            AbendException.RETURN_CODE_IO_ERROR, READ_FAILURE_REASON,
                            new IOException("TRNXFILE unavailable")))
                    .withMessageContaining("program must not be null");
        }

        @ParameterizedTest(name = "a blank name [{0}] is rejected")
        @ValueSource(strings = {"", " ", "        ", "\t", "\n"})
        @DisplayName("a blank name is rejected by both families, eight blanks included")
        void blankProgramIsRejected(String blankProgram) {
            // "        " is the all-blanks PIC X(8) field: a COBOL PROGRAM-ID area that was never
            // filled in. It is rejected rather than defaulted, because a name that identifies no
            // abend site defeats the only reason the name is carried.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AbendException.standard(blankProgram,
                            AbendException.RETURN_CODE_IO_ERROR))
                    .withMessageContaining("program must not be blank");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AbendException.withoutAbendParameters(blankProgram,
                            AbendException.RETURN_CODE_IO_ERROR))
                    .withMessageContaining("program must not be blank");
        }

        @Test
        @DisplayName("the rejection messages name the parameter and give a worked example")
        void rejectionMessagesAreExplanatory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AbendException.standard(null,
                            AbendException.RETURN_CODE_IO_ERROR))
                    .withMessage("program must not be null: name the abending COBOL program, "
                            + "for example \"CBACT01C\"");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AbendException.standard("",
                            AbendException.RETURN_CODE_IO_ERROR))
                    .withMessage("program must not be blank: name the abending COBOL program, "
                            + "for example \"CBACT01C\"");
        }
    }

    @Nested
    @DisplayName("Cause chaining")
    class CauseChaining {

        @Test
        @DisplayName("retains the underlying failure on both shapes")
        void retainsTheUnderlyingFailure() {
            // COBOL has no exception chain, so CALL 'CEE3ABD' carries no cause. The parameter exists
            // for the Java translation only: when a repository call fails, keeping the original
            // exception preserves the diagnostic trail without altering any observable behaviour.
            IOException standardCause = new IOException("ACCTFILE unavailable");
            IOException divergentCause = new IOException("TRNXFILE unavailable");

            AbendException standardShape = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON, standardCause);
            AbendException divergentShape = AbendException.withoutAbendParameters(
                    DIVERGENT_SITE_PROGRAM, AbendException.RETURN_CODE_IO_ERROR,
                    READ_FAILURE_REASON, divergentCause);

            assertThat(standardShape.getCause()).isSameAs(standardCause);
            assertThat(divergentShape.getCause()).isSameAs(divergentCause);
        }

        @Test
        @DisplayName("preserves a whole chain, not just the immediate cause")
        void preservesTheWholeChain() {
            IllegalStateException root = new IllegalStateException("record area not initialised");
            IOException intermediate = new IOException("ACCTFILE read failed", root);

            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON, intermediate);

            assertThat(abend.getCause()).isSameAs(intermediate);
            assertThat(abend.getCause().getCause()).isSameAs(root);
        }

        @Test
        @DisplayName("leaves the cause null on every factory that takes none")
        void leavesTheCauseNullWhenNoneIsSupplied() {
            assertThat(AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR).getCause()).isNull();
            assertThat(AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON).getCause()).isNull();
            assertThat(AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR).getCause()).isNull();
            assertThat(AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, READ_FAILURE_REASON).getCause()).isNull();
        }

        @Test
        @DisplayName("keeps the cause out of the detail message, which parity compares byte for byte")
        void keepsTheCauseOutOfTheMessage() {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON,
                    new IOException("SQLSTATE 08001 connection refused"));

            assertThat(abend.getMessage()).doesNotContain("SQLSTATE");
            assertThat(abend.getMessage()).doesNotContain("connection refused");
            assertThat(abend.getMessage())
                    .isEqualTo("ABENDING PROGRAM CBACT01C RETURN-CODE=12 ABCODE=999 TIMING=0"
                            + " - ERROR OPENING ACCTFILE");
        }
    }

    @Nested
    @DisplayName("Detail message composition")
    class MessageComposition {

        @Test
        @DisplayName("a standard site with detail text composes exactly as documented")
        void standardSiteWithDetailText() {
            // Pinned exactly, not loosely: the web error mapping preserves this text byte for byte
            // and the parity harness compares emitted messages, so any change to the layout is a
            // change to observable output.
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON);

            assertThat(abend.getMessage())
                    .isEqualTo("ABENDING PROGRAM CBACT01C RETURN-CODE=12 ABCODE=999 TIMING=0"
                            + " - ERROR OPENING ACCTFILE");
        }

        @Test
        @DisplayName("a standard site without detail text ends after TIMING")
        void standardSiteWithoutDetailText() {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_ASSUMED_FAILURE);

            assertThat(abend.getMessage())
                    .isEqualTo("ABENDING PROGRAM CBACT01C RETURN-CODE=8 ABCODE=999 TIMING=0");
        }

        @Test
        @DisplayName("the CBSTM03A site with detail text omits both arguments")
        void divergentSiteWithDetailText() {
            AbendException abend = AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, READ_FAILURE_REASON);

            assertThat(abend.getMessage())
                    .isEqualTo("ABENDING PROGRAM CBSTM03A RETURN-CODE=12"
                            + " - ERROR READING XREFFILE");
        }

        @Test
        @DisplayName("the CBSTM03A site without detail text ends after the return code")
        void divergentSiteWithoutDetailText() {
            AbendException abend = AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR);

            assertThat(abend.getMessage())
                    .isEqualTo("ABENDING PROGRAM CBSTM03A RETURN-CODE=12");
        }

        @Test
        @DisplayName("is composed with Locale.ROOT, so a non-ASCII digit locale cannot alter it")
        void isComposedWithLocaleRoot() {
            // Under ar-EG with the Arabic-Indic numbering system a default-locale %d renders 12 as
            // two Arabic-Indic digits, which would silently corrupt every abend message on a machine
            // configured that way. The default is restored in the finally block, so no other test
            // observes the change.
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(ARABIC_INDIC_DIGIT_LOCALE);

                AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                        AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON);

                assertThat(abend.getMessage())
                        .isEqualTo("ABENDING PROGRAM CBACT01C RETURN-CODE=12 ABCODE=999 TIMING=0"
                                + " - ERROR OPENING ACCTFILE");
                assertThat(abend.getMessage()).containsOnlyOnce("RETURN-CODE=12");
            } finally {
                Locale.setDefault(original);
            }
        }

        @Test
        @DisplayName("is fixed at construction, so repeated reads and the localized view all agree")
        void isFixedAtConstruction() {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON);

            assertThat(abend.getMessage()).isEqualTo(abend.getMessage());
            assertThat(abend.getLocalizedMessage()).isEqualTo(abend.getMessage());
            assertThat(abend.toString()).contains(abend.getMessage());
        }

        @Test
        @DisplayName("is identical for identical inputs and differs when any input differs")
        void isDeterministic() {
            AbendException first = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON);
            AbendException second = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON);
            AbendException differentCode = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_ASSUMED_FAILURE, OPEN_FAILURE_REASON);
            AbendException differentProgram = AbendException.standard("CBTRN02C",
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON);

            assertThat(first.getMessage()).isEqualTo(second.getMessage());
            assertThat(first.getMessage()).isNotEqualTo(differentCode.getMessage());
            assertThat(first.getMessage()).isNotEqualTo(differentProgram.getMessage());
        }

        @Test
        @DisplayName("orders its segments as display literal, program, return code, then arguments")
        void ordersItsSegments() {
            String message = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON).getMessage();

            assertThat(message.indexOf(AbendException.ABEND_DISPLAY_TEXT)).isZero();
            assertThat(message.indexOf(STANDARD_SITE_PROGRAM))
                    .isLessThan(message.indexOf("RETURN-CODE="));
            assertThat(message.indexOf("RETURN-CODE=")).isLessThan(message.indexOf("ABCODE="));
            assertThat(message.indexOf("ABCODE=")).isLessThan(message.indexOf("TIMING="));
            assertThat(message.indexOf("TIMING=")).isLessThan(message.indexOf(" - "));
        }
    }

    @Nested
    @DisplayName("Throw and catch")
    class ThrowAndCatch {

        @Test
        @DisplayName("every carried value survives being thrown and caught - standard shape")
        void standardShapeSurvivesThrowAndCatch() {
            AbendException caught = null;
            try {
                raiseStandardAbend();
            } catch (AbendException expected) {
                caught = expected;
            }

            assertThat(caught).isNotNull();
            assertThat(caught.getProgram()).isEqualTo(STANDARD_SITE_PROGRAM);
            assertThat(caught.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
            assertThat(caught.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(caught.getTiming()).hasValue(AbendException.STANDARD_TIMING);
            assertThat(caught.hasAbendCode()).isTrue();
            assertThat(caught.hasTiming()).isTrue();
            assertThat(caught.getReason()).contains(OPEN_FAILURE_REASON);
        }

        @Test
        @DisplayName("the absence of both arguments survives being thrown and caught - CBSTM03A")
        void divergentShapeSurvivesThrowAndCatch() {
            AbendException caught = null;
            try {
                raiseDivergentAbend();
            } catch (AbendException expected) {
                caught = expected;
            }

            assertThat(caught).isNotNull();
            assertThat(caught.getProgram()).isEqualTo(DIVERGENT_SITE_PROGRAM);
            assertThat(caught.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
            assertThat(caught.hasAbendCode()).isFalse();
            assertThat(caught.hasTiming()).isFalse();
            assertThat(caught.getAbendCode()).isEmpty();
            assertThat(caught.getTiming()).isEmpty();
            assertThat(caught.getReason()).contains(READ_FAILURE_REASON);
        }

        @Test
        @DisplayName("is catchable as RuntimeException, which keeps every job signature clean")
        void isCatchableAsRuntimeException() {
            // raiseStandardAbend declares no throws clause, so this test compiling at all is the
            // practical proof that the exception is unchecked.
            RuntimeException caught = null;
            try {
                raiseStandardAbend();
            } catch (RuntimeException expected) {
                caught = expected;
            }

            assertThat(caught).isInstanceOf(AbendException.class);
            assertThat(caught).hasMessageContaining(STANDARD_SITE_PROGRAM);
            assertThat(caught).hasMessageStartingWith(AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("propagates out of a Runnable with no checked-exception plumbing")
        void propagatesOutOfARunnable() {
            // A Runnable cannot declare a checked exception, so this assignment only compiles
            // because AbendException is unchecked - exactly the property a Spring Batch tasklet or a
            // repository callback relies on.
            Runnable abendingStep = AbendExceptionTest::raiseDivergentAbend;

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(abendingStep::run)
                    .withMessageContaining(DIVERGENT_SITE_PROGRAM)
                    .withNoCause();
        }

        @Test
        @DisplayName("carries a stack trace, so the abend is locatable in a batch log")
        void carriesAStackTrace() {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR);

            assertThat(abend.getStackTrace()).isNotEmpty();
        }
    }
}
