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
 * Unit tests for {@link AbendException}, the Java equivalent of the COBOL {@code CALL 'CEE3ABD'} abend
 * service.
 */
@DisplayName("AbendException - the Java equivalent of CALL 'CEE3ABD' at all nine COBOL abend sites")
class AbendExceptionTest {
    private static final String STANDARD_SITE_PROGRAM = "CBACT01C";

    private static final String DIVERGENT_SITE_PROGRAM = "CBSTM03A";

    private static final String OPEN_FAILURE_REASON = "ERROR OPENING ACCTFILE";

    private static final String READ_FAILURE_REASON = "ERROR READING XREFFILE";

    private static final Locale ARABIC_INDIC_DIGIT_LOCALE = Locale.forLanguageTag("ar-EG-u-nu-arab");

    private static final List<String> CARRIED_FIELD_NAMES =
            List.of("program", "returnCode", "abendCode", "timing", "reason", "sourceDiagnostic");

    private static final List<Class<?>> FORBIDDEN_NUMERIC_TYPES =
            List.<Class<?>>of(double.class, float.class, Double.class, Float.class);

    private static boolean isInstrumentationArtifact(String memberName, boolean synthetic) {
        return synthetic || memberName.startsWith("$");
    }

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

    private static void raiseStandardAbend() {
        throw AbendException.standard(STANDARD_SITE_PROGRAM, AbendException.RETURN_CODE_IO_ERROR,
                OPEN_FAILURE_REASON);
    }

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
            assertThat(constructors[0].getParameterCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("declares serialVersionUID explicitly rather than relying on a computed hash")
        void declaresSerialVersionUid() throws ReflectiveOperationException {
            Field field = AbendException.class.getDeclaredField("serialVersionUID");

            assertThat(field.getType()).isEqualTo(long.class);
            assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
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
        @DisplayName("carries exactly six private final instance fields, so instances are immutable")
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
            assertThat(AbendException.standard(STANDARD_SITE_PROGRAM, returnCode).getReturnCode())
                    .isEqualTo(returnCode);
            assertThat(AbendException.withoutAbendParameters(DIVERGENT_SITE_PROGRAM, returnCode)
                    .getReturnCode()).isEqualTo(returnCode);
        }

        @ParameterizedTest(name = "a negative return code {0} is carried verbatim")
        @ValueSource(ints = {-1, -8, -999_999_999, -2_147_483_648})
        @DisplayName("a negative code is carried verbatim, since APPL-RESULT is PIC S9(9) - signed")
        void carriesNegativeReturnCodesVerbatim(int returnCode) {
            AbendException abend = AbendException.standard(STANDARD_SITE_PROGRAM, returnCode);

            assertThat(abend.getReturnCode()).isEqualTo(returnCode);
            assertThat(abend.getMessage()).contains("RETURN-CODE=" + returnCode);
            assertThat(abend.getMessage()).contains("RETURN-CODE=-");
        }

        @Test
        @DisplayName("only the success code is zero, which is what COND=(0,NE) step gating keys on")
        void onlySuccessIsZero() {
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

            assertThat(abend.hasAbendCode()).isFalse();
            assertThat(abend.hasTiming()).isFalse();
            assertThat(abend.getAbendCode()).isEmpty();
            assertThat(abend.getTiming()).isEmpty();
            assertThat(abend.getAbendCode().isPresent()).isFalse();
            assertThat(abend.getTiming().isPresent()).isFalse();
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
            assertThat(standardShape.getTiming().orElse(-1)).isZero();
            assertThat(divergentShape.getTiming().orElse(-1)).isEqualTo(-1);
            assertThat(standardShape.getTiming()).isNotEqualTo(divergentShape.getTiming());
        }

        @Test
        @DisplayName("the detail messages differ even when program, code and reason are identical")
        void messagesSeparateTheTwoShapesWithEverythingElseHeldEqual() {
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
    @DisplayName("The transmitted diagnostic - COCRDSLC:865-869 only")
    class SourceDiagnostic {
        private static final String TRANSMITTED = "9999COCRDSLC"
                + " ".repeat(SystemMessages.ABEND_REASON_LENGTH)
                + "UNEXPECTED ABEND OCCURRED."
                + " ".repeat(SystemMessages.ABEND_MSG_LENGTH - "UNEXPECTED ABEND OCCURRED.".length());

        @Test
        @DisplayName("a CEE3ABD abend carries none, in either shape")
        void neitherShapeCarriesOneByDefault() {
            assertThat(AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR).hasSourceDiagnostic()).isFalse();
            assertThat(AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR).getSourceDiagnostic()).isEmpty();
            assertThat(AbendException.withoutAbendParameters("CBSTM03A",
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON)
                    .hasSourceDiagnostic()).isFalse();
        }

        @Test
        @DisplayName("a transmitted area is carried verbatim, at the full group length")
        void aTransmittedAreaIsCarriedVerbatim() {
            AbendException abend = AbendException.withoutAbendParameters("COCRDSLC",
                    AbendException.RETURN_CODE_IO_ERROR).withSourceDiagnostic(TRANSMITTED);

            assertThat(abend.hasSourceDiagnostic()).isTrue();
            assertThat(abend.getSourceDiagnostic()).contains(TRANSMITTED);
            assertThat(abend.getSourceDiagnostic().orElseThrow())
                    .hasSize(SystemMessages.ABEND_DATA_LENGTH);
        }

        @Test
        @DisplayName("null and blank both state nothing, so both leave the abend without one")
        void nullAndBlankAreBothAbsent() {
            AbendException base = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_ASSUMED_FAILURE);

            assertThat(base.withSourceDiagnostic(null).hasSourceDiagnostic()).isFalse();
            assertThat(base.withSourceDiagnostic("").hasSourceDiagnostic()).isFalse();
            assertThat(base.withSourceDiagnostic("   ").hasSourceDiagnostic()).isFalse();
        }

        @Test
        @DisplayName("the original is unchanged, because an exception must not mutate once thrown")
        void theOriginalIsUnchanged() {
            AbendException original = AbendException.standard(STANDARD_SITE_PROGRAM,
                    AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON);

            AbendException carrying = original.withSourceDiagnostic(TRANSMITTED);

            assertThat(original.hasSourceDiagnostic()).isFalse();
            assertThat(carrying).isNotSameAs(original);
            assertThat(carrying.getMessage()).isEqualTo(original.getMessage());
        }

        @Test
        @DisplayName("the diagnostic is not the reason, and neither becomes the other")
        void theDiagnosticIsNotTheReason() {
            AbendException abend = AbendException.withoutAbendParameters("COCRDSLC",
                            AbendException.RETURN_CODE_IO_ERROR, OPEN_FAILURE_REASON)
                    .withSourceDiagnostic(TRANSMITTED);

            assertThat(abend.getReason()).contains(OPEN_FAILURE_REASON);
            assertThat(abend.getSourceDiagnostic().orElseThrow())
                    .doesNotContain(OPEN_FAILURE_REASON);
            assertThat(abend.getMessage()).doesNotContain(TRANSMITTED);
        }

        @Test
        @DisplayName("it survives a serialization round trip, like every other carried value")
        void itSurvivesSerialization() throws IOException, ClassNotFoundException {
            AbendException restored = serializeAndBack(AbendException
                    .withoutAbendParameters("COCRDSLC", AbendException.RETURN_CODE_IO_ERROR)
                    .withSourceDiagnostic(TRANSMITTED));

            assertThat(restored.getSourceDiagnostic()).contains(TRANSMITTED);
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
